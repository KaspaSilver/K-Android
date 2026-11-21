package com.kachat.app.services.tokens

import android.content.Context
import android.util.Log
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import org.json.JSONObject
import java.security.MessageDigest

/**
 * kUSDT on Kaspa L1: Trestly's KCC-20 token (kaspador/kusdt-open, SPEC.md). What a wallet needs
 * to read and move it: the deployment (covenant ids, the KAS a coin carries), the token template
 * (pinned, `assets/kusdt/kusdt-kcc20-template.json`), a coin's state, and the script it locks to.
 * iOS `Kusdt.swift` (5b7c772c).
 *
 * Trust: nothing Trestly's API says is believed until the node shows it. A coin counts only when
 * the node holds that exact outpoint, at the script recomputed here from the state, carrying
 * kUSDT's covenant id ([KusdtBalances]). A wrong template or a lying API can only hide coins,
 * never invent them.
 */
object Kusdt {
    private const val TAG = "kUSDT"

    /** kUSDT has 6 decimals, like USDT. */
    const val DECIMALS = 6

    // MARK: Deployment

    class Deployment(
        /** kUSDT's covenant id: every kUSDT coin carries it (and so does the minter and its seed,
         *  which is why the script check matters too). */
        val covenantId: ByteArray,
        /** KasPool's covenant id. */
        val poolId: ByteArray,
        /** KAS (sompi) every new token coin carries for its storage mass (`tokenValue`). */
        val tokenValue: Long,
        /** KAS (sompi) a new liquidity position carries. */
        val positionValue: Long,
    ) {
        val covenantIdHex: String get() = hex(covenantId)
    }

    /** kusdt-open `deployments/<network>.json` and `wallet/extension/src/config/defaults.ts`. */
    fun deployment(network: KaspaNetwork.Type): Deployment = when (network) {
        KaspaNetwork.Type.MAINNET -> Deployment(
            covenantId = unhex("50b3477e7368d0c5bfbd64483b4607350ec411377972f33e40b2d4b6d1513fd3"),
            poolId = unhex("28e7758e6fc0e2054cceb7f87f9da44a53accfa0a1795c8075c80fb99c83aaad"),
            tokenValue = 150_000_000L,
            positionValue = 200_000_000L,
        )
        KaspaNetwork.Type.TESTNET -> Deployment(
            covenantId = unhex("a1d9fd1347128011ad0036b077949d63274d5d3b8ac8351776c30c945ef2c2fb"),
            poolId = unhex("99445008e36139d0a57040f562414e298a3022fb739c0e9374b1dbb9f4c42171"),
            tokenValue = 150_000_000L,
            positionValue = 200_000_000L,
        )
    }

    /** The deployment of the network this launch runs on. */
    val current: Deployment get() = deployment(KaspaNetwork.launch)

    // MARK: Template

    /** The KCC20 token template: a coin's redeem script is `prefix ‖ state ‖ suffix`. */
    class Template(val templateHash: ByteArray, val prefix: ByteArray, val suffix: ByteArray)

    /** SHA-256 of `prefix ‖ suffix` as shipped: a resource that differs is refused. */
    const val TEMPLATE_SHA256 = "e87405ef5a227167e373f94968432ccec6d792c47dce783a41f45c0095a97137"

    /** Where the pinned template ships. */
    const val TEMPLATE_ASSET = "kusdt/kusdt-kcc20-template.json"

    @Volatile private var cachedTemplate: Template? = null

    /** The pinned template, or null if the asset is missing or not the one pinned. Read once
     *  (a small file; safe on any thread). */
    fun template(context: Context): Template? {
        cachedTemplate?.let { return it }
        val json = try {
            context.assets.open(TEMPLATE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "the token template resource is missing or unreadable")
            return null
        }
        val parsed = parseTemplate(json)
        if (parsed == null) Log.w(TAG, "the token template resource isn't the pinned one: refused")
        cachedTemplate = parsed
        return parsed
    }

