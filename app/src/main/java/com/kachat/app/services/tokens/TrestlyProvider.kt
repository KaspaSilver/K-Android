package com.kachat.app.services.tokens

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.AddressActivityNotifier
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.services.NodePoolManager
import com.kachat.app.services.Outpoint
import com.kachat.app.services.OutpointReservations
import com.kachat.app.services.WalletManager
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.Schnorr
import com.kachat.app.util.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The address Trestly's site works with while it's open (iOS 148a5331 `TrestlyTarget`,
 *  Views/Tokens/TokenManageViews.swift, from KaPay). */
data class TrestlyTarget(
    val label: String,
    val address: String,
    /** null: the Chatting Address; else the regular spending slot, whose key signs the site's trades. */
    val spendingIndex: Int?,
    /** The token on it, shown on its row in the address picker. */
    val units: Long = 0,
) {
    val id: String get() = address
}

/**
 * A trade Trestly's site asked for, as Trestly's own code prepared it (iOS 148a5331 `TrestlyApproval`,
 * Views/Tokens/TrestlyApprovalSheet.swift): the numbers come from its `prepare` (amounts as decimal
 * strings), and [respond] answers the provider, once.
 */
class TrestlyApproval(
    val method: String,
    val prepared: JsonObject,
    /** The quote the user approved before the pool moved (a requote), or null. */
    val seen: JsonObject?,
    /** The connected address's name. */
    val from: String,
) {
    val id: String = UUID.randomUUID().toString()
    private val answer = CompletableDeferred<Boolean>()

    /** Answers once; later calls do nothing. */
    fun respond(approved: Boolean) {
        answer.complete(approved)
    }

    internal suspend fun await(): Boolean = answer.await()

    fun number(key: String): Long? = number(prepared.get(key))
    fun flag(key: String): Boolean = prepared.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
    fun string(key: String): String? = prepared.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        /** A whole non-negative number from a JSON number or a string of digits (iOS `number(_:)`):
         *  a boolean, a fraction or anything over 2^63 is none, read without trapping. */
        fun number(v: JsonElement?): Long? {
            if (v == null || !v.isJsonPrimitive) return null
            val p = v.asJsonPrimitive
            if (p.isString) {
                val s = p.asString
                return if (s.isNotEmpty() && s.all { it in '0'..'9' }) s.toLongOrNull() else null
            }
            if (!p.isNumber) return null
            val d = try { java.math.BigDecimal(p.asString) } catch (e: Exception) { return null }
            if (d.signum() < 0) return null
            return try { d.toBigIntegerExact().longValueExact() } catch (e: ArithmeticException) { null }
        }
    }
}

/**
 * Makes one [TrestlyProvider] per open browser (iOS's `@StateObject` in `TrestlyBrowserView`), with
 * the app's services; reached from the browser's composable through a Hilt entry point.
 */
@Singleton
class TrestlyProviderFactory @Inject constructor(
    internal val core: KusdtCore,
    internal val tokenWallet: TokenWallet,
    internal val walletManager: WalletManager,
    internal val nodePoolManager: NodePoolManager,
    internal val addressActivityNotifier: AddressActivityNotifier,
    internal val notificationCenter: GlobalNotificationCenterStore,
    internal val settings: AppSettingsRepository,
) {
    fun make(target: TrestlyTarget, siteUrl: String): TrestlyProvider = TrestlyProvider(target, siteUrl, this)
}

