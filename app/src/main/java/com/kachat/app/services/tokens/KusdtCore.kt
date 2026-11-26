package com.kachat.app.services.tokens

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Trestly's own kUSDT transaction builders (kusdt-open `bridge/packages/core/pkg-web`, the same
 * WebAssembly their wallet runs), run inside KaChat as in KaPay (owner's call, 2026-11-19): a
 * KCC-20 input's unlock script and compute budget come from running the token contract, which only
 * their runtime does exactly. iOS `KusdtCore.swift` (148a5331), on an Android [WebView].
 *
 * How it's kept safe:
 * - The files ship in the app (`assets/kusdt/`, byte for byte iOS's: the WebAssembly, its loader,
 *   iOS's own `kusdt-core.html` and Trestly's bundled wallet logic `kusdt-wallet.js`) and are checked
 *   against the SHA-256s below (iOS's pins) before anything loads; the WebAssembly's is the one in
 *   kusdt-open's PROVENANCE.json. Read and hashed off the main thread (~8 MB).
 * - They're served from memory by [WebViewClient.shouldInterceptRequest] at a fixed origin
 *   ([ORIGIN], Android's reserved in-app asset host) to a hidden web view that's never shown.
 *   Every other request gets an empty 403 there and the page can't navigate. Its
 *   Content-Security-Policy ([CSP], a header, so the pinned page stays byte for byte, plus the
 *   page's own) lets it run only its own scripts and reach only its own WebAssembly and the public
 *   Kaspa node WebSockets Trestly's pool code opens (`wss://<name>.kaspa.stream`, `.red`, `.green`,
 *   `.blue`; iOS's content rule list). The page holds a spending key during a send, so it reaches
 *   nowhere else. No file or content access, no storage, no cache.
 * - KaChat talks to it over one bridge ([BRIDGE], `addWebMessageListener`) that only a main frame
 *   on [ORIGIN] gets. The page calls `window.webkit.messageHandlers` as on iOS: [SHIM], put in at
 *   document start on [ORIGIN] only, makes them from that bridge. A key goes in only for the
 *   address being spent from, and only for that call.
 * - Trestly's wallet logic for the pool (`kusdt-wallet.js`, [wallet]) runs beside it for trades on
 *   Trestly's site. Its HTTP goes through [KusdtNetRelay] (the Kaspa resolvers and the Trestly API
 *   only) and what it keeps through [KusdtStoreRelay].
 * - A start that doesn't say ready within 25 s, or a renderer that dies, fails what's waiting; the
 *   next call starts it again (after 10 s for a failed start). Every call has its own time limit.
 *
 * Threading: [run] and [wallet] are suspend functions safe to call from any dispatcher; the web
 * view itself is only ever touched on the main thread.
 */
@Singleton
class KusdtCore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val netRelay: KusdtNetRelay,
    private val storeRelay: KusdtStoreRelay,
) {
    /** Why the builder didn't answer ([Unavailable]: couldn't start or gave no answer, tried
     *  again next time) or refused the request ([Refused]: its own `{"error": ...}`). */
    sealed class CoreError(message: String) : Exception(message) {
        class Unavailable(message: String) : CoreError(message)
        class Refused(message: String) : CoreError(message)
        /** `execute` held a key and gave no answer (time ran out, the page died or threw): the
         *  trade may have gone out anyway (iOS `CoreError.maybeSent`, audit 2026-11-21 #1). */
        class MaybeSent(message: String) : CoreError(message)
    }

    private class CallAnswer(val text: String?, val error: String?)

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Main thread only, except [files], which the web view reads on its own threads.
    private var webView: WebView? = null
    @Volatile private var files: Map<String, ByteArray> = emptyMap()
    private var ready = false
    private val waiting = ArrayList<CompletableDeferred<Unit>>()
    private var loadError: String? = null
    /** When the last start failed: a later call tries again after [RETRY_AFTER_MS], so one failure
     *  (a page killed in the background, a slow start) doesn't turn kUSDT off for the session. */
    private var loadFailedAt: Long? = null
    /** The pinned files are being checked (off the main thread) before the page exists. */
    private var starting = false
    /** Trestly's wallet logic (`window.kapayWallet`): loaded after the core, so a failure there never
     *  stops kUSDT sends. */
    private var walletReady = false
    private var walletError: String? = null
    private val walletWaiting = ArrayList<CompletableDeferred<Unit>>()
    /** The page's end of the bridge, from its first message. */
    private var replyProxy: JavaScriptReplyProxy? = null
    private var nextCallId = 1L
    private val calls = HashMap<Long, CompletableDeferred<CallAnswer>>()

    // MARK: Calls

    /**
     * Runs one builder command on a JSON request; answers the result as JSON, or throws the
     * builder's own error ([CoreError.Refused]) or [CoreError.Unavailable].
     */
    suspend fun run(command: String, request: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        load()
        val text = call(kind = "run", fn = null, command = command, request = request.toString(), timeoutMs = callTimeoutMs("run"))
            ?: throw CoreError.Unavailable(context.getString(R.string.kusdt_builder_no_answer))
        val parsed = try { JSONObject(text) } catch (e: Exception) {
            throw CoreError.Unavailable(context.getString(R.string.kusdt_builder_no_answer))
        }
        if (parsed.has("error")) throw CoreError.Refused(parsed.optString("error"))
        parsed
    }

    /**
     * Runs `window.kapayWallet.prepare` or `.execute` (the iPhone repo's tools/kusdt-wallet/src/entry.ts)
     * on one request; answers its JSON, which carries `error` (and maybe `code`) when it refused.
     * iOS `wallet(_:request:)`.
     */
    suspend fun wallet(function: String, request: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        if (function != "prepare" && function != "execute") throw CoreError.Unavailable("bad call")
        load()
        loadWallet()
        // From the post on, `execute` holds the key: no answer may mean the trade went out anyway,
        // so it says so and the caller holds the address (iOS `wallet`, audit 2026-11-21 #1).
        val mayLand = function == "execute"
        val noAnswer = context.getString(R.string.kp_trestly_pool_code_no_answer)
        val text = call(kind = "wallet", fn = function, command = null, request = request.toString(), timeoutMs = callTimeoutMs(function), sentMayLand = mayLand)
            ?: throw (if (mayLand) CoreError.MaybeSent(noAnswer) else CoreError.Unavailable(noAnswer))
        parseObject(text) ?: throw (if (mayLand) CoreError.MaybeSent(noAnswer) else CoreError.Unavailable(noAnswer))
    }

    /** Sends one call to the page and waits for its answer, at most [timeoutMs]. Null: no answer.
     *  [sentMayLand]: once posted, a missing answer is [CoreError.MaybeSent], not Unavailable. */
    private suspend fun call(kind: String, fn: String?, command: String?, request: String, timeoutMs: Long, sentMayLand: Boolean = false): String? {
        val proxy = replyProxy ?: throw CoreError.Unavailable(context.getString(R.string.kusdt_builder_couldnt_start))
        val id = nextCallId++
        val answer = CompletableDeferred<CallAnswer>()
        calls[id] = answer
        // a spending key goes in with a transfer's coins and with a trade's execute
        if ((kind == "wallet" && fn == "execute") || request.contains("\"secretKey\"")) keyHeld = true
        try {
            val message = JsonObject().apply {
                addProperty("t", "call")
                addProperty("id", id)
                addProperty("kind", kind)
                fn?.let { addProperty("fn", it) }
                command?.let { addProperty("command", it) }
                addProperty("request", request)
            }
            try {
                proxy.postMessage(message.toString())
            } catch (e: Exception) {
                throw CoreError.Unavailable(context.getString(R.string.kusdt_builder_no_answer))
            }
            fun noAnswer(): CoreError = if (sentMayLand) {
                CoreError.MaybeSent(context.getString(R.string.kp_trestly_pool_code_no_answer))
            } else {
                CoreError.Unavailable(context.getString(R.string.kusdt_builder_no_answer))
            }
            // a review or trade never spins forever (iOS audit, 2026-11-20); the page dying while
            // it ran (tearDown) is no answer too
            val got = try {
                withTimeoutOrNull(timeoutMs) { answer.await() }
            } catch (e: CoreError.Unavailable) {
                throw noAnswer()
            } ?: run {
                // The script may still be running in the page (an `execute` that may yet send its
                // trade): the page isn't closed under it until it answers (iOS 961fc05a).
                orphanedCalls[id] = System.currentTimeMillis()
                throw noAnswer()
            }
            got.error?.let { why ->
                // iOS: callAsyncJavaScript throws when the page's code does
                Log.w(TAG, "the builder page threw: ${why.take(200)}")
                throw noAnswer()
            }
            return got.text
        } finally {
            calls.remove(id)
            scheduleIdleTeardown()
        }
    }

    /**
     * The page lives only while it's used: about two minutes after the last call it's closed (the
     * 8 MB WebAssembly, the renderer and the pool code's node WebSocket with it), and the next call
     * starts it again; it stayed up all session (iOS 961fc05a, audit 2026-11-25 B20). Sooner when a
     * key went into the page since it started: its JS heap may still hold the key as a string, so
     * it goes once nothing has used it for [KEY_IDLE_RETIRE_MS] (audit L14). Calls in a row (a
     * transfer's retries, a requote) keep it up, and so does a call that gave no answer in time
     * and may still be running there ([orphanedCalls]).
     */
    private var keyHeld = false
    private var retireJob: kotlinx.coroutines.Job? = null
    /** Calls that timed out, by id, with when: their script may still run in the page. */
    private val orphanedCalls = HashMap<Long, Long>()

    /** Main thread. */
    private fun scheduleIdleTeardown() {
        val view = webView ?: return
        retireJob?.cancel()
        retireJob = main.launch {
            delay(if (keyHeld) KEY_IDLE_RETIRE_MS else IDLE_TEARDOWN_MS)
            val now = System.currentTimeMillis()
            orphanedCalls.entries.removeAll { now - it.value > ORPHANED_CALL_MAX_MS }
            // a call still running re-arms this when it ends
            if (webView !== view || calls.isNotEmpty() || waiting.isNotEmpty() || walletWaiting.isNotEmpty() ||
                starting || orphanedCalls.isNotEmpty()) return@launch
            Log.i(TAG, if (keyHeld) "the builder page held a key: closed until the next use" else "builder page idle: closed until the next use")
            tearDown()
            walletReady = false
            walletError = null
        }
    }

    private suspend fun loadWallet() {
        if (walletReady) return
        walletError?.let { throw CoreError.Unavailable(it) }
        val wait = CompletableDeferred<Unit>()
        walletWaiting += wait
        wait.await()
    }

    /** The page never said whether Trestly's code loaded: give up rather than wait forever. */
    private fun armWalletTimeout(view: WebView) {
        main.launch {
            delay(START_TIMEOUT_MS)
            if (webView !== view || walletReady || walletError != null || (walletWaiting.isEmpty() && !ready)) return@launch
            finishWallet(context.getString(R.string.kp_trestly_pool_code_couldnt_start_kc))
        }
    }

    private fun finishWallet(error: String?) {
        if (error != null) walletError = error else walletReady = true
        // the page has everything it asked for: the 8 MB of files needn't stay in memory
        if (ready || error != null) files = emptyMap()
        val waits = walletWaiting.toList()
        walletWaiting.clear()
        for (w in waits) {
            if (error != null) w.completeExceptionally(CoreError.Unavailable(error)) else w.complete(Unit)
        }
    }

    // MARK: Loading

    private suspend fun load() {
        if (ready) return
        loadError?.let { error ->
            // a start that failed a while ago is tried again; one that just failed answers at once
            val failed = loadFailedAt
            if (failed == null || System.currentTimeMillis() - failed <= RETRY_AFTER_MS) throw CoreError.Unavailable(error)
            loadError = null
            walletError = null
        }
        val wait = CompletableDeferred<Unit>()
        waiting += wait
        if (webView == null && !starting) start()
        wait.await()
    }

    private fun start() {
        starting = true
        // every file read and hashed off the main thread (the WebAssembly is about 8 MB), and
        // checked before the web view exists
        io.launch {
            val checked = readPinnedFiles()
            withContext(Dispatchers.Main.immediate) {
                starting = false
                if (checked == null) {
                    finish(context.getString(R.string.kusdt_builder_not_pinned))
                } else {
                    files = checked
                    openPage()
                }
            }
        }
    }

    /** The pinned files, each checked against its SHA-256; null if any is missing or not the
     *  pinned one. */
    private fun readPinnedFiles(): Map<String, ByteArray>? {
        val out = HashMap<String, ByteArray>()
        for ((name, sha) in PINNED) {
            val data = try { context.assets.open("kusdt/$name").use { it.readBytes() } } catch (e: Exception) { null }
            if (data == null || Kusdt.hex(MessageDigest.getInstance("SHA-256").digest(data)) != sha) {
                Log.w(TAG, "the builder file $name is missing or not the pinned one: refused")
                return null
            }
            out[name] = data
        }
        return out
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun openPage() {
        val listener = try { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } catch (e: Exception) { false }
        if (!listener) {
            Log.w(TAG, "this WebView has no message listener: the builder can't run")
            finish(context.getString(R.string.kusdt_builder_couldnt_start))
            return
        }
        val documentStart = try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Exception) { false }
        val view = try { WebView(context) } catch (e: Exception) {
            // no WebView on this device (disabled or updating)
            Log.w(TAG, "the builder's web view couldn't be made: ${e.javaClass.simpleName}")
            finish(context.getString(R.string.kusdt_builder_couldnt_start))
            return
        }
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkImage = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            // no URL checks against Google's lists: it loads nothing from the network to check
            safeBrowsingEnabled = false
        }
        try {
            WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, proxy ->
                // only the page itself, on its own origin
                if (!isMainFrame || !isOwnOrigin(sourceOrigin)) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                main.launch { if (webView === view) received(data, proxy, view) }
            }
            if (documentStart) WebViewCompat.addDocumentStartJavaScript(view, SHIM, setOf(ORIGIN))
        } catch (e: Exception) {
            Log.w(TAG, "the builder's bridge couldn't be set up: ${e.javaClass.simpleName}")
            view.destroy()
            finish(context.getString(R.string.kusdt_builder_couldnt_start))
            return
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse =
                serve(request?.url)

            /** The page goes nowhere. */
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean = true

            override fun onPageFinished(v: WebView?, url: String?) {
                // a WebView without document-start scripts: the bridge's names go in now, and
                // readiness is asked for, since the page's own word may have come too early
                if (!documentStart && v === view) {
                    v.evaluateJavascript(SHIM, null)
                    pollReadiness(view)
                }
            }

            override fun onReceivedError(v: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) main.launch { if (webView === view && !ready) finish(context.getString(R.string.kusdt_builder_couldnt_start)) }
            }

            /** The hidden page's process was killed (memory pressure, the app in the background):
             *  start over on the next call instead of answering "no answer" until a relaunch. The
             *  web view must not be used again, and returning true keeps the app alive. */
            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                main.launch { renderProcessGone(view) }
                return true
            }
        }
        webView = view
        view.loadUrl("$ORIGIN/kusdt/$PAGE")
        // a page that never reports (its module failed to load before any code ran) fails here
        main.launch {
            delay(START_TIMEOUT_MS)
            if (!ready && webView === view) {
                Log.w(TAG, "the builder didn't start in time")
                finish(context.getString(R.string.kusdt_builder_couldnt_start))
            }
        }
        armWalletTimeout(view)
    }

    /** Only without document-start scripts: asks every 100 ms whether the core is up; once it is,
     *  the page says so through the bridge (so its end is known) and Trestly's code is loaded. */
    private fun pollReadiness(view: WebView) {
        main.launch {
            while (webView === view && !ready) {
                val answer = withTimeoutOrNull(1_000L) {
                    suspendCancellableCoroutine<String?> { cont ->
                        view.evaluateJavascript("(function(){return !!(window.kusdtCore&&window.kusdtCore.ready);})()") { cont.resume(it) }
                    }
                }
                if (answer == "true" && webView === view && !ready) {
                    view.evaluateJavascript(LATE_START, null)
                    return@launch
                }
                delay(100)
            }
        }
    }

    /** What the page may load: the pinned files from memory, at [ORIGIN]/kusdt/. Anything else is
     *  an empty 403, never a network request. Runs on the web view's own threads. */
    private fun serve(url: Uri?): WebResourceResponse {
        val name = url?.lastPathSegment.orEmpty()
        val data = if (url != null && "${url.scheme}://${url.host}" == ORIGIN && url.port == -1 &&
            url.path == "/kusdt/$name" && url.query == null) files[name] else null
        if (data == null) {
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        }
        val mime = when {
            name.endsWith(".wasm") -> "application/wasm"
            name.endsWith(".js") -> "text/javascript"
            else -> "text/html"
        }
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        headers["X-Content-Type-Options"] = "nosniff"
        headers["Content-Length"] = data.size.toString()
        if (name == PAGE) headers["Content-Security-Policy"] = CSP
        return WebResourceResponse(mime, if (mime == "application/wasm") null else "utf-8", 200, "OK", headers, ByteArrayInputStream(data))
    }

    /** Main thread. */
    private fun finish(error: String?) {
        if (error != null) {
            loadError = error
            loadFailedAt = System.currentTimeMillis()
            tearDown()
            finishWallet(error)
        } else {
            ready = true
        }
        val waits = waiting.toList()
        waiting.clear()
        for (w in waits) {
            if (error != null) w.completeExceptionally(CoreError.Unavailable(error)) else w.complete(Unit)
        }
    }

    /** Main thread. The page and everything it was answering go. */
    private fun tearDown() {
        val view = webView
        webView = null
        replyProxy = null
        files = emptyMap()
        ready = false
        keyHeld = false
        retireJob?.cancel()
        retireJob = null
        orphanedCalls.clear()
        val pending = calls.values.toList()
        calls.clear()
        pending.forEach { it.completeExceptionally(CoreError.Unavailable(context.getString(R.string.kusdt_builder_no_answer))) }
        view?.let { destroy(it) }
    }

    private fun destroy(view: WebView) {
        try {
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroying the builder's web view: ${e.javaClass.simpleName}")
        }
    }

    /** Main thread. */
    private fun renderProcessGone(view: WebView) {
        if (view !== webView) {
            destroy(view)
            return
        }
        Log.w(TAG, "the builder's renderer ended: it starts again on the next use")
        tearDown()
        walletReady = false
        walletError = null
        loadError = null
        val waits = waiting.toList()
        waiting.clear()
        waits.forEach { it.completeExceptionally(CoreError.Unavailable(context.getString(R.string.kusdt_builder_couldnt_start))) }
        val walletWaits = walletWaiting.toList()
        walletWaiting.clear()
        walletWaits.forEach { it.completeExceptionally(CoreError.Unavailable(context.getString(R.string.kp_trestly_pool_code_couldnt_start_kc))) }
    }

    // MARK: The bridge

    /** Main thread. One message from [SHIM]: the page's own `kusdtCore` word (ready, the wallet
     *  logic's state, a failed start), a call's answer, or a relay request from Trestly's code. */
    private fun received(data: String, proxy: JavaScriptReplyProxy, view: WebView) {
        val message = parseObject(data) ?: return
        when (message.string("t")) {
            "core" -> {
                val body = message.get("body")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                when {
                    body.get("ready")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true -> {
                        replyProxy = proxy
                        if (!ready) {
                            Log.i(TAG, "builder ready (${body.string("version") ?: "?"})")
                            finish(null)
                        }
                    }
                    body.get("walletReady")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true -> {
                        if (!walletReady) {
                            Log.i(TAG, "Trestly's wallet logic ready")
                            finishWallet(null)
                        }
                    }
                    body.has("walletError") -> {
                        Log.w(TAG, "Trestly's wallet logic failed to load: ${body.string("walletError")?.take(200)}")
                        if (!walletReady) finishWallet(context.getString(R.string.kp_trestly_pool_code_couldnt_start_kc))
                    }
                    body.has("error") -> {
                        Log.w(TAG, "builder failed to start: ${body.string("error")?.take(200)}")
                        if (!ready) finish(context.getString(R.string.kusdt_builder_couldnt_start))
                    }
                }
            }
            "answer" -> {
                val id = message.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return
                calls[id]?.complete(CallAnswer(message.string("answer"), message.string("error")))
                // a call that timed out has finished in the page after all: it may close now
                if (orphanedCalls.remove(id) != null) scheduleIdleTeardown()
            }
            "relay" -> {
                val id = message.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return
                val name = message.string("name")
                val body = message.get("body")?.takeIf { it.isJsonObject }?.asJsonObject
                io.launch {
                    val answer = when (name) {
                        "kusdtNet" -> netRelay.handle(body)
                        "kusdtStore" -> storeRelay.handle(body)
                        else -> JsonObject().apply { addProperty("error", "KaChat bridge $name is missing") }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (webView !== view) return@withContext
                        val reply = JsonObject().apply {
                            addProperty("t", "relayAnswer")
                            addProperty("id", id)
                            add("answer", answer)
                        }
                        try {
                            proxy.postMessage(reply.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "the builder page is gone: a relay answer was dropped")
                        }
                    }
                }
            }
        }
    }

    private fun parseObject(text: String): JsonObject? =
        try { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject } catch (e: Exception) { null }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        private const val TAG = "kUSDT"

        /** Android's reserved host for in-app content (the one androidx's asset loader uses):
         *  never a real server, and every request to it is answered here. */
        internal const val ORIGIN = "https://appassets.androidplatform.net"
        internal const val PAGE = "kusdt-core.html"
        /** The page's end of the bridge: `window.kusdtBridge`, only on [ORIGIN]'s main frame. */
        private const val BRIDGE = "kusdtBridge"

        /**
         * kusdt-open b70707c: `kusdt_wasm_bg.wasm` (PROVENANCE.json `wasm`), its loader, iOS's page
         * and Trestly's own wallet logic for the pool (wallet/extension/src/kusdt at b70707c, bundled
         * by the iPhone repo's tools/kusdt-wallet; build.mjs prints its hash), by the name the page
         * asks for, and their SHA-256: iOS 148a5331's `KusdtCore.pinned`, the same bytes (iOS ships
         * the two scripts as `.js.txt`).
         */
        internal val PINNED: Map<String, String> = linkedMapOf(
            PAGE to "b745a9a272cbf8fc593bea189af02f8ab4602c5f5a8c6317ae903e2ef12b8dc2",
            "kusdt_wasm.js" to "be01841b7f2eb700acb38c7b36b9faef7d3deeefb7c86a233731afef0052dc78",
            "kusdt_wasm_bg.wasm" to "a7be48e962dab57902924ea71fbad31b073cd2ba2197ebcf0de83b0f2a54fd53",
            "kusdt-wallet.js" to "6631a35d5c0fc2ceb00ac08611379442e6a8fe75aec36dbcb530343ffd439cb2",
        )

        /** Only its own scripts (the page's inline module, the loader, Trestly's wallet logic), its
         *  own WebAssembly fetch and the public Kaspa node WebSockets (iOS's content rule list);
         *  nothing else at all. A header, so the pinned page stays byte for byte. */
        internal const val CSP = "default-src 'none'; " +
            "script-src $ORIGIN/kusdt/kusdt_wasm.js $ORIGIN/kusdt/kusdt-wallet.js 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval'; " +
            "connect-src $ORIGIN/kusdt/kusdt_wasm_bg.wasm wss://*.kaspa.stream wss://*.kaspa.red wss://*.kaspa.green wss://*.kaspa.blue; " +
            "base-uri 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'"

        /**
         * iOS's `window.webkit.messageHandlers`, which the pinned page and Trestly's code call, made
         * from the bridge: `kusdtCore` (the page's word, no answer), `kusdtNet` and `kusdtStore`
         * (each postMessage a promise of KaChat's answer, as iOS's reply handlers). It also answers
         * KaChat's calls: `run` on the core, `prepare` / `execute` on Trestly's wallet logic.
         */
        private val SHIM = """
            (function () {
              'use strict';
              if (location.origin !== '$ORIGIN' || window.top !== window || window.webkit) return;
              var bridge = function () { return window.$BRIDGE; };
              var send = function (m) { var b = bridge(); if (b) b.postMessage(JSON.stringify(m)); };
              var nextId = 1;
              var waiting = new Map();
              var listening = false;
              var listen = function () {
                if (listening) return;
                var b = bridge();
                if (!b) return;
                listening = true;
                b.addEventListener('message', function (event) {
                  var m;
                  try { m = JSON.parse(event.data); } catch (e) { return; }
                  if (!m) return;
                  if (m.t === 'relayAnswer') {
                    var w = waiting.get(m.id);
                    if (!w) return;
                    waiting.delete(m.id);
                    w(m.answer);
                  } else if (m.t === 'call') {
                    (async function () {
                      try {
                        var answer = null;
                        if (m.kind === 'run') {
                          answer = (window.kusdtCore && window.kusdtCore.ready) ? window.kusdtCore.run(m.command, m.request) : null;
                        } else if (m.kind === 'wallet' && (m.fn === 'prepare' || m.fn === 'execute') && window.kapayWallet) {
                          answer = await window.kapayWallet[m.fn](m.request);
                        }
                        send({ t: 'answer', id: m.id, answer: typeof answer === 'string' ? answer : null });
                      } catch (e) {
                        send({ t: 'answer', id: m.id, error: String(e) });
                      }
                    })();
                  }
                });
              };
              var relay = function (name) {
                return Object.freeze({
                  postMessage: function (body) {
                    listen();
                    return new Promise(function (resolve) {
                      var id = nextId++;
                      waiting.set(id, resolve);
                      send({ t: 'relay', id: id, name: name, body: body });
                    });
                  }
                });
              };
              window.webkit = Object.freeze({ messageHandlers: Object.freeze({
                kusdtCore: Object.freeze({ postMessage: function (m) { listen(); send({ t: 'core', body: m }); } }),
                kusdtNet: relay('kusdtNet'),
                kusdtStore: relay('kusdtStore')
              }) });
              listen();
            })();
        """.trimIndent()

        /** Without document-start scripts, once the core is up: its word through the bridge, then
         *  Trestly's code (the page's own import may have failed on the missing bridge). */
        private const val LATE_START = "(function(){try{var h=window.webkit.messageHandlers.kusdtCore;" +
            "h.postMessage({ready:true,version:window.kusdtCore.version()});" +
            "if(!window.kapayWallet){import('./kusdt-wallet.js').then(function(){h.postMessage({walletReady:true});}," +
            "function(e){h.postMessage({walletError:String(e)});});}else{h.postMessage({walletReady:true});}" +
            "}catch(e){}})()"

        /** How long the page may take to come up, and the longest any one call may take (iOS
         *  148a5331). Execute covers Trestly's own waits (the pool coin aging up to a minute, then
         *  up to 30 s to see the trade accepted, merges first). */
        private const val START_TIMEOUT_MS = 25_000L
        private const val RETRY_AFTER_MS = 10_000L
        /** How long a page that held a key stays up with nothing using it. */
        private const val KEY_IDLE_RETIRE_MS = 15_000L
        /** How long the page stays up after its last call (iOS `idleTeardownAfter`). */
        private const val IDLE_TEARDOWN_MS = 120_000L
        /** A timed-out call that never answers stops holding the page up after this. */
        private const val ORPHANED_CALL_MAX_MS = 10 * 60_000L

        internal fun callTimeoutMs(function: String): Long = when (function) {
            "execute" -> 240_000L
            "prepare" -> 90_000L
            else -> 30_000L
        }

        private fun isOwnOrigin(origin: Uri): Boolean =
            "${origin.scheme}://${origin.host}" == ORIGIN && origin.port == -1
    }
}
