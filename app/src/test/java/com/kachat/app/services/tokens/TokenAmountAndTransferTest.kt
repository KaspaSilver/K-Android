package com.kachat.app.services.tokens

import com.kachat.app.util.PaymentPoolProtocol
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import protowire.Rpc

/**
 * Token amounts as typed and shown (iOS `TokenAmount`, `WalletToken.plainAmount`), the transfer's
 * pure rules (iOS `KusdtTransfer`: Fund This Address's shortfall, coin order, audit #5's review
 * check, KaPost's build-for-this-review check) and the `token_payment_notice` envelope (MESSAGING.md
 * "Token Payments in a Chat").
 */
class TokenAmountAndTransferTest {
    private val kusdt = WalletToken.KUSDT

    @Test
    fun `amounts read back as typed`() {
        assertEquals(12_500_000L, TokenAmount.unitsFromTyped("12.5", kusdt))
        assertEquals(1_250_500_000L, TokenAmount.unitsFromTyped("1,250.5", kusdt))
        assertEquals(1L, TokenAmount.unitsFromTyped("0.000001", kusdt))
        assertEquals(500_000L, TokenAmount.unitsFromTyped(".5", kusdt))
        assertNull(TokenAmount.unitsFromTyped("", kusdt))
        assertNull(TokenAmount.unitsFromTyped("0", kusdt))
        assertNull(TokenAmount.unitsFromTyped("0.0000001", kusdt))
        assertNull(TokenAmount.unitsFromTyped("1.2.3", kusdt))
        assertNull(TokenAmount.unitsFromTyped("-1", kusdt))
        assertNull(TokenAmount.unitsFromTyped("abc", kusdt))
        assertNull(TokenAmount.unitsFromTyped("99999999999999999", kusdt))
    }

    @Test
    fun `amounts written plainly keep cents for a dollar token`() {
        assertEquals("12.50", kusdt.plainAmount(12_500_000L))
        assertEquals("12", TokenAmount.typed(12_000_000L, kusdt))
        assertEquals("12.00", TokenAmount.typed(12_000_000L, kusdt, keepCents = true))
        assertEquals("0.000001", kusdt.plainAmount(1L))
        assertEquals("1250.25", kusdt.plainAmount(1_250_250_000L))
    }

    @Test
    fun `a recorded line knows its transaction`() {
        val txid = "a".repeat(64)
        assertEquals(txid, TokenActivity("$txid:s3:sent", "sent", 1, "", 0).txid)
        assertNull(TokenActivity("not-a-txid", "sent", 1, "", 0).txid)
        assertEquals("s3", TokenHolding(3, "kaspa:x", 1, 1).id)
        assertEquals(TokenHolding.CHATTING_ID, TokenHolding(null, "kaspa:x", 1, 1).id)
    }

    @Test
    fun `Fund This Address asks for what's short plus a margin`() {
        // 1.5 KAS for a change coin, nothing there: 1.5 + 0.2
        assertEquals(170_000_000L, KusdtTransfer.shortfall(150_000_000L, 0L))
        // never less than 0.1 KAS
        assertEquals(10_000_000L, KusdtTransfer.shortfall(0L, 50_000_000L))
        // rounded up to 0.01 KAS
        assertEquals(11_000_000L, KusdtTransfer.shortfall(0L, 9_500_001L))
    }

    private fun coin(txid: String, index: Int, amount: Long) =
        KusdtBalances.Coin(txid, index, Kusdt.State.plain(ByteArray(32), amount), 150_000_000L)

    @Test
    fun `a Not confirmed send's coin goes first, then the largest`() {
        val small = coin("a".repeat(64), 0, 1)
        val big = coin("b".repeat(64), 0, 100)
        val unconfirmed = coin("c".repeat(64), 1, 5)
        val empty = coin("d".repeat(64), 0, 0)
        val order = KusdtTransfer.sortedTokenCoins(listOf(small, big, unconfirmed, empty), setOf("c".repeat(64) + ":1"))
        assertEquals(listOf(unconfirmed, big, small), order)
    }

    private fun prepared(fee: Long, holding: TokenHolding = TokenHolding(null, "kaspa:a", 10, 1), kasToRecipient: Long = 150_000_000L,
                         feeFromTokenKas: Boolean = false, recipient: String = "kaspa:b", amount: Long = 5) =
        KusdtTransfer.Prepared(
            txid = "e".repeat(64), fee = fee, kasToRecipient = kasToRecipient, rpc = Rpc.RpcTransaction.getDefaultInstance(),
            spentCoins = emptyList(), changeIndex = 1, holding = holding, recipient = recipient, amount = amount,
            feeFromTokenKas = feeFromTokenKas, kasBack = 0,
        )

    @Test
    fun `a rebuilt review that would send something else stops the swipe`() {
        val reviewed = prepared(fee = 3_000_000L)
        assertFalse(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 3_000_000L)))
        assertFalse(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 2_000_000L)))
        assertTrue(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 3_000_001L)))
        assertTrue(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 3_000_000L, holding = TokenHolding(2, "kaspa:c", 10, 1))))
        assertTrue(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 3_000_000L, kasToRecipient = 148_000_000L)))
        assertTrue(KusdtTransfer.changedSinceReview(reviewed, prepared(fee = 3_000_000L, feeFromTokenKas = true)))
    }

    @Test
    fun `only the build for what the review shows is sent`() {
        val built = prepared(fee = 1)
        assertTrue(KusdtTransfer.matches(built, "chatting", "KASPA:B".lowercase(), 5))
        assertFalse(KusdtTransfer.matches(built, "s1", "kaspa:b", 5))
        assertFalse(KusdtTransfer.matches(built, "chatting", "kaspa:c", 5))
        assertFalse(KusdtTransfer.matches(built, "chatting", "kaspa:b", 6))
        assertFalse(KusdtTransfer.matches(built, "chatting", null, 5))
    }

    @Test
    fun `a token payment notice has the MESSAGING md shape and parses back`() {
        val json = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.TokenPaymentNoticeContent(token = "kusdt", txId = "a1", index = 0, amount = 12_500_000L, address = "kaspa:qq")
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("token_payment_notice", obj.get("type").asString)
        assertEquals("kusdt", obj.get("token").asString)
        assertEquals(0, obj.get("index").asInt)
        assertEquals(12_500_000L, obj.get("amount").asLong)
        val parsed = PaymentPoolProtocol.parse(json) as PaymentPoolProtocol.Envelope.TokenNotice
        assertEquals("kaspa:qq", parsed.content.address)
        // every field is required
        assertNull(PaymentPoolProtocol.parse("""{"type":"token_payment_notice","token":"kusdt","txId":"a1","index":0,"address":"kaspa:qq"}"""))
        assertNull(PaymentPoolProtocol.parse("""{"type":"token_payment_notice","txId":"a1","index":0,"amount":1,"address":"kaspa:qq"}"""))
    }

    @Test
    fun `a token payment bubble reads like a payment`() {
        assertEquals("Sent 12.50 kUSDT", PaymentPoolProtocol.tokenPaymentText("12.50", "kUSDT", isOutgoing = true))
        assertEquals("Received 12.50 kUSDT", PaymentPoolProtocol.tokenPaymentText("12.50", "kUSDT", isOutgoing = false))
        assertEquals("Unverified kUSDT payment", PaymentPoolProtocol.tokenUnverifiedText("kUSDT"))
    }
}
