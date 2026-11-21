package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reaction's emoji is one emoji or nothing (iOS 5b7c772c `MessageReactionCodec.isReactionEmoji`,
 * audit #12): anything else reads as a removal of that sender's reaction, and never rings.
 */
class MessageReactionEmojiTest {

    @Test
    fun `one emoji is accepted, in every shape iOS accepts`() {
        listOf(
            "👍", // emoji presentation
            "❤️", // text-default heart made an emoji by U+FE0F
            "👍🏽", // skin tone
            "👨‍👩‍👧", // ZWJ family
            "🇺🇸", // flag: a regional-indicator pair
            "1️⃣", // keycap
            "🏴󠁧󠁢󠁳󠁣󠁴󠁿", // subdivision flag: tags
        ).forEach { assertTrue("expected an emoji: $it", MessageReaction.isReactionEmoji(it)) }
    }

    @Test
    fun `anything but one emoji is refused`() {
        listOf(
            null,
            "",
            "a",
            "ok",
            "❤", // text presentation, alone
            "1", // a keycap base, alone
            "👍👍",
            "🇺🇸🇬🇧",
            "👍a",
            "😀".repeat(20),
            "x".repeat(100_000),
        ).forEach { assertFalse("expected no emoji: ${it?.take(20)}", MessageReaction.isReactionEmoji(it)) }
    }

    @Test
    fun `a valid reaction parses as sent`() {
        val parsed = MessageReaction.parseOrNull(MessageReaction.encode("tx1", "😂", "add"))
        assertNotNull(parsed)
        assertEquals("😂", parsed!!.emoji)
        assertEquals("add", parsed.action)
        assertFalse(MessageReaction.isInvalidReaction(MessageReaction.encode("tx1", "😂", "add")))
    }

    @Test
    fun `an invalid emoji reads as a removal of that sender's reaction`() {
        val text = MessageReaction.encode("tx1", "not an emoji at all", "add")
        val parsed = MessageReaction.parseOrNull(text)
        assertNotNull("still a reaction, never a bubble", parsed)
        assertEquals("tx1", parsed!!.targetTxId)
        assertEquals("", parsed.emoji)
        assertEquals("remove", parsed.action)
        assertTrue(MessageReaction.isInvalidReaction(text))
    }

    @Test
    fun `other envelopes aren't reactions`() {
        assertFalse(MessageReaction.isInvalidReaction("""{"type":"edit","targetTxId":"tx","text":"hi"}"""))
        assertFalse(MessageReaction.isInvalidReaction("hello"))
    }
}
