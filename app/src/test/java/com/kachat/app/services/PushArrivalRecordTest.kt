package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS NotificationService `recordPushArrival` / `InAppBanner.pushArrived` (2026-11-22). */
class PushArrivalRecordTest {

    private val now = 1_800_000_000_000L
    private val tx = "AB".repeat(32)

    @Test
    fun `a chat push is recorded under its txid, lowercased`() {
        val keys = PushArrivalRecord.keysFor(mapOf("type" to "contextual", "tx_id" to tx), tag = null, collapseKey = null)
        assertEquals(listOf(tx.lowercase()), keys)
    }

    @Test
    fun `a room push's collapse id and tag count once`() {
        val keys = PushArrivalRecord.keysFor(mapOf("type" to "broadcast"), tag = tx, collapseKey = tx)
        assertEquals(listOf(tx.lowercase()), keys)
    }

    @Test
    fun `a KaPost push also gets the poller's key`() {
        val actor = "02" + "cd".repeat(32)
        val post = "EF".repeat(32)
        val keys = PushArrivalRecord.keysFor(
            mapOf(
                "type" to "kaposts", "thread_id" to "kaposts", "tx_id" to tx,
                "actor_pubkey" to actor, "post_id" to post, "kaposts_kind" to "vote_up",
            ),
            tag = tx, collapseKey = tx,
        )
        assertEquals(listOf(tx.lowercase(), "kaposts:${"cd".repeat(32)}|${post.lowercase()}|like"), keys)
        // the poll's row for the same like
        assertEquals(
            "${"cd".repeat(32)}|${post.lowercase()}|like",
            PushArrivalRecord.kaPostsKey("cd".repeat(32), post, PushArrivalRecord.kaPostsKind("vote", "upvote", hasText = false)),
        )
    }

    @Test
    fun `the kind is read from the wording when the push has none`() {
        assertEquals("dislike", PushArrivalRecord.kaPostsKind(null, "disliked your post"))
        assertEquals("like", PushArrivalRecord.kaPostsKind(null, "liked your post: hi"))
        assertEquals("tip", PushArrivalRecord.kaPostsKind(null, "tipped your post: 0.2 KAS"))
        assertEquals("other", PushArrivalRecord.kaPostsKind(null, "something"))
        assertEquals("repost", PushArrivalRecord.kaPostsKind("quote", null, hasText = false))
        assertEquals("quote", PushArrivalRecord.kaPostsKind("quote", null, hasText = true))
    }

    @Test
    fun `a second push with the same key is seen before`() {
        val (first, seenFirst) = PushArrivalRecord.recorded(emptyMap(), listOf(tx), now)
        assertFalse(seenFirst)
        val (_, seenSecond) = PushArrivalRecord.recorded(first, listOf("other", tx.lowercase()), now + 1_000)
        assertTrue(seenSecond)
    }

    @Test
    fun `an arrival counts for 30 minutes`() {
        val (ledger, _) = PushArrivalRecord.recorded(emptyMap(), listOf(tx), now)
        assertTrue(PushArrivalRecord.arrived(ledger, tx, now + PushArrivalRecord.WINDOW_MS - 1))
        assertFalse(PushArrivalRecord.arrived(ledger, tx, now + PushArrivalRecord.WINDOW_MS))
        assertFalse(PushArrivalRecord.arrived(ledger, "unknown", now))
    }

    @Test
    fun `old arrivals go and only the newest 500 stay`() {
        val old = mapOf("stale" to now - PushArrivalRecord.WINDOW_MS - 1)
        val (pruned, _) = PushArrivalRecord.recorded(old, listOf(tx), now)
        assertFalse(pruned.containsKey("stale"))

        val full = (0 until PushArrivalRecord.LIMIT).associate { "k$it" to now - 1_000 + it }
        val (capped, _) = PushArrivalRecord.recorded(full, listOf(tx), now)
        assertEquals(PushArrivalRecord.LIMIT, capped.size)
        assertTrue(capped.containsKey(tx.lowercase()))
        assertFalse(capped.containsKey("k0"))
    }
}
