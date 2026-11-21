package com.kachat.app.ui.screens.coldstorage

import com.kachat.app.ui.screens.*

import com.kachat.app.R
import com.kachat.app.util.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.launch
import java.util.Locale

// KaPay's Coin Control (iOS 148a5331: Cold Storage's Coin Control is KaPay's `CoinControlView`):
// every coin at the address, the ones the build can't spend greyed out with why, KasSigner's input
// cap, and the coin labels. KaChat's hot-wallet `CoinControlScreen` (ui/screens/CoinControl.kt) is
// its own and is left as it is.
/**
 * [ColdCoinControlScreen] as iOS presents its `CoinControlView`: a sheet over the send that asked for
 * it, Cancel leading its bar. Confirm hands the pick to [onDone] and slides the sheet down; Cancel,
 * Back or a swipe down leave the pick as it was.
 */
@Composable
internal fun ColdCoinControlSheet(
    fromAddress: String,
    fetchUtxos: suspend (String) -> List<UtxoEntry>,
    initialSelection: List<UtxoEntry>?,
    onDone: (List<UtxoEntry>?) -> Unit,
    onDismiss: () -> Unit,
    maxSelection: Int = com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION,
    /** The coin labels for [fromAddress] (outpoint key "txId:index" -> label), shown over each
     *  coin's amount: a spending address's (Manage Addresses) or a KasSigner address's, as iOS
     *  148a5331's Cold Storage coin control shows them. */
    utxoLabels: Map<String, String> = emptyMap(),
    /** See [CoinControlScreen]: a hot-wallet send's coins with why it can't spend some of them. */
    fetchCoinsWithReasons: (suspend (String) -> List<Pair<UtxoEntry, String?>>)? = null,
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true, grouped = true) { close ->
        BackHandler(onBack = close)
        ColdCoinControlScreen(
            fromAddress = fromAddress,
            fetchUtxos = fetchUtxos,
            initialSelection = initialSelection,
            onDone = { selection -> onDone(selection); close() },
            onCancel = close,
            maxSelection = maxSelection,
            utxoLabels = utxoLabels,
            fetchCoinsWithReasons = fetchCoinsWithReasons,
        )
    }
}

