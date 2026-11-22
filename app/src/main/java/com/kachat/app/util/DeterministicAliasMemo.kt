package com.kachat.app.util

import java.util.concurrent.ConcurrentHashMap

/**
 * The alias a contact's messages to me carry ([com.kachat.app.services.WalletManager.myDeterministicAlias])
 * never changes for one account key and contact, but deriving it is an ECDH point multiplication -
 * and the poll's contact sweep and the push registration ask again for every contact each time.
 * Remembered for the last account key used, as [AccountKey] remembers its public key. Only an
 * alias [derive] returned is kept, so a key the check refused (it throws) is never cached.
 */
object DeterministicAliasMemo {
    private class Entry(val privateKey: ByteArray, val myAddress: String) {
        val aliases = ConcurrentHashMap<String, String>()
    }

    @Volatile private var entry: Entry? = null

    /** Plenty for any address book; past it the memo starts over rather than grow. */
    private const val MAX_ENTRIES = 4096

    fun myAlias(contactAddress: String, privateKey: ByteArray, myAddress: String, derive: () -> String): String {
        val current = entry?.takeIf { it.myAddress == myAddress && it.privateKey.contentEquals(privateKey) }
            ?: Entry(privateKey.copyOf(), myAddress).also { entry = it }
        current.aliases[contactAddress]?.let { return it }
        val alias = derive()
        if (current.aliases.size >= MAX_ENTRIES) current.aliases.clear()
        current.aliases[contactAddress] = alias
        return alias
    }
}
