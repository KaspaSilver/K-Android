package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** KaPost's action row: the widest gap that fits, else the tightest with the last items giving
 *  up width (iOS 518fdc8c `KaPostActionRowLayout`, audit 2026-11-24 #24). */
class KaPostActionRowPlanTest {
    private val gaps = listOf(18, 12, 8)

    @Test
    fun `the widest gap that fits is taken`() {
        // five items of 20 and four gaps: 18 needs 172, 12 needs 148
        val widths = List(5) { 20 }
        val trailing = List(5) { false }
        assertEquals(18, KaPostActionRowPlan.plan(widths, trailing, gaps, 172) { 0 }.gap)
        assertEquals(12, KaPostActionRowPlan.plan(widths, trailing, gaps, 171) { 0 }.gap)
        assertEquals(8, KaPostActionRowPlan.plan(widths, trailing, gaps, 132) { 0 }.gap)
    }

    @Test
    fun `nothing shrinks when a gap fits`() {
        val widths = listOf(20, 30, 40)
        val plan = KaPostActionRowPlan.plan(widths, listOf(false, false, true), gaps, 500) { 0 }
        assertEquals(widths, plan.widths)
    }

    @Test
    fun `when even 8 doesn't fit, the last leading item gives up width first, then the trailing one`() {
        // natural 20+20+50 + trailing 40 = 130, three gaps of 8 = 154; 10 over
        val widths = listOf(20, 20, 50, 40)
        val trailing = listOf(false, false, false, true)
        val plan = KaPostActionRowPlan.plan(widths, trailing, gaps, 144) { i -> if (i == 2) 16 else 16 }
        assertEquals(8, plan.gap)
        assertEquals(listOf(20, 20, 40, 40), plan.widths)
    }

    @Test
    fun `items shrink no further than their smallest, then the next one gives`() {
        // 30 over: the last leading item can give 10 (50 -> 40), the one before it 4 (20 -> 16),
        // then the trailing one the rest
        val widths = listOf(20, 20, 50, 40)
        val trailing = listOf(false, false, false, true)
        val smallest = listOf(16, 16, 40, 16)
        val plan = KaPostActionRowPlan.plan(widths, trailing, gaps, 124) { smallest[it] }
        assertEquals(listOf(16, 16, 40, 28), plan.widths)
    }

    @Test
    fun `an empty item takes no gap`() {
        // the delivery state that shows nothing: two items of 50, one gap
        val plan = KaPostActionRowPlan.plan(listOf(50, 50, 0), listOf(false, false, true), gaps, 118) { 0 }
        assertEquals(18, plan.gap)
    }
}