/**
 * Coin control — lets the user fix the exact UTXO set a send spends from instead of the
 * automatic largest-first selector. Shared by [ColdSendFlow] and the spending-address send flow
 * (`SpendingAddressSendFlow` in Screens.kt) — not Cold-Storage-specific: parameterized by
 * [fetchUtxos] rather than a concrete ViewModel, since the only thing this screen needs is a way
 * to fetch UTXOs for an address. Passes the selection back as a plain `List<UtxoEntry>?` (null =
 * automatic) rather than owning any state itself, since the actual spendable set needs
 * re-resolving against a fresh fetch at build time anyway (see [ColdStorageSendEngine]'s/
 * [KaspaWalletEngine]'s `manualUtxos` handling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColdCoinControlScreen(
    fromAddress: String,
    fetchUtxos: suspend (String) -> List<UtxoEntry>,
    initialSelection: List<UtxoEntry>?,
    onDone: (List<UtxoEntry>?) -> Unit,
    onCancel: () -> Unit,
    /** The most coins one transaction from here may spend: the hot wallet's input cap, or
     *  KasSigner's ([com.kachat.app.util.KsptCodec.MAX_INPUTS]) for Cold Storage. A pick never
     *  grows past it (see [com.kachat.app.util.CoinControlSelection]). */
    maxSelection: Int = com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION,
    utxoLabels: Map<String, String> = emptyMap(),
    /** A hot-wallet send's coins, each with why the send can't spend it (null when it can): those
     *  are listed greyed out with the reason and never picked, so a selection can't be made of
     *  coins the send would refuse (iOS 1652437 `CoinControlView.unavailableReason`). Null for
     *  Cold Storage, whose coins the app doesn't hold back: [fetchUtxos] then lists them all. */
    fetchCoinsWithReasons: (suspend (String) -> List<Pair<UtxoEntry, String?>>)? = null,
) {
    var utxos by remember { mutableStateOf<List<UtxoEntry>>(emptyList()) }
    /** Why a listed coin can't be spent, by outpoint (see [fetchCoinsWithReasons]). */
    var unavailable by remember { mutableStateOf<Map<com.kachat.app.services.Outpoint, String>>(emptyMap()) }
    var selectedKeys by remember { mutableStateOf<Set<com.kachat.app.services.Outpoint>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    // The node could not be asked. Shown in place of "no coins": an outage must never read as an
    // empty address when the coins here are about to be spent (iOS CoinControlView.loadError).
    var loadError by remember { mutableStateOf<String?>(null) }
    val loadScope = rememberCoroutineScope()

    suspend fun loadUtxos() {
        isLoading = true
        try {
            val withReasons = fetchCoinsWithReasons
            if (withReasons != null) {
                val coins = withReasons(fromAddress)
                utxos = coins.map { it.first }
                unavailable = coins.mapNotNull { (utxo, reason) -> reason?.let { utxo.outpoint to it } }.toMap()
            } else {
                utxos = fetchUtxos(fromAddress)
                unavailable = emptyMap()
            }
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = com.kachat.app.util.UserFacingError.message(e, "Couldn't load the coins.")
        }
        isLoading = false
    }

    /** The coins that can be picked: every listed one but those [unavailable] names. */
    val availableUtxos = utxos.filter { it.outpoint !in unavailable }
    LaunchedEffect(fromAddress) {
        loadUtxos()
        if (!initialSelection.isNullOrEmpty()) {
            // coins spent since they were picked drop out of the selection
            selectedKeys = com.kachat.app.util.CoinControlSelection
                .resolve(utxos.filter { it.outpoint !in unavailable }, initialSelection.map { it.outpoint }.toSet(), maxSelection)
                .map { it.outpoint }.toSet()
        }
    }
    val atCap = com.kachat.app.util.CoinControlSelection.isAtCap(selectedKeys.size, availableUtxos.size, maxSelection)

    val selectedTotalSompi = availableUtxos.filter { selectedKeys.contains(it.outpoint) }.sumOf { it.utxoEntry.amount }

    // iOS's CoinControlView: an inline bar (Cancel, "Coin Control", the ellipsis.circle menu), an
    // inset grouped list of the coins with the running total as its footer, and a capsule button
    // pinned under it.
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.coin_control), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.padding(start = 12.dp))
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    // iOS 26: the menu on a glass disc, 16 in from the edge.
                    SheetBarIconButton(
                        Icons.Default.MoreHoriz,
                        "More",
                        onClick = { showMenu = true },
                        modifier = Modifier.padding(end = 12.dp),
                        iconSize = 22.dp,
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = { selectedKeys = com.kachat.app.util.CoinControlSelection.selectAll(availableUtxos, maxSelection); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automatic_clear_selection)) },
                            onClick = { selectedKeys = emptySet(); showMenu = false }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .clickable {
                        val selected = com.kachat.app.util.CoinControlSelection.resolve(availableUtxos, selectedKeys, maxSelection)
                        onDone(selected.ifEmpty { null })
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selectedKeys.isEmpty()) stringResource(R.string.use_automatic_selection) else stringResource(R.string.confirm_selection),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            when (coinListContent(isLoading, utxos.isNotEmpty(), loadError)) {
                CoinListContent.LOADING -> item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                    }
                }
                CoinListContent.LOAD_FAILED -> item {
                    UtxoLoadFailureRow(
                        detail = loadError.orEmpty(),
                        retry = { loadScope.launch { loadUtxos() } },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface),
                    )
                }
                CoinListContent.EMPTY -> item {
                    Text(
                        stringResource(R.string.no_utxos_found_at_address),
                        color = colors.textSecondary,
                        fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                CoinListContent.COINS -> {
                    if (atCap) {
                        item {
                            Text(
                                stringResource(R.string.coin_control_max_selection, maxSelection),
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    itemsIndexed(utxos, key = { _, it -> "${it.outpoint.transactionId}:${it.outpoint.index}" }) { index, utxo ->
                        val isSelected = selectedKeys.contains(utxo.outpoint)
                        val unavailableReason = unavailable[utxo.outpoint]
                        val first = index == 0
                        val last = index == utxos.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (first) 10.dp else 0.dp, topEnd = if (first) 10.dp else 0.dp,
                            bottomStart = if (last) 10.dp else 0.dp, bottomEnd = if (last) 10.dp else 0.dp,
                        )
                        Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // a coin the send can't spend is never picked (iOS 1652437)
                                    .clickable(enabled = unavailableReason == null) {
                                        // Past the cap the tap is refused; the note above says why.
                                        com.kachat.app.util.CoinControlSelection
                                            .toggle(selectedKeys, utxo.outpoint, maxSelection)
                                            ?.let { selectedKeys = it }
                                    }
                                    .alpha(if (unavailableReason != null || (!isSelected && atCap)) 0.5f else 1f)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                    null,
                                    tint = if (isSelected) KaspaTeal else colors.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    utxoLabels["${utxo.outpoint.transactionId}:${utxo.outpoint.index}"]?.takeIf { it.isNotEmpty() }?.let { label ->
                                        Text(label, color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Text(
                                        "%.8f ${KaspaUnit.symbol}".format(Locale.US, utxo.utxoEntry.amount / 100_000_000.0),
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                    )
                                    Text(
                                        "${utxo.outpoint.transactionId.take(10)}...:${utxo.outpoint.index}",
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                    unavailableReason?.let { reason ->
                                        Text(reason, color = colors.warning, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (!last) HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp))
                        }
                    }
                    // iOS's section footer, word for word (it is not translated there either).
                    if (selectedKeys.isNotEmpty()) {
                        item {
                            val count = selectedKeys.size
                            Text(
                                // iOS's footer, "Selected: %@ (1 UTXO)" / "(%lld UTXOs)"
                                run {
                                    val total = "%.8f ${KaspaUnit.symbol}".format(Locale.US, selectedTotalSompi / 100_000_000.0)
                                    if (count == 1) tr(R.string.kp_selected_one_utxo_kc, total)
                                    else tr(R.string.kp_selected_utxos_kc, total, count)
                                },
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
