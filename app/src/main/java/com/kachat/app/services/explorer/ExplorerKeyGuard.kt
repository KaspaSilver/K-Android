package com.kachat.app.services.explorer

import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.Schnorr

/**
 * The Explorer's search never sends one of this wallet's private keys anywhere (iOS d88891ea
 * `isOwnPrivateKey`, audit 2026-11-27 L5): a 64-character id is looked up only on Search or a tap,
 * and refused when it is the key of the chatting address or of a revealed spending address. Told
 * from the addresses the typed key would sign for (a regular one, and the quantum chain's P2SH),
 * so no key of the wallet is derived to compare.
 */
object ExplorerKeyGuard {
    private val CURVE_ORDER = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /** The addresses [hex], read as a private key, would own: none when it isn't 64 hex or not a
     *  valid key. */
    fun addressesFor(hex: String): List<String> {
        val text = hex.trim()
        if (!ExplorerParsing.isHexId(text)) return emptyList()
        val bytes = ByteArray(32) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        // a key is 1 ..< the curve's order
        val d = java.math.BigInteger(1, bytes)
        if (d.signum() == 0 || d >= CURVE_ORDER) return emptyList()
        val xOnly = runCatching { Schnorr.publicKeyXOnly(bytes) }.getOrNull()?.takeIf { it.size == 32 } ?: return emptyList()
        val regular = runCatching { KaspaAddress.encode(KaspaNetwork.hrp, 0x00, xOnly) }.getOrNull()
        val quantum = runCatching {
            KachatNamesService.p2shAddress(KachatNames.Codec.p2shScript(KachatNames.Codec.p2pkScript(xOnly)))
        }.getOrNull()
        return listOfNotNull(regular, quantum).map { it.lowercase() }
    }

    /** Whether [hex] is the private key of one of [ownAddresses]. */
    fun isOwnKey(hex: String, ownAddresses: Collection<String>): Boolean {
        if (ownAddresses.isEmpty()) return false
        val own = ownAddresses.map { it.trim().lowercase() }.toSet()
        return addressesFor(hex).any { it in own }
    }

    /** A 64-hex id is never looked up as it's typed: it could be a pasted private key. */
    fun isLookedUpOnlyOnSearch(text: String): Boolean = ExplorerParsing.isHexId(text.trim())
}
