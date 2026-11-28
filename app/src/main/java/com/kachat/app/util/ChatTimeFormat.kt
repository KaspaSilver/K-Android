package com.kachat.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Today, as the Chats list's rows know it (iOS 01f71184 `ConversationRowContent.todayStamp`,
 * release audit C2): a row's time ("10:15 PM") becomes "Yesterday" only when the row draws again,
 * and a row with nothing new doesn't. The list moves this on at midnight (and when the clock or
 * zone is changed, or the app comes back), and every row that reads it draws again.
 */
val LocalChatDayStamp = androidx.compose.runtime.compositionLocalOf { 0L }

/** [ChatTimeFormat.todayStamp], moved on at the next local midnight, on a clock, date or zone
 *  change, and whenever the screen resumes (iOS `significantTimeChangeNotification`, which is
 *  also posted on waking). */
@androidx.compose.runtime.Composable
fun rememberChatDayStamp(): Long {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var stamp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(ChatTimeFormat.todayStamp()) }
    androidx.compose.runtime.DisposableEffect(context, lifecycleOwner) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                stamp = ChatTimeFormat.todayStamp()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) stamp = ChatTimeFormat.todayStamp()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    // Midnight while the screen is up (the broadcast can come late, or not at all on some builds).
    androidx.compose.runtime.LaunchedEffect(stamp) {
        kotlinx.coroutines.delay(ChatTimeFormat.millisUntilNextDay(System.currentTimeMillis()) + 1_000L)
        stamp = ChatTimeFormat.todayStamp()
    }
    return stamp
}

/** Shared date/time formatting for message lists (1:1 chats and broadcast rooms) — day dividers plus per-message times revealed by swiping the list left. */
object ChatTimeFormat {
    /** Whether two instants fall on the same local day. Asked for every message row a chat draws
     *  while it scrolls, so it works from the zone's offset at each instant rather than building
     *  two Calendars per row (iOS 2026-11-24 `ChatTimelineLayout`, CLAUDE.md "Smooth scrolling");
     *  the offset is per instant, so DST days come out right. */
    fun isSameDay(t1: Long, t2: Long): Boolean = isSameDay(t1, t2, java.util.TimeZone.getDefault())

    internal fun isSameDay(t1: Long, t2: Long, zone: java.util.TimeZone): Boolean =
        localDayIndex(t1, zone) == localDayIndex(t2, zone)

    /** Today's [localDayIndex] here: the Chats rows' day (see [LocalChatDayStamp]). */
    fun todayStamp(): Long = localDayIndex(System.currentTimeMillis(), java.util.TimeZone.getDefault())

    /** How long from [now] until the next local day starts in [zone] (at least 1 ms). */
    fun millisUntilNextDay(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        // A calendar day, not 24 hours: a daylight saving change makes it 23 or 25.
        val next = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return (next - now).coerceAtLeast(1L)
    }

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
