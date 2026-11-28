package com.kachat.app.services.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The point under the finger, by binary search (iOS 01f71184 `ExplorerChartSelection`). */
class ExplorerChartPickTest {
    private val points = listOf(100L, 200L, 300L, 400L).map { ExplorerChartPoint(it, it / 10.0) }

    @Test
    fun nearestPicksTheClosestPoint() {
        assertEquals(100L, ExplorerChartPick.nearest(points, 0L)?.date)
        assertEquals(200L, ExplorerChartPick.nearest(points, 240L)?.date)
        assertEquals(300L, ExplorerChartPick.nearest(points, 260L)?.date)
        // a tie goes to the earlier point, as iOS's `<=`
        assertEquals(200L, ExplorerChartPick.nearest(points, 250L)?.date)
        assertEquals(400L, ExplorerChartPick.nearest(points, 9_999L)?.date)
        assertNull(ExplorerChartPick.nearest(emptyList(), 5L))
    }

    @Test
    fun nearestMatchesALinearScan() {
        val many = (0 until 240).map { ExplorerChartPoint(it * 37L + (it % 5), it.toDouble()) }
        for (date in -50L..9_000L step 7) {
            val linear = many.minByOrNull { kotlin.math.abs(it.date - date) }
            assertEquals(kotlin.math.abs(linear!!.date - date), kotlin.math.abs(ExplorerChartPick.nearest(many, date)!!.date - date))
        }
    }

    @Test
    fun spanningIsTheBarHoldingTheDate() {
        assertEquals(100L, ExplorerChartPick.spanning(points, 50L)?.date)
        assertEquals(100L, ExplorerChartPick.spanning(points, 100L)?.date)
        assertEquals(200L, ExplorerChartPick.spanning(points, 299L)?.date)
        assertEquals(400L, ExplorerChartPick.spanning(points, 1_000L)?.date)
        assertNull(ExplorerChartPick.spanning(emptyList(), 5L))
    }
}
