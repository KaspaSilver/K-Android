package com.kachat.app.services.tokens

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP for Trestly's wallet logic in the hidden core page (the iPhone repo's
 * tools/kusdt-wallet/src/shims.ts replaces its `fetch` with this). Only https, and only to:
 * - the public Kaspa node resolvers their node client asks (`<name>.kaspa.stream`, `.red`, `.green`,
 *   `.blue`, the same rule as kusdt-open's `NODE_HOST`), and
 * - the Trestly API host set in Settings > Connection > Trestly ([AppSettingsRepository.trestlyApiUrl]).
 * Anything else is refused, and so is a redirect to anywhere else (each hop is checked again).
 * Requests carry at most 64 KB; answers are read only up to 4 MB.
 * iOS 148a5331 `Services/Tokens/KusdtWalletRelays.swift` `KusdtNetRelay`, from KaPay.
 */
@Singleton
class KusdtNetRelay @Inject constructor(
    private val settings: AppSettingsRepository,
) {

    /** One request from the page (`{url, method, headers, body}`); answers
     *  `{status, headers, body}` or `{error}`, as iOS's reply. */
    suspend fun handle(body: JsonObject?): JsonObject = withContext(Dispatchers.IO) {
        if (body == null) return@withContext failure("bad request")
        val url = body.string("url")?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull() ?: return@withContext failure("bad request")
        val apiHost = settings.trestlyApiUrl.first().trim().toHttpUrlOrNull()?.host?.lowercase()
        if (!allowed(url, apiHost)) {
            Log.w(TAG, "wallet logic asked for ${url.host}: refused")
            return@withContext failure("K doesn't let this reach ${url.host}.")
        }
        val method = (body.string("method") ?: "GET").uppercase()
        if (method != "GET" && method != "POST") return@withContext failure("method not allowed")
        // only what a JSON API needs: never cookies or credentials
        val headers = ArrayList<Pair<String, String>>()
        body.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
            val v = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
            val k = key.lowercase()
            if ((k == "content-type" || k == "accept") && v.length <= 200) headers += key to v
        }
        var payload: ByteArray? = null
        if (method == "POST") {
            val p = body.string("body")
            if (p != null) {
                val bytes = p.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_REQUEST_BYTES) return@withContext failure("request too long")
                payload = bytes
            }
        }
        try {
            fetch(url, method, headers, payload, apiHost)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Follows a redirect only to an allowed address: the first URL's check holds for every hop
     *  (iOS `RedirectGuard`). 301/302/303 turn a POST into a GET, as URLSession does. */
    private fun fetch(start: HttpUrl, startMethod: String, headers: List<Pair<String, String>>, startPayload: ByteArray?, apiHost: String?): JsonObject {
        var url = start
        var method = startMethod
        var payload = startPayload
        repeat(MAX_REDIRECTS + 1) {
            val contentType = headers.firstOrNull { it.first.equals("content-type", ignoreCase = true) }?.second
            val requestBody = if (method == "POST") (payload ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull()) else null
            val builder = Request.Builder().url(url).method(method, requestBody).header("Cache-Control", "no-cache")
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    val next = response.header("Location")?.let { url.resolve(it) }
                    if (next == null || !allowed(next, apiHost)) {
                        Log.w(TAG, "wallet logic was redirected to ${next?.host ?: "?"}: refused")
                        return failure("K doesn't let this reach ${next?.host ?: "that address"}.")
                    }
                    if (response.code in 301..303 && method == "POST") {
                        method = "GET"
                        payload = null
                    }
                    url = next
                    return@repeat
                }
                val declared = response.header("Content-Length")?.toLongOrNull()
                if (declared != null && declared > MAX_ANSWER_BYTES) return failure("answer too long")
                val source = response.body?.source() ?: return failure("no answer")
                // read up to the cap and no further, whatever the server says its length is
                if (source.request(MAX_ANSWER_BYTES + 1) && source.buffer.size > MAX_ANSWER_BYTES) return failure("answer too long")
                val data = source.buffer.readByteArray()
                val out = JsonObject()
                out.addProperty("status", response.code)
                val h = JsonObject()
                h.addProperty("content-length", data.size.toString())
                response.header("Content-Type")?.let { h.addProperty("content-type", it) }
                response.header("ETag")?.let { h.addProperty("etag", it) }
                out.add("headers", h)
                out.addProperty("body", String(data, Charsets.UTF_8))
                return out
            }
        }
        return failure("too many redirects")
    }

    private fun failure(why: String): JsonObject = JsonObject().apply { addProperty("error", why) }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        private const val TAG = "kUSDT"
        private val RESOLVER_HOST = Regex("^[a-z0-9-]+\\.kaspa\\.(stream|red|green|blue)$")
        const val MAX_ANSWER_BYTES = 4L * 1024 * 1024
        const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** https to a Kaspa resolver or the Settings Trestly API host ([apiHost]), no user or password. */
        fun allowed(url: HttpUrl, apiHost: String?): Boolean {
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            val host = url.host.lowercase()
            if (apiHost != null && host == apiHost) return true
            return RESOLVER_HOST.matches(host)
        }

        /** No cookies, no cache, redirects followed here only after [allowed] (iOS's ephemeral
         *  session: 15 s a request, 30 s in all). */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * `chrome.storage.local` for Trestly's wallet logic: what it keeps between runs (the liquidity
 * positions it made, coins it knows, transactions it's following), per network and owner key.
 * Kept as one JSON file in app-private storage (`files/kusdt-wallet-store.json`, never backed up:
 * the app allows no backup), 2 MB at most in all, only keys starting `kusdt.`. Nothing secret goes
 * in it: their code keeps keys elsewhere, and KaChat hands a key in only for one call. An account's
 * entries go with the account ([forget]; iOS calls it from `TokenWallet.forgetLocalData`).
 * iOS 148a5331 `KusdtStoreRelay`, from KaPay; iOS's one-time move from UserDefaults has nothing
 * to move here.
 */