    /** The template in [json] if it is exactly the pinned one ([TEMPLATE_SHA256], a 34-byte
     *  prefix ending in its hash); null otherwise. */
    fun parseTemplate(json: String): Template? {
        val obj = try { JSONObject(json) } catch (e: Exception) { return null }
        val hash = obj.optString("templateHash").let(::unhexOrNull) ?: return null
        val prefix = obj.optString("prefix").let(::unhexOrNull) ?: return null
        val suffix = obj.optString("suffix").let(::unhexOrNull) ?: return null
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(prefix + suffix))
        if (digest != TEMPLATE_SHA256 || prefix.size != 34 || !prefix.copyOfRange(2, 34).contentEquals(hash)) return null
        return Template(hash, prefix, suffix)
    }

    // MARK: State

    /** KCC-2 owner schemes (SPEC section 4). KaChat holds tokens only as 0x00 (a Schnorr key). */
    const val OWNER_P2PK_SCHNORR = 0x00
    const val OWNER_P2SH = 0x03
    const val OWNER_COVENANT_ID = 0x04

    private val ZERO32 = ByteArray(32)

    /**
     * A KCC20 coin's state: what it holds and who owns it. Encoded as 112 bytes of explicit
     * pushes: `08 amount(8, LE sign-magnitude) 20 owner 01 scheme 01 borrow 20 guard 20 extension`.
     * [amount] is in base units and never negative (a JSON amount of 2^63 or more is refused
     * where it is read).
     */
    class State(
        val amount: Long,
        val owner: ByteArray,
        val ownerScheme: Int,
        val borrowScheme: Int = 0,
        val borrowGuard: ByteArray = ByteArray(32),
        val extensionCommitment: ByteArray = ByteArray(32),
    ) {
        /** A plain coin of [owner]: Schnorr owner, nothing borrowed, no extension (the only kind
         *  KaChat shows or spends; SPEC section 5). */
        fun isPlainOf(owner: ByteArray): Boolean =
            ownerScheme == OWNER_P2PK_SCHNORR && this.owner.contentEquals(owner) && borrowScheme == 0 &&
                borrowGuard.contentEquals(ZERO32) && extensionCommitment.contentEquals(ZERO32)

        val encoded: ByteArray
            get() {
                val out = java.io.ByteArrayOutputStream(112)
                out.write(0x08)
                var a = amount
                repeat(8) { out.write((a and 0xff).toInt()); a = a ushr 8 }
                out.write(0x20); out.write(owner)
                out.write(0x01); out.write(ownerScheme and 0xff)
                out.write(0x01); out.write(borrowScheme and 0xff)
                out.write(0x20); out.write(borrowGuard)
                out.write(0x20); out.write(extensionCommitment)
                return out.toByteArray()
            }

        override fun equals(other: Any?): Boolean = other is State && amount == other.amount &&
            owner.contentEquals(other.owner) && ownerScheme == other.ownerScheme && borrowScheme == other.borrowScheme &&
            borrowGuard.contentEquals(other.borrowGuard) && extensionCommitment.contentEquals(other.extensionCommitment)

        override fun hashCode(): Int = amount.hashCode() * 31 + owner.contentHashCode()

        companion object {
            fun plain(owner: ByteArray, amount: Long): State = State(amount = amount, owner = owner, ownerScheme = OWNER_P2PK_SCHNORR)
        }
    }

    /** The coin's redeem script. */
    fun redeemScript(state: State, template: Template): ByteArray = template.prefix + state.encoded + template.suffix

    /** Its P2SH locking script (`OP_BLAKE2B <hash> OP_EQUAL`). */
    fun lockingScript(state: State, template: Template): ByteArray = KachatNames.Codec.p2shScript(redeemScript(state, template))

    /** The address the node lists the coin under (this network's prefix). */
    fun address(lockingScript: ByteArray): String? = KachatNamesService.p2shAddress(lockingScript)

    // MARK: Owners

    /** The KCC-2 owner a KaChat address stands for: a Schnorr key address's x-only key, on the
     *  network this launch runs on. Null for anything else (KaChat never holds tokens on a script
     *  or ECDSA address). */
    fun schnorrOwner(address: String): ByteArray? {
        val trimmed = address.trim()
        if (!trimmed.lowercase().startsWith(KaspaNetwork.hrp + ":")) return null
        val (version, payload) = try { KaspaAddress.decode(trimmed) } catch (e: Exception) { return null }
        if (version.toInt() != 0 || payload.size != 32) return null
        return payload
    }

    /**
     * The owner key of a token coin an input spends (iOS `ChatService.tokenCoinOwner`): the
     * redeem script, the last push of a P2SH input's signature script, is `prefix ‖ 112-byte
     * state ‖ suffix`, and the state's bytes 10-41 are the owner ([State.encoded]). Null when the
     * input doesn't spend a token coin. [signatureScriptHex] and [templatePrefixHex] lowercase.
     */
    fun tokenCoinOwner(signatureScriptHex: String, templatePrefixHex: String): ByteArray? {
        if (templatePrefixHex.isEmpty()) return null
        var from = 0
        while (true) {
            val found = signatureScriptHex.indexOf(templatePrefixHex, from)
            if (found < 0) return null
            // byte-aligned only
            if (found % 2 == 0) {
                val stateStart = found + templatePrefixHex.length
                if (stateStart + 224 <= signatureScriptHex.length) {
                    val stateHex = signatureScriptHex.substring(stateStart, stateStart + 224)
                    if (stateHex.startsWith("08") && stateHex.startsWith("20", 18)) {
                        unhexOrNull(stateHex.substring(20, 84))?.let { return it }
                    }
                }
            }
            from = found + 1
        }
    }

    // MARK: Hex

    fun unhex(s: String): ByteArray = unhexOrNull(s) ?: ByteArray(0)

    /** Strict: an even number of hex digits, nothing else. */
    fun unhexOrNull(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(s[2 * i])
            val lo = hexDigit(s[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** ASCII hex digits only (never another script's digits, which `Character.digit` takes). */
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(d: ByteArray): String {
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xff
            out[2 * i] = HEX[v ushr 4]
            out[2 * i + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}
