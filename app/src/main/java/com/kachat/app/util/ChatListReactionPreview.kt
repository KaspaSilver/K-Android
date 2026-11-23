package com.kachat.app.util

/**
 * Whether a chat row's newest reaction may stand in for its preview (iOS ConversationRow
 * .reactionPreviewText and GroupChatRow, owner 2026-11-22). Both sides are chain time in ms: the
 * last message's block time against the reaction's. A message not in a block yet (no block time)
 * is the newest thing in the chat, and one within a second of the reaction wins too, so a reply
 * right after a reaction never leaves the reaction showing. With no message at all, the reaction
 * shows. Used by the 1:1 rows and the group rows alike.
 */
object ChatListReactionPreview {
    /** How close (ms) a message may be to the reaction and still count as the newer one. */
    const val MESSAGE_WINS_WITHIN_MS = 1_000L

    fun reactionShows(lastMessageBlockTimeMs: Long?, reactionBlockTimeMs: Long): Boolean {
        val lastMs = lastMessageBlockTimeMs ?: return true
        if (lastMs <= 0L) return false
        return lastMs + MESSAGE_WINS_WITHIN_MS < reactionBlockTimeMs
    }
}
