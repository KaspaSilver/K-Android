package com.kachat.app.ui.screens

import com.kachat.app.ui.screens.tokens.SwapStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Wallet tab's saved Buy / Sell: FORM, the prepared kUSDT sell, only within its process. */
class SwapStartRestoreTest {
    @Test
    fun everyStartComesBackInTheSameProcess() {
        for (start in SwapStart.entries) {
            assertEquals(start, restoredSwapStart(savedSwapStart(start, "p1"), "p1"))
        }
    }

    @Test
    fun formIsClosedAfterARecreatedProcess() {
        assertNull(restoredSwapStart(savedSwapStart(SwapStart.FORM, "p1"), "p2"))
    }

    @Test
    fun theOtherStartsOpenFromNothingInANewProcess() {
        for (start in listOf(SwapStart.BUY, SwapStart.SELL, SwapStart.HISTORY)) {
            assertEquals(start, restoredSwapStart(savedSwapStart(start, "p1"), "p2"))
        }
    }

    @Test
    fun closedOrUnknownStaysClosed() {
        assertNull(restoredSwapStart(savedSwapStart(null, "p1"), "p1"))
        assertNull(restoredSwapStart("", "p1"))
        assertNull(restoredSwapStart("NOPE@p1", "p1"))
        // an older save without its process
        assertNull(restoredSwapStart("FORM", "p1"))
        assertEquals(SwapStart.BUY, restoredSwapStart("BUY", "p1"))
    }
}
