package com.kachat.app.services.explorer

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.models.KachatNameLabel
import com.kachat.app.repository.PortfolioRepository
import com.kachat.app.services.NameServicesClient
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.tokens.Kusdt
import com.kachat.app.services.tokens.KusdtBalances
import com.kachat.app.util.KaPostsProtocol
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.capResponseBodies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Why a kaspa.stream call failed; the screens say it in words ([com.kachat.app.ui.screens.explorer]). */
sealed class ExplorerFailure(message: String) : Exception(message) {
    class NotFound : ExplorerFailure("not found")
    class Server(val code: Int) : ExplorerFailure("server $code")
    class BadAnswer : ExplorerFailure("bad answer")
}

/**
 * The Explorer's data (iOS 961fc05a `ExplorerAPI`, owner: "wire it up to kaspa.stream"):
 * kaspa.stream's REST API, the open-source kaspa-rest-server (the same one api.kaspa.org runs), at
 * api.kaspa.stream on mainnet and api-tn10.kaspa.stream on testnet-10. Transactions are labelled by
 * what they are in K from their payloads, here on the phone ([ExplorerParsing.classify]).
 */
@Singleton
class ExplorerApi @Inject constructor(
    private val nameServices: NameServicesClient,
    private val registry: KachatNamesRegistry,
    private val kusdtBalances: KusdtBalances,
    private val portfolioRepository: PortfolioRepository,
    private val liveProvider: Provider<ExplorerLive>,
) {
    private val live: ExplorerLive get() = liveProvider.get()

    val baseUrl: String
        get() = if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET) "https://api.kaspa.stream" else "https://api-tn10.kaspa.stream"

    /** Nothing kept between lookups: no cookies, no cache on disk. The biggest page K asks for (40
     *  transactions with their inputs) is well under the cap. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .capResponseBodies("kaspa.stream") { MAX_ANSWER_BYTES }
        .build()

    class Answer(val body: String, val nextPageBefore: String?)

    suspend fun request(path: String, query: List<Pair<String, String>> = emptyList()): Answer = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            for ((name, value) in query) addQueryParameter(name, value)
        }?.build() ?: throw ExplorerFailure.BadAnswer()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        call.execute().use { response ->
            if (response.code == 404) throw ExplorerFailure.NotFound()
            if (!response.isSuccessful) throw ExplorerFailure.Server(response.code)
            val body = try {
                response.body?.string() ?: throw ExplorerFailure.BadAnswer()
            } catch (e: com.kachat.app.util.ResponseTooLargeException) {
                throw ExplorerFailure.BadAnswer()
            }
            Answer(body, response.header("x-next-page-before"))
        }
    }

    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): JsonElement {
        val answer = request(path, query)
        return try {
            JsonParser.parseString(answer.body)
        } catch (e: Exception) {
            Log.w(TAG, "$path didn't decode: ${e.message}")
            throw ExplorerFailure.BadAnswer()
        }
    }

    suspend fun getObject(path: String, query: List<Pair<String, String>> = emptyList()): JsonObject =
        get(path, query) as? JsonObject ?: throw ExplorerFailure.BadAnswer()

    // MARK: Pages

    suspend fun transaction(id: String): ExplorerTransaction {
        // seconds old: kaspa.stream hasn't indexed it yet, but the node already sent it
        return try {
            indexedTransaction(id)
        } catch (e: ExplorerFailure.NotFound) {
            live.transaction(id)?.let { ExplorerParsing.readFully(it, KaspaNetwork.launch) } ?: throw e
        }
    }

    private suspend fun indexedTransaction(id: String): ExplorerTransaction {
        val network = KaspaNetwork.launch
        val tx = getObject(
            "/transactions/$id",
            listOf("inputs" to "true", "outputs" to "true", "resolve_previous_outpoints" to "light"),
        )
        val blue = live.currentBlueScore()
        return withContext(Dispatchers.Default) {
            ExplorerParsing.indexedTransaction(tx, blue, network, full = true) ?: throw ExplorerFailure.BadAnswer()
        }
    }

    suspend fun block(hash: String): ExplorerBlock {
        val network = KaspaNetwork.launch
        return try {
            val json = getObject("/blocks/$hash", listOf("includeTransactions" to "true"))
            withContext(Dispatchers.Default) { ExplorerParsing.nodeBlock(json, network) } ?: throw ExplorerFailure.BadAnswer()
        } catch (e: ExplorerFailure.NotFound) {
            live.block(hash) ?: throw e
        }
    }

    /** One page of an address's history, newest first, and where the next page starts. Every
     *  transaction comes with its payload (kaspa.stream can leave payloads out but not cut them
     *  short, and a row's kind needs the payload's first bytes; audit 2026-11-25 E4). */
    suspend fun addressPage(address: String, before: Long?, limit: Int = LATER_ADDRESS_PAGE): Pair<List<ExplorerTransaction>, Long?> {
        val network = KaspaNetwork.launch
        val query = mutableListOf("limit" to limit.toString(), "resolve_previous_outpoints" to "light")
        if (before != null) query += "before" to before.toString()
        val answer = request("/addresses/$address/full-transactions-page", query)
        val array = try {
            JsonParser.parseString(answer.body) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: throw ExplorerFailure.BadAnswer()
        val blue = live.currentBlueScore()
        val txs = withContext(Dispatchers.Default) {
            array.mapNotNull { (it as? JsonObject)?.let { o -> ExplorerParsing.indexedTransaction(o, blue, network) } }
        }
        val next = answer.nextPageBefore?.trim()?.toLongOrNull()
        return txs to (if (array.size() < limit) null else next)
    }

    suspend fun address(address: String): ExplorerAddress = coroutineScope {
        val balance = async { ExplorerParsing.long(getObject("/addresses/$address/balance").get("balance")) ?: throw ExplorerFailure.BadAnswer() }
        val count = async {
            runCatchingNonCancel { ExplorerParsing.long(getObject("/addresses/$address/transactions-count").get("total"))?.toInt() }
        }
        val page = async { addressPage(address, before = null, limit = FIRST_ADDRESS_PAGE) }
        val kusdt = async { runCatchingNonCancel { kusdtBalances.fetch(address).units } }
        val identity = async { runCatchingNonCancel { registry.refreshIdentity(address) } }
        val (txs, next) = page.await()
        // received and sent over the last 30 days, from the history page (complete when it
        // reaches back that far)
        val monthAgo = System.currentTimeMillis() - 30 * 86_400_000L
        var received = 0L
        var sent = 0L
        // what the network accepted only: an unaccepted copy moved nothing
        for (tx in txs) {
            if (tx.time < monthAgo || tx.isNotAccepted) continue
            val (incoming, amount) = tx.net(address)
            if (incoming) received = ExplorerMath.add(received, amount) else sent = ExplorerMath.add(sent, amount)
        }
        val coversMonth = next == null || (txs.lastOrNull()?.let { it.time < monthAgo } ?: true)
        ExplorerAddress(
            address = address,
            username = identity.await()?.label?.let { KachatNameLabel.display(it) },
            balance = balance.await(),
            kusdtUnits = kusdt.await(),
            received30d = received,
            sent30d = sent,
            summaryCoversMonth = coversMonth,
            transactionCount = count.await(),
            transactions = txs,
            nextPageBefore = next,
        )
    }

    /** An address's balance alone (the as-you-type result), null when it couldn't be read. */
    suspend fun balance(address: String): Long? =
        runCatchingNonCancel { ExplorerParsing.long(getObject("/addresses/$address/balance").get("balance")) }

    /** What a 64-character id is (iOS `ExplorerAPI.IdMatch`). */
    sealed class IdMatch {
        data class Transaction(val kind: ExplorerTxKind) : IdMatch()
        data object Block : IdMatch()
        data object NotFound : IdMatch()
    }

    /**
     * What a 64-character id is, with light calls (no inputs, no block transactions); the node's
     * copy when kaspa.stream hasn't indexed it yet. Throws when kaspa.stream couldn't be asked and
     * nothing was found, so "not found" means not found (audit 2026-11-25 C11).
     */
    suspend fun identify(id: String): IdMatch {
        val network = KaspaNetwork.launch
        var unreachable: Exception? = null
        try {
            val tx = getObject("/transactions/$id", listOf("inputs" to "false", "outputs" to "true"))
            val covenants = (tx.get("outputs") as? JsonArray).orEmptyObjects().mapNotNull { ExplorerParsing.string(it.get("covenant_id")) }
            val kind = ExplorerParsing.classify(
                ExplorerParsing.string(tx.get("payload")), ExplorerParsing.string(tx.get("subnetwork_id")), covenants, network,
            ).first
            return IdMatch.Transaction(kind)
        } catch (e: ExplorerFailure.NotFound) {
            // not a transaction kaspa.stream knows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreachable = e
        }
        live.transaction(id)?.let { return IdMatch.Transaction(it.kind) }
        try {
            request("/blocks/$id", listOf("includeTransactions" to "false"))
            return IdMatch.Block
        } catch (e: ExplorerFailure.NotFound) {
            // not a block either
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (unreachable == null) unreachable = e
        }
        if (live.block(id) != null) return IdMatch.Block
        unreachable?.let { throw it }
        return IdMatch.NotFound
    }

    /** What a search finds: an address, a transaction or block id, or a .kachat username. */
    suspend fun search(raw: String): ExplorerSearchMatch {
        val text = raw.trim()
        val lower = text.lowercase()
        if (lower.startsWith("kaspa:") || lower.startsWith("kaspatest:")) {
            return if (KaspaAddress.isValid(lower)) ExplorerSearchMatch.Route(ExplorerRoute.Address(lower)) else ExplorerSearchMatch.NotFound
        }
        if (ExplorerParsing.isHexId(lower)) {
            return when (runCatchingNonCancel { identify(lower) }) {
                is IdMatch.Transaction -> ExplorerSearchMatch.Route(ExplorerRoute.Transaction(lower))
                IdMatch.Block -> ExplorerSearchMatch.Route(ExplorerRoute.Block(lower))
                else -> ExplorerSearchMatch.NotFound
            }
        }
        val address = runCatchingNonCancel { nameServices.resolveEverywhere(text).firstOrNull()?.address }
        return if (address != null) ExplorerSearchMatch.Route(ExplorerRoute.Address(address)) else ExplorerSearchMatch.NotFound
    }

    /** A typed .kachat username: its name as shown and its address, or null when it has none. */
    suspend fun username(text: String): Pair<String, String>? {
        val found = runCatchingNonCancel { nameServices.resolveEverywhere(text).firstOrNull() } ?: return null
        val address = found.address ?: return null
        return found.display to address
    }

    /** The address's .kachat name as shown, from the registry (fresh) or what this phone has. */
    suspend fun identityName(address: String): String? =
        runCatchingNonCancel { registry.refreshIdentity(address) }?.label?.let { KachatNameLabel.display(it) }
            ?: cachedName(address)

    fun cachedName(address: String): String? =
        runCatching { registry.cachedIdentity(address) }.getOrNull()?.label?.let { KachatNameLabel.display(it) }

    // MARK: History for the charts

    /** The network's hashrate over [period], oldest first, in H/s. */
    suspend fun hashrateHistory(period: ExplorerKPeriod): List<ExplorerChartPoint> {
        val now = System.currentTimeMillis()
        val raw = mutableListOf<JsonElement>()
        val since: Long
        suspend fun add(path: String, query: List<Pair<String, String>> = emptyList(), required: Boolean = false) {
            val answer = if (required) get(path, query) else runCatchingNonCancel { get(path, query) }
            (answer as? JsonArray)?.let { raw.addAll(it) }
        }
        when (period) {
            ExplorerKPeriod.DAY -> {
                since = now - 86_400_000L
                for (d in setOf(ExplorerParsing.utcDay(since), ExplorerParsing.utcDay(now))) add("/info/hashrate/history/$d")
            }
            ExplorerKPeriod.WEEK, ExplorerKPeriod.MONTH -> {
                since = now - (if (period == ExplorerKPeriod.WEEK) 7 else 30) * 86_400_000L
                for (m in setOf(ExplorerParsing.utcMonth(since), ExplorerParsing.utcMonth(now))) add("/info/hashrate/history/$m")
            }
            ExplorerKPeriod.ALL_TIME -> {
                since = Long.MIN_VALUE
                add("/info/hashrate/history", listOf("resolution" to "7d"), required = true)
            }
        }
        val points = raw.mapNotNull { ExplorerParsing.hashratePoint(it) }
            .filter { it.date >= since }
            .sortedBy { it.date }
        if (points.isEmpty()) throw ExplorerFailure.NotFound()
        return ExplorerParsing.downsample(points, 240)
    }

    /** KAS's price over [period], from the price feed Portfolio uses (CoinGecko, then Gate.io). */
    suspend fun priceHistory(period: ExplorerKPeriod): List<ExplorerChartPoint> {
        val days = when (period) {
            ExplorerKPeriod.DAY -> 1
            ExplorerKPeriod.WEEK -> 7
            ExplorerKPeriod.MONTH -> 30
            ExplorerKPeriod.ALL_TIME -> PortfolioRepository.ALL_TIME_DAYS
        }
        val history = runCatchingNonCancel { portfolioRepository.getPriceHistory(days = days, currency = "usd") }.orEmpty()
        return ExplorerParsing.downsample(history.map { ExplorerChartPoint(it.first, it.second) }.sortedBy { it.date }, 240)
    }

    companion object {
        private const val TAG = "Explorer"
        private const val MAX_ANSWER_BYTES = 12L * 1024 * 1024

        /** An address page's first page of history: small, since every transaction comes with its
         *  payload (audit 2026-11-25 E4). Load More asks for more at a time. */
        const val FIRST_ADDRESS_PAGE = 20
        const val LATER_ADDRESS_PAGE = 40
    }
}

