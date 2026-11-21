package com.kachat.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.tokens.BalancePagePills
import com.kachat.app.ui.screens.tokens.SendAssetChooser
import com.kachat.app.ui.screens.tokens.SlidingCardPager
import com.kachat.app.ui.screens.tokens.TapToManageLabel
import com.kachat.app.ui.screens.tokens.TokenBalanceCard
import com.kachat.app.ui.screens.tokens.TokenManageScreen
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.KasPriceViewModel
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** What Wallet's three round buttons open (iOS bfe9f022 `WalletView.WalletAction`). */
private enum class WalletAction { SEND, RECEIVE, REQUEST }

/**
 * The Wallet tab (iOS 5b7c772c `WalletView`, laid out as KaPay's Wallet tab): everything this
 * account holds and what you can do with it. The total up top, split into the chatting and
 * spending addresses, swiping to a card per token (kUSDT); Send, Receive and Request under it;
 * Backup Hygiene when due; then the Chatting Address, Your Domains and Notifications as cards.
 * Manage Addresses opens from the balance card's Tap to Manage, a token's Manage screen from its
 * card. Own-address receipts that name no one address land here (MainShell), and a "Received
 * kUSDT" notification opens Manage kUSDT ([WalletViewModel.pendingTokenRoute]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    navController: NavController,
    chatViewModel: ChatViewModel = hiltViewModel(),
    priceViewModel: KasPriceViewModel = hiltViewModel(),
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val address by viewModel.address.collectAsState()
    val chattingSompi by viewModel.balanceSompi.collectAsState()
    /** The chatting balance on the card. Null until it's known, which shows as a dash. */
    val knownChattingSompi by viewModel.knownChattingSompi.collectAsState()
    /** Every revealed spending address together. Null until it's known, which shows as a dash. */
    val spendingTotalSompi by viewModel.spendingTotalSompi.collectAsState()
    val backupConfirmedAt by viewModel.accountBackupStatus.confirmedAt.collectAsState()
    val backupPendingAddresses by viewModel.accountBackupStatus.pendingAddresses.collectAsState()
    var action by remember { mutableStateOf<WalletAction?>(null) }
    var showChattingQr by remember { mutableStateOf(false) }
    /** Wallet's Notifications card, tapped: the list, pushed (iOS 5b7c772c `isPushed`). */
    var showNotifications by remember { mutableStateOf(false) }
    /** A token's page of the balance card, tapped: Manage kUSDT (`TokenManageView`), pushed. */
    var openToken by remember { mutableStateOf<WalletToken?>(null) }
    /** Swap Stablecoins: "Which stablecoin?", then Trestly's site for it (iOS 148a5331
     *  `BuyStablecoinsSheet`). */
    var showSwapStablecoins by remember { mutableStateOf(false) }
    /** The balance card's page: 0 is Kaspa, then one per token (`TokenWallet.tokens`). */
    var balancePage by rememberSaveable { mutableIntStateOf(0) }
    /** The pull's address sweep is still going (it runs on after the spinner ends). */
    val isScanningAddresses by viewModel.isScanningAddresses.collectAsState()
    val tokens = tokenViewModel.wallet.tokens
    // Receive's fresh address: decided before the tap, so Receive opens on a code rather than a
    // spinner, and re-confirmed when the page comes up (shown now: it goes on the Manage Addresses
    // list and is watched for payments). A QR must not change under a pointed camera for any
    // lesser reason, so it isn't cleared first.
    var receiveAddress by remember(address) { mutableStateOf<String?>(null) }
    var receiveResolving by remember(address) { mutableStateOf(true) }
    /** "Address #4": the receive address's name, under Receive Kaspa's title. */
    var receiveLabel by remember(address) { mutableStateOf<String?>(null) }

    LaunchedEffect(address) {
        viewModel.resolveFreshReceiveAddress(revealing = false) { resolved ->
            receiveAddress = resolved
            receiveResolving = false
        }
        // The network part at most every 30 s: switching tabs back and forth would start these
        // requests each time. Arriving funds still refresh at once, below.
        if (viewModel.claimWalletAppearRefresh()) {
            viewModel.refreshBalance()
            viewModel.refreshSpendingAddress()
        }
    }
    LaunchedEffect(action) {
        if (action == WalletAction.RECEIVE) {
            if (receiveAddress == null) receiveResolving = true
            viewModel.resolveFreshReceiveAddress(revealing = true) { resolved ->
                receiveAddress = resolved
                receiveResolving = false
            }
        }
    }
    LaunchedEffect(receiveAddress) {
        receiveLabel = receiveAddress?.let { viewModel.ownAddressLabel(it) }
    }
    // Coalesces the bursts the activity events arrive in (iOS `scheduleRefresh`): each new one
    // restarts the half-second wait.
    LaunchedEffect(Unit) {
        chatViewModel.ownAddressUtxoActivityEvents.collectLatest {
            delay(500L)
            viewModel.refreshBalance()
            viewModel.refreshSpendingAddress()
        }
    }
    // A tapped "Received kUSDT" notification or Notifications row (iOS `takeTokenRoute`): its
    // Manage screen, once the tab is on screen.
    val pendingTokenRoute by viewModel.pendingTokenRoute.collectAsState()
    LaunchedEffect(pendingTokenRoute) {
        if (pendingTokenRoute == null) return@LaunchedEffect
        delay(400L)
        val id = viewModel.takeTokenRoute() ?: return@LaunchedEffect
        showNotifications = false
        openToken = tokens.firstOrNull { it.id == id } ?: WalletToken.KUSDT
    }

    // Pull to refresh (iOS 5b7c772c `scanAndRefresh`, audit #18): the balances are read first and
    // the spinner ends there; then every address is scanned for what it holds (Kaspa's Discover
    // and kUSDT's, side by side), shown under the balance card, and the balances read again.
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshBalanceAndAwait()
            viewModel.refreshSpendingBalanceAndAwait()
            pullRefreshState.endRefresh()
            viewModel.scanAddressesForBalances()
        }
    }

    // Done in Trestly: back to the top of the Wallet tab, whatever it was opened from (iOS 148a5331
    // `.trestlyDone`).
    LaunchedEffect(Unit) {
        com.kachat.app.ui.screens.tokens.TrestlyRoute.done.collect {
            openToken = null
            showSwapStablecoins = false
        }
    }

    // Re-tapping the Wallet tab backs out of a pushed page (see WalletViewModel.notifyTabReselected).
    val tabReselectSignal by viewModel.tabReselectSignal.collectAsState()
    LaunchedEffect(tabReselectSignal) {
        if (tabReselectSignal.second == com.kachat.app.ui.Screen.Wallet.route) {
            showChattingQr = false
            showNotifications = false
            openToken = null
        }
    }

    // The Your Domains card's count: every name the account owns, .kachat, KNS, .k and .kaspa
    // (iOS 5b7c772c `YourDomainsCard`, moved here from Profile).
    val ownedKnsDomains by viewModel.ownedDomains.collectAsState()
    val ownedServiceNames by viewModel.nameServices.owned.collectAsState()
    val kachatRevision by viewModel.kachatRegistryRevision.collectAsState()
    var kachatOwnedCount by remember(address) { mutableIntStateOf(0) }
    LaunchedEffect(address) {
        // cache first, the network only when stale
        viewModel.refreshOwnedDomains()
        // The .k and .kaspa lookups, so the count includes them before the list opens.
        address?.let { viewModel.nameServices.refreshIfStale(it) }
    }
    // Reloads whenever the registry moves (a registration, sale or transfer lands).
    LaunchedEffect(address, kachatRevision) {
        val owner = address ?: return@LaunchedEffect
        viewModel.watchKachatOwnedCount(owner) { kachatOwnedCount = it }
    }
    val domainsCount = kachatOwnedCount + ownedKnsDomains.size + ownedServiceNames.values.sumOf { it.size }

    // Notifications (Kaspa and kUSDT received): its unread count, on the card and the tab's dot.
    val notifCenterVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
    val notifEntries by notifCenterVm.store.entries.collectAsState()
    val notifLastSeen by notifCenterVm.store.lastSeenAt.collectAsState()
    val notifUnread = notifEntries.count { it.timestampMs > notifLastSeen }

    val haptic = com.kachat.app.util.rememberHaptics()
    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // The page runs on under the dock; its scroll ends with room to clear it.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = stringResource(R.string.wallet_title),
                balanceLabel = { BalanceLabelRow(knownChattingSompi?.let { KaspaUnit.amount(it) } ?: "-- ${KaspaUnit.symbol}") },
                banner = { AccountBackupBanner(viewModel) { navController.navigate("account_backup") } },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            val current = address
            if (current != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp + dockInset),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Total Balance, then a page per token, swiped between, with a pill per card
                    // under it (iOS `balancePager`). Plain taps, not a long press: those held the
                    // touch before a swipe could have it.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val pages: List<@Composable () -> Unit> = listOf<@Composable () -> Unit>({
                            WalletBalanceCard(
                                chattingSompi = knownChattingSompi,
                                spendingSompi = spendingTotalSompi,
                                priceViewModel = priceViewModel,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                    navController.navigate("manage_addresses")
                                },
                            )
                        }) + tokens.map { token ->
                            @Composable {
                                TokenBalanceCard(
                                    token = token,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                        openToken = token
                                    },
                                )
                            }
                        }
                        SlidingCardPager(pages = pages, page = balancePage, onPageChange = { balancePage = it })
                        BalancePagePills(
                            titles = listOf(stringResource(R.string.kaspa)) + tokens.map { it.symbol },
                            selected = balancePage,
                            onSelect = { balancePage = it },
                        )
                    }
                    AnimatedVisibility(visible = isScanningAddresses, enter = fadeIn(), exit = fadeOut()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp))
                            Text(
                                stringResource(R.string.wallet_scanning_addresses),
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        WalletActionButton(stringResource(R.string.wallet_send), Icons.Filled.ArrowUpward, Modifier.weight(1f)) { action = WalletAction.SEND }
                        WalletActionButton(stringResource(R.string.wallet_receive), Icons.Filled.ArrowDownward, Modifier.weight(1f)) { action = WalletAction.RECEIVE }
                        WalletActionButton(stringResource(R.string.wallet_request), Icons.Outlined.Description, Modifier.weight(1f)) { action = WalletAction.REQUEST }
                    }
                    if (remember(current, backupConfirmedAt, backupPendingAddresses) { viewModel.accountBackupStatus.isHygieneDue(current) }) {
                        BackupHygieneCard { navController.navigate("backup_hygiene") }
                    }
                    // Testnet only: free TN10 coins for the chatting address, from the official faucet.
                    if (KaspaNetwork.isTestnet) {
                        TestnetFaucetClaimButton(
                            address = current,
                            balanceSompi = { viewModel.balanceSompi.value },
                            refreshBalance = { viewModel.refreshBalanceAndAwait() },
                        )
                    }
                    // The Chatting Address's receive page, as a card in the list (as KaPay has it).
                    WalletLinkCard(
                        label = stringResource(R.string.chatting_address),
                        icon = Icons.Filled.QrCode,
                        onClick = { showChattingQr = true },
                    )
                    // Swap Stablecoins (from KaPay, iOS 148a5331): "Which stablecoin?" first (kUSDT
                    // today), then Trestly's site inside KaChat, connected to the address you pick.
                    WalletLinkCard(
                        label = stringResource(R.string.kp_swap_stablecoins),
                        // SF Symbols "dollarsign.circle"
                        icon = Icons.Outlined.MonetizationOn,
                        onClick = {
                            haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                            showSwapStablecoins = true
                        },
                    )
                    // Moved here from Profile (iOS 5b7c772c YourDomainsCard), as KaPay's Wallet tab has it.
                    WalletLinkCard(
                        label = stringResource(R.string.kns_domains),
                        icon = Icons.Filled.AlternateEmail,
                        onClick = { navController.navigate("kns_domains") },
                        trailing = { WalletCardCount("$domainsCount") },
                    )
                    // Notifications are behind Profile's bell since iOS 148a5331, with KaPost's.
                }
            }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    // Pushed as iOS's NavigationLink pushes it: over the whole screen, the dock left in place.
    PushedQrPage(visible = showChattingQr, white = false, onBack = { showChattingQr = false }) {
        ChattingAddressQrPage(
            address = address ?: "",
            balanceSompi = chattingSompi,
            dismiss = QrPageDismiss.BACK,
            onDismiss = { showChattingQr = false },
        )
    }
    PushedQrPage(visible = showNotifications, white = false, onBack = { showNotifications = false }) {
        GlobalNotificationPage(
            store = notifCenterVm.store,
            walletViewModel = viewModel,
            navController = navController,
            onBack = { showNotifications = false },
        )
    }
    // Manage kUSDT, pushed from its page of the balance card. Kept while it slides away.
    var shownToken by remember { mutableStateOf<WalletToken?>(null) }
    if (openToken != null) shownToken = openToken
    PushedQrPage(visible = openToken != null, white = false, onBack = { openToken = null }) {
        shownToken?.let { token ->
            CompositionLocalProvider(LocalDockInset provides 0.dp) {
                TokenManageScreen(token = token, onBack = { openToken = null }, walletViewModel = viewModel)
            }
        }
    }
    if (showSwapStablecoins) {
        com.kachat.app.ui.screens.tokens.SwapStablecoinsSheet(walletViewModel = viewModel, onDismissed = { showSwapStablecoins = false })
    }
    when (action) {
        // "What are you sending?" first: Kaspa or a token (iOS 5b7c772c SendAssetChooser).
        WalletAction.SEND -> SendAssetChooser(
            kaspaTotal = knownChattingSompi?.let { chatting -> spendingTotalSompi?.let { chatting + it } },
            onDismiss = { action = null },
            walletViewModel = viewModel,
        )
        WalletAction.RECEIVE -> QrPageSheet(onDismiss = { action = null }, white = false) {
            ReceiveKaspaQrPage(
                address = receiveAddress,
                resolving = receiveResolving,
                dismiss = QrPageDismiss.DONE,
                onDismiss = { action = null },
                addressLabel = receiveLabel,
            )
        }
        WalletAction.REQUEST -> CreatePaymentRequestSheet(
            walletViewModel = viewModel,
            onDismissed = { action = null },
        )
        null -> Unit
    }
}

