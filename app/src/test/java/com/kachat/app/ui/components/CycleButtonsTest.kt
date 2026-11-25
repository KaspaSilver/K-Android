package com.kachat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cycle button's stepping and its dots-or-count rule (iOS `CycleCapsuleButton`, 518fdc8c). */
class CycleButtonsTest {
    @Test
    fun `each tap goes to the next stop and round again`() {
        val feeds = listOf("Following", "Feed", "Popular")
        assertEquals("Feed", CycleButtons.next(feeds, "Following"))
        assertEquals("Popular", CycleButtons.next(feeds, "Feed"))
        assertEquals("Following", CycleButtons.next(feeds, "Popular"))
    }

    @Test
    fun `a selection not among the stops steps to the second, as iOS's firstIndex ?? 0 does`() {
        assertEquals(2, CycleButtons.next(listOf(1, 2, 3), 9))
    }

    @Test
    fun `no stops, no step`() {
        assertNull(CycleButtons.next(emptyList<Int>(), 0))
    }

    @Test
    fun `one stop stays on itself`() {
        assertEquals("Posts", CycleButtons.next(listOf("Posts"), "Posts"))
    }

    @Test
    fun `six stops or fewer show dots, more show a count`() {
        assertTrue(CycleButtons.showsDots(2))
        assertTrue(CycleButtons.showsDots(6))
        assertFalse(CycleButtons.showsDots(7))
        assertFalse(CycleButtons.showsDots(14))
    }

    @Test
    fun `the count is one based`() {
        val boards = (1..14).toList()
        assertEquals("3/14", CycleButtons.positionLabel(boards, 3))
        assertEquals("1/14", CycleButtons.positionLabel(boards, 1))
        assertEquals("14/14", CycleButtons.positionLabel(boards, 14))
    }

    @Test
    fun `the fade is iOS's quarter second`() {
        assertEquals(250, CycleButtons.FADE_MILLIS)
    }
}
