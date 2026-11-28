package com.kachat.app.services

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.kachat.app.R
import com.kachat.app.util.KaPostsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is in the FOREGROUND (KaChatApplication's process lifecycle observer calls
 * [start]/[stop] on foreground/background transitions), polls the indexer's notification stream
 * on the shared foreground beat ([ForegroundBeat]: every 30 s on Wi-Fi, 60 s on a metered path;
 * iOS 961fc05a, audit 2026-11-25 B4), or every 5 minutes as a safety net while push is active (the
 * push asks for a poll itself; owner, 2026-11-25: "do it like X", B15), keeps the unseen count and shows a banner for new activity on your posts (iOS
 * 428a797b `KaPostsNotificationService`, owner 2026-11-22), so a banner shows even when the push
 * service misses one; one banner per action when the push comes too. A KaPost push while the app
 * is open asks for a poll at once ([pollSoon]). Once the app is backgrounded or closed, the push
 * service is the only KaPosts notification source. Last-seen is stored per wallet so nothing
 * replays, and opening the Notifications screen marks everything seen.
 */
@Singleton
class KaPostsNotificationPoller @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val kaPostsService: KaPostsService,
    private val walletManager: WalletManager,
    private val notificationHelper: KaPostsNotifier,
    private val dataStore: DataStore<Preferences>,
    // Whether the app is open, and whether an action's push is in the shade already.
    private val appNotifications: NotificationHelper,
    // Settings > Notifications > KaPosts: per-kind toggles filtered here at the poll source
    // (never at display), mirroring iOS — a toggled-off kind is dropped permanently: the
    // last-seen watermark advances regardless, so it never re-fires if re-enabled later.
    private val settingsRepository: com.kachat.app.repository.AppSettingsRepository,
    // Global notification center (bell on the Profile screen): every fresh KaPosts action is
    // listed there, independent of the per-kind OS-banner gates below.
    private val notificationCenter: GlobalNotificationCenterStore,
    // KaPosts activity is counted here, not listed in the global center - see recordArrivals.
    private val unseenStore: KaPostsUnseenStore,
    // Actor naming (iOS parity): your saved contact name wins, then their .kachat name,
    // then the shortened address — never a bare address when a better name exists.
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    // Push active: KaPost activity arrives as pushes, and the poll is only a safety net (B15).
    private val pushState: PushState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("KaPostsNotificationPoller"))
    private var pollJob: Job? = null

    /** Session cache: one alias/.kachat resolution per actor per process, not one per poll. */
    private val actorNameCache = mutableMapOf<String, String>()

    private suspend fun actorDisplayName(address: String): String {
        actorNameCache[address]?.let { return it }
        val contact = try { chatRepository.getContacts().first().find { it.id == address } } catch (_: Exception) { null }
        val alias = contact?.alias?.trim().orEmpty()
        // The address's last 10 characters, as iOS KaPostsNotificationService.actorName.
        val fallback = address.takeLast(10)
        // Their .kachat name, the only name service K reads (iOS 518fdc8c `actorName`).
        val name = alias.ifEmpty {
            com.kachat.app.services.kachatnames.KachatNamesRegistry.kachatName(address)?.trim().orEmpty().ifEmpty { fallback }
        }
        // Only cache real resolutions — a network miss must not pin the short-address
        // fallback for the rest of the session. Bounded: clear wholesale past 500 entries.
        if (actorNameCache.size > 500) actorNameCache.clear()
        if (name != fallback) actorNameCache[address] = name
        return name
    }

    private fun lastSeenKey(address: String) = longPreferencesKey("kaposts_notifs_last_seen_$address")

    /**
     * The open-conversation rule for KaPosts: while the Notifications list is on screen the
     * reader is already looking at the stream, so a banner for it is noise. The count and the
     * watermark still advance underneath (iOS isNotificationsScreenVisible).
     */
    @Volatile
    var isNotificationsScreenVisible: Boolean = false

    /** When the last poll started, so a burst of pushes asks the indexer once. */
    @Volatile private var lastPollAt = 0L

    /**
     * Follows the signed-in account (iOS 961fc05a, audit 2026-11-25 B19): the poll stops on Log
     * Out, Delete Account and a switch, and starts again for the next account while the app is open.
     */
    private val walletObserver = scope.launch {
        walletManager.signedInAddressFlow
            .distinctUntilChanged()
            .drop(1)
            .collect { address ->
                stop()
                if (address != null && appNotifications.isAppInForeground) start()
            }
    }

    @Synchronized
    fun start() {
        if (pollJob?.isActive == true) return
        // signed out (the sign-in screen): nothing to poll for (iOS 961fc05a, B19)
        if (walletManager.isLoggedOut) return
        pollJob = scope.launch {
            // At once: K just opened or came back (or a push asked, [pollSoon]).
            runPoll()
            while (true) {
                // On the shared beat, so a poll never wakes the radio by itself. With push
                // working, the push is the notice and asks for a poll itself ([pollSoon]); the
                // poll is only a safety net then, every 5 minutes. Without push, every beat, since
                // nothing else brings the banner. Checked each beat, so registering or losing push
                // takes effect at once.
                ForegroundBeat.sleepUntilNextBeat(offsetMs = 800L)
                if (!shouldPollOnBeat(pushState.isActive, lastPollAt, System.currentTimeMillis())) continue
                runPoll()
            }
        }
    }

    private suspend fun runPoll() {
        lastPollAt = System.currentTimeMillis()
        try { pollOnce() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w("KaPostsPoller", "poll failed", e) }
    }

    /** Profile's bell shows: its count and banners catch up now, unless a poll ran in the last
     *  15 s (iOS 961fc05a `pollForBell`: with push the poll is otherwise only every 5 minutes). */
    fun pollForBell() = pollSoon()

    /** A KaPost push arrived while the app is open: poll now (the bell's count comes from the
     *  poll), unless one started in the last 15 s. Does nothing while the poller is stopped (iOS
     *  428a797b `pollSoon`). */
    @Synchronized
    fun pollSoon() {
        if (pollJob?.isActive != true) return
        if (System.currentTimeMillis() - lastPollAt < POLL_SOON_MIN_GAP_MS) return
        stop()
        start()
    }

    /** Delete Account: the last notification already surfaced for [address] (iOS 428a797b
     *  `kaposts_notifications_last_seen_<wallet>` in `removeLocalData`). */
    suspend fun removeAllData(address: String) {
        dataStore.edit { it.remove(lastSeenKey(address)) }
    }

    /** Called when the app leaves the foreground — background KaPosts pings are push's job. */
    @Synchronized
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The Notifications screen calls this with the newest timestamp it displayed: what the user
     * has seen on screen shouldn't ping later, and whatever KaPosts banners are still sitting in
     * the shade come down with it (iOS markSeen + clearDeliveredNotifications).
     */
    suspend fun markSeen(address: String, upTo: Long) {
        notificationHelper.cancelKaPostsNotifications()
        if (upTo <= 0L) return
        dataStore.edit { prefs ->
            val key = lastSeenKey(address)
            if ((prefs[key] ?: 0L) < upTo) prefs[key] = upTo
        }
    }

    private suspend fun pollOnce() {
        // Signed out: no banners for the account behind the sign-in screen (iOS).
        if (walletManager.isLoggedOut) return
        val address = try { walletManager.getAddress() } catch (_: Exception) { return }
        // No KaPost indexer on this network (testnet) - none of the feed work runs (iOS 0bda85e).
        if (settingsRepository.kapostIndexerUrl.first().isBlank()) return
        // Asked with notifications off too: the poll is what keeps Profile's bell counting while
        // the app is open, and only its banners belong to the switch ([KaPostsNotifier.showKaPosts]
        // checks it; iOS 8cb4c88e, audit 2026-11-24 notifications).
        var page = kaPostsService.fetchNotificationsPage(limit = 50)
        var notifications = page.items
        val key = lastSeenKey(address)
        val lastSeen = dataStore.data.first()[key]
        // A long absence runs past the first page: the bell counts all of it, within reason (iOS ee5ec98).
        if (lastSeen != null) {
            var pages = 1
            while (pages < MAX_UNSEEN_COUNT_PAGES && page.hasMore && !page.cursor.isNullOrEmpty()) {
                val oldest = notifications.minOfOrNull { it.timestamp } ?: break
                if (oldest <= lastSeen) break
                page = try {
                    kaPostsService.fetchNotificationsPage(limit = 50, before = page.cursor)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                notifications = notifications + page.items
                pages++
            }
        }
        // The account may have changed while the pages loaded: these rows (and `key`) belong to
        // the old one, and counting them now would add them to the new account's bell (iOS 369794e).
        if ((try { walletManager.getAddress() } catch (_: Exception) { null }) != address) return
        val newest = notifications.maxOfOrNull { it.timestamp } ?: return
        if (lastSeen == null) {
            // First run for this wallet: baseline silently instead of replaying history.
            dataStore.edit { it[key] = newest }
            return
        }
        val freshAll = notifications.filter { it.timestamp > lastSeen }
        dataStore.edit { it[key] = maxOf(newest, lastSeen) }
        // Muted and blocked accounts are gone from every KaPosts surface, the bell count and the
        // shade included (iOS drops them from both the unseen ingest and the banner path).
        val hidden = settingsRepository.kapostsMuted(address).first() + settingsRepository.kapostsBlocked(address).first()
        // Counted, not listed. The KaPosts notifications screen already serves these rows from
        // the indexer with richer formatting, so keeping a second copy in the global center
        // reported the same like or reply twice and let one busy feed dominate the profile
        // bell's count. What the indexer cannot tell us is how many the user has not looked at,
        // which is what this feeds.
        //
        // Gated by the same per-kind switches as the banner. An earlier revision counted every
        // fresh action regardless, treating the bell as a record of activity and the switches as
        // being only about interruption - but the setting reads "do not tell me about this", and
        // switching Likes off only to find a hundred of them waiting on the badge is the switch
        // not working.
        val arrivals = freshAll.filter { n ->
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey)
            actor != null && actor != address && actor !in hidden &&
                settingsRepository.shouldNotifyKaPostsAction(n.contentType, n.voteType)
        }
        // Nothing is unseen while the notifications screen is open (iOS 33095ac).
        if (isNotificationsScreenVisible) {
            unseenStore.markAllSeen()
            return
        }
        unseenStore.recordArrivals(arrivals.size)
        // While the app is open it banners new activity on your posts itself, push or not (iOS
        // 428a797b `postBanners`, owner 2026-11-22): the newest few recent arrivals, oldest
        // first, each once - not when its push already showed (the push's tag is the action's
        // txid; [KaPostsNotifier.claim] holds back a push that comes later). Older activity, and
        // the rest of a burst, are only counted in the bell.
        if (!appNotifications.isAppInForeground) return
        val nowMs = System.currentTimeMillis()
        val recent = arrivals
            .filter { nowMs - milliseconds(it.timestamp) <= BANNER_MAX_AGE_MS }
            .sortedByDescending { it.timestamp }
            .take(MAX_BANNERS_PER_POLL)
            .reversed()
        for (n in recent) {
            if (isNotificationsScreenVisible || !appNotifications.isAppInForeground) return
            // A tip is a payment in the tipper's chat: that chat's banner says "Received a tip"
            // and opens the chat, so this one would be a second notice for it (iOS 2026-11-22).
            if (n.contentType == "tip") continue
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            // Its push already came, maybe the one you tapped to open K: no second banner (iOS
            // `InAppBanner.pushArrived("kaposts:" + KaPostsBannerLedger.key)`, 2026-11-22).
            val kaPostKey = com.kachat.app.services.PushArrivalRecord.kaPostsKey(
                n.userPublicKey,
                n.contentId,
                com.kachat.app.services.PushArrivalRecord.kaPostsKind(
                    n.contentType, n.voteType, KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim().isNotEmpty()
                ),
            )
            val pushCame = appNotifications.pushArrived(n.id) || appNotifications.pushArrived("kaposts:$kaPostKey")
            if (pushCame || appNotifications.isShowing(n.id)) {
                notificationHelper.claim(n.id)
                continue
            }
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            val name = actorDisplayName(actor)
            notificationHelper.showKaPosts(
                // "alice liked your post: <text>", the text cut at 200 characters (iOS 428a797b).
                text = "$name ${actionText(n.contentType, n.voteType, text, n.tipAmountSompi)}" + if (text.isEmpty()) "" else ": ${prefixCodePoints(text, 200)}",
                actionTxId = n.id,
                // Same per-kind target rule as the in-app notifications overlay and iOS: a
                // reply targets the reply ITSELF, and the landing rule opens its parent's thread
                // with the reply spliced in and scrolled to; quote-with-text opens the quote;
                // vote/mention open the acted-on post.
                postTxId = when (n.contentType) {
                    "reply" -> n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    // Same mention fallback as the bell targetId above.
                    "mention" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
                    else -> n.contentId
                },
            )
        }
    }

    companion object {
        /** With push active, how often the poll runs anyway, in case a push went missing (iOS
         *  961fc05a `pushSafetyNetInterval`). */
        const val PUSH_SAFETY_NET_MS = 5 * 60 * 1000L

        /** Whether a beat runs the poll: always without push; with push only once the safety net's
         *  five minutes (less a few seconds of beat jitter) have passed since the last poll. */
        fun shouldPollOnBeat(pushActive: Boolean, lastPollAtMs: Long, nowMs: Long): Boolean =
            !pushActive || lastPollAtMs <= 0L || nowMs - lastPollAtMs >= PUSH_SAFETY_NET_MS - 5_000L

        /** A push asks for a poll at once, unless one started this recently (iOS 428a797b `pollSoon`). */
        private const val POLL_SOON_MIN_GAP_MS = 15_000L

        /** Activity older than this when a poll finds it gets no banner (it is in the bell): a
         *  return after a long absence doesn't replay a stack of banners (iOS 428a797b). */
        private const val BANNER_MAX_AGE_MS = 10 * 60 * 1000L

        /** The most banners one poll shows; the rest are only counted in the bell (iOS 428a797b). */
        private const val MAX_BANNERS_PER_POLL = 3

        /** Indexer timestamps are milliseconds; a seconds value is scaled up. */
        private fun milliseconds(timestamp: Long): Long = if (timestamp < 100_000_000_000L) timestamp * 1000 else timestamp

        /** The first [count] characters of [text], never cutting an emoji in half. */
        private fun prefixCodePoints(text: String, count: Int): String {
            val points = text.codePointCount(0, text.length)
            return if (points <= count) text else text.substring(0, text.offsetByCodePoints(0, count))
        }

        /** How far back one poll follows the bell's count (50 a page, iOS ee5ec98). */
        private const val MAX_UNSEEN_COUNT_PAGES = 10
    }

    // in the app's language (iOS audit 2026-11-27 M10)
    private fun actionText(contentType: String?, voteType: String?, text: String, tipAmountSompi: Long? = null): String = when (contentType) {
        "vote" -> if (voteType == "downvote") notice(R.string.notice_post_disliked) else notice(R.string.notice_post_liked)
        // "tipped your post: 1.5 KAS" (iOS 09f6ae0)
        "tip" -> tipAmountSompi?.let { notice(R.string.notice_post_tipped_amount, "${com.kachat.app.util.KaspaUnit.plain(it)} ${com.kachat.app.util.KaspaUnit.symbol}") }
            ?: notice(R.string.notice_post_tipped)
        "reply" -> notice(R.string.notice_post_replied)
        "quote" -> if (text.isEmpty()) notice(R.string.notice_post_reposted) else notice(R.string.notice_post_quoted)
        "follow" -> notice(R.string.notice_followed_you)
        "mention" -> notice(R.string.notice_post_mentioned)
        else -> notice(R.string.notice_post_interacted)
    }

    private fun notice(res: Int, vararg args: Any): String =
        runCatching { com.kachat.app.util.tr(res, *args) }.getOrElse { appContext.getString(res, *args) }
}
