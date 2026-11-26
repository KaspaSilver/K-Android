package com.kachat.app.repository

/**
 * The 1:1 poll's schedule, kept pure so it can be tested (audit 2026-11-25 A1/B1/S1, A3/S2/B4/N7).
 *
 * Mirrors iPhone's foreground contact sweep (K-iPhone CLAUDE.md "Foreground contact sweep"): a
 * window of [SWEEP_WINDOW] contacts per pass - the [SWEEP_PINNED] most recent every pass plus a
 * rotating slice of the rest - every beat (30 s Wi-Fi / 60 s metered, twice that with push
 * active), counted from the end of the last sweep; the open chat alone keeps a fast tick.
 */
internal object ChatPollSchedule {
    const val SWEEP_WINDOW = 12
    const val SWEEP_PINNED = 4

    /** The open 1:1 chat's tick: 2 s on Wi-Fi, 5 s metered (unchanged from before). */
    const val OPEN_CHAT_TICK_MS = 2_000L
    const val OPEN_CHAT_TICK_METERED_MS = 5_000L

    /** Longest a failing sweep backs off to (iPhone: 120 s on indexer errors). */
    const val SWEEP_BACKOFF_CAP_MS = 120_000L

    /** Longest a failing open-chat tick backs off to. */
    const val TICK_BACKOFF_CAP_MS = 60_000L

    /** Where the 1:1 sweep sits inside the shared beat (the room sweep is at 0, KaPost +800 ms). */
    const val SWEEP_BEAT_OFFSET_MS = 400L

    /** In the background without push (notifications on, signed in): one light pass this often. */
    const val BACKGROUND_LIGHT_INTERVAL_MS = 5 * 60_000L

    /**
     * A catch-up sweep (the up-to-[CATCH_UP_CAP] most recent chats, outgoing handshakes too)
     * runs at process start and on coming back to the front after at least this long away.
     */
    const val CATCH_UP_MIN_GAP_MS = 5 * 60_000L
    const val CATCH_UP_CAP = 40

    /**
     * This pass's window out of [ordered] (most recent first): the first [pinned] every time, then
     * [max] - [pinned] of the rest starting at [rotation]. Returns the window and the rotation to
     * pass next time. Everyone fits: all of them, rotation 0.
     */
    fun <T> window(ordered: List<T>, rotation: Int, max: Int = SWEEP_WINDOW, pinned: Int = SWEEP_PINNED): Pair<List<T>, Int> {
        if (ordered.size <= max) return ordered to 0
        val head = ordered.take(pinned)
        val rest = ordered.drop(pinned)
        val sliceSize = minOf(max - head.size, rest.size)
        var index = Math.floorMod(rotation, rest.size)
        val slice = ArrayList<T>(sliceSize)
        repeat(sliceSize) {
            slice.add(rest[index])
            index = (index + 1) % rest.size
        }
        return (head + slice) to index
    }

    /** The sweep's interval: the beat, doubled with push active (a push already brings each
     *  message), doubled again per failed sweep in a row up to [SWEEP_BACKOFF_CAP_MS] (or the
     *  push interval when that is longer). */
    fun sweepIntervalMs(beatMs: Long, pushActive: Boolean, consecutiveFailures: Int): Long {
        val base = if (pushActive) beatMs * 2 else beatMs
        var interval = base
        repeat(minOf(consecutiveFailures, 6)) { interval = minOf(interval * 2, maxOf(SWEEP_BACKOFF_CAP_MS, base)) }
        return interval
    }

    /**
     * When the next sweep is due: on a beat of [intervalMs] (plus [offsetMs]) and at least half an
     * interval after the last sweep ended at [endMs], so a slow sweep never runs back to back.
     */
    fun nextSweepAt(endMs: Long, intervalMs: Long, offsetMs: Long = SWEEP_BEAT_OFFSET_MS): Long {
        val earliest = endMs + intervalMs / 2
        // The first beat at or after `earliest`; beats sit at whole multiples of the interval since
        // 1970 plus the offset, as [ForegroundBeat] puts them.
        return Math.floorDiv(earliest - offsetMs + intervalMs - 1, intervalMs) * intervalMs + offsetMs
    }

    /** The open chat's tick, doubled per failed tick in a row up to [TICK_BACKOFF_CAP_MS]. */
    fun openChatTickMs(metered: Boolean, consecutiveFailures: Int): Long {
        var tick = if (metered) OPEN_CHAT_TICK_METERED_MS else OPEN_CHAT_TICK_MS
        repeat(minOf(consecutiveFailures, 6)) { tick = minOf(tick * 2, TICK_BACKOFF_CAP_MS) }
        return tick
    }

    /** What the loop does while K is in the background (audit A3, N7). */
    enum class Background {
        /** Push delivers, or nothing could be shown (signed out, notifications off): no polling. */
        PAUSE,

        /** No push, but notifications can show: a light pass every [BACKGROUND_LIGHT_INTERVAL_MS]. */
        LIGHT,
    }

    fun background(pushActive: Boolean, signedIn: Boolean, notificationsOn: Boolean): Background =
        if (pushActive || !signedIn || !notificationsOn) Background.PAUSE else Background.LIGHT
}

/**
 * One chain check per payment a payment or token notice names (audit 2026-11-25 S3): a notice is
 * read again on every fetch whose rewind window still covers it, and each read used to start a new
 * chain of on-chain checks. Keys are `wallet|paymentTxId`. A key is skipped while its chain runs,
 * for good once it settled (filed, confirmed or dropped), and for [RECHECK_MIN_MS] after a chain
 * that left only an unverified bubble, which a later read may check again.
 */
internal class NoticeChainGate(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val running = HashSet<String>()
    private val settled = HashSet<String>()
    private val unverifiedAt = HashMap<String, Long>()

    @Synchronized
    fun tryStart(key: String): Boolean {
        if (key in running || key in settled) return false
        val last = unverifiedAt[key]
        if (last != null && nowMs() - last < RECHECK_MIN_MS) return false
        if (running.size + settled.size + unverifiedAt.size > MAX_KEYS) {
            settled.clear()
            unverifiedAt.clear()
        }
        running.add(key)
        return true
    }

    @Synchronized
    fun finish(key: String, settledNow: Boolean) {
        running.remove(key)
        if (settledNow) {
            unverifiedAt.remove(key)
            settled.add(key)
        } else {
            unverifiedAt[key] = nowMs()
        }
    }

    @Synchronized
    fun clear() {
        settled.clear()
        unverifiedAt.clear()
    }

    companion object {
        const val RECHECK_MIN_MS = 60_000L
        private const val MAX_KEYS = 5_000
    }
}
