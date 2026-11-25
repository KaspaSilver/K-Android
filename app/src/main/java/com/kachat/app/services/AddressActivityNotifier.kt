package com.kachat.app.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for external receipts on any of the wallet's own SPENDING or COLD STORAGE
 * addresses, and plain transfers to the CHATTING address (iOS 56b75c8). Android port of iOS's
 * `AddressActivityNotifier.swift`.
 *
 * iOS drives this from live utxosChanged subscription events plus a foreground catch-up diff.
 * Android has no UTXO push subscription (chat delivery is poll/FCM based), so this runs the same
 * per-wallet BASELINE + DIFF algorithm on a foreground poll loop ([POLL_INTERVAL_MS]) plus an
 * immediate pass on app-foreground ([onAppForeground]) - the catch-up half of the iOS design is
 * the whole engine here:
 *
 * - Per-wallet balance baselines persist across launches; the very first run for a wallet seeds
 *   them silently (no dredging up history as "new" receipts).
 * - A balance increase is attributed to recent transactions for that address; a tx whose inputs
 *   include ANY of our own addresses (chatting or spending) is a self-transfer and is
 *   suppressed. Unresolvable transactions notify anyway - missing real funds is worse than a
 *   rare duplicate. One notification per tx, outputs to our addresses summed.
 * - Handled txIds are persisted per wallet (cap 500) so live/catch-up passes never double-notify.
 * - [utxoActivityEvents] ALWAYS emits when watched balances change - a UI-refresh signal
 *   (payment composer's Available pill, Manage Addresses) deliberately separate
 *   from the notification decision and NOT gated by the Address Activity setting.
 * - The notification itself is gated by Settings > Notifications > Wallet > "Address Activity"
 *   (default ON) plus the global notifications toggle. The gates decide only what is announced:
 *   baselines, handled marks and the chatting floor follow every own address whatever they say,
 *   so turning a switch back on never announces what came while it was off (iOS 8cb4c88e, audit
 *   2026-11-24 notifications).
 *
 * The chatting address notifies too, for plain transfers only (no payload, iOS 56b75c8): a
 * KaChat chat payment or a handshake is the chat's own news and stays quiet. Its catch-up only
 * counts plain transfers newer than its last look ([chattingFloorKey]) - never a burst of old
 * payments - and it has no "Balance increased" fallback (its balance moves with every chat
 * payment too).
 */
@Singleton
class AddressActivityNotifier @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val networkService: NetworkService,
    private val settingsRepository: AppSettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val notificationCenter: GlobalNotificationCenterStore,
    private val coldStorageManager: ColdStorageManager,
    /** Whether the push service is this device's notice source ([activeSinceMs]). */
    private val pushState: PushState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("AddressActivityNotifier"))
    private val runMutex = Mutex()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private var pollJob: Job? = null

    /** Always-emitted UI refresh event: the set of watched own addresses whose balance changed
     *  in the last diff pass. NOT gated by the Address Activity setting - consumers (Available
     *  pill, Manage Addresses, Cold Storage screens) refresh regardless of notification prefs. */
    private val _utxoActivityEvents = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)
    val utxoActivityEvents: SharedFlow<Set<String>> = _utxoActivityEvents

    // --- Lifecycle --------------------------------------------------------------------

    /**
     * When the app last came to the foreground (or this notifier started, on a cold launch). A
     * receive in a block before it came while the app was away: for an address the push service
     * watches, its `address_activity` push was the notice, and the catch-up only lists it in the
     * bell ("In the background and when closed, pushes alone"). The push ledger
     * ([NotificationHelper.pushArrived]) keeps only 30 minutes, so after a longer absence the
     * catch-up announced every receive again (iOS 8cb4c88e `activeSince`, audit 2026-11-24
     * notifications).
     */
    @Volatile private var activeSinceMs = System.currentTimeMillis()

    /** Called from the process lifecycle observer on foreground: immediate catch-up pass, then a
     *  foreground-only poll loop approximating iOS's live subscription events. */
    fun onAppForeground() {
        activeSinceMs = System.currentTimeMillis()
        scope.launch { runDiffPass() }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                try {
                    runDiffPass()
                } catch (e: Exception) {
                    Log.w(TAG, "Address activity poll failed", e)
                }
            }
        }
    }

    /** Called on background - the loop freezes with the process, matching the app's
     *  foreground-only sync design. Next foreground runs the catch-up diff. */
    fun onAppBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One immediate pass - used by the payment composer's post-send refresh path. */
    fun requestRefresh() {
        scope.launch {
            try {
                runDiffPass()
            } catch (e: Exception) {
                Log.w(TAG, "Address activity refresh failed", e)
            }
        }
    }

    // --- Watched sets -----------------------------------------------------------------

    private fun walletAddressOrNull(): String? = try { walletManager.getAddress() } catch (e: Exception) { null }

    /** Cold-storage receive address -> owning account's name (notification wording, iOS ba352a2
     *  `coldLabelByAddress`), for the current wallet's KasSigner accounts, 0..maxDerivedIndex. */
    @Volatile private var coldLabelByAddress: Map<String, String> = emptyMap()
    /** Cold addresses whose account has receive notifications switched off: still ours for the
     *  self-send check, never notified (iOS ba352a2 `coldMutedAddresses`). */
    @Volatile private var coldMutedAddresses: Set<String> = emptySet()
    /** The cold addresses whose receives the push service watches (`watch_only_addresses`, iOS
     *  428a797b `coldNotifySet`): accounts with Receive Notifications on, hidden slots left out. */
    @Volatile private var coldNotifyAddresses: Set<String> = emptySet()
    @Volatile private var coldFingerprint: String = ""

    /** Re-derives the cold address sets only when the wallet, network or an account's bound or
     *  switch changed - EC math per address, so never on every pass. */
    @Synchronized
    private fun refreshColdAddresses(walletAddress: String) {
        val accounts = try { coldStorageManager.getAccounts() } catch (e: Exception) { emptyList() }
        val hiddenByAccount = accounts.associate { account ->
            account.id to (runCatching { coldStorageManager.getHiddenIndices(account.id) }.getOrNull() ?: emptySet())
        }
        val fingerprint = walletAddress + "|" + com.kachat.app.util.KaspaNetwork.hrp + "|" +
            accounts.joinToString(",") {
                "${it.id}:${it.maxDerivedIndex}:${it.notifyOnReceive}:${hiddenByAccount[it.id].orEmpty().sorted().joinToString("+")}"
            }
        if (fingerprint == coldFingerprint) return
        val labels = HashMap<String, String>()
        val muted = HashSet<String>()
        val notify = HashSet<String>()
        for (account in accounts) {
            val root = com.kachat.app.util.KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
                ?.let { runCatching { com.kachat.app.util.KaspaExtendedPublicKey.toDeterministicKey(it) }.getOrNull() }
                ?: continue
            val hidden = hiddenByAccount[account.id].orEmpty()
            for (index in 0..maxOf(0, account.maxDerivedIndex)) {
                val address = runCatching {
                    com.kachat.app.util.KaspaExtendedPublicKey.deriveChildAddress(root, chain = 0, index = index)
                }.getOrNull() ?: continue
                labels[address] = account.name
                if (!account.notifyOnReceive) muted.add(address)
                else if (index !in hidden) notify.add(address)
            }
        }
        coldLabelByAddress = labels
        coldMutedAddresses = muted
        coldNotifyAddresses = notify
        coldFingerprint = fingerprint
    }

    /**
     * The addresses the push service watches for Received KAS while the app is closed
     * (`watch_only_addresses`, iOS 428a797b `pushWatchOnlyAddresses`): the spending ones while
     * their switch is on and the Cold Storage ones whose account notifies; none with Address
     * Activity off or signed out. Not the chatting address: its receives come as chat pushes.
     * Derives Cold Storage addresses, so off the main thread.
     */
    suspend fun pushWatchOnlyAddresses(): Set<String> {
        if (!settingsRepository.addressActivityNotificationsEnabled.first()) return emptySet()
        if (walletManager.isLoggedOut) return emptySet()
        val walletAddress = walletAddressOrNull() ?: return emptySet()
        refreshColdAddresses(walletAddress)
        val spending = walletManager.allSpendingAddresses().toSet()
        val set = HashSet<String>()
        if (settingsRepository.spendingReceiveNotifications.first()) set += spending
        set += coldNotifyAddresses
        set -= walletAddress
        return set
    }

    /** What [pushWatchOnlyAddresses] was last made of, so a change re-registers once. */
    @Volatile private var lastWatchOnlySignature: Int? = null

    /** The push registration follows the Received KAS addresses (iOS 428a797b
     *  `pushAddressesMayHaveChanged`): a new spending address, a Cold Storage account added,
     *  switched or with more addresses. The switches themselves reach it through settings. */
    private fun noteWatchOnlyMaybeChanged(walletAddress: String, spending: Set<String>) {
        val signature = listOf(walletAddress, spending.sorted().joinToString("+"), coldNotifyAddresses.sorted().joinToString("+")).hashCode()
        if (signature == lastWatchOnlySignature) return
        val first = lastWatchOnlySignature == null
        lastWatchOnlySignature = signature
        if (!first) watchOnlyChanges.value = watchOnlyChanges.value + 1
    }

    /** A Cold Storage address that isn't also one of the wallet's own (iOS 428a797b `isColdOnly`). */
    private fun isColdOnly(address: String, walletAddress: String?, spending: Set<String>): Boolean =
        address in coldLabelByAddress && address !in spending && address != walletAddress

    /**
     * Whether a tapped Received notice for [address] opens its history (iOS 428a797b
     * `opensHistory(for:)`): a Cold Storage address has none here, so its tap opens Wallet.
     */
    suspend fun opensHistory(address: String): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (address.isEmpty()) return@withContext false
        val walletAddress = walletAddressOrNull()
        walletAddress?.let { refreshColdAddresses(it) }
        !isColdOnly(address, walletAddress, walletManager.allSpendingAddresses().toSet())
    }

    /** [walletAddress] is still the signed-in account's. */
    private fun isStillCurrent(walletAddress: String): Boolean =
        !walletManager.isLoggedOut && walletAddressOrNull() == walletAddress

    // --- Core diff pass ---------------------------------------------------------------

    private suspend fun runDiffPass() = runMutex.withLock {
        // Signed out: the baselines stay put, or what arrives meanwhile would never get its banner
        // once signed back in (iOS runCatchUpIfNeeded guards on the current wallet).
        if (walletManager.isLoggedOut) return@withLock
        val walletAddress = walletAddressOrNull() ?: return@withLock
        val api = networkService.kaspaRestApi.value ?: return@withLock

        val spending = walletManager.allSpendingAddresses().toSet()
        // The chatting address is watched like the others (iOS 56b75c8), for plain transfers only.
        // Cold Storage is back (KasSigner accounts): their receive addresses are watched too, so
        // a move from cold storage to a spending address is a self-send, and a receipt on a cold
        // address notifies per its account's switch (iOS ba352a2).
        refreshColdAddresses(walletAddress)
        noteWatchOnlyMaybeChanged(walletAddress, spending)
        val cold = coldLabelByAddress.keys - spending - walletAddress
        val watched = spending + walletAddress + cold

        // NOTIFIABLE = watched, the chatting address included (only its plain transfers count -
        // see attributeAndMaybeNotifyIncrease).
        // The spending chain has a switch of its own, and each cold account its own. Switched off
        // it is still ours - still watched, still counted for the self-send test - just quiet
        // (iOS ba352a2).
        val spendingMuted = if (settingsRepository.spendingReceiveNotifications.first()) emptySet() else spending
        val notifiable = watched - spendingMuted - coldMutedAddresses
        // "Ours" for the self-send input test includes the chatting address too.
        val ownAll = watched

        // Balances - one fetch per spending/chatting address; any failure keeps every baseline
        // untouched. Cold accounts can reach hundreds of addresses, so theirs go in batches.
        val currentBalances = mutableMapOf<String, Long>()
        for (address in spending + walletAddress) {
            val balance = try { api.getBalance(address).balance } catch (e: Exception) { return@withLock }
            currentBalances[address] = balance
        }
        for (chunk in cold.chunked(100)) {
            val rows = try { api.getBalances(BalancesRequest(chunk)) } catch (e: Exception) { return@withLock }
            val byAddress = rows.associate { it.address to it.balance }
            // an address the answer left out: not read, so its baseline stays as it was
            for (address in chunk) byAddress[address]?.let { currentBalances[address] = it }
        }
        // Signed out or switched while fetching: these balances aren't the current account's
        // (iOS 83806b3d).
        if (!isStillCurrent(walletAddress)) return@withLock

        val baselines = loadBaselines(walletAddress).toMutableMap()
        val isFirstRun = baselines.isEmpty()
        val featureOn = settingsRepository.addressActivityNotificationsEnabled.first() &&
            settingsRepository.notificationsEnabled.first()

        var changed = false
        val changedAddresses = mutableSetOf<String>()
        for ((address, balance) in currentBalances) {
            val previous = baselines[address]
            if (previous == null) {
                // First sighting of this address (feature install, newly revealed slot) - seed
                // silently.
                baselines[address] = balance
                if (address == walletAddress) {
                    // the chatting address's receives count from now on
                    prefs.edit().putLong(chattingFloorKey(walletAddress), System.currentTimeMillis()).apply()
                }
                changed = true
                continue
            }
            if (balance != previous) {
                changed = true
                // The UI refresh signal stays about the spending addresses, as before:
                // the chatting address's balance moves with every message sent.
                if (address != walletAddress) changedAddresses.add(address)
            }
            if (balance > previous && !isFirstRun && address in notifiable) {
                attributeAndMaybeNotifyIncrease(
                    address = address,
                    delta = balance - previous,
                    walletAddress = walletAddress,
                    ownAll = ownAll,
                    featureOn = featureOn,
                    api = api
                )
                // Switched account meanwhile: stop, and save nothing more for this one now; the
                // next catch-up for it starts from the saved baseline (iOS 83806b3d).
                if (!isStillCurrent(walletAddress)) return@withLock
            }
            baselines[address] = balance
        }

        if (changed) {
            saveBaselines(baselines, walletAddress)
            if (changedAddresses.isNotEmpty()) {
                _utxoActivityEvents.tryEmit(changedAddresses)
            }
        }
    }

    /**
     * Attributes a balance increase to its transaction(s) via recent history for the address:
     * per unhandled tx paying this address, resolve the inputs - any own address among them
     * means self-transfer, suppressed silently; otherwise notify once for the tx's summed
     * outputs to this address. If history can't attribute the delta at all, a neutral
     * "Balance increased" notification fires instead (never silently missing real funds).
     */
    private suspend fun attributeAndMaybeNotifyIncrease(
        address: String,
        delta: Long,
        walletAddress: String,
        ownAll: Set<String>,
        featureOn: Boolean,
        api: KaspaRestApi
    ) {
        ensureHandledLoaded(walletAddress)
        val isChatting = address == walletAddress
        val transactions = try {
            api.getTransactions(address, limit = 10)
        } catch (e: Exception) {
            emptyList()
        }
        // That read can take a while: if the account changed meanwhile, this receive isn't the
        // current account's news. Left unmarked, so the catch-up finds it on the way back (iOS
        // 4f8e14bb).
        if (walletAddressOrNull() != walletAddress) return
        // the chatting address: only plain transfers newer than what was already looked at
        val floorKey = chattingFloorKey(walletAddress)
        val floor = prefs.getLong(floorKey, 0L).coerceAtLeast(0L)
        if (isChatting) {
            val newest = transactions.mapNotNull { it.blockTime }.maxOrNull() ?: System.currentTimeMillis()
            prefs.edit().putLong(floorKey, maxOf(newest, floor)).apply()
            // the first look only sets where it starts
            if (floor == 0L) return
        }
        if (transactions.isEmpty()) {
            // Resolution failed entirely - still surface the funds, neutral wording (not for the
            // chatting address, whose balance moves with every chat payment too).
            if (featureOn && !isChatting) postBalanceIncreased(address, delta)
            return
        }
        // Receives the push service announced while the app was away (see [activeSinceMs]).
        val pushWatched = !isChatting && pushState.isActive &&
            runCatching { address in pushWatchOnlyAddresses() }.getOrDefault(false)
        val awayBeforeMs = activeSinceMs
        var attributedAny = false
        for (tx in transactions) {
            if (isChatting && (!isPlainTransfer(tx) || (tx.blockTime ?: 0L) <= floor)) continue
            val toAddress = tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
            if (toAddress <= 0) continue
            if (isHandled(tx.transactionId, walletAddress)) {
                attributedAny = true
                continue
            }
            val inputAddresses = tx.inputs.mapNotNull { it.previousOutpointAddress }.filter { it.isNotEmpty() }
            val isSelfSend = inputAddresses.isNotEmpty() && inputAddresses.any { it in ownAll }
            markHandled(tx.transactionId, walletAddress)
            attributedAny = true
            if (isSelfSend) {
                Log.i(TAG, "Suppressed self-send ${tx.transactionId.take(12)}")
                continue
            }
            if (featureOn) {
                val blockTime = tx.blockTime ?: 0L
                val pushWasTheNotice = pushWatched && blockTime > 0L && blockTime < awayBeforeMs
                postReceive(totalSompi = toAddress, address = address, dedupeKey = tx.transactionId, banner = !pushWasTheNotice)
            }
        }
        if (!attributedAny && featureOn && !isChatting) {
            // Balance grew but none of the fetched recent txs pays this address (deep history
            // page or indexer lag) - neutral fallback rather than staying silent.
            postBalanceIncreased(address, delta)
        }
    }

    // --- Notifications ----------------------------------------------------------------

    /** [banner] false: the bell only (the push already told them, [activeSinceMs]). */
    private suspend fun postReceive(totalSompi: Long, address: String, dedupeKey: String, banner: Boolean = true) {
        if (totalSompi <= 0) return
        // The chatting address: the payment sync may have bannered this payment already.
        // One notice per transaction (iOS 428a797b `InAppBanner.claim`): the payment sync, or this
        // transaction's Received push (`address_activity`, same tx_id) already showing, means none.
        val title = "Received ${com.kachat.app.repository.ChatRepository.formatKas(totalSompi)} ${KaspaUnit.symbol}"
        val pushShowing = notificationHelper.isReceivedPushShowing(dedupeKey, totalSompi)
        // A Cold Storage address has no history sheet of its own here: the tap opens Wallet (iOS
        // 428a797b).
        val opensAddress = !isColdOnly(address, walletAddressOrNull(), walletManager.allSpendingAddresses().toSet())
        // Nor when its Received push already came, even one tapped and gone from the shade (iOS
        // `InAppBanner.pushArrived`, 2026-11-22).
        if (banner && !notificationHelper.pushArrived(dedupeKey) && notificationHelper.claimForBanner(dedupeKey) && !pushShowing) {
            notificationHelper.showAddressActivity(
                title = title,
                text = describe(address),
                dedupeKey = dedupeKey,
                kind = kindOf(address),
                // One address per notification here (each receipt is posted per address), so the
                // tap opens that address's History (iOS 13046ad).
                address = address.takeIf { opensAddress }
            )
        }
        // Also list it in the Profile notifications bell; the row opens the same address's
        // History (iOS 95e2cba).
        notificationCenter.record("wallet-$dedupeKey", "wallet", title, describe(address), System.currentTimeMillis(), address.takeIf { opensAddress })
        Log.i(TAG, "Notified external receive $dedupeKey")
    }

    private suspend fun postBalanceIncreased(address: String, delta: Long) {
        if (delta <= 0) return
        val dedupeKey = "bal-${address.takeLast(12)}-${System.currentTimeMillis()}"
        // a Cold Storage address's tap opens Wallet (iOS 428a797b)
        val opensAddress = !isColdOnly(address, walletAddressOrNull(), walletManager.allSpendingAddresses().toSet())
        notificationHelper.showAddressActivity(
            title = "Balance increased by ${com.kachat.app.repository.ChatRepository.formatKas(delta)} ${KaspaUnit.symbol}",
            text = describe(address),
            dedupeKey = dedupeKey,
            kind = kindOf(address),
            // One address per notification here (each receipt is posted per address), so the
            // tap opens that address's History (iOS 13046ad).
            address = address.takeIf { opensAddress }
        )
        // the bell's row opens the same address's History (iOS 95e2cba)
        notificationCenter.record("wallet-$dedupeKey", "wallet", "Balance increased by ${com.kachat.app.repository.ChatRepository.formatKas(delta)} ${KaspaUnit.symbol}", describe(address), System.currentTimeMillis(), address.takeIf { opensAddress })
    }

    /** Which wallet screen the notification's tap should open - mirrors iOS's `kindKey(for:)`,
     *  which puts the same string in the notification's `userInfo["kind"]`. */
    private fun kindOf(address: String): String = when {
        address == walletAddressOrNull() -> NotificationHelper.KIND_CHATTING
        isColdOnly(address, walletAddressOrNull(), walletManager.allSpendingAddresses().toSet()) -> NotificationHelper.KIND_COLD
        else -> NotificationHelper.KIND_SPENDING
    }

    private fun describe(address: String): String {
        if (address == walletAddressOrNull()) return "Chatting address ${shortAddress(address)}"
        coldLabelByAddress[address]?.let { return "Cold storage ($it) ${shortAddress(address)}" }
        return "Spending address ${shortAddress(address)}"
    }

    // --- Persistence (per wallet) -----------------------------------------------------

    /** Everything kept for [walletAddress]: it's going off this device (iOS bfe9f022). */
    fun removeAllData(walletAddress: String) {
        prefs.edit()
            .remove(baselinesKey(walletAddress))
            .remove(chattingFloorKey(walletAddress))
            .remove(handledKey(walletAddress))
            .apply()
        // and the copy in memory (iOS 5b7c772c `removeAllData(forWallet:)`)
        scope.launch {
            runMutex.withLock {
                if (handledLoadedFor == walletAddress) {
                    handledLoadedFor = null
                    handledOrder = mutableListOf()
                    handledSet = mutableSetOf()
                }
            }
        }
    }

    private fun baselinesKey(walletAddress: String) = "baselines_$walletAddress"

    /** The newest block time (ms) the chatting address's catch-up has already looked at, per
     *  wallet: older receives were before it was watched, or already notified - never a burst of
     *  old payments (iOS 56b75c8 `chattingCatchUpFloorKey`). */
    private fun chattingFloorKey(walletAddress: String) = "addr_activity_chat_floor_${walletAddress.lowercase()}"

    /** A transaction with no payload: plain KAS, not a KaChat chat payment, message or handshake. */
    private fun isPlainTransfer(tx: TransactionResponse): Boolean = tx.payload.isNullOrEmpty()
    private fun handledKey(walletAddress: String) = "handled_txids_$walletAddress"

    private fun loadBaselines(walletAddress: String): Map<String, Long> {
        val json = prefs.getString(baselinesKey(walletAddress), null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveBaselines(baselines: Map<String, Long>, walletAddress: String) {
        prefs.edit().putString(baselinesKey(walletAddress), gson.toJson(baselines)).apply()
    }

    private var handledOrder: MutableList<String> = mutableListOf()
    private var handledSet: MutableSet<String> = mutableSetOf()
    private var handledLoadedFor: String? = null

    private fun ensureHandledLoaded(walletAddress: String) {
        if (handledLoadedFor == walletAddress) return
        val json = prefs.getString(handledKey(walletAddress), null)
        val list: List<String> = if (json == null) emptyList() else try {
            gson.fromJson(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
        handledOrder = list.toMutableList()
        handledSet = list.toMutableSet()
        handledLoadedFor = walletAddress
    }

    private fun isHandled(txId: String, walletAddress: String): Boolean {
        if (txId.lowercase() in ownTransactions) return true
        ensureHandledLoaded(walletAddress)
        return txId in handledSet
    }

    /** Transactions this phone sent itself ([markOwnTransaction]), lowercased: read by the diff
     *  pass from the moment they're marked, before the persisted mark below lands. */
    private val ownTransactions: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /**
     * A transaction this phone sends whose KAS change lands on one of your addresses with no input
     * from one of them (a kUSDT send or Combine Coins: its inputs are token coins at their own
     * contract addresses), so the own-funds check can't tell it's yours: never "Received" (iOS
     * 5b7c772c `markOwnTransaction`). Call it before the submit.
     */
    fun markOwnTransaction(txId: String) {
        val id = txId.trim().lowercase()
        if (id.isEmpty()) return
        val walletAddress = walletAddressOrNull() ?: return
        synchronized(ownTransactions) {
            ownTransactions += id
            // a session's worth, the persisted list below keeps them for good
            while (ownTransactions.size > HANDLED_TX_ID_CAP) ownTransactions.remove(ownTransactions.first())
        }
        scope.launch { runMutex.withLock { markHandled(id, walletAddress) } }
    }

    private fun markHandled(txId: String, walletAddress: String) {
        ensureHandledLoaded(walletAddress)
        if (!handledSet.add(txId)) return
        handledOrder.add(txId)
        while (handledOrder.size > HANDLED_TX_ID_CAP) {
            handledSet.remove(handledOrder.removeAt(0))
        }
        prefs.edit().putString(handledKey(walletAddress), gson.toJson(handledOrder)).apply()
    }

    companion object {
        private const val TAG = "AddressActivity"
        private const val PREFS_NAME = "kachat_address_activity"
        private const val HANDLED_TX_ID_CAP = 500
        private const val POLL_INTERVAL_MS = 30_000L

        /** Bumped when the Received KAS addresses may have changed: the push registration
         *  follows ([PushRegistrationManager], iOS 428a797b `pushAddressesMayHaveChanged`). */
        val watchOnlyChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

        internal fun shortAddress(a: String): String =
            if (a.length <= 20) a else a.take(14) + "..." + a.takeLast(6)
    }
}
