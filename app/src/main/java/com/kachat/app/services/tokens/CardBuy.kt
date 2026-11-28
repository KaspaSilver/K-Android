package com.kachat.app.services.tokens

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.ForegroundBeat
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.WalletManager
import com.kachat.app.util.EIP712
import com.kachat.app.util.EthereumHex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing K signs on Ethereum (iOS bc0e6ba9 `PermitRequest`, Services/Tokens/CardBuy.swift):
 * a USDC permit (EIP-2612) on mainnet from K's own address, as Trestly's buy page asks for it.
 * Anything else is refused before a sheet shows. [respond] answers the provider, once.
 */
class PermitRequest private constructor(
    /** What the permit lets the bridge take, in USDC's 6-decimal units. */
    val valueUnits: Long,
    val spender: String,
    /** When the permit ends, ms since 1970. */
    val deadlineMs: Long,
    val target: TrestlyTarget,
    val digest: ByteArray,
) {
    val id: String = UUID.randomUUID().toString()
    private val answer = CompletableDeferred<Boolean>()

    /** Answers once; later calls do nothing. */
    fun respond(approved: Boolean) {
        answer.complete(approved)
    }

    internal suspend fun await(): Boolean = answer.await()

    /** "52.00 USDC". */
    val amountText: String
        get() = String.format(Locale.US, "%.2f USDC", valueUnits.toDouble() / 1_000_000)

    companion object {
        /**
         * The most a permit may let the bridge take for an order of [dollars] ("50.00"), in USDC
         * units: the order plus 1% and a dollar (iOS d88891ea, audit 2026-11-27 L2). Null while K
         * doesn't know the order's amount. The USDC delivered is that amount less fees, and this
         * address may be MetaMask's account 0 for someone who imported their MetaMask words.
         */
        fun capUnits(dollars: String?): Long? {
            val amount = knownDollars(dollars) ?: return null
            return capUnits(amount)
        }

        /** [capUnits] for an amount already checked by [knownDollars]. */
        fun capUnits(amount: Double): Long {
            // in whole units, so float drift never makes $1.00's cap 2.009999 (iOS truncates the
            // Double; this is the same cap without its off-by-one-unit)
            val units = Math.round(amount * 1_000_000)
            return units + units / 100 + 1_000_000L
        }

        /** The permit limit, $1,000,000 (iOS 01f71184 `knownOrderDollars`). */
        const val MAX_ORDER_DOLLARS = 1_000_000.0

        /** An order's amount as K trusts it: finite, above 0 and up to [MAX_ORDER_DOLLARS].
         *  Trestly's answer is data, and a huge or infinite amount would overflow the cap (iOS
         *  01f71184, release audit 2026-11-27 R6). */
        fun knownDollars(dollars: String?): Double? =
            dollars?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= MAX_ORDER_DOLLARS }

        /** A permit for more than [order] paid for (the order to the same Kaspa address). */
        fun exceedsOrder(valueUnits: Long, order: CardBuy?, targetAddress: String): Boolean {
            if (order == null || !order.address.equals(targetAddress, ignoreCase = true)) return false
            val cap = capUnits(order.dollars) ?: return false
            return valueUnits > cap
        }

        /** [exceedsOrder] for an amount already read ([CardBuyTracker.knownOrderDollars]); no cap
         *  while it isn't known (iOS 01f71184, release audit 2026-11-27 C6). */
        fun exceedsDollars(valueUnits: Long, dollars: Double?): Boolean {
            val amount = dollars?.takeIf { it.isFinite() && it > 0 && it <= MAX_ORDER_DOLLARS } ?: return false
            return valueUnits > capUnits(amount)
        }

        private val PERMIT_FIELDS = listOf("address owner", "address spender", "uint256 value", "uint256 nonce", "uint256 deadline")
        private val ALLOWED_DOMAIN = setOf("string name", "string version", "uint256 chainId", "address verifyingContract")

        private fun stringOf(value: JsonElement?): String? =
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null

        /** `[address, typedData]`, the typed data as JSON text (or an object), checked field by field. */
        fun parse(params: JsonElement?, owner: String, target: TrestlyTarget, nowMs: Long = System.currentTimeMillis()): PermitRequest {
            val list = params?.takeIf { it.isJsonArray }?.asJsonArray
            val signer = list?.takeIf { it.size() == 2 }?.let { stringOf(it[0]) }
            if (list == null || signer == null) throw EIP712.Failure("Expected [address, typedData].")
            if (signer.lowercase() != owner.lowercase()) throw EIP712.Failure("K signs only for its own address.")
            val raw = list[1]
            val typed: JsonObject = when {
                stringOf(raw) != null ->
                    TrestlyProvider.parseStrict(raw.asString)?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: throw EIP712.Failure("The typed data isn't JSON.")
                raw != null && raw.isJsonObject -> raw.asJsonObject
                else -> throw EIP712.Failure("The typed data is missing.")
            }

            // exactly an EIP-2612 permit: these types, nothing more
            if (stringOf(typed.get("primaryType")) != "Permit") throw EIP712.Failure("K signs only a USDC permit here.")
            val types = EIP712.parseTypes(typed.get("types"))
            if (types.keys != setOf("EIP712Domain", "Permit")) throw EIP712.Failure("K signs only a USDC permit here.")
            val permitFields = types["Permit"]?.map { "${it.type} ${it.name}" }
            if (permitFields != PERMIT_FIELDS) throw EIP712.Failure("This isn't a standard permit.")
            val domainFields = types["EIP712Domain"]?.map { "${it.type} ${it.name}" }
            if (domainFields == null || !ALLOWED_DOMAIN.containsAll(domainFields.toSet()) ||
                "uint256 chainId" !in domainFields || "address verifyingContract" !in domainFields
            ) {
                throw EIP712.Failure("This permit's domain isn't USDC's.")
            }

            // USDC on Ethereum mainnet, from K's own address
            val domain = typed.get("domain")?.takeIf { it.isJsonObject }?.asJsonObject
            if (domain == null || EIP712.smallUInt(domain.get("chainId")) != EthereumAccount.CHAIN_ID.toLong() ||
                stringOf(domain.get("verifyingContract"))?.lowercase() != EthereumAccount.USDC_CONTRACT
            ) {
                throw EIP712.Failure("K signs a permit only for USDC on Ethereum.")
            }
            val message = typed.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            // The spender is the deposit address Trestly's factory names for the Kaspa address
            // (`forwarderOf(0, kaspaOwner, maxFee, maxDepositFee)`, which its page checks with an
            // eth_call). K can't check it: the fee caps aren't in the typed data. Shown in full on
            // the sheet; iOS CLAUDE.md's Buy Kaspa section has the ask for Trestly (audit 2026-11-26).
            val spender = message?.let { stringOf(it.get("spender")) }?.takeIf { EthereumHex.isAddress(it) }
            val value = message?.let { EIP712.smallUInt(it.get("value")) }
            val deadline = message?.let { EIP712.smallUInt(it.get("deadline")) }
            if (message == null || stringOf(message.get("owner"))?.lowercase() != owner.lowercase() ||
                spender == null || value == null || value <= 0 || value > 1_000_000_000_000L ||
                deadline == null || EIP712.smallUInt(message.get("nonce")) == null
            ) {
                throw EIP712.Failure("This permit is malformed.")
            }
            val now = nowMs / 1000.0
            if (deadline.toDouble() <= now || deadline.toDouble() > now + 86_400) {
                throw EIP712.Failure("K signs only a permit that ends within a day.")
            }
            return PermitRequest(value, spender, deadline * 1000, target, EIP712.digest(typed))
        }
    }
}

