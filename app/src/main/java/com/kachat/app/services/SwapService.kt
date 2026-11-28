package com.kachat.app.services

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import com.kachat.app.R
import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransaction
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TrestlyApproval
import com.kachat.app.services.tokens.TrestlyProvider
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.services.tokens.TrestlyTrade
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.util.KaspaUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Swap (iOS 518fdc8c `SwapService`, the Wallet tab's Swap button): KAS for kUSDT natively through
 * Trestly's pool on Kaspa ([TrestlyTrade], when kUSDT is the other coin), and KAS for coins on other
 * networks through ChangeNOW. Both open only after their third-party agreements ("Before You Swap").
 *
 * One app-wide service, as iOS's `SwapService.shared`: its choices survive closing Swap, and reset
 * on an account switch (iOS audit 2026-11-24 #5). Neither side of a ChangeNOW swap is sent
 * automatically: the person pays into ChangeNOW's deposit address themselves (shown as a QR code).
 *
 * Its state is Compose snapshot state, read by the Swap screen and changed only on the main thread.
 */
@Singleton
class SwapService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val trestlyTrade: TrestlyTrade,
    private val tokenWallet: com.kachat.app.services.tokens.TokenWallet,
    private val nodePoolManager: NodePoolManager,
    /** Refresh Status on a kUSDT swap asks the network whether its transaction was accepted
     *  (iOS `KachatNamesRegistry.isAccepted`). Lazy: only a Refresh reads it. */
    private val namesRegistry: dagger.Lazy<com.kachat.app.services.kachatnames.KachatNamesRegistry>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    enum class AmountSide { SEND, GET }
    enum class EstimateStatus { IDLE, LOADING, SUCCESS, FAILED }

    data class EstimateUiState(
        val status: EstimateStatus = EstimateStatus.IDLE,
        /** Both sides of the quote once it succeeds: the typed one echoed back, the other the answer. */
        val fromAmount: Double? = null,
        val toAmount: Double? = null,
        val errorMessage: String? = null,
    )

    enum class CreateSwapStatus { IDLE, CREATING, SUCCESS, FAILED }

    /** The exchange ChangeNOW created, with the coins and amount it was made for: the deposit card
     *  is drawn from this, never from the coins picked now (iOS audit 2026-11-24 #2). */
    data class CreatedSwap(val transaction: SwapTransaction, val fromCoin: SwapCoin, val toCoin: SwapCoin)

    data class CreateSwapUiState(
        val status: CreateSwapStatus = CreateSwapStatus.IDLE,
        val result: CreatedSwap? = null,
        val errorMessage: String? = null,
    )

    /** The account (lowercased chatting address) whose history and agreements are loaded. */
    private var account: String? = null

    var history by mutableStateOf<List<SwapTransaction>>(emptyList())
        private set

    /** True: KAS is what you send. KAS is always one side; only which side flips. Starts false:
     *  most people opening Swap want KAS. */
    var kasIsSendSide by mutableStateOf(false)
        private set
    var otherCoin by mutableStateOf(SwapCoin.USDC_POLYGON)
        private set
    /** "You Send": typed when [editedSide] is SEND, else the reverse quote's answer. */
    var amountText by mutableStateOf("")
        private set
    /** "You Get": typed when [editedSide] is GET, else the direct quote's answer. */
    var receiveAmountText by mutableStateOf("")
        private set
    var editedSide by mutableStateOf(AmountSide.SEND)
        private set
    /** Where ChangeNOW delivers the "to" coin, when that coin isn't KAS. */
    var payoutAddressText by mutableStateOf("")
    /** The payout's tag (XRP's destination tag), sent as ChangeNOW's `extraId` (iOS audit #1). */
    var payoutExtraIdText by mutableStateOf("")

    /** Where swap-received KAS lands: null previews the next never-used spending address (reserved
     *  only when the swap is made); an index reuses that address. */
    var toAddressOverrideIndex by mutableStateOf<Int?>(null)
        private set
    /** Bought Kaspa lands on the Chatting Address (picked on Buy's "where" step, iOS 824a30bb
     *  `toChatting`). */
    var toChatting by mutableStateOf(false)
        private set
    var toAddress by mutableStateOf("")
        private set

    var estimateState by mutableStateOf(EstimateUiState())
        private set
    var createSwapState by mutableStateOf(CreateSwapUiState())
        private set

    private var estimateJob: Job? = null

    // kUSDT (Trestly)

    /** The address a kUSDT swap trades from: KAS leaves it and kUSDT lands on it (or the other way
     *  round). Null is the Chatting Address. Only plain (Schnorr) addresses. */
    var kusdtFrom by mutableStateOf<TrestlyTarget?>(null)
        private set
    /** The pool's quote for what was typed, in the received side's base units. */
    var kusdtQuote by mutableStateOf<Long?>(null)
        private set
    /** The trade waiting for Approve or Decline, shown as the approval sheet. */
    var kusdtApproval by mutableStateOf<TrestlyApproval?>(null)
    /** "Swapped 10 KAS for 0.26 kUSDT", for the screen's toast; cleared once shown. */
    var notice by mutableStateOf<String?>(null)

    /** Bumped when the agreements are accepted or the account changes ([agreementsAccepted]). */
    private var agreementsRevision by mutableIntStateOf(0)

    val isKusdt: Boolean get() = otherCoin.isTrestly
    val fromCoin: SwapCoin get() = if (kasIsSendSide) SwapCoin.KAS else otherCoin
    val toCoin: SwapCoin get() = if (kasIsSendSide) otherCoin else SwapCoin.KAS

    /** The kUSDT swap's address, the Chatting Address unless another was picked. */
    val kusdtTarget: TrestlyTarget?
        get() {
            kusdtFrom?.let { return it }
            val chatting = walletManager.activeAddressFlow.value ?: return null
            return TrestlyTarget(context.getString(R.string.chatting_address), chatting, null)
        }

    /** The typed "You Send" in base units: sompi for a buy, kUSDT units for a sell. */
    private val kusdtAmountIn: Long?
        get() {
            val units = if (kasIsSendSide) KaspaUnit.sompiFromUserText(amountText)
            else TokenAmount.unitsFromTyped(KaspaUnit.sanitizeAmountInput(amountText), WalletToken.KUSDT)
            return units?.takeIf { it > 0 }
        }

    init {
        account = currentAccount()
        history = loadHistory(account)
        refreshToAddress()
        scope.launch {
            walletManager.activeAddressFlow
                .map { it?.lowercase() }
                .distinctUntilChanged()
                .drop(1)
                .collect { accountChanged() }
        }
    }

    private fun currentAccount(): String? = walletManager.activeAddressFlow.value?.lowercase()

    /** Another account (or none): nothing of the last one's swap stays on screen or goes into the
     *  next one's requests (iOS audit 2026-11-24 #5). */
    private fun accountChanged() {
        val now = currentAccount()
        if (now == account) return
        account = now
        history = loadHistory(now)
        estimateJob?.cancel()
        kusdtFrom = null
        kusdtQuote = null
        toAddressOverrideIndex = null
        toChatting = false
        payoutAddressText = ""
        payoutExtraIdText = ""
        amountText = ""
        receiveAmountText = ""
        editedSide = AmountSide.SEND
        estimateState = EstimateUiState()
        createSwapState = CreateSwapUiState()
        refreshToAddress()
        agreementsRevision += 1
    }

    // Available and Max (kUSDT swaps, iOS 6b99c6dd)

    /** The Swap From address's spendable KAS, for "Available" and Max on a kUSDT swap. Null until
     *  read ([refreshKusdtBalances]). */
    var kusdtFromKasSompi by mutableStateOf<Long?>(null)
        private set
    private var balanceJob: Job? = null

    /** Every address's kUSDT, for [kusdtFromUnits]; the screen collects it so a change redraws. */
    val tokenHoldings get() = tokenWallet.holdingsFlow

    /** The Swap From address's kUSDT, in units. */
    val kusdtFromUnits: Long
        get() {
            val address = kusdtTarget?.address?.lowercase() ?: return 0
            return tokenWallet.holdings(WalletToken.KUSDT).filter { it.address.lowercase() == address }.sumOf { it.units }
        }

    /** The most "You Send" can be on a kUSDT swap: the address's kUSDT on a sell, its KAS less what
     *  a buy keeps back ([kasKeptForBuy]). Null when it isn't known (or this isn't a kUSDT swap). */
    val kusdtMaxSend: Long?
        get() {
            if (!isKusdt) return null
            if (kasIsSendSide) {
                val kas = kusdtFromKasSompi ?: return null
                return if (kas > kasKeptForBuy) kas - kasKeptForBuy else 0
            }
            return kusdtFromUnits
        }

    /** Reads the Swap From address's KAS (its plain coins; kUSDT coins live at the token's own
     *  script address, so they aren't counted). */
    fun refreshKusdtBalances() {
        balanceJob?.cancel()
        val address = kusdtTarget?.address
        if (!isKusdt || address == null) {
            kusdtFromKasSompi = null
            return
        }
        balanceJob = scope.launch {
            val utxos = withContext(Dispatchers.IO) { nodePoolManager.getUtxosByAddress(address) } ?: return@launch
            if (kusdtTarget?.address != address) return@launch
            kusdtFromKasSompi = utxos.sumOf { it.utxoEntry.amount }
        }
    }

    /** Max: "You Send" becomes all that can go. */
    fun setMaxAmount() {
        val max = kusdtMaxSend?.takeIf { it > 0 } ?: return
        if (kasIsSendSide) {
            val whole = max / 100_000_000L
            val frac = String.format(Locale.US, "%08d", max % 100_000_000L).trimEnd('0')
            typeAmount(if (frac.isEmpty()) "$whole" else "$whole.$frac")
        } else {
            typeAmount(TokenAmount.typed(max, WalletToken.KUSDT))
        }
    }

    // Coin selection / direction

    fun flipDirection() {
        kasIsSendSide = !kasIsSendSide
        clearCreatedSwap()
        clearQuotedAmount()
        rescheduleEstimate()
    }

    fun chooseOtherCoin(coin: SwapCoin) {
        if (coin != otherCoin) {
            clearCreatedSwap()
            payoutExtraIdText = ""
        }
        otherCoin = coin
        // Trestly's pool quotes from what you send, so a typed "You Get" moves to "You Send"
        if (coin.isTrestly && editedSide == AmountSide.GET) {
            amountText = ""
            editedSide = AmountSide.SEND
        }
        clearQuotedAmount()
        rescheduleEstimate()
    }

    /** Swap from another address (kUSDT): the quote is that address's to make again (iOS
     *  `setKusdtFrom`). */
    fun chooseKusdtFrom(target: TrestlyTarget?) {
        kusdtFrom = target
        rescheduleEstimate()
    }

    /**
     * Buy Kaspa with Debit Card's last step (iOS bc0e6ba9 `prepareKusdtSell(from:units:)`): kUSDT
     * for KAS, from the address the card's kUSDT landed on, "You Pay" filled with what landed. Open
     * Swap at [com.kachat.app.ui.screens.tokens.SwapStart.FORM] after it.
     */
    fun prepareKusdtSell(from: TrestlyTarget, units: Long) {
        clearCreatedSwap()
        kasIsSendSide = false
        chooseOtherCoin(SwapCoin.KUSDT)
        kusdtFrom = from
        typeAmount(TokenAmount.typed(units, WalletToken.KUSDT))
        refreshKusdtBalances()
    }

    private fun clearQuotedAmount() {
        when (editedSide) {
            AmountSide.SEND -> receiveAmountText = ""
            AmountSide.GET -> amountText = ""
        }
    }

    /** "You Send" typed. Cleaned as every amount field is: "1,5" is 1.5, never 15 (iOS audit #3). */
    fun typeAmount(text: String) {
        amountText = KaspaUnit.sanitizeAmountInput(text)
        editedSide = AmountSide.SEND
        receiveAmountText = ""
        rescheduleEstimate()
    }

    /** "You Get" typed: the reverse quote will fill "You Send" with what that costs. */
    fun typeReceiveAmount(text: String) {
        receiveAmountText = KaspaUnit.sanitizeAmountInput(text)
        editedSide = AmountSide.GET
        amountText = ""
        rescheduleEstimate()
    }

    private val editedAmountText: String get() = if (editedSide == AmountSide.SEND) amountText else receiveAmountText

    // To spending address

    fun selectToSpendingAddress(index: Int) {
        toAddressOverrideIndex = index
        toChatting = false
        refreshToAddress()
    }

    /** Bought Kaspa lands on a fresh address (the default, iOS 824a30bb `useFreshToAddress`). */
    fun useFreshToAddress() {
        toAddressOverrideIndex = null
        toChatting = false
        refreshToAddress()
    }

    /** Bought Kaspa lands on the Chatting Address (iOS 824a30bb `useChattingToAddress`). */
    fun useChattingToAddress() {
        toAddressOverrideIndex = null
        toChatting = true
        refreshToAddress()
    }

    /** The legacy slot the payout will take: one past the legacy chain. */
    private fun nextFreshSpendingIndex(): Int? =
        walletManager.getActiveAccount()?.let { WalletManager.legacyMaxSpendingIndex(it) + 1 }

    fun refreshToAddress() {
        if (toChatting) {
            toAddress = walletManager.activeAddressFlow.value ?: ""
            return
        }
        val override = toAddressOverrideIndex
        scope.launch {
            val address = withContext(Dispatchers.Default) {
                val index = override ?: nextFreshSpendingIndex() ?: return@withContext ""
                try { walletManager.deriveSpendingAddress(index) } catch (e: Exception) { "" }
            }
            // still the choice it was worked out for (not the Chatting Address meanwhile)
            if (!toChatting && override == toAddressOverrideIndex) toAddress = address
        }
    }

    // Live quote

    /** Debounced live quote: re-fires on every relevant change rather than a "Get Rate" tap. */
    private fun rescheduleEstimate() {
        estimateJob?.cancel()
        kusdtQuote = null
        if (isKusdt) {
            rescheduleKusdtEstimate()
            return
        }
        val amount = decimalValue(editedAmountText)
        if (amount == null || amount <= 0) {
            estimateState = EstimateUiState()
            return
        }
        val from = fromCoin
        val to = toCoin
        val side = editedSide
        val amountStr = KaspaUnit.sanitizeAmountInput(editedAmountText)
        estimateJob = scope.launch {
            delay(ESTIMATE_DEBOUNCE_MS)
            estimateState = EstimateUiState(status = EstimateStatus.LOADING)
            try {
                // Direct quote answers "You Get" for a typed "You Send"; the reverse quote answers
                // "You Send" for a typed "You Get". Both figures are ChangeNOW's.
                val response = when (side) {
                    AmountSide.SEND -> ChangeNowApiClient.getEstimatedAmount(from.ticker, from.network, to.ticker, to.network, amountStr)
                    AmountSide.GET -> reverseQuote(from, to, amount)
                }
                when (side) {
                    AmountSide.SEND -> receiveAmountText = formatQuotedAmount(response.toAmount)
                    AmountSide.GET -> amountText = formatQuotedAmount(response.fromAmount)
                }
                estimateState = EstimateUiState(EstimateStatus.SUCCESS, response.fromAmount, response.toAmount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                estimateState = EstimateUiState(status = EstimateStatus.FAILED, errorMessage = e.message)
            }
        }
    }

    /** kUSDT: Trestly's `prepare` for what was typed (nothing signed), with no minimum, so the pool's
     *  own quote lands in "You Get". A paused pool or its circuit breaker says so instead. */
    private fun rescheduleKusdtEstimate() {
        val amountIn = kusdtAmountIn
        val target = kusdtTarget
        if (amountIn == null || target == null) {
            receiveAmountText = ""
            estimateState = EstimateUiState()
            return
        }
        val buying = kasIsSendSide
        estimateJob = scope.launch {
            delay(ESTIMATE_DEBOUNCE_MS)
            estimateState = EstimateUiState(status = EstimateStatus.LOADING)
            try {
                val prepared = trestlyTrade.prepare("kusdt_swap", swapParams(buying, amountIn, 0L), target)
                val quote = TrestlyApproval.number(prepared.get("quote"))
                if (quote == null || quote <= 0) {
                    throw TrestlyProvider.ProviderError(TrestlyProvider.Code.FAILED, context.getString(R.string.swap_pool_gives_nothing))
                }
                val paused = prepared.get("paused")
                if (paused != null && paused.isJsonPrimitive && paused.asJsonPrimitive.isBoolean && paused.asBoolean) {
                    throw TrestlyProvider.ProviderError(TrestlyProvider.Code.FAILED, context.getString(R.string.swap_pool_paused))
                }
                val breaker = prepared.get("breaker")
                if (breaker != null && breaker.isJsonPrimitive && breaker.asJsonPrimitive.isString) {
                    throw TrestlyProvider.ProviderError(TrestlyProvider.Code.FAILED, context.getString(R.string.swap_pool_breaker))
                }
                kusdtQuote = quote
                receiveAmountText = if (buying) TokenAmount.typed(quote, WalletToken.KUSDT) else KaspaUnit.plain(quote)
                // the rate line works on whole coins
                val sentWhole = if (buying) amountIn / 100_000_000.0 else amountIn / 1_000_000.0
                val gotWhole = if (buying) quote / 1_000_000.0 else quote / 100_000_000.0
                estimateState = EstimateUiState(EstimateStatus.SUCCESS, sentWhole, gotWhole)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                receiveAmountText = ""
                estimateState = EstimateUiState(status = EstimateStatus.FAILED, errorMessage = e.message)
            }
        }
    }

    private fun swapParams(buying: Boolean, amountIn: Long, minOut: Long) = JsonObject().apply {
        addProperty("side", if (buying) "buy" else "sell")
        addProperty("amountIn", amountIn.toString())
        addProperty("minOut", minOut.toString())
    }

    // Reverse quote on the standard flow

    /** A standard-flow quote is a straight line in the send amount (`to = slope × from +
     *  intercept`); two direct quotes pin it for a pair, cached for a minute. */
    private data class LinearQuote(val slope: Double, val intercept: Double, val minFrom: Double, val fetchedAt: Long) {
        fun from(target: Double): Double = (target - intercept) / slope
        fun to(from: Double): Double = slope * from + intercept
    }

    private val linearQuoteCache = HashMap<String, LinearQuote>()

    private suspend fun linearQuote(from: SwapCoin, to: SwapCoin): LinearQuote {
        val key = "${from.ticker}/${from.network}>${to.ticker}/${to.network}"
        linearQuoteCache[key]?.let { if (System.currentTimeMillis() - it.fetchedAt < LINEAR_QUOTE_TTL_MS) return it }
        val minFrom = ChangeNowApiClient.getMinAmount(from.ticker, from.network, to.ticker, to.network)
        // Two probes well inside the accepted range, a decade apart so fee rounding can't tilt it.
        val (low, high) = coroutineScope {
            val low = async { ChangeNowApiClient.getEstimatedAmount(from.ticker, from.network, to.ticker, to.network, formatQuotedAmount(minFrom * 2)) }
            val high = async { ChangeNowApiClient.getEstimatedAmount(from.ticker, from.network, to.ticker, to.network, formatQuotedAmount(minFrom * 20)) }
            low.await() to high.await()
        }
        val slope = (high.toAmount - low.toAmount) / (high.fromAmount - low.fromAmount)
        if (!(slope > 0) || !slope.isFinite()) throw Exception(context.getString(R.string.swap_unusable_rate))
        val model = LinearQuote(slope, low.toAmount - slope * low.fromAmount, minFrom, System.currentTimeMillis())
        linearQuoteCache[key] = model
        return model
    }

    /** What to send to receive [target]: solve the pair's line, then confirm with a real direct
     *  quote; one refinement if it is off by more than 0.2%. Rounded up at the eighth place. */
    private suspend fun reverseQuote(from: SwapCoin, to: SwapCoin, target: Double): ChangeNowEstimate {
        val model = linearQuote(from, to)
        var sendAmount = roundUp8(model.from(target))
        if (sendAmount < model.minFrom) {
            val minTo = model.to(model.minFrom)
            throw Exception(context.getString(R.string.swap_least_you_can_get, formatQuotedAmount(minTo), to.displayName))
        }
        var confirmed = ChangeNowApiClient.getEstimatedAmount(from.ticker, from.network, to.ticker, to.network, formatQuotedAmount(sendAmount))
        if (abs(confirmed.toAmount - target) / target > 0.002) {
            sendAmount += (target - confirmed.toAmount) / model.slope
            sendAmount = maxOf(model.minFrom, roundUp8(sendAmount))
            confirmed = ChangeNowApiClient.getEstimatedAmount(from.ticker, from.network, to.ticker, to.network, formatQuotedAmount(sendAmount))
        }
        return confirmed
    }

    // Execute swap

    fun executeSwap() {
        if (isKusdt) {
            executeKusdtSwap()
            return
        }
        // The exchange is always made from the send amount: typed, or the reverse quote's answer.
        val amountStr = KaspaUnit.sanitizeAmountInput(amountText)
        val amount = decimalValue(amountStr)?.takeIf { it > 0 } ?: return
        val from = fromCoin
        val to = toCoin
        val startedOn = account
        // the payout's tag, for a coin that may need one (iOS audit 2026-11-24 #1)
        val extraId = if (to.needsMemo) payoutExtraIdText.trim().takeIf { it.isNotEmpty() } else null
        if (extraId != null && !to.isValidMemo(extraId)) {
            createSwapState = CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = context.getString(R.string.swap_invalid_destination_tag))
            return
        }
        createSwapState = CreateSwapUiState(CreateSwapStatus.CREATING)
        val overrideIndex = toAddressOverrideIndex
        val landOnChatting = toChatting
        val chattingAddress = walletManager.activeAddressFlow.value
        val typedPayout = payoutAddressText.trim()

        scope.launch {
            // Into KAS: a fresh, never-used spending address by default (so exchange-received coins
            // can't be chain-linked to everyday spending), unless one was picked. Out of KAS: the
            // address typed for the other coin.
            val payoutAddress = if (to.ticker == "kas" && landOnChatting) {
                // picked on Buy's "where" step (iOS 824a30bb)
                chattingAddress ?: ""
            } else if (to.ticker == "kas") {
                withContext(Dispatchers.Default) {
                    try {
                        val index = overrideIndex ?: walletService.generateNextSpendingAddress()
                        walletManager.deriveSpendingAddress(index)
                    } catch (e: Exception) {
                        ""
                    }
                }
            } else {
                typedPayout
            }

            // the account changed while the address was found: it may be the other account's
            if (startedOn != account) {
                createSwapState = CreateSwapUiState()
                return@launch
            }
            if (payoutAddress.isEmpty()) {
                createSwapState = CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = context.getString(R.string.swap_enter_payout_address, to.displayName))
                return@launch
            }

            try {
                val response = ChangeNowApiClient.createTransaction(
                    from.ticker, from.network, to.ticker, to.network, amountStr, payoutAddress, extraId,
                )
                val payinAddress = response.payinAddress?.trim().orEmpty()
                if (payinAddress.isEmpty()) {
                    createSwapState = CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = context.getString(R.string.swap_no_deposit_address))
                    return@launch
                }
                // ChangeNOW's answer must be the exchange asked for (iOS audit 2026-11-24 #6)
                if (!answerMatches(response, from, to, amount, payoutAddress, extraId)) {
                    createSwapState = CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = context.getString(R.string.swap_answer_mismatch))
                    return@launch
                }
                val payinExtraId = response.payinExtraId?.trim()?.takeIf { it.isNotEmpty() }
                val transaction = SwapTransaction(
                    id = response.id,
                    fromTicker = from.ticker,
                    fromNetwork = from.network,
                    toTicker = to.ticker,
                    toNetwork = to.network,
                    fromAmount = amountStr,
                    toAmount = response.toAmount?.let { formatQuotedAmount(it) } ?: "",
                    payinAddress = payinAddress,
                    payoutAddress = payoutAddress,
                    status = response.status ?: "new",
                    createdAtMillis = System.currentTimeMillis(),
                    payinExtraId = payinExtraId,
                    payoutExtraId = extraId,
                )
                // filed under the account it was made on, even if that changed meanwhile
                if (startedOn != account) {
                    appendToStoredHistory(transaction, startedOn)
                    createSwapState = CreateSwapUiState()
                    return@launch
                }
                history = listOf(transaction) + history
                saveHistory()
                createSwapState = CreateSwapUiState(CreateSwapStatus.SUCCESS, CreatedSwap(transaction, from, to))
                amountText = ""
                receiveAmountText = ""
                editedSide = AmountSide.SEND
                estimateState = EstimateUiState()
                toAddressOverrideIndex = null
                toChatting = false
                refreshToAddress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                createSwapState = if (startedOn != account) CreateSwapUiState()
                else CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = e.message)
            }
        }
    }

    /** The deposit card goes once the coin, direction or account changes (iOS audit #2). */
    private fun clearCreatedSwap() {
        if (createSwapState.status == CreateSwapStatus.CREATING) return
        createSwapState = CreateSwapUiState()
    }

    /** kUSDT: Trestly's code prepares the swap with a minimum 1% under the quote, K shows it in the
     *  approval sheet, and only on Approve signs with the address's key and sends. */
    private fun executeKusdtSwap() {
        val amountIn = kusdtAmountIn ?: return
        val quote = kusdtQuote ?: return
        val target = kusdtTarget ?: return
        val buying = kasIsSendSide
        createSwapState = CreateSwapUiState(CreateSwapStatus.CREATING)
        scope.launch {
            try {
                val done = trestlyTrade.run(
                    method = "kusdt_swap",
                    params = swapParams(buying, amountIn, minimumOut(quote)),
                    target = target,
                    native = true,
                    ask = { askKusdt(it) },
                )
                notice = trestlyTrade.recordSwap(done, target) ?: context.getString(R.string.swap_sent)
                createSwapState = CreateSwapUiState()
                amountText = ""
                receiveAmountText = ""
                kusdtQuote = null
                estimateState = EstimateUiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrestlyProvider.ProviderError) {
                createSwapState = if (e.code == TrestlyProvider.Code.REJECTED) CreateSwapUiState()
                else CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = e.message)
            } catch (e: Exception) {
                createSwapState = CreateSwapUiState(CreateSwapStatus.FAILED, errorMessage = e.message)
            }
        }
    }

    /** Shows the approval and waits for Approve or Decline (closing the sheet declines). */
    private suspend fun askKusdt(request: TrestlyApproval): Boolean {
        kusdtApproval = request
        try {
            return request.await()
        } finally {
            if (kusdtApproval === request) kusdtApproval = null
        }
    }

    /**
     * A kUSDT swap through Trestly's pool, into Buy/Sell History (iOS b49a021d `recordKusdtSwap`,
     * owner 2026-11-27). Amounts as people type them ("10", "0.26"); "finished" once the network
     * accepted it, "sending" while it may still land (Refresh Status checks the network, not
     * ChangeNOW).
     */
    fun recordKusdtSwap(
        txid: String, bought: Boolean, amountIn: Long, amountOut: Long, address: String, confirmed: Boolean,
        /** The account signed in when the trade started ([TrestlyTrade.Done.account]). */
        forAccount: String? = null,
    ) {
        val owner = (forAccount ?: account)?.lowercase() ?: return
        val swap = kusdtSwapTransaction(txid, bought, amountIn, amountOut, address, confirmed, System.currentTimeMillis())
        // Accounts switched while the trade ran: it goes into the history of the account that made
        // it, never the one now signed in (iOS d88891ea, audit 2026-11-27 L3).
        if (owner != account) {
            if (loadHistory(owner).any { it.id == txid }) return
            appendToStoredHistory(swap, owner)
            return
        }
        if (history.any { it.id == txid }) return
        history = listOf(swap) + history
        saveHistory()
    }

    /** K sent the deposit for this swap ("Send KAS from K"): kept on the swap, so the deposit
     *  screen stops offering a second payment (iOS d88891ea `recordDepositSent`, audit 2026-11-27
     *  M1). An empty [txId] (a send cancelled mid-submit, which may still land) counts too. */
    fun recordDepositSent(swapId: String, txId: String) {
        if (history.none { it.id == swapId }) return
        history = history.map { if (it.id == swapId) it.copy(kasSendTxId = txId) else it }
        saveHistory()
    }

    /** For the detail page's Refresh: the fresh status on success (history updated either way).
     *  A kUSDT swap asks the network whether its transaction was accepted. */
    suspend fun refreshSwapStatusNow(id: String): String? {
        history.firstOrNull { it.id == id }?.takeIf { it.isTrestly }?.let { swap ->
            if (!namesRegistry.get().isAccepted(id)) return swap.status
            if (history.any { it.id == id }) {
                history = history.map { if (it.id == id) it.copy(status = "finished") else it }
                saveHistory()
            }
            return "finished"
        }
        val status = try {
            ChangeNowApiClient.getTransactionStatus(id).status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        // only a change is written: each write re-saved the whole history, every 10 s on the
        // deposit screen (iOS d88891ea, audit 2026-11-27 L18)
        if (history.any { it.id == id && it.status != status }) {
            history = history.map { if (it.id == id) it.copy(status = status) else it }
            saveHistory()
        }
        return status
    }

    fun deleteSwap(id: String) {
        history = history.filterNot { it.id == id }
        saveHistory()
    }

    // Add to Portfolio

    fun markAddedToPortfolio(swapId: String) {
        if (history.none { it.id == swapId }) return
        history = history.map { if (it.id == swapId) it.copy(addedToPortfolio = true) else it }
        saveHistory()
    }

    // Agreements

    /** Swap opens only once both third-party agreements are accepted on this account. */
    val agreementsAccepted: Boolean
        get() {
            @Suppress("UNUSED_VARIABLE") val revision = agreementsRevision
            val key = currentAccount()?.let { agreementsStorageKey(AGREEMENTS_VERSION, it) } ?: return false
            return prefs.getBoolean(key, false)
        }

    fun acceptAgreements() {
        val key = currentAccount()?.let { agreementsStorageKey(AGREEMENTS_VERSION, it) } ?: return
        prefs.edit().putBoolean(key, true).apply()
        agreementsRevision += 1
    }

    // History persistence

    private fun loadHistory(account: String?): List<SwapTransaction> {
        account ?: return emptyList()
        return SwapTransaction.listFromJson(prefs.getString(historyKey(account), null))
    }

    private fun saveHistory() {
        history = history.sortedByDescending { it.createdAtMillis }
        val account = account ?: return
        prefs.edit().putString(historyKey(account), SwapTransaction.listToJson(history)).apply()
    }

    /** An exchange made for an account that is no longer the open one: into its stored list. */
    private fun appendToStoredHistory(transaction: SwapTransaction, account: String?) {
        account ?: return
        val list = (listOf(transaction) + loadHistory(account)).sortedByDescending { it.createdAtMillis }
        prefs.edit().putString(historyKey(account), SwapTransaction.listToJson(list)).apply()
    }

    data class PortfolioPrefill(
        /** "buy" | "sell", PortfolioTransactionEntity's types. */
        val type: String,
        val amountKas: Double,
        val fiatValue: Double,
        val timestampMillis: Long,
        val notes: String,
    )

    companion object {
        /** What a KAS-for-kUSDT buy keeps back, exactly as Trestly's pool works out its own max
         *  (`maxKasIn` in kusdt-wallet.js): the KAS locked in the new kUSDT coin (1.5 KAS) and
         *  `FEE_KEEP` (0.3 KAS) for the network fee. Less, and the pool refuses a Max buy. */
        const val FEE_KEPT_FOR_BUY = 30_000_000L
        val kasKeptForBuy: Long get() = WalletToken.KUSDT.kasPerCoinSompi + FEE_KEPT_FOR_BUY

        private const val PREFS = "kachat_swap"
        private const val ESTIMATE_DEBOUNCE_MS = 500L
        private const val LINEAR_QUOTE_TTL_MS = 60_000L

        /** Bump when the agreements' text changes: everyone is asked again. */
        const val AGREEMENTS_VERSION = 1

        fun historyKey(account: String): String = "kachat_swap_history_$account"
        fun agreementsStorageKey(version: Int, account: String): String = "kachat_swap_agreements_v${version}_$account"

        /** 1% under the quote: the least the swap goes ahead for, if the pool moves before it lands. */
        fun minimumOut(quote: Long): Long = quote - quote / 100

        /** A typed amount as a number, read the way the field cleans it ("1,5" is 1.5). */
        fun decimalValue(text: String): Double? = KaspaUnit.sanitizeAmountInput(text).toDoubleOrNull()

        /** Plain decimal text for a quoted amount: up to 8 places, trailing zeros trimmed, never
         *  scientific notation (it goes back to ChangeNOW as a `fromAmount`). */
        fun formatQuotedAmount(value: Double): String {
            var text = String.format(Locale.US, "%.8f", value)
            if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
            return text
        }

        /** Rounded up at the eighth decimal place. */
        fun roundUp8(value: Double): Double = ceil(value * 100_000_000) / 100_000_000

        /**
         * Whether ChangeNOW made the exchange K asked for (iOS audit 2026-11-24 #6): the same payout
         * address (letter case aside), the same coins and networks where it names them, a send amount
         * within 1% of the one asked, and the payout tag if it echoes one. The payout address must be
         * in the answer.
         */
        fun answerMatches(
            response: ChangeNowTransaction, from: SwapCoin, to: SwapCoin,
            amount: Double, payoutAddress: String, extraId: String?,
        ): Boolean {
            fun same(answered: String?, asked: String): Boolean {
                val a = answered?.trim()?.takeIf { it.isNotEmpty() } ?: return true
                return a.equals(asked, ignoreCase = true)
            }
            val payout = response.payoutAddress?.trim() ?: return false
            if (!payout.equals(payoutAddress, ignoreCase = true)) return false
            if (!same(response.fromCurrency, from.ticker) || !same(response.toCurrency, to.ticker) ||
                !same(response.fromNetwork, from.network) || !same(response.toNetwork, to.network)
            ) return false
            response.fromAmount?.let { sent -> if (!(abs(sent - amount) <= amount * 0.01)) return false }
            if (extraId != null) {
                val echoed = response.payoutExtraId
                if (!echoed.isNullOrEmpty() && echoed != extraId) return false
            }
            return true
        }

        /** What a finished swap adds to Portfolio (iOS `portfolioPrefill`): a buy when KAS came in,
         *  a sell when it went out; null when the amounts don't read as numbers. */
        fun portfolioPrefill(swap: SwapTransaction): PortfolioPrefill? {
            val isKasReceived = swap.toTicker == "kas"
            val amountKas = (if (isKasReceived) swap.toAmount else swap.fromAmount).toDoubleOrNull() ?: return null
            val fiatValue = (if (isKasReceived) swap.fromAmount else swap.toAmount).toDoubleOrNull() ?: return null
            return PortfolioPrefill(
                type = if (isKasReceived) "buy" else "sell",
                amountKas = amountKas,
                fiatValue = fiatValue,
                timestampMillis = swap.createdAtMillis,
                notes = if (swap.isTrestly) "kUSDT swap (Trestly) ${swap.id}" else "ChangeNOW swap ${swap.id}",
            )
        }

        /** What [recordKusdtSwap] files: a [SwapTransaction] from Trestly ([SwapTransaction.TRESTLY]),
         *  its Kaspa txid as the id, both addresses the one it traded from. */
        fun kusdtSwapTransaction(
            txid: String, bought: Boolean, amountIn: Long, amountOut: Long,
            address: String, confirmed: Boolean, createdAtMillis: Long,
        ): SwapTransaction {
            val kas = KaspaUnit.plain(if (bought) amountIn else amountOut)
            val kusdt = TokenAmount.typed(if (bought) amountOut else amountIn, WalletToken.KUSDT)
            return SwapTransaction(
                id = txid,
                fromTicker = if (bought) "kas" else "kusdt", fromNetwork = "kaspa",
                toTicker = if (bought) "kusdt" else "kas", toNetwork = "kaspa",
                fromAmount = if (bought) kas else kusdt, toAmount = if (bought) kusdt else kas,
                payinAddress = address, payoutAddress = address,
                status = if (confirmed) "finished" else "sending",
                createdAtMillis = createdAtMillis,
                kasSendTxId = txid,
                provider = SwapTransaction.TRESTLY,
            )
        }

        /** A ChangeNOW status after which nothing more happens (iOS d88891ea `isEndStatus`). */
        fun isEndStatus(status: String): Boolean = status.lowercase() in END_STATUSES

        private val END_STATUSES = setOf("finished", "failed", "refunded", "expired")

        /** "Send KAS from K" shows only while ChangeNOW still waits for the deposit and K hasn't
         *  sent it: a second tap paid it twice (iOS d88891ea, audit 2026-11-27 M1). */
        fun offersDepositSend(status: String, kasSendTxId: String?): Boolean =
            kasSendTxId == null && status.lowercase() in setOf("new", "waiting")

        /** The key a ChangeNOW swap is recorded under in Portfolio (iOS `swapSourceTxId`). */
        fun swapSourceTxId(swapId: String): String = "changenow:$swapId"

        /**
         * The account is being removed (Delete Account): its swap history and its accepted
         * agreements, every version (iOS `SwapService.removeAllData`, audit 2026-11-24 #5).
         */
        fun removeAllData(context: Context, walletAddress: String) {
            val account = walletAddress.lowercase()
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            editor.remove(historyKey(account))
            for (version in 1..AGREEMENTS_VERSION) editor.remove(agreementsStorageKey(version, account))
            editor.apply()
        }
    }
}
