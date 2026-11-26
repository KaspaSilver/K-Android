package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Received KAS catch-up (iOS 961fc05a, audit 2026-11-25 C1, C10): only a receive from the last
 * few minutes rings, and the chatting address's floor never moves past an unaccepted block reward
 * that may still be accepted.
 */
class AddressActivityRecencyTest {

    private val coinbase = TransactionResponse.COINBASE_SUBNETWORK_ID
    private val native = "0000000000000000000000000000000000000000"
    private val now = 10_000_000_000L

    private fun tx(blockTime: Long?, subnetworkId: String = native, isAccepted: Boolean? = true) =
        TransactionResponse(
            transactionId = "ff".repeat(32),
            inputsOrNull = emptyList(),
            outputs = listOf(TransactionOutput(500L, "20" + "ab".repeat(32) + "ac", "kaspa:me")),
            blockTime = blockTime,
            payload = null,
            subnetworkId = subnetworkId,
            isAccepted = isAccepted,
        )

    @Test
    fun `a receive from the last 3 minutes rings, an older one doesn't`() {
        assertTrue(AddressActivityNotifier.isRecent(now - 60_000L, now))
        assertTrue(AddressActivityNotifier.isRecent(now - 180_000L, now))
        assertFalse(AddressActivityNotifier.isRecent(now - 180_001L, now))
    }

    @Test
    fun `an unknown block time counts as now`() {
        assertTrue(AddressActivityNotifier.isRecent(0L, now))
    }

    @Test
    fun `the floor moves to the newest transaction seen`() {
        val floor = AddressActivityNotifier.nextChattingFloor(listOf(tx(now - 5_000L), tx(now - 1_000L)), now - 60_000L, now)
        assertEquals(now - 1_000L, floor)
    }

    @Test
    fun `the floor stays below a fresh unaccepted block reward`() {
        val reward = tx(now - 30_000L, subnetworkId = coinbase, isAccepted = false)
        val floor = AddressActivityNotifier.nextChattingFloor(listOf(reward, tx(now - 1_000L)), now - 60_000L, now)
        assertEquals(now - 30_001L, floor)
    }

    @Test
    fun `an old unaccepted block reward no longer holds the floor`() {
        val reward = tx(now - 11 * 60_000L, subnetworkId = coinbase, isAccepted = false)
        val floor = AddressActivityNotifier.nextChattingFloor(listOf(reward, tx(now - 1_000L)), now - 20 * 60_000L, now)
        assertEquals(now - 1_000L, floor)
    }

    @Test
    fun `the floor never goes back`() {
        val reward = tx(now - 30_000L, subnetworkId = coinbase, isAccepted = false)
        val floor = AddressActivityNotifier.nextChattingFloor(listOf(reward), now - 10_000L, now)
        assertEquals(now - 10_000L, floor)
    }

    @Test
    fun `an empty answer moves the floor only on the first look`() {
        assertEquals(now, AddressActivityNotifier.nextChattingFloor(emptyList(), 0L, now))
        assertEquals(now - 90_000L, AddressActivityNotifier.nextChattingFloor(emptyList(), now - 90_000L, now))
    }

    @Test
    fun `an unaccepted ordinary payment still belongs in history`() {
        assertTrue(tx(now, isAccepted = false).belongsInHistory)
        assertFalse(tx(now, subnetworkId = coinbase, isAccepted = false).belongsInHistory)
    }
}
