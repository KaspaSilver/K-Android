package com.kachat.app.viewmodels

import com.kachat.app.util.UserFacingError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.services.ColdStorageSendEngine
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaExtendedPublicKey
import com.kachat.app.util.KsptCodec
import com.kachat.app.util.QrFrameChunker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cold Storage — a fully separate area of the app for interacting with a KasSigner air-gapped
 * device via QR exchange. Deliberately does not touch [WalletManager]/[WalletViewModel] at all:
 * this ViewModel only ever knows about watch-only kpub accounts, never a mnemonic or private key.
 */
@HiltViewModel
class ColdStorageViewModel @Inject constructor(
    private val coldStorageManager: ColdStorageManager,
    private val addressDiscovery: ColdStorageAddressDiscovery,
    private val sendEngine: ColdStorageSendEngine,
    private val settings: AppSettingsRepository,
    private val nameServices: com.kachat.app.services.NameServicesClient,
    /** .kachat owners count toward the "Contains domain" tag (iOS 881ada6). Lazy, and only asked
     *  where the registry is launched, so mainnet never builds it (iOS 7227d69). */
    private val kachatRegistry: dagger.Lazy<com.kachat.app.services.kachatnames.KachatNamesRegistry>,
    /** Only for a spending address's coin labels in Coin Control (iOS 148a5331: Manage Addresses'
     *  labels beside KasSigner's) - never the words or a key. */
    private val walletManager: com.kachat.app.services.WalletManager,
    addressActivityNotifier: com.kachat.app.services.AddressActivityNotifier,
    /** Whether a quantum address has spent (the slashed shield, iOS 961fc05a): the same probe
     *  the hot wallet's quantum addresses use. */
    private val walletServiceLazy: dagger.Lazy<com.kachat.app.services.WalletService>,
) : ViewModel() {

    /** Own addresses whose balance just changed (cold ones included): the detail screen reloads
     *  when one of its rows moved, as iOS's `.ownAddressUtxoActivity` does. */
    val ownAddressActivity: kotlinx.coroutines.flow.SharedFlow<Set<String>> = addressActivityNotifier.utxoActivityEvents

    /** What a typed name points to on every service, .kachat first - the primary answer and the
     *  "Other domains" (iOS 6ac48a7). */
    suspend fun resolveEverywhere(input: String): List<com.kachat.app.services.NameResolution> =
        runCatching { nameServices.resolveEverywhere(input) }.getOrDefault(emptyList())

    private val _accounts = MutableStateFlow(coldStorageManager.getAccounts())
    val accounts: StateFlow<List<ColdStorageManager.ColdAccount>> = _accounts.asStateFlow()

    /** Which block explorer website "Go to Explorer" opens — shared preference, set in Settings > Kaspa Explorer. */
    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.kachat.app.models.KaspaExplorer.default)

    enum class ImportStatus { IDLE, VALIDATING, SUCCESS, INVALID_KPUB }
    data class ImportUiState(val status: ImportStatus = ImportStatus.IDLE, val errorMessage: String? = null)

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** [scannedText] is whatever the QR scanner handed back — validated as a real kpub before saving. */
    fun importKpub(scannedText: String, name: String) {
        _importState.value = ImportUiState(status = ImportStatus.VALIDATING)
        coldStorageManager.saveAccount(name.ifBlank { "Cold Storage" }, scannedText.trim()).fold(
            onSuccess = {
                _accounts.value = coldStorageManager.getAccounts()
                _importState.value = ImportUiState(status = ImportStatus.SUCCESS)
            },
            onFailure = { e ->
                _importState.value = ImportUiState(
                    status = ImportStatus.INVALID_KPUB,
                    errorMessage = UserFacingError.message(e, "Not a valid kpub")
                )
            }
        )
    }

    fun resetImportState() {
        _importState.value = ImportUiState()
    }

    fun renameAccount(id: String, newName: String) {
        coldStorageManager.renameAccount(id, newName)
        _accounts.value = coldStorageManager.getAccounts()
    }

    /** Receive notifications for one cold account, on or off - the others keep theirs. */
    fun setNotifyOnReceive(id: String, enabled: Boolean) {
        coldStorageManager.setNotifyOnReceive(id, enabled)
        _accounts.value = coldStorageManager.getAccounts()
    }

    fun deleteAccount(id: String) {
        coldStorageManager.deleteAccount(id)
        _accounts.value = coldStorageManager.getAccounts()
    }

    // -------------------------------------------------------------------------
    // Detail screen — derived addresses + live balances for a single account
    // -------------------------------------------------------------------------

    data class AddressRow(
        val index: Int,
        val address: String,
        val balanceSompi: Long,
        val hasHistory: Boolean,
        val label: String? = null,
        val hidden: Boolean = false,
        // Whether THIS session's live load (the batched balance pass or a per-address check)
        // actually confirmed the row's balance. Snapshot-painted rows come back false — the
        // spending list's identical rule — so visibility toggles know when they can trust the
        // row instantly versus when they must fall back to a fail-closed live check.
        val liveChecked: Boolean = false,
        /** A KasSigner quantum-resistant (KCC-3) address, added by scan (iOS 961fc05a). Its
         *  [index] is [ColdStorageManager.QUANTUM_INDEX_BASE] + its place in the account's list. */
        val isQuantum: Boolean = false,
        /** The unit's number for a quantum address, when known. */
        val unitIndex: Long? = null,
        /** The unit's KCC-3 chain for a quantum address (0 receive, 1 change). */
        val quantumChain: Int = 0,
    ) {
        /** The row's name: its label, else "Quantum Address #n" for a quantum one, else
         *  "Address #n" (iOS `ColdStorageAddressEntry.displayLabel`). */
        val displayLabel: String
            get() = label?.takeIf { it.isNotBlank() }
                ?: if (isQuantum) ColdStorageManager.ColdQuantumAddress.defaultLabel(quantumChain, unitIndex)
                else com.kachat.app.util.tr(com.kachat.app.R.string.kp_address_lld_64ab3, index)
    }

    private val _addresses = MutableStateFlow<List<AddressRow>>(emptyList())
    val addresses: StateFlow<List<AddressRow>> = _addresses.asStateFlow()

    // Which account [_addresses] currently belongs to. The rows are shared VM state across every
    // cold account's detail screen, so opening account B while A's rows were still loaded used to
    // merge A's balances/used flags into B's list (refreshAddresses seeds its "known" map from
    // whatever is painted) and let Generate recycle indices using A's data. Loading a different
    // account now starts from an empty list.
    private var loadedAccountId: String? = null

    private fun resetAddressesIfAccountChanged(accountId: String) {
        if (loadedAccountId != accountId) {
            _addresses.value = emptyList()
            loadedAccountId = accountId
        }
    }

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Live scan position while discovering, so the address-actions sheet can show progress rather
     * than a spinner that says nothing about whether anything is happening.
     */
    private val _discoveryProgress = MutableStateFlow<ColdStorageAddressDiscovery.DiscoveryProgress?>(null)
    val discoveryProgress: StateFlow<ColdStorageAddressDiscovery.DiscoveryProgress?> = _discoveryProgress.asStateFlow()

    /**
     * Whether the CURRENT scan was started by the user tapping Discover, as opposed to the
     * automatic list refresh that runs on screen entry and pull-to-refresh.
     *
     * [isDiscovering] covers all three, which is right for suppressing overlapping work and wrong
     * for driving UI: binding the Address Actions button to it made the button sit there spinning
     * every time the screen opened, for a load the user never asked for.
     */
    private val _isUserDiscovering = MutableStateFlow(false)
    val isUserDiscovering: StateFlow<Boolean> = _isUserDiscovering.asStateFlow()

    /**
     * Loads the account's address list the way iOS does (ColdStorageManager.getAddressList +
     * ColdStorageView.loadEntries): instant paint from the persisted snapshot of the last full
     * load, then every index up to [ColdStorageManager.ColdAccount.maxDerivedIndex] derived
     * locally with ONE batched balance call — funded addresses always land in that first live
     * commit — then a gap-limit scan past the bound for addresses used before they were ever
     * shown here, and finally a background Used backfill. The old shape (a full sequential
     * 2-calls-per-index scan, committed only at the very end, wiped to an empty list on any
     * error) meant one failed or throttled history lookup at index 0 showed NOTHING at all.
     */
    fun refreshAddresses(
        accountId: String,
        userInitiated: Boolean = false,
        onResult: (Int) -> Unit = {},
    ) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        resetAddressesIfAccountChanged(accountId)
        viewModelScope.launch {
            _isDiscovering.value = true
            _isUserDiscovering.value = userInitiated
            // Progress only matters while the sheet that shows it is open.
            _discoveryProgress.value =
                if (userInitiated) ColdStorageAddressDiscovery.DiscoveryProgress(0, 0) else null
            var loaded: List<AddressRow>? = null
            try {
                val labels = coldStorageManager.getAddressLabels(accountId)
                val hiddenIndices = coldStorageManager.getHiddenIndices(accountId)
                // Instant paint from the snapshot (balances may be a refresh stale — the live
                // pass replaces them). Only seeds an empty screen so live data never regresses;
                // labels/hidden always come from their own live stores, not the snapshot.
                if (_addresses.value.isEmpty()) {
                    snapshotRows(accountId)?.let { rows ->
                        _addresses.value = rows.map {
                            // A snapshot row's confirmation belonged to a previous session:
                            // untrusted until the live pass below re-confirms it.
                            it.copy(label = labels[it.index], hidden = it.index in hiddenIndices, liveChecked = false)
                        }
                    }
                }
                val rootKey = rootKeyFor(accountId)
                if (rootKey == null) {
                    onResult(0)
                    return@launch
                }

                // Pass 1 — fast: derive 0..maxDerivedIndex locally, one batched balance round
                // trip for all of them (iOS's single getUtxosByAddresses call).
                val known = _addresses.value.associateBy { it.index }
                // EC math per address: off the main thread (it froze the screen on open).
                val derived = withContext(Dispatchers.Default) {
                    (0..account.maxDerivedIndex).mapNotNull { index ->
                        runCatching { KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index) }
                            .getOrNull()?.let { index to it }
                    }
                }
                // The quantum addresses added by scan are read in the same round trip (iOS 961fc05a).
                val quantum = coldStorageManager.currentQuantumAddresses(accountId)
                val balances = addressDiscovery.fetchBalances(derived.map { it.second } + quantum.map { it.address })
                if (balances == null) {
                    // Nothing could be checked live. Keep whatever is painted (snapshot or
                    // previous rows) — a stale list beats a blank screen claiming no addresses.
                    onResult(0)
                    return@launch
                }
                _addresses.value = derived.map { (index, address) ->
                    val liveBalance = balances[address]
                    val balance = liveBalance ?: known[index]?.balanceSompi ?: 0L
                    AddressRow(
                        index, address, balance,
                        // Used-ness is monotonic, and a live balance also proves it; the
                        // backfill below fills in the rest.
                        hasHistory = known[index]?.hasHistory == true || balance > 0L,
                        label = labels[index],
                        hidden = index in hiddenIndices,
                        liveChecked = liveBalance != null
                    )
                }.plus(quantumRows(accountId, quantum, balances, known, labels, hiddenIndices))
                    .sortedByDescending { it.index }
                refreshQuantumSpent(quantum.map { it.address })

                // Pass 2 — discovery: gap-limit scan BEYOND the derived bound for addresses
                // funded/used before they were ever shown here (plus the scan's trailing fresh
                // rows, same as before). A failed lookup stops only this scan; pass 1's rows
                // stay put.
                // From ZERO, not from the account's high-water mark. Starting past what is
                // already known meant a rescan could only ever report nothing, which is exactly
                // what it did - and an address that gains a balance later would never be seen.
                // The scan derives a thousand-address window locally: off the main thread too.
                val beyond = withContext(Dispatchers.Default) {
                    addressDiscovery.discoverAddresses(
                        rootKey,
                        startIndex = 0,
                        onProgress = { if (userInitiated) _discoveryProgress.value = it },
                    )
                }
                if (beyond.isNotEmpty()) {
                    // Raise the persisted bound only to cover USED/funded finds — raising it to
                    // the scan's trailing unused rows would grow it by another gap every refresh.
                    // `beyond` now contains ONLY matches (balance or KNS domain), so the bound
                    // covers exactly the rows worth showing.
                    beyond.maxOfOrNull { it.index }?.let { usedMax ->
                        // Rows default to VISIBLE unless explicitly hidden, so hide the empty
                        // indices the new bound sweeps in. The scan reaches a thousand addresses
                        // deep now, and a match at index 291 would otherwise fill this account's
                        // list with 290 empty rows. Matches are un-hidden instead: one that now
                        // holds a balance would otherwise be found and dropped straight back out,
                        // which reads as not finding it at all.
                        val previousMax = coldStorageManager.getAccounts()
                            .firstOrNull { it.id == accountId }?.maxDerivedIndex ?: -1
                        val matchedIndices = beyond.map { it.index }.toSet()
                        if (usedMax > previousMax) {
                            val sweptIn = ((previousMax + 1) until usedMax).filterNot { it in matchedIndices }
                            coldStorageManager.setAddressesHidden(accountId, sweptIn, true)
                        }
                        coldStorageManager.setAddressesHidden(accountId, matchedIndices, false)
                        coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, usedMax)
                        _accounts.value = coldStorageManager.getAccounts()
                    }
                    val beforeMerge = _addresses.value.associateBy { it.index }
                    _addresses.value = (
                        _addresses.value.filterNot { row -> beyond.any { it.index == row.index } } +
                            beyond.map {
                                // Never let an unconfirmed balance overwrite one pass 1 already
                                // read: a KNS-matched row whose balance lookup failed carries 0,
                                // and pass 1's batched figure is the better answer.
                                val known = beforeMerge[it.index]
                                val balance = if (it.balanceConfirmed) it.balanceSompi
                                    else known?.balanceSompi ?: 0L
                                AddressRow(
                                    it.index, it.address, balance,
                                    // A balance IS history - it had to arrive somehow. The scan
                                    // stopped probing history (it costs a second REST call per
                                    // address and is not part of what makes an address worth
                                    // surfacing), so without this a funded address discovered by
                                    // the scan read "Unused", and pass 3 skips funded rows so it
                                    // was never corrected. Same rule pass 1 already applies.
                                    it.hasHistory || balance > 0L || known?.hasHistory == true,
                                    labels[it.index], it.index in hiddenIndices,
                                    // Only claim a live check when the balance was really read -
                                    // the hide guard trusts this flag to skip its own re-check.
                                    liveChecked = it.balanceConfirmed || known?.liveChecked == true
                                )
                            }
                        ).sortedByDescending { it.index }
                }
                loaded = _addresses.value
                // The number of MATCHES, not the growth in list size. Reporting growth meant a
                // rescan of an account whose addresses were already listed said "found none" -
                // true of the list, useless as an answer to "what did the scan find?", and the
                // other half of why the two phones disagreed on the same kpub.
                onResult(beyond.size)
                // "Contains domain" tags: batched cached KNS lookups after the rows are visible.
                refreshDomainOwningAddresses(_addresses.value.map { it.address })
            } catch (e: Exception) {
                // Never wipe the list on failure — that was exactly the "cold wallet shows
                // nothing" bug.
                onResult(0)
            } finally {
                _isDiscovering.value = false
                _isUserDiscovering.value = false
                _discoveryProgress.value = null
            }

            // Pass 3 — background Used backfill for zero-balance rows, sequential on purpose
            // (same rate-limit reasoning as iOS's loadEntries backfill), then persist the
            // snapshot so the next open paints instantly.
            val rows = loaded ?: return@launch
            for (row in rows) {
                if (row.balanceSompi > 0L || row.hasHistory) continue
                val used = addressDiscovery.hasHistory(row.address) ?: continue
                if (used) {
                    _addresses.value = _addresses.value.map { if (it.index == row.index) it.copy(hasHistory = true) else it }
                }
            }
            try { coldStorageManager.setAddressSnapshot(accountId, gson.toJson(_addresses.value)) } catch (e: Exception) { /* best-effort */ }
        }
    }

    /** The account's quantum addresses as rows, after the regular ones, in the order added. */
    private fun quantumRows(
        accountId: String,
        quantum: List<ColdStorageManager.ColdQuantumAddress>,
        balances: Map<String, Long>,
        known: Map<Int, AddressRow>,
        labels: Map<Int, String>,
        hiddenIndices: Set<Int>,
    ): List<AddressRow> = quantum.mapIndexed { position, entry ->
        val index = ColdStorageManager.QUANTUM_INDEX_BASE + position
        val prior = known[index]?.takeIf { it.address == entry.address }
        val liveBalance = balances[entry.address]
        val balance = liveBalance ?: prior?.balanceSompi ?: 0L
        AddressRow(
            index = index,
            address = entry.address,
            balanceSompi = balance,
            hasHistory = prior?.hasHistory == true || balance > 0L,
            label = labels[index],
            hidden = index in hiddenIndices,
            liveChecked = liveBalance != null,
            isQuantum = true,
            unitIndex = entry.unitIndex,
            quantumChain = entry.chain,
        )
    }

    /** Quantum addresses whose key a spend has put on chain (the slashed shield). */
    private val _quantumSpentAddresses = MutableStateFlow<Set<String>>(emptySet())
    val quantumSpentAddresses: StateFlow<Set<String>> = _quantumSpentAddresses.asStateFlow()

    private fun refreshQuantumSpent(addresses: List<String>) {
        if (addresses.isEmpty()) return
        viewModelScope.launch {
            val walletService = walletServiceLazy.get()
            val spent = HashSet<String>()
            for (address in addresses) {
                if (walletService.quantumAddressSpentState(address) == true) spent.add(address.lowercase())
            }
            _quantumSpentAddresses.value = _quantumSpentAddresses.value + spent
        }
    }

    // -------------------------------------------------------------------------
    // Quantum-resistant (KCC-3) addresses (iOS 961fc05a)
    // -------------------------------------------------------------------------

    /** Adds a scanned or pasted KCC-3 address; the error's message is the localized reason. */
    fun addQuantumAddress(accountId: String, text: String, unitIndex: Long?, chain: Int): Result<ColdStorageManager.ColdQuantumAddress> {
        val result = coldStorageManager.addQuantumAddress(text, unitIndex, chain, accountId)
        if (result.isSuccess) {
            _accounts.value = coldStorageManager.getAccounts()
            refreshAddresses(accountId)
        }
        return result
    }

    /** Edit Address Number: sets or clears the number and chain on the unit (Q4). */
    fun updateQuantumAddress(accountId: String, address: String, unitIndex: Long?, chain: Int) {
        coldStorageManager.updateQuantumAddress(address, unitIndex, chain, accountId)
        _accounts.value = coldStorageManager.getAccounts()
        _addresses.value = _addresses.value.map {
            if (it.isQuantum && it.address == address) it.copy(unitIndex = unitIndex, quantumChain = chain) else it
        }
    }

    /** Stop Watching: the address leaves the account; its coins stay on chain. The rows after it
     *  move up a place, as their labels and hidden flags do. */
    fun stopWatchingQuantumAddress(accountId: String, address: String) {
        coldStorageManager.removeQuantumAddress(address, accountId)
        _accounts.value = coldStorageManager.getAccounts()
        _addresses.value = _addresses.value.filterNot { it.isQuantum && it.address == address }
        refreshAddresses(accountId)
    }

    /** The stored record of a quantum address (its number and chain now, not a row's copy). */
    fun quantumRecord(address: String): ColdStorageManager.ColdQuantumAddress? =
        coldStorageManager.quantumAddressRecord(address)?.second

    /** The index an edit of [row] files under now: a quantum row's moves when an earlier quantum
     *  address stops being watched, and the row may be from before that (Q7). */
    fun currentIndex(accountId: String, row: AddressRow): Int? =
        if (row.isQuantum) coldStorageManager.currentQuantumIndex(row.address, accountId) else row.index

    /** Whether Compound is offered on [address]: a quantum one moves its coins to another quantum
     *  address of the account, so only when there is another (iOS 961fc05a `canCompound`). */
    fun canCompound(address: String): Boolean {
        val (account, _) = coldStorageManager.quantumAddressRecord(address) ?: return !ColdStorageManager.isScriptHashAddress(address)
        return account.quantumList.size > 1
    }

    /** Every address of the account holding [address] (lowercased), for the history rows (L3). */
    suspend fun accountAddresses(address: String): Set<String> =
        withContext(Dispatchers.Default) { coldStorageManager.accountAddresses(address) }

    /** The name a quantum address shows (its label, else its default). */
    fun quantumDisplayLabel(address: String): String? = coldStorageManager.quantumDisplayLabel(address)

    private val gson = com.google.gson.Gson()

    /** Last fully-loaded row list for [accountId], from [ColdStorageManager.getAddressSnapshot] —
     *  the instant-paint cache behind [refreshAddresses]. Null when absent or unreadable. */
    private fun snapshotRows(accountId: String): List<AddressRow>? {
        val json = coldStorageManager.getAddressSnapshot(accountId) ?: return null
        val type = object : com.google.gson.reflect.TypeToken<List<AddressRow>>() {}.type
        return try {
            // Gson fills this class field-by-field without running the Kotlin constructor, so a
            // payload that doesn't carry `address` (a snapshot written by a build whose field
            // names differed) yields rows whose non-null `address` is actually null — which then
            // NPEs the moment the row is rendered. Drop anything that didn't come back whole
            // rather than painting it; the live pass repopulates the list either way.
            gson.fromJson<List<AddressRow>>(json, type)?.filter { row ->
                val address: String? = row.address
                !address.isNullOrBlank()
            }
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Usernames on cold addresses: the "Contains username" list tags. .kachat names only since
    // 2026-11-23 (iOS 518fdc8c), where the registry is launched (iOS 881ada6).
    // -------------------------------------------------------------------------

    private val _domainOwningAddresses = MutableStateFlow<Set<String>>(emptySet())
    val domainOwningAddresses: StateFlow<Set<String>> = _domainOwningAddresses.asStateFlow()

    private fun refreshDomainOwningAddresses(addresses: List<String>) {
        if (addresses.isEmpty()) return
        if (!com.kachat.app.services.kachatnames.KachatNamesService.isLaunched) {
            _domainOwningAddresses.value = emptySet()
            return
        }
        viewModelScope.launch {
            _domainOwningAddresses.value = try {
                kachatRegistry.get().ownersOfNames(addresses)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }
    }

    // Parsed kpub root keys, cached per account — the Address Visibility pager derives 50
    // addresses per page on demand, and re-parsing the kpub for each would be wasted work
    // (mirrors WalletManager.spendingChainKey's caching on the spending side).
    // Concurrent: the pager derives its page on Dispatchers.Default.
    private val rootKeyCache = java.util.concurrent.ConcurrentHashMap<String, org.bitcoinj.crypto.DeterministicKey>()

    private fun rootKeyFor(accountId: String): org.bitcoinj.crypto.DeterministicKey? {
        rootKeyCache[accountId]?.let { return it }
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return null
        return KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
            ?.let { KaspaExtendedPublicKey.toDeterministicKey(it) }
            ?.also { rootKeyCache[accountId] = it }
    }

    /**
     * Which cold-storage account and index [address] is, as an address row with its live
     * balance - for the History sheet a tapped "Received" notification opens (iOS 13046ad,
     * `OwnAddressHistorySheet.resolve`, which walks every account's 0...maxAddressIndex the same
     * way). Null when no account derives it. Kept off [_addresses] on purpose: that list belongs
     * to whichever account detail is open underneath the sheet.
     */
    suspend fun resolveOwnColdAddress(address: String): AddressRow? {
        val match = withContext(Dispatchers.Default) {
            for (account in coldStorageManager.getAccounts()) {
                val rootKey = KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
                    ?.let { runCatching { KaspaExtendedPublicKey.toDeterministicKey(it) }.getOrNull() }
                    ?: continue
                for (index in 0..maxOf(0, account.maxDerivedIndex)) {
                    val candidate = runCatching {
                        KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index)
                    }.getOrNull() ?: continue
                    if (candidate.equals(address, ignoreCase = true)) return@withContext Triple(account.id, index, candidate)
                }
            }
            null
        } ?: coldStorageManager.quantumAddressRecord(address)?.let { (account, entry) ->
            coldStorageManager.currentQuantumIndex(entry.address, account.id)?.let { Triple(account.id, it, entry.address) }
        } ?: return null
        val (accountId, index, candidate) = match
        val quantumEntry = if (ColdStorageManager.isQuantumIndex(index)) coldStorageManager.quantumAddressRecord(candidate)?.second else null
        val balance = addressDiscovery.fetchBalances(listOf(candidate))?.get(candidate) ?: 0L
        return AddressRow(
            index = index,
            address = candidate,
            balanceSompi = balance,
            hasHistory = true,
            label = coldStorageManager.getAddressLabels(accountId)[index],
            hidden = index in coldStorageManager.getHiddenIndices(accountId),
            isQuantum = quantumEntry != null,
            unitIndex = quantumEntry?.unitIndex,
            quantumChain = quantumEntry?.chain ?: 0,
        )
    }

    /** On-demand watch-only derivation of a single receive address — the cold twin of
     *  WalletViewModel.spendingAddressAt, used by the visibility pager's beyond-bound rows. */
    fun coldAddressAt(accountId: String, index: Int): String? {
        val rootKey = rootKeyFor(accountId) ?: return null
        return runCatching { KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index) }.getOrNull()
    }

    /**
     * Whether this address has ever been used - or NULL when the probe could not answer.
     *
     * Null is the whole point, and it used to be false. A failed lookup, an unreadable balance, a
     * missing root key: all three collapsed into "false", the caller cached it, and the row read
     * "Unused" for the rest of the session. That is the answer someone uses to decide an address
     * is safe to hide, so a lookup that did not happen must not produce it. Same contract as
     * [ColdStorageAddressDiscovery.hasHistory], which already says so.
     */
    suspend fun hasColdAddressBeenUsed(accountId: String, index: Int): Boolean? {
        val rootKey = rootKeyFor(accountId) ?: return null
        val discovered = addressDiscovery.checkAddress(rootKey, chain = 0, index = index) ?: return null
        if (discovered.balanceSompi > 0) return true
        // A balance that could not be read leaves "used" unknown too: the history says nothing
        // about money currently sitting there.
        if (!discovered.balanceConfirmed) return null
        return discovered.hasHistory
    }

    /**
     * Reveals a specific index from the Address Visibility pager, extending the derived chain
     * when the index is beyond the current bound — intermediate newly-covered indices are marked
     * hidden so checking ONE far-out row doesn't flood the main list with everything below it.
     * The cold twin of WalletViewModel.revealSpendingAddress.
     */
    fun revealColdAddress(accountId: String, index: Int) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        // The kpub chain's highest index; quantum entries (1,000,000+) aren't on it (Q5).
        val currentMax = maxOf(account.maxDerivedIndex, _addresses.value.filterNot { it.isQuantum }.maxOfOrNull { it.index } ?: -1)
        if (index > currentMax) {
            coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, index)
            for (i in (currentMax + 1) until index) coldStorageManager.setAddressHidden(accountId, i, true)
            _accounts.value = coldStorageManager.getAccounts()
        }
        coldStorageManager.setAddressHidden(accountId, index, false)
        if (_addresses.value.any { it.index == index }) {
            _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = false) else it }
            return
        }
        // Stamp a placeholder row into the loaded list so the detail screen (which shares this
        // ViewModel) shows it the moment the sheet closes, then backfill its real balance/history.
        val address = coldAddressAt(accountId, index) ?: return
        val label = coldStorageManager.getAddressLabels(accountId)[index]
        _addresses.value = (_addresses.value + AddressRow(index, address, 0L, false, label, false))
            .sortedByDescending { it.index }
        viewModelScope.launch {
            val rootKey = rootKeyFor(accountId) ?: return@launch
            addressDiscovery.checkAddress(rootKey, chain = 0, index = index)?.let { d ->
                _addresses.value = _addresses.value.map {
                    if (it.index == index) it.copy(balanceSompi = d.balanceSompi, hasHistory = d.hasHistory, liveChecked = true) else it
                }
            }
        }
    }

    /**
     * Visibility-checklist toggle that also works for rows the loaded list has never seen
     * (a hidden intermediate whose one-off check failed, say) — [setAddressHidden] requires a
     * loaded row.
     *
     * THE TOGGLE RULE (same as the spending checklist's): the screen batch-loads live balances
     * when it opens, so a hide against a row that load confirmed ([AddressRow.liveChecked])
     * commits instantly and optimistically — no per-toggle network round trip; the funded guard
     * is enforced from that same fresh row. Only when no live-confirmed row exists (list painted
     * from the snapshot, live load failed, or the row was never loaded) does hiding fall back to
     * the fail-closed live check: one balance fetch, and no network answer means no hide.
     * Unhiding is always instant. [onResult] reports whether the flag actually committed.
     */
    fun setColdVisibilityHidden(accountId: String, index: Int, hidden: Boolean, onResult: (Boolean) -> Unit = {}) {
        val row = _addresses.value.find { it.index == index }
        if (!hidden) {
            coldStorageManager.setAddressHidden(accountId, index, false)
            if (row != null) {
                _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = false) else it }
            }
            onResult(true)
            return
        }
        if (row != null && row.balanceSompi > 0) {
            onResult(false)
            return
        }
        if (row != null && row.liveChecked) {
            coldStorageManager.setAddressHidden(accountId, index, true)
            _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = true) else it }
            onResult(true)
            return
        }
        if (ColdStorageManager.isQuantumIndex(index)) {
            // A scanned quantum address: its balance read live, fail-closed as the rest.
            val address = row?.address ?: coldStorageManager.currentQuantumAddresses(accountId)
                .getOrNull(index - ColdStorageManager.QUANTUM_INDEX_BASE)?.address
            if (address == null) {
                onResult(false)
                return
            }
            viewModelScope.launch {
                val balance = addressDiscovery.fetchBalances(listOf(address))?.get(address)
                if (balance == null || balance > 0) {
                    if (balance != null) {
                        _addresses.value = _addresses.value.map { if (it.index == index) it.copy(balanceSompi = balance, liveChecked = true) else it }
                    }
                    onResult(false)
                    return@launch
                }
                coldStorageManager.setAddressHidden(accountId, index, true)
                _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = true, liveChecked = true) else it }
                onResult(true)
            }
            return
        }
        viewModelScope.launch {
            val rootKey = rootKeyFor(accountId)
            if (rootKey == null) {
                onResult(false)
                return@launch
            }
            val discovered = addressDiscovery.checkAddress(rootKey, chain = 0, index = index)
            if (discovered == null) {
                onResult(false)
                return@launch
            }
            if (discovered.balanceSompi > 0) {
                if (row != null) {
                    _addresses.value = _addresses.value.map {
                        if (it.index == index) it.copy(balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true) else it
                    }
                }
                onResult(false)
                return@launch
            }
            coldStorageManager.setAddressHidden(accountId, index, true)
            _addresses.value = _addresses.value.map {
                if (it.index == index) it.copy(hidden = true, balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true) else it
            }
            onResult(true)
        }
    }

    /**
     * "Generate More Addresses" — a SEQUENCE: every press must land another unused row on the
     * visible list, forever. A press first un-hides the LOWEST HIDDEN index this session
     * live-confirmed as truly unused (zero balance, no on-chain history); the hidden requirement
     * is what makes press N+1 different from press N — a row that is already visible is already
     * "generated", and re-picking it (the old behavior) made the second press a silent no-op.
     * When no hidden unused row remains, it derives one past the all-time max, which is always a
     * distinct new index. Reports the ready index via [onResult]; null means the kpub could not
     * derive an address at all (nothing was landed or persisted).
     */
    fun generateMoreAddresses(accountId: String, onResult: (Int?) -> Unit = {}) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                // Recycle only HIDDEN rows this session live-confirmed as empty and unused — a
                // snapshot-painted row could be funded since; deriving past the end (below) is
                // always safe, so unconfirmed rows are skipped rather than trusted.
                // Only the kpub's chain: a quantum entry is a scanned address, not the next one to
                // hand out, and Generate More must never un-hide one (audit 2026-11-25 Q5).
                val recycled = _addresses.value.filterNot { it.isQuantum }.sortedBy { it.index }
                    .firstOrNull { it.hidden && it.liveChecked && it.balanceSompi == 0L && !it.hasHistory }
                if (recycled != null) {
                    coldStorageManager.setAddressHidden(accountId, recycled.index, false)
                    _addresses.value = _addresses.value.map {
                        if (it.index == recycled.index) it.copy(hidden = false) else it
                    }
                    onResult(recycled.index)
                    return@launch
                }
                val nextIndex = maxOf(account.maxDerivedIndex, _addresses.value.filterNot { it.isQuantum }.maxOfOrNull { it.index } ?: -1) + 1
                // Derive BEFORE persisting anything: if the kpub can't produce this address the
                // press must refuse cleanly instead of toasting an index that never appears.
                val address = coldAddressAt(accountId, nextIndex)
                if (address == null) {
                    onResult(null)
                    return@launch
                }
                coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, nextIndex)
                coldStorageManager.setAddressHidden(accountId, nextIndex, false)
                _accounts.value = coldStorageManager.getAccounts()
                // iOS parity (ColdStorageView.generateMore awaits its reload before toasting):
                // the new row lands on screen NOW — its address derives locally — so the toast
                // never names a row the list doesn't show. The live check afterwards only
                // backfills balance/used flags; its failure no longer makes the row vanish.
                val labels = coldStorageManager.getAddressLabels(accountId)
                _addresses.value = (
                    _addresses.value.filterNot { it.index == nextIndex } +
                        AddressRow(nextIndex, address, 0L, false, labels[nextIndex], hidden = false)
                    ).sortedByDescending { it.index }
                onResult(nextIndex)
                val rootKey = rootKeyFor(accountId)
                val discovered = rootKey?.let { addressDiscovery.checkAddress(it, chain = 0, index = nextIndex) }
                if (discovered != null) {
                    _addresses.value = _addresses.value.map {
                        if (it.index == nextIndex) {
                            it.copy(balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true)
                        } else it
                    }
                }
            } finally {
                _isDiscovering.value = false
                _isUserDiscovering.value = false
                _discoveryProgress.value = null
            }
        }
    }

    fun setAddressLabel(accountId: String, index: Int, label: String) {
        coldStorageManager.setAddressLabel(accountId, index, label)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(label = label.trim().ifBlank { null }) else it
        }
    }

    /** Rename from a row: a quantum row files under its index now (Q7), else its own. */
    fun setAddressLabel(accountId: String, row: AddressRow, label: String) {
        val index = currentIndex(accountId, row) ?: return
        coldStorageManager.setAddressLabel(accountId, index, label)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(label = label.trim().ifBlank { null }) else it
        }
    }

    fun getUtxoLabels(address: String): Map<String, String> = coldStorageManager.getUtxoLabels(address)

    /** Coin Control's labels for [address] (iOS 148a5331 ColdSendFlowView): a spending address's
     *  (Manage Addresses' Rename UTXO) or a KasSigner address's, the spending one winning where
     *  both name the same coin. */
    fun getCoinControlLabels(address: String): Map<String, String> {
        val spending = runCatching { walletManager.getSpendingUtxoLabels(address) }.getOrDefault(emptyMap())
        return coldStorageManager.getUtxoLabels(address) + spending
    }

    fun setUtxoLabel(address: String, outpointKey: String, label: String) {
        coldStorageManager.setUtxoLabel(address, outpointKey, label)
    }

    /**
     * Hiding is purely a display preference — the address and its label are untouched, and it
     * always shows back up under "Hidden Addresses" to be unhidden. Unhiding is always allowed,
     * but an address can't be hidden in the first place while it still holds a balance — that's
     * a case you'd want to keep an eye on, not tuck away.
     */
    fun setAddressHidden(accountId: String, index: Int, hidden: Boolean) {
        // Same live-balance fail-closed rule as the checklist path; one implementation.
        setColdVisibilityHidden(accountId, index, hidden)
    }

    // -------------------------------------------------------------------------
    // Address transaction history
    // -------------------------------------------------------------------------

    private val _txHistory = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>>(emptyList())
    val txHistory: StateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>> = _txHistory.asStateFlow()

    private val _isLoadingTxHistory = MutableStateFlow(false)
    val isLoadingTxHistory: StateFlow<Boolean> = _isLoadingTxHistory.asStateFlow()

    /** iOS ColdStorageView `historyIncomplete`: the last fetch did not get every page (or none),
     *  so an empty list is "Could not load transactions." rather than "No transactions yet.". */
    private val _txHistoryIncomplete = MutableStateFlow(false)
    val txHistoryIncomplete: StateFlow<Boolean> = _txHistoryIncomplete.asStateFlow()

    // In-memory, per-address caches so re-visiting the same address's tx-history screen shows
    // what was already fetched instantly instead of a blank blocking spinner every single time -
    // the real gap this screen had vs. Kaspium's persistent-cache approach (a full local DB is
    // overkill for a watch-only REST screen, but "don't re-blank on every visit" isn't). Each
    // `load*` call serves the cached value immediately (if any) while still kicking off a fresh
    // fetch in the background to keep it current - stale-while-revalidate, not stale-forever.
    private val txHistoryCache = mutableMapOf<String, List<ColdStorageAddressDiscovery.AddressTransaction>>()
    private val utxoCache = mutableMapOf<String, List<ColdStorageAddressDiscovery.AddressUtxo>>()
    /** Each address's account addresses, for its history rows; loaded once per address while
     *  the accounts' address sets stay as they were ([ownAddressesStamp]). */
    private val ownAddressesCache = mutableMapOf<String, Set<String>>()
    /** The accounts' address sets when [ownAddressesCache] was filled: a quantum address added or
     *  dropped, or more addresses derived, empties it, so change to a new address is never read
     *  as Sent (audit 2026-11-25 T5). */
    private var ownAddressesStamp: Int? = null

    private fun ownAddressesStampNow(): Int =
        coldStorageManager.getAccounts().map { account ->
            Triple(account.id, account.maxDerivedIndex, account.quantumList.map { it.address })
        }.hashCode()

    fun loadTxHistory(address: String) {
        val cached = txHistoryCache[address]
        if (cached != null) {
            _txHistory.value = cached
        }
        viewModelScope.launch {
            if (cached == null) _isLoadingTxHistory.value = true
            try {
                // The account's own addresses: change to another of them isn't Sent (L3).
                val stamp = ownAddressesStampNow()
                if (stamp != ownAddressesStamp) {
                    ownAddressesCache.clear()
                    ownAddressesStamp = stamp
                }
                val own = ownAddressesCache[address] ?: accountAddresses(address).also { ownAddressesCache[address] = it }
                val result = addressDiscovery.getTransactionHistoryResult(address, ownAddresses = own)
                _txHistoryIncomplete.value = !result.complete
                // A fetch that got nothing at all leaves what was already shown.
                if (result.complete || result.transactions.isNotEmpty() || cached == null) {
                    txHistoryCache[address] = result.transactions
                    _txHistory.value = result.transactions
                }
            } finally {
                _isLoadingTxHistory.value = false
            }
        }
    }

    private val _utxos = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>>(emptyList())
    val utxos: StateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>> = _utxos.asStateFlow()

    private val _isLoadingUtxos = MutableStateFlow(false)
    val isLoadingUtxos: StateFlow<Boolean> = _isLoadingUtxos.asStateFlow()
    /** iOS's utxoLoadError: why the last fetch could not ask the node, for the failure row. */
    private val _utxosLoadError = MutableStateFlow<String?>(null)
    val utxosLoadError: StateFlow<String?> = _utxosLoadError.asStateFlow()

    fun loadUtxos(address: String) {
        val cached = utxoCache[address]
        if (cached != null) {
            _utxos.value = cached
        }
        viewModelScope.launch {
            if (cached == null) _isLoadingUtxos.value = true
            // Another address's failure must not stand in for this one's (while this loads, the
            // spinner shows anyway).
            _utxosLoadError.value = null
            try {
                val fresh = addressDiscovery.getUtxosOrThrow(address)
                utxoCache[address] = fresh
                _utxos.value = fresh
                _utxosLoadError.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The list keeps what it had; an outage is not an empty address.
                _utxosLoadError.value = com.kachat.app.util.UserFacingError.message(e, "Couldn't load the coins.")
            } finally {
                _isLoadingUtxos.value = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send flow — build an unsigned tx from one address, show it as an animated KSPT QR, scan
    // the signed response back, and broadcast it. [ColdStorageSendEngine] does the actual tx
    // building/KSPT encoding/broadcast; this just sequences the UI-facing steps.
    // -------------------------------------------------------------------------

    enum class ColdSendStep { IDLE, BUILDING, SHOWING_QR, BROADCASTING, SUCCESS, FAILED }

    data class ColdSendUiState(
        val step: ColdSendStep = ColdSendStep.IDLE,
        val qrFrames: List<ByteArray> = emptyList(),
        val feeSompi: Long = 0L,
        val txId: String? = null,
        val errorMessage: String? = null,
        /** Counts broadcasts, so a Try Again that fails the same way is still a new state: a
         *  failure before anything suspends goes BROADCASTING -> FAILED at once, and an equal
         *  FAILED would never reach the screen, which then waited forever. */
        val attempt: Int = 0,
        /** The broadcast's answer was lost (it may have gone): "Not confirmed" with Done only, as
         *  every send in the app ends (`SendOutcomeUnknownException`). */
        val outcomeUnknown: Boolean = false,
        /** What the signing step says about a send from a quantum address (iOS 961fc05a
         *  `quantumSendNote`); null for any other send. */
        val quantum: QuantumSendInfo? = null,
    )

    /** A quantum source's built request, as the signing step shows it. */
    data class QuantumSendInfo(
        /** Where the change (or a quantum compound's coins) goes; null when there is none. */
        val ownOutput: String?,
        /** Its name: the person's label, else "Quantum Address #n". */
        val ownOutputLabel: String?,
        /** KasSigner will mark that output as CHANGE (change chain #0-4), else it shows it as a
         *  payment (audit 2026-11-25 Q2). */
        val unitMarksAsChange: Boolean,
        /** Coins past one transaction's input cap, left behind the revealed key (Q6). */
        val coinsLeftBehind: Int,
        /** The request names the address's number: Sign Without the Address Number is offered (Q4). */
        val hasUnitIndex: Boolean,
    )

    /** The quantum address this send reserved for its change (or a quantum compound's
     *  destination), handed back if the send never goes out (iOS 961fc05a `reservedChange`). */
    private var reservedChange: String? = null
    /** Whether [reservedChange] is a quantum compound's destination (it stays across rebuilds). */
    private var reservedIsCompoundDestination = false

    /** Compound on a quantum address: its coins move to a fresh quantum address of the account,
     *  reserved now for this flow; null (Something Went Wrong) when there is none (Q6). */
    suspend fun reserveQuantumCompoundDestination(fromAddress: String): String? {
        sendEngine.releaseQuantumChange(reservedChange)
        val fresh = sendEngine.freshQuantumChangeAddress(fromAddress)
        reservedChange = fresh
        reservedIsCompoundDestination = fresh != null
        return fresh
    }

    /** The localized message for a quantum compound with nowhere to go. */
    fun quantumCompoundNeedsFreshAddressMessage(): String =
        com.kachat.app.util.tr(com.kachat.app.R.string.coldq_error_compound_needs_fresh)

    private fun releaseReservedChange() {
        sendEngine.releaseQuantumChange(reservedChange)
        reservedChange = null
        reservedIsCompoundDestination = false
    }

    private fun quantumInfo(unsigned: ColdStorageSendEngine.UnsignedColdTx, compound: Boolean): QuantumSendInfo? {
        if (!unsigned.isQuantumSource) return null
        val own = if (compound) reservedChange else unsigned.changeAddress
        val record = own?.let { coldStorageManager.quantumAddressRecord(it)?.second }
        return QuantumSendInfo(
            ownOutput = own,
            ownOutputLabel = own?.let { coldStorageManager.quantumDisplayLabel(it) },
            unitMarksAsChange = record?.unitMarksAsChange == true,
            coinsLeftBehind = unsigned.coinsLeftBehind,
            hasUnitIndex = unsigned.quantumUnitIndex != null,
        )
    }

    /**
     * Sign Without the Address Number: the same transaction again with no KCC-3 hint, for a unit
     * that refused the number (a wrong one, or the wrong chain); the unit then searches its
     * quantum addresses itself (iOS 961fc05a, audit 2026-11-25 Q4). The new QR frames, or null.
     */
    fun signWithoutAddressNumber(): List<ByteArray>? {
        val unsigned = pendingUnsignedTx ?: return null
        if (!unsigned.isQuantumSource) return null
        val withoutHint = unsigned.copy(quantumUnitIndex = null)
        return try {
            val frames = QrFrameChunker.chunk(sendEngine.toKspt(withoutHint))
            pendingUnsignedTx = withoutHint
            _sendState.value = _sendState.value.copy(
                qrFrames = frames,
                quantum = _sendState.value.quantum?.copy(hasUnitIndex = false)
            )
            frames
        } catch (e: Exception) {
            null
        }
    }

    private val _sendState = MutableStateFlow(ColdSendUiState())
    val sendState: StateFlow<ColdSendUiState> = _sendState.asStateFlow()

    // Held between "show the unsigned QR" and "scan the signed one back" — broadcastSigned needs
    // the original tx to verify the signed response's outputs/inputs weren't tampered with.
    private var pendingUnsignedTx: ColdStorageSendEngine.UnsignedColdTx? = null

    /**
     * Which send screen the shared [sendState] belongs to: [resetColdSendState] (the screen
     * closing, or its scan given up) moves it on. A build or broadcast still running for an
     * earlier one finishes in the background, as an iOS cold send does once its sheet is gone,
     * but no longer writes here - its outcome would otherwise open the next send screen on
     * someone else's QR or "Sent".
     */
    private var sendGeneration = 0

    /** Sets [sendState] for [generation]'s screen, if that is still the one up. */
    private fun publishSendState(generation: Int, state: (ColdSendUiState) -> ColdSendUiState) {
        if (generation == sendGeneration) _sendState.value = state(_sendState.value)
    }

    fun startColdSend(
        fromAddress: String,
        toAddress: String,
        amountSompi: Long,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        /** Fast / Priority / custom fee as a flat extra over the base fee, paid exactly. */
        extraFeeSompi: Long = 0L,
        /** [manualUtxos] are the review's automatic preview, not a coin-control pick (iOS
         *  `previewed`, see [ColdStorageSendEngine.buildUnsignedTransaction]). */
        previewed: Boolean = false,
        /** Compound: a quantum source's coins go to the destination reserved for this flow. */
        isCompound: Boolean = false,
    ) {
        val step = _sendState.value.step
        if (step != ColdSendStep.IDLE && step != ColdSendStep.SUCCESS && step != ColdSendStep.FAILED) return

        _sendState.value = ColdSendUiState(step = ColdSendStep.BUILDING)
        val generation = sendGeneration
        val quantumRecord = coldStorageManager.quantumAddressRecord(fromAddress)?.second
        viewModelScope.launch {
            // A quantum source's change goes to a fresh quantum address of the account: this
            // spend makes the source's key public, and the build refuses change back to it. A
            // rebuild hands the earlier pick back and chooses again. (A quantum compound's
            // destination was reserved when the flow opened and stays.)
            var changeAddress: String? = null
            if (quantumRecord != null && !isCompound) {
                releaseReservedChange()
                changeAddress = sendEngine.freshQuantumChangeAddress(fromAddress)
                reservedChange = changeAddress
            }
            val result = sendEngine.buildUnsignedTransaction(
                fromAddress, toAddress, amountSompi, feeRateOverride, manualUtxos, extraFeeSompi, previewed,
                changeAddress = changeAddress,
                quantumUnitIndex = quantumRecord?.unitIndex,
                quantumUnitChain = quantumRecord?.chain ?: ColdStorageManager.ColdQuantumAddress.CHAIN_RECEIVE,
            )
            result.fold(
                onSuccess = { unsigned ->
                    // No change after all (a Max send): the address isn't taken.
                    if (!isCompound && changeAddress != null && unsigned.changeAddress == null) releaseReservedChange()
                    if (generation != sendGeneration) return@fold
                    try {
                        val kspt = sendEngine.toKspt(unsigned)
                        pendingUnsignedTx = unsigned
                        publishSendState(generation) {
                            ColdSendUiState(
                                step = ColdSendStep.SHOWING_QR,
                                qrFrames = QrFrameChunker.chunk(kspt),
                                feeSompi = unsigned.feeSompi,
                                quantum = quantumInfo(unsigned, isCompound),
                            )
                        }
                    } catch (e: Exception) {
                        if (!isCompound) releaseReservedChange()
                        publishSendState(generation) { ColdSendUiState(step = ColdSendStep.FAILED, errorMessage = UserFacingError.message(e, "Failed to build transaction")) }
                    }
                },
                onFailure = { e ->
                    if (!isCompound) releaseReservedChange()
                    publishSendState(generation) { ColdSendUiState(step = ColdSendStep.FAILED, errorMessage = UserFacingError.message(e, "Failed to build transaction")) }
                }
            )
        }
    }

    /** [scannedBytes] is the fully reassembled signed-KSPT payload from [com.kachat.app.ui.screens.coldstorage.MultiFrameQrScannerSheet]. */
    fun onSignedKsptScanned(scannedBytes: ByteArray) {
        val unsigned = pendingUnsignedTx ?: return
        _sendState.value = _sendState.value.let { it.copy(step = ColdSendStep.BROADCASTING, errorMessage = null, attempt = it.attempt + 1) }
        val generation = sendGeneration
        // The quantum address this send pays its change (or a compound) to, read now: a later
        // screen may reset the reservation fields while the broadcast runs (audit 2026-11-25 T2).
        val ownQuantumOutput = if (unsigned.isQuantumSource) {
            if (reservedIsCompoundDestination) reservedChange else unsigned.changeAddress
        } else null
        viewModelScope.launch {
            val decoded = KsptCodec.decode(scannedBytes).getOrElse { e ->
                publishSendState(generation) {
                    it.copy(
                        step = ColdSendStep.FAILED,
                        errorMessage = UserFacingError.message(e, "Couldn't read the signed transaction")
                    )
                }
                return@launch
            }
            sendEngine.broadcastSigned(unsigned, decoded).fold(
                onSuccess = { txId ->
                    // The quantum address that took the change (or the compound) is used now, and
                    // stays taken (iOS 961fc05a `noteQuantumChangeSent`).
                    if (unsigned.isQuantumSource) noteOwnQuantumOutputSent(ownQuantumOutput, generation)
                    if (generation == sendGeneration) pendingUnsignedTx = null
                    publishSendState(generation) { ColdSendUiState(step = ColdSendStep.SUCCESS, txId = txId) }
                },
                onFailure = { e ->
                    val unknown = e is com.kachat.app.services.SendOutcomeUnknownException
                    // It may have landed: its change address counts as used and is never handed
                    // back to the pool (audit 2026-11-25 T2).
                    if (unknown && unsigned.isQuantumSource) noteOwnQuantumOutputSent(ownQuantumOutput, generation)
                    if (unknown && generation == sendGeneration) pendingUnsignedTx = null
                    publishSendState(generation) {
                        it.copy(step = ColdSendStep.FAILED, errorMessage = UserFacingError.message(e, "Broadcast failed"), outcomeUnknown = unknown)
                    }
                }
            )
        }
    }

    /** The quantum address that took the change (or the compound) is used now, and stays taken
     *  (iOS 961fc05a `noteQuantumChangeSent`). */
    private fun noteOwnQuantumOutputSent(address: String?, generation: Int) {
        sendEngine.noteQuantumChangeSent(address)
        if (generation == sendGeneration) {
            reservedChange = null
            reservedIsCompoundDestination = false
        }
    }

    fun resetColdSendState() {
        sendGeneration++
        pendingUnsignedTx = null
        _sendState.value = ColdSendUiState()
    }

    /** The send screen closed: a change address it reserved and never sent to goes back to the
     *  pool (iOS 961fc05a `.onDisappear`). */
    fun closeColdSend() {
        releaseReservedChange()
        resetColdSendState()
    }

    suspend fun estimateMaxAmount(
        fromAddress: String,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        toAddress: String? = null
    ): Long =
        sendEngine.estimateMaxAmount(fromAddress, feeRateOverride, manualUtxos, feeMultiplier, customExtraFeeSompi, toAddress)

    /** Throws when the coins could not be fetched, so coin control can say so. */
    suspend fun fetchUtxosForCoinControl(fromAddress: String): List<UtxoEntry> = sendEngine.fetchUtxosOrThrow(fromAddress)

    /** Coin control's coins, each with why the build can't spend it (null when it can), listed
     *  greyed out as on a hot-wallet send (iOS 5848fb0 `hotWalletReason(chatting: false)`). */
    suspend fun fetchCoinControlCoins(fromAddress: String): List<Pair<UtxoEntry, String?>> =
        sendEngine.fetchCoinControlCoinsOrThrow(fromAddress)

    suspend fun compoundInputs(fromAddress: String): ColdStorageSendEngine.CompoundInputs =
        sendEngine.compoundInputs(fromAddress)

    suspend fun previewAutomaticSelection(
        fromAddress: String,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        toAddress: String? = null
    ): com.kachat.app.util.ColdSendFeeModel.Preview? =
        sendEngine.previewAutomaticSelection(fromAddress, amountSompi, feeRateSompiPerGram, feeMultiplier, customExtraFeeSompi, toAddress)

    suspend fun fetchQuotedFeeRateSompiPerGram(): Long = sendEngine.fetchQuotedFeeRateSompiPerGram()

    /**
     * Refreshes immediately, then again after a short delay — a just-broadcast transaction's
     * UTXO changes aren't always reflected in the very next balance query, so one refresh right
     * after a send can still show the stale pre-send balance. The second pass catches it without
     * making the user pull-to-refresh themselves.
     */
    fun refreshAddressesSoonAfterSend(accountId: String) {
        refreshAddresses(accountId)
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            refreshAddresses(accountId)
        }
    }
}
