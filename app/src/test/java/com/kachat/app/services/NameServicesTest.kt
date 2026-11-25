package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NameServicesTest {
    @Test
    fun `only a kachat ending is split off`() {
        assertEquals("bob" to NameServiceTLD.KACHAT, NameServiceTLD.splitTypedName("bob.kachat"))
        assertEquals("Bob" to NameServiceTLD.KACHAT, NameServiceTLD.splitTypedName(" Bob.KACHAT ")) // case kept, as on iOS
        assertEquals("bob" to null, NameServiceTLD.splitTypedName("bob"))
        // the other services are gone: their endings are part of the label (iOS 518fdc8c)
        assertEquals("bob.kas" to null, NameServiceTLD.splitTypedName("bob.kas"))
    }

    @Test
    fun `a typed name resolves as kachat only`() {
        assertNull(NameServicesClient.primary(emptyList(), "bob"))
        assertNull(NameServicesClient.primary(listOf(NameResolution(NameServiceTLD.KACHAT, "bob.kachat", null, false)), "bob"))
        val found = NameResolution(NameServiceTLD.KACHAT, "bob.kachat", "kaspa:kachat", false)
        assertEquals(found, NameServicesClient.primary(listOf(found), "bob"))
        assertEquals(found, NameServicesClient.primary(listOf(found), "bob.kachat"))
        assertTrue(NameServicesClient.looksLikeName("bob"))
        assertTrue(NameServicesClient.looksLikeName("bob.kachat"))
        // .kas, .k and .kaspa are not names any more
        assertFalse(NameServicesClient.looksLikeName("bob.kas"))
        assertFalse(NameServicesClient.looksLikeName("bob.k"))
        assertFalse(NameServicesClient.looksLikeName("bob.kaspa"))
        assertFalse(NameServicesClient.looksLikeName("kaspa:qqq"))
    }

    @Test
    fun `full name always keeps the ending`() {
        assertEquals("bob.kachat", NameResolution(NameServiceTLD.KACHAT, "bob", null, false).fullName)
        assertEquals("bob.kachat", NameResolution(NameServiceTLD.KACHAT, "bob.kachat", null, false).fullName)
    }

    @Test
    fun `the registry's address answer is validated for the running network`() {
        // Unit tests never call KaspaNetwork.init, so the running network is mainnet.
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val mainnet = com.kachat.app.util.KaspaAddress.encode("kaspa", 0x00, key)
        val testnet = com.kachat.app.util.KaspaAddress.encode("kaspatest", 0x00, key)
        assertEquals(mainnet, NameResolution.validatedAddress("  ${mainnet.uppercase()} "))
        assertNull(NameResolution.validatedAddress(testnet))
        assertNull(NameResolution.validatedAddress(mainnet.dropLast(1)))
        assertNull(NameResolution.validatedAddress("kaspa:garbage"))
        assertNull(NameResolution.validatedAddress(""))
        assertNull(NameResolution.validatedAddress(null))
    }
}
