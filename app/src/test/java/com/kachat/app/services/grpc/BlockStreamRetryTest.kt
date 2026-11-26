package com.kachat.app.services.grpc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The block streams' retry (audit 2026-11-25 N1): doubling from the first wait to a cap, a long
 *  run starting the count again, and the network awaited after the wait. */
class BlockStreamRetryTest {

    @Test
    fun `failures double the wait up to the cap`() {
        assertEquals(5_000L, BlockStreamRetry.delayMs(1))
        assertEquals(10_000L, BlockStreamRetry.delayMs(2))
        assertEquals(20_000L, BlockStreamRetry.delayMs(3))
        assertEquals(80_000L, BlockStreamRetry.delayMs(5))
        assertEquals(120_000L, BlockStreamRetry.delayMs(6))
        assertEquals(120_000L, BlockStreamRetry.delayMs(60))
        assertEquals(2_000L, BlockStreamRetry.delayMs(1, first = 2_000L, max = 30_000L))
        assertEquals(30_000L, BlockStreamRetry.delayMs(9, first = 2_000L, max = 30_000L))
    }

    @Test
    fun `a try that ran a minute starts the count again`() {
        assertEquals(1, BlockStreamRetry.failuresAfter(previous = 0, ranForMs = 100))
        assertEquals(4, BlockStreamRetry.failuresAfter(previous = 3, ranForMs = 59_999))
        assertEquals(1, BlockStreamRetry.failuresAfter(previous = 7, ranForMs = 60_000))
    }

    @Test
    fun `pause waits, then for the network`() = runBlocking {
        val events = mutableListOf<String>()
        var clock = 0L
        val retry = BlockStreamRetry(
            firstDelayMs = 20L,
            maxDelayMs = 1_000L,
            awaitOnline = { events += "online?" },
            now = { clock },
        )
        val started = System.nanoTime()
        retry.pause()
        val firstMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $firstMs ms", firstMs >= 20)
        assertEquals(listOf("online?"), events)
        // A quick second failure waits twice as long.
        val again = System.nanoTime()
        retry.pause()
        assertTrue((System.nanoTime() - again) / 1_000_000 >= 40)
        assertEquals(2, events.size)
    }
}
