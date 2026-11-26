package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Test

/** The node pool's probe cadence (audit 2026-11-25 N8, N9, N13). */
class NodeProbeCadenceTest {

    @Test
    fun `a healthy pool sleeps to the foreground beat`() {
        assertEquals(NodeProbeCadence.Wait(30_000L, onBeat = true), NodeProbeCadence.automatic(0, 8, 8, 0, metered = false))
        assertEquals(NodeProbeCadence.Wait(60_000L, onBeat = true), NodeProbeCadence.automatic(0, 9, 8, 3, metered = true))
    }

    @Test
    fun `a pool short of nodes stretches 10, 20, 40, then 60 s while it doesn't grow`() {
        val waits = (0..5).map { NodeProbeCadence.automatic(0, 3, 8, it, metered = false).millis }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun `the stall count resets when the pool grows or is healthy`() {
        assertEquals(1, NodeProbeCadence.nextStallCycles(previous = 0, activeBefore = 3, activeAfter = 3, target = 8))
        assertEquals(3, NodeProbeCadence.nextStallCycles(previous = 2, activeBefore = 4, activeAfter = 2, target = 8))
        assertEquals(0, NodeProbeCadence.nextStallCycles(previous = 4, activeBefore = 3, activeAfter = 4, target = 8))
        assertEquals(0, NodeProbeCadence.nextStallCycles(previous = 4, activeBefore = 8, activeAfter = 8, target = 8))
    }

    @Test
    fun `a dead pool backs off to two minutes`() {
        val waits = (1..6).map { NodeProbeCadence.automatic(it, 0, 8, 0, metered = false) }
        assertEquals(listOf(20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L), waits.map { it.millis })
        assertEquals(setOf(false), waits.map { it.onBeat }.toSet())
    }

    @Test
    fun `the pinned node probes on the beat, and backs off once dead longer than the beat`() {
        assertEquals(NodeProbeCadence.Wait(30_000L, onBeat = true), NodeProbeCadence.trusted(0, 30_000L))
        assertEquals(NodeProbeCadence.Wait(30_000L, onBeat = true), NodeProbeCadence.trusted(1, 30_000L))
        assertEquals(NodeProbeCadence.Wait(40_000L, onBeat = false), NodeProbeCadence.trusted(2, 30_000L))
        assertEquals(NodeProbeCadence.Wait(120_000L, onBeat = false), NodeProbeCadence.trusted(5, 30_000L))
        // Metered: the 60 s beat covers the first two misses.
        assertEquals(NodeProbeCadence.Wait(60_000L, onBeat = true), NodeProbeCadence.trusted(2, 60_000L))
        assertEquals(NodeProbeCadence.Wait(80_000L, onBeat = false), NodeProbeCadence.trusted(3, 60_000L))
    }
}
