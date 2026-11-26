package com.kachat.app.ui.screens

import com.kachat.app.services.GlobalNotificationCenterStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A public chat to open at one message (a mention in Profile's bell, iOS d089011f
 * `PublicChatJumpRoute`): the room's screen takes it as it shows, or at once when it is already
 * open, and scrolls to the message if it's loaded; an older message not in the loaded window
 * leaves the room opening as usual.
 */
object PublicChatJumpRoute {
    data class Jump(val channel: String, val txId: String)

    val pending = MutableStateFlow<Jump?>(null)

    /** The jump for [channelName], taken (cleared) - null when there's none for this room. */
    fun take(channelName: String): Jump? {
        val jump = pending.value ?: return null
        if (normalize(jump.channel) != normalize(channelName)) return null
        pending.value = null
        return jump
    }

    private fun normalize(name: String) = com.kachat.app.util.MessageProtocol.normalizeChannelName(name)
}

/** A group to open from a mention in Profile's bell (iOS `pendingGroupNavigation` + `.openGroup`):
 *  the shell shows Chats, then the group on top of it. */
object MentionGroupRoute {
    val pending = MutableStateFlow<String?>(null)
}

/**
 * A mention row tapped in Profile's bell (iOS d089011f `GlobalNotificationCenter.open(.mention)`):
 * a room opens at the message, a group opens on Chats. [close] closes the bell first, then runs
 * what it is given.
 */
fun openMentionEntry(entry: GlobalNotificationCenterStore.Entry, close: (then: () -> Unit) -> Unit) {
    when (val target = GlobalNotificationCenterStore.parseMentionTarget(entry.targetId)) {
        is GlobalNotificationCenterStore.Companion.MentionTarget.Room -> close {
            if (target.txId.isNotEmpty()) PublicChatJumpRoute.pending.value = PublicChatJumpRoute.Jump(target.channel, target.txId)
            if (!BroadcastDeepLink.request(target.channel)) PublicChatJumpRoute.pending.value = null
        }
        is GlobalNotificationCenterStore.Companion.MentionTarget.Group -> close {
            MentionGroupRoute.pending.value = target.groupId
        }
        null -> close {}
    }
}
