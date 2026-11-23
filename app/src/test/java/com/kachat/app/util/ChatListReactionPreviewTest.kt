package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS ConversationRow.reactionPreviewText / GroupChatRow (2026-11-22): chain time on both sides. */
class ChatListReactionPreviewTest {

    private val reaction = 1_700_000_010_000L

    @Test
    fun `no message lets the reaction show`() {
        assertTrue(ChatListReactionPreview.reactionShows(null, reaction))
    }

    @Test
    fun `a message not in a block yet always wins`() {
        assertFalse(ChatListReactionPreview.reactionShows(0L, reaction))
    }

    @Test
    fun `a message newer than the reaction wins`() {
        assertFalse(ChatListReactionPreview.reactionShows(reaction + 5_000, reaction))
    }

    @Test
    fun `a message within one second before the reaction still wins`() {
        assertFalse(ChatListReactionPreview.reactionShows(reaction - 1_000, reaction))
        assertFalse(ChatListReactionPreview.reactionShows(reaction - 400, reaction))
    }

    @Test
    fun `a reaction more than one second after the message shows`() {
        assertTrue(ChatListReactionPreview.reactionShows(reaction - 1_001, reaction))
        assertTrue(ChatListReactionPreview.reactionShows(reaction - 60_000, reaction))
    }
}
