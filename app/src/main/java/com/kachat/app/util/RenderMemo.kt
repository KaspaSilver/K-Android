package com.kachat.app.util

/**
 * A small thread-safe LRU for what a chat row works out from text that never changes: link
 * ranges, @username tokens, a multi-part photo's joined bytes (iOS d089011f, the 2026-11-25
 * smoothness sweep: `LinkifiedMessageTextView.sharedAttributed`, `UsernameMention.tokenMemo`,
 * `PartsInputMemo`). A row's `remember` is dropped when it scrolls out of a lazy list, so without
 * this every row coming back on screen ran the same regex or decode again on the main thread.
 *
 * Plain JVM (no android.util.LruCache), so the pure helpers that use it stay unit-testable.
 * [maxCount] entries at most, and [maxCost] in all by [costOf] (an entry costing more than that is
 * not kept).
 */
class RenderMemo<K : Any, V : Any>(
    private val maxCount: Int,
    private val maxCost: Long = Long.MAX_VALUE,
    private val costOf: (V) -> Long = { 1L },
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var cost = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        val valueCost = costOf(value)
        if (valueCost > maxCost) return
        map.put(key, value)?.let { cost -= costOf(it) }
        cost += valueCost
        val entries = map.entries.iterator()
        while ((map.size > maxCount || cost > maxCost) && entries.hasNext()) {
            val eldest = entries.next()
            cost -= costOf(eldest.value)
            entries.remove()
        }
    }

    /** [key]'s value, else [compute]'s, kept. Two callers racing may both compute; either result
     *  is the same. */
    inline fun getOrPut(key: K, compute: () -> V): V {
        get(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    @get:Synchronized
    val size: Int get() = map.size

    @Synchronized
    fun clear() {
        map.clear()
        cost = 0L
    }
}
