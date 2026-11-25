package com.kachat.app.ui.screens

import com.kachat.app.services.SavedAccountAvatars
import com.kachat.app.services.WalletManager
import com.kachat.app.util.KaspaNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Profile's account chip and the account switcher (iOS 8cb4c88e). */
class AccountSwitcherTest {
    private val mainnet = "kaspa:qqtk9u6alm8vz7tp5a425x9cacad7nezhpwmvz890yndpt9f07e0udw59l300"
    private val other = "kaspa:qrv9h4stvudjawleuxd8gxn766jrl9qqeretz34sjt6sgzacd882km8ngephg"

    @Test
    fun `chip shows the username, cut in the middle past 18 characters`() {
        assertEquals("alice.kachat", profileChipLabel(mainnet, "alice.kachat"))
        assertEquals("abcdefghijklmnopqr", profileChipLabel(mainnet, "abcdefghijklmnopqr"))
        assertEquals("abcdefghi…mnopqrs", profileChipLabel(mainnet, "abcdefghijklmnopqrs"))
    }

    @Test
    fun `chip without a username shows the short address`() {
        assertEquals("qqtk…l300", profileChipLabel(mainnet, null))
        assertEquals("short", profileChipLabel("kaspa:short", null))
        assertEquals("", profileChipLabel("", null))
    }

    @Test
    fun `the signed-in account is listed first, on either network`() {
        val a = WalletManager.Account(name = "A", address = other, mnemonic = "a b c")
        val b = WalletManager.Account(name = "B", address = mainnet, mnemonic = "d e f")
        val testnetOfB = KaspaNetwork.reencode(mainnet, "kaspatest")
        assertEquals(listOf("B", "A"), accountsCurrentFirst(listOf(a, b), testnetOfB).map { it.name })
        assertEquals(listOf("A", "B"), accountsCurrentFirst(listOf(a, b), null).map { it.name })
    }

    @Test
    fun `saved pictures are filed by public key, the same on both networks`() {
        val key = SavedAccountAvatars.publicKeyOf(mainnet)
        assertNotNull(key)
        assertEquals(key, SavedAccountAvatars.publicKeyOf(KaspaNetwork.reencode(mainnet, "kaspatest")))
        assertNull(SavedAccountAvatars.publicKeyOf("not an address"))
        val file = SavedAccountAvatars.fileKey(key!!)
        assertEquals(16, file.length)
        assertEquals(file, SavedAccountAvatars.fileKey(key.uppercase()))
    }
}
