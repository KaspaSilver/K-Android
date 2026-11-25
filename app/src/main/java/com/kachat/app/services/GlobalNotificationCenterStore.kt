package com.kachat.app.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bell on the Profile screen holds Kaspa arriving in one of your own addresses - the chatting
 * address or a spending address (fed by [AddressActivityNotifier]; iOS 86471dd). .kachat name news
 * left with the .kachat market on 2026-10-24 (iOS d54ea87), and group chats keep their own unread
 * counts in the Chats tab (iOS cda2715). Entries are account-scoped, persisted, deduped by id and
 * capped; opening the list marks everything seen.
 */
@Singleton
class GlobalNotificationCenterStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
) {
    data class Entry(
        val id: String,
        /** "wallet" or "token" ([IN_BELL]) - older builds also stored "kaposts", "group",
         *  "broadcast" and "kachat" rows. */
        val source: String,
        val title: String,
        val body: String,
        val timestampMs: Long,
        /** group id / channel name / post txid / .kachat name / the own address a wallet receipt
         *  hit (iOS 95e2cba) - what tapping the row relates to. Null on wallet rows saved before
         *  receipts carried their address; those open Portfolio. */
        val targetId: String?,
    )

    private val prefs = context.getSharedPreferences("global_notification_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    private val maxEntries = 100

    /** The account [entries] were loaded for. The feed is read and saved under this address, not
     *  whatever wallet is current at write time, so one account's rows never land under another's
     *  key. Null while no one is signed in: nothing is shown and nothing is saved (iOS 428a797b
     *  `loadedAddress`, audit 2026-11-21 #8). */
    private var loadedWallet: String? = null

    init {
        // Log Out, sign-in, import and account switches all change the signed-in account.
        // Reloading only when a screen asked left the last account's rows in the bell, and the
        // next account's first notification saved them under its own key for good (iOS 428a797b,
        // as KaPostsUnseenStore).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            walletManager.signedInAddressFlow.collect { address -> load(address) }
        }
    }

    private fun walletAddressOrNull(): String? =
        try { walletManager.getAddress() } catch (_: Exception) { null }

    /** The signed-in account's address; null while signed out. */
    private fun signedInAddressOrNull(): String? = if (walletManager.isLoggedOut) null else walletAddressOrNull()

    private fun entriesKey(wallet: String) = "entries_$wallet"
    private fun seenKey(wallet: String) = "seen_$wallet"

    /** Loads the signed-in wallet's feed (account switches reload by themselves, see init). */
    @Synchronized
    fun reloadIfNeeded() {
        load(signedInAddressOrNull())
    }

    @Synchronized
    private fun load(wallet: String?) {
        if (loadedWallet == wallet) return
        loadedWallet = wallet
        if (wallet == null) {
            // signed out: the bell is empty, never the last account's
            _entries.value = emptyList()
            _lastSeenAt.value = 0L
            return
        }
        val loaded = try {
            gson.fromJson(prefs.getString(entriesKey(wallet), null) ?: "[]", listType) ?: emptyList()
        } catch (_: Exception) { emptyList<Entry>() }
        // Wallet rows only. Group mentions carry their own unread counts in the Chats tab, and
        // KaPosts, Public Chats and the .kachat market are gone; anything an older build saved for
        // those is dropped here.
        val kept = loaded.filter { it.source in IN_BELL }
        _entries.value = kept
        _lastSeenAt.value = prefs.getLong(seenKey(wallet), 0L)
        if (kept.size != loaded.size) persist()
    }

    private fun persist() {
        val wallet = loadedWallet ?: return
        prefs.edit().putString(entriesKey(wallet), gson.toJson(_entries.value.take(maxEntries))).apply()
    }

    @Synchronized
    fun record(id: String, source: String, title: String, body: String, timestampMs: Long, targetId: String?) {
        // Wallet news only. Refused here rather than merely left uncalled, so a future caller
        // cannot quietly put anything else back in the bell.
        if (source !in IN_BELL) return
        reloadIfNeeded()
        if (loadedWallet == null) return
        if (id.isEmpty() || _entries.value.any { it.id == id }) return
        _entries.value = (listOf(Entry(id, source, title, body, timestampMs, targetId)) + _entries.value)
            .take(maxEntries)
        persist()
    }

    fun markAllSeen() {
        val wallet = loadedWallet ?: return
        _lastSeenAt.value = System.currentTimeMillis()
        prefs.edit().putLong(seenKey(wallet), _lastSeenAt.value).apply()
    }

    @Synchronized
    fun clearAll() {
        if (loadedWallet == null) return
        _entries.value = emptyList()
        persist()
    }

    /** Delete Account: one wallet's feed, from this device (iOS d063b897). */
    @Synchronized
    fun removeAllData(walletAddress: String) {
        prefs.edit().remove(entriesKey(walletAddress)).remove(seenKey(walletAddress)).apply()
        if (loadedWallet == walletAddress) {
            loadedWallet = null
            _entries.value = emptyList()
            _lastSeenAt.value = 0L
        }
    }

    // MARK: - Group @mentions

    /** Group chats carry their own unread counts and mention handling in the Chats tab, so
     *  mentions no longer go through the bell (iOS cda2715). Kept as a no-op for the call site;
     *  rows an older build recorded are dropped on load. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recordGroupMentionIfNeeded(
        groupId: String,
        groupName: String,
        senderAddress: String,
        senderName: String,
        text: String,
        txId: String?,
        timestampMs: Long,
    ) {}

    companion object {
        private const val TAG = "GlobalNotifCenter"

        /** What the notifications list holds (iOS `Entry.Source.inBell`, 4500ebc): Kaspa received,
         *  and a token received ("token", iOS 5b7c772c; its targetId is the token's id, "kusdt",
         *  and a tap opens that token's Manage screen on the Wallet tab). */
        val IN_BELL = setOf("wallet", "token")

        /** The source tag a row shows (iOS `Entry.Source.label`). */
        fun sourceLabel(source: String): String = when (source) {
            "wallet" -> "Wallet"
            "token" -> "Tokens"
            "kachat" -> ".kachat"
            "group" -> "Group"
            else -> source
        }

        /** An @mention token in a message: "@bob" or "@bob.kachat". */
        val MENTION_TOKEN_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]+(?:\\.[a-z0-9-]+)*)", RegexOption.IGNORE_CASE)
    }
}
