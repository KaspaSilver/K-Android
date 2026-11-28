package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address cards' pure rules (iOS 824a30bb `String.wrappingAnywhere`, `quantumState(for:)`,
 *  `kasAmount`). */
class AddressCardRulesTest {
    private val zwsp = '​'

    @Test
    fun wrappingAnywhereSeparatesEveryCharacter() {
        assertEquals("k${zwsp}a${zwsp}s", "kas".wrappingAnywhere())
    }

    @Test
    fun wrappingAnywhereRemovedGivesTheAddressBack() {
        val address = "kaspa:qypq0jusm4l2xyz9h8w5r3p6v7c2n0d4f8g7h6j5k4l3m2n1p0q9r8s7t6"
        val wrapped = address.wrappingAnywhere()
        assertEquals(address, wrapped.replace(zwsp.toString(), ""))
        assertEquals(address.length * 2 - 1, wrapped.length)
        assertFalse(wrapped.startsWith(zwsp.toString()))
        assertFalse(wrapped.endsWith(zwsp.toString()))
        assertFalse(wrapped.contains('-'))
    }

    @Test
    fun wrappingAnywhereLeavesShortStringsAlone() {
        assertEquals("", "".wrappingAnywhere())
        assertEquals("k", "k".wrappingAnywhere())
    }

    @Test
    fun wrappingAnywhereNeverSplitsASurrogatePair() {
        val wrapped = "a😀b".wrappingAnywhere()
        assertEquals("a$zwsp😀${zwsp}b", wrapped)
    }

    @Test
    fun quantumProtectionIsOnlyForAnUnspentQuantumAddress() {
        assertEquals(QuantumProtection.NOT_PROTECTED, QuantumProtection.of(isQuantum = false, spent = false, balanceSompi = 0))
        assertEquals(QuantumProtection.NOT_PROTECTED, QuantumProtection.of(isQuantum = false, spent = true, balanceSompi = 500))
        assertEquals(QuantumProtection.PROTECTED, QuantumProtection.of(isQuantum = true, spent = false, balanceSompi = 500))
        assertEquals(QuantumProtection.PROTECTED, QuantumProtection.of(isQuantum = true, spent = false, balanceSompi = 0))
    }

    @Test
    fun aSpentQuantumAddressIsNotProtectedAndOrangeWhileFunded() {
        assertEquals(QuantumProtection.REVEALED_WITH_FUNDS, QuantumProtection.of(isQuantum = true, spent = true, balanceSompi = 1))
        assertEquals(QuantumProtection.NOT_PROTECTED, QuantumProtection.of(isQuantum = true, spent = true, balanceSompi = 0))
    }

    @Test
    fun managedKasAmountShowsADashUntilKnown() {
        assertTrue(managedKasAmount(null).startsWith("-- "))
    }
}
