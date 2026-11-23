package com.kachat.app.services

import com.kachat.app.services.PaymentNoticeOutbox.UnsentNoticeRetry
import com.kachat.app.services.PaymentNoticeOutbox.UnsentNoticeRetry.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held payment notice is retried every 20 s while open, for 15 minutes (iOS scheduleUnsentNoticeRetry). */
class PaymentNoticeRetryTimingTest {
    @Test
    fun `tries every 20 seconds for 15 minutes`() {
        assertEquals(20_000L, UnsentNoticeRetry.INTERVAL_MS)
        assertEquals(15 * 60 * 1000L, UnsentNoticeRetry.WINDOW_MS)
        assertTrue(UnsentNoticeRetry.keepsTrying(0))
        assertTrue(UnsentNoticeRetry.keepsTrying(15 * 60 * 1000L - 1))
        assertFalse(UnsentNoticeRetry.keepsTrying(15 * 60 * 1000L))
    }

    @Test
    fun `stops once nothing is held, waits while in the background, else sends`() {
        assertEquals(Step.STOP, UnsentNoticeRetry.step(hasNotices = false, foreground = true))
        assertEquals(Step.STOP, UnsentNoticeRetry.step(hasNotices = false, foreground = false))
        assertEquals(Step.SKIP, UnsentNoticeRetry.step(hasNotices = true, foreground = false))
        assertEquals(Step.SEND, UnsentNoticeRetry.step(hasNotices = true, foreground = true))
    }
}
