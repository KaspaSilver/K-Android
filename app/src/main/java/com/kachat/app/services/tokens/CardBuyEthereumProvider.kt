package com.kachat.app.services.tokens

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.kachat.app.services.WalletManager
import com.kachat.app.util.EIP712
import com.kachat.app.util.EthereumHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Buy Kaspa with Debit Card (iOS bc0e6ba9 `CardBuyEthereumProvider`, Services/Tokens/CardBuy.swift,
 * owner 2026-11-26): the Ethereum wallet Trestly's buy page (`trestly.io/buy`) connects to inside
 * K, announced the standard way (EIP-6963) as "K".
 *
 * The page sells USDC by card through Stripe into the buyer's own Ethereum address, and has that
 * address sign one EIP-2612 permit first, so Trestly's bridge can deposit it to Kaspa as kUSDT
 * (no ETH needed: Trestly's gas wallet sends the bridge transaction). K is that wallet:
 * [EthereumAccount]'s address, never shown as a wallet of its own.
 *
 * - Injected at document start, top frame only, on the Trestly site set in Settings (the same
 *   checks as [TrestlyProvider], which the page gets beside it for the Kaspa side): the browser
 *   adds [script] and the bridge ([BRIDGE_NAME]) with androidx.webkit's origin rules, the script
 *   checks the page's origin, and [handle] checks every message's origin and frame.
 * - Answers `eth_requestAccounts` / `eth_accounts` (the address: opening Buy Kaspa in K was the
 *   approval), `eth_chainId` / `net_version` (Ethereum mainnet), `wallet_switchEthereumChain`
 *   (to mainnet only) and `eth_signTypedData_v4` for **one thing only**: a USDC permit on Ethereum
 *   mainnet from this address ([PermitRequest.parse] checks every field), shown as the Approve the
 *   Bridge sheet ([pending]) and signed only on Approve. Everything else is 4200: K sends no
 *   Ethereum transaction and signs no other message.
 * - Tells [CardBuyTracker] the order's session as the page saves it, so K can follow the order
 *   after the page closes.
 *
 * Its state is touched only on the main thread (iOS's @MainActor): the bridge's callbacks run there.
 */
class CardBuyEthereumProvider(
    /** Where the kUSDT lands (the address picked in Buy Kaspa). */
    val target: TrestlyTarget,
    siteUrl: String,
    /** This account's Ethereum address, worked out when Buy Kaspa opened. */
    val address: String,
    private val iconDataUrl: String,
    private val tracker: CardBuyTracker,
    private val walletManager: WalletManager,
) {
    class ProviderError(val code: Int, override val message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val allowedHost: String
    val allowedPort: Int

    init {
        val uri = try { java.net.URI(siteUrl.trim()) } catch (e: Exception) { null }
        allowedHost = uri?.host?.lowercase().orEmpty()
        allowedPort = uri?.port?.takeIf { it > 0 } ?: 443
    }

    /** androidx.webkit's origin rule for the script and the bridge: "https://host[:port]". */
    val originRule: String get() = if (allowedPort == 443) "https://$allowedHost" else "https://$allowedHost:$allowedPort"

    private val _pending = MutableStateFlow<PermitRequest?>(null)
    /** The permit waiting for Approve or Decline, shown as the Approve the Bridge sheet. */
    val pending: StateFlow<PermitRequest?> = _pending.asStateFlow()

    private var closed = false
    private var declinedAt: Long? = null
    private var running = 0
    private val uuid = UUID.randomUUID().toString().lowercase()

    /** The page closed: a sheet still up is declined, and nothing more is signed for it. */
    fun close() {
        closed = true
        val request = _pending.value
        _pending.value = null
        request?.respond(false)
    }

    /** The page side: an EIP-1193 provider announced by EIP-6963, only on the allowed https
     *  origin's top frame. Requests go over the bridge as JSON with an id; answers come back the
     *  same way. Also reports the order's session as the page saves it. */
    val script: String
        get() {
            val info = JsonObject().apply {
                addProperty("uuid", uuid)
                addProperty("name", "K")
                addProperty("icon", iconDataUrl)
                addProperty("rdns", TrestlyProvider.RDNS)
            }.toString()
            val host = JsonPrimitive(allowedHost).toString()
            return """
            (() => {
              'use strict';
              try {
                if (window.top !== window) return;
                const port = location.port === '' ? 443 : Number(location.port);
                if (location.protocol !== 'https:' || location.hostname.toLowerCase() !== $host || port !== $allowedPort) return;
                const bridge = window.$BRIDGE_NAME;
                if (!bridge || typeof bridge.postMessage !== 'function') return;
                try { delete window.$BRIDGE_NAME; } catch (e) {}
                const listeners = Object.create(null);
                const fail = (code, message) => Object.assign(new Error(message), { code });
                const waiting = new Map();
                let nextId = 1;
                bridge.addEventListener('message', (event) => {
                  let answer;
                  try { answer = JSON.parse(event.data); } catch (e) { return; }
                  if (!answer || typeof answer.id !== 'number') return;
                  const entry = waiting.get(answer.id);
                  if (!entry) return;
                  waiting.delete(answer.id);
                  if (answer.error) entry.reject(fail(answer.error.code, answer.error.message));
                  else entry.resolve(answer.result === undefined ? null : answer.result);
                });
                const send = (method, params) => new Promise((resolve, reject) => {
                  const id = nextId++;
                  waiting.set(id, { resolve, reject });
                  try {
                    bridge.postMessage(JSON.stringify({ id, method, params }));
                  } catch (e) {
                    waiting.delete(id);
                    reject(fail(${Code.FAILED}, String((e && e.message) || e)));
                  }
                });
                const provider = {
                  isKaChat: true,
                  request(args) {
                    if (!args || typeof args !== 'object' || typeof args.method !== 'string' || args.method.length > 64) {
                      return Promise.reject(fail(${Code.INVALID}, 'Expected { method, params }.'));
                    }
                    let text;
                    try { text = JSON.stringify(args.params === undefined ? null : args.params); } catch (e) { return Promise.reject(fail(${Code.INVALID}, 'Params must be JSON.')); }
                    if (text !== undefined && text.length > $MAX_PARAMS_CHARS) return Promise.reject(fail(${Code.INVALID}, 'Params are too long.'));
                    return send(args.method, text === undefined ? null : JSON.parse(text));
                  },
                  on(event, listener) {
                    if (typeof listener === 'function') (listeners[event] = listeners[event] || []).push(listener);
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
                const detail = Object.freeze({ info: Object.freeze($info), provider });
                const announce = () => {
                  try { window.dispatchEvent(new CustomEvent('eip6963:announceProvider', { detail })); } catch (e) {}
                };
                window.addEventListener('eip6963:requestProvider', announce);
                announce();

                // The order K follows after the page closes: the session the page saves when the order
                // is made (not paid yet), and the one it marks paid (`buy.paid:<session>`) and puts in
                // its address once Stripe says the payment went through.
                const report = (session, paid) => {
                  if (typeof session === 'string' && /^[A-Za-z0-9_-]{6,200}$/.test(session)) {
                    send('k_session', { session, paid: !!paid }).catch(() => {});
                  }
                };
                const fromURL = (url) => {
                  try { report(new URL(url, location.href).searchParams.get('session'), true); } catch (e) {}
                };
                fromURL(location.href);
                for (const name of ['pushState', 'replaceState']) {
                  const original = history[name];
                  history[name] = function (state, title, url) {
                    const result = original.apply(this, arguments);
                    if (url != null) fromURL(String(url));
                    return result;
                  };
                }
                const setItem = Storage.prototype.setItem;
                Storage.prototype.setItem = function (key, value) {
                  const result = setItem.apply(this, arguments);
                  if (key === 'kusdt.web.buy.last') {
                    try { report(JSON.parse(value).session, false); } catch (e) {}
                  } else if (typeof key === 'string' && key.startsWith('kusdt.web.buy.paid:')) {
                    report(key.slice('kusdt.web.buy.paid:'.length), true);
                  }
                  return result;
                };
              } catch (e) {}
            })();
            """.trimIndent()
        }

    /**
     * One message from the page's bridge (iOS `userContentController(_:didReceive:)`). [reply] sends
     * the answer back; a message without an id gets none. Only the allowed site's own top page, and
     * the same limits as the page's wrapper: at most 64 characters of method, plain JSON params of at
     * most 16384 characters, eight requests at a time.
     */
    fun handle(data: String?, scheme: String?, host: String?, port: Int, isMainFrame: Boolean, reply: (String) -> Unit) {
        if (data == null || data.length > MAX_MESSAGE_CHARS) return
        val message = TrestlyProvider.parseStrict(data)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val id = message.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try { it.asBigDecimal.longValueExact() } catch (e: ArithmeticException) { null }
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
            try { reply(out.toString()) } catch (e: Exception) { Log.w(TAG, "the buy page is gone: an answer was dropped") }
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
        if (params != null && params.toString().length > MAX_PARAMS_CHARS) {
            send(null, ProviderError(Code.INVALID, "Params must be JSON of at most $MAX_PARAMS_CHARS characters."))
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

    private suspend fun answer(method: String, params: JsonElement?): JsonElement? = when (method) {
        "eth_requestAccounts", "eth_accounts" -> JsonArray().apply { add(address) }
        "eth_chainId" -> JsonPrimitive("0x" + Integer.toHexString(EthereumAccount.CHAIN_ID))
        "net_version" -> JsonPrimitive(EthereumAccount.CHAIN_ID.toString())
        "wallet_switchEthereumChain" -> {
            val asked = params?.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() > 0 }?.get(0)
                ?.takeIf { it.isJsonObject }?.asJsonObject?.get("chainId")
            if (EIP712.smallUInt(asked) != EthereumAccount.CHAIN_ID.toLong()) {
                throw ProviderError(Code.UNKNOWN_CHAIN, "K's Ethereum address is on Ethereum only.")
            }
            null
        }
        "eth_signTypedData_v4" -> signPermit(params)
        "k_session" -> {
            val report = params?.takeIf { it.isJsonObject }?.asJsonObject
            val session = report?.get("session")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (session != null && SESSION_PATTERN.matches(session)) {
                val paid = report.get("paid")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
                tracker.follow(session, target, paid)
            }
            null
        }
        else -> throw ProviderError(Code.UNSUPPORTED, "K signs only the bridge's USDC permit here.")
    }

    private suspend fun signPermit(params: JsonElement?): JsonElement {
        if (closed) throw ProviderError(Code.UNAUTHORIZED, "K closed this page.")
        if (_pending.value != null) throw ProviderError(Code.BUSY, "A request from this page is already waiting in K.")
        declinedAt?.let {
            if (System.currentTimeMillis() - it < DECLINE_PAUSE_MS) throw ProviderError(Code.REJECTED, "You declined in K a moment ago.")
        }
        val request = try {
            PermitRequest.parse(params, address, target)
        } catch (e: EIP712.Failure) {
            throw ProviderError(Code.INVALID, e.message)
        }
        // Never more than the order paid for (iOS d88891ea, audit 2026-11-27 L2): the USDC
        // delivered is that amount less fees, and this address may be MetaMask's account 0 for
        // someone who imported their MetaMask words. K asks Trestly once for the order's amount
        // (iOS 01f71184, release audit 2026-11-27 C6). Not refused when it isn't known yet: the
        // page asks for the permit before the card is charged, maybe before the order is saved,
        // and refusing then would stop every card buy. The sheet still shows the amount and
        // spender before anything is signed.
        if (PermitRequest.exceedsDollars(request.valueUnits, tracker.knownOrderDollars(target))) {
            throw ProviderError(Code.INVALID, "This permit is for more than the order.")
        }
        // the order lookup may have waited on the network
        if (closed) throw ProviderError(Code.UNAUTHORIZED, "K closed this page.")
        if (_pending.value != null) throw ProviderError(Code.BUSY, "A request from this page is already waiting in K.")
        _pending.value = request
        val approved = request.await()
        if (_pending.value === request) _pending.value = null
        if (!approved || closed) {
            declinedAt = System.currentTimeMillis()
            throw ProviderError(Code.REJECTED, "Declined in K.")
        }
        // signed out meanwhile (privateKey throws): the same refusal as no words
        val key = withContext(Dispatchers.Default) {
            try { EthereumAccount.privateKey(walletManager) } catch (e: IllegalStateException) { null }
        } ?: throw ProviderError(Code.FAILED, "K couldn't read this account's words.")
        try {
            val signature = withContext(Dispatchers.Default) {
                // the key must still make the address the page was given
                if (!EthereumAccount.address(key).equals(address, ignoreCase = true)) {
                    throw ProviderError(Code.FAILED, "K's Ethereum address changed. Open Buy Kaspa again.")
                }
                EthereumAccount.sign(request.digest, key)
            }
            return JsonPrimitive("0x" + EthereumHex.string(signature))
        } catch (e: ProviderError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProviderError(Code.FAILED, "K couldn't sign: ${e.message}")
        } finally {
            key.fill(0)
        }
    }

    /** The browser is gone for good: nothing more runs for this page. */
    fun release() {
        close()
        scope.cancel()
    }

    /** EIP-1193 error codes. */
    object Code {
        const val REJECTED = 4001
        const val UNAUTHORIZED = 4100
        const val UNSUPPORTED = 4200
        const val UNKNOWN_CHAIN = 4902
        const val BUSY = -32002
        const val INVALID = -32602
        const val FAILED = -32603
    }

    companion object {
        private const val TAG = "CardBuy"
        /** The bridge's name in the page (iOS's message handler `kachatEthereum`), only on the
         *  Trestly origin; the page's script removes it from `window` once it holds it. */
        const val BRIDGE_NAME = "kachatEthereum"
        private const val MAX_PARAMS_CHARS = 16_384
        /** The params, plus room for the id and the method. */
        private const val MAX_MESSAGE_CHARS = 20_000
        private const val DECLINE_PAUSE_MS = 3_000L
        /** What the page's script reports as a session; checked here too, as a page can post to
         *  the bridge directly. */
        private val SESSION_PATTERN = Regex("^[A-Za-z0-9_-]{6,200}$")
    }
}
