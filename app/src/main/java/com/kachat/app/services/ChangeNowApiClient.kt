package com.kachat.app.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.BuildConfig
import com.kachat.app.R
import com.kachat.app.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** iOS `ChangeNowEstimateResponse`. */
data class ChangeNowEstimate(
    val fromAmount: Double,
    val toAmount: Double,
)

/** The shape shared by "create exchange" and "check status" (iOS `ChangeNowTransactionResponse`). */
data class ChangeNowTransaction(
    val id: String,
    /** "new" | "waiting" | "confirming" | "exchanging" | "sending" | "finished" | "failed" |
     *  "refunded" | "verifying" */
    val status: String? = null,
    val payinAddress: String? = null,
    val payoutAddress: String? = null,
    /** The deposit's tag (XRP's destination tag): the deposit must carry it. */
    val payinExtraId: String? = null,
    val payoutExtraId: String? = null,
    val fromAmount: Double? = null,
    val toAmount: Double? = null,
    val fromCurrency: String? = null,
    val toCurrency: String? = null,
    val fromNetwork: String? = null,
    val toNetwork: String? = null,
) {
    companion object {
        private fun JsonObject.str(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.num(key: String): Double? =
            get(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
                try { p.asDouble.takeIf { it.isFinite() } } catch (e: Exception) { null }
            }

        fun from(o: JsonObject): ChangeNowTransaction? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return ChangeNowTransaction(
                id = id,
                status = o.str("status"),
                payinAddress = o.str("payinAddress"),
                payoutAddress = o.str("payoutAddress"),
                payinExtraId = o.str("payinExtraId"),
                payoutExtraId = o.str("payoutExtraId"),
                fromAmount = o.num("fromAmount"),
                toAmount = o.num("toAmount"),
                fromCurrency = o.str("fromCurrency"),
                toCurrency = o.str("toCurrency"),
                fromNetwork = o.str("fromNetwork"),
                toNetwork = o.str("toNetwork"),
            )
        }
    }
}

/** iOS `ChangeNowError`. */
sealed class ChangeNowError(message: String) : Exception(message) {
    class InvalidUrl : ChangeNowError("Invalid ChangeNOW request URL.")
    class NoKey : ChangeNowError(tr(R.string.swap_changenow_unavailable))
    class Http(val code: Int, body: String) :
        ChangeNowError(if (body.isEmpty()) "ChangeNOW error ($code)." else "ChangeNOW error ($code): $body")
}

/**
 * ChangeNOW's v2 exchange API (iOS 518fdc8c `ChangeNowAPIClient`): lets K swap KAS for another coin
 * (and back) without leaving the app. Auth is a per-request `x-changenow-api-key` header.
 *
 * The key is built in from the gitignored local.properties (`changenow.api.key`) through
 * [BuildConfig.CHANGENOW_API_KEY], as iOS builds it in from Secrets.xcconfig; it is readable from
 * the APK (rate limits, attribution; no funds at risk: iOS audit 2026-11-24 #7, accepted). With no
 * key, ChangeNOW coins say "ChangeNOW swaps aren't available in this build."
 */
object ChangeNowApiClient {
    private const val BASE_URL = "https://api.changenow.io"

    private val apiKey: String = BuildConfig.CHANGENOW_API_KEY.trim()

    /** Whether this build carries a ChangeNOW key. */
    val hasKey: Boolean get() = apiKey.isNotEmpty()

    // An explicit 20 s cap rather than a minute: a quote that takes a minute to fail is worse than
    // one that fails in 20 s and lets the user retry (iOS `timeoutInterval = 20`).
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun url(path: String, query: List<Pair<String, String>>): okhttp3.HttpUrl {
        val base = try { (BASE_URL + path).toHttpUrl() } catch (e: Exception) { throw ChangeNowError.InvalidUrl() }
        return base.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    private suspend fun send(request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // ChangeNOW's 4xx answers carry the real reason in the JSON body
                throw ChangeNowError.Http(response.code, body.take(300))
            }
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw ChangeNowError.Http(response.code, body.take(300))
        }
    }

    private fun get(path: String, query: List<Pair<String, String>>): Request {
        if (!hasKey) throw ChangeNowError.NoKey()
        return Request.Builder().url(url(path, query)).header("x-changenow-api-key", apiKey).get().build()
    }

    private fun pair(fromCurrency: String, fromNetwork: String, toCurrency: String, toNetwork: String) = listOf(
        "fromCurrency" to fromCurrency,
        "fromNetwork" to fromNetwork,
        "toCurrency" to toCurrency,
        "toNetwork" to toNetwork,
    )

    /** A live quote: how much `toAmount` a given `fromAmount` converts to right now. */
    suspend fun getEstimatedAmount(
        fromCurrency: String, fromNetwork: String, toCurrency: String, toNetwork: String, fromAmount: String,
    ): ChangeNowEstimate {
        val o = send(get(
            "/v2/exchange/estimated-amount",
            pair(fromCurrency, fromNetwork, toCurrency, toNetwork) + listOf("fromAmount" to fromAmount, "flow" to "standard"),
        ))
        val from = o.get("fromAmount")?.takeIf { it.isJsonPrimitive }?.asDouble
        val to = o.get("toAmount")?.takeIf { it.isJsonPrimitive }?.asDouble
        if (from == null || to == null) throw ChangeNowError.Http(200, o.toString().take(300))
        return ChangeNowEstimate(from, to)
    }

    /** The smallest `fromAmount` ChangeNOW accepts for this pair on the standard flow. There is no
     *  reverse quote on that flow, so [com.kachat.app.services.SwapService] derives "what do I send
     *  to get X" from direct quotes, and this is where those probes start. */
    suspend fun getMinAmount(fromCurrency: String, fromNetwork: String, toCurrency: String, toNetwork: String): Double {
        val o = send(get(
            "/v2/exchange/min-amount",
            pair(fromCurrency, fromNetwork, toCurrency, toNetwork) + listOf("flow" to "standard"),
        ))
        return o.get("minAmount")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: throw ChangeNowError.Http(200, o.toString().take(300))
    }

    /** Opens a new exchange: the answer's `payinAddress` is where the "from" coin has to arrive.
     *  [extraId] is the payout's tag (XRP's destination tag), left out of the body when null. */
    suspend fun createTransaction(
        fromCurrency: String, fromNetwork: String, toCurrency: String, toNetwork: String,
        fromAmount: String, address: String, extraId: String? = null,
    ): ChangeNowTransaction {
        if (!hasKey) throw ChangeNowError.NoKey()
        val body = JsonObject().apply {
            addProperty("fromCurrency", fromCurrency)
            addProperty("fromNetwork", fromNetwork)
            addProperty("toCurrency", toCurrency)
            addProperty("toNetwork", toNetwork)
            addProperty("fromAmount", fromAmount)
            addProperty("address", address)
            extraId?.let { addProperty("extraId", it) }
            addProperty("flow", "standard")
        }
        val request = Request.Builder()
            .url(url("/v2/exchange", emptyList()))
            .header("x-changenow-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val o = send(request)
        return ChangeNowTransaction.from(o) ?: throw ChangeNowError.Http(200, o.toString().take(300))
    }

    /** The current status of an exchange made earlier, by its id. */
    suspend fun getTransactionStatus(id: String): ChangeNowTransaction {
        val o = send(get("/v2/exchange/by-id", listOf("id" to id)))
        return ChangeNowTransaction.from(o) ?: throw ChangeNowError.Http(200, o.toString().take(300))
    }
}
