package com.kachat.app.ui.screens.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransaction
import com.kachat.app.services.SwapService
import com.kachat.app.services.WalletService
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TrestlySite
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.components.CycleFade
import com.kachat.app.ui.components.PageCycleBar
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.ActionSheetRow
import com.kachat.app.ui.screens.BalanceTopBarLabel
import com.kachat.app.ui.screens.CoverDock
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosSearchField
import com.kachat.app.ui.screens.IosSheetNavBar
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.IosToolbarRow
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.LargeTitleSize
import com.kachat.app.ui.screens.SheetBarButton
import com.kachat.app.ui.screens.rememberQrBitmapPainter
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
import kotlinx.coroutines.Dispatchers
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
 * Swap, full screen over the Wallet tab (iOS 518fdc8c `.fullScreenCover { SwapView() }`): up from
 * the bottom, the dock out of the way while it shows, Back or Done to close.
 */
@Composable
fun SwapCover(visible: Boolean, onDismiss: () -> Unit, navController: NavController? = null) {
    AnimatedVisibility(
        visible = visible,
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
            SwapScreen(onDone = onDismiss, navController = navController)
        }
    }
}

/**
 * iOS `SwapView`: "Before You Swap" until both agreements are accepted on this account, then Swap
 * itself (You Send, flip, You Get; Swap History behind the page cycle button).
 */
@Composable
fun SwapScreen(onDone: () -> Unit, navController: NavController? = null, viewModel: SwapViewModel = hiltViewModel()) {
    val swap = viewModel.service
    val haptic = rememberHaptics()
    if (swap.agreementsAccepted) {
        SwapMain(viewModel, onDone, navController)
    } else {
        SwapAgreementsPage(onCancel = onDone, onAccept = {
            haptic(IosHaptic.SUCCESS)
            swap.acceptAgreements()
        })
    }
}

