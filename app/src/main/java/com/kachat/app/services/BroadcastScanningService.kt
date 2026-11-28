package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import protowire.Rpc
import javax.inject.Inject
import javax.inject.Singleton

private fun String.hexToBytesOrNull(): ByteArray? {
    return try {
        if (isEmpty()) ByteArray(0) else chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (e: Exception) {
        null
    }
}

/**
 * Scanning for broadcast messages, on-demand from two independent sources that share one
 * underlying subscription via a simple reference count — but each source only causes messages for
 * *its own* channel(s) to actually be cached, not every joined channel:
 *  1. Any channel marked "always listen" (see [BroadcastRepository.setAlwaysListen], toggled via
 *     the speaker icon next to a channel in the broadcast list) — per-channel opt-in, off by
 *     default. The stream runs only while the app is in front (audit 2026-11-25 N1): in the
 *     background a bell-on room comes from push, or without push from a small indexer poll
 *     ([startBackgroundPoll]). There's no separate global setting: the user picks exactly which
 *     channels stay live rather than an all-or-nothing toggle.
 *  2. [startLiveViewing] — held open by a broadcast channel screen for as long as it's on
 *     screen, so messages appear live while actively viewing that specific room even if it isn't
 *     marked always-listen. Bounded to only while that specific screen is visible.
 *
 * There's no per-address query for a public "bcast" channel — unlike normal messages, which are
 * found via the indexer's by-address lookups — so this inspects every transaction in every new
 * block via the node's block-added notification stream (see
 * [com.kachat.app.services.grpc.KaspadConnection.subscribeToBlockAdded]), matching Kasia's own
 * approach. The subscription itself can't be scoped to one channel (Kaspa transactions have no
 * concept of a "channel" at the network level, only in how the app parses payloads) — every new
 * block is inspected regardless — but [processBlock] only *inserts* a message if its channel is in
 * the always-listen set or is currently being live-viewed, so toggling one channel's speaker icon
 * doesn't silently start caching every other joined channel's messages too.
 *
 * Builds a rolling local cache of wanted channels' messages, retained per channel for up to
 * BroadcastRetention.MAX_MILLIS (user configurable per channel via the settings icon) — there is
 * no way to retroactively query the blockchain for past broadcasts (no indexer supports it, and a
 * node's block-added subscription only pushes forward from the moment of subscription), so
 * history only exists for whatever window a given channel was actually wanted (always-listen or
 * live-viewed).
 */
@Singleton
class BroadcastScanningService @Inject constructor(
    private val nodePoolManager: NodePoolManager,
    private val database: KaChatDatabase,
    private val broadcastRepository: BroadcastRepository,
    private val notificationHelper: NotificationHelper,
    // Consulted ONLY at the notification-posting site: while native FCM push is active, the
    // server pushes bell-enabled channels' messages (the iOS repo's PUSH_EXTENSIONS.md §2/§4) and a local
    // banner here too would be a duplicate. Scanning/caching itself is never gated on it.
    private val pushState: PushState,
    // Which room is on screen, and the room banners (Public Chats' own, beside NotificationHelper).
    private val broadcastNotifier: com.kachat.app.services.publicchats.BroadcastNotifier,
    // Metered gate — see reevaluate(): on cellular the full-block stream only runs for wanted
    // channels that have NO other delivery path, and only while the app is foregrounded.
    private val meteredNetwork: MeteredNetwork,
    // Public-chat authorship (audit XP-012): a post is stored only once input 0's address is
    // known to equal output 0's - see BroadcastSenderVerifier.
    private val senderVerifier: BroadcastSenderVerifier,
    // Signed out, no room streams or polls (audit 2026-11-25 B2, B6): the active address
    // outlives Log Out, so the bell-on rooms it reads kept the stream going on the sign-in screen.
    private val walletManager: WalletManager,
    // The background poll without push runs only when its banner can show (audit 2026-11-25 N1).
    private val settings: com.kachat.app.repository.AppSettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    // A new message that @'s you: a row in Profile's bell (iOS d089011f `insertRoomMessages`).
    private val roomMentionBell: dagger.Lazy<RoomMentionBell>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("BroadcastScanningService"))
    private var scanJob: Job? = null
    private var lastPruneAt = 0L
    private var wantCount = 0
    private var alwaysListenWasWanted = false

    // Union of alwaysListenChannelNames/liveViewedChannelNames is exactly which channels'
    // messages get cached while scanning runs. notifyEnabledChannelNames is a further filter on
    // top of that for which of those also fire a system notification (see BroadcastRepository —
    // notify-enabled always implies always-listen, so this is always a subset of the union
    // above). Plain fields guarded by @Synchronized alongside acquire()/release(), since they're
    // mutated from whatever thread calls startLiveViewing()/its close() as well as this class's
    // own settings-observing coroutines.
    private var alwaysListenChannelNames: Set<String> = emptySet()
    private var liveViewedChannelNames: Set<String> = emptySet()
    private var notifyEnabledChannelNames: Set<String> = emptySet()
        private var hiddenSenderRows: List<com.kachat.app.models.HiddenBroadcastSenderEntity> = emptyList()
    /** Which indexer each room reads from and which are down; null until the settings are read. */
    private var roomIndexerCoverage: RoomIndexerCoverage? = null

    /** Whether an account is signed in ([WalletManager.signedInAddressFlow]). */
    @Volatile
    private var signedIn: Boolean = !walletManager.isLoggedOut && walletManager.activeAddressFlow.value != null

    /** K's own Notifications switch (Settings), for the background poll's gate. */
    @Volatile
    private var appNotificationsOn: Boolean = true

    init {
        // Self-observing rather than wired externally per-screen: this needs to hold/release its
        // "want" for the whole app's lifetime based purely on whether any channel is marked
        // always-listen, regardless of which screen (if any) is open — KaChatApplication
        // field-injects this class just to force Hilt to instantiate it (and so run this
        // observer) at app startup, since a @Singleton is otherwise only created lazily the first
        // time something actually requests it.
        scope.launch {
            broadcastRepository.getAlwaysListenChannelNames().collectLatest { names ->
                onAlwaysListenChannelsChanged(names)
            }
        }
        scope.launch {
            broadcastRepository.getNotifyEnabledChannelNames().collectLatest { names ->
                setNotifyEnabledChannelNames(names)
            }
        }
        scope.launch {
            broadcastRepository.getHiddenSenders().collectLatest { rows ->
                setHiddenSenderRows(rows)
            }
        }
        // The metered gate depends on foreground state and the network type — both can change
        // while demand (wantCount) is unchanged, so each change re-runs the start/stop decision.
        scope.launch {
            notificationHelper.appForegroundFlow.collect {
                reevaluate()
                updateBackgroundPoll()
            }
        }
        // Signed in or out: the stream and the background poll follow (audit 2026-11-25 B6).
        scope.launch {
            walletManager.signedInAddressFlow.collect { address ->
                signedIn = address != null
                reevaluate()
                updateBackgroundPoll()
            }
        }
        scope.launch {
            settings.notificationsEnabled.collect { on ->
                appNotificationsOn = on
                updateBackgroundPoll()
            }
        }
        // The closed-room sweep, alive only while the app is on screen.
        scope.launch {
            notificationHelper.appForegroundFlow.collect { foreground ->
                if (foreground) startForegroundSweep() else stopForegroundSweep()
                // On app open: which rooms the indexer tracks (at most hourly, iOS 21c480d).
                if (foreground) refreshIndexerTrackedRooms()
            }
        }
        // And on wallet switch, as iOS does on every wallet load.
        scope.launch {
            broadcastRepository.activeAddressFlow.collect { address ->
                if (address != null) refreshIndexerTrackedRooms()
            }
        }
        // An account switch stops the sweep where it is - a room's fetch still out drops its
        // answer (BroadcastRepository.isCurrentWallet) - and the new account's starts afresh
        // (iOS b78fdb0 setCurrentWallet: "every room's poll stops here, and the sweep with it").
        scope.launch {
            broadcastRepository.activeAddressFlow.drop(1).collect { address ->
                stopForegroundSweep()
                // Recorded first (idempotent), so the sweep starts again as the new account.
                broadcastRepository.onActiveWalletChanged(address)
                if (notificationHelper.isAppInForeground) startForegroundSweep()
            }
        }
        // A room the indexer turned out to track (or stopped tracking): the metered gate's
        // "any wanted room without another delivery path?" answer may have flipped. The screens
        // collect the same flow for their wording.
        scope.launch {
            IndexerTrackedRooms.changes.collect { reevaluate() }
        }
        scope.launch {
            meteredNetwork.isMeteredFlow.collect { reevaluate() }
        }
        // Push on or off: whether a bell-on room in the background needs the poll.
        scope.launch {
            pushState.pushActive.collect { updateBackgroundPoll() }
        }
        // The chess arena's indexer down, or back: with it down the block stream is the arena's
        // only live route, metered or not (iOS da59726). Its current value too: this service can
        // start after the indexer went down.
        scope.launch {
            broadcastRepository.arenaIndexerDown.collect { reevaluate() }
        }
        // An indexer confirmed down, or back: its rooms' delivery path changed (iOS ee5ec98).
        scope.launch {
            kotlinx.coroutines.flow.combine(
                broadcastRepository.roomIndexerBases(),
                broadcastRepository.unreachableIndexers,
            ) { bases, down ->
                RoomIndexerCoverage(appWideBase = bases.first, overrides = bases.second, unreachable = down.keys)
            }.collect { setRoomIndexerCoverage(it) }
        }
        // Rows cached before public-chat sender verification existed took output 0's address
        // (or the indexer's guess) as the sender: re-check them against the chain once (XP-012).
        scope.launch {
            val attempt = senderVerifier.cachedReverifyAttempt() ?: return@launch
            delay(REVERIFY_START_DELAY_MS)
            senderVerifier.awaitRestApi(REVERIFY_REST_WAIT_MS)
            val complete = try {
                broadcastRepository.reverifyCachedSenders(attempt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BroadcastScanningService", "Cached public-chat sender re-check failed", e)
                false
            }
            senderVerifier.recordCachedReverify(complete)
        }
    }

    @Synchronized
    private fun setNotifyEnabledChannelNames(names: Set<String>) {
        notifyEnabledChannelNames = names
    }

    @Synchronized
    private fun isChannelNotifyEnabled(channelName: String): Boolean = channelName in notifyEnabledChannelNames

    @Synchronized
    private fun setRoomIndexerCoverage(coverage: RoomIndexerCoverage) {
        roomIndexerCoverage = coverage
        reevaluate()
    }

    /** Whether an indexer covers this room: an indexer that isn't answering covers nothing, and
     *  its rooms keep the stream until it does (iOS ee5ec98 `scanWantedChannels`). */
    private fun hasIndexerDelivery(channelName: String): Boolean {
        // The arena has its own indexer health (iOS `PublicChatIndexerClient.arenaHealth`).
        if (channelName in FeaturedBroadcastChannels.SERVICE_CHANNELS) {
            if (broadcastRepository.arenaIndexerDown.value) return false
        } else if (!FeaturedBroadcastChannels.isIndexerTracked(channelName)) return false
        val coverage = roomIndexerCoverage ?: return true
        val base = BroadcastRepository.indexerBaseUrl(channelName, coverage.appWideBase, coverage.overrides)
        return base.isNotEmpty() && base !in coverage.unreachable
    }

    /** Whether an indexer keeps this room's history, answering now or not: the background poll
     *  asks it, so a down indexer is re-checked with that one request (audit 2026-11-25 N1). */
    private fun indexerServesRoom(channelName: String): Boolean {
        if (channelName in FeaturedBroadcastChannels.SERVICE_CHANNELS) return false
        if (!FeaturedBroadcastChannels.isIndexerTracked(channelName)) return false
        val coverage = roomIndexerCoverage ?: return true
        return BroadcastRepository.indexerBaseUrl(channelName, coverage.appWideBase, coverage.overrides).isNotEmpty()
    }

    @Synchronized
    private fun setHiddenSenderRows(rows: List<com.kachat.app.models.HiddenBroadcastSenderEntity>) {
        hiddenSenderRows = rows
    }

    /** Skips storing a sender's messages for a room they're hidden in (per-room since 4.0; "" rows hide everywhere) — see BroadcastRepository.getMessages for the display-side filter covering already-cached ones too. */
    @Synchronized
    private fun isSenderHidden(address: String, channelName: String): Boolean =
        hiddenSenderRows.any { it.senderAddress == address && (it.channelName.isEmpty() || it.channelName == channelName) }

    val isRunning: Boolean get() = scanJob?.isActive == true

    // MARK: - Foreground sweep of closed rooms

    private var sweepJob: kotlinx.coroutines.Job? = null
    /** Rooms the sweep has asked about at least once this session: only rows found AFTER that
     *  first pass are news worth a banner - the first pass is history catching up. */
    private val sweptChannels = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * While the app is on screen, a room the user is NOT looking at used to be refreshed only by
     * the live block scan - which misses blocks whenever the stream reconnects, and is off
     * altogether on metered networks for the indexed rooms. So a message in #kaspa showed up only
     * once the room was opened. On every foreground beat ([ForegroundBeat]: 30 s on Wi-Fi, 60 s on
     * a metered path, shared with KaPost's and kUSDT's polls so the radio wakes once per beat; iOS
     * 961fc05a, audit 2026-11-25 B4; 20 s of its own before) each joined room with its bell on
     * that is not open asks the indexer for its newest rows instead (one small request per room,
     * sequential); the open room keeps its own 8s poll and the block scan stays the fast path
     * (iOS 3c14b46).
     *
     * With push working, a room the push covers is swept only every 5 minutes, as a safety net
     * (iOS audit 2026-11-27 B4, as KaPost): its push fetches it at once. Rooms the push doesn't
     * cover keep every beat, and push off keeps this cadence.
     */
    @Synchronized
    private fun startForegroundSweep() {
        if (sweepJob?.isActive == true) return
        // Back in the foreground: the first sweep covers every room again.
        lastPushCoveredSweepAt = 0L
        sweepJob = scope.launch {
            while (true) {
                if (notificationHelper.isAppInForeground) sweepClosedRooms()
                ForegroundBeat.sleepUntilNextBeat(offsetMs = 0L)
            }
        }
    }

    @Synchronized
    private fun stopForegroundSweep() {
        sweepJob?.cancel()
        sweepJob = null
    }

    private fun refreshIndexerTrackedRooms() {
        scope.launch { broadcastRepository.refreshIndexerTrackedRooms() }
    }

    private fun myAddressOrNull(): String? = runCatching { broadcastRepository.myAddress() }.getOrNull()

    // MARK: - Background poll of bell-on rooms without push

    private var backgroundPollJob: Job? = null

    /**
     * Without push, nothing else can banner a bell-on room while the app is in the background, and
     * the block stream no longer runs there (audit 2026-11-25 N1: on Wi-Fi it kept the radio and
     * CPU awake all night, ≈10 blocks a second). Instead, every [BACKGROUND_POLL_MS] each bell-on
     * room an indexer keeps asks it for its newest rows, as the foreground sweep does: one small
     * request per room. Only while signed in, without push, and with notifications on in K and in
     * Android: otherwise nothing found could be shown, and the front catches up on opening. A
     * room whose indexer was marked down is asked too, so the mark clears when it answers.
     */
    @Synchronized
    private fun updateBackgroundPoll() {
        val wanted = !notificationHelper.isAppInForeground && signedIn && !pushState.isActive && appNotificationsOn
        if (!wanted) {
            backgroundPollJob?.cancel()
            backgroundPollJob = null
            return
        }
        startBackgroundPoll()
    }

    @Synchronized
    private fun startBackgroundPoll() {
        if (backgroundPollJob?.isActive == true) return
        backgroundPollJob = scope.launch {
            while (true) {
                // The foreground sweep has just run: the first background look is one interval on.
                delay(BACKGROUND_POLL_MS)
                if (!systemNotificationsOn()) continue
                try {
                    sweepClosedRooms(background = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("BroadcastScanningService", "Background room poll failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun systemNotificationsOn(): Boolean =
        runCatching { androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled() }.getOrDefault(true)

    /** When the last foreground sweep included the rooms push covers (0: none yet). */
    @Volatile private var lastPushCoveredSweepAt = 0L

    /** A room the push registration watches (indexer-tracked, bell on - PushRegistrationManager
     *  `collectFeatureFields`) and that is read from the app-wide indexer, which sends its pushes
     *  (iOS `pushCoversRoom`). */
    private fun pushCoversRoom(channelName: String): Boolean {
        if (!FeaturedBroadcastChannels.isIndexerTracked(channelName)) return false
        val overrides = roomIndexerCoverage?.overrides ?: return true
        return overrides[channelName.trim().lowercase()]
            ?.takeUnless { BroadcastRepository.isCleartextHttp(it) }
            .isNullOrBlank()
    }

    /** One pass over the bell-on rooms: in front, those not open (on the beat); in the background
     *  without push, those an indexer keeps ([updateBackgroundPoll]). */
    private suspend fun sweepClosedRooms(background: Boolean = false) {
        // Signed out: no rooms to sweep (iOS b78fdb0 `startForegroundSweep`).
        if (!broadcastRepository.isSignedIn()) return
        // In front with push working: the rooms it covers every 5 minutes only (iOS B4).
        val now = System.currentTimeMillis()
        val coveredDue = background || isPushCoveredSweepDue(pushState.isActive, lastPushCoveredSweepAt, now)
        if (!background && coveredDue) lastPushCoveredSweepAt = now
        val targets = synchronized(this) { notifyEnabledChannelNames.toList() }
            .filter { it !in FeaturedBroadcastChannels.SERVICE_CHANNELS }
            .filter { if (background) indexerServesRoom(it) else !broadcastNotifier.isViewingChannel(it) }
            .filter { coveredDue || !pushCoversRoom(it) || it !in sweptChannels }
        for (channel in targets) {
            val stillWanted = if (background) {
                !notificationHelper.isAppInForeground && !pushState.isActive
            } else {
                notificationHelper.isAppInForeground
            }
            if (!stillWanted || !broadcastRepository.isSignedIn()) return
            val fresh = runCatching { broadcastRepository.fetchNewestFromIndexer(channel) }.getOrDefault(emptyList())
            val firstPass = sweptChannels.add(channel)
            // Rows older than a few minutes are backlog, not a message that just arrived - and
            // the first pass for a room is history catching up, so it stays quiet.
            val cutoff = System.currentTimeMillis() - SWEEP_BANNER_WINDOW_MS
            // While the app is open it banners new room messages itself, push or not (iOS
            // 428a797b `InAppBanner`): one per message, never a reaction or an edit.
            if (!firstPass) {
                for (row in fresh) {
                    if (row.blockTimestamp <= cutoff) continue
                    if (!notificationHelper.mayBannerLocally(pushState.isActive, row.blockTimestamp)) continue
                    if (broadcastRepository.isLocalAccountAddress(row.senderAddress)) continue
                    if (isSenderHidden(row.senderAddress, channel)) continue
                    // never a reaction, an edit or a delete for everyone (iOS isControlEnvelope)
                    if (com.kachat.app.util.isRoomControlEnvelope(row.content)) continue
                    broadcastNotifier.showBroadcast(
                        channelName = channel,
                        title = "#$channel",
                        text = com.kachat.app.util.BroadcastPushPreview.clean(row.content),
                        dedupeTxId = row.id,
                    )
                }
            }
            kotlinx.coroutines.delay(SWEEP_SPACING_MS)
        }
    }

    @Synchronized
    private fun onAlwaysListenChannelsChanged(names: Set<String>) {
        alwaysListenChannelNames = names
        val wanted = names.isNotEmpty()
        if (wanted != alwaysListenWasWanted) {
            alwaysListenWasWanted = wanted
            if (wanted) acquire() else release()
        } else {
            // Same demand, different channel mix — the metered gate's "any wanted channel
            // without another delivery path?" answer may have flipped.
            reevaluate()
        }
    }

    /**
     * Keeps scanning active — and messages for [channelName] specifically cached — only while the
     * caller holds onto the returned handle. Used by a broadcast channel screen so live messages
     * appear while it's open, without requiring that channel to be marked always-listen. Close the
     * handle (e.g. from a DisposableEffect) when the screen goes away; scanning only actually
     * stops once nothing else — including any always-listen channel — still wants it.
     */
    fun startLiveViewing(channelName: String): AutoCloseable {
        acquire()
        addLiveViewedChannel(channelName)
        var released = false
        return AutoCloseable {
            if (!released) {
                released = true
                removeLiveViewedChannel(channelName)
                release()
            }
        }
    }

    @Synchronized
    private fun addLiveViewedChannel(channelName: String) {
        liveViewedChannelNames = liveViewedChannelNames + channelName
        reevaluate()
    }

    @Synchronized
    private fun removeLiveViewedChannel(channelName: String) {
        liveViewedChannelNames = liveViewedChannelNames - channelName
        reevaluate()
    }

    @Synchronized
    private fun acquire() {
        wantCount++
        reevaluate()
    }

    @Synchronized
    private fun release() {
        wantCount = (wantCount - 1).coerceAtLeast(0)
        reevaluate()
    }

    /**
     * One start/stop decision for every input change (demand, foreground, sign-in, network type,
     * channel mix). The full-block stream (roughly 1-4 GB/day if left running, every block
     * decoded) runs only while the app is in front and an account is signed in (audit 2026-11-25
     * N1, B2, B6): in the background, on Wi-Fi too, it kept the radio and the CPU awake all night
     * for bell-on rooms. There a bell-on room comes from push, or without push from the indexer
     * poll ([updateBackgroundPoll]); a room with no indexer waits for the app to open, as on iOS,
     * which is suspended. In front, the indexer-tracked rooms (curated, or added on the indexer)
     * already get an 8s indexer poll while open (BroadcastViewModel.startIndexerBackfill) plus a
     * 30-day history backfill on open, and the closed-room sweep on the beat, so on a metered
     * network only non-indexed always-listen / live-viewed channels (and those whose indexer is
     * confirmed down, iOS ee5ec98) need the stream.
     */
    @Synchronized
    private fun reevaluate() {
        // Only the rooms that need the stream turn it on (iOS `scanWantedChannels`, its audit #26:
        // streaming every bell-on room on Wi-Fi kept the app parsing ~10 full blocks a second;
        // audit M7 here):
        // - a room the indexer doesn't cover (none keeps its history, or it isn't answering, or
        //   the arena with its indexer down): the stream is its only live route;
        // - a room open on screen, on Wi-Fi: the fast path on top of its 8 s indexer poll (the
        //   chess arena counts as open while a chess screen is up).
        // A covered bell-on room otherwise gets its messages from the closed-room sweep (in front,
        // on the beat) and remote push. Never in the background or signed out (see above).
        val foreground = notificationHelper.isAppInForeground
        val metered = meteredNetwork.isMetered
        val rooms = alwaysListenChannelNames + liveViewedChannelNames
        // Per room, so the block handler only checks authors for the rooms the stream is on for
        // (iOS `streamChannels`): a covered bell-on room riding along with an uncovered one used
        // to cost an author lookup per post too.
        val stream = if (!foreground || !signedIn) emptySet() else rooms.filterTo(HashSet()) { room ->
            !hasIndexerDelivery(room) || (!metered && room in liveViewedChannelNames)
        }
        streamChannelNames = stream
        val shouldRun = wantCount > 0 && stream.isNotEmpty()
        if (shouldRun != isRunning) {
            Log.i("BroadcastScanningService", if (shouldRun) "Block stream on for ${stream.size} room(s)"
                else "Block stream off")
        }
        // Off, the collector goes with the subscription: no room's scan parses blocks (iOS
        // removes its block handler with no such room).
        if (shouldRun) startInternal() else stopInternal()
    }

    /** The rooms the stream is on for, as of the last [reevaluate]: the block handler only checks
     *  authors for these (iOS `streamChannels`). */
    @Volatile private var streamChannelNames: Set<String> = emptySet()

    /** The exact connection the live NOTIFY_START went to — [stopInternal] must send its
     *  NOTIFY_STOP to THIS node. A fresh getBroadcastConnection() there could hand back a
     *  different (or newly dialed) node, leaving the actually-subscribed one streaming forever. */
    @Volatile
    private var subscribedConnection: KaspadConnection? = null

    private fun startInternal() {
        if (isRunning) return
        scanJob = scope.launch {
            // A node that keeps failing is tried less and less often, and offline the loop waits
            // for the network (5 s every time before, audit 2026-11-25 N1).
            val retry = nodePoolManager.blockStreamRetry()
            while (true) {
                var conn: KaspadConnection? = null
                try {
                    conn = nodePoolManager.getBroadcastConnection()
                    // Captured BEFORE subscribing: a death/reconnect racing the NOTIFY_START
                    // must read as "generation moved" below, not get swallowed.
                    val subscribedGeneration = conn.connectionGeneration.value
                    val blocks = conn.subscribeToBlockAdded(owner = "public-rooms")
                    subscribedConnection = conn
                    coroutineScope {
                        val collector = launch { blocks.collect { block -> processBlock(block) } }
                        // The blocks Flow is a SharedFlow — its collector NEVER completes, so
                        // "collect returned" can never signal stream death (the old code waited
                        // on exactly that and so could never resubscribe after a failover). The
                        // real signal is the connection's generation counter, bumped on every
                        // stream death, reconnect, and close: when it moves past the value
                        // captured at subscribe time, the server-side NOTIFY_START state is
                        // gone and the loop must re-send it on whatever connection is best now.
                        conn.connectionGeneration.first { it != subscribedGeneration }
                        collector.cancel()
                    }
                } catch (e: Exception) {
                    Log.w("BroadcastScanningService", "Block scanning interrupted, retrying", e)
                } finally {
                    if (subscribedConnection === conn) subscribedConnection = null
                }
                retry.pause()
            }
        }
    }

    /** Must genuinely halt network/battery usage, not just stop writing to the DB — sends NOTIFY_STOP (to the exact node that was subscribed) before cancelling the collector. */
    private fun stopInternal() {
        // Read the subscribed connection BEFORE cancelling — the job's finally block clears it.
        val conn = subscribedConnection
        subscribedConnection = null
        scanJob?.cancel()
        scanJob = null
        if (conn != null) {
            scope.launch {
                try {
                    conn.unsubscribeFromBlockAdded(owner = "public-rooms")
                } catch (e: Exception) {
                    Log.w("BroadcastScanningService", "Failed to send NOTIFY_STOP", e)
                }
            }
        }
    }

    private suspend fun processBlock(block: Rpc.RpcBlock) {
        for (tx in block.transactionsList) {
            if (!tx.hasVerboseData()) continue
            val txId = tx.verboseData.transactionId
            if (txId.isBlank()) continue

            val payloadBytes = tx.payload.hexToBytesOrNull() ?: continue
            if (!MessageProtocol.isKaChatPayload(payloadBytes)) continue
            val parsed = MessageProtocol.parseBcastPayload(payloadBytes) ?: continue

            // Output 0 alone is NOT the sender (audit XP-012): anyone can pay someone else's
            // address with a bcast payload. The sender is the address input 0 spends from, and
            // the post counts only when output 0 pays that same address. The outputs are kept so
            // a later post spending one of them resolves locally.
            val outputAddresses = tx.outputsList.map { output ->
                KaspaAddress.addressFromScriptPublicKey(output.scriptPublicKey.scriptPublicKey)
                    ?: output.takeIf { it.hasVerboseData() }?.verboseData?.scriptPublicKeyAddress?.takeIf { it.isNotBlank() }
            }
            senderVerifier.rememberOutputs(txId, outputAddresses)
            // Only rooms the stream is on for (iOS `hitsWorthVerifying` over `streamChannels`):
            // the indexer serves the others, and their authors aren't looked up from here.
            if (parsed.channel !in streamChannelNames) continue
            // A sender hidden in this room is still checked and stored (iOS 369794e
            // `hitsWorthVerifying` drops them, but its store is per account): the room table is
            // shared by every account on the phone, and one account's hide must not cost another
            // the posts (audit L3). Reads filter them out, and they never banner.

            val input0 = tx.inputsList.firstOrNull()
            val hit = BroadcastSenderVerifier.LiveHit(
                txId = txId,
                channel = parsed.channel,
                outputAddress = outputAddresses.firstOrNull(),
                // The spent UTXO, when the node attaches it to the input (newer nodes may).
                inputAddress = input0?.takeIf { it.hasVerboseData() && it.verboseData.hasUtxoEntry() }
                    ?.verboseData?.utxoEntry?.scriptPublicKey?.scriptPublicKey
                    ?.let { KaspaAddress.addressFromScriptPublicKey(it) },
                inputOutpoint = input0?.takeIf { it.hasPreviousOutpoint() }?.previousOutpoint
                    ?.let { it.transactionId.lowercase() to it.index },
            )
            val blockTimestampMillis = if (block.hasHeader()) block.header.timestamp else System.currentTimeMillis()
            val settled = senderVerifier.settleLive(hit)
            when (settled.verdict) {
                BroadcastSenderVerifier.Verdict.VERIFIED ->
                    storeVerifiedHit(txId, parsed, settled.senderAddress ?: continue, blockTimestampMillis)
                BroadcastSenderVerifier.Verdict.FORGED -> senderVerifier.logDropped(txId, parsed.channel, settled)
                // Not known locally: ask the REST API off the block loop (retries for ~95 s), so
                // one slow lookup never holds up the stream. Stored only if it comes back verified.
                BroadcastSenderVerifier.Verdict.UNKNOWN -> scope.launch {
                    val judged = senderVerifier.verifyLive(hit)
                    val sender = judged.senderAddress
                    if (judged.verdict == BroadcastSenderVerifier.Verdict.VERIFIED && sender != null) {
                        storeVerifiedHit(txId, parsed, sender, blockTimestampMillis)
                    } else {
                        senderVerifier.logDropped(txId, parsed.channel, judged)
                    }
                }
            }
        }

        pruneIfDueGated()
    }

    /** Stores one live post whose [senderAddress] is VERIFIED, and notifies as before. */
    private suspend fun storeVerifiedHit(
        txId: String,
        parsed: MessageProtocol.BroadcastMessage,
        senderAddress: String,
        blockTimestampMillis: Long,
    ) {
        // Stored even when hidden in this room (audit L3, see the block loop); only the banner
        // below is skipped. The chess arena's reducer reads the table unfiltered, so a hidden
        // sender's arena rows stay out as before.
        val hiddenHere = isSenderHidden(senderAddress, parsed.channel)
        if (hiddenHere && parsed.channel in FeaturedBroadcastChannels.SERVICE_CHANNELS) return

        // A delete for everyone (iOS 2026-11-25): applied, never a row, a banner or a push, hidden
        // senders' too (removing their own message only hides more).
        com.kachat.app.util.MessageDelete.parseOrNull(parsed.content)?.let { delete ->
            notificationHelper.claimWithoutNotifying(txId)
            broadcastRepository.applyIncomingDeletes(listOf(delete.targetTxId to senderAddress), parsed.channel)
            return
        }
        // Never a message its sender deleted for everyone.
        if (broadcastRepository.isDeletedRow(parsed.channel, txId, senderAddress)) return

        val stored = database.broadcastDao().insertMessageIfAbsent(
            BroadcastMessageEntity(
                id = txId,
                channelName = parsed.channel,
                senderAddress = senderAddress,
                content = parsed.content,
                blockTimestamp = blockTimestampMillis
            )
        )
        // Already stored: if it was our send whose outcome was unknown, this is its delivered
        // copy, so it is sent now (iOS 33095ac `insertMessages`).
        if (stored == -1L) database.broadcastDao().settleUnconfirmed(txId, blockTimestampMillis)
        // A new message that @'s you, hidden senders aside: a row in Profile's bell, whatever the
        // room's bell (iOS d089011f).
        if (stored != -1L && !hiddenHere) {
            roomMentionBell.get().recordMentions(listOf(
                BroadcastMessageEntity(id = txId, channelName = parsed.channel, senderAddress = senderAddress,
                    content = parsed.content, blockTimestamp = blockTimestampMillis)
            ))
        }
        if (parsed.channel in FeaturedBroadcastChannels.SERVICE_CHANNELS) {
            val onOwnClock = broadcastRepository.isLocallyStamped(txId)
            broadcastRepository.confirmLocalStamp(txId)
            // Our own arena row, stamped with this phone's clock when it was submitted: the
            // block's time is what every other phone sees, so take it (the reducer orders joins
            // and runs the clocks by it). A row dropped as never landed that the chain shows after
            // all counts again (iOS 4922804). Only those two: any other row already has a chain
            // time, and the indexer's is the one every phone ends up with (iOS da59726).
            if (stored == -1L) {
                val existing = database.broadcastDao().getMessage(txId)
                if (existing != null && (onOwnClock || existing.deliveryStatus == "failed") &&
                    (existing.blockTimestamp != blockTimestampMillis || existing.deliveryStatus != "sent")) {
                    database.broadcastDao().insertMessage(existing.copy(blockTimestamp = blockTimestampMillis, deliveryStatus = "sent"))
                }
            }
        }

        // The bell lists KaPost and wallet activity only (iOS 148a5331 `AllNotificationsView`):
        // a room message never becomes a row there.

        // An edit is never announced - it changes an earlier message in place. Claim the
        // txId so a racing push cannot banner what this deliberately did not.
        //
        // Otherwise: the remote push is the only banner source while push is active,
        // foreground or background - see PushState. A room the push server does not index
        // notifies nothing; that is the trade, made on purpose and the same one iOS makes.
        // Only a device with no push at all banners from the scan. The chess arena is
        // machinery, never a chat: it never notifies (iOS serviceChannels).
        //
        // A post by an account on this phone is the user's own - a send that finished after they
        // left the room, or their other account - and is never announced either (audit M5; the
        // closed-room sweep skips them the same way).
        if (hiddenHere) return
        if (com.kachat.app.util.MessageEdit.parseOrNull(parsed.content) != null ||
            broadcastRepository.isLocalAccountAddress(senderAddress)
        ) {
            notificationHelper.claimWithoutNotifying(txId)
        } else if (isChannelNotifyEnabled(parsed.channel) &&
            parsed.channel !in FeaturedBroadcastChannels.SERVICE_CHANNELS &&
            // No push: as before. With push, only while the app is open, a recent message and
            // never a reaction (iOS 428a797b `InAppBanner`).
            (!pushState.isActive || (notificationHelper.mayBannerLocally(true, blockTimestampMillis) &&
                MessageReaction.parseOrNull(parsed.content) == null))
        ) {
            // A reaction's raw JSON must never surface in a notification — humanize it.
            // Otherwise unwrap a reply first so a voice reply's notification says "🎤 Audio
            // message" too, rather than showing the raw reply JSON (see MessageReply).
            val reaction = MessageReaction.parseOrNull(parsed.content)
            val displayContent = MessageReply.parseOrNull(parsed.content)?.text ?: parsed.content
            val notificationText = when {
                reaction != null -> "Reacted ${reaction.emoji}"
                VoiceMessage.parseOrNull(displayContent) != null -> "🎤 Audio message"
                else -> displayContent
            }
            broadcastNotifier.showBroadcast(
                channelName = parsed.channel,
                title = "#${parsed.channel}",
                text = notificationText,
                dedupeTxId = txId
            )
        }
    }

    /** Retention, also from an open room (iOS prunes on open, after each indexer merge and every
     *  few minutes): on mobile data an indexer room streams no blocks, so the block path above
     *  never ran it and old rows stayed. [force] skips the interval gate. */
    suspend fun pruneIfDue(force: Boolean = false) {
        if (force) lastPruneAt = 0L
        pruneIfDueGated()
    }

    private suspend fun pruneIfDueGated() {
        // Pruning on every single block would hammer the DB — Kaspa blocks arrive fast. Gate it
        // instead to roughly half the shortest retention any joined channel is currently
        // configured for (floor/ceiling below) — a fixed hourly gate would silently ignore a
        // channel someone deliberately set to a much shorter retention (e.g. to verify pruning
        // actually works) until the next hourly sweep finally caught up.
        val now = System.currentTimeMillis()
        val retentions = database.broadcastDao().getChannelRetentions()
        val pruneInterval = retentions.minOfOrNull { it.retentionMillis / 2 }
            ?.coerceIn(MIN_PRUNE_INTERVAL_MILLIS, MAX_PRUNE_INTERVAL_MILLIS)
            ?: MAX_PRUNE_INTERVAL_MILLIS
        if (now - lastPruneAt > pruneInterval) {
            lastPruneAt = now
            // Every room the indexer tracks - the curated ones and any added on the indexer
            // (iOS 21c480d) - keeps its full window.
            val fullWindowChannels = FeaturedBroadcastChannels.indexerTrackedNames()
            retentions.forEach { retention ->
                // Indexer-tracked rooms keep the indexer's FULL 30-day window regardless
                // of the stored per-channel value (their retention gear is hidden in the UI) —
                // the 3-day cap was pruning history the backfill had just fetched.
                // The chess arena is served by the indexer too and read back on every open
                // (ChessTournamentService), so the 3-hour default would delete the history a
                // backfill had just fetched - and with it the tournaments people are in.
                val effective = if (retention.channelName in fullWindowChannels ||
                    retention.channelName in FeaturedBroadcastChannels.SERVICE_CHANNELS
                ) {
                    BroadcastRetention.INDEXER_MILLIS
                } else {
                    retention.retentionMillis
                }
                database.broadcastDao().deleteOlderThan(retention.channelName, now - effective)
            }
        }
    }

    private data class RoomIndexerCoverage(
        val appWideBase: String,
        val overrides: Map<String, String>,
        val unreachable: Set<String>,
    )

    companion object {
        /** With push working, how often the foreground sweep includes the rooms it covers (iOS
         *  `pushSafetyNetInterval`, audit 2026-11-27 B4). */
        internal const val PUSH_SAFETY_NET_MS = 5 * 60_000L

        /** Whether this sweep includes the rooms push covers: always without push, else every
         *  [PUSH_SAFETY_NET_MS] (less five seconds of slack) since [lastCoveredAt] (0: never). */
        internal fun isPushCoveredSweepDue(pushActive: Boolean, lastCoveredAt: Long, nowMs: Long): Boolean =
            !pushActive || lastCoveredAt == 0L || nowMs - lastCoveredAt >= PUSH_SAFETY_NET_MS - 5_000L

        /** A breath between rooms, so a sweep is a drizzle rather than a burst. */
        private const val SWEEP_SPACING_MS = 150L
        /** Rows older than this are backlog, not news, and never banner. */
        private const val SWEEP_BANNER_WINDOW_MS = 3 * 60 * 1000L

        /** Without push, how often the background asks the indexer about bell-on rooms: the 1:1
         *  background pace without push (5 minutes). */
        private const val BACKGROUND_POLL_MS = com.kachat.app.repository.ChatPollSchedule.BACKGROUND_LIGHT_INTERVAL_MS
        /** The cached-row sender re-check waits for startup to settle, then for the REST client. */
        private const val REVERIFY_START_DELAY_MS = 10_000L
        private const val REVERIFY_REST_WAIT_MS = 60_000L
        // Floor: never sweep more often than this even if every channel is set to a tiny retention.
        private const val MIN_PRUNE_INTERVAL_MILLIS = 5_000L
        // Ceiling: never wait longer than this between sweeps, matching the old fixed cadence for
        // channels at (or near) the 3-day default.
        private const val MAX_PRUNE_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}
