package com.kachat.app.ui.screens.tokens

import com.kachat.app.models.SwapCoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buy / Sell's coin screens (iOS 824a30bb `SwapCoinChoiceList`): kUSDT, then USD Coin and Tether
 *  folded by network, then the rest. */
class SwapCoinPickerTest {

    private val coins = SwapCoinChoices.everyCoin(includingKaspa = false)

    @Test
    fun `every coin, kUSDT first, then USD Coin's and Tether's networks, then the rest`() {
        assertEquals(SwapCoin.KUSDT, coins[0])
        assertTrue(coins.none { it == SwapCoin.KAS })
        val usdc = SwapCoin.CURATED.filter { it.ticker == "usdc" }
        val usdt = SwapCoin.CURATED.filter { it.ticker == "usdt" }
        assertEquals(usdc, coins.subList(1, 1 + usdc.size))
        assertEquals(usdt, coins.subList(1 + usdc.size, 1 + usdc.size + usdt.size))
        assertEquals(SwapCoin.BTC, coins[1 + usdc.size + usdt.size])
        assertEquals(1 + SwapCoin.CURATED.size, coins.size)
        assertEquals(SwapCoin.KAS, SwapCoinChoices.everyCoin(includingKaspa = true).first())
    }

    @Test
    fun `USD Coin and Tether are one row each, folded by network`() {
        val rows = SwapCoinChoices.rows(coins, emptySet(), "")
        assertEquals("c-kusdt-kaspa-trestly", rows[0].id)
        assertEquals("g-usdc", rows[1].id)
        assertEquals("g-usdt", rows[2].id)
        assertEquals("c-btc-btc", rows[3].id)
        assertTrue(rows.none { it is SwapCoinChoices.Row.Coin && (it.coin.ticker == "usdc" || it.coin.ticker == "usdt") })
        assertEquals(1 + 2 + 14, rows.size)
    }

    @Test
    fun `an opened group lists its networks under it`() {
        val rows = SwapCoinChoices.rows(coins, setOf("usdt"), "")
        val start = rows.indexOfFirst { it.id == "g-usdt" }
        val networks = rows.drop(start + 1).takeWhile { it is SwapCoinChoices.Row.Network }
        assertEquals(7, networks.size)
        assertEquals(SwapCoin.USDT_ETH, (networks.first() as SwapCoinChoices.Row.Network).coin)
        // the other group stays shut
        assertTrue(rows.none { it is SwapCoinChoices.Row.Network && it.coin.ticker == "usdc" })
    }

    @Test
    fun `a ticker on one network is never folded`() {
        val rows = SwapCoinChoices.rows(listOf(SwapCoin.KUSDT, SwapCoin.USDC_POLYGON, SwapCoin.BTC), emptySet(), "")
        assertEquals(listOf("c-kusdt-kaspa-trestly", "c-usdc-matic", "c-btc-btc"), rows.map { it.id })
    }

    @Test
    fun `search lists matching coins flat, by name or ticker`() {
        assertEquals(listOf(SwapCoin.XRP), SwapCoinChoices.rows(coins, emptySet(), "xrp").map { (it as SwapCoinChoices.Row.Coin).coin })
        val tether = SwapCoinChoices.rows(coins, emptySet(), " Teth ")
        assertEquals(7, tether.size)
        assertTrue(tether.all { it is SwapCoinChoices.Row.Coin && it.coin.ticker == "usdt" })
        assertEquals(SwapCoin.KUSDT, (SwapCoinChoices.rows(coins, emptySet(), "KUSDT").single() as SwapCoinChoices.Row.Coin).coin)
    }

    @Test
    fun `the symbol under a coin's name`() {
        assertEquals("kUSDT", SwapCoinChoices.symbol(SwapCoin.KUSDT))
        assertEquals("USDT", SwapCoinChoices.symbol(SwapCoin.USDT_TRX))
    }
}
