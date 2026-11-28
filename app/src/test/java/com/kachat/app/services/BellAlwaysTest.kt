package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 01f71184: Received KAS always in the bell, and the release audit's bell items (R2, B6, P9, R6). */
class BellAlwaysTest {
    private val now = 1_800_000_000_000L

    private fun entry(id: String, ts: Long) =
        GlobalNotificationCenterStore.Entry(id, "wallet", "t", "b", ts, null)

    // R2: every address has a catch-up floor

    @Test
    fun `the chatting address's first look only sets its floor`() {
        assertTrue(AddressActivityNotifier.isFirstLookOnly(storedFloor = null, isChatting = true, rings = true))
        assertTrue(AddressActivityNotifier.isFirstLookOnly(storedFloor = null, isChatting = true, rings = false))
    }

    @Test
    fun `a quiet address's first look only sets its floor, one that rings looks as before`() {
        assertTrue(AddressActivityNotifier.isFirstLookOnly(storedFloor = null, isChatting = false, rings = false))
        assertFalse(AddressActivityNotifier.isFirstLookOnly(storedFloor = null, isChatting = false, rings = true))
    }

    @Test
    fun `once a floor is set every look files what is newer`() {
        assertFalse(AddressActivityNotifier.isFirstLookOnly(storedFloor = now, isChatting = true, rings = false))
        assertFalse(AddressActivityNotifier.isFirstLookOnly(storedFloor = now, isChatting = false, rings = false))
    }

    @Test
    fun `a transaction at or below the floor is not filed again`() {
        val floor = now - 60_000L
        assertTrue(AddressActivityNotifier.isAtOrBelowFloor(floor, floor, now))
        assertTrue(AddressActivityNotifier.isAtOrBelowFloor(floor - 1, floor, now))
        assertFalse(AddressActivityNotifier.isAtOrBelowFloor(floor + 1, floor, now))
        // an unknown block time is now
        assertFalse(AddressActivityNotifier.isAtOrBelowFloor(null, floor, now))
        // no floor yet (a ringing address's first look): everything counts
        assertFalse(AddressActivityNotifier.isAtOrBelowFloor(now - 90L * 86_400_000L, 0L, now))
    }

    // B6: three transactions, not ten

    @Test
    fun `a catch-up reads three transactions`() {
        assertEquals(3, AddressActivityNotifier.CATCH_UP_TX_LIMIT)
    }

    // R6: REST sums stop at the top instead of wrapping

    @Test
    fun `amounts sum without wrapping negative`() {
        assertEquals(30L, AddressActivityNotifier.saturatingSum(listOf(10L, 20L)))
        assertEquals(Long.MAX_VALUE, AddressActivityNotifier.saturatingSum(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(Long.MAX_VALUE, AddressActivityNotifier.saturatingSum(listOf(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE / 2 + 1)))
        assertEquals(0L, AddressActivityNotifier.saturatingSum(emptyList()))
    }

    // P9: the bell inserts in place

    @Test
    fun `a row goes ahead of rows with the same time`() {
        val rows = listOf(entry("a", now), entry("b", now - 1_000L))
        val updated = GlobalNotificationCenterStore.withEntry(rows, entry("c", now), max = 10)
        assertEquals(listOf("c", "a", "b"), updated.map { it.id })
    }

    @Test
    fun `a row older than everything in a full list is dropped at once`() {
        val rows = (0 until 3).map { entry("r$it", now - it * 1_000L) }
        val updated = GlobalNotificationCenterStore.withEntry(rows, entry("old", now - 10_000L), max = 3)
        assertSame(rows, updated)
    }

    @Test
    fun `an older row joins the end of a list with room`() {
        val rows = (0 until 2).map { entry("r$it", now - it * 1_000L) }
        val updated = GlobalNotificationCenterStore.withEntry(rows, entry("old", now - 10_000L), max = 3)
        assertEquals(listOf("r0", "r1", "old"), updated.map { it.id })
    }

    @Test
    fun `a full list keeps its cap and loses its oldest`() {
        val rows = (0 until 3).map { entry("r$it", now - it * 1_000L) }
        val updated = GlobalNotificationCenterStore.withEntry(rows, entry("mid", now - 500L), max = 3)
        assertEquals(listOf("r0", "mid", "r1"), updated.map { it.id })
        val empty = GlobalNotificationCenterStore.withEntry(emptyList(), entry("only", now), max = 3)
        assertEquals(listOf("only"), empty.map { it.id })
    }
}
