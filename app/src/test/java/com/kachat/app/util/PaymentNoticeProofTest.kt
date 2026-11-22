package com.kachat.app.util

import com.kachat.app.util.PaymentNoticeProof.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security audit H1 / M1 / M2: who may claim a payment with a payment_notice. */
class PaymentNoticeProofTest {

    private val me = "kaspa:qme"
    private val payer = "kaspa:qpayer"
    private val spending = "kaspa:qpayerspending"
    private val paidAt = 1_790_000_000_000L

    private fun check(
        inputs: List<String?> = listOf(spending),
        paid: Long = 100,
        claimed: Long = 100,
        payload: String? = null,
        accepted: Boolean? = true,
        own: Set<String> = setOf(me),
        others: Set<String> = emptySet(),
        noticeAt: Long = paidAt + 3_000,
        paymentAt: Long? = paidAt,
    ) = PaymentNoticeProof.checkKas(paid, claimed, payload, accepted, inputs, own, others, payer, paymentAt, noticeAt)

    @Test
    fun genuineNoticeRightAfterASpendingAddressPaymentIsValid() {
        assertEquals(Verdict.Valid, check())
    }

    @Test
    fun paymentFromTheContactsChattingAddressIsValidWhenever() {
        assertEquals(Verdict.Valid, check(inputs = listOf(payer), noticeAt = paidAt + 30L * 24 * 3600 * 1000))
    }

    @Test
    fun someoneElsesOldPaymentCantBeClaimed() {
        assertTrue(check(inputs = listOf("kaspa:qexchange"), noticeAt = paidAt + 2L * 3600 * 1000) is Verdict.Invalid)
    }

    @Test
    fun aNoticeWithoutAKnownTimeIsNotTied() {
        assertTrue(check(noticeAt = 0) is Verdict.Invalid)
        assertTrue(check(paymentAt = null) is Verdict.Invalid)
    }

    @Test
    fun smallClockSkewStillCounts() {
        assertEquals(Verdict.Valid, check(noticeAt = paidAt - 30_000))
        assertTrue(check(noticeAt = paidAt - PaymentNoticeProof.CLOCK_SKEW_MS - 1) is Verdict.Invalid)
    }

    @Test
    fun unacceptedPaymentIsASettledUnknown() {
        assertEquals(Verdict.Unknown(settled = true), check(accepted = false))
        assertEquals(Verdict.Unknown(settled = true), check(accepted = null))
    }

    @Test
    fun unresolvedInputIsUnknown() {
        assertEquals(Verdict.Unknown(), check(inputs = listOf(spending, null)))
        assertEquals(Verdict.Unknown(), check(inputs = emptyList()))
    }

    @Test
    fun wrongAmountPayloadOwnAndOtherContactAreInvalid() {
        assertTrue(check(paid = 99) is Verdict.Invalid)
        assertTrue(check(payload = "6b63") is Verdict.Invalid)
        assertTrue(check(inputs = listOf(me)) is Verdict.Invalid)
        assertTrue(check(inputs = listOf("kaspa:qother"), others = setOf("kaspa:qother")) is Verdict.Invalid)
    }

    @Test
    fun givesUpOnlyOnASettledUnknownADayOld() {
        val now = paidAt + PaymentNoticeProof.UNVERIFIED_GIVE_UP_MS
        assertTrue(PaymentNoticeProof.shouldGiveUp(Verdict.Unknown(settled = true), paidAt, now))
        assertFalse(PaymentNoticeProof.shouldGiveUp(Verdict.Unknown(settled = true), paidAt, now - 1))
        assertFalse(PaymentNoticeProof.shouldGiveUp(Verdict.Unknown(), paidAt, now))
        assertFalse(PaymentNoticeProof.shouldGiveUp(Verdict.Valid, paidAt, now))
    }
}
