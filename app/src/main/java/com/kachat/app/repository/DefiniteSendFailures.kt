package com.kachat.app.repository

import android.content.Context

/**
 * The 1:1 send placeholders ("pending_<uuid>") whose send failed with a definite error - refused
 * before or by the network (insufficient funds, a node rejection, an account switch), never an
 * unknown outcome, which becomes Not confirmed instead (audit 2026-11-27 C3).
 *
 * Such a send did not go out, so no delivered copy of it can exist: the stuck-pair repair and the
 * import-side matcher must never treat another delivered message with the same text ("ok",
 * "thanks") as its twin and delete it or take it over. Kept on disk, since the repair also runs
 * at the next launch. Ids are random and global, so one list serves every account; a Retry takes
 * its id back out ([remove]). Capped, oldest out first.
 */
class DefiniteSendFailures(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile private var cached: List<String>? = null

    private fun load(): List<String> = cached ?: synchronized(lock) {
        cached ?: decode(prefs.getString(KEY, null)).also { cached = it }
    }

    fun ids(): Set<String> = load().toSet()

    operator fun contains(id: String): Boolean = id in load()

    fun add(id: String) = synchronized(lock) {
        val next = appended(load(), id)
        cached = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    fun remove(id: String) = synchronized(lock) {
        val current = load()
        if (id !in current) return@synchronized
        val next = current - id
        cached = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    companion object {
        private const val PREFS = "chat_definite_send_failures"
        private const val KEY = "ids"
        internal const val MAX_IDS = 500

        internal fun appended(current: List<String>, id: String): List<String> =
            ((current - id) + id).takeLast(MAX_IDS)

        internal fun encode(ids: List<String>): String = ids.joinToString("\n")

        internal fun decode(raw: String?): List<String> =
            raw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
}
