package com.kachat.app.ui.screens.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransaction
import com.kachat.app.services.SwapService
import com.kachat.app.services.WalletService
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.TrestlySite
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.ActionSheetRow
import com.kachat.app.ui.screens.CoverDock
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosSearchField
import com.kachat.app.ui.screens.IosSheetNavBar
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.KaspaAmountGrouping
import com.kachat.app.ui.screens.OwnAddressList
import com.kachat.app.ui.screens.SelectablePill
import com.kachat.app.ui.screens.SendSource
import com.kachat.app.ui.screens.SendStepQuestion
import com.kachat.app.ui.screens.SheetBarBackButton
import com.kachat.app.ui.screens.SheetBarButton
import com.kachat.app.ui.screens.SheetNavBar
import com.kachat.app.ui.screens.SpendingAddressSendFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import com.kachat.app.ui.screens.rememberScreenRunning
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.IosAlertDialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.ui.screens.amount
import com.kachat.app.util.currencySymbolFor
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.showAddressCopiedToast
import com.kachat.app.viewmodels.KasPriceViewModel
import com.kachat.app.viewmodels.PortfolioViewModel
import com.kachat.app.viewmodels.SwapViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** iOS's orange (pending statuses, the deposit tag's warning) and green / red. */
private val SwapOrange = Color(0xFFFF9500)
private val SwapGreen = Color(0xFF34C759)
private val SwapRed = Color(0xFFFF3B30)

/**
 * Where Swap opens (iOS 824a30bb `SwapView.Start`): buying Kaspa (what to pay with), selling it
 * (what to get), the history, or straight on the order (the debit card's kUSDT for KAS, set up
 * before with [SwapService.prepareKusdtSell]). Code names stay Swap; the words are Buy and Sell.
 */
enum class SwapStart { BUY, SELL, HISTORY, FORM }

/** The screens after the first (iOS `SwapView.Step`). */
internal sealed class SwapStep {
    /** Buying Kaspa: where it lands, before the amounts. */
    data object Destination : SwapStep()
    data object Form : SwapStep()
    data class Deposit(val id: String) : SwapStep()
    data class Complete(val id: String) : SwapStep()
    data class KusdtDone(val message: String) : SwapStep()

    /** The deposit and both ends hide the back button (iOS `navigationBarBackButtonHidden`). */
    val canGoBack: Boolean get() = this is Destination || this is Form

    /** AnimatedContent's key: one per screen. */
    val key: String get() = when (this) {
        Destination -> "destination"
        Form -> "form"
        is Deposit -> "deposit-$id"
        is Complete -> "complete-$id"
        is KusdtDone -> "done"
    }
}

/**
 * The pair picked on a coin screen (iOS `choosePair`): Kaspa is always one side, so which side it
 * is on sets the direction and the other coin is the pair's. Buying lands on the Chatting Address
 * when opened to fund it, else on a fresh address, and asks where it lands as its own step (for
 * kUSDT too); selling goes straight to the order.
 */
internal data class SwapPairPlan(val kasSends: Boolean, val otherCoin: SwapCoin, val landOnChatting: Boolean, val next: SwapStep)

internal fun swapPairPlan(first: SwapCoin, second: SwapCoin, buyToChatting: Boolean): SwapPairPlan {
    val kasSends = first == SwapCoin.KAS
    return SwapPairPlan(
        kasSends = kasSends,
        otherCoin = if (kasSends) second else first,
        landOnChatting = !kasSends && buyToChatting,
        next = if (kasSends) SwapStep.Form else SwapStep.Destination,
    )
}

/**
 * Swap, full screen over the Wallet tab (iOS 824a30bb `.fullScreenCover(item: $swapCover) {
 * SwapView(start:) }`): up from the bottom, the dock out of the way while it shows, Back or Done
 * to close. Null [start] is closed; the start it opened with stays while it slides away.
 */
@Composable
fun SwapCover(start: SwapStart?, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(start) }
    if (start != null) shown = start
    AnimatedVisibility(
        visible = start != null,
        enter = slideInVertically(tween(350)) { it },
        exit = slideOutVertically(tween(300)) { it },
    ) {
        CoverDock()
        BackHandler(onBack = onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalAppColors.current.background)
                // nothing behind the cover takes a tap
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            shown?.let { SwapScreen(start = it, onDone = onDismiss) }
        }
    }
}

/**
 * iOS `SwapView`: "Before You Swap" until both agreements are accepted on this account (the
 * history is only a list: no agreement needed to read it), then the steps from [start].
 * [buyToChatting]: opened to fund the chatting address, bought Kaspa is set to land there.
 */
@Composable
fun SwapScreen(
    start: SwapStart,
    onDone: () -> Unit,
    buyToChatting: Boolean = false,
    viewModel: SwapViewModel = hiltViewModel(),
) {
    val swap = viewModel.service
    val haptic = rememberHaptics()
    if (swap.agreementsAccepted || start == SwapStart.HISTORY) {
        SwapMain(viewModel, start, buyToChatting, onDone)
    } else {
        SwapAgreementsPage(onCancel = onDone, onAccept = {
            haptic(IosHaptic.SUCCESS)
            swap.acceptAgreements()
        })
    }
}

/** The app's own [WalletViewModel] (the activity's), else this screen's. */
@Composable
private fun rememberSwapWalletViewModel(): WalletViewModel {
    val activity = generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .firstOrNull { it is androidx.activity.ComponentActivity } as? androidx.activity.ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}

