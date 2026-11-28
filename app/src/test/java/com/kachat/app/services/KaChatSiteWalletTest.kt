package com.kachat.app.services

import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The pure parts of K's website wallet (iOS 48f5c357 `KaChatSiteWallet`). */
class KaChatSiteWalletTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A fixed test key (never a real wallet's). */
    private val key = unhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")

    private val mainnetAddress = KaspaAddress.encode("kaspa", 0x00, ByteArray(32) { (it + 1).toByte() })
    private val testnetAddress = KaspaAddress.encode("kaspatest", 0x00, ByteArray(32) { (it + 1).toByte() })

    // Hosts

    @Test
    fun `kasshi and its subdomains are allowed, nothing else`() {
        assertTrue(KaChatSiteWallet.isAllowed("kasshi.io"))
        assertTrue(KaChatSiteWallet.isAllowed("www.kasshi.io"))
        assertTrue(KaChatSiteWallet.isAllowed("app.beta.kasshi.io"))
        assertTrue(KaChatSiteWallet.isAllowed("KASSHI.IO"))
        assertFalse(KaChatSiteWallet.isAllowed("evilkasshi.io"))
        assertFalse(KaChatSiteWallet.isAllowed("kasshi.io.evil.com"))
        assertFalse(KaChatSiteWallet.isAllowed("kasshi.iox"))
        assertFalse(KaChatSiteWallet.isAllowed("io"))
        assertFalse(KaChatSiteWallet.isAllowed(""))
        assertFalse(KaChatSiteWallet.isAllowed(null))
    }

    @Test
    fun `only the main frame of an https kasshi page reaches the bridge`() {
        assertTrue(KaChatSiteWallet.isAllowedOrigin("https", "kasshi.io", -1, isMainFrame = true))
        assertTrue(KaChatSiteWallet.isAllowedOrigin("https", "www.kasshi.io", 443, isMainFrame = true))
        assertFalse(KaChatSiteWallet.isAllowedOrigin("https", "kasshi.io", -1, isMainFrame = false))
        assertFalse(KaChatSiteWallet.isAllowedOrigin("http", "kasshi.io", -1, isMainFrame = true))
        assertFalse(KaChatSiteWallet.isAllowedOrigin("https", "kasshi.io", 8443, isMainFrame = true))
        assertFalse(KaChatSiteWallet.isAllowedOrigin("https", "example.com", -1, isMainFrame = true))
        assertFalse(KaChatSiteWallet.isAllowedOrigin(null, null, -1, isMainFrame = true))
    }

    @Test
    fun `the webview origin rules are the host and its subdomains over https`() {
        assertEquals(setOf("https://kasshi.io", "https://*.kasshi.io"), KaChatSiteWallet.ORIGIN_RULES)
    }

    // The remembered pick

    @Test
    fun `one site per host and one key per account`() {
        assertEquals("kasshi.io", KaChatSiteWallet.siteKey("www.kasshi.io"))
        assertEquals("kasshi.io", KaChatSiteWallet.siteKey("kasshi.io"))
        assertEquals("site_connections_kaspa:qabc", KaChatSiteWallet.pickKey("kaspa:QABC"))
    }

    @Test
    fun `a remembered pick is suggested only for a revealed regular address`() {
        assertNull(KaChatSiteWallet.rememberedIndex(null, legacyMax = 5))
        // -1 is the Chatting Address
        assertNull(KaChatSiteWallet.rememberedIndex(-1, legacyMax = 5))
        assertNull(KaChatSiteWallet.rememberedIndex(WalletManager.QUANTUM_INDEX_BASE + 2, legacyMax = 5))
        assertNull(KaChatSiteWallet.rememberedIndex(6, legacyMax = 5))
        assertEquals(0, KaChatSiteWallet.rememberedIndex(0, legacyMax = 5))
        assertEquals(5, KaChatSiteWallet.rememberedIndex(5, legacyMax = 5))
    }

    // sendKaspa's params

    private fun params(vararg items: Any?): JsonArray = JsonArray().apply {
        items.forEach {
            when (it) {
                null -> add(com.google.gson.JsonNull.INSTANCE)
                is Number -> add(JsonPrimitive(it))
                else -> add(JsonPrimitive(it.toString()))
            }
        }
    }

    private fun assertInvalid(params: JsonArray) {
        try {
            KaChatSiteWallet.parseSendParams(params, hrp = "kaspa")
            fail("expected -32602")
        } catch (e: KaChatSiteWallet.SiteError) {
            assertEquals(KaChatSiteWallet.Code.INVALID, e.code)
        }
    }

    @Test
    fun `a send takes a running-network address and whole sompi above zero`() {
        assertEquals(mainnetAddress to 100_000_000L, KaChatSiteWallet.parseSendParams(params(mainnetAddress, "100000000"), hrp = "kaspa"))
        // trimmed and lowercased, as on iOS
        assertEquals(mainnetAddress to 1L, KaChatSiteWallet.parseSendParams(params("  ${mainnetAddress.uppercase()} ", "1"), hrp = "kaspa"))
        // a page posting straight to the bridge may send a number
        assertEquals(mainnetAddress to 42L, KaChatSiteWallet.parseSendParams(params(mainnetAddress, 42), hrp = "kaspa"))
    }

    @Test
    fun `a send with a bad address or amount is refused`() {
        assertInvalid(params())
        assertInvalid(params(testnetAddress, "100"))
        assertInvalid(params("kaspa:notanaddress", "100"))
        assertInvalid(params(mainnetAddress))
        assertInvalid(params(mainnetAddress, "0"))
        assertInvalid(params(mainnetAddress, "-1"))
        assertInvalid(params(mainnetAddress, "1.5"))
        assertInvalid(params(mainnetAddress, "1e8"))
        assertInvalid(params(mainnetAddress, " 100"))
        assertInvalid(params(mainnetAddress, "12345678901234567890"))
        assertInvalid(params(mainnetAddress, null))
        assertInvalid(params(123, "100"))
        assertEquals(testnetAddress to 5L, KaChatSiteWallet.parseSendParams(params(testnetAddress, "5"), hrp = "kaspatest"))
    }

    // Signing

    @Test
    fun `the personal message hash is rusty-kaspa's`() {
        // rusty-kaspa's "Hello Kaspa!" test vector (KasSigner crates/kassigner-kaspa sighash.rs)
        assertEquals(
            "2e55deda4a5224208dbd4d93cfe5aa22d945eaa63172e329c49afed62f0e1510",
            hex(KaspaMessageSigner.personalMessageHash("Hello Kaspa!".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun `a site signature verifies against the connected address's key`() {
        val text = "Sign in to kasshi.io\nNonce: 8f3a"
        val signature = unhex(KaChatSiteWallet.personalMessageSignature(text, key))
        assertEquals(64, signature.size)
        val xOnly = Schnorr.publicKeyXOnly(key)
        val digest = KaspaMessageSigner.personalMessageHash(text.toByteArray(Charsets.UTF_8))
        assertTrue(Schnorr.verify(digest, signature, xOnly))
        // not over the raw text, and not for another message
        assertFalse(Schnorr.verify(text.toByteArray(Charsets.UTF_8), signature, xOnly))
        assertFalse(Schnorr.verify(KaspaMessageSigner.personalMessageHash("other".toByteArray()), signature, xOnly))
    }

    @Test
    fun `the address and public key are the key's own`() {
        val xOnly = Schnorr.publicKeyXOnly(key)
        assertEquals(KaspaAddress.encode("kaspa", 0x00, xOnly), KaChatSiteWallet.addressOfKey(key, hrp = "kaspa"))
        val compressed = KaChatSiteWallet.compressedPublicKeyHex(key)
        assertEquals(66, compressed.length)
        assertTrue(compressed.startsWith("02") || compressed.startsWith("03"))
        assertEquals(hex(xOnly), compressed.substring(2))
        // BIP-340 test vector 1's key: its x-only public key is known
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", hex(xOnly))
    }

    // Sign in first (iOS b49a021d: "Sign In to Kasshi", then the sign-in signed with no sheet)

    @Test
    fun `the sign-in approval lasts two minutes on the site it was given`() {
        val approval = KaChatSiteWallet.signInApproval("WWW.Kasshi.io", nowMs = 1_000L)
        assertEquals("kasshi.io", approval.host)
        assertEquals(1_000L + 120_000L, approval.untilMs)
        assertEquals(120_000L, KaChatSiteWallet.SIGN_IN_WINDOW_MS)
    }

    @Test
    fun `the first sign-in within two minutes on the same site needs no sheet`() {
        with(KaChatSiteWallet) {
            val approval = signInApproval("kasshi.io", nowMs = 0L)
            assertTrue(approval.coversSignIn("kasshi.io", nowMs = 0L, idle = true))
            assertTrue(approval.coversSignIn("www.kasshi.io", nowMs = 119_999L, idle = true))
            // a subdomain never signs in without the sheet (iOS d88891ea, audit 2026-11-27 M2)
            assertFalse(approval.coversSignIn("app.kasshi.io", nowMs = 60_000L, idle = true))
        }
    }

    @Test
    fun `a late, foreign, busy or unapproved sign-in still asks`() {
        with(KaChatSiteWallet) {
            val approval = signInApproval("kasshi.io", nowMs = 0L)
            // two minutes are up
            assertFalse(approval.coversSignIn("kasshi.io", nowMs = 120_000L, idle = true))
            // another site
            assertFalse(approval.coversSignIn("example.com", nowMs = 1L, idle = true))
            assertFalse(approval.coversSignIn("evilkasshi.io", nowMs = 1L, idle = true))
            // something else is waiting on the person
            assertFalse(approval.coversSignIn("kasshi.io", nowMs = 1L, idle = false))
            // never approved (or already used, or disconnected)
            val none: KaChatSiteWallet.SignInApproval? = null
            assertFalse(none.coversSignIn("kasshi.io", nowMs = 1L, idle = true))
        }
    }

    @Test
    fun `the page script tells K once its provider is up`() {
        assertTrue(KaChatSiteWallet.script.contains("method: '${KaChatSiteWallet.READY_METHOD}'"))
    }
}
