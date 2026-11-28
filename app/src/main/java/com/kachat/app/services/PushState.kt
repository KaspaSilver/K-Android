package com.kachat.app.services

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether native FCM push is currently ACTIVE for this device — meaning the server, not the
 * in-app pollers, is the notification source for the push-covered surfaces (1:1 DMs, broadcast
 * channels, KaPosts pings), per the iOS repo's PUSH_EXTENSIONS.md §4: the app posts no banners of its own for
 * what it discovers itself while push is active.
 *
 * Since iOS 428a797b (owner, 2026-11-22) the app banners again while it is OPEN: new 1:1, group
 * and room messages and KaPost activity it finds itself, one banner per message
 * ([NotificationHelper.mayBannerLocally], the txId ledger, a push already in the shade). What
 * follows still holds for the background and the closed app.
 *
 * (Until 2026-11-22) Foreground included. The app used to banner from both sources while on screen - the local
 * poll or scan posted, and a racing push for the same tx was collapsed by NotificationHelper's
 * txId ledger - and the seams showed: the same message arriving as two differently-worded
 * banners, or a banner whose wording depended on which path won. Every discovery path (the
 * 1:1 sync, the broadcast scan, the KaPosts poller) now returns before posting whenever this
 * flag is true, exactly as when the app is closed; discovery itself is untouched, so the data
 * is fresh when the app opens. The trade, made on purpose and the same one iOS makes: a
 * message the push service misses, or a broadcast room the server does not index, notifies
 * nothing. Unlike iOS, a device with no push at all (no Play services, registration failed)
 * keeps the local banners, because there the pollers are the only source there is.
 *
 * Set true by [PushRegistrationManager] only after a registration round-trip SUCCEEDS while
 * system notifications are actually deliverable (POST_NOTIFICATIONS granted); flipped false on
 * any registration failure, on unregister, and whenever FCM itself is unavailable (no
 * google-services.json / no Play services — registration can't succeed then, so it never turns
 * on). Consumers ([ChatRepository], [BroadcastScanningService], [KaPostsNotificationPoller])
 * consult it at their notification-posting sites, and [ChatRepository]'s 2s sync loop plus
 * [NodePoolManager]'s probe loop additionally PAUSE while the app is backgrounded and this flag
 * is true (battery: an exempted background process must not poll forever when the server is the
 * delivery path) — both resume instantly on foreground or the flag turning false.
 * Groups too ([GroupRepository]): their pushes post the banner from the FCM handler.
 *
 * A separate dependency-free holder (rather than a flag on PushRegistrationManager) because the
 * manager depends on ChatRepository/BroadcastRepository — the very classes that need to read the
 * flag — and Dagger can't resolve that cycle.
 *
 * It restores the last known answer itself, as it is built (audit 2026-11-25 S7): the loops that
 * read it start before [PushRegistrationManager] is built, and with the flag still false a
 * process woken for a push or a periodic job ran one full node probe and 1:1 sweep first.
 *
 * [backgroundQuiet] is the gate the background loops use (audit 2026-11-25 N7, B4): the no-push
 * fallback is for a device that must banner by itself, so it runs only when there is something
 * it could show - signed in, Notifications on in K and in Android, and no push.
 */