// MARK: - Following the order

/** One card purchase, from the page's order to the kUSDT on the picked address (iOS `CardBuy`). */
data class CardBuy(
    val session: String,
    val address: String,
    val label: String,
    val spendingIndex: Int?,
    /** ms since 1970. */
    val startedAtMs: Long,
    val stage: Stage = Stage.WAITING_PAYMENT,
    /** What was paid for, "50.00" (Stripe's amount). */
    val dollars: String? = null,
    /** kUSDT minted to the address, in units. */
    val mintedUnits: Long? = null,
    /** USDC sent back, in units. */
    val refundUnits: Long? = null,
    /** The page said Stripe took the payment. Until then (or Trestly says so) the order is dropped
     *  once Buy Kaspa closes: an order never paid isn't followed. */
    val paidSeen: Boolean? = null,
) {
    enum class Stage {
        /** Created on Trestly; Stripe hasn't been paid. */
        WAITING_PAYMENT,
        /** Paid; Stripe is delivering the USDC. */
        PAID,
        /** The bridge transaction is out; kUSDT is being minted on Kaspa. */
        BRIDGING,
        /** The kUSDT is on the address. */
        ARRIVED,
        /** The permit ran out before the bridge used it: the page asks for a new one. */
        SIGN_AGAIN,
        /** Stripe refused the purchase. */
        REJECTED,
        /** The bridge couldn't run, and the USDC went back to K's Ethereum address. */
        REFUNDED,
        /** Never paid, and the order is over. */
        EXPIRED,
    }

    val id: String get() = session

    val isFinished: Boolean get() = stage in FINISHED

    /** Still worth asking Trestly about: not over, and not stuck for a week (a paid order sitting
     *  in signAgain, bridging or paid polled on every beat forever; iOS d88891ea `isFollowed`,
     *  audit 2026-11-27 M4). The card keeps showing it, and opening it still goes to its page. */
    fun isFollowed(nowMs: Long = System.currentTimeMillis()): Boolean =
        !isFinished && nowMs - startedAtMs < FOLLOW_MAX_MS

    val target: TrestlyTarget get() = TrestlyTarget(label, address, spendingIndex)

    companion object {
        private val FINISHED = setOf(Stage.ARRIVED, Stage.REJECTED, Stage.REFUNDED, Stage.EXPIRED)
        /** A week. */
        const val FOLLOW_MAX_MS = 7 * 86_400_000L
    }
}

