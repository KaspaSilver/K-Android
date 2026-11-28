package com.kachat.app.ui.screens

import com.kachat.app.repository.shouldFetchNewestOnJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-27 Smoothness S2 / S7 and release E3 helpers. */
class SmoothnessHelpersTest {
    @Test
    fun qrScaleIsAWholeNumberThatFits() {
        // 33 modules in 420 px (140 dp at 3x): 12 px each, 396 px drawn.
        assertEquals(12, qrPixelsPerModule(33, 420f))
        assertEquals(1, qrPixelsPerModule(33, 10f))
        assertEquals(1, qrPixelsPerModule(0, 420f))
        assertEquals(20, qrPixelsPerModule(25, 500f))
    }

    @Test
    fun activityRowsComeInBatchesUpToTheTotal() {
        assertEquals(5, RowBatch.first(5))
        assertEquals(RowBatch.FIRST, RowBatch.first(100))
        var count = RowBatch.first(100)
        var frames = 0
        while (count < 100) {
            count = RowBatch.next(count, 100)
            frames++
        }
        assertEquals(100, count)
        assertTrue(frames in 1..10)
        assertEquals(100, RowBatch.next(99, 100))
    }

    @Test
    fun joiningANewRoomFetchesItsNewestPage() {
        assertTrue(shouldFetchNewestOnJoin("kaspa", alreadyJoined = false, notify = true))
        assertFalse(shouldFetchNewestOnJoin("kaspa", alreadyJoined = true, notify = true))
        assertFalse(shouldFetchNewestOnJoin("kaspa", alreadyJoined = false, notify = false))
        for (service in com.kachat.app.models.FeaturedBroadcastChannels.SERVICE_CHANNELS) {
            assertFalse(shouldFetchNewestOnJoin(service, alreadyJoined = false, notify = true))
        }
    }
}
