package com.kachat.app.services.chess

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Every field is nullable so a row missing one can be refused as a whole, like iOS's strict
 *  Decodable (see ChessTournamentService.acceptedIndexerBoard). */
data class ChessIndexerLeaderboardResponse(
    val players: List<ChessIndexerLeaderboardPlayer?>?,
    val period: String?,
    val since: Long?,
)

data class ChessIndexerLeaderboardPlayer(
    val address: String?,
    val wins: Int?,
    val losses: Int?,
    val duelWins: Int?,
    val duelLosses: Int?,
    val tournamentGameWins: Int?,
    val tournamentGameLosses: Int?,
    val tournamentsPlayed: Int?,
    val tournamentsWon: Int?,
    val tournamentsLost: Int?,
    val lastPlayedAt: Long?,
)

/** Every field is nullable so a board missing one can be refused as a whole, like iOS's strict
 *  Decodable (see ChessBotLeaderboardService). */
data class ChessBotLeaderboardResponse(
    val period: String?,
    val bots: List<ChessBotLeaderboardBot?>?,
)

data class ChessBotLeaderboardBot(
    val level: Int?,
    val games: Int?,
    val players: List<ChessBotLeaderboardPlayer?>?,
)

data class ChessBotLeaderboardPlayer(
    val address: String?,
    val moves: Int?,
    /** Block time of the win (ms). */
    val at: Long?,
)

/**
 * The chess boards on the arena's indexer (ONLINE_CHESS.md §6, §7.1), asked directly as iOS does
 * (`ChessTournamentService.refreshIndexerLeaderboards`, `ChessBotLeaderboardService.refresh`): the
 * base URL is the room's indexer ([ChessArenaChannel.indexerBaseUrl], the message indexer unless
 * the room has its own). Null on any failure: the phone's own boards stay in place.
 */
@Singleton
class ChessIndexerClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val arena: ChessArenaChannel,
) {
    /** iOS `request.timeoutInterval = 15`. */
    private val client = okHttpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /** GET /chess/leaderboard?period=month|all&limit=1000 */
    suspend fun fetchChessLeaderboard(channel: String, period: String): ChessIndexerLeaderboardResponse? =
        get(channel, "chess/leaderboard", period, limit = 1000, ChessIndexerLeaderboardResponse::class.java)

    /** GET /chess/bots/leaderboard?period=month|all&limit=100 */
    suspend fun fetchChessBotLeaderboard(channel: String, period: String): ChessBotLeaderboardResponse? =
        get(channel, "chess/bots/leaderboard", period, limit = 100, ChessBotLeaderboardResponse::class.java)

    private suspend fun <T> get(channel: String, path: String, period: String, limit: Int, type: Class<T>): T? {
        val base = runCatching { arena.indexerBaseUrl(channel) }.getOrNull()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) return null
        val url = "$base/$path".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("period", period)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    gson.fromJson(body, type)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chess board ($path, $period) not served: ${e.message}")
                null
            }
        }
    }

    private companion object {
        const val TAG = "ChessIndexer"
    }
}
