package com.kachat.app.services

import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaPostsMarkdown
import com.kachat.app.util.MessageReply
import com.kachat.app.util.UsernameMention
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A public room's half of the mentions in Profile's bell (iOS d089011f
 * `PublicChatService.insertRoomMessages`): each message newly stored in a room - from an indexer
 * page or the live block scan - that @'s you by your username is a row in the bell
 * ([GlobalNotificationCenterStore.recordMention]), whatever the room's bell (owner, 2026-11-25:
 * every mention). Never a banner from here; the room's own rules decide that.
 */
@Singleton
class RoomMentionBell @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val bell: GlobalNotificationCenterStore,
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val settings: com.kachat.app.services.publicchats.PublicChatSettings,
) {
    /** [rows] were just inserted (not ones the store already held). */
    suspend fun recordMentions(rows: List<BroadcastMessageEntity>) {
        if (rows.isEmpty() || walletManager.isLoggedOut) return
        val me = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val myName = UsernameMention.myName(me) ?: return
        var hidden: List<com.kachat.app.models.HiddenBroadcastSenderEntity>? = null
        var blocked: Set<String>? = null
        // A room's history loads on joining (30 days): only mentions since you joined, and at most
        // a week old, become rows (iOS audit 2026-11-27 M8: joining #kaspa flooded the bell).
        var joinedAt: Map<String, Long>? = null
        val now = System.currentTimeMillis()
        for (row in rows) {
            if (!isCandidate(row.channelName, row.senderAddress, row.content, me)) continue
            val joined = joinedAt ?: runCatching { database.broadcastDao().getJoinedChannels(me).first() }
                .getOrDefault(emptyList()).associate { it.channelName to it.joinedAt }.also { joinedAt = it }
            if (!isRecentEnough(row.blockTimestamp, joined[row.channelName], now)) continue
            val preview = MessageReply.previewText(row.content)
            if (!UsernameMention.mentions(preview, myName)) continue
            // a sender hidden in this room, or everywhere (KaPost's block too), stays out
            val hiddenRows = hidden ?: runCatching { database.broadcastDao().getHiddenSenders(me).first() }
                .getOrDefault(emptyList()).also { hidden = it }
            val blockedSet = blocked ?: runCatching { settings.kapostsBlocked(me).first() }
                .getOrDefault(emptySet()).also { blocked = it }
            if (row.senderAddress in blockedSet ||
                row.senderAddress in BroadcastRepository.hiddenAddressesIn(row.channelName, hiddenRows)
            ) continue
            bell.recordMention(
                sender = KaPostsMarkdown.strippingBidiControls(senderName(row.senderAddress, me)),
                place = "#${KaPostsMarkdown.strippingBidiControls(row.channelName)}",
                preview = KaPostsMarkdown.strippingBidiControls(preview),
                txId = row.id,
                blockTimeMs = row.blockTimestamp,
                target = "room:${row.channelName}",
            )
        }
    }

    /**
     * A push that @'s you from a room you haven't joined (iOS audit 2026-11-27 L13): nothing loads
     * that room's messages, so the push itself becomes the bell's row as it's shown. One row per
     * message, as always. [sender] is the push's sender name (its subtitle), [preview] its text.
     */
    suspend fun recordMentionPush(rawChannel: String, txId: String?, sender: String, preview: String, mention: Boolean) {
        if (!mention || walletManager.isLoggedOut) return
        val me = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (UsernameMention.myName(me) == null) return
        val room = com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel(rawChannel) ?: return
        val joined = runCatching { database.broadcastDao().getJoinedChannels(me).first() }.getOrNull() ?: return
        if (!isUnjoinedMentionPush(room, txId, preview, joined.map { it.channelName }.toSet())) return
        bell.recordMention(
            sender = KaPostsMarkdown.strippingBidiControls(sender),
            place = "#$room",
            preview = KaPostsMarkdown.strippingBidiControls(preview),
            txId = txId!!.lowercase(),
            blockTimeMs = System.currentTimeMillis(),
            target = "room:$room",
        )
    }

    /**
     * Messages their sender deleted for everyone leave the Profile bell and the shade too: a
     * mention row, and a push or banner still showing for one of them (iOS audit 2026-11-27 L10).
     * A room's own banner holds only its newest message, so it goes only when that's the one.
     */
    fun forgetNotices(txIds: Collection<String>) {
        val lowered = txIds.map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (lowered.isEmpty()) return
        for (id in lowered) bell.removeMention(id)
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
            for (sbn in manager.activeNotifications) {
                // one the system drew from a push carries the txid as its tag (the collapse id);
                // the app's own carry it in their extras
                val tagged = sbn.tag?.lowercase() in lowered
                val extra = sbn.notification.extras?.getString(NotificationHelper.EXTRA_TX_ID)?.lowercase() in lowered
                if (tagged || extra) manager.cancel(sbn.tag, sbn.id)
            }
        }
    }

    /** Your name for them, else their .kachat name, else a short address (iOS `roomSenderName`). */
    private suspend fun senderName(address: String, me: String): String {
        val alias = runCatching { database.contactDao().getContact(address, me)?.alias?.trim() }.getOrNull()
        return com.kachat.app.models.addressDisplayName(address, alias = alias)
    }

    companion object {
        /** A bell row from room history at most this old (iOS audit 2026-11-27 M8). */
        const val MENTION_MAX_AGE_MS = 7L * 86_400_000

        /** Whether a stored room message at [blockTimeMs] may become a mention row: since the
         *  room was joined ([joinedAtMs], null when it isn't), and at most a week old. Pure. */
        fun isRecentEnough(blockTimeMs: Long, joinedAtMs: Long?, nowMs: Long): Boolean =
            blockTimeMs >= maxOf(nowMs - MENTION_MAX_AGE_MS, joinedAtMs ?: 0L)

        /** Whether a mention push for [room] (already normalized and valid) becomes a bell row
         *  of its own: a chat room you haven't joined, with a txid and some text. Pure. */
        fun isUnjoinedMentionPush(room: String, txId: String?, preview: String, joined: Set<String>): Boolean {
            if (room in FeaturedBroadcastChannels.SERVICE_CHANNELS || room in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS) return false
            if (room in joined) return false
            if (txId == null || !com.kachat.app.repository.PushBannerFetch.isTxIdShaped(txId)) return false
            return preview.isNotBlank()
        }

        /** A row that could be a mention in the bell: a delivered message in a chat room (not the
         *  machinery rooms), from someone else, that isn't a reaction, edit or delete. Pure. */
        fun isCandidate(channel: String, sender: String, content: String, me: String): Boolean {
            if (channel in FeaturedBroadcastChannels.SERVICE_CHANNELS ||
                channel in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS
            ) return false
            if (sender.equals(me, ignoreCase = true)) return false
            if (!content.contains('@')) return false
            return BroadcastRepository.isReadableContent(content)
        }
    }
}