@Composable
private fun SwapMain(viewModel: SwapViewModel, start: SwapStart, buyToChatting: Boolean, onDone: () -> Unit) {
    val swap = viewModel.service
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val portfolioViewModel: PortfolioViewModel = hiltViewModel()
    val priceViewModel: KasPriceViewModel = hiltViewModel()
    val walletViewModel = rememberSwapWalletViewModel()
    val currency by priceViewModel.currency.collectAsState()

    var path by remember { mutableStateOf(listOf<SwapStep>()) }
    var selectedSwapId by remember { mutableStateOf<String?>(null) }
    var showKusdtFromPicker by remember { mutableStateOf(false) }
    var pendingDeleteSwap by remember { mutableStateOf<SwapTransaction?>(null) }
    var pendingPortfolio by remember { mutableStateOf<Pair<SwapService.PortfolioPrefill, String>?>(null) }
    // The "where" step's choice, kept here so it is still there coming back from the order
    // (iOS's NavigationStack keeps the step's state).
    var destinationChoosing by remember { mutableStateOf(false) }
    var destinationPicked by remember { mutableStateOf<String?>(null) }

    fun push(step: SwapStep) { path = path + step }
    fun pop() { if (path.isNotEmpty()) path = path.dropLast(1) }

    /** Add to Portfolio, from the completion screen or a history entry: the portfolio chooser. */
    fun addToPortfolio(entry: SwapTransaction) {
        val prefill = SwapService.portfolioPrefill(entry)
        if (prefill == null) {
            IosToasts.show(context.getString(R.string.swap_couldnt_read_amounts))
        } else {
            pendingPortfolio = prefill to entry.id
        }
    }

    fun choosePair(first: SwapCoin, second: SwapCoin) {
        val plan = swapPairPlan(first, second, buyToChatting)
        if (swap.kasIsSendSide != plan.kasSends) swap.flipDirection()
        swap.chooseOtherCoin(plan.otherCoin)
        swap.typeAmount("")
        if (plan.landOnChatting) swap.useChattingToAddress() else swap.useFreshToAddress()
        destinationChoosing = false
        destinationPicked = null
        push(plan.next)
    }

    // a kUSDT swap that went through: its own screen, from the order (iOS `.onChange(of: notice)`)
    val latestPath by rememberUpdatedState(path)
    LaunchedEffect(Unit) {
        snapshotFlow { swap.notice }.drop(1).collect { notice ->
            if (notice == null) return@collect
            swap.notice = null
            val onForm = latestPath.lastOrNull() == SwapStep.Form || (latestPath.isEmpty() && start == SwapStart.FORM)
            if (onForm) push(SwapStep.KusdtDone(notice)) else IosToasts.show(notice)
        }
    }
    // a ChangeNOW exchange made: its deposit screen
    LaunchedEffect(Unit) {
        snapshotFlow { swap.createSwapState.status }.drop(1).collect { status ->
            when (status) {
                SwapService.CreateSwapStatus.SUCCESS -> swap.createSwapState.result?.let { push(SwapStep.Deposit(it.transaction.id)) }
                SwapService.CreateSwapStatus.FAILED ->
                    IosToasts.show(swap.createSwapState.errorMessage ?: context.getString(R.string.swap_failed))
                else -> Unit
            }
        }
    }
    BackHandler(enabled = path.lastOrNull()?.canGoBack == true) {
        focusManager.clearFocus()
        pop()
    }

    val formTitle = if (swap.kasIsSendSide) stringResource(R.string.buysell_sell_kaspa_for, swap.otherCoin.displayName)
    else stringResource(R.string.buysell_buy_kaspa_with, swap.otherCoin.displayName)
    val top = path.lastOrNull()
    val title = when (top) {
        null -> when (start) {
            SwapStart.BUY -> stringResource(R.string.buysell_buy_kaspa)
            SwapStart.SELL -> stringResource(R.string.buysell_sell_kaspa)
            SwapStart.HISTORY -> stringResource(R.string.buysell_history)
            SwapStart.FORM -> formTitle
        }
        SwapStep.Destination -> stringResource(R.string.buysell_buy_kaspa)
        SwapStep.Form -> formTitle
        is SwapStep.Deposit -> stringResource(R.string.buysell_send_payment)
        is SwapStep.Complete -> {
            val entry = swap.history.firstOrNull { it.id == top.id }
            stringResource(if (entry?.toTicker == "kas") R.string.buysell_purchase_complete else R.string.buysell_sale_complete)
        }
        is SwapStep.KusdtDone -> stringResource(R.string.buysell_order_sent)
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // iOS: Done as the cancellation action on the first screen; on the ones after it, the
        // back button (where it shows) and Done at the trailing edge.
        SheetNavBar(
            title = title,
            modifier = Modifier.statusBarsPadding(),
            leading = {
                if (top == null) {
                    SheetBarButton(stringResource(R.string.done), onClick = onDone)
                } else if (top.canGoBack) {
                    SheetBarBackButton(onClick = {
                        focusManager.clearFocus()
                        pop()
                    })
                }
            },
            trailing = {
                if (top != null) SheetBarButton(stringResource(R.string.done), onClick = onDone)
            },
        )
        AnimatedContent(
            targetState = path,
            contentKey = { it.lastOrNull()?.key ?: "root" },
            transitionSpec = {
                when {
                    targetState.size > initialState.size ->
                        slideInHorizontally(tween(300)) { it } togetherWith slideOutHorizontally(tween(300)) { -it / 3 }
                    targetState.size < initialState.size ->
                        slideInHorizontally(tween(300)) { -it / 3 } togetherWith slideOutHorizontally(tween(300)) { it }
                    else -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "swap-steps",
        ) { shownPath ->
            Box(Modifier.fillMaxSize().background(colors.background)) {
                when (val step = shownPath.lastOrNull()) {
                    null -> when (start) {
                        SwapStart.BUY -> SwapCoinChoiceList(
                            question = stringResource(R.string.buysell_pay_with_question),
                            coins = SwapCoinChoices.everyCoin(includingKaspa = false),
                            onPick = { coin -> choosePair(coin, SwapCoin.KAS) },
                        )
                        SwapStart.SELL -> SwapCoinChoiceList(
                            question = stringResource(R.string.buysell_sell_for_question),
                            coins = SwapCoinChoices.everyCoin(includingKaspa = false),
                            onPick = { coin -> choosePair(SwapCoin.KAS, coin) },
                        )
                        SwapStart.HISTORY -> SwapHistoryPage(
                            history = swap.history,
                            onOpen = { selectedSwapId = it.id },
                            onDelete = { pendingDeleteSwap = it },
                        )
                        SwapStart.FORM -> SwapFormPage(swap = swap, onPickKusdtFrom = { showKusdtFromPicker = true })
                    }
                    SwapStep.Destination -> SwapDestinationStep(
                        swap = swap,
                        viewModel = viewModel,
                        walletViewModel = walletViewModel,
                        choosing = destinationChoosing,
                        onChoosingChange = { destinationChoosing = it },
                        picked = destinationPicked,
                        onPickedChange = { destinationPicked = it },
                        onContinue = { push(SwapStep.Form) },
                    )
                    SwapStep.Form -> SwapFormPage(swap = swap, onPickKusdtFrom = { showKusdtFromPicker = true })
                    is SwapStep.Deposit -> SwapDepositScreen(
                        service = swap,
                        swapId = step.id,
                        walletViewModel = walletViewModel,
                        // finished: the completion takes the deposit's place
                        onFinished = { path = path.dropLast(1) + SwapStep.Complete(step.id) },
                    )
                    is SwapStep.Complete -> SwapCompleteScreen(
                        service = swap,
                        swapId = step.id,
                        onAddToPortfolio = { addToPortfolio(it) },
                        onDone = onDone,
                    )
                    is SwapStep.KusdtDone -> SwapSentScreen(message = step.message, onDone = onDone)
                }
            }
        }
    }

    // Trestly's approval for a kUSDT swap: closing it declines
    swap.kusdtApproval?.let { request ->
        key(request.id) {
            IosFullSheet(onDismissed = { request.respond(false) }, swipeToDismiss = true) { close ->
                TrestlyApprovalSheet(request) { approved ->
                    swap.kusdtApproval = null
                    request.respond(approved)
                    close()
                }
            }
        }
    }
    if (showKusdtFromPicker) {
        IosFullSheet(onDismissed = { showKusdtFromPicker = false }, swipeToDismiss = true) { close ->
            KusdtSwapFromPicker(
                viewModel = viewModel,
                current = swap.kusdtTarget?.address,
                onCancel = close,
                onPick = { target ->
                    swap.chooseKusdtFrom(target)
                    close()
                },
            )
        }
    }
    selectedSwapId?.let { id ->
        val detail = swap.history.firstOrNull { it.id == id }
        if (detail == null) {
            selectedSwapId = null
        } else {
            IosFullSheet(onDismissed = { selectedSwapId = null }, swipeToDismiss = true, grouped = true) { close ->
                val kaspaExplorer by viewModel.kaspaExplorer.collectAsState(initial = com.kachat.app.models.KaspaExplorer.default)
                SwapDetailPage(
                    swap = detail,
                    service = swap,
                    kaspaExplorer = kaspaExplorer,
                    onDone = close,
                    onAddToPortfolio = {
                        val prefill = SwapService.portfolioPrefill(detail)
                        if (prefill == null) {
                            IosToasts.show(context.getString(R.string.swap_couldnt_read_amounts))
                        } else {
                            pendingPortfolio = prefill to detail.id
                            close()
                        }
                    },
                )
            }
        }
    }
    // Add to Portfolio: which portfolio, each row saying when the swap is already in it
    pendingPortfolio?.let { (prefill, swapId) ->
        val portfolios by portfolioViewModel.portfolios.collectAsState()
        val duplicates = remember(swapId, portfolios) { portfolioViewModel.portfolioIdsContaining(SwapService.swapSourceTxId(swapId)) }
        // ChangeNOW's number, clamped: never below zero (iOS d88891ea `clampedUInt64`, audit
        // 2026-11-27 L4; Math.round already caps at Long.MAX_VALUE and reads NaN as 0)
        val amount = KaspaUnit.plain(Math.round(prefill.amountKas * 100_000_000).coerceAtLeast(0L))
        val price = currencySymbolFor(currency) + String.format(Locale.US, "%.2f", prefill.fiatValue)
        // one sentence per side, so each language words it whole (iOS audit 2026-11-24 #34)
        val prompt = if (prefill.type == "buy") stringResource(R.string.swap_portfolio_buy_prompt, amount, price)
        else stringResource(R.string.swap_portfolio_sell_prompt, amount, price)
        ActionSheetContainer(
            title = stringResource(R.string.kp_add_to_portfolio_9eb23),
            subtitle = prompt,
            onDismiss = { pendingPortfolio = null },
        ) {
            portfolios.forEach { portfolio ->
                val duplicate = portfolio.id in duplicates
                ActionSheetRow(
                    icon = if (duplicate) Icons.Filled.Warning else Icons.Filled.PieChart,
                    title = portfolio.name,
                    subtitle = stringResource(if (duplicate) R.string.swap_already_holds else R.string.swap_lands_in_portfolio),
                    tint = if (duplicate) SwapOrange else KaspaTeal,
                ) {
                    portfolioViewModel.addTransaction(
                        type = prefill.type,
                        amountKas = prefill.amountKas,
                        fiatValue = prefill.fiatValue,
                        timestampMillis = prefill.timestampMillis,
                        notes = prefill.notes,
                        portfolioId = portfolio.id,
                        // namespaced so it can never collide with a Kaspa txid
                        sourceTxId = SwapService.swapSourceTxId(swapId),
                    )
                    swap.markAddedToPortfolio(swapId)
                    IosToasts.show(context.getString(R.string.kp_added_to_2c931, portfolio.name))
                    pendingPortfolio = null
                }
            }
        }
    }
    pendingDeleteSwap?.let { target ->
        IosAlertDialog(
            onDismissRequest = { pendingDeleteSwap = null },
            title = { Text(stringResource(R.string.swap_delete_title)) },
            text = { Text(stringResource(R.string.swap_delete_message)) },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSwap = null }) { Text(stringResource(R.string.cancel), color = KaspaTeal) }
            },
            confirmButton = {
                TextButton(onClick = {
                    swap.deleteSwap(target.id)
                    pendingDeleteSwap = null
                }) { Text(stringResource(R.string.delete), color = SwapRed, fontWeight = FontWeight.SemiBold) }
            },
        )
    }
}

