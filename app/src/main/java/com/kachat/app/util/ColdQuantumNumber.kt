package com.kachat.app.util

/**
 * The unit's number for a KasSigner quantum (KCC-3) address as typed in Add Quantum Address / Edit
 * Address Number (iOS 961fc05a `ColdQuantumNumberFields.parse`): empty is none (the unit
 * searches), otherwise ASCII digits below 2^31, since higher indices are hardened.
 */
object ColdQuantumNumber {
    sealed class Parsed {
        /** A number the unit can have, or null for none. */
        data class Value(val number: Long?) : Parsed()
        /** Not a whole number ([tooLarge] false), or 2^31 or more ([tooLarge] true). */
        data class Invalid(val tooLarge: Boolean) : Parsed()
    }

    fun parse(text: String): Parsed {
        val digits = text.trim()
        if (digits.isEmpty()) return Parsed.Value(null)
        if (!digits.all { it in '0'..'9' }) return Parsed.Invalid(tooLarge = false)
        val value = digits.trimStart('0').ifEmpty { "0" }
        if (value.length > 10) return Parsed.Invalid(tooLarge = true)
        val number = value.toLong()
        if (number >= 0x8000_0000L) return Parsed.Invalid(tooLarge = true)
        return Parsed.Value(number)
    }
}
