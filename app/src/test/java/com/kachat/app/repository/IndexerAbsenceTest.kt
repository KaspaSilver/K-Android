package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS d089011f: for a room the indexer tracks, the indexer is the truth. */
class IndexerAbsenceTest {
    private val day = 86_400_000L
    private val retention = 30 * day
    private val now = 100 * day

    @Test
    fun `the newest page answers from a minute above its oldest row up to 90 seconds ago`() {
        // a quiet room: the newest page's rows are hours old, yet it answers up to now - 90 s,
        // so a deleted newest message goes too
        val span = IndexerAbsence.span(oldest = now - 6 * 3_600_000L, before = null, now = now, retentionMs = retention)!!
        assertEquals(now - 6 * 3_600_000L + 60_000, span.first)
        assertEquals(now - 90_000, span.last)
        // a message 2 minutes old is inside, one a minute old is the indexer's catch-up time
        assertTrue(now - 120_000 in span)
        assertFalse(now - 60_000 in span)
    }

    @Test
    fun `an older page answers between its oldest row and before, a minute inside both`() {
        val before = now - 19 * day
        val older = IndexerAbsence.span(oldest = now - 21 * day, before = before, now = now, retentionMs = retention)!!
        assertEquals(now - 21 * day + 60_000, older.first)
        assertEquals(before - 1 - 60_000, older.last)
        // `before` near now: still never the last 90 seconds
        val recent = IndexerAbsence.span(oldest = now - 3_600_000L, before = now - 10_000, now = now, retentionMs = retention)!!
        assertEquals(now - 90_000, recent.last)
    }

    @Test
    fun `never below the page's oldest row nor in the oldest day of the window`() {
        val edge = IndexerAbsence.span(oldest = now - 30 * day, before = null, now = now, retentionMs = retention)!!
        assertEquals(now - retention + day, edge.first)
        // a page whose only row is newer than 90 s ago answers for nothing
        assertNull(IndexerAbsence.span(oldest = now - 30_000, before = null, now = now, retentionMs = retention))
        // an older page one minute long: nothing inside its edges
        assertNull(IndexerAbsence.span(oldest = now - day - 60_000, before = now - day, now = now, retentionMs = retention))
    }

    @Test
    fun `your own messages count once 5 minutes old, anyone else's always`() {
        assertTrue(IndexerAbsence.considers(isOwn = false, blockTime = now - 100_000, now = now))
        assertFalse(IndexerAbsence.considers(isOwn = true, blockTime = now - 100_000, now = now))
        assertFalse(IndexerAbsence.considers(isOwn = true, blockTime = now - 300_000, now = now))
        assertTrue(IndexerAbsence.considers(isOwn = true, blockTime = now - 300_001, now = now))
    }

    @Test
    fun `the first read after a room opens removes on one miss, others on the second`() {
        assertEquals(1, IndexerAbsence.missesNeeded(firstMissRemoves = true))
        assertEquals(2, IndexerAbsence.missesNeeded(firstMissRemoves = false))
        val strikes = IndexerAbsence.Strikes()
        assertEquals(setOf("a"), strikes.strike("kaspa", listOf("a", "b"), onPage = setOf("b"), nowMs = 0, needed = 1))
        // a background read: one miss is not enough
        assertTrue(strikes.strike("kaspa", listOf("c"), onPage = emptySet(), nowMs = 0, needed = 2).isEmpty())
    }

    @Test
    fun `no brake - every missing message goes at once`() {
        val strikes = IndexerAbsence.Strikes()
        val held = (1..50).map { "tx$it" }
        assertTrue(strikes.strike("kaspa", held, onPage = emptySet(), nowMs = 0).isEmpty())
        assertEquals(held.toSet(), strikes.strike("kaspa", held, onPage = emptySet(), nowMs = 8_000))
    }

    @Test
    fun `two 8 second polls are two misses, a push burst is one (audit T3)`() {
        val strikes = IndexerAbsence.Strikes()
        // the open room's poll: about 16 s, as iOS
        assertTrue(strikes.strike("kaspa", listOf("a"), onPage = emptySet(), nowMs = 0).isEmpty())
        assertEquals(setOf("a"), strikes.strike("kaspa", listOf("a"), onPage = emptySet(), nowMs = 8_000))
        // the push-banner fetch: up to four reads in four seconds
        for (i in 0 until 4) {
            assertTrue(strikes.strike("kaspa", listOf("b"), onPage = emptySet(), nowMs = 100_000L + i * 1_100L).isEmpty())
        }
    }

    @Test
    fun `the Rooms list checks bell-off indexed rooms nothing keeps current, every 2 minutes`() {
        val rooms = listOf("kaspa" to false, "bellon" to true, "open" to false, "own" to false, "chess" to false, "recent" to false)
        val checked = mapOf("recent" to now - 60_000L, "kaspa" to now - 120_000L)
        val due = IndexerAbsence.roomsDueForListCheck(
            rooms = rooms,
            isOpen = { it == "open" },
            isIndexed = { it != "own" },
            isExcluded = { it == "chess" },
            lastCheckedAt = { checked[it] },
            now = now,
        )
        assertEquals(listOf("kaspa"), due)
    }
}