@Composable
private fun SwapMain(viewModel: SwapViewModel, onDone: () -> Unit, navController: NavController?) {
    val swap = viewModel.service
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = rememberHaptics()
    val portfolioViewModel: PortfolioViewModel = hiltViewModel()
    val priceViewModel: KasPriceViewModel = hiltViewModel()
    val currency by priceViewModel.currency.collectAsState()

    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedSwapId by remember { mutableStateOf<String?>(null) }
    var showToAddressPicker by remember { mutableStateOf(false) }
    var showCoinPicker by remember { mutableStateOf(false) }
    var showKusdtFromPicker by remember { mutableStateOf(false) }
    var pendingDeleteSwap by remember { mutableStateOf<SwapTransaction?>(null) }
    var pendingPortfolio by remember { mutableStateOf<Pair<SwapService.PortfolioPrefill, String>?>(null) }

    // a kUSDT swap that went through
    LaunchedEffect(swap.notice) {
        val notice = swap.notice ?: return@LaunchedEffect
        haptic(IosHaptic.SUCCESS)
        IosToasts.show(notice)
        swap.notice = null
    }
    LaunchedEffect(swap.createSwapState.status) {
        when (swap.createSwapState.status) {
            SwapService.CreateSwapStatus.SUCCESS -> IosToasts.show(context.getString(R.string.swap_started))
            SwapService.CreateSwapStatus.FAILED ->
                IosToasts.show(swap.createSwapState.errorMessage ?: context.getString(R.string.swap_failed))
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // iOS: Done (cancellationAction), the balance as the principal item, the large title "Swap"
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            IosToolbarRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                leading = { SheetBarButton(stringResource(R.string.done), onClick = onDone) },
                principal = { BalanceTopBarLabel() },
                trailing = {},
            )
            Text(
                stringResource(R.string.swap_title),
                color = colors.textPrimary,
                fontSize = LargeTitleSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 5.dp),
            )
        }
        // the app's cycle button (iOS `PageCycleBar`, 2026-11-24)
        PageCycleBar(
            titles = listOf(stringResource(R.string.swap_title), stringResource(R.string.swap_history)),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        CycleFade(tab, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                SwapFormPage(
                    swap = swap,
                    onPickCoin = { showCoinPicker = true },
                    onPickToAddress = { showToAddressPicker = true },
                    onPickKusdtFrom = { showKusdtFromPicker = true },
                    onGoToSpendingAddresses = {
                        onDone()
                        navController?.navigate("manage_addresses")
                    },
                )
            } else {
                SwapHistoryPage(
                    history = swap.history,
                    onOpen = { selectedSwapId = it.id },
                    onDelete = { pendingDeleteSwap = it },
                )
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
                SwapDetailPage(
                    swap = detail,
                    service = swap,
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
    if (showToAddressPicker) {
        IosFullSheet(onDismissed = { showToAddressPicker = false }, swipeToDismiss = true, grouped = true) { close ->
            SwapAddressPicker(viewModel = viewModel, onCancel = close, onPick = { entry ->
                swap.selectToSpendingAddress(entry.index)
                close()
            })
        }
    }
    if (showCoinPicker) {
        IosFullSheet(onDismissed = { showCoinPicker = false }, swipeToDismiss = true) { close ->
            SwapCoinPicker(currentCoin = swap.otherCoin, onCancel = close, onPick = { coin ->
                swap.chooseOtherCoin(coin)
                close()
            })
        }
    }
    // Add to Portfolio: which portfolio, each row saying when the swap is already in it
    pendingPortfolio?.let { (prefill, swapId) ->
        val portfolios by portfolioViewModel.portfolios.collectAsState()
        val duplicates = remember(swapId, portfolios) { portfolioViewModel.portfolioIdsContaining(SwapService.swapSourceTxId(swapId)) }
        val amount = KaspaUnit.plain(Math.round(prefill.amountKas * 100_000_000))
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

// The swap form

@Composable
private fun SwapFormPage(
    swap: SwapService,
    onPickCoin: () -> Unit,
    onPickToAddress: () -> Unit,
    onPickKusdtFrom: () -> Unit,
    onGoToSpendingAddresses: () -> Unit,
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
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
        // Both cards take input: type under "You Send" and "You Get" is quoted; type under
        // "You Get" and the reverse quote fills "You Send" with what that costs.
        // kUSDT swaps spend what K holds: Max, and the balance beside "You Send" (iOS 6b99c6dd,
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
            label = stringResource(R.string.swap_you_send),
            coin = swap.fromCoin,
            amountText = swap.amountText,
            isQuoting = swap.editedSide == SwapService.AmountSide.GET && loading,
            onAmountChange = { swap.typeAmount(it) },
            onCoinClick = onPickCoin,
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val switchLabel = stringResource(R.string.swap_switch_direction)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .clickable { swap.flipDirection() }
                    .semantics { contentDescription = switchLabel },
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (canSwap) KaspaTeal else colors.surfaceVariant)
                    .clickable(enabled = canSwap) { swap.executeSwap() },
            ) {
                if (isBusy) {
                    IosActivityIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
                } else {
                    Text(
                        stringResource(if (swap.isKusdt) R.string.swap_title else R.string.swap_get_deposit_address),
                        color = if (canSwap) Color.Black else colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                }
            }
        }

        SwapAmountCard(
            label = stringResource(R.string.swap_you_get),
            coin = toCoin,
            amountText = swap.receiveAmountText,
            isQuoting = swap.editedSide == SwapService.AmountSide.SEND && loading,
            onAmountChange = { swap.typeReceiveAmount(it) },
            onCoinClick = onPickCoin,
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

        if (swap.isKusdt) {
            SpendingAddressRow(
                title = stringResource(if (swap.kasIsSendSide) R.string.swap_from_kusdt_lands else R.string.swap_from_kas_lands),
                value = swap.kusdtTarget?.let { "${it.label} · ${shortAddress(it.address)}" } ?: "",
                onChange = onPickKusdtFrom,
            )
        } else if (!swap.kasIsSendSide) {
            SpendingAddressRow(
                title = stringResource(R.string.swap_receiving_kas_at),
                value = shortAddress(swap.toAddress),
                onChange = onPickToAddress,
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

        val created = swap.createSwapState.result
        if (!swap.isKusdt && created != null) {
            SwapResultCard(swap, created, onGoToSpendingAddresses)
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

/**
 * The deposit card, drawn from the exchange ChangeNOW made (its coins, amount, addresses and tag),
 * never from the coins picked now (iOS audit 2026-11-24 #2). Its status is the live history
 * entry's, so Refresh Status shows.
 */
@Composable
private fun SwapResultCard(service: SwapService, created: SwapService.CreatedSwap, onGoToSpendingAddresses: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val haptic = rememberHaptics()
    val swap = service.history.firstOrNull { it.id == created.transaction.id } ?: created.transaction
    fun copyAddress(address: String) {
        clipboard.setText(AnnotatedString(address))
        showAddressCopiedToast(context, address)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().iosGlass(18.dp, shadowAlpha = 0f).padding(16.dp),
    ) {
        if (created.fromCoin == SwapCoin.KAS) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onGoToSpendingAddresses() }.padding(bottom = 2.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.swap_go_to_spending_addresses), color = KaspaTeal, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
            }
        }
        Text(stringResource(R.string.swap_send_coin_to_address, created.fromCoin.displayName), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.swap_amount_to_send), color = colors.textSecondary, fontSize = 12.sp)
            Text("${swap.fromAmount} ${created.fromCoin.displayName}", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SwapQrCode(swap.payinAddress, onClick = { copyAddress(swap.payinAddress) })
        }
        Text(
            swap.payinAddress,
            color = colors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.clickable { copyAddress(swap.payinAddress) },
        )
        val tag = swap.payinExtraId
        if (!tag.isNullOrEmpty()) {
            SwapDepositTagBox(tag) {
                clipboard.setText(AnnotatedString(tag))
                haptic(IosHaptic.SUCCESS)
                IosToasts.show(context.getString(R.string.swap_destination_tag_copied))
            }
        }
        // where ChangeNOW pays out, as it answered (iOS audit 2026-11-24 #6)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.swap_payout_address), color = colors.textSecondary, fontSize = 12.sp)
            Text(swap.payoutAddress, color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            val payoutTag = swap.payoutExtraId
            if (!payoutTag.isNullOrEmpty()) {
                Text(stringResource(R.string.swap_destination_tag_value, payoutTag), color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Text(stringResource(R.string.swap_status_value, swapStatusName(swap.status)), color = colors.textSecondary, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.swap_exchange_id), color = colors.textSecondary, fontSize = 12.sp)
            Text(
                swap.id,
                color = colors.textPrimary,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(swap.id))
                    haptic(IosHaptic.SUCCESS)
                    IosToasts.show(context.getString(R.string.swap_exchange_id_copied))
                },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.swap_refresh_status),
                color = KaspaTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { service.refreshSwapStatus(swap.id) },
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.swap_view_on_changenow),
                color = KaspaTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    try { uriHandler.openUri(changeNowTxUrl(swap.id)) } catch (e: Exception) {}
                },
            )
        }
    }
}

