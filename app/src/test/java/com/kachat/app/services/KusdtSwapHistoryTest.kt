package com.kachat.app.services

import com.kachat.app.models.SwapTransaction
import com.kachat.app.ui.screens.tokens.swapProviderName
import com.kachat.app.ui.screens.tokens.swapSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** kUSDT swaps in Buy/Sell History (iOS b49a021d `SwapService.recordKusdtSwap`, `SwapTransaction.provider`). */
class KusdtSwapHistoryTest {

    private val address = "kaspa:qpk4m2xyz"
    private val txid = "ab".repeat(32)

    @Test
    fun `a kUSDT buy is filed as a Trestly swap from KAS to kUSDT, both addresses the one it traded from`() {
        // 100 KAS in, 2.61 kUSDT out
        val swap = SwapService.kusdtSwapTransaction(
            txid, bought = true, amountIn = 10_000_000_000L, amountOut = 2_610_000L,
            address = address, confirmed = true, createdAtMillis = 5L,
        )
        assertEquals(txid, swap.id)
        assertEquals(txid, swap.kasSendTxId)
        assertEquals("kas", swap.fromTicker)
        assertEquals("kusdt", swap.toTicker)
        assertEquals("kaspa", swap.fromNetwork)
        assertEquals("kaspa", swap.toNetwork)
        assertEquals("100", swap.fromAmount)
        assertEquals("2.61", swap.toAmount)
        assertEquals(address, swap.payinAddress)
        assertEquals(address, swap.payoutAddress)
        assertEquals("finished", swap.status)
        assertEquals(SwapTransaction.TRESTLY, swap.provider)
        assertTrue(swap.isTrestly)
    }

    @Test
    fun `a kUSDT sale goes the other way, sending until the network accepts it`() {
        // 0.26 kUSDT in, 10.5 KAS out
        val swap = SwapService.kusdtSwapTransaction(
            txid, bought = false, amountIn = 260_000L, amountOut = 1_050_000_000L,
            address = address, confirmed = false, createdAtMillis = 5L,
        )
        assertEquals("kusdt", swap.fromTicker)
        assertEquals("kas", swap.toTicker)
        assertEquals("0.26", swap.fromAmount)
        assertEquals("10.5", swap.toAmount)
        assertEquals("sending", swap.status)
    }

    @Test
    fun `the provider survives a round trip, and older swaps read as ChangeNOW`() {
        val trestly = SwapService.kusdtSwapTransaction(txid, true, 100_000_000L, 26_000L, address, true, 9L)
        val changeNow = SwapTransaction(
            id = "cn1", fromTicker = "usdc", fromNetwork = "matic", toTicker = "kas", toNetwork = "kas",
            fromAmount = "50", toAmount = "1234.5", payinAddress = "0xdep", payoutAddress = address,
            status = "finished", createdAtMillis = 1L,
        )
        val back = SwapTransaction.listFromJson(SwapTransaction.listToJson(listOf(changeNow, trestly)))
        assertEquals(trestly, back[0])
        assertEquals(changeNow, back[1])
        assertNull(back[1].provider)
        assertFalse(back[1].isTrestly)
    }

    @Test
    fun `rows say which service ran the swap, kUSDT written as kUSDT`() {
        val trestly = SwapService.kusdtSwapTransaction(txid, true, 10_000_000_000L, 2_610_000L, address, true, 9L)
        assertEquals("Trestly", swapProviderName(trestly))
        assertEquals("100 KAS → 2.61 kUSDT", swapSummary(trestly))
        val changeNow = trestly.copy(
            id = "cn1", fromTicker = "usdc", toTicker = "kas", fromAmount = "50", toAmount = "1234.5", provider = null,
        )
        assertEquals("ChangeNOW", swapProviderName(changeNow))
        assertEquals("50 USDC → 1234.50000000 KAS", swapSummary(changeNow))
        assertEquals("kUSDT", SwapTransaction.displayTicker("KUSDT"))
        assertEquals("BTC", SwapTransaction.displayTicker("btc"))
    }

    @Test
    fun `kUSDT sold for KAS adds to Portfolio as a buy, noted as Trestly`() {
        val sale = SwapService.kusdtSwapTransaction(txid, false, 2_610_000L, 10_000_000_000L, address, true, 9L)
        val prefill = SwapService.portfolioPrefill(sale)
        assertNotNull(prefill)
        assertEquals("buy", prefill!!.type)
        assertEquals(100.0, prefill.amountKas, 0.0)
        assertEquals(2.61, prefill.fiatValue, 0.0)
        assertEquals("kUSDT swap (Trestly) $txid", prefill.notes)
        val buy = SwapService.kusdtSwapTransaction(txid, true, 10_000_000_000L, 2_610_000L, address, true, 9L)
        assertEquals("sell", SwapService.portfolioPrefill(buy)!!.type)
    }
}
