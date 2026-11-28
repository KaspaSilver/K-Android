package com.kachat.app.services

import com.kachat.app.services.tokens.PermitRequest
import com.kachat.app.services.tokens.TokenWallet
import com.kachat.app.services.tokens.TrestlyTrade
import com.kachat.app.ui.screens.managedKasAmount
import com.kachat.app.ui.screens.ownAddressListOrder
import com.kachat.app.util.UserFacingError
import com.kachat.app.viewmodels.ColdStorageViewModel
import com.kachat.app.viewmodels.discoveryProgressShows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet, money and site-signing fixes of iOS 01f71184 (release audit 2026-11-27): site
 * signing refuses KaPost shapes (R3), the challenge's timestamp range (R6), the card-buy permit's
 * amount checks (R6, C6), the Trestly hold after a relaunch, Received kUSDT always in the bell,
 * Choose an Address's order, Cold Storage's quick return and its Used probes (L1, C1), and "0 KAS".
 */
class ReleaseAuditWalletTest {

    private val txid = "a".repeat(64)

    // R3: KaPost's signing shapes are never signed for a site

    @Test
    fun `KaPost action prefixes are refused in any case and after spaces`() {
        for (text in listOf(
            "delete:$txid", "edit:$txid:aGk=", "poll:x", "pollvote:$txid:1", "tip:$txid",
            "schedule:abc", "cancel-schedule:abc", "follow:02abcdef", "unfollow:02abcdef",
            "DELETE:$txid", "  follow:02ab\n",
        )) {
            assertTrue(text, KaChatSiteWallet.looksLikeKaPostAction(text))
        }
    }

