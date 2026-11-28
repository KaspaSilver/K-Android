package com.kachat.app.util

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Buy Kaspa with Debit Card's Ethereum pieces ([Keccak256], [EthereumHex], [EIP712]) against
 * published vectors, and a USDC permit shaped as Trestly's buy page asks for one: iOS bc0e6ba9
 * `scripts/test_ethereum_crypto.swift`, vector for vector.
 */
class EthereumCryptoTest {

    private fun keccak(bytes: ByteArray) = EthereumHex.string(Keccak256.hash(bytes))

    private fun assertHex(want: String, got: String) = assertEquals(want.lowercase(), got.lowercase())

    // Keccak-256 (not SHA3-256)

    @Test fun keccakEmpty() = assertHex("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", keccak(ByteArray(0)))

    @Test fun keccakAbc() = assertHex("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", keccak("abc".toByteArray()))

    /** One byte short of the rate, so the padding's 0x01 and 0x80 share a byte. */
    @Test fun keccak135() = assertHex("34367dc248bbd832f4e3e69dfaac2f92638bd0bbd18f2912ba4ef454919cf446", keccak(ByteArray(135) { 0x61 }))

    /** Exactly the rate (a whole padding block follows) and two blocks; iOS took these from
     *  `openssl dgst -keccak-256` (OpenSSL 3.6). */
    @Test fun keccak136() = assertHex("a6c4d403279fe3e0af03729caada8374b5ca54d8065329a3ebcaeb4b60aa386e", keccak(ByteArray(136) { 0x61 }))

    @Test fun keccak200() = assertHex("96ea54061def936c4be90b518992fdc6f12f535068a256229aca54267b4d084d", keccak(ByteArray(200) { 0x61 }))

    // EIP-55

    @Test fun eip55() {
        for (address in listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )) {
            assertEquals(address, EthereumHex.checksumAddress(EthereumHex.bytes(address)!!))
        }
    }

    @Test fun isAddress() {
        assertTrue(EthereumHex.isAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        assertTrue(!EthereumHex.isAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe"))
        assertTrue(!EthereumHex.isAddress("5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed00"))
        assertTrue(!EthereumHex.isAddress("0xZaAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    }

    // EIP-712's own example (eips.ethereum.org/EIPS/eip-712, "Mail")

    private val mail = JsonParser.parseString(
        """
        {"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],
        "Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],
        "Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},
        "primaryType":"Mail",
        "domain":{"name":"Ether Mail","version":"1","chainId":1,"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},
        "message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},"to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}
        """.trimIndent()
    ).asJsonObject

    @Test fun eip712Mail() {
        val types = EIP712.parseTypes(mail.get("types"))
        assertEquals("Mail(Person from,Person to,string contents)Person(string name,address wallet)", EIP712.encodeType("Mail", types))
        assertHex("f2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f",
            EthereumHex.string(EIP712.hashStruct("EIP712Domain", mail.getAsJsonObject("domain"), types)))
        assertHex("c52c0ee5d84264471806290a3f2c4cecfc5490626bf912d01f240d7a274b371e",
            EthereumHex.string(EIP712.hashStruct("Mail", mail.getAsJsonObject("message"), types)))
        assertHex("be609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", EthereumHex.string(EIP712.digest(mail)))
    }

    // numbers: decimal strings, hex and JSON numbers say the same

    @Test fun uint256Forms() {
        val a = EthereumHex.string(EIP712.uint256(JsonPrimitive("1000000")))
        assertEquals(a, EthereumHex.string(EIP712.uint256(JsonPrimitive("0xf4240"))))
        assertEquals(a, EthereumHex.string(EIP712.uint256(JsonPrimitive(1_000_000))))
        assertEquals("f".repeat(64), EthereumHex.string(EIP712.uint256(
            JsonPrimitive("115792089237316195423570985008687907853269984665640564039457584007913129639935"))))
    }

    @Test fun uint256OverflowRefused() {
        try {
            EIP712.uint256(JsonPrimitive("115792089237316195423570985008687907853269984665640564039457584007913129639936"))
            fail("2^256 was taken")
        } catch (e: EIP712.Failure) {}
    }

    @Test fun uint256RefusesFractionsNegativesAndBooleans() {
        for (bad in listOf(JsonPrimitive(1.5), JsonPrimitive(-1), JsonPrimitive(true), JsonPrimitive("-1"), JsonPrimitive("1e3"), JsonPrimitive(""))) {
            try {
                EIP712.uint256(bad)
                fail("$bad was taken")
            } catch (e: EIP712.Failure) {}
        }
    }

    @Test fun smallUInt() {
        assertEquals(50_000_000L, EIP712.smallUInt(JsonPrimitive("50000000")))
        assertEquals(255L, EIP712.smallUInt(JsonPrimitive("0xff")))
        assertEquals(1L, EIP712.smallUInt(JsonPrimitive(1)))
        assertNull(EIP712.smallUInt(JsonPrimitive(true)))
        assertNull(EIP712.smallUInt(JsonPrimitive(1.5)))
        assertNull(EIP712.smallUInt(JsonPrimitive(-3)))
        assertNull(EIP712.smallUInt(JsonPrimitive("abc")))
        assertNull(EIP712.smallUInt(JsonPrimitive("0x")))
        assertNull(EIP712.smallUInt(null))
    }

    /** `chainId: 1` is a number, never read as a bool (iOS's NSNumber trap). */
    @Test fun boolField() {
        val types = EIP712.parseTypes(JsonParser.parseString("""{"T":[{"name":"b","type":"bool"}]}"""))
        val yes = EIP712.encodeValue("bool", JsonPrimitive(true), types)
        assertEquals(1, yes[31].toInt())
        assertEquals(EthereumHex.string(yes), EthereumHex.string(EIP712.encodeValue("bool", JsonPrimitive("true"), types)))
        try {
            EIP712.encodeValue("bool", JsonPrimitive(1), types)
            fail("1 read as a bool")
        } catch (e: EIP712.Failure) {}
    }

    // a USDC permit as Trestly's buy page asks for one, sent as JSON text the way the page sends it

    @Test fun usdcPermit() {
        val permit = JsonParser.parseString(PERMIT_JSON).asJsonObject
        val types = EIP712.parseTypes(permit.get("types"))
        assertEquals("Permit(address owner,address spender,uint256 value,uint256 nonce,uint256 deadline)", EIP712.encodeType("Permit", types))
        // the Permit typehash every EIP-2612 token uses
        assertHex("6e71edae12b1b97f4d1f60370fef10105fa2faae0126114a169c64845d6126c9", EthereumHex.string(EIP712.typeHash("Permit", types)))
        // USDC's own DOMAIN_SEPARATOR on Ethereum mainnet
        assertHex("06c37168a7db5138defc7866392bb87a741f9b3d104deb5094588ce041cae335",
            EthereumHex.string(EIP712.hashStruct("EIP712Domain", permit.getAsJsonObject("domain"), types)))
        assertEquals(32, EIP712.digest(permit).size)
    }

    companion object {
        const val PERMIT_JSON = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit","domain":{"name":"USD Coin","version":"2","chainId":1,"verifyingContract":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"},"message":{"owner":"0x9858EfFD232B4033E47d90003D41EC34EcaEda94","spender":"0x9d9D5d596d9157b00317FCa7faCcb885DD3fAa60","value":"50000000","nonce":"0","deadline":"1800000000"}}"""
    }
}
