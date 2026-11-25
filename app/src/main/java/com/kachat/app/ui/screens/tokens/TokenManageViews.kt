package com.kachat.app.ui.screens.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.WalletManager
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.ActionSheetRow
import com.kachat.app.ui.screens.AddressBalanceCard
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosSheetNavBar
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.SheetBarBackButton
import com.kachat.app.ui.screens.SheetBarIconButton
import com.kachat.app.ui.screens.amount
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.ui.theme.mirrorInRtl
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.showAddressCopiedToast
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Managing a token, the way Manage Addresses manages Kaspa (iOS 5b7c772c
// `Views/Tokens/TokenManageViews.swift`, owner's design 2026-11-19, from KaPay): the addresses
// holding it, each with its own page (its history of just that token, Receive and Send), and a
// floating options button at the bottom right. Every row has its own ⋮ (owner's call, 2026-11-21,
// the Chatting Address too). Buying, swapping and the pool are Trestly's site, opened from the
// Wallet tab's Swap Stablecoins (iOS 148a5331: KaChat trades too).

// MARK: - Manage kUSDT

/**
 * "Manage kUSDT" (iOS `TokenManageView`): the total, then every address holding it (the Chatting
 * Address and regular spending addresses), each opening [TokenAddressScreen], then what the token
 * is. The floating button at the bottom right opens its actions: a fresh address, Discover and
 * Address Visibility. A pull is Discover. Full-screen content: it draws its own bar ([onBack]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenManageScreen(
    token: WalletToken,
    onBack: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val dockInset = LocalDockInset.current

    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val activityMap by tokenViewModel.activityFlow.collectAsState()
    val revision by tokenViewModel.visibilityRevision.collectAsState()
    val isRefreshing by tokenViewModel.isRefreshing.collectAsState()
    val lastError by tokenViewModel.lastError.collectAsState()
    val chattingAddress by walletViewModel.address.collectAsState()
    val manageAddresses by walletViewModel.manageAddresses.collectAsState()

    var showActions by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showVisibility by remember { mutableStateOf(false) }
    /** Open Trestly, under the balance card (iOS 518fdc8c, owner's call 2026-11-23): the address it
     *  should use, then Trestly's site inside K, for its pool and everything else there. */
    var showTrestly by remember { mutableStateOf(false) }
    /** The ⋮ on an address row: its own actions (as Manage Addresses has for each address). */
    var rowActionsTarget by remember { mutableStateOf<TokenHolding?>(null) }
    var renameTarget by remember { mutableStateOf<TokenHolding?>(null) }
    var renameText by remember { mutableStateOf("") }
    /** Bumped after a rename, so the rows read their names again. */
    var labelRevision by remember { mutableIntStateOf(0) }
    var qrTarget by remember { mutableStateOf<TokenHolding?>(null) }
    var combineTarget by remember { mutableStateOf<TokenHolding?>(null) }
    /** Send to Chatting Address from a row's ⋮: that address's kUSDT, the Chatting Address filled in. */
    var sendToChattingTarget by remember { mutableStateOf<TokenHolding?>(null) }
    /** The address page pushed over this one (iOS NavigationLink). */
    var openedHolding by remember { mutableStateOf<TokenHolding?>(null) }
    var lastOpenedHolding by remember { mutableStateOf<TokenHolding?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    /** Discover kUSDT Addresses: it belongs to this screen, so closing the sheet doesn't stop it. */
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveryChecking by remember { mutableIntStateOf(0) }
    var discoveryFound by remember { mutableIntStateOf(0) }
    var discoverySummary by remember { mutableStateOf<String?>(null) }

    // every revealed address here (the regular look asks about fewer, for privacy)
    LaunchedEffect(Unit) { tokenViewModel.refreshIfStale(everyAddress = true) }
    // Manage Addresses' Used / Unused: the snapshot first, then the live list
    LaunchedEffect(Unit) { walletViewModel.loadManageAddresses() }
    val usedSpendingIndices = remember(manageAddresses) {
        manageAddresses.filter { it.everUsed || it.balanceSompi > 0 }.map { it.index }.toSet()
    }

    // null until the first answer, so the empty state never flashes
    val rows by produceState<List<TokenHolding>?>(null, holdingsMap, revision, token) {
        value = tokenViewModel.listedHoldings(token)
    }
    val total = remember(holdingsMap, token) { tokenViewModel.balance(token) }
    val hiddenCount = remember(holdingsMap, revision, token) {
        tokenViewModel.holdings(token).count { !tokenViewModel.isListed(it, token) }
    }

    /** Used, as Manage Addresses says it (owner's call, 2026-11-20, as KaPay has it): the Chatting
     *  Address always; another address once it holds or has held kUSDT on this phone, or has any
     *  Kaspa history. No "Checking": it shows what's known now. */
    fun isUsed(holding: TokenHolding): Boolean {
        val index = holding.spendingIndex ?: return true
        return holding.units > 0 || holding.coins > 0 || index in usedSpendingIndices ||
            (activityMap.isNotEmpty() && tokenViewModel.activity(token, holding).isNotEmpty())
    }

    /** A new regular address on the list, with Manage Addresses' "Address #n is ready." */
    fun generateFresh() {
        if (isGenerating) return
        isGenerating = true
        scope.launch {
            try {
                val number = tokenViewModel.generateFreshAddress(token) ?: return@launch
                haptic(IosHaptic.SUCCESS)
                com.kachat.app.ui.screens.IosToasts.show(context.getString(R.string.address_number_is_ready, number))
            } finally {
                isGenerating = false
            }
        }
    }

    /** Walks the addresses for the token, reporting in the sheet as it goes; the result stays in
     *  the sheet, or the button's spinner if it was closed. */
    fun discover() {
        if (isDiscovering) return
        isDiscovering = true
        discoveryChecking = 0
        discoveryFound = 0
        discoverySummary = null
        scope.launch {
            val found = tokenViewModel.discover(token) { checking, soFar ->
                discoveryChecking = checking
                discoveryFound = soFar
            }
            isDiscovering = false
            discoverySummary = if (found == 0) context.getString(R.string.token_no_addresses_found, token.symbol)
            else context.getString(R.string.token_found_addresses, found, token.symbol)
            haptic(IosHaptic.SUCCESS)
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    // a pull is Discover kUSDT Addresses: every address to 20 past the last, revealing any holding some
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            tokenViewModel.discover(token)
            pullRefreshState.endRefresh()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(stringResource(R.string.token_manage_title, token.symbol), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    },
                    navigationIcon = { SheetBarBackButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clipToBounds()
                    .nestedScroll(pullRefreshState.nestedScrollConnection),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        // the same card as Manage Addresses' total and an address's own page
                        AddressBalanceCard(
                            label = stringResource(R.string.total_balance),
                            amount = total?.let { TokenAmount.amount(it, token) } ?: "--",
                            detail = total?.let { TokenAmount.dollars(it, token) },
                        ) {
                            BridgedByPill(token, onClick = {
                                haptic(IosHaptic.IMPACT_LIGHT)
                                showAbout = true
                            })
                            val error = lastError
                            if (error != null) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                                    Text(
                                        stringResource(R.string.token_couldnt_check, token.symbol, error),
                                        color = colors.warning,
                                        fontSize = 12.sp,
                                    )
                                }
                            } else if (isRefreshing && total == null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    IosActivityIndicator(modifier = Modifier.size(14.dp))
                                    Text(stringResource(R.string.token_checking_your, token.symbol), color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (token == WalletToken.KUSDT) {
                        item {
                            OpenTrestlyButton(
                                onClick = {
                                    haptic(IosHaptic.IMPACT_LIGHT)
                                    showTrestly = true
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    val list = rows
                    if (list != null) {
                        if (list.isEmpty() && hiddenCount == 0) {
                            item { TokenManageEmptyState(token) }
                        } else {
                            items(list, key = { it.id }) { holding ->
                                TokenManageAddressRow(
                                    token = token,
                                    holding = holding,
                                    used = isUsed(holding),
                                    labelRevision = labelRevision,
                                    tokenViewModel = tokenViewModel,
                                    onOpen = {
                                        lastOpenedHolding = holding
                                        openedHolding = holding
                                    },
                                    onActions = { rowActionsTarget = holding },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            if (hiddenCount > 0) {
                                item {
                                    Text(
                                        stringResource(R.string.token_hidden_count, hiddenCount),
                                        color = colors.textSecondary,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showVisibility = true }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Room to scroll the last row out from under the floating button.
                    item { Spacer(Modifier.height(72.dp + 16.dp + dockInset)) }
                }

                if (showTrestly) {
                    SwapStablecoinsSheet(walletViewModel = walletViewModel, onDismissed = { showTrestly = false }, startToken = token)
                }
                TokenActionsFloatingButton(
                    token = token,
                    isBusy = isDiscovering,
                    onClick = {
                        haptic(IosHaptic.IMPACT_LIGHT)
                        showActions = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 16.dp + dockInset),
                )

                if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
                    PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }

        // One address's page, pushed over this one.
        AnimatedVisibility(
            visible = openedHolding != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            lastOpenedHolding?.let { holding ->
                TokenAddressScreen(
                    token = token,
                    holding = holding,
                    onBack = { openedHolding = null },
                    tokenViewModel = tokenViewModel,
                    walletViewModel = walletViewModel,
                )
            }
        }
    }
    BackHandler(enabled = openedHolding == null, onBack = onBack)

    if (showActions) {
        ActionSheetContainer(
            title = stringResource(R.string.token_actions_title, token.symbol),
            subtitle = null,
            onDismiss = {
                showActions = false
                if (!isDiscovering) discoverySummary = null
            },
        ) {
            if (isDiscovering) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                ) {
                    IosActivityIndicator()
                    Text(stringResource(R.string.checking_address_n, discoveryChecking), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (discoveryFound == 0) stringResource(R.string.token_no_addresses_yet, token.symbol)
                        else stringResource(R.string.n_found_so_far, discoveryFound),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        stringResource(R.string.close_and_keep_scanning),
                        color = KaspaTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .sendKaspaGlass(14.dp)
                            .clickable(role = Role.Button) { showActions = false }
                            .padding(vertical = 12.dp),
                    )
                }
            } else {
                ActionSheetRow(
                    icon = Icons.Filled.LibraryAdd,
                    title = stringResource(R.string.generate_new_address),
                    subtitle = stringResource(R.string.token_generate_new_address_subtitle),
                    enabled = !isGenerating,
                ) {
                    showActions = false
                    generateFresh()
                }
                ActionSheetRow(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.token_discover_addresses),
                    subtitle = stringResource(R.string.token_discover_addresses_subtitle),
                ) { discover() }
                ActionSheetRow(
                    icon = Icons.Filled.Checklist,
                    title = stringResource(R.string.token_address_visibility_title, token.symbol),
                    subtitle = stringResource(R.string.token_address_visibility_subtitle),
                ) {
                    showActions = false
                    showVisibility = true
                }
                discoverySummary?.let { summary ->
                    Text(summary, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    rowActionsTarget?.let { holding ->
        TokenRowActionsSheet(
            token = token,
            holding = holding,
            labelRevision = labelRevision,
            tokenViewModel = tokenViewModel,
            onDismiss = { rowActionsTarget = null },
            onRename = { currentLabel ->
                rowActionsTarget = null
                // the name it was given, or empty for the default "Address #n"
                renameText = if (currentLabel.startsWith("Address #")) "" else currentLabel
                renameTarget = holding
            },
            onCopy = {
                clipboard.setText(AnnotatedString(holding.address))
                rowActionsTarget = null
                showAddressCopiedToast(context, holding.address)
            },
            onShowQr = {
                rowActionsTarget = null
                qrTarget = holding
            },
            onSendToChatting = {
                rowActionsTarget = null
                sendToChattingTarget = holding
            },
            onCombine = {
                rowActionsTarget = null
                combineTarget = holding
            },
            onHide = {
                rowActionsTarget = null
                if (tokenViewModel.isListed(holding, token)) tokenViewModel.toggleListed(holding, token)
            },
        )
    }

    renameTarget?.let { holding ->
        TokenRenameDialog(
            text = renameText,
            onTextChange = { renameText = it },
            onDismiss = { renameTarget = null },
            onSave = {
                holding.spendingIndex?.let { index ->
                    walletViewModel.setManageAddressLabel(index, renameText)
                    labelRevision++
                }
                renameTarget = null
            },
        )
    }

    if (showVisibility) {
        TokenAddressVisibilityScreen(token = token, onDismiss = { showVisibility = false }, tokenViewModel = tokenViewModel, walletViewModel = walletViewModel)
    }
    qrTarget?.let { holding ->
        TokenReceiveSheet(token = token, chattingAddress = "", fixedHolding = holding, onDismiss = { qrTarget = null }, tokenViewModel = tokenViewModel)
    }
    combineTarget?.let { holding ->
        TokenCombineScreen(token = token, holding = holding, onDismiss = { combineTarget = null }, tokenViewModel = tokenViewModel)
    }
    sendToChattingTarget?.let { holding ->
        TokenSendScreen(
            token = token,
            startFrom = holding,
            prefillRecipient = chattingAddress,
            prefillName = stringResource(R.string.chatting_address),
            onDismiss = { sendToChattingTarget = null },
            tokenViewModel = tokenViewModel,
            walletViewModel = walletViewModel,
        )
    }
    if (showAbout) {
        TokenAboutSheet(token = token, onDismiss = { showAbout = false })
    }
}

/** The "Bridged by Trestly" pill under the total: the details, in a sheet. */
@Composable
private fun BridgedByPill(token: WalletToken, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(KaspaTeal.copy(alpha = 0.15f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.token_bridged_by, token.issuer), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Outlined.Info, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun TokenManageEmptyState(token: WalletToken) {
    val colors = LocalAppColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        TokenIcon(token, size = 48.dp)
        Text(stringResource(R.string.token_no_token_yet, token.symbol), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.kp_token_empty_state_swap_kc),
            color = colors.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** One address holding the token, as Manage Addresses draws a row: its name, the address, the
 *  amount, the KAS held with it, and Used or Unused. */
@Composable
private fun TokenManageAddressRow(
    token: WalletToken,
    holding: TokenHolding,
    used: Boolean,
    labelRevision: Int,
    tokenViewModel: TokenViewModel,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val label = rememberHoldingLabel(holding, tokenViewModel, labelRevision)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .sendKaspaGlass(18.dp)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(
                    if (holding.isChatting) Icons.Filled.Forum else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = if (holding.isChatting) KaspaTeal else colors.success,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(shortTokenAddress(holding.address), color = colors.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(TokenAmount.amount(holding.units, token), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.token_held_with_it, KaspaUnit.amount(holding.coins.toLong() * token.kasPerCoinSompi)),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            // Manage Addresses' tag: orange once the address has been used, green while fresh
            Text(
                stringResource(if (used) R.string.pr_used else R.string.pr_unused),
                color = if (used) colors.warning else colors.success,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // every row has its own actions (owner's call, 2026-11-21: the Chatting Address too)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onActions),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.address_actions), tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

/** The floating glass button at the bottom right, as on Manage Addresses. */
@Composable
private fun TokenActionsFloatingButton(token: WalletToken, isBusy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.token_actions_title, token.symbol)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .iosGlass(cornerRadius = 28.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (isBusy) {
            IosActivityIndicator(modifier = Modifier.size(18.dp))
        } else {
            // "slider.horizontal.3"
            Icon(Icons.Filled.Tune, contentDescription = label, tint = KaspaTeal, modifier = Modifier.size(26.dp))
        }
    }
}

/** One address's ⋮: Rename, Copy, Show QR Code, Send to Chatting Address, Combine Coins and Hide,
 *  as Manage Addresses has for each address (iOS `rowActionsSheet`). The Chatting Address has only
 *  Copy, Show QR Code and Combine Coins: it can't be renamed, sent to itself or hidden (148a5331). */
@Composable
private fun TokenRowActionsSheet(
    token: WalletToken,
    holding: TokenHolding,
    labelRevision: Int,
    tokenViewModel: TokenViewModel,
    onDismiss: () -> Unit,
    onRename: (currentLabel: String) -> Unit,
    onCopy: () -> Unit,
    onShowQr: () -> Unit,
    onSendToChatting: () -> Unit,
    onCombine: () -> Unit,
    onHide: () -> Unit,
) {
    val label = rememberHoldingLabel(holding, tokenViewModel, labelRevision)
    ActionSheetContainer(
        title = label,
        subtitle = null,
        detail = shortTokenAddress(holding.address),
        onDismiss = onDismiss,
    ) {
        if (!holding.isChatting) {
            ActionSheetRow(
                icon = Icons.Filled.Edit,
                title = stringResource(R.string.rename_address),
                subtitle = stringResource(R.string.gives_this_address_a_label_of_your),
            ) { onRename(label) }
        }
        ActionSheetRow(
            icon = Icons.Filled.ContentCopy,
            title = stringResource(R.string.copy_address),
            subtitle = stringResource(R.string.puts_the_full_address_on_the_clipboard),
            onClick = onCopy,
        )
        ActionSheetRow(
            icon = Icons.Filled.QrCode,
            title = stringResource(R.string.show_qr_code),
            subtitle = stringResource(R.string.full_screen_for_scanning_with_another_device),
            onClick = onShowQr,
        )
        // as Kaspa's Manage Addresses: this address's kUSDT, the Chatting Address filled in
        if (!holding.isChatting) {
            ActionSheetRow(
                icon = Icons.Outlined.ArrowCircleUp,
                title = stringResource(R.string.send_to_chatting_address),
                subtitle = stringResource(R.string.token_send_to_chatting_subtitle),
                enabled = holding.units > 0,
                onClick = onSendToChatting,
            )
        }
        // more than one coin: each holds 1.5 KAS, merging them gives the extra back
        if (holding.coins > 1) {
            ActionSheetRow(
                icon = Icons.Filled.Merge,
                title = stringResource(R.string.token_combine_coins),
                subtitle = stringResource(R.string.token_combine_coins_subtitle),
                onClick = onCombine,
            )
        }
        // as unchecking it in kUSDT Address Visibility; it still counts in the total
        if (!holding.isChatting) {
            ActionSheetRow(
                icon = Icons.Outlined.VisibilityOff,
                title = stringResource(R.string.hide_address),
                subtitle = stringResource(R.string.token_hide_address_subtitle, token.symbol),
                tint = LocalAppColors.current.warning,
                onClick = onHide,
            )
        }
    }
}

/** "Rename Address" with its Label field (iOS's alert). Blank goes back to "Address #n". */
@Composable
private fun TokenRenameDialog(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_address), color = colors.textPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.token_rename_message), color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                com.kachat.app.ui.theme.IosTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.token_rename_label_placeholder)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = colors.textSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = colors.textSecondary) }
        },
    )
}

// MARK: - Address Visibility

/**
 * kUSDT Address Visibility (iOS `TokenAddressVisibilityView`): the same screen as Kaspa's
 * Non-Quantum Addresses, for the token. Tokens never live on quantum addresses, so there's no
 * chain to choose. Every regular address in pages of 50 (#0 the Chatting Address, always on), a
 * checkmark per row (checked = on Manage kUSDT's list), and the token's amount on the right, or
 * Unused. A tap saves at once; Hide All starts the list over with only the Chatting Address.
 * Presents itself (a sheet); [onDismiss] once it has gone.
 */
@Composable
fun TokenAddressVisibilityScreen(
    token: WalletToken,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val pageSize = 50
    var page by remember { mutableIntStateOf(0) }
    var showHideAllConfirm by remember { mutableStateOf(false) }
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val revision by tokenViewModel.visibilityRevision.collectAsState()
    val chattingAddress by walletViewModel.address.collectAsState()

    /** The page's regular addresses (#1 on: #0 is the Chatting Address), derived in one go, with
     *  the token's amount where they hold some. */
    val pageRows by produceState<List<TokenHolding>?>(null, page, holdingsMap) {
        val held = tokenViewModel.holdings(token).mapNotNull { h -> h.spendingIndex?.let { it to h } }.toMap()
        val range = (page * pageSize) until (page * pageSize + pageSize)
        val derived = withContext(Dispatchers.Default) { walletViewModel.spendingAddressesAt(range) }
        value = range.mapNotNull { index ->
            held[index] ?: derived[index]?.let { TokenHolding(spendingIndex = index, address = it, units = 0, coins = 0) }
        }
    }

    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        Column(Modifier.fillMaxSize().background(colors.background)) {
            IosSheetNavBar(
                title = stringResource(R.string.token_address_visibility_title, token.symbol),
                leading = { IosBarTextButton(stringResource(R.string.hide_all), onClick = { showHideAllConfirm = true }) },
                trailing = { IosBarTextButton(stringResource(R.string.done), onClick = close, bold = true) },
            )
            // A new list per page, scrolled to the top.
            androidx.compose.runtime.key(page) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    val wallet = chattingAddress
                    if (page == 0 && wallet != null) {
                        item {
                            val units = remember(holdingsMap) { tokenViewModel.holdings(token).firstOrNull { it.isChatting }?.units ?: 0L }
                            TokenVisibilityRow(
                                number = "#0",
                                caption = stringResource(R.string.chatting_address),
                                address = wallet,
                                listed = true,
                                dimmed = true,
                                units = units,
                                token = token,
                                onClick = null,
                            )
                        }
                    }
                    val rowsNow = pageRows
                    if (rowsNow != null && rowsNow.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.token_visibility_derive_failed),
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(rowsNow.orEmpty(), key = { it.id }) { holding ->
                        val listed = remember(revision, holding.id) { tokenViewModel.isListed(holding, token) }
                        TokenVisibilityRow(
                            number = "#${WalletManager.addressNumber(holding.spendingIndex ?: 0)}",
                            caption = null,
                            address = holding.address,
                            listed = listed,
                            dimmed = false,
                            units = holding.units,
                            token = token,
                            onClick = {
                                haptic(IosHaptic.IMPACT_LIGHT)
                                tokenViewModel.toggleListed(holding, token)
                            },
                        )
                    }
                }
            }
            // the pager
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (colors.isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.03f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                SheetBarIconButton(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    onClick = { page = maxOf(0, page - 1) },
                    enabled = page > 0,
                )
                Text(
                    "#${if (page == 0) 0 else WalletManager.addressNumber(page * pageSize)} - #${WalletManager.addressNumber(page * pageSize + pageSize - 1)}",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.weight(1f),
                )
                SheetBarIconButton(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    onClick = { page += 1 },
                )
            }
        }
    }

    if (showHideAllConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showHideAllConfirm = false },
            title = { Text(stringResource(R.string.hide_all_addresses_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.token_hide_all_message, token.symbol, token.symbol), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showHideAllConfirm = false
                    tokenViewModel.hideAllListed(token)
                }) { Text(stringResource(R.string.hide_all), color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showHideAllConfirm = false }) { Text(stringResource(R.string.cancel), color = KaspaTeal) }
            },
        )
    }
}

/** One row of [TokenAddressVisibilityScreen]: the check, "#n" (with the Chatting Address's name
 *  on #0), the address, and the token's amount in accent, or Unused. */
@Composable
private fun TokenVisibilityRow(
    number: String,
    caption: String?,
    address: String,
    listed: Boolean,
    dimmed: Boolean,
    units: Long,
    token: WalletToken,
    onClick: (() -> Unit)?,
) {
    val colors = LocalAppColors.current
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                if (listed) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (listed) KaspaTeal else colors.textSecondary,
                modifier = Modifier.size(22.dp).alpha(if (dimmed) 0.45f else 1f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(number, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = "tnum"))
                    if (caption != null) {
                        Text(caption, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                }
                Text(shortTokenAddress(address, head = 16, separator = "…"), color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            if (units > 0) {
                Text(TokenAmount.amount(units, token), color = KaspaTeal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(stringResource(R.string.pr_unused), color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.2f), thickness = 0.5.dp, modifier = Modifier.padding(start = 48.dp))
    }
}

// MARK: - One address's page

/**
 * One address holding the token (iOS `TokenAddressView`), like a spending address's page in Manage
 * Addresses: its balance, its history of just this token, and Receive and Send at the bottom (from
 * this address). The options button at the top right has Combine Coins (more than one coin) and the
 * explorer. Full-screen content: it draws its own bar ([onBack]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenAddressScreen(
    token: WalletToken,
    holding: TokenHolding,
    onBack: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val dockInset = LocalDockInset.current
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val activityMap by tokenViewModel.activityFlow.collectAsState()
    val explorer by tokenViewModel.kaspaExplorer.collectAsState()
    val label = rememberHoldingLabel(holding, tokenViewModel)
    /** The address as it is now (its amount changes after a send). */
    val current = remember(holdingsMap, holding) {
        tokenViewModel.holdings(token).firstOrNull { it.id == holding.id } ?: holding
    }
    val rows = remember(activityMap, holding) { tokenViewModel.activity(token, holding) }
    var showOptions by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showCombine by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { SheetBarBackButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) },
                actions = {
                    SheetBarIconButton(
                        Icons.Filled.MoreHoriz,
                        stringResource(R.string.address_actions),
                        onClick = { showOptions = true },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .background(if (colors.isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.03f))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = dockInset),
            ) {
                TokenPageButton(
                    title = stringResource(R.string.receive),
                    icon = Icons.Filled.QrCode,
                    enabled = true,
                    bold = false,
                    modifier = Modifier.weight(1f),
                ) { showReceive = true }
                TokenPageButton(
                    title = stringResource(R.string.send),
                    icon = Icons.Filled.ArrowCircleUp,
                    enabled = current.units > 0,
                    bold = true,
                    modifier = Modifier.weight(1f),
                ) { showSend = true }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // the same card as a Kaspa address's page
            AddressBalanceCard(
                label = label,
                amount = TokenAmount.amount(current.units, token),
                detail = stringResource(R.string.token_held_with_it, KaspaUnit.amount(current.coins.toLong() * token.kasPerCoinSompi)),
            )
            Text(
                stringResource(R.string.token_history_title, token.symbol),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 6.dp),
            )
            if (rows.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(26.dp))
                    // Trestly's API keeps no history: this phone keeps what it sees from now on
                    Text(stringResource(R.string.token_no_activity_yet, token.symbol), color = colors.textSecondary, fontSize = 15.sp)
                    Text(
                        stringResource(R.string.token_history_from_now_on),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                // the same list as a Kaspa address's transactions (Manage Addresses), inset grouped
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(rows, key = { it.id }) { row ->
                        val isFirst = row.id == rows.first().id
                        val isLast = row.id == rows.last().id
                        val shape = RoundedCornerShape(
                            topStart = if (isFirst) 12.dp else 0.dp, topEnd = if (isFirst) 12.dp else 0.dp,
                            bottomStart = if (isLast) 12.dp else 0.dp, bottomEnd = if (isLast) 12.dp else 0.dp,
                        )
                        Column(Modifier.clip(shape).background(colors.surface)) {
                            TokenActivityRow(
                                token = token,
                                row = row,
                                tokenViewModel = tokenViewModel,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            if (!isLast) HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 54.dp))
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showOptions) {
        ActionSheetContainer(
            title = stringResource(R.string.address_actions),
            subtitle = null,
            onDismiss = { showOptions = false },
        ) {
            if (current.coins > 1) {
                ActionSheetRow(
                    icon = Icons.Filled.Merge,
                    title = stringResource(R.string.token_combine_coins),
                    subtitle = stringResource(R.string.token_combine_coins_subtitle),
                ) {
                    showOptions = false
                    showCombine = true
                }
            }
            ActionSheetRow(
                icon = Icons.Filled.Explore,
                title = stringResource(R.string.view_in_explorer),
                subtitle = stringResource(R.string.token_view_address_in_explorer_subtitle),
            ) {
                showOptions = false
                runCatching { uriHandler.openUri(explorer.addressUrl(holding.address)) }
            }
        }
    }
    if (showReceive) {
        TokenReceiveSheet(token = token, chattingAddress = "", fixedHolding = current, onDismiss = { showReceive = false }, tokenViewModel = tokenViewModel)
    }
    if (showSend) {
        TokenSendScreen(token = token, startFrom = current, onDismiss = { showSend = false }, tokenViewModel = tokenViewModel, walletViewModel = walletViewModel)
    }
    if (showCombine) {
        TokenCombineScreen(token = token, holding = current, onDismiss = { showCombine = false }, tokenViewModel = tokenViewModel)
    }
}

/** Receive / Send at the bottom of an address's page: teal capsules with black text. */
@Composable
private fun TokenPageButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    bold: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(KaspaTeal)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Text(title, color = Color.Black, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

// MARK: - What the token is

/** The "Bridged by Trestly" pill's sheet: the details about the token and who stands behind it
 *  (iOS `TokenAboutSheet`). Presents itself; [onDismiss] once it has gone. */
@Composable
fun TokenAboutSheet(token: WalletToken, onDismiss: () -> Unit) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        Column(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
            IosSheetNavBar(
                title = stringResource(R.string.token_about_title, token.symbol),
                trailing = { IosBarTextButton(stringResource(R.string.done), onClick = close) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp)
            ) {
                TokenAboutCard(token)
            }
        }
    }
}

/** "Bridged by Trestly": what the token is, the not-audited warning, the KAS each coin holds, and
 *  a link to the vault (iOS `TokenAboutCard`). No link into Trestly's site: that's KaPay's. */
@Composable
fun TokenAboutCard(token: WalletToken, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .sendKaspaGlass(18.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.token_bridged_by, token.issuer), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.token_about_kusdt), color = colors.textSecondary, fontSize = 15.sp)
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.warning.copy(alpha = 0.12f))
                .padding(10.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.token_not_audited), color = colors.warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.token_every_coin_holds_kas), color = colors.textSecondary, fontSize = 13.sp)
        Row {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(KaspaTeal.copy(alpha = 0.15f))
                    .clickable(role = Role.Button) { runCatching { uriHandler.openUri(token.backingUrl) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.Verified, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.token_view_the_vault), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Manage kUSDT's Open Trestly (iOS 518fdc8c `openTrestlyButton`): a glass row, the safari symbol,
 *  "Open Trestly" and a chevron. */
@Composable
private fun OpenTrestlyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(Icons.Filled.Explore, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.kp_open_trestly), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}
