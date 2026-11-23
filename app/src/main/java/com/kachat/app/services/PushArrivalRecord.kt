package com.kachat.app.services

import android.content.Context
import com.google.firebase.messaging.RemoteMessage

/**
 * Every push that reached this phone in the last 30 minutes (iOS NotificationService
 * `recordPushArrival`, App Group `push_arrivals_v1`, owner 2026-11-22), so the app never banners
 * the same message, payment or action again once it is open: chats, groups, rooms, KaPost and
 * Received KAS all check [arrived] first (iOS `InAppBanner.pushArrived`).
 *
 * On Android the push you tapped to open the app has left the shade, so [NotificationHelper.isShowing]
 * can't see it, and a push the system drew while the app was away (rooms, KaPost, Received KAS
 * carry a `notification` block) never reached `onMessageReceived`. So the FCM service records
 * every push as it comes in, before the system or the app draws it
 * (`KaChatFirebaseMessagingService.handleIntent`).
 *
 * Keys, lowercased: the push's `tx_id` (chats, groups, payments, Received KAS), its collapse id /
 * notification tag (a room push's is the message's txid; iOS's request id), and for KaPost
 * `kaposts:<actor>|<post>|<kind>` as [kaPostsKey] writes it, which the poller's own key matches.
 * Newest [LIMIT] kept. Cleared with the account (AccountFeatureCleanup).
 */
object PushArrivalRecord {
    private const val PREFS = "kachat_push_arrivals"
    private const val KEY = "push_arrivals_v1"
    const val WINDOW_MS = 30 * 60 * 1000L
    const val LIMIT = 500
    private const val KAPOSTS_PREFIX = "kaposts:"

    /** Records [message]; true when a push with one of the same keys already came (a KaPost
     *  tip's payment push and its "tipped your post" push share the tip's txid). */
    @Synchronized
    fun record(context: Context, message: RemoteMessage, nowMs: Long = System.currentTimeMillis()): Boolean {
        val keys = keysFor(
            data = message.data,
            tag = message.notification?.tag,
            collapseKey = message.collapseKey?.takeIf { it != context.packageName },
        )
        if (keys.isEmpty()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (ledger, seen) = recorded(decode(prefs.getString(KEY, null)), keys, nowMs)
        prefs.edit().putString(KEY, encode(ledger)).apply()
        return seen
    }

    /** Whether a push for [key] (a txid, a room push's collapse id, or `kaposts:` + [kaPostsKey])
     *  reached the phone in the last 30 minutes. */
    fun arrived(context: Context, key: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lowered = key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val json = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }.getOrNull()
        return arrived(decode(json), lowered, nowMs)
    }

    /** Delete Account (iOS `clearAllSharedData` removes `push_arrivals_v1`). */
    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // Pure rules (tested)

