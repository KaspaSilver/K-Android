package com.kachat.app.services.chess

import com.kachat.app.util.UserFacingError
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kachat.app.util.KaPlayText
import com.kachat.app.services.KnsService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.SendOutcomeUnknownException
import com.kachat.app.services.WalletManager
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessArenaEvent
import com.kachat.app.util.ChessClock
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.ChessLeaderboardRow
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessText
import com.kachat.app.util.ChessTournament
import com.kachat.app.util.ChessTournamentCodec
import com.kachat.app.util.ChessTournamentEngine
import com.kachat.app.util.ChessTournamentGame
import com.kachat.app.util.ChessTournamentMessage
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chess tournaments (5.1): the `#chess-arena` room read through the Public Chats service
 * ([ChessArenaChannel]), reduced by [ChessTournamentEngine] into the bracket every phone agrees
 * on, and the actions a player can take - each one a room transaction. Mirrors iOS's
 * ChessTournamentService (back from KaPlay, 148a5331).
 *
 * The arena is machinery, not a chat: it is scanned only while a chess screen holds [acquire],
 * never notifies, and never appears in Public Chats (see [SERVICE_CHANNELS]).
 */
@Singleton
class ChessTournamentService @Inject constructor(
    /** The arena's side of the Public Chats service (iOS 148a5331: merged into PublicChatService). */
    private val arena: ChessArenaChannel,
    /** `GET /chess/leaderboard` on the arena's indexer. */
    private val indexerClient: ChessIndexerClient,
    private val walletManager: WalletManager,
    private val knsService: KnsService,
    /** Whether the app is on screen: the arena's poll only runs while it is (iOS da59726). */
    private val notificationHelper: NotificationHelper,
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // The chess sentences are looked up by their English (KaPlayText); the app context may
        // not have reached it yet when this service is the first to ask.
        KaPlayText.init(context)
    }

    private val _tournaments = MutableStateFlow<Map<String, ChessTournament>>(emptyMap())
    val tournaments: StateFlow<Map<String, ChessTournament>> = _tournaments.asStateFlow()

    /** All time (since [ChessTournamentEngine.LEADERBOARD_FRESH_FROM_MS]) and this UTC month: the
     *  indexer's boards (all history) merged with what this phone has read (the newest rows). */
    private val _leaderboard = MutableStateFlow<List<ChessLeaderboardRow>>(emptyList())
    val leaderboard: StateFlow<List<ChessLeaderboardRow>> = _leaderboard.asStateFlow()
    private val _monthlyLeaderboard = MutableStateFlow<List<ChessLeaderboardRow>>(emptyList())
    val monthlyLeaderboard: StateFlow<List<ChessLeaderboardRow>> = _monthlyLeaderboard.asStateFlow()
    fun board(period: ChessLeaderboardPeriod): StateFlow<List<ChessLeaderboardRow>> =
        if (period == ChessLeaderboardPeriod.MONTH) monthlyLeaderboard else leaderboard

    /**
     * Chain time as this phone estimates it: wall clock, for running clocks between blocks. It is
     * [ChessClock.now], which only a view that draws time collects (iOS 32e6392). A screen reads
     * `now.value` as it composes and recomposes on [timeRevision]: whatever the time decides that
     * a screen shows - a game lost on the clock, a seat that ran out, the player's own clock at
     * zero - bumps it the moment it flips, so a screen reading `now` is current whenever its
     * content can have changed.
     */
    val now: StateFlow<Long> get() = ChessClock.now
    private val nowMs: Long get() = ChessClock.now.value

    /** Bumped when something the clock decides has changed ([TimeState]), so the screens
     *  recompose then, and only then (iOS 32e6392). */
    private val _timeRevision = MutableStateFlow(0)
    val timeRevision: StateFlow<Int> = _timeRevision.asStateFlow()

    /** The error toast every chess screen shows. Cleared by itself [ERROR_SHOWN_MS] after it was
     *  set ([showError]); a newer error restarts that, and the clear never wipes an error newer
     *  than the one it was for ([errorToken], iOS 077d777). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val errorToken = java.util.concurrent.atomic.AtomicInteger(0)

    private fun showError(message: String) {
        val token = errorToken.incrementAndGet()
        _lastError.value = message
        scope.launch {
            delay(ERROR_SHOWN_MS)
            if (errorToken.get() == token) _lastError.value = null
        }
    }

    /** KNS domains for arena players, so a name can fall back to one - see [resolveNames]. */
    private val _knsNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val knsNames: StateFlow<Map<String, String>> = _knsNames.asStateFlow()

    /** Moves sent and not yet seen back from the chain ("<tournament>|<game>"), so the player
     *  cannot double-send. */
    private val _pendingMoveGames = MutableStateFlow<Set<String>>(emptySet())
    val pendingMoveGames: StateFlow<Set<String>> = _pendingMoveGames.asStateFlow()

    private val lock = Any()
    private var refCount = 0
    private var liveViewing: AutoCloseable? = null
    private var clockJob: Job? = null
    private var backfillJob: Job? = null
    /** The arena's reducer, while a chess screen is up ([startReducing]). */
    private var reduceJob: Job? = null
    private var historyReadyJob: Job? = null
    private var fastPollJob: Job? = null
    /** When a claim was last posted for a game (wall ms). One is enough when it takes; one the
     *  chain voided (a last-second move landed first, a clock ahead of the chain) or that failed
     *  to send is posted again after [CLAIM_RETRY_MS], while the game is still undecided on chain. */
    private val claimedGames = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The automatic claims of one flag. A claim that keeps not taking is going against a board
     *  that's wrong, so each retry first pages the arena again, and after a few the app stops
     *  paying for them: the board's Claim win is still there (iOS da59726). */
    private data class AutoClaim(val flagAt: Long, val tries: Int = 0, val repaged: Boolean = false)
    private val autoClaims = java.util.concurrent.ConcurrentHashMap<String, AutoClaim>()
    /** One of this player's games has a later move than the board reached: at most every
     *  [GAP_REPAGE_GAP_MS] the arena is paged back to the game's start. */
    @Volatile private var lastGapRepageAt = 0L
    /** When each pending move was sent (wall ms): one the reducer still has not applied after
     *  [PENDING_MOVE_TIMEOUT_MS] stops showing as sending. (One the network dropped is applied from
     *  its local row until [dropUnconfirmedRowsIfDue] takes it out, iOS 4922804.) */
    private val pendingMoveSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Games whose claim is being sent right now: the board's Claim win does nothing while one is
     *  (iOS 4922804). */
    private val _claimsInFlight = MutableStateFlow<Set<String>>(emptySet())
    val claimsInFlight: StateFlow<Set<String>> = _claimsInFlight.asStateFlow()
    /** True while the chess server (the arena's indexer) isn't answering: what's on screen is what
     *  this phone has, plus what the block stream brings (iOS da59726). */
    val arenaServerDown: StateFlow<Boolean> get() = arena.arenaIndexerDown
    /** While the indexer is down, when each of this player's games last had all its players read
     *  from the REST API (wall ms). */
    private val chainReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var nextChainRead = 0L
    /** The account the per-account state above belongs to. */
    @Volatile private var stateAddress: String? = null

    private fun accountChanged(address: String?) {
        if (address == stateAddress) return
        stateAddress = address
        queuedPublicRoomId = null
        queuedAt = 0L
        _pendingMoveGames.update { emptySet() }
        pendingMoveSentAt.clear()
        claimedGames.clear()
        autoClaims.clear()
        autoRejoinsLeft = 0
        chainReadAt.clear()
        nextChainRead = 0L
        lastGapRepageAt = 0L
        _lastError.value = null
        // The lists are this player's ("my" rooms, busy or not) (iOS 32e6392).
        refreshTimeState(force = true)
    }

    val myAddress: String? get() = runCatching { walletManager.getAddress() }.getOrNull()

    // MARK: - Watching the arena

    /** A chess screen came on: keep the arena scanned while any is up. */
    fun acquire() = synchronized(lock) {
        refCount += 1
        if (refCount != 1) return@synchronized
        startReducing()
        scope.launch {
            runCatching { arena.joinChannel(ChessTournamentCodec.ARENA_CHANNEL) }
                .onFailure { Log.w(TAG, "Could not join the arena", it) }
        }
        refreshIndexerLeaderboards()
        // The clock stood still while no chess screen was up: the first checks on this screen must
        // not read a time minutes old.
        ChessClock.set(System.currentTimeMillis())
        liveViewing = arena.startLiveViewing(ChessTournamentCodec.ARENA_CHANNEL)
        // The live block scan only ever sees what is mined while it runs, so on its own a phone
        // opening Chess cannot see a seat taken a minute earlier - the two would never find each
        // other in the same room. The broadcast indexer serves the arena's history like a
        // curated room's, so it is read on open and kept fresh while a chess screen is up.
        backfillJob?.cancel()
        setHistoryReady(false)
        historyReadyJob?.cancel()
        // Rooms can be picked once the indexer has answered a page (even with nothing), is down or
        // isn't set, or the wait ran out: the whole backfill can take far longer (iOS da59726).
        historyReadyJob = scope.launch {
            val deadline = System.currentTimeMillis() + HISTORY_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (arena.arenaIndexerAnswered || arena.arenaIndexerDown.value ||
                    !runCatching { arena.arenaIndexerAddressSet() }.getOrDefault(true)
                ) break
                delay(HISTORY_CHECK_INTERVAL_MS)
            }
            setHistoryReady(true)
        }
        backfillJob = scope.launch {
            // Opening a chess screen asks at once whether the arena's indexer is back (iOS da59726).
            arena.allowArenaProbeNow()
            runCatching { arena.resyncArenaIndexerIfUnset() }
                .onFailure { Log.w(TAG, "Arena indexer resync failed", it) }
            while (isActive) {
                // A chess screen stays up while the app is in the background, with nobody to show
                // a row to: no network until it's back, when the gap is paged in again (iOS da59726).
                if (notificationHelper.isAppInForeground) {
                    runCatching { arena.backfillFromIndexer(ChessTournamentCodec.ARENA_CHANNEL) }
                        .onFailure { Log.w(TAG, "Arena backfill failed", it) }
                }
                delay(ARENA_BACKFILL_INTERVAL_MS)
            }
        }
        // The lists as of this instant: the clock stood still while no chess screen was up.
        refreshTimeState(force = true)
        // While this player is waiting in a room or playing, the arena is pulled every two
        // seconds on top of the 8s backfill and the block scan - the other side's join or move
        // should be on this phone in a couple of seconds, not ten, out of a five-minute clock
        // (iOS 593620e).
        fastPollJob?.cancel()
        fastPollJob = scope.launch {
            while (isActive) {
                delay(FAST_POLL_INTERVAL_MS)
                // Only while the app is up, like the room's own 8 s poll (iOS 32e6392). Back in
                // front, [resumeAfterBackground] polls at once.
                if (!notificationHelper.isAppInForeground) continue
                fastPollOnce()
            }
        }
        clockJob?.cancel()
        // Once a second: the clock views ([ChessClock]) recompose on this; the rest of a chess
        // screen only when something the time decides has changed ([refreshTimeState]). The
        // board's clock chip draws its own tenths in the last ten seconds from a local timeline.
        // Paused in the background: nothing is on screen, and the claims, drops and pending moves
        // are caught up the moment the app is back ([resumeAfterBackground], iOS 32e6392).
        clockJob = scope.launch {
            while (isActive) {
                delay(1_000)
                if (!notificationHelper.isAppInForeground) continue
                tick()
            }
        }
    }

    /** One second of the arena: the clock, then everything that runs on it (iOS 32e6392). */
    private fun tick() {
        ChessClock.set(System.currentTimeMillis())
        refreshTimeState()
        expirePendingMoves()
        dropUnconfirmedRowsIfDue()
        // A claim is a transaction: only with the app in front, as on iPhone, where a backgrounded
        // app is suspended - which is the only time the clock ticks.
        claimTimeoutsIfDue()
        // The 1st of the month (UTC) came while a chess screen was up: a new month board.
        val monthStart = boardsMonthStart
        if (monthStart != 0L && ChessTournamentEngine.utcMonthStartMs(nowMs) != monthStart) {
            recomputeLocalBoards()
            refreshIndexerLeaderboards(force = true)
        }
    }

    /** The fast poll's one pass (every 2 s while the app is in front, and once on coming back). */
    private suspend fun fastPollOnce() {
        if (synchronized(lock) { refCount } == 0) return
        runCatching { arena.resyncArenaIndexerIfUnset() }
            .onFailure { Log.w(TAG, "Arena indexer resync failed", it) }
        // With the indexer down the room's own poll checks when it's back; this phone's games are
        // read by their players' addresses (iOS da59726).
        if (arena.arenaIndexerDown.value) {
            runCatching { readMyGamesFromChainIfDue() }
                .onFailure { Log.w(TAG, "Arena chain read failed", it) }
            return
        }
        // Only while it matters: a seat held, a game on, or a flag waiting to be claimed. Idle in
        // the lobby, the ordinary backfill is plenty.
        if (!_isBusyOnline.value) return
        runCatching { arena.fetchNewestFromIndexer(ChessTournamentCodec.ARENA_CHANNEL) }
            .onFailure { Log.w(TAG, "Arena fast poll failed", it) }
    }

    /** Back from the background: the clock and the fast poll were paused there (they only run
     *  while the app is up), so catch up at once rather than on the next tick (iOS 32e6392). */
    private fun resumeAfterBackground() {
        if (synchronized(lock) { refCount } == 0) return
        tick()
        scope.launch { fastPollOnce() }
    }

    /**
     * Reduces the arena on every change while a chess screen is up (from [acquire], till
     * [release]). Nothing outside the chess screens reads what it works out, and each of its
     * reads is the whole thirty-day arena, re-run by Room on any write to the Public Chats table
     * (the chess moves, every room's polls and the block scan): with Chess closed that was
     * background work for nobody (audit M8). Opening Chess again reduces at once, on the rows as
     * they stand then.
     */
    private fun startReducing() {
        reduceJob?.cancel()
        reduceJob = scope.launch {
            arena.messages(ChessTournamentCodec.ARENA_CHANNEL)
                .distinctUntilChanged()
                .conflate()
                .collect { rows -> reduceMutex.withLock { reduce(rows) } }
        }
    }

    fun release() = synchronized(lock) {
        refCount = maxOf(0, refCount - 1)
        if (refCount != 0) return@synchronized
        reduceJob?.cancel()
        reduceJob = null
        liveViewing?.close()
        liveViewing = null
        backfillJob?.cancel()
        backfillJob = null
        historyReadyJob?.cancel()
        historyReadyJob = null
        fastPollJob?.cancel()
        fastPollJob = null
        clockJob?.cancel()
        clockJob = null
    }

    /**
     * The indexer is down: this player's live games, and the ones just flagged, from the two
     * players' addresses. It fills in what the block stream missed (a reconnect, the app in the
     * background) and is what lets a timeout claim go out ([flagIsConfirmed]), which rests on the
     * opponent's address alone: their moves are what could be missing (iOS da59726).
     */
    private suspend fun readMyGamesFromChainIfDue() {
        val me = myAddress ?: return
        val now = System.currentTimeMillis()
        if (now < nextChainRead) return
        nextChainRead = now + CHAIN_READ_INTERVAL_MS
        for (tournament in _tournaments.value.values) {
            if (tournament.status != ChessTournament.Status.LIVE) continue
            for (game in tournament.games.values) {
                val mine = game.color(me) ?: continue
                val flagAt = game.flagAt ?: continue
                if (now - flagAt >= AUTO_CLAIM_WINDOW_MS) continue
                val askedAt = System.currentTimeMillis()
                val answered = arena.readArenaFromChain(
                    tournamentId = tournament.id, players = listOf(game.address(mine.opposite)), since = game.startedAt,
                )
                arena.readArenaFromChain(
                    tournamentId = tournament.id, players = listOf(me), since = game.startedAt,
                )
                if (answered) chainReadAt["${tournament.id}|${game.id}"] = askedAt
            }
        }
    }

    /** Whether the chain has been read since this flag: the moves on this phone can be behind
     *  (just relaunched, or the indexer down), and a clock judged from those says nothing
     *  (iOS da59726). An indexer answer counts when every row since the game's start is in: the
     *  whole window paged in, or - while older pages are still failing - a fresh newest page over
     *  a stretch already paged that reaches back to the game's start (iOS 077d777). */
    private fun flagIsConfirmed(tournament: ChessTournament, game: ChessTournamentGame, flagAt: Long): Boolean {
        if (!arena.arenaHasIndexer) return true
        if (arena.arenaAnsweredAt(game.startedAt) >= flagAt + 1_500) return true
        return (chainReadAt["${tournament.id}|${game.id}"] ?: 0L) >= flagAt + CHAIN_FLAG_MARGIN_MS
    }

    /** One reduce at a time, off the main thread: the DAO flow's, and the one a join runs on the
     *  freshest rows before it picks a room. Rows that change while one runs get one more replay
     *  after it, of the rows as they stand then (the collector is conflated, iOS 4922804). */
    private val reduceMutex = Mutex()

    private fun reduce(rows: List<ChessArenaRow>) {
        val events = rows.mapNotNull { row ->
            if (row.deliveryStatus != "sent" || row.id.startsWith("pending_")) return@mapNotNull null
            val message = ChessTournamentCodec.decode(row.content) ?: return@mapNotNull null
            ChessArenaEvent(row.id, row.senderAddress, row.blockTimestamp, message)
        }
        val mineAddress = myAddress
        val localStamps = arena.locallyStampedTxIds.toSet()
        val reduced = ChessTournamentEngine.reduce(orderingLocalStamps(events, localStamps))
        val wallNow = System.currentTimeMillis()
        val gapSince = mineAddress?.let { earliestGap(events, reduced, it, wallNow) }
        // The rooms this player's join still carries this phone's clock in: where it stands
        // against the other joins is not the chain's word yet (iOS 077d777).
        val locallyStampedJoins = if (mineAddress == null || localStamps.isEmpty()) emptySet() else {
            events.filter { it.sender == mineAddress && it.message.a == "join" && it.txId in localStamps }
                .map { it.message.t }.toSet()
        }
        _tournaments.value = reduced
        // The lists, worked out once per arena change rather than on every read (iOS 32e6392).
        synchronized(listsLock) {
            unsettledIds = reduced.values
                .filter { it.status == ChessTournament.Status.OPEN || it.status == ChessTournament.Status.LIVE }
                .map { it.id }
        }
        refreshTimeState(force = true)
        if (gapSince != null && wallNow - lastGapRepageAt >= GAP_REPAGE_GAP_MS) {
            lastGapRepageAt = wallNow
            arena.repageArena(gapSince)
        }
        resolveNames(reduced.values.flatMap { it.players }.toSet() + listOfNotNull(mineAddress))
        recomputeLocalBoards()
        val me = myAddress
        // Another account by now: its joins are not the ones just read.
        val stampedJoins = if (me == mineAddress) locallyStampedJoins else emptySet()
        // The queued room is still waiting, but this player's seat in it has run out (or a join
        // that never showed is older than any seat): nothing races for that room any more. A fill
        // much later is not this join's race, and must not send a join nobody asked for
        // (iOS 077d777).
        val waiting = queuedPublicRoomId
        if (waiting != null && me != null && queuedAt > 0L &&
            (reduced[waiting]?.let { it.status == ChessTournament.Status.OPEN } ?: true)
        ) {
            // Only the seat this join took counts: an old one that ran out stays in `players`
            // until the next join drops it (a returning player's).
            val expiry = reduced[waiting]?.seatExpiry(me)
            val seatGone = if (expiry != null && expiry - ChessTournamentCodec.SEAT_TTL_MS >= queuedAt - JOIN_CLOCK_SLACK_MS) {
                expiry <= wallNow
            } else {
                wallNow - queuedAt > ChessTournamentCodec.SEAT_TTL_MS
            }
            if (seatGone) {
                queuedPublicRoomId = null
                queuedAt = 0L
                autoRejoinsLeft = 0
            }
        }
        // Asked to join a public room that filled first: queue into the next one. Bounded per tap
        // ([autoRejoinsLeft]), so a join can never turn into spending nobody asked for (iOS da59726).
        val queued = queuedPublicRoomId
        if (queued != null && me != null) {
            val room = reduced[queued]
            if (room != null && room.isFull && me !in room.players) {
                queuedPublicRoomId = null
                queuedAt = 0L
                if (autoRejoinsLeft > 0) {
                    autoRejoinsLeft -= 1
                    val wasDuel = ChessTournamentCodec.duelNumber(queued) != null
                    scope.launch { joinPublicRoom(duel = wasDuel, automaticAfter = queued) }
                } else {
                    showError(ROOM_FILLED_MESSAGE)
                }
            } else if (room != null && room.status != ChessTournament.Status.OPEN && me in room.players &&
                queued !in stampedJoins
            ) {
                // Settled: the room started with this player in it, and their join has the chain's
                // time. Until then a seat that rests on this phone's clock can still turn out to
                // have come after the last one (the indexer's block times reorder the race), and
                // the branch above must still be able to queue into the next room (iOS 077d777).
                queuedPublicRoomId = null
                queuedAt = 0L
                autoRejoinsLeft = 0
            }
        }
        // A move of ours that the chain now shows is no longer pending.
        val mine = myAddress
        _pendingMoveGames.update { pending -> pending.filter { key ->
            val (tid, gid) = key.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@filter true
            val game = reduced[tid]?.games?.get(gid) ?: return@filter true
            game.playerToMove == mine && !game.isOver
        }.toSet() }
        pendingMoveSentAt.keys.retainAll(_pendingMoveGames.value)
    }

    /** A game of [me]'s still undecided has a move from one of its players beyond the next ply: a
     *  row before it is missing on this phone. Returns the earliest such game's start (iOS da59726). */
    private fun earliestGap(events: List<ChessArenaEvent>, tournaments: Map<String, ChessTournament>, me: String, now: Long): Long? {
        var since: Long? = null
        for (event in events) {
            if (event.message.a != "move") continue
            val gameId = event.message.g ?: continue
            val ply = event.message.n ?: continue
            val game = tournaments[event.message.t]?.games?.get(gameId) ?: continue
            if (game.color(me) == null || game.color(event.sender) == null) continue
            if (ply <= game.moves.size + 1 || game.isDecided(now)) continue
            since = minOf(since ?: game.startedAt, game.startedAt)
        }
        return since
    }

    /**
     * Our own rows carry this phone's clock until the chain's block time reaches us
     * ([ChessArenaChannel.locallyStampedTxIds]). A clock ahead of the chain put our move after
     * the reply to it, a clock behind before the move it answers - either way a move out of turn,
     * ignored, and a board stuck until the chain's time came. Until then such a move sits between
     * the moves of its game around it by ply, whatever the local stamp says. This phone's view
     * only: every phone still reduces the chain's times once they are known (iOS 8d5f925).
     */
    private fun orderingLocalStamps(events: List<ChessArenaEvent>, localStamps: Set<String>): List<ChessArenaEvent> {
        if (localStamps.isEmpty()) return events
        return events.map { event ->
            val message = event.message
            val gameId = message.g
            val ply = message.n
            if (event.txId !in localStamps || message.a != "move" || gameId == null || ply == null) return@map event
            var lower: Long? = null
            var upper: Long? = null
            for (other in events) {
                if (other.txId == event.txId || other.message.t != message.t || other.message.g != gameId || other.message.a != "move") continue
                val otherPly = other.message.n ?: continue
                if (otherPly < ply) lower = maxOf(lower ?: other.blockTime, other.blockTime)
                if (otherPly > ply) upper = minOf(upper ?: other.blockTime, other.blockTime)
            }
            var time = event.blockTime
            upper?.let { time = minOf(time, it - 1) }
            lower?.let { time = maxOf(time, it + 1) }
            if (time == event.blockTime) event else event.copy(blockTime = time)
        }
    }

    /** Set while an unconfirmed-row check runs, so the next tick does not start another. */
    private val checkingUnconfirmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Own arena transactions the network never took ([UNCONFIRMED_ROW_MS]) stop counting on this
     * phone: the reducer drops them and the board is the chain's again - a dropped move is the
     * player's to play again, and a timeout claim the board was waiting on goes out after all.
     * Only once the arena's history is in, so a row the chain showed while the app was closed is
     * found there first (iOS 4922804).
     */
    private fun dropUnconfirmedRowsIfDue() {
        if (!_historyReady.value || !checkingUnconfirmed.compareAndSet(false, true)) return
        scope.launch {
            try {
                val dropped = runCatching {
                    arena.checkUnconfirmedServiceRows(
                        olderThanMs = UNCONFIRMED_ROW_MS, giveUpAfterMs = UNCONFIRMED_GIVE_UP_MS,
                    )
                }.onFailure { Log.w(TAG, "Unconfirmed arena row check failed", it) }.getOrDefault(emptySet())
                if (dropped.isEmpty()) return@launch
                // What each dropped row said: its own row is still in the store, marked failed.
                val messages = dropped.mapNotNull { txId ->
                    runCatching { arena.message(txId) }.getOrNull()
                        ?.let { ChessTournamentCodec.decode(it.content) }
                }
                // A join that never landed queues nothing: the room it named filling later is not
                // this player's race (iOS 077d777).
                val queued = queuedPublicRoomId
                if (queued != null && messages.any { it.a == "join" && it.t == queued }) {
                    queuedPublicRoomId = null
                    queuedAt = 0L
                    autoRejoinsLeft = 0
                }
                showError(
                    when {
                        messages.any { it.a == "move" } -> "Your move didn't reach the network. Play it again."
                        messages.any { it.a == "join" || it.a == "create" } -> JOIN_NOT_LANDED_MESSAGE
                        else -> "That didn't reach the network. Try again."
                    }
                )
            } finally {
                checkingUnconfirmed.set(false)
            }
        }
    }

    /** A move the reducer never applied (it came too late for the clock, say): the board stops
     *  saying "Sending your move…" (iOS 4922804). */
    private fun expirePendingMoves() {
        val now = nowMs
        for ((key, sentAt) in pendingMoveSentAt.entries.toList()) {
            if (now - sentAt <= PENDING_MOVE_TIMEOUT_MS) continue
            pendingMoveSentAt.remove(key)
            _pendingMoveGames.update { it - key }
            showError("Your move didn't count. Check the board.")
        }
    }

    // MARK: - Rooms

    /** The public tournament room taking players right now - see [currentRoomNumber]. Its id
     *  exists before anyone has joined it (the first join creates it), so the lobby can always
     *  show "Public tournament #N" with its seats. */
    fun currentPublicRoomId(all: Map<String, ChessTournament>): String =
        ChessTournamentCodec.publicId(currentRoomNumber(all, { ChessTournamentCodec.publicNumber(it) }, { ChessTournamentCodec.publicId(it) }))

    /** The public 1v1 room taking players right now. */
    fun currentDuelRoomId(all: Map<String, ChessTournament>): String =
        ChessTournamentCodec.duelId(currentRoomNumber(all, { ChessTournamentCodec.duelNumber(it) }, { ChessTournamentCodec.duelId(it) }))

    /**
     * The room to queue into: the lowest-numbered public room of that kind still waiting for
     * players; when none is, one past the highest room this phone knows. Every phone with the
     * same recent history lands on the same number - and it does not need the history back to
     * room 1, which is why the engine no longer requires the previous room to be full
     * (iOS d2ab780).
     *
     * Anyone can post a join naming any room, so this is the choice of a room to pick, never the
     * reducer's view of what rooms exist: only a room's canonical id counts (`duel-01` is not room
     * 1), nothing past [MAX_ROOM_NUMBER], and of the runs of numbers near each other only the
     * longest (the real queue), so a stray `duel-900000` can't move it (iOS da59726).
     */
    private fun currentRoomNumber(all: Map<String, ChessTournament>, numberOf: (String) -> Int?, idOf: (Int) -> String): Int {
        val rooms = ArrayList<Pair<Int, Boolean>>()
        for (room in all.values) {
            val number = numberOf(room.id) ?: continue
            if (number > MAX_ROOM_NUMBER || idOf(number) != room.id) continue
            rooms += number to (room.status == ChessTournament.Status.OPEN && !room.isFull)
        }
        rooms.sortBy { it.first }
        var best: List<Pair<Int, Boolean>> = emptyList()
        var start = 0
        for (index in rooms.indices) {
            if (index + 1 != rooms.size && rooms[index + 1].first - rooms[index].first <= MAX_ROOM_GAP) continue
            val run = rooms.subList(start, index + 1)
            if (run.size > best.size) best = run
            start = index + 1
        }
        return best.firstOrNull { it.second }?.first ?: ((best.lastOrNull()?.first ?: 0) + 1)
    }

    /**
     * True once the arena's history has come back from the indexer (or a few seconds have
     * passed). A join before that would pick a room from an empty view - room 1 - which
     * everyone else finished long ago.
     */
    private val _historyReady = MutableStateFlow(false)
    val historyReady: StateFlow<Boolean> = _historyReady.asStateFlow()

    /** iOS's `historyReady` didSet: busy or not turns on it (iOS 32e6392). */
    private fun setHistoryReady(ready: Boolean) {
        synchronized(listsLock) {
            if (_historyReady.value == ready) return
            _historyReady.value = ready
            _isBusyOnline.value = computeIsBusyOnline(_myActiveTournament.value, _tournaments.value)
        }
    }

    // MARK: - Lists and the clock

    /**
     * What the time decides on the screens, at one instant: which undecided games have run out of
     * clock, who is still seated in each waiting room, which of this player's games have their
     * clock at zero, and which of their flags are recent enough to keep them busy. The lists below
     * and every chess screen are worked out again when this changes (or the arena does) - never on
     * a tick that changed none of it (iOS 32e6392).
     */
    private data class TimeState(
        val decided: Set<String> = emptySet(),
        val seated: Map<String, List<String>> = emptyMap(),
        val myClockOut: Set<String> = emptySet(),
        val recentFlags: Set<String> = emptySet(),
    )

    /** Guards [timeState], [unsettledIds] and the lists' recompute: the tick, the reducer and an
     *  account switch run on different threads, where iOS has them take turns on the main actor. */
    private val listsLock = Any()
    private var timeState = TimeState()
    /** The rooms the time can still change (waiting or live), so a tick looks at those alone. */
    private var unsettledIds: List<String> = emptyList()

    private fun computeTimeState(): TimeState {
        val all = _tournaments.value
        val me = myAddress
        val now = nowMs
        val decided = HashSet<String>()
        val seated = HashMap<String, List<String>>()
        val myClockOut = HashSet<String>()
        val recentFlags = HashSet<String>()
        for (id in unsettledIds) {
            val tournament = all[id] ?: continue
            when (tournament.status) {
                ChessTournament.Status.OPEN -> seated[id] = tournament.seatedPlayers(now)
                ChessTournament.Status.LIVE -> for (game in tournament.games.values) {
                    if (game.isOver) continue
                    val key = "$id|${game.id}"
                    if (game.isDecided(now)) decided += key
                    if (me == null) continue
                    val mine = game.color(me) ?: continue
                    if (game.remainingMs(mine, now) <= 0L) myClockOut += key
                    val flagAt = game.flagAt
                    if (flagAt != null && now - flagAt < AUTO_CLAIM_WINDOW_MS) recentFlags += key
                }
                else -> Unit
            }
        }
        return TimeState(decided, seated, myClockOut, recentFlags)
    }

    /** Works the lists out again when the arena changed ([force]) or the time has changed what
     *  they hold; in the second case the screens are told too ([timeRevision]). */
    private fun refreshTimeState(force: Boolean = false) {
        synchronized(listsLock) {
            val state = computeTimeState()
            val changed = state != timeState
            if (!force && !changed) return
            timeState = state
            recomputeLists()
            if (changed && !force) _timeRevision.update { it + 1 }
        }
    }

    private fun recomputeLists() {
        val all = _tournaments.value
        val me = myAddress
        val now = nowMs
        _liveTournaments.value = all.values.filter { it.isInPlay(now) }.sortedByDescending { it.startedAt ?: 0 }
        _finishedTournaments.value = all.values
            .filter {
                it.status == ChessTournament.Status.FINISHED ||
                    (it.status == ChessTournament.Status.LIVE && !it.isInPlay(now))
            }
            .sortedByDescending { it.startedAt ?: 0 }
        if (me != null) {
            val mine = all.values
                .filter {
                    !it.isPublic && me in it.players &&
                        (it.status == ChessTournament.Status.OPEN || it.isInPlay(now) || hasUnclaimedWin(it))
                }
                .sortedByDescending { it.createdAt }
            _myPrivateDuels.value = mine.filter { it.isDuel }
            _myPrivateTournaments.value = mine.filter { !it.isDuel }
        } else {
            _myPrivateDuels.value = emptyList()
            _myPrivateTournaments.value = emptyList()
        }
        val active = computeMyActiveTournament(all)
        if (active != _myActiveTournament.value) _myActiveTournament.value = active
        val busy = computeIsBusyOnline(active, all)
        if (busy != _isBusyOnline.value) _isBusyOnline.value = busy
    }

    /** Private 1v1s this player is in, still open or in play. */
    private val _myPrivateDuels = MutableStateFlow<List<ChessTournament>>(emptyList())
    val myPrivateDuels: StateFlow<List<ChessTournament>> = _myPrivateDuels.asStateFlow()

    /** Private tournaments this player is in, still open or in play. */
    private val _myPrivateTournaments = MutableStateFlow<List<ChessTournament>>(emptyList())
    val myPrivateTournaments: StateFlow<List<ChessTournament>> = _myPrivateTournaments.asStateFlow()

    /** A game here this player has won on the clock, with no claim on chain. Finished lists public
     *  games only, so this keeps a private one reachable, for its Claim win (iOS da59726). */
    fun hasUnclaimedWin(tournament: ChessTournament): Boolean {
        val me = myAddress ?: return false
        if (tournament.status != ChessTournament.Status.LIVE) return false
        val now = nowMs
        return tournament.games.values.any { !it.isOver && it.decidedWinner(now) == me }
    }

    /** Rooms with a game still being played. A live room whose clocks have all run out with no
     *  claim posted is not one of them (see [ChessTournamentGame.flaggedSide]). */
    private val _liveTournaments = MutableStateFlow<List<ChessTournament>>(emptyList())
    val liveTournaments: StateFlow<List<ChessTournament>> = _liveTournaments.asStateFlow()

    /** Finished on the chain, or finished on the clock with the claim still to come. */
    private val _finishedTournaments = MutableStateFlow<List<ChessTournament>>(emptyList())
    val finishedTournaments: StateFlow<List<ChessTournament>> = _finishedTournaments.asStateFlow()

    /** The tournament this player is in that is not over, if any. A waiting seat that has expired
     *  does not count: the player is free to join elsewhere. Worked out when the arena or the time
     *  changes it ([refreshTimeState]); the actions below ask afresh
     *  ([computeMyActiveTournament]), to the millisecond (iOS 32e6392). */
    private val _myActiveTournament = MutableStateFlow<ChessTournament?>(null)
    val myActiveTournament: StateFlow<ChessTournament?> = _myActiveTournament.asStateFlow()

    private fun computeMyActiveTournament(all: Map<String, ChessTournament>): ChessTournament? {
        val me = myAddress ?: return null
        val now = nowMs
        return all.values
            .filter {
                // Out of a live room once you have lost a game in it, on the board or on the
                // clock with the claim still to come - otherwise a winner who had left kept the
                // loser "busy" forever and unable to join another room (iOS a94f0e8).
                it.isStillPlaying(me, now) ||
                    (it.status == ChessTournament.Status.OPEN && it.isSeated(me, now))
            }
            .maxByOrNull { it.createdAt }
    }

    /**
     * Too busy online for a challenge link to open a bot screen: the rooms are still loading (a
     * game in play may not be known yet), the player is seated or playing, or they won a game on
     * the clock lately with the claim not on chain yet - they're still on the board to claim it. A
     * game they lost on time keeps nobody busy: there's nothing left for them there (iOS 077d777).
     * Cached like the lists (iOS 32e6392).
     */
    private val _isBusyOnline = MutableStateFlow(true)
    val isBusyOnline: StateFlow<Boolean> = _isBusyOnline.asStateFlow()

    /** [isBusyOnline] worked out afresh from [all]. Kept for `ChessMainShell`, which should
     *  collect [isBusyOnline] instead (iOS 32e6392). */
    fun isBusyOnline(all: Map<String, ChessTournament>): Boolean =
        computeIsBusyOnline(computeMyActiveTournament(all), all)

    private fun computeIsBusyOnline(active: ChessTournament?, all: Map<String, ChessTournament>): Boolean {
        if (!_historyReady.value) return true
        if (active != null) return true
        val me = myAddress ?: return false
        val now = nowMs
        return all.values.any { tournament ->
            tournament.status == ChessTournament.Status.LIVE && tournament.games.values.any { game ->
                val flagAt = game.flagAt
                !game.isOver && flagAt != null && game.decidedWinner(now) == me && now - flagAt < AUTO_CLAIM_WINDOW_MS
            }
        }
    }

    // MARK: - Actions (each one a broadcast transaction)

    /** The room this player asked to join and is waiting to appear in. Kept until the room has
     *  started with them in it and their join carries the chain's time ([reduce]), so a seat won
     *  on this phone's clock and lost on the chain's still moves on to the next room (iOS 077d777). */
    @Volatile
    private var queuedPublicRoomId: String? = null

    /** When that join was sent (wall ms). */
    @Volatile
    private var queuedAt = 0L

    /** How many more rooms the app may try by itself before the player taps Join again. */
    @Volatile
    private var autoRejoinsLeft = 0

    /** Whether the rooms on this phone are the indexer's as of just now, with any history still
     *  owed paged in. One failed request isn't an outage: it's tried again, and an answer from the
     *  last few seconds stands (iOS da59726). */
    private suspend fun arenaViewIsConfirmed(): Boolean {
        val channel = ChessTournamentCodec.ARENA_CHANNEL
        if (!arena.indexerConfigured(ChessTournamentCodec.ARENA_CHANNEL)) return true
        var confirmed = arena.refreshFromIndexerNow(channel)
        if (!confirmed && !arena.arenaIndexerDown.value) {
            delay(800)
            confirmed = arena.refreshFromIndexerNow(channel)
        }
        if (!confirmed && System.currentTimeMillis() - arena.arenaIndexerAnsweredAt >= RECENT_ANSWER_MS) return false
        // Past one poll interval: paging is done by the room's 8 s poll.
        val deadline = System.currentTimeMillis() + ARENA_BACKFILL_WAIT_MS
        while (!arena.arenaBackfillDone && System.currentTimeMillis() < deadline) delay(250)
        return arena.arenaBackfillDone
    }

    /** Joins the public room taking players now. If that room fills before this join lands
     *  (someone else got the last seat), [reduce] notices and joins the next room. */
    suspend fun joinPublicQueue(): String? = withContext(NonCancellable) { joinPublicRoom(duel = false) }

    /** Joins the public 1v1 room taking players now; same race handling as the tournaments. */
    suspend fun joinPublicDuelQueue(): String? = withContext(NonCancellable) { joinPublicRoom(duel = true) }

    /**
     * The seat that ran out is still in `players` until the next join drops it (the engine judges
     * that at the join's block time) - so "already in" means seated NOW, never the stale list, or
     * a returning player's tap would do nothing at all. Every refusal says why (iOS b552d7d).
     *
     * Returns the room joined (or the one the player is already in), so the screen can open its
     * waiting room - and null when nothing was sent. [automaticAfter]: this is the app moving on
     * from a room that filled first, not a tap (iOS da59726).
     */
    private suspend fun joinPublicRoom(duel: Boolean, automaticAfter: String? = null): String? {
        val me = myAddress ?: return null
        if (!_historyReady.value) {
            showError("Still loading the rooms - try again in a moment.")
            return null
        }
        if (automaticAfter == null) autoRejoinsLeft = MAX_AUTO_REJOINS
        // The freshest shared view first: whatever the indexer holds this second is what every
        // other phone is choosing from, and a phone choosing off its own stale view offers a room
        // the others have moved past (iOS 29bf054). The DAO flow delivers the merge a beat later,
        // so the rows are reduced here and now rather than read off the old state.
        // Without that view the room number and its seats are only what this phone last saw:
        // nothing is sent. (No indexer configured at all: the block stream is all there is.)
        val confirmed = runCatching { arenaViewIsConfirmed() }
            .onFailure { Log.w(TAG, "Arena refresh before joining failed", it) }
            .getOrDefault(false)
        if (!confirmed) {
            showError(ROOMS_UNCONFIRMED_MESSAGE)
            return null
        }
        runCatching {
            withContext(Dispatchers.Default) {
                reduceMutex.withLock {
                    reduce(arena.messages(ChessTournamentCodec.ARENA_CHANNEL).first())
                }
            }
        }.onFailure { Log.w(TAG, "Arena reduce before joining failed", it) }
        // The account may have changed while the rooms loaded.
        if (myAddress != me) return null
        val id = if (duel) currentDuelRoomId(_tournaments.value) else currentPublicRoomId(_tournaments.value)
        // Never a join the reducer would ignore: it can't take a seat, and it costs a fee.
        val targetRoom = _tournaments.value[id]
        if (id == automaticAfter || (targetRoom != null && targetRoom.status != ChessTournament.Status.OPEN)) {
            showError(ROOM_FILLED_MESSAGE)
            return null
        }
        val busy = computeMyActiveTournament(_tournaments.value)
        if (busy != null) {
            showError(busyMessage(busy))
            return null
        }
        // Already seated here: no transaction, but the room is still where this player belongs.
        if (_tournaments.value[id]?.isSeated(me, nowMs) == true) return id
        queuedPublicRoomId = id
        queuedAt = System.currentTimeMillis()
        if (send(ChessTournamentCodec.join(id))) return id
        // A join that did not go out queues nothing: the next room must not be joined for it -
        // unless the queue has already moved on to another room while it was being sent
        // (iOS 4922804).
        if (queuedPublicRoomId == id) {
            queuedPublicRoomId = null
            queuedAt = 0L
        }
        return null
    }

    /** "You're already waiting in Public 1v1 #4." / "You're still playing in …", one whole
     *  sentence each, the room's name as people read it (iOS 077d777). */
    private fun busyMessage(busy: ChessTournament): String =
        if (busy.status == ChessTournament.Status.OPEN) {
            ChessText.format("You're already waiting in %s.", busy.displayName)
        } else {
            ChessText.format("You're still playing in %s.", busy.displayName)
        }

    /** A private 1v1 for a friend: no creator code, an eight-character code to share. */
    suspend fun createPrivateDuel(): String? {
        val id = ChessTournamentCodec.newPrivateId()
        if (!send(ChessTournamentCodec.createDuel(id, ChessTournamentCodec.PRIVATE_DUEL_NAME))) return null
        return id
    }

    /** A private tournament for friends. Needs the creator code; returns null (with a message)
     *  when it is wrong, without sending anything. */
    suspend fun createPrivateTournament(code: String): String? {
        if (!ChessTournamentCodec.isValidCreateKey(ChessTournamentCodec.createKey(code, "check"), "check")) {
            showError("That creator code is not right.")
            return null
        }
        val id = ChessTournamentCodec.newPrivateId()
        if (!send(ChessTournamentCodec.create(id, ChessTournamentCodec.PRIVATE_TOURNAMENT_NAME, code))) return null
        return id
    }

    /** Joins a friend's private tournament or 1v1 by its code (the id). */
    suspend fun joinPrivate(rawCode: String): Boolean {
        val id = rawCode.trim().lowercase()
        val tournament = _tournaments.value[id]
        if (tournament == null || tournament.isPublic) {
            showError(if (arena.arenaIndexerDown.value) {
                "The chess server is unreachable, so that code can't be looked up. Try again when it's back."
            } else {
                "No open tournament with that code. Codes are eight characters; the tournament must exist and still have seats."
            })
            return false
        }
        if (tournament.status != ChessTournament.Status.OPEN) {
            showError("That tournament has already started.")
            return false
        }
        return join(tournament)
    }

    /** True when the player is seated or the join went out; false (with [lastError]) when
     *  nothing was sent. */
    suspend fun join(tournament: ChessTournament): Boolean {
        val me = myAddress ?: return false
        if (tournament.status != ChessTournament.Status.OPEN) return false
        if (tournament.isSeated(me, nowMs)) return true
        val busy = computeMyActiveTournament(_tournaments.value)
        if (busy != null && busy.id != tournament.id) {
            showError(busyMessage(busy))
            return false
        }
        if (tournament.isPublic && arena.arenaIndexerDown.value) {
            showError(ROOMS_UNCONFIRMED_MESSAGE)
            return false
        }
        return send(ChessTournamentCodec.join(tournament.id))
    }

    /** Gives the seat back while the room is still waiting (one transaction). True when the
     *  leave went out (or there was no seat to give back). */
    suspend fun leave(tournament: ChessTournament): Boolean {
        val me = myAddress ?: return true
        if (tournament.status != ChessTournament.Status.OPEN || !tournament.isSeated(me, nowMs)) return true
        queuedPublicRoomId = null
        queuedAt = 0L
        autoRejoinsLeft = 0
        return send(ChessTournamentCodec.leave(tournament.id))
    }

    suspend fun cancel(tournament: ChessTournament) {
        if (tournament.creator != myAddress || tournament.status != ChessTournament.Status.OPEN) return
        send(ChessTournamentCodec.cancel(tournament.id))
    }

    /** Plays a move if it is ours to make and legal; the board shows it as pending until the
     *  chain returns it. Finishes even when the board is left mid-send, so its pending state is
     *  always settled (an iOS Task outlives its view the same way). */
    suspend fun play(move: ChessMove, tournament: ChessTournament, game: ChessTournamentGame) =
        withContext(NonCancellable) { playNow(move, tournament, game) }

    private suspend fun playNow(move: ChessMove, tournament: ChessTournament, game: ChessTournamentGame) {
        val me = myAddress ?: return
        val key = "${tournament.id}|${game.id}"
        // Not once the clock has decided it: a move after the flag is void on chain (iOS 4922804).
        // For the player's own move that's the moment their clock reads zero, by the wall clock
        // this instant - not [ChessTournamentGame.flaggedSide]'s three-second margin, which is
        // there so a move in flight isn't beaten to the flag, never to let one more go out after
        // it (iOS 077d777).
        val wallNow = System.currentTimeMillis()
        val mine = game.color(me) ?: return
        if (game.playerToMove != me || game.isDecided(nowMs) || game.remainingMs(mine, maxOf(nowMs, wallNow)) <= 0 ||
            key in _pendingMoveGames.value || !ChessEngine.isLegal(move, game.board)) return
        val normalized = ChessEngine.normalizingPromotion(move, game.board)
        _pendingMoveGames.update { it + key }
        // A retry (the last move's change not mined yet) waits a second or more: never one that
        // would go out after the player's own clock ran out.
        val sent = send(
            ChessTournamentCodec.move(
                id = tournament.id, game = game.id, ply = game.moves.size + 1,
                from = normalized.from.algebraic, to = normalized.to.algebraic,
                promotion = normalized.promotion?.promotionLetter,
            ),
            stillWanted = {
                val current = _tournaments.value[tournament.id]?.games?.get(game.id) ?: game
                current.remainingMs(mine, System.currentTimeMillis()) > 0
            },
        )
        if (myAddress != me) return
        if (sent) {
            // Timed from the moment it went out, if the chain has not shown it already.
            if (key in _pendingMoveGames.value) pendingMoveSentAt[key] = System.currentTimeMillis()
        } else {
            _pendingMoveGames.update { it - key }
            pendingMoveSentAt.remove(key)
        }
    }

    /** True when the resignation went out; false (with [lastError]) when it did not, and the
     *  game goes on - the board must keep the player (iOS 4922804). */
    suspend fun resign(tournament: ChessTournament, game: ChessTournamentGame): Boolean {
        val me = myAddress ?: return false
        if (game.color(me) == null || game.isOver) return false
        return send(ChessTournamentCodec.resign(tournament.id, game.id))
    }

    // MARK: - Names

    /** Addresses whose KNS domain was asked for this session. */
    private val resolvedNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Names in the arena follow the app's rule - the contact's name, then their KNS domain, then
     * the shortened address - and the domain part needs a lookup. Asked once per address per
     * session; [knsNames] then answers, and the screens re-render when it lands (iOS 30d0cca).
     */
    fun resolveNames(addresses: Set<String>) {
        val fresh = addresses.filter { it.isNotEmpty() && resolvedNames.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            for (address in fresh) {
                val domain = runCatching { knsService.identityName(address) }.getOrNull()
                if (!domain.isNullOrBlank()) _knsNames.update { it + (address to domain) }
            }
        }
    }

    // MARK: - Leaderboards

    /** One period's board as the indexer served it: the window it counted, and its rows. */
    data class IndexerBoard(val since: Long, val rows: List<ChessLeaderboardRow>)

    private val boardsLock = Any()
    private var localBoards: Map<ChessLeaderboardPeriod, List<ChessLeaderboardRow>> = emptyMap()
    private val indexerBoards = HashMap<ChessLeaderboardPeriod, IndexerBoard>()
    @Volatile
    private var boardsMonthStart = 0L
    private var lastIndexerLeaderboardFetch: Long? = null
    private var indexerLeaderboardJob: Job? = null

    private fun recomputeLocalBoards() {
        val nowMs = System.currentTimeMillis()
        val all = _tournaments.value.values
        val boards = ChessLeaderboardPeriod.entries.associateWith { period ->
            ChessTournamentEngine.leaderboard(all, since = ChessTournamentEngine.periodSinceMs(period, nowMs))
        }
        synchronized(boardsLock) {
            boardsMonthStart = ChessTournamentEngine.utcMonthStartMs(nowMs)
            localBoards = boards
            publishBoards()
        }
    }

    /**
     * Per player, each figure is the larger of the two sources: both count the same window, the
     * indexer has the older history and this phone the newest blocks, so neither can be above
     * the true count. An indexer board for another window (an old indexer without `period`, or a
     * month that has since rolled over) is not used. Call with [boardsLock] held.
     */
    private fun publishBoards() {
        val nowMs = System.currentTimeMillis()
        fun merged(period: ChessLeaderboardPeriod) = mergedBoard(
            localBoards[period].orEmpty(),
            usableIndexerRows(period, indexerBoards[period], nowMs),
        )
        val all = merged(ChessLeaderboardPeriod.ALL_TIME)
        val month = merged(ChessLeaderboardPeriod.MONTH)
        if (all != _leaderboard.value) _leaderboard.value = all
        if (month != _monthlyLeaderboard.value) _monthlyLeaderboard.value = month
    }

    /**
     * GET /chess/leaderboard?period=month|all on the arena's indexer (ONLINE_CHESS.md §6). At
     * most every 20 s unless forced; any failure leaves the phone's own boards in place.
     */
    fun refreshIndexerLeaderboards(force: Boolean = false) {
        synchronized(boardsLock) {
            val last = lastIndexerLeaderboardFetch
            if (!force && last != null && System.currentTimeMillis() - last < INDEXER_LEADERBOARD_INTERVAL_MS) return
            lastIndexerLeaderboardFetch = System.currentTimeMillis()
            indexerLeaderboardJob?.cancel()
            indexerLeaderboardJob = scope.launch {
                for (period in ChessLeaderboardPeriod.entries) {
                    val response = indexerClient.fetchChessLeaderboard(ChessTournamentCodec.ARENA_CHANNEL, period.raw)
                        ?: continue
                    val board = acceptedIndexerBoard(period, response) ?: continue
                    if (!isActive) return@launch
                    synchronized(boardsLock) {
                        indexerBoards[period] = board
                        publishBoards()
                    }
                    resolveNames(board.rows.take(100).map { it.address }.toSet())
                }
            }
        }
    }

    /** Pull to refresh: both boards from the indexer now, past the 20 s throttle, returning once
     *  they have landed (or failed), so the spinner stays up for as long as the fetch
     *  (iOS 32e6392). */
    suspend fun refreshIndexerLeaderboardsNow() {
        refreshIndexerLeaderboards(force = true)
        synchronized(boardsLock) { indexerLeaderboardJob }?.join()
    }

    // MARK: - Fees

    /**
     * What sending [message] costs right now, as "0.0017 KAS", for a button label. An arena
     * message is a fixed-size payload with one input, like every arena send, so this is the same
     * local calculation the composers use - no network round trip (iOS 3076f66).
     */
    fun feeText(message: ChessTournamentMessage): String? = runCatching {
        val payload = com.kachat.app.util.MessageProtocol.buildBcastPayload(
            ChessTournamentCodec.ARENA_CHANNEL, ChessTournamentCodec.encode(message),
        )
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = 1, outputScriptLens = listOf(34), payloadSize = payload.size,
        )
        val sompi = com.kachat.app.util.KaspaMass.calculateFee(
            mass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toLong(),
        )
        // Four decimals: "0.0017 KAS" reads at a glance; the exact sompi is in the transaction.
        "%.4f %s".format(sompi / 100_000_000.0, KaspaUnit.symbol)
    }.getOrNull()

    /** The join button's label: "Join (Fee: 0.0017 KAS)", one whole sentence (iOS 077d777). */
    fun joinLabel(roomId: String): String {
        val fee = feeText(ChessTournamentCodec.join(roomId)) ?: return ChessText.string("Join")
        return ChessText.format("Join (Fee: %s)", fee)
    }

    /** The opponent flagged in one of our games, lately: post the claim. Runs on every tick; a
     *  claim that has not taken after [CLAIM_RETRY_MS] (voided, or not sent) goes again. */
    private fun claimTimeoutsIfDue() {
        val me = myAddress ?: return
        val now = nowMs
        val localStamps = arena.locallyStampedTxIds
        for (tournament in _tournaments.value.values) {
            if (tournament.status != ChessTournament.Status.LIVE) continue
            for (game in tournament.games.values) {
                if (game.isOver) continue
                val mine = game.color(me) ?: continue
                if (game.sideToMove == mine) continue
                val flagAt = game.flagAt ?: continue
                // A second's margin past zero, so the claim's block time is safely after; and only
                // a recent flag - an old abandoned game is left to the board's Claim win.
                if (now - 1_500 < flagAt || now - flagAt >= AUTO_CLAIM_WINDOW_MS) continue
                // Not off a board that may be behind the chain (iOS da59726).
                if (!flagIsConfirmed(tournament, game, flagAt)) continue
                // Our own last move still carries this phone's clock: the opponent's clock started
                // at an instant the chain has not confirmed yet - wait for it.
                val last = game.moves.lastOrNull()
                if (last != null && last.txId in localStamps) continue
                postClaim(tournament, game)
            }
        }
    }

    /** The board's Claim win, for a game the opponent has lost on time with no claim on chain yet -
     *  the way off the board for its winner that is not Resign (iOS 8d5f925). Repeated taps are one
     *  claim: nothing while one is being sent or within a few seconds of the last (iOS 4922804). */
    fun claimWin(tournament: ChessTournament, game: ChessTournamentGame) {
        val me = myAddress ?: return
        if (game.decidedWinner(nowMs) != me || game.isOver) return
        val at = claimedGames["${tournament.id}|${game.id}"]
        if (at != null && nowMs - at < CLAIM_TAP_GAP_MS) return
        if (arena.arenaIndexerDown.value && !flagIsConfirmed(tournament, game, game.flagAt ?: nowMs)) {
            showError("The chess server is unreachable and the network hasn't confirmed this yet. Try again in a moment.")
            return
        }
        postClaim(tournament, game, force = true)
    }

    private fun postClaim(tournament: ChessTournament, game: ChessTournamentGame, force: Boolean = false) {
        val key = "${tournament.id}|${game.id}"
        if (key in _claimsInFlight.value) return
        val now = nowMs
        var autoClaim: AutoClaim? = null
        if (!force) {
            val at = claimedGames[key]
            if (at != null && now - at < CLAIM_RETRY_MS) return
            val flagAt = game.flagAt ?: 0L
            val record = autoClaims[key]?.takeIf { it.flagAt == flagAt } ?: AutoClaim(flagAt)
            if (record.tries >= MAX_AUTO_CLAIMS) return
            // A claim that didn't take: read the arena again before paying for another. The next
            // tick waits on [flagIsConfirmed] until that's done (iOS da59726).
            if (record.tries > 0 && !record.repaged) {
                autoClaims[key] = record.copy(repaged = true)
                arena.repageArena(game.startedAt)
                return
            }
            autoClaim = record
        }
        // A tap comes in on the main thread and the clock's tick on another, where iOS has them
        // take turns on the main actor: whoever adds the key is the one that pays.
        if (key in _claimsInFlight.getAndUpdate { it + key }) return
        autoClaim?.let { autoClaims[key] = it.copy(tries = it.tries + 1, repaged = false) }
        claimedGames[key] = now
        scope.launch {
            try {
                send(ChessTournamentCodec.claim(tournament.id, game.id))
            } finally {
                _claimsInFlight.update { it - key }
            }
        }
    }

    fun isClaiming(tournament: ChessTournament, game: ChessTournamentGame, inFlight: Set<String> = _claimsInFlight.value): Boolean =
        "${tournament.id}|${game.id}" in inFlight

    /**
     * One broadcast transaction, with the retries a fast sequence of sends needs: the change of
     * the previous transaction is not spendable until it is mined, so a move sent within a second
     * of the last one can hit "no spendable UTXO" - the same retry iOS uses. Only that kind of
     * error is retried ([isRetryableSendError]): no KAS on the chatting address fails at once with
     * its message (iOS 8d5f925).
     *
     * Every attempt is signed by the account that asked (`expectedSender`): one that switched
     * meanwhile - during a retry's wait, or before the send went out - sends nothing more.
     * [stillWanted]: asked before every attempt; false sends nothing more (iOS 077d777).
     */
    private suspend fun send(message: ChessTournamentMessage, stillWanted: () -> Boolean = { true }): Boolean =
        withContext(NonCancellable) { sendNow(message, stillWanted) }

    private suspend fun sendNow(message: ChessTournamentMessage, stillWanted: () -> Boolean): Boolean {
        val content = ChessTournamentCodec.encode(message)
        val sender = myAddress ?: return false
        var attempt = 0
        while (true) {
            // A switched account: nothing more goes out for the old one.
            if (myAddress != sender) return false
            if (!stillWanted()) {
                showError("Your clock ran out before the move could be sent.")
                return false
            }
            try {
                arena.sendPublicChat(ChessTournamentCodec.ARENA_CHANNEL, content, deferNoInputRetry = false, expectedSender = sender)
                if (myAddress == sender) _lastError.value = null
                return true
            } catch (e: SendOutcomeUnknownException) {
                // A node may hold it: the move's ply stays taken, the join or claim counts as out,
                // and the unconfirmed-row check settles it - the chain shows it, or it is dropped
                // ([dropUnconfirmedRowsIfDue]). Never a blind resend (iOS 4922804).
                Log.w(TAG, "Send outcome unknown", e)
                if (myAddress == sender) _lastError.value = null
                return true
            } catch (e: Exception) {
                // [SenderChangedException] included: nothing went out, and the check below ends
                // it for the old account.
                attempt += 1
                if (myAddress != sender) return false
                if (attempt > 5 || !isRetryableSendError(e)) {
                    showError(friendlySendErrorMessage(e))
                    Log.w(TAG, "Send failed", e)
                    return false
                }
                delay(1_200)
            }
        }
    }

    // Last in the class: Kotlin runs initializers in order, and these collectors reduce on
    // another thread at once, so every field they touch is set before they start.
    init {
        // The arena's reducer runs while a chess screen is up ([acquire] starts it, [release]
        // stops it - [startReducing]): its read is the whole arena, and Room re-runs it on every
        // write to the Public Chats table, chess closed or not (audit M8).
        // A queued join, a pending move or a claim belongs to the account that made it: after a
        // log out or a switch they must never be sent from the next one (iOS 8d5f925).
        scope.launch {
            walletManager.activeAddressFlow.collect { address -> accountChanged(address) }
        }
        // The chess server came back: the boards too, without waiting for a pull (iOS da59726).
        scope.launch {
            arena.arenaIndexerDown.drop(1).collect { down ->
                if (!down) refreshIndexerLeaderboards(force = true)
            }
        }
        // Back from the background: catch the clock and the fast poll up (iOS 32e6392).
        scope.launch {
            notificationHelper.appForegroundFlow.drop(1).collect { foreground ->
                if (foreground) resumeAfterBackground()
            }
        }
    }

    companion object {
        private const val TAG = "ChessTournament"
        private const val CLAIM_RETRY_MS = 30_000L
        private const val MAX_AUTO_CLAIMS = 4
        private const val GAP_REPAGE_GAP_MS = 30_000L
        private const val CHAIN_READ_INTERVAL_MS = 10_000L
        /** The REST API shows a move a few seconds after it lands: a read this long past the flag
         *  has seen any move made before it (iOS da59726). */
        private const val CHAIN_FLAG_MARGIN_MS = 5_000L
        private const val MAX_ROOM_NUMBER = 1_000_000_000
        private const val MAX_ROOM_GAP = 1_000
        private const val MAX_AUTO_REJOINS = 2
        private const val RECENT_ANSWER_MS = 15_000L
        private const val ARENA_BACKFILL_WAIT_MS = 12_000L
        private const val ROOMS_UNCONFIRMED_MESSAGE = "The chess server is unreachable, so open rooms can't be confirmed. Try again when it's back."
        private const val ROOM_FILLED_MESSAGE = "That room filled first. Tap Join to try the next one."
        /** The player's own join (or create) never reached the network: the toast after it was
         *  dropped, and the waiting room's note when it closes for that (iOS 077d777). */
        const val JOIN_NOT_LANDED_MESSAGE = "Your join didn't reach the network. Join again whenever you like."
        /** How long the error toast stays up ([showError], iOS `errorShownNanos`). */
        private const val ERROR_SHOWN_MS = 3_000L
        /** How far a join's block time may sit before the phone's own send time (clocks differ). */
        private const val JOIN_CLOCK_SLACK_MS = 60_000L
        /** The app claims by itself only for a flag this recent: an old game both players walked
         *  away from is not paid for on every launch. The board's Claim win covers the rest. */
        private const val AUTO_CLAIM_WINDOW_MS = 30L * 60_000
        private const val PENDING_MOVE_TIMEOUT_MS = 25_000L
        /** Taps on Claim win closer together than this are one claim (iOS 4922804). */
        private const val CLAIM_TAP_GAP_MS = 5_000L
        /** An own arena transaction the chain has not shown this long after it was sent is looked
         *  up, and dropped when the network has never seen it
         *  ([ChessArenaChannel.checkUnconfirmedServiceRows]). Blocks come ten a second and the
         *  indexer is polled every 2-8 s, so a transaction that landed is normally back in well
         *  under 10 s; 60 s also covers a slow indexer or an orphan waiting on its parent, and
         *  still leaves most of a five-minute clock to play the move again. One the network cannot
         *  be asked about is given up after five minutes (iOS 4922804). */
        private const val UNCONFIRMED_ROW_MS = 60_000L
        private const val UNCONFIRMED_GIVE_UP_MS = 5L * 60_000

        /** What a chess send says when the chatting address has no coins at all - not the "wait"
         *  of a change output still being mined, which retries. */
        const val NOT_ENOUGH_KAS_MESSAGE = "Not enough KAS on your Chatting Address. Add some and try again."
        private const val STILL_CONFIRMING_MESSAGE = "The network is still confirming your previous send. Please try again in a few seconds."

        /** Worth trying again in a moment: the coins are there but not spendable yet (the change
         *  of the last send), or the node rejected the transaction as an orphan or already spent -
         *  none of which reached the chain. No funds is not retried blindly (iOS 8d5f925). */
        fun isRetryableSendError(error: Throwable): Boolean {
            val text = error.message.orEmpty().lowercase()
            if (text.contains("insufficient funds") || text.contains("no utxos found")) return false
            return text.contains("orphan") || text.contains("already spent") || text.contains("double spend") ||
                text.contains("no confirmed") || text.contains("not spendable") || text.contains("still confirming") ||
                text.contains("no spendable") || text.contains("no utxos available")
        }

        fun friendlySendErrorMessage(error: Throwable): String {
            val text = error.message.orEmpty().lowercase()
            return when {
                text.contains("insufficient funds") || text.contains("no utxos found") -> NOT_ENOUGH_KAS_MESSAGE
                text.contains("orphan") || text.contains("already spent") || text.contains("double spend") -> STILL_CONFIRMING_MESSAGE
                // iOS PublicChatService: `UserFacingError.message(for:)`, its own fallback
                else -> UserFacingError.message(error, "Something went wrong. Please try again.")
            }
        }

        /** How often the arena's history is re-read while a chess screen is up - the same
         *  cadence a public room's own indexer poll uses. */
        private const val ARENA_BACKFILL_INTERVAL_MS = 8_000L

        /** How long a join waits for that history before going ahead anyway. */
        private const val HISTORY_WAIT_MS = 8_000L
        private const val HISTORY_CHECK_INTERVAL_MS = 250L

        /** The tighter pull while a seat is held or a game is on - see [acquire]. */
        private const val FAST_POLL_INTERVAL_MS = 2_000L

        /** The indexer's leaderboards are re-read at most this often unless forced. */
        private const val INDEXER_LEADERBOARD_INTERVAL_MS = 20_000L

        /**
         * The indexer's answer for [period], or null when it is not one: another `period` (an old
         * indexer without it), no `since`, or a row missing a figure - iOS's strict Decodable
         * refuses the whole answer then too.
         */
        internal fun acceptedIndexerBoard(
            period: ChessLeaderboardPeriod,
            response: ChessIndexerLeaderboardResponse,
        ): IndexerBoard? {
            if (response.period != period.raw) return null
            val since = response.since ?: return null
            val players = response.players ?: return null
            val rows = players.map { p ->
                ChessLeaderboardRow(
                    address = p?.address ?: return null,
                    wins = p.wins ?: return null,
                    losses = p.losses ?: return null,
                    tournamentsPlayed = p.tournamentsPlayed ?: return null,
                    tournamentsWon = p.tournamentsWon ?: return null,
                    tournamentsLost = p.tournamentsLost ?: return null,
                    lastPlayedAt = p.lastPlayedAt ?: return null,
                    duelWins = p.duelWins ?: return null,
                    duelLosses = p.duelLosses ?: return null,
                    tournamentGameWins = p.tournamentGameWins ?: return null,
                    tournamentGameLosses = p.tournamentGameLosses ?: return null,
                )
            }
            return IndexerBoard(since, rows)
        }

        /** The indexer's rows for [period], only if they count the window this phone counts now. */
        internal fun usableIndexerRows(
            period: ChessLeaderboardPeriod,
            board: IndexerBoard?,
            nowMs: Long,
        ): List<ChessLeaderboardRow>? =
            board?.takeIf { it.since == ChessTournamentEngine.periodSinceMs(period, nowMs) }?.rows

        /** Per player, the larger of each figure; then most wins, fewest losses, latest played.
         *  Without an indexer board the phone's own board is shown as it is. */
        internal fun mergedBoard(
            local: List<ChessLeaderboardRow>,
            remote: List<ChessLeaderboardRow>?,
        ): List<ChessLeaderboardRow> {
            if (remote == null) return local
            val rows = LinkedHashMap<String, ChessLeaderboardRow>()
            for (r in remote) rows.putIfAbsent(r.address, r)
            for (r in local) {
                val m = rows[r.address]
                rows[r.address] = if (m == null) r else m.copy(
                    wins = maxOf(m.wins, r.wins),
                    losses = maxOf(m.losses, r.losses),
                    duelWins = maxOf(m.duelWins, r.duelWins),
                    duelLosses = maxOf(m.duelLosses, r.duelLosses),
                    tournamentGameWins = maxOf(m.tournamentGameWins, r.tournamentGameWins),
                    tournamentGameLosses = maxOf(m.tournamentGameLosses, r.tournamentGameLosses),
                    tournamentsPlayed = maxOf(m.tournamentsPlayed, r.tournamentsPlayed),
                    tournamentsWon = maxOf(m.tournamentsWon, r.tournamentsWon),
                    tournamentsLost = maxOf(m.tournamentsLost, r.tournamentsLost),
                    lastPlayedAt = maxOf(m.lastPlayedAt, r.lastPlayedAt),
                )
            }
            return rows.values.sortedWith(
                compareByDescending<ChessLeaderboardRow> { it.wins }
                    .thenBy { it.losses }
                    .thenByDescending { it.lastPlayedAt }
                    .thenBy { it.address }
            )
        }

        /** Rooms the app uses as machinery, never shown as chats: the chess arena. Hidden from
         *  Public Chats, no unread, no banners (iOS PublicChatService.serviceChannels). */
        val SERVICE_CHANNELS: Set<String> = setOf(ChessTournamentCodec.ARENA_CHANNEL)
    }
}
