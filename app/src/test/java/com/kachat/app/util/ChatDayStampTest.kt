package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The Chats rows' day stamp moves on at local midnight (iOS 01f71184, release audit C2). */
class ChatDayStampTest {
    private val newYork = TimeZone.getTimeZone("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone = newYork): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test
    fun untilMidnightOnAnOrdinaryDay() {
        assertEquals(15 * 60_000L, ChatTimeFormat.millisUntilNextDay(at(2026, 11, 27, 23, 45), newYork))
        assertEquals(24 * 3_600_000L, ChatTimeFormat.millisUntilNextDay(at(2026, 11, 27, 0, 0), newYork))
    }

    @Test
    fun daylightSavingDaysAreCalendarDays() {
        // 2026-03-08 is 23 hours long in New York, 2026-11-01 is 25
        assertEquals(23 * 3_600_000L, ChatTimeFormat.millisUntilNextDay(at(2026, 3, 8, 0, 0), newYork))
        assertEquals(25 * 3_600_000L, ChatTimeFormat.millisUntilNextDay(at(2026, 11, 1, 0, 0), newYork))
    }

    @Test
    fun theStampChangesExactlyAtMidnight() {
        val beforeMidnight = at(2026, 11, 27, 23, 59)
        val next = beforeMidnight + ChatTimeFormat.millisUntilNextDay(beforeMidnight, newYork)
        assertEquals(ChatTimeFormat.localDayIndex(beforeMidnight, newYork) + 1, ChatTimeFormat.localDayIndex(next, newYork))
        assertEquals(ChatTimeFormat.localDayIndex(beforeMidnight, newYork), ChatTimeFormat.localDayIndex(next - 1, newYork))
        assertTrue(ChatTimeFormat.millisUntilNextDay(next - 1, newYork) >= 1)
    }
}