    /** The keys a push is recorded under (iOS `recordPushArrival`). */
    internal fun keysFor(data: Map<String, String>, tag: String?, collapseKey: String?): List<String> {
        val keys = mutableListOf<String>()
        tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(keys::add)
        collapseKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(keys::add)
        data["tx_id"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(keys::add)
        val threadId = data["thread_id"].orEmpty()
        if (threadId.startsWith("kaposts") || data["type"] == "kaposts") {
            val postId = POST_ID_KEYS.firstNotNullOfOrNull { data[it]?.takeIf { v -> v.isNotBlank() } }
            keys.add(KAPOSTS_PREFIX + kaPostsKey(data["actor_pubkey"], postId, kaPostsKind(data["kaposts_kind"], data["body"].orEmpty())))
        }
        return keys.distinct()
    }

    /** [ledger] past the window dropped, with [keys] stamped [nowMs] and only the newest [LIMIT]
     *  kept; and whether any of [keys] was there already. */
    internal fun recorded(ledger: Map<String, Long>, keys: List<String>, nowMs: Long): Pair<Map<String, Long>, Boolean> {
        val live = ledger.filter { nowMs - it.value < WINDOW_MS }.toMutableMap()
        val wanted = keys.map { it.lowercase() }.filter { it.isNotEmpty() }
        val seen = wanted.any { live.containsKey(it) }
        for (key in wanted) live[key] = nowMs
        val kept = if (live.size > LIMIT) {
            live.entries.sortedByDescending { it.value }.take(LIMIT).associate { it.key to it.value }
        } else {
            live
        }
        return kept to seen
    }

    internal fun arrived(ledger: Map<String, Long>, key: String, nowMs: Long): Boolean {
        val at = ledger[key.lowercase()] ?: return false
        return nowMs - at < WINDOW_MS
    }

    /** iOS `KaPostsBannerLedger.key`: `<actor>|<post>|<kind>`, a compressed key as its x-only form. */
    fun kaPostsKey(actorPubkey: String?, postId: String?, kind: String): String {
        var actor = actorPubkey.orEmpty().trim().lowercase()
        if (actor.length == 66) actor = actor.drop(2)
        return "$actor|${postId.orEmpty().trim().lowercase()}|$kind"
    }

    /** iOS `KaPostsBannerLedger.kind(pushKind:body:)`: a push's `kaposts_kind`, else its wording. */
    fun kaPostsKind(pushKind: String?, body: String): String {
        when (pushKind) {
            "vote_up" -> return "like"
            "vote_down" -> return "dislike"
            "reply", "quote", "repost", "follow", "mention", "tip" -> return pushKind
        }
        val lowered = body.lowercase()
        return when {
            lowered.contains("disliked your") -> "dislike"
            lowered.contains("liked your") -> "like"
            lowered.contains("replied to your") -> "reply"
            lowered.contains("quoted your") -> "quote"
            lowered.contains("reposted your") -> "repost"
            lowered.contains("followed you") -> "follow"
            lowered.contains("mentioned you") -> "mention"
            lowered.contains("tipped your") -> "tip"
            else -> "other"
        }
    }

    /** iOS `KaPostsBannerLedger.kind(contentType:voteType:hasText:)`: the kind of a poll's row. */
    fun kaPostsKind(contentType: String?, voteType: String?, hasText: Boolean): String = when (contentType) {
        "vote" -> if (voteType == "downvote") "dislike" else "like"
        "tip" -> "tip"
        "reply" -> "reply"
        "quote" -> if (hasText) "quote" else "repost"
        "follow" -> "follow"
        "mention" -> "mention"
        else -> "other"
    }

    /** Every spelling a KaPost push has used for the acted-on post (iOS reads `postId`). */
    private val POST_ID_KEYS = listOf("postId", "post_id", "content_id")

    private fun decode(json: String?): Map<String, Long> {
        if (json.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(json)
            val out = HashMap<String, Long>(obj.length())
            for (key in obj.keys()) out[key] = obj.optLong(key, 0L)
            out
        }.getOrElse { emptyMap() }
    }

    private fun encode(ledger: Map<String, Long>): String =
        org.json.JSONObject().apply { ledger.forEach { (k, v) -> put(k, v) } }.toString()
}

/**
 * The `.kachat` names this phone already knows (lowercased address -> "alice.kachat"), the newest
 * 500, so a KaPost push names the person who liked or replied even when they aren't a contact
 * (iOS `SharedDataManager.syncIdentityNamesForExtension`, App Group `shared_identity_names`, owner
 * 2026-11-22). Nothing is looked up for it: only names the app has already seen, written with the
 * registry's cached identities ([com.kachat.app.services.kachatnames.KachatNamesRegistry.knownIdentityNames]),
 * readable by a push handler in a process that has only just started. Cleared with the account.
 */
object KnownKachatNames {
    private const val PREFS = "kachat_known_identity_names"
    private const val KEY = "shared_identity_names"
    const val LIMIT = 500

    @Volatile private var cached: Map<String, String>? = null

    fun write(context: Context, names: Map<String, String>) {
        val json = org.json.JSONObject().apply { names.forEach { (k, v) -> put(k.lowercase(), v) } }.toString()
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        }
        cached = names.mapKeys { it.key.lowercase() }
    }

    /** The cached `.kachat` name for [address], or null. */
    fun name(context: Context, address: String): String? {
        val names = cached ?: runCatching {
            val json = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            val obj = org.json.JSONObject(json ?: "{}")
            buildMap { for (key in obj.keys()) put(key, obj.optString(key)) }
        }.getOrElse { emptyMap() }.also { cached = it }
        return names[address.trim().lowercase()]?.takeIf { it.isNotBlank() }
    }

    /** Delete Account (iOS `clearAllSharedData` removes `shared_identity_names`). */
    fun clear(context: Context) {
        cached = emptyMap()
        runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
