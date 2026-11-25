package com.kachat.app.services.arcade

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.NetworkService
import com.kachat.app.services.SendOutcomeUnknownException
import com.kachat.app.services.WalletManager
import com.kachat.app.services.chess.ChessTournamentService
import com.kachat.app.util.ArcadeGame
import com.kachat.app.util.ArcadeRandom
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.ChessTournamentEngine
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.capResponseBodies
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// KaPlay's arcade on Kaspa (ARCADE.md; iOS 518fdc8c `Services/ArcadeServices.swift`): a finished
// game is stamped in the hidden `#kplay-arcade` room by the player's chatting address; the indexer
// replays each stamp and serves the boards (`GET /arcade/leaderboard`); a score shared to KaPost
// carries a `kachatarcade://` challenge.

// MARK: - The stamp

/**
 * A finished game's stamp: plaintext broadcast JSON in `#kplay-arcade`, signed by the player's
 * address, sent once when the game ends (ARCADE.md §1).
 */
data class ArcadeStamp(
    /** "snake" or "2048". */
    val game: String,
    /** Game id: a lowercase UUID chosen by the player's phone. */
    val g: String,
    /** The game's seed, 16 hex digits. */
    val seed: String,
    /** The score the game reached. */
    val score: Int,
    /** The moves: Snake's turns ("12R 30U"), 2048's swipes ("ULDR..."). */
    val m: String,
) {
    val type: String get() = "arcade"
    val v: Int get() = 1

    /** Keys sorted, no extra whitespace, slashes not escaped (iOS `.sortedKeys,
     *  .withoutEscapingSlashes`). */
    val encoded: String
        get() = "{\"g\":${json(g)},\"game\":${json(game)},\"m\":${json(m)},\"score\":$score," +
            "\"seed\":${json(seed)},\"type\":${json(type)},\"v\":$v}"

    companion object {
        const val CHANNEL = "kplay-arcade"

        /** A JSON string as Foundation's encoder writes it. */
        internal fun json(value: String): String = buildString {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
    }
}

/**
 * The sentences the arcade's services hand the screens, in English: the screens show each in the
 * picked language (`ArcadeText.message` in the arcade screens), the same way the chess services'
 * sentences are shown ([com.kachat.app.util.KaPlayText]).
 */
object ArcadeMessages {
    const val OTHER_ACCOUNT = "This game was played on another account. Sign in to it to stamp the score."
    const val NOT_SENT = "The score was not sent."
    const val NOT_CONFIRMED_YET = "Sent, but Kaspa hasn't confirmed it yet."
    /** [SendOutcomeUnknownException]'s sentence (iOS `SendOutcomeUnknownError.message`). */
    const val OUTCOME_UNKNOWN = "This may have gone through, but the network didn't confirm it. Check your balance before sending again."
}

/**
 * One finished game being stamped, from the game-over sheet: goes on even if the screen closes
 * (iOS `ArcadeStampJob`). Made by [ArcadeService.stamp].
 */
class ArcadeStampJob internal constructor(
    val game: ArcadeGame,
    val score: Int,
    val seed: Long,
    val moves: String,
    /** The account the game was played on. */
    val playerAddress: String?,
    private val service: ArcadeService?,
    send: Boolean,
) {
    sealed class State {
        data object Sending : State()
        data object Stamped : State()
        /** [message]: an English sentence ([ArcadeMessages] or a send error's). */
        data class Failed(val message: String) : State()
        /** It may have gone through: no Retry, which could stamp twice. */
        data class Unconfirmed(val message: String) : State()
        /** A score of 0: nothing is sent. */
        data object NotSent : State()
    }

    /** Share to KaPost from the game-over sheet. Posting, shared and unconfirmed keep Share off,
     *  so one score is never posted twice (audit 2026-11-24 #8). */
    sealed class ShareState {
        data object None : ShareState()
        data object Posting : ShareState()
        data object Shared : ShareState()
        /** The post may have gone out: no second Share. */
        data object Unconfirmed : ShareState()
        /** It definitely didn't go: Share is on again. [message] is English. */
        data class Failed(val message: String) : ShareState()
    }

    val id: String = java.util.UUID.randomUUID().toString().lowercase()

    private val _state = MutableStateFlow<State>(if (send) State.Sending else State.NotSent)
    val state: StateFlow<State> = _state.asStateFlow()

    val share = MutableStateFlow<ShareState>(ShareState.None)

    val stamp: ArcadeStamp
        get() = ArcadeStamp(game = game.raw, g = id, seed = ArcadeRandom.hex(seed), score = score, m = moves)

    init {
        if (send) send()
    }

    fun retry() {
        if (_state.value !is State.Failed) return
        send()
    }

    private fun send() {
        val service = service ?: return
        val sender = playerAddress
        if (sender == null || service.currentAddress() != sender) {
            _state.value = State.Failed(ArcadeMessages.OTHER_ACCOUNT)
            return
        }
        _state.value = State.Sending
        val content = stamp.encoded
        service.scope.launch {
            var attempt = 0
            while (true) {
                if (service.currentAddress() != sender) {
                    _state.value = State.Failed(ArcadeMessages.OTHER_ACCOUNT)
                    return@launch
                }
                try {
                    val txId = service.broadcasts.sendPublicChat(
                        channelName = ArcadeStamp.CHANNEL,
                        content = content,
                        deferNoInputRetry = false,
                        expectedSender = sender,
                    )
                    if (txId == null) {
                        _state.value = State.Failed(ArcadeMessages.NOT_SENT)
                        return@launch
                    }
                    // The node took it into its mempool, and Kaspa puts that in a block within a
                    // second or so: "stamped" now (owner, 2026-11-24: waiting for the REST API to
                    // index it took 8-12 s). The check below still runs, and takes it back if the
                    // chain never shows it.
                    _state.value = State.Stamped
                    val record = ArcadeRecord(game.raw, score, System.currentTimeMillis(), id, txId)
                    service.progress.add(record, sender)
                    // Only a definite "not accepted" takes the record back; no answer keeps it and
                    // checks again later (`ArcadeProgress.recheckUnconfirmed`), since until the
                    // boards are served it is the player's only row (audit 2026-11-24 #9).
                    service.progress.markUnconfirmed(record, sender)
                    when (landed(service, txId)) {
                        ArcadeTransactionCheck.Answer.ACCEPTED ->
                            service.progress.confirm(id, sender)
                        ArcadeTransactionCheck.Answer.NOT_ACCEPTED -> {
                            // off the board, but still checked: a slow REST API can say 404 for a
                            // while, and a later "accepted" puts it back
                            _state.value = State.Unconfirmed(ArcadeMessages.NOT_CONFIRMED_YET)
                            service.progress.takeBack(id, sender)
                        }
                        ArcadeTransactionCheck.Answer.UNANSWERED ->
                            _state.value = State.Unconfirmed(ArcadeMessages.NOT_CONFIRMED_YET)
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is SendOutcomeUnknownException) {
                        _state.value = State.Unconfirmed(ArcadeMessages.OUTCOME_UNKNOWN)
                        return@launch
                    }
                    attempt += 1
                    if (attempt > 5 || !ChessTournamentService.isRetryableSendError(e)) {
                        Log.w(TAG, "Stamp failed: ${e.message}")
                        _state.value = State.Failed(ChessTournamentService.friendlySendErrorMessage(e))
                        return@launch
                    }
                    delay(1_200)
                }
            }
        }
    }

    /** Whether the REST API shows the stamp accepted within a minute and a half. Not accepted only
     *  when its last answer, past the window, said so; no answer is [ArcadeTransactionCheck.Answer.UNANSWERED]. */
    private suspend fun landed(service: ArcadeService, txId: String): ArcadeTransactionCheck.Answer {
        var last = ArcadeTransactionCheck.Answer.UNANSWERED
        repeat(CONFIRM_CHECKS) {
            delay(CONFIRM_CHECK_GAP_MS)
            last = service.transactionCheck.lookUp(txId)
            if (last == ArcadeTransactionCheck.Answer.ACCEPTED) return last
        }
        return last
    }

    companion object {
        private const val TAG = "Arcade"
        /** 45 looks, 2 s apart: the 90 s the stamp is checked on chain for. */
        const val CONFIRM_CHECKS = 45
        const val CONFIRM_CHECK_GAP_MS = 2_000L

        /** A finished game with no score: shown, never sent. */
        fun notStamped(game: ArcadeGame): ArcadeStampJob =
            ArcadeStampJob(game, 0, 0, "", playerAddress = null, service = null, send = false)
    }
}

/**
 * The REST API's word on a stamp, keeping "no answer" apart from "not accepted" (iOS
 * `ArcadeTransactionCheck`; `BroadcastRepository.isTransactionAccepted` folds both into false,
 * audit 2026-11-24 #9).
 */
@Singleton
class ArcadeTransactionCheck @Inject constructor(
    private val networkService: NetworkService,
) {
    enum class Answer { ACCEPTED, NOT_ACCEPTED, UNANSWERED }

    suspend fun lookUp(txId: String): Answer {
        if (!isTransactionId(txId)) return Answer.UNANSWERED
        val rest = networkService.kaspaRestApi.value ?: return Answer.UNANSWERED
        return try {
            val answer = withTimeoutOrNull(8_000L) { rest.getTransactionAcceptance(txId) }
                ?: return Answer.UNANSWERED
            // in a block but not accepted yet, or anything odd: not an answer either way
            if (answer.isAccepted == true) Answer.ACCEPTED else Answer.UNANSWERED
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) Answer.NOT_ACCEPTED else Answer.UNANSWERED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Answer.UNANSWERED
        }
    }

    companion object {
        fun isTransactionId(txId: String): Boolean =
            txId.length == 64 && txId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

// MARK: - Your own scores

/** [game] is [ArcadeGame.raw]. */
data class ArcadeRecord(
    val game: String,
    val score: Int,
    /** When it was stamped (ms). */
    val at: Long,
    val gameId: String,
    val txId: String?,
)

/**
 * The account's stamped scores on this phone, so its own best shows on the board at once (iOS
 * `ArcadeProgress`).
 */
@Singleton
class ArcadeProgress @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val transactionCheck: ArcadeTransactionCheck,
) {
    private val prefs = context.getSharedPreferences("kachat_arcade", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<ArcadeRecord>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every change, for the screens to read the scores again. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    private var rechecking = false
    @Volatile
    private var lastRecheck: Long? = null

    private val address: String? get() = runCatching { walletManager.getAddress() }.getOrNull()

    private fun records(address: String): List<ArcadeRecord> = read(key(address))

    private fun read(key: String): List<ArcadeRecord> = runCatching {
        val json = prefs.getString(key, null) ?: return@runCatching emptyList<ArcadeRecord>()
        gson.fromJson<List<ArcadeRecord>>(json, listType)?.filterNotNull().orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(record: ArcadeRecord, address: String) {
        val list = records(address) + record
        // the best 50 per game are plenty for any board
        val kept = ArcadeGame.entries.flatMap { game ->
            list.filter { it.game == game.raw }.sortedByDescending { it.score }.take(50)
        }
        prefs.edit().putString(key(address), gson.toJson(kept)).apply()
        _revision.value += 1
    }

    /** The signed-in account's best stamped score in [game] since [since] (ms); the earlier one
     *  on a tie. */
    fun best(game: ArcadeGame, since: Long = 0): ArcadeRecord? {
        val address = address ?: return null
        return ArcadeProgressRules.best(records(address), game, since)
    }

    /** A stamp the REST API says isn't accepted: off the board, still checked ([recheckUnconfirmed]). */
    @Synchronized
    fun takeBack(gameId: String, address: String) {
        val list = records(address).filter { it.gameId != gameId }
        prefs.edit().putString(key(address), gson.toJson(list)).apply()
        _revision.value += 1
    }

    /** A stamp the chain never showed: gone for good. */
    fun remove(gameId: String, address: String) {
        takeBack(gameId, address)
        confirm(gameId, address)
    }

    /** Delete Account: on both networks' addresses, any case. */
    @Synchronized
    fun removeAll(forAddress: String) {
        val variants = com.kachat.app.util.KaspaNetwork.accountAddressVariants(forAddress)
        val editor = prefs.edit()
        for (variant in variants) {
            editor.remove(key(variant))
            editor.remove(unconfirmedKey(variant))
        }
        editor.apply()
        _revision.value += 1
    }

    // MARK: Stamps not confirmed yet

    private fun unconfirmed(address: String): List<ArcadeRecord> = read(unconfirmedKey(address))

    private fun setUnconfirmed(list: List<ArcadeRecord>, address: String) {
        if (list.isEmpty()) {
            prefs.edit().remove(unconfirmedKey(address)).apply()
        } else {
            prefs.edit().putString(unconfirmedKey(address), gson.toJson(list)).apply()
        }
    }

    /** Sent, its acceptance not known yet. */
    @Synchronized
    fun markUnconfirmed(record: ArcadeRecord, address: String) {
        if (record.txId == null) return
        val list = unconfirmed(address).filter { it.gameId != record.gameId } + record
        setUnconfirmed(list.takeLast(50), address)
    }

    /** The REST API showed it accepted (or it was taken back): no more checks. */
    @Synchronized
    fun confirm(gameId: String, address: String) {
        val list = unconfirmed(address)
        val kept = list.filter { it.gameId != gameId }
        if (kept.size != list.size) setUnconfirmed(kept, address)
    }

    /**
     * Looks the signed-in account's unconfirmed stamps up again, at most once a minute: accepted
     * ones stop being checked, ones the REST API says aren't accepted well past their send are
     * taken back, and no answer leaves them for next time.
     */
    fun recheckUnconfirmed() {
        if (rechecking) return
        val address = address ?: return
        val last = lastRecheck
        if (last != null && System.currentTimeMillis() - last < 60_000L) return
        val pending = unconfirmed(address)
        if (pending.isEmpty()) return
        rechecking = true
        lastRecheck = System.currentTimeMillis()
        scope.launch {
            try {
                for (record in pending) {
                    val txId = record.txId ?: continue
                    when (transactionCheck.lookUp(txId)) {
                        ArcadeTransactionCheck.Answer.ACCEPTED -> {
                            // taken back on an earlier 404: it landed after all
                            if (records(address).none { it.gameId == record.gameId }) add(record, address)
                            confirm(record.gameId, address)
                        }
                        ArcadeTransactionCheck.Answer.NOT_ACCEPTED -> {
                            if (System.currentTimeMillis() - record.at > NOT_ACCEPTED_AFTER_MS) {
                                Log.i("Arcade", "Stamp $txId never accepted: its score is taken back")
                                remove(record.gameId, address)
                            }
                        }
                        ArcadeTransactionCheck.Answer.UNANSWERED -> Unit
                    }
                }
            } finally {
                rechecking = false
            }
        }
    }

    private companion object {
        fun key(address: String) = "kachat_arcade_records_v1_${address.lowercase()}"
        /** Stamps the REST API hasn't confirmed yet, checked again on each board refresh (audit
         *  2026-11-24 #9). */
        fun unconfirmedKey(address: String) = "kachat_arcade_unconfirmed_v1_${address.lowercase()}"
        /** A stamp still not accepted this long after it was sent is gone: its record goes. */
        const val NOT_ACCEPTED_AFTER_MS = 10L * 60 * 1000
    }
}

/** The pure parts of [ArcadeProgress] and [ArcadeLeaderboardService], for the unit tests. */
object ArcadeProgressRules {
    /** The best score in [game] since [since], the earlier stamp on a tie. */
    fun best(records: List<ArcadeRecord>, game: ArcadeGame, since: Long): ArcadeRecord? =
        records.filter { it.game == game.raw && it.at >= since }
            .maxWithOrNull(compareBy<ArcadeRecord> { it.score }.thenByDescending { it.at })

    /** The indexer's rows as a board: valid addresses and scores only, one per address (its best,
     *  the earlier stamp on a tie), highest first, at most [limit] (audit 2026-11-24 #22). */
    fun boardRows(players: List<ArcadeBoardRow>, limit: Int): List<ArcadeBoardRow> {
        val seen = HashSet<String>()
        val rows = ArrayList<ArcadeBoardRow>()
        for (player in players.sortedWith(compareByDescending<ArcadeBoardRow> { it.score }.thenBy { it.at })) {
            if (!KaspaAddress.isValid(player.address) || player.score < 0) continue
            if (!seen.add(player.address.lowercase())) continue
            rows.add(player)
            if (rows.size == limit) break
        }
        return rows
    }

    /** The board with the player's own stamped best merged in: highest first, the earlier stamp on
     *  a tie, then by address; at most [limit]. */
    fun merged(board: List<ArcadeBoardRow>, address: String?, mine: ArcadeRecord?, limit: Int): List<ArcadeBoardRow> {
        val rows = board.toMutableList()
        if (address != null && mine != null) {
            val own = ArcadeBoardRow(address, mine.score, mine.at)
            val index = rows.indexOfFirst { it.address.equals(address, ignoreCase = true) }
            if (index >= 0) {
                if (mine.score > rows[index].score) rows[index] = own
            } else {
                rows.add(own)
            }
        }
        return rows.sortedWith(
            compareByDescending<ArcadeBoardRow> { it.score }.thenBy { it.at }.thenBy { it.address }
        ).take(limit)
    }
}

// MARK: - Leaderboards

data class ArcadeBoardRow(
    val address: String,
    val score: Int,
    /** Block time of the stamp (ms). */
    val at: Long,
)

/** The indexer's answer; every field nullable so a row missing one refuses the whole answer, as
 *  iOS's strict Decodable does. */
private data class ArcadeLeaderboardResponse(
    val game: String?,
    val period: String?,
    val games: Int?,
    val players: List<ArcadeLeaderboardPlayer?>?,
)

private data class ArcadeLeaderboardPlayer(
    val address: String?,
    val score: Int?,
    val at: Long?,
)

/**
 * The boards, from the indexer's `/arcade/leaderboard` (ARCADE.md §5), with the player's own
 * stamped best merged in so a score shows the moment it is stamped. A failure leaves what is there
 * in place (iOS `ArcadeLeaderboardService`).
 */
@Singleton
class ArcadeLeaderboardService @Inject constructor(
    okHttpClient: OkHttpClient,
    private val broadcasts: BroadcastRepository,
    private val progress: ArcadeProgress,
    private val walletManager: WalletManager,
) {
    /** An ephemeral client that stops reading past [MAX_RESPONSE_BYTES] (audit 2026-11-24 #22). */
    private val client = okHttpClient.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(15, TimeUnit.SECONDS)
        .capResponseBodies("Arcade board") { MAX_RESPONSE_BYTES }
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "game|period" -> rows. */
    private val _boards = MutableStateFlow<Map<String, List<ArcadeBoardRow>>>(emptyMap())
    val boards: StateFlow<Map<String, List<ArcadeBoardRow>>> = _boards.asStateFlow()

    /** Per game: Snake's board failing says nothing about 2048's (audit 2026-11-24 #19). */
    private val _unreachable = MutableStateFlow<Set<ArcadeGame>>(emptySet())
    val unreachable: StateFlow<Set<ArcadeGame>> = _unreachable.asStateFlow()

    private val lastFetch = java.util.concurrent.ConcurrentHashMap<ArcadeGame, Long>()

    /** At most every 20 s per game unless forced. */
    fun refresh(game: ArcadeGame, force: Boolean = false) {
        progress.recheckUnconfirmed()
        val last = lastFetch[game]
        if (!force && last != null && System.currentTimeMillis() - last < 20_000L) return
        scope.launch { load(game) }
    }

    /** [refresh], forced, waiting for the answers (pull to refresh's spinner). */
    suspend fun refreshNow(game: ArcadeGame) {
        progress.recheckUnconfirmed()
        withContext(Dispatchers.IO) { load(game) }
    }

    private suspend fun load(game: ArcadeGame) {
        val base = runCatching { broadcasts.indexerBaseUrlOrNull(ArcadeStamp.CHANNEL) }.getOrNull()
            ?.trimEnd('/')
        if (base.isNullOrEmpty()) return
        lastFetch[game] = System.currentTimeMillis()
        var answered = false
        for (period in ChessLeaderboardPeriod.entries) {
            val url = "$base/arcade/leaderboard".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("game", game.raw)
                ?.addQueryParameter("period", period.raw)
                ?.addQueryParameter("limit", LIMIT.toString())
                ?.build() ?: return
            val result = try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    // Not served yet (ARCADE.md §5 is still open): an answer, of no scores; the own
                    // stamped best still shows (audit 2026-11-24 #19).
                    if (response.code == 404) return@use Fetch.NotServed
                    if (response.code !in 200..299) return@use Fetch.Failed
                    val body = response.body?.string() ?: return@use Fetch.Failed
                    Fetch.Body(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Arcade", "Board (${game.raw}, ${period.raw}) not read: ${e.message}")
                Fetch.Failed
            }
            when (result) {
                Fetch.Failed -> continue
                Fetch.NotServed -> {
                    answered = true
                    continue
                }
                is Fetch.Body -> {
                    val rows = decode(result.text, game, period) ?: continue
                    answered = true
                    _boards.value = _boards.value + (key(game, period) to rows)
                }
            }
        }
        _unreachable.value = if (answered) _unreachable.value - game else _unreachable.value + game
    }

    private sealed class Fetch {
        data object Failed : Fetch()
        data object NotServed : Fetch()
        data class Body(val text: String) : Fetch()
    }

    /** The answer's rows, or null when it isn't one for this game and period, or anything in it
     *  is missing. */
    private fun decode(text: String, game: ArcadeGame, period: ChessLeaderboardPeriod): List<ArcadeBoardRow>? {
        val response = runCatching { gson.fromJson(text, ArcadeLeaderboardResponse::class.java) }.getOrNull()
            ?: return null
        if (response.game != game.raw || response.period != period.raw) return null
        val players = response.players ?: return null
        val rows = players.map { player ->
            ArcadeBoardRow(
                address = player?.address ?: return null,
                score = player.score ?: return null,
                at = player.at ?: return null,
            )
        }
        return ArcadeProgressRules.boardRows(rows, LIMIT)
    }

    fun serverUnreachable(game: ArcadeGame, unreachable: Set<ArcadeGame> = _unreachable.value): Boolean = game in unreachable

    /** Highest score first, the earlier stamp on a tie. */
    fun rows(
        game: ArcadeGame,
        period: ChessLeaderboardPeriod,
        boards: Map<String, List<ArcadeBoardRow>> = _boards.value,
    ): List<ArcadeBoardRow> {
        val since = ChessTournamentEngine.periodSinceMs(period, System.currentTimeMillis())
        val address = runCatching { walletManager.getAddress() }.getOrNull()
        return ArcadeProgressRules.merged(boards[key(game, period)].orEmpty(), address, progress.best(game, since), LIMIT)
    }

    companion object {
        /** The most rows a board keeps (audit 2026-11-24 #22). */
        const val LIMIT = 100
        /** The most bytes an answer may be (audit 2026-11-24 #22). */
        const val MAX_RESPONSE_BYTES = 512L * 1024

        private fun key(game: ArcadeGame, period: ChessLeaderboardPeriod) = "${game.raw}|${period.raw}"
    }
}

/**
 * What the arcade's screens and jobs need: the scope a stamp goes on in after its screen closes,
 * the room it is sent to, and the account (iOS's singletons, in one injectable place).
 */
@Singleton
class ArcadeService @Inject constructor(
    internal val broadcasts: BroadcastRepository,
    internal val walletManager: WalletManager,
    val progress: ArcadeProgress,
    val boards: ArcadeLeaderboardService,
    internal val transactionCheck: ArcadeTransactionCheck,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun currentAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /** A finished game with a score: its stamp goes out now. */
    fun stamp(game: ArcadeGame, score: Int, seed: Long, moves: String): ArcadeStampJob =
        ArcadeStampJob(game, score, seed, moves, currentAddress(), this, send = true)
}

// MARK: - Challenges

/**
 * Someone's score, from the Challenge card on their post: beat [score] in [game] (iOS
 * `ArcadeChallenge`, ARCADE.md §6).
 *
 * `kachatarcade://<game>?score=<n>&by=<address>&game=<game id>`
 *
 * Links come from other people's posts: only the game has to be right; a bad score or address is
 * left out, and [url] is rebuilt from what passed. Parsed in plain Kotlin (no android.net.Uri), so
 * the rules are unit tested.
 */
data class ArcadeChallenge(
    val game: ArcadeGame,
    val score: Int?,
    /** Who scored it, as the link claims. */
    val by: String?,
    val gameId: String?,
    val url: String,
    /** [by] is the post's author, so the name can be shown; anyone can write any address into a
     *  link (audit 2026-11-24 #21). */
    val byIsAuthor: Boolean = false,
) {
    /** The name to show for who scored it: only [by] confirmed as the post's author. */
    val confirmedBy: String? get() = if (byIsAuthor) by else null

    data class Match(val challenge: ArcadeChallenge, val matchedText: String)

    companion object {
        const val SCHEME = "kachatarcade"
        private const val PREFIX = "kachatarcade://"
        private val LINK = Regex("""kachatarcade://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private const val TRAILING_TRIM = ".,;:!?)]}'\""

        /** `kachatarcade://<game>?score=<n>&by=<address>&game=<game id>` */
        fun url(game: ArcadeGame, score: Int, by: String, gameId: String): String =
            "$PREFIX${game.raw}?score=$score&by=${encode(by)}&game=${encode(gameId)}"

        /** The challenge in a link, or null when it isn't one for a game the arcade has. */
        fun parse(source: String): ArcadeChallenge? {
            if (!source.startsWith(PREFIX, ignoreCase = true)) return null
            val rest = source.substring(PREFIX.length)
            val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val game = ArcadeGame.fromRaw(rest.substring(0, hostEnd).lowercase()) ?: return null
            val afterHost = rest.substring(hostEnd)
            val query = afterHost.substringAfter('?', "").substringBefore('#')
            val items = if (afterHost.contains('?')) {
                query.split('&').map { item ->
                    val name = decode(item.substringBefore('='))
                    val value = if (item.contains('=')) decode(item.substringAfter('=')) else null
                    name to value
                }
            } else {
                emptyList()
            }
            fun value(name: String): String? = items.firstOrNull { it.first == name }?.second

            val score = value("score")
                ?.takeIf { raw -> raw.isNotEmpty() && raw.all { it in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..10_000_000 }
            val by = value("by")?.takeIf { KaspaAddress.isValid(it) }
            val gameId = value("game")?.takeIf { raw ->
                raw.length <= 64 && raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }
            val query2 = listOfNotNull(
                score?.let { "score=$it" },
                by?.let { "by=${encode(it)}" },
                gameId?.let { "game=${encode(it)}" },
            )
            val url = PREFIX + game.raw + if (query2.isEmpty()) "" else "?" + query2.joinToString("&")
            return ArcadeChallenge(game, score, by, gameId, url)
        }

        /** The first valid challenge in a post's text. A scan for the scheme first: most posts have
         *  no link at all, and this runs for every post the feed draws. */
        fun match(text: String): Match? {
            if (!text.contains(PREFIX, ignoreCase = true)) return null
            for (found in LINK.findAll(text)) {
                val candidate = found.value.trimEnd { it in TRAILING_TRIM }
                val challenge = parse(candidate) ?: continue
                return Match(challenge, candidate)
            }
            return null
        }

        /** A query value as iOS's URLComponents writes it: the link's own values never need more
         *  than this (digits, an address, a UUID). */
        private fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val ch = byte.toInt().toChar()
                if (byte >= 0 && (ch.isLetterOrDigit() || ch in "-._~:@/!$'()*+,;?")) append(ch)
                else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }

        private fun decode(value: String): String {
            if (!value.contains('%')) return value
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%' && i + 2 < value.length) {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}

/**
 * A challenge waiting for KaPlay, which may not be up yet (a cold start), and what holds it back
 * (iOS `ArcadeLinkRouter`).
 */
object ArcadeLinkRouter {
    val challenge = MutableStateFlow<ArcadeChallenge?>(null)

    /** A bot game on screen (the bot board's token while it is up), and an arcade round on screen
     *  (the play screen): a challenge waits here until both are done, so it never pops a game
     *  (audit 2026-11-24 #11). */
    private val _botGameToken = MutableStateFlow<String?>(null)
    val botGameToken: StateFlow<String?> = _botGameToken.asStateFlow()
    val arcadeRoundOpen = MutableStateFlow(false)

    val isBusy: Boolean get() = _botGameToken.value != null || arcadeRoundOpen.value

    /** The bot board calls this with its own token: running while its board is up, not running
     *  when the screen goes (only the token that set it clears it). */
    fun botGame(token: String, running: Boolean) {
        if (running) {
            if (_botGameToken.value != token) _botGameToken.value = token
        } else if (_botGameToken.value == token) {
            _botGameToken.value = null
        }
    }

    private val _openGames = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** An arcade challenge to play (iOS `.openGames`): the dock shows the KaPlay tab, wherever it
     *  lives, closing any covers over KaPost first; [challenge] says which. */
    val openGames: SharedFlow<Unit> = _openGames.asSharedFlow()

    /**
     * A challenge card's Play, or a tapped link: KaPlay opens the game. [author] is the address of
     * the post the card is on; the challenger is named only when it is `by` (audit 2026-11-24
     * #21), so a tapped link (no post) says "Someone". False when [url] isn't a challenge.
     */
    fun open(url: String, author: String? = null): Boolean {
        var challenge = ArcadeChallenge.parse(url) ?: return false
        val by = challenge.by
        if (by != null && author != null && by.equals(author, ignoreCase = true)) {
            challenge = challenge.copy(byIsAuthor = true)
        }
        this.challenge.value = challenge
        _openGames.tryEmit(Unit)
        return true
    }
}
