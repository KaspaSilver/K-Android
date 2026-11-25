package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live group delivery in chunks of 256 lanes (iOS audit 2026-11-24 #14). */
class GroupLiveCursorTest {

    @Test
    fun theCursorMovesOnlyAsFarAsTheSlowestChunk() {
        assertEquals(1_500L, liveCursorAfterChunks(1_000L, listOf(2_000L, 1_500L, 3_000L)))
    }

    @Test
    fun theCursorNeverGoesBack() {
        assertEquals(1_000L, liveCursorAfterChunks(1_000L, listOf(900L, 5_000L)))
        assertEquals(1_000L, liveCursorAfterChunks(1_000L, emptyList()))
    }

    @Test
    fun theIndexerTakes256IdsPerRead() {
        assertEquals(256, LIVE_IDS_PER_READ)
        assertEquals(listOf(256, 256, 88), (0 until 600).toList().chunked(LIVE_IDS_PER_READ).map { it.size })
    }
}
