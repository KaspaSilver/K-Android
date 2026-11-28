package com.kachat.app.ui.screens.tokens

import com.kachat.app.models.SwapCoin
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.ui.screens.KaspaAmountGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Buy / Sell step by step (iOS 824a30bb `SwapView`, `BuyKaspaSheet`, `SwapDestinationStep`,
 *  `SwapDepositScreen`; `KaspaAmountEntry.grouped` / `ungrouped`). */
class SwapBuySellTest {

    // Buy opens

    @Test
    fun `Buy offers crypto or debit card on mainnet, goes straight to crypto on testnet`() {
        assertEquals(BuyOpening.CHOOSER, buyOpening(isTestnet = false))
        assertEquals(BuyOpening.CRYPTO, buyOpening(isTestnet = true))
        assertTrue(DebitCardBuyOffer.isOffered(isTestnet = false))
        assertFalse(DebitCardBuyOffer.isOffered(isTestnet = true))
    }

    @Test
    fun `the funding card's flow opens the chooser or the crypto screens`() {
        val mainnet = BuyKaspaFlowState().apply { open(isTestnet = false) }
        assertTrue(mainnet.showSheet)
        assertNull(mainnet.swapStart)
        assertTrue(mainnet.isOpen)
        val testnet = BuyKaspaFlowState().apply { open(isTestnet = true) }
        assertFalse(testnet.showSheet)
        assertEquals(SwapStart.BUY, testnet.swapStart)
        assertFalse(BuyKaspaFlowState().isOpen)
    }

    // The pair and where it goes next

    @Test
    fun `buying Kaspa asks where it lands next, for kUSDT too`() {
        val plan = swapPairPlan(SwapCoin.BTC, SwapCoin.KAS, buyToChatting = false)
        assertFalse(plan.kasSends)
        assertEquals(SwapCoin.BTC, plan.otherCoin)
        assertFalse(plan.landOnChatting)
        assertEquals(SwapStep.Destination, plan.next)
        assertEquals(SwapStep.Destination, swapPairPlan(SwapCoin.KUSDT, SwapCoin.KAS, buyToChatting = false).next)
    }

    @Test
    fun `selling Kaspa goes straight to the order and never lands on the Chatting Address`() {
        val plan = swapPairPlan(SwapCoin.KAS, SwapCoin.USDT_TRX, buyToChatting = true)
        assertTrue(plan.kasSends)
        assertEquals(SwapCoin.USDT_TRX, plan.otherCoin)
        assertFalse(plan.landOnChatting)
        assertEquals(SwapStep.Form, plan.next)
    }

    @Test
    fun `opened to fund the chatting address, bought Kaspa lands there`() {
        assertTrue(swapPairPlan(SwapCoin.ETH, SwapCoin.KAS, buyToChatting = true).landOnChatting)
    }

    @Test
    fun `only the where step and the order have a back button`() {
        assertTrue(SwapStep.Destination.canGoBack)
        assertTrue(SwapStep.Form.canGoBack)
        assertFalse(SwapStep.Deposit("a").canGoBack)
        assertFalse(SwapStep.Complete("a").canGoBack)
        assertFalse(SwapStep.KusdtDone("sent").canGoBack)
    }

    // Where the Kaspa lands

    @Test
    fun `Continue waits for a pick only under Choose an Address`() {
        assertTrue(swapDestinationCanContinue(isKusdt = false, choosing = false, picked = null))
        assertFalse(swapDestinationCanContinue(isKusdt = false, choosing = true, picked = null))
        assertTrue(swapDestinationCanContinue(isKusdt = false, choosing = true, picked = "kaspa:qabc"))
        assertTrue(swapDestinationCanContinue(isKusdt = true, choosing = true, picked = null))
    }