/** One amount card. [isQuoting] marks the card whose figure is being fetched for the other card's
 *  input: a spinner where the number will land. Only the non-KAS side's coin is pickable. */
@Composable
private fun SwapAmountCard(
    label: String,
    coin: SwapCoin,
    amountText: String,
    isQuoting: Boolean,
    onAmountChange: (String) -> Unit,
    onCoinClick: () -> Unit,
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
                        onValueChange = onAmountChange,
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
            CoinBadge(coin, onClick = if (coin.ticker == "kas") null else onCoinClick)
        }
    }
}

@Composable
private fun CoinBadge(coin: SwapCoin, onClick: (() -> Unit)?) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
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
        if (onClick != null) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
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

@Composable
private fun SwapQrCode(value: String, onClick: (() -> Unit)? = null) {
    val painter = rememberQrBitmapPainter(value, size = 180)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
    ) {
        Image(painter, contentDescription = null, modifier = Modifier.size(180.dp))
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
                        Text(swapDate(swap.createdAtMillis), color = colors.textSecondary, fontSize = 12.sp)
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

/** "50 USDC → 1234.00000000 KAS" (iOS's row: the typed amount as is, the quoted one to 8 places). */
private fun swapSummary(swap: SwapTransaction): String =
    "${swap.fromAmount} ${swap.fromTicker.uppercase()} → ${formatDecimal(swap.toAmount)} ${swap.toTicker.uppercase()}"

private fun formatDecimal(text: String): String = text.toDoubleOrNull()?.let { String.format(Locale.US, "%.8f", it) } ?: text

/** iOS `Text(date, style: .date)`: the long date in this phone's language. */
private fun swapDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))

