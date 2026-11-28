package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Release audit 2026-11-27, battery section: R2, R6, R8. */
class BatteryReleaseAuditFixTest {

    // R2: the background never discovers

    private fun plan(
        background: Boolean = false,
        metered: Boolean = false,
        healthy: Boolean = false,
        active: Int = 0,
        cycles: Int = 0,
    ) = NodeWavePlan.kind(background, metered, healthy, active, cycles, fullWaveEveryCycles = 10)

    @Test
    fun `the background probes the primary alone whatever the pool's health`() {
        assertEquals(NodeWavePlan.Kind.BACKGROUND_PRIMARY, plan(background = true))
        assertEquals(NodeWavePlan.Kind.BACKGROUND_PRIMARY, plan(background = true, healthy = true))
        assertEquals(NodeWavePlan.Kind.BACKGROUND_PRIMARY, plan(background = true, metered = true, active = 3, cycles = 9))
    }

    @Test
    fun `the foreground keeps its waves`() {
        assertEquals(NodeWavePlan.Kind.PRIMARY, plan(metered = true, healthy = true, active = 8))
        assertEquals(NodeWavePlan.Kind.STEADY, plan(healthy = true, active = 8, cycles = 3))
        assertEquals(NodeWavePlan.Kind.FULL, plan(healthy = true, active = 8, cycles = 9))
        // Short of nodes on Wi-Fi: the full wave every cycle, as before.
        assertEquals(NodeWavePlan.Kind.FULL, plan(active = 3, cycles = 0))
    }

    // R6: an unhealthy pool on a metered path

    @Test
    fun `a metered pool short of nodes probes the known-good ones, with a full wave every tenth cycle`() {
        assertEquals(NodeWavePlan.Kind.KNOWN_GOOD, plan(metered = true, active = 3, cycles = 0))
        assertEquals(NodeWavePlan.Kind.KNOWN_GOOD, plan(metered = true, active = 1, cycles = 8))
        assertEquals(NodeWavePlan.Kind.FULL, plan(metered = true, active = 3, cycles = 9))
        // Nothing Active yet (a cold start on cellular): discovery has to run.
        assertEquals(NodeWavePlan.Kind.FULL, plan(metered = true, active = 0, cycles = 0))
    }

    @Test
    fun `an unhealthy probe lands on the beat once its stretch reaches it`() {
        // Wi-Fi beat 30 s: 10 and 20 s off the beat, 40 and 60 s on it.
        val wifi = (0..3).map { NodeProbeCadence.automatic(0, 3, 8, it, metered = false, beatMs = 30_000L) }
        assertEquals(listOf(false, false, true, true), wifi.map { it.onBeat })
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L), wifi.map { it.millis })
        // Metered beat 60 s: only the 60 s stretch is on it.
        val metered = (0..3).map { NodeProbeCadence.automatic(0, 3, 8, it, metered = true, beatMs = 60_000L) }
        assertEquals(listOf(false, false, false, true), metered.map { it.onBeat })
    }

    @Test
    fun `the first beat at least a wait away`() {
        // At 10 s past a 30 s beat, at least 40 s: the beat at 60 s, 50 s away.
        assertEquals(50_000L, ForegroundBeat.delayToBeatAtLeast(30_000L, 0L, 10_000L, 40_000L))
        // Exactly on a beat 60 s away.
        assertEquals(60_000L, ForegroundBeat.delayToBeatAtLeast(30_000L, 0L, 0L, 60_000L))
        // The offset moves the beat.
        assertEquals(51_200L, ForegroundBeat.delayToBeatAtLeast(30_000L, 1_200L, 10_000L, 40_000L))
        // Never under a second.
        assertEquals(1_000L, ForegroundBeat.delayToBeatAtLeast(30_000L, 0L, 29_000L, 0L))
        for (now in listOf(0L, 7_777L, 59_999L, 123_456L)) {
            val wait = ForegroundBeat.delayToBeatAtLeast(30_000L, 2_000L, now, 45_000L)
            assertTrue(wait >= 45_000L && wait < 75_000L)
            assertEquals(0L, Math.floorMod(now + wait - 2_000L, 30_000L))
        }
    }

    // R8: a weak cellular signal isn't an outage

    @Before
    fun reset() = IndexerHealth.resetForTest()

    private val start = 1_000_000L

    @Test
    fun `slow-link failures on a metered path need two minutes to take the indexer down`() {
        assertFalse(IndexerHealth.recordFailure(start, slowLink = true))
        assertFalse(IndexerHealth.recordFailure(start + 5_000, slowLink = true))
        assertFalse(IndexerHealth.recordFailure(start + 10_000, slowLink = true))
        assertFalse(IndexerHealth.recordFailure(start + 60_000, slowLink = true))
        assertFalse(IndexerHealth.isDown.value)
        assertTrue(IndexerHealth.recordFailure(start + IndexerHealth.SLOW_LINK_RUN_MS, slowLink = true))
        assertTrue(IndexerHealth.isDown.value)
    }

    @Test
    fun `an answer on a flaky link starts the run over`() {
        IndexerHealth.recordFailure(start, slowLink = true)
        IndexerHealth.recordFailure(start + 60_000, slowLink = true)
        IndexerHealth.recordAnswer()
        IndexerHealth.recordFailure(start + 90_000, slowLink = true)
        IndexerHealth.recordFailure(start + 120_000, slowLink = true)
        assertFalse(IndexerHealth.recordFailure(start + 150_000, slowLink = true))
        assertFalse(IndexerHealth.isDown.value)
    }

    @Test
    fun `a real failure in the run takes it down at three as before`() {
        IndexerHealth.recordFailure(start, slowLink = true)
        IndexerHealth.recordFailure(start + 5_000, slowLink = false)
        assertTrue(IndexerHealth.recordFailure(start + 10_000, slowLink = true))
    }

    @Test
    fun `timeouts right after a network change don't count, and on cellular are a slow link`() {
        val now = 10_000_000L
        val changedJustNow = now - 5_000L
        assertEquals(IndexerFailureKind.IGNORED, indexerFailureKind(SocketTimeoutException(), metered = false, nowMs = now, lastNetworkChangeMs = changedJustNow))
        assertEquals(IndexerFailureKind.SLOW_LINK, indexerFailureKind(SocketTimeoutException(), metered = true, nowMs = now, lastNetworkChangeMs = 0L))
        assertEquals(IndexerFailureKind.FAILURE, indexerFailureKind(SocketTimeoutException(), metered = false, nowMs = now, lastNetworkChangeMs = 0L))
        // A refused connection is the server, on any network.
        assertEquals(IndexerFailureKind.FAILURE, indexerFailureKind(ConnectException(), metered = true, nowMs = now, lastNetworkChangeMs = changedJustNow))
        assertEquals(IndexerFailureKind.FAILURE, indexerFailureKind(IOException("boom"), metered = true, nowMs = now, lastNetworkChangeMs = 0L))
    }
}
