package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-27 E1: the feed's top anchor row, and Back to Top around it. */
class KaPostsFeedTopAnchorTest {

    @Test
    fun `the anchor is the feed list's first row`() {
        assertEquals(0, FEED_TOP_ANCHOR_INDEX)
    }

    @Test
    fun `back to top shows once the first post has scrolled away, as before the anchor`() {
        // anchor at the top, then the first post at the top
        assertFalse(feedAwayFromTop(0))
        assertFalse(feedAwayFromTop(1))
        // the first post is gone
        assertTrue(feedAwayFromTop(2))
    }
}
