package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The chat timeline's day check, worked from the zone's offset per instant (iOS 2026-11-24). */
class ChatTimeFormatSameDayTest {
    private val newYork = TimeZone.getTimeZone("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test
    fun sameAndNextDay() {
        assertTrue(ChatTimeFormat.isSameDay(at(2026, 3, 1, 0, 1, newYork), at(2026, 3, 1, 23, 59, newYork), newYork))
        assertFalse(ChatTimeFormat.isSameDay(at(2026, 3, 1, 23, 59, newYork), at(2026, 3, 2, 0, 0, newYork), newYork))
    }

    @Test
    fun dstDaysComeOutRight() {
        // 2026-03-08: clocks go forward at 02:00 (a 23 hour day); 2026-11-01: back (25 hours)
        assertTrue(ChatTimeFormat.isSameDay(at(2026, 3, 8, 0, 30, newYork), at(2026, 3, 8, 23, 30, newYork), newYork))
        assertFalse(ChatTimeFormat.isSameDay(at(2026, 3, 8, 23, 30, newYork), at(2026, 3, 9, 0, 30, newYork), newYork))
        assertTrue(ChatTimeFormat.isSameDay(at(2026, 11, 1, 0, 30, newYork), at(2026, 11, 1, 23, 30, newYork), newYork))
        assertFalse(ChatTimeFormat.isSameDay(at(2026, 10, 31, 23, 30, newYork), at(2026, 11, 1, 0, 30, newYork), newYork))
    }

    @Test
    fun agreesWithTheCalendar() {
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        var t = at(2026, 1, 1, 0, 0, zone)
        val step = 37L * 60 * 1000 + 11_000
        var previous = t
        repeat(2_000) {
            val c1 = Calendar.getInstance(zone).apply { timeInMillis = previous }
            val c2 = Calendar.getInstance(zone).apply { timeInMillis = t }
            val expected = c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
            assertEquals(expected, ChatTimeFormat.isSameDay(previous, t, zone))
            previous = t
            t += step
        }
    }
}