/**
 * The wallet provider Trestly's site talks to inside KaChat (kusdt-open SPEC §14, `window.kusdt`),
 * for the one address picked in Open Trestly. KaChat's own code answers every request; the page
 * never sees a key. iOS 148a5331 `Services/Tokens/TrestlyProvider.swift`, from KaPay.
 *
 * - Injected at document start, main frame only, and only on the Trestly origin set in Settings
 *   ([TrestlySite.siteUrl]): the browser adds [script] and the bridge ([BRIDGE_NAME]) with
 *   androidx.webkit's origin rules ([originRule]), the script checks the page's origin and that it
 *   is the top frame, and [handle] checks every message's origin and that it came from the main
 *   frame, so another site or an iframe gets nothing.
 * - trestly.io (its 2026-11-20 build) finds it by `window.kusdt`, by `kusdt#initialized` and by
 *   `kusdt:announceProvider`, which is dispatched again whenever the page dispatches
 *   `kusdt:requestProvider`. It reads what KaChat answers from `methods` (on the provider and on
 *   `info`) and `kusdt_capabilities`, and names KaChat from `info` (no icon: iOS KaChat has no
 *   wallet logo to send, so `info.icon` is left out).
 * - Answers: `kaspa_requestAccounts` (the picked address: opening Trestly with it was the approval),
 *   `kaspa_getNetwork`, `kaspa_switchNetwork` (only to the network KaChat is on; else 4200),
 *   `kusdt_getBalances` (that address's plain KAS from the node and its node-checked kUSDT),
 *   `kusdt_capabilities`, and the trades `kusdt_swap`, `kusdt_addLiquidity`, `kusdt_removeLiquidity`:
 *   Trestly's own wallet logic ([KusdtCore.wallet]) checks and quotes, KaChat asks in the Approve
 *   sheet ([approval]), then the same code builds, signs with this one address's key and sends.
 *   Everything else, `kusdt_send` and `kusdt_redeem` included, is 4200.
 *
 * Its state is touched only on the main thread (iOS's @MainActor): the bridge's callbacks run there.
 */
