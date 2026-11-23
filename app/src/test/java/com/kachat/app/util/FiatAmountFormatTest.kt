package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** [formatFiatAmount]: grouped, fixed decimals, symbol first (iOS formatFiatAmount, 2026-11-22). */
class FiatAmountFormatTest {

    // "$" on an en-US machine; read rather than assumed, since the JVM's own locale names it.
    private val usd = currencySymbolFor("usd")

    @Test
    fun groupsThousandsWithTwoDecimals() {
        assertEquals("${usd}1,234.56", formatFiatAmount(1234.56, "usd", locale = Locale.US))
        assertEquals("${usd}1,234,567.00", formatFiatAmount(1_234_567.0, "usd", locale = Locale.US))
    }

    @Test
    fun smallAmountsKeepTheirDecimals() {
        assertEquals("${usd}0.50", formatFiatAmount(0.5, "usd", locale = Locale.US))
        assertEquals("${usd}0.00", formatFiatAmount(0.0, "usd", locale = Locale.US))
        assertEquals("${usd}999.00", formatFiatAmount(999.0, "usd", locale = Locale.US))
    }

    @Test
    fun keepsTheDecimalsAskedFor() {
        // The Wallet tab's balance uses 4.
        assertEquals("${usd}1,234.5678", formatFiatAmount(1234.5678, "usd", decimals = 4, locale = Locale.US))
        assertEquals("₿0.00012345", formatFiatAmount(0.00012345, "btc", decimals = 8, locale = Locale.US))
    }

    @Test
    fun negativeSignGoesBeforeTheSymbol() {
        assertEquals("-${usd}1,234.50", formatFiatAmount(-1234.5, "usd", locale = Locale.US))
    }

    @Test
    fun roundingCarriesIntoTheGroups() {
        assertEquals("${usd}1,000.00", formatFiatAmount(999.999, "usd", locale = Locale.US))
    }

    @Test
    fun followsTheLocalesMarks() {
        assertEquals("${usd}1.234,56", formatFiatAmount(1234.56, "usd", locale = Locale.GERMANY))
    }

    @Test
    fun defaultLocaleNeverThrowsOffDevice() {
        // AppCompat's app-language lookup isn't there in a plain JVM; it falls back to the default.
        formatFiatAmount(1234.56, "usd")
    }
}
