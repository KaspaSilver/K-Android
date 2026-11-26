package com.kachat.app.ui.screens.explorer

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.explorer.ExplorerAddress
import com.kachat.app.services.explorer.ExplorerApi
import com.kachat.app.services.explorer.ExplorerBlock
import com.kachat.app.services.explorer.ExplorerFailure
import com.kachat.app.services.explorer.ExplorerIO
import com.kachat.app.services.explorer.ExplorerRoute
import com.kachat.app.services.explorer.ExplorerTransaction
import com.kachat.app.services.explorer.ExplorerTxKind
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.components.PageCycleBar
import com.kachat.app.ui.screens.ContactAvatar
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.SheetBarBackButton
import com.kachat.app.ui.screens.ToolbarGlassGroup
import com.kachat.app.ui.screens.ToolbarIconButton
import com.kachat.app.ui.screens.rememberQrBitmapPainter
import com.kachat.app.ui.screens.tokens.TokenIcon
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// MARK: - Pages

/** Each route's page (iOS `ExplorerPage`). */
@Composable
internal fun ExplorerPage(
    viewModel: ExplorerViewModel,
    route: ExplorerRoute,
    cacheKey: String,
    onBack: () -> Unit,
    open: (ExplorerRoute) -> Unit,
) {
    when (route) {
        is ExplorerRoute.Transaction -> ExplorerLoader(
            viewModel, cacheKey, stringResource(R.string.transaction), onBack, share = route.id,
            load = { viewModel.api.transaction(route.id) },
        ) { tx: ExplorerTransaction -> ExplorerTransactionView(viewModel, tx, onBack, open) }
        is ExplorerRoute.Block -> ExplorerLoader(
            viewModel, cacheKey, stringResource(R.string.ex_block), onBack, share = route.hash,
            load = { viewModel.api.block(route.hash) },
        ) { block: ExplorerBlock -> ExplorerBlockView(viewModel, block, onBack, open) }
        is ExplorerRoute.Address -> ExplorerLoader(
            viewModel, cacheKey, stringResource(R.string.address), onBack, share = route.address,
            load = { viewModel.api.address(route.address) },
        ) { info: ExplorerAddress -> ExplorerAddressView(viewModel, info, onBack, open) }
        is ExplorerRoute.Chart -> ExplorerChartScreen(viewModel, route.metric, onBack)
    }
}

/** A pushed page's bar: back, the title centred, and Share when the page has something to share. */
@Composable
internal fun ExplorerPageBar(title: String, onBack: () -> Unit, share: String? = null) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        SheetBarBackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
        Text(
            title,
            color = colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
        )
        if (share != null) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                ToolbarGlassGroup {
                    ToolbarIconButton(Icons.Filled.IosShare, stringResource(R.string.ex_share), onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share)
                        context.startActivity(Intent.createChooser(send, null))
                    })
                }
            }
        }
    }
}

/** What a failed load says. */
@Composable
private fun failureText(error: Throwable): String = when (error) {
    is ExplorerFailure.NotFound -> stringResource(R.string.ex_failure_not_found)
    is ExplorerFailure.Server -> stringResource(R.string.ex_failure_server, error.code)
    is ExplorerFailure.BadAnswer -> stringResource(R.string.ex_failure_bad_answer)
    else -> stringResource(R.string.ex_unreachable)
}

/**
 * Loads a page's data from kaspa.stream, with a spinner, and the error and Try Again if it fails
 * (iOS `ExplorerLoader`). What loaded is kept by [cacheKey] while the page is open, so it comes back
 * as it was from a page opened on top of it.
 */
@Suppress("UNCHECKED_CAST")
@Composable
private fun <T : Any> ExplorerLoader(
    viewModel: ExplorerViewModel,
    cacheKey: String,
    title: String,
    onBack: () -> Unit,
    share: String?,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    val colors = LocalAppColors.current
    val value = viewModel.pages[cacheKey] as? T
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheKey, attempt) {
        if (viewModel.pages[cacheKey] != null) return@LaunchedEffect
        failure = null
        try {
            viewModel.pages[cacheKey] = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
    }
    val error = failure
    when {
        value != null -> content(value)
        else -> Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0),
            topBar = { ExplorerPageBar(title, onBack, share) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(bottom = LocalDockInset.current), contentAlignment = Alignment.Center) {
                if (error != null) {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Filled.SearchOff, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(42.dp))
                        Text(failureText(error), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(KaspaTeal)
                                .clickable { attempt++ }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        ) {
                            Text(stringResource(R.string.try_again), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    IosActivityIndicator()
                }
            }
        }
    }
}

