package com.kachat.app.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface KaPostTipLedgerDeps {
    fun networkService(): NetworkService
}

/**
 * Which chat payments are KaPost tips (`kchat:1:tip:`), and the post each one tipped (iOS
 * `KaPostTipLedger`, 2026-11-22): the chat card says "Received tip" / "Sent tip" and a tap opens
 * that post. Filled when a tip is sent ([ChatPaymentSender]), when a tip's push comes
 * ([KaChatFirebaseMessagingService]: the payment push read off chain, or the KaPost "tipped your
 * post" push naming the post), when the payment sync reads a tip, and otherwise by reading the
 * payment's transaction once ([tipPostId]). Not-a-tip answers are remembered too, so each payment
 * is read at most once. iOS keeps the map in the App Group (`kapost_tips_v1`) for its notification
 * extension; Android has no extension process, so it is ordinary app storage under the same keys.
 * Delete Account clears it ([AccountFeatureCleanup]).
 */
object KaPostTipLedger {
    private const val TAG = "KaPostTipLedger"
    private const val PREFS = "kapost_tips"
    /** tip payment txid -> tipped post's txid (iOS `KaPostTipLedger.sharedKey`). */
    const val TIPS_KEY = "kapost_tips_v1"
    /** Payments read and found not to be tips (iOS `notTipKey`). */
    private const val NOT_TIP_KEY = "kapost_tips_none_v1"
    private const val LIMIT = 2000
    /** How long one read of a payment's transaction may take (iOS fetchKaspaTransaction). */
    private const val READ_TIMEOUT_MS = 8_000L

    private const val TIP_PREFIX = "kchat:1:tip:"

    /** Bumped when an answer lands, so cards re-render (iOS `revision`). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val lock = Any()
    private var tips: LinkedHashMap<String, String>? = null
    private var notTip: LinkedHashSet<String>? = null
    private val resolving = HashSet<String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadedTips(context: Context): LinkedHashMap<String, String> {
        tips?.let { return it }
        val map = LinkedHashMap<String, String>()
        runCatching {
            prefs(context).getString(TIPS_KEY, null)?.let { json ->
                val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                for ((k, v) in obj.entrySet()) map[k] = v.asString
            }
        }
        tips = map
        return map
    }

    private fun loadedNotTip(context: Context): LinkedHashSet<String> {
        notTip?.let { return it }
        val set = LinkedHashSet<String>()
        runCatching {
            prefs(context).getString(NOT_TIP_KEY, null)?.let { json ->
                com.google.gson.JsonParser.parseString(json).asJsonArray.forEach { set.add(it.asString) }
            }
        }
        notTip = set
        return set
    }

    /** The tipped post's txid, when this payment is a known tip. */
    fun postId(context: Context, txId: String): String? = synchronized(lock) {
        loadedTips(context)[txId.lowercase()]
    }

    fun record(context: Context, txId: String, postId: String) {
        val key = txId.lowercase()
        val post = postId.lowercase()
        if (key.isEmpty() || post.isEmpty()) return
        synchronized(lock) {
            val all = loadedTips(context)
            if (all[key] == post) return
            all[key] = post
            while (all.size > LIMIT) all.remove(all.keys.first())
            val obj = com.google.gson.JsonObject()
            for ((k, v) in all) obj.addProperty(k, v)
            prefs(context).edit().putString(TIPS_KEY, obj.toString()).apply()
        }
        _revision.value = _revision.value + 1
    }

    /**
     * The tipped post's txid, reading the payment's transaction when it isn't known yet; null for
     * a payment that isn't a tip, or can't be read right now (asked again next time). Pending
     * sends (no txid yet) are skipped.
     */
    suspend fun tipPostId(context: Context, txId: String): String? {
        val key = txId.lowercase()
        synchronized(lock) {
            loadedTips(context)[key]?.let { return it }
            if (!isTxId(key) || key in loadedNotTip(context) || key in resolving) return null
            resolving.add(key)
        }
        val payload: String?
        try {
            payload = readPayload(context, key) ?: return null // not readable now
        } finally {
            synchronized(lock) { resolving.remove(key) }
        }
        tipPostIdFromPayloadHex(payload)?.let { postId ->
            record(context, key, postId)
            return postId
        }
        synchronized(lock) {
            val set = loadedNotTip(context)
            set.add(key)
            while (set.size > LIMIT) set.remove(set.first())
            val arr = com.google.gson.JsonArray()
            set.forEach { arr.add(it) }
            prefs(context).edit().putString(NOT_TIP_KEY, arr.toString()).apply()
        }
        return null
    }

