package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingBreakdownTest {
    private val self = "kaspa:qself"
    private val labels = mapOf(
        "kaspa:qfifteen" to "Address #15",
        "kaspa:qchat" to "Chatting Address",
    )
    private fun own(address: String) = labels[address]

    @Test
    fun sentWithChangeToAnotherOwnAddress() {
        // Address #14 got 10, sent 1, and the change went to Address #15.
        val b = OutgoingBreakdown.of(self, listOf("kaspa:qfriend" to 100_000_000L, "kaspa:qfifteen" to 899_790_000L), ::own)
        assertEquals(100_000_000L, b.toOthers)
        assertEquals(listOf("Address #15" to 899_790_000L), b.change)
        assertFalse(b.moved)
        assertEquals(100_000_000L, b.amountSompi)
    }

    @Test
    fun outputsToOthersAreSummedNotTheSmallest() {
        val b = OutgoingBreakdown.of(self, listOf("kaspa:qa" to 300L, "kaspa:qb" to 200L), ::own)
        assertEquals(500L, b.toOthers)
        assertTrue(b.change.isEmpty())
        assertEquals(500L, b.amountSompi)
    }

    @Test
    fun changeBackToThisAddressIsSkipped() {
        val b = OutgoingBreakdown.of(self, listOf("kaspa:qa" to 300L, "KASPA:QSELF" to 900L, "" to 5L), ::own)
        assertEquals(300L, b.toOthers)
        assertTrue(b.change.isEmpty())
    }

    @Test
    fun everythingToOwnAddressesIsMoved() {
        val b = OutgoingBreakdown.of(
            self,
            listOf("kaspa:qfifteen" to 400L, "kaspa:qchat" to 100L, "kaspa:qfifteen" to 50L),
            ::own,
        )
        assertTrue(b.moved)
        assertEquals(550L, b.amountSompi)
        // One line per own address, in output order, repeats added up.
        assertEquals(listOf("Address #15" to 450L, "Chatting Address" to 100L), b.change)
    }

    @Test
    fun nothingLeftIsNotMoved() {
        val b = OutgoingBreakdown.of(self, listOf(self to 900L), ::own)
        assertFalse(b.moved)
        assertEquals(0L, b.amountSompi)
    }
}
