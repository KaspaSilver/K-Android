package com.kachat.app.ui.screens

import com.kachat.app.services.ForegroundBeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI's battery rules from the 2026-11-25 audit (R2-R5, B10): pure cadences and gates. */
class UiBatteryRulesTest {

    // R3: KaPost's 60 s "new posts" check wakes on the shared beat.

    @Test
    fun `a 60 s check runs on every other 30 s beat`() {
        val every = 60_000L
        var last = 0L
        val runs = mutableListOf<Long>()
        var now = 0L
        repeat(8) {
            now += ForegroundBeat.delayToNextBeat(30_000L, 1_000L, now)
            if (dueOnBeat(last, now, every)) {
                last = now
                runs += now
            }
        }
        // the first beat, 31 s after the start, is too soon; then every second beat
        assertEquals(listOf(61_000L, 121_000L, 181_000L, 241_000L), runs)
    }

    @Test
    fun `a 60 s check runs on every 60 s metered beat`() {
        var last = 0L
        var now = 0L
        var runs = 0
        repeat(5) {
            now += ForegroundBeat.delayToNextBeat(60_000L, 1_000L, now)
            if (dueOnBeat(last, now, 60_000L)) {
                last = now
                runs++
            }
        }
        // every beat: 61 s, 121 s, ...
        assertEquals(5, runs)
    }

    @Test
    fun `a beat landing a little early still counts, a half interval does not`() {
        assertTrue(dueOnBeat(lastRunMs = 0L, nowMs = 59_000L, everyMs = 60_000L))
        assertFalse(dueOnBeat(lastRunMs = 0L, nowMs = 30_000L, everyMs = 60_000L))
    }

    // R2: the funding fallback reads the balance on the beat, never faster than 30 s.

    @Test
    fun `the funding fallback never polls faster than the beat`() {
        var now = 1_700_000_000_123L
        repeat(10) {
            val wait = ForegroundBeat.delayToNextBeat(30_000L, FUNDING_GATE_BEAT_OFFSET_MS, now)
            assertTrue(wait >= 1_000L)
            now += wait
            assertEquals(FUNDING_GATE_BEAT_OFFSET_MS, Math.floorMod(now, 30_000L))
        }
        assertTrue(FUNDING_GATE_BEAT_OFFSET_MS < 30_000L)
    }

    // R4: the fee shimmer sweeps -1..1 every 1.2 s at about 30 fps.

    @Test
    fun `the fee shimmer phase sweeps left to right and restarts`() {
        assertEquals(-1f, feeShimmerPhase(0L), 1e-6f)
        assertEquals(0f, feeShimmerPhase(600L), 1e-6f)
        assertEquals(-1f, feeShimmerPhase(1_200L), 1e-6f)
        assertEquals(feeShimmerPhase(300L), feeShimmerPhase(2_700L), 1e-6f)
        assertTrue(feeShimmerPhase(1_199L) < 1f)
        assertTrue(FEE_SHIMMER_FRAME_MS in 30L..40L)
    }

    @Test
    fun `the spinner steps ten times a second`() {
        assertEquals(100L, com.kachat.app.ui.theme.SPINNER_STEP_MS)
    }

    // R5: the send screens' DAG stops once it has faded behind the outcome.

    @Test
    fun `the send DAG pauses only after Sent or Failed and the fade`() {
        assertFalse(dagSettled(finished = false, alpha = 1f))
        assertFalse(dagSettled(finished = true, alpha = 0.7f))
        assertTrue(dagSettled(finished = true, alpha = 0.35f))
        // Try Again: running again while it fades back in
        assertFalse(dagSettled(finished = false, alpha = 0.35f))
    }
}
