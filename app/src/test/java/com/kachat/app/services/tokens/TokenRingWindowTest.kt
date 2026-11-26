package com.kachat.app.services.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Received kUSDT rings only for a coin from the last 6 minutes, and the watched receive
 *  addresses survive a relaunch (iOS 961fc05a, audit 2026-11-25 B13). */
class TokenRingWindowTest {
    @Test
    fun `a coin from the last 6 minutes rings`() {
        val now = 1_000_000_000L
        assertTrue(TokenWallet.isWithinRingWindow(now - 6 * 60_000L, now))
        assertFalse(TokenWallet.isWithinRingWindow(now - 6 * 60_000L - 1, now))
    }

    @Test
    fun `a coin of unknown time counts as now`() {
        assertTrue(TokenWallet.isWithinRingWindow(null, 0L))
    }

    @Test
    fun `the receive watch round-trips`() {
        val map = mapOf("kaspa:qabc" to 123L, "kaspa:qdef" to 456L)
        assertEquals(map, TokenWallet.decodeReceiveWatch(TokenWallet.encodeReceiveWatch(map)))
        assertEquals(emptyMap<String, Long>(), TokenWallet.decodeReceiveWatch(null))
        assertEquals(mapOf("kaspa:q1" to 5L), TokenWallet.decodeReceiveWatch("kaspa:q1=5,broken,=7,x=y"))
    }
}