// Swap Details

/** iOS `SwapDetailView`: one past swap, its deposit QR again, its live status, ChangeNOW's id. */
@Composable
private fun SwapDetailPage(swap: SwapTransaction, service: SwapService, onDone: () -> Unit, onAddToPortfolio: () -> Unit) {
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
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { SwapQrCode(swap.payinAddress) }
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
                                else context.getString(R.string.swap_couldnt_reach_changenow)
                            )
                        }
                    }.padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.swap_refresh_status), color = KaspaTeal, modifier = Modifier.weight(1f))
                    if (isRefreshing) IosActivityIndicator(modifier = Modifier.size(16.dp))
                }
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

// Choose Address

/**
 * iOS `SwapAddressPickerView`: where a swap into KAS pays out. Only fresh addresses are offered:
 * reusing one publishes the link between this swap and everything that address did. A tap asks
 * what to do with it (look at it in the explorer, or use it).
 */
@Composable
private fun SwapAddressPicker(viewModel: SwapViewModel, onCancel: () -> Unit, onPick: (WalletService.SpendingAddressEntry) -> Unit) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val explorer by viewModel.kaspaExplorer.collectAsState(initial = com.kachat.app.models.KaspaExplorer.default)
    var entries by remember { mutableStateOf<List<WalletService.SpendingAddressEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<WalletService.SpendingAddressEntry?>(null) }
    BackHandler(onBack = onCancel)

    suspend fun load() {
        isLoading = true
        entries = viewModel.spendingAddresses()
        isLoading = false
    }
    LaunchedEffect(Unit) { load() }
    val selectable = entries.filter { !it.hidden && !it.everUsed }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        IosSheetNavBar(
            title = stringResource(R.string.swap_choose_address),
            leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = onCancel) },
        )
        if (isLoading && entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IosActivityIndicator(modifier = Modifier.size(24.dp)) }
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isGenerating) {
                            isGenerating = true
                            scope.launch {
                                withContext(Dispatchers.Default) { try { viewModel.generateNextSpendingAddress() } catch (e: Exception) {} }
                                load()
                                isGenerating = false
                            }
                        }
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.generate_new_address), color = KaspaTeal, modifier = Modifier.weight(1f))
                    if (isGenerating) IosActivityIndicator(modifier = Modifier.size(16.dp))
                }
            }
            Text(stringResource(R.string.swap_picker_footer), color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.swap_unused_addresses).uppercase(), color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface)) {
                if (selectable.isEmpty()) {
                    Text(stringResource(R.string.swap_picker_all_used), color = colors.textSecondary, modifier = Modifier.padding(16.dp))
                }
                selectable.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { actionTarget = entry }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.displayLabel, color = colors.textSecondary, fontSize = 12.sp)
                                // the badge stays: the used flag comes from a check that can fail
                                Text(
                                    stringResource(if (entry.everUsed) R.string.swap_used else R.string.swap_unused),
                                    color = if (entry.everUsed) SwapOrange else SwapGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(shortAddress(entry.address), color = colors.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text("${KaspaUnit.plain(entry.balanceSompi)} KAS", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    actionTarget?.let { entry ->
        ActionSheetContainer(
            title = entry.displayLabel,
            subtitle = shortAddress(entry.address),
            onDismiss = { actionTarget = null },
        ) {
            ActionSheetRow(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.view_in_explorer),
                subtitle = stringResource(R.string.swap_check_history_before),
            ) {
                actionTarget = null
                try { uriHandler.openUri(explorer.addressUrl(entry.address)) } catch (e: Exception) {}
            }
            ActionSheetRow(
                icon = Icons.Filled.ArrowCircleDown,
                title = stringResource(R.string.swap_use_for_this_swap),
                subtitle = stringResource(R.string.swap_changenow_pays_out_here),
            ) {
                actionTarget = null
                onPick(entry)
            }
        }
    }
}

// Choose Coin

private sealed class CoinPickerRow(val id: String) {
    class Coin(val coin: SwapCoin) : CoinPickerRow("${coin.ticker}-${coin.network}")
    class Group(val ticker: String, val displayName: String) : CoinPickerRow("group-$ticker")
    class Network(val coin: SwapCoin) : CoinPickerRow("network-${coin.ticker}-${coin.network}")
}

/** Tickers on more than one network: one row that opens in place (iOS `groupedTickers`). */
private val groupedTickers = mapOf("usdt" to "Tether", "usdc" to "USD Coin")

/** USDC and USDT first (in that order), after kUSDT (iOS `pinnedGroupTickers`). */
private val pinnedGroupTickers = listOf("usdc", "usdt")

/** The picker's rows (iOS `rootRows` + `filteredRows`), pure so it can be tested. */
internal fun swapCoinPickerRows(expanded: Set<String>, search: String): List<Pair<String, SwapCoin?>> =
    coinPickerRows(expanded, search).map { row ->
        when (row) {
            is CoinPickerRow.Coin -> row.id to row.coin
            is CoinPickerRow.Network -> row.id to row.coin
            is CoinPickerRow.Group -> row.id to null
        }
    }

private fun coinPickerRows(expanded: Set<String>, search: String): List<CoinPickerRow> {
    // kUSDT first: K swaps it itself, on Kaspa, through Trestly's pool
    val rows = ArrayList<CoinPickerRow>()
    rows += CoinPickerRow.Coin(SwapCoin.KUSDT)
    for (ticker in pinnedGroupTickers) {
        val name = groupedTickers[ticker] ?: continue
        rows += CoinPickerRow.Group(ticker, name)
        if (ticker in expanded) SwapCoin.CURATED.filter { it.ticker == ticker }.forEach { rows += CoinPickerRow.Network(it) }
    }
    SwapCoin.CURATED.filter { groupedTickers[it.ticker] == null }.forEach { rows += CoinPickerRow.Coin(it) }
    if (search.isBlank()) return rows
    val query = search.lowercase()
    return rows.filter { row ->
        when (row) {
            is CoinPickerRow.Coin -> row.coin.displayName.lowercase().contains(query) || row.coin.ticker.lowercase().contains(query)
            is CoinPickerRow.Group -> row.displayName.lowercase().contains(query) || row.ticker.lowercase().contains(query)
            is CoinPickerRow.Network -> row.coin.displayName.lowercase().contains(query) || row.coin.ticker.lowercase().contains(query)
        }
    }
}

/** iOS `SwapCoinPickerView`: searchable, kUSDT first, Tether and USD Coin each one row that opens
 *  to its networks. */
@Composable
private fun SwapCoinPicker(currentCoin: SwapCoin, onCancel: () -> Unit, onPick: (SwapCoin) -> Unit) {
    val colors = LocalAppColors.current
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    BackHandler(onBack = onCancel)
    val rows = remember(expanded, search) { coinPickerRows(expanded, search) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        IosSheetNavBar(
            title = stringResource(R.string.swap_choose_coin),
            leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = onCancel) },
        )
        IosSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = stringResource(R.string.swap_search_coins),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is CoinPickerRow.Coin -> CoinPickerLine(indent = false, coin = row.coin, title = row.coin.displayName, checked = row.coin == currentCoin) { onPick(row.coin) }
                    is CoinPickerRow.Network -> CoinPickerLine(indent = true, coin = row.coin, title = row.coin.displayName, checked = row.coin == currentCoin) { onPick(row.coin) }
                    is CoinPickerRow.Group -> {
                        val isExpanded = row.ticker in expanded
                        val representative = SwapCoin.CURATED.firstOrNull { it.ticker == row.ticker }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = if (isExpanded) expanded - row.ticker else expanded + row.ticker }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            representative?.let { SwapCoinIcon(it) }
                            Text(row.displayName, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            if (currentCoin.ticker == row.ticker) Icon(Icons.Filled.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp).rotate(if (isExpanded) 180f else 0f),
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
            }
        }
    }
}

@Composable
private fun CoinPickerLine(indent: Boolean, coin: SwapCoin, title: String, checked: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (indent) Spacer(Modifier.width(28.dp))
        SwapCoinIcon(coin)
        Text(title, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
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
