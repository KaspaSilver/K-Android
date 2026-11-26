package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An outgoing history row counts what left this wallet (iOS 961fc05a `ownHistoryAmount`, audit
 * 2026-11-25 L2): a kUSDT swap also spends Trestly's pool, whose output back to itself used to
 * count as "Sent".
 */
class OwnHistoryAmountTest {
    private val me = "kaspa:me"
    private val other = "kaspa:other"
    private val pool = "kaspa:pool"
    private val change = "kaspa:change"
    private val ownSet = setOf(me, change)

    private fun own(a: String) = a in ownSet

    @Test
    fun `a plain send is what went to others`() {
        // 10 in, 3 to someone, 6.99 back, fee 0.01
        val result = OutgoingBreakdown.ownHistoryAmount(
            thisAddress = me,
            inputs = listOf(me to 1_000L),
            outputs = listOf(other to 300L, me to 699L),
            feeSompi = 1L,
            toOthers = 300L,
            ownChange = 0L,
            isOwnAddress = ::own,
        )
        assertEquals(true to 300L, result)
    }

    @Test
    fun `a swap counts only what left the wallet, not the pool's output to itself`() {
        // We put in 500, the pool 1,000,000; the pool pays itself 1,000,400 back, we get 99 change.
        val result = OutgoingBreakdown.ownHistoryAmount(
            thisAddress = me,
            inputs = listOf(me to 500L, pool to 1_000_000L),
            outputs = listOf(pool to 1_000_400L, me to 99L),
            feeSompi = 1L,
            toOthers = 1_000_400L,
            ownChange = 0L,
            isOwnAddress = ::own,
        )
        // 500 - 99 = 401, less our share of a 1-sompi fee (0): 401
        assertEquals(true to 401L, result)
    }

    @Test
    fun `a swap that paid the wallet Kaspa is a receive`() {
        val result = OutgoingBreakdown.ownHistoryAmount(
            thisAddress = me,
            inputs = listOf(me to 100L, pool to 1_000_000L),
            outputs = listOf(pool to 999_000L, me to 1_099L),
            feeSompi = 1L,
            toOthers = 999_000L,
            ownChange = 0L,
            isOwnAddress = ::own,
        )
        assertEquals(false to 999L, result)
    }

    @Test
    fun `two own funding addresses each show the whole send`() {
        val result = OutgoingBreakdown.ownHistoryAmount(
            thisAddress = me,
            inputs = listOf(me to 100L, change to 900L),
            outputs = listOf(other to 800L, change to 190L),
            feeSompi = 10L,
            toOthers = 800L,
            ownChange = 190L,
            isOwnAddress = ::own,
        )
        assertEquals(true to 800L, result)
    }

    @Test
    fun `an own input of unknown amount keeps the caller's figure`() {
        assertNull(
            OutgoingBreakdown.ownHistoryAmount(me, listOf(me to null), listOf(other to 5L), null, 5L, 0L, ::own),
        )
    }

    @Test
    fun `no input from this address keeps the caller's figure`() {
        assertNull(
            OutgoingBreakdown.ownHistoryAmount(me, listOf(other to 50L), listOf(me to 49L), 1L, 0L, 0L, ::own),
        )
    }

    @Test
    fun `a received result turns the row into a receive with no change lines`() {
        val outputs = listOf(pool to 998_995L, me to 1_099L, change to 5L)
        val base = OutgoingBreakdown.of(me, outputs) { if (own(it)) "Address #1" else null }
        val shown = OutgoingBreakdown.withOwnHistory(
            base, me,
            inputs = listOf(me to 100L, pool to 1_000_000L),
            outputs = outputs,
            feeSompi = 1L,
            isOwnAddress = ::own,
        )
        // 1,099 back here and 5 to another own address, for the 100 put in
        assertEquals(1_004L, shown.receivedSompi)
        assertTrue(shown.change.isEmpty())
    }

    @Test
    fun `the fee share works in full width`() {
        val big = 2_000_000_000_000_000_000L
        val result = OutgoingBreakdown.ownHistoryAmount(
            thisAddress = me,
            inputs = listOf(me to 1_000_000L, pool to big),
            outputs = listOf(pool to big - 10_000L + 1_000_000L - 2_000L, me to 0L, other to 10_000L),
            feeSompi = 2_000L,
            toOthers = 10_000L + big - 10_000L + 1_000_000L - 2_000L,
            ownChange = 0L,
            isOwnAddress = ::own,
        )
        // all of our 1,000,000 left; our fee share rounds to 0
        assertEquals(true to 1_000_000L, result)
    }
}
