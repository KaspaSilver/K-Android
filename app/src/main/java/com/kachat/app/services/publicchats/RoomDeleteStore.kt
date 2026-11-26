package com.kachat.app.services.publicchats

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room messages their sender deleted for everyone ([com.kachat.app.util.MessageDelete]), per
 * account, as "room|txid|sender" (iOS `PublicChatStore.recordDelete`, `kachat_room_deletes_<wallet>`):
 * kept so a page or block that still carries one (before the indexer has removed it, or from an
 * indexer that never will) doesn't bring it back. Only that room's row from that same address is
 * refused, so nobody else's "delete" can hide a message. Newest [LIMIT]. Cleared with the
 * account ([com.kachat.app.services.AccountFeatureCleanup]).
 */
@Singleton
class RoomDeleteStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var cache: Pair<String, LinkedHashSet<String>>? = null

    private fun load(wallet: String): LinkedHashSet<String> {
        cache?.let { if (it.first == wallet) return it.second }
        val set = LinkedHashSet<String>()
        runCatching {
            val array = JSONArray(prefs.getString(key(wallet), null) ?: "[]")
            for (i in 0 until array.length()) set.add(array.getString(i))
        }
        cache = wallet to set
        return set
    }

    /** Records a delete for [wallet]'s account; true when it's new. */
    fun record(wallet: String, channel: String, targetTxId: String, sender: String): Boolean = synchronized(lock) {
        val owner = wallet.lowercase()
        val set = load(owner)
        if (!RoomDeletes.remember(set, RoomDeletes.entry(channel, targetTxId, sender))) return@synchronized false
        prefs.edit().putString(key(owner), JSONArray(set.toList()).toString()).apply()
        true
    }

    /** Whether [wallet]'s account holds a delete of this room's row from this sender. */
    fun isDeleted(wallet: String?, channel: String, txId: String, sender: String): Boolean {
        val owner = wallet?.lowercase() ?: return false
        return synchronized(lock) { RoomDeletes.entry(channel, txId, sender) in load(owner) }
    }

    /** Delete Account: the account's deletes go (iOS `WalletManager` removes the same key). */
    fun clear(wallet: String) = synchronized(lock) {
        val owner = wallet.lowercase()
        prefs.edit().remove(key(owner)).apply()
        if (cache?.first == owner) cache = null
    }

    private fun key(wallet: String) = "kachat_room_deletes_v2_$wallet"

    companion object {
        const val PREFS = "kachat_room_deletes"
    }
}

/** The pure parts of [RoomDeleteStore], for tests. */
object RoomDeletes {
    const val LIMIT = 2_000

    /** "room|txid|sender", the room normalized and the rest lowercased (iOS `deleteEntry`). */
    fun entry(channel: String, txId: String, sender: String): String =
        "${com.kachat.app.util.MessageProtocol.normalizeChannelName(channel)}|${txId.lowercase()}|${sender.lowercase()}"

    /** Adds [entry] (newest last), dropping the oldest past [limit]; false when already there. */
    fun remember(set: LinkedHashSet<String>, entry: String, limit: Int = LIMIT): Boolean {
        if (!set.add(entry)) return false
        val extra = set.size - limit
        if (extra > 0) {
            val iterator = set.iterator()
            repeat(extra) { iterator.next(); iterator.remove() }
        }
        return true
    }
}
