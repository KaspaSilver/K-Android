package com.kachat.app.services.tokens

import com.kachat.app.services.WalletManager
import com.kachat.app.util.EIP712
import com.kachat.app.util.EthereumHex
import com.kachat.app.util.Keccak256
import com.kachat.app.util.Secp256k1
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.math.BigInteger

/**
 * The account's Ethereum address, used only by Buy Kaspa with Debit Card (iOS bc0e6ba9
 * `Services/Tokens/EthereumAccount.swift`, owner 2026-11-26).
 *
 * Trestly's buy page sells USDC on Ethereum through Stripe into the buyer's own wallet, which then
 * signs one EIP-2612 permit so Trestly's bridge can deposit it to Kaspa as kUSDT. K has no other
 * use for Ethereum, so the address is never shown as a wallet: the key is derived from the
 * account's own words at the standard path m/44'/60'/0'/0/0 (with the account's passphrase, if it
 * has one), which is the address MetaMask or any Ethereum wallet shows for the same words. USDC
 * that ever stays there (a refund) can therefore be reached with the words alone.
 *
 * The private key is derived only to sign and wiped right after (the byte copies K holds; the
 * derivation's own big integers are left to the garbage collector); the address is kept in memory
 * per account. Nothing here is ever logged. Derivation runs PBKDF2, so call it off the main thread.
 */
object EthereumAccount {
    /** Ethereum mainnet. Trestly sells by card on mainnet only. */
    const val CHAIN_ID = 1

    /** USDC's contract on Ethereum mainnet: the only token K signs a permit for. */
    const val USDC_CONTRACT = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

    @Volatile
    private var cached: Pair<String, String>? = null

    /** The address for the signed-in account; null when its words aren't on this phone. */
    fun address(walletManager: WalletManager): String? {
        // signed out: no account to show an address for
        if (walletManager.isLoggedOut) return null
        val account = walletManager.getActiveAccount()?.address ?: return null
        cached?.let { if (it.first == account) return it.second }
        val key = try { privateKey(walletManager) } catch (e: IllegalStateException) { null } ?: return null
        return try {
            val address = address(key)
            cached = account to address
            address
        } catch (e: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    /** Forgets the cached address. */
    fun reset() { cached = null }

    /** m/44'/60'/0'/0/0 of the active account's words and passphrase; null without words.
     *  Throws once signed out, like [WalletManager.getPrivateKeyBytes]: a permit still being
     *  signed after Log Out must not sign as the old account. */
    fun privateKey(walletManager: WalletManager): ByteArray? {
        if (walletManager.isLoggedOut) throw IllegalStateException("Signed out")
        val account = walletManager.getActiveAccount() ?: return null
        // the words as WalletManager splits them for the account's Kaspa keys, so the seed is the same
        val phrase: String = account.mnemonic
        if (phrase.isBlank()) return null
        val seed = try {
            WalletManager.seedFor(phrase.split(" "), account.passphrase, WalletManager.passphraseFormOf(account))
        } catch (e: Exception) {
            return null
        }
        return try { privateKeyFromSeed(seed) } catch (e: Exception) { null } finally { seed.fill(0) }
    }

    /** m/44'/60'/0'/0/0 of a BIP39 seed, as 32 bytes. */
    internal fun privateKeyFromSeed(seed: ByteArray): ByteArray {
        val master = HDKeyDerivation.createMasterPrivateKey(seed)
        val purpose = HDKeyDerivation.deriveChildKey(master, ChildNumber(44, true))
        val coin = HDKeyDerivation.deriveChildKey(purpose, ChildNumber(60, true))
        val account = HDKeyDerivation.deriveChildKey(coin, ChildNumber(0, true))
        val change = HDKeyDerivation.deriveChildKey(account, ChildNumber(0, false))
        val key = HDKeyDerivation.deriveChildKey(change, ChildNumber(0, false))
        return fixed32(key.privKey)
    }

    /** The EIP-55 address of a key: the last 20 bytes of keccak256 of its uncompressed public key. */
    fun address(privateKey: ByteArray): String {
        val d = scalar(privateKey)
        val point = Secp256k1.G.multiply(d).normalize().getEncoded(false)
        if (point.size != 65 || point[0] != 0x04.toByte()) throw EIP712.Failure("Bad public key.")
        val hash = Keccak256.hash(point.copyOfRange(1, 65))
        return EthereumHex.checksumAddress(hash.copyOfRange(12, 32))
    }

    /**
     * Signs a 32-byte digest as Ethereum does: r ‖ s ‖ v, 65 bytes, v = 27 + the recovery id.
     * RFC 6979's deterministic nonce (as libsecp256k1 on iOS), and always the low-s form, which
     * USDC's `ecrecover` check needs.
     */
    fun sign(digest: ByteArray, privateKey: ByteArray): ByteArray {
        if (digest.size != 32) throw EIP712.Failure("The digest isn't 32 bytes.")
        val n = Secp256k1.N
        val d = scalar(privateKey)
        val e = BigInteger(1, digest)
        val calculator = HMacDSAKCalculator(SHA256Digest())
        calculator.init(n, d, digest)
        while (true) {
            val k = calculator.nextK()
            val point = Secp256k1.G.multiply(k).normalize()
            val x = point.affineXCoord.toBigInteger()
            val r = x.mod(n)
            if (r.signum() == 0) continue
            var s = k.modInverse(n).multiply(e.add(d.multiply(r))).mod(n)
            if (s.signum() == 0) continue
            var recovery = (if (point.affineYCoord.toBigInteger().testBit(0)) 1 else 0) or (if (x >= n) 2 else 0)
            if (s > n.shiftRight(1)) {
                s = n.subtract(s)
                recovery = recovery xor 1
            }
            if (recovery !in 0..1) throw EIP712.Failure("The signature came out malformed.")
            return fixed32(r) + fixed32(s) + byteArrayOf((27 + recovery).toByte())
        }
    }

    private fun scalar(privateKey: ByteArray): BigInteger {
        if (privateKey.size != 32) throw EIP712.Failure("Bad private key.")
        val d = BigInteger(1, privateKey)
        if (d.signum() == 0 || d >= Secp256k1.N) throw EIP712.Failure("Bad private key.")
        return d
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
