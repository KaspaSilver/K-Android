package com.kachat.app.services

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.kachat.app.util.KaPostsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is in the FOREGROUND (KaChatApplication's process lifecycle observer calls
 * [start]/[stop] on foreground/background transitions), polls the indexer's notification stream
 * every 20 s, keeps the unseen count and shows a banner for new activity on your posts (iOS
 * 428a797b `KaPostsNotificationService`, owner 2026-11-22), so a banner shows even when the push
 * service misses one; one banner per action when the push comes too. A KaPost push while the app
 * is open asks for a poll at once ([pollSoon]). Once the app is backgrounded or closed, the push
 * service is the only KaPosts notification source. Last-seen is stored per wallet so nothing
 * replays, and opening the Notifications screen marks everything seen.
 */
@Singleton
class KaPostsNotificationPoller @Inject constructor(
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
    // Actor naming (iOS parity): your saved contact name wins, then their KNS domain,
    // then the shortened address — never a bare address when a better name exists.
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val knsService: KnsService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("KaPostsNotificationPoller"))
    private var pollJob: Job? = null

    /** Session cache: one alias/KNS resolution per actor per process, not one per poll. */
    private val actorNameCache = mutableMapOf<String, String>()

    private suspend fun actorDisplayName(address: String): String {
        actorNameCache[address]?.let { return it }
        val contact = try { chatRepository.getContacts().first().find { it.id == address } } catch (_: Exception) { null }
        val alias = contact?.alias?.trim().orEmpty()
        // The address's last 10 characters, as iOS KaPostsNotificationService.actorName.
        val fallback = address.takeLast(10)
        // A domain keeps its ".kas" here too: the suffix is part of the name.
        val name = if (alias.isNotEmpty()) {
            alias
        } else {
            val domain = contact?.knsName?.takeIf { com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY }?.trim().orEmpty().ifEmpty {
                try { knsService.identityPrimaryDomain(address) ?: knsService.identityName(address) ?: "" }
                catch (_: Exception) { "" }
            }
            if (domain.isNotEmpty()) domain else fallback
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

    @Synchronized
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                lastPollAt = System.currentTimeMillis()
                try { pollOnce() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w("KaPostsPoller", "poll failed", e) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

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
        // Notifications off: nothing here would be shown, so the indexer isn't asked (iOS 33095ac).
        if (!settingsRepository.notificationsEnabled.first()) return
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
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (appNotifications.isShowing(n.id)) {
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

    private companion object {
        /** Same cadence as iOS's KaPostsNotificationService. */
        const val POLL_INTERVAL_MS = 20_000L

        /** A push asks for a poll at once, unless one started this recently (iOS 428a797b `pollSoon`). */
        const val POLL_SOON_MIN_GAP_MS = 15_000L

        /** Activity older than this when a poll finds it gets no banner (it is in the bell): a
         *  return after a long absence doesn't replay a stack of banners (iOS 428a797b). */
        const val BANNER_MAX_AGE_MS = 10 * 60 * 1000L

        /** The most banners one poll shows; the rest are only counted in the bell (iOS 428a797b). */
        const val MAX_BANNERS_PER_POLL = 3

        /** Indexer timestamps are milliseconds; a seconds value is scaled up. */
        fun milliseconds(timestamp: Long): Long = if (timestamp < 100_000_000_000L) timestamp * 1000 else timestamp

        /** The first [count] characters of [text], never cutting an emoji in half. */
        fun prefixCodePoints(text: String, count: Int): String {
            val points = text.codePointCount(0, text.length)
            return if (points <= count) text else text.substring(0, text.offsetByCodePoints(0, count))
        }

        /** How far back one poll follows the bell's count (50 a page, iOS ee5ec98). */
        const val MAX_UNSEEN_COUNT_PAGES = 10
    }

    private fun actionText(contentType: String?, voteType: String?, text: String, tipAmountSompi: Long? = null): String = when (contentType) {
        "vote" -> if (voteType == "downvote") "disliked your post" else "liked your post"
        // "tipped your post: 1.5 KAS" (iOS 09f6ae0)
        "tip" -> tipAmountSompi?.let { "tipped your post: ${com.kachat.app.util.KaspaUnit.plain(it)} ${com.kachat.app.util.KaspaUnit.symbol}" }
            ?: "tipped your post"
        "reply" -> "replied to your post"
        "quote" -> if (text.isEmpty()) "reposted your post" else "quoted your post"
        "follow" -> "followed you"
        "mention" -> "mentioned you in a post"
        else -> "interacted with your post"
    }
}
