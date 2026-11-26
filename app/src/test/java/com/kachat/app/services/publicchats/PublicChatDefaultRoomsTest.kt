package com.kachat.app.services.publicchats

import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.PushBannerFetch
import com.kachat.app.ui.screens.selectedRoomNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 961fc05a: #report-bugs, indexer-listed rooms, the dropped-room grace and the room push's
 *  wait for its message. */
class PublicChatDefaultRoomsTest {
    private val day = 86_400_000L

    @Test
    fun `report-bugs is a default room, kachat-bugs an ordinary one again`() {
        assertEquals(listOf("kaspa", "report-bugs"), FeaturedBroadcastChannels.NAMES)
        // iOS ef760cfa: nothing is retired
        assertTrue(FeaturedBroadcastChannels.RETIRED.isEmpty())
        assertEquals("kachat-bugs", FeaturedBroadcastChannels.replacingRetired("kachat-bugs"))
        assertFalse("kachat-bugs" in FeaturedBroadcastChannels.BUILT_IN_CURATED_NAMES)
    }

    @Test
    fun `a link to kachat-bugs opens kachat-bugs`() {
        assertEquals("kachat-bugs", PublicChatLink.normalizeAndValidateChannel("#kachat-bugs"))
        assertEquals("kachat-bugs", PublicChatLink.normalizeAndValidateChannel(" KaChat-Bugs "))
        assertEquals("kachat-bugs", PublicChatLink.parse("https://kachat.app/broadcast/kachat-bugs"))
        assertEquals("kaspa", PublicChatLink.normalizeAndValidateChannel("kaspa"))
    }

    @Test
    fun `indexer rooms leave out built-in and machinery rooms, kachat-bugs under More Rooms`() {
        val reported = listOf("kaspa", "report-bugs", "kaspa-german", "chess-arena", "chess-bots",
            "kplay-arcade", "kachat-bugs", "miners", "dev-talk", "bad name")
        assertEquals(listOf("kachat-bugs", "miners", "dev-talk"), FeaturedBroadcastChannels.indexerDefaultNames(reported))
    }

    @Test
    fun `a switched-off room the indexer drops is kept for the grace period`() {
        val curated = setOf("kaspa", "report-bugs")
        val now = 100 * day
        // newly missing: its clock starts, nothing dropped yet
        val first = DroppedDefaultRooms.step(emptyMap(), setOf("miners"), curated, now)
        assertEquals(mapOf("miners" to now), first.missingSince)
        assertTrue(first.dropped.isEmpty())
        // still missing six days later: kept
        val later = DroppedDefaultRooms.step(first.missingSince, setOf("miners"), curated, now + 6 * day)
        assertEquals(first.missingSince, later.missingSince)
        assertTrue(later.dropped.isEmpty())
        // a week: forgotten, and its clock goes
        val week = DroppedDefaultRooms.step(first.missingSince, setOf("miners"), curated, now + 7 * day)
        assertEquals(setOf("miners"), week.dropped)
        assertTrue(week.missingSince.isEmpty())
    }

    @Test
    fun `a room back on the list or switched on again stops its clock`() {
        val saved = mapOf("miners" to 0L, "dev-talk" to 0L)
        // miners is listed again; dev-talk was switched on (no longer hidden)
        val step = DroppedDefaultRooms.step(saved, setOf("miners"), setOf("miners"), 3 * day)
        assertTrue(step.missingSince.isEmpty())
        assertTrue(step.dropped.isEmpty())
    }

    @Test
    fun `a joined room the indexer listed and dropped goes after two hours`() {
        val now = 100 * day
        val hour = 3_600_000L
        val curated = setOf("kaspa", "report-bugs")
        val first = DroppedDefaultRooms.step(emptyMap(), emptySet(), curated, now, indexerListed = setOf("miners"))
        assertEquals(mapOf("miners" to now), first.missingSince)
        assertTrue(first.dropped.isEmpty())
        // one hourly read later: still kept
        assertTrue(DroppedDefaultRooms.step(first.missingSince, emptySet(), curated, now + hour, setOf("miners")).dropped.isEmpty())
        // two: left
        val gone = DroppedDefaultRooms.step(first.missingSince, emptySet(), curated, now + 2 * hour, setOf("miners"))
        assertEquals(setOf("miners"), gone.dropped)
        assertTrue(gone.missingSince.isEmpty())
    }

    @Test
    fun `a switched-off listed room keeps the week, one back on the list has no clock`() {
        val now = 100 * day
        val saved = mapOf("miners" to now - 3 * 3_600_000L, "dev-talk" to now - 3 * 3_600_000L)
        // miners switched off: the week's grace; dev-talk back on the list: its clock stops
        val step = DroppedDefaultRooms.step(saved, setOf("miners"), setOf("dev-talk"), now, indexerListed = setOf("miners", "dev-talk"))
        assertTrue(step.dropped.isEmpty())
        assertEquals(setOf("miners"), step.missingSince.keys)
    }

    @Test
    fun `a room the indexer never listed has no clock`() {
        // one the person made, or one with its own indexer: never in the indexer-listed set
        val step = DroppedDefaultRooms.step(emptyMap(), emptySet(), setOf("kaspa"), 100 * day, indexerListed = emptySet())
        assertTrue(step.missingSince.isEmpty())
    }

    @Test
    fun `listed rooms are noted, and one off the list is kept only while joined`() {
        // iOS 35b112ce: joined by name or posted in no longer matters
        val noted = DroppedDefaultRooms.notedIndexerListed(
            saved = setOf("miners", "old-left", "old-joined"),
            listed = setOf("dev-talk", "kachat-bugs"),
            legacy = setOf("from-before"),
            joined = setOf("miners", "old-joined", "from-before", "my-own"),
        )
        assertEquals(setOf("miners", "old-joined", "from-before", "dev-talk", "kachat-bugs"), noted)
    }

    @Test
    fun `a retired room's pin moves to its replacement`() {
        assertEquals(listOf("a", "report-bugs", "b"), DroppedDefaultRooms.movedPins(listOf("a", "kachat-bugs", "b"), "kachat-bugs", "report-bugs"))
        // already pinned: the old pin just goes
        assertEquals(listOf("report-bugs", "b"), DroppedDefaultRooms.movedPins(listOf("report-bugs", "kachat-bugs", "b"), "kachat-bugs", "report-bugs"))
        assertNull(DroppedDefaultRooms.movedPins(listOf("a"), "kachat-bugs", "report-bugs"))
    }

    @Test
    fun `only a txid is waited for`() {
        assertTrue(PushBannerFetch.isTxIdShaped("ab".repeat(32)))
        assertTrue(PushBannerFetch.isTxIdShaped("AB".repeat(32)))
        assertFalse(PushBannerFetch.isTxIdShaped("ab".repeat(31)))
        assertFalse(PushBannerFetch.isTxIdShaped("zz".repeat(32)))
    }

    @Test
    fun `Select on the rooms page reads the picked rooms`() {
        assertEquals(listOf("kaspa", "miners"), selectedRoomNames(setOf("r:miners", "c:kaspa:qq", "r:kaspa", "g:abc")))
    }
}
