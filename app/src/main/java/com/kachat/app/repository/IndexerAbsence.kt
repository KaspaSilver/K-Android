package com.kachat.app.repository

/**
 * The rules for dropping a cached room message the indexer no longer serves (iOS d089011f
 * `PublicChatService.dropRowsTheIndexerNoLongerHas`). For a room the indexer tracks, the indexer
 * is the truth (owner, 2026-11-25: "work like KaPost"): what a read returns is what the room
 * shows, and a delivered message the phone holds in the span the read answers for, that the read
 * doesn't have, goes - a moderator's removal, its sender's delete, or anything else the indexer no
 * longer serves. No brake on how many go at once: if the indexer loses history, phones show that
 * too, as KaPost would. Pure, for tests.
 */
object IndexerAbsence {
    /** A minute inside the edges a page answers for: this phone's time for a row can differ from
     *  the indexer's. */
    const val EDGE_MARGIN_MS = 60_000L

    /** The newest page answers up to this long ago: the last 90 seconds are left for the indexer
     *  to catch up. */
    const val CATCH_UP_MS = 90_000L

    /** Never the oldest day of the retention window (retention's edge). */
    const val OLDEST_SKIPPED_MS = 86_400_000L

    /** Your own messages go once this old: sends still on their way to the indexer stay. */
    const val OWN_MESSAGE_GRACE_MS = 300_000L

    /**
     * The span of chain time a page answers for, or null when it answers for nothing:
     * - the newest page ([before] null): from its oldest row (a minute in) up to 90 seconds ago,
     *   so the newest message goes too once deleted;
     * - an older page: between its oldest row and [before] (exclusive), a minute inside both;
     * - never below the page's oldest row (an indexer holding less than this phone says nothing
     *   about older messages), never the oldest day of [retentionMs].
     */
    fun span(oldest: Long, before: Long?, now: Long, retentionMs: Long): LongRange? {
        var upper = before?.let { it - 1 - EDGE_MARGIN_MS } ?: (now - CATCH_UP_MS)
        upper = minOf(upper, now - CATCH_UP_MS)
        var lower = oldest + EDGE_MARGIN_MS
        lower = maxOf(lower, now - retentionMs + OLDEST_SKIPPED_MS)
        return if (lower <= upper) lower..upper else null
    }

    /** Whether a held row in the span is judged: anyone else's always, your own (any account on
     *  this phone) once [OWN_MESSAGE_GRACE_MS] old. */
    fun considers(isOwn: Boolean, blockTime: Long, now: Long): Boolean =
        !isOwn || blockTime < now - OWN_MESSAGE_GRACE_MS

    /** Misses needed before a row goes: one on the first read after the room opens and in the
     *  Rooms list's check (the reader is looking at it now), two for every other read. */
    fun missesNeeded(firstMissRemoves: Boolean): Int = if (firstMissRemoves) 1 else 2

    /** Two misses closer together than this count once: the push-banner fetch reads the same
     *  room up to four times in four seconds, and those reads see one indexer state, not two
     *  (audit 2026-11-25 T3). Under the open room's 8 s poll, so two polls are two misses (about
     *  16 s, as iOS). */
    const val MIN_STRIKE_GAP_MS = 6_000L

    /**
     * How many reads in a row each cached room message ("room|txid") was missing from: a row goes
     * on its [needed]th miss, and two misses count as two only when at least [MIN_STRIKE_GAP_MS]
     * apart. Thread-safe.
     */
    class Strikes(private val cap: Int = 5_000) {
        private class Strike(val count: Int, val lastAtMs: Long)

        private val counts = HashMap<String, Strike>()

        /** Counts this read: [considered] ids held in the span, [onPage] the page's ids
         *  (lowercased). Returns the ids missing [needed] times or more. */
        fun strike(
            channel: String,
            considered: List<String>,
            onPage: Set<String>,
            nowMs: Long = System.currentTimeMillis(),
            needed: Int = 2,
        ): Set<String> = synchronized(counts) {
            val gone = LinkedHashSet<String>()
            for (id in considered) {
                val key = "$channel|${id.lowercase()}"
                if (id.lowercase() in onPage) {
                    counts.remove(key)
                    continue
                }
                val previous = counts[key]
                val strike = when {
                    previous == null -> Strike(1, nowMs)
                    nowMs - previous.lastAtMs < MIN_STRIKE_GAP_MS -> previous
                    else -> Strike(previous.count + 1, nowMs)
                }
                counts[key] = strike
                if (strike.count >= needed) gone += id
            }
            if (counts.size > cap) counts.clear()
            gone
        }

        fun forget(channel: String, ids: Collection<String>) = synchronized(counts) {
            for (id in ids) counts.remove("$channel|${id.lowercase()}")
        }
    }

    /** How often the Rooms list reads each room nothing else keeps current. */
    const val LIST_CHECK_INTERVAL_MS = 120_000L

    /**
     * The rooms the Rooms list's check reads now (iOS `checkListedRoomsAgainstIndexer`): indexed
     * rooms nothing else keeps current (bell off, not open), never the machinery or hidden rooms,
     * each at most every [LIST_CHECK_INTERVAL_MS]. [rooms] pairs each listed room with its bell.
     */
    fun roomsDueForListCheck(
        rooms: List<Pair<String, Boolean>>,
        isOpen: (String) -> Boolean,
        isIndexed: (String) -> Boolean,
        isExcluded: (String) -> Boolean,
        lastCheckedAt: (String) -> Long?,
        now: Long,
    ): List<String> = rooms.filter { (name, bellOn) ->
        !bellOn && !isOpen(name) && isIndexed(name) && !isExcluded(name) &&
            now - (lastCheckedAt(name) ?: 0L) >= LIST_CHECK_INTERVAL_MS
    }.map { it.first }
}
