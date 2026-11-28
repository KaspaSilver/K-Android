package com.kachat.app.services.tokens

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One trade on Trestly's pool, as K makes it (iOS 518fdc8c `TrestlyTrade`, kusdt-open SPEC §14-15):
 * Trestly's own pinned code ([KusdtCore.wallet]) checks the pool and quotes (`prepare`), K asks the
 * person ([TrestlyApproval], shown as the approval sheet), and only on Approve hands that code the
 * address's key to build, sign and send (`execute`). A pool that moved to a worse quote comes back
 * as a requote and is asked again.
 *
 * Shared by Trestly's site inside K ([TrestlyProvider], any of its trades) and K's own Swap screen
 * (kUSDT picked: a `kusdt_swap`), so both keep the same checks: the key wiped whatever happens, a
 * trade that may have gone out holds its address for 10 minutes, and spent coins are reserved like
 * a send's.
 */
@Singleton
class TrestlyTrade @Inject constructor(
    private val core: KusdtCore,
    private val tokenWallet: TokenWallet,
    private val walletManager: WalletManager,
    private val nodePoolManager: NodePoolManager,
    private val addressActivityNotifier: AddressActivityNotifier,
    private val notificationCenter: GlobalNotificationCenterStore,
    private val settings: AppSettingsRepository,
    /** Buy/Sell History, where every kUSDT swap goes too (iOS b49a021d). Lazy: [SwapService]
     *  needs this class itself. */
    private val swapService: dagger.Lazy<com.kachat.app.services.SwapService>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What a finished trade answered. */
    class Done(
        val result: JsonObject,
        /** The `prepare` the person approved (the last one, after any requote). */
        val prepared: JsonObject,
        val fee: JsonElement?,
        /** The account signed in when the trade started (its history gets it; iOS d88891ea,
         *  audit 2026-11-27 L3). */
        val account: String? = null,
    )

    /** The address's side of a request to Trestly's code (no key: that is added only on Approve). */
    suspend fun wallet(target: TrestlyTarget): JsonObject {
        val owner = Kusdt.schnorrOwner(target.address)
            ?: throw TrestlyProvider.ProviderError(Code.FAILED, "K can't trade from this kind of address.")
        return JsonObject().apply {
            addProperty("network", networkName)
            addProperty("address", target.address)
            addProperty("ownerHex", Kusdt.hex(owner))
            addProperty("apiUrl", settings.trestlyApiUrl.first().trim())
        }
    }

    /** Trestly's `prepare` alone: the quote and its checks, nothing signed or sent. */
    suspend fun prepare(method: String, params: JsonElement?, target: TrestlyTarget): JsonObject =
        walletCall("prepare", JsonObject().apply {
            addProperty("method", method)
            add("params", params ?: JsonNull.INSTANCE)
            add("wallet", wallet(target))
        })

    /**
     * The whole trade. [ask] shows the approval and answers Approve (true) or Decline; [isClosed]
     * stops it when the screen that asked has gone away. [native]: asked from K's own Swap screen,
     * not by Trestly's site (the approval words its minimum as yours).
     */
    suspend fun run(
        method: String,
        params: JsonElement?,
        target: TrestlyTarget,
        native: Boolean = false,
        ask: suspend (TrestlyApproval) -> Boolean,
        isClosed: () -> Boolean = { false },
    ): Done {
        val startAccount = walletManager.activeAddressFlow.value?.lowercase()
        heldMinutes(target.address)?.let { minutes ->
            throw TrestlyProvider.ProviderError(Code.BUSY, "Your last trade from this address gave no answer and may still go through. Check the address's history. K takes trades from it again once that one shows on the network, or in about $minutes min.")
        }
        val paramsJSON: JsonElement = params ?: JsonNull.INSTANCE
        val wallet = wallet(target)
        val owner = Kusdt.schnorrOwner(target.address)
            ?: throw TrestlyProvider.ProviderError(Code.FAILED, "K can't trade from this kind of address.")
        fun prepareRequest() = JsonObject().apply {
            addProperty("method", method)
            add("params", paramsJSON.deepCopy())
            add("wallet", wallet.deepCopy())
        }
        var prepared = walletCall("prepare", prepareRequest())
        var seen: JsonObject? = null

        while (true) {
            val approved = ask(TrestlyApproval(method, prepared, seen, target.label, native))
            if (!approved || isClosed()) {
                throw TrestlyProvider.ProviderError(Code.REJECTED, "You declined in K. Nothing was sent.")
            }

            // the address's coins as they were before the trade: if it gives no answer, these are
            // held, and the hold ends early once one of them is spent (the trade landed)
            val coinsBefore = try {
                withContext(Dispatchers.IO) { nodePoolManager.getUtxosByAddressesFromNode(listOf(target.address)) }
                    .map { it.outpoint }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }

            // the key goes into this one request only, never into `wallet`
            val signing = wallet.deepCopy().apply { addProperty("secret", secretHex(target, owner)) }
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
            val done = try {
                walletCall("execute", request)
            } catch (e: TrestlyProvider.ProviderError) {
                if (e.maybeSent) {
                    // it may have gone out: hold this address's coins and its trades (iOS, audit
                    // 2026-11-21 #1)
                    hold(target.address, coinsBefore)
                    if (coinsBefore.isNotEmpty()) OutpointReservations.shared.reserve(coinsBefore)
                    refreshTokensSoon()
                }
                throw e
            } finally {
                // the key leaves this function's hands whether execute answers, throws or times
                // out (iOS audit 2026-11-21 #7)
                signing.remove("secret")
                request.remove("wallet")
            }

            done.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { step ->
                step.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { addressActivityNotifier.markOwnTransaction(it.asString) }
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
                ?: throw TrestlyProvider.ProviderError(Code.FAILED, "Trestly's pool code gave no result.")
            val txid = result.get("txid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (txid != null) {
                addressActivityNotifier.markOwnTransaction(txid)
                // looked up again until the swap's coins show (iOS 961fc05a `followTrade`)
                tokenWallet.followTrade(txid, target.address)
            } else {
                // TokenWallet looks again once the pool's new coins can be listed (iOS: 4 s)
                refreshTokensSoon()
            }
            return Done(result, prepared, done.get("fee"), startAccount)
        }
    }

    private fun refreshTokensSoon() {
        scope.launch {
            delay(REFRESH_AFTER_TRADE_MS)
            try { tokenWallet.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        }
    }

    /** This address's key as hex, for one execute. The key must be this address's own: a target
     *  picked on another account (the account switched while Swap kept it) would pair this
     *  account's key with the other account's address (iOS audit 2026-11-24 #5). Bytes wiped after. */
    private suspend fun secretHex(target: TrestlyTarget, owner: ByteArray): String = withContext(Dispatchers.Default) {
        val unlocked: ByteArray? = try {
            val index = target.spendingIndex
            when {
                index == null -> walletManager.getPrivateKeyBytes()
                WalletManager.isQuantumIndex(index) -> null
                else -> walletManager.getSpendingPrivateKeyBytes(index)
            }
        } catch (e: Exception) {
            null
        }
        val secret = unlocked ?: throw TrestlyProvider.ProviderError(Code.FAILED, "K couldn't unlock this address's key.")
        try {
            val signer = try { Schnorr.publicKeyXOnly(secret) } catch (e: Exception) { null }
            if (signer == null || !signer.contentEquals(owner)) {
                throw TrestlyProvider.ProviderError(Code.FAILED, tr(R.string.swap_address_not_this_account))
            }
            Kusdt.hex(secret)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * A swap that went through (iOS `TrestlyTrade.recordSwap`): a line in Notifications (the bell;
     * its tap opens Manage kUSDT) and the kUSDT side in that address's history. Returns the text
     * ("Swapped 10 KAS for 0.26 kUSDT"), for a banner; "Swap sent" when it went out and wasn't seen
     * accepted in time (it may still be). Null when the answer lacks the numbers.
     */
    fun recordSwap(done: Done, target: TrestlyTarget): String? {
        val txid = done.result.get("txid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        val amountIn = TrestlyApproval.number(done.prepared.get("amountIn")) ?: return null
        val amountOut = TrestlyApproval.number(done.result.get("amountOut")) ?: return null
        val bought = done.prepared.get("side")?.takeIf { it.isJsonPrimitive }?.asString == "buy"
        val kas = "${KaspaUnit.plain(if (bought) amountIn else amountOut)} ${KaspaUnit.symbol}"
        val kusdt = TokenAmount.amount(if (bought) amountOut else amountIn, WalletToken.KUSDT)
        val confirmedElement = done.result.get("confirmed")
        val confirmed = !(confirmedElement != null && confirmedElement.isJsonPrimitive && confirmedElement.asJsonPrimitive.isBoolean && !confirmedElement.asBoolean)
        val text = tr(
            if (confirmed) R.string.kp_swapped_x_for_y else R.string.kp_swap_sent_x_for_y,
            if (bought) kas else kusdt,
            if (bought) kusdt else kas,
        )
        notificationCenter.record(
            "trestly-swap-$txid", TokenWallet.NOTIFICATION_SOURCE,
            tr(if (confirmed) R.string.kp_swap_complete else R.string.kp_swap_sent),
            text, System.currentTimeMillis(), WalletToken.KUSDT.id,
        )
        scope.launch {
            tokenWallet.noteSwap(
                token = WalletToken.KUSDT, spendingIndex = target.spendingIndex, txid = txid,
                units = if (bought) amountOut else amountIn, sold = !bought, fee = TrestlyApproval.number(done.fee),
            )
        }
        // and in Buy/Sell History, with the ChangeNOW swaps (owner, 2026-11-27); on the main
        // thread, where the Swap screen reads its history
        scope.launch {
            swapService.get().recordKusdtSwap(
                txid = txid, bought = bought, amountIn = amountIn, amountOut = amountOut,
                address = target.address, confirmed = confirmed, forAccount = done.account,
            )
        }
        return text
    }

    /** One call into Trestly's code; its refusal becomes the provider's error (their message, their code). */
    suspend fun walletCall(function: String, request: JsonObject): JsonObject {
        val answer = try {
            core.wallet(function, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KusdtCore.CoreError.MaybeSent) {
            throw TrestlyProvider.ProviderError(Code.FAILED, maybeSentMessage(e.message ?: tr(R.string.kp_trestly_pool_code_no_answer)), maybeSent = true)
        } catch (e: Exception) {
            throw TrestlyProvider.ProviderError(Code.FAILED, e.message ?: tr(R.string.kp_trestly_pool_code_no_answer))
        }
        val error = answer.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (error != null) {
            val code = answer.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
                try { it.asBigDecimal.intValueExact() } catch (e: ArithmeticException) { null }
            } ?: Code.FAILED
            val maybeSent = answer.get("maybeSent")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
            if (maybeSent) throw TrestlyProvider.ProviderError(code, maybeSentMessage(error), maybeSent = true)
            throw TrestlyProvider.ProviderError(code, error)
        }
        return answer
    }

    private fun maybeSentMessage(why: String): String =
        why + " " + tr(R.string.kp_trade_may_have_gone_through) + " " + tr(R.string.kp_check_address_history_before_trying_again)

    // Trades that may have gone out

    /** Minutes left on this address's hold, or null when it may trade: no hold, the hold ran out,
     *  or a coin it had before is gone from the node (the earlier trade landed). iOS `heldMinutes`.
     *  A gone coin counts only while this run still reserves the hold's coins: then no send of K's
     *  could have spent it. After a relaunch the reservation is gone, a send may have taken one,
     *  and the hold lasts its ten minutes (iOS 01f71184, release audit 2026-11-27, Low). */
    suspend fun heldMinutes(address: String): Int? {
        val held = loadHolds()[address] ?: return null
        val left = held.until - System.currentTimeMillis()
        if (left <= 0) {
            saveHolds(loadHolds() - address)
            return null
        }
        if (held.outpoints.isNotEmpty() && allStillReserved(held.outpoints)) {
            val now = try {
                withContext(Dispatchers.IO) { nodePoolManager.getUtxosByAddressesFromNode(listOf(address)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (now != null) {
                val present = now.map { "${it.outpoint.transactionId.lowercase()}:${it.outpoint.index}" }.toSet()
                if (held.outpoints.any { it !in present }) {
                    saveHolds(loadHolds() - address)
                    return null
                }
            }
        }
        return maxOf(1, ((left + 59_999L) / 60_000L).toInt())
    }

    /** A trade whose `execute` gave no answer may still land: its address takes no new trade until
     *  one of the coins it held before is spent or [HOLD_MS] pass. Kept on the phone, so a relaunch
     *  doesn't drop it (iOS `MaybeSentHold`). */
    private fun hold(address: String, outpoints: List<Outpoint>) {
        val keys = outpoints.map { "${it.transactionId.lowercase()}:${it.index}" }
        saveHolds(loadHolds() + (address to MaybeSentHold(System.currentTimeMillis() + HOLD_MS, keys)))
    }

    private class MaybeSentHold(val until: Long, val outpoints: List<String>)


    private fun holdsPrefs() = com.kachat.app.util.AppText.context()
        .getSharedPreferences(HOLDS_PREFS, android.content.Context.MODE_PRIVATE)

    /** `{address: {"until": ms, "outpoints": ["txid:index"]}}`, written by hand (no reflection,
     *  so a minified build reads what an earlier one wrote). */
    private fun loadHolds(): Map<String, MaybeSentHold> {
        val json = holdsPrefs().getString(HOLDS_KEY, null) ?: return emptyMap()
        return try {
            val root = org.json.JSONObject(json)
            val out = HashMap<String, MaybeSentHold>()
            for (address in root.keys()) {
                val entry = root.optJSONObject(address) ?: continue
                val list = entry.optJSONArray("outpoints")
                val outpoints = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optString(it).takeIf { s -> s.isNotEmpty() } }
                out[address] = MaybeSentHold(entry.optLong("until", 0L), outpoints)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveHolds(all: Map<String, MaybeSentHold>) {
        val now = System.currentTimeMillis()
        val live = all.filterValues { it.until > now }
        val editor = holdsPrefs().edit()
        if (live.isEmpty()) {
            editor.remove(HOLDS_KEY)
        } else {
            val root = org.json.JSONObject()
            for ((address, held) in live) {
                root.put(address, org.json.JSONObject().apply {
                    put("until", held.until)
                    put("outpoints", org.json.JSONArray(held.outpoints))
                })
            }
            editor.putString(HOLDS_KEY, root.toString())
        }
        editor.apply()
    }

    private object Code {
        const val REJECTED = TrestlyProvider.Code.REJECTED
        const val BUSY = TrestlyProvider.Code.BUSY
        const val FAILED = TrestlyProvider.Code.FAILED
    }

    companion object {
        /** Every "txid:index" in [keys] is still held by this run's own sends ([isHeld]); an unreadable
         *  key counts as not held. */
        internal fun allStillReserved(
            keys: List<String>,
            isHeld: (String, Int) -> Boolean = UnknownOutcomeOutpoints::isHeld,
        ): Boolean = keys.all { key ->
            val txid = key.substringBeforeLast(':', "")
            val index = key.substringAfterLast(':', "").toIntOrNull()
            txid.isNotEmpty() && index != null && isHeld(txid, index)
        }

        private const val REFRESH_AFTER_TRADE_MS = 4_000L
        /** How long an address that may have a trade out takes no new one (iOS `holdSeconds`). */
        private const val HOLD_MS = 600_000L
        // the provider's names, so a hold written before Swap shared this code still counts
        private const val HOLDS_PREFS = "kachat_trestly_holds"
        private const val HOLDS_KEY = "maybe_sent_holds"

        /** K's network as Trestly's code names it. */
        val networkName: String
            get() = if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET) "mainnet" else "testnet-10"
    }
}