/**
 * The total and its split (iOS 5b7c772c `balanceCard`): "Total Balance" with Tap to Manage beside
 * it, the total as largeTitle bold, grouped, its worth in your currency, then Chatting and
 * Spending. Both parts or a dash: a total missing one of them would read as the whole balance.
 * Tapped (the caller's [modifier]), it opens Manage Addresses.
 */
@Composable
private fun WalletBalanceCard(
    chattingSompi: Long?,
    spendingSompi: Long?,
    priceViewModel: KasPriceViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val price by priceViewModel.currentPriceUsd.collectAsState()
    val currency by priceViewModel.currency.collectAsState()
    val total = if (chattingSompi != null && spendingSompi != null) chattingSompi + spendingSompi else null
    // What the total is worth in your currency. Testnet coins aren't worth anything.
    val fiat = price?.takeIf { total != null && !KaspaNetwork.isTestnet && it > 0.0 }?.let { p ->
        formatFiatAmount((total ?: 0L) / 100_000_000.0 * p, currency, decimals = 4)
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.32f), KaspaTeal.copy(alpha = 0.08f))))
            .border(0.8.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .then(modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallet_total_balance), color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TapToManageLabel()
            }
            KachatFitText(
                total?.let { KaspaUnit.groupedAmount(it) } ?: "--",
                color = colors.textPrimary,
                fontSize = LargeTitleSize,
                fontWeight = FontWeight.Bold,
                minScale = 0.6f,
            )
            if (fiat != null) {
                Text(fiat, color = colors.textSecondary, fontSize = 15.sp)
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            WalletBalancePart(stringResource(R.string.wallet_chatting), chattingSompi, Modifier.weight(1f))
            WalletBalancePart(stringResource(R.string.wallet_spending), spendingSompi, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WalletBalancePart(title: String, sompi: Long?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp)
        KachatFitText(
            sompi?.let { KaspaUnit.groupedAmount(it) } ?: "--",
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            minScale = 0.7f,
        )
    }
}

/** One of Send, Receive and Request (iOS `actionButton`): a 60 accent circle with a black symbol
 *  over its subheadline title. */
@Composable
private fun WalletActionButton(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = com.kachat.app.util.rememberHaptics()
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(CircleShape).background(KaspaTeal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(26.dp))
        }
        Text(title, color = LocalAppColors.current.textPrimary, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