class TrestlyProvider internal constructor(
    val target: TrestlyTarget,
    siteUrl: String,
    private val deps: TrestlyProviderFactory,
) {
    class ProviderError(val code: Int, override val message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The only origin answered, e.g. https://trestly.io (iOS `allowedOrigin`). */
    val allowedHost: String
    val allowedPort: Int

    init {
        val uri = try { java.net.URI(siteUrl.trim()) } catch (e: Exception) { null }
        allowedHost = uri?.host?.lowercase().orEmpty()
        allowedPort = uri?.port?.takeIf { it > 0 } ?: 443
    }

    /** androidx.webkit's origin rule for the script and the bridge: "https://host[:port]". */
    val originRule: String get() = if (allowedPort == 443) "https://$allowedHost" else "https://$allowedHost:$allowedPort"

    private val _approval = MutableStateFlow<TrestlyApproval?>(null)
    /** The trade waiting for the user's answer, shown by the browser as the Approve sheet. */
    val approval: StateFlow<TrestlyApproval?> = _approval.asStateFlow()

    private val _banner = MutableStateFlow<String?>(null)
    /** "Swapped 10 KAS for 0.26 kUSDT" (or the other way round), shown over the page for a few seconds. */
    val banner: StateFlow<String?> = _banner.asStateFlow()
    private var bannerToken = 0L

    /** One trade at a time, from its first check to the end of its send (iOS audit, 2026-11-20): a
     *  second request while one is being prepared, shown or sent is answered -32002. */
    private var tradeInFlight = false
    /** After Decline the page waits a moment before it can ask again. */
    private var declinedAt: Long? = null
    /** The browser closed: nothing more is shown or signed for this page. */
    private var closed = false
    /** Set once the page asked for the account (SPEC §14: before that, reads are 4100). */
    private var connected = false
    /** Requests running now: more than 8 at once is -32002, as kUSDT Wallet does. */
    private var running = 0

    // Installing

    /** The page side: `window.kusdt`, frozen, only on the allowed https origin's top frame. Requests
     *  go over the bridge as JSON with an id; answers come back the same way. */
    val script: String
        get() {
            val methodsJSON = JsonArray().apply { METHODS.forEach { add(it) } }.toString()
            val info = JsonObject().apply {
                addProperty("name", "KaChat")
                addProperty("rdns", RDNS)
                addProperty("icon", "")
            }.toString()
            val host = JsonPrimitive(allowedHost).toString()
            return """
            (() => {
              'use strict';
              try {
                if (window.top !== window) return;
                const port = location.port === '' ? 443 : Number(location.port);
                if (location.protocol !== 'https:' || location.hostname.toLowerCase() !== $host || port !== $allowedPort) return;
                if (window.kusdt) return;
                const bridge = window.$BRIDGE_NAME;
                if (!bridge || typeof bridge.postMessage !== 'function') return;
                try { delete window.$BRIDGE_NAME; } catch (e) {}
                const methods = Object.freeze($methodsJSON);
                const base = $info;
                if (!base.icon) delete base.icon;
                const info = Object.freeze(Object.assign(base, { methods }));
                const listeners = Object.create(null);
                const fail = (code, message, data) => Object.assign(new Error(message), data === undefined ? { code } : { code, data });
                const pending = new Map();
                let nextId = 1;
                bridge.addEventListener('message', (event) => {
                  let answer;
                  try { answer = JSON.parse(event.data); } catch (e) { return; }
                  if (!answer || typeof answer.id !== 'number') return;
                  const waiting = pending.get(answer.id);
                  if (!waiting) return;
                  pending.delete(answer.id);
                  if (answer.error) waiting.reject(fail(answer.error.code, answer.error.message, answer.error.data));
                  else waiting.resolve(answer.result === undefined ? null : answer.result);
                });
                const provider = {
                  isKaPay: true,
                  info,
                  methods,
                  request(args) {
                    if (!args || typeof args !== 'object' || typeof args.method !== 'string' || args.method.length > 64) {
                      return Promise.reject(fail(${Code.INVALID}, 'Expected { method, params }.'));
                    }
                    let params = args.params === undefined ? null : args.params;
                    let text;
                    try { text = JSON.stringify(params); } catch (e) { return Promise.reject(fail(${Code.INVALID}, 'Params must be JSON.')); }
                    if (text !== undefined && text.length > 4096) return Promise.reject(fail(${Code.INVALID}, 'Params are too long.'));
                    const id = nextId++;
                    return new Promise((resolve, reject) => {
                      pending.set(id, { resolve, reject });
                      try {
                        bridge.postMessage(JSON.stringify({ id, method: args.method, params: text === undefined ? null : JSON.parse(text) }));
                      } catch (e) {
                        pending.delete(id);
                        reject(fail(${Code.FAILED}, String((e && e.message) || e)));
                      }
                    });
                  },
                  on(event, listener) {
                    if (typeof listener !== 'function') return provider;
                    (listeners[event] = listeners[event] || []).push(listener);
                    return provider;
                  },
                  removeListener(event, listener) {
                    const list = listeners[event];
                    if (list) listeners[event] = list.filter((l) => l !== listener);
                    return provider;
                  },
                };
                provider.off = provider.removeListener;
                Object.freeze(provider);
                Object.defineProperty(window, 'kusdt', { value: provider, writable: false, configurable: false, enumerable: true });
                const announce = () => {
                  try { window.dispatchEvent(new CustomEvent('kusdt:announceProvider', { detail: Object.freeze({ provider, info }) })); } catch (e) {}
                };
                window.addEventListener('kusdt:requestProvider', announce);
                announce();
                try { window.dispatchEvent(new Event('kusdt#initialized')); } catch (e) {}
              } catch (e) {}
            })();
            """.trimIndent()
        }

    // Answering

    /**
     * One message from the page's bridge (iOS `userContentController(_:didReceive:)`). [reply] sends
     * the answer back to the page; a message without an id gets none. Only the allowed site's own
     * page, never a frame or another origin, and the same limits as the page's wrapper, since a page
     * can post to the bridge directly: at most 64 characters of method, plain JSON params of at most
     * 4096 characters.
     */
    fun handle(data: String?, scheme: String?, host: String?, port: Int, isMainFrame: Boolean, reply: (String) -> Unit) {
        if (data == null || data.length > MAX_MESSAGE_CHARS) return
        val message = parseStrict(data)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val id = message.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try { it.asBigDecimal.toBigIntegerExact().longValueExact() } catch (e: ArithmeticException) { null }
        } ?: return
        fun send(result: JsonElement?, error: ProviderError?) {
            val out = JsonObject()
            out.addProperty("id", id)
            if (error != null) {
                out.add("error", JsonObject().apply {
                    addProperty("code", error.code)
                    addProperty("message", error.message)
                })
            } else {
                out.add("result", result ?: JsonNull.INSTANCE)
            }
            try { reply(out.toString()) } catch (e: Exception) { Log.w(TAG, "the Trestly page is gone: an answer was dropped") }
        }

        val sourcePort = if (port <= 0) 443 else port
        if (!isMainFrame || scheme?.lowercase() != "https" || host?.lowercase() != allowedHost || sourcePort != allowedPort) {
            send(null, ProviderError(Code.UNAUTHORIZED, "This page can't use K."))
            return
        }
        val method = message.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (method == null || method.length > 64) {
            send(null, ProviderError(Code.INVALID, "Expected { method, params }."))
            return
        }
        val params = message.get("params")?.takeIf { !it.isJsonNull }
        if (params != null && params.toString().length > 4096) {
            send(null, ProviderError(Code.INVALID, "Params must be JSON of at most 4096 characters."))
            return
        }
        if (running >= 8) {
            send(null, ProviderError(Code.BUSY, "K is still answering this page's earlier requests."))
            return
        }
        running += 1
        scope.launch {
            try {
                send(answer(method, params), null)
            } catch (e: ProviderError) {
                send(null, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(null, ProviderError(Code.FAILED, e.message ?: "K couldn't answer."))
            } finally {
                running -= 1
            }
        }
    }

    private suspend fun answer(method: String, params: JsonElement?): JsonElement? {
        return when (method) {
            "kaspa_requestAccounts" -> {
                connected = true
                JsonArray().apply { add(target.address) }
            }
            "kaspa_getNetwork" -> JsonPrimitive(networkName)
            "kaspa_switchNetwork" -> {
                if (!connected) throw ProviderError(Code.UNAUTHORIZED, "Connect K to this site first.")
                val network = params?.takeIf { it.isJsonObject }?.asJsonObject?.get("network")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (network == null || network !in setOf("mainnet", "testnet-10", "simnet")) {
                    throw ProviderError(Code.INVALID, "Expected { network }.")
                }
                if (network != networkName) throw ProviderError(Code.UNSUPPORTED, "K changes networks only in its own Settings.")
                JsonPrimitive(network)
            }
            "kusdt_getBalances" -> {
                if (!connected) throw ProviderError(Code.UNAUTHORIZED, "Connect K to this site first.")
                balances()
            }
            "kusdt_capabilities" -> JsonArray().apply { METHODS.forEach { add(it) } }
            in TRADE_METHODS -> {
                if (!connected) throw ProviderError(Code.UNAUTHORIZED, "Connect K to this site first.")
                trade(method, params)
            }
            else -> throw ProviderError(Code.UNSUPPORTED, "K doesn't support $method here.")
        }
    }

    // Trades (Trestly's own wallet logic, KusdtCore.wallet)

    /**
     * A swap, a deposit or a withdrawal (iOS `trade`): Trestly's code checks the pool and quotes
     * (`prepare`), KaChat shows it ([approval]), and only on Approve hands it this address's key to
     * build, sign and send (`execute`). A pool that moved to a worse quote comes back as a requote,
     * prepared again and shown again; their code never goes below the site's minimum.
     */
    private suspend fun trade(method: String, params: JsonElement?): JsonElement {
        if (closed) throw ProviderError(Code.UNAUTHORIZED, "K closed this page.")
        if (tradeInFlight || _approval.value != null) {
            throw ProviderError(Code.BUSY, "K is already working on a request from this page.")
        }
        declinedAt?.let { if (System.currentTimeMillis() - it < DECLINE_PAUSE_MS) throw ProviderError(Code.REJECTED, "You declined in K a moment ago.") }
        tradeInFlight = true
        try {
            val owner = Kusdt.schnorrOwner(target.address)
                ?: throw ProviderError(Code.FAILED, "K can't trade from this kind of address.")
            val paramsJSON: JsonElement = params ?: JsonNull.INSTANCE
            val wallet = JsonObject().apply {
                addProperty("network", networkName)
                addProperty("address", target.address)
                addProperty("ownerHex", Kusdt.hex(owner))
                addProperty("apiUrl", deps.settings.trestlyApiUrl.first().trim())
            }
            fun prepareRequest() = JsonObject().apply {
                addProperty("method", method)
                add("params", paramsJSON.deepCopy())
                add("wallet", wallet.deepCopy())
            }
            var prepared = walletCall("prepare", prepareRequest())
            var seen: JsonObject? = null

            while (true) {
                val approved = ask(TrestlyApproval(method, prepared, seen, target.label))
                if (!approved || closed) {
                    declinedAt = System.currentTimeMillis()
                    throw ProviderError(Code.REJECTED, "You declined in K. Nothing was sent.")
                }

                // the key goes into this one request only, never into `wallet`
                val signing = wallet.deepCopy().apply { addProperty("secret", secretHex(owner)) }
                val request = JsonObject().apply {
                    addProperty("method", method)
                    add("params", paramsJSON.deepCopy())
                    add("wallet", signing)
                    add("shownFeerate", prepared.get("feerate") ?: JsonNull.INSTANCE)
                    add("acceptedFee", prepared.get("acceptedFee") ?: JsonNull.INSTANCE)
                    if (method == "kusdt_removeLiquidity") {
                        add("quoted", JsonObject().apply {
                            add("kasOut", prepared.get("kasOut") ?: JsonNull.INSTANCE)
                            add("tokensOut", prepared.get("tokensOut") ?: JsonNull.INSTANCE)
                        })
                    } else {
                        add("quoted", prepared.get("quote") ?: JsonNull.INSTANCE)
                    }
                }
                val done = walletCall("execute", request)

                done.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { step ->
                    step.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { deps.addressActivityNotifier.markOwnTransaction(it.asString) }
                }
                // the coins it spent are held like a send's own (ten minutes), so a send from this
                // address right after can't pick them (iOS audit, 2026-11-20)
                val spent = done.get("inputs")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
                    ?.filter { it.length in 66..79 }
                    .orEmpty()
                if (spent.isNotEmpty()) {
                    OutpointReservations.shared.reserve(spent.mapNotNull { key ->
                        val txid = key.substringBeforeLast(':', "")
                        val index = key.substringAfterLast(':', "").toIntOrNull()
                        if (txid.length == 64 && index != null && index >= 0) Outpoint(transactionId = txid, index = index) else null
                    })
                }

                if (done.get("requote")?.isJsonNull == false) {
                    // The pool moved and pays less than approved: nothing was sent. Trestly's code
                    // prepares it again from scratch, so the sheet shows the new quote with its own
                    // warnings, fee and minimum check (iOS audit, 2026-11-20).
                    seen = if (method == "kusdt_removeLiquidity") {
                        JsonObject().apply {
                            add("kasOut", prepared.get("kasOut") ?: JsonPrimitive("0"))
                            add("tokensOut", prepared.get("tokensOut") ?: JsonPrimitive("0"))
                        }
                    } else {
                        JsonObject().apply { add("quote", prepared.get("quote") ?: JsonPrimitive("0")) }
                    }
                    prepared = walletCall("prepare", prepareRequest())
                    continue
                }
                val result = done.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw ProviderError(Code.FAILED, "Trestly's pool code gave no result.")
                result.get("txid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let { txid ->
                    deps.addressActivityNotifier.markOwnTransaction(txid)
                    if (method == "kusdt_swap") announceSwap(txid, prepared, result, done.get("fee"))
                }
                // TokenWallet looks again once the pool's new coins can be listed (iOS: 4 s)
                scope.launch {
                    delay(REFRESH_AFTER_TRADE_MS)
                    try { deps.tokenWallet.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) {}
                }
                return result
            }
        } finally {
            tradeInFlight = false
        }
    }

    /** This address's key as hex, for one execute: checked against the address (the account may
     *  have changed since the page connected), the bytes wiped after. */
    private suspend fun secretHex(owner: ByteArray): String = withContext(Dispatchers.Default) {
        val unlocked: ByteArray? = try {
            val index = target.spendingIndex
            when {
                index == null -> deps.walletManager.getPrivateKeyBytes()
                WalletManager.isQuantumIndex(index) -> null
                else -> deps.walletManager.getSpendingPrivateKeyBytes(index)
            }
        } catch (e: Exception) {
            null
        }
        val secret = unlocked ?: throw ProviderError(Code.FAILED, "K couldn't unlock this address's key.")
        try {
            if (!Schnorr.publicKeyXOnly(secret).contentEquals(owner)) {
                throw ProviderError(Code.FAILED, "K couldn't unlock this address's key.")
            }
            Kusdt.hex(secret)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * A swap that went through (iOS `announceSwap`, owner's call 2026-11-20): a banner over the
     * page, a line in Notifications (the bell; its tap opens Manage kUSDT), and the kUSDT side in
     * that address's history. "Swapped" once the node shows it accepted; "Swap sent" when it went
     * out and wasn't seen accepted in time (it may still be).
     */
    private fun announceSwap(txid: String, prepared: JsonObject, result: JsonObject, fee: JsonElement?) {
        val amountIn = TrestlyApproval.number(prepared.get("amountIn")) ?: return
        val amountOut = TrestlyApproval.number(result.get("amountOut")) ?: return
        val bought = prepared.get("side")?.takeIf { it.isJsonPrimitive }?.asString == "buy"
        val kas = "${KaspaUnit.plain(if (bought) amountIn else amountOut)} ${KaspaUnit.symbol}"
        val kusdt = TokenAmount.amount(if (bought) amountOut else amountIn, WalletToken.KUSDT)
        val confirmedElement = result.get("confirmed")
        val confirmed = !(confirmedElement != null && confirmedElement.isJsonPrimitive && confirmedElement.asJsonPrimitive.isBoolean && !confirmedElement.asBoolean)
        val text = tr(
            if (confirmed) R.string.kp_swapped_x_for_y else R.string.kp_swap_sent_x_for_y,
            if (bought) kas else kusdt,
            if (bought) kusdt else kas,
        )

        bannerToken += 1
        val token = bannerToken
        _banner.value = text
        scope.launch {
            delay(BANNER_MS)
            if (bannerToken == token) _banner.value = null
        }
        deps.notificationCenter.record(
            "trestly-swap-$txid", TokenWallet.NOTIFICATION_SOURCE,
            tr(if (confirmed) R.string.kp_swap_complete else R.string.kp_swap_sent),
            text, System.currentTimeMillis(), WalletToken.KUSDT.id,
        )
        scope.launch {
            deps.tokenWallet.noteSwap(
                token = WalletToken.KUSDT, spendingIndex = target.spendingIndex, txid = txid,
                units = if (bought) amountOut else amountIn, sold = !bought, fee = TrestlyApproval.number(fee),
            )
        }
    }

    /** One call into Trestly's code; its refusal becomes the provider's error (their message, their code). */
    private suspend fun walletCall(function: String, request: JsonObject): JsonObject {
        val answer = try {
            deps.core.wallet(function, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProviderError(Code.FAILED, e.message ?: tr(R.string.kp_trestly_pool_code_no_answer))
        }
        val error = answer.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (error != null) {
            val code = answer.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
                try { it.asBigDecimal.intValueExact() } catch (e: ArithmeticException) { null }
            } ?: Code.FAILED
            val maybeSent = answer.get("maybeSent")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
            throw ProviderError(code, if (maybeSent) error + " " + tr(R.string.kp_check_address_history_before_trying_again) else error)
        }
        return answer
    }

    /** Shows the approval and waits for Approve or Decline (closing the sheet declines). */
    private suspend fun ask(request: TrestlyApproval): Boolean {
        // a page that is gone gets no sheet (it would never be answered)
        if (closed) return false
        _approval.value = request
        val approved = request.await()
        if (_approval.value === request) _approval.value = null
        return approved
    }

    /** The browser closed (Done): a sheet still up is declined, and nothing later is shown or
     *  signed. A send already under way finishes (its coins are still held and noted). */
    fun close() {
        closed = true
        val pending = _approval.value
        _approval.value = null
        pending?.respond(false)
    }

    /** `{kas, kusdt}` for the connected address, as decimal strings (sompi and base units): the plain
     *  KAS coins the node shows at it, and its kUSDT as [TokenWallet] checked it on the node. */
    private suspend fun balances(): JsonObject {
        val utxos = try {
            withContext(Dispatchers.IO) { deps.nodePoolManager.getUtxosByAddressesFromNode(listOf(target.address)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProviderError(Code.FAILED, "K couldn't read this address's KAS just now.")
        }
        val kas = utxos.filter { it.utxoEntry.covenantId.isNullOrEmpty() }.sumOf { it.utxoEntry.amount }

        val tokens = deps.tokenWallet
        try { tokens.refreshIfStale() } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        if (tokens.balance(WalletToken.KUSDT) == null) {
            throw ProviderError(Code.FAILED, tokens.lastError.value ?: "K couldn't check this address's kUSDT just now.")
        }
        val kusdt = tokens.holdings(WalletToken.KUSDT).firstOrNull { it.address == target.address }?.units ?: 0L
        return JsonObject().apply {
            addProperty("kas", kas.toString())
            addProperty("kusdt", kusdt.toString())
        }
    }

    /** SPEC §14 error codes. */
    object Code {
        const val REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val BUSY = -32002
        const val INVALID = -32602
        const val FAILED = -32603
    }

    companion object {
        private const val TAG = "Trestly"

        /** The bridge's name in the page: `window.kapayTrestly` (KaPay's, unchanged on iOS), only on
         *  the Trestly origin. */
        const val BRIDGE_NAME = "kapayTrestly"

        /**
         * `info.rdns`: iOS 148a5331's, still KaPay's `com.kachat.wallet` (TRESTLY_INTEGRATION.md,
         * "Telling the site who it is"), so the site names the wallet the same on both phones.
         */
        const val RDNS = "com.kachat.wallet"

        /** What KaChat answers, as `methods` and `kusdt_capabilities` say (iOS `methods`). Send and
         *  redeem aren't in it: the site says KaChat doesn't support them. */
        val METHODS = listOf(
            "kaspa_requestAccounts", "kaspa_getNetwork", "kaspa_switchNetwork", "kusdt_getBalances",
            "kusdt_swap", "kusdt_addLiquidity", "kusdt_removeLiquidity",
        )
        val TRADE_METHODS = setOf("kusdt_swap", "kusdt_addLiquidity", "kusdt_removeLiquidity")

        private const val DECLINE_PAUSE_MS = 3_000L
        private const val REFRESH_AFTER_TRADE_MS = 4_000L
        private const val BANNER_MS = 4_000L
        /** The page's wrapper sends at most 4096 characters of params; room for the rest. */
        private const val MAX_MESSAGE_CHARS = 16_384

        /** KaChat's running network as the provider names it. */
        val networkName: String
            get() = if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET) "mainnet" else "testnet-10"

        private val gson = Gson()

        /** Plain JSON only (no NaN, no trailing text), as iOS's JSONSerialization check. */
        internal fun parseStrict(text: String): JsonElement? = try {
            val reader = JsonReader(StringReader(text))
            @Suppress("DEPRECATION")
            reader.isLenient = false
            val element = gson.getAdapter(JsonElement::class.java).read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else element
        } catch (e: Exception) {
            null
        }
    }
}
