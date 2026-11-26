package com.kachat.app.services.grpc

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Finds public Kaspa nodes that speak wRPC over `wss://`, through the public node network's
 * resolvers (the same list and checks Trestly's node code uses; iOS 961fc05a
 * `PublicNodeFallback.swift`). K reaches for one only when no gRPC node answers
 * ([com.kachat.app.services.NodePoolManager]'s public fallback), and Automatic Scan keeps up to
 * three among its seeds: a network that blocks gRPC's HTTP/2 (DPI, some firewalls) usually lets
 * an ordinary HTTPS WebSocket on 443 through.
 */
object PublicNodeResolver {
    val resolvers = listOf(
        "eric.kaspa.stream", "maxim.kaspa.stream", "sean.kaspa.stream", "troy.kaspa.stream",
        "john.kaspa.red", "mike.kaspa.red", "paul.kaspa.red", "alex.kaspa.red",
        "jake.kaspa.green", "mark.kaspa.green", "adam.kaspa.green", "liam.kaspa.green",
        "noah.kaspa.blue", "ryan.kaspa.blue", "jack.kaspa.blue", "luke.kaspa.blue",
    )

    /** Node hosts a resolver may send K to: names on the public node network's own domains. */
    private val nodeHostSuffixes = listOf(".kaspa.stream", ".kaspa.red", ".kaspa.green", ".kaspa.blue")
    private const val MAX_ANSWER_BYTES = 16 * 1024L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** wRPC's name for the network in a resolver path. */
    fun networkId(testnet: Boolean): String = if (testnet) "testnet-10" else "mainnet"

    /** Up to [count] distinct nodes (their `wss://…/wrpc/json` addresses, [ParsedNodeAddress.key])
     *  for the network, asking resolvers in a random order; none already in [avoiding]. */
    suspend fun resolve(testnet: Boolean, count: Int, avoiding: Set<String> = emptySet()): List<String> {
        val id = networkId(testnet)
        val found = mutableListOf<String>()
        val seen = avoiding.toMutableSet()
        for (host in resolvers.shuffled()) {
            if (found.size >= count) break
            val node = ask(host, id) ?: continue
            if (!seen.add(node)) continue
            found += node
        }
        return found
    }

    private suspend fun ask(host: String, networkId: String): String? = withContext(Dispatchers.IO) {
        // The resolver answers for Borsh only; the node serves JSON on the same path's sibling.
        val request = Request.Builder().url("https://$host/v2/kaspa/$networkId/tls/wrpc/borsh").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return@withContext null
                val body = response.body ?: return@withContext null
                val length = body.contentLength()
                if (length > MAX_ANSWER_BYTES) return@withContext null
                val source = body.source()
                // At most the cap plus one byte is read: a longer answer is refused.
                source.request(MAX_ANSWER_BYTES + 1)
                if (source.buffer.size > MAX_ANSWER_BYTES) return@withContext null
                parseAnswer(source.buffer.readUtf8(), networkId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The node a resolver's answer `{"url": "wss://…/wrpc/borsh"}` names, as its JSON-encoding
     *  address, or null when it isn't a `wss://` node on the public network's own domains for
     *  [networkId]. Pure, for tests. */
    fun parseAnswer(text: String, networkId: String): String? {
        val answer = try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return null
            val url = root.asJsonObject["url"]
            if (url == null || !url.isJsonPrimitive || !url.asJsonPrimitive.isString) return null
            url.asString
        } catch (e: Exception) {
            return null
        }
        val uri = try { java.net.URI(answer) } catch (e: Exception) { return null }
        if (uri.scheme?.lowercase() != "wss") return null
        val nodeHost = uri.host?.lowercase() ?: return null
        val suffix = nodeHostSuffixes.firstOrNull { nodeHost.endsWith(it) } ?: return null
        val label = nodeHost.dropLast(suffix.length)
        if (label.isEmpty() || !label.all { it.isLetterOrDigit() || it == '-' }) return null
        val path = uri.rawPath ?: return null
        if (!path.endsWith("/wrpc/borsh") || !path.contains("/$networkId/")) return null
        // parseNodeAddress turns the Borsh path into the JSON one.
        return parseNodeAddress("wss://$nodeHost$path")?.key
    }
}
