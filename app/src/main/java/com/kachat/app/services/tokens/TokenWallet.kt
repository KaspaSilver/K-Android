package com.kachat.app.services.tokens

import android.content.Context
import android.util.Log
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NoticeBubbleStore
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.WalletManager
import com.kachat.app.util.StartupSafety
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's tokens: balances by address, activity, and what can be done with them (iOS
 * `TokenWallet` in `Views/Tokens/WalletTokens.swift`, 5b7c772c). Tokens live on the Chatting
 * Address and on the regular spending addresses (owner's call, 2026-11-19: privacy from the start),
 * so a payment can go to an address that isn't tied to your name.
 *
 * Balances come from Trestly's API, every coin checked on the node ([KusdtBalances]).
 *
 * Threading: every suspend function is safe from any dispatcher (the work runs on this object's
 * own scope). The plain functions read state and are safe from any thread; those marked "derives"
 * may run the seed derivation on a cache miss, so call them off the main thread. A lookup
 * ([refresh], [discoverHoldings]) is ONE lookup for every caller: a caller that is cancelled (a
 * screen left) stops waiting, but the lookup itself runs on (audit #17).
 *
 * Errors: a failed lookup never throws; it sets [lastError] (the screens keep the last good
 * balances and show it). Discover skips an address that doesn't answer.
 */
@Singleton
class TokenWallet @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val balances: KusdtBalances,
    private val networkService: NetworkService,
    private val settings: AppSettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val notificationCenter: GlobalNotificationCenterStore,
    private val noticeBubbles: NoticeBubbleStore,
    private val storeRelay: dagger.Lazy<KusdtStoreRelay>,
) {
    /** The tokens KaChat shows, in order. */
    val tokens: List<WalletToken> = listOf(WalletToken.KUSDT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + StartupSafety.logOnlyHandler(TAG))
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    // MARK: Published state

    private val _holdings = MutableStateFlow<Map<String, List<TokenHolding>>>(emptyMap())
    /** Where each token is held, by token id; a token is missing until its first lookup answers.
     *  Read it through [holdings] / [listedHoldings] / [balance], which never show another
     *  account's; collect this flow to redraw. */
    val holdingsFlow: StateFlow<Map<String, List<TokenHolding>>> = _holdings.asStateFlow()

    private val _activity = MutableStateFlow<Map<String, List<TokenActivity>>>(emptyMap())
    /** Each token's history, newest first ([activity]). */
    val activityFlow: StateFlow<Map<String, List<TokenActivity>>> = _activity.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    /** A Discover is looking (Send Dollars runs one as it opens, so a From step with no kUSDT
     *  says it's still checking rather than "none"). */
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last lookup failed (text to show); null after one that worked. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _visibilityRevision = MutableStateFlow(0)
    /** Bumped when Address Visibility changes, so the screens that read it redraw. */
    val visibilityRevision: StateFlow<Int> = _visibilityRevision.asStateFlow()

    // MARK: Private state (under [lock])

    /** Each address's verified coins, by holding id (what a send spends). */
    private var coins: Map<String, List<KusdtBalances.Coin>> = emptyMap()
    /** Sends already noted ([noteSent]), so a resend of the same transaction isn't counted twice. */
    private val notedSentTxids = HashSet<String>()
    @Volatile private var lastRefreshed: Long? = null
    /** The account the balances belong to: another account's are dropped at once. */
    @Volatile private var loadedAccount: String? = null
    @Volatile private var lastDiscovered: Long? = null
    /** Addresses the last tolerant lookup couldn't read. */
    @Volatile private var lastLookupFailures = 0
    /** Addresses a receive screen showed lately ([noteReceiveAddressShown]): the regular poll looks
     *  at them too, so kUSDT sent there is noticed without asking about every address. */
    private val recentReceiveAddresses = HashMap<String, Long>()
    private var activityAccount: String? = null

    /**
     * A coin of yours that a send from this phone just made (its change, or kUSDT sent to another
     * of your addresses). Trestly's list can take a while to show it, and a balance built from
     * that list alone dropped to 0 after a send. So it's checked on the node with the listed coins
     * ([KusdtBalances.Expected]) until Trestly lists it, for up to 30 minutes.
     */
    private class PendingCoin(val holdingId: String, val address: String, val expected: KusdtBalances.Expected, val since: Long) {
        val key: String get() = expected.outpointKey
    }
    private var pendingCoins: List<PendingCoin> = emptyList()
    /** A refresh is due again shortly: a pending coin wasn't on the node yet. */
    @Volatile private var followUpScheduled = false

    /** The one lookup running (a refresh or a Discover). Every caller waits on it rather than
     *  starting its own, and it runs on this object's scope, so leaving a screen doesn't cut it off. */
    @Volatile private var runningLookup: Deferred<*>? = null
    private val lookupGate = Mutex()

    /** The signed-in account's chatting address (iOS `WalletManager.currentWallet?.publicAddress`). */
    private fun currentAccount(): String? =
        if (walletManager.isLoggedOut) null else walletManager.activeAddressFlow.value

    private fun maxSpendingIndex(): Int =
        walletManager.getActiveAccount()?.let { WalletManager.legacyMaxSpendingIndex(it) } ?: -1

    // MARK: Lookups

    /** Refreshes unless the balances are under [maxAgeMs] old (and for this account).
     *  [everyAddress]: every revealed address (Manage kUSDT, Send's From); otherwise only the ones
     *  that matter ([pollTargets], audit #14). */
    suspend fun refreshIfStale(maxAgeMs: Long = 60_000L, everyAddress: Boolean = false) {
        val last = lastRefreshed
        if (currentAccount() == loadedAccount && last != null && System.currentTimeMillis() - last < maxAgeMs) return
        refresh(everyAddress)
    }

    /** Looks the balances up again. One at a time: a call while another lookup runs waits for it,
     *  then runs only if that one was for another account (or a narrower look than asked for). */
    suspend fun refresh(everyAddress: Boolean = false) {
        val running = runningLookup
        if (running != null) {
            running.join()
            val last = lastRefreshed
            if (loadedAccount == currentAccount() && last != null && System.currentTimeMillis() - last < 5_000L && !everyAddress) return
        }
        runLookup { performRefresh(everyAddress) }
    }

    /** Runs [work] as the one lookup, after any other one finishes. */
    private suspend fun <T> runLookup(work: suspend () -> T): T {
        val deferred = scope.async { lookupGate.withLock { work() } }
        runningLookup = deferred
        deferred.invokeOnCompletion { if (runningLookup === deferred) runningLookup = null }
        return deferred.await()
    }

    /** A new account: the old one's balances go at once. */
    private fun adoptAccount(account: String) {
        synchronized(lock) {
            if (account == loadedAccount) return
            coins = emptyMap()
            pendingCoins = emptyList()
            loadedAccount = account
            // The old account's lookup times don't describe this one: its first refresh must be
            // the full lookup, or coins outside the narrow poll read as missing (too low a
            // balance) and come back later as "Received".
            lastRefreshed = null
            lastDiscovered = null
        }
        _holdings.value = emptyMap()
    }

    private suspend fun performRefresh(everyAddress: Boolean) {
        val account = currentAccount() ?: return
        adoptAccount(account)
        loadActivityIfNeeded(account)
        _isRefreshing.value = true
        try {
            val targets = if (everyAddress || lastRefreshed == null) allRevealedTargets(account) else pollTargets(account)
            val results = lookUp(targets)
            // the account may have changed while the lookups ran
            if (currentAccount() != account) return
            val previous = synchronized(lock) { coins }
            applyResults(results, WalletToken.KUSDT, merging = true)
            loadActivityIfNeeded(account)
            announceNewCoins(previous, account, WalletToken.KUSDT)
            _lastError.value = null
            lastRefreshed = System.currentTimeMillis()
            scheduleFollowUpIfPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = describe(e)
            Log.w(TAG, "lookup failed: ${e.message}")
        } finally {
            _isRefreshing.value = false
        }
    }

    private fun describe(e: Exception): String =
        (e as? KusdtBalances.Failure)?.message ?: e.localizedMessage ?: context.getString(R.string.trestly_no_answer)

    private class Target(val spendingIndex: Int?, val address: String)

    /** The Chatting Address and every revealed regular address. Derives. */
    private fun allRevealedTargets(account: String): List<Target> {
        val targets = arrayListOf(Target(null, account))
        val revealed = walletManager.deriveSpendingAddresses(0..maxOf(0, maxSpendingIndex()))
        for (index in revealed.keys.sorted()) revealed[index]?.let { targets += Target(index, it) }
        return targets
    }

    /**
     * What the regular refresh asks Trestly about: the Chatting Address, the addresses holding or
     * listed for kUSDT, ones with kUSDT history here, ones a send is waiting on, the newest few
     * revealed and any a receive screen showed in the last week. Not every address every time:
     * Trestly would see them all together, from one IP, every 90 seconds, and could link them
     * (audit #14). Derives.
     */
    private fun pollTargets(account: String): List<Target> {
        val maxIndex = maxSpendingIndex()
        val indices = HashSet<Int>()
        _holdings.value[WalletToken.KUSDT.id].orEmpty().mapNotNullTo(indices) { it.spendingIndex }
        indices += shownEmptyIndices(WalletToken.KUSDT)
        _activity.value[WalletToken.KUSDT.id].orEmpty().forEach { row ->
            if (row.holdingId.startsWith("s")) row.holdingId.drop(1).toIntOrNull()?.let { indices += it }
        }
        if (maxIndex >= 0) indices += (maxOf(0, maxIndex - 2)..maxIndex)
        val now = System.currentTimeMillis()
        val watched = synchronized(lock) {
            pendingCoins.map { it.address.lowercase() }.toSet() +
                recentReceiveAddresses.filterValues { now - it < WEEK_MS }.keys
        }
        val revealed = walletManager.deriveSpendingAddresses(0..maxOf(0, maxIndex))
        val targets = arrayListOf(Target(null, account))
        for (index in revealed.keys.sorted()) {
            val address = revealed[index] ?: continue
            if (index in indices || address.lowercase() in watched) targets += Target(index, address)
        }
        return targets
    }

    /** A receive screen showed this address: the regular refresh watches it for a week (iOS
     *  AddressReceiveView calls this as it draws the address). */
    fun noteReceiveAddressShown(address: String) {
        synchronized(lock) { recentReceiveAddresses[address.lowercase()] = System.currentTimeMillis() }
    }

    private class LookupResult(val spendingIndex: Int?, val result: KusdtBalances.AddressResult)

    /**
     * The lookups, four at a time. Any one failing fails the refresh, so a partial answer is never
     * shown as the whole balance. [tolerant] (Discover): an address whose lookup fails is left out
     * of the answer (it keeps what was known about it) instead of failing the whole sweep;
     * [lastLookupFailures] counts them.
     */
    private suspend fun lookUp(
        targets: List<Target>,
        tolerant: Boolean = false,
        progress: ((checkedThrough: Int?, found: Int) -> Unit)? = null,
    ): List<LookupResult> {
        val out = ArrayList<LookupResult>()
        val expectedByAddress = synchronized(lock) { pendingCoins.groupBy({ it.address }, { it.expected }) }
        var failures = 0
        try {
            for (batch in targets.chunked(4)) {
                val answers = coroutineScope {
                    batch.map { target ->
                        async {
                            try {
                                LookupResult(target.spendingIndex, balances.fetch(target.address, expectedByAddress[target.address].orEmpty()))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!tolerant) throw e
                                null
                            }
                        }
                    }.awaitAll()
                }
                for (answer in answers) if (answer != null) out += answer else failures += 1
                progress?.invoke(batch.last().spendingIndex, out.count { it.result.coins.isNotEmpty() })
            }
        } finally {
            lastLookupFailures = failures
        }
        return out
    }

    /** [merging]: the addresses not looked up this time keep what was known about them. */
    private fun applyResults(results: List<LookupResult>, token: WalletToken, merging: Boolean = false) {
        val now = System.currentTimeMillis()
        val list: MutableList<TokenHolding>
        synchronized(lock) {
            // pending coins: let go once Trestly lists them, or after 30 minutes
            val listed = results.flatMapTo(HashSet()) { it.result.listedByApi }
            pendingCoins = pendingCoins.filterNot { it.key in listed || now - it.since > 30 * 60_000L }
            val verified = results.flatMap { it.result.coins }.mapTo(HashSet()) { it.outpointKey }
            // one sent in the last 2 minutes that the node doesn't show yet still counts in the
            // balance (not in what a send can spend), so the balance doesn't dip while the network
            // catches up
            val landing = pendingCoins.filter { it.key !in verified && now - it.since < 120_000L }
            val lookedUp = results.mapTo(HashSet()) { it.result.address.lowercase() }
            list = if (merging) _holdings.value[token.id].orEmpty().filter { it.address.lowercase() !in lookedUp }.toMutableList()
            else ArrayList()
            val byHolding = HashMap<String, List<KusdtBalances.Coin>>()
            if (merging) for (h in list) coins[h.id]?.let { byHolding[h.id] = it }
            for (r in results) {
                // a coin a send from here is holding is spent, even while the node and Trestly still list it
                val live = r.result.coins.filterNot { UnknownOutcomeOutpoints.isHeld(it.transactionId, it.index) }
                val extra = landing.filter { it.address == r.result.address }
                if (live.isEmpty() && extra.isEmpty()) continue
                val units = live.sumOf { it.state.amount } + extra.sumOf { it.expected.state.amount }
                val holding = TokenHolding(r.spendingIndex, r.result.address, units, live.size + extra.size)
                list += holding
                byHolding[holding.id] = live
            }
            list.sortBy { it.spendingIndex ?: -1 }
            coins = byHolding
        }
        _holdings.value = _holdings.value + (token.id to list.toList())
    }

    // MARK: Reading the balances

    /** Every address holding [token], hidden ones included (the total, Send's From). Never another
     *  account's. */
    fun holdings(token: WalletToken = WalletToken.KUSDT): List<TokenHolding> = allHoldings(token).orEmpty().filter { it.units > 0 }

    /** Every address together; null until the first lookup answers for this account. */
    fun balance(token: WalletToken = WalletToken.KUSDT): Long? = allHoldings(token)?.sumOf { it.units }

    private fun allHoldings(token: WalletToken): List<TokenHolding>? {
        // another account's balances are never shown, even for the moment before they're replaced
        // (audit #9)
        if (loadedAccount == null || loadedAccount != currentAccount()) return null
        return _holdings.value[token.id]
    }

    /** The verified coins of one address (what a send from it can spend); none while the
     *  balances are another account's (audit #9). */
    fun coins(holding: TokenHolding): List<KusdtBalances.Coin> {
        if (loadedAccount == null || loadedAccount != currentAccount()) return emptyList()
        return synchronized(lock) { coins[holding.id].orEmpty() }
    }

    /** KAS held with the token's coins (1.5 KAS each for kUSDT), returned to you as they're
     *  merged or sent. */
    fun kasHeld(token: WalletToken = WalletToken.KUSDT): Long = holdings(token).sumOf { it.coins.toLong() } * token.kasPerCoinSompi

    /** "Chatting Address", or the address's name as Manage Addresses shows it: the one you gave it,
     *  else "Address #3". Derives. */
    fun label(holding: TokenHolding): String {
        val index = holding.spendingIndex ?: return context.getString(R.string.chatting_address)
        return runCatching { walletManager.ownAddressLabel(holding.address) }.getOrNull()
            ?: WalletManager.spendingAddressTitle(index)
    }

    /** "Chatting Address", "Address #3", or "2 Addresses". Derives. */
    fun heldOnText(token: WalletToken = WalletToken.KUSDT): String {
        val list = holdings(token)
        if (list.size == 1) return label(list[0])
        return context.getString(R.string.token_held_on_addresses, list.size)
    }

    /**
     * The addresses Manage kUSDT lists, as Kaspa's Address Visibility decides for Manage Addresses:
     * the Chatting Address always, then each regular address holding the token unless it's hidden,
     * and any address without it that's checked on, in address order. Derives.
     */
    fun listedHoldings(token: WalletToken = WalletToken.KUSDT): List<TokenHolding> {
        val held = holdings(token).associateBy { it.id }
        val hidden = hiddenHoldingIds(token)
        val shown = shownEmptyIndices(token)
        val out = ArrayList<TokenHolding>()
        val chatting = held[TokenHolding.CHATTING_ID]
        if (chatting != null) out += chatting
        else currentAccount()?.let { out += TokenHolding(null, it, 0, 0) }
        val indices = held.values.mapNotNull { it.spendingIndex }.filter { "s$it" !in hidden }.toMutableSet()
        indices += shown
        val missing = indices.filter { held["s$it"] == null }
        val derived = if (missing.isEmpty()) emptyMap() else walletManager.deriveSpendingAddresses(missing)
        for (index in indices.sorted()) {
            val holding = held["s$index"]
            if (holding != null) out += holding
            else derived[index]?.let { out += TokenHolding(index, it, 0, 0) }
        }
        return out
    }

    /** The regular spending slots Manage kUSDT lists. Manage Addresses always shows these too
     *  (owner's call, 2026-11-20, as KaPay has it): an address on the kUSDT list is never hidden
     *  from the Kaspa one. One way only, never a two-way sync. Derives. */
    fun listedSpendingIndices(token: WalletToken = WalletToken.KUSDT): Set<Int> =
        listedHoldings(token).mapNotNull { it.spendingIndex }.toSet()

    /** Whether Address Visibility has this address on Manage kUSDT's list. */
    fun isListed(holding: TokenHolding, token: WalletToken = WalletToken.KUSDT): Boolean {
        val index = holding.spendingIndex ?: return true
        if (holding.units > 0) return holding.id !in hiddenHoldingIds(token)
        return index in shownEmptyIndices(token)
    }

    /** Address Visibility's checkmark, tapped: an address holding the token is hidden or shown,
     *  one without it is put on the list or taken off. The Chatting Address always stays. */
    fun toggleListed(holding: TokenHolding, token: WalletToken = WalletToken.KUSDT) {
        val index = holding.spendingIndex ?: return
        if (holding.units > 0) {
            val hidden = hiddenHoldingIds(token).toMutableSet()
            if (!hidden.remove(holding.id)) hidden += holding.id
            setHiddenHoldingIds(hidden, token)
        } else {
            val shown = shownEmptyIndices(token).toMutableSet()
            if (!shown.remove(index)) shown += index
            setShownEmptyIndices(shown, token)
        }
    }

    /** Hide All: the list starts over with only the Chatting Address. Nothing is lost: the hidden
     *  addresses still count in the total. */
    fun hideAllListed(token: WalletToken = WalletToken.KUSDT) {
        setHiddenHoldingIds(holdings(token).filter { !it.isChatting }.map { it.id }.toSet(), token)
        setShownEmptyIndices(emptySet(), token)
    }

    // MARK: Address Visibility (kept per account and token)

    /** Addresses holding the token that are hidden from Manage kUSDT. */
    fun hiddenHoldingIds(token: WalletToken = WalletToken.KUSDT): Set<String> {
        val key = visibilityKey("hidden", token) ?: return emptySet()
        return prefs.getStringSet(key, null)?.toSet() ?: emptySet()
    }

    fun setHiddenHoldingIds(ids: Set<String>, token: WalletToken = WalletToken.KUSDT) {
        val key = visibilityKey("hidden", token) ?: return
        prefs.edit().putStringSet(key, ids.toSet()).apply()
        _visibilityRevision.value += 1
    }

    /** Spending indices without the token that are checked on, so Manage kUSDT lists them. */
    fun shownEmptyIndices(token: WalletToken = WalletToken.KUSDT): Set<Int> {
        val key = visibilityKey("shown", token) ?: return emptySet()
        return prefs.getStringSet(key, null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }

    fun setShownEmptyIndices(indices: Set<Int>, token: WalletToken = WalletToken.KUSDT) {
        val key = visibilityKey("shown", token) ?: return
        prefs.edit().putStringSet(key, indices.map { it.toString() }.toSet()).apply()
        _visibilityRevision.value += 1
    }

    private fun visibilityKey(kind: String, token: WalletToken): String? =
        currentAccount()?.let { "token_${kind}_${token.id}_$it" }

    // MARK: Sends

    /**
     * A kUSDT send went out ([KusdtTransfer.send]): its coins are gone from the balances at once,
     * every output it made is known (so your own change is never announced as received), and the
     * balances are looked up again shortly, once the node has it. Once per transaction: Try Again
     * after an unknown outcome notes the same one again, which counted its change coin twice
     * (audit #10).
     */
    fun noteSent(prepared: KusdtTransfer.Prepared) {
        val account = currentAccount() ?: return
        val token = WalletToken.KUSDT
        val ownRecipient = ownTokenAddress(prepared.recipient)
        val now = System.currentTimeMillis()
        val spent = prepared.spentCoins.mapTo(HashSet()) { it.outpointKey }
        synchronized(lock) {
            if (!notedSentTxids.add(prepared.txid)) return
            coins = coins.mapValues { (_, list) -> list.filterNot { it.outpointKey in spent } }
            val pending = pendingCoins.filterNot { it.key in spent }.toMutableList()
            // the coins this send made that are yours: checked on the node until Trestly lists them
            val spentUnits = prepared.spentCoins.sumOf { it.state.amount }
            val senderKey = Kusdt.schnorrOwner(prepared.holding.address)
            if (prepared.changeIndex != null && spentUnits > prepared.amount && senderKey != null) {
                pending += PendingCoin(prepared.holding.id, prepared.holding.address,
                    KusdtBalances.Expected(prepared.txid, prepared.changeIndex, Kusdt.State.plain(senderKey, spentUnits - prepared.amount)), now)
            }
            val ownerKey = ownRecipient?.let { Kusdt.schnorrOwner(it.address) }
            if (ownRecipient != null && ownerKey != null) {
                pending += PendingCoin(ownRecipient.id, ownRecipient.address,
                    KusdtBalances.Expected(prepared.txid, 0, Kusdt.State.plain(ownerKey, prepared.amount)), now)
            }
            pendingCoins = pending
        }

        // the history: a combine, or a send (and its arrival, when it went to another of yours)
        if (prepared.recipient.equals(prepared.holding.address, ignoreCase = true)) {
            record(TokenActivity("${prepared.txid}:${prepared.holding.id}:combined", TokenActivity.Kind.COMBINED.raw, prepared.amount,
                prepared.spentCoins.size.toString(), now, holdingId = prepared.holding.id, fee = prepared.fee), token, account)
        } else {
            val to = ownRecipient?.let { label(TokenHolding(it.spendingIndex, it.address, 0, 0)) }
                ?: prepared.recipientLabel ?: prepared.recipient
            record(TokenActivity("${prepared.txid}:${prepared.holding.id}:sent", TokenActivity.Kind.SENT.raw, prepared.amount,
                to, now, holdingId = prepared.holding.id, fee = prepared.fee), token, account)
            if (ownRecipient != null) {
                record(TokenActivity("${prepared.txid}:${ownRecipient.id}:received", TokenActivity.Kind.RECEIVED.raw, prepared.amount,
                    label(prepared.holding), now, holdingId = ownRecipient.id), token, account)
            }
        }

        val list = _holdings.value[token.id].orEmpty().toMutableList()
        val i = list.indexOfFirst { it.id == prepared.holding.id }
        if (i >= 0) {
            val old = list[i]
            val units = if (old.units > prepared.amount) old.units - prepared.amount else 0L
            list[i] = old.copy(units = units,
                coins = maxOf(0, old.coins - prepared.spentCoins.size + (if (prepared.changeIndex == null) 0 else 1)))
        }
        if (ownRecipient != null) {
            val j = list.indexOfFirst { it.id == ownRecipient.id }
            if (j >= 0) {
                val old = list[j]
                list[j] = old.copy(units = old.units + prepared.amount, coins = old.coins + 1)
            } else {
                list += TokenHolding(ownRecipient.spendingIndex, ownRecipient.address, prepared.amount, 1)
                list.sortBy { it.spendingIndex ?: -1 }
            }
        }
        _holdings.value = _holdings.value + (token.id to list.filter { it.units > 0 || it.coins > 0 })
        // every output of the send: it can only be yours if it's change, or a send to yourself
        val seenKey = seenKey(account, token)
        val seen = prefs.getStringSet(seenKey, null)?.toMutableSet() ?: mutableSetOf()
        for (index in 0 until prepared.rpc.outputsCount) seen += "${prepared.txid}:$index"
        prefs.edit().putStringSet(seenKey, seen).apply()
        scope.launch {
            delay(4_000)
            refresh()
        }
    }

    /**
     * A swap made on Trestly's site from KaChat ([TrestlyProvider], iOS 148a5331 `noteSwap`): the
     * kUSDT side, on that address's history ([TrestlyProvider] then refreshes once the pool's new
     * coins can be listed). iOS also stores whether it sold kUSDT (`outgoing`), which nothing reads.
     */
    fun noteSwap(token: WalletToken, spendingIndex: Int?, txid: String, units: Long, @Suppress("UNUSED_PARAMETER") sold: Boolean, fee: Long?) {
        val account = currentAccount() ?: return
        val holdingId = spendingIndex?.let { "s$it" } ?: TokenHolding.CHATTING_ID
        record(TokenActivity("$txid:$holdingId:swapped", TokenActivity.Kind.SWAPPED.raw, units, "Trestly",
            System.currentTimeMillis(), holdingId = holdingId, fee = fee), token, account)
    }

    private class OwnAddress(val id: String, val spendingIndex: Int?, val address: String)

    /** The address a send went to, if it's one of yours that can hold tokens: the Chatting Address
     *  or a revealed regular spending address. Derives. */
    private fun ownTokenAddress(address: String): OwnAddress? {
        val target = address.trim().lowercase()
        val account = currentAccount() ?: return null
        if (account.lowercase() == target) return OwnAddress(TokenHolding.CHATTING_ID, null, account)
        val revealed = walletManager.deriveSpendingAddresses(0..maxOf(0, maxSpendingIndex()))
        val match = revealed.entries.firstOrNull { it.value.lowercase() == target } ?: return null
        return OwnAddress("s${match.key}", match.key, match.value)
    }

    /** A pending coin the node didn't show yet: look again in 5 seconds (the 2-minute window ends it). */
    private fun scheduleFollowUpIfPending() {
        val now = System.currentTimeMillis()
        val waiting = synchronized(lock) {
            val verified = coins.values.flatten().mapTo(HashSet()) { it.outpointKey }
            pendingCoins.any { it.key !in verified && now - it.since < 120_000L }
        }
        if (!waiting || followUpScheduled) return
        followUpScheduled = true
        scope.launch {
            delay(5_000)
            followUpScheduled = false
            refresh()
        }
    }

    // MARK: Received notifications

    /**
     * A token coin doesn't land on your address (it sits at its own contract address), so the
     * Kaspa watch never sees it arrive. Instead each refresh compares the coins it found with the
     * ones known before (kept per account on this phone) and announces the new ones as Received,
     * like Kaspa: a local notification and the Notifications list, behind Settings > Notifications
     * > Address Activity. Never announced: the first look on this phone (it only learns what's
     * there), what Discover finds, and coins from your own transfers (the transaction spent a coin
     * of yours, or paid from one of your addresses). A contact's Send Dollars the chat already
     * showed (a token_payment_notice's bubble) is recorded but not bannered again.
     */
    private suspend fun announceNewCoins(previous: Map<String, List<KusdtBalances.Coin>>, account: String, token: WalletToken) {
        val byHolding = synchronized(lock) { coins }
        val current = byHolding.values.flatten()
        val known = seenOutpoints(account, token)
        try {
            if (known == null) return // first look: learn, don't announce
            val fresh = current.filter { it.outpointKey !in known }
            if (fresh.isEmpty()) return
            val freshKeys = fresh.mapTo(HashSet()) { it.outpointKey }
            // a coin we knew that's gone now: something of ours was spent in this window
            val previousKeys = previous.values.flatten().mapTo(HashSet()) { it.outpointKey }
            val currentKeys = current.mapTo(HashSet()) { it.outpointKey }
            val ownCoinSpent = (previousKeys - currentKeys).isNotEmpty()
            val ownAddresses = (runCatching { walletManager.allSpendingAddresses() }.getOrDefault(emptyList()) + account)
                .mapTo(HashSet()) { it.lowercase() }

            val received = ArrayList<Pair<String, Long>>()
            val checked = HashMap<String, Boolean>()
            for ((holdingId, list) in byHolding) {
                for (coin in list) {
                    if (coin.outpointKey !in freshKeys) continue
                    val own = checked.getOrPut(coin.transactionId) {
                        isOwnTransfer(coin.transactionId, known, ownAddresses, fallback = ownCoinSpent)
                    }
                    if (own || coin.state.amount <= 0) continue
                    // a contact's Send Dollars the chat already showed and announced: no second banner
                    if (!noticeBubbles.isNoticeBubble(account, coin.transactionId)) received += holdingId to coin.state.amount
                    record(TokenActivity("${coin.transactionId}:$holdingId:received", TokenActivity.Kind.RECEIVED.raw,
                        coin.state.amount, "", System.currentTimeMillis(), holdingId = holdingId), token, account)
                }
            }
            if (received.isEmpty() || !notificationsEnabled()) return
            postReceived(received, token)
        } finally {
            rememberSeen(current, account, token)
        }
    }

    /** Whether the transaction that made a coin was your own: it spent a coin of yours, or an
     *  input came from one of your addresses. When the REST API can't say, [fallback]. */
    private suspend fun isOwnTransfer(txid: String, known: Set<String>, ownAddresses: Set<String>, fallback: Boolean): Boolean {
        val api = networkService.kaspaRestApi.value ?: return fallback
        val tx = try { api.getTransactionWithInputAddresses(txid) } catch (e: CancellationException) { throw e } catch (e: Exception) { return fallback }
        val inputs = tx.inputs
        if (inputs.isEmpty()) return fallback
        for (input in inputs) {
            val hash = (input.previousOutpointHash as String?)?.lowercase()
            if (hash != null && "$hash:${input.previousOutpointIndex}" in known) return true
            val address = input.previousOutpointAddress?.lowercase()
            if (address != null && address in ownAddresses) return true
        }
        return false
    }

    private suspend fun postReceived(received: List<Pair<String, Long>>, token: WalletToken) {
        val total = received.sumOf { it.second }
        val ids = received.mapTo(HashSet()) { it.first }
        val labels = holdings(token).filter { it.id in ids }.map { label(it) }
        val title = context.getString(R.string.token_received_title, TokenAmount.amount(total, token))
        val body = if (labels.size == 1) context.getString(R.string.token_received_on_one, labels[0])
        else context.getString(R.string.token_received_on_many, labels.size)
        val id = "token-received-${token.id}-${java.util.UUID.randomUUID()}"
        notificationHelper.showAddressActivity(title = title, text = body, dedupeKey = id, kind = NOTIFICATION_KIND)
        notificationCenter.record(id, NOTIFICATION_SOURCE, title, body, System.currentTimeMillis(), token.id)
    }

    /** Settings > Notifications > Address Activity, and notifications on at all (as for Kaspa). */
    private suspend fun notificationsEnabled(): Boolean =
        settings.addressActivityNotificationsEnabled.first() && settings.notificationsEnabled.first()

    private fun seenKey(account: String, token: WalletToken) = "token_seen_${token.id}_$account"

    /** The coins known on this phone for the account; null before its first look. */
    private fun seenOutpoints(account: String, token: WalletToken): Set<String>? =
        prefs.getStringSet(seenKey(account, token), null)?.toSet()

    private fun rememberSeen(list: List<KusdtBalances.Coin>, account: String, token: WalletToken) {
        val key = seenKey(account, token)
        val stored = prefs.getStringSet(key, null)?.toSet() ?: emptySet()
        // the coins there now (and what Discover found), so the list never outgrows the wallet
        val present = synchronized(lock) { coins.values.flatten().mapTo(HashSet()) { it.outpointKey } }
        val next = stored.intersect(present) + list.map { it.outpointKey }
        prefs.edit().putStringSet(key, next.toSet()).apply()
    }

    // MARK: Checking while the app is open

    @Volatile private var pollJob: Job? = null

    /** While the app is open and signed in, the balances are checked every 90 seconds, so a token
     *  that arrives is announced - only with Address Activity notifications on; the screens refresh
     *  themselves either way. Started on foreground by KaChatApplication. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!notificationHelper.isAppInForeground || currentAccount() == null || !notificationsEnabled()) continue
                refreshIfStale(maxAgeMs = 80_000L)
            }
        }
    }

    /** Backgrounded: the poll stops (its lookups need the app open, as on iOS). */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // MARK: Discover

    /**
     * Finds the addresses holding the token: the Chatting Address and the regular spending
     * addresses from the first through 20 past the last one revealed, each looked up (and checked
     * on the node). The furthest one it finds past the revealed ones is revealed, so it stays on
     * the list. Reports each batch as it goes through [progress] (address number checked through,
     * how many hold some so far; called on a background thread). Returns how many hold some.
     */
    suspend fun discoverHoldings(token: WalletToken = WalletToken.KUSDT, progress: (checking: Int, found: Int) -> Unit = { _, _ -> }): Int =
        runLookup { performDiscover(token, progress) }

    /** Discover, unless one ran in the last [maxAgeMs] for this account (Send Dollars opens it
     *  every time; once in a while is enough; audit #21). */
    suspend fun discoverIfStale(token: WalletToken = WalletToken.KUSDT, maxAgeMs: Long = 300_000L) {
        val last = lastDiscovered
        if (loadedAccount == currentAccount() && last != null && System.currentTimeMillis() - last < maxAgeMs) return
        discoverHoldings(token)
    }

    private suspend fun performDiscover(token: WalletToken, progress: (Int, Int) -> Unit): Int {
        val account = currentAccount() ?: return 0
        adoptAccount(account)
        _isDiscovering.value = true
        try {
            val last = maxSpendingIndex() + 20
            val derived = walletManager.deriveSpendingAddresses(0..maxOf(last, 0))
            val targets = arrayListOf(Target(null, account))
            for (index in derived.keys.sorted()) derived[index]?.let { targets += Target(index, it) }
            val results = try {
                // four at a time, like a refresh, reporting as each batch answers; an address that
                // doesn't answer is skipped, not the whole sweep
                lookUp(targets, tolerant = true) { checkedThrough, soFar ->
                    progress(checkedThrough?.let { WalletManager.addressNumber(it) } ?: 0, soFar)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = describe(e)
                return 0
            }
            if (lastLookupFailures > 0) Log.i(TAG, "Discover: $lastLookupFailures addresses didn't answer, kept as they were")
            val found = results.count { it.result.coins.isNotEmpty() }
            if (currentAccount() != account) return found
            // what Discover finds was there already: known from now on, never announced as received
            rememberSeen(results.flatMap { it.result.coins }, account, token)
            // the furthest address holding some goes on the list: exactly that slot, for the account
            // the sweep was for (the ones between stay hidden), and the account checked again after
            val furthest = results.filter { it.result.coins.isNotEmpty() }.mapNotNull { it.spendingIndex }.maxOrNull()
            if (furthest != null && furthest > maxSpendingIndex()) walletManager.revealSpendingAddress(account, furthest)
            if (currentAccount() != account) return found
            applyResults(results, token, merging = true)
            _lastError.value = null
            lastRefreshed = System.currentTimeMillis()
            lastDiscovered = System.currentTimeMillis()
            return found
        } finally {
            _isDiscovering.value = false
        }
    }

    /**
     * An account removed from this phone (Delete Account and the other removals): its token
     * history, known coins and Address Visibility choices go, and so do the balances in memory if
     * they're that account's (iOS `forgetLocalData(account:)`, called from
     * `WalletManager.removeLocalData`).
     */
    fun forgetLocalData(account: String) {
        val editor = prefs.edit()
        for (token in tokens) for (kind in listOf("activity", "seen", "hidden", "shown")) editor.remove("token_${kind}_${token.id}_$account")
        editor.apply()
        // What Trestly's code kept for each spending address's key (liquidity positions, followed
        // trades): read now, while the account's derived-address cache still exists (iOS
        // `removeLocalData`; the chatting address's key goes in AccountFeatureCleanup).
        try {
            val owners = walletManager.cachedSpendingAddresses(account)
                .mapNotNull { address -> Kusdt.schnorrOwner(address)?.let { Kusdt.hex(it) } }
            if (owners.isNotEmpty()) storeRelay.get().forget(owners)
        } catch (e: Exception) {
            Log.w(TAG, "Trestly store not cleared for the spending addresses: ${e.javaClass.simpleName}")
        }
        var clearHoldings = false
        var clearActivity = false
        synchronized(lock) {
            if (loadedAccount == account) {
                coins = emptyMap()
                pendingCoins = emptyList()
                loadedAccount = null
                lastRefreshed = null
                clearHoldings = true
            }
            if (activityAccount == account) {
                activityAccount = null
                clearActivity = true
            }
        }
        if (clearHoldings) _holdings.value = emptyMap()
        if (clearActivity) _activity.value = emptyMap()
    }

    // MARK: History (kept on this phone)

    /** Trestly's API keeps no history and a token coin never sits at your own address, so the
     *  history is what this phone saw: its sends and Combine Coins ([noteSent]), and kUSDT received
     *  from others ([announceNewCoins]), newest first, per account and token, 300 at most. */
    fun activity(token: WalletToken = WalletToken.KUSDT): List<TokenActivity> {
        currentAccount()?.let { loadActivityIfNeeded(it) }
        return _activity.value[token.id].orEmpty()
    }

    /** One address's activity: its page's history. */
    fun activity(token: WalletToken, holding: TokenHolding): List<TokenActivity> =
        activity(token).filter { it.holdingId == holding.id }

    private fun activityKey(account: String, token: WalletToken) = "token_activity_${token.id}_$account"

    /** The account's saved history, read once per account. */
    private fun loadActivityIfNeeded(account: String) {
        synchronized(lock) {
            if (activityAccount == account) return
            activityAccount = account
        }
        val loaded = HashMap<String, List<TokenActivity>>()
        for (token in tokens) {
            prefs.getString(activityKey(account, token), null)?.let { loaded[token.id] = decodeActivity(it) }
        }
        _activity.value = loaded
    }

    private fun record(row: TokenActivity, token: WalletToken, account: String) {
        loadActivityIfNeeded(account)
        val rows = synchronized(lock) {
            val list = _activity.value[token.id].orEmpty().filterNot { it.id == row.id }.toMutableList()
            list.add(0, row)
            list.sortByDescending { it.date }
            val capped = list.take(ACTIVITY_CAP)
            _activity.value = _activity.value + (token.id to capped)
            capped
        }
        prefs.edit().putString(activityKey(account, token), encodeActivity(rows)).apply()
    }

    /** Runs [block] on this object's scope: work that must outlive the screen that started it
     *  (TokenViewModel's wait for a "Not confirmed" send to land). */
    fun launchDetached(block: suspend () -> Unit): Job = scope.launch { block() }

    // MARK: Fresh receive addresses

    /** A brand-new regular address to receive on: the next legacy slot, never revealed, funded or
     *  handed out. Derived only; [revealFreshReceiveAddress] puts it on the list once it's copied
     *  or shared. Derives. */
    fun freshReceiveAddress(): Pair<Int, String>? {
        // past every revealed slot, and never one already holding a token (it isn't fresh)
        val taken = tokens.flatMap { holdings(it) }.mapNotNull { it.spendingIndex }.toSet()
        var index = maxSpendingIndex() + 1
        while (index in taken) index += 1
        val address = walletManager.deriveSpendingAddresses(listOf(index))[index] ?: return null
        return index to address
    }

    /** Generate New Address (kUSDT Actions): the next unused regular address, revealed (so it's on
     *  Manage Addresses' list and watched) and checked on in Manage kUSDT's list, though it holds
     *  nothing yet. Answers its number ("#5"), or null if no address could be derived. */
    suspend fun generateFreshAddress(token: WalletToken = WalletToken.KUSDT): Int? {
        val fresh = kotlinx.coroutines.withContext(Dispatchers.Default) { freshReceiveAddress() } ?: return null
        revealFreshReceiveAddress(fresh.first)
        setShownEmptyIndices(shownEmptyIndices(token) + fresh.first, token)
        return WalletManager.addressNumber(fresh.first)
    }

    /** The fresh receive address goes on Manage Addresses' list (and is watched) once it's handed
     *  out: up to that slot (a fresh address can skip one already holding a token). */
    suspend fun revealFreshReceiveAddress(index: Int) {
        val account = currentAccount() ?: return
        while (index > maxSpendingIndex()) {
            val before = maxSpendingIndex()
            walletManager.generateNextSpendingAddress(account)
            if (maxSpendingIndex() <= before) break
        }
    }

    companion object {
        private const val TAG = "kUSDT"
        private const val PREFS_NAME = "kachat_tokens"
        private const val ACTIVITY_CAP = 300
        private const val POLL_INTERVAL_MS = 90_000L
        private const val WEEK_MS = 7 * 86_400_000L

        /** [NotificationHelper.showAddressActivity]'s `kind` for a "Received kUSDT" notification:
         *  its tap opens Manage kUSDT on the Wallet tab (iOS `TokenWallet.notificationThread`). */
        const val NOTIFICATION_KIND = "token"

        /** The Notifications list's source for a token received (iOS `Entry.Source.token`); the
         *  row's targetId is the token's id. */
        const val NOTIFICATION_SOURCE = "token"

        internal fun encodeActivity(rows: List<TokenActivity>): String {
            val array = JSONArray()
            for (r in rows) {
                val o = JSONObject().put("id", r.id).put("kind", r.kind).put("units", r.units)
                    .put("counterparty", r.counterparty).put("date", r.date).put("pending", r.pending)
                    .put("holdingId", r.holdingId)
                r.fee?.let { o.put("fee", it) }
                array.put(o)
            }
            return array.toString()
        }

        internal fun decodeActivity(json: String): List<TokenActivity> {
            val array = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
            val out = ArrayList<TokenActivity>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val kind = o.optString("kind").takeIf { it.isNotEmpty() } ?: continue
                out += TokenActivity(
                    id = id, kind = kind, units = o.optLong("units"), counterparty = o.optString("counterparty"),
                    date = o.optLong("date"), pending = o.optBoolean("pending"),
                    holdingId = o.optString("holdingId", TokenHolding.CHATTING_ID),
                    fee = if (o.has("fee")) o.optLong("fee") else null,
                )
            }
            return out
        }
    }
}
