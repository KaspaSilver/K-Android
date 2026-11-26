package com.kachat.app.services.grpc

import kotlinx.coroutines.delay

/**
 * The wait before a block stream's next try (audit 2026-11-25 N1): the room, group, inbox and
 * KaPost streams used to redial every 5 s, plus the RPC timeout, for as long as the node was down
 * or the phone offline. Now each try that fails soon after it started waits twice as long as the
 * one before (5 s, 10 s, 20 s ... up to two minutes), a stream that ran for a minute or more
 * starts again from the first wait, and offline the loop waits for the network instead of failing.
 *
 * One per loop (it keeps that loop's count); [pause] takes the place of the fixed `delay`.
 */
class BlockStreamRetry(
    private val firstDelayMs: Long = FIRST_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    /** Suspends until the phone has a network (returns at once when it has). */
    private val awaitOnline: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var attemptStartedAt = now()

    /** Waits before the next try, then for the network. */
    suspend fun pause() {
        failures = failuresAfter(failures, now() - attemptStartedAt)
        delay(delayMs(failures, firstDelayMs, maxDelayMs))
        awaitOnline()
        attemptStartedAt = now()
    }

    companion object {
        const val FIRST_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 120_000L
        /** A try that lasted this long was a working stream, not a failure. */
        const val HEALTHY_RUN_MS = 60_000L

        /** The count after a try that lasted [ranForMs]: a long run starts the count again. */
        fun failuresAfter(previous: Int, ranForMs: Long): Int =
            if (ranForMs >= HEALTHY_RUN_MS) 1 else previous + 1

        /** [first] for the first failure, doubling, never over [max]. */
        fun delayMs(failures: Int, first: Long = FIRST_DELAY_MS, max: Long = MAX_DELAY_MS): Long {
            val shifts = (failures - 1).coerceIn(0, 20)
            return minOf(max, first shl shifts)
        }
    }
}