/**
 * Which poll loop owns [CardBuyTracker]'s slot (iOS d88891ea `loopToken`, audit 2026-11-27 M4):
 * each loop gets its own token, runs only while that token is current, and on finishing clears
 * the slot only if it is still its own, so a stopped loop finishing late never clears its
 * successor's.
 */
internal class PollLoopSlot {
    var token = 0
        private set
    var job: Job? = null
        private set

    /** A new loop: its token; [start] gives it the job. */
    fun next(): Int {
        token += 1
        return token
    }

    fun start(token: Int, job: Job) {
        if (token == this.token) this.job = job
    }

    fun isCurrent(token: Int): Boolean = token == this.token

    /** Running: a job that is this slot's and still active. */
    val isRunning: Boolean get() = job?.isActive == true

    /** The loop with [token] ended: the slot empties only if it is still that loop's. */
    fun finished(token: Int) {
        if (token == this.token) job = null
    }

    /** Ends the loop: cancelled, and its token no longer current. */
    fun stop() {
        job?.cancel()
        job = null
        token += 1
    }
}

/**
 * Follows the account's card purchase (one at a time) after Trestly's page closes, so the Buy
 * chooser shows where it is and Buy Kaspa with Debit Card goes on to Swap once the kUSDT lands
 * (iOS bc0e6ba9 / 824a30bb `CardBuyTracker`).
 *
 * Reads Trestly's own endpoints, as the buy page does: `/buy-api/order?session=` on the Trestly
 * site (Stripe's payment, the bridge transaction, a refund) and the Trestly API's
 * `/deposits/1/<nonce>` (chain 1 is Ethereum) for the minted kUSDT. Polls every 8 s while Buy
 * Kaspa is open, else on the foreground beat, and only while K is on screen (iOS's tasks stop
 * with the app); stops once the purchase is over. Kept per account
 * (`kachat_card_buy_v1_<account>`), removed with it (`AccountFeatureCleanup`).
 *
 * Its state is touched only on the main thread (iOS's @MainActor).
 */
