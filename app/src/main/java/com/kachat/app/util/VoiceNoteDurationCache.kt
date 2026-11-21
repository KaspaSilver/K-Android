package com.kachat.app.util

import android.util.LruCache

/**
 * Voice-note lengths already worked out, so a bubble scrolling back in doesn't parse its audio
 * again (iOS f954738 `VoiceNoteDurationCache`). Keyed by the audio itself (its parts' sizes and
 * hash), so two notes never share an entry.
 */
object VoiceNoteDurationCache {
    private val cache = LruCache<String, Long>(1_000)

    /** The cached length for [key], else [compute]'s, kept when it is known (above 0). */
    fun durationMs(key: String, compute: () -> Long): Long {
        cache.get(key)?.let { return it }
        val value = compute()
        if (value > 0) cache.put(key, value)
        return value
    }

    /** A long voice note's key: how many parts are present, their sizes, and their contents' hash. */
    fun key(partContents: List<String>): String =
        "${partContents.size}:${partContents.joinToString(",") { it.length.toString() }}:${partContents.hashCode()}"
}
