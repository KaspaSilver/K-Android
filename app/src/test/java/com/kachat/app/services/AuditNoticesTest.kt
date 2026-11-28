package com.kachat.app.services

import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.publicchats.RoomDeletes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bell, room deletes and the indexer's room list after iOS audit 2026-11-27 (M8, L7, L12, L13). */
class AuditNoticesTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val txA = "a".repeat(64)
    private val sender = "kaspa:qqsendersendersendersendersendersendersendersender00"

    private fun entry(id: String, at: Long) = GlobalNotificationCenterStore.Entry(id, "wallet", "t", "b", at, null)

    // M8: room history mentions

    @Test
    fun `a mention from before you joined the room is not a row`() {
        val joined = now - 2 * day
        assertFalse(RoomMentionBell.isRecentEnough(joined - 1, joined, now))
        assertTrue(RoomMentionBell.isRecentEnough(joined, joined, now))
        assertTrue(RoomMentionBell.isRecentEnough(now - 1_000, joined, now))
    }

    @Test
    fun `a mention more than a week old is not a row, even in a room joined long ago`() {
        val joined = now - 60 * day
        assertFalse(RoomMentionBell.isRecentEnough(now - 8 * day, joined, now))
        assertTrue(RoomMentionBell.isRecentEnough(now - 6 * day, joined, now))
        // a room not joined: the week alone
        assertFalse(RoomMentionBell.isRecentEnough(now - 8 * day, null, now))
        assertTrue(RoomMentionBell.isRecentEnough(now - day, null, now))
    }

    // M8: the bell's trim keeps the newest by time

    @Test
    fun `the bell sorts by time before trimming`() {
        val rows = (0 until 3).map { entry("new$it", now - it * 1_000L) }
        // an old backfilled row arrives last: it is the one trimmed, not the newest
        val kept = GlobalNotificationCenterStore.withEntry(rows, entry("old", now - 30 * day), max = 3)
        assertEquals(listOf("new0", "new1", "new2"), kept.map { it.id })
        // a newer row lands at the top
        val withNew = GlobalNotificationCenterStore.withEntry(rows, entry("newest", now + 1), max = 3)
        assertEquals(listOf("newest", "new0", "new1"), withNew.map { it.id })
        // an older row in the middle keeps the time order
        val middle = GlobalNotificationCenterStore.withEntry(rows, entry("mid", now - 1_500L), max = 10)
        assertEquals(listOf("new0", "new1", "mid", "new2"), middle.map { it.id })
    }

    // L7: deletes for messages not on the phone

    @Test
    fun `a delete for a message not held needs a txid-shaped target`() {
        assertTrue(RoomDeletes.acceptsUnheld(emptySet(), txA, sender))
        assertTrue(RoomDeletes.acceptsUnheld(emptySet(), txA.uppercase(), sender))
        assertFalse(RoomDeletes.acceptsUnheld(emptySet(), "not-a-txid", sender))
        assertFalse(RoomDeletes.acceptsUnheld(emptySet(), "g".repeat(64), sender))
        assertFalse(RoomDeletes.acceptsUnheld(emptySet(), "a".repeat(63), sender))
    }

    @Test
    fun `deletes for messages not held are capped per sender`() {
        val set = LinkedHashSet<String>()
        repeat(RoomDeletes.UNHELD_PER_SENDER) { i ->
            RoomDeletes.remember(set, RoomDeletes.entry("kaspa", i.toString(16).padStart(64, '0'), sender))
        }
        assertFalse(RoomDeletes.acceptsUnheld(set, txA, sender))
        assertFalse(RoomDeletes.acceptsUnheld(set, txA, sender.uppercase()))
        // another sender is not held back
        assertTrue(RoomDeletes.acceptsUnheld(set, txA, "kaspa:qqother"))
    }

    // L12: the indexer's room list

    @Test
    fun `an empty or mostly empty list waits for a second answer`() {
        val reported = setOf("a", "b", "c", "d")
        assertTrue(IndexerTrackedRooms.needsSecondAnswer(reported, emptySet()))
        assertTrue(IndexerTrackedRooms.needsSecondAnswer(reported, setOf("a")))
        // half gone is not more than half
        assertFalse(IndexerTrackedRooms.needsSecondAnswer(reported, setOf("a", "b")))
        assertFalse(IndexerTrackedRooms.needsSecondAnswer(reported, reported + "e"))
        // nothing listed before: any answer is taken
        assertFalse(IndexerTrackedRooms.needsSecondAnswer(emptySet(), emptySet()))
        assertFalse(IndexerTrackedRooms.needsSecondAnswer(emptySet(), setOf("a")))
        // a single listed room dropping out is taken (fewer than two)
        assertFalse(IndexerTrackedRooms.needsSecondAnswer(setOf("a"), setOf("b")))
        assertTrue(IndexerTrackedRooms.needsSecondAnswer(setOf("a"), emptySet()))
    }

    @Test
    fun `each indexer but the default one keeps its own list`() {
        assertEquals("", IndexerTrackedRooms.indexerSuffix(""))
        assertEquals("", IndexerTrackedRooms.indexerSuffix(AppSettingsRepository.DEFAULT_INDEXER_URL))
        assertEquals("", IndexerTrackedRooms.indexerSuffix(AppSettingsRepository.DEFAULT_INDEXER_URL.uppercase() + "/ "))
        assertEquals("@my.indexer.example", IndexerTrackedRooms.indexerSuffix("https://My.Indexer.example/"))
        assertEquals("@my.indexer.example", IndexerTrackedRooms.indexerSuffix("https://my.indexer.example:8443/api"))
    }

    // L13: a mention push from a room you haven't joined

    @Test
    fun `a mention push becomes a row only for an unjoined chat room`() {
        assertTrue(RoomMentionBell.isUnjoinedMentionPush("kaspa", txA, "hi @me", joined = setOf("other")))
        assertFalse(RoomMentionBell.isUnjoinedMentionPush("kaspa", txA, "hi @me", joined = setOf("kaspa")))
        assertFalse(RoomMentionBell.isUnjoinedMentionPush("kaspa", "short", "hi @me", joined = emptySet()))
        assertFalse(RoomMentionBell.isUnjoinedMentionPush("kaspa", null, "hi @me", joined = emptySet()))
        assertFalse(RoomMentionBell.isUnjoinedMentionPush("kaspa", txA, " ", joined = emptySet()))
        for (room in FeaturedBroadcastChannels.SERVICE_CHANNELS + FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS) {
            assertFalse(RoomMentionBell.isUnjoinedMentionPush(room, txA, "hi @me", joined = emptySet()))
        }
    }
}
