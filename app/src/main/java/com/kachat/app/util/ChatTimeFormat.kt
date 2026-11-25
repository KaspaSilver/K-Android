package com.kachat.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared date/time formatting for message lists (1:1 chats and broadcast rooms) — day dividers plus per-message times revealed by swiping the list left. */
object ChatTimeFormat {
    /** Whether two instants fall on the same local day. Asked for every message row a chat draws
     *  while it scrolls, so it works from the zone's offset at each instant rather than building
     *  two Calendars per row (iOS 2026-11-24 `ChatTimelineLayout`, CLAUDE.md "Smooth scrolling");
     *  the offset is per instant, so DST days come out right. */
    fun isSameDay(t1: Long, t2: Long): Boolean = isSameDay(t1, t2, java.util.TimeZone.getDefault())

    internal fun isSameDay(t1: Long, t2: Long, zone: java.util.TimeZone): Boolean =
        localDayIndex(t1, zone) == localDayIndex(t2, zone)

    /** Days since the epoch of [t]'s local date in [zone]. */
    internal fun localDayIndex(t: Long, zone: java.util.TimeZone): Long =
        Math.floorDiv(t + zone.getOffset(t), 86_400_000L)

    /** The divider between days' worth of messages (iOS MessageDaySeparatorFormatter): "Today",
     *  "Yesterday", then "Thursday, Sep 24" this year and "Sep 24, 2025" before it, in the
     *  device's language (iOS formats from the templates "EEEE, MMM d" / "MMM d, y"). Callers pass
     *  the localized "Today"/"Yesterday" (iOS fc6aec6). */
    fun formatDateDivider(timestamp: Long, today: String = "Today", yesterday: String = "Yesterday"): String {
        val now = System.currentTimeMillis()
        if (isSameDay(timestamp, now)) return today
        if (isSameDay(timestamp, now - 24L * 60 * 60 * 1000)) return yesterday
        val locale = Locale.getDefault()
        val sameYear = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)
        val skeleton = if (sameYear) "EEEEMMMd" else "MMMdy"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(Date(timestamp))
    }

    /** "10:57 AM" — iOS SharedFormatting.chatTime ("h:mm a", en_US_POSIX). */
    fun formatMessageTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(timestamp))
    }

    /** The time under a message (iOS MessageTimeLine). With [showsDay] (public chat rooms) a
     *  message from another day reads "Yesterday, 10:57 AM" or "Sep 28, 10:57 AM". */
    fun formatTimeLine(timestamp: Long, showsDay: Boolean, yesterday: String): String {
        val time = formatMessageTime(timestamp)
        val now = System.currentTimeMillis()
        if (!showsDay || isSameDay(timestamp, now)) return time
        val day = if (isSameDay(timestamp, now - 24L * 60 * 60 * 1000)) yesterday
            else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        return "$day, $time"
    }
}
