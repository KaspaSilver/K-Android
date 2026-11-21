package com.kachat.app.ui.screens

import com.kachat.app.util.KaspaUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The address cards' grouped amounts (iOS 5b7c772c `KaspaUnit.groupedAmount` / `groupedExact`). */
class KaspaGroupedAmountTest {
    private lateinit var saved: Locale

    @Before
    fun setUp() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(saved)

    @Test
    fun groupedAmountDropsTrailingZeros() {
        assertEquals("1,234.5 ${KaspaUnit.symbol}", KaspaUnit.groupedAmount(123_450_000_000L))
        assertEquals("0 ${KaspaUnit.symbol}", KaspaUnit.groupedAmount(0L))
    }

    @Test
    fun groupedExactKeepsEightDecimals() {
        assertEquals("1,234.50000000", KaspaUnit.groupedExact(123_450_000_000L))
        assertEquals("0.00000001", KaspaUnit.groupedExact(1L))
        assertEquals("12,345,678.00000000", KaspaUnit.groupedExact(1_234_567_800_000_000L))
    }
}
