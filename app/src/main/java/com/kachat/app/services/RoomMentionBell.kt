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
        for (row in rows) {
            if (!isCandidate(row.channelName, row.senderAddress, row.content, me)) continue
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

    /** Your name for them, else their .kachat name, else a short address (iOS `roomSenderName`). */
    private suspend fun senderName(address: String, me: String): String {
        val alias = runCatching { database.contactDao().getContact(address, me)?.alias?.trim() }.getOrNull()
        return com.kachat.app.models.addressDisplayName(address, alias = alias)
    }

    companion object {
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
