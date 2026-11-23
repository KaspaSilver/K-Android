package com.kachat.app.services.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wallet's "Received kUSDT" timing (iOS TokenWallet.noticeWait, startPolling; 2026-11-22). */
class TokenNoticeWaitTest {
    @Test
    fun `a coin on the Chatting Address waits up to 2 minutes for its chat notice`() {
        val first = 1_000_000L
        assertTrue(TokenWallet.shouldHoldForNotice(first, first))
        assertTrue(TokenWallet.shouldHoldForNotice(first, first + 119_999L))
        assertFalse(TokenWallet.shouldHoldForNotice(first, first + 120_000L))
    }

    @Test
    fun `a coin that doesn't wait is never held`() {
        assertFalse(TokenWallet.shouldHoldForNotice(null, 0L))
    }

    @Test
    fun `the balances are checked every 30 seconds while open`() {
        assertEquals(30_000L, TokenWallet.POLL_INTERVAL_MS)
        assertEquals(25_000L, TokenWallet.POLL_MAX_AGE_MS)
    }
}