    @Test
    fun `a post id alone or first is refused`() {
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction(txid))
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction(txid.uppercase() + ":1"))
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction("$txid:aGVsbG8=:[]"))
    }

    @Test
    fun `a post's base64 text then its mentions is refused`() {
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction("aGVsbG8gd29ybGQ=:[]"))
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction("aGk=: [\"02ab\"]"))
        assertTrue(KaChatSiteWallet.looksLikeKaPostAction("aGk=:{\"a\":1}"))
    }

    @Test
    fun `ordinary texts and Kasshi's own challenge still sign`() {
        val challenge = "Sign this message to authenticate with KasShi.\n\nAddress: kaspa:qabc\nTimestamp: 1800000000000\nNonce: 9f2c4a7e1b3d5f60"
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction(challenge))
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction("Hello from Kasshi"))
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction("Order 42: two items"))
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction("hello:world"))
        // 63 hex characters isn't a txid
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction("a".repeat(63)))
    }

    // R6: the page picks the timestamp

    @Test
    fun `a challenge's timestamp outside the range is refused, never trapped`() {
        val address = "kaspa:qabc"
        fun challenge(ts: String) = "Sign this message to authenticate with KasShi.\n\nAddress: $address\nTimestamp: $ts\nNonce: 9f2c4a7e1b3d5f60"
        for (ts in listOf(Long.MIN_VALUE.toString(), "-1", "0", "100000000000000", Long.MAX_VALUE.toString())) {
            assertFalse(ts, KaChatSiteWallet.isKasshiSignInChallenge(challenge(ts), address, Long.MAX_VALUE))
            assertFalse(ts, KaChatSiteWallet.isKasshiSignInChallenge(challenge(ts), address, 1_800_000_000_000L))
        }
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge("1800000000000"), address, 1_800_000_000_000L))
    }

    @Test
    fun `a send hold is kept by account and site`() {
        assertEquals("kaspa:qacct|kasshi.io", KaChatSiteWallet.sendHoldKey("KASPA:QACCT", "WWW.Kasshi.io"))
        assertEquals(KaChatSiteWallet.sendHoldKey("kaspa:qa", "kasshi.io"), KaChatSiteWallet.sendHoldKey("kaspa:qa", "www.kasshi.io"))
        assertFalse(KaChatSiteWallet.sendHoldKey("kaspa:qa", "kasshi.io") == KaChatSiteWallet.sendHoldKey("kaspa:qb", "kasshi.io"))
    }

    // R6, C6: the order's amount

    @Test
    fun `only finite order amounts up to a million dollars count`() {
        assertEquals(50.0, PermitRequest.knownDollars("50.00")!!, 0.0)
        assertEquals(1_000_000.0, PermitRequest.knownDollars("1000000")!!, 0.0)
        assertNull(PermitRequest.knownDollars("1000000.01"))
        assertNull(PermitRequest.knownDollars("Infinity"))
        assertNull(PermitRequest.knownDollars("NaN"))
        assertNull(PermitRequest.knownDollars("1e300"))
        assertNull(PermitRequest.knownDollars("0"))
        assertNull(PermitRequest.knownDollars(null))
        assertNull(PermitRequest.capUnits("1e300"))
    }

    @Test
    fun `a permit is capped at the known amount, and not refused while it isn't known`() {
        assertFalse(PermitRequest.exceedsDollars(51_500_000L, 50.0))
        assertTrue(PermitRequest.exceedsDollars(51_500_001L, 50.0))
        assertFalse(PermitRequest.exceedsDollars(1_000_000_000_000L, null))
        assertFalse(PermitRequest.exceedsDollars(1_000_000_000_000L, Double.POSITIVE_INFINITY))
    }

    // Low: a Trestly hold after a relaunch

    @Test
    fun `a gone coin ends a hold only while this run still reserves every coin`() {
        val keys = listOf("${txid}:0", "${"b".repeat(64)}:3")
        assertTrue(TrestlyTrade.allStillReserved(keys) { _, _ -> true })
        assertFalse(TrestlyTrade.allStillReserved(keys) { id, _ -> id == txid })
        assertFalse(TrestlyTrade.allStillReserved(keys) { _, _ -> false })
        assertFalse(TrestlyTrade.allStillReserved(listOf("garbage")) { _, _ -> true })
    }

    // Received kUSDT always in the bell: the poll keeps going with Address Activity off

    @Test
    fun `the token poll slows to five minutes with Address Activity off`() {
        assertEquals(25_000L, TokenWallet.pollMaxAgeMs(true))
        assertEquals(290_000L, TokenWallet.pollMaxAgeMs(false))
    }

    // Choose an Address: regular ones by number, then quantum ones by number

    @Test
    fun `Choose an Address lists regular addresses before quantum ones, each by number`() {
        val entries = listOf(true to 1_000_000, false to 7, true to 1_000_002, false to 1)
        val ordered = ownAddressListOrder(entries) { it }
        assertEquals(listOf(false to 1, false to 7, true to 1_000_000, true to 1_000_002), ordered)
    }

    // Display: an empty address reads "0 KAS"

    @Test
    fun `an empty card reads 0 KAS, a funded one all eight decimals`() {
        assertTrue(managedKasAmount(0L).startsWith("0 "))
        assertTrue(managedKasAmount(150_000_000L).startsWith("1.50000000 "))
    }

    // Cold Storage: the quick return (L1) and its Used probes (C1)

    private fun row(index: Int, balance: Long = 0L, history: Boolean = false) =
        ColdStorageViewModel.AddressRow(index = index, address = "a$index", balanceSompi = balance, hasHistory = history)

    @Test
    fun `back within two minutes of a full load is a quick return`() {
        assertTrue(ColdStorageViewModel.isQuickReturn(1_000L, 1_000L + 119_999L))
        assertFalse(ColdStorageViewModel.isQuickReturn(1_000L, 1_000L + 120_000L))
        assertFalse(ColdStorageViewModel.isQuickReturn(null, 1_000L))
        // a clock set back isn't one
        assertFalse(ColdStorageViewModel.isQuickReturn(10_000L, 1_000L))
    }

    @Test
    fun `a balances-only reload probes the moved rows and the failed ones`() {
        val before = listOf(row(0, balance = 5), row(1), row(2), row(3, history = true))
        // 0 emptied (a just-used change address); 1 unchanged; 2 failed last time
        val after = ColdStorageViewModel.mergeBalances(before, mapOf("a0" to 0L, "a1" to 0L, "a2" to 0L, "a3" to 0L))
        // an address that held coins before was used
        assertTrue(after[0].hasHistory)
        assertEquals(listOf("a2"), ColdStorageViewModel.balancesOnlyProbes(before, after, setOf("a2")))
        val moved = listOf(row(0), row(1))
        val movedAfter = listOf(row(0), row(1).copy(balanceSompi = 0L))
        assertEquals(emptyList<String>(), ColdStorageViewModel.balancesOnlyProbes(moved, movedAfter, emptySet()))
        // a row that wasn't there before counts as moved
        assertEquals(listOf("a9"), ColdStorageViewModel.balancesOnlyProbes(emptyList(), listOf(row(9)), emptySet()))
    }

    // Manage Addresses' Discover progress

    @Test
    fun `Discover's progress shows each find and at most four times a second`() {
        assertTrue(discoveryProgressShows(1, 0, 1_000L, 990L))
        assertFalse(discoveryProgressShows(0, 0, 1_000L, 900L))
        assertTrue(discoveryProgressShows(0, 0, 1_250L, 1_000L))
    }

    // Errors people read

    @Test
    fun `an unknown error text comes back as it is`() {
        assertEquals("Something odd happened", UserFacingError.translated("Something odd happened"))
        assertTrue(UserFacingError.KNOWN_SENTENCES.containsKey("Insufficient funds for payment"))
        assertTrue(UserFacingError.KNOWN_SENTENCES.containsKey("No internet connection. Check your connection and try again."))
    }
}