/** A loaded page: its bar over a scrolling column. */
@Composable
private fun ExplorerPageScaffold(
    title: String,
    onBack: () -> Unit,
    share: String?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = LocalAppColors.current.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ExplorerPageBar(title, onBack, share) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = LocalDockInset.current + 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

// MARK: Transaction

@Composable
private fun ExplorerTransactionView(viewModel: ExplorerViewModel, tx: ExplorerTransaction, onBack: () -> Unit, open: (ExplorerRoute) -> Unit) {
    val colors = LocalAppColors.current
    val own by viewModel.ownAddress.collectAsState()
    val stats by viewModel.live.stats.collectAsState()
    var showPayload by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    val copied = stringResource(R.string.ab_copied)
    ExplorerPageScaffold(stringResource(R.string.transaction), onBack = onBack, share = tx.id) {
        // hero
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExplorerKindBadge(tx.kind, 58.dp)
            Text(stringResource(tx.kind.title), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            KachatFitText(
                ExplorerFormat.amountLine(tx),
                color = colors.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = Rounded,
                minScale = 0.5f,
            )
            val moved = tx.movedAmount
            if (tx.tokenUnits == null && moved != null) {
                Text(ExplorerFormat.usd(moved, stats?.priceUSD ?: 0.0), color = colors.textSecondary, fontSize = 15.sp)
            }
            Box(Modifier.padding(top = 2.dp)) {
                ExplorerStatusPill(tx.confirmations, tx.isInMempool, tx.isAccepted)
            }
        }
        // what it is
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                    Text(stringResource(tx.kind.explanation), color = colors.textPrimary, fontSize = 15.sp)
                }
                if (tx.isNotAccepted) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Block, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(if (tx.kind == ExplorerTxKind.COINBASE) R.string.ex_not_accepted_reward else R.string.ex_not_accepted_moved),
                            color = colors.textSecondary,
                            fontSize = 15.sp,
                        )
                    }
                }
                tx.decoded?.let { decoded ->
                    Text(
                        decoded,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontFamily = if (tx.kind == ExplorerTxKind.KAPOST) null else FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(KaspaTeal.copy(alpha = 0.12f))
                            .padding(12.dp),
                    )
                }
            }
        }
        // details
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            ExplorerDetailRow(stringResource(R.string.ex_transaction_id)) { ExplorerCopyValue(tx.id) }
            ExplorerDivider(14.dp)
            tx.blockHash?.let { block ->
                ExplorerDetailRow(stringResource(R.string.ex_block), Modifier.explorerRow { open(ExplorerRoute.Block(block)) }, chevron = true) {
                    ExplorerMonoText(block, middle = true)
                }
                ExplorerDivider(14.dp)
            }
            ExplorerDetailRow(stringResource(R.string.shell54_time)) { ExplorerValueText(ExplorerFormat.dateTime(tx.time)) }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.network_fee)) { ExplorerValueText(tx.fee?.let { ExplorerFormat.kas(it) } ?: "–") }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.ex_mass)) { ExplorerValueText(ExplorerFormat.grouped(tx.mass)) }
        }
        if (tx.inputs.isNotEmpty()) IoCard(stringResource(R.string.from), tx.inputs, tx, own, marksChange = false, open = open)
        IoCard(stringResource(R.string.to), tx.outputs, tx, own, marksChange = true, open = open)
        if (tx.payloadHex.isNotEmpty()) {
            ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showPayload = !showPayload },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.ex_payload), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ex_bytes, tx.payloadHex.length / 2), color = colors.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp).rotate(if (showPayload) 180f else 0f),
                        )
                    }
                    AnimatedVisibility(showPayload) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SelectionContainer {
                                Text(tx.payloadHex, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(
                                Modifier.clickable {
                                    clipboard.setText(AnnotatedString(tx.payloadHex))
                                    haptic(IosHaptic.SUCCESS)
                                    IosToasts.show(copied)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(15.dp))
                                Text(stringResource(R.string.ex_copy_payload), color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IoCard(
    title: String,
    items: List<ExplorerIO>,
    tx: ExplorerTransaction,
    own: String?,
    marksChange: Boolean,
    open: (ExplorerRoute) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExplorerSectionTitle(title)
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            items.forEachIndexed { index, io ->
                if (index > 0) ExplorerDivider(60.dp)
                if (io.address.isEmpty()) {
                    // not known (read off the node) or no address (a script): nothing to open
                    ExplorerUnknownIORow(
                        label = stringResource(if (marksChange) R.string.ex_output_n else R.string.ex_input_n, io.index),
                        amount = if (marksChange) io.amount else null,
                    )
                } else {
                    ExplorerAddressRow(
                        address = io.address,
                        amount = io.amount,
                        isYou = own != null && io.address.equals(own, ignoreCase = true),
                        isChange = marksChange && io.index > 0 && io.address == tx.inputs.firstOrNull()?.address,
                        modifier = Modifier.explorerRow { open(ExplorerRoute.Address(io.address)) },
                    )
                }
            }
        }
    }
}

// MARK: Block

private enum class BlockPage { TRANSACTIONS, PARENTS, CHILDREN }

@Composable
private fun ExplorerBlockView(viewModel: ExplorerViewModel, block: ExplorerBlock, onBack: () -> Unit, open: (ExplorerRoute) -> Unit) {
    val colors = LocalAppColors.current
    var page by rememberSaveable { mutableStateOf(BlockPage.TRANSACTIONS) }
    ExplorerPageScaffold(stringResource(R.string.ex_block), onBack = onBack, share = block.id) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(KaspaTeal), contentAlignment = Alignment.Center) {
                Icon(ExplorerSymbols.CubeFill, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(ExplorerFormat.grouped(block.blueScore), color = colors.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Rounded, style = Tabular)
            Text(stringResource(R.string.ex_blue_score), color = colors.textSecondary, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplorerPill(
                    stringResource(if (block.isChainBlock) R.string.ex_chain_block else R.string.ex_merged_block),
                    if (block.isChainBlock) Icons.Filled.Link else Icons.Filled.MergeType,
                    if (block.isChainBlock) KaspaTeal else colors.textSecondary,
                )
                ExplorerPill(ExplorerFormat.ago(LocalContext.current, block.time, System.currentTimeMillis()), Icons.Filled.Schedule, colors.textSecondary)
            }
        }
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            ExplorerDetailRow(stringResource(R.string.ex_block_hash)) { ExplorerCopyValue(block.id) }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.ex_daa_score)) { ExplorerValueText(ExplorerFormat.grouped(block.daaScore)) }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.shell54_time)) { ExplorerValueText(ExplorerFormat.dateTime(block.time)) }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.ex_merge_set)) {
                ExplorerValueText(stringResource(R.string.ex_merge_set_value, block.mergeSetBlues, block.mergeSetReds))
            }
            ExplorerDivider(14.dp)
            ExplorerDetailRow(stringResource(R.string.ex_difficulty)) { ExplorerValueText(ExplorerFormat.difficulty(block.difficulty)) }
        }
        PageCycleBar(
            titles = listOf(
                stringResource(R.string.transactions) + " · ${block.transactionIds.size}",
                stringResource(R.string.ex_parents) + " · ${block.parents.size}",
                stringResource(R.string.ex_children) + " · ${block.children.size}",
            ),
            selectedIndex = page.ordinal,
            onSelect = { page = BlockPage.entries[it] },
        )
        when (page) {
            BlockPage.TRANSACTIONS -> ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                block.transactions.forEachIndexed { index, tx ->
                    if (index > 0) ExplorerDivider(62.dp)
                    ExplorerTransactionRow(tx, Modifier.explorerRow { open(ExplorerRoute.Transaction(tx.id)) }, showsAge = false)
                }
            }
            BlockPage.PARENTS, BlockPage.CHILDREN -> {
                val hashes = if (page == BlockPage.PARENTS) block.parents else block.children
                if (hashes.isEmpty()) {
                    Text(
                        stringResource(if (page == BlockPage.CHILDREN) R.string.ex_no_children else R.string.ex_no_parents),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 8.dp),
                    )
                } else {
                    ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                        hashes.forEachIndexed { index, hash ->
                            if (index > 0) ExplorerDivider(52.dp)
                            Row(
                                Modifier.fillMaxWidth().explorerRow { open(ExplorerRoute.Block(hash)) }.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(ExplorerSymbols.Cube, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                                ExplorerMonoText(hash, middle = true, modifier = Modifier.weight(1f))
                                ExplorerChevron()
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: Address

private enum class AddressPage { HISTORY, ASSETS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerAddressView(viewModel: ExplorerViewModel, info: ExplorerAddress, onBack: () -> Unit, open: (ExplorerRoute) -> Unit) {
    val colors = LocalAppColors.current
    val own by viewModel.ownAddress.collectAsState()
    val stats by viewModel.live.stats.collectAsState()
    val price = stats?.priceUSD ?: 0.0
    val isYou = own != null && info.address.equals(own, ignoreCase = true)
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.ab_copied)
    var page by rememberSaveable { mutableStateOf(AddressPage.HISTORY) }
    var showQR by remember { mutableStateOf(false) }
    // pages of history loaded after the first
    var morePages by remember { mutableStateOf<List<ExplorerTransaction>>(emptyList()) }
    var nextBefore by remember { mutableStateOf<Long?>(info.nextPageBefore) }
    var loadingMore by remember { mutableStateOf(false) }
    // unaccepted mining rewards are left out (owner, 2026-11-25): every block merging a found block
    // carries one, and only one counts; other unaccepted transactions stay, marked
    val history = (info.transactions + morePages).filter { !(it.kind == ExplorerTxKind.COINBASE && it.isNotAccepted) }

    fun loadMore() {
        val before = nextBefore ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val (txs, next) = viewModel.api.addressPage(info.address, before)
                val known = history.map { it.id }.toHashSet()
                morePages = morePages + txs.filter { it.id !in known }
                nextBefore = next
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Load More stays for another try
            } finally {
                loadingMore = false
            }
        }
    }

    ExplorerPageScaffold(stringResource(R.string.address), onBack = onBack, share = info.address) {
        // hero
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isYou) {
                ExplorerOwnAvatar(info.address, viewModel.ownName(), 72.dp)
            } else {
                ContactAvatar(imageUrl = null, fallbackText = info.username ?: "K", size = 72.dp, includeAssignedPhoto = false)
            }
            when {
                info.username != null -> Text("@${info.username}", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                isYou -> Text(stringResource(R.string.ex_your_chatting_address), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            // two even lines, never hyphenated
            SelectionContainer {
                Text(
                    ExplorerFormat.twoLines(info.address),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExplorerActionChip(stringResource(R.string.copy), Icons.Filled.ContentCopy) {
                    clipboard.setText(AnnotatedString(info.address))
                    haptic(IosHaptic.SUCCESS)
                    IosToasts.show(copied)
                }
                ExplorerActionChip(stringResource(R.string.qr_code), Icons.Filled.QrCode) { showQR = true }
            }
        }
        // balance
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.balance).uppercase(), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                KachatFitText(ExplorerFormat.kas(info.balance), color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Rounded, minScale = 0.5f)
                Text(ExplorerFormat.usd(info.balance, price), color = colors.textSecondary, fontSize = 15.sp)
                info.kusdtUnits?.let { units ->
                    ExplorerDivider(0.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TokenIcon(WalletToken.KUSDT, size = 20.dp)
                        Text(TokenAmount.amount(units, WalletToken.KUSDT), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                    }
                }
            }
        }
        // received and sent, complete when the first page of history reaches back 30 days
        val summaryDetail = if (info.summaryCoversMonth) stringResource(R.string.ex_last_30_days)
        else stringResource(R.string.ex_newest_n, info.transactions.size)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExplorerStatTile(Icons.Filled.SouthWest, stringResource(R.string.payment_received), ExplorerFormat.compactKAS(info.received30d), summaryDetail, Modifier.weight(1f))
            ExplorerStatTile(Icons.Filled.NorthEast, stringResource(R.string.sent), ExplorerFormat.compactKAS(info.sent30d), summaryDetail, Modifier.weight(1f))
        }
        PageCycleBar(
            titles = listOf(stringResource(R.string.address_tab_history), stringResource(R.string.ex_assets)),
            selectedIndex = page.ordinal,
            onSelect = { page = AddressPage.entries[it] },
        )
        when (page) {
            AddressPage.HISTORY -> {
                ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                    if (history.isEmpty()) {
                        Text(stringResource(R.string.ex_no_transactions), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                    history.forEachIndexed { index, tx ->
                        key(tx.id) {
                            if (index > 0) ExplorerDivider(62.dp)
                            ExplorerTransactionRow(tx, Modifier.explorerRow { open(ExplorerRoute.Transaction(tx.id)) }, perspective = info.address)
                        }
                    }
                }
                // the transaction count, and Load More while there's older history
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (nextBefore != null) {
                        Box(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface)
                                .clickable(enabled = !loadingMore) { loadMore() }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loadingMore) IosActivityIndicator(Modifier.size(20.dp))
                            else Text(stringResource(R.string.ex_load_more), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    info.transactionCount?.let { total ->
                        Text(stringResource(R.string.ex_n_of_total, history.size, total), color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            AddressPage.ASSETS -> {
                // Kaspa and kUSDT, always both: kUSDT shows at 0 too (owner, 2026-11-25)
                ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                    AssetRow(
                        name = "Kaspa",
                        detail = stringResource(R.string.ex_kaspa_coin),
                        amount = ExplorerFormat.kas(info.balance),
                        value = ExplorerFormat.usd(info.balance, price),
                    ) {
                        Image(painterResource(R.drawable.ic_kaspa_logo), contentDescription = null, modifier = Modifier.size(36.dp))
                    }
                    ExplorerDivider(62.dp)
                    val units = info.kusdtUnits ?: 0L
                    AssetRow(
                        name = "kUSDT",
                        detail = stringResource(R.string.ex_kusdt_coin),
                        amount = TokenAmount.amount(units, WalletToken.KUSDT),
                        value = ExplorerFormat.usdValue(units / 1_000_000.0),
                    ) {
                        TokenIcon(WalletToken.KUSDT, size = 36.dp)
                    }
                }
            }
        }
    }

    if (showQR) {
        ModalBottomSheet(
            onDismissRequest = { showQR = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(stringResource(R.string.address), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp)) {
                    Image(rememberQrBitmapPainter(info.address), contentDescription = null, modifier = Modifier.size(220.dp))
                }
                Text(
                    info.address,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        }
    }
}

/** One asset the address holds: its logo, name, amount and dollar value. */
@Composable
private fun AssetRow(name: String, detail: String, amount: String, value: String, icon: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.textSecondary, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KachatFitText(amount, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, minScale = 0.7f)
            Text(value, color = colors.textSecondary, fontSize = 12.sp, style = Tabular)
        }
    }
}

// MARK: - Rows

@Composable
internal fun ExplorerBlockRow(block: ExplorerBlock, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(KaspaTeal), contentAlignment = Alignment.Center) {
            Icon(ExplorerSymbols.CubeFill, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ExplorerFormat.grouped(block.blueScore), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            ExplorerMonoText(block.id, middle = true)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val count = block.transactionIds.size
            Text(
                if (count == 1) stringResource(R.string.ex_one_transaction) else stringResource(R.string.ex_n_transactions, count),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            ExplorerAgoText(block.time)
        }
    }
}

/**
 * A transaction's row: its kind, id and amount. On an address page ([perspective]) the amount is
 * what the transaction did to that address, in (+) or out (-): its outputs to it less its inputs
 * from it (audit 2026-11-25 C4). One the network didn't accept is dimmed, its amount struck through.
 */
@Composable
internal fun ExplorerTransactionRow(
    tx: ExplorerTransaction,
    modifier: Modifier = Modifier,
    showsAge: Boolean = true,
    perspective: String? = null,
) {
    val colors = LocalAppColors.current
    val net = perspective?.let { tx.net(it) }
    val amountText = if (perspective == null || tx.tokenUnits != null || net == null) {
        ExplorerFormat.amountLine(tx, short = true)
    } else {
        (if (net.first) "+" else "−") + ExplorerFormat.shortKAS(net.second)
    }
    val outgoing = net?.first == false
    val green = colors.success
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .alpha(if (tx.isNotAccepted) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExplorerKindBadge(tx.kind, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(tx.kind.title), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (tx.isInMempool) SmallCapsule(stringResource(R.string.token_pending), colors.warning)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExplorerMonoText(tx.id, middle = true)
                // beside the id, so the title keeps one line
                if (tx.isNotAccepted) SmallCapsule(stringResource(R.string.ex_not_accepted), colors.textSecondary)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                amountText,
                color = if (perspective == null || tx.isNotAccepted || outgoing) colors.textPrimary else green,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                style = Tabular,
                textDecoration = if (tx.isNotAccepted) TextDecoration.LineThrough else null,
            )
            if (showsAge) ExplorerAgoText(tx.time)
        }
    }
}

@Composable
private fun SmallCapsule(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** An input's or output's address and amount; "You" on your own, "Change" on one back to the sender. */
@Composable
internal fun ExplorerAddressRow(address: String, amount: Long, isYou: Boolean, isChange: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isYou) ExplorerOwnAvatar(address, "", 34.dp)
        else ContactAvatar(imageUrl = null, fallbackText = address.takeLast(2).uppercase(), size = 34.dp, includeAssignedPhoto = false)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (isYou || isChange) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isYou) Text(stringResource(R.string.reactions_you), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (isChange) Text(stringResource(R.string.swap_change), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            // never cut: the amount beside it shrinks first
            ExplorerMonoText(address, middle = true)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(start = 8.dp)) {
            KachatFitText(ExplorerFormat.kas(amount), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, minScale = 0.6f)
        }
    }
}
