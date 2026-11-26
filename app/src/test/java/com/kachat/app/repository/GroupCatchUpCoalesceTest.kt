package com.kachat.app.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A burst of group pushes runs one full catch-up (audit 2026-11-25 N10). */
class GroupCatchUpCoalesceTest {

    private val last = "kaspa:qa" to 1_000_000L

    @Test
    fun aCatchUpUnderAMinuteOldCoversAPush() {
        assertTrue(catchUpIsFresh(last, "kaspa:qa", 1_000_000L + 59_999L, PUSH_CATCH_UP_COALESCE_MS))
    }

    @Test
    fun anOlderOneDoesNot() {
        assertFalse(catchUpIsFresh(last, "kaspa:qa", 1_000_000L + 60_000L, PUSH_CATCH_UP_COALESCE_MS))
    }

    @Test
    fun anotherWalletOrNoneNeverCounts() {
        assertFalse(catchUpIsFresh(last, "kaspa:qb", 1_000_100L, PUSH_CATCH_UP_COALESCE_MS))
        assertFalse(catchUpIsFresh(last, null, 1_000_100L, PUSH_CATCH_UP_COALESCE_MS))
        assertFalse(catchUpIsFresh(null, "kaspa:qa", 1_000_100L, PUSH_CATCH_UP_COALESCE_MS))
    }

    @Test
    fun noWindowOrAClockThatWentBackNeverSkips() {
        assertFalse(catchUpIsFresh(last, "kaspa:qa", 1_000_100L, 0L))
        assertFalse(catchUpIsFresh(last, "kaspa:qa", 999_000L, PUSH_CATCH_UP_COALESCE_MS))
    }
}
