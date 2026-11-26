package com.kachat.app.services

import kotlinx.coroutines.delay

/**
 * One shared beat for K's foreground polls (iOS 961fc05a `ForegroundBeat`, audit 2026-11-25 B4).
 *
 * The room sweep, KaPost's poll, the kUSDT poll and the group pass each used to wake on their own
 * schedule (20 s, 20 s, 30 s, 30-60 s), so on cellular the radio, which stays in high power for
 * about 10 s after any traffic, never went idle while K was open. Now each loop sleeps to the same
 * wall-clock beat (every 30 s on Wi-Fi, 60 s on a metered path) and they run within a second or
 * two of each other, so the radio wakes once per beat.
 *
 * A loop calls `ForegroundBeat.sleepUntilNextBeat(offsetMs)` instead of a fixed `delay`. The
 * offset spreads the loops a few hundred ms apart inside the beat. Cancelling the coroutine
 * returns at once. The open chat (2 s), open room (8 s) and open group keep their own cadence.
 */
object ForegroundBeat {
    /** Whether the device is on an expensive (metered) path; set by [NodePoolManager] from
     *  [MeteredNetwork]. Unmetered until then. */
    @Volatile
    var isExpensivePath: () -> Boolean = { false }

    /** 30 s on Wi-Fi, 60 s on a metered path. */
    val intervalMs: Long get() = if (runCatching { isExpensivePath() }.getOrDefault(false)) 60_000L else 30_000L

    /** Sleeps until the next beat (the next whole multiple of [intervalMs] since 1970), plus
     *  [offsetMs]. Never less than one second, so a loop that just ran doesn't run twice in one beat. */
    suspend fun sleepUntilNextBeat(offsetMs: Long = 0L) {
        delay(delayToNextBeat(intervalMs, offsetMs, System.currentTimeMillis()))
    }

    /** The wait until the next beat; pure, for the loops that need the number. */
    fun delayToNextBeat(intervalMs: Long, offsetMs: Long, nowMs: Long): Long {
        var next = (Math.floorDiv(nowMs, intervalMs) + 1) * intervalMs + offsetMs
        if (next - nowMs < 1_000L) next += intervalMs
        return next - nowMs
    }
}
