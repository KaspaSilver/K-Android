package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-27 R9: the KaPost repost-origin and link-preview caches are bounded. */
class BoundedLruMapTest {

    @Test
    fun `never holds more than its limit`() {
        val map = BoundedLruMap<String, Int>(3)
        for (i in 1..10) map["k$i"] = i
        assertEquals(3, map.size)
        assertEquals(setOf("k8", "k9", "k10"), map.keys)
    }

    @Test
    fun `drops the least recently used first`() {
        val map = BoundedLruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        // reading a keeps it
        assertEquals(1, map["a"])
        map["c"] = 3
        assertNull(map["b"])
        assertTrue("a" in map && "c" in map)
    }

    @Test
    fun `the KaPost caches keep 500 posts`() {
        val map = BoundedLruMap<Int, Int>(BoundedLruMap.KAPOST_CACHE_ENTRIES)
        repeat(2_000) { map[it] = it }
        assertEquals(500, map.size)
    }
}
