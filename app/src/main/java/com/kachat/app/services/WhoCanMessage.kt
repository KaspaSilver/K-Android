package com.kachat.app.services

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings > Chats > Who Can Message You? (iOS `WhoCanMessage`, owner's call 2026-11-23): Anyone,
 * or Only Users with a Username. With usernames only, a Message Request from someone with no
 * .kachat name is hidden (not deleted: Anyone shows it again) and rings nothing: the push handler
 * ([KaChatFirebaseMessagingService]) reads the choice and stays silent for a sender with no name
 * this phone knows ([KnownKachatNames]). Accepted chats, contacts and groups are never affected.
 * Saved on the device (`kachat_who_can_message`), as iOS keeps it in UserDefaults.
 */
enum class WhoCanMessage(val raw: String) {
    ANYONE("anyone"),
    USERNAMES("usernames");

    companion object {
        private const val PREFS = "kachat_who_can_message"
        const val KEY = "kachat_who_can_message"

        private val state = MutableStateFlow<WhoCanMessage?>(null)

        fun fromRaw(raw: String?): WhoCanMessage = entries.firstOrNull { it.raw == raw } ?: ANYONE

        /** The choice, read once and kept (iOS `DefaultsBackedCache`, audit 2026-11-24 #28). */
        fun current(context: Context): WhoCanMessage {
            state.value?.let { return it }
            val loaded = runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            }.getOrNull().let(::fromRaw)
            state.compareAndSet(null, loaded)
            return state.value ?: loaded
        }

        /** The choice as it changes: Message Requests and their count filter by it. */
        fun flow(context: Context): StateFlow<WhoCanMessage?> {
            current(context)
            return state.asStateFlow()
        }

        fun set(context: Context, value: WhoCanMessage) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.raw).apply()
            }
            state.value = value
        }

        /**
         * A request Who Can Message You? hides (iOS `isHiddenRequest`): Only Users with a Username
         * is on and the sender has no .kachat name, or none known yet ([label] null; one that
         * lands shows the request).
         */
        fun hidesRequest(choice: WhoCanMessage, label: String?): Boolean =
            choice == USERNAMES && label.isNullOrEmpty()
    }
}

/**
 * The request senders the push handler silenced for want of a known name (iOS App Group
 * `chat_hidden_request_addresses`, "wallet|address", newest 500). The push handler can't look
 * names up, and once the app has the sender's chat it is a Message Request, which is silent for
 * good; so once the app learns their name it rings the request once and takes them off this list
 * (`ChatRepository.announceNamedHiddenRequests`, audit 2026-11-24 #13). Cleared with the account.
 */
object HiddenRequestSenders {
    private const val PREFS = "kachat_hidden_requests"
    private const val KEY = "chat_hidden_request_addresses"
    const val LIMIT = 500
    private val lock = Any()

    fun entry(wallet: String, address: String): String = "${wallet.trim().lowercase()}|${address.trim().lowercase()}"

    /** [entries] with [entry] added at the end (once), the oldest dropped past [LIMIT]. */
    internal fun adding(entries: List<String>, entry: String): List<String> {
        if (entry in entries) return entries
        val out = entries + entry
        return if (out.size > LIMIT) out.drop(out.size - LIMIT) else out
    }

    /**
     * What an announce pass does with [entries] for [wallet] (iOS `announceNamedHiddenRequests`):
     * [isRequest] says whether the address's chat is still an unanswered, unblocked request (null:
     * not loaded yet, kept for the next pass); [label] is its known .kachat name. Returns the
     * entries left and the (address, label) pairs to ring.
     */
    internal fun plan(
        entries: List<String>,
        wallet: String,
        isRequest: (String) -> Boolean?,
        label: (String) -> String?,
    ): Pair<List<String>, List<Pair<String, String>>> {
        val prefix = wallet.trim().lowercase() + "|"
        val remaining = ArrayList<String>(entries.size)
        val ring = ArrayList<Pair<String, String>>()
        for (entry in entries) {
            if (!entry.startsWith(prefix)) {
                remaining += entry
                continue
            }
            val address = entry.removePrefix(prefix)
            when (isRequest(address)) {
                null -> remaining += entry // not loaded yet: asked again on the next change
                false -> Unit // accepted, blocked or no longer a request: nothing to announce
                true -> {
                    val name = label(address)?.takeIf { it.isNotEmpty() }
                    if (name == null) remaining += entry else ring += address to name
                }
            }
        }
        return remaining to ring
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<String> = runCatching {
        prefs(context).getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }
    }.getOrNull().orEmpty()

    private fun save(context: Context, entries: List<String>) {
        runCatching { prefs(context).edit().putString(KEY, entries.joinToString("\n")).apply() }
    }

    fun add(context: Context, wallet: String, address: String) {
        synchronized(lock) {
            val current = all(context)
            val next = adding(current, entry(wallet, address))
            if (next != current) save(context, next)
        }
    }

    fun remove(context: Context, wallet: String, address: String) {
        synchronized(lock) {
            val current = all(context)
            val e = entry(wallet, address)
            if (e in current) save(context, current - e)
        }
    }

    fun hasAny(context: Context, wallet: String): Boolean {
        val prefix = wallet.trim().lowercase() + "|"
        return all(context).any { it.startsWith(prefix) }
    }

    /** Takes [entries] off the list (an announce pass's result), keeping anything filed since. */
    fun removeEntries(context: Context, entries: Collection<String>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val current = all(context)
            val next = current - entries.toSet()
            if (next.size != current.size) save(context, next)
        }
    }

    /** Delete Account (iOS `clearAllSharedData` removes `chat_hidden_request_addresses`). */
    fun clear(context: Context) {
        synchronized(lock) { runCatching { prefs(context).edit().clear().apply() } }
    }
}