// The order

/**
 * The order (iOS 824a30bb `swapFormPage`): Buy Kaspa with a coin or Sell Kaspa for one, "You Pay"
 * and "You Get" either typed, both grouped by thousands as you type; no flip, no coin picker, no
 * Receiving KAS At row (the coin and where it lands were picked on the screens before).
 */
@Composable
private fun SwapFormPage(swap: SwapService, onPickKusdtFrom: () -> Unit) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isBusy = swap.createSwapState.status == SwapService.CreateSwapStatus.CREATING
    val canSwap = swap.estimateState.status == SwapService.EstimateStatus.SUCCESS && !isBusy
    val toCoin = swap.toCoin
    val needsPayoutAddress = toCoin.ticker != "kas" && !swap.isKusdt
    val loading = swap.estimateState.status == SwapService.EstimateStatus.LOADING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Both cards take input: type under "You Pay" and "You Get" is quoted; type under
        // "You Get" and the reverse quote fills "You Pay" with what that costs.
        // kUSDT swaps spend what K holds: Max, and the balance beside "You Pay" (iOS 6b99c6dd,
        // owner 2026-11-24). A ChangeNOW coin is paid from elsewhere.
        val holdings by swap.tokenHoldings.collectAsState()
        LaunchedEffect(swap.isKusdt, swap.kusdtTarget?.address, swap.kasIsSendSide) {
            swap.refreshKusdtBalances()
        }
        val kusdtAvailable: String? = remember(holdings, swap.isKusdt, swap.kasIsSendSide, swap.kusdtFromKasSompi, swap.kusdtTarget?.address) {
            when {
                !swap.isKusdt -> null
                swap.kasIsSendSide -> swap.kusdtFromKasSompi?.let { KaspaUnit.amount(it) }
                else -> TokenAmount.amount(swap.kusdtFromUnits, WalletToken.KUSDT)
            }
        }
        val maxSend = remember(holdings, swap.isKusdt, swap.kasIsSendSide, swap.kusdtFromKasSompi, swap.kusdtTarget?.address) { swap.kusdtMaxSend }
        SwapAmountCard(
            label = stringResource(R.string.buysell_you_pay),
            coin = swap.fromCoin,
            amountText = swap.amountText,
            isQuoting = swap.editedSide == SwapService.AmountSide.GET && loading,
            onAmountChange = { swap.typeAmount(it) },
            onMaxTap = if ((maxSend ?: 0L) > 0L) ({ swap.setMaxAmount() }) else null,
            available = kusdtAvailable,
        )

        // Why Max on a buy is less than the balance (owner, 2026-11-24: "max does not actually
        // pull"): what stays behind, and what it's for.
        if (swap.isKusdt && swap.kasIsSendSide) {
            Text(
                stringResource(
                    R.string.swap_max_keeps_back,
                    KaspaUnit.amount(SwapService.kasKeptForBuy),
                    KaspaUnit.amount(WalletToken.KUSDT.kasPerCoinSompi),
                    KaspaUnit.amount(SwapService.FEE_KEPT_FOR_BUY),
                ),
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }

        SwapAmountCard(
            label = stringResource(R.string.swap_you_get),
            coin = toCoin,
            amountText = swap.receiveAmountText,
            isQuoting = swap.editedSide == SwapService.AmountSide.SEND && loading,
            onAmountChange = { swap.typeReceiveAmount(it) },
            // Trestly's pool quotes from what you send: "You Get" is its answer
            editable = !swap.isKusdt,
        )

        if (needsPayoutAddress) {
            SwapPlainField(
                value = swap.payoutAddressText,
                onValueChange = { swap.payoutAddressText = it },
                placeholder = stringResource(R.string.swap_receive_coin_at, toCoin.displayName),
                keyboardType = KeyboardType.Ascii,
            )
        }

        // The payout's tag, for a coin whose exchange addresses need one: XRP's destination tag
        // (iOS audit 2026-11-24 #1)
        if (needsPayoutAddress && toCoin.needsMemo) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwapPlainField(
                    value = swap.payoutExtraIdText,
                    onValueChange = { swap.payoutExtraIdText = it },
                    placeholder = stringResource(R.string.swap_destination_tag_optional),
                    keyboardType = if (toCoin == SwapCoin.XRP) KeyboardType.Number else KeyboardType.Ascii,
                )
                Text(stringResource(R.string.swap_destination_tag_hint), color = colors.textSecondary, fontSize = 12.sp)
            }
        }

        // Selling Kaspa for kUSDT: which address trades. Buying with kUSDT, the address was
        // picked on the "where" step, so the order has no Swap From row (iOS 824a30bb).
        if (swap.isKusdt && swap.kasIsSendSide) {
            SpendingAddressRow(
                title = stringResource(R.string.swap_from_kusdt_lands),
                value = swap.kusdtTarget?.let { "${it.label} · ${shortAddress(it.address)}" } ?: "",
                onChange = onPickKusdtFrom,
            )
        }

        // iOS rateCard
        val failed = swap.estimateState.status == SwapService.EstimateStatus.FAILED
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().iosGlass(14.dp, shadowAlpha = 0f).padding(12.dp),
        ) {
            Text(stringResource(R.string.swap_rate), color = colors.textSecondary, fontSize = 12.sp)
            Text(rateText(swap), color = if (failed) SwapRed else colors.textSecondary, fontSize = 12.sp)
        }

        // Buy Kaspa / Sell Kaspa: a ChangeNOW order goes on to its deposit screen; a kUSDT one
        // asks for approval first.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(if (canSwap) KaspaTeal else colors.surfaceVariant)
                .clickable(enabled = canSwap) {
                    focusManager.clearFocus()
                    swap.executeSwap()
                },
        ) {
            if (isBusy) {
                IosActivityIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
            } else {
                Text(
                    stringResource(if (swap.kasIsSendSide) R.string.buysell_sell_kaspa else R.string.buysell_buy_kaspa),
                    color = if (canSwap) Color.Black else colors.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        }

        val poweredBy = if (swap.isKusdt) stringResource(R.string.swap_powered_by_trestly) else stringResource(R.string.swap_powered_by_changenow)
        Text(
            poweredBy,
            color = KaspaTeal,
            fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable {
                    val url = if (swap.isKusdt) trestlyTermsUrl(context) else CHANGENOW_TERMS_SHORT_URL
                    try { uriHandler.openUri(url) } catch (e: Exception) {}
                },
        )
    }
}

/** One amount card. [isQuoting] marks the card whose figure is being fetched for the other card's
 *  input: a spinner where the number will land. Thousands are grouped as you type (iOS 824a30bb
 *  `KaspaAmountEntry.grouped` / `ungrouped`): the field shows them, the value stays plain. The coin
 *  is fixed: it was picked on the coin screen. */
@Composable
private fun SwapAmountCard(
    label: String,
    coin: SwapCoin,
    amountText: String,
    isQuoting: Boolean,
    onAmountChange: (String) -> Unit,
    editable: Boolean = true,
    onMaxTap: (() -> Unit)? = null,
    available: String? = null,
) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().iosGlass(18.dp, shadowAlpha = 0f).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (available != null) {
                KachatFitText(
                    stringResource(R.string.token_available, available),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    minScale = 0.8f,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                if (isQuoting) {
                    IosActivityIndicator(modifier = Modifier.size(20.dp))
                } else {
                    BasicTextField(
                        value = amountText,
                        onValueChange = { onAmountChange(KaspaAmountGrouping.ungrouped(it, previous = amountText)) },
                        visualTransformation = KaspaAmountGrouping.visualTransformation,
                        enabled = editable,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(KaspaTeal),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (amountText.isEmpty()) {
                                Text("0.00", color = colors.textSecondary.copy(alpha = 0.6f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            }
                            inner()
                        },
                    )
                }
            }
            if (onMaxTap != null) {
                Text(
                    stringResource(R.string.max),
                    color = KaspaTeal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onMaxTap).padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
            CoinBadge(coin)
        }
    }
}

