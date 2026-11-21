package com.kachat.app.services

import com.kachat.app.util.Schnorr
import org.bouncycastle.crypto.digests.Blake2bDigest

/**
 * Checks a KaPost's signature as KaPost signs it: BIP-340 over the Kaspa personal-message hash
 * (keyed BLAKE2b, "PersonalMessageSigningHash"), the hash [com.kachat.app.util.KaspaMessageSigner]
 * signs with in KASPA_PERSONAL_MESSAGE mode (KaPost's `KaspaMessageSigner.verifyPersonalMessage`,
 * iOS `KaPostsChainPosts.signatureIsValid`).
 */
object KaPostSignatures {
    private val PERSONAL_MESSAGE_KEY = "PersonalMessageSigningHash".toByteArray(Charsets.US_ASCII)

    fun verifyPersonalMessage(message: String, signature: ByteArray, xOnlyPublicKey: ByteArray): Boolean =
        try {
            Schnorr.verify(personalMessageHash(message.toByteArray(Charsets.UTF_8)), signature, xOnlyPublicKey)
        } catch (_: Exception) {
            false
        }

    private fun personalMessageHash(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(PERSONAL_MESSAGE_KEY, 32, null, null)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }
}
