package com.kachat.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The converters' grouping as you type, in comma- and dot-decimal languages and other digits. */
class DecimalInputFormatTest {

    private lateinit var saved: Locale

    @Before
    fun save() {
        saved = Locale.getDefault()
    }

    @After
    fun restore() {
        Locale.setDefault(saved)
    }

    private fun value(text: String) = DecimalInputFormat.value(text)

    @Test
    fun `english groups whole digits and keeps the fraction as typed`() {
        Locale.setDefault(Locale.US)
        assertEquals("1,200,000", DecimalInputFormat.grouped("1200000"))
        assertEquals("1,234.5", DecimalInputFormat.grouped("1,234.5"))
        assertEquals("1.", DecimalInputFormat.grouped("1."))
        assertEquals("1.50", DecimalInputFormat.grouped("1.50"))
        assertEquals("", DecimalInputFormat.grouped(""))
        assertEquals(1_234.5, value("1,234.5")!!, 0.0)
        assertNull(value(""))
    }

    @Test
    fun `english regroups after a digit is typed or deleted`() {
        Locale.setDefault(Locale.US)
        assertEquals("12,345", DecimalInputFormat.grouped("1,2345"))
        assertEquals("123", DecimalInputFormat.grouped("1,23"))
        assertEquals("1,245", DecimalInputFormat.grouped("12,45"))
    }

    @Test
    fun `german reads a typed dot as the decimal point, not grouping`() {
        Locale.setDefault(Locale.GERMANY)
        // the bug: "1.5" became 15
        assertEquals("1,", DecimalInputFormat.grouped("1."))
        assertEquals("1,5", DecimalInputFormat.grouped("1,5"))
        assertEquals(1.5, value("1.5")!!, 0.0)
        assertEquals(1.5, value("1,5")!!, 0.0)
        // a dot typed after a grouped number
        assertEquals("1.234,", DecimalInputFormat.grouped("1.234."))
    }

    @Test
    fun `german still groups with dots as digits are typed and deleted`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.234", DecimalInputFormat.grouped("1234"))
        assertEquals("12.345", DecimalInputFormat.grouped("1.2345"))
        assertEquals("1.234", DecimalInputFormat.grouped("12.34"))
        assertEquals("1.234.567,25", DecimalInputFormat.grouped("1.234.567,25"))
        assertEquals(1_234_567.25, value("1.234.567,25")!!, 0.0)
        assertEquals(1_234.0, value("1.234")!!, 0.0)
    }

    @Test
    fun `french takes either separator as the decimal point`() {
        Locale.setDefault(Locale.FRANCE)
        val g = DecimalFormatSymbols.getInstance(Locale.FRANCE).groupingSeparator
        assertEquals("1${g}234,5", DecimalInputFormat.grouped("1234.5"))
        assertEquals("1${g}234,5", DecimalInputFormat.grouped("1234,5"))
        assertEquals(1.5, value("1.5")!!, 0.0)
        assertEquals(1_234.5, value("1${g}234,5")!!, 0.0)
    }

    @Test
    fun `arabic-indic and persian digits are read and shown as ascii digits`() {
        Locale.setDefault(Locale.US)
        assertEquals("1,234.5", DecimalInputFormat.grouped("١٢٣٤٫٥"))
        assertEquals(1_234.5, value("١٢٣٤٫٥")!!, 0.0)
        assertEquals(12.0, value("۱۲")!!, 0.0)
        assertEquals(1_234.0, value("١٬٢٣٤")!!, 0.0)
    }

    @Test
    fun `canonical text is grouped in the locale's own form`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.200.000,00", DecimalInputFormat.groupedFromCanonical("1200000.00"))
        assertEquals("1.234,567", DecimalInputFormat.groupedFromCanonical("1234.567"))
        Locale.setDefault(Locale.US)
        assertEquals("1,200,000.00", DecimalInputFormat.groupedFromCanonical("1200000.00"))
        assertEquals(1_200_000.0, value(DecimalInputFormat.groupedFromCanonical("1200000.00"))!!, 0.0)
    }
}