    @Test
    fun `kUSDT pays from the Chatting Address or an address holding it, most first`() {
        val held = listOf(
            TokenHolding(spendingIndex = null, address = "kaspa:chat", units = 5, coins = 1),
            TokenHolding(spendingIndex = 3, address = "kaspa:three", units = 10, coins = 1),
            TokenHolding(spendingIndex = 7, address = "kaspa:seven", units = 900, coins = 2),
            TokenHolding(spendingIndex = 9, address = "kaspa:empty", units = 0, coins = 0),
        )
        val choices = kusdtDestinationChoices("kaspa:chat", "Chatting Address", held) { "Address #${it.spendingIndex}" }
        assertEquals(listOf("kaspa:chat", "kaspa:seven", "kaspa:three"), choices.map { it.address })
        assertEquals("Chatting Address", choices[0].label)
        assertNull(choices[0].spendingIndex)
        assertEquals(5L, choices[0].units)
        assertEquals("Address #7", choices[1].label)
        assertEquals(7, choices[1].spendingIndex)
    }

    @Test
    fun `the Chatting Address is offered even holding no kUSDT`() {
        val choices = kusdtDestinationChoices("kaspa:chat", "Chatting Address", emptyList()) { "x" }
        assertEquals(1, choices.size)
        assertEquals(0L, choices[0].units)
        assertTrue(kusdtDestinationChoices(null, "Chatting Address", emptyList()) { "x" }.isEmpty())
    }

    // The deposit screen

    @Test
    fun `the deposit's status card reads ChangeNOW's status`() {
        assertEquals(DepositStage.WAITING, DepositStage.of("new"))
        assertEquals(DepositStage.WAITING, DepositStage.of("waiting"))
        assertEquals(DepositStage.CONFIRMING, DepositStage.of("Confirming"))
        assertEquals(DepositStage.SENDING, DepositStage.of("sending"))
        assertFalse(DepositStage.of("exchanging").isProblem)
        listOf("failed", "refunded", "expired", "verifying").forEach { assertTrue(it, DepositStage.of(it).isProblem) }
        assertEquals(10_000L, DEPOSIT_POLL_MS)
    }

    @Test
    fun `a saved swap's coins come back by ticker and network`() {
        assertEquals(SwapCoin.KAS, swapCoinFor("kas", "kas"))
        assertEquals(SwapCoin.KUSDT, swapCoinFor("kusdt", "kaspa-trestly"))
        assertEquals(SwapCoin.USDC_BASE, swapCoinFor("usdc", "base"))
        assertEquals(SwapCoin("pepe", "eth", "PEPE"), swapCoinFor("pepe", "eth"))
    }

    // Grouped digits as you type

    @Test
    fun `amounts group thousands as they are shown`() {
        assertEquals("12,345.5", KaspaAmountGrouping.grouped("12345.5", Locale.US))
        assertEquals("1,234,567", KaspaAmountGrouping.grouped("1234567", Locale.US))
        assertEquals("999.99", KaspaAmountGrouping.grouped("999.99", Locale.US))
        assertEquals("", KaspaAmountGrouping.grouped("", Locale.US))
        assertEquals("12 345,5", KaspaAmountGrouping.grouped("12345.5", Locale.GERMANY))
    }

    @Test
    fun `what is typed goes back plain`() {
        // the field's value never holds the drawn commas: a digit typed is just a digit more
        assertEquals("123456", KaspaAmountGrouping.ungrouped("123456", previous = "12345", locale = Locale.US))
        // a comma typed at the end is the decimal point
        assertEquals("12345.", KaspaAmountGrouping.ungrouped("12345,", previous = "12345", locale = Locale.US))
        assertEquals("0.5", KaspaAmountGrouping.ungrouped("0,5", previous = "0", locale = Locale.US))
        // a pasted grouped number loses its grouping
        assertEquals("1234", KaspaAmountGrouping.ungrouped("1,234", previous = "", locale = Locale.US))
        // a comma-decimal phone: the comma is the point, dots beside it are grouping
        assertEquals("12345.5", KaspaAmountGrouping.ungrouped("12345,5", previous = "12345", locale = Locale.GERMANY))
        assertEquals("1234.5", KaspaAmountGrouping.ungrouped("1.234,5", previous = "", locale = Locale.GERMANY))
        assertEquals("12.51", KaspaAmountGrouping.ungrouped("12.51", previous = "12.5", locale = Locale.GERMANY))
    }
}
