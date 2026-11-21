package com.kachat.app.services.tokens

import android.content.Context
import android.util.Log
import com.kachat.app.R
import com.kachat.app.services.AddressActivityNotifier
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NodePoolManager
import com.kachat.app.services.Outpoint
import com.kachat.app.services.UtxoEntry
import com.kachat.app.services.WalletManager
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.util.KaspaNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import protowire.Rpc
import protowire.rpcCovenantBinding
import protowire.rpcOutpoint
import protowire.rpcScriptPublicKey
import protowire.rpcTransaction
import protowire.rpcTransactionInput
import protowire.rpcTransactionOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A kUSDT send, as kusdt-open's wallet makes one (`wallet/extension/src/kusdt/tokens.ts`
 * `buildTransfer`, `build.ts` `buildFunded` and `checkBuilt`; iOS `KusdtTransfer.swift`,
 * 5b7c772c), with Trestly's own builder doing the build and the signing ([KusdtCore]):
 *   1. kUSDT coins of the sending address, largest first, at most 3 (more needs a merge first);
 *   2. outputs: the recipient's coin, and the rest back to the sender as a change coin, each
 *      carrying the token's 1.5 KAS;
 *   3. plain KAS coins of the same address pay what the coins' own KAS doesn't, and the fee
 *      (largest first, one more while the builder asks for more), so no other address is linked;
 *   4. before anything is sent: every token output is exactly the approved state at its
 *      recomputed script with its 1.5 KAS, every other output pays the sender back, and the fee
 *      is under 3 KAS - checked on the builder's report AND on the transaction that is submitted
 *      (its outputs, exactly the picked coins as inputs, the fee worked out here; audit #6).
 *      Anything off and nothing is sent.
 *
 * Threading: every function is a suspend function, safe from any dispatcher.
 */
@Singleton
class KusdtTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val nodePool: NodePoolManager,
    private val networkService: NetworkService,
    private val core: KusdtCore,
    private val tokenWallet: TokenWallet,
    private val addressActivity: AddressActivityNotifier,
) {
    /** A built, signed, checked transfer, ready to send. */
    class Prepared(
        val txid: String,
        /** The network fee (sompi), exactly: what the transaction pays (inputs minus outputs). */
        val fee: Long,
        /** KAS that goes to the recipient with the kUSDT (the new coin's 1.5 KAS). */
        val kasToRecipient: Long,
        val rpc: Rpc.RpcTransaction,
        val spentCoins: List<KusdtBalances.Coin>,
        /** The change coin's output index, if there is one (yours: never announced as received). */
        val changeIndex: Int?,
        val holding: TokenHolding,
        /** Where the kUSDT goes (output 0). */
        val recipient: String,
        val amount: Long,
        /** All of the address's kUSDT went and it had no KAS: the fee came out of the KAS that
         *  travels with the tokens, so the recipient's coin holds a little under 1.5 KAS. */
        val feeFromTokenKas: Boolean,
        /** Plain KAS the transaction pays back to the sending address (its change). For Combine
         *  Coins, the KAS the merged coins held beyond the one coin's 1.5 KAS, less the fee. */
        val kasBack: Long,
        /** The name it was sent to, if any (the history shows it in place of the address). */
        val recipientLabel: String? = null,
        /** Epoch millis it was built. */
        val preparedAt: Long = System.currentTimeMillis(),
    ) {
        /** The same build with the name it goes to (the review's resolved name). */
        fun withRecipientLabel(label: String?): Prepared = Prepared(
            txid, fee, kasToRecipient, rpc, spentCoins, changeIndex, holding, recipient, amount,
            feeFromTokenKas, kasBack, label, preparedAt,
        )

        /** Older than [REVIEW_MAX_AGE_MS]: build it again at the swipe ([changedSinceReview]). */
        val isStale: Boolean get() = System.currentTimeMillis() - preparedAt > REVIEW_MAX_AGE_MS
    }

    /** Why a transfer can't be built or sent; [message] is the text to show. */
    sealed class Failure(message: String) : Exception(message) {
        class Recipient(m: String) : Failure(m)
        class NotEnoughToken(m: String) : Failure(m)
        class MergeFirst(m: String) : Failure(m)
        /** [short]: about how much KAS the sending address needs added (sompi), for Fund This
         *  Address. [forChangeCoin]: part of it is the 1.5 KAS your change coin holds (a partial send). */
        class NotEnoughKas(m: String, val short: Long, val forChangeCoin: Boolean) : Failure(m)
        class NoKey(m: String) : Failure(m)
        class FeeTooHigh(m: String) : Failure(m)
        class CheckFailed(m: String) : Failure(m)
        class TemplateMissing(m: String) : Failure(m)
        class NothingToCombine(m: String) : Failure(m)
    }

    /** A review rebuilt at the swipe came out different (more fee, another From, other KAS to the
     *  recipient), or the build on screen isn't for what the review shows: nothing was sent, and
     *  the review shows the new build (audit #5, KaPost's audit). */
    class SendChanged(message: String) : Exception(message)

    private fun recipientError() = Failure.Recipient(context.getString(R.string.kusdt_error_recipient))
    private fun notEnoughToken() = Failure.NotEnoughToken(context.getString(R.string.kusdt_error_not_enough_token))
    private fun mergeFirst() = Failure.MergeFirst(context.getString(R.string.kusdt_error_merge_first))
    private fun notEnoughKas(short: Long, forChangeCoin: Boolean) = Failure.NotEnoughKas(
        context.getString(if (forChangeCoin) R.string.kusdt_error_kas_for_change_coin else R.string.kusdt_error_kas_for_fee),
        short, forChangeCoin,
    )
    private fun noKey() = Failure.NoKey(context.getString(R.string.kusdt_error_no_key))
    private fun feeTooHigh() = Failure.FeeTooHigh(context.getString(R.string.kusdt_error_fee_too_high))
    private fun checkFailed() = Failure.CheckFailed(context.getString(R.string.kusdt_error_check_failed))

    /** "This send changed while the screen was open. Nothing was sent: check it again and swipe." */
    fun sendChanged() = SendChanged(context.getString(R.string.kusdt_send_changed))

    // MARK: Build

    /**
     * Builds and signs a transfer of [amount] base units from [holding] to [recipient], spending
     * [coins] (`TokenWallet.coins(holding)`). Nothing goes to the network but the coin reads.
     * Throws [Failure], [KusdtCore.CoreError] or an I/O error.
     */
    suspend fun prepare(holding: TokenHolding, coins: List<KusdtBalances.Coin>, recipient: String, amount: Long): Prepared =
        withContext(Dispatchers.Default) { prepareInternal(holding, coins, recipient, amount) }

    private suspend fun prepareInternal(holding: TokenHolding, coins: List<KusdtBalances.Coin>, recipient: String, amount: Long): Prepared {
        val template = Kusdt.template(context) ?: throw Failure.TemplateMissing(context.getString(R.string.kusdt_template_missing))
        val senderKey = Kusdt.schnorrOwner(holding.address) ?: throw noKey()
        val recipientKey = Kusdt.schnorrOwner(recipient) ?: throw recipientError()
        if (amount <= 0) throw notEnoughToken()
        val deployment = Kusdt.current
        val network = if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET) "mainnet" else "testnet-10"

        // 1. the coins, at most 3: never one a send from this app is still holding, and a "Not
        // confirmed" send's coin first, so this send and that one conflict and at most one can
        // land (as KaPay, audit 2026-11-20). Then largest first.
        val unconfirmed = UnknownOutcomeOutpoints.shared.keys()
        val sorted = sortedTokenCoins(coins, unconfirmed)
        val picked = ArrayList<KusdtBalances.Coin>()
        var sum = 0L
        for (coin in sorted) {
            if (sum >= amount || picked.size >= MAX_TOKEN_INPUTS) break
            picked += coin
            sum = Math.addExact(sum, coin.state.amount)
        }
        if (sum < amount) {
            val all = sorted.fold(0L) { acc, c -> acc + c.state.amount }
            throw if (all >= amount) mergeFirst() else notEnoughToken()
        }

        // 2. the outputs
        val outputStates = ArrayList<Kusdt.State>()
        val outputValues = ArrayList<Long>()
        outputStates += Kusdt.State.plain(recipientKey, amount); outputValues += deployment.tokenValue
        if (sum > amount) {
            outputStates += Kusdt.State.plain(senderKey, sum - amount); outputValues += deployment.tokenValue
        }
        val kasIn = picked.fold(0L) { acc, c -> Math.addExact(acc, c.kasSompi) }
        val kasOut = outputValues.sum()
        val need = if (kasOut > kasIn) kasOut - kasIn else 0L
        // more coins in than out (Combine Coins, or a send of everything from several coins): the
        // extra KAS pays the fee and comes back to this address as plain KAS
        val excess = if (kasIn > kasOut) kasIn - kasOut else 0L

        // the key of the sending address, for this one build - and it must be that address's
        val secret = try {
            holding.spendingIndex?.let { walletManager.getSpendingPrivateKeyBytes(it) } ?: walletManager.getPrivateKeyBytes()
        } catch (e: Exception) { throw noKey() }
        if (!com.kachat.app.util.Schnorr.publicKeyXOnly(secret).contentEquals(senderKey)) throw noKey()
        val secretHex = Kusdt.hex(secret)
        secret.fill(0)

        // 3. KAS of the same address: plain Schnorr coins, not held by another send; a "Not
        // confirmed" send's KAS coin first too, then largest first
        val utxos = nodePool.getUtxosByAddressesFromNode(listOf(holding.address))
        val funding = sortedFunding(utxos.filter { u ->
            val script = Kusdt.unhexOrNull(u.utxoEntry.scriptPublicKey.scriptPublicKey)
            u.utxoEntry.covenantId == null && !u.utxoEntry.isCoinbase && script != null && script.size == 34 &&
                (script[0].toInt() and 0xff) == 0x20 && (script[33].toInt() and 0xff) == 0xac &&
                !UnknownOutcomeOutpoints.isHeld(u.outpoint.transactionId, u.outpoint.index)
        }, UnknownOutcomeOutpoints.shared.keys())

        val inputsJson = JSONArray()
        for (coin in picked) {
            inputsJson.put(JSONObject()
                .put("utxo", JSONObject()
                    .put("transactionId", coin.transactionId).put("index", coin.index).put("amount", coin.kasSompi)
                    .put("scriptPublicKey", "0000" + Kusdt.hex(Kusdt.lockingScript(coin.state, template)))
                    .put("blockDaaScore", coin.blockDaaScore).put("isCoinbase", false)
                    .put("covenantId", deployment.covenantIdHex))
                .put("state", stateJson(coin.state))
                .put("secretKey", secretHex))
        }
        // All of the address's kUSDT goes (no change coin): with no KAS here to pay the fee, it
        // comes out of the KAS that travels with the tokens (kusdt-open `feeFromTokenKas`).
        val wholeSend = outputStates.size == 1
        var feeFromTokenKas = false

        var n = 0
        var covered = 0L
        val margin = 50_000_000L
        while (n < funding.size && covered + excess < need + margin) {
            covered += funding[n].utxoEntry.amount
            n += 1
        }
        val available = funding.fold(0L) { acc, u -> acc + u.utxoEntry.amount }
        if (n == 0 && need > 0) throw notEnoughKas(shortfall(need, available), forChangeCoin = !wholeSend)

        var built: JSONObject? = null
        val tokenKasFees = ArrayDeque(listOf(2_000_000L, 5_000_000L, 10_000_000L, 19_000_000L))
        if (wholeSend && funding.isEmpty() && excess == 0L) {
            feeFromTokenKas = true
            n = 0
        }
        for (attempt in 0 until 16) {
            if (feeFromTokenKas) {
                // the fee taken from the coins' own KAS, the least that does; under the builder's
                // 0.2 KAS change floor, so nothing comes back as change
                val take = tokenKasFees.firstOrNull()
                if (take == null || kasIn <= take) throw notEnoughKas(shortfall(need, available), forChangeCoin = false)
                tokenKasFees.removeFirst()
                outputValues[0] = kasIn - take
            }
            val outputsJson = JSONArray()
            for (i in outputStates.indices) outputsJson.put(JSONObject().put("state", stateJson(outputStates[i])).put("value", outputValues[i]))
            val fundingJson = JSONArray()
            for (u in funding.take(n)) {
                fundingJson.put(JSONObject()
                    .put("utxo", JSONObject()
                        .put("transactionId", u.outpoint.transactionId.lowercase()).put("index", u.outpoint.index)
                        .put("amount", u.utxoEntry.amount)
                        .put("scriptPublicKey", "0000" + u.utxoEntry.scriptPublicKey.scriptPublicKey.lowercase())
                        .put("blockDaaScore", u.utxoEntry.blockDaaScore).put("isCoinbase", false))
                    .put("secretKey", secretHex))
            }
            val request = JSONObject()
                .put("network", network).put("feerate", FEERATE).put("changeAddress", holding.address)
                .put("funding", fundingJson).put("inputs", inputsJson).put("outputs", outputsJson).put("redeem", JSONArray())
            try {
                val answer = core.run("transfer", request)
                val fee = number(answer.opt("fee")) ?: Long.MAX_VALUE
                val feeMass = number(answer.optJSONObject("mass")?.opt("feeMass")) ?: 0L
                val absorbed = if (feeMass <= Long.MAX_VALUE / FEERATE && fee > feeMass * FEERATE) fee - feeMass * FEERATE else 0L
                if (!feeFromTokenKas && absorbed > ABSORB_LIMIT && n < funding.size) { n += 1; continue }
                built = answer
                break
            } catch (refused: KusdtCore.CoreError.Refused) {
                val why = refused.message.orEmpty()
                // the builder wants more KAS, or its fee didn't settle at this change: one more coin
                if (why.contains("add funding") || why.contains("fee did not settle")) {
                    if (feeFromTokenKas) continue
                    if (n < funding.size) { n += 1; continue }
                    // a whole send whose KAS here isn't enough either: the fee from the tokens' KAS
                    if (wholeSend) {
                        feeFromTokenKas = true
                        n = 0
                        continue
                    }
                    throw notEnoughKas(shortfall(need, available), forChangeCoin = true)
                }
                throw refused
            }
        }
        val answer = built ?: throw checkFailed()
        val txid = (answer.opt("txid") as? String)?.takeIf { it.length == 64 } ?: throw checkFailed()
        val fee = number(answer.opt("fee")) ?: throw checkFailed()
        val transaction = answer.optJSONObject("transaction") ?: throw checkFailed()
        if (fee > MAX_FEE) throw feeTooHigh()

        // 4. what was built is what was approved
        val builtOutputs = answer.optJSONArray("outputs") ?: throw checkFailed()
        val mine = "0000" + Kusdt.hex(KachatNames.Codec.p2pkScript(senderKey))
        var kasBack = 0L
        for (index in 0 until builtOutputs.length()) {
            val output = builtOutputs.optJSONObject(index) ?: throw checkFailed()
            val script = (output.opt("scriptPublicKey") as? String)?.lowercase().orEmpty()
            if (index < outputStates.size) {
                val token = output.optJSONObject("token") ?: throw checkFailed()
                if (!sameState(token, outputStates[index]) || number(output.opt("value")) != outputValues[index] ||
                    script != "0000" + Kusdt.hex(Kusdt.lockingScript(outputStates[index], template))
                ) throw checkFailed()
            } else if (script != mine) {
                throw checkFailed()
            } else {
                kasBack = Math.addExact(kasBack, number(output.opt("value")) ?: 0L)
            }
        }
        // ...and so is the transaction itself, which is what's submitted: the same outputs
        // (script, value, kUSDT's covenant on the token ones), exactly the coins picked as
        // inputs, and the fee worked out here from them (not the builder's own report of it).
        val txOutputs = transaction.optJSONArray("outputs") ?: throw checkFailed()
        val txInputs = transaction.optJSONArray("inputs") ?: throw checkFailed()
        if (txOutputs.length() != builtOutputs.length()) throw checkFailed()
        val covenantHex = deployment.covenantIdHex
        var outSum = 0L
        for (index in 0 until txOutputs.length()) {
            val output = txOutputs.optJSONObject(index) ?: throw checkFailed()
            val reported = builtOutputs.optJSONObject(index) ?: throw checkFailed()
            val script = (output.opt("scriptPublicKey") as? String)?.lowercase().orEmpty()
            val value = number(output.opt("value")) ?: throw checkFailed()
            if (script != (reported.opt("scriptPublicKey") as? String)?.lowercase() || value != number(reported.opt("value"))) throw checkFailed()
            if (index < outputStates.size) {
                val covenant = output.optJSONObject("covenant") ?: throw checkFailed()
                if ((covenant.opt("covenantId") as? String)?.lowercase() != covenantHex) throw checkFailed()
            }
            outSum = try { Math.addExact(outSum, value) } catch (e: ArithmeticException) { throw checkFailed() }
        }
        val expectedInputs = HashMap<String, Long>()
        for (coin in picked) expectedInputs["${coin.transactionId.lowercase()}:${coin.index}"] = coin.kasSompi
        for (u in funding.take(n)) expectedInputs["${u.outpoint.transactionId.lowercase()}:${u.outpoint.index}"] = u.utxoEntry.amount
        val seenInputs = HashSet<String>()
        for (i in 0 until txInputs.length()) {
            val op = txInputs.optJSONObject(i)?.optJSONObject("previousOutpoint") ?: throw checkFailed()
            val id = (op.opt("transactionId") as? String)?.lowercase() ?: throw checkFailed()
            val index = number(op.opt("index")) ?: throw checkFailed()
            seenInputs += "$id:$index"
        }
        if (seenInputs != expectedInputs.keys || txInputs.length() != expectedInputs.size) throw checkFailed()
        val inSum = expectedInputs.values.fold(0L) { acc, v -> try { Math.addExact(acc, v) } catch (e: ArithmeticException) { throw checkFailed() } }
        if (inSum < outSum) throw checkFailed()
        // what the transaction really pays, shown on the review and capped like the builder's figure
        val paidFee = inSum - outSum
        if (paidFee > MAX_FEE) throw feeTooHigh()

        val mass = number(answer.optJSONObject("mass")?.opt("storage")) ?: 0L
        val rpc = rpcTransaction(transaction, mass)
        if (paidFee != fee) Log.i(TAG, "builder reported fee $fee, transaction pays $paidFee")
        return Prepared(
            txid = txid.lowercase(), fee = paidFee, kasToRecipient = outputValues[0], rpc = rpc,
            spentCoins = picked, changeIndex = if (outputStates.size > 1) 1 else null, holding = holding,
            recipient = recipient, amount = amount, feeFromTokenKas = feeFromTokenKas, kasBack = kasBack,
        )
    }

    // MARK: Combine Coins

    /**
     * Combine Coins: the address's largest coins (up to 3) become one, sent to itself. Every kUSDT
     * coin holds 1.5 KAS, so each coin merged away gives 1.5 KAS back to the address as plain KAS
     * (less the fee, which that KAS pays: no funding needed). Built and checked like any send;
     * [Prepared.kasBack] is what comes back. More than 3 coins take another round.
     */
    suspend fun prepareCombine(holding: TokenHolding, coins: List<KusdtBalances.Coin>): Prepared {
        // never a coin a send is holding; a "Not confirmed" send's coin first (see `prepare`)
        val largest = sortedTokenCoins(coins, UnknownOutcomeOutpoints.shared.keys()).take(MAX_TOKEN_INPUTS)
        if (largest.size < 2) throw Failure.NothingToCombine(context.getString(R.string.kusdt_error_nothing_to_combine))
        val amount = largest.fold(0L) { acc, c -> Math.addExact(acc, c.state.amount) }
        return prepare(holding, largest, holding.address, amount)
    }

    // MARK: Send

    /**
     * Sends a prepared transfer; answers its id. The submit holds every coin it spends, and a node
     * whose answer is lost is looked up by id before anything fails, so it never sends twice.
     * Throws [com.kachat.app.services.SendOutcomeUnknownException] when it may have gone through:
     * never offer a fresh build then - Try Again resends this same [Prepared].
     */
    suspend fun send(prepared: Prepared): String = withContext(Dispatchers.IO) {
        // its KAS change comes back with no input from your addresses: not a "Received KAS"
        addressActivity.markOwnTransaction(prepared.txid)
        val spent = prepared.rpc.inputsList.map { Outpoint(it.previousOutpoint.transactionId.lowercase(), it.previousOutpoint.index) }
        val txid = nodePool.submitConfirmingKnown(prepared.txid, networkService.kaspaRestApi.value, spent = spent) {
            nodePool.getBroadcastConnection().submitRpcTransaction(prepared.rpc)
        }
        Log.i(TAG, "sent ${txid.take(12)}")
        if (!txid.equals(prepared.txid, ignoreCase = true)) {
            Log.w(TAG, "the node named ${txid.take(12)} for the transaction built as ${prepared.txid.take(12)}")
        }
        tokenWallet.noteSent(prepared)
        txid
    }

    /**
     * After "Not confirmed" ([com.kachat.app.services.SendOutcomeUnknownException]): whether
     * [txid] landed, looking every 10 s for up to 3 minutes (iOS TokenSendView `send()`: a chat's
     * bubble and notice are made once it's known). False when it never showed as accepted.
     */
    suspend fun awaitAccepted(txid: String): Boolean = withContext(Dispatchers.IO) {
        repeat(18) {
            delay(10_000)
            val api = networkService.kaspaRestApi.value
            val accepted = try { api?.getTransactionWithInputAddresses(txid)?.isAccepted == true } catch (e: Exception) { false }
            if (accepted) return@withContext true
        }
        false
    }

    // MARK: Helpers

    /** What to add so the send can go: what it needs, a fee margin, less what's there (0.1 KAS at
     *  least), rounded up to 0.01 KAS. */
    private fun shortfall(need: Long, available: Long): Long = Companion.shortfall(need, available)

    private fun stateJson(s: Kusdt.State): JSONObject = JSONObject()
        .put("amount", s.amount).put("owner", Kusdt.hex(s.owner)).put("ownerScheme", s.ownerScheme)
        .put("borrowScheme", s.borrowScheme).put("borrowGuard", Kusdt.hex(s.borrowGuard))
        .put("extensionCommitment", Kusdt.hex(s.extensionCommitment))

    private fun sameState(j: JSONObject, s: Kusdt.State): Boolean =
        number(j.opt("amount")) == s.amount &&
            (j.opt("owner") as? String)?.lowercase() == Kusdt.hex(s.owner) &&
            number(j.opt("ownerScheme")) == s.ownerScheme.toLong() &&
            (number(j.opt("borrowScheme")) ?: 0L) == s.borrowScheme.toLong() &&
            (j.opt("borrowGuard") as? String)?.lowercase() == Kusdt.hex(s.borrowGuard) &&
            (j.opt("extensionCommitment") as? String)?.lowercase() == Kusdt.hex(s.extensionCommitment)

    /** A field that must fit 32 bits: refused, not wrapped, when it doesn't. */
    private fun u32(v: Long): Int {
        if (v < 0 || v > 0xFFFF_FFFFL) throw checkFailed()
        return v.toInt()
    }

    private fun hexData(v: Any?): ByteArray = (v as? String)?.let(Kusdt::unhexOrNull) ?: throw checkFailed()

    /** The builder's transaction (the node's JSON form) as the gRPC submit takes it, with the
     *  storage mass filled in as KaChat's other version-1 transactions do (the id doesn't cover it). */
    private fun rpcTransaction(t: JSONObject, storageMass: Long): Rpc.RpcTransaction {
        val version = number(t.opt("version")) ?: throw checkFailed()
        val inputs = t.optJSONArray("inputs") ?: throw checkFailed()
        val outputs = t.optJSONArray("outputs") ?: throw checkFailed()
        val builtInputs = (0 until inputs.length()).map { k ->
            val i = inputs.optJSONObject(k) ?: throw checkFailed()
            val op = i.optJSONObject("previousOutpoint") ?: throw checkFailed()
            val txid = (op.opt("transactionId") as? String) ?: throw checkFailed()
            val index = number(op.opt("index")) ?: throw checkFailed()
            val signature = Kusdt.hex(hexData(i.opt("signatureScript")))
            rpcTransactionInput {
                previousOutpoint = rpcOutpoint {
                    transactionId = txid.lowercase()
                    this.index = u32(index)
                }
                signatureScript = signature
                sequence = number(i.opt("sequence")) ?: 0L
                sigOpCount = u32(number(i.opt("sigOpCount")) ?: 0L)
                computeBudget = u32(number(i.opt("computeBudget")) ?: 0L)
            }
        }
        val builtOutputs = (0 until outputs.length()).map { k ->
            val o = outputs.optJSONObject(k) ?: throw checkFailed()
            val spk = hexData(o.opt("scriptPublicKey"))
            if (spk.size < 2) throw checkFailed()
            val value = number(o.opt("value")) ?: throw checkFailed()
            val c = o.optJSONObject("covenant")
            val binding = c?.let {
                val authorizing = u32(number(it.opt("authorizingInput")) ?: 0L)
                val id = ((it.opt("covenantId") as? String) ?: "").lowercase()
                rpcCovenantBinding {
                    authorizingInput = authorizing
                    covenantId = id
                }
            }
            rpcTransactionOutput {
                amount = value
                scriptPublicKey = rpcScriptPublicKey {
                    this.version = ((spk[0].toInt() and 0xff) shl 8) or (spk[1].toInt() and 0xff)
                    scriptPublicKey = Kusdt.hex(spk.copyOfRange(2, spk.size))
                }
                if (binding != null) covenant = binding
            }
        }
        val subnetwork = ((t.opt("subnetworkId") as? String) ?: "0".repeat(40)).lowercase()
        val payloadHex = ((t.opt("payload") as? String) ?: "").lowercase()
        return rpcTransaction {
            this.version = u32(version)
            this.inputs.addAll(builtInputs)
            this.outputs.addAll(builtOutputs)
            lockTime = number(t.opt("lockTime")) ?: 0L
            subnetworkId = subnetwork
            gas = number(t.opt("gas")) ?: 0L
            payload = payloadHex
            this.storageMass = storageMass
        }
    }

    companion object {
        private const val TAG = "kUSDT"

        /** The most a covenant transaction's fee may be (kusdt-open `MAX_FEE`). */
        const val MAX_FEE = 300_000_000L
        /** Change the builder couldn't keep, above which one more KAS coin is tried (`ABSORB_LIMIT`). */
        const val ABSORB_LIMIT = 5_000_000L
        /** The relay minimum, sompi per gram of normalized mass (kusdt-open tx.rs `MIN_FEERATE`). */
        const val FEERATE = 100L
        const val MAX_TOKEN_INPUTS = 3
        /** The fee room Fund This Address adds on top of what a send needs (fees run about 0.02 to 0.05 KAS). */
        const val FUNDING_FEE_MARGIN = 20_000_000L
        /** A review older than this is built again at the swipe (iOS TokenSendView, 120 s). */
        const val REVIEW_MAX_AGE_MS = 120_000L

        /** [coins] a send may take: none a send from this app holds, a "Not confirmed" send's
         *  ([unconfirmed]) first, then largest first. */
        internal fun sortedTokenCoins(coins: List<KusdtBalances.Coin>, unconfirmed: Set<String>): List<KusdtBalances.Coin> =
            coins.filter { it.state.amount > 0 && !UnknownOutcomeOutpoints.isHeld(it.transactionId, it.index) }
                .sortedWith(compareByDescending<KusdtBalances.Coin> { if (it.outpointKey.lowercase() in unconfirmed) 1 else 0 }
                    .thenByDescending { it.state.amount })

        /** Plain KAS coins in the order a send funds from them: a "Not confirmed" send's first,
         *  then largest first. */
        internal fun sortedFunding(utxos: List<UtxoEntry>, unconfirmed: Set<String>): List<UtxoEntry> =
            utxos.sortedWith(compareByDescending<UtxoEntry> {
                if (UnknownOutcomeOutpoints.key(it.outpoint.transactionId, it.outpoint.index) in unconfirmed) 1 else 0
            }.thenByDescending { it.utxoEntry.amount })

        /** What to add so a send can go: what it needs plus [FUNDING_FEE_MARGIN], less what's
         *  there; at least 0.1 KAS, rounded up to 0.01 KAS. */
        internal fun shortfall(need: Long, available: Long): Long {
            val target = need + FUNDING_FEE_MARGIN
            val short = if (target > available) target - available else 0L
            return (maxOf(short, 10_000_000L) + 999_999L) / 1_000_000L * 1_000_000L
        }

        /** Whether [built] is the build for what the review shows now: the same From, To and
         *  amount (KaPost's audit: a build still running when you went Back and changed it). */
        fun matches(built: Prepared, fromId: String?, recipient: String?, amount: Long?): Boolean =
            recipient != null && built.holding.id == fromId && built.amount == amount && built.recipient.equals(recipient, ignoreCase = true)

        /** A review rebuilt at the swipe ([Prepared.isStale]) that would not send what was
         *  reviewed: more fee, another From, other KAS to the recipient, or the fee now from the
         *  tokens' KAS (audit #5). The swipe then stops with [sendChanged]. */
        fun changedSinceReview(reviewed: Prepared, rebuilt: Prepared): Boolean =
            rebuilt.fee > reviewed.fee || rebuilt.holding.id != reviewed.holding.id ||
                rebuilt.kasToRecipient != reviewed.kasToRecipient || rebuilt.feeFromTokenKas != reviewed.feeFromTokenKas

        /** A non-negative whole number from a JSON number or a string of digits (a fraction, a
         *  negative or a Boolean is no number). */
        internal fun number(v: Any?): Long? = KusdtBalances.natural(v)
    }
}
