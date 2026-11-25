package com.kachat.app.services

import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swap's pure rules (iOS 518fdc8c `SwapService`, `SwapModels`; audit 2026-11-24 #1, #3, #5, #6). */
class SwapServiceTest {

    private val payout = "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh"

    private fun answer(
        payoutAddress: String? = payout,
        fromCurrency: String? = "kas",
        toCurrency: String? = "xrp",
        fromNetwork: String? = "kas",
        toNetwork: String? = "xrp",
        fromAmount: Double? = 100.0,
        payoutExtraId: String? = null,
    ) = ChangeNowTransaction(
        id = "abc", payinAddress = "kaspa:deposit", payoutAddress = payoutAddress,
        fromCurrency = fromCurrency, toCurrency = toCurrency, fromNetwork = fromNetwork, toNetwork = toNetwork,
        fromAmount = fromAmount, payoutExtraId = payoutExtraId,
    )

    @Test
    fun `an answer that matches the request is taken`() {
        assertTrue(SwapService.answerMatches(answer(), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        // letter case aside, and names it leaves out are not held against it
        assertTrue(SwapService.answerMatches(answer(payoutAddress = payout.uppercase(), toCurrency = null, toNetwork = ""), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        // within 1% of the amount asked
        assertTrue(SwapService.answerMatches(answer(fromAmount = 100.9), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
    }

    @Test
    fun `an answer for another exchange is refused`() {
        // no payout address in the answer
        assertFalse(SwapService.answerMatches(answer(payoutAddress = null), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        assertFalse(SwapService.answerMatches(answer(payoutAddress = "rSomeoneElse"), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        assertFalse(SwapService.answerMatches(answer(toCurrency = "btc"), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        assertFalse(SwapService.answerMatches(answer(fromNetwork = "eth"), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        assertFalse(SwapService.answerMatches(answer(fromAmount = 102.0), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, null))
        // a payout tag echoed back must be the one sent
        assertFalse(SwapService.answerMatches(answer(payoutExtraId = "999"), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, "123"))
        assertTrue(SwapService.answerMatches(answer(payoutExtraId = "123"), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, "123"))
        assertTrue(SwapService.answerMatches(answer(payoutExtraId = ""), SwapCoin.KAS, SwapCoin.XRP, 100.0, payout, "123"))
    }

    @Test
    fun `quoted amounts are plain decimals`() {
        assertEquals("1.5", SwapService.formatQuotedAmount(1.5))
        assertEquals("12", SwapService.formatQuotedAmount(12.0))
        assertEquals("0.00000001", SwapService.formatQuotedAmount(0.00000001))
        assertEquals("0.12345679", SwapService.formatQuotedAmount(0.123456789))
        assertEquals("1234567.1", SwapService.formatQuotedAmount(1234567.1))
        assertEquals(0.00000002, SwapService.roundUp8(0.000000011), 1e-12)
    }

    @Test
    fun `comma decimals are read as decimals, never multiplied`() {
        // iOS audit 2026-11-24 #3: "1,5" sold 15 kUSDT in German
        assertEquals(1.5, SwapService.decimalValue("1,5")!!, 0.0)
        assertEquals(2.25, SwapService.decimalValue("2.25")!!, 0.0)
        assertNull(SwapService.decimalValue(""))
    }

    @Test
    fun `the kUSDT minimum is 1 percent under the quote`() {
        assertEquals(990L, SwapService.minimumOut(1000L))
        assertEquals(99L, SwapService.minimumOut(100L))
        assertEquals(99L, SwapService.minimumOut(99L))
    }

    @Test
    fun `history and agreements are kept per account`() {
        assertEquals("kachat_swap_history_kaspa:qabc", SwapService.historyKey("kaspa:qabc"))
        assertEquals("kachat_swap_agreements_v1_kaspa:qabc", SwapService.agreementsStorageKey(1, "kaspa:qabc"))
        assertEquals("changenow:xyz", SwapService.swapSourceTxId("xyz"))
    }

    private fun tx(id: String, created: Long, to: String = "kas", fromAmount: String = "50", toAmount: String = "1234.5") = SwapTransaction(
        id = id, fromTicker = "usdc", fromNetwork = "matic", toTicker = to, toNetwork = to,
        fromAmount = fromAmount, toAmount = toAmount, payinAddress = "0xdeposit", payoutAddress = "kaspa:qpayout",
        status = "waiting", createdAtMillis = created,
    )

    @Test
    fun `a swap into KAS is a buy, out of it a sell`() {
        val buy = SwapService.portfolioPrefill(tx("a", 5L))!!
        assertEquals("buy", buy.type)
        assertEquals(1234.5, buy.amountKas, 0.0)
        assertEquals(50.0, buy.fiatValue, 0.0)
        assertEquals("ChangeNOW swap a", buy.notes)
        val sell = SwapService.portfolioPrefill(tx("b", 5L, to = "usdc", fromAmount = "100", toAmount = "9.5"))!!
        assertEquals("sell", sell.type)
        assertEquals(100.0, sell.amountKas, 0.0)
        assertNull(SwapService.portfolioPrefill(tx("c", 5L, toAmount = "")))
    }

    @Test
    fun `history survives a round trip, newest first, XRP tags included`() {
        val list = listOf(
            tx("old", 1L),
            tx("new", 9L).copy(payinExtraId = "12345", payoutExtraId = "678", addedToPortfolio = true),
        )
        val back = SwapTransaction.listFromJson(SwapTransaction.listToJson(list))
        assertEquals(listOf("new", "old"), back.map { it.id })
        assertEquals(list[1], back[0])
        assertEquals(list[0], back[1])
        assertTrue(SwapTransaction.listFromJson("not json").isEmpty())
        assertTrue(SwapTransaction.listFromJson(null).isEmpty())
    }

    @Test
    fun `XRP destination tags are whole numbers below 2 to the 32`() {
        assertTrue(SwapCoin.XRP.needsMemo)
        assertFalse(SwapCoin.BTC.needsMemo)
        assertTrue(SwapCoin.XRP.isValidMemo(""))
        assertTrue(SwapCoin.XRP.isValidMemo(" 123456 "))
        assertTrue(SwapCoin.XRP.isValidMemo("4294967295"))
        assertFalse(SwapCoin.XRP.isValidMemo("4294967296"))
        assertFalse(SwapCoin.XRP.isValidMemo("12a"))
        assertFalse(SwapCoin.XRP.isValidMemo("-1"))
        assertFalse(SwapCoin.XRP.isValidMemo("12345678901"))
    }

    @Test
    fun `kUSDT goes through Trestly and is not in ChangeNOW's list`() {
        assertTrue(SwapCoin.KUSDT.isTrestly)
        assertFalse(SwapCoin.CURATED.any { it.isTrestly })
        assertEquals(30, SwapCoin.CURATED.size)
        assertEquals(SwapCoin.CURATED.size, SwapCoin.CURATED.map { it.ticker to it.network }.toSet().size)
    }
}
