package com.kachat.app.util

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Thousands grouping for a number the user is typing. Mirrors iOS's `DecimalInputFormat`.
 *
 * A decimal keypad gives you digits and nothing else, so a six-figure hashrate arrives as
 * "1200000" and has to be counted by eye. [grouped] regroups after every keystroke; [value] hands
 * the number back for whatever needs to compute with it.
 *
 * What the person types is read the way they meant it, whatever the phone's language: Arabic-Indic
 * and Persian digits are digits, and a "." or "," typed at the end is the decimal point even where
 * it is also the grouping separator (German's "1.5" is 1,5, not 15). The text shown always uses
 * ASCII digits and the language's own separators.
 */
object DecimalInputFormat {
    private val symbols: DecimalFormatSymbols get() = DecimalFormatSymbols.getInstance(Locale.getDefault())

    /** The number in [text]: its whole digits, and its fraction's digits (null with no decimal
     *  point). ASCII digits only. */
    private data class Parts(val whole: String, val fraction: String?)

    private fun isSpaceLike(c: Char) = c == ' ' || c == ' ' || c == ' '

    /** Arabic-Indic and Persian digits as ASCII digits. */
    private fun asciiDigit(c: Char): Char = when (c) {
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        else -> c
    }

    private fun parts(text: String): Parts {
        val grouping = symbols.groupingSeparator
        val decimal = symbols.decimalSeparator
        fun isGrouping(c: Char) = c != decimal &&
            (c == grouping || c == '٬' || (isSpaceLike(grouping) && isSpaceLike(c)))
        // The locale's own decimal, the Arabic one, and whichever of "." and "," the locale does
        // not group with: K never writes those, so the person typed them as a decimal point.
        fun isDecimal(c: Char) = !isGrouping(c) &&
            (c == decimal || c == '٫' || c == '.' || c == ',')

        val chars = text.map(::asciiDigit)
        var point = chars.indexOfFirst(::isDecimal)
        if (point < 0) {
            // Only grouping separators: K puts those in groups of three, so one with no digit or a
            // single digit after it (the last thing typed, "1.") is the person's decimal point;
            // two or four digits after one is a digit just deleted or typed.
            val last = chars.indexOfLast(::isGrouping)
            if (last >= 0 && chars.subList(last + 1, chars.size).count { it in '0'..'9' } <= 1) {
                point = last
            }
        }
        val wholeChars = if (point >= 0) chars.subList(0, point) else chars
        val whole = wholeChars.filter { it in '0'..'9' }.joinToString("")
        val fraction = if (point >= 0) {
            chars.subList(point + 1, chars.size).filter { it in '0'..'9' }.joinToString("")
        } else {
            null
        }
        return Parts(whole, fraction)
    }

    private fun format(parts: Parts): String {
        val grouping = symbols.groupingSeparator
        val decimal = symbols.decimalSeparator
        val rest = parts.fraction?.let { decimal + it } ?: ""
        if (parts.whole.isEmpty()) return rest
        val withSeparators = parts.whole
            .reversed()
            .chunked(3)
            .joinToString(grouping.toString())
            .reversed()
        return withSeparators + rest
    }

    /**
     * [text] with grouping separators put back where they belong.
     *
     * The fractional part is left as typed (digits only) - grouping it means nothing, and
     * reformatting it would fight the user over trailing zeros and over a lone separator
     * mid-entry ("1." has to survive long enough to become "1.5").
     */
    fun grouped(text: String): String = format(parts(text))

    /**
     * Groups a string that uses "." as its decimal point - String.format output, say - into the
     * locale's own form.
     *
     * Not the same as calling [grouped] on it: in a locale like German "." IS the grouping
     * separator, so "1200000.000" typed would be read as grouped.
     */
    fun groupedFromCanonical(text: String): String {
        val ascii = text.map(::asciiDigit).joinToString("")
        val point = ascii.indexOf('.')
        val whole = (if (point >= 0) ascii.substring(0, point) else ascii).filter { it in '0'..'9' }
        val fraction = if (point >= 0) ascii.substring(point + 1).filter { it in '0'..'9' } else null
        return format(Parts(whole, fraction))
    }

    /** The number behind grouped text, or null when there isn't one yet. */
    fun value(text: String): Double? {
        val parts = parts(text)
        if (parts.whole.isEmpty() && parts.fraction.isNullOrEmpty()) return null
        val canonical = parts.whole.ifEmpty { "0" } + (parts.fraction?.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
        return canonical.toDoubleOrNull()
    }
}
