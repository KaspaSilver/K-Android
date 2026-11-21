package com.kachat.app.services.chess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One row of a chess room (the hidden `chess-arena` room, or `chess-bots`), as the Public Chats
 * store holds it. Only rows with [deliveryStatus] "sent" and a real transaction id (not
 * `pending_...`) count for the arena.
 */
data class ChessArenaRow(
    /** The transaction id ("pending_<uuid>" while a send is still going out). */
    val id: String,
    val senderAddress: String,
    /** Block time (ms); this phone's clock while the row is locally stamped. */
    val blockTimestamp: Long,
    val content: String,
    /** "sent", "pending" or "failed". */
    val deliveryStatus: String,
)

/**
 * What chess needs from the Public Chats service. On iPhone (148a5331) KaPlay's arena side is
 * merged into `PublicChatService`; the members here carry its names (and KaPlay Android's, where
 * iOS splits one call into two). The Public Chats service implements it; the chess services only
 * see this, so they never depend on how the rooms are stored or scanned.
 *
 * The arena is machinery, not a chat: never listed in the Chats list, no unread, no banners
 * ([ChessTournamentService.SERVICE_CHANNELS], iOS `PublicChatService.serviceChannels`). It follows
 * the message indexer, so there is no Chess card in Connection Settings.
 */
interface ChessArenaChannel {
    /** Joins (or keeps joined) a hidden service room, so its rows are stored. */
    suspend fun joinChannel(channel: String)

    /** Keeps [channel] scanned from the block stream until the returned handle is closed (iOS
     *  `acquire(_:)` / `release(_:)`). */
    fun startLiveViewing(channel: String): AutoCloseable

    /** The rows of [channel] as stored, oldest first, updating as they change. */
    fun messages(channel: String): Flow<List<ChessArenaRow>>

    /** One stored row by its transaction id (a dropped own row is still there, marked failed). */
    suspend fun message(txId: String): ChessArenaRow?

    // MARK: - The arena's indexer (iOS `PublicChatIndexerClient.arenaHealth`, `ArenaIndexerHealth`)

    /** True while the arena's indexer has stopped answering (iOS `arenaIndexerDown`, changes
     *  posted as `.arenaIndexerAvailabilityChanged`, never the message indexer's). */
    val arenaIndexerDown: StateFlow<Boolean>

    /** The indexer has answered an arena page since it was last reset (iOS
     *  `indexerFetchedChannels.contains(arena)`). */
    val arenaIndexerAnswered: Boolean

    /** When the newest full arena answer was asked for (wall ms), 0 when none. */
    val arenaIndexerAnsweredAt: Long

    /** The arena's history is paged in, as far as this session owes it. */
    val arenaBackfillDone: Boolean

    /** Whether the arena had an indexer configured at all, as of the last read that asked. */
    val arenaHasIndexer: Boolean

    /** When the newest answer holding every arena row since [since] (block ms) was asked for
     *  (wall ms), or 0 (iOS `arenaAnsweredAt(covering:)`). */
    fun arenaAnsweredAt(since: Long): Long

    /** Whether [channel] has an indexer to ask (iOS `indexerBaseURL(forChannel:)` not empty). */
    suspend fun indexerConfigured(channel: String): Boolean

    /** The base URL of [channel]'s indexer (its own where it has one, else the message indexer:
     *  `AppSettings.publicChatIndexerURL`), without a trailing slash; null when none is set. For
     *  `GET /chess/leaderboard` and `GET /chess/bots/leaderboard`. */
    suspend fun indexerBaseUrl(channel: String): String?

    /** The arena's indexer address is set in the settings (the client itself may come up a moment
     *  after launch, which mustn't read as "there is none"). */
    suspend fun arenaIndexerAddressSet(): Boolean

    /** Opening a chess screen asks at once whether the arena's indexer is back. */
    fun allowArenaProbeNow()

    /** iOS `resyncArenaIndexerIfUnset()`. */
    suspend fun resyncArenaIndexerIfUnset()

    /** One step of paging the arena's history in from its indexer (the room's 8 s poll). */
    suspend fun backfillFromIndexer(channel: String)

    /** The newest page of [channel] from its indexer, merged in (the 2 s poll while busy). */
    suspend fun fetchNewestFromIndexer(channel: String)

    /** The newest page now; true when the indexer answered (iOS `refreshFromIndexerNow`). */
    suspend fun refreshFromIndexerNow(channel: String): Boolean

    /** Pages the arena again from [since] (block ms), after a lapse, an outage or a gap
     *  (iOS `repageArena(since:)`). */
    fun repageArena(since: Long)

    /** While the indexer is down: [players]' arena rows for [tournamentId] since [since], read
     *  from the REST API and merged in. True when every address answered
     *  (iOS `readArenaFromChain`). */
    suspend fun readArenaFromChain(tournamentId: String, players: List<String>, since: Long): Boolean

    // MARK: - Own rows on this phone's clock (iOS `localStampSentAt`)

    /** Own service-room rows still carrying this phone's clock: the chain's block time has not
     *  reached the phone yet (iOS `locallyStampedTxIds`). */
    val locallyStampedTxIds: Set<String>

    /** Own service-room rows older than [olderThanMs] the chain still hasn't shown are looked up;
     *  the ones the network never took are marked failed and returned. One that can't be asked
     *  about is given up after [giveUpAfterMs] (iOS `checkUnconfirmedServiceRows`, about 75 s). */
    suspend fun checkUnconfirmedServiceRows(olderThanMs: Long, giveUpAfterMs: Long): Set<String>

    // MARK: - Sending

    /**
     * One room message (iOS `sendPublicChat(channel:content:feeOverride:deferNoInputRetry:
     * expectedSender:)`). Returns the transaction id, or null when nothing was sent. Chess always passes
     * `deferNoInputRetry = false` (a chess send retries itself) and [expectedSender]: when the
     * signed-in account is another one nothing is sent (an exception is thrown), so a move,
     * join, claim or stamp never goes out signed by the next account. A send whose outcome is
     * unknown throws [com.kachat.app.services.SendOutcomeUnknownException]; an own arena row is
     * kept on this phone's clock until the chain's time comes back.
     */
    suspend fun sendPublicChat(
        channel: String,
        content: String,
        deferNoInputRetry: Boolean = true,
        expectedSender: String? = null,
    ): String?

    /** Whether the REST API shows [txId] accepted (a bot game's stamp landing). */
    suspend fun isTransactionAccepted(txId: String): Boolean
}
