package com.kachat.app.services

import com.kachat.app.util.HandshakeAliasPolicy
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.HandshakePayload
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WalletService — handles high-level wallet operations like balance tracking
 * and transaction orchestration (Build -> Sign -> Broadcast).
 *
 * This matches the WalletService/ChatService logic in the iOS app.
 */
@Singleton
class WalletService @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val walletEngine: KaspaWalletEngine,
    private val chatRepository: ChatRepository,
    /** .kachat names for the chatting-address scan. */
    private val nameServices: NameServicesClient,
    /** Finishes a KNS reveal an older version left unfinished (audit 2026-11-24 #4). */
    private val knsRevealRecovery: KnsRevealRecovery,
    /** Writes the `saved_handshake` note after every handshake (XP-003). Lazy: the note service
     *  sends through this one. */
    private val contactNotesLazy: dagger.Lazy<ContactNoteService>,
) {
    private val gson = Gson()

    private val _balance = MutableStateFlow(0L)
    val balance: StateFlow<Long> = _balance.asStateFlow()

    // [balance]'s 0L initial value is indistinguishable from a genuinely empty wallet, so
    // consumers that must react only to a *confirmed* zero (ChatThreadScreen's funding gate)
    // check this alongside it — it flips true the first time a balance fetch for the chatting
    // (identity) address actually succeeds, and only ever flips back on an account switch
    // (see resetBalancesForAccountSwitch: the new account's balance is unknown again).
    private val _balanceKnown = MutableStateFlow(false)
    val balanceKnown: StateFlow<Boolean> = _balanceKnown.asStateFlow()

    private val _spendingBalance = MutableStateFlow(0L)

    /** Every revealed spending address together, not just the current one. Null until a fetch
     *  succeeds, and left alone on a failure, so the caller can omit the line rather than claim
     *  a total of zero. */
    private val _spendingTotalBalance = MutableStateFlow<Long?>(null)
    val spendingTotalBalance: StateFlow<Long?> = _spendingTotalBalance.asStateFlow()

    /** Amount sent with a handshake transaction: 0.2 KAS (matches iOS `handshakeAmount`). */
    private val HANDSHAKE_AMOUNT_SOMPI = 20_000_000L

    data class SendResult(val txId: String, val payloadHex: String)

    /**
     * The REST API client is built asynchronously off [AppSettingsRepository]'s persisted URL
     * (see [NetworkService.observeSettings]), so right after a cold app launch it can still be
     * null for the first tens-of-milliseconds while that first DataStore read resolves. Every
     * caller here used to just no-op on null with no retry, so a refresh that raced this window
     * (as [WalletViewModel]'s own init block always does) silently never happened, leaving
     * balance/UTXOs stale until some later, unrelated action happened to call this again — this
     * waits briefly instead, bounded so a genuinely broken custom endpoint doesn't hang forever.
     */
    private suspend fun readyApi(): KaspaRestApi? =
        networkService.kaspaRestApi.value ?: withTimeoutOrNull(10_000) { networkService.kaspaRestApi.filterNotNull().first() }

    /**
     * Account switch: zero the balances and drop [balanceKnown] back to false so the new account
     * never renders the previous account's numbers while its own first fetch is in flight — a
     * brand-new account would otherwise flash the old account's balance. Called by
     * WalletViewModel's activeAddressFlow collector, the same reset point the KNS profile state
     * uses.
     */
    fun resetBalancesForAccountSwitch() {
        _balance.value = 0L
        _spendingBalance.value = 0L
        // Profile's Manage Addresses total belongs to the account it was read for: cleared (the
        // card shows no figure) until the new account's lands, never carried over (iOS c130dc1).
        _spendingTotalBalance.value = null
        _balanceKnown.value = false
    }

    suspend fun refreshBalance() {
        val address = try { walletManager.getAddress() } catch (e: Exception) { return }
        val api = readyApi() ?: return

        try {
            // Node first (the sum of the address's UTXOs, as iOS computes it), the REST
            // gateway second - see KaspaWalletEngine.nodeBalance.
            val balance = walletEngine.nodeBalance(address) ?: api.getBalance(address).balance
            // A fetch that raced an account switch must not stamp the OLD account's balance
            // under the new one.
            if ((try { walletManager.getAddress() } catch (e: Exception) { null }) != address) return
            _balance.value = balance
            _balanceKnown.value = true
            balanceReadAt = address to System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("WalletService", "Error refreshing balance", e)
        }
    }

    /** When [refreshBalance] / [refreshSpendingBalance] last succeeded, and for which address. */
    @Volatile private var balanceReadAt: Pair<String, Long>? = null
    @Volatile private var spendingBalanceReadAt: Pair<String, Long>? = null

    /** Whether the chatting balance was read for the active account within [maxAgeMs] - so a
     *  screen opening right after another doesn't fetch the same balance again. */
    fun balanceReadWithin(maxAgeMs: Long): Boolean {
        val (address, at) = balanceReadAt ?: return false
        val current = try { walletManager.getAddress() } catch (e: Exception) { return false }
        return address == current && System.currentTimeMillis() - at in 0 until maxAgeMs
    }

    /** [balanceReadWithin] for the spending balance and total. */
    fun spendingBalanceReadWithin(maxAgeMs: Long): Boolean {
        val (address, at) = spendingBalanceReadAt ?: return false
        val current = try { walletManager.currentSpendingAddress() } catch (e: Exception) { return false }
        return address == current && System.currentTimeMillis() - at in 0 until maxAgeMs
    }

    suspend fun refreshSpendingBalance() {
        val address = try { walletManager.currentSpendingAddress() } catch (e: Exception) { return }
        val api = readyApi() ?: return

        try {
            val balance = walletEngine.nodeBalance(address) ?: api.getBalance(address).balance
            // Same account-switch race guard as refreshBalance (the spending address is
            // per-account too).
            if ((try { walletManager.currentSpendingAddress() } catch (e: Exception) { null }) != address) return
            _spendingBalance.value = balance
        } catch (e: Exception) {
            Log.e("WalletService", "Error refreshing spending balance", e)
        }

        // The whole set, in ONE call (iOS WalletView.loadSpendingTotal): the node first, the REST
        // gateway's batch endpoint second. No list means the addresses couldn't be worked out, not
        // that they hold nothing: a dash. A failure leaves the previous total in place: a few
        // seconds stale reads better than a figure that vanishes because one request timed out -
        // and never a partial sweep, whose missing addresses would read as zero.
        val all = try { walletManager.allSpendingAddresses() } catch (e: Exception) { emptyList() }
        if (all.isEmpty()) {
            _spendingTotalBalance.value = null
            return
        }
        try {
            val total = walletEngine.nodeBalance(all)
                ?: api.getBalances(BalancesRequest(all)).sumOf { it.balance }
            if ((try { walletManager.currentSpendingAddress() } catch (e: Exception) { null }) != address) return
            _spendingTotalBalance.value = total
            spendingBalanceReadAt = address to System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WalletService", "Error refreshing total spending balance", e)
        }
    }

    /**
     * Orchestrates a Kaspa payment: Fetch UTXOs -> Build -> Sign -> Broadcast. Identity-address
     * sourced — used internally by [sendKasiaMessage]/[sendHandshake] below (all
     * unqualified same-class calls to this exact method) as well as anything else that needs a
     * plain identity-sourced send. "Pay in Kaspa" does NOT go through this — see [payInKaspa].
     * @return The transaction ID if successful.
     */
    suspend fun sendKaspa(
        toAddress: String,
        amountSompi: Long,
        payloadBytes: ByteArray? = null,
        feeRateOverride: Long? = null,
        allowReducedAmount: Boolean = false,
        /** Coin control and the extra fee - the Send KAS sheet with Chats Payment Privacy off
         *  (iOS 62c2773). Defaults keep every other caller unchanged. */
        manualUtxos: List<UtxoEntry>? = null,
        extraFeeSompi: Long = 0L,
        /** A chat payment spends only confirmed non-coinbase coins, and picks greedily among coin
         *  control's (iOS sendPaymentInternal). */
        chatPayment: Boolean = false,
        /** The account that pays and signs, bound by the caller (see [sendKasiaMessage]); null
         *  means the active account at the moment of the call. Passed together. */
        fromAddress: String? = null,
        signingPrivateKey: ByteArray? = null,
    ): String {
        require((fromAddress == null) == (signingPrivateKey == null)) { "fromAddress and signingPrivateKey go together" }
        val from = fromAddress ?: walletManager.getAddress()
        val signer = signingPrivateKey ?: walletManager.getPrivateKeyBytes()
        // Coin selection, fee math and a Schnorr signature per input are CPU work, and callers
        // reach here from the main thread (a view model's launch): off it, so a send never
        // stalls the screen. The network calls inside simply suspend as before.
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) { walletEngine.sendKaspa(
            toAddress, amountSompi, payloadBytes,
            fromAddress = from,
            signingPrivateKey = signer,
            feeRateOverride = feeRateOverride,
            manualUtxos = manualUtxos,
            allowReducedAmount = allowReducedAmount,
            extraFeeSompi = extraFeeSompi,
            excludeCoinbase = chatPayment,
            confirmedOnly = chatPayment,
            greedyAmongManual = chatPayment
        ) }

        if (result.isSuccess) {
            refreshBalance()
            return result.getOrThrow()
        } else {
            throw result.exceptionOrNull() ?: Exception("Unknown error during Kaspa send")
        }
    }

    /**
     * A chat payment whose From is one of the spending addresses (iOS 6333dd3
     * `sendPayment(sourceSpendingIndex:)`) - see [KaspaWalletEngine.sendSpendingPayment]: signed
     * with [sourceSpendingIndex]'s own key, change to a never-used address. A payment from the
     * chatting address (the default From) goes through [sendKaspa] above instead.
     * @return The transaction ID if successful.
     */
    suspend fun payInKaspa(
        toAddress: String,
        amountSompi: Long,
        sourceSpendingIndex: Int,
        feeRateOverride: Long? = null,
        payloadBytes: ByteArray? = null,
        /** Coin control on the source address (null = automatic selection) and the Fast /
         *  Priority / custom extra fee (iOS 62c2773). */
        manualUtxos: List<UtxoEntry>? = null,
        extraFeeSompi: Long = 0L,
        /** The fee the Review showed; a send that would pay more goes nowhere (iOS 4f8e14bb). */
        expectedFeeSompi: Long? = null,
    ): String {
        // Off the main thread, as [sendKaspa]: this also derives the spending key (BIP39 PBKDF2).
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) { walletEngine.sendSpendingPayment(
            toAddress = toAddress,
            amountSompi = amountSompi,
            sourceSpendingIndex = sourceSpendingIndex,
            feeRateOverride = feeRateOverride,
            payloadBytes = payloadBytes,
            manualUtxos = manualUtxos,
            extraFeeSompi = extraFeeSompi,
            isAddressUsed = { spendingAddressUsedState(it) },
            expectedFeeSompi = expectedFeeSompi,
        ) }

        if (result.isSuccess) {
            refreshSpendingBalance()
            return result.getOrThrow()
        } else {
            throw result.exceptionOrNull() ?: Exception("Unknown error during Kaspa send")
        }
    }

    /** Addresses a history lookup found unused this session (iOS `sessionUnusedAddresses`): "unused"
     *  can change any time, so it is never persisted, only remembered until the app restarts. */
    private val sessionUnusedAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** What is already known about [address]'s use without a lookup (iOS
     *  `cachedSpendingAddressUsedState`): true once it was ever seen used, false when a lookup this
     *  session found it unused, null when unknown. */
    fun cachedSpendingAddressUsedState(address: String): Boolean? = when {
        walletManager.isAddressKnownUsed(address) -> true
        address in sessionUnusedAddresses -> false
        else -> null
    }

    /**
     * Whether [address] was ever used (iOS `spendingAddressUsedState`, 338d9f5): the cached answer,
     * else a one-row history lookup. True = used (remembered for good: "used" is monotonic), false
     * = confirmed unused, null = the lookup failed, so used-ness is unknown right now - never
     * guessed as unused.
     */
    suspend fun spendingAddressUsedState(
        address: String,
        /** Ask the network even when this session saw the address unused, for a decision that
         *  must not rest on a stale "unused" (a quantum change address that may have been used
         *  since, audit 2026-11-25 Q3). A persisted "used" still answers at once: used never goes
         *  back. */
        skipSessionCache: Boolean = false,
    ): Boolean? {
        cachedSpendingAddressUsedState(address)?.let { if (it || !skipSessionCache) return it }
        val api = readyApi() ?: return null
        return try {
            val used = api.getTransactions(address, limit = 1).isNotEmpty()
            if (used) {
                sessionUnusedAddresses.remove(address)
                walletManager.markAddressUsed(address)
            } else {
                sessionUnusedAddresses.add(address)
            }
            used
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    data class SpendingAddressEntry(
        val index: Int,
        val address: String,
        val balanceSompi: Long,
        val everUsed: Boolean,
        val isCurrent: Boolean,
        val hidden: Boolean = false,
        val label: String? = null,
        // Whether this load actually confirmed the row's balance AND used-ness live. A throttled
        // or failed lookup used to be swallowed as balance=0/everUsed=false — indistinguishable
        // from a genuinely fresh address, which is how Generate kept re-offering the same low
        // index. Old persisted snapshots lack this field (Gson defaults it to false), which is
        // safe: only Generate consults it, and Generate only ever reads the live list.
        val liveChecked: Boolean = true
    ) {
        /** A quantum-safe (KIP-22) slot: public index [WalletManager.QUANTUM_INDEX_BASE] + n.
         *  Computed, so snapshots from before the upgrade decode unchanged. */
        val isQuantum: Boolean get() = WalletManager.isQuantumIndex(index)

        /** The row's number on its own chain, "Address #n" on both (quantum restarts at 0). */
        val displayTitle: String get() = WalletManager.spendingAddressTitle(index)

        /** Its nickname, else [displayTitle] (iOS `SpendingAddressEntry.displayLabel`). */
        val displayLabel: String get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: displayTitle
    }

    /**
     * Whether a quantum-safe (KIP-22) address has ever SPENT - the one event that puts its key on
     * chain (receiving reveals nothing; iOS `ChatService.quantumAddressSpentState`). true is
     * marked locally and permanent; false means no spend in its whole history; null when the
     * history can't be read right now, or is deeper than the walk goes.
     *
     * The whole history, a page at a time ([quantumSpentFromPages]): a spend buried under later
     * activity still revealed the key (iOS da2c8e2, IOS-105 / AND-051). A found spend is marked
     * for good, so the walk runs once per address; a page that can't be read leaves the answer
     * unknown, never "not spent".
     */
    suspend fun quantumAddressSpentState(address: String): Boolean? {
        if (walletManager.isQuantumAddressSpentLocally(address)) return true
        // the account whose address this is; the pages below can span an account switch, and a
        // found spend is marked for it - nowhere once it was removed (iOS ca09dd0, IOS-109 /
        // AND-056)
        val owner = walletManager.getActiveAccount()?.address
        val api = readyApi() ?: return null
        val spent = quantumSpentFromPages(address) { offset, limit ->
            try {
                api.getTransactions(address, limit = limit, offset = offset)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (spent == true) walletManager.markQuantumAddressSpent(address, owner = owner)
        return spent
    }

    /**
     * Every spending-chain address derived/shown so far for the active account — index 0 through
     * the higher of the current active index and the highest one the Manage Addresses screen has
     * generated — each with its live balance and whether it's ever had any on-chain history
     * (so the UI can steer the user away from reusing an already-used address).
     *
     * Returns an EMPTY list only when nothing could be loaded live (no account, API unreachable,
     * or every balance lookup failed) — a real wallet always lists at least index 0, so callers
     * can treat empty as "the live check failed", and [WalletViewModel.generateNewSpendingAddress]
     * does exactly that. A failed load never overwrites the persisted snapshot.
     */
    suspend fun getSpendingAddressList(): List<SpendingAddressEntry> {
        val account = walletManager.getActiveAccount() ?: return emptyList()
        val maxIndex = WalletManager.legacyMaxSpendingIndex(account)
        val api = readyApi() ?: return emptyList()
        val hiddenIndices = walletManager.getHiddenSpendingIndices(account.address)
        val labels = walletManager.getSpendingAddressLabels(account.address)
        // One seed read for every miss, cache first (iOS 5b7c772c `cachedOrDerivedChainAddresses`).
        val addressByIndex = walletManager.deriveSpendingAddresses(0..maxIndex).toMutableMap()
        if (addressByIndex.size != maxIndex + 1) return emptyList()
        // Both chains (iOS getSpendingAddressList): the quantum-safe slots after the legacy ones,
        // derived with one seed computation for the whole range.
        val quantumMax = WalletManager.maxQuantumIndex(account)
        if (quantumMax >= 0) {
            val base = WalletManager.QUANTUM_INDEX_BASE
            val quantum = walletManager.deriveSpendingAddresses(base until base + quantumMax + 1)
            if (quantum.size != quantumMax + 1) return emptyList()
            addressByIndex.putAll(quantum)
        }
        // Balances in ONE batched round trip (with fetchBalancesBatched's own per-address
        // fallback) instead of the old per-index burst — 2x(maxIndex+1) simultaneous requests
        // against the shared REST host routinely got throttled, and every swallowed failure
        // painted a funded/used row as fresh. An entirely-empty result means the host answered
        // nothing: bail out as a failed load rather than fabricate an all-zero list.
        val balances = fetchBalancesBatched(addressByIndex.values.toList())
        if (balances.isEmpty()) return emptyList()
        // Re-read the primary index AFTER the slow balance round trip — a send can rotate it
        // mid-load, and stamping rows with the index captured before the fetch is how a stale
        // isCurrent used to land in the committed list and the persisted snapshot. Display
        // derives isCurrent live anyway (WalletViewModel.manageAddresses); this keeps the
        // built rows and the self-heal below as fresh as possible too.
        val primaryNow = walletManager.getActiveAccount()?.spendingAddressIndex ?: account.spendingAddressIndex
        // "Used" is monotonic — a persisted positive answer skips the history probe forever;
        // only never-used addresses re-probe (they can become used anytime). Probes run in small
        // chunks, not one big burst, for the same rate-limit reason as above.
        val entries = coroutineScope {
            addressByIndex.keys.sorted().chunked(4).flatMap { chunk ->
                chunk.map { index ->
                    async {
                        val address = addressByIndex.getValue(index)
                        val balance = balances[address]
                        var confirmed = balance != null
                        val everUsed = when {
                            walletManager.isAddressKnownUsed(address) -> true
                            (balance ?: 0L) > 0L -> { walletManager.markAddressUsed(address); true }
                            else -> {
                                val used = try {
                                    api.getTransactions(address, limit = 1).isNotEmpty()
                                } catch (e: Exception) {
                                    confirmed = false
                                    false
                                }
                                if (used) walletManager.markAddressUsed(address)
                                used
                            }
                        }
                        SpendingAddressEntry(
                            index, address, balance ?: 0L, everUsed,
                            isCurrent = index == primaryNow,
                            hidden = index in hiddenIndices,
                            label = labels[index],
                            liveChecked = confirmed
                        )
                    }
                }.awaitAll()
            }
        }
        // Any address may be hidden - funded ones and the current one too (iOS 245a5d2: Address
        // Visibility > Hide All starts the list over), so nothing here forces a row visible.
        // Persist the snapshot so the next open paints instantly (see cachedSpendingAddressList).
        try { walletManager.setManageAddressesSnapshot(account.address, gson.toJson(entries)) } catch (e: Exception) { /* best-effort */ }
        return entries
    }

    /** Last fully-loaded Manage Addresses list for the active account — instant-paint cache;
     *  balances may be a refresh stale, the live load replaces them. `isCurrent` is recomputed
     *  against the LIVE primary index (the snapshot's copy goes stale the moment a send rotates
     *  it), and the hidden flags are the persisted set's, as the live load paints them. */
    fun cachedSpendingAddressList(): List<SpendingAddressEntry> {
        val account = walletManager.getActiveAccount() ?: return emptyList()
        val json = walletManager.getManageAddressesSnapshot(account.address) ?: return emptyList()
        val type = object : TypeToken<List<SpendingAddressEntry>>() {}.type
        val raw: List<SpendingAddressEntry> = try { gson.fromJson(json, type) ?: emptyList() } catch (e: Exception) { emptyList() }
        val hiddenIndices = walletManager.getHiddenSpendingIndices(account.address)
        return raw.map { entry ->
            val isCurrent = entry.index == account.spendingAddressIndex
            // liveChecked means "confirmed by the fetch that produced THIS object" — a snapshot
            // row was confirmed in some PREVIOUS session at best, so it comes back untrusted.
            // Only a fresh getSpendingAddressList load can mark rows live-confirmed.
            entry.copy(
                isCurrent = isCurrent,
                hidden = entry.index in hiddenIndices,
                liveChecked = false
            )
        }
    }

    /**
     * One-time visibility seeding right after an import's spending-index recovery
     * ([WalletViewModel.commitImport] -> [SpendingAddressDiscovery.discoverIndex]): the recovery
     * raises the index bounds to cover every previously used slot, and with a brand-new install's
     * EMPTY hidden set the default "visible unless hidden" painted all of 0..maxIndex — burned
     * change indices, gap addresses, old rotated primaries — as rows the user never revealed
     * here. The expected initial set is the primary plus anything holding a balance; every other
     * recovered index starts hidden (it stays reachable via Address Visibility and Generate's
     * recycling, which un-hides the lowest hidden unused index).
     *
     * Explicit user state always wins: if this wallet already has hidden entries or a Manage
     * Addresses snapshot on this device (e.g. restored from an OS backup), the user curated their
     * visibility before and nothing is rewritten. When the balance fetch fails entirely, seeding
     * still hides the non-primary rows — hiding a funded row is self-healing (the list loader
     * force-unhides primary/funded rows on every successful load), while painting dozens of
     * random rows is not.
     *
     * For [restored], the account the restore started for, whichever account is active by now
     * (iOS e571f45, IOS-107): its own record, chain and hidden set; nothing once it was removed.
     */
    suspend fun seedImportedSpendingVisibility(restored: WalletManager.SpendingSweepAccount) {
        val account = walletManager.sweepAccountRecord(restored) ?: return
        val primary = account.spendingAddressIndex
        val maxIndex = WalletManager.legacyMaxSpendingIndex(account)
        if (maxIndex <= 0) return
        if (walletManager.getHiddenSpendingIndices(account.address).isNotEmpty()) return
        if (walletManager.getManageAddressesSnapshot(account.address) != null) return
        val addressByIndex = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            walletManager.deriveSpendingAddresses(0..maxIndex, restored)
        }
        val balances = fetchBalancesBatched(addressByIndex.values.toList())
        if (!walletManager.sweepAccountStillSaved(restored)) return
        for ((index, address) in addressByIndex) {
            if (index == primary) continue
            if ((balances[address] ?: 0L) > 0L) continue
            walletManager.setSpendingAddressHidden(account.address, index, true)
        }
    }

    /** Whether [address] has any on-chain history — the same single-tx probe the list loader
     *  uses, exposed for Address Visibility rows derived beyond the loaded list. Positive
     *  answers persist (monotonic) so re-opens skip the round-trip. */
    suspend fun hasSpendingAddressBeenUsed(address: String): Boolean {
        if (walletManager.isAddressKnownUsed(address)) return true
        val api = readyApi() ?: return false
        val used = try { api.getTransactions(address, limit = 1).isNotEmpty() } catch (e: Exception) { false }
        if (used) walletManager.markAddressUsed(address)
        return used
    }

    /**
     * One scanned identity-chain slot for the import wizard's "Change Chatting Address" picker —
     * mirrors iOS's `ChattingAddressCandidate`. The picker lists only the interesting ones
     * (balance or usernames), always including index 0 and the current index.
     */
    data class ChattingAddressCandidate(
        val index: Int,
        val address: String,
        val balanceSompi: Long,
        /** .kachat names (active or in grace) where the registry is live. */
        val kachatNames: List<OwnedServiceName> = emptyList(),
    ) {
        val nameCount: Int get() = kachatNames.size
        /** The one name to show on the row when there is exactly one. */
        val onlyName: String? get() = if (nameCount != 1) null else kachatNames.firstOrNull()?.display
        val isInteresting: Boolean get() = balanceSompi > 0L || nameCount > 0
    }

    /**
     * Derives [indices] on the active account's identity chain — WITHIN its own source family,
     * see [WalletManager.deriveChattingAddresses] — and checks the whole batch for KAS balance
     * (one batched `POST addresses/balances` call, with a bounded-concurrency per-address sweep as
     * fallback) and .kachat names. Never fires 50 raw balance requests.
     *
     * Returns ALL derived candidates in index order; the caller filters for interesting ones. Null
     * when derivation itself fails (no active account / unusable seed), which the UI surfaces as
     * an error rather than an empty scan.
     */
    suspend fun scanChattingAddressCandidates(indices: IntRange): List<ChattingAddressCandidate>? = coroutineScope {
        val derived = walletManager.deriveChattingAddresses(indices)
        if (derived.isEmpty()) return@coroutineScope null
        val addresses = derived.map { it.second }

        val balanceByAddress = fetchBalancesBatched(addresses)

        val kachatNamesByAddress = runCatching { nameServices.kachatNames(addresses) }.getOrDefault(emptyMap())

        derived.map { (index, address) ->
            ChattingAddressCandidate(
                index = index,
                address = address,
                balanceSompi = balanceByAddress[address] ?: 0L,
                kachatNames = kachatNamesByAddress[address].orEmpty(),
            )
        }
    }

    /**
     * Import's auto-detect: every wallet type's addresses, keeping the ones with funds, plus any
     * address #0 with transaction history (a chat identity or an emptied wallet holds no coins).
     * The wallet type holding the most comes first and each type's addresses are in index order,
     * so the first entry is the likeliest account ([ImportAccountCandidate.ranked]). Empty when
     * nothing has any activity; null when the words are invalid or the balances couldn't be
     * checked (iOS da467165).
     */
    suspend fun findImportAccounts(
        words: List<String>,
        passphrase: String,
    ): List<com.kachat.app.models.ImportAccountCandidate>? = coroutineScope {
        val candidates = withContext(kotlinx.coroutines.Dispatchers.Default) {
            walletManager.importCandidates(words, passphrase) { it.importSearchDepth }
        }
        if (candidates.isEmpty()) return@coroutineScope null
        val used = async { addressesWithHistory(candidates.filter { it.index == 0 }.map { it.address }) }
        val balances = fetchBalancesBatched(candidates.map { it.address })
        if (balances.isEmpty()) {
            used.cancel()
            return@coroutineScope null
        }
        com.kachat.app.models.ImportAccountCandidate.ranked(candidates, balances, used.await())
    }

    /** The addresses with at least one transaction. A failed probe counts as no history: this only
     *  ranks what import shows first, and every wallet type stays one tap away (iOS da467165). */
    private suspend fun addressesWithHistory(addresses: List<String>): Set<String> = coroutineScope {
        val api = readyApi() ?: return@coroutineScope emptySet()
        addresses.map { address ->
            async {
                val total = withTimeoutOrNull(HISTORY_PROBE_TIMEOUT_MS) {
                    try { api.getTransactionsCount(address).total } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                }
                address.takeIf { (total ?: 0) > 0 }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    /** One batched balances call, degrading to a chunked-concurrent sweep when the configured REST
     *  host doesn't implement the batch endpoint. Missing/failed addresses simply come back absent
     *  (treated as zero by the caller). */
    private suspend fun fetchBalancesBatched(addresses: List<String>): Map<String, Long> = coroutineScope {
        val api = readyApi() ?: return@coroutineScope emptyMap()
        try {
            api.getBalances(BalancesRequest(addresses)).associate { it.address to it.balance }
        } catch (e: Exception) {
            Log.w("WalletService", "Batched balances unavailable, falling back to per-address sweep", e)
            val out = mutableMapOf<String, Long>()
            for (chunk in addresses.chunked(8)) {
                chunk.map { address ->
                    async { address to (try { api.getBalance(address).balance } catch (e: Exception) { null }) }
                }.awaitAll().forEach { (address, balance) -> if (balance != null) out[address] = balance }
            }
            out
        }
    }

    /**
     * Makes the identity-chain address at [index] this account's chatting address, returning the
     * new address. Delegates straight to [WalletManager.switchChattingAddress] (which rewrites the
     * account record and routes the change through the normal account-switch machinery); the
     * caller is responsible for refreshing its own derived UI state afterwards.
     */
    fun switchChattingAddress(index: Int): String = walletManager.switchChattingAddress(index)

    /**
     * Hides (or shows) one spending-chain address in Manage Addresses (iOS 245a5d2
     * `setSpendingAddressHidden`). Any address can be hidden - funded ones and the current one
     * too: the person can start the list over from nothing (Address Visibility > Hide All).
     * Nothing is lost: Profile's Manage Addresses total still counts hidden addresses, and
     * Address Visibility brings any back.
     * @return whether the flag was persisted (false only with no active account).
     */
    suspend fun setSpendingAddressHidden(index: Int, hidden: Boolean): Boolean {
        val account = walletManager.getActiveAccount() ?: return false
        walletManager.setSpendingAddressHidden(account.address, index, hidden)
        return true
    }

    /** Hides every revealed spending address at once (iOS 245a5d2 `hideAllSpendingAddresses`,
     *  Address Visibility > Hide All): Manage Addresses then starts empty under the chatting
     *  address. */
    fun hideAllSpendingAddresses() {
        val account = walletManager.getActiveAccount() ?: return
        val maxIndex = maxOf(0, WalletManager.legacyMaxSpendingIndex(account))
        // both chains: every revealed quantum slot too
        val quantumMax = WalletManager.maxQuantumIndex(account)
        val base = WalletManager.QUANTUM_INDEX_BASE
        walletManager.setSpendingAddressesHidden(account.address, (0..maxIndex).toSet() + (base until base + quantumMax + 1))
    }

    /** Sets or clears (blank/null) a user nickname for one spending-chain address — see [WalletManager.setSpendingAddressLabel]. */
    fun setSpendingAddressLabel(index: Int, label: String?) {
        val account = walletManager.getActiveAccount() ?: return
        walletManager.setSpendingAddressLabel(account.address, index, label)
    }

    /** Derives one more spending-chain address for the Manage Addresses screen, without changing which one "Pay in Kaspa" currently sources from. */
    fun generateNextSpendingAddress(): Int {
        val address = walletManager.getAddress()
        return walletManager.generateNextSpendingAddress(address)
    }

    /** Reveals one more quantum-safe (KIP-22) slot, un-hidden, and returns its public index -
     *  what Generate New Address makes from now on (iOS `generateNextQuantumAddress`). Checked on
     *  chain first, like every other fresh quantum slot (iOS 2343e84 `verifiedFreshQuantumIndex`,
     *  IOS-101 / AND-049): a slot already used elsewhere is skipped, never handed out as new.
     *  The reveal itself stays under the allocation lock with the in-flight guard
     *  ([WalletManager.generateNextQuantumAddress]). */
    suspend fun generateNextQuantumAddress(owner: String? = null): Int {
        // One account for the probe and the reveal, taken now (iOS 6891935, IOS-109 / AND-056):
        // the slot is probed on that account's chain and revealed there, whichever account is
        // active by then. Removed meanwhile: nothing is revealed.
        val account = walletManager.spendingSweepAccount(owner) ?: throw IllegalStateException("No active account")
        val index = walletManager.verifyFreshQuantumIndex(account) { spendingAddressUsedState(it) }
        if (!walletManager.sweepAccountStillSaved(account)) return index
        return walletManager.generateNextQuantumAddress(account.owner)
    }

    /**
     * Sends an encrypted on-chain message (Kasia "comm" protocol) as a self-stash
     * transaction. No handshake is required first: the message is tagged with the deterministic
     * alias derived via ECDH from both addresses - the recipient can independently derive the
     * exact same value and find it without ever seeing a handshake (see
     * [WalletManager.theirDeterministicAlias]). Only a pre-existing legacy handshake chat - one
     * whose handshake from an older build stored a random alias of ours - keeps writing on that
     * alias (XP-003, [HandshakeAliasPolicy]).
     *
     * [firstContactTag] false: never the first-contact inbox tag - an invisible envelope that
     * is not a message (a `payment_notice`, as iOS `sendEnvelopeInternal` sends it) must not use
     * up the one tagged first message.
     */
    suspend fun sendKasiaMessage(
        toContactId: String,
        text: String,
        feeRateOverride: Long? = null,
        firstContactTag: Boolean = true,
        /** The account the send was started under. One account seals, tags and pays for it, read
         *  once here (audit 2026-11-27 C5/A4): the address and key used to be read again across
         *  the suspensions below, so a switch in between sealed it as one account and paid from
         *  the other, linking the two on chain. A switch away from [owner] refuses the send. */
        owner: String? = null,
    ): SendResult {
        val me = walletManager.getAddress()
        if (owner != null && !me.equals(owner, ignoreCase = true)) throw SendAccountChangedException()
        val key = walletManager.getPrivateKeyBytes()
        if (!com.kachat.app.util.AccountKey.belongsTo(key, me)) throw SendAccountChangedException()
        // Writing to someone is consent: their chat is accepted, and a block on them lifts
        // (NO_HANDSHAKE_MESSAGING.md §4).
        if (!toContactId.equals(me, ignoreCase = true)) chatRepository.acceptChat(toContactId)
        val recipientPubKey = KaspaAddress.decode(toContactId).second
        val contact = chatRepository.getContact(toContactId, walletAddress = me)

        val alias = HandshakeAliasPolicy.messageAlias(
            handshakeComplete = contact?.handshakeComplete == true,
            myAlias = contact?.myAlias,
            deterministicAlias = com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(key, recipientPubKey, contextXOnlyPubKey = recipientPubKey),
        )

        // Sealed so the seed can read it back on any device (iOS bfe9f022, MESSAGING.md "Reading
        // back your own sends"); to the recipient it's an ordinary message.
        val encrypted = withContext(kotlinx.coroutines.Dispatchers.Default) {
            MessageProtocol.encryptContextual(text, recipientPubKey, senderPrivateKey = key)
        }
        // First contact carries their inbox tag so they can find it (NO_HANDSHAKE_MESSAGING.md §3).
        val inboxTag = if (firstContactTag) chatRepository.firstContactInboxTag(toContactId, me = me) else null
        val payloadBytes = MessageProtocol.buildCommPayload(alias, encrypted, inboxTag)

        // Still the same account right before it is signed and paid for, from that account's own
        // address with that account's own key.
        if (!walletManager.getAddress().equals(me, ignoreCase = true)) throw SendAccountChangedException()
        val txId = sendKaspa(
            toAddress = me, amountSompi = 0, payloadBytes = payloadBytes, feeRateOverride = feeRateOverride,
            fromAddress = me, signingPrivateKey = key,
        )
        // The one tagged first message is out - recorded only after the submit, so a failed send
        // does not use the tag up.
        if (inboxTag != null) chatRepository.noteInboxTagged(toContactId)
        return SendResult(txId, payloadBytes.toHexString())
    }

    /**
     * Sends a "handshake" transaction (0.2 KAS to the recipient) carrying our alias
     * and encrypted for their address-derived public key.
     *
     * NOTE: [handshakeComplete] only reflects "we sent a handshake," not "the recipient
     * acknowledged it" — the real protocol has no explicit handshake-ack message either.
     * [isResponse] marks this as a reply to an incoming request (see [acceptHandshake]),
     * which immediately activates the conversation locally rather than leaving it pending.
     */
    private suspend fun sendHandshake(toAddress: String, recipientPubKey: ByteArray, isResponse: Boolean = false): String {
        val existing = chatRepository.getContact(toAddress)
        // Our protocol alias (12 lowercase hex chars, which real clients validate) - NOT our
        // human-readable account name. It is our actual outgoing alias for this contact
        // (DETERMINISTIC_ALIASES.md §4.4, audit XP-003): the deterministic one for a new chat -
        // the alias Desktop fetches under and a reinstall derives again - and a legacy random
        // alias only for a chat that already has one from an older build. Never generated
        // fresh. Accepting an incoming (legacy) handshake follows the same rule.
        val myAlias = HandshakeAliasPolicy.handshakeAlias(
            existingMyAlias = existing?.myAlias,
            deterministicAlias = walletManager.theirDeterministicAlias(toAddress),
        )

        val payload = HandshakePayload(
            alias = myAlias,
            timestamp = System.currentTimeMillis(),
            conversationId = null,
            recipientAddress = toAddress,
            isResponse = isResponse,
            // Confirms both sides' aliases in one shot on a response — without this, a
            // real Kasia client may not consider its side of the conversation fully
            // active and so never start polling for our self-stashed messages.
            theirAlias = if (isResponse) existing?.theirAlias else null
        )
        val json = Gson().toJson(payload)
        val encrypted = withContext(kotlinx.coroutines.Dispatchers.Default) { MessageProtocol.encrypt(json, recipientPubKey) }
        val payloadBytes = MessageProtocol.buildHandshakePayload(encrypted)

        // Our own bubble for it, under [txId].
        fun handshakeRow(txId: String, deliveryStatus: String) = MessageEntity(
            id = txId,
            contactId = toAddress,
            walletAddress = walletManager.getAddress(),
            type = MessageProtocol.TYPE_HANDSHAKE,
            direction = "sent",
            plaintextBody = if (isResponse) "[Handshake completed]" else "[Request to communicate]",
            encryptedPayload = payloadBytes.toHexString(),
            amountSompi = HANDSHAKE_AMOUNT_SOMPI,
            blockTimestamp = System.currentTimeMillis(),
            deliveryStatus = deliveryStatus
        )

        // A handshake is recognised by its payload, not its amount. When this account cannot
        // cover 0.2 KAS plus the fee - typically because its only coin IS the 0.2 KAS handshake
        // it just received - the amount is reduced by the fee instead of failing, so the
        // conversation can still be answered (mirrors iOS buildHandshakeTx).
        val txId = try {
            sendKaspa(
                toAddress = toAddress,
                amountSompi = HANDSHAKE_AMOUNT_SOMPI,
                payloadBytes = payloadBytes,
                allowReducedAmount = true
            )
        } catch (unknown: SendOutcomeUnknownException) {
            // It may have landed: its bubble is Not confirmed ("warning") under the transaction's
            // own id, and counts as a handshake sent, so none goes out a second time; it settles
            // to sent once the handshake is read back (iOS d23e6f7a `markSendUnconfirmed`). The
            // contact and the note wait for that, as on iOS.
            chatRepository.insertMessage(handshakeRow(unknown.txId, "warning"))
            throw unknown
        }

        chatRepository.addContact(
            (existing ?: ContactEntity(id = toAddress, walletAddress = walletManager.getAddress(), alias = null, knsName = null, publicKeyHex = null))
                .copy(
                    publicKeyHex = recipientPubKey.toHexString(),
                    handshakeComplete = true,
                    conversationStatus = if (isResponse) "active" else (existing?.conversationStatus ?: "active"),
                    myAlias = myAlias
                )
        )

        // So the sender also sees a bubble for their own handshake in their own thread,
        // matching the real reference apps — previously only the recipient ever got a local
        // record of the handshake. A response (accepting an incoming request) reads as
        // "[Handshake completed]" instead of the generic outreach text, since by definition
        // accepting means the connection is now live, not still pending - matches the
        // "🤝 Handshake completed" pill already shown for the other side of a completed
        // handshake (see MessageBubble's `showCompleted`/`pillText`).
        chatRepository.insertMessage(handshakeRow(txId, "sent"))

        // The iOS-shape `saved_handshake` note (alias, theirAlias, partnerAddress), so a reinstall
        // of this seed finds the chat again - the handshake itself is sealed to the peer.
        contactNotesLazy.get().onHandshakeSent(
            partnerAddress = toAddress,
            ourAlias = myAlias,
            theirAlias = existing?.theirAlias,
            isResponse = isResponse,
        )

        return txId
    }

    /**
     * Accepts an incoming handshake request: sends a real reciprocal handshake
     * transaction and activates the conversation locally. Declining is handled
     * entirely client-side (see ChatViewModel.declineHandshake) — no transaction
     * is sent for a decline, matching the real protocol/reference apps.
     */
    suspend fun acceptHandshake(contactId: String): String {
        val recipientPubKey = KaspaAddress.decode(contactId).second
        return sendHandshake(contactId, recipientPubKey, isResponse = true)
    }

    /**
     * Sends an initial (non-response) handshake to a brand new contact — ONLY ever called from an
     * explicit user tap ("Send Handshake" / "start a conversation"). A handshake is an on-chain tx
     * and must never be auto-sent on login, launch, sync, or import. NOTE: [sendKasiaMessage] does
     * NOT auto-send a handshake — messages self-stash with a deterministic alias and need none.
     */
    suspend fun sendHandshakeToNewContact(contactId: String): String {
        val recipientPubKey = KaspaAddress.decode(contactId).second
        return sendHandshake(contactId, recipientPubKey, isResponse = false)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /** The key of the address that made [pending] (its reveal target), when it is this account's:
     *  the chatting key or a legacy spending address's (KNS can't move anything out of a quantum
     *  one, iOS 2a8ca5a). Null when it is another account's. */
    private suspend fun knsRevealOwnerKey(pending: com.kachat.app.models.PendingKnsCommit): ByteArray? {
        val account = walletManager.getActiveAccount() ?: return null
        val owner = pending.revealTargetAddress
        if (account.address.equals(owner, ignoreCase = true)) return walletManager.getPrivateKeyBytes()
        val last = WalletManager.legacyMaxSpendingIndex(account)
        val index = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            walletManager.deriveSpendingAddresses(0..last)
        }.entries.firstOrNull { it.value.equals(owner, ignoreCase = true) }?.key ?: return null
        return walletManager.getSpendingPrivateKeyBytes(index)
    }

    /** Accounts already looked at this run, so an account change back doesn't ask again. */
    private val knsResumeChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Finishes this account's KNS reveal left by an earlier version (iOS `KNSRevealRecovery
     * .resumeIfNeeded`, audit 2026-11-24 #4): KNS left K on 2026-11-23, but a commit whose reveal
     * failed before then still holds coins only its saved redeem script can spend. Called when the
     * app comes to the front and when an account is loaded. Once per account while it goes
     * through; a reveal that can't be settled is tried again on the next return to the app. The
     * node pool may still be coming up, so it is given a few tries. Starts nothing new.
     */
    suspend fun resumePendingKnsReveal() {
        val account = walletManager.getActiveAccount()?.address ?: return
        if (!knsResumeChecked.add(account)) return
        var settled = false
        try {
            val pending = knsRevealRecovery.pendingCommit() ?: run {
                settled = true
                return
            }
            for (attempt in 0 until KNS_RESUME_TRIES) {
                if (attempt > 0) delay(KNS_RESUME_RETRY_MS)
                if (walletManager.getActiveAccount()?.address != account) return
                val key = knsRevealOwnerKey(pending) ?: return
                if (knsRevealRecovery.resumeReveal(pending, key)) {
                    settled = true
                    break
                }
            }
            if (settled) refreshBalance()
        } finally {
            if (!settled) knsResumeChecked.remove(account)
        }
    }

    companion object {
        private const val KNS_RESUME_TRIES = 3
        private const val KNS_RESUME_RETRY_MS = 20_000L

        /** How long import's history probe waits for one address (iOS da467165 `timeoutInterval`). */
        private const val HISTORY_PROBE_TIMEOUT_MS = 8_000L

        /** The shield probe's page size and how many pages it reads at most (iOS da2c8e2: 200 x
         *  50 = 10,000 transactions). */
        internal const val QUANTUM_SPENT_PAGE_SIZE = 200
        internal const val QUANTUM_SPENT_MAX_PAGES = 50

        /**
         * The shield probe's walk (iOS `quantumAddressSpentState`, da2c8e2 / IOS-105 / AND-051):
         * [page] (offset, limit) reads one page of [address]'s history, null when it can't. True
         * at the first page where an input spends from [address]; false once a short page ends
         * the history without one; null when a page can't be read, or the history is deeper than
         * [QUANTUM_SPENT_MAX_PAGES] pages - never "not spent" on a guess.
         */
        internal suspend fun quantumSpentFromPages(
            address: String,
            page: suspend (offset: Int, limit: Int) -> List<TransactionResponse>?,
        ): Boolean? {
            var offset = 0
            while (offset < QUANTUM_SPENT_MAX_PAGES * QUANTUM_SPENT_PAGE_SIZE) {
                val transactions = page(offset, QUANTUM_SPENT_PAGE_SIZE) ?: return null
                val spent = transactions.any { tx ->
                    tx.inputs.any { it.previousOutpointAddress?.equals(address, ignoreCase = true) == true }
                }
                if (spent) return true
                if (transactions.size < QUANTUM_SPENT_PAGE_SIZE) return false
                offset += QUANTUM_SPENT_PAGE_SIZE
            }
            // deeper than the walk goes: say so rather than claim the key is hidden
            return null
        }

        /** Legacy per-conversation random alias — 6 random bytes as 12 lowercase hex chars, the
         *  format Kasia web and iOS KaChat validate. No longer used for new chats (XP-003: a
         *  handshake carries the deterministic alias); kept for its format test. */
        internal fun generateAlias(): String {
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** A send started under one account found another one signed in before it was signed (audit
 *  2026-11-27 C5/A4): nothing was sent. Its text is the chat's "Not sent: you switched accounts". */
class SendAccountChangedException : IllegalStateException(
    runCatching { com.kachat.app.util.AppText.context().getString(com.kachat.app.R.string.not_sent_you_switched_accounts) }
        .getOrDefault("Not sent: you switched accounts")
)
