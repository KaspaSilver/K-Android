package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Settings > Connection Status's Indexer Status (iOS 8cb4c88e `KasiaAPIClient.connectionState()`). */
class IndexerConnectionStateTest {
    private val start = 1_000_000L

    @Before
    fun reset() = IndexerHealth.resetForTest()

    private fun state(atMs: Long, configured: Boolean = true) = IndexerHealth.connectionState(configured, atMs)

    @Test
    fun `answering indexer is connected`() {
        assertEquals(IndexerHealth.ConnectionState.CONNECTED, state(start))
    }

    @Test
    fun `no indexer in Settings is not set`() {
        IndexerHealth.recordFailure(start)
        assertEquals(IndexerHealth.ConnectionState.NOT_SET, state(start, configured = false))
    }

    @Test
    fun `a recent failure is degraded until two minutes pass`() {
        IndexerHealth.recordFailure(start)
        assertEquals(IndexerHealth.ConnectionState.DEGRADED, state(start + 1_000))
        assertEquals(IndexerHealth.ConnectionState.DEGRADED, state(start + 120_000))
        assertEquals(IndexerHealth.ConnectionState.CONNECTED, state(start + 120_001))
    }

    @Test
    fun `an answer after a failure is connected again`() {
        IndexerHealth.recordFailure(start)
        IndexerHealth.recordAnswer()
        assertEquals(IndexerHealth.ConnectionState.CONNECTED, state(start + 1_000))
    }

    @Test
    fun `three spaced failures are disconnected`() {
        IndexerHealth.recordFailure(start)
        IndexerHealth.recordFailure(start + 5_000)
        IndexerHealth.recordFailure(start + 10_000)
        assertEquals(IndexerHealth.ConnectionState.DISCONNECTED, state(start + 11_000))
        // still down long after the last failure, until an answer
        assertEquals(IndexerHealth.ConnectionState.DISCONNECTED, state(start + 600_000))
        IndexerHealth.recordAnswer()
        assertEquals(IndexerHealth.ConnectionState.CONNECTED, state(start + 600_001))
    }
}
