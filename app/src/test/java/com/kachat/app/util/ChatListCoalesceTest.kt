package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Chats list's rebuild: 180 ms of quiet, never held past a second (iOS audit 2026-11-24 #27). */
class ChatListCoalesceTest {

    @Test
    fun theFirstChangeRebuildsAtOnce() {
        assertTrue(CoalescePolicy.emitsNow(nowMs = 5_000, lastEmitMs = null, maxWaitMs = 1_000))
    }

    @Test
    fun changesWithinASecondOfTheLastRebuildWait() {
        assertFalse(CoalescePolicy.emitsNow(nowMs = 5_100, lastEmitMs = 5_000, maxWaitMs = 1_000))
        assertFalse(CoalescePolicy.emitsNow(nowMs = 6_000, lastEmitMs = 5_000, maxWaitMs = 1_000))
    }

    @Test
    fun aChangeMoreThanASecondLaterRebuildsAtOnce() {
        assertTrue(CoalescePolicy.emitsNow(nowMs = 6_001, lastEmitMs = 5_000, maxWaitMs = 1_000))
    }

    @Test
    fun theTimingsAreIos() {
        assertTrue(CoalescePolicy.CHAT_LIST_QUIET_MS == 180L)
        assertTrue(CoalescePolicy.CHAT_LIST_MAX_WAIT_MS == 1_000L)
    }
}
