package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Test

/** The group block stream's retry backs off instead of redialling every 5 s (audit 2026-11-25 N1). */
class GroupStreamBackoffTest {

    @Test
    fun theFirstRetryComesIn5s() {
        assertEquals(5_000L, GroupScanningService.retryDelayMs(0))
        assertEquals(5_000L, GroupScanningService.retryDelayMs(1))
    }

    @Test
    fun failuresDoubleToTwoMinutes() {
        assertEquals(
            listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L),
            (2..8).map { GroupScanningService.retryDelayMs(it) },
        )
        assertEquals(120_000L, GroupScanningService.retryDelayMs(10_000))
    }
}