@Singleton
class CardBuyTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val tokenWallet: TokenWallet,
    private val settings: AppSettingsRepository,
    private val notificationHelper: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var account: String? = currentAccount()
    private val _buy = MutableStateFlow(load(account))
    /** The order being followed (one at a time); null when there's none. */
    val buy: StateFlow<CardBuy?> = _buy.asStateFlow()

    /** Buy Kaspa is on screen: poll faster. When it closes, an order that wasn't paid is dropped
     *  (one last look first, in case the payment went through as it closed). */
    var screenOpen: Boolean = false
        set(value) {
            val old = field
            field = value
            if (value) pollSoon() else if (old) ensurePolling()
        }

    /** The one poll loop ([PollLoopSlot]). */
    private val loop = PollLoopSlot()
    /** The running loop's own wakeup: each run gets a new one, so a stopped loop's timer or a
     *  [pollSoon] meant for it never wakes the next (iOS d88891ea, audit 2026-11-27 M4). */
    private var wake: Channel<Unit>? = null

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // never from https down to http
        .followSslRedirects(false)
        .build()

    init {
        // the order is followed again whenever K comes back on screen or another account signs in
        // (iOS: the Wallet tab's `.task(id: currentWallet)`)
        scope.launch {
            combine(walletManager.signedInAddressFlow, notificationHelper.appForegroundFlow) { address, foreground ->
                address != null && foreground
            }.collect { active -> if (active) ensurePolling() }
        }
        // Log Out: nothing of the last account's purchase keeps running (iOS d88891ea
        // `signedOut`, audit 2026-11-27 M4)
        scope.launch {
            walletManager.signedInAddressFlow.collect { address -> if (address == null) signedOut() }
        }
    }

    private fun currentAccount(): String? =
        if (walletManager.loggedOutFlow.value) null else walletManager.getActiveAccount()?.address?.lowercase()

    /** The order followed now, for the signed-in account. */
    fun currentBuy(): CardBuy? {
        syncAccount()
        return _buy.value
    }

    /** The page saved an order (or marked it paid): follow it. */
    fun follow(session: String, target: TrestlyTarget, paid: Boolean) {
        syncAccount()
        when (followAction(_buy.value, session, paid)) {
            FollowAction.NOTHING, FollowAction.KEEP_CURRENT -> return
            FollowAction.MARK_PAID -> {
                val current = _buy.value ?: return
                val marked = current.copy(paidSeen = true)
                _buy.value = marked
                save()
                revealAddress(marked)
                return
            }
            FollowAction.START_NEW -> Unit
        }
        val started = CardBuy(
            session = session, address = target.address, label = target.label,
            spendingIndex = target.spendingIndex, startedAtMs = System.currentTimeMillis(),
            paidSeen = if (paid) true else null,
        )
        _buy.value = started
        save()
        if (paid) revealAddress(started)
        // the regular kUSDT look covers this address from now on
        tokenWallet.noteReceiveAddressShown(target.address)
        ensurePolling()
    }

    /** A paid order's fresh address goes on Manage Addresses' list (Buy Kaspa only derives it, so
     *  leaving without paying uses nothing up). Nothing for the Chatting Address or one already listed. */
    private fun revealAddress(buy: CardBuy) {
        val index = buy.spendingIndex ?: return
        scope.launch {
            try {
                withContext(Dispatchers.Default) { tokenWallet.revealFreshReceiveAddress(index) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a paid order's address wasn't listed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** What the followed order for [target] costs, in dollars, asking Trestly once if K doesn't
     *  know yet (the page can ask for the permit before the next poll). Only finite amounts up to
     *  the permit limit ([PermitRequest.knownDollars]; iOS 01f71184 `knownOrderDollars`, release
     *  audit 2026-11-27 R6, C6). */
    suspend fun knownOrderDollars(target: TrestlyTarget): Double? {
        syncAccount()
        val current = _buy.value ?: return null
        if (!current.isFollowed() || !current.address.equals(target.address, ignoreCase = true)) return null
        if (current.dollars == null) {
            try {
                poll(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the order's amount wasn't read: ${e.javaClass.simpleName}")
            }
        }
        val after = _buy.value ?: return null
        if (after.session != current.session) return null
        return PermitRequest.knownDollars(after.dollars)
    }

    /** Swap to Kaspa, or a finished order opened again: the purchase is no longer shown. */
    fun clear() {
        syncAccount()
        _buy.value = null
        save()
        stopLoop()
    }

    /** Ends the loop, its timer with it (iOS d88891ea `stopLoop`). */
    private fun stopLoop() {
        loop.stop()
        wake = null
    }

    /** Log Out: the loop stops and the last account's order leaves memory (kept in storage for
     *  when it signs in again). */
    fun signedOut() {
        stopLoop()
        account = null
        _buy.value = null
    }

    /** Starts following again (the Wallet tab shows, Buy Kaspa opened), if there's anything to follow. */
    fun ensurePolling() {
        syncAccount()
        val buy = _buy.value ?: return
        if (!buy.isFollowed()) return
        if (!loop.isRunning) {
            val token = loop.next()
            val channel = Channel<Unit>(Channel.CONFLATED)
            wake = channel
            loop.start(token, scope.launch { run(token, channel) })
        } else {
            pollSoon()
        }
    }

    private fun pollSoon() {
        wake?.trySend(Unit)
    }

    /** The loop ends with the order (or a week on); [ensurePolling] starts a new one when this one
     *  is no longer running. Only while [token] is current. */
    private suspend fun run(token: Int, wake: Channel<Unit>) {
        try {
            while (scope.isActive && loop.isCurrent(token)) {
                val current = _buy.value ?: return
                if (!current.isFollowed()) return
                poll(current)
                if (!loop.isCurrent(token)) return
                val after = _buy.value ?: return
                if (!after.isFollowed()) return
                waitForNext(wake)
            }
        } finally {
            loop.finished(token)
        }
    }

    /** 8 s while Buy Kaspa is open, else to the next foreground beat; [pollSoon] cuts it short.
     *  Never while K is off screen. */
    private suspend fun waitForNext(wake: Channel<Unit>) {
        val open = screenOpen
        // a wake from before this wait (the poll that just ran covered it) doesn't count
        wake.tryReceive()
        coroutineScope {
            val timer = launch {
                notificationHelper.appForegroundFlow.first { it }
                if (open) delay(OPEN_INTERVAL_MS) else ForegroundBeat.sleepUntilNextBeat(1_500L)
                wake.trySend(Unit)
            }
            wake.receive()
            timer.cancel()
        }
    }

    private suspend fun poll(current: CardBuy) {
        val order = fetchOrder(current.session) ?: return
        // a switch of account while the request ran: not this account's purchase any more
        if (_buy.value?.session != current.session) return
        val now = System.currentTimeMillis()
        var next: CardBuy
        when (val reading = readOrder(order, current, now)) {
            OrderReading.NotKnown -> {
                // Trestly doesn't know it (yet): nothing was paid, so with Buy Kaspa closed it's dropped
                if (!screenOpen && current.paidSeen != true) { clear(); return }
                next = current
                if (now - current.startedAtMs > 86_400_000L) next = next.copy(stage = CardBuy.Stage.EXPIRED)
                update(next)
                return
            }
            // the kUSDT goes where the order says; if that isn't the address picked here, K has
            // nothing to swap, and stops following it (the page shows its own progress)
            OrderReading.OtherAddress -> { clear(); return }
            is OrderReading.Known -> {
                next = reading.next
                if (reading.bridgedNonce != null) {
                    fetchMinted(reading.bridgedNonce)?.let { minted ->
                        next = next.copy(stage = CardBuy.Stage.ARRIVED, mintedUnits = minted)
                    }
                }
            }
        }
        if (_buy.value?.session != current.session) return
        // only a real payment counts: Stripe's status, the USDC delivered, the bridge, a refund
        if (next.stage in PAID_STAGES && next.paidSeen != true) {
            next = next.copy(paidSeen = true)
            revealAddress(next)
        }
        // never paid and Buy Kaspa closed: not followed any more, and nothing shows
        if (shouldDrop(next, screenOpen)) {
            clear()
            return
        }
        val arrivedNow = next.stage == CardBuy.Stage.ARRIVED && current.stage != CardBuy.Stage.ARRIVED
        update(next)
        if (arrivedNow) tokenWallet.launchDetached { tokenWallet.refresh() }
    }

    private fun update(next: CardBuy) {
        if (next == _buy.value) return
        _buy.value = next
        save()
    }

    private suspend fun fetchOrder(session: String): JsonObject? {
        val site = TrestlySite.siteUrl(context).trim().trimEnd('/')
        val url = "$site/buy-api/order".toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("session", session)?.build()
            ?: return null
        if (url.scheme != "https") return null
        return fetchJson(url)
    }

    /** The kUSDT the bridge minted for deposit [nonce], once Trestly says it's minted. */
    private suspend fun fetchMinted(nonce: Long): Long? {
        val base = try { settings.trestlyApiUrl.first().trim().trimEnd('/') } catch (e: Exception) { return null }
        val url = "$base/deposits/1/$nonce".toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        val deposit = fetchJson(url) ?: return null
        val status = deposit.get("status")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (status != "minted") return null
        return EIP712.smallUInt(deposit.get("minted"))
    }

    private suspend fun fetchJson(url: okhttp3.HttpUrl): JsonObject? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { response ->
                if (response.code != 200) return@use null
                val body = response.body ?: return@use null
                val bytes = body.byteStream().use { stream -> readCapped(stream, MAX_BODY_BYTES) } ?: return@use null
                TrestlyProvider.parseStrict(String(bytes, Charsets.UTF_8))?.takeIf { it.isJsonObject }?.asJsonObject
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() >= cap) return null
        }
        return out.toByteArray()
    }

    // MARK: Storage, per account

    private fun syncAccount() {
        val now = currentAccount()
        if (now == account) return
        account = now
        stopLoop()
        _buy.value = load(now)
    }

    private fun load(account: String?): CardBuy? {
        account ?: return null
        val raw = prefs.getString(STORAGE_PREFIX + account, null) ?: return null
        return decode(raw)
    }

    private fun save() {
        val account = account ?: return
        val buy = _buy.value
        if (buy != null) prefs.edit().putString(STORAGE_PREFIX + account, gson.toJson(buy)).apply()
        else prefs.edit().remove(STORAGE_PREFIX + account).apply()
    }

    /** What reading Trestly's order said. */
    internal sealed class OrderReading {
        /** Neither an order nor a purchase: Trestly doesn't know the session (yet). */
        object NotKnown : OrderReading()
        /** The order deposits to another Kaspa address than the one picked in K. */
        object OtherAddress : OrderReading()
        /** [next] with its stage; [bridgedNonce] when the bridge ran and the mint is to be looked up. */
        data class Known(val next: CardBuy, val bridgedNonce: Long?) : OrderReading()
    }

    internal enum class FollowAction { NOTHING, MARK_PAID, KEEP_CURRENT, START_NEW }

    companion object {
        private const val TAG = "CardBuy"
        const val PREFS_NAME = "kachat_card_buy"
        /** iOS `CardBuyTracker.storagePrefix`, then the account's address, lowercased. */
        const val STORAGE_PREFIX = "kachat_card_buy_v1_"
        private const val OPEN_INTERVAL_MS = 8_000L
        private const val MAX_BODY_BYTES = 256_000
        private val gson = Gson()

        private val PAID_STAGES = setOf(CardBuy.Stage.PAID, CardBuy.Stage.BRIDGING, CardBuy.Stage.ARRIVED, CardBuy.Stage.REFUNDED)
        private val DROPPED_UNPAID = setOf(CardBuy.Stage.WAITING_PAYMENT, CardBuy.Stage.SIGN_AGAIN, CardBuy.Stage.EXPIRED)

        /** A stored order, or null if it doesn't read as one (Gson leaves a missing field null). */
        @Suppress("SENSELESS_COMPARISON")
        internal fun decode(raw: String): CardBuy? = try {
            val buy = gson.fromJson(raw, CardBuy::class.java)
            if (buy == null || buy.session == null || buy.address == null || buy.label == null || buy.stage == null) null else buy
        } catch (e: Exception) {
            null
        }

        internal fun encode(buy: CardBuy): String = gson.toJson(buy)

        /**
         * What the page's report of [session] does to the order followed now (iOS `follow`): the
         * same session marks it paid once; a paid order still on its way isn't swapped for a new
         * one nobody has paid (audit 2026-11-26: its progress and its kUSDT-arrived shortcut would
         * be lost); anything else starts following the new one (one order at a time).
         */
        internal fun followAction(current: CardBuy?, session: String, paid: Boolean): FollowAction {
            if (current != null && current.session == session) {
                return if (paid && current.paidSeen != true) FollowAction.MARK_PAID else FollowAction.NOTHING
            }
            if (current != null && current.paidSeen == true && !current.isFinished && !paid) return FollowAction.KEEP_CURRENT
            return FollowAction.START_NEW
        }

        /** Never paid and Buy Kaspa closed: an order that isn't followed any more. */
        internal fun shouldDrop(next: CardBuy, screenOpen: Boolean): Boolean =
            next.paidSeen != true && !screenOpen && next.stage in DROPPED_UNPAID

        private fun objectOf(value: JsonElement?): JsonObject? = value?.takeIf { it.isJsonObject }?.asJsonObject

        private fun stringOf(value: JsonElement?): String? =
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null

        /**
         * Trestly's `/buy-api/order` answer for [current] (iOS `poll`): its stage, Stripe's
         * amount and a refund's size. A JSON null isn't a value: an order nobody paid has
         * `"delivered": null`, which read as delivered and showed "Paid" (iOS 6d1971c8).
         */
        internal fun readOrder(order: JsonObject, current: CardBuy, nowMs: Long): OrderReading {
            val orderObject = objectOf(order.get("order"))
            val purchase = objectOf(order.get("purchase"))
            if (orderObject == null && purchase == null) return OrderReading.NotKnown
            val kaspa = stringOf(orderObject?.get("kaspaAddress"))
            if (kaspa != null && kaspa.lowercase() != current.address.lowercase()) return OrderReading.OtherAddress
            var next = current
            if (purchase != null && purchase.has("amount")) next = next.copy(dollars = dollarsText(purchase.get("amount")))

            val state = stringOf(orderObject?.get("state"))
            val stripe = stringOf(purchase?.get("stripe"))
            val refund = objectOf(orderObject?.get("refund"))
            var nonce: Long? = null
            val stage = when {
                stripe == "rejected" -> CardBuy.Stage.REJECTED
                refund != null && stringOf(refund.get("state")) == "returned" -> {
                    next = next.copy(refundUnits = EIP712.smallUInt(refund.get("amount")))
                    CardBuy.Stage.REFUNDED
                }
                state != null && state in setOf("needs-sign", "expired", "replaced") -> CardBuy.Stage.SIGN_AGAIN
                state == "unpaid" && EIP712.smallUInt(orderObject?.get("deadline"))?.let { it.toDouble() < nowMs / 1000.0 } == true ->
                    CardBuy.Stage.EXPIRED
                state == "bridged" && EIP712.smallUInt(orderObject?.get("nonce")) != null -> {
                    nonce = EIP712.smallUInt(orderObject?.get("nonce"))
                    CardBuy.Stage.BRIDGING
                }
                stringOf(orderObject?.get("tx")) != null -> CardBuy.Stage.BRIDGING
                stripe == "fulfillment_complete" || stripe == "fulfillment_processing" || hasValue(purchase?.get("delivered")) ->
                    CardBuy.Stage.PAID
                else -> CardBuy.Stage.WAITING_PAYMENT
            }
            return OrderReading.Known(next.copy(stage = stage), nonce)
        }

        /** A field that's really there: missing and JSON null aren't, nor is an empty string. */
        internal fun hasValue(value: JsonElement?): Boolean {
            if (value == null || value.isJsonNull) return false
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString.isNotEmpty()
            return true
        }

        /** "50.00" from Stripe's amount (a string or a number). */
        internal fun dollarsText(value: JsonElement?): String? {
            if (value == null || !value.isJsonPrimitive) return null
            val primitive = value.asJsonPrimitive
            val number = when {
                primitive.isString -> primitive.asString.toDoubleOrNull()
                primitive.isNumber -> primitive.asDouble
                else -> null
            } ?: return null
            return String.format(Locale.US, "%.2f", number)
        }
    }
}
