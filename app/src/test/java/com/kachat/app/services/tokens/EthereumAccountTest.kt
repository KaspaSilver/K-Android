package com.kachat.app.services.tokens

import com.google.gson.JsonParser
import com.kachat.app.services.WalletManager
import com.kachat.app.util.EIP712
import com.kachat.app.util.EthereumHex
import com.kachat.app.util.Keccak256
import com.kachat.app.util.Secp256k1
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Buy Kaspa with Debit Card's Ethereum key (iOS bc0e6ba9 `EthereumAccount`): m/44'/60'/0'/0/0 of
 * the account's words is the address any Ethereum wallet shows for them, and the signature is
 * r ‖ s ‖ v with the low s that USDC's `ecrecover` check needs.
 */
class EthereumAccountTest {

    private fun addressOf(words: String, passphrase: String = ""): String {
        val seed = WalletManager.seedFor(words.split(" "), passphrase)
        val key = EthereumAccount.privateKeyFromSeed(seed)
        return EthereumAccount.address(key)
    }

    /** BIP39's all-zero test words: MetaMask's first account (also the permit owner in iOS's test). */
    @Test fun abandonAboutAddress() {
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            addressOf("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
    }

    /** Hardhat's and Foundry's test words: their account #0. */
    @Test fun testJunkAddress() {
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", addressOf("test test test test test test test test test test test junk"))
    }

    private val cowKey = Keccak256.hash("cow".toByteArray())

    /** EIP-712's example signer: keccak256("cow"). */
    @Test fun cowAddress() {
        assertEquals("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826", EthereumAccount.address(cowKey))
    }

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

    /** EIP-712's example signature (eth-sig-util's `signTypedData_v4` of "Mail" with the cow key),
     *  deterministic (RFC 6979) as libsecp256k1's. */
    @Test fun signsMailAsEthereumWallets() {
        val signature = EthereumAccount.sign(EIP712.digest(mail), cowKey)
        assertEquals(
            "4355c47d63924e8a72e509b65029052eb6c299d53a04e167c5775fd466751c9d" +
                "07299936d304c153f6443dfa05f40ff007d72911b6f72307f996231605b91562" + "1c",
            EthereumHex.string(signature),
        )
    }

    /** Any digest: 65 bytes, low s, v 27 or 28, and the signature checks out against the key. */
    @Test fun signatureShape() {
        val key = Keccak256.hash("buy kaspa".toByteArray())
        val n = Secp256k1.N
        val publicPoint = Secp256k1.G.multiply(BigInteger(1, key)).normalize()
        for (i in 0 until 20) {
            val digest = Keccak256.hash("digest $i".toByteArray())
            val signature = EthereumAccount.sign(digest, key)
            assertEquals(65, signature.size)
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            val v = signature[64].toInt() and 0xFF
            assertTrue(s <= n.shiftRight(1))
            assertTrue(v == 27 || v == 28)
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(publicPoint, ECDomainParameters(Secp256k1.CURVE, Secp256k1.G, n)))
            assertTrue(verifier.verifySignature(digest, r, s))
            assertEquals(recoverAddress(digest, r, s, v - 27), EthereumAccount.address(key))
        }
    }

    /** SEC 1 4.1.6 public key recovery, as `ecrecover` does it. */
    private fun recoverAddress(digest: ByteArray, r: BigInteger, s: BigInteger, recovery: Int): String {
        val n = Secp256k1.N
        val curve = Secp256k1.CURVE
        val x = r
        val encoded = ByteArray(33)
        encoded[0] = if (recovery and 1 == 1) 0x03 else 0x02
        val xBytes = x.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        System.arraycopy(xBytes, 0, encoded, 1, 32)
        val bigR = curve.decodePoint(encoded)
        val e = BigInteger(1, digest)
        val rInv = r.modInverse(n)
        val q = bigR.multiply(s).subtract(Secp256k1.G.multiply(e)).multiply(rInv).normalize()
        val point = q.getEncoded(false)
        val hash = Keccak256.hash(point.copyOfRange(1, 65))
        return EthereumHex.checksumAddress(hash.copyOfRange(12, 32))
    }
}
