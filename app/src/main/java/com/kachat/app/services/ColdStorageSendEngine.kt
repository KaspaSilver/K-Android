package com.kachat.app.services

import com.kachat.app.R
import com.kachat.app.util.tr
import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.ColdSendFeeModel
import com.kachat.app.util.ColdUtxoSelector
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KsptCodec
import com.kachat.app.util.SendFeeModel
import com.kachat.app.util.UtxoMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Builds unsigned transactions for a Cold Storage (kpub watch-only) address and broadcasts the
 * signed response scanned back from a KasSigner device — the "send" half of Cold Storage
 * ([ColdStorageManager]/[ColdStorageAddressDiscovery] only cover the watch-only half). Deliberately
 * has no dependency on [WalletManager]: this engine never sees a mnemonic or private key, only
 * public addresses and whatever signature KasSigner hands back.
 */
@Singleton
class ColdStorageSendEngine @Inject constructor(
    private val networkService: NetworkService,
    private val nodePoolManager: NodePoolManager,
    /** The account's quantum addresses, for a quantum source's change (iOS 961fc05a). */
    private val coldStorageManager: ColdStorageManager,
    /** Fresh used / spent probes for a quantum change pick (audit 2026-11-25 Q3). Lazy: only a
     *  quantum send asks. */
    private val walletServiceLazy: dagger.Lazy<WalletService>,
    /** Marks a quantum change address used once its send went out (Q3). */
    private val walletManagerLazy: dagger.Lazy<WalletManager>,
) {
    // Same reasoning as KaspaWalletEngine.sendMutex — one build-then-broadcast sequence at a time.
    private val mutex = Mutex()

    /** Coins this engine has broadcast that a fetch may still list - filtered out of every
     *  build, Max, preview and coin-control list (audit AND-015). */
    private val pendingSpent = PendingSpentOutpoints()

    data class UnsignedColdTx(
        val rawTx: RawTransaction,
        // Same order as rawTx.inputs — KSPT's per-input amount/scriptPublicKey come from here,
        // since RawInput alone (just an outpoint + empty signatureScript) doesn't carry them.
        val inputUtxos: List<UtxoEntry>,
        val feeSompi: Long,
        val changeSompi: Long,
        /** The address every input comes from - where [broadcastSigned] records them as spent. */
        val fromAddress: String = "",
        /** Spends from a KasSigner quantum-resistant (KCC-3) address: the request goes as KSPT
         *  0x06 with a KCC-3 hint on every input and no redeem script, which the unit supplies
         *  (iOS 961fc05a). */
        val isQuantumSource: Boolean = false,
        /** The unit's number for that address, sent as the hint; null lets the unit search. */
        val quantumUnitIndex: Long? = null,
        /** The KCC-3 chain of that number (0 receive, 1 change), sent with it in the hint. */
        val quantumUnitChain: Int = 0,
        /** Where the change went: a fresh quantum address of the account for a quantum source
         *  (never the source), else the source. Null when there is no change. */
        val changeAddress: String? = null,
        /** A quantum source's spendable coins this transaction couldn't take (past
         *  [KsptCodec.MAX_INPUTS]): they stay behind the key this spend reveals. */
        val coinsLeftBehind: Int = 0,
    )

    /**
     * KasSigner's KSPT wire format carries no BIP32 derivation path per input — the device
     * presumably resolves a signing key per input by matching its scriptPublicKey against its own
     * derived address set, but nothing in the format lets KaChat *tell* it which path to use. To
     * stay unambiguous, every input in a single send is sourced from exactly one address (picked
     * by the user from the account's address list), never aggregated across several.
     *
     * [manualUtxos], if given (coin control), fixes the exact input set instead of letting
     * [KaspaUtxoSelector.selectUtxosAndCalculateFee] greedily grow one — re-resolved against
     * this call's own fresh [api.getUtxos] fetch by outpoint, not used as-is, so a UTXO spent
     * since the coin-control picker was shown can't silently get included.
     */
    suspend fun buildUnsignedTransaction(
        fromAddress: String,
        toAddress: String,
        amountSompi: Long,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        /** Fast / Priority / custom fee as a flat extra over the base fee, paid exactly - see
         *  [com.kachat.app.util.SendFeeModel]. */
        extraFeeSompi: Long = 0L,
        /** [manualUtxos] are [previewAutomaticSelection]'s, not the person's coin-control pick
         *  (iOS `previewed`): chosen again, leading with a "Not confirmed" send's coin, when the
         *  preview doesn't spend it (iOS 3d126db). */
        previewed: Boolean = false,
        /** Where the change goes, when not back to [fromAddress]: a quantum source's fresh
         *  quantum address ([freshQuantumChangeAddress]). */
        changeAddress: String? = null,
        /** A quantum source's number and chain on the unit, for the request's KCC-3 hint. */
        quantumUnitIndex: Long? = null,
        quantumUnitChain: Int = ColdStorageManager.ColdQuantumAddress.CHAIN_RECEIVE,
    ): Result<UnsignedColdTx> = mutex.withLock {
        try {
            require(amountSompi > 0) { "Amount must be greater than zero" }
            // The script ignores the prefix: a kaspatest: address on mainnet would pay real KAS.
            KaspaAddress.requireActiveNetwork(toAddress)
            require(KaspaAddress.isValid(toAddress)) { "Invalid recipient address" }

            val api = networkService.kaspaRestApi.value
                ?: return@withLock Result.failure(IllegalStateException("Network service unavailable"))

            // Node first, REST second - see NodePoolManager.getUtxosByAddress. Without the coins an
            // earlier send from here already spent: the fetch can still list them (AND-015).
            val utxos = spendableCoins(fromAddress, api)
            if (utxos.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("No spendable UTXOs at this address"))
            }

            val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                ?: fetchQuotedFeeRateSompiPerGram()

            val recipientScriptHex = KaspaAddress.getScriptPublicKey(toAddress)
            val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress ?: fromAddress)
            val isQuantumSource = ColdStorageManager.isScriptHashAddress(fromAddress)
            val sigScriptSize = signatureScriptSize(fromAddress)

            // After a "Not confirmed" send from this address, an automatic selection leads with
            // one of its coins, so at most one of the two can land (iOS 3d126db
            // `conflictingOutpoint`, as the hot wallet's sends). A coin-control pick is the
            // person's own and is left as it is.
            val leadingOutpoint = conflictingOutpoint(utxos)
            // The review's preview without the coin to lead with (the earlier send ended after it
            // was shown) is chosen again here, leading with it.
            val previewMissesLead = previewed && leadingOutpoint != null &&
                manualUtxos.orEmpty().none { KaspaWalletEngine.outpointKey(it.outpoint).lowercase() == leadingOutpoint.lowercase() }
            var coinsLeftBehind = 0
            val selection = if (isQuantumSource) {
                // A quantum source spends every coin it can: this spend puts its key on chain, and
                // any coin left at the address would sit behind that key. Coin control has no say;
                // only past one transaction's input cap do coins stay, and the review says so
                // (audit 2026-11-25 Q6).
                val all = quantumSourceCoins(utxos, leadingOutpoint)
                coinsLeftBehind = utxos.size - all.size
                ColdUtxoSelector.selectManual(
                    utxos = all,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi,
                    signatureScriptSize = sigScriptSize
                )
            } else if (!manualUtxos.isNullOrEmpty() && !previewMissesLead) {
                val freshByOutpoint = utxos.associateBy { it.outpoint }
                val picked = manualUtxos.distinctBy { it.outpoint }
                val resolved = picked.mapNotNull { freshByOutpoint[it.outpoint] }
                // A picked coin that is gone (spent, or sent from here already) is said, never
                // quietly dropped: the transaction to sign would not be the one picked (the hot
                // wallet's words).
                if (resolved.size != picked.size) {
                    return@withLock Result.failure(IllegalStateException("Selected UTXOs are no longer available - please reselect"))
                }
                ColdUtxoSelector.selectManual(
                    utxos = resolved,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi,
                    signatureScriptSize = sigScriptSize
                )
            } else {
                ColdUtxoSelector.select(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi,
                    leadingOutpoint = leadingOutpoint,
                    signatureScriptSize = sigScriptSize
                )
            }
            if (selection.totalSelected < selection.requiredAmount) {
                return@withLock Result.failure(
                    IllegalStateException("Insufficient funds: needed ${selection.requiredAmount}, have ${selection.totalSelected}")
                )
            }
            if (selection.selectedUtxos.size > KsptCodec.MAX_INPUTS) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "This send would need ${selection.selectedUtxos.size} UTXOs, but KasSigner only supports " +
                            "${KsptCodec.MAX_INPUTS} inputs per transaction. Send a smaller amount or consolidate this address first."
                    )
                )
            }

            // Never change back onto the key this spend reveals (IOS-104, as the hot wallet).
            val changeToSource = isQuantumSource &&
                (changeAddress ?: fromAddress).trim().lowercase() == fromAddress.trim().lowercase()
            val built = coldOutputs(selection, recipientScriptHex, changeScriptHex, refuseChange = changeToSource)
            val outputs = built.outputs

            val rawTx = RawTransaction(
                inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
                outputs = outputs
            )

            Result.success(
                UnsignedColdTx(
                    rawTx = rawTx,
                    inputUtxos = selection.selectedUtxos,
                    feeSompi = built.paidFeeSompi,
                    changeSompi = built.changeSompi,
                    fromAddress = fromAddress,
                    isQuantumSource = isQuantumSource,
                    quantumUnitIndex = quantumUnitIndex,
                    quantumUnitChain = quantumUnitChain,
                    changeAddress = if (built.changeSompi > 0) (changeAddress ?: fromAddress) else null,
                    coinsLeftBehind = coinsLeftBehind,
                )
            )
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to build unsigned transaction", e)
            Result.failure(e)
        }
    }

    /**
     * Live max-sendable estimate — matches iOS's `estimateMaxAmount` exactly: a fresh
     * `getUtxos` fetch, not the cached balance shown in the address list. That cached value can
     * be stale (this address's real balance moved since the list was last refreshed), so basing
     * Max on it can offer more than what's actually spendable, and the real build then fails
     * with insufficient funds even though the on-screen "Available" looked fine.
     *
     * If coin control has fixed a UTXO set ([manualUtxos]), Max reflects only that subset
     * (re-resolved against this call's own fresh fetch, same as [buildUnsignedTransaction])
     * rather than the whole address's balance.
     *
     * The Fast / Priority / custom extra ([feeMultiplier], [customExtraFeeSompi]) comes off flat,
     * derived from this same base - see [com.kachat.app.util.SendFeeModel.maxAfterFees].
     */
    suspend fun estimateMaxAmount(
        fromAddress: String,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        /** The recipient, when there is one: priced at its real script, as the build is (iOS
         *  5848fb0 - a quantum, 35-byte recipient priced as 34 left Max a few sompi short). */
        toAddress: String? = null
    ): Long {
        val api = networkService.kaspaRestApi.value
            ?: throw IllegalStateException("Network service unavailable")
        val fetched = spendableCoins(fromAddress, api)
        if (fetched.isEmpty()) return 0L

        val utxos = if (ColdStorageManager.isScriptHashAddress(fromAddress)) {
            // A quantum source's build ignores coin control and spends exactly these, so Max
            // prices exactly these (audit 2026-11-25 T1).
            quantumSourceCoins(fetched, conflictingOutpoint(fetched))
        } else if (!manualUtxos.isNullOrEmpty()) {
            val freshByOutpoint = fetched.associateBy { it.outpoint }
            manualUtxos.mapNotNull { freshByOutpoint[it.outpoint] }
        } else {
            // Only as much as ONE transaction can actually spend. Selection takes UTXOs
            // largest-first and stops when the amount is covered, so the most a single send can
            // move is the largest [KsptCodec.MAX_INPUTS] of them. Summing all of them, which is
            // what this used to do, offered a Max that could not be built: the build needs every
            // UTXO to reach it, trips the input cap, and refuses - and the reader only finds that
            // out after pressing Build. Compound is the way to spend the rest, and it is a tap
            // away in the same menu.
            fetched.sortedByDescending { it.utxoEntry.amount }.take(KsptCodec.MAX_INPUTS)
        }
        if (utxos.isEmpty()) return 0L

        val totalBalance = UtxoMath.total(utxos.map { it.utxoEntry.amount }) // throws on invalid node data (IOS-020)
        val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            ?: fetchQuotedFeeRateSompiPerGram()

        return ColdSendFeeModel.maxAfterFees(
            totalBalance, utxos.size, feeRateSompiPerGram, feeMultiplier, customExtraFeeSompi,
            recipientScriptLen = scriptLength(toAddress), changeScriptLen = scriptLength(fromAddress),
            signatureScriptSize = signatureScriptSize(fromAddress),
        )
    }

    /**
     * Live preview of what automatic selection *would* pick for [amountSompi] at
     * [feeRateSompiPerGram] — same selector [buildUnsignedTransaction] itself uses, just without
     * actually building. Lets the send form show a fee that's already exact (not the 1-input
     * reference-mass guess) whenever a fresh preview is available, and — critically — lets the
     * form pass this exact same UTXO set into the real build as `manualUtxos`, so the two numbers
     * can't diverge the way they could when each independently guessed at the input count. Priced
     * at the recipient's real script once there is one ([toAddress], iOS 5848fb0), 34 bytes until
     * then. The Fast / Priority / custom extra is settled together with the inputs it needs
     * ([ColdSendFeeModel.previewAutomaticSelection]); the build passes the preview's extra.
     */
    suspend fun previewAutomaticSelection(
        fromAddress: String,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        /** The recipient, when there is one: its fee is then what the build charges for the same
         *  coins (iOS 5848fb0); 34 bytes until there is one. */
        toAddress: String? = null
    ): ColdSendFeeModel.Preview? {
        if (amountSompi <= 0) return null
        val api = networkService.kaspaRestApi.value ?: return null
        val utxos = try {
            spendableCoins(fromAddress, api)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        // A quantum source spends all its coins (see the build), so that is what's priced.
        val quantum = ColdStorageManager.isScriptHashAddress(fromAddress)
        val lead = conflictingOutpoint(utxos)
        return ColdSendFeeModel.previewAutomaticSelection(
            utxos = if (quantum) quantumSourceCoins(utxos, lead) else utxos,
            amountSompi = amountSompi,
            feeRateSompiPerGram = feeRateSompiPerGram,
            feeMultiplier = feeMultiplier,
            customExtraFeeSompi = customExtraFeeSompi,
            recipientScriptLen = scriptLength(toAddress),
            changeScriptLen = scriptLength(fromAddress),
            // the same lead the build takes, so the review shows the coins it will spend (iOS 3d126db)
            leadingOutpoint = lead,
            signatureScriptSize = signatureScriptSize(fromAddress),
            spendAll = quantum,
        )
    }

    /** The coin an automatic send from [utxos] must spend first: the largest one a "Not
     *  confirmed" send also spent (iOS 3d126db `ChatService.conflictingOutpoint`; KaChat keeps
     *  those coins in [com.kachat.app.services.tokens.UnknownOutcomeOutpoints]); null if none. */
    private fun conflictingOutpoint(utxos: List<UtxoEntry>): String? {
        val keys = com.kachat.app.services.tokens.UnknownOutcomeOutpoints.shared.keys()
        if (keys.isEmpty()) return null
        return utxos
            .filter { com.kachat.app.services.tokens.UnknownOutcomeOutpoints.key(it.outpoint.transactionId, it.outpoint.index) in keys }
            .maxByOrNull { it.utxoEntry.amount }
            ?.let { KaspaWalletEngine.outpointKey(it.outpoint) }
    }

    /** An address's script length for pricing; 34 (P2PK) when there's no valid address yet (iOS
     *  5848fb0 `scriptLength(of:)`). */
    private fun scriptLength(address: String?): Int =
        address?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { KaspaAddress.getScriptPublicKey(it).length / 2 }.getOrNull() }
            ?: SendFeeModel.STANDARD_SCRIPT_LEN


    /** Change addresses handed to a request built this session and not released: a second send
     *  built before the first one's change shows on chain must not pick the same address. */
    private val reservedQuantumChange: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * Where a send from a quantum address puts its change (and where Compound moves a quantum
     * address's coins): another quantum address of the same account that holds nothing, has never
     * been used and has never spent, as the hot wallet sends a quantum source's change to a fresh
     * slot - the spend makes the source's key public (iOS 961fc05a, audit 2026-11-25 Q2/Q3).
     * Change-chain #0-4 first, the ones KasSigner itself marks as CHANGE when it shows the
     * outputs; then other change-chain addresses; receive addresses last. Used-ness is probed
     * fresh (the session's "unused" cache can be stale), and the pick is reserved until
     * [releaseQuantumChange], or for good once the send goes out ([noteQuantumChangeSent]). Null
     * when there is none or [fromAddress] isn't a quantum address.
     */
    suspend fun freshQuantumChangeAddress(fromAddress: String): String? {
        val (account, _) = coldStorageManager.quantumAddressRecord(fromAddress) ?: return null
        val source = fromAddress.trim().lowercase()
        val ranked = coldStorageManager.currentQuantumAddresses(account.id)
            .withIndex()
            .filter { it.value.address != source && it.value.address !in reservedQuantumChange }
            .sortedWith(compareBy({ changeRank(it.value) }, { it.index }))
            .map { it.value.address }
        if (ranked.isEmpty()) return null
        val funded = try {
            nodePoolManager.getUtxosByAddressesFromNode(ranked).map { it.address.lowercase() }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val walletService = walletServiceLazy.get()
        for (candidate in ranked) {
            if (candidate in funded) continue
            // never used: no transaction in its history, read now; a read that failed counts as
            // used. A spend would have shown as use, but a revealed key is never change.
            if (walletService.spendingAddressUsedState(candidate, skipSessionCache = true) != false) continue
            if (walletService.quantumAddressSpentState(candidate) == true) continue
            // another build may have taken it while this one was reading
            if (!reservedQuantumChange.add(candidate)) continue
            return candidate
        }
        return null
    }

    /** Hands back a change address [freshQuantumChangeAddress] reserved, for a request that was
     *  rebuilt, failed or abandoned before it went out. */
    fun releaseQuantumChange(address: String?) {
        address ?: return
        reservedQuantumChange.remove(address.trim().lowercase())
    }

    /** The send went out: its change address is used from now on, whatever the history API says
     *  until it indexes the transaction. */
    fun noteQuantumChangeSent(address: String?) {
        address ?: return
        walletManagerLazy.get().markAddressUsed(address.trim().lowercase())
    }

    /** 0: change chain #0-4 (the unit marks them CHANGE), 1: other change-chain, 2: the rest. */
    private fun changeRank(entry: ColdStorageManager.ColdQuantumAddress): Int = when {
        entry.unitMarksAsChange -> 0
        entry.chain == ColdStorageManager.ColdQuantumAddress.CHAIN_CHANGE -> 1
        else -> 2
    }

    /** Raw UTXOs at [fromAddress] for the coin-control picker — unlike
     *  [com.kachat.app.services.ColdStorageAddressDiscovery.getUtxos] (a simplified display
     *  model), these are the actual [UtxoEntry] objects [buildUnsignedTransaction] and
     *  [estimateMaxAmount] accept as `manualUtxos`. */
    suspend fun fetchUtxos(fromAddress: String): List<UtxoEntry> {
        return try {
            fetchUtxosOrThrow(fromAddress)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [fetchUtxos], throwing when the node cannot be asked rather than reading as empty - see
     *  [KaspaWalletEngine.fetchUtxosOrThrow]. Node first, REST second, as the build reads them, so
     *  coin control offers the coins the build will find. */
    suspend fun fetchUtxosOrThrow(fromAddress: String): List<UtxoEntry> {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException(COIN_FETCH_NOT_CONNECTED)
        return spendableCoins(fromAddress, api)
    }

    /**
     * Coin control's coins (iOS 5848fb0, `CoinControlView.hotWalletReason(chatting: false)` on Cold
     * Storage too): every coin at [fromAddress], each with why the build can't spend it, or null
     * when it can - the same coins [spendableCoins] leaves out, listed greyed out and never picked.
     */
    suspend fun fetchCoinControlCoinsOrThrow(fromAddress: String): List<Pair<UtxoEntry, String?>> {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException(COIN_FETCH_NOT_CONNECTED)
        val fetched = nodePoolManager.getUtxosByAddress(fromAddress) ?: api.coldUtxosOfAddress(fromAddress)
        val unspentCoins = unspentCoins(fromAddress, fetched)
        val unspent = unspentCoins.map { it.outpoint }.toSet()
        val spendable = matureCoins(unspentCoins).map { it.outpoint }.toSet()
        return fetched.map { utxo ->
            utxo to when {
                !utxo.utxoEntry.coldPlainSpendable -> tr(R.string.kp_coin_locked_by_covenant)
                utxo.outpoint !in unspent -> tr(R.string.kp_coin_just_spent)
                utxo.outpoint !in spendable -> tr(R.string.kp_coin_not_spendable_yet)
                else -> null
            }
        }
    }

    /**
     * What a send from [address] may spend, as for a hot-wallet spending address (iOS 5848fb0
     * `spendableCoins`): node first, REST second, as every build reads them; not a coin a send has
     * just spent (this engine's own sends, and any node-pool submit's, held ten minutes); not a
     * covenant-bound coin; a mining reward only once mature. Every build, Max, preview, coin
     * control and Compound reads these, so they all count the same coins.
     */
    private suspend fun spendableCoins(address: String, api: KaspaRestApi): List<UtxoEntry> {
        val fetched = nodePoolManager.getUtxosByAddress(address) ?: api.coldUtxosOfAddress(address)
        return matureCoins(unspentCoins(address, fetched))
    }

    /** [fetched] without the coins a send just spent, and without covenant-bound ones. */
    private fun unspentCoins(address: String, fetched: List<UtxoEntry>): List<UtxoEntry> =
        // not a coin any submit from this app holds (iOS `ChatService.unreservedUtxos`)
        pendingSpent.filter(address, fetched).filterNot { OutpointReservations.shared.isReserved(it.outpoint) }
            .filter { it.utxoEntry.coldPlainSpendable }

    /** [utxos] without immature mining rewards. The virtual DAA score is asked only when there is a
     *  coinbase coin; without it the coins are kept (iOS `spendableUtxos`, nil score) and the node
     *  decides. */
    private suspend fun matureCoins(utxos: List<UtxoEntry>): List<UtxoEntry> {
        if (utxos.none { it.utxoEntry.isCoinbase }) return utxos
        val virtualDaaScore = try {
            nodePoolManager.getBroadcastConnection().getBlockDagInfo().virtualDaaScore
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w("ColdStorageSendEngine", "virtualDaaScore timed out; keeping all coins")
            return utxos
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ColdStorageSendEngine", "Could not fetch virtualDaaScore for coinbase maturity; keeping all coins", e)
            return utxos
        }
        return utxos.filter {
            !it.utxoEntry.isCoinbase || UtxoMath.isMatureCoinbase(it.utxoEntry.blockDaaScore, KaspaWalletEngine.COINBASE_MATURITY, virtualDaaScore)
        }
    }

    data class CompoundInputs(val utxos: List<UtxoEntry>, val hasMore: Boolean)

    /**
     * Cold-storage "Compound UTXOs" is a single self-send that merges as many of this address's
     * UTXOs as one KasSigner-signable transaction can hold. A KSPT transaction is capped at
     * [KsptCodec.MAX_INPUTS] inputs, so this returns the largest up-to-cap spendable UTXOs at
     * [fromAddress] (largest-first, so each round sheds the most value and converges fastest),
     * plus whether more than that many remain. Merging cap -> 1 per round means an address with N
     * UTXOs takes ceil((N-1)/(cap-1)) rounds; the caller repeats Compound until a single UTXO is
     * left.
     */
    suspend fun compoundInputs(fromAddress: String): CompoundInputs {
        val spendable = fetchUtxos(fromAddress)
        // A quantum source's build spends quantumSourceCoins (a "Not confirmed" send's coin
        // leading), so the compound is priced on that same set (audit 2026-11-25 T1).
        val capped = if (ColdStorageManager.isScriptHashAddress(fromAddress)) {
            quantumSourceCoins(spendable, conflictingOutpoint(spendable))
        } else {
            spendable.sortedByDescending { it.utxoEntry.amount }.take(KsptCodec.MAX_INPUTS)
        }
        return CompoundInputs(capped, spendable.size > KsptCodec.MAX_INPUTS)
    }

    /** Live quoted fee rate (sompi per mass-gram) — whichever is higher, the network's current
     *  "normal" bucket quote or the protocol minimum. Falls back to the minimum on any request
     *  failure. Shared by [buildUnsignedTransaction]'s default, [estimateMaxAmount]'s default,
     *  and the send form's own fee preview (via [com.kachat.app.viewmodels.ColdStorageViewModel]),
     *  so all three always agree on the same rate instead of each doing its own separate fetch
     *  that could return a slightly different quote and make the fee shown before Build not
     *  match what the real transaction costs. */
    suspend fun fetchQuotedFeeRateSompiPerGram(): Long {
        val api = networkService.kaspaRestApi.value ?: return KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        return try {
            quotedFeeRate(api.getFeeEstimate().normalBuckets.firstOrNull()?.feerate)
        } catch (e: Exception) {
            KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        }
    }

    /** KSPT-encodes [tx] for display as an (animated) QR sequence — see [com.kachat.app.util.QrFrameChunker]. */
    fun toKspt(tx: UnsignedColdTx): ByteArray {
        val inputs = tx.rawTx.inputs.mapIndexed { index, input ->
            val utxo = tx.inputUtxos[index]
            KsptCodec.UnsignedInput(
                prevTxId = input.previousOutpoint.transactionId,
                prevIndex = input.previousOutpoint.index,
                amountSompi = utxo.utxoEntry.amount,
                sequence = input.sequence,
                sigOpCount = input.sigOpCount,
                spkVersion = 0,
                spkScriptHex = utxo.utxoEntry.scriptPublicKey.scriptPublicKey
            )
        }
        val outputs = tx.rawTx.outputs.map { output ->
            KsptCodec.UnsignedOutput(
                valueSompi = output.amount,
                spkVersion = output.scriptPublicKey.version,
                spkScriptHex = output.scriptPublicKey.scriptPublicKey
            )
        }
        // A quantum (KCC-3) source goes as KSPT 0x06 - only KasSigner v1.1 and later read it - with
        // a KCC-3 hint per input when the address's number is known and no redeem script: the
        // unit supplies `<P> OP_CHECKSIG` when it signs. Everything else stays 0x01, which every
        // KasSigner reads (iOS 961fc05a).
        if (tx.isQuantumSource) {
            val hint = tx.quantumUnitIndex?.let { KsptCodec.InputHint.Kcc3(chain = tx.quantumUnitChain.toLong(), addrIndex = it) }
            return KsptCodec.encodeUnsignedV6(
                txVersion = tx.rawTx.version,
                lockTime = tx.rawTx.lockTime,
                subnetworkIdHex = tx.rawTx.subnetworkId,
                gas = tx.rawTx.gas,
                payloadHex = tx.rawTx.payload,
                inputs = inputs,
                inputHints = List(inputs.size) { hint },
                outputs = outputs
            )
        }
        return KsptCodec.encodeUnsigned(
            txVersion = tx.rawTx.version,
            lockTime = tx.rawTx.lockTime,
            subnetworkIdHex = tx.rawTx.subnetworkId,
            gas = tx.rawTx.gas,
            payloadHex = tx.rawTx.payload,
            inputs = tx.rawTx.inputs.mapIndexed { index, input ->
                val utxo = tx.inputUtxos[index]
                KsptCodec.UnsignedInput(
                    prevTxId = input.previousOutpoint.transactionId,
                    prevIndex = input.previousOutpoint.index,
                    amountSompi = utxo.utxoEntry.amount,
                    sequence = input.sequence,
                    sigOpCount = input.sigOpCount,
                    spkVersion = 0,
                    spkScriptHex = utxo.utxoEntry.scriptPublicKey.scriptPublicKey
                )
            },
            outputs = tx.rawTx.outputs.map { output ->
                KsptCodec.UnsignedOutput(
                    valueSompi = output.amount,
                    spkVersion = output.scriptPublicKey.version,
                    spkScriptHex = output.scriptPublicKey.scriptPublicKey
                )
            }
        )
    }

    /**
     * Merges a scanned signed-KSPT response's per-input Schnorr signatures back into
     * [unsignedTx]'s inputs and broadcasts. Verifies every input's outpoint AND every output's
     * amount/script against what was actually sent for signing before broadcasting anything — a
     * compromised/malfunctioning device altering the destination or amount must fail loudly here,
     * not get silently broadcast.
     */
    suspend fun broadcastSigned(unsignedTx: UnsignedColdTx, decoded: KsptCodec.Decoded): Result<String> {
        return try {
            require(decoded.signed) { "Scanned transaction is not signed" }
            // Nothing this app builds binds a covenant: a reply that does is not what was signed for.
            if (decoded.records.any { it is KsptCodec.Record.Covenant || it is KsptCodec.Record.CoinCovenant }) {
                throw IllegalStateException(tr(R.string.coldq_error_unexpected_covenant))
            }
            require(decoded.inputs.size == unsignedTx.rawTx.inputs.size) { "Signed transaction has a different number of inputs" }
            require(decoded.outputs.size == unsignedTx.rawTx.outputs.size) { "Signed transaction has a different number of outputs" }

            decoded.outputs.forEachIndexed { index, decodedOutput ->
                val original = unsignedTx.rawTx.outputs[index]
                require(decodedOutput.valueSompi == original.amount && decodedOutput.spkScriptHex == original.scriptPublicKey.scriptPublicKey) {
                    "Signed transaction's outputs don't match what was sent for signing, so it won't be broadcast"
                }
            }

            val signedInputs = unsignedTx.rawTx.inputs.mapIndexed { index, input ->
                val decodedInput = decoded.inputs[index]
                require(
                    decodedInput.prevTxId == input.previousOutpoint.transactionId &&
                        decodedInput.prevIndex == input.previousOutpoint.index
                ) { "Signed transaction's input $index doesn't match what was sent for signing" }

                val sigHex = decodedInput.signatureHex
                    ?: return Result.failure(IllegalStateException("Input $index wasn't signed"))
                val sigBytes = sigHex.hexToBytesLocal()
                require(sigBytes.size == 64) { "Unexpected signature length for input $index" }

                // Only SIGHASH_ALL: a NONE / ANYONECANPAY signature doesn't commit to the outputs,
                // so anyone seeing it in the mempool could redirect the funds (audit IOS-019).
                val sighashType = requireSighashAll(index, decodedInput.sighashType)

                val sigScript = ByteArray(66)
                sigScript[0] = 0x41
                sigBytes.copyInto(sigScript, 1)
                sigScript[65] = sighashType.toByte()
                val redeemHex = decodedInput.redeemScriptHex
                val fullScript = if (unsignedTx.isQuantumSource) {
                    // A quantum (KCC-3) input: then the redeem the unit supplied, `<P> OP_CHECKSIG`,
                    // pushed. It must be exactly the script this input's address is the hash of -
                    // checked here, since a wrong one would only fail at the node, and the codec
                    // doesn't hash (iOS 961fc05a).
                    val redeem = redeemHex?.hexToBytesLocal()
                    val lock = unsignedTx.inputUtxos[index].utxoEntry.scriptPublicKey.scriptPublicKey.lowercase()
                    if (redeem == null || !KsptCodec.isKcc3Redeem(redeem) ||
                        com.kachat.app.services.kachatnames.KachatNames.Codec.p2shScript(redeem).toHexStringLocal() != lock
                    ) {
                        throw IllegalStateException(tr(R.string.coldq_error_script_mismatch) + " (#$index)")
                    }
                    sigScript + byteArrayOf(redeem.size.toByte()) + redeem
                } else {
                    // a plain single-key input never carries one
                    require(redeemHex == null) { "Signed transaction's input $index doesn't match what was sent for signing" }
                    sigScript
                }
                input.copy(signatureScript = fullScript.toHexStringLocal())
            }

            val signedTx = unsignedTx.rawTx.copy(inputs = signedInputs)
            // A submit error is checked against the network before it counts: a signed Cold
            // Storage send reported failed would be signed and sent again (audit IOS-014).
            val txId = nodePoolManager.submitConfirmingKnown(signedTx, networkService.kaspaRestApi.value) {
                // a send that moves money isn't left in a node's orphan pool (iOS 5848fb0)
                nodePoolManager.submitWaitingForParent(signedTx, escalateOrphan = false)
            }
            // Spent: a send built before the index catches up must not pick these again (AND-015).
            recordSpent(unsignedTx)
            Result.success(txId)
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to broadcast signed transaction", e)
            Result.failure(e)
        }
    }

    /** Records [tx]'s inputs as spent from the address they came from. */
    private fun recordSpent(tx: UnsignedColdTx) {
        if (tx.fromAddress.isNotEmpty()) {
            pendingSpent.record(tx.fromAddress, tx.rawTx.inputs.map { it.previousOutpoint })
        } else {
            tx.inputUtxos.groupBy { it.address }.forEach { (address, utxos) ->
                pendingSpent.record(address, utxos.map { it.outpoint })
            }
        }
    }

    /** A Cold Storage send's outputs, and what it really pays - see [coldOutputs]. */
    internal data class ColdOutputs(
        val outputs: List<RawOutputWithVersion>,
        /** The change output's value, 0 when none was emitted. */
        val changeSompi: Long,
        /** Everything the inputs hold that no output carries: the network fee plus any dust
         *  folded into it. What the confirmation screen shows. */
        val paidFeeSompi: Long
    )

    internal companion object {
        /** The coins a quantum source's transaction takes: all of them up to the input cap, the
         *  largest first, a coin a "Not confirmed" send spent leading (as the automatic selector).
         *  The build, Max and Compound all price and spend exactly this set (audit 2026-11-25 T1). */
        fun quantumSourceCoins(spendable: List<UtxoEntry>, leadingOutpoint: String?): List<UtxoEntry> {
            val sorted = spendable.sortedByDescending { it.utxoEntry.amount }.toMutableList()
            val lead = leadingOutpoint?.lowercase()
            if (lead != null) {
                val index = sorted.indexOfFirst { KaspaWalletEngine.outpointKey(it.outpoint).lowercase() == lead }
                if (index > 0) sorted.add(0, sorted.removeAt(index))
            }
            return sorted.take(KsptCodec.MAX_INPUTS)
        }

        /** SIGHASH_ALL - the only signature type a Cold Storage send broadcasts. */
        const val SIGHASH_ALL = 0x01

        /** The signature script one input from [address] will carry, for pricing: a KCC-3
         *  (quantum) input's is the 66-byte signature then the 34-byte redeem pushed, 101 bytes
         *  (iOS 961fc05a `signatureScriptLength(spending:)`). */
        fun signatureScriptSize(address: String?): Int =
            if (ColdStorageManager.isScriptHashAddress(address)) KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN
            else KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN

        /**
         * A server's quoted rate, floored at the network minimum. One server answering NaN, infinity
         * or 1e30 is ignored rather than paid (iOS 953b255 `fetchQuotedFeeRateSompiPerGram`, IOS-061).
         */
        fun quotedFeeRate(quoted: Double?): Long {
            val minimum = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
            if (quoted == null || !quoted.isFinite() || quoted < 0 || quoted > com.kachat.app.services.kachatnames.KachatNames.MAX_FEERATE) return minimum
            return ceil(quoted).toLong().coerceAtLeast(minimum)
        }

        /**
         * The sighash type input [index] was signed with, which must be SIGHASH_ALL (an absent
         * byte means the signer's default, SIGHASH_ALL). Anything else - NONE, SINGLE,
         * ANYONECANPAY - does not commit to the whole transaction, so a buggy or tampered signer's
         * signature is refused before broadcast (iOS 5096466, audit IOS-019).
         */
        fun requireSighashAll(index: Int, sighashType: Int?): Int {
            val type = sighashType ?: SIGHASH_ALL
            if (type != SIGHASH_ALL) {
                throw IllegalStateException(
                    "Input $index was signed with a signature type that doesn't cover the whole transaction, so it won't be broadcast"
                )
            }
            return type
        }

        /**
         * The recipient output, plus the change whenever this transaction's KIP-9 storage mass
         * lets it stand (the hot wallet's rule, and KasSigner's own dust test). Only a remainder
         * of at most [KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI] is folded into the fee; a bigger
         * one that cannot stand is refused with the small-send message (iOS dd4d977 / e6f0dfe).
         *
         * The fee reported is inputs - outputs, so a folded remainder is shown as paid rather
         * than hidden behind the network fee alone (iOS e6f0dfe, audit IOS-013).
         */
        fun coldOutputs(
            selection: ColdUtxoSelector.ColdSelection,
            recipientScriptHex: String,
            changeScriptHex: String,
            /** The change would go back to a quantum source whose key this spend reveals: refused
             *  when there is change to keep (iOS 961fc05a, IOS-104). */
            refuseChange: Boolean = false,
        ): ColdOutputs {
            val outputs = mutableListOf(
                RawOutputWithVersion(amount = selection.finalAmount, scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0))
            )
            val inputAmounts = selection.selectedUtxos.map { it.utxoEntry.amount }
            if (selection.storageMassBlocked || (selection.changeAmount > 0 &&
                    !KaspaUtxoSelector.changeIsKeptOrFoldable(inputAmounts, listOf(selection.finalAmount), selection.changeAmount))
            ) {
                throw IllegalStateException(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE)
            }
            // A selection priced without a change output never gets one (its fee wouldn't cover it).
            val keepsChange = selection.changeAmount > 0 && selection.changeOutputPriced &&
                KaspaMass.fitsStorageMass(inputAmounts, listOf(selection.finalAmount, selection.changeAmount))
            if (keepsChange && refuseChange) {
                throw IllegalStateException(tr(R.string.coldq_error_change_needs_fresh))
            }
            if (keepsChange) {
                outputs.add(
                    RawOutputWithVersion(amount = selection.changeAmount, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0))
                )
            }
            if (!KaspaMass.fitsStorageMass(inputAmounts, outputs.map { it.amount })) {
                throw IllegalStateException(
                    "This amount is too small to send from the coins available (Kaspa storage-mass limit). Try a larger amount, or consolidate this address first."
                )
            }
            if (outputs.size > KsptCodec.MAX_OUTPUTS) {
                throw IllegalStateException("Too many outputs for KSPT")
            }
            val inputTotal = UtxoMath.total(inputAmounts)
            val outputTotal = UtxoMath.total(outputs.map { it.amount })
            val paidFee = if (inputTotal >= outputTotal) inputTotal - outputTotal else selection.estimatedFee
            return ColdOutputs(outputs, if (keepsChange) selection.changeAmount else 0L, paidFee)
        }
    }

    private fun String.hexToBytesLocal(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexStringLocal(): String = joinToString("") { "%02x".format(it) }
}