    /** The payment's payload hex ("" for none), or null when the REST API didn't answer. */
    private suspend fun readPayload(context: Context, txId: String): String? = try {
        val network = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, KaPostTipLedgerDeps::class.java)
            .networkService()
        val api = network.kaspaRestApi.value
            ?: withTimeoutOrNull(5_000) { network.kaspaRestApi.filterNotNull().first() }
        api?.let { withTimeoutOrNull(READ_TIMEOUT_MS) { it.getTransactionPayload(txId) } }?.let { it.payload.orEmpty() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Payment ${txId.take(12)} not readable now: ${e.message}")
        null
    }

    /** Delete Account (iOS `clear`). */
    fun clear(context: Context) {
        synchronized(lock) {
            prefs(context).edit().remove(TIPS_KEY).remove(NOT_TIP_KEY).apply()
            tips = LinkedHashMap()
            notTip = LinkedHashSet()
        }
        _revision.value = _revision.value + 1
    }

    // MARK: - Pure parsing (unit-tested)

    private fun isTxId(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * The tipped post's txid when a transaction's payload (REST hex) is a KaPost tip:
     * `kchat:1:tip:<pubkey>:<signature>:<post_id>:<author_pubkey>:<sealed_hex>` (iOS
     * NotificationService `tipPostId(payloadHex:)`).
     */
    fun tipPostIdFromPayloadHex(payloadHex: String): String? {
        if (payloadHex.isEmpty() || payloadHex.length % 2 != 0) return null
        val bytes = try {
            ByteArray(payloadHex.length / 2) { i -> payloadHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            return null
        }
        return tipPostIdFromPayload(bytes)
    }

    /** [tipPostIdFromPayloadHex] for payload bytes already in hand (the payment sync's). */
    fun tipPostIdFromPayload(bytes: ByteArray): String? {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (!text.startsWith(TIP_PREFIX)) return null
        val fields = text.substring(TIP_PREFIX.length).split(":")
        if (fields.size != 5 || fields[4].isEmpty() || fields[2].length != 64) return null
        return fields[2].lowercase()
    }

    /** A push's `amount` (sompi, as text) in KAS without trailing zeros (iOS `kasText`). */
    fun kasText(amount: String?): String? {
        val text = amount?.trim().orEmpty()
        val sompi = text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: return null
        if (sompi <= 0) return null
        return String.format(java.util.Locale.US, "%.8f", sompi / 100_000_000.0).trimEnd('0').trimEnd('.')
    }

    /** A KaPost push's kind from its `kaposts_kind`, else its body (iOS NotificationService
     *  `kaPostsKind(pushKind:body:)`, the app's `KaPostsBannerLedger.kind`). */
    fun kaPostsPushKind(pushKind: String?, body: String): String {
        when (pushKind) {
            "vote_up" -> return "like"
            "vote_down" -> return "dislike"
            "reply", "quote", "repost", "follow", "mention", "tip" -> return pushKind
        }
        val lowered = body.lowercase()
        return when {
            "disliked your" in lowered -> "dislike"
            "liked your" in lowered -> "like"
            "replied to your" in lowered -> "reply"
            "quoted your" in lowered -> "quote"
            "reposted your" in lowered -> "repost"
            "followed you" in lowered -> "follow"
            "mentioned you" in lowered -> "mention"
            "tipped your" in lowered -> "tip"
            else -> "other"
        }
    }

    /** The amount in a KaPost tip push's body: "alice tipped your post: 0.2 KAS" -> "0.2" (iOS
     *  `applyKaPostsTip`). */
    fun amountFromTipPushBody(body: String): String? {
        val token = body.split(": ").last().split(" ").first()
        return token.takeIf { it.toDoubleOrNull() != null }
    }
}
