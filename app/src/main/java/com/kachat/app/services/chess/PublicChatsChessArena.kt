package com.kachat.app.services.chess

import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.BroadcastScanningService
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.ChessTournamentCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChessArenaChannel] on KaChat's Public Chats service (iOS 148a5331: KaPlay's arena side merged
 * into `PublicChatService`; on Android, [BroadcastRepository] and [BroadcastScanningService]).
 * Only passes calls through: what each one does is the Public Chats service's.
 */
@Singleton
class PublicChatsChessArena @Inject constructor(
    private val repository: BroadcastRepository,
    private val scanning: BroadcastScanningService,
    private val database: KaChatDatabase,
) : ChessArenaChannel {

    private fun BroadcastMessageEntity.toArenaRow() = ChessArenaRow(
        id = id,
        senderAddress = senderAddress,
        blockTimestamp = blockTimestamp,
        content = content,
        deliveryStatus = deliveryStatus,
    )

    // silent: the arena is machinery, never a room whose bell goes on (iOS 824a30bb notify: false)
    override suspend fun joinChannel(channel: String) = repository.joinChannel(channel, notify = false)

    override fun startLiveViewing(channel: String): AutoCloseable = scanning.startLiveViewing(channel)

    /** Every stored row of the running network, oldest first: the reducer needs the whole room,
     *  as KaPlay read it (the room table holds both networks' rows, audit M2). */
    override fun messages(channel: String): Flow<List<ChessArenaRow>> =
        database.broadcastDao().getMessagesForChannel(channel, repository.networkSenderPattern())
            .map { rows -> rows.map { it.toArenaRow() } }

    override suspend fun message(txId: String): ChessArenaRow? =
        database.broadcastDao().getMessage(txId)?.toArenaRow()

    override val arenaIndexerDown: StateFlow<Boolean> get() = repository.arenaIndexerDown
    override val arenaIndexerAnswered: Boolean get() = repository.arenaIndexerAnswered
    override val arenaIndexerAnsweredAt: Long get() = repository.arenaIndexerAnsweredAt
    override val arenaBackfillDone: Boolean get() = repository.arenaBackfillDone
    override val arenaHasIndexer: Boolean get() = repository.arenaHasIndexer

    override fun arenaAnsweredAt(since: Long): Long = repository.arenaAnsweredAt(since)

    override suspend fun indexerConfigured(channel: String): Boolean = repository.indexerConfigured(channel)

    override suspend fun indexerBaseUrl(channel: String): String? = repository.indexerBaseUrlOrNull(channel)

    override suspend fun arenaIndexerAddressSet(): Boolean = repository.arenaIndexerAddressSet()

    override fun allowArenaProbeNow() {
        repository.allowArenaProbeNow()
    }

    override suspend fun resyncArenaIndexerIfUnset() = repository.resyncArenaIndexerIfUnset()

    override suspend fun backfillFromIndexer(channel: String) {
        repository.backfillFromIndexer(channel)
    }

    override suspend fun fetchNewestFromIndexer(channel: String) {
        repository.fetchNewestFromIndexer(channel)
    }

    override suspend fun refreshFromIndexerNow(channel: String): Boolean = repository.refreshFromIndexerNow(channel)

    override fun repageArena(since: Long) {
        repository.repageArena(since)
    }

    /** The Public Chats service holds no chess code: a row is this game's when the chess codec
     *  reads its tournament id from the content. */
    override suspend fun readArenaFromChain(tournamentId: String, players: List<String>, since: Long): Boolean =
        repository.readArenaFromChain(players = players, since = since) { content ->
            ChessTournamentCodec.decode(content)?.t == tournamentId
        }

    override val locallyStampedTxIds: Set<String> get() = repository.locallyStampedTxIds

    override suspend fun checkUnconfirmedServiceRows(olderThanMs: Long, giveUpAfterMs: Long): Set<String> =
        repository.checkUnconfirmedServiceRows(olderThanMs = olderThanMs, giveUpAfterMs = giveUpAfterMs)

    override suspend fun sendPublicChat(
        channel: String,
        content: String,
        deferNoInputRetry: Boolean,
        expectedSender: String?,
    ): String? = repository.sendPublicChat(
        channelName = channel,
        content = content,
        deferNoInputRetry = deferNoInputRetry,
        expectedSender = expectedSender,
    )

    override suspend fun isTransactionAccepted(txId: String): Boolean = repository.isTransactionAccepted(txId)
}
