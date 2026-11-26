package com.kachat.app.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.R
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaExtendedPublicKey
import com.kachat.app.util.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists imported KasSigner "kpub" watch-only accounts — deliberately in its own
 * `EncryptedSharedPreferences` file, entirely separate from [WalletManager]'s. Cold Storage is a
 * completely different trust domain from KaChat's identity/spending wallet: no mnemonic or
 * private key material is ever stored here, only public keys, and a future "wipe wallet" action
 * on the main wallet must never be able to touch (or be confused with) this store, or vice versa.
 * [WalletManager] is injected purely to read which wallet is currently active for scoping
 * accounts per wallet (see [ColdAccount.walletAddress]) — never its mnemonic/private key.
 */
@Singleton
class ColdStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager
) {
    companion object {
        private const val SECURE_PREFS_NAME = "cold_storage_secure_prefs"
        private const val PREF_ACCOUNTS = "cold_accounts"
        private const val PREF_ADDRESS_LABELS = "cold_address_labels"
        private const val PREF_HIDDEN_ADDRESSES = "cold_hidden_addresses"
        private const val PREF_UTXO_LABELS = "cold_utxo_labels"
        private const val PREF_ADDRESS_SNAPSHOT = "cold_address_snapshot"

        /** Where quantum addresses start in an account's index space (labels, hidden flags),
         *  clear of any receive index a kpub could reach. The hot wallet uses the same base. */
        const val QUANTUM_INDEX_BASE = 1_000_000

        fun isQuantumIndex(index: Int): Boolean = index >= QUANTUM_INDEX_BASE

        /** A pay-to-script-hash address: its script is `OP_BLAKE2B <32> OP_EQUAL`. */
        fun isScriptHashAddress(address: String?): Boolean {
            val text = address?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val script = runCatching { KaspaAddress.getScriptPublicKey(text) }.getOrNull() ?: return false
            return script.length == 70 && script.startsWith("aa20") && script.endsWith("87")
        }

        /** The text a scanned or pasted quantum address reads as: trimmed, a payment URI's query
         *  dropped, lowercased. */
        fun normalizedQuantumText(text: String): String =
            text.trim().substringBefore('?').lowercase()
    }

    // Every field has a default so Kotlin emits a no-arg constructor: Gson then runs it and a
    // record saved before a field existed keeps that field's default (on, ""), instead of the
    // false / null Gson's constructor-less path leaves (a null walletAddress crashed the tab).
    data class ColdAccount(
        val id: String = "",
        val name: String = "",
        val kpub: String = "",
        // Which spending wallet this was imported under - "" for accounts saved before this
        // field existed (see getAccounts()'s claim-on-first-load migration).
        val walletAddress: String = "",
        // Highest external-chain (chain 0) index ever derived/shown for this account — bounds
        // the Manage-style address list the same way WalletManager.Account.maxSpendingAddressIndex
        // bounds the spending-chain list. "Generate More Addresses" bumps this directly, ahead of
        // whatever the gap-limit scan itself would have stopped at.
        val maxDerivedIndex: Int = 0,
        // Whether a receipt on this account's addresses raises a notification. Per account: one
        // cold account can be watched closely while another stays quiet, and an account switched
        // off is still ours for the self-send check, just silent (iOS ba352a2). Default on, so
        // accounts saved before this field keep the behaviour they had.
        val notifyOnReceive: Boolean = true,
        /** The KasSigner's quantum-resistant (KCC-3, `m/22'`) addresses watched in this account,
         *  in the order added (iOS 961fc05a `ColdStorageAccount.quantumAddresses`). A unit never
         *  exports a `22'` kpub - it would show every key the addresses hide - so each is scanned
         *  from the unit's address screen, one at a time. Records saved before decode as none. */
        val quantumAddresses: List<ColdQuantumAddress> = emptyList(),
    ) {
        /** [quantumAddresses], never null: Gson leaves a list field it never saw null. */
        val quantumList: List<ColdQuantumAddress> get() = quantumAddresses ?: emptyList()
    }

    /**
     * One KasSigner quantum-resistant (KCC-3) address: P2SH of `<P> OP_CHECKSIG`, P at
     * `m/22'/111111'/0'/<chain>/<unitIndex>` on the unit. Known only by its text: the key stays on
     * the unit until the address spends, and the unit supplies the script when it signs (iOS
     * 961fc05a `ColdQuantumAddress`). Every field defaults, so Gson runs the constructor and a
     * record saved before [chain] existed decodes as receive, which is what it was sent as.
     */
    data class ColdQuantumAddress(
        val address: String = "",
        /** The address's number on the unit's KCC-3 chain, when the person gave it: sent as the
         *  request's KCC-3 hint so the unit finds the key at once. Without it the unit searches. */
        val unitIndex: Long? = null,
        /** Which of the unit's two KCC-3 chains it is on: [CHAIN_RECEIVE] or [CHAIN_CHANGE]. It
         *  matters for change: KasSigner marks a script-hash output as CHANGE only when it is one
         *  of its KCC-3 change-chain keys #0-4 (firmware `Keyring::kcc3_change_index`); any other,
         *  a receive address included, shows on the unit as an ordinary payment. */
        val chain: Int = CHAIN_RECEIVE,
        val addedAt: Long = 0L,
    ) {
        /** The unit will show change sent here as CHANGE, not as a payment to someone. */
        val unitMarksAsChange: Boolean
            get() = chain == CHAIN_CHANGE && unitIndex != null && unitIndex in UNIT_MARKED_CHANGE_RANGE

        companion object {
            const val CHAIN_RECEIVE = 0
            const val CHAIN_CHANGE = 1

            /** The change-chain numbers KasSigner recognises as its own change when reviewing
             *  outputs (`CHANGE_DEPTH` keys of its bank). */
            val UNIT_MARKED_CHANGE_RANGE = 0L..4L

            /** The default name, by chain and number ("Quantum Address #3", "Quantum Change
             *  Address #0"). */
            fun defaultLabel(chain: Int, unitIndex: Long?): String = when {
                chain == CHAIN_CHANGE && unitIndex == null -> tr(R.string.coldq_label_change)
                chain == CHAIN_CHANGE -> tr(R.string.coldq_label_change_n, unitIndex)
                unitIndex == null -> tr(R.string.coldq_label_receive)
                else -> tr(R.string.coldq_label_receive_n, unitIndex)
            }
        }
    }

    /** Why a quantum address couldn't be added (iOS 961fc05a `ColdStorageError`); [message] is
     *  the localized text. */
    class QuantumAddressException(message: String) : IllegalArgumentException(message)

    /** A user-given label for one specific derived address — flat list keyed by (accountId, index) rather than nested in [ColdAccount], so labeling is independent of the gap-limit-scanned address list's own lifecycle. */
    private data class AddressLabel(val accountId: String, val index: Int, val label: String)

    /** One address a user chose to hide from the main list — same flat (accountId, index) keying as [AddressLabel]. Hiding never deletes anything; it's purely a display preference. */
    private data class HiddenAddress(val accountId: String, val index: Int)

    /** A user-given label for one specific UTXO — keyed by address (the UTXOs tab is per-address,
     *  not per-account) + `"txId:index"` outpoint key, mirroring [AddressLabel]'s flat-list shape. */
    private data class UtxoLabel(val address: String, val outpointKey: String, val label: String)

    private val gson = Gson()

    // A store the Keystore refuses is an empty in-memory one for this session; the file is left as it is (StartupSafety).
    private val sharedPrefs = com.kachat.app.util.StartupSafety.encryptedPrefsOrInMemory(
        context,
        SECURE_PREFS_NAME,
        "ColdStorageManager"
    )

    private fun getAllAccounts(): List<ColdAccount> {
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<ColdAccount>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAccounts(accounts: List<ColdAccount>) {
        val json = gson.toJson(accounts)
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
        // the push registration's Received KAS addresses follow (iOS 428a797b coldStorageAccountsChanged)
        AddressActivityNotifier.watchOnlyChanges.value += 1
    }

    /**
     * Accounts imported under whichever wallet is currently active — otherwise switching
     * accounts on this device would show one wallet's imported kpubs under another. Accounts
     * saved before this field existed (walletAddress="") are claimed for the first wallet that
     * loads them post-upgrade, then persisted immediately so no other wallet can also claim
     * them — mirrors PortfolioRepository's identical migration.
     */
    fun getAccounts(): List<ColdAccount> {
        val walletAddress = walletManager.getAddress()
        val all = getAllAccounts()
        val unclaimed = all.filter { it.walletAddress.isEmpty() }
        if (unclaimed.isNotEmpty()) {
            val claimed = all.map { if (it.walletAddress.isEmpty()) it.copy(walletAddress = walletAddress) else it }
            saveAccounts(claimed)
            return claimed.filter { it.walletAddress == walletAddress }
        }
        return all.filter { it.walletAddress == walletAddress }
    }

    /** Re-importing the same kpub under the same wallet updates the existing entry's name rather than creating a duplicate; the same kpub imported under a different wallet is a separate entry. */
    fun saveAccount(name: String, kpub: String): Result<ColdAccount> {
        if (!KaspaExtendedPublicKey.isValidKpub(kpub)) {
            return Result.failure(IllegalArgumentException("Not a valid kpub"))
        }
        val walletAddress = walletManager.getAddress()
        val all = getAllAccounts()
        val existing = all.find { it.kpub == kpub && it.walletAddress == walletAddress }
        val account = existing?.copy(name = name)
            ?: ColdAccount(id = UUID.randomUUID().toString(), name = name, kpub = kpub, walletAddress = walletAddress)
        val accounts = all.filterNot { it.kpub == kpub && it.walletAddress == walletAddress } + account
        saveAccounts(accounts)
        return Result.success(account)
    }

    fun renameAccount(id: String, newName: String) {
        saveAccounts(getAllAccounts().map { if (it.id == id) it.copy(name = newName) else it })
    }

    /** Turns receive notifications for one cold account on or off (iOS ba352a2). */
    fun setNotifyOnReceive(id: String, enabled: Boolean) {
        saveAccounts(getAllAccounts().map { if (it.id == id) it.copy(notifyOnReceive = enabled) else it })
    }

    fun deleteAccount(id: String) {
        val all = getAllAccounts()
        val removed = all.filter { it.id == id }
        saveAccounts(all.filter { it.id != id })
        // its labels, hidden addresses, snapshot and coin labels go with it (iOS 5848fb0 `removeAccount`)
        removePerAccountData(removed)
    }

    /** Delete Account: everything imported under [walletAddress], from this device (iOS
     *  `clearAllLocalData` / 5848fb0 `removeAllData(forWalletAddress:)`). */
    fun removeAllData(walletAddress: String) {
        val all = getAllAccounts()
        val gone = all.filter { it.walletAddress == walletAddress }
        if (gone.isEmpty()) return
        val ids = gone.map { it.id }.toSet()
        saveAccounts(all.filter { it.id !in ids })
        removePerAccountData(gone)
    }

    /**
     * What each removed account filed besides the list (iOS 5848fb0 `removePerAccountData`): its
     * address labels, hidden addresses and address-list snapshot, and the coin labels filed under
     * its receive addresses. Those go only when no account left on this device (any wallet's)
     * holds the same kpub, since they're filed by address. Call after [removed] left the list.
     */
    private fun removePerAccountData(removed: List<ColdAccount>) {
        if (removed.isEmpty()) return
        val ids = removed.map { it.id }.toSet()
        saveAddressLabels(getAllAddressLabels().filter { it.accountId !in ids })
        saveHiddenAddresses(getAllHiddenAddresses().filter { it.accountId !in ids })
        val editor = sharedPrefs.edit()
        for (id in ids) editor.remove("${PREF_ADDRESS_SNAPSHOT}_$id")
        editor.apply()

        val utxoLabels = getAllUtxoLabels()
        val labelled = utxoLabels.map { it.address }.toMutableSet()
        if (labelled.isEmpty()) return
        val kept = getAllAccounts()
        val keptKpubs = kept.map { it.kpub }.toSet()
        val dropped = HashSet<String>()
        // A quantum address's coin labels go with the last account watching it (audit
        // 2026-11-25 Q7, iOS 961fc05a).
        val keptQuantum = kept.flatMap { account -> account.quantumList.map { it.address } }.toSet()
        for (account in removed) {
            for (quantum in account.quantumList) {
                if (quantum.address !in keptQuantum && labelled.remove(quantum.address)) dropped.add(quantum.address)
            }
        }
        for (account in removed) {
            if (account.kpub in keptKpubs) continue
            val root = KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
                ?.let { runCatching { KaspaExtendedPublicKey.toDeterministicKey(it) }.getOrNull() } ?: continue
            for (network in com.kachat.app.util.KaspaNetwork.Type.entries) {
                for (index in 0..maxOf(account.maxDerivedIndex, 0)) {
                    if (labelled.isEmpty()) break
                    val address = runCatching { KaspaExtendedPublicKey.deriveChildAddress(root, 0, index, network.hrp) }.getOrNull() ?: break
                    if (labelled.remove(address)) dropped.add(address)
                }
            }
        }
        if (dropped.isNotEmpty()) saveUtxoLabels(utxoLabels.filter { it.address !in dropped })
    }

    // --- Address-list snapshot: opaque JSON of the last fully-loaded row list, persisted per
    // account so the detail screen paints instantly while the live refresh runs — the cold twin
    // of WalletManager's Manage Addresses snapshot (ColdStorageViewModel owns the row type and
    // its serialization; this is just the per-account keyed store).
    fun getAddressSnapshot(accountId: String): String? =
        sharedPrefs.getString("${PREF_ADDRESS_SNAPSHOT}_$accountId", null)

    fun setAddressSnapshot(accountId: String, json: String) {
        sharedPrefs.edit().putString("${PREF_ADDRESS_SNAPSHOT}_$accountId", json).apply()
    }

    fun ensureMaxDerivedIndexAtLeast(id: String, minIndex: Int) {
        saveAccounts(
            getAllAccounts().map {
                if (it.id == id && minIndex > it.maxDerivedIndex) it.copy(maxDerivedIndex = minIndex) else it
            }
        )
    }

    // --- Quantum-resistant (KCC-3) addresses (iOS 961fc05a)

    /**
     * Adds a KasSigner KCC-3 address scanned (or pasted) from the unit's address screen. Only a
     * pay-to-script-hash address of this network reads: the text can't say what is behind the
     * hash, so the unit is the one that checks it holds the key, when it signs. One address, one
     * account: watched twice, its coins would count in both totals and a send from one could
     * pick it as the other's change (audit 2026-11-25 Q7).
     */
    fun addQuantumAddress(text: String, unitIndex: Long?, chain: Int, accountId: String): Result<ColdQuantumAddress> {
        val address = normalizedQuantumText(text)
        if (!KaspaAddress.isValidOnActiveNetwork(address) || !isScriptHashAddress(address)) {
            return Result.failure(QuantumAddressException(tr(R.string.coldq_error_not_quantum)))
        }
        val all = getAllAccounts()
        val account = all.find { it.id == accountId }
            ?: return Result.failure(QuantumAddressException(tr(R.string.coldq_error_not_quantum)))
        if (account.quantumList.any { it.address == address }) {
            return Result.failure(QuantumAddressException(tr(R.string.coldq_error_already_added)))
        }
        getAccounts().firstOrNull { it.id != accountId && it.quantumList.any { q -> q.address == address } }?.let { other ->
            return Result.failure(QuantumAddressException(tr(R.string.coldq_error_in_other_account, other.name)))
        }
        val entry = ColdQuantumAddress(
            address = address,
            unitIndex = unitIndex,
            chain = if (chain == ColdQuantumAddress.CHAIN_CHANGE) ColdQuantumAddress.CHAIN_CHANGE else ColdQuantumAddress.CHAIN_RECEIVE,
            addedAt = System.currentTimeMillis(),
        )
        saveAccounts(all.map { if (it.id == accountId) it.copy(quantumAddresses = it.quantumList + entry) else it })
        return Result.success(entry)
    }

    /** Stops watching a quantum address (its coins stay where they are on chain). Labels and
     *  hidden flags are kept by index: the ones after it move up a place with their addresses. */
    fun removeQuantumAddress(address: String, accountId: String) {
        val lowered = address.trim().lowercase()
        val all = getAllAccounts()
        val account = all.find { it.id == accountId } ?: return
        val position = account.quantumList.indexOfFirst { it.address == lowered }
        if (position < 0) return
        saveAccounts(all.map {
            if (it.id == accountId) it.copy(quantumAddresses = it.quantumList.filterIndexed { i, _ -> i != position }) else it
        })
        val removedIndex = QUANTUM_INDEX_BASE + position
        fun shifted(index: Int): Int? {
            if (!isQuantumIndex(index) || index < removedIndex) return index
            return if (index == removedIndex) null else index - 1
        }
        saveAddressLabels(getAllAddressLabels().mapNotNull { label ->
            if (label.accountId != accountId) label else shifted(label.index)?.let { label.copy(index = it) }
        })
        saveHiddenAddresses(getAllHiddenAddresses().mapNotNull { hidden ->
            if (hidden.accountId != accountId) hidden else shifted(hidden.index)?.let { hidden.copy(index = it) }
        })
    }

    /** Sets or clears a quantum address's number and chain on the unit (Edit Address Number): a
     *  wrong one makes the unit refuse the request, which only the person can put right (Q4). */
    fun updateQuantumAddress(address: String, unitIndex: Long?, chain: Int, accountId: String) {
        val lowered = address.trim().lowercase()
        val all = getAllAccounts()
        if (all.none { it.id == accountId && it.quantumList.any { q -> q.address == lowered } }) return
        saveAccounts(all.map { account ->
            if (account.id != accountId) account else account.copy(quantumAddresses = account.quantumList.map {
                if (it.address == lowered) it.copy(unitIndex = unitIndex, chain = chain) else it
            })
        })
    }

    /** The account's quantum addresses as stored now. */
    fun currentQuantumAddresses(accountId: String): List<ColdQuantumAddress> =
        getAllAccounts().find { it.id == accountId }?.quantumList ?: emptyList()

    /**
     * Where a quantum address sits in its account's index space now, null once it's no longer
     * watched. Labels and hidden flags of quantum entries are filed by this index, which moves
     * when an earlier one is removed, so an edit made from a row loaded before that resolves its
     * index here, by address, at the moment it saves (Q7).
     */
    fun currentQuantumIndex(address: String, accountId: String): Int? {
        val position = currentQuantumAddresses(accountId).indexOfFirst { it.address == address.trim().lowercase() }
        return if (position < 0) null else QUANTUM_INDEX_BASE + position
    }

    /** The account (of the current wallet) and record holding quantum address [address], if any. */
    fun quantumAddressRecord(address: String): Pair<ColdAccount, ColdQuantumAddress>? {
        val lowered = address.trim().lowercase()
        for (account in getAccounts()) {
            account.quantumList.firstOrNull { it.address == lowered }?.let { return account to it }
        }
        return null
    }

    /** Whether [address] is one of any account's quantum addresses. */
    fun isQuantumAddress(address: String): Boolean = quantumAddressRecord(address) != null

    /** The name a quantum address shows: the person's label, else its default by chain and number. */
    fun quantumDisplayLabel(address: String): String? {
        val (account, entry) = quantumAddressRecord(address) ?: return null
        val index = currentQuantumIndex(address, account.id) ?: return null
        getAddressLabels(account.id)[index]?.takeIf { it.isNotBlank() }?.let { return it }
        return ColdQuantumAddress.defaultLabel(entry.chain, entry.unitIndex)
    }

    /**
     * Every address of the Cold Storage account holding [address], lowercased: its derived
     * receive addresses up to the highest shown, and its quantum ones. What a history row counts
     * as the account's own, so change sent to another of them (a quantum source's change goes to
     * a fresh quantum address) isn't shown as Sent (audit 2026-11-25 L3). Just [address] when no
     * account holds it. Derives keys: call off the main thread.
     */
    fun accountAddresses(containing: String): Set<String> {
        val lowered = containing.trim().lowercase()
        for (account in getAccounts()) {
            val quantum = account.quantumList.map { it.address }
            val root = KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
                ?.let { runCatching { KaspaExtendedPublicKey.toDeterministicKey(it) }.getOrNull() }
            val regular = if (root == null) emptyList() else (0..maxOf(account.maxDerivedIndex, 0)).mapNotNull { index ->
                runCatching { KaspaExtendedPublicKey.deriveChildAddress(root, chain = 0, index = index) }.getOrNull()?.lowercase()
            }
            val all = (quantum + regular).toSet()
            if (lowered in all) return all
        }
        return setOf(lowered)
    }

    private fun getAllAddressLabels(): List<AddressLabel> {
        val json = sharedPrefs.getString(PREF_ADDRESS_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<AddressLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAddressLabels(labels: List<AddressLabel>) {
        sharedPrefs.edit().putString(PREF_ADDRESS_LABELS, gson.toJson(labels)).apply()
    }

    /** index -> label, for every labeled address under [accountId]. Unlabeled addresses just aren't present in the map. */
    fun getAddressLabels(accountId: String): Map<Int, String> =
        getAllAddressLabels().filter { it.accountId == accountId }.associate { it.index to it.label }

    /** A blank [label] clears any existing label for this address rather than storing an empty string. */
    fun setAddressLabel(accountId: String, index: Int, label: String) {
        val remaining = getAllAddressLabels().filterNot { it.accountId == accountId && it.index == index }
        val trimmed = label.trim()
        saveAddressLabels(if (trimmed.isNotEmpty()) remaining + AddressLabel(accountId, index, trimmed) else remaining)
    }

    private fun getAllUtxoLabels(): List<UtxoLabel> {
        val json = sharedPrefs.getString(PREF_UTXO_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<UtxoLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveUtxoLabels(labels: List<UtxoLabel>) {
        sharedPrefs.edit().putString(PREF_UTXO_LABELS, gson.toJson(labels)).apply()
    }

    /** outpointKey ("txId:index") -> label, for every labeled UTXO at [address]. Unlabeled UTXOs just aren't present in the map. */
    fun getUtxoLabels(address: String): Map<String, String> =
        getAllUtxoLabels().filter { it.address == address }.associate { it.outpointKey to it.label }

    /** A blank [label] clears any existing label for this UTXO rather than storing an empty string. */
    fun setUtxoLabel(address: String, outpointKey: String, label: String) {
        val remaining = getAllUtxoLabels().filterNot { it.address == address && it.outpointKey == outpointKey }
        val trimmed = label.trim()
        saveUtxoLabels(if (trimmed.isNotEmpty()) remaining + UtxoLabel(address, outpointKey, trimmed) else remaining)
    }

    private fun getAllHiddenAddresses(): List<HiddenAddress> {
        val json = sharedPrefs.getString(PREF_HIDDEN_ADDRESSES, null) ?: return emptyList()
        val type = object : TypeToken<List<HiddenAddress>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveHiddenAddresses(hidden: List<HiddenAddress>) {
        sharedPrefs.edit().putString(PREF_HIDDEN_ADDRESSES, gson.toJson(hidden)).apply()
        AddressActivityNotifier.watchOnlyChanges.value += 1
    }

    /** Indices hidden under [accountId] — never deletes the address itself, just what the main list filters out. */
    fun getHiddenIndices(accountId: String): Set<Int> =
        getAllHiddenAddresses().filter { it.accountId == accountId }.map { it.index }.toSet()

    fun setAddressHidden(accountId: String, index: Int, hidden: Boolean) {
        val remaining = getAllHiddenAddresses().filterNot { it.accountId == accountId && it.index == index }
        saveHiddenAddresses(if (hidden) remaining + HiddenAddress(accountId, index) else remaining)
    }

    /**
     * Bulk form: ONE rewrite of the stored blob rather than one per index. Discovery can now
     * sweep hundreds of indices in at once, and [setAddressHidden] in a loop would serialize and
     * rewrite the whole set every time.
     */
    fun setAddressesHidden(accountId: String, indices: Collection<Int>, hidden: Boolean) {
        if (indices.isEmpty()) return
        val wanted = indices.toSet()
        val remaining = getAllHiddenAddresses().filterNot { it.accountId == accountId && it.index in wanted }
        saveHiddenAddresses(
            if (hidden) remaining + wanted.map { HiddenAddress(accountId, it) } else remaining
        )
    }
}
