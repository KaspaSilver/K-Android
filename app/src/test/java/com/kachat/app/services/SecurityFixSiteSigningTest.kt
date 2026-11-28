package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security audit 2026-11-27 S2: a site never gets K to sign KaPost's block list or a K profile
 * (the indexer's `"<block|unblock>:<pubkey>"` and `"<b64 nick>:<b64 image or empty>:<b64 bio>"`),
 * while Kasshi's sign-in and ordinary messages still sign. S1: the in-app browser's profiles.
 */
class SecurityFixSiteSigningTest {

    private val pubkey = "02" + "ab".repeat(32)

    @Test
    fun `block and unblock are refused in any case and after spaces`() {
        for (text in listOf("block:$pubkey", "unblock:$pubkey", "BLOCK:$pubkey", "  Unblock:$pubkey\n")) {
            assertTrue(text, KaChatSiteWallet.looksLikeKaPostAction(text))
        }
    }

    @Test
    fun `the profile broadcast shape is refused`() {
        for (text in listOf(
            "SGFja2Vk::SGk=", // the audit's example: nickname, no image, bio
            "SGFja2Vk:aW1hZ2U=:SGk=",
            "::", // an empty profile verifies too
            "SGk=::",
            " SGFja2Vk::SGk= ",
        )) {
            assertTrue(text, KaChatSiteWallet.looksLikeKaPostAction(text))
        }
    }

    @Test
    fun `three fields that aren't strict base64 still sign`() {
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("a:b:c"))
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("10:30:00"))
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("Order 1: two: three"))
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("SGk===::"))
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("SGk=::SGk=:SGk="))
        assertFalse(KaChatSiteWallet.looksLikeProfileBroadcast("SGk=:SGk="))
    }

    @Test
    fun `Kasshi's sign-in challenge and ordinary messages are still allowed`() {
        val address = "kaspa:qabc"
        val now = 1_800_000_000_000L
        val challenge = "Sign this message to authenticate with KasShi.\n\nAddress: $address\nTimestamp: $now\nNonce: 9f2c4a7e1b3d5f60"
        assertFalse(KaChatSiteWallet.looksLikeKaPostAction(challenge))
        assertTrue(KaChatSiteWallet.isKasshiSignInChallenge(challenge, address, now))
        for (text in listOf(
            "Hello from Kasshi", "hello:world", "Order 42: two items", "blocked: nothing",
            "Meet at 10:30:00", "a:b:c", "Unblocking a friend",
        )) {
            assertFalse(text, KaChatSiteWallet.looksLikeKaPostAction(text))
        }
    }

    @Test
    fun `the browser's profiles are its own and only its leftovers are swept`() {
        val name = InAppBrowserData.profileName(1234, 0)
        assertTrue(name.startsWith(InAppBrowserData.PROFILE_PREFIX))
        assertFalse(name == InAppBrowserData.profileName(1234, 1))
        val all = listOf("Default", "kachat-trestly", name, InAppBrowserData.profileName(99, 3))
        assertEquals(listOf(InAppBrowserData.profileName(99, 3)), InAppBrowserData.staleProfiles(all, listOf(name)))
        assertEquals(listOf(name, InAppBrowserData.profileName(99, 3)), InAppBrowserData.staleProfiles(all, emptyList()))
    }
}
