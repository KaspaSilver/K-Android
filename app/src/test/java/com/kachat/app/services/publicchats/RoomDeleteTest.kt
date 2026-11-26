package com.kachat.app.services.publicchats

import com.kachat.app.repository.IndexerAbsence
import com.kachat.app.util.MessageDelete
import com.kachat.app.util.isRoomControlEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS e4062188: Delete for Everyone's wire format, its record, and the rules for dropping rows
 *  the indexer no longer serves. */
class RoomDeleteTest {
    private val tx = "ab".repeat(32)

    @Test
    fun `the delete envelope is byte for byte iOS's`() {
        assertEquals("{\"type\":\"delete\",\"targetTxId\":\"$tx\"}", MessageDelete.encode(tx))
    }

    @Test
    fun `a delete parses, round trip and re-spaced`() {
        assertEquals(tx, MessageDelete.parseOrNull(MessageDelete.encode(tx))?.targetTxId)
        assertEquals(tx, MessageDelete.parseOrNull("  { \"targetTxId\" : \"$tx\", \"type\" : \"delete\" }\n")?.targetTxId)
    }

    @Test
    fun `anything else isn't a delete`() {
        assertNull(MessageDelete.parseOrNull(null))
        assertNull(MessageDelete.parseOrNull("delete $tx"))
        assertNull(MessageDelete.parseOrNull("{\"type\":\"edit\",\"targetTxId\":\"$tx\",\"text\":\"delete\"}"))
        assertNull(MessageDelete.parseOrNull("{\"type\":\"delete\",\"targetTxId\":\"\"}"))
        assertNull(MessageDelete.parseOrNull("{\"type\":\"delete\"}"))
        // the size guard: 2,000 UTF-8 bytes or more
        assertNull(MessageDelete.parseOrNull("{\"type\":\"delete\",\"targetTxId\":\"$tx\",\"pad\":\"${"x".repeat(2_000)}\"}"))
    }

    @Test
    fun `a cut-off delete push is still a delete`() {
        assertTrue(MessageDelete.isDeleteEnvelope("{\"type\": \"delete\",\"targetTxId\":\"ab"))
        assertFalse(MessageDelete.isDeleteEnvelope("please delete this"))
    }

    @Test
    fun `deletes, edits and reactions are never message rows`() {
        assertTrue(isRoomControlEnvelope(MessageDelete.encode(tx)))
        assertTrue(isRoomControlEnvelope(com.kachat.app.util.MessageEdit.encode(tx, "new")))
        assertFalse(isRoomControlEnvelope("hello"))
        assertFalse(isRoomControlEnvelope("{\"type\":\"delete\"}"))
        assertTrue(com.kachat.app.repository.BroadcastRepository.isReadableContent("hello"))
        assertFalse(com.kachat.app.repository.BroadcastRepository.isReadableContent(MessageDelete.encode(tx)))
    }

    @Test
    fun `a delete is kept as room, txid and sender, newest 2000`() {
        assertEquals("kaspa|$tx|kaspa:qq", RoomDeletes.entry(" Kaspa ", tx.uppercase(), "KASPA:QQ"))
        val set = LinkedHashSet<String>()
        assertTrue(RoomDeletes.remember(set, "a", limit = 2))
        assertFalse(RoomDeletes.remember(set, "a", limit = 2))
        RoomDeletes.remember(set, "b", limit = 2)
        RoomDeletes.remember(set, "c", limit = 2)
        assertEquals(listOf("b", "c"), set.toList())
    }

    // The span and drop rules of iOS d089011f ("the indexer is the truth"): IndexerAbsenceTest.

    @Test
    fun `a row goes on its second miss, and a row back on a page is forgiven`() {
        val strikes = IndexerAbsence.Strikes()
        val gap = IndexerAbsence.MIN_STRIKE_GAP_MS
        assertTrue(strikes.strike("kaspa", listOf("a", "b"), onPage = setOf("b"), nowMs = 0).isEmpty())
        assertEquals(setOf("a"), strikes.strike("kaspa", listOf("a", "b"), onPage = setOf("b"), nowMs = gap))
        // c missed once, then seen: starts over
        strikes.strike("kaspa", listOf("c"), onPage = emptySet(), nowMs = 0)
        strikes.strike("kaspa", listOf("c"), onPage = setOf("c"), nowMs = gap)
        assertTrue(strikes.strike("kaspa", listOf("c"), onPage = emptySet(), nowMs = 2 * gap).isEmpty())
    }

    @Test
    fun `misses from one burst of reads count once (audit T3)`() {
        val strikes = IndexerAbsence.Strikes()
        val start = 1_000_000L
        // the push-banner fetch: four reads, 800 ms apart
        for (i in 0 until 4) {
            assertTrue(strikes.strike("kaspa", listOf("a"), onPage = emptySet(), nowMs = start + i * 800L).isEmpty())
        }
        // a separate read later is the second miss
        assertEquals(
            setOf("a"),
            strikes.strike("kaspa", listOf("a"), onPage = emptySet(), nowMs = start + IndexerAbsence.MIN_STRIKE_GAP_MS)
        )
    }

    @Test
    fun `only a real reaction or edit names a removed message, never a reply saying edit (audit T4)`() {
        val target = com.kachat.app.repository.BroadcastRepository.Companion
        assertEquals(tx, target.controlTarget(com.kachat.app.util.MessageEdit.encode(tx, "new")))
        assertEquals(tx, target.controlTarget(com.kachat.app.util.MessageReaction.encode(tx, "👍", "add")))
        assertNull(target.controlTarget(com.kachat.app.util.MessageReply.encode(tx, "kaspa:qq", "edit", "edit")))
        assertNull(target.controlTarget(com.kachat.app.util.MessageReply.encode(tx, "kaspa:qq", "hi", "reaction")))
        assertNull(target.controlTarget("hello"))
    }
}
