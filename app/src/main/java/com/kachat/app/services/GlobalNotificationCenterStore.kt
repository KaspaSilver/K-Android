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
 * and every @mention of you in a public chat or a group ([recordMention], iOS d089011f). .kachat
 * name news left with the .kachat market on 2026-10-24 (iOS d54ea87), and group chats otherwise
 * keep their own unread counts in the Chats tab (iOS cda2715). Entries are account-scoped,
 * persisted, deduped by id and capped; opening the list marks everything seen.
 */
@Singleton
class GlobalNotificationCenterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
) {
    data class Entry(
        val id: String,
        /** "wallet", "token" or "mention" ([IN_BELL]) - older builds also stored "kaposts",
         *  "group", "broadcast" and "kachat" rows. */
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

    init {
        // Log Out, sign-in, import and account switches all change the signed-in account.
        // Reloading only when a screen asked left the last account's rows in the bell, and the
        // next account's first notification saved them under its own key for good (iOS 428a797b,
        // as KaPostsUnseenStore).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
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
        // Wallet rows and mentions only ([IN_BELL]). KaPosts, Public Chats' other rows and the
        // .kachat market are gone, as are the "group" rows an older build saved; anything else is
        // dropped here.
        val kept = loaded.filter { it.source in IN_BELL }
        _entries.value = kept
        _lastSeenAt.value = prefs.getLong(seenKey(wallet), 0L)
        if (kept.size != loaded.size) persist()
    }

    private fun persist() {
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
        _entries.value = (listOf(Entry(id, source, title, body, timestampMs, targetId)) + _entries.value)
            .take(maxEntries)
        persist()
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

        /** What the notifications list holds (iOS `Entry.Source.inBell`, 4500ebc): Kaspa received,
         *  and a token received ("token", iOS 5b7c772c; its targetId is the token's id, "kusdt",
         *  and a tap opens that token's Manage screen on the Wallet tab), and @mentions of you in
         *  a room or a group ("mention", iOS d089011f). */
        val IN_BELL = setOf("wallet", "token", SOURCE_MENTION)

        /** The source tag a row shows (iOS `Entry.Source.label`). */
        fun sourceLabel(source: String): String = when (source) {
            "wallet" -> "Wallet"
            "token" -> "Tokens"
            "kachat" -> ".kachat"
            "group" -> "Group"
            SOURCE_MENTION -> "Mention"
            else -> source
        }

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
