package com.kachat.app.services.tokens

import android.content.Context
import android.util.Log
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.NodePoolManager
import com.kachat.app.services.UtxoEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads an address's kUSDT the way kusdt-open's wallet does (`wallet/extension/src/kusdt/verify.ts`;
 * iOS `KusdtBalances.swift`, 5b7c772c): Trestly's API says which coins the owner has
 * (`GET /tokens?owner=`, SPEC section 13), and each one is believed only once the node shows it:
 *   1. the state is a plain coin of this address's key ([Kusdt.State.isPlainOf]),
 *   2. its locking script is recomputed here from the state and the pinned template,
 *   3. the node holds that exact outpoint at that script, carrying kUSDT's covenant id.
 * Anything else is dropped. Only the confirmed UTXO set counts (a coin still in the mempool shows
 * once it's accepted). The API learns the address asked about; nothing else leaves the phone.
 *
 * Every function here does network I/O: call from a coroutine (it moves to [Dispatchers.IO]).
 */
@Singleton
class KusdtBalances @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    private val nodePool: NodePoolManager,
) {
    class Coin(
        val transactionId: String,
        val index: Int,
        val state: Kusdt.State,
        /** The KAS the coin carries (1.5 KAS for a new one). */
        val kasSompi: Long,
        /** The DAA score of the block that accepted it (the builder takes it with the coin). */
        val blockDaaScore: Long = 0L,
    ) {
        /** "txid:index", lowercase. */
        val outpointKey: String get() = "$transactionId:$index"

        override fun equals(other: Any?): Boolean = other is Coin && other.transactionId == transactionId &&
            other.index == index && other.state == state && other.kasSompi == kasSompi && other.blockDaaScore == blockDaaScore

        override fun hashCode(): Int = outpointKey.hashCode()
    }

    /** A coin this phone expects at the address (a send from here made it) that Trestly may not
     *  list yet: checked on the node like the listed ones, so it counts as soon as the node has it. */
    class Expected(val transactionId: String, val index: Int, val state: Kusdt.State) {
        val outpointKey: String get() = "$transactionId:$index"
    }

    class AddressResult(
        val address: String,
        val coins: List<Coin>,
        /** The outpoints ("txid:index") Trestly listed itself, so an expected coin can be let go. */
        val listedByApi: Set<String> = emptySet(),
        /** Coins the API lists at an address the node can't list (too many coins there): neither
         *  shown nor taken as gone. */
        val uncheckedCount: Int,
    ) {
        val units: Long get() = coins.sumOf { it.state.amount }
    }

    /** Why a lookup failed; [message] is the text the screens show. */
    sealed class Failure(message: String) : Exception(message) {
        class TemplateMissing(message: String) : Failure(message)
        class Api(message: String) : Failure(message)
    }

    /**
     * Trestly's lookups go through their own client: no cookies, no cache, nothing kept on disk,
     * so nothing ties one lookup to the next beyond the address asked about (KaPay's audit,
     * 2026-11-20). Its own connection pool too, never the app's shared client.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** The address's verified kUSDT coins. An address that isn't a Schnorr key address holds none
     *  KaChat can use, and isn't asked about. Throws [Failure] (or an I/O error) when Trestly or
     *  the node can't answer. */
    suspend fun fetch(address: String, expected: List<Expected> = emptyList()): AddressResult = withContext(Dispatchers.IO) {
        val owner = Kusdt.schnorrOwner(address) ?: return@withContext AddressResult(address, emptyList(), uncheckedCount = 0)
        val template = Kusdt.template(context)
            ?: throw Failure.TemplateMissing(context.getString(R.string.kusdt_template_missing))
        val deployment = Kusdt.current
        val answer = tokens(address)

        // 1 and 2: plain coins of this key, with their scripts and addresses worked out here
        class Wanted(val txid: String, val index: Int, val state: Kusdt.State, val script: ByteArray, val address: String)
        val wanted = ArrayList<Wanted>()
        // one entry per outpoint: an answer listing a coin twice must not count it twice
        val seen = HashSet<String>()
        for (entry in answer.states) {
            val state = entry.state ?: continue
            if (!state.isPlainOf(owner) || !seen.add("${entry.txid}:${entry.index}")) continue
            val script = Kusdt.lockingScript(state, template)
            val coinAddress = Kusdt.address(script) ?: continue
            wanted += Wanted(entry.txid, entry.index, state, script, coinAddress)
        }
        val listedByApi = wanted.map { "${it.txid}:${it.index}" }.toSet()
        for (e in expected) {
            val key = "${e.transactionId}:${e.index}"
            if (key in listedByApi || !e.state.isPlainOf(owner) || !seen.add(key)) continue
            val script = Kusdt.lockingScript(e.state, template)
            val coinAddress = Kusdt.address(script) ?: continue
            wanted += Wanted(e.transactionId, e.index, e.state, script, coinAddress)
        }
        if (wanted.isEmpty()) return@withContext AddressResult(address, emptyList(), uncheckedCount = answer.unreadCount)

        // 3: the node, one read for every address (never REST: it carries no covenant id)
        val utxos = nodePool.getUtxosByAddressesFromNode(wanted.map { it.address }.distinct())
        val covenant = deployment.covenantIdHex
        val coins = ArrayList<Coin>()
        for (w in wanted) {
            val u = utxos.firstOrNull { it.outpoint.transactionId.lowercase() == w.txid && it.outpoint.index == w.index } ?: continue
            if (!u.utxoEntry.scriptPublicKey.scriptPublicKey.equals(Kusdt.hex(w.script), ignoreCase = true)) continue
            if (u.utxoEntry.covenantId?.lowercase() != covenant) continue
            coins += Coin(w.txid, w.index, w.state, u.utxoEntry.amount, u.utxoEntry.blockDaaScore)
        }
        if (coins.size < wanted.size) {
            Log.i(TAG, "${wanted.size - coins.size} of ${wanted.size} listed coins weren't on the node as listed: not shown")
        }
        AddressResult(address, coins, listedByApi, answer.unreadCount)
    }

    // MARK: Trestly's API

    internal class Listed(val txid: String, val index: Int, val state: Kusdt.State?)

    internal class TokensAnswer(val states: List<Listed>, val unreadCount: Int)

    private suspend fun tokens(owner: String): TokensAnswer {
        val base = settings.trestlyApiUrl.first().trim()
        val url = tokensUrl(base, owner)
            ?: throw Failure.Api(context.getString(R.string.trestly_url_invalid))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Failure.Api(context.getString(R.string.trestly_http_status, response.code))
            }
            val body = response.body ?: throw Failure.Api(context.getString(R.string.trestly_no_answer))
            // the biggest honest answer (500 coins with their states) is well under this
            val declared = body.contentLength()
            if (declared > MAX_ANSWER_BYTES) throw Failure.Api(context.getString(R.string.trestly_answer_unreadable))
            val bytes = body.byteStream().use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_ANSWER_BYTES) throw Failure.Api(context.getString(R.string.trestly_answer_unreadable))
                }
                out.toByteArray()
            }
            return parseTokensAnswer(bytes.toString(Charsets.UTF_8))
                ?: throw Failure.Api(context.getString(R.string.trestly_answer_unreadable))
        }
    }

    companion object {
        private const val TAG = "kUSDT"
        internal const val MAX_ANSWER_BYTES = 4 * 1024 * 1024

        /** `<base>/tokens?owner=<address>`, https only; null for anything else. */
        internal fun tokensUrl(base: String, owner: String): okhttp3.HttpUrl? {
            val trimmed = base.trim().removeSuffix("/")
            if (!trimmed.lowercase().startsWith("https://")) return null
            val parsed = "$trimmed/tokens".toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https") return null
            return parsed.newBuilder().addQueryParameter("owner", owner).build()
        }

        /** Trestly's `/tokens` answer: its `states` (each a coin and its state) and how many it
         *  couldn't read (`unread`). Null when it isn't a JSON object. */
        internal fun parseTokensAnswer(text: String): TokensAnswer? {
            val json = try { JSONObject(text) } catch (e: Exception) { return null }
            val states = json.optJSONArray("states") ?: JSONArray()
            val listed = ArrayList<Listed>()
            for (i in 0 until states.length()) {
                val entry = states.optJSONObject(i) ?: continue
                listed(entry)?.let { listed += it }
            }
            val unread = json.optJSONArray("unread")?.length() ?: 0
            return TokensAnswer(listed, unread)
        }

        private fun listed(entry: JSONObject): Listed? {
            val utxo = entry.optJSONObject("utxo") ?: return null
            val txid = (utxo.opt("transactionId") as? String)?.lowercase() ?: return null
            if (txid.length != 64 || Kusdt.unhexOrNull(txid) == null) return null
            val index = natural(utxo.opt("index")) ?: return null
            if (index > 0xFFFF_FFFFL) return null
            // an index past Int's range can't be spent by this app anyway
            if (index > Int.MAX_VALUE) return null
            return Listed(txid, index.toInt(), entry.optJSONObject("state")?.let(::state))
        }

        /** A state as the API writes it (kusdt-open `parseTokenState`): hex32 fields, small ints,
         *  the amount as a JSON integer or a string of digits. */
        internal fun state(s: JSONObject): Kusdt.State? {
            val amount = natural(s.opt("amount")) ?: return null
            val owner = hex32(s.opt("owner")) ?: return null
            val scheme = natural(s.opt("ownerScheme")) ?: return null
            if (scheme > 255) return null
            val borrow = if (s.has("borrowScheme")) natural(s.opt("borrowScheme")) ?: 256L else 0L
            if (borrow > 255) return null
            val guard = if (s.has("borrowGuard")) hex32(s.opt("borrowGuard")) ?: return null else ByteArray(32)
            val ext = if (s.has("extensionCommitment")) hex32(s.opt("extensionCommitment")) ?: return null else ByteArray(32)
            return Kusdt.State(amount, owner, scheme.toInt(), borrow.toInt(), guard, ext)
        }

        private fun hex32(v: Any?): ByteArray? {
            val s = v as? String ?: return null
            if (s.length != 64) return null
            return Kusdt.unhexOrNull(s)
        }

        /** A non-negative integer under 2^63, from a JSON number or a string of ASCII digits (a
         *  fraction, a negative or a Boolean is no number). */
        internal fun natural(v: Any?): Long? = when (v) {
            is String -> if (v.isNotEmpty() && v.length <= 19 && v.all { it in '0'..'9' }) v.toLongOrNull() else null
            is Boolean -> null
            is Int -> v.toLong().takeIf { it >= 0 }
            is Long -> v.takeIf { it >= 0 }
            is Number -> {
                // org.json reads a big or fractional number as Double or BigDecimal / BigInteger
                val d = v.toDouble()
                if (d >= 0 && d == Math.floor(d) && d < 9.007199254740992E15) d.toLong() else null
            }
            else -> null
        }
    }
}
