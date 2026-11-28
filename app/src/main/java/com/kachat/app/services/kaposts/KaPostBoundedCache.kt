package com.kachat.app.services

/**
 * A map that keeps at most [maxEntries], dropping the least recently used first: the KaPost caches
 * that are keyed by post (repost origins, link previews) grew by every quoted or reposted post
 * shown, for the life of the process (audit 2026-11-27 R9). Not thread-safe: even a read reorders
 * it, so every use is under its owner's lock.
 */
internal class BoundedLruMap<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    init {
        require(maxEntries > 0)
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries

    companion object {
        /** Per cache: far more posts than one session shows on screen. */
        const val KAPOST_CACHE_ENTRIES = 500
    }
}
