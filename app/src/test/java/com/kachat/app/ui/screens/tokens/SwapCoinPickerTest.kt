package com.kachat.app.ui.screens.tokens

import com.kachat.app.models.SwapCoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swap's coin picker (iOS 518fdc8c `SwapCoinPickerView.rootRows` / `filteredRows`). */
class SwapCoinPickerTest {

    @Test
    fun `kUSDT first, then USD Coin and Tether folded, then the rest`() {
        val rows = swapCoinPickerRows(emptySet(), "")
        assertEquals(SwapCoin.KUSDT, rows[0].second)
        assertEquals("group-usdc", rows[1].first)
        assertEquals("group-usdt", rows[2].first)
        assertEquals(SwapCoin.BTC, rows[3].second)
        assertTrue(rows.none { it.second?.ticker == "usdc" || it.second?.ticker == "usdt" })
        assertEquals(1 + 2 + 14, rows.size)
    }

    @Test
    fun `an opened group lists its networks under it`() {
        val rows = swapCoinPickerRows(setOf("usdt"), "")
        val start = rows.indexOfFirst { it.first == "group-usdt" }
        val networks = rows.drop(start + 1).takeWhile { it.first.startsWith("network-usdt") }
        assertEquals(7, networks.size)
        assertEquals(SwapCoin.USDT_ETH, networks.first().second)
    }

    @Test
    fun `search matches names and tickers`() {
        assertEquals(listOf(SwapCoin.XRP), swapCoinPickerRows(emptySet(), "xrp").map { it.second })
        assertEquals(listOf("group-usdt"), swapCoinPickerRows(emptySet(), "teth").map { it.first })
        assertEquals(SwapCoin.KUSDT, swapCoinPickerRows(emptySet(), "kusdt").single().second)
    }
}