@Singleton
class PushState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pushActive = MutableStateFlow(prefs.getBoolean(KEY_PUSH_ACTIVE, false))

    /** Observable form, for anything that wants to react to the mode changing. */
    val pushActive: StateFlow<Boolean> = _pushActive

    /** Cheap synchronous read for the notification-posting guard sites. */
    val isActive: Boolean get() = _pushActive.value

    internal fun setActive(active: Boolean) {
        _pushActive.value = active
        recomputeQuiet()
    }

    /** Signed in with K's Notifications switch on: fed by [PushRegistrationManager], and
     *  remembered for the next process like [pushActive]. True until first known, so a device
     *  that must banner by itself never misses a poll on the first launch. */
    private val localBannersWanted = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_BANNERS_WANTED, true))

    /** Android's own switch for K's notifications (and, on 13+, the permission). Read again on
     *  every [isBackgroundQuiet], each foreground and background move and Android's block-state
     *  broadcast; assumed on until first read, off the main thread. */
    private val systemNotificationsOn = MutableStateFlow(true)

    private val _backgroundQuiet = MutableStateFlow(
        backgroundQuiet(_pushActive.value, localBannersWanted.value, systemNotificationsOn.value)
    )

    /**
     * True while the background has nothing to deliver by itself: push is the source, or nothing
     * may be shown at all (signed out, Notifications off in K or in Android). The background loops
     * (the 1:1 poll, the node pool, the room stream, SyncWorker) pause on this instead of on
     * [pushActive] alone, and resume when it turns false or the app comes to the front.
     */
    val backgroundQuiet: StateFlow<Boolean> = _backgroundQuiet.asStateFlow()

    /** [backgroundQuiet] now, Android's notification switch read again first. Off the main
     *  thread (one binder call). */
    val isBackgroundQuiet: Boolean
        get() {
            refreshSystemNotifications()
            return _backgroundQuiet.value
        }

    /** Signed in and K's Notifications switch on ([PushRegistrationManager] follows both). */
    internal fun setLocalBannersWanted(wanted: Boolean) {
        if (localBannersWanted.value != wanted) {
            prefs.edit().putBoolean(KEY_LOCAL_BANNERS_WANTED, wanted).apply()
        }
        localBannersWanted.value = wanted
        recomputeQuiet()
    }

    /** Reads Android's notification switch for K again (a binder call). */
    fun refreshSystemNotifications() {
        val on = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)
        systemNotificationsOn.value = on
        recomputeQuiet()
    }

    @Synchronized
    private fun recomputeQuiet() {
        _backgroundQuiet.value = backgroundQuiet(_pushActive.value, localBannersWanted.value, systemNotificationsOn.value)
    }

    init {
        // Android tells an app when the user blocks or unblocks its notifications (API 28+), so
        // the gate follows a change made while K is away without polling for it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: android.content.Intent?) = refreshSystemNotifications()
                    },
                    android.content.IntentFilter(android.app.NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
    }

    /**
     * Read-only debugging surface for Settings > Notifications ("why aren't pushes arriving?").
     * Updated by [PushRegistrationManager] around every registration/unregistration attempt;
     * everything here is also logged under the `KaChatPush` logcat tag, so
     * `adb logcat -s KaChatPush` tells the same story with history.
     */
    data class PushDiagnostics(
        /** Epoch ms of the most recent register/unregister attempt; null = none this process. */
        val lastAttemptAtMs: Long? = null,
        /** Outcome of that attempt; null = still none / in flight. */
        val lastAttemptSucceeded: Boolean? = null,
        /** Human-readable failure reason from the last FAILED attempt; null after a success. */
        val lastError: String? = null,
        /** Whether an FCM token could be obtained (false = no google-services/Play services). */
        val fcmTokenPresent: Boolean = false,
        /** What the last attempt was, e.g. "register", "unregister". */
        val lastAction: String? = null,
    )

    private val _diagnostics = MutableStateFlow(PushDiagnostics())
    val diagnostics: StateFlow<PushDiagnostics> = _diagnostics

    /** Registration attempts that failed in a row, and since when: across processes, cleared by
     *  the next one that succeeds (audit 2026-11-27 R7). One failure (offline that moment, a
     *  timeout, a 5xx) no longer counts as push being unavailable. */
    data class RegisterFailures(val count: Int = 0, val sinceMs: Long = 0L)

    private val _registerFailures = MutableStateFlow(
        RegisterFailures(prefs.getInt(KEY_REGISTER_FAILURES, 0), prefs.getLong(KEY_REGISTER_FAILING_SINCE, 0L))
    )
    val registerFailures: StateFlow<RegisterFailures> = _registerFailures.asStateFlow()

    internal fun recordAttempt(
        action: String,
        succeeded: Boolean,
        error: String?,
        fcmTokenPresent: Boolean,
    ) {
        val now = System.currentTimeMillis()
        _diagnostics.value = PushDiagnostics(
            lastAttemptAtMs = now,
            lastAttemptSucceeded = succeeded,
            lastError = error,
            fcmTokenPresent = fcmTokenPresent,
            lastAction = action,
        )
        if (action.startsWith("register")) {
            val next = nextRegisterFailures(_registerFailures.value, succeeded, now)
            if (next != _registerFailures.value) {
                _registerFailures.value = next
                prefs.edit().putInt(KEY_REGISTER_FAILURES, next.count).putLong(KEY_REGISTER_FAILING_SINCE, next.sinceMs).apply()
            }
        }
    }

    /** [pushUnavailable] with what this holder knows now; [noPushService] from
     *  [PushRegistrationManager.canRegister]. Read again just before the battery sheet shows. */
    fun pushUnavailableNow(noPushService: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val failures = _registerFailures.value
        return pushUnavailable(_pushActive.value, noPushService, failures.count, failures.sinceMs, nowMs)
    }

    companion object {
        /** The prefs [PushRegistrationManager] keeps its registration state in. */
        internal const val PREFS_NAME = "kachat_push_registration"
        internal const val KEY_PUSH_ACTIVE = "push_active"
        private const val KEY_LOCAL_BANNERS_WANTED = "local_banners_wanted"
        private const val KEY_REGISTER_FAILURES = "register_failures"
        private const val KEY_REGISTER_FAILING_SINCE = "register_failing_since"

        /** Failed registrations in a row before push counts as unavailable (audit 2026-11-27 R7). */
        internal const val UNAVAILABLE_AFTER_FAILURES = 3
        /** ...and how long they must have been failing. */
        internal const val UNAVAILABLE_AFTER_MS = 60 * 60_000L

        /**
         * Whether push can't deliver on this device (MainActivity's battery exemption): not
         * active, and either there is no push service to register with (testnet, no Play
         * services) or registration has failed [UNAVAILABLE_AFTER_FAILURES] times in a row over
         * at least [UNAVAILABLE_AFTER_MS] (audit 2026-11-27 R7: a single failure, offline at that
         * moment or a 5xx, used to be enough, and the exemption then stayed after push worked).
         * Unknown (nothing tried yet) is not unavailable, and neither is notifications switched
         * off (an unregister).
         */
        internal fun pushUnavailable(
            pushActive: Boolean,
            noPushService: Boolean,
            failedRegistrations: Int,
            failingSinceMs: Long,
            nowMs: Long,
        ): Boolean = !pushActive && (
            noPushService ||
                (failedRegistrations >= UNAVAILABLE_AFTER_FAILURES && failingSinceMs > 0L && nowMs - failingSinceMs >= UNAVAILABLE_AFTER_MS)
            )

        /** The failure streak after one registration attempt: a success clears it. */
        internal fun nextRegisterFailures(previous: RegisterFailures, succeeded: Boolean, nowMs: Long): RegisterFailures =
            if (succeeded) RegisterFailures()
            else RegisterFailures(previous.count + 1, if (previous.count == 0 || previous.sinceMs == 0L) nowMs else previous.sinceMs)

        /** The [backgroundQuiet] rule: quiet with push, or when nothing could be shown. */
        internal fun backgroundQuiet(pushActive: Boolean, localBannersWanted: Boolean, systemNotificationsOn: Boolean): Boolean =
            pushActive || !localBannersWanted || !systemNotificationsOn
    }
}
