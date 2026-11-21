package com.kachat.app.services.tokens

import com.google.gson.JsonParser
import com.kachat.app.util.KaspaAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * kUSDT's pure pieces (iOS 5b7c772c `Kusdt.swift`, `ChatService.tokenCoinOwner`): the shipped
 * files match their pins, a coin's state encodes to the 112 bytes the template expects, and a
 * token input's owner is read out of its signature script (audit #1).
 */
class KusdtCoreLogicTest {

    private fun sha256(bytes: ByteArray): String = Kusdt.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val owner = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `the shipped builder files are the pinned ones`() {
        for ((name, pin) in KusdtCore.PINNED) {
            val file = File("src/main/assets/kusdt/$name")
            assertTrue("$name is shipped", file.isFile)
            assertEquals("$name matches its pin", pin, sha256(file.readBytes()))
        }
    }

    @Test
    fun `the shipped template is the pinned one`() {
        val json = JsonParser.parseString(File("src/main/assets/${Kusdt.TEMPLATE_ASSET}").readText()).asJsonObject
        val prefix = Kusdt.unhexOrNull(json.get("prefix").asString)!!
        val suffix = Kusdt.unhexOrNull(json.get("suffix").asString)!!
        val hash = Kusdt.unhexOrNull(json.get("templateHash").asString)!!
        assertEquals(Kusdt.TEMPLATE_SHA256, sha256(prefix + suffix))
        assertEquals(34, prefix.size)
        assertArrayEquals(hash, prefix.copyOfRange(2, 34))
    }

    @Test
    fun `a state encodes to 112 bytes of explicit pushes`() {
        val state = Kusdt.State.plain(owner, 12_500_000L)
        val e = state.encoded
        assertEquals(112, e.size)
        assertEquals(0x08, e[0].toInt())
        // amount, little-endian
        val amount = (0 until 8).fold(0L) { acc, i -> acc or ((e[1 + i].toLong() and 0xff) shl (8 * i)) }
        assertEquals(12_500_000L, amount)
        assertEquals(0x20, e[9].toInt())
        assertArrayEquals(owner, e.copyOfRange(10, 42))
        assertEquals(0x01, e[42].toInt()); assertEquals(Kusdt.OWNER_P2PK_SCHNORR, e[43].toInt())
        assertEquals(0x01, e[44].toInt()); assertEquals(0, e[45].toInt())
        assertEquals(0x20, e[46].toInt()); assertEquals(0x20, e[79].toInt())
    }

    @Test
    fun `only a plain coin of the key is plain`() {
        assertTrue(Kusdt.State.plain(owner, 1).isPlainOf(owner))
        assertFalse(Kusdt.State.plain(owner, 1).isPlainOf(ByteArray(32)))
        assertFalse(Kusdt.State(1, owner, Kusdt.OWNER_P2SH).isPlainOf(owner))
        assertFalse(Kusdt.State(1, owner, Kusdt.OWNER_P2PK_SCHNORR, borrowScheme = 1).isPlainOf(owner))
        assertFalse(Kusdt.State(1, owner, Kusdt.OWNER_P2PK_SCHNORR, extensionCommitment = ByteArray(32) { 1 }).isPlainOf(owner))
    }

    @Test
    fun `the locking script is a P2SH script`() {
        val template = Kusdt.Template(ByteArray(32), ByteArray(34), ByteArray(31))
        val script = Kusdt.lockingScript(Kusdt.State.plain(owner, 5), template)
        assertEquals(35, script.size)
        assertEquals(0xaa, script[0].toInt() and 0xff)
        assertEquals(0x20, script[1].toInt())
        assertEquals(0x87, script[34].toInt() and 0xff)
    }

