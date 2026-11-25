package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > Chats > Who Can Message You? (iOS 2026-11-23) and the senders it silences (audit 2026-11-24 #13). */
class WhoCanMessageTest {

    @Test
    fun anyoneHidesNothing() {
        assertFalse(WhoCanMessage.hidesRequest(WhoCanMessage.ANYONE, null))
        assertFalse(WhoCanMessage.hidesRequest(WhoCanMessage.ANYONE, "alice"))
    }

    @Test
    fun usernamesHidesOnlySendersWithNoName() {
        assertTrue(WhoCanMessage.hidesRequest(WhoCanMessage.USERNAMES, null))
        assertTrue(WhoCanMessage.hidesRequest(WhoCanMessage.USERNAMES, ""))
        assertFalse(WhoCanMessage.hidesRequest(WhoCanMessage.USERNAMES, "alice"))
    }

    @Test
    fun rawValuesMatchIos() {
        assertEquals("anyone", WhoCanMessage.ANYONE.raw)
        assertEquals("usernames", WhoCanMessage.USERNAMES.raw)
        assertEquals(WhoCanMessage.ANYONE, WhoCanMessage.fromRaw(null))
        assertEquals(WhoCanMessage.ANYONE, WhoCanMessage.fromRaw("everyone"))
        assertEquals(WhoCanMessage.USERNAMES, WhoCanMessage.fromRaw("usernames"))
    }

    @Test
    fun entriesAreWalletPipeAddressLowercased() {
        assertEquals("kaspa:qwallet|kaspa:qsender", HiddenRequestSenders.entry("Kaspa:QWallet ", "kaspa:QSender"))
    }

    @Test
    fun addingKeepsOneOfEachAndTheNewest500() {
        val once = HiddenRequestSenders.adding(emptyList(), "w|a")
        assertEquals(listOf("w|a"), HiddenRequestSenders.adding(once, "w|a"))
        val full = (0 until HiddenRequestSenders.LIMIT).map { "w|$it" }
        val next = HiddenRequestSenders.adding(full, "w|new")
        assertEquals(HiddenRequestSenders.LIMIT, next.size)
        assertEquals("w|1", next.first())
        assertEquals("w|new", next.last())
    }

    @Test
    fun planRingsNamedRequestsAndDropsAnsweredOnes() {
        val entries = listOf("w|named", "w|nameless", "w|accepted", "w|notloaded", "other|named")
        val (remaining, ring) = HiddenRequestSenders.plan(
            entries = entries,
            wallet = "W",
            isRequest = { address ->
                when (address) {
                    "accepted" -> false
                    "notloaded" -> null
                    else -> true
                }
            },
            label = { address -> if (address == "named") "alice" else null },
        )
        // another wallet's entry, a request still with no name and one not loaded yet stay
        assertEquals(listOf("w|nameless", "w|notloaded", "other|named"), remaining)
        assertEquals(listOf("named" to "alice"), ring)
    }
}
