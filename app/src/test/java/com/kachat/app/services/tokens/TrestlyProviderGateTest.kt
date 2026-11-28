package com.kachat.app.services.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Trestly's provider gate that run without a WebView (release audit 2026-11-27 L7):
 * the strict JSON every bridge message must be, and which methods can open the Approve sheet.
 */
class TrestlyProviderGateTest {
    @Test
    fun bridgeMessagesMustBePlainJson() {
        assertNotNull(TrestlyProvider.parseStrict("""{"id":1,"method":"kaspa_getNetwork"}"""))
        assertNull("trailing text", TrestlyProvider.parseStrict("""{"id":1} {"id":2}"""))
        assertNull("NaN", TrestlyProvider.parseStrict("""{"id":NaN}"""))
        assertNull("unquoted key", TrestlyProvider.parseStrict("""{id:1}"""))
        assertNull("single quotes", TrestlyProvider.parseStrict("""{'id':1}"""))
        assertNull("not JSON", TrestlyProvider.parseStrict("hello"))
    }

    @Test
    fun onlyTheThreeTradesAskForApproval() {
        assertEquals(setOf("kusdt_swap", "kusdt_addLiquidity", "kusdt_removeLiquidity"), TrestlyProvider.TRADE_METHODS)
        // Every trade is one the site is told K supports; send and redeem are not.
        assertTrue(TrestlyProvider.METHODS.containsAll(TrestlyProvider.TRADE_METHODS))
        assertTrue("kusdt_send" !in TrestlyProvider.METHODS && "kusdt_redeem" !in TrestlyProvider.METHODS)
    }
}
