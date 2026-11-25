package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** A chat's Tip with the instant (default) tip (iOS 2026-11-23 `tipFromChat`). */
class ChatTipDecisionTest {
    private val min = 20_000_000L
    private val limit = 1_000_000_000L
    private val convert: (Double, Double) -> Long? = { fiat, price -> (fiat / price * 100_000_000).toLong().takeIf { it in 1..limit * 10 } }

    private fun decide(sompi: Long? = null, fiat: Double? = null, price: Double? = null) =
        ChatTip.decide(sompi, fiat, fiat?.let { "$$it" }, price, min, limit, convert)

    @Test
    fun noDefaultAsksTheAmount() {
        assertEquals(ChatTip.Instant.None, decide())
        assertEquals(ChatTip.Instant.None, decide(sompi = 0))
    }

    @Test
    fun aKaspaDefaultWaitsInTheComposer() {
        assertEquals(ChatTip.Instant.Send(100_000_000L, null), decide(sompi = 100_000_000L))
        assertEquals(ChatTip.Instant.Send(limit, null), decide(sompi = limit))
    }

    @Test
    fun theLimitsHold() {
        assertEquals(ChatTip.Instant.OverLimit, decide(sompi = limit + 1))
        assertEquals(ChatTip.Instant.TooSmall, decide(sompi = min - 1))
    }

    @Test
    fun aCurrencyDefaultNeedsARecentPrice() {
        assertEquals(ChatTip.Instant.NoPrice("$1.0"), decide(fiat = 1.0, price = null))
        assertEquals(ChatTip.Instant.Send(1_000_000_000L, "$1.0"), decide(fiat = 1.0, price = 0.1))
        assertEquals(ChatTip.Instant.OverLimit, decide(fiat = 2.0, price = 0.1))
    }

    @Test
    fun amountTextNamesTheCurrencyAmount() {
        assertEquals("1.5 KAS", ChatTip.amountText(150_000_000L, null).replace(com.kachat.app.util.KaspaUnit.symbol, "KAS"))
        assertEquals("1.5 KAS ($0.20)", ChatTip.amountText(150_000_000L, "$0.20").replace(com.kachat.app.util.KaspaUnit.symbol, "KAS"))
    }
}