    @Test
    fun `a token input's owner is read from its redeem script`() {
        val prefixHex = "6b" + "ab".repeat(33)
        val state = Kusdt.State.plain(owner, 42)
        val sigScript = "41" + "cd".repeat(65) + "4d7001" + prefixHex + Kusdt.hex(state.encoded) + "6c76"
        assertArrayEquals(owner, Kusdt.tokenCoinOwner(sigScript, prefixHex))
        // not a token coin
        assertNull(Kusdt.tokenCoinOwner("41" + "cd".repeat(65), prefixHex))
        // the prefix only at an odd (not byte-aligned) offset
        assertNull(Kusdt.tokenCoinOwner("4" + prefixHex + Kusdt.hex(state.encoded), prefixHex))
        // a state cut short
        assertNull(Kusdt.tokenCoinOwner(prefixHex + Kusdt.hex(state.encoded).take(100), prefixHex))
    }

    @Test
    fun `hex is strict`() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte()), Kusdt.unhexOrNull("0aFF"))
        assertNull(Kusdt.unhexOrNull("abc"))
        assertNull(Kusdt.unhexOrNull("0g"))
        // another script's digits are no hex digits
        assertNull(Kusdt.unhexOrNull("٣٣"))
        assertEquals("0aff", Kusdt.hex(byteArrayOf(0x0a, 0xff.toByte())))
    }

    @Test
    fun `only a Schnorr key address on this network has a token owner`() {
        val key = ByteArray(32) { 7 }
        assertArrayEquals(key, Kusdt.schnorrOwner(KaspaAddress.encode("kaspa", 0x00, key)))
        assertNull(Kusdt.schnorrOwner(KaspaAddress.encode("kaspa", 0x08, key)))
        assertNull(Kusdt.schnorrOwner(KaspaAddress.encode("kaspatest", 0x00, key)))
        assertNull(Kusdt.schnorrOwner("kaspa:nonsense"))
    }

    @Test
    fun `the deployments carry kUSDT's covenant ids`() {
        assertEquals("50b3477e7368d0c5bfbd64483b4607350ec411377972f33e40b2d4b6d1513fd3",
            Kusdt.deployment(com.kachat.app.util.KaspaNetwork.Type.MAINNET).covenantIdHex)
        assertEquals(150_000_000L, Kusdt.deployment(com.kachat.app.util.KaspaNetwork.Type.TESTNET).tokenValue)
        assertNotNull(Kusdt.current)
    }

    @Test
    fun `Trestly numbers are natural numbers only`() {
        assertEquals(12L, KusdtBalances.natural("12"))
        assertEquals(12L, KusdtBalances.natural(12))
        assertEquals(12L, KusdtBalances.natural(12L))
        assertNull(KusdtBalances.natural("-1"))
        assertNull(KusdtBalances.natural(-1))
        assertNull(KusdtBalances.natural(""))
        assertNull(KusdtBalances.natural("1.5"))
        assertNull(KusdtBalances.natural(1.5))
        assertNull(KusdtBalances.natural(true))
        assertNull(KusdtBalances.natural("99999999999999999999"))
        assertEquals(Long.MAX_VALUE, KusdtBalances.natural(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `the tokens lookup is https only`() {
        val url = KusdtBalances.tokensUrl("https://api.trestly.io/api/v1/", "kaspa:qq")!!
        assertEquals("/api/v1/tokens", url.encodedPath)
        assertEquals("kaspa:qq", url.queryParameter("owner"))
        assertEquals("api.trestly.io", url.host)
        assertNull(KusdtBalances.tokensUrl("http://api.trestly.io/api/v1", "kaspa:qq"))
        assertNull(KusdtBalances.tokensUrl("", "kaspa:qq"))
    }

    @Test
    fun `a Not confirmed send's coins are remembered for a hold's time`() {
        var now = 1_000L
        val store = UnknownOutcomeOutpoints(ttlMs = 600_000L, now = { now })
        store.mark(listOf(com.kachat.app.services.Outpoint("AB", 1)))
        assertEquals(setOf("ab:1"), store.keys())
        now += 600_001L
        assertTrue(store.keys().isEmpty())
    }
}
