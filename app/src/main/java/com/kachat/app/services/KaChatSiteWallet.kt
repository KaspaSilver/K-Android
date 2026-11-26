package com.kachat.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.kachat.app.services.tokens.TrestlyProvider
import com.kachat.app.ui.screens.SendSource
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.Schnorr
import com.kachat.app.util.Secp256k1
import com.kachat.app.util.UtxoMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes one [KaChatSiteWallet] per open browser (iOS's `@StateObject` in `InAppBrowserScreen`),
 * with the app's services; reached from the browser's composable through a Hilt entry point.
 */
@Singleton
class KaChatSiteWalletFactory @Inject constructor(
    internal val walletManager: WalletManager,
    internal val nodePoolManager: NodePoolManager,
    @ApplicationContext internal val context: Context,
) {
    fun make(): KaChatSiteWallet = KaChatSiteWallet(this)
}

/**
 * K as a wallet for a Kaspa website opened in K's own browser (`InAppBrowserScreen`) (iOS 48f5c357
 * `KaChatSiteWallet`): the same `window.kachat` provider the K browser extension puts on pages
 * (K-Browser-Extension `public/inpage.js`), so a site that supports the extension works in the app
 * unchanged. Kasshi (kasshi.io) is the first and only site (owner, 2026-11-26; KASSHI_INTEGRATION.md).
 *
 * - Injected at document start, main frame only, on an allowed https origin: androidx.webkit's
 *   origin rules ([ORIGIN_RULES]) for the script and the bridge ([BRIDGE_NAME]), the script checks
 *   the page's origin and that it is the top frame, and [handle] checks every message's origin and
 *   that it came from the main frame again, so an iframe or another site gets nothing. A WebView
 *   without the origin-bound message listener gets no provider at all.
 * - `requestAccounts` opens K's Connect sheet, where the person picks the address: the Chatting
 *   Address or another regular address of the account (never a quantum one: Kasshi checks the
 *   signature against the address the key makes). Everything after uses that address. The pick is
 *   remembered per K account and site and suggested next time.
 * - `signMessage` opens the Sign sheet (the full text); the signature is KasWare's personal message
 *   scheme, hex: schnorr(blake2b-256(key "PersonalMessageSigningHash", utf8(text))).
 * - `sendKaspa` opens K's own Send Kaspa review (`SpendingAddressSendFlow` with the address and
 *   amount fixed and From locked to the connected address), swipe up to send; the txid comes back.
 * - One sheet at a time (-32002); a decline makes the site wait 3 s before it can ask again.
 * - Nothing persists but the remembered pick: the connection goes when the browser closes.
 * - The page sees `platform: "android"` where iOS says "ios"; everything else is the same.
 *
 * Its state is touched only on the main thread (iOS's @MainActor): the bridge's callbacks run there.
 */
class KaChatSiteWallet internal constructor(private val deps: KaChatSiteWalletFactory) {

    /** An answer the page gets as `{ code, message }` (iOS `SiteError`). */
    class SiteError(val code: Int, override val message: String) : Exception(message)

    /** What the page is waiting on the person for, shown by the browser as a sheet (iOS `Request`). */
    class Request(val host: String, val kind: Kind) {
        val id: String = UUID.randomUUID().toString()
    }

    sealed class Kind {
        object Connect : Kind()
        data class Sign(val message: String) : Kind()
        data class Send(val to: String, val sompi: Long) : Kind()
    }

    /** The address the page is connected to (iOS `Connection`). */
    data class Connection(
        val source: SendSource,
        val address: String,
        /** 33-byte compressed key, hex ("02..."), as the extension and KasWare return it. */
        val publicKey: String,
    )

    /** How the page script went in ([install]). */
    enum class Install { NONE, DOCUMENT_START, PAGE_STARTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    /** A sheet is on screen (a send's Sent screen stays up after its answer): no new request until
     *  it's closed, so one sheet never replaces another. */
    var sheetOpen = false

    private var waitingAnswer: CompletableDeferred<Result<JsonElement?>>? = null
    private var declinedAt: Long? = null
    private var running = 0
    private var closed = false
    /** The last main-frame bridge reply on the allowed site: where `accountsChanged` and
     *  `disconnect` go (iOS evaluates them in the page). */
    private var eventSink: ((String) -> Unit)? = null
    private var installed = Install.NONE

    // Installing

    /**
     * Adds the bridge and the page script to [view] before it loads (iOS `install(in:)`): the
     * script at document start when the WebView can, else on every page start ([injectOnPageStarted]).
     * [Install.NONE] when the WebView has no origin-bound message listener: then no provider.
     */
    @SuppressLint("RequiresFeature")
    fun install(view: WebView): Install {
        val listener = try { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } catch (e: Exception) { false }
        if (!listener) return Install.NONE.also { installed = it }
        val documentStart = try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (e: Exception) { false }
        installed = try {
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, ORIGIN_RULES) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                handle(message.data, sourceOrigin.scheme, sourceOrigin.host, sourceOrigin.port, isMainFrame) { answer ->
                    replyProxy.postMessage(answer)
                }
            }
            if (documentStart) {
                WebViewCompat.addDocumentStartJavaScript(view, script, ORIGIN_RULES)
                Install.DOCUMENT_START
            } else {
                Install.PAGE_STARTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "the site wallet couldn't be installed: ${e.javaClass.simpleName}")
            try { WebViewCompat.removeWebMessageListener(view, BRIDGE_NAME) } catch (ignored: Exception) {}
            Install.NONE
        }
        return installed
    }

    /** An older WebView without document-start scripts: the script goes in as the page starts,
     *  only on an allowed https page. */
    fun injectOnPageStarted(view: WebView, url: String?) {
        if (installed != Install.PAGE_STARTED || url == null) return
        val uri = try { java.net.URI(url) } catch (e: Exception) { return }
        if (uri.scheme?.lowercase() != "https" || !isAllowed(uri.host)) return
        view.evaluateJavascript(script, null)
    }

    /** The browser closed: a sheet still up is declined, and nothing more is answered. */
    fun close() {
        closed = true
        finish(Result.failure(SiteError(Code.REJECTED, "K closed this page.")))
        _pending.value = null
        _connection.value = null
        eventSink = null
    }

    // Answering

    /**
     * One message from the page's bridge (iOS `userContentController(_:didReceive:)`). [reply] sends
     * the answer back to the page; a message without an id gets none. Only the allowed site's own
     * page, never a frame or another origin, and the page wrapper's limits, since a page can post to
     * the bridge directly.
     */
    fun handle(data: String?, scheme: String?, host: String?, port: Int, isMainFrame: Boolean, reply: (String) -> Unit) {
        if (data == null || data.length > MAX_MESSAGE_CHARS) return
        val message = TrestlyProvider.parseStrict(data)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val id = message.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try { it.asBigDecimal.toBigIntegerExact().longValueExact() } catch (e: ArithmeticException) { null }
        } ?: return
        fun send(result: JsonElement?, error: SiteError?) {
            try { reply(answerJson(id, result, error)) } catch (e: Exception) { Log.w(TAG, "the page is gone: an answer was dropped") }
        }

        if (!isAllowedOrigin(scheme, host, port, isMainFrame)) {
            send(null, SiteError(Code.UNAUTHORIZED, "This page can't use K."))
            return
        }
        val method = message.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (method == null || method.length > 64) {
            send(null, SiteError(Code.INVALID, "Expected { method, params }."))
            return
        }
        val params = message.get("params")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        if (params.toString().length > MAX_PARAMS_CHARS) {
            send(null, SiteError(Code.INVALID, "Params are too long."))
            return
        }
        if (running >= MAX_RUNNING) {
            send(null, SiteError(Code.BUSY, "K is still answering this page's earlier requests."))
            return
        }
        eventSink = reply
        running += 1
        val siteHost = host.orEmpty().lowercase()
        scope.launch {
            try {
                send(answer(method, params, siteHost), null)
            } catch (e: SiteError) {
                send(null, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(null, SiteError(Code.UNAVAILABLE, e.message ?: "K couldn't answer."))
            } finally {
                running -= 1
            }
        }
    }

    private suspend fun answer(method: String, params: JsonArray, host: String): JsonElement? {
        if (closed) throw SiteError(Code.UNAUTHORIZED, "K closed this page.")
        return when (method) {
            // only a connected site learns the network, as in the extension
            "getNetwork" -> if (_connection.value == null) JsonNull.INSTANCE else JsonPrimitive(networkName)
            "getAccounts" -> JsonArray().apply { _connection.value?.let { add(it.address) } }
            "requestAccounts" -> {
                _connection.value?.let { return JsonArray().apply { add(it.address) } }
                ask(Kind.Connect, host)
                val connected = _connection.value ?: throw SiteError(Code.REJECTED, "Request rejected in K.")
                JsonArray().apply { add(connected.address) }
            }
            "getPublicKey" -> JsonPrimitive((_connection.value ?: throw notConnected()).publicKey)
            "getBalance" -> balance((_connection.value ?: throw notConnected()).address)
            "disconnect" -> {
                if (_connection.value != null) {
                    _connection.value = null
                    emit("accountsChanged", JsonArray())
                    emit("disconnect", JsonNull.INSTANCE)
                }
                JsonPrimitive(true)
            }
            "signMessage" -> {
                if (_connection.value == null) throw notConnected()
                val text = params.firstOrNull()?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (text.isNullOrEmpty()) throw SiteError(Code.INVALID, "signMessage: the message is required.")
                if (com.kachat.app.models.PaymentRequest.characterCount(text) > MAX_MESSAGE_LENGTH) {
                    throw SiteError(Code.INVALID, "signMessage: messages are limited to 4096 characters.")
                }
                ask(Kind.Sign(text), host)
            }
            "sendKaspa" -> {
                if (_connection.value == null) throw notConnected()
                val (to, sompi) = parseSendParams(params)
                ask(Kind.Send(to, sompi), host)
            }
            else -> throw SiteError(Code.UNSUPPORTED, "K does not support ${method.take(40)}.")
        }
    }

    // The person's answer

    /** Shows the sheet and waits for it (iOS `ask`). One at a time; a decline holds the site off for 3 s. */
    private suspend fun ask(kind: Kind, host: String): JsonElement? {
        if (_pending.value != null || waitingAnswer != null || sheetOpen) {
            throw SiteError(Code.BUSY, "A request from this site is already waiting in K.")
        }
        declinedAt?.let {
            if (System.currentTimeMillis() - it < DECLINE_PAUSE_MS) {
                throw SiteError(Code.REJECTED, "The last request from this site was just rejected in K. Wait a few seconds before asking again.")
            }
        }
        val waiting = CompletableDeferred<Result<JsonElement?>>()
        waitingAnswer = waiting
        _pending.value = Request(host, kind)
        val result = waiting.await()
        val error = result.exceptionOrNull()
        if (error is SiteError) {
            if (error.code == Code.REJECTED) declinedAt = System.currentTimeMillis()
            throw error
        }
        return result.getOrNull()
    }

    private fun finish(result: Result<JsonElement?>) {
        val waiting = waitingAnswer ?: return
        waitingAnswer = null
        waiting.complete(result)
    }

    /** The sheet went away without an answer (Cancel, a swipe down, Decline). */
    fun decline() {
        _pending.value = null
        finish(Result.failure(SiteError(Code.REJECTED, "Request rejected in K.")))
    }

    /** Connect approved with the address picked in the sheet. The sheet closes at once; the key
     *  check runs off the main thread, and the page waits for it (no other request meanwhile). */
    fun approveConnect(source: SendSource, host: String) {
        _pending.value = null
        scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.Default) { connectionFor(source) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(SiteError(Code.UNAVAILABLE, e.message ?: "K couldn't open this address's key."))
            }
            if (closed) return@launch
            val connected = result.getOrNull()
            if (connected == null) {
                finish(Result.failure(result.exceptionOrNull() ?: SiteError(Code.UNAVAILABLE, "K couldn't open this address's key.")))
                return@launch
            }
            _connection.value = connected
            rememberPick(source, host)
            finish(Result.success(JsonArray().apply { add(connected.address) }))
            emit("accountsChanged", JsonArray().apply { add(connected.address) })
        }
    }

    /** Sign approved: the connected address's key signs the text. */
    fun approveSign(text: String) {
        val connected = _connection.value ?: return decline()
        _pending.value = null
        scope.launch {
            val result = try {
                Result.success<JsonElement?>(JsonPrimitive(withContext(Dispatchers.Default) { sign(text, connected.source, connected.address) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(SiteError(Code.UNAVAILABLE, "K couldn't sign this message."))
            }
            if (!closed) finish(result)
        }
    }

    /** The send went out (K's own Send review). */
    fun sent(txId: String) {
        _pending.value = null
        finish(Result.success(JsonPrimitive(txId)))
    }

    // Keys

    /** The Chatting Address, in this network's form. */
    private fun chattingAddress(): String? = try { KaspaNetwork.reencode(deps.walletManager.getAddress()) } catch (e: Exception) { null }

    private fun privateKey(source: SendSource): ByteArray? = try {
        when (source) {
            SendSource.Chatting -> deps.walletManager.getPrivateKeyBytes()
            is SendSource.Spending ->
                if (WalletManager.isQuantumIndex(source.index)) null else deps.walletManager.getSpendingPrivateKeyBytes(source.index)
        }
    } catch (e: Exception) {
        null
    }

    /** The picked address with its public key, checked: the key must make that address. */
    private fun connectionFor(source: SendSource): Connection {
        val address = source.address(chattingAddress())?.lowercase()
        val key = privateKey(source)
        if (address == null || key == null) throw SiteError(Code.UNAVAILABLE, "K couldn't open this address's key.")
        try {
            if (addressOfKey(key).lowercase() != address) {
                throw SiteError(Code.UNAVAILABLE, "This address can't connect to a website.")
            }
            return Connection(source, address, compressedPublicKeyHex(key))
        } finally {
            key.fill(0)
        }
    }

    /** KasWare's personal message signature, hex, by [source]'s key, which must make [address]. */
    private fun sign(text: String, source: SendSource, address: String): String {
        val key = privateKey(source) ?: throw SiteError(Code.UNAVAILABLE, "No key")
        try {
            if (addressOfKey(key).lowercase() != address.lowercase()) throw SiteError(Code.UNAVAILABLE, "Key mismatch")
            return personalMessageSignature(text, key)
        } finally {
            key.fill(0)
        }
    }

    // Balance

    /** `{confirmed, unconfirmed, total}` in sompi: the plain KAS coins the node holds at it. */
    private suspend fun balance(address: String): JsonObject {
        val utxos = withContext(Dispatchers.IO) { deps.nodePoolManager.getUtxosByAddress(address) }
            ?: throw SiteError(Code.UNAVAILABLE, "K couldn't read this address's balance just now.")
        val total = UtxoMath.checkedTotal(utxos.filter { it.utxoEntry.covenantId == null }.map { it.utxoEntry.amount })
            ?: throw SiteError(Code.UNAVAILABLE, "K couldn't read this address's balance just now.")
        return JsonObject().apply {
            addProperty("confirmed", total)
            addProperty("unconfirmed", 0)
            addProperty("total", total)
        }
    }

    // Events

    private fun emit(event: String, payload: JsonElement) {
        val sink = eventSink ?: return
        val json = JsonObject().apply {
            addProperty("event", event)
            add("payload", payload)
        }.toString()
        try { sink(json) } catch (e: Exception) { Log.w(TAG, "the page is gone: an event was dropped") }
    }

    // The remembered pick

    private val prefs get() = deps.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The account's key ([pickKey]); null while signed out. */
    private fun accountPickKey(): String? = deps.walletManager.getActiveAccount()?.address?.let(::pickKey)

    private fun rememberPick(source: SendSource, host: String) {
        val key = accountPickKey() ?: return
        val all = readPicks(prefs.getString(key, null))
        all.addProperty(siteKey(host), source.spendingIndex ?: -1)
        prefs.edit().putString(key, all.toString()).apply()
    }

    /** The spending address index picked last time on this site (null: the Chatting Address), if
     *  it's still one this account can connect (iOS `rememberedSpendingIndex(host:)`). */
    fun rememberedSpendingIndex(host: String): Int? {
        val account = deps.walletManager.getActiveAccount() ?: return null
        val stored = readPicks(prefs.getString(pickKey(account.address), null)).get(siteKey(host))
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { try { it.asInt } catch (e: Exception) { null } }
        return rememberedIndex(stored, WalletManager.legacyMaxSpendingIndex(account))
    }

    private fun notConnected() = SiteError(Code.UNAUTHORIZED, "Connect first: call kachat.requestAccounts().")

    /** The extension's error codes (iOS `Code`). */
    object Code {
        const val REJECTED = 4001
        const val MAYBE_SENT = 4002
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val UNAVAILABLE = 4900
        const val BUSY = -32002
        const val INVALID = -32602
    }

    companion object {
        private const val TAG = "KaChatSiteWallet"

        /** The bridge's name in the page: `window.kachatSite` (iOS `handlerName`), only on allowed
         *  origins, taken away by the script before the page's own scripts run. */
        const val BRIDGE_NAME = "kachatSite"

        /** Sites that get the provider: the host itself and its subdomains, https only (iOS
         *  `allowedHosts`). */
        val ALLOWED_HOSTS: List<String> = listOf("kasshi.io")

        /** androidx.webkit's origin rules for the bridge and the script: each host and its
         *  subdomains, https on the default port. */
        val ORIGIN_RULES: Set<String> = ALLOWED_HOSTS.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

        /** Where the remembered picks live; one entry per account ([pickKey]). */
        const val PREFS_NAME = "kachat_site_wallet"

        private const val DECLINE_PAUSE_MS = 3_000L
        private const val MAX_RUNNING = 8
        private const val MAX_MESSAGE_LENGTH = 4096
        /** The page's wrapper sends at most 8192 characters of params. */
        private const val MAX_PARAMS_CHARS = 8192
        private const val MAX_MESSAGE_CHARS = 16_384

        /** K's running network as the provider names it. */
        val networkName: String
            get() = if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET) "mainnet" else "testnet-10"

        /** iOS `isAllowed(host:)`. */
        fun isAllowed(host: String?): Boolean {
            val lower = host?.lowercase()
            if (lower.isNullOrEmpty()) return false
            return ALLOWED_HOSTS.any { lower == it || lower.endsWith(".$it") }
        }

        /** The bridge's check of where a message came from: the main frame of an allowed https
         *  page on the default port (iOS checks `frameInfo.isMainFrame` and its security origin). */
        fun isAllowedOrigin(scheme: String?, host: String?, port: Int, isMainFrame: Boolean): Boolean =
            isMainFrame && scheme?.lowercase() == "https" && isAllowed(host) && (port <= 0 || port == 443)

        /** kasshi.io and www.kasshi.io are one site. */
        fun siteKey(host: String): String = ALLOWED_HOSTS.firstOrNull { host == it || host.endsWith(".$it") } ?: host

        /** The remembered picks of one K account: `site_connections_<account address, lowercased>`
         *  in [PREFS_NAME] (iOS `kachat_site_connections_<address>`). */
        fun pickKey(account: String): String = "site_connections_${account.lowercase()}"

        /** A remembered spending index still worth suggesting: a regular (never quantum) address
         *  within the account's revealed ones. */
        fun rememberedIndex(stored: Int?, legacyMax: Int): Int? =
            stored?.takeIf { it >= 0 && !WalletManager.isQuantumIndex(it) && it <= legacyMax }

        private fun readPicks(text: String?): JsonObject =
            text?.let { TrestlyProvider.parseStrict(it) }?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        /**
         * `sendKaspa`'s `[toAddress, sompi]` (iOS `answer` "sendKaspa"): an address of the running
         * network ([hrp]) and a whole number of sompi above zero, at most 19 digits.
         */
        fun parseSendParams(params: JsonArray, hrp: String = KaspaNetwork.hrp): Pair<String, Long> {
            val to = params.firstOrNull()?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.lowercase().orEmpty()
            if (!KaspaAddress.isValidOnNetwork(to, hrp)) {
                throw SiteError(Code.INVALID, "sendKaspa: a $hrp: address is required.")
            }
            val amountText = params.takeIf { it.size() > 1 }?.get(1)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val sompi = amountText.takeIf { SOMPI_TEXT.matches(it) }?.toLongOrNull()
            if (sompi == null || sompi <= 0) {
                throw SiteError(Code.INVALID, "sendKaspa: the amount is a whole number of sompi above zero.")
            }
            return to to sompi
        }

        private val SOMPI_TEXT = Regex("^\\d{1,19}$")

        /** The address a key makes on [hrp] (a Schnorr P2PK address). */
        fun addressOfKey(privateKey: ByteArray, hrp: String = KaspaNetwork.hrp): String =
            KaspaAddress.encode(hrp, 0x00, Schnorr.publicKeyXOnly(privateKey))

        /** The 33-byte compressed public key, hex ("02..." / "03..."). */
        fun compressedPublicKeyHex(privateKey: ByteArray): String =
            Secp256k1.G.multiply(BigInteger(1, privateKey)).normalize().getEncoded(true).joinToString("") { "%02x".format(it) }

        /** KasWare's personal message signature, hex: schnorr(blake2b-256 keyed
         *  "PersonalMessageSigningHash" over utf8([text])). */
        fun personalMessageSignature(text: String, privateKey: ByteArray): String =
            KaspaMessageSigner.sign(text, privateKey, KaspaMessageSigner.SigningMode.KASPA_PERSONAL_MESSAGE)

        private fun answerJson(id: Long, result: JsonElement?, error: SiteError?): String = JsonObject().apply {
            addProperty("id", id)
            if (error != null) {
                add("error", JsonObject().apply {
                    addProperty("code", error.code)
                    addProperty("message", error.message)
                })
            } else {
                add("result", result ?: JsonNull.INSTANCE)
            }
        }.toString()

        /** The page side: `window.kachat`, frozen, only on an allowed https origin's top frame. The
         *  same calls and shapes as the extension's inpage.js, plus `platform: "android"`. Requests
         *  go over the bridge as JSON with an id; answers and events come back the same way. */
        val script: String
            get() {
                val hosts = JsonArray().apply { ALLOWED_HOSTS.sorted().forEach { add(it) } }.toString()
                return """
                (() => {
                  'use strict';
                  try {
                    if (window.top !== window) return;
                    if (location.protocol !== 'https:') return;
                    const host = location.hostname.toLowerCase();
                    const allowed = $hosts;
                    if (!allowed.some((h) => host === h || host.endsWith('.' + h))) return;
                    if (window.kachat) return;
                    const bridge = window.$BRIDGE_NAME;
                    if (!bridge || typeof bridge.postMessage !== 'function') return;
                    try { delete window.$BRIDGE_NAME; } catch (e) {}
                    const listeners = { accountsChanged: new Set(), disconnect: new Set(), networkChanged: new Set() };
                    class KaChatWalletError extends Error {
                      constructor(message, code, txid) {
                        super(message);
                        this.name = 'KaChatWalletError';
                        this.code = code;
                        if (typeof txid === 'string' && /^[0-9a-f]{64}$/.test(txid)) this.txid = txid;
                      }
                    }
                    const pending = new Map();
                    let nextId = 1;
                    bridge.addEventListener('message', (event) => {
                      let d;
                      try { d = JSON.parse(event.data); } catch (e) { return; }
                      if (!d || typeof d !== 'object') return;
                      if (typeof d.event === 'string') {
                        if (!listeners[d.event]) return;
                        for (const handler of listeners[d.event]) { try { handler(d.payload); } catch (err) { console.error(err); } }
                        return;
                      }
                      if (typeof d.id !== 'number') return;
                      const waiting = pending.get(d.id);
                      if (!waiting) return;
                      pending.delete(d.id);
                      if (d.error) waiting.reject(new KaChatWalletError(d.error.message || 'Request failed.', d.error.code, d.error.txid));
                      else waiting.resolve(d.result === undefined ? null : d.result);
                    });
                    const call = (method, params) => {
                      let text;
                      try { text = JSON.stringify(params || []); } catch (e) { return Promise.reject(new KaChatWalletError('Params must be JSON.', ${Code.INVALID})); }
                      if (text.length > $MAX_PARAMS_CHARS) return Promise.reject(new KaChatWalletError('Params are too long.', ${Code.INVALID}));
                      const id = nextId++;
                      return new Promise((resolve, reject) => {
                        pending.set(id, { resolve, reject });
                        try {
                          bridge.postMessage(JSON.stringify({ id, method, params: JSON.parse(text) }));
                        } catch (e) {
                          pending.delete(id);
                          reject(new KaChatWalletError(String((e && e.message) || e), ${Code.UNAVAILABLE}));
                        }
                      });
                    };
                    const provider = Object.freeze({
                      isKaChat: true,
                      version: '1',
                      platform: 'android',
                      requestAccounts: () => call('requestAccounts'),
                      getAccounts: () => call('getAccounts'),
                      getNetwork: () => call('getNetwork'),
                      getPublicKey: () => call('getPublicKey'),
                      getBalance: () => call('getBalance'),
                      sendKaspa: (toAddress, sompi, options = {}) => call('sendKaspa', [String(toAddress), String(sompi), { priorityFee: options && options.priorityFee != null ? String(options.priorityFee) : '0' }]),
                      signMessage: (message) => call('signMessage', [String(message)]),
                      disconnect: () => call('disconnect'),
                      on(event, handler) { if (listeners[event] && typeof handler === 'function') listeners[event].add(handler); return provider; },
                      removeListener(event, handler) { if (listeners[event]) listeners[event].delete(handler); return provider; },
                    });
                    Object.defineProperty(window, 'kachat', { value: provider, writable: false, configurable: false, enumerable: true });
                    window.dispatchEvent(new Event('kachat#initialized'));
                  } catch (e) {}
                })();
                """.trimIndent()
            }
    }
}