/** The coin, fixed (iOS `coinBadge`): its icon and name on a grey capsule. */
@Composable
private fun CoinBadge(coin: SwapCoin) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        SwapCoinIcon(coin)
        KachatFitText(coin.displayName, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, minScale = 0.8f)
    }
}


@Composable
private fun rateText(swap: SwapService): String {
    val state = swap.estimateState
    return when (state.status) {
        SwapService.EstimateStatus.SUCCESS -> {
            // Both figures come from the quote itself, whichever side was typed.
            val fromAmount = state.fromAmount ?: 0.0
            val toAmount = state.toAmount ?: 0.0
            if (fromAmount <= 0) return "—"
            val fromLabel = if (swap.kasIsSendSide) "KAS" else swap.otherCoin.displayName
            val toLabel = if (swap.kasIsSendSide) swap.otherCoin.displayName else "KAS"
            "1 $fromLabel ≈ ${String.format(Locale.US, "%.8f", toAmount / fromAmount)} $toLabel"
        }
        SwapService.EstimateStatus.FAILED -> state.errorMessage ?: stringResource(R.string.swap_unavailable)
        else -> "—"
    }
}


// Swap step by step (iOS 824a30bb, owner 2026-11-26)

/**
 * The coins to pick from (iOS `SwapCoinChoiceList`): kUSDT, then USD Coin and Tether folded by
 * network, then the rest. Pure, so it can be tested.
 */
internal object SwapCoinChoices {
    /** Tickers on several networks, folded into one row that opens in place. */
    val groupNames: Map<String, String> = mapOf("usdc" to "USD Coin", "usdt" to "Tether")

    /** Every coin K swaps, in the list's order (iOS `everyCoin(includingKaspa:)`). */
    fun everyCoin(includingKaspa: Boolean): List<SwapCoin> {
        val list = ArrayList<SwapCoin>()
        if (includingKaspa) list += SwapCoin.KAS
        list += SwapCoin.KUSDT
        for (ticker in listOf("usdc", "usdt")) list += SwapCoin.CURATED.filter { it.ticker == ticker }
        list += SwapCoin.CURATED.filter { groupNames[it.ticker] == null }
        return list
    }

    sealed class Row(val id: String) {
        class Coin(val coin: SwapCoin) : Row("c-${coin.ticker}-${coin.network}")
        class Group(val ticker: String) : Row("g-$ticker")
        class Network(val coin: SwapCoin) : Row("n-${coin.ticker}-${coin.network}")
    }

    /** The rows shown: a search lists the coins that match by name or ticker, flat; otherwise a
     *  ticker on several networks is one row, its networks under it once [expanded]. */
    fun rows(coins: List<SwapCoin>, expanded: Set<String>, search: String): List<Row> {
        val query = search.trim().lowercase()
        if (query.isNotEmpty()) {
            return coins.filter { it.displayName.lowercase().contains(query) || it.ticker.contains(query) }.map { Row.Coin(it) }
        }
        val out = ArrayList<Row>()
        val seen = HashSet<String>()
        for (coin in coins) {
            val siblings = coins.filter { it.ticker == coin.ticker }
            if (groupNames[coin.ticker] != null && siblings.size > 1) {
                if (!seen.add(coin.ticker)) continue
                out += Row.Group(coin.ticker)
                if (coin.ticker in expanded) siblings.forEach { out += Row.Network(it) }
            } else {
                out += Row.Coin(coin)
            }
        }
        return out
    }

    /** The coin's symbol under its name: "kUSDT", else the ticker in capitals. */
    fun symbol(coin: SwapCoin): String = if (coin.isTrestly) "kUSDT" else coin.ticker.uppercase()
}

/** A screen of coins to pick from: what to pay with (Buy) or what to get (Sell). Search once the
 *  list is long. */
