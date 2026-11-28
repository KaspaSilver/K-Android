package com.kachat.app.services

import com.kachat.app.services.tokens.CardBuy
import com.kachat.app.services.tokens.PermitRequest
import com.kachat.app.services.tokens.PollLoopSlot
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money fixes of iOS d88891ea (audit 2026-11-27): Kasshi's sheetless sign-in only for its own
 * challenge (M2), a lost send held (M3), the card-buy poll loop's token and week cap (M4), the
 * permit capped at the order (L2), and Swap's deposit end states (M1, L18).
 */
class AuditMoneyFixesTest {

    private val address = "kaspa:qpk4m2abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmno"
    private val now = 1_800_000_000_000L

    private fun challenge(
        address: String = this.address,
        timestamp: Long = now,
        nonce: String = "9f2c4a7e1b3d5f60",
        head: String = "Sign this message to authenticate with KasShi.",
    ) = "$head\n\nAddress: $address\nTimestamp: $timestamp\nNonce: $nonce"

    // M2: the challenge shape

    @Test
    fun `Kasshi's own fresh challenge for the connected address is the sign-in`() {
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge(), address, now))
        // the address in another case, CRLF line ends, a timestamp a few minutes off
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge(address.uppercase()), address, now))
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge().replace("\n", "\r\n"), address, now))
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge(timestamp = now - 599_999L), address, now))
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge(nonce = "ABCDEF12"), address, now))
    }

    @Test
    fun `any other text gets the Sign sheet`() {
        val check = { text: String -> KaChatSiteWallet.isKasshiSignInChallenge(text, address, now) }
        // another site's login, another address, an old or future timestamp
        assertFalse(check("Welcome to OtherSite! Sign to log in.\nNonce: 1234abcd"))
        assertFalse(check(challenge(head = "Sign this message to authenticate with OtherSite.")))
        assertFalse(check(challenge(address = "kaspa:qqother")))
        assertFalse(check(challenge(timestamp = now - 600_000L)))
        assertFalse(check(challenge(timestamp = now + 600_000L)))
        assertFalse(check(challenge(timestamp = Long.MIN_VALUE)))
        assertFalse(check(challenge(timestamp = now + Long.MIN_VALUE)))
        assertFalse(check(challenge().replace("Timestamp: $now", "Timestamp: soon")))
        // a nonce too short, too long or not hex
        assertFalse(check(challenge(nonce = "abc123")))
        assertFalse(check(challenge(nonce = "a".repeat(129))))
        assertFalse(check(challenge(nonce = "zzzzzzzz")))
        // anything added
        assertFalse(check(challenge() + "\nAlso: transfer"))
        assertFalse(check("x\n" + challenge()))
        assertFalse(check(challenge().replace("\n\n", "\nhidden\n")))
    }

    @Test
    fun `only kasshi io and www kasshi io sign in without a sheet`() {
        assertEquals(setOf("kasshi.io", "www.kasshi.io"), KaChatSiteWallet.SIGN_IN_HOSTS)
        with(KaChatSiteWallet) {
            val approval = signInApproval("kasshi.io", nowMs = 0L)
            assertTrue(approval.coversSignIn("www.kasshi.io", nowMs = 1L, idle = true))
            assertFalse(approval.coversSignIn("user.kasshi.io", nowMs = 1L, idle = true))
        }
    }

    // M3: a lost send

    @Test
    fun `a send whose answer was lost holds the page's next one for ten minutes`() {
        assertEquals(600_000L, KaChatSiteWallet.SEND_HOLD_MS)
        val until = now + KaChatSiteWallet.SEND_HOLD_MS
        assertTrue(KaChatSiteWallet.sendHeld(until, now))
        assertTrue(KaChatSiteWallet.sendHeld(until, until - 1))
        assertFalse(KaChatSiteWallet.sendHeld(until, until))
        assertFalse(KaChatSiteWallet.sendHeld(null, now))
    }

    // M4: one poll loop

    @Test
    fun `a stopped loop finishing late never clears its successor's slot`() {
        val slot = PollLoopSlot()
        val first = slot.next()
        val jobA = Job()
        slot.start(first, jobA)
        assertTrue(slot.isRunning)
        assertTrue(slot.isCurrent(first))

        // cleared (Swap to Kaspa, an account switch): A is cancelled and no longer current
        slot.stop()
        assertTrue(jobA.isCancelled)
        assertFalse(slot.isCurrent(first))
        assertFalse(slot.isRunning)

        // B starts; A finishing now leaves B's slot alone
        val second = slot.next()
        val jobB = Job()
        slot.start(second, jobB)
        slot.finished(first)
        assertTrue(slot.isRunning)
        assertTrue(slot.isCurrent(second))
        // a stale start doesn't take the slot either
        slot.start(first, Job())
        assertTrue(slot.isRunning)

        // B's own end empties it
        slot.finished(second)
        assertFalse(slot.isRunning)
        jobB.cancel()
    }

    @Test
    fun `an order is followed for a week at most, and never once it is over`() {
        val started = CardBuy(session = "s", address = "kaspa:q", label = "A", spendingIndex = null,
            startedAtMs = now, stage = CardBuy.Stage.BRIDGING, paidSeen = true)
        assertTrue(started.isFollowed(now + CardBuy.FOLLOW_MAX_MS - 1))
        assertFalse(started.isFollowed(now + CardBuy.FOLLOW_MAX_MS))
        assertFalse(started.copy(stage = CardBuy.Stage.ARRIVED).isFollowed(now))
        assertFalse(started.copy(stage = CardBuy.Stage.REFUNDED).isFollowed(now))
        assertTrue(started.copy(stage = CardBuy.Stage.SIGN_AGAIN).isFollowed(now + 86_400_000L))
    }

    // L2: the permit cap

    @Test
    fun `a permit is capped at the order plus 1 percent and a dollar`() {
        // $50.00: 50.50 + 1 = 51.50 USDC
        assertEquals(51_500_000L, PermitRequest.capUnits("50.00"))
        assertEquals(2_010_000L, PermitRequest.capUnits("1.00"))
        assertNull(PermitRequest.capUnits(null))
        assertNull(PermitRequest.capUnits("0"))
        assertNull(PermitRequest.capUnits("abc"))
        assertNull(PermitRequest.capUnits("-5"))

        val order = CardBuy(session = "s", address = "kaspa:qTarget", label = "A", spendingIndex = null,
            startedAtMs = now, dollars = "50.00")
        assertFalse(PermitRequest.exceedsOrder(51_500_000L, order, "kaspa:qtarget"))
        assertTrue(PermitRequest.exceedsOrder(51_500_001L, order, "kaspa:qtarget"))
        assertTrue(PermitRequest.exceedsOrder(1_000_000_000_000L, order, "kaspa:qtarget"))
        // no amount known yet, no order, or another address's order: the parse's own limit stands
        assertFalse(PermitRequest.exceedsOrder(1_000_000_000_000L, order.copy(dollars = null), "kaspa:qtarget"))
        assertFalse(PermitRequest.exceedsOrder(1_000_000_000_000L, null, "kaspa:qtarget"))
        assertFalse(PermitRequest.exceedsOrder(1_000_000_000_000L, order, "kaspa:qother"))
    }

    // M1, L18: Swap's deposit

    @Test
    fun `the deposit screen stops polling at any end state`() {
        for (status in listOf("finished", "failed", "refunded", "expired", "FAILED")) {
            assertTrue(status, SwapService.isEndStatus(status))
        }
        for (status in listOf("new", "waiting", "confirming", "exchanging", "sending", "verifying")) {
            assertFalse(status, SwapService.isEndStatus(status))
        }
    }

    @Test
    fun `Send KAS from K shows only while ChangeNOW waits and K hasn't sent it`() {
        assertTrue(SwapService.offersDepositSend("new", null))
        assertTrue(SwapService.offersDepositSend("Waiting", null))
        // sent from K (a txid, or a send cancelled mid-submit that may still land)
        assertFalse(SwapService.offersDepositSend("waiting", "ab".repeat(32)))
        assertFalse(SwapService.offersDepositSend("waiting", ""))
        // ChangeNOW has seen a deposit, or it's over
        assertFalse(SwapService.offersDepositSend("confirming", null))
        assertFalse(SwapService.offersDepositSend("exchanging", null))
        assertFalse(SwapService.offersDepositSend("expired", null))
    }

    @Test
    fun `a lost-answer error reaches the page with its txid`() {
        val error = KaChatSiteWallet.SiteError(KaChatSiteWallet.Code.MAYBE_SENT, "may", txid = "cd".repeat(32))
        assertEquals("cd".repeat(32), error.txid)
        assertNull(KaChatSiteWallet.SiteError(KaChatSiteWallet.Code.REJECTED, "no").txid)
        // the page script carries a txid on its error
        assertTrue(KaChatSiteWallet.script.contains("d.error.txid"))
    }
}