/** The answer, or null when it failed; a cancellation still cancels. */
internal inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun JsonArray?.orEmptyObjects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

/**
 * The pure parts of the Explorer's data: reading kaspa.stream's and the node's JSON, and what a
 * transaction is in K. Safe off the main thread, and unit tested.
 */
object ExplorerParsing {
    /** How much of a payload the lists read for its kind: K's prefixes, an action and a room's
     *  name all fit in its first 128 bytes, so a 100 KB photo isn't decoded to label a row. */
    private const val KIND_PREFIX_HEX_LENGTH = 256

    /** kUSDT's and its pool's covenant ids as hex, made once per network rather than for every
     *  transaction (audit 2026-11-25 E1). */
    private val kusdtIds: Map<KaspaNetwork.Type, Pair<String, String>> by lazy {
        KaspaNetwork.Type.entries.associateWith { network ->
            val d = Kusdt.deployment(network)
            hex(d.poolId) to d.covenantIdHex.lowercase()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Hex as bytes; null for odd lengths or anything not hex. */
    fun hexBytes(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[2 * i], 16)
            val lo = Character.digit(text[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isHexId(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * A transaction's kind and what K can show of it, read from its payload and the covenants it
     * touches: K's own protocols (`kchat:1:`, the older `ciph_msg:`, KaPost's legacy `k:1:`),
     * kUSDT and its pool, and mining rewards. Lists read only the payload's first bytes and leave
     * the text empty; [full] (a transaction's own page) decodes it all, KaPost's signature check
     * included (audit 2026-11-25 E1).
     */
    fun classify(
        payloadHex: String?,
        subnetwork: String?,
        covenants: List<String>,
        network: KaspaNetwork.Type,
        full: Boolean = false,
    ): Pair<ExplorerTxKind, String?> {
        if (subnetwork != null && subnetwork.startsWith("01")) return ExplorerTxKind.COINBASE to null
        val (pool, token) = kusdtIds.getValue(network)
        if (covenants.any { it.lowercase() == pool }) return ExplorerTxKind.KUSDT_SWAP to null
        if (covenants.any { it.lowercase() == token }) return ExplorerTxKind.KUSDT_TRANSFER to null
        if (payloadHex.isNullOrEmpty()) return ExplorerTxKind.PAYMENT to null
        val text: String = if (full) {
            val bytes = hexBytes(payloadHex) ?: return ExplorerTxKind.PAYMENT to null
            // K's payloads start with plain text; anything else is some other app's
            strictUtf8(bytes) ?: return ExplorerTxKind.OTHER to null
        } else {
            val bytes = hexBytes(payloadHex.take(KIND_PREFIX_HEX_LENGTH)) ?: return ExplorerTxKind.PAYMENT to null
            // cut mid-character at worst: only the prefixes below are compared
            String(bytes, Charsets.UTF_8)
        }
        var rest: String = when {
            text.startsWith("kchat:1:") -> text.removePrefix("kchat:1:")
            text.startsWith("ciph_msg:1:") -> text.removePrefix("ciph_msg:1:")
            text.startsWith("ciph_msg:") -> text.removePrefix("ciph_msg:")
            text.startsWith("k:1:") -> return ExplorerTxKind.KAPOST to (if (full) kaPostText(text) else null)
            else -> return ExplorerTxKind.OTHER to null
        }
        val action = rest.substringBefore(':')
        rest = if (rest.length > action.length) rest.substring(action.length + 1) else ""
        return when (action) {
            "handshake", "self_stash" -> ExplorerTxKind.HANDSHAKE to null
            "comm", "gcomm", "gctl", "dm" -> ExplorerTxKind.KACHAT_MESSAGE to null
            "pay", "tip" -> ExplorerTxKind.PAYMENT to null
            "name", "offer", "profile" -> ExplorerTxKind.KACHAT_NAME to null
            "bcast" -> {
                val channel = rest.substringBefore(':')
                val content = if (rest.length > channel.length) rest.substring(channel.length + 1) else ""
                when (channel) {
                    CHESS_ARENA, CHESS_BOTS -> ExplorerTxKind.CHESS_MOVE to null
                    ARCADE -> ExplorerTxKind.ARCADE_STAMP to null
                    else -> ExplorerTxKind.PUBLIC_CHAT to (if (full) preview("#$channel: $content") else null)
                }
            }
            // post, reply, quote, vote, follow...: KaPost shares the root
            else -> ExplorerTxKind.KAPOST to (if (full) kaPostText(text) else null)
        }
    }

    private val CHESS_ARENA = com.kachat.app.util.ChessTournamentCodec.ARENA_CHANNEL
    private val CHESS_BOTS = com.kachat.app.util.ChessBotCodec.CHANNEL
    private val ARCADE = com.kachat.app.services.arcade.ArcadeStamp.CHANNEL

    private fun kaPostText(text: String): String? =
        runCatching { KaPostsProtocol.parseChainPayload(text) }.getOrNull()?.message?.let(::preview)

    /** Bytes as UTF-8, or null when they aren't valid UTF-8 (Swift's `String(data:encoding:.utf8)`). */
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /** A payload's text as a one-paragraph preview. */
    fun preview(text: String): String? {
        val flat = text.trim()
        if (flat.isEmpty()) return null
        return if (flat.length > 280) flat.take(280) + "…" else flat
    }

    /** A transaction a list built, with its payload read in full for its own page. */
    fun readFully(tx: ExplorerTransaction, network: KaspaNetwork.Type): ExplorerTransaction {
        if (tx.decoded != null || (tx.kind != ExplorerTxKind.KAPOST && tx.kind != ExplorerTxKind.PUBLIC_CHAT)) return tx
        val (_, decoded) = classify(tx.payloadHex, null, emptyList(), network, full = true)
        return tx.copy(decoded = decoded)
    }

    // MARK: JSON

    fun string(element: JsonElement?): String? =
        element?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * A u64 as the node's JSON writes it (audit 2026-11-25 C6): proto3 JSON sends 64-bit numbers
     * as strings, some builds as plain numbers, and a field that is 0 not at all (so every use is
     * optional). Never negative; past Long's range is its largest value.
     */
    fun long(element: JsonElement?): Long? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isNumber -> {
                val decimal = runCatching { primitive.asBigDecimal }.getOrNull() ?: return null
                when {
                    decimal.signum() <= 0 -> 0L
                    decimal >= java.math.BigDecimal.valueOf(Long.MAX_VALUE) -> Long.MAX_VALUE
                    else -> decimal.toLong()
                }
            }
            primitive.isString -> {
                val text = primitive.asString.trim()
                text.toLongOrNull()?.coerceAtLeast(0)
                    ?: text.toBigIntegerOrNull()?.let { if (it.signum() <= 0) 0L else Long.MAX_VALUE }
                    ?: text.toDoubleOrNull()?.let { ExplorerMath.clampedLong(it) }
            }
            else -> null
        }
    }

    fun double(element: JsonElement?): Double? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isNumber -> primitive.asDouble
            primitive.isString -> primitive.asString.trim().toDoubleOrNull()
            else -> null
        }
    }

    fun bool(element: JsonElement?): Boolean? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { string(it) }.orEmpty()

    private fun obj(element: JsonElement?): JsonObject? = element as? JsonObject

    /**
     * A transaction as kaspa.stream's indexer keeps it (`/transactions/{id}`, an address's
     * history). [full] reads the payload in full (a transaction's own page); lists read its kind.
     * Null when it has no id.
     */
    fun indexedTransaction(tx: JsonObject, virtualBlueScore: Long?, network: KaspaNetwork.Type, full: Boolean = false): ExplorerTransaction? {
        val id = string(tx.get("transaction_id"))?.takeIf { it.isNotEmpty() } ?: return null
        val rawInputs = (tx.get("inputs") as? JsonArray).orEmptyObjects()
        val rawOutputs = (tx.get("outputs") as? JsonArray).orEmptyObjects()
        val inputs = rawInputs.mapIndexed { offset, input ->
            ExplorerIO(
                index = long(input.get("index"))?.toInt() ?: offset,
                address = string(input.get("previous_outpoint_address")) ?: "",
                amount = long(input.get("previous_outpoint_amount")) ?: 0L,
            )
        }
        val outputs = rawOutputs.mapIndexed { offset, output ->
            ExplorerIO(
                index = long(output.get("index"))?.toInt() ?: offset,
                address = string(output.get("script_public_key_address")) ?: "",
                amount = long(output.get("amount")) ?: 0L,
            )
        }
        val covenants = rawInputs.mapNotNull { string(it.get("covenant_id")) } + rawOutputs.mapNotNull { string(it.get("covenant_id")) }
        val payload = string(tx.get("payload"))
        val (kind, decoded) = classify(payload, string(tx.get("subnetwork_id")), covenants, network, full)
        // the fee is only known when every input's amount came back
        val resolved = rawInputs.isNotEmpty() && rawInputs.all { long(it.get("previous_outpoint_amount")) != null }
        val inSum = inputs.fold(0L) { s, io -> ExplorerMath.add(s, io.amount) }
        val outSum = outputs.fold(0L) { s, io -> ExplorerMath.add(s, io.amount) }
        val fee: Long? = if (kind == ExplorerTxKind.COINBASE) 0L else if (resolved && inSum >= outSum) inSum - outSum else null
        val accepted = bool(tx.get("is_accepted"))
        var confirmations = 0
        val acceptedScore = long(tx.get("accepting_block_blue_score"))
        if (accepted == true && acceptedScore != null && virtualBlueScore != null) {
            confirmations = ((if (virtualBlueScore > acceptedScore) virtualBlueScore - acceptedScore else 0L) + 1)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return ExplorerTransaction(
            id = id,
            kind = kind,
            time = double(tx.get("block_time"))?.let { ExplorerMath.clampedLong(it) } ?: System.currentTimeMillis(),
            blockHash = strings(tx.get("block_hash")).firstOrNull(),
            confirmations = confirmations,
            fee = fee,
            mass = long(tx.get("mass")) ?: 0L,
            inputs = inputs,
            outputs = outputs,
            payloadHex = payload ?: "",
            tokenUnits = null,
            decoded = decoded,
            isAccepted = accepted,
        )
    }

    /** A block as the node shows it (`/blocks/{hash}`); null without its hash. */
    fun nodeBlock(block: JsonObject, network: KaspaNetwork.Type): ExplorerBlock? {
        val header = obj(block.get("header")) ?: JsonObject()
        val verbose = obj(block.get("verboseData")) ?: return null
        val hash = string(verbose.get("hash"))?.takeIf { it.isNotEmpty() } ?: return null
        val time = long(header.get("timestamp")) ?: 0L
        val ids = strings(verbose.get("transactionIds"))
        val txs = (block.get("transactions") as? JsonArray).orEmptyObjects().mapIndexedNotNull { offset, tx ->
            val (kind, decoded) = classify(string(tx.get("payload")), string(tx.get("subnetworkId")), emptyList(), network)
            val id = string(obj(tx.get("verboseData"))?.get("transactionId"))?.takeIf { it.isNotEmpty() } ?: ids.getOrNull(offset) ?: ""
            if (id.isEmpty()) return@mapIndexedNotNull null
            ExplorerTransaction(
                id = id,
                kind = kind,
                time = time,
                blockHash = hash,
                confirmations = 0,
                fee = if (kind == ExplorerTxKind.COINBASE) 0L else null,
                mass = long(tx.get("mass")) ?: 0L,
                inputs = (tx.get("inputs") as? JsonArray).orEmptyObjects().indices.map { ExplorerIO(it, "", 0L) },
                outputs = (tx.get("outputs") as? JsonArray).orEmptyObjects().mapIndexed { i, out ->
                    ExplorerIO(i, string(obj(out.get("verboseData"))?.get("scriptPublicKeyAddress")) ?: "", long(out.get("amount")) ?: 0L)
                },
                payloadHex = string(tx.get("payload")) ?: "",
                tokenUnits = null,
                decoded = decoded,
            )
        }
        return ExplorerBlock(
            id = hash,
            blueScore = long(header.get("blueScore")) ?: 0L,
            daaScore = long(header.get("daaScore")) ?: 0L,
            time = time,
            isChainBlock = bool(verbose.get("isChainBlock")) ?: false,
            difficulty = double(verbose.get("difficulty")) ?: 0.0,
            parents = (header.get("parents") as? JsonArray).orEmptyObjects().firstOrNull()?.let { strings(it.get("parentHashes")) }.orEmpty(),
            children = strings(verbose.get("childrenHashes")),
            mergeSetBlues = strings(verbose.get("mergeSetBluesHashes")).size,
            mergeSetReds = strings(verbose.get("mergeSetRedsHashes")).size,
            transactionIds = ids,
            transactions = txs,
        )
    }

    /** A block the node sent, as the lists show it. Reads only the payload's first bytes for each
     *  kind (audit 2026-11-25 E1). */
    fun liveBlock(d: ExplorerBlockDetail, network: KaspaNetwork.Type): ExplorerBlock {
        val txs = d.transactions.map { tx ->
            val (kind, decoded) = classify(tx.payloadHex, tx.subnetwork, tx.outputs.map { it.covenantId }.filter { it.isNotEmpty() }, network)
            ExplorerTransaction(
                id = tx.id, kind = kind, time = d.timestamp, blockHash = d.hash, confirmations = 0,
                fee = if (kind == ExplorerTxKind.COINBASE) 0L else null, mass = tx.mass,
                inputs = (0 until tx.inputCount).map { ExplorerIO(it, "", 0L) },
                outputs = tx.outputs.mapIndexed { i, o -> ExplorerIO(i, o.address, o.amount) },
                payloadHex = tx.payloadHex, tokenUnits = null, decoded = decoded,
            )
        }
        return ExplorerBlock(
            id = d.hash, blueScore = d.blueScore, daaScore = d.daaScore, time = d.timestamp, isChainBlock = d.isChainBlock,
            difficulty = d.difficulty, parents = d.parents, children = d.children, mergeSetBlues = d.mergeSetBlues,
            mergeSetReds = d.mergeSetReds, transactionIds = d.transactionIds, transactions = txs,
        )
    }

    /** One `/info/hashrate/history` sample: its time (ms) and `hashrate_kh` as H/s. */
    fun hashratePoint(element: JsonElement): ExplorerChartPoint? {
        val o = element as? JsonObject ?: return null
        val t = double(o.get("timestamp")) ?: return null
        val kh = double(o.get("hashrate_kh")) ?: return null
        return ExplorerChartPoint(ExplorerMath.clampedLong(t), kh * 1000)
    }

    fun downsample(points: List<ExplorerChartPoint>, limit: Int): List<ExplorerChartPoint> {
        if (points.size <= limit) return points
        val step = (points.size - 1).toDouble() / (limit - 1)
        return (0 until limit).map { points[Math.round(it * step).toInt().coerceIn(0, points.size - 1)] }
    }

    private fun utc(ms: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }

    /** "2026-11-25", in UTC. */
    fun utcDay(ms: Long): String {
        val c = utc(ms)
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** "2026-11", in UTC. */
    fun utcMonth(ms: Long): String {
        val c = utc(ms)
        return String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** "kaspa:qpzpfw…" found anywhere in pasted text ("send to kaspa:qq…, thanks"). */
    fun addressIn(text: String): String? =
        Regex("kaspa(test)?:[a-z0-9]{50,70}").find(text.lowercase())?.value
}