@Composable
private fun SwapCoinChoiceList(question: String, coins: List<SwapCoin>, onPick: (SwapCoin) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val rows = remember(coins, expanded, search) { SwapCoinChoices.rows(coins, expanded, search) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "question") {
            Text(
                question,
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
        if (coins.size > 6) {
            item(key = "search") {
                IosSearchField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = stringResource(R.string.swap_search_coins),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        items(rows, key = { it.id }) { row ->
            when (row) {
                is SwapCoinChoices.Row.Coin -> SwapCoinChoiceRow(row.coin, indent = false) {
                    haptic(IosHaptic.IMPACT_LIGHT)
                    onPick(row.coin)
                }
                is SwapCoinChoices.Row.Network -> SwapCoinChoiceRow(row.coin, indent = true) {
                    haptic(IosHaptic.IMPACT_LIGHT)
                    onPick(row.coin)
                }
                is SwapCoinChoices.Row.Group -> {
                    val networks = coins.filter { it.ticker == row.ticker }
                    val isOpen = row.ticker in expanded
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .iosGlass(16.dp, shadowAlpha = 0f)
                            .clickable { expanded = if (isOpen) expanded - row.ticker else expanded + row.ticker }
                            .padding(14.dp),
                    ) {
                        networks.firstOrNull()?.let { SwapCoinIcon(it, size = 34.dp) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                SwapCoinChoices.groupNames[row.ticker] ?: row.ticker.uppercase(),
                                color = colors.textPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.buysell_on_networks, row.ticker.uppercase(), networks.size),
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp).rotate(if (isOpen) 180f else 0f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwapCoinChoiceRow(coin: SwapCoin, indent: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .iosGlass(16.dp, shadowAlpha = 0f)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        SwapCoinIcon(coin, size = if (indent) 26.dp else 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                coin.displayName,
                color = colors.textPrimary,
                fontSize = if (indent) 15.sp else 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(SwapCoinChoices.symbol(coin), color = colors.textSecondary, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}

/**
 * Buying with kUSDT, where it pays from and the Kaspa lands (iOS `SwapDestinationStep.kusdtChoices`):
 * the Chatting Address, then every other address holding kUSDT, most first. Pure, for tests.
 */
internal fun kusdtDestinationChoices(
    chatting: String?,
    chattingLabel: String,
    held: List<TokenHolding>,
    label: (TokenHolding) -> String,
): List<TrestlyTarget> {
    val list = ArrayList<TrestlyTarget>()
    if (chatting != null) list += TrestlyTarget(chattingLabel, chatting, null, held.firstOrNull { it.isChatting }?.units ?: 0L)
    held.filter { !it.isChatting && it.units > 0 }.sortedByDescending { it.units }.forEach { holding ->
        list += TrestlyTarget(label(holding), holding.address, holding.spendingIndex, holding.units)
    }
    return list
}

/** The "where" step's Continue: always for kUSDT and a fresh address; under Choose an Address,
 *  once one is picked. */
internal fun swapDestinationCanContinue(isKusdt: Boolean, choosing: Boolean, picked: String?): Boolean =
    isKusdt || !choosing || picked != null

/**
 * Buying Kaspa, its own step before the amounts (iOS 824a30bb `SwapDestinationStep`): "Where
 * should your Kaspa land?" Through ChangeNOW: a fresh address (the default: an exchange's coins
 * stay apart from the username and chats) or one picked from the account's addresses
 * ([OwnAddressList], the Chatting Address too). With kUSDT: one of the addresses holding kUSDT,
 * since Trestly's trade pays from an address and lands its Kaspa on the same one.
 */
@Composable
private fun SwapDestinationStep(
    swap: SwapService,
    viewModel: SwapViewModel,
    walletViewModel: WalletViewModel,
    choosing: Boolean,
    onChoosingChange: (Boolean) -> Unit,
    picked: String?,
    onPickedChange: (String?) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val chatting by walletViewModel.address.collectAsState()
    val canContinue = swapDestinationCanContinue(swap.isKusdt, choosing, picked)

    // opened to fund the chatting address: Choose an Address, the Chatting Address picked
    LaunchedEffect(Unit) {
        if (swap.toChatting && !swap.isKusdt) {
            onChoosingChange(true)
            onPickedChange(walletViewModel.address.value)
        }
    }
    LaunchedEffect(swap.isKusdt) {
        if (swap.isKusdt) viewModel.tokenWallet.refreshIfStale(everyAddress = true)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SendStepQuestion(stringResource(R.string.buysell_where_land))
            if (swap.isKusdt) {
                val tokenWallet = viewModel.tokenWallet
                val holdingsByToken by tokenWallet.holdingsFlow.collectAsState()
                val held = remember(holdingsByToken) { tokenWallet.holdings(WalletToken.KUSDT) }
                var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                LaunchedEffect(held) {
                    labels = withContext(Dispatchers.Default) { held.associate { it.id to tokenWallet.label(it) } }
                }
                val chattingLabel = stringResource(R.string.chatting_address)
                val choices = remember(chatting, held, labels, chattingLabel) {
                    kusdtDestinationChoices(chatting, chattingLabel, held) { labels[it.id] ?: it.address }
                }
                Text(stringResource(R.string.buysell_kusdt_one_address), color = colors.textSecondary, fontSize = 15.sp)
                choices.forEach { choice ->
                    val isPicked = choice.address == swap.kusdtTarget?.address
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .iosGlass(18.dp, shadowAlpha = 0f)
                            .border(1.5.dp, if (isPicked) KaspaTeal else Color.Transparent, shape)
                            .clickable {
                                haptic(IosHaptic.IMPACT_LIGHT)
                                swap.chooseKusdtFrom(if (choice.spendingIndex == null) null else choice)
                            }
                            .padding(14.dp),
                    ) {
                        Icon(
                            if (isPicked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (isPicked) KaspaTeal else colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(choice.label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${choice.address.take(14)}...${choice.address.takeLast(8)}",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(TokenAmount.amount(choice.units, WalletToken.KUSDT), color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectablePill(stringResource(R.string.pr_fresh_address), selected = !choosing, modifier = Modifier.weight(1f)) {
                        onChoosingChange(false)
                        onPickedChange(null)
                        swap.useFreshToAddress()
                    }
                    SelectablePill(stringResource(R.string.pr_choose_an_address), selected = choosing, modifier = Modifier.weight(1f)) {
                        onChoosingChange(true)
                    }
                }
                if (choosing) {
                    OwnAddressList(
                        selected = picked,
                        walletViewModel = walletViewModel,
                        onPick = { source ->
                            when (source) {
                                SendSource.Chatting -> swap.useChattingToAddress()
                                is SendSource.Spending -> swap.selectToSpendingAddress(source.index)
                            }
                            onPickedChange(source.address(chatting))
                        },
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().iosGlass(18.dp, shadowAlpha = 0f).padding(16.dp),
                    ) {
                        Text(stringResource(R.string.buysell_fresh_note), color = colors.textSecondary, fontSize = 15.sp)
                        if (swap.toAddress.isNotEmpty()) {
                            Text(swap.toAddress, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        SwapBottomButton(
            text = stringResource(R.string.continue_label),
            enabled = canContinue,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        ) {
            haptic(IosHaptic.IMPACT_LIGHT)
            onContinue()
        }
    }
}

/** A 50-high capsule: accent and black text when [enabled], grey otherwise. */
@Composable
private fun SwapBottomButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(
                if (filled) Modifier.clip(CircleShape).background(if (enabled) KaspaTeal else colors.surfaceVariant)
                else Modifier.iosGlass(25.dp, shadowAlpha = 0f).clip(CircleShape)
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text,
            color = when {
                !filled -> colors.textPrimary
                enabled -> Color.Black
                else -> colors.textSecondary
            },
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A coin of a saved swap, by its ticker and network, with its name (iOS `swapCoinFor`). */
internal fun swapCoinFor(ticker: String, network: String): SwapCoin =
    (listOf(SwapCoin.KAS, SwapCoin.KUSDT) + SwapCoin.CURATED).firstOrNull { it.ticker == ticker && it.network == network }
        ?: SwapCoin(ticker, network, ticker.uppercase())

/** Where a ChangeNOW order is, as the deposit screen's status card says it (iOS `statusCard`). */
internal enum class DepositStage { WAITING, CONFIRMING, EXCHANGING, SENDING, FAILED, REFUNDED, EXPIRED, VERIFYING;
    /** A stage that needs the person's attention: a warning, not a spinner. */
    val isProblem: Boolean get() = this == FAILED || this == REFUNDED || this == EXPIRED || this == VERIFYING

    companion object {
        fun of(status: String): DepositStage = when (status.lowercase()) {
            "confirming" -> CONFIRMING
            "exchanging" -> EXCHANGING
            "sending" -> SENDING
            "failed" -> FAILED
            "refunded" -> REFUNDED
            "expired" -> EXPIRED
            "verifying" -> VERIFYING
            else -> WAITING
        }
    }
}

/** How often the deposit screen asks ChangeNOW where the order is, while it shows. */
internal const val DEPOSIT_POLL_MS = 10_000L

/**
 * A ChangeNOW order's deposit (iOS 824a30bb `SwapDepositScreen`): what to send and where (QR and
 * address, the tag if the coin needs one), waiting for payment, and the transaction ID. Checks
 * ChangeNOW every 10 s, only while it shows and the app is in front (someone is watching for their
 * payment), and moves on to the completion screen once the order finishes. Drawn from the exchange
 * made, never from the coins picked now (iOS audit 2026-11-24 #2).
 */
@Composable
private fun SwapDepositScreen(service: SwapService, swapId: String, walletViewModel: WalletViewModel, onFinished: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val haptic = rememberHaptics()
    val running = rememberScreenRunning()
    val latestFinished by rememberUpdatedState(onFinished)
    var showSendKas by remember { mutableStateOf(false) }
    LaunchedEffect(swapId, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            val status = service.refreshSwapStatusNow(swapId)
            if (status?.lowercase() == "finished") {
                haptic(IosHaptic.SUCCESS)
                latestFinished()
                return@LaunchedEffect
            }
            // failed, refunded or expired: nothing more will happen, so stop asking (iOS d88891ea,
            // audit 2026-11-27 L18)
            if (status != null && SwapService.isEndStatus(status)) return@LaunchedEffect
            delay(DEPOSIT_POLL_MS)
        }
    }
    val swap = service.history.firstOrNull { it.id == swapId }
    if (swap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IosActivityIndicator(modifier = Modifier.size(24.dp)) }
        return
    }
    val from = swapCoinFor(swap.fromTicker, swap.fromNetwork)
    val to = swapCoinFor(swap.toTicker, swap.toNetwork)
    fun copy(text: String, toast: String?) {
        clipboard.setText(AnnotatedString(text))
        haptic(IosHaptic.SUCCESS)
        if (toast == null) showAddressCopiedToast(context, text, haptic = false) else IosToasts.show(toast)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text(
                stringResource(R.string.buysell_send_amount_coin, KaspaAmountGrouping.grouped(swap.fromAmount), from.displayName),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                if (to == SwapCoin.KAS) stringResource(R.string.buysell_to_buy_kaspa)
                else stringResource(R.string.buysell_to_sell_for, to.displayName),
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }

        DepositStatusCard(swap.status, to)

        SwapQrCode(swap.payinAddress, size = 200)

        DepositCard {
            Text(stringResource(R.string.swap_deposit_address), color = colors.textSecondary, fontSize = 12.sp)
            Text(swap.payinAddress, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            DepositCopyButton(stringResource(R.string.copy_address)) { copy(swap.payinAddress, null) }
        }

        DepositCard {
            Text(stringResource(R.string.swap_amount_to_send), color = colors.textSecondary, fontSize = 12.sp)
            Text("${KaspaAmountGrouping.grouped(swap.fromAmount)} ${from.displayName}", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            DepositCopyButton(stringResource(R.string.buysell_copy_amount)) { copy(swap.fromAmount, context.getString(R.string.buysell_amount_copied)) }
        }

        val tag = swap.payinExtraId
        if (!tag.isNullOrEmpty()) {
            SwapDepositTagBox(tag) { copy(tag, context.getString(R.string.swap_destination_tag_copied)) }
        }

        // Kaspa in: K pays it, the address and amount filled in. Once K has sent it, or ChangeNOW
        // has seen a deposit, the button is gone: a second tap paid it twice (iOS d88891ea, audit
        // 2026-11-27 M1).
        val sentTxId = swap.kasSendTxId
        val sompi = if (from == SwapCoin.KAS && SwapService.offersDepositSend(swap.status, sentTxId)) {
            KaspaUnit.sompiFromUserText(swap.fromAmount)
        } else null
        if (from == SwapCoin.KAS && sentTxId != null) {
            DepositCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SwapGreen, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.audit_money_sent_from_k), color = SwapGreen, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (sentTxId.isNotEmpty()) {
                    com.kachat.app.ui.screens.MiddleEllipsisText(
                        sentTxId,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        } else if (sompi != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .clickable { showSendKas = true },
            ) {
                Icon(Icons.Filled.ArrowCircleUp, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.buysell_send_kas_from_k), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showSendKas) {
                val chatting by walletViewModel.address.collectAsState()
                val balanceSompi by walletViewModel.balanceSompi.collectAsState()
                val fromAddress = chatting
                if (fromAddress == null) {
                    showSendKas = false
                } else {
                    IosFullSheet(onDismissed = { showSendKas = false }, swipeToDismiss = false) { close ->
                        SpendingAddressSendFlow(
                            fromAddress = fromAddress,
                            balanceSompi = balanceSompi,
                            title = stringResource(R.string.chats_send_kaspa),
                            viewModel = walletViewModel,
                            onDone = close,
                            paymentRequest = remember(swap.payinAddress, sompi) {
                                com.kachat.app.models.PaymentRequest.create(address = swap.payinAddress, amountSompi = sompi, memo = "", toChattingAddress = false)
                            },
                            onSent = { txId -> service.recordDepositSent(swap.id, txId) },
                            onOutcomeUnknown = { txId -> service.recordDepositSent(swap.id, txId) },
                        )
                    }
                }
            }
        }

        DepositCard {
            Text(stringResource(R.string.buysell_transaction_id), color = colors.textSecondary, fontSize = 12.sp)
            Text(swap.id, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                DepositCopyButton(stringResource(R.string.buysell_copy_id)) { copy(swap.id, context.getString(R.string.swap_exchange_id_copied)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.swap_view_on_changenow),
                    color = KaspaTeal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { try { uriHandler.openUri(changeNowTxUrl(swap.id)) } catch (e: Exception) {} },
                )
            }
        }

        if (swap.toAmount.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.buysell_you_get_about,
                    KaspaAmountGrouping.grouped(swap.toAmount),
                    to.displayName,
                    "${swap.payoutAddress.take(12)}...${swap.payoutAddress.takeLast(6)}",
                ),
                color = colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DepositCard(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().iosGlass(18.dp, shadowAlpha = 0f).padding(16.dp),
    ) { content() }
}

/** iOS's copy capsule: the doc.on.doc symbol and its title, accent on a light accent capsule. */
@Composable
private fun DepositCopyButton(title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
        Text(title, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Where the order is: waiting for payment until ChangeNOW sees it, then each step. */
@Composable
private fun DepositStatusCard(status: String, to: SwapCoin) {
    val colors = LocalAppColors.current
    val stage = DepositStage.of(status)
    val title: String
    val detail: String
    when (stage) {
        DepositStage.CONFIRMING -> {
            title = stringResource(R.string.buysell_payment_received)
            detail = stringResource(R.string.buysell_waiting_confirm)
        }
        DepositStage.EXCHANGING -> {
            title = stringResource(R.string.swap_status_exchanging)
            detail = stringResource(R.string.buysell_converting)
        }
        DepositStage.SENDING -> {
            title = stringResource(R.string.buysell_sending_your, to.displayName)
            detail = stringResource(R.string.buysell_almost_done)
        }
        DepositStage.FAILED -> {
            title = swapStatusName(status)
            detail = stringResource(R.string.buysell_failed_detail)
        }
        DepositStage.REFUNDED -> {
            title = swapStatusName(status)
            detail = stringResource(R.string.buysell_refunded_detail)
        }
        DepositStage.EXPIRED -> {
            title = swapStatusName(status)
            detail = stringResource(R.string.buysell_expired_detail)
        }
        DepositStage.VERIFYING -> {
            title = swapStatusName(status)
            detail = stringResource(R.string.buysell_verifying_detail)
        }
        DepositStage.WAITING -> {
            title = stringResource(R.string.buysell_waiting_payment)
            detail = stringResource(R.string.buysell_waiting_payment_detail)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().iosGlass(18.dp, shadowAlpha = 0f).padding(16.dp),
    ) {
        if (stage.isProblem) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = SwapOrange, modifier = Modifier.size(22.dp))
        } else {
            IosActivityIndicator(modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

/** A ChangeNOW order that finished (iOS `SwapCompleteScreen`): Purchase Complete or Sale Complete,
 *  what went in and what came out, Add to Portfolio, and Done. */
@Composable
private fun SwapCompleteScreen(service: SwapService, swapId: String, onAddToPortfolio: (SwapTransaction) -> Unit, onDone: () -> Unit) {
    val colors = LocalAppColors.current
    val swap = service.history.firstOrNull { it.id == swapId }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SwapGreen, modifier = Modifier.size(72.dp))
        Text(
            stringResource(if (swap?.toTicker == "kas") R.string.buysell_purchase_complete else R.string.buysell_sale_complete),
            color = colors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (swap != null) {
            val from = swapCoinFor(swap.fromTicker, swap.fromNetwork)
            val to = swapCoinFor(swap.toTicker, swap.toNetwork)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.buysell_you_paid, KaspaAmountGrouping.grouped(swap.fromAmount), from.displayName),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
                if (swap.toAmount.isNotEmpty()) {
                    Text(
                        stringResource(R.string.buysell_you_got_about, KaspaAmountGrouping.grouped(swap.toAmount), to.displayName),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (swap != null) {
            val added = swap.addedToPortfolio
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(if (added) colors.surfaceVariant else KaspaTeal)
                    .clickable(enabled = !added) { onAddToPortfolio(swap) },
            ) {
                Icon(
                    if (added) Icons.Filled.Check else Icons.Filled.PieChart,
                    contentDescription = null,
                    tint = if (added) colors.textSecondary else Color.Black,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(if (added) R.string.buysell_added_to_portfolio else R.string.kp_add_to_portfolio_9eb23),
                    color = if (added) colors.textSecondary else Color.Black,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        SwapBottomButton(text = stringResource(R.string.done), enabled = true, filled = false, onClick = onDone)
    }
}

/** A kUSDT order Trestly's pool took (iOS `SwapSentScreen`): what it says, and Done. */
@Composable
private fun SwapSentScreen(message: String, onDone: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SwapGreen, modifier = Modifier.size(72.dp))
        Text(stringResource(R.string.buysell_order_sent), color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        SwapBottomButton(text = stringResource(R.string.done), enabled = true, onClick = onDone)
    }
}

/** iOS `swapCoinIcon`: kUSDT's token icon, the Kaspa logo, a coin's brand mark (Tangem's art, iOS
 *  ed8f4575), else its ticker in a plain circle. */
@Composable
internal fun SwapCoinIcon(coin: SwapCoin, size: Dp = 20.dp) {
    when {
        coin.isTrestly -> TokenIcon(WalletToken.KUSDT, size = size)
        coin.ticker == "kas" -> Image(painterResource(R.drawable.ic_kaspa_logo), contentDescription = null, modifier = Modifier.size(size))
        else -> {
            val logo = swapCoinLogo(coin.ticker)
            if (logo != null) {
                Image(painterResource(logo), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size).clip(CircleShape))
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(size).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.3f)),
                ) {
                    Text(coin.ticker.uppercase(), color = LocalAppColors.current.textPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

private fun swapCoinLogo(ticker: String): Int? = when (ticker) {
    "btc" -> R.drawable.ic_coin_btc
    "eth" -> R.drawable.ic_coin_eth
    "sol" -> R.drawable.ic_coin_sol
    "xrp" -> R.drawable.ic_coin_xrp
    "bnb" -> R.drawable.ic_coin_bnb
    "trx" -> R.drawable.ic_coin_trx
    "hype" -> R.drawable.ic_coin_hype
    "doge" -> R.drawable.ic_coin_doge
    "ltc" -> R.drawable.ic_coin_ltc
    "ada" -> R.drawable.ic_coin_ada
    "bch" -> R.drawable.ic_coin_bch
    "etc" -> R.drawable.ic_coin_etc
    "usdc" -> R.drawable.ic_coin_usdc
    "usdt" -> R.drawable.ic_coin_usdt
    "zec" -> R.drawable.ic_coin_zec
    "xmr" -> R.drawable.ic_coin_xmr
    else -> null
}

/** iOS: a plain monospaced field in a 14-cornered glass card. */
@Composable
private fun SwapPlainField(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType) {
    val colors = LocalAppColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp, fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrect = false),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(KaspaTeal),
        modifier = Modifier.fillMaxWidth().iosGlass(14.dp, shadowAlpha = 0f).padding(12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = colors.textSecondary.copy(alpha = 0.6f), fontSize = 17.sp, fontFamily = FontFamily.Monospace)
            inner()
        },
    )
}

/** iOS `spendingAddressRow`: the title and the address on the left, "Change" on the right. */
@Composable
private fun SpendingAddressRow(title: String, value: String, onChange: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().iosGlass(14.dp, shadowAlpha = 0f).padding(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textSecondary, fontSize = 11.sp)
            Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.swap_change),
            color = KaspaTeal,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onChange),
        )
    }
}

private fun shortAddress(address: String): String =
    if (address.length > 20) "${address.take(12)}...${address.takeLast(6)}" else address

/** Deposit QRs already made, by their text and pixel size (iOS 01f71184 `swapQRCache`). */
private val swapQrCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(8)

/** A swap's deposit QR as black on white pixels, [sizePx] square; null for no text. */
private fun makeSwapQr(text: String, sizePx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    if (text.isEmpty() || sizePx <= 0) return null
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0),
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap.asImageBitmap()
}

/** A swap's deposit QR, made once off the main thread (iOS 01f71184 `SwapQRCodeView`, smoothness
 *  audit L6), not while the deposit screen opens. Blank until it's made. */
@Composable
private fun SwapQrCode(value: String, onClick: (() -> Unit)? = null, size: Int = 180) {
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { size.dp.roundToPx() }
    val key = "$sizePx|$value"
    val image by produceState(initialValue = swapQrCache.get(key), key) {
        if (this.value != null) return@produceState
        val made = withContext(Dispatchers.Default) {
            try { makeSwapQr(value, sizePx) } catch (e: Exception) { null }
        }
        if (made != null) swapQrCache.put(key, made)
        this.value = made
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(androidx.compose.ui.graphics.painter.BitmapPainter(bitmap, filterQuality = androidx.compose.ui.graphics.FilterQuality.None),
                contentDescription = null, modifier = Modifier.size(size.dp))
        } else {
            Spacer(Modifier.size(size.dp))
        }
    }
}

/** ChangeNOW's deposit tag (XRP's destination tag), which the deposit must carry: shown large, with
 *  Copy and why it's required, on the deposit card and in Swap Details (iOS audit 2026-11-24 #1). */
@Composable
private fun SwapDepositTagBox(tag: String, onCopy: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SwapOrange.copy(alpha = 0.14f))
            .border(1.dp, SwapOrange.copy(alpha = 0.6f), shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = SwapOrange, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.swap_destination_tag_memo), color = SwapOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(tag, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.swap_destination_tag_required), color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable(onClick = onCopy),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.swap_copy_destination_tag), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Status

/** ChangeNOW's status, in the app's language (iOS audit 2026-11-24 #35). */
@Composable
private fun swapStatusName(status: String): String {
    val id = swapStatusResource(status) ?: return status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    return stringResource(id)
}

private fun swapStatusResource(status: String): Int? = when (status.lowercase()) {
    "new" -> R.string.swap_status_new
    "waiting" -> R.string.swap_status_waiting
    "confirming" -> R.string.swap_status_confirming
    "exchanging" -> R.string.swap_status_exchanging
    "sending" -> R.string.swap_status_sending
    "finished" -> R.string.swap_status_finished
    "failed" -> R.string.swap_status_failed
    "refunded" -> R.string.swap_status_refunded
    "verifying" -> R.string.swap_status_verifying
    "expired" -> R.string.swap_status_expired
    else -> null
}

private fun swapStatusColor(status: String): Color = when (status) {
    "finished" -> SwapGreen
    "failed", "refunded" -> SwapRed
    else -> SwapOrange
}

// History

@Composable
private fun SwapHistoryPage(history: List<SwapTransaction>, onOpen: (SwapTransaction) -> Unit, onDelete: (SwapTransaction) -> Unit) {
    val colors = LocalAppColors.current
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.swap_no_swaps_yet), color = colors.textSecondary)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        items(history, key = { it.id }) { swap ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .iosGlass(18.dp, shadowAlpha = 0f)
                        .clickable { onOpen(swap) }
                        .padding(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(swapSummary(swap), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        // which service ran it: kUSDT swaps go through Trestly's pool on Kaspa
                        Text("${swapDate(swap.createdAtMillis)} · ${swapProviderName(swap)}", color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Text(swapStatusName(swap.status), color = swapStatusColor(swap.status), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                val deleteLabel = stringResource(R.string.delete)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onDelete(swap) }
                        .semantics { contentDescription = deleteLabel },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = SwapRed, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "50 USDC → 1234.00000000 KAS" (iOS's row: the typed amount as is, the quoted one to 8 places);
 *  a kUSDT swap's both as typed ("100 KAS → 2.61 kUSDT", iOS b49a021d). */
internal fun swapSummary(swap: SwapTransaction): String =
    "${swap.fromAmount} ${SwapTransaction.displayTicker(swap.fromTicker)} → " +
        "${if (swap.isTrestly) swap.toAmount else formatDecimal(swap.toAmount)} ${SwapTransaction.displayTicker(swap.toTicker)}"

/** Which service ran it, on its history row (iOS b49a021d): a brand name, never translated. */
internal fun swapProviderName(swap: SwapTransaction): String = if (swap.isTrestly) "Trestly" else "ChangeNOW"

private fun formatDecimal(text: String): String = text.toDoubleOrNull()?.let { String.format(Locale.US, "%.8f", it) } ?: text

/** iOS `Text(date, style: .date)`: the long date in this phone's language. */
private fun swapDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))

// Swap Details

/** iOS `SwapDetailView`: one past swap, its deposit QR again, its live status, ChangeNOW's id. */
@Composable
private fun SwapDetailPage(
    swap: SwapTransaction,
    service: SwapService,
    kaspaExplorer: com.kachat.app.models.KaspaExplorer,
    onDone: () -> Unit,
    onAddToPortfolio: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)
    fun copyAddress(address: String) {
        clipboard.setText(AnnotatedString(address))
        showAddressCopiedToast(context, address)
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        IosSheetNavBar(
            title = stringResource(R.string.swap_details),
            trailing = { IosBarTextButton(stringResource(R.string.done), onClick = onDone, bold = true) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            DetailSection(null) {
                Text(swapSummary(swap), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(swapDate(swap.createdAtMillis), color = colors.textSecondary, fontSize = 12.sp)
                if (!swap.isTrestly) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { SwapQrCode(swap.payinAddress) }
                }
            }
            @Composable
            fun statusSection() {
                DetailSection(stringResource(R.string.status)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(swapStatusName(swap.status), color = swapStatusColor(swap.status), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (swap.status == "finished") {
                            Text(
                                stringResource(R.string.kp_add_to_portfolio_9eb23),
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = onAddToPortfolio),
                            )
                        }
                    }
                }
            }
            @Composable
            fun refreshRow(failure: Int) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (isRefreshing) return@clickable
                        isRefreshing = true
                        scope.launch {
                            val status = service.refreshSwapStatusNow(swap.id)
                            isRefreshing = false
                            IosToasts.show(
                                if (status != null) context.getString(R.string.swap_status_value, swapStatusResource(status)?.let { context.getString(it) } ?: status)
                                else context.getString(failure)
                            )
                        }
                    }.padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.swap_refresh_status), color = KaspaTeal, modifier = Modifier.weight(1f))
                    if (isRefreshing) IosActivityIndicator(modifier = Modifier.size(16.dp))
                }
            }
            if (swap.isTrestly) {
                // A kUSDT swap through Trestly's pool on Kaspa (iOS b49a021d `trestlySections`): the
                // address it traded from, its status, and its Kaspa transaction. No deposit address
                // or exchange id: nothing was sent to a third party.
                DetailSection(stringResource(R.string.swap_swapped_through)) {
                    Text(stringResource(R.string.swap_trestly_kusdt_pool), color = colors.textPrimary)
                }
                DetailSection(stringResource(R.string.address)) {
                    Text(
                        swap.payoutAddress,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clickable { copyAddress(swap.payoutAddress) },
                    )
                }
                statusSection()
                DetailSection(stringResource(R.string.transaction_id)) {
                    Text(
                        swap.id,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString(swap.id))
                            haptic(IosHaptic.SUCCESS)
                            IosToasts.show(context.getString(R.string.transaction_id_copied))
                        },
                    )
                }
                DetailSection(null) {
                    refreshRow(R.string.swap_couldnt_reach_network)
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    Text(
                        stringResource(R.string.view_in_explorer),
                        color = KaspaTeal,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val url = kaspaExplorer.txUrl(swap.id)
                            // K's own Explorer (iOS bfb902b7): this sheet closes, or its page would open behind it.
                            if (com.kachat.app.services.explorer.ExplorerDeepLink.openIfLink(url)) onDone()
                            else try { uriHandler.openUri(url) } catch (e: Exception) {}
                        }.padding(vertical = 4.dp),
                    )
                }
                return@Column
            }
            DetailSection(stringResource(R.string.swap_deposit_address)) {
                Text(
                    swap.payinAddress,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable { copyAddress(swap.payinAddress) },
                )
            }
            val tag = swap.payinExtraId
            if (!tag.isNullOrEmpty()) {
                SwapDepositTagBox(tag) {
                    clipboard.setText(AnnotatedString(tag))
                    haptic(IosHaptic.SUCCESS)
                    IosToasts.show(context.getString(R.string.swap_destination_tag_copied))
                }
            }
            DetailSection(stringResource(R.string.swap_payout_address)) {
                Text(
                    swap.payoutAddress,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable { copyAddress(swap.payoutAddress) },
                )
                val payoutTag = swap.payoutExtraId
                if (!payoutTag.isNullOrEmpty()) {
                    Text(stringResource(R.string.swap_destination_tag_value, payoutTag), color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            statusSection()
            DetailSection(stringResource(R.string.swap_exchange_id)) {
                Text(
                    swap.id,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable {
                        clipboard.setText(AnnotatedString(swap.id))
                        haptic(IosHaptic.SUCCESS)
                        IosToasts.show(context.getString(R.string.swap_exchange_id_copied))
                    },
                )
            }
            DetailSection(null) {
                refreshRow(R.string.swap_couldnt_reach_changenow)
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                Text(
                    stringResource(R.string.swap_view_on_changenow),
                    color = KaspaTeal,
                    modifier = Modifier.fillMaxWidth().clickable {
                        try { uriHandler.openUri(changeNowTxUrl(swap.id)) } catch (e: Exception) {}
                    }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** A Form section: an optional small caps header over a rounded card. */
@Composable
private fun DetailSection(header: String?, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(header.uppercase(), color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        ) { content() }
    }
}

// Swap From (kUSDT)

/**
 * iOS `KusdtSwapFromPicker`: where a kUSDT swap trades from (and lands): the Chatting Address, every
 * address holding kUSDT, or a regular spending address holding KAS. Never a quantum one: Trestly's
 * code trades only from plain Schnorr addresses.
 */
@Composable
private fun KusdtSwapFromPicker(viewModel: SwapViewModel, current: String?, onCancel: () -> Unit, onPick: (TrestlyTarget?) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val walletViewModel: com.kachat.app.viewmodels.WalletViewModel = hiltViewModel()
    val chatting by walletViewModel.address.collectAsState()
    val chattingSompi by walletViewModel.balanceSompi.collectAsState()
    val tokenWallet = viewModel.tokenWallet
    val holdingsByToken by tokenWallet.holdingsFlow.collectAsState()
    val held = remember(holdingsByToken) { tokenWallet.holdings(WalletToken.KUSDT) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(held) {
        labels = withContext(Dispatchers.Default) { held.associate { it.id to tokenWallet.label(it) } }
    }
    var entries by remember { mutableStateOf<List<WalletService.SpendingAddressEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = viewModel.spendingAddresses() }
    BackHandler(onBack = onCancel)

    val chattingLabel = stringResource(R.string.chatting_address)
    val choices = remember(chatting, held, labels, entries, chattingLabel) {
        val list = ArrayList<TrestlyTarget>()
        chatting?.let { address -> list += TrestlyTarget(chattingLabel, address, null, held.firstOrNull { it.isChatting }?.units ?: 0L) }
        for (holding in held) {
            if (holding.isChatting || list.any { it.address == holding.address }) continue
            list += TrestlyTarget(labels[holding.id] ?: holding.address, holding.address, holding.spendingIndex, holding.units)
        }
        entries.filter { !it.isQuantum && it.balanceSompi > 0 }.sortedBy { it.index }.forEach { entry ->
            if (list.none { it.address == entry.address }) list += TrestlyTarget(entry.displayLabel, entry.address, entry.index)
        }
        list
    }
    fun kas(address: String): Long =
        if (address == chatting) chattingSompi else entries.firstOrNull { it.address == address }?.balanceSompi ?: 0L

    Column(Modifier.fillMaxSize().background(colors.background)) {
        IosSheetNavBar(
            title = stringResource(R.string.swap_from),
            leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = onCancel) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text(stringResource(R.string.swap_from_picker_note), color = colors.textSecondary, fontSize = 13.sp)
            choices.forEach { choice ->
                val selected = choice.address == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .iosGlass(18.dp)
                        .clickable {
                            haptic(IosHaptic.IMPACT_LIGHT)
                            onPick(if (choice.spendingIndex == null) null else choice)
                        }
                        .padding(14.dp),
                ) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (selected) KaspaTeal else colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(choice.label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (choice.address.length > 24) "${choice.address.take(14)}...${choice.address.takeLast(8)}" else choice.address,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${KaspaUnit.plain(kas(choice.address))} ${KaspaUnit.symbol} · ${TokenAmount.amount(choice.units, WalletToken.KUSDT)}",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

// Before You Swap

private const val CHANGENOW_TERMS_URL = "https://changenow.io/terms-of-use"
/** "Powered by ChangeNOW" opens ChangeNOW's own terms page (iOS SwapView). */
private const val CHANGENOW_TERMS_SHORT_URL = "https://changenow.io/terms-of-use/changenow-terms"

private fun changeNowTxUrl(id: String) = "https://changenow.io/exchange/txs/$id"

/** `/terms` on the Trestly site set in Settings > Connection (trestly.io on mainnet). */
internal fun trestlyTermsUrl(context: android.content.Context): String {
    val site = try { java.net.URI(TrestlySite.siteUrl(context).trim()) } catch (e: Exception) { null }
    val scheme = site?.scheme ?: "https"
    val host = site?.host ?: "trestly.io"
    val port = site?.port?.takeIf { it > 0 }?.let { ":$it" } ?: ""
    return "$scheme://$host$port/terms"
}

/**
 * iOS `SwapAgreementsView` ("Before You Swap"): shown before Swap opens until both are accepted on
 * this account. ChangeNOW's for coins on other networks and Trestly's for kUSDT, each a third party
 * K doesn't run, each with its terms and its own checkbox; Continue unlocks when both are checked.
 */
@Composable
private fun SwapAgreementsPage(onCancel: () -> Unit, onAccept: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var changeNowChecked by rememberSaveable { mutableStateOf(false) }
    var trestlyChecked by rememberSaveable { mutableStateOf(false) }
    val ready = changeNowChecked && trestlyChecked

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        IosSheetNavBar(
            title = stringResource(R.string.swap_before_you_swap),
            leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = onCancel) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text(stringResource(R.string.swap_agreements_intro), color = colors.textSecondary, fontSize = 15.sp)
            AgreementCard(
                title = "ChangeNOW",
                subtitle = stringResource(R.string.swap_coins_on_other_networks),
                icon = { Icon(Icons.Filled.SwapHorizontalCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(34.dp)) },
                text = stringResource(R.string.swap_changenow_agreement),
                termsTitle = stringResource(R.string.swap_read_changenow_terms),
                termsUrl = CHANGENOW_TERMS_URL,
                checkboxText = stringResource(R.string.swap_changenow_checkbox),
                checked = changeNowChecked,
                onToggle = { changeNowChecked = !changeNowChecked },
            )
            AgreementCard(
                title = "Trestly",
                subtitle = stringResource(R.string.swap_kusdt_on_kaspa),
                icon = { TokenIcon(WalletToken.KUSDT, size = 30.dp) },
                text = stringResource(R.string.swap_trestly_agreement),
                termsTitle = stringResource(R.string.swap_read_trestly_terms),
                termsUrl = remember { trestlyTermsUrl(context) },
                checkboxText = stringResource(R.string.swap_trestly_checkbox),
                checked = trestlyChecked,
                onToggle = { trestlyChecked = !trestlyChecked },
            )
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(KaspaTeal.copy(alpha = if (ready) 1f else 0.4f))
                    .clickable(enabled = ready, onClick = onAccept),
            ) {
                Text(stringResource(R.string.continue_label), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AgreementCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    text: String,
    termsTitle: String,
    termsUrl: String,
    checkboxText: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val haptic = rememberHaptics()
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().iosGlass(20.dp).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Text(text, color = colors.textSecondary, fontSize = 15.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable { try { uriHandler.openUri(termsUrl) } catch (e: Exception) {} },
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
            Text(termsTitle, color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                ) {
                    haptic(IosHaptic.SELECTION)
                    onToggle()
                },
        ) {
            Icon(
                if (checked) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) KaspaTeal else colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            Text(checkboxText, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
