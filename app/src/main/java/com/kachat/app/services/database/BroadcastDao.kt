package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.HiddenBroadcastSenderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BroadcastDao {

    @Query("SELECT * FROM broadcast_channels WHERE walletAddress = :walletAddress ORDER BY joinedAt DESC")
    fun getJoinedChannels(walletAddress: String): Flow<List<BroadcastChannelEntity>>

    // IGNORE, never REPLACE: joining is create-if-absent. REPLACE deletes the existing row and
    // re-inserts entity DEFAULTS, which silently wiped notifyEnabled/alwaysListen every time
    // ensureFeaturedChannelsJoined re-ran (each BroadcastViewModel init, e.g. entering a room) -
    // the "bell turns itself off" bug.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun joinChannel(channel: BroadcastChannelEntity)

    @Query("DELETE FROM broadcast_channels WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun leaveChannel(channelName: String, walletAddress: String)

    /** A deleted account's joined rooms and hidden senders (iOS `PublicChatStore.clearAll`). */
    @Query("DELETE FROM broadcast_channels WHERE walletAddress = :walletAddress")
    suspend fun deleteChannelsForWallet(walletAddress: String)

    @Query("DELETE FROM broadcast_hidden_senders WHERE walletAddress = :walletAddress")
    suspend fun deleteHiddenSendersForWallet(walletAddress: String)

    /** Rooms no account on this device has joined any more: their cached messages go too. */
    @Query("DELETE FROM broadcast_messages WHERE channelName NOT IN (SELECT channelName FROM broadcast_channels)")
    suspend fun deleteMessagesOfUnjoinedChannels()

    /** A deleted account's sends that never landed. The room table is shared, so they would stay
     *  in a room another account still has, as if they had been posted (iOS ee5ec98: its store goes). */
    @Query("DELETE FROM broadcast_messages WHERE senderAddress = :senderAddress AND deliveryStatus != 'sent'")
    suspend fun deleteUnsentMessagesFrom(senderAddress: String)

    /** How many accounts on this device still have [channelName] joined. */
    @Query("SELECT COUNT(*) FROM broadcast_channels WHERE channelName = :channelName")
    suspend fun countJoinedAccounts(channelName: String): Int

    @Query("UPDATE broadcast_channels SET alwaysListen = :alwaysListen WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun setAlwaysListen(channelName: String, walletAddress: String, alwaysListen: Boolean)

    /** Also forces alwaysListen off — a channel that isn't being listened to can't sensibly still notify. */
    @Query("UPDATE broadcast_channels SET alwaysListen = 0, notifyEnabled = 0 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun disableAlwaysListenAndNotify(channelName: String, walletAddress: String)

    /** Also forces alwaysListen on — notifications need the channel to actually be scanned. */
    @Query("UPDATE broadcast_channels SET alwaysListen = 1, notifyEnabled = 1 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun enableNotifyAndAlwaysListen(channelName: String, walletAddress: String)

    @Query("UPDATE broadcast_channels SET notifyEnabled = 0 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun disableNotify(channelName: String, walletAddress: String)

    /** Per-channel override of local message retention, set via the settings icon next to a channel — see BroadcastRepository.setRetentionMillis for the 3-day cap enforcement. */
    @Query("UPDATE broadcast_channels SET retentionMillis = :retentionMillis WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun setRetentionMillis(channelName: String, walletAddress: String, retentionMillis: Long)

    /**
     * One row per distinct channel name with the *longest* retention any local account has asked
     * for it — messages aren't scoped per wallet, so if two accounts on this device joined the
     * same channel with different retention settings, pruning must honor whichever is longer
     * rather than deleting data a different account still wants to keep.
     */
    @Query("SELECT channelName, MAX(retentionMillis) AS retentionMillis FROM broadcast_channels GROUP BY channelName")
    suspend fun getChannelRetentions(): List<ChannelRetention>

    /**
     * Drives both whether background scanning should run at all (non-empty) and which channels'
     * messages actually get cached while it's running - see BroadcastScanningService.
     *
     * The bell is the one control now (iOS ac34790): a room with notifications on is listened to
     * while the app is open, which is what lets it notify and count unread. The separate listen
     * switch is gone, and a value an older build stored in `alwaysListen` is no longer read.
     */
    @Query("SELECT channelName FROM broadcast_channels WHERE walletAddress = :walletAddress AND notifyEnabled = 1")
    fun getAlwaysListenChannelNames(walletAddress: String): Flow<List<String>>

    /** Which channels should fire a system notification for new messages — see BroadcastScanningService. */
    @Query("SELECT channelName FROM broadcast_channels WHERE walletAddress = :walletAddress AND notifyEnabled = 1")
    fun getNotifyEnabledChannelNames(walletAddress: String): Flow<List<String>>

    // Every room read below takes [network], the running network's sender pattern ("kaspa:%" or
    // "kaspatest:%", BroadcastRepository.networkSenderPattern): the table is shared by both
    // networks (iOS keeps one store per wallet address, so per network), and a room shows only
    // the posts of the network the app runs on (audit M2).

    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network ORDER BY blockTimestamp ASC")
    fun getMessagesForChannel(channelName: String, network: String): Flow<List<BroadcastMessageEntity>>

    /** The newest rows in a room, newest first: the room screen's window. Uses the
     *  (channelName, blockTimestamp) index. */
    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network ORDER BY blockTimestamp DESC LIMIT :limit")
    fun getLatestMessagesForChannel(channelName: String, network: String, limit: Int): Flow<List<BroadcastMessageEntity>>

    /**
     * The Chats list's room summary (BroadcastRepository.roomSummary), in four small reads instead
     * of the room's newest 200 rows parsed on every write. A row whose content has none of the
     * markers "reaction", "edit" or "voicePart" is a plain message, settled in SQL; the few that
     * carry a marker are read and parsed by the caller. Each read skips the senders hidden here,
     * another local account's sends that never landed, and the other network's rows.
     */
    @Query(
        "SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network " +
            "AND (deliveryStatus = 'sent' OR senderAddress = :myAddress) AND senderAddress NOT IN (:hidden) " +
            "AND content NOT LIKE '%\"reaction\"%' AND content NOT LIKE '%\"edit\"%' " +
            "ORDER BY blockTimestamp DESC LIMIT 1"
    )
    fun summaryNewestPlain(channelName: String, network: String, myAddress: String, hidden: List<String>): Flow<BroadcastMessageEntity?>

    /** The rows that may be reactions or edits, newer than [afterMillis], newest first: whichever
     *  of them parses as neither is a message newer than [summaryNewestPlain]'s. */
    @Query(
        "SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network " +
            "AND (deliveryStatus = 'sent' OR senderAddress = :myAddress) AND senderAddress NOT IN (:hidden) " +
            "AND (content LIKE '%\"reaction\"%' OR content LIKE '%\"edit\"%') AND blockTimestamp > :afterMillis " +
            "ORDER BY blockTimestamp DESC LIMIT :limit"
    )
    fun summaryMarkedAfter(channelName: String, network: String, myAddress: String, hidden: List<String>, afterMillis: Long, limit: Int): Flow<List<BroadcastMessageEntity>>

    /** Other people's plain messages newer than the read marker: the unread count, in SQL. */
    @Query(
        "SELECT COUNT(*) FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network " +
            "AND deliveryStatus = 'sent' AND senderAddress != :myAddress AND senderAddress NOT IN (:hidden) " +
            "AND blockTimestamp > :afterMillis " +
            "AND content NOT LIKE '%\"reaction\"%' AND content NOT LIKE '%\"edit\"%' AND content NOT LIKE '%\"voicePart\"%'"
    )
    fun summaryUnreadPlainCount(channelName: String, network: String, myAddress: String, hidden: List<String>, afterMillis: Long): Flow<Int>

    /** Other people's marked rows newer than the read marker, for the caller to count: a reaction
     *  or edit not at all, a long voice note's parts once. A voice part is cut to its head, which
     *  is all its tag needs - not its ~20 KB of audio. */
    @Query(
        "SELECT id, channelName, senderAddress, blockTimestamp, deliveryStatus, " +
            "CASE WHEN content LIKE '%\"voicePart\"%' AND content NOT LIKE '%\"reaction\"%' AND content NOT LIKE '%\"edit\"%' " +
            "THEN substr(content, 1, 2048) ELSE content END AS content " +
            "FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network " +
            "AND deliveryStatus = 'sent' AND senderAddress != :myAddress AND senderAddress NOT IN (:hidden) " +
            "AND blockTimestamp > :afterMillis " +
            "AND (content LIKE '%\"reaction\"%' OR content LIKE '%\"edit\"%' OR content LIKE '%\"voicePart\"%')"
    )
    fun summaryUnreadMarked(channelName: String, network: String, myAddress: String, hidden: List<String>, afterMillis: Long): Flow<List<BroadcastMessageEntity>>

    /** Only a room's rows that can be reactions: the JSON type marker, filtered in SQL so the
     *  room's other rows are never read or parsed (iOS keeps reactions apart and loads them on
     *  change, PublicChatService `loadReactions`). The caller still parses each row. */
    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network AND content LIKE '%\"reaction\"%' ORDER BY blockTimestamp ASC")
    fun getReactionRowsForChannel(channelName: String, network: String): Flow<List<BroadcastMessageEntity>>

    /** Only a room's rows that can be edits - see [getReactionRowsForChannel] (iOS `loadEdits`). */
    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network AND content LIKE '%\"edit\"%' ORDER BY blockTimestamp ASC")
    fun getEditRowsForChannel(channelName: String, network: String): Flow<List<BroadcastMessageEntity>>

    /** The rows with these ids - the messages a room's edits name. Keep [ids] under SQLite's
     *  999-variable limit. */
    @Query("SELECT * FROM broadcast_messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<BroadcastMessageEntity>

    /** The newest chain time stored for a room, for its read marker (iOS `markChannelRead`). */
    @Query("SELECT MAX(blockTimestamp) FROM broadcast_messages WHERE channelName = :channelName AND senderAddress LIKE :network")
    suspend fun newestBlockTimestamp(channelName: String, network: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: BroadcastMessageEntity)

    /** Adds a chain post only when its id isn't stored yet (iOS PublicChatStore inserts only a
     *  missing id): a delayed block-scan store never overwrites the row the indexer (or our own
     *  send) wrote first, and its chain time. Returns -1 when the id was already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: BroadcastMessageEntity): Long

    /** Every row that came from the chain or an indexer (not a pending/failed send of ours) -
     *  for the one-time sender re-verification (audit XP-012). */
    @Query("SELECT * FROM broadcast_messages WHERE deliveryStatus = 'sent' AND id NOT LIKE 'pending_%'")
    suspend fun getAllSentMessages(): List<BroadcastMessageEntity>

    /** One message row by id — used to look a failed reaction's own message row back up for retry (see BroadcastRepository.retryReactionMessage). */
    @Query("SELECT * FROM broadcast_messages WHERE id = :id LIMIT 1")
    suspend fun getMessage(id: String): BroadcastMessageEntity?

    /** Removes a single message by id — used to drop a "pending_<uuid>" placeholder once its real send resolves (success swaps it for the real-txId row; see BroadcastRepository.sendBroadcast). */
    @Query("DELETE FROM broadcast_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    /** Flips a message's own delivery status in place (pending -> sent/failed) — used by sendBroadcast/retryBroadcast, mirroring ChatRepository.updateMessageStatus for 1:1 chats. */
    @Query("UPDATE broadcast_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String)

    /** Failed -> pending, only if it is still failed: returns 0 when another tap already took it. */
    @Query("UPDATE broadcast_messages SET deliveryStatus = 'pending' WHERE id = :id AND deliveryStatus = 'failed'")
    suspend fun claimFailedForRetry(id: String): Int

    /** A room's pending rows, and its unconfirmed rows whose id never became a transaction's -
     *  what the settle looks at (iOS 33095ac `settlePendingMessages`). */
    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName AND (deliveryStatus = 'pending' OR (deliveryStatus = 'unconfirmed' AND substr(id, 1, 8) = 'pending_'))")
    suspend fun getUnsettledSends(channelName: String): List<BroadcastMessageEntity>

    /** An unconfirmed row's delivered copy arrived: it is sent, at the chain's time (iOS 33095ac
     *  `insertMessages`). Returns 0 when the row isn't unconfirmed. */
    @Query("UPDATE broadcast_messages SET deliveryStatus = 'sent', blockTimestamp = :blockTimestamp WHERE id = :id AND deliveryStatus = 'unconfirmed'")
    suspend fun settleUnconfirmed(id: String, blockTimestamp: Long): Int

    /** The delivered rows matching a pending one, oldest first: same sender and content, sent, and
     *  no older than [sinceMillis] (iOS e5a9b11 `settlePendingMessages`). Their ids, so one copy
     *  settles one pending row (iOS b78fdb0). */
    @Query("SELECT id FROM broadcast_messages WHERE channelName = :channelName AND senderAddress = :senderAddress AND content = :content AND deliveryStatus = 'sent' AND blockTimestamp >= :sinceMillis ORDER BY blockTimestamp ASC")
    suspend fun deliveredCopyIds(channelName: String, senderAddress: String, content: String, sinceMillis: Long): List<String>

    /** The room's rolling retention. Never a send of ours still queued or failed: it stays until
     *  it is sent or deleted, or its Retry would find nothing and drop it (audit L4). */
    @Query("DELETE FROM broadcast_messages WHERE channelName = :channelName AND blockTimestamp < :cutoffMillis AND deliveryStatus NOT IN ('pending', 'failed')")
    suspend fun deleteOlderThan(channelName: String, cutoffMillis: Long)

    /** Wipes every cached message for a channel — used when leaving, since the user is explicitly warned that leaving deletes them (unlike the normal per-channel rolling retention, which is passive). */
    @Query("DELETE FROM broadcast_messages WHERE channelName = :channelName")
    suspend fun deleteMessagesForChannel(channelName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideSender(entity: HiddenBroadcastSenderEntity)

    /** Removes both the room-scoped hide AND any legacy every-room ("") hide for the sender. */
    @Query("DELETE FROM broadcast_hidden_senders WHERE senderAddress = :senderAddress AND walletAddress = :walletAddress AND (channelName = :channelName OR channelName = '')")
    suspend fun unhideSender(senderAddress: String, walletAddress: String, channelName: String)

    /** Every cached room message: they aren't per account, so only once no account is left
     *  (iOS da59726). */
    @Query("DELETE FROM broadcast_messages")
    suspend fun deleteAllMessages()

    /** Reactive so a fresh hide/unhide immediately re-filters any already-open channel screen and the scanning service's insert-time check (see BroadcastScanningService). */
    @Query("SELECT * FROM broadcast_hidden_senders WHERE walletAddress = :walletAddress")
    fun getHiddenSenders(walletAddress: String): Flow<List<HiddenBroadcastSenderEntity>>
}

data class ChannelRetention(val channelName: String, val retentionMillis: Long)
