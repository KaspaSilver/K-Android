package com.kachat.app.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Messages deleted on this device (Select, then Delete), per account, for 1:1 chats and groups
 * (iOS d7c56280 `DeletedMessageLog`). Deleting only removes the local copy; the chain still has
 * the message, so every sync, the open chat's poll and a chat archive import would bring it back
 * without this list. Every way a message gets filed checks it
 * ([com.kachat.app.repository.ChatRepository.insertMessage], the group stream's incoming
 * message in [com.kachat.app.repository.GroupRepository]). Device-local like the delete itself,
 * and removed with the account ([com.kachat.app.repository.ChatRepository.wipeAllLocalDataForAddress]).
 *
 * Its own small SQLite database, like [GroupSeenMessageStore] (audit 2026-11-25 S17): it used to
 * be one SharedPreferences string of up to 20,000 ids per account, held in memory and rewritten
 * in full on every delete. Now a delete writes its own rows and a check is one keyed lookup. A
 * list left in SharedPreferences by an older build moves here the first time it is needed.
 *
 * An object rather than an injected store so the repositories reach it through the context they
 * already hold.
 */
object DeletedMessageLog {
    enum class Kind(val raw: String) { CHAT("chat"), GROUP("group") }

    /** Oldest dropped first past this: a delete thousands of deletes ago may come back. */
    const val LIMIT = 20_000

    private const val PREFS = "kachat_deleted_messages"
    private const val DATABASE_NAME = "kachat_deleted_messages.db"
    private const val TABLE = "deleted_messages"

    private class Store(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            // The rowid keeps the order of deletes, for dropping the oldest past LIMIT.
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, walletAddress TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, txId TEXT NOT NULL, UNIQUE (walletAddress, kind, txId))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile
    private var store: Store? = null

    /** The (kind, wallet) lists already checked for an older build's SharedPreferences copy. */
    private val migrated = HashSet<String>()

    private fun db(context: Context): SQLiteDatabase {
        val helper = store ?: synchronized(this) {
            store ?: Store(context.applicationContext).also { store = it }
        }
        return helper.writableDatabase
    }

    private fun prefsKey(kind: Kind, wallet: String) = "kachat_deleted_${kind.raw}_messages_v1.${wallet.lowercase()}"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Moves an older build's list for (kind, wallet) into the database, once. */
    private fun migrateIfNeeded(context: Context, kind: Kind, wallet: String) {
        val key = prefsKey(kind, wallet)
        if (!migrated.add(key)) return
        val prefs = prefs(context)
        val stored = prefs.getString(key, null) ?: return
        val order = ArrayList<String>()
        append(order, HashSet(), stored.split('\n'), LIMIT)
        insert(db(context), order, kind, wallet)
        prefs.edit().remove(key).apply()
    }

    private fun insert(db: SQLiteDatabase, txIds: Collection<String>, kind: Kind, wallet: String): Boolean {
        var changed = false
        db.beginTransaction()
        try {
            for (txId in txIds) {
                if (txId.isEmpty()) continue
                val values = ContentValues().apply {
                    put("walletAddress", wallet.lowercase())
                    put("kind", kind.raw)
                    put("txId", txId)
                }
                if (db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) changed = true
            }
            if (changed) {
                // Past LIMIT the oldest go.
                db.execSQL(
                    "DELETE FROM $TABLE WHERE walletAddress = ? AND kind = ? AND seq <= (" +
                        "SELECT seq FROM $TABLE WHERE walletAddress = ? AND kind = ? ORDER BY seq DESC LIMIT 1 OFFSET ?)",
                    arrayOf<Any>(wallet.lowercase(), kind.raw, wallet.lowercase(), kind.raw, LIMIT)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    @Synchronized
    fun contains(context: Context, txId: String, kind: Kind, wallet: String): Boolean {
        if (txId.isEmpty() || wallet.isEmpty()) return false
        migrateIfNeeded(context, kind, wallet)
        return db(context).query(
            TABLE, arrayOf("seq"), "walletAddress = ? AND kind = ? AND txId = ?",
            arrayOf(wallet.lowercase(), kind.raw, txId), null, null, null, "1"
        ).use { it.moveToFirst() }
    }

    @Synchronized
    fun add(context: Context, txIds: Collection<String>, kind: Kind, wallet: String) {
        if (wallet.isEmpty()) return
        migrateIfNeeded(context, kind, wallet)
        insert(db(context), txIds, kind, wallet)
    }

    /**
     * Adds the new, non-empty [txIds] to [order] / [ids], then drops the oldest past [limit].
     * False when nothing changed. (The in-memory form of the list, for moving an older build's.)
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

    /** Delete Account: the account's lists go with it. */
    @Synchronized
    fun forget(context: Context, wallet: String) {
        db(context).delete(TABLE, "walletAddress = ?", arrayOf(wallet.lowercase()))
        val editor = prefs(context).edit()
        for (kind in Kind.values()) {
            val key = prefsKey(kind, wallet)
            migrated.add(key)
            editor.remove(key)
        }
        editor.apply()
    }
}
