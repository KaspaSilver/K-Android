package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment notices waiting for the chatting address (iOS 710fdc0, IOS-086).
 *
 * A chat payment From a spending address is told to the payee by a `payment_notice` the chatting
 * address sends ([com.kachat.app.viewmodels.ChatViewModel]'s `sendSpendingPaymentNotice`). When
 * the chatting address is empty that send fails; the notice is kept here, per wallet, and sent as
 * soon as the chatting address has KAS (iOS `observeChattingBalanceForNotices`), and also every
 * 20 seconds while the app is open, for up to 15 minutes ([scheduleUnsentNoticeRetry]). Built at
 * startup (KaChatApplication) so the balance is watched from launch.
 */
@Singleton
class PaymentNoticeOutbox @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val chatRepository: ChatRepository,
    /** Lazy: WalletService is what sends, and it is heavy to build at startup. */
    private val walletServiceLazy: dagger.Lazy<WalletService>,
    /** Whether the app is on screen (KaChatApplication's ProcessLifecycleOwner observer). */
    private val notificationHelper: NotificationHelper,
) {
    data class UnsentPaymentNotice(
        val contactAddress: String,
        val payload: String,
        val txId: String,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listLock = Any()
    private val sending = Mutex()
    private val retryLock = Any()
    @Volatile private var retryJob: Job? = null

    init {
        // notices still held from an earlier run get the timer too (iOS observeChattingBalanceForNotices)
        scope.launch { if (hasUnsentNotices()) scheduleUnsentNoticeRetry() }
        // The chatting address got KAS: the notices waiting for it go out.
        scope.launch {
            // A StateFlow only emits changes already (iOS removeDuplicates).
            walletServiceLazy.get().balance
                .filter { it > 0 }
                .collect { sendUnsentPaymentNotices() }
        }
    }

    /** Whether the active account has notices waiting (iOS `unsentNotices.isEmpty`). */
    private fun hasUnsentNotices(): Boolean {
        val key = currentKey() ?: return false
        return synchronized(listLock) { read(key) }.isNotEmpty()
    }

    /**
     * A held notice is usually waiting for the payment's own change to become spendable, which
     * doesn't change the balance, so the balance watch alone could hold it until some unrelated
     * payment came in (a kUSDT card reached the contact minutes late on 2026-11-22 that way). So
     * it is also tried every 20 seconds while the app is open, for up to 15 minutes (iOS
     * `scheduleUnsentNoticeRetry`). One timer at a time; it stops early once nothing is held.
     */
    fun scheduleUnsentNoticeRetry() {
        synchronized(retryLock) {
            if (retryJob?.isActive == true) return
            retryJob = scope.launch {
                val started = System.currentTimeMillis()
                while (isActive && UnsentNoticeRetry.keepsTrying(System.currentTimeMillis() - started)) {
                    delay(UnsentNoticeRetry.INTERVAL_MS)
                    when (UnsentNoticeRetry.step(hasUnsentNotices(), notificationHelper.isAppInForeground)) {
                        UnsentNoticeRetry.Step.STOP -> break
                        UnsentNoticeRetry.Step.SKIP -> continue
                        UnsentNoticeRetry.Step.SEND -> sendUnsentPaymentNotices()
                    }
                }
            }
        }
    }

    private fun keyFor(walletAddress: String) = "$KEY_PREFIX${walletAddress.trim().lowercase()}"

    private fun currentKey(): String? =
        runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::keyFor)

    private fun read(key: String): List<UnsentPaymentNotice> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<UnsentPaymentNotice>>(json, object : TypeToken<List<UnsentPaymentNotice>>() {}.type)
        }.getOrNull().orEmpty()
    }

    private fun write(key: String, list: List<UnsentPaymentNotice>) {
        if (list.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    /** Everything kept for [walletAddress]: it's going off this device (iOS bfe9f022). */
    fun removeAllData(walletAddress: String) {
        synchronized(listLock) { prefs.edit().remove(keyFor(walletAddress)).apply() }
    }

    /**
     * Keeps a notice that failed to send (usually an empty chatting address) under [owner], the
     * account that made the payment - never the one active when the send failed: after a switch,
     * sending it from the other account's chatting address would link the two accounts on chain
     * (iOS 5b7c772c `keepUnsentNotice(_:owner:)`, audit #7). It goes out once [owner] is active
     * again and its chatting address has KAS. Null: a caller that didn't say whose it is - the
     * active account, as before.
     */
    fun keep(notice: UnsentPaymentNotice, owner: String? = null) {
        val key = (owner?.takeIf { it.isNotBlank() }?.let(::keyFor) ?: currentKey()) ?: return
        synchronized(listLock) {
            val list = read(key)
            if (list.any { it.txId == notice.txId }) return
            write(key, (list + notice).takeLast(MAX_KEPT))
        }
        scheduleUnsentNoticeRetry()
    }

    /**
     * Sends the notices that failed for want of KAS on the chatting address - called when its
     * balance turns positive. One at a time; any that still fails stays for next time, and one
     * whose contact is gone is dropped (iOS `sendUnsentPaymentNotices`).
     */
    suspend fun sendUnsentPaymentNotices() {
        if (!sending.tryLock()) return
        try {
            val key = currentKey() ?: return
            val pending = synchronized(listLock) { read(key) }
            if (pending.isEmpty()) return
            val done = mutableSetOf<String>()
            for (notice in pending) {
                if (currentKey() != key) return
                if (chatRepository.getContact(notice.contactAddress) == null) {
                    done += notice.txId
                    continue
                }
                try {
                    // Untagged, like iOS sendEnvelopeInternal: kchat:1:comm:, never a first-contact dm.
                    walletServiceLazy.get().sendKasiaMessage(notice.contactAddress, notice.payload, firstContactTag = false)
                    done += notice.txId
                    Log.i(TAG, "Sent a kept payment_notice for ${notice.txId.take(12)}")
                } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
                    // It may have landed: dropped, so it is never sent twice (audit 2026-11-25 P6).
                    done += notice.txId
                    Log.w(TAG, "Kept payment_notice ${notice.txId.take(12)} not confirmed; not resent")
                } catch (e: Exception) {
                    Log.w(TAG, "Kept payment_notice ${notice.txId.take(12)} still not sent: ${e.message}")
                }
            }
            synchronized(listLock) { write(key, read(key).filterNot { it.txId in done }) }
        } finally {
            sending.unlock()
        }
    }

    /** The timing of [scheduleUnsentNoticeRetry] (iOS `scheduleUnsentNoticeRetry`). */
    internal object UnsentNoticeRetry {
        /** Tried every 20 seconds... */
        const val INTERVAL_MS = 20_000L
        /** ...for up to 15 minutes from the notice being kept (or from launch). */
        const val WINDOW_MS = 15 * 60_000L

        enum class Step { STOP, SKIP, SEND }

        /** Whether the timer goes round again [elapsedMs] after it started. */
        fun keepsTrying(elapsedMs: Long): Boolean = elapsedMs < WINDOW_MS

        /** After a wait: stop once nothing is held, skip while the app isn't on screen, else send. */
        fun step(hasNotices: Boolean, foreground: Boolean): Step = when {
            !hasNotices -> Step.STOP
            !foreground -> Step.SKIP
            else -> Step.SEND
        }
    }

    private companion object {
        const val TAG = "PaymentNoticeOutbox"
        const val PREFS_NAME = "kachat_unsent_payment_notices"
        const val KEY_PREFIX = "kachat_unsent_payment_notices."
        const val MAX_KEPT = 50
    }
}
