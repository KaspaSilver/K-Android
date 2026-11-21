package com.kachat.app.services

import android.content.Context

/**
 * Messages deleted on this device (Select, then Delete), per account, for 1:1 chats and groups
 * (iOS d7c56280 `DeletedMessageLog`). Deleting only removes the local copy; the chain still has
 * the message, so every sync, the open chat's poll and a chat archive import would bring it back
 * without this list. Every way a message gets filed checks it
 * ([com.kachat.app.repository.ChatRepository.insertMessage], the group stream's incoming
 * message in [com.kachat.app.repository.GroupRepository]). Device-local like the delete itself,
 * and removed with the account ([com.kachat.app.repository.ChatRepository.wipeAllLocalDataForAddress]).
 *
 * An object rather than an injected store so the repositories reach it through the context they
 * already hold; the lists are read once per account and kept in memory.
 */
object DeletedMessageLog {
    enum class Kind(val raw: String) { CHAT("chat"), GROUP("group") }

    /** Oldest dropped first past this: a delete thousands of deletes ago may come back. */
    const val LIMIT = 20_000

    private const val PREFS = "kachat_deleted_messages"

    private class Entry(val order: ArrayList<String>, val ids: HashSet<String>)

    private val cache = HashMap<String, Entry>()

    private fun key(kind: Kind, wallet: String) = "kachat_deleted_${kind.raw}_messages_v1.${wallet.lowercase()}"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun entry(context: Context, kind: Kind, wallet: String): Pair<String, Entry> {
        val key = key(kind, wallet)
        cache[key]?.let { return key to it }
        val stored = prefs(context).getString(key, null).orEmpty()
        val order = ArrayList(stored.split('\n').filter { it.isNotEmpty() })
        val entry = Entry(order, HashSet(order))
        cache[key] = entry
        return key to entry
    }

    @Synchronized
    fun contains(context: Context, txId: String, kind: Kind, wallet: String): Boolean {
        if (txId.isEmpty() || wallet.isEmpty()) return false
        return entry(context, kind, wallet).second.ids.contains(txId)
    }

    @Synchronized
    fun add(context: Context, txIds: Collection<String>, kind: Kind, wallet: String) {
        if (wallet.isEmpty()) return
        val (key, entry) = entry(context, kind, wallet)
        if (!append(entry.order, entry.ids, txIds, LIMIT)) return
        prefs(context).edit().putString(key, entry.order.joinToString("\n")).apply()
    }

    /** Delete Account: the account's lists go with it. */
    @Synchronized
    fun forget(context: Context, wallet: String) {
        val editor = prefs(context).edit()
        for (kind in Kind.values()) {
            val key = key(kind, wallet)
            cache.remove(key)
            editor.remove(key)
        }
        editor.apply()
    }

    /**
     * Adds the new, non-empty [txIds] to [order] / [ids], then drops the oldest past [limit].
     * False when nothing changed.
     */
    internal fun append(order: MutableList<String>, ids: MutableSet<String>, txIds: Collection<String>, limit: Int): Boolean {
        var changed = false
        for (txId in txIds) {
            if (txId.isEmpty() || !ids.add(txId)) continue
            order.add(txId)
            changed = true
        }
        if (order.size > limit) {
            val dropped = order.subList(0, order.size - limit)
            ids.removeAll(dropped.toSet())
            dropped.clear()
        }
        return changed
    }
}
