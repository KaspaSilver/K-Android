package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 1:1 poll's schedule (audit 2026-11-25 A1, A3, S3). */
class ChatPollScheduleTest {

    @Test
    fun `a short list is swept whole`() {
        val (window, next) = ChatPollSchedule.window((1..12).toList(), rotation = 7)
        assertEquals((1..12).toList(), window)
        assertEquals(0, next)
    }

    @Test
    fun `the four most recent every pass, the rest in turn`() {
        val contacts = (1..20).toList()
        val (first, r1) = ChatPollSchedule.window(contacts, rotation = 0)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), first)
        val (second, r2) = ChatPollSchedule.window(contacts, rotation = r1)
        assertEquals(listOf(1, 2, 3, 4, 13, 14, 15, 16, 17, 18, 19, 20), second)
        val (third, _) = ChatPollSchedule.window(contacts, rotation = r2)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), third)
    }

    @Test
    fun `a rotation past a shrunken list wraps`() {
        val (window, _) = ChatPollSchedule.window((1..14).toList(), rotation = 99)
        assertEquals(12, window.size)
        assertEquals(listOf(1, 2, 3, 4), window.take(4))
        assertEquals(window.size, window.toSet().size)
    }

    @Test
    fun `the sweep runs on the beat, longer with push, backing off on failures`() {
        assertEquals(30_000L, ChatPollSchedule.sweepIntervalMs(30_000L, pushActive = false, consecutiveFailures = 0))
        assertEquals(60_000L, ChatPollSchedule.sweepIntervalMs(60_000L, pushActive = false, consecutiveFailures = 0))
        assertEquals(60_000L, ChatPollSchedule.sweepIntervalMs(30_000L, pushActive = true, consecutiveFailures = 0))
        assertEquals(120_000L, ChatPollSchedule.sweepIntervalMs(60_000L, pushActive = true, consecutiveFailures = 0))
        assertEquals(60_000L, ChatPollSchedule.sweepIntervalMs(30_000L, pushActive = false, consecutiveFailures = 1))
        assertEquals(120_000L, ChatPollSchedule.sweepIntervalMs(30_000L, pushActive = false, consecutiveFailures = 5))
        assertEquals(120_000L, ChatPollSchedule.sweepIntervalMs(60_000L, pushActive = true, consecutiveFailures = 5))
    }

    @Test
    fun `the next sweep is on a beat and never right after the last one ended`() {
        val interval = 30_000L
        val offset = ChatPollSchedule.SWEEP_BEAT_OFFSET_MS
        for (end in listOf(1_000_000_000L, 1_000_000_400L, 1_000_014_000L, 1_000_029_900L, 1_000_045_000L)) {
            val next = ChatPollSchedule.nextSweepAt(end, interval)
            assertEquals(offset, Math.floorMod(next, interval))
            assertTrue("at least half a beat after $end", next - end >= interval / 2)
            assertTrue("within a beat and a half of $end", next - end <= interval + interval / 2)
        }
    }

    @Test
    fun `only the open chat ticks fast`() {
        assertEquals(2_000L, ChatPollSchedule.openChatTickMs(metered = false, consecutiveFailures = 0))
        assertEquals(5_000L, ChatPollSchedule.openChatTickMs(metered = true, consecutiveFailures = 0))
        assertEquals(60_000L, ChatPollSchedule.openChatTickMs(metered = false, consecutiveFailures = 6))
    }

    @Test
    fun `the background polls only when nothing else can deliver and something can show`() {
        assertEquals(ChatPollSchedule.Background.PAUSE, ChatPollSchedule.background(pushActive = true, signedIn = true, notificationsOn = true))
        assertEquals(ChatPollSchedule.Background.PAUSE, ChatPollSchedule.background(pushActive = false, signedIn = false, notificationsOn = true))
        assertEquals(ChatPollSchedule.Background.PAUSE, ChatPollSchedule.background(pushActive = false, signedIn = true, notificationsOn = false))
        assertEquals(ChatPollSchedule.Background.LIGHT, ChatPollSchedule.background(pushActive = false, signedIn = true, notificationsOn = true))
    }

    @Test
    fun `one chain per notice at a time, none after it settled`() {
        var now = 1_000L
        val gate = NoticeChainGate { now }
        assertTrue(gate.tryStart("w|tx"))
        assertFalse(gate.tryStart("w|tx"))
        gate.finish("w|tx", settledNow = true)
        now += 10 * 60_000L
        assertFalse(gate.tryStart("w|tx"))
        assertTrue(gate.tryStart("w|other"))
    }

    @Test
    fun `an unverified notice is checked again, but not on every tick`() {
        var now = 1_000L
        val gate = NoticeChainGate { now }
        assertTrue(gate.tryStart("w|tx"))
        gate.finish("w|tx", settledNow = false)
        now += 2_000L
        assertFalse(gate.tryStart("w|tx"))
        now += NoticeChainGate.RECHECK_MIN_MS
        assertTrue(gate.tryStart("w|tx"))
    }
}