@Singleton
class KusdtStoreRelay @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Each value as its JSON text, by key. */
    private var cache: MutableMap<String, String>? = null

    private val file: AtomicFile get() = AtomicFile(File(context.filesDir, FILE_NAME))

    @Synchronized
    private fun loadStore(): MutableMap<String, String> {
        cache?.let { return it }
        val stored = HashMap<String, String>()
        try {
            val text = String(file.readFully(), Charsets.UTF_8)
            val obj = JsonParser.parseString(text)
            if (obj.isJsonObject) {
                for ((k, v) in obj.asJsonObject.entrySet()) {
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isString) stored[k] = v.asString
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            // nothing kept yet
        } catch (e: Exception) {
            Log.w(TAG, "the wallet store couldn't be read: starting empty")
        }
        cache = stored
        return stored
    }

    @Synchronized
    private fun save(values: MutableMap<String, String>) {
        cache = values
        val obj = JsonObject()
        values.forEach { (k, v) -> obj.addProperty(k, v) }
        val f = file
        val out = try { f.startWrite() } catch (e: Exception) { return }
        try {
            out.write(obj.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    /** Drops what Trestly's code kept for one owner key: entries named after it, and records in
     *  lists (positions) that name it as their owner. */
    @Synchronized
    fun forget(ownerHex: String) {
        val owner = ownerHex.lowercase()
        val all = HashMap(loadStore())
        for ((key, text) in all.entries.toList()) {
            if (key.lowercase().contains(owner)) {
                all.remove(key)
            } else if (text.lowercase().contains(owner)) {
                val list = try { JsonParser.parseString(text) } catch (e: Exception) { null }
                if (list != null && list.isJsonArray && list.asJsonArray.all { it.isJsonObject }) {
                    val kept = JsonArray()
                    list.asJsonArray.forEach { item ->
                        val o = item.asJsonObject.get("owner")
                        val itemOwner = o?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.lowercase().orEmpty()
                        if (itemOwner != owner) kept.add(item)
                    }
                    all[key] = kept.toString()
                }
            }
        }
        save(all)
    }

    /** One request from the page (`{op: get|set|remove, keys|values}`); answers `{value}` or
     *  `{error}`, as iOS's reply. */
    @Synchronized
    fun handle(body: JsonObject?): JsonObject {
        if (body == null) return failure("bad request")
        val op = body.get("op")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return failure("bad request")
        return when (op) {
            "get" -> {
                val keys = stringList(body.get("keys")).take(64)
                val all = loadStore()
                val value = JsonObject()
                for (key in keys) {
                    val text = all[key] ?: continue
                    val parsed = try { JsonParser.parseString(text) } catch (e: Exception) { null } ?: continue
                    value.add(key, parsed)
                }
                JsonObject().apply { add("value", value) }
            }
            "set" -> {
                val all = HashMap(loadStore())
                body.get("values")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                    if (!key.startsWith("kusdt.") || key.length > 256 || value == null || value is JsonNull) return@forEach
                    val data = value.toString().toByteArray(Charsets.UTF_8)
                    if (data.size > MAX_ENTRY_BYTES) return@forEach
                    all[key] = String(data, Charsets.UTF_8)
                }
                // over the total cap: refuse the write rather than grow without bound
                val total = all.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size }
                if (total > MAX_TOTAL_BYTES) return failure("store full")
                save(all)
                JsonObject().apply { add("value", JsonObject()) }
            }
            "remove" -> {
                val all = HashMap(loadStore())
                stringList(body.get("keys")).filter { it.startsWith("kusdt.") }.forEach { all.remove(it) }
                save(all)
                JsonObject().apply { add("value", JsonObject()) }
            }
            else -> failure("bad op")
        }
    }

    private fun stringList(v: JsonElement?): List<String> =
        v?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
            ?: emptyList()

    private fun failure(why: String): JsonObject = JsonObject().apply { addProperty("error", why) }

    private companion object {
        const val TAG = "kUSDT"
        const val FILE_NAME = "kusdt-wallet-store.json"
        const val MAX_ENTRY_BYTES = 512 * 1024
        const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    }
}
