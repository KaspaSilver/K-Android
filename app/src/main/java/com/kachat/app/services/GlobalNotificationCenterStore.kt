package com.kachat.app.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bell on the Profile screen holds Kaspa arriving in one of your own addresses - the chatting
 * address or a spending address (fed by [AddressActivityNotifier]; iOS 86471dd) - tokens received,
 * every @mention of you in a public chat or a group ([recordMention], iOS d089011f), and your
 * .kachat names' events ([com.kachat.app.services.kachatnames.KachatNamesNotifier]: renewal open,
 * expired, freed, offers, sales; back in the bell since iOS b49a021d, owner 2026-11-27: "make sure
 * no one misses it"). Group chats keep their own unread counts in the Chats tab (iOS cda2715). Entries are account-scoped,
 * persisted, deduped by id and capped; opening the list marks everything seen.
 */
@Singleton
class GlobalNotificationCenterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
) {
    data class Entry(
        val id: String,
        /** "wallet", "token", "mention" or "kachat" ([IN_BELL]) - older builds also stored
         *  "kaposts", "group" and "broadcast" rows. */
        val source: String,
        val title: String,
        val body: String,
        val timestampMs: Long,
        /** group id / channel name / post txid / .kachat name / the own address a wallet receipt
         *  hit (iOS 95e2cba) / "room:<name>|<txid>" or "group:<id>|<txid>" for a mention
         *  ([parseMentionTarget]) - what tapping the row relates to. Null on wallet rows saved before
         *  receipts carried their address; those open Portfolio. */
        val targetId: String?,
    )

    private val prefs = context.getSharedPreferences("global_notification_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    private val maxEntries = 100

    /** The account [entries] were loaded for. The feed is read and saved under this address, not
     *  whatever wallet is current at write time, so one account's rows never land under another's
     *  key. Null while no one is signed in: nothing is shown and nothing is saved (iOS 428a797b
     *  `loadedAddress`, audit 2026-11-21 #8). */
    private var loadedWallet: String? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** The coalesced save ([scheduleSave]): a room's backfill records many rows in a row, and
     *  encoding and writing the list for each one was the cost (iOS audit 2026-11-27 P9). */
    private var pendingSave: kotlinx.coroutines.Job? = null

    init {
        // Log Out, sign-in, import and account switches all change the signed-in account.
        // Reloading only when a screen asked left the last account's rows in the bell, and the
        // next account's first notification saved them under its own key for good (iOS 428a797b,
        // as KaPostsUnseenStore).
        scope.launch {
            walletManager.signedInAddressFlow.collect { address -> load(address) }
        }
    }

    private fun walletAddressOrNull(): String? =
        try { walletManager.getAddress() } catch (_: Exception) { null }

    /** The signed-in account's address; null while signed out. */
    private fun signedInAddressOrNull(): String? = if (walletManager.isLoggedOut) null else walletAddressOrNull()

    private fun entriesKey(wallet: String) = "entries_$wallet"
    private fun seenKey(wallet: String) = "seen_$wallet"

    /** Loads the signed-in wallet's feed (account switches reload by themselves, see init). */
    @Synchronized
    fun reloadIfNeeded() {
        load(signedInAddressOrNull())
    }

    @Synchronized
    private fun load(wallet: String?) {
        if (loadedWallet == wallet) return
        // the leaving account's rows are saved under its own key first
        flushPendingSave()
        loadedWallet = wallet
        if (wallet == null) {
            // signed out: the bell is empty, never the last account's
            _entries.value = emptyList()
            _lastSeenAt.value = 0L
            return
        }
        val loaded = try {
            gson.fromJson(prefs.getString(entriesKey(wallet), null) ?: "[]", listType) ?: emptyList()
        } catch (_: Exception) { emptyList<Entry>() }
        // Wallet rows, mentions and .kachat name events only ([IN_BELL]). KaPosts and Public
        // Chats' other rows are gone, as are the "group" rows an older build saved; anything else
        // is dropped here.
        // newest first: [record] inserts in place and relies on it
        val kept = loaded.filter { it.source in IN_BELL }.sortedByDescending { it.timestampMs }
        _entries.value = kept
        _lastSeenAt.value = prefs.getLong(seenKey(wallet), 0L)
        if (kept.size != loaded.size) persist()
    }

    private fun persist() {
        pendingSave?.cancel()
        pendingSave = null
        val wallet = loadedWallet ?: return
        prefs.edit().putString(entriesKey(wallet), gson.toJson(_entries.value.take(maxEntries))).apply()
    }

    @Synchronized
    fun record(id: String, source: String, title: String, body: String, timestampMs: Long, targetId: String?) {
        // Only what the bell lists. Refused here rather than merely left uncalled, so a future
        // caller cannot quietly put anything else back in the bell.
        if (source !in IN_BELL) return
        reloadIfNeeded()
        if (loadedWallet == null) return
        if (id.isEmpty() || _entries.value.any { it.id == id }) return
        val current = _entries.value
        val updated = withEntry(current, Entry(id, source, title, body, timestampMs, targetId), maxEntries)
        // older than everything in a full list: the trim would drop it at once
        if (updated === current) return
        _entries.value = updated
        scheduleSave()
    }

    /** Saves within a second, once for any number of rows recorded meanwhile. The account it
     *  saves for can't change under it: an account switch flushes first ([load]). */
    @Synchronized
    private fun scheduleSave() {
        if (pendingSave != null) return
        pendingSave = scope.launch {
            kotlinx.coroutines.delay(SAVE_DELAY_MS)
            synchronized(this@GlobalNotificationCenterStore) {
                if (pendingSave?.isCancelled == true) return@synchronized
                pendingSave = null
                persist()
            }
        }
    }

    /** A save still waiting goes out now: the app is going to the background (it may be ended
     *  there), or the account is changing (iOS audit 2026-11-27 P9). */
    @Synchronized
    fun flushPendingSave() {
        if (pendingSave == null) return
        persist()
    }

    /** A room message deleted by its sender: its mention row goes too (iOS audit 2026-11-27 L10). */
    @Synchronized
    fun removeMention(txId: String) {
        if (loadedWallet == null || txId.isEmpty()) return
        val id = mentionId(txId)
        if (_entries.value.none { it.id == id }) return
        _entries.value = _entries.value.filterNot { it.id == id }
        scheduleSave()
    }

    fun markAllSeen() {
        val wallet = loadedWallet ?: return
        _lastSeenAt.value = System.currentTimeMillis()
        prefs.edit().putLong(seenKey(wallet), _lastSeenAt.value).apply()
    }

    @Synchronized
    fun clearAll() {
        if (loadedWallet == null) return
        _entries.value = emptyList()
        persist()
    }

    /** Delete Account: one wallet's feed, from this device (iOS d063b897). */
    @Synchronized
    fun removeAllData(walletAddress: String) {
        prefs.edit().remove(entriesKey(walletAddress)).remove(seenKey(walletAddress)).apply()
        if (loadedWallet == walletAddress) {
            // a save still waiting would write the feed back
            pendingSave?.cancel()
            pendingSave = null
            loadedWallet = null
            _entries.value = emptyList()
            _lastSeenAt.value = 0L
        }
    }

    // MARK: - @mentions (iOS d089011f `recordMention`)

    /**
     * Someone @'d you in a room or a group: a row in the bell (never a banner of its own; the
     * room's or group's own rules decide that), whatever the room's bell or the group's setting
     * (owner, 2026-11-25). One row per message. [place] is "#room" or the group's name; [target]
     * is "room:<name>" or "group:<id>", and the row keeps "<target>|<txId>".
     */
    fun recordMention(sender: String, place: String, preview: String, txId: String, blockTimeMs: Long, target: String) {
        if (txId.isEmpty()) return
        val title = runCatching { com.kachat.app.util.tr(com.kachat.app.R.string.kp55_mentioned_you_in, sender, place) }
            .getOrElse { context.getString(com.kachat.app.R.string.kp55_mentioned_you_in, sender, place) }
        record(mentionId(txId), SOURCE_MENTION, title, preview, blockTimeMs, "$target|$txId")
    }

    companion object {
        private const val TAG = "GlobalNotifCenter"

        /** How long a recorded row waits for others before the feed is saved (iOS P9: 1 s). */
        private const val SAVE_DELAY_MS = 1_000L

        /**
         * [entries] (newest first) with [entry] added in its place by when it happened, then cut
         * to [max]: the trim drops the oldest rows, not whatever came in first (iOS audit
         * 2026-11-27 M8: a room's backfill pushed out yesterday's rows). Put ahead of rows with the
         * same time, as the old insert-then-sort did, instead of re-sorting the list for every
         * row (P9). An entry older than everything in a full list returns [entries] itself (the
         * trim would drop it at once). Pure, for tests.
         */
        fun withEntry(entries: List<Entry>, entry: Entry, max: Int): List<Entry> {
            val found = entries.indexOfFirst { it.timestampMs <= entry.timestampMs }
            val index = if (found < 0) entries.size else found
            if (index >= max) return entries
            val updated = ArrayList<Entry>(minOf(entries.size + 1, max))
            updated.addAll(entries.subList(0, index))
            updated.add(entry)
            updated.addAll(entries.subList(index, minOf(entries.size, max - 1)))
            return updated
        }

        /** What the notifications list holds (iOS `Entry.Source.inBell`, 4500ebc): Kaspa received,
         *  and a token received ("token", iOS 5b7c772c; its targetId is the token's id, "kusdt",
         *  and a tap opens that token's Manage screen on the Wallet tab), and @mentions of you in
         *  a room or a group ("mention", iOS d089011f), and .kachat name events ("kachat", iOS
         *  b49a021d; its targetId is the bare name, and a tap opens it). */
        val IN_BELL = setOf("wallet", "token", SOURCE_MENTION, SOURCE_KACHAT)

        /** The source tag a row shows (iOS `Entry.Source.label`). */
        fun sourceLabel(source: String): String = when (source) {
            "wallet" -> "Wallet"
            "token" -> "Tokens"
            "kachat" -> ".kachat"
            "group" -> "Group"
            SOURCE_MENTION -> "Mention"
            else -> source
        }

        /** A .kachat name event (iOS `Entry.Source.kachat`). */
        const val SOURCE_KACHAT = "kachat"

        /** A room or group @mention of you (iOS `Entry.Source.mention`). */
        const val SOURCE_MENTION = "mention"

        /** One row per message (iOS "mention-<txid>"). */
        fun mentionId(txId: String): String = "mention-${txId.lowercase()}"

        /** Where a mention row goes: a room at a message, or a group (iOS `open(.mention)`). */
        sealed class MentionTarget {
            data class Room(val channel: String, val txId: String) : MentionTarget()
            data class Group(val groupId: String) : MentionTarget()
        }

        /** A mention row's targetId ("room:<name>|<txid>" / "group:<id>|<txid>"), or null. */
        fun parseMentionTarget(targetId: String?): MentionTarget? {
            val raw = targetId?.takeIf { it.isNotEmpty() } ?: return null
            val place = raw.substringBefore('|')
            val txId = if ('|' in raw) raw.substringAfter('|') else ""
            return when {
                place.startsWith("room:") -> place.removePrefix("room:").takeIf { it.isNotEmpty() }?.let { MentionTarget.Room(it, txId) }
                place.startsWith("group:") -> place.removePrefix("group:").takeIf { it.isNotEmpty() }?.let { MentionTarget.Group(it) }
                else -> null
            }
        }

        /** An @mention token in a message: "@bob" or "@bob.kachat". */
        val MENTION_TOKEN_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]+(?:\\.[a-z0-9-]+)*)", RegexOption.IGNORE_CASE)
    }
}
