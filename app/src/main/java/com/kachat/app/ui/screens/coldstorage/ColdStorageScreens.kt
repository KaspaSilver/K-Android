package com.kachat.app.ui.screens.coldstorage

import com.kachat.app.ui.screens.*

import com.kachat.app.util.tr
import com.kachat.app.R
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Verified
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.services.ColdStorageSendEngine
import com.kachat.app.services.KnsService
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.KsptCodec
import com.kachat.app.viewmodels.ColdStorageViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cold Storage — a fully separate area of the app for watching/spending funds held on an
 * air-gapped KasSigner device. Everything here is watch-only (public keys only); signing always
 * happens on the physical device via QR exchange, never inside KaChat. See the KasSigner project
 * README for the device's own safety disclaimers: experimental, unaudited, no secure element.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageListScreen(
    /** Opens one account's addresses ([ColdStorageDetailScreen]). */
    onOpenAccount: (String) -> Unit,
    /** The backup banner's tap: Back Up Account. */
    onOpenAccountBackup: () -> Unit,
    // MainShell's one WalletViewModel, for the backup banner (see PortfolioScreen).
    walletViewModel: WalletViewModel,
    viewModel: ColdStorageViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualKpubInput by remember { mutableStateOf("") }
    var pendingKpub by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    // A kpub reveals every address of the account and their whole history: copied like the app's
    // other sensitive values, marked sensitive and cleared after 30 s (iOS 428a797b, audit #4).
    val kpubContext = LocalContext.current
    val kpubHaptic = com.kachat.app.util.rememberHaptics()
    /** Account whose kpub QR is on screen. */
    var kpubQrAccount by remember { mutableStateOf<ColdStorageManager.ColdAccount?>(null) }
    var renameInput by remember { mutableStateOf("") }

    LaunchedEffect(importState.status) {
        if (importState.status == ColdStorageViewModel.ImportStatus.SUCCESS) {
            pendingKpub = null
            nameInput = ""
            viewModel.resetImportState()
        }
    }

    // A sheet over the list, as iOS's ColdStorageView presents its QRScannerView.
    if (showScanner) {
        QrScannerSheet(
            onScanned = { scanned ->
                pendingKpub = scanned
                nameInput = "Cold Storage ${accounts.size + 1}"
            },
            onDismiss = { showScanner = false }
        )
    }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = stringResource(R.string.cold_storage),
                banner = { AccountBackupBanner(walletViewModel, onOpenAccountBackup) },
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Matches iOS's ColdStorageListView: "Paste kpub" (outlined, secondary) and "Scan"
            // (filled, primary) side by side at the bottom, instead of a single centered FAB.
            // Above the dock the list runs under.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = dockInset)
            ) {
                Button(
                    onClick = {
                        manualKpubInput = ""
                        showManualEntry = true
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.paste_kpub), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    val scanContentDescription = stringResource(R.string.scan_kpub_from_kassigner)
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.scan),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = scanContentDescription }
                    )
                }
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            // iOS ColdStorageView.emptyState: at the top of the list, 40 down - a 40pt shield, a
            // headline, the subheadline note in the secondary colour, 12 apart.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(com.kachat.app.ui.theme.IosSymbols.LockShield, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(44.dp))
                Text(
                    stringResource(R.string.cold_storage_empty_title),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    stringResource(R.string.cold_storage_empty_body),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // The last account scrolls clear of the dock it runs under.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + dockInset),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(accounts) { account ->
                    var showMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { onOpenAccount(account.id) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                account.kpub,
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.account_actions), tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }

                    if (showMenu) {
                        // Renaming happens IN the sheet rather than by dismissing into a dialog:
                        // the sheet is already the context for "this account", and bouncing out
                        // to a dialog to type one word threw that away. Matches iOS's
                        // ColdStorageAccountActionsSheet.
                        var isRenaming by remember(account.id) { mutableStateOf(false) }
                        ActionSheetContainer(
                            title = if (isRenaming) tr(R.string.kp_rename_account_90ddc) else account.name,
                            subtitle = if (isRenaming) null else {
                                com.kachat.app.services.AddressActivityNotifier
                                    .shortAddress(account.kpub)
                            },
                            onDismiss = { showMenu = false },
                        ) {
                            if (isRenaming) {
                                ActionSheetRenameFields(
                                    value = renameInput,
                                    onValueChange = { renameInput = it },
                                    // Cancel walks back to the options rather than closing, so a
                                    // mis-tap on Rename costs nothing.
                                    onCancel = { isRenaming = false },
                                    onSave = {
                                        viewModel.renameAccount(account.id, renameInput.trim())
                                        showMenu = false
                                    },
                                )
                            } else {
                                ActionSheetRow(
                                    icon = Icons.Default.ContentCopy,
                                    title = stringResource(R.string.copy_kpub),
                                    subtitle = tr(R.string.kp_puts_the_extended_public_key_kc),
                                ) {
                                    showMenu = false
                                    com.kachat.app.util.copyPrivateKeyWithAutoWipe(kpubContext, account.kpub, label = "kpub")
                                    kpubHaptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                    IosToasts.show("kpub copied to clipboard.")
                                }
                                ActionSheetRow(
                                    icon = Icons.Default.QrCode,
                                    title = tr(R.string.kp_show_kpub_qr_kc),
                                    subtitle = tr(R.string.kp_scan_it_into_another_device_kc),
                                ) {
                                    showMenu = false
                                    kpubQrAccount = account
                                }
                                ActionSheetRow(
                                    icon = Icons.Default.Edit,
                                    title = stringResource(R.string.rename),
                                    subtitle = tr(R.string.kp_changes_the_name_shown_for_this_account_kc),
                                ) {
                                    renameInput = account.name
                                    isRenaming = true
                                }
                                // Per account, not all of cold storage at once: this account's
                                // receives alone (iOS ba352a2).
                                ActionSheetRow(
                                    icon = if (account.notifyOnReceive) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                    title = if (account.notifyOnReceive) tr(R.string.kp_turn_off_receive_notifications_kc) else tr(R.string.kp_turn_on_receive_notifications_kc),
                                    subtitle = if (account.notifyOnReceive) {
                                        tr(R.string.kp_stops_notifying_when_this_account_kc)
                                    } else {
                                        tr(R.string.kp_notifies_you_when_this_account_kc)
                                    },
                                ) {
                                    viewModel.setNotifyOnReceive(account.id, !account.notifyOnReceive)
                                    showMenu = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    kpubQrAccount?.let { account ->
        // The app's own full-screen QR overlay, the same one an address uses - white to the
        // edges, tap anywhere to copy, back arrow to leave. A kpub deserves the same treatment as
        // an address, and it is the harder of the two to scan (114 characters against ~60), so
        // the extra screen actually matters here.
        //
        // The message says what a kpub is: no private key, cannot spend - which is why showing it
        // is safe at all - but it derives EVERY address in the account, so whoever scans it can
        // watch that balance and history forever.
        QrCodeOverlay(
            value = account.kpub,
            onDismiss = { kpubQrAccount = null },
            message = tr(R.string.kp_watch_only_this_cannot_spend_but_95314),
            // A kpub is ~114 characters; the two-line default is for an address and would cut it.
            valueMaxLines = 5,
            copiedToast = "kpub copied to clipboard.",
            sensitiveCopy = true,
        )
    }

    if (showManualEntry) {
        // A half sheet rather than a dialog: it holds one field, and a kpub is 114 characters -
        // a dialog's single-line slot showed a sliver of it.
        ActionSheetContainer(
            title = stringResource(R.string.enter_kpub),
            subtitle = stringResource(R.string.paste_the_kpub_exported_from_your),
            onDismiss = { showManualEntry = false },
        ) {
            com.kachat.app.ui.theme.IosTextField(
                value = manualKpubInput,
                onValueChange = { manualKpubInput = it },
                label = { Text(stringResource(R.string.kpub_2)) },
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    focusedBorderColor = KaspaTeal,
                    unfocusedBorderColor = LocalAppColors.current.textSecondary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showManualEntry = false },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textPrimary)
                }
                Button(
                    onClick = {
                        val trimmed = manualKpubInput.trim()
                        showManualEntry = false
                        // Feeds the same pendingKpub/nameInput -> import flow the QR scanner
                        // already uses - scan vs. paste differ only in how the raw kpub string
                        // is obtained, not in how it is validated, named or imported.
                        pendingKpub = trimmed
                        nameInput = "Cold Storage ${accounts.size + 1}"
                    },
                    enabled = manualKpubInput.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                ) {
                    Text(stringResource(R.string.next), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (pendingKpub != null) {
        val kpub = pendingKpub!!
        val isInvalid = importState.status == ColdStorageViewModel.ImportStatus.INVALID_KPUB
        val colors = LocalAppColors.current
        // A half sheet rather than a dialog box, matching every other menu on this screen. This
        // was the one step in a sheet-shaped flow that still popped a dialog.
        com.kachat.app.ui.theme.IosSheetColors {
            val colors = LocalAppColors.current
            ModalBottomSheet(
                scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
                shape = com.kachat.app.ui.theme.IosSheetShape,
                tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
                onDismissRequest = { pendingKpub = null; viewModel.resetImportState() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.background,
            ) {
                // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
                // its content stays above it.
                Column(Modifier.navigationBarsPadding()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // iOS importNameSheet's headline.
                        Text(
                            stringResource(R.string.cold_name_this_account),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                        Text(
                            tr(R.string.kp_give_this_account_a_name_so_18d55),
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "kpub: ${kpub.take(24)}\u2026",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        com.kachat.app.ui.theme.IosTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text(stringResource(R.string.name)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = KaspaTeal,
                                unfocusedBorderColor = colors.textSecondary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isInvalid) {
                            Text(
                                importState.errorMessage ?: "Not a valid kpub",
                                color = LocalAppColors.current.danger,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextButton(
                                onClick = { pendingKpub = null; viewModel.resetImportState() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.cancel), color = colors.textSecondary)
                            }
                            Button(
                                enabled = nameInput.isNotBlank(),
                                onClick = { viewModel.importKpub(kpub, nameInput) },
                                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.import_action), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageDetailScreen(
    accountId: String,
    viewModel: ColdStorageViewModel,
    onBack: () -> Unit,
    /** One address's page ([ColdStorageTxHistoryScreen]). */
    onOpenAddress: (String) -> Unit,
    /** Address Visibility for this account ([ColdStorageAddressVisibilityScreen]). */
    onOpenVisibility: (String) -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState()
    val account = accounts.find { it.id == accountId }
    val addresses by viewModel.addresses.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val discoveryProgress by viewModel.discoveryProgress.collectAsState()
    val isUserDiscovering by viewModel.isUserDiscovering.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val kpubHaptic = com.kachat.app.util.rememberHaptics()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sendFromRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var labelingRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var qrRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showActionsSheet by remember { mutableStateOf(false) }
    /** Result line kept on screen after a scan finishes, until the sheet is dismissed. */
    var discoverySummary by remember { mutableStateOf<String?>(null) }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(accountId) {
        viewModel.refreshAddresses(accountId)
    }

    // A receipt or spend on one of this account's addresses: reload, so the funds show without a
    // manual pull (iOS ColdStorageView's `.ownAddressUtxoActivity`).
    LaunchedEffect(accountId) {
        viewModel.ownAddressActivity.collect { changed ->
            if (!viewModel.isDiscovering.value && viewModel.addresses.value.any { it.address in changed }) {
                viewModel.refreshAddresses(accountId)
            }
        }
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshAddresses(accountId)
        }
    }

    LaunchedEffect(isDiscovering) {
        if (!isDiscovering && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    // Ordering (matches iOS ColdStorageView / Manage Addresses): addresses with a balance OR an
    // owned KNS domain first (funded before domain-only, newest index first within each group),
    // then fresh addresses. Domain knowledge fills in asynchronously after the batched lookups.
    val domainOwningAddresses by viewModel.domainOwningAddresses.collectAsState()
    val visibleAddresses = remember(addresses, domainOwningAddresses) {
        val visible = addresses.filterNot { it.hidden }
        val rest = visible
            .sortedWith(compareByDescending<ColdStorageViewModel.AddressRow> { it.balanceSompi > 0 }.thenByDescending { it.index })
        val active = rest.filter { it.balanceSompi > 0 || it.address in domainOwningAddresses }
        val fresh = rest.filter { it.balanceSompi == 0L && it.address !in domainOwningAddresses }
        active + fresh
    }
    // A hidden address is excluded on purpose (a "put this aside" gesture) — it shouldn't keep
    // inflating the balance you actually think of as available.
    val totalBalanceSompi = visibleAddresses.sumOf { it.balanceSompi }

    // A sheet over the list, as iOS presents ColdSendFlowView; a swipe down closes it, except
    // while the signed transaction is going out.
    sendFromRow?.let { row ->
        var isSending by remember { mutableStateOf(false) }
        IosFullSheet(
            onDismissed = { sendFromRow = null; viewModel.refreshAddressesSoonAfterSend(accountId) },
            swipeToDismiss = !isSending,
        ) { close ->
            ColdSendFlow(
                fromAddress = row.address,
                availableBalanceSompi = row.balanceSompi,
                viewModel = viewModel,
                onDone = close,
                presentedAsSheet = true,
                onSendingChange = { isSending = it },
            )
        }
    }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(account?.name ?: tr(R.string.kp_cold_storage_e1246), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton(onBack, Modifier.padding(start = 12.dp)) },
                actions = {
                    // Address Visibility used to be an unlabelled checklist glyph up here. It is
                    // one of this account's address actions, so it lives with the others in the
                    // Address Actions sheet, where it has room to say what it does.
                    SheetBarIconButton(
                        Icons.Default.Delete,
                        "Remove account",
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.padding(end = 12.dp),
                        tint = Color.Red,
                        iconSize = 22.dp,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(pullRefreshState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The last address scrolls clear of the dock it runs under.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + dockInset),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.name), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(
                                account?.name ?: tr(R.string.kp_cold_storage_e1246),
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                renameInput = account?.name ?: ""
                                showRenameDialog = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, tr(R.string.kp_rename_d3f4c), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.kpub), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        account?.kpub ?: "",
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.clickable {
                            account?.kpub?.let {
                                // marked sensitive, cleared after 30 s (iOS 428a797b, audit #4)
                                com.kachat.app.util.copyPrivateKeyWithAutoWipe(context, it, label = "kpub")
                                kpubHaptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                IosToasts.show("kpub copied to clipboard.")
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.copy_kpub), color = KaspaTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // The same card as Manage Addresses' total (and an address's own page), with
                    // its worth in your currency on mainnet (iOS 428a797b `KaspaAddressBalanceCard`).
                    com.kachat.app.ui.screens.KaspaAddressBalanceCard(
                        label = stringResource(R.string.total_balance),
                        sompi = totalBalanceSompi,
                    )

                    // iOS: the summary's 18 apart, under the card's own 8
                    Spacer(Modifier.height(18.dp))
                    // Directly under the balance rather than floating over the list: the actions
                    // are about this account, so they belong with it, and a FAB covered the last
                    // address row on a short list.
                    Button(
                        onClick = { showActionsSheet = true },
                        enabled = !isUserDiscovering,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (isUserDiscovering) {
                            com.kachat.app.ui.theme.IosActivityIndicator(strokeWidth = 2.dp, color = Color.Black, modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(R.string.address_actions), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.addresses),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (isDiscovering && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                    }
                }
            } else if (visibleAddresses.isEmpty()) {
                item {
                    Text(
                        if (addresses.isEmpty()) tr(R.string.kp_no_addresses_discovered_yet_15738) else "All addresses are hidden.",
                        color = LocalAppColors.current.textSecondary
                    )
                }
            } else {
                items(visibleAddresses, key = { it.index }) { row ->
                    ColdAddressRow(
                        row = row,
                        showsDomainTag = row.address in domainOwningAddresses,
                        onAddressClick = { onOpenAddress(row.address) },
                        onLabelClick = { labelingRow = row; labelInput = row.label ?: "" },
                        onCopyClick = {
                            clipboardManager.setText(AnnotatedString(row.address))
                            com.kachat.app.util.showAddressCopiedToast(context, row.address)
                        },
                        onSendClick = { if (row.balanceSompi > 0) sendFromRow = row },
                        onShowQrClick = { qrRow = row },
                        // iOS: Hide is offered only for an unfunded address; the toast waits for
                        // the setter's live check, and a refusal says the address can't be hidden.
                        onHideClick = if (row.balanceSompi == 0L) {
                            {
                                viewModel.setColdVisibilityHidden(accountId, row.index, true) { ok ->
                                    IosToasts.show(
                                        if (ok) tr(R.string.kp_address_hidden_reenable)
                                        else tr(R.string.kp_address_cant_be_hidden)
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            item {
                // Leaves room so the last address row isn't hidden behind the FAB.
                Spacer(Modifier.height(64.dp))
            }
        }
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        qrRow?.let { row ->
            // The shared receive screen, Kaspa only, in a sheet with Close (iOS 3d126db
            // ColdStorageAddressQRView).
            QrPageSheet(onDismiss = { qrRow = null }, white = false) {
                ColdStorageReceivePage(
                    address = row.address,
                    label = row.label?.takeIf { it.isNotBlank() } ?: tr(R.string.kp_address_lld_64ab3, row.index),
                    balanceSompi = row.balanceSompi,
                    dismiss = QrPageDismiss.CLOSE,
                    onDismiss = { qrRow = null },
                )
            }
        }
        }
    }

    if (showDeleteConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.remove_cold_storage_account), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    // KaChat's watch list (iOS 148a5331)
                    tr(R.string.kp_this_only_removes_it_from_kachat_watch_list_kc),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(accountId)
                    showDeleteConfirm = false
                    onBack()
                }) {
                    Text(stringResource(R.string.remove), color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showRenameDialog) {
        ActionSheetContainer(
            title = stringResource(R.string.rename_cold_storage_account),
            subtitle = account?.name,
            onDismiss = { showRenameDialog = false },
        ) {
            ActionSheetRenameFields(
                value = renameInput,
                onValueChange = { renameInput = it },
                onCancel = { showRenameDialog = false },
                onSave = {
                    viewModel.renameAccount(accountId, renameInput.trim())
                    showRenameDialog = false
                },
            )
        }
    }

    labelingRow?.let { row ->
        ActionSheetContainer(
            title = stringResource(R.string.name_this_address),
            subtitle = com.kachat.app.services.AddressActivityNotifier.shortAddress(row.address),
            onDismiss = { labelingRow = null },
        ) {
            ActionSheetRenameFields(
                value = labelInput,
                onValueChange = { labelInput = it },
                onCancel = { labelingRow = null },
                onSave = {
                    viewModel.setAddressLabel(accountId, row.index, labelInput)
                    labelingRow = null
                },
            )
        }
    }

    if (showActionsSheet) {
        ColdStorageAddressActionsSheet(
            isDiscovering = isUserDiscovering,
            progress = discoveryProgress,
            summary = discoverySummary,
            onGenerate = {
                showActionsSheet = false
                viewModel.generateMoreAddresses(accountId) { index ->
                    if (index != null) IosToasts.show(tr(R.string.kp_address_number_ready, index)) else IosToasts.error("Could not derive a new address.")
                }
            },
            onDiscover = {
                discoverySummary = null
                viewModel.refreshAddresses(accountId, userInitiated = true) { count ->
                    val summary = if (count > 0) {
                        if (count == 1) tr(R.string.kp_found_one_address_balance_domain) else tr(R.string.kp_found_n_addresses_balance_domain, count)
                    } else {
                        tr(R.string.kp_no_addresses_balance_domain_found)
                    }
                    discoverySummary = summary
                    // Closed the sheet and carried on? Then the summary above has nowhere to
                    // render, so say it here instead of finishing silently.
                    if (!showActionsSheet) IosToasts.show(summary)
                }
            },
            onVisibility = {
                showActionsSheet = false
                discoverySummary = null
                onOpenVisibility(accountId)
            },
            onDismiss = {
                showActionsSheet = false
                discoverySummary = null
            },
        )
    }
}

/**
 * Address Visibility for one Cold Storage account (the same tool Manage Addresses has) — a
 * compact checkmark list of EVERY derived address, paged 50 at a time, so dozens can be toggled
 * off the main list in one sitting. The right arrow never runs out: future pages derive
 * addresses beyond the derived bound on the fly, and toggling one on raises the bound while
 * keeping the intermediate indices hidden. Funded addresses are locked visible. Shares
 * [ColdStorageDetailScreen]'s ViewModel instance (see KaChatApp.kt) so edits show on the detail
 * list the moment this screen pops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageAddressVisibilityScreen(
    accountId: String,
    viewModel: ColdStorageViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val addresses by viewModel.addresses.collectAsState()
    var page by remember { mutableStateOf(0) }
    val pageSize = 50
    val byIndex = remember(addresses) { addresses.associateBy { it.index } }
    val listMax = remember(addresses) { addresses.maxOfOrNull { it.index } ?: -1 }
    // Lazily filled Used/Unused results for rows derived beyond the loaded list.
    val usedCache = remember { mutableStateMapOf<Int, Boolean>() }

    // Batch-load fresh balances on entry (one round trip for the whole derived list): the toggle
    // rule trusts rows THIS load confirms, letting every checkmark flip instantly with no
    // per-toggle network wait. Rows the load could not confirm fall back to the fail-closed
    // per-address check. Mirrors the spending checklist's same refresh-on-entry.
    LaunchedEffect(Unit) { viewModel.refreshAddresses(accountId) }

    val start = page * pageSize
    val end = start + pageSize - 1
    // The page's addresses derive off the main thread (EC math, 50 per page); until they land a
    // row reads "deriving...", as it already did for one that failed.
    val derivedPage by produceState<Map<Int, String>>(emptyMap(), accountId, page) {
        val range = start..end
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            range.associateWith { viewModel.coldAddressAt(accountId, it) ?: "" }
        }
    }
    val pageEntries = remember(byIndex, page, derivedPage) {
        (start..end).map { index ->
            byIndex[index] ?: ColdStorageViewModel.AddressRow(
                index = index,
                address = derivedPage[index] ?: "",
                balanceSompi = 0L,
                hasHistory = false,
                label = null,
                hidden = true
            )
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(tr(R.string.kp_address_visibility_9c9a7), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                // iOS 26: only Done, semibold, on a glass capsule (iOS's confirmation action) -
                // the back arrow beside it did the same thing.
                actions = {
                    SheetBarButton(tr(R.string.kp_done_e9b45), onClick = onBack, emphasized = true, modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalAppColors.current.background)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { if (page > 0) page -= 1 }, enabled = page > 0) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        "Previous page",
                        tint = if (page > 0) KaspaTeal else LocalAppColors.current.textSecondary.copy(alpha = 0.4f)
                    )
                }
                Text(
                    "#$start - #$end",
                    color = LocalAppColors.current.textSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { page += 1 }) {
                    Icon(Icons.Default.ChevronRight, "Next page", tint = KaspaTeal)
                }
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()
        // Turning the page keeps the scroll offset otherwise, so page 2 opened wherever page 1
        // was left - at the bottom, which is the only place the pager can be tapped from.
        LaunchedEffect(page) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(pageEntries, key = { it.index }) { entry ->
                val visible = entry.index <= listMax && !entry.hidden
                val funded = entry.balanceSompi > 0
                // Used-state for derived rows the list loader has never seen.
                if (entry.index > listMax && entry.address.isNotEmpty() && entry.index !in usedCache) {
                    LaunchedEffect(entry.index) {
                        // Only a real answer is cached. A null means the probe failed, and the row
                        // keeps its neutral badge and is asked again rather than claiming "Unused".
                        viewModel.hasColdAddressBeenUsed(accountId, entry.index)
                            ?.let { usedCache[entry.index] = it }
                    }
                }
                val used = if (entry.index <= listMax) entry.hasHistory else usedCache[entry.index]
                // The WHOLE row toggles, not just the checkmark. No primary-address rule here —
                // a watch-only kpub account has no primary; only "funded stays visible" applies.
                // iOS's toggle: a funded row does nothing, and a hide the live check refuses
                // leaves the row as it was.
                val toggleVisibility: () -> Unit = {
                    when {
                        funded -> Unit
                        entry.index > listMax ->
                            viewModel.revealColdAddress(accountId, entry.index)
                        else -> viewModel.setColdVisibilityHidden(accountId, entry.index, !entry.hidden)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(onClick = toggleVisibility)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .alpha(if (visible) 1f else 0.55f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = toggleVisibility,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (visible) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            if (visible) "Visible" else "Hidden",
                            tint = if (visible) KaspaTeal else LocalAppColors.current.textSecondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${entry.index}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            entry.label?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        Text(
                            if (entry.address.isNotEmpty()) "${entry.address.take(14)}...${entry.address.takeLast(6)}" else "deriving...",
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        funded -> Text(
                            "%.4f ${KaspaUnit.symbol}".format(java.util.Locale.US, entry.balanceSompi / 100_000_000.0),
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        used != null -> Text(
                            if (used) tr(R.string.kp_used_02c0e) else tr(R.string.kp_unused_41369),
                            color = if (used) LocalAppColors.current.warning else LocalAppColors.current.success,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // Not yet answered, or a probe that failed. iOS shows the same neutral
                        // placeholder rather than an empty gap where every other row has a badge.
                        else -> Text(
                            "…",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * One address row on [ColdStorageDetailScreen]. Swipe-to-hide was retired when the Address
 * Visibility checklist ([ColdStorageAddressVisibilityScreen]) became the one place to manage
 * visibility — the same redesign Manage Addresses' spending rows got. A quick "Hide Address"
 * lives in the row's overflow menu (same as spending rows), writing the same hidden flag the
 * checklist edits.
 */
@Composable
private fun ColdAddressRow(
    row: ColdStorageViewModel.AddressRow,
    onAddressClick: () -> Unit,
    onLabelClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSendClick: () -> Unit,
    onShowQrClick: () -> Unit,
    /** Row-menu "Hide Address" (same as spending rows) — same flag the Address Visibility
     *  checklist edits. Null hides the menu entry. */
    onHideClick: (() -> Unit)? = null,
    /** "Contains domain" tag — this address owns at least one KNS domain (batched lookup). */
    showsDomainTag: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LocalAppColors.current.surface)
                .clickable(onClick = onAddressClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label?.takeIf { it.isNotBlank() } ?: tr(R.string.kp_address_lld_64ab3, row.index),
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${row.address.take(14)}...${row.address.takeLast(6)}",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${KaspaUnit.groupedExact(row.balanceSompi)} ${KaspaUnit.symbol}",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Same three-state rule as the spending list: "Used" is monotonic, "Unused"
                    // only when this session's live pass confirmed the row, and a neutral
                    // "Unverified" for snapshot-painted or failed-check rows — a failed check
                    // must not masquerade as a fresh address.
                    val (usedTagText, usedTagColor) = when {
                        row.hasHistory -> tr(R.string.kp_used_02c0e) to LocalAppColors.current.warning
                        row.liveChecked -> tr(R.string.kp_unused_41369) to LocalAppColors.current.success
                        else -> "Unverified" to LocalAppColors.current.textSecondary
                    }
                    Text(
                        text = usedTagText,
                        color = usedTagColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (showsDomainTag) {
                        com.kachat.app.ui.screens.ContainsDomainTag()
                    }
                }
            }
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.MoreVert, "Address actions", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(28.dp))
            }
        }

    if (showMenu) {
        ActionSheetContainer(
            title = row.label ?: tr(R.string.kp_address_lld_64ab3, row.index),
            subtitle = com.kachat.app.services.AddressActivityNotifier.shortAddress(row.address),
            onDismiss = { showMenu = false },
        ) {
            ActionSheetRow(
                icon = Icons.Default.Edit,
                title = stringResource(R.string.rename_address),
                subtitle = tr(R.string.kp_address_row_rename_subtitle),
            ) {
                showMenu = false
                onLabelClick()
            }
            ActionSheetRow(
                icon = Icons.Default.ContentCopy,
                title = stringResource(R.string.copy_address),
                subtitle = tr(R.string.kp_address_row_copy_subtitle),
            ) {
                showMenu = false
                onCopyClick()
            }
            ActionSheetRow(
                icon = Icons.Default.QrCode,
                title = stringResource(R.string.show_qr_code),
                subtitle = tr(R.string.kp_address_row_qr_subtitle),
            ) {
                showMenu = false
                onShowQrClick()
            }
            // Hide straight from the row (same as spending rows) — no primary-address rule for a
            // watch-only kpub account; the caller passes null for a funded address (iOS).
            if (onHideClick != null) {
                ActionSheetRow(
                    icon = Icons.Default.VisibilityOff,
                    title = tr(R.string.kp_hide_address),
                    subtitle = tr(R.string.kp_address_row_hide_subtitle),
                    tint = LocalAppColors.current.warning,
                ) {
                    showMenu = false
                    onHideClick()
                }
            }
        }
    }
}

/**
 * The whole "send" round trip for one Cold Storage address, in steps as Send Kaspa (iOS wallet
 * 05db583 `ColdSendFlowView`): To, Amount (the app's keypad, "To" / "From Cold Storage" chips),
 * and the review, where only the fee (its Edit opens [SendFeeEditorScreen]) and coin control
 * change, ending in Build Unsigned Transaction. Then **Sign with KasSigner**: the unsigned
 * transaction as an animated KSPT QR for the device to scan and sign; Scan Signed Transaction
 * opens the scanner, and closing it without a scan returns to the QR. The signed transaction
 * scanned back is the **signed** screen ("Signed by KasSigner", the summary) with swipe up to
 * send: the BlockDAG, then Sent, or Failed with Try Again (back on it, still signed). Compound
 * opens on the review, its recipient and amount set. Every screen draws its own header; the
 * engine never sees a private key - signing happens entirely on the physical device.
 */
@Composable
private fun ColdSendFlow(
    fromAddress: String,
    availableBalanceSompi: Long,
    viewModel: ColdStorageViewModel,
    onDone: () -> Unit,
    // Pre-fills the recipient with fromAddress itself (a self-send) and auto-fills Max, for the
    // "Compound UTXOs" entry point — merges every UTXO at this address into one. Opens on the
    // review, which has no Back: editing the recipient away from fromAddress would defeat the
    // point of a compound send.
    isCompoundMode: Boolean = false,
    /** Inside an [IosFullSheet], as iOS presents it: the screens take no system insets of their
     *  own. */
    presentedAsSheet: Boolean = false,
    /** True while the signed transaction is going out: the presenting sheet refuses a swipe down
     *  (iOS `interactiveDismissDisabled`). */
    onSendingChange: (Boolean) -> Unit = {},
    priceViewModel: com.kachat.app.viewmodels.KasPriceViewModel = hiltViewModel()
) {
    val sendState by viewModel.sendState.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val fiatPriceInCurrency by priceViewModel.currentPriceUsd.collectAsState()
    val fiatCurrencyCode by priceViewModel.currency.collectAsState()
    val colors = LocalAppColors.current
    val focusManager = LocalFocusManager.current
    val somethingWentWrong = tr(R.string.kp_something_went_wrong_kc)
    val screenTitle = stringResource(if (isCompoundMode) R.string.compound_utxos else R.string.send_from_cold_storage)
    var toAddress by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(onKasTextChange = { amountText = it })
    var showSignedScanner by remember { mutableStateOf(false) }
    var showRecipientScanner by remember { mutableStateOf(false) }
    var isEstimatingMax by remember { mutableStateOf(false) }
    // Coin control — null means automatic (greedy, largest-first) selection; non-null fixes the
    // exact input set the user picked instead.
    var manualUtxos by remember { mutableStateOf<List<UtxoEntry>?>(null) }
    var showCoinControl by remember { mutableStateOf(false) }
    // Compound mode only: true when this address holds more UTXOs than one KasSigner transaction
    // can merge at once (KsptCodec.MAX_INPUTS), so the user must run Compound again after this
    // round. Drives the "repeat to finish" note.
    var compoundHasMoreRounds by remember { mutableStateOf(false) }
    var feeTier by remember { mutableStateOf(FeeTier.NORMAL) }
    var customExtraFeeSompi by remember { mutableStateOf<Long?>(null) }
    // The fee editor's typed KAS (iOS feeDraft).
    var feeDraft by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    // Fetched once and reused for both the preview below and the actual build/estimate calls
    // (via feeRateOverrideSompi) — letting each of those independently fetch their own quote was
    // exactly why the fee shown before Build didn't match what the real transaction ended up
    // costing, whenever the live quote drifted between two separate fetches.
    var liveFeeRateSompiPerGram by remember { mutableStateOf<Long?>(null) }

    // iOS ColdSendFlowView.Step: the form (To, Amount, Review - Build keeps it up with the button
    // busy), the QR, the signed screen, or a failed build. The scanner is a sheet over the QR.
    var screen by remember { mutableStateOf<ColdSendScreen>(ColdSendScreen.Form) }
    // iOS FormStep: null until a step is chosen - To for a send, the review for a compound.
    var formStep by remember { mutableStateOf<ColdFormStep?>(null) }
    val currentFormStep = formStep ?: if (isCompoundMode) ColdFormStep.REVIEW else ColdFormStep.RECIPIENT
    val go: (ColdFormStep) -> Unit = { next ->
        focusManager.clearFocus()
        formStep = next
    }
    // The signed transaction going out (the swipe-up), and how it went.
    var isBroadcasting by remember { mutableStateOf(false) }
    var broadcastError by remember { mutableStateOf<String?>(null) }
    var broadcastOutcomeUnknown by remember { mutableStateOf(false) }
    // the presenting sheet refuses a swipe down while the signed transaction goes out
    LaunchedEffect(isBroadcasting) { onSendingChange(isBroadcasting) }
    var sentTransaction by remember { mutableStateOf<SentTransaction?>(null) }

    LaunchedEffect(Unit) {
        liveFeeRateSompiPerGram = viewModel.fetchQuotedFeeRateSompiPerGram()
        if (isCompoundMode) {
            toAddress = fromAddress
            // Fix the input set to the largest <=8 UTXOs — the most one KasSigner-signable
            // transaction can hold — then Max the amount for exactly that set. Without this cap,
            // Max spans every UTXO at the address and the build fails with "too many inputs" the
            // moment it holds more than 8. `compoundHasMoreRounds` records whether another round
            // is needed afterward. Inputs that can't be read are Something Went Wrong, as iOS's
            // setupCompoundInputs.
            val compound = try {
                viewModel.compoundInputs(fromAddress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                screen = ColdSendScreen.Failed(com.kachat.app.util.UserFacingError.message(e, somethingWentWrong))
                return@LaunchedEffect
            }
            manualUtxos = compound.utxos
            compoundHasMoreRounds = compound.hasMore
            isEstimatingMax = true
            try {
                // a compound pays itself: priced at its own script (iOS 5848fb0)
                val maxSompi = viewModel.estimateMaxAmount(fromAddress, liveFeeRateSompiPerGram, compound.utxos, toAddress = fromAddress)
                fiatAmountState.setMaxKas(maxSompi / 100_000_000.0, fiatPriceInCurrency)
            } catch (e: Exception) {
                // Leave the amount untouched on failure — same as the Max button itself.
            } finally {
                isEstimatingMax = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetColdSendState() }
    }

    // The one exact parser for typed KAS (iOS 16b64bc): comma or dot, at most 8 decimals. Zero is
    // no amount, as iOS's amountSompi.
    val amountSompi = KaspaUnit.sompiFromUserText(amountText)?.takeIf { it > 0L }
    // Only an address of the network the app runs on (iOS ce20e87).
    val isValidRecipient = remember(toAddress) { KaspaAddress.isValidOnActiveNetwork(toAddress.trim()) }

    // Debounced live preview of what automatic selection would pick for the current amount/fee —
    // see the LaunchedEffect below. Non-null only while still fresh for the current amount/fee;
    // cleared immediately on any relevant change so a stale preview is never shown or built with.
    var previewSelection by remember { mutableStateOf<com.kachat.app.util.ColdSendFeeModel.Preview?>(null) }

    val referenceMass1Input = remember {
        com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
    }
    // Same live-or-minimum rate the engine's own nil-override default falls back to — using it
    // here too (instead of always assuming the bare protocol minimum) means the "Normal" preview
    // matches what a nil-override build would actually charge whenever the network's live quote
    // is currently above the minimum.
    val baseFeeRateSompiPerGram = liveFeeRateSompiPerGram ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM

    // The build, Max and the preview all run at the live rate; Fast / Priority / a custom fee is a
    // flat extra on top (SendFeeModel), paid exactly. This used to fold the extra into a per-gram
    // rate sized on ONE input, which the build then charged on every real input - a custom fee on
    // an 8-input send paid nearly five times what was typed.
    val feeRateOverrideSompi: Long = baseFeeRateSompiPerGram

    // Real input count when known (coin control, or a fresh automatic-selection preview) instead
    // of always guessing 1 — otherwise the fee shown here could understate what a multi-UTXO send
    // actually costs, only becoming visible after tapping Build.
    val estimatedMass = when {
        !manualUtxos.isNullOrEmpty() ->
            com.kachat.app.util.KaspaMass.calculateMass(numInputs = manualUtxos!!.size, outputScriptLens = listOf(34, 34), payloadSize = 0)
        previewSelection != null ->
            com.kachat.app.util.KaspaMass.calculateMass(numInputs = previewSelection!!.utxos.size, outputScriptLens = listOf(34, 34), payloadSize = 0)
        else -> referenceMass1Input
    }
    // The live preview (when fresh and automatic selection is in play — coin control already
    // knows its exact count another way) wins over the mass-derived number: it's the actual
    // result of running the real selector, base and extra settled together, not a recomputation.
    val usesPreview = manualUtxos == null && previewSelection != null
    val defaultFeeSompi = if (usesPreview) {
        previewSelection!!.baseFeeSompi
    } else {
        com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, baseFeeRateSompiPerGram)
    }
    // Normal/Fast/Priority multiplier system, same as iOS's ColdSendFlowView and this app's own
    // spending-address send flow.
    val extraFeeSompi = if (usesPreview) {
        previewSelection!!.extraFeeSompi
    } else {
        com.kachat.app.util.SendFeeModel.extraFee(defaultFeeSompi, feeTier.multiplier, customExtraFeeSompi)
    }
    // Change too small to keep goes to the fee, and the Review counts it (iOS 953b255
    // `SendFeeEstimate`).
    // Coin control: priced exactly as the send prices those coins, so with no change output
    // the Review doesn't count a change output's fee on top of the folded change.
    val manualFeeSompi = remember(manualUtxos, amountSompi, baseFeeRateSompiPerGram, extraFeeSompi) {
        val coins = manualUtxos?.takeIf { it.isNotEmpty() }
        if (coins != null && amountSompi != null && amountSompi > 0) {
            com.kachat.app.util.ColdSendFeeModel.manualTotalFee(coins, amountSompi, baseFeeRateSompiPerGram, extraFeeSompi)
        } else null
    }
    val effectiveFeeSompi = manualFeeSompi
        ?: (defaultFeeSompi + extraFeeSompi + (previewSelection?.foldedChangeSompi ?: 0L))

    // Debounced (400ms) — cancels and restarts automatically whenever any key changes, so a
    // burst of typing doesn't fire a network call per keystroke. No-ops entirely once coin
    // control is active (manualUtxos != null) since that already knows its exact count.
    // (The preview's LaunchedEffect sits below effectiveAddress: it prices the real recipient.)

    var isResolvingKns by remember { mutableStateOf(false) }
    var knsResolvedAddress by remember { mutableStateOf<String?>(null) }
    var knsResolvedDomain by remember { mutableStateOf<String?>(null) }
    var knsError by remember { mutableStateOf<String?>(null) }
    // Every service's answer for a typed name and the one in use: the rest go under "Other
    // domains" (iOS 6ac48a7).
    var nameResolutions by remember { mutableStateOf<List<com.kachat.app.services.NameResolution>>(emptyList()) }
    var selectedTld by remember { mutableStateOf<com.kachat.app.services.NameServiceTLD?>(null) }
    // Use one service's answer (the priority one, or a pick from "Other domains").
    val selectResolution: (com.kachat.app.services.NameResolution) -> Unit = { resolution ->
        resolution.address?.let {
            knsResolvedAddress = it
            knsResolvedDomain = resolution.display
            selectedTld = resolution.tld
            knsError = null
        }
    }
    // Debounced KNS domain resolution - lets typing "name.kas" here resolve the same way Create
    // Chat's own address field already does. Skipped entirely in compound mode, where the
    // recipient is always the locked self-address, never user-typed.
    val resolveContext = LocalContext.current
    LaunchedEffect(toAddress) {
        knsResolvedAddress = null
        knsResolvedDomain = null
        knsError = null
        nameResolutions = emptyList()
        selectedTld = null
        if (isCompoundMode) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        val trimmed = toAddress.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("kaspa:", ignoreCase = true) ||
            trimmed.startsWith("kaspatest:", ignoreCase = true) || !com.kachat.app.services.NameServicesClient.looksLikeName(trimmed)
        ) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        isResolvingKns = true
        kotlinx.coroutines.delay(500)
        // Every name service, in priority: the ending typed, else .kachat only (the others wait
        // under Other domains, iOS 5a5122d). The resolved line names which one answered; the
        // others are offered under "Other domains" (iOS 6ac48a7).
        val results = viewModel.resolveEverywhere(trimmed)
        nameResolutions = results
        isResolvingKns = false
        val primary = com.kachat.app.services.NameServicesClient.primary(results, trimmed)
        if (primary?.address != null) {
            selectResolution(primary)
        } else {
            knsError = com.kachat.app.services.NameServicesClient.notFoundMessage(resolveContext, trimmed, results)
        }
    }
    // The actual address to use (resolved from a KNS domain, or the direct input) - same
    // precedence as Create Chat's own address field.
    val effectiveAddress = knsResolvedAddress ?: toAddress
    // Priced at the recipient's real script (35 bytes for a quantum address), 34 until there is a
    // valid one, so the preview's fee is what the build charges for the same coins (iOS 5848fb0).
    val pricedRecipient = effectiveAddress.trim()
    LaunchedEffect(amountSompi, feeTier, customExtraFeeSompi, liveFeeRateSompiPerGram, manualUtxos, pricedRecipient) {
        previewSelection = null
        if (manualUtxos == null && amountSompi != null && amountSompi > 0) {
            kotlinx.coroutines.delay(400)
            previewSelection = viewModel.previewAutomaticSelection(
                fromAddress, amountSompi, feeRateOverrideSompi, feeTier.multiplier, customExtraFeeSompi,
                toAddress = pricedRecipient.takeIf { it.isNotEmpty() }
            )
        }
    }
    // a name's answer counts only as a valid address of this network (iOS 5848fb0)
    val hasValidRecipient = knsResolvedAddress?.let { KaspaAddress.isValidOnActiveNetwork(it) } ?: (isValidRecipient && !isResolvingKns)
    val canBuild = hasValidRecipient && amountSompi != null
    val exceedsBalance = amountSompi != null && amountSompi > availableBalanceSompi
    // What the swipe-up and Sent name as the recipient: a resolved name as the name. A compound
    // is a self-send, so it names none.
    val recipientLabel = if (isCompoundMode) null else (knsResolvedDomain ?: effectiveAddress.trim()).takeIf { it.isNotEmpty() }

    // The build's outcome moves the form on: the QR once built, Something Went Wrong if it
    // failed. The broadcast's outcome is the signed screen's Sent or Failed.
    LaunchedEffect(sendState) {
        val state = sendState
        if (screen == ColdSendScreen.Form) {
            when (state.step) {
                ColdStorageViewModel.ColdSendStep.SHOWING_QR -> screen = ColdSendScreen.Qr(state.qrFrames, state.feeSompi)
                ColdStorageViewModel.ColdSendStep.FAILED -> screen = ColdSendScreen.Failed(state.errorMessage ?: somethingWentWrong)
                else -> Unit
            }
        } else if (isBroadcasting) {
            when (state.step) {
                ColdStorageViewModel.ColdSendStep.SUCCESS -> {
                    isBroadcasting = false
                    sentTransaction = state.txId?.let { txId ->
                        SentTransaction(txId = txId, amountSompi = amountSompi?.takeIf { !isCompoundMode }, recipient = recipientLabel)
                    }
                }
                ColdStorageViewModel.ColdSendStep.FAILED -> {
                    isBroadcasting = false
                    broadcastError = state.errorMessage
                    broadcastOutcomeUnknown = state.outcomeUnknown
                }
                else -> Unit
            }
        }
    }

    // Back on the form as it was (the QR's Back, a failure's Try Again): a new Build starts over,
    // so the built transaction is let go.
    val backToForm: () -> Unit = {
        viewModel.resetColdSendState()
        screen = ColdSendScreen.Form
    }

    val buildTransaction: () -> Unit = {
        amountSompi?.let {
            // Real coin control (explicit user selection) wins if set; otherwise, if a fresh
            // automatic-selection preview is available, pass its exact UTXO set through too -
            // guaranteeing the fee just shown on this screen and the fee the real build produces
            // are the same number, not just close. Re-resolved against a fresh fetch inside
            // buildUnsignedTransaction either way, so this is never stale-unsafe.
            val utxosForBuild = manualUtxos ?: previewSelection?.utxos
            // the preview's coins, not a pick: the build may lead with a "Not confirmed" coin (iOS 3d126db)
            val previewed = manualUtxos == null && previewSelection != null
            viewModel.startColdSend(fromAddress, effectiveAddress.trim(), it, feeRateOverrideSompi, utxosForBuild, extraFeeSompi, previewed)
        }
    }

    // The swipe let go: the signed transaction goes out (iOS broadcast(unsignedTx:decoded:)). A
    // debug build can be made to fail it on purpose first, as iOS's SendFailureSimulation.
    val broadcast: (ByteArray) -> Unit = { signedKspt ->
        isBroadcasting = true
        broadcastError = null
        broadcastOutcomeUnknown = false
        coroutineScope.launch {
            if (SendFailureSimulation.isEnabled) {
                kotlinx.coroutines.delay(SendFailureSimulation.DELAY_MS)
                isBroadcasting = false
                broadcastError = SendFailureSimulation.error().message
            } else {
                viewModel.onSignedKsptScanned(signedKspt)
            }
        }
    }

    val setMaxAmount: () -> Unit = {
        if (hasValidRecipient) {
            coroutineScope.launch {
                isEstimatingMax = true
                try {
                    // Room for the base and the Fast / Priority / custom extra, the extra derived
                    // from Max's own base.
                    val maxSompi = viewModel.estimateMaxAmount(
                        fromAddress, feeRateOverrideSompi, manualUtxos, feeTier.multiplier, customExtraFeeSompi,
                        toAddress = effectiveAddress.trim()
                    )
                    fiatAmountState.setMaxKas(maxSompi / 100_000_000.0, fiatPriceInCurrency)
                } catch (e: Exception) {
                    // Leave the amount untouched on failure — same as iOS.
                } finally {
                    isEstimatingMax = false
                }
            }
        }
    }

    // The recipient scanner and coin control are sheets over the send, as iOS's ColdSendFlowView
    // presents its QRScannerView and CoinControlView.
    if (showRecipientScanner) {
        QrScannerSheet(
            onScanned = { scanned -> toAddress = com.kachat.app.util.KaspaAddress.fromScanned(scanned) },
            onDismiss = { showRecipientScanner = false }
        )
    }

    // iOS's MultiFrameQRScannerView, a sheet over the QR: a complete scan is read and opens the
    // signed screen (one that can't be read is Something Went Wrong); closed without one, the
    // QR is still there.
    if (showSignedScanner) {
        MultiFrameQrScannerSheet(
            isComplete = { KsptCodec.looksLikeKspt(it) },
            onComplete = { bytes ->
                (screen as? ColdSendScreen.Qr)?.let { qr ->
                    KsptCodec.decode(bytes).fold(
                        onSuccess = {
                            broadcastError = null
                            sentTransaction = null
                            screen = ColdSendScreen.Signed(qr.frames, qr.feeSompi, bytes)
                        },
                        onFailure = { e ->
                            screen = ColdSendScreen.Failed(
                                com.kachat.app.util.UserFacingError.message(e, "Couldn't read the signed transaction")
                            )
                        },
                    )
                }
            },
            onDismiss = { showSignedScanner = false }
        )
    }

    if (showCoinControl) {
        ColdCoinControlSheet(
            fromAddress = fromAddress,
            fetchUtxos = { addr -> viewModel.fetchUtxosForCoinControl(addr) },
            // the build leaves out immature mining rewards, covenant-bound coins and coins a send
            // just spent: listed greyed out with why (iOS 5848fb0)
            fetchCoinsWithReasons = { addr -> viewModel.fetchCoinControlCoins(addr) },
            initialSelection = manualUtxos,
            onDone = { selection -> manualUtxos = selection },
            onDismiss = { showCoinControl = false },
            // KasSigner signs at most this many inputs per transaction.
            maxSelection = com.kachat.app.util.KsptCodec.MAX_INPUTS,
            // a spending address's labels (Manage Addresses) or a KasSigner address's (iOS 148a5331)
            utxoLabels = remember(fromAddress) { viewModel.getCoinControlLabels(fromAddress) },
        )
    }

    // The summary's To and From rows (iOS summaryCard / the review's card).
    val toRow: @Composable () -> Unit = {
        ColdReviewRow(stringResource(R.string.to)) {
            if (isCompoundMode) {
                Text(tr(R.string.kp_this_address_consolidating_d0ba3), color = colors.textPrimary, fontSize = 15.sp, textAlign = TextAlign.End)
            } else {
                knsResolvedDomain?.let {
                    Text(it, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                }
                MiddleEllipsisText(
                    effectiveAddress,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
    val fromRow: @Composable () -> Unit = {
        ColdReviewRow(stringResource(R.string.from)) {
            Text(stringResource(R.string.cold_storage), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${fromAddress.take(14)}...${fromAddress.takeLast(6)}",
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
    // To, From, the fee and the total, as built (iOS summaryCard).
    val summaryCard: @Composable (Long) -> Unit = { feeSompi ->
        SendReviewCard {
            toRow()
            HorizontalDivider(color = colors.divider)
            fromRow()
            HorizontalDivider(color = colors.divider)
            ColdReviewRow(stringResource(R.string.network_fee)) {
                Text("${KaspaUnit.plain(feeSompi)} ${KaspaUnit.symbol}", color = colors.textPrimary, fontSize = 15.sp, style = ColdTabularDigits)
            }
            amountSompi?.let {
                HorizontalDivider(color = colors.divider)
                ColdReviewRow(stringResource(R.string.total)) {
                    Text(
                        "${KaspaUnit.plain(it + feeSompi)} ${KaspaUnit.symbol}",
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = ColdTabularDigits,
                    )
                }
            }
        }
    }

    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = {
            // As iOS animates its steps: the signed screen comes in from the trailing edge over
            // the QR, and the QR's Back slides it out to the leading edge as the form fades in. The
            // rest (built, failed, the signed screen's Back) change at once.
            val initial = initialState
            val target = targetState
            when {
                initial is ColdSendScreen.Qr && target is ColdSendScreen.Signed ->
                    slideInHorizontally(ColdSendStepSpring) { it } togetherWith slideOutHorizontally(ColdSendStepSpring) { -it }
                initial is ColdSendScreen.Qr && target == ColdSendScreen.Form ->
                    fadeIn(spring(dampingRatio = 0.9f, stiffness = 247f)) togetherWith slideOutHorizontally(ColdSendStepSpring) { -it }
                else -> EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "coldSendScreen",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        when (shown) {
            ColdSendScreen.Form -> AnimatedContent(
                targetState = currentFormStep,
                transitionSpec = {
                    // iOS: the next step moves in from the trailing edge, Back brings one in from the leading
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(ColdSendStepSpring) { if (forward) it else -it } togetherWith
                        slideOutHorizontally(ColdSendStepSpring) { if (forward) -it else it })
                },
                label = "coldSendFormStep",
                modifier = Modifier.fillMaxSize(),
            ) { step ->
                when (step) {
                    // MARK: To
                    ColdFormStep.RECIPIENT -> SendStepScreen(
                        title = screenTitle,
                        onCancel = onDone,
                        canContinue = hasValidRecipient,
                        onContinue = { go(ColdFormStep.AMOUNT) },
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                                .padding(top = 12.dp, bottom = 24.dp),
                        ) {
                            SendStepQuestion(tr(R.string.kp_who_are_you_sending_to_2a7f1))
                            SendRecipientCard(
                                input = toAddress,
                                onInputChange = { toAddress = it },
                                isResolving = isResolvingKns,
                                resolvedAddress = knsResolvedAddress,
                                resolvedName = knsResolvedDomain,
                                nameResolutions = nameResolutions,
                                selectedTld = selectedTld,
                                onSelectResolution = selectResolution,
                                lookupError = knsError,
                                isValidAddress = isValidRecipient,
                                onScan = { showRecipientScanner = true },
                                focusOnAppear = true,
                            )
                        }
                    }

                    // MARK: Amount
                    ColdFormStep.AMOUNT -> SendStepScreen(
                        title = screenTitle,
                        onBack = { go(ColdFormStep.RECIPIENT) },
                        onCancel = onDone,
                        continueTitle = tr(R.string.kp_review_e29a7),
                        canContinue = canBuild && !exceedsBalance && !isEstimatingMax,
                        onContinue = { go(ColdFormStep.REVIEW) },
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(top = 4.dp),
                        ) {
                            SendStepChip(
                                label = stringResource(R.string.to),
                                value = knsResolvedDomain ?: effectiveAddress,
                                isAddress = knsResolvedDomain == null,
                            )
                            SendStepChip(label = stringResource(R.string.from), value = stringResource(R.string.cold_storage))
                        }
                        KaspaKeypadAmountEntry(
                            fiatAmountState = fiatAmountState,
                            priceInCurrency = fiatPriceInCurrency,
                            currencyCode = fiatCurrencyCode,
                            onMax = setMaxAmount,
                            isEstimatingMax = isEstimatingMax,
                            maxEnabled = hasValidRecipient,
                            footnote = if (exceedsBalance) {
                                tr(R.string.kp_this_is_more_than_the_address_7676b)
                            } else {
                                KaspaUnit.label("Available: ${String.format(Locale.US, "%.8f", availableBalanceSompi / 100_000_000.0)} KAS")
                            },
                            footnoteIsError = exceedsBalance,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // MARK: Review
                    ColdFormStep.REVIEW -> {
                        val isBuilding = sendState.step == ColdStorageViewModel.ColdSendStep.BUILDING
                        // A tap, not a swipe: this only builds the transaction for KasSigner to sign.
                        SendStepScreen(
                            title = if (isCompoundMode) screenTitle else tr(R.string.kp_review_e29a7),
                            onBack = if (isCompoundMode) null else ({ go(ColdFormStep.AMOUNT) }),
                            onCancel = onDone,
                            continueTitle = if (isBuilding) "Building..." else stringResource(R.string.build_unsigned_transaction),
                            canContinue = canBuild && !isBuilding,
                            onContinue = buildTransaction,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp)
                                    .padding(top = 12.dp, bottom = 24.dp),
                            ) {
                                ColdSendAmountHeadline(
                                    caption = if (isCompoundMode) tr(R.string.kp_consolidating_f7afc) else tr(R.string.kp_you_re_sending_e8358),
                                    amountSompi = amountSompi,
                                    showsProgressWhenEmpty = true,
                                    modifier = Modifier.padding(top = 8.dp),
                                )

                                SendReviewCard {
                                    toRow()
                                    HorizontalDivider(color = colors.divider)
                                    fromRow()
                                    HorizontalDivider(color = colors.divider)
                                    ColdReviewRow(stringResource(R.string.total)) {
                                        if (amountSompi != null) {
                                            Text(
                                                "~${KaspaUnit.plain(amountSompi + effectiveFeeSompi)} ${KaspaUnit.symbol}",
                                                color = colors.textPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                style = ColdTabularDigits,
                                            )
                                        } else {
                                            Text("—", color = colors.textSecondary, fontSize = 15.sp)
                                        }
                                    }
                                }

                                if (isCompoundMode) {
                                    Text(
                                        if (compoundHasMoreRounds)
                                            stringResource(R.string.compound_more_rounds_note, KsptCodec.MAX_INPUTS)
                                        else
                                            stringResource(R.string.compound_single_round_note),
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }

                                // Compound auto-manages its own input set (largest <=8, KasSigner's
                                // per-tx limit), so manual coin control is hidden there - it only
                                // applies to a normal send.
                                SendFeeControls(
                                    feeTier = feeTier,
                                    onFeeTierChange = {
                                        feeTier = it
                                        customExtraFeeSompi = null
                                    },
                                    // the fee is edited on its own screen (onEditFee)
                                    isEditingFee = false,
                                    customFeeText = "",
                                    onCustomFeeTextChange = {},
                                    onStartEditing = {},
                                    onCommit = {},
                                    isEstimatingFee = false,
                                    feeText = "~${KaspaUnit.plain(effectiveFeeSompi)} ${KaspaUnit.symbol}",
                                    showsCoinControl = !isCompoundMode,
                                    coinControlSummary = coinControlSummary(manualUtxos),
                                    onCoinControl = { showCoinControl = true },
                                    onEditFee = {
                                        feeDraft = KaspaUnit.plain(effectiveFeeSompi)
                                        go(ColdFormStep.FEE)
                                    },
                                )
                            }
                        }
                    }

                    // MARK: Fee
                    ColdFormStep.FEE -> SendFeeEditorScreen(
                        draft = feeDraft,
                        onDraftChange = { feeDraft = it },
                        onCancel = { go(ColdFormStep.REVIEW) },
                        onConfirm = { typed ->
                            // never under the network's minimum
                            customExtraFeeSompi = (typed - defaultFeeSompi).coerceAtLeast(0L)
                            go(ColdFormStep.REVIEW)
                        },
                        minimumSompi = defaultFeeSompi,
                    )
                }
            }

            // MARK: Sign with KasSigner
            // The unsigned transaction as an animated QR for KasSigner, on its own white card
            // whatever the theme - a dark surround gives a camera photographing this screen far
            // less contrast around the code. Continue opens the scanner for the signed one.
            is ColdSendScreen.Qr -> SendStepScreen(
                title = tr(R.string.kp_sign_with_kassigner_034f5),
                onBack = backToForm,
                onCancel = onDone,
                continueTitle = stringResource(R.string.scan_signed_transaction),
                canContinue = true,
                onContinue = { showSignedScanner = true },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    SendStepQuestion(tr(R.string.kp_scan_this_on_your_kassigner_kc))
                    AnimatedQrDisplay(frames = shown.frames)
                    Text(
                        tr(R.string.kp_kassigner_shows_what_it_is_signing_6617a),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    summaryCard(shown.feeSompi)
                }
            }

            // MARK: Signed
            // Signed and scanned back: the last look, then swipe up to send it - the BlockDAG,
            // then Sent with the transaction, or Failed with Try Again (back here, still signed).
            is ColdSendScreen.Signed -> SwipeUpToSendScreen(
                screenTitle = screenTitle,
                onCancel = onDone,
                title = stringResource(if (isCompoundMode) R.string.swipe_up_to_consolidate else R.string.swipe_up_to_send),
                isBusy = isBroadcasting,
                isEnabled = !isBroadcasting && sentTransaction == null,
                amountSompi = amountSompi,
                recipient = recipientLabel,
                sent = sentTransaction,
                failureMessage = broadcastError,
                // its answer was lost: "Not confirmed" with Done only, never a second broadcast
                outcomeUnknown = broadcastOutcomeUnknown,
                explorer = kaspaExplorer,
                onDone = onDone,
                action = { broadcast(shown.signedKspt) },
                onBack = { screen = ColdSendScreen.Qr(shown.frames, shown.feeSompi) },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                ) {
                    // iOS Label("Signed by KasSigner", systemImage: "checkmark.seal.fill") on a
                    // green capsule
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(CircleShape)
                            .background(colors.success.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = colors.success, modifier = Modifier.size(17.dp))
                        Text(tr(R.string.kp_signed_by_kassigner_8b310), color = colors.success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    ColdSendAmountHeadline(
                        caption = if (isCompoundMode) tr(R.string.kp_consolidating_f7afc) else tr(R.string.kp_you_re_sending_e8358),
                        amountSompi = amountSompi,
                        showsProgressWhenEmpty = false,
                    )
                    summaryCard(shown.feeSompi)
                    Text(
                        tr(R.string.kp_nothing_can_change_now_this_is_8bf3a),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // MARK: Failed
            // Building failed (or the compound inputs couldn't be read, or the scan couldn't be
            // read): what went wrong, and Try Again back to the form as it was.
            is ColdSendScreen.Failed -> SendStepScreen(
                title = screenTitle,
                onCancel = onDone,
                continueTitle = stringResource(R.string.try_again),
                canContinue = true,
                onContinue = backToForm,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.danger, modifier = Modifier.size(56.dp))
                    Text(somethingWentWrong, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        shown.message,
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Where a [ColdSendFlow] is (iOS `ColdSendFlowView.Step`); the scanner is a sheet over [Qr]. */
private sealed interface ColdSendScreen {
    /** To, Amount, Review (and the fee editor) - Build keeps it up with the button busy. */
    data object Form : ColdSendScreen
    /** Built: the unsigned transaction's QR frames, and the fee it pays. */
    class Qr(val frames: List<ByteArray>, val feeSompi: Long) : ColdSendScreen
    /** The signed transaction scanned back, ready for the swipe up. */
    class Signed(val frames: List<ByteArray>, val feeSompi: Long, val signedKspt: ByteArray) : ColdSendScreen
    data class Failed(val message: String) : ColdSendScreen
}

/** A [ColdSendFlow] form's steps, in order (iOS `ColdSendFlowView.FormStep`). */
private enum class ColdFormStep { RECIPIENT, AMOUNT, REVIEW, FEE }

/** iOS `.spring(response: 0.4, dampingFraction: 0.9)`: stiffness (2*pi/0.4)^2. */
private val ColdSendStepSpring = spring(dampingRatio = 0.9f, stiffness = 247f, visibilityThreshold = IntOffset.VisibilityThreshold)

/** iOS `.monospacedDigit()`. */
private val ColdTabularDigits = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/** One row of [SendReviewCard] (iOS `reviewRow`): the grey label leading, the value trailing,
 *  on the first line's baseline. */
@Composable
private fun ColdReviewRow(label: String, value: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 15.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(12.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
            content = value,
        )
    }
}

/** "You're sending" / "Consolidating" over the amount, 40 bold rounded and shrinking to half
 *  rather than being cut (iOS `.minimumScaleFactor(0.5)`); a spinner in its place while there is
 *  no amount yet, with [showsProgressWhenEmpty] (a compound's Max still being worked out). */
@Composable
private fun ColdSendAmountHeadline(
    caption: String,
    amountSompi: Long?,
    showsProgressWhenEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(caption, color = colors.textSecondary, fontSize = 15.sp)
        if (amountSompi != null) {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val text = "${KaspaUnit.plain(amountSompi)} ${KaspaUnit.symbol}"
            val style = androidx.compose.ui.text.TextStyle(
                color = colors.textPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = com.kachat.app.ui.theme.IosRoundedFontFamily,
                fontFeatureSettings = "tnum",
            )
            BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                val maxWidth = constraints.maxWidth
                val scale = remember(text, maxWidth) {
                    if (maxWidth == androidx.compose.ui.unit.Constraints.Infinity) {
                        1f
                    } else {
                        val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
                        if (width <= maxWidth) 1f else (maxWidth.toFloat() / width).coerceIn(0.5f, 1f)
                    }
                }
                Text(
                    text,
                    style = style.copy(fontSize = style.fontSize * scale),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (showsProgressWhenEmpty) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(48.dp)) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
            }
        }
    }
}

/** On-chain transaction history for one Cold Storage address — reached by tapping an address row in [ColdStorageDetailScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageTxHistoryScreen(
    address: String,
    onBack: () -> Unit,
    viewModel: ColdStorageViewModel = hiltViewModel(),
    /** Add to Portfolio files a transaction there (Portfolio is back in KaChat, iOS 148a5331). */
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel(),
    /** This address's row when it was resolved outside the account detail - the History sheet a
     *  tapped "Received" notification opens (iOS 13046ad) - so it still reads "Address #N" with
     *  its balance while the shared list holds another account, or nothing yet. */
    resolvedRow: ColdStorageViewModel.AddressRow? = null,
    /** Opened as the root of the "Received" History sheet (iOS 13046ad `OwnAddressHistorySheet`):
     *  Done closes it, top left, in place of the back arrow. */
    closesWithDone: Boolean = false,
) {
    val txHistory by viewModel.txHistory.collectAsState()
    val isLoading by viewModel.isLoadingTxHistory.collectAsState()
    val historyIncomplete by viewModel.txHistoryIncomplete.collectAsState()
    val utxos by viewModel.utxos.collectAsState()
    val isLoadingUtxos by viewModel.isLoadingUtxos.collectAsState()
    val utxosLoadError by viewModel.utxosLoadError.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val addresses by viewModel.addresses.collectAsState()
    val uriHandler = LocalUriHandler.current
    // The tapped transaction, while its action chooser is up.
    var transactionActionTarget by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    // The transaction being filed into a portfolio, if any - see [AddToPortfolioSheet].
    var portfolioCandidate by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    var addedPortfolioName by remember { mutableStateOf<String?>(null) }

    // Looked up from the already-loaded address list (shared with the account detail screen)
    // rather than a new route param — avoids widening the nav route just for a display name.
    val addressRow = remember(addresses, address, resolvedRow) {
        addresses.firstOrNull { it.address == address } ?: resolvedRow?.takeIf { it.address == address }
    }
    val displayName = addressRow?.label?.takeIf { it.isNotBlank() }
        ?: addressRow?.let { tr(R.string.kp_address_lld_64ab3, it.index) }
        ?: address
    // Live balance for this address: the UTXO set when this screen has one, the account row's
    // balance otherwise.
    //
    // The fallback is the load-bearing half. An empty `utxos` means either "this address really
    // holds nothing" or "the fetch failed", and those were treated the same - so with the block
    // explorer down the page printed 0.00000000 KAS and greyed out Send on an address the list
    // one screen back showed a balance for. Falling back costs nothing when the address is
    // genuinely empty (the row reads 0 too, from the same chain state) and is right whenever the
    // lookup simply did not land. Matches iOS, which reads the row here.
    val addressBalanceSompi = remember(utxos, addressRow) {
        if (utxos.isNotEmpty()) utxos.sumOf { it.amountSompi }
        else addressRow?.balanceSompi ?: 0L
    }

    var selectedTab by remember { mutableStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showSendFlow by remember { mutableStateOf(false) }
    var showCompoundFlow by remember { mutableStateOf(false) }
    // The "Address Actions" sheet, and the public-key screen it can open (iOS 2da5f4b).
    var showAddressActions by remember { mutableStateOf(false) }
    var showPublicKey by remember { mutableStateOf(false) }
    var utxoLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var labelingUtxoKey by remember { mutableStateOf<String?>(null) }
    var labelInput by remember { mutableStateOf("") }
    // The name service opened from the Domains tab (iOS fe52376).
    var openedDomainService by remember { mutableStateOf<com.kachat.app.services.NameServiceTLD?>(null) }

    LaunchedEffect(address) {
        viewModel.loadTxHistory(address)
        viewModel.loadUtxos(address)
        utxoLabels = viewModel.getUtxoLabels(address)
    }

    // A name service opened from this address's Domains tab: its names (iOS fe52376).
    openedDomainService?.let { tld ->
        AboveDock {
            AddressDomainServiceScreen(
                address = address,
                tld = tld,
                onBack = { openedDomainService = null },
            )
        }
        return
    }

    // Send and Compound are sheets over this screen, as iOS presents ColdSendFlowView (which a
    // swipe down also closes, except while the signed transaction is going out).
    if (showSendFlow) {
        var isSending by remember { mutableStateOf(false) }
        IosFullSheet(
            onDismissed = {
                showSendFlow = false
                viewModel.loadTxHistory(address)
                viewModel.loadUtxos(address)
            },
            swipeToDismiss = !isSending,
        ) { close ->
            ColdSendFlow(
                fromAddress = address,
                availableBalanceSompi = addressBalanceSompi,
                viewModel = viewModel,
                onDone = close,
                presentedAsSheet = true,
                onSendingChange = { isSending = it },
            )
        }
    }

    if (showCompoundFlow) {
        var isSending by remember { mutableStateOf(false) }
        IosFullSheet(
            onDismissed = {
                showCompoundFlow = false
                viewModel.loadTxHistory(address)
                viewModel.loadUtxos(address)
            },
            swipeToDismiss = !isSending,
        ) { close ->
            ColdSendFlow(
                fromAddress = address,
                availableBalanceSompi = addressBalanceSompi,
                viewModel = viewModel,
                isCompoundMode = true,
                onDone = close,
                presentedAsSheet = true,
                onSendingChange = { isSending = it },
            )
        }
    }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // Runs on under Receive / Send and the dock, as iOS's list runs on under its
        // safeAreaInset bar and tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    // At the root of the "Received" History sheet: Done, as iOS's
                    // OwnAddressHistorySheet puts it (13046ad); a back arrow everywhere else.
                    // iOS 26: either on glass.
                    if (closesWithDone) {
                        SheetBarButton(stringResource(R.string.done), onClick = onBack, modifier = Modifier.padding(start = 12.dp))
                    } else {
                        SheetBarBackButton(onBack, Modifier.padding(start = 12.dp))
                    }
                },
                actions = {
                    ColdAddressOptionsButton(
                        onClick = { showAddressActions = true },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    // Above the dock the page runs under.
                    .padding(bottom = dockInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showQr = true },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.receive), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showSendFlow = true },
                    // The live UTXO sum, not the account row's cached balance. The row is null
                    // whenever the account's address list is not loaded in this ViewModel, which
                    // left Send greyed out on an address plainly holding coins - the same cause
                    // as the 0.00000000 KAS the balance used to print above a UTXOs (1) tab.
                    enabled = addressBalanceSompi > 0L,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        // What Receive / Send and the dock cover: the lists scroll clear of it.
        val barInset = padding.calculateBottomPadding()
        Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.balance),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    // Summed from the UTXOs this screen loads for itself. It used to read
                    // addressRow.balanceSompi, which is null whenever the account's address list
                    // is not loaded in this ViewModel - so the page showed 0.00000000 KAS above
                    // a UTXOs (1) tab holding real coins. The UTXO set IS the balance.
                    "${KaspaUnit.groupedExact(addressBalanceSompi)} ${KaspaUnit.symbol}",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            // The app's tab look (Chats / Group Chats / Public Chats), not a segmented control;
            // plain names - History, UTXOs, Domains (iOS b96d727/718b88c, fe52376).
            UnderlineTabBar(
                titles = listOf(stringResource(R.string.address_tab_history), stringResource(R.string.utxos), stringResource(R.string.address_tab_domains)),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(top = 4.dp),
            )
            when (selectedTab) {
                2 -> AddressDomainsTab(address, onOpenService = { openedDomainService = it }, bottomPadding = barInset)
                0 -> when {
                    isLoading && txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(bottom = barInset), contentAlignment = Alignment.Center) {
                            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                        }
                    }
                    // A failed fetch is not an empty history (iOS ColdStorageView
                    // `historyIncomplete`): say so, with Try Again.
                    txHistory.isEmpty() && historyIncomplete -> {
                        Box(modifier = Modifier.fillMaxSize().padding(bottom = barInset).padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tr(R.string.kp_could_not_load_transactions_4bd4e), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                                Text(
                                    stringResource(R.string.try_again),
                                    color = KaspaTeal,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { viewModel.loadTxHistory(address) },
                                )
                            }
                        }
                    }
                    txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(bottom = barInset).padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_transactions_yet), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        // iOS's `.refreshable`: pull the list down to load the history again.
                        val historyPullState = rememberPullToRefreshState()
                        LaunchedEffect(historyPullState.isRefreshing) {
                            if (historyPullState.isRefreshing) {
                                viewModel.loadTxHistory(address)
                                historyPullState.endRefresh()
                            }
                        }
                        Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(historyPullState.nestedScrollConnection)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + barInset),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Some pages landed, some did not: say so rather than letting a
                            // truncated list read as the whole history.
                            if (historyIncomplete) {
                                item(key = "incomplete") {
                                    Text(
                                        tr(R.string.kp_some_transactions_could_not_be_loaded_c11c5),
                                        color = LocalAppColors.current.warning,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            items(txHistory, key = { it.txId }) { tx ->
                                ColdTxHistoryRow(
                                    tx = tx,
                                    // Tapping a transaction asks what to do with it. It used to
                                    // open the explorer outright, which left "Add to Portfolio"
                                    // on a button most people never looked for.
                                    onClick = { transactionActionTarget = tx }
                                )
                            }
                        }
                        if (historyPullState.verticalOffset > 0f || historyPullState.isRefreshing) {
                            PullToRefreshContainer(state = historyPullState, modifier = Modifier.align(Alignment.TopCenter))
                        }
                        }
                    }
                }
                else -> when {
                    isLoadingUtxos && utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(bottom = barInset), contentAlignment = Alignment.Center) {
                            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                        }
                    }
                    utxos.isEmpty() -> ColdUtxoListEmptyRow(
                        loadError = utxosLoadError,
                        retry = { viewModel.loadUtxos(address) },
                    )
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + barInset),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (utxos.size > 1) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(LocalAppColors.current.surface)
                                            .clickable { showCompoundFlow = true }
                                            .padding(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CallMerge, null, tint = KaspaTeal)
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                stringResource(R.string.compound_utxos),
                                                color = LocalAppColors.current.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            tr(R.string.kp_compound_utxos_description_kc),
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            items(utxos, key = { "${it.transactionId}:${it.index}" }) { utxo ->
                                val key = "${utxo.transactionId}:${utxo.index}"
                                ColdUtxoRow(
                                    utxo = utxo,
                                    label = utxoLabels[key],
                                    onRenameClick = {
                                        labelingUtxoKey = key
                                        labelInput = utxoLabels[key] ?: ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    labelingUtxoKey?.let { key ->
        ActionSheetContainer(
            title = stringResource(R.string.rename_utxo),
            subtitle = com.kachat.app.services.AddressActivityNotifier.shortAddress(key),
            onDismiss = { labelingUtxoKey = null },
        ) {
            ActionSheetRenameFields(
                value = labelInput,
                onValueChange = { labelInput = it },
                onCancel = { labelingUtxoKey = null },
                onSave = {
                    viewModel.setUtxoLabel(address, key, labelInput)
                    utxoLabels = viewModel.getUtxoLabels(address)
                    labelingUtxoKey = null
                },
            )
        }
    }

    if (showQr) {
        // The shared receive screen, Kaspa only, in a sheet with Close (iOS 3d126db
        // ColdStorageAddressQRView).
        QrPageSheet(onDismiss = { showQr = false }, white = false) {
            ColdStorageReceivePage(
                address = address,
                label = displayName,
                balanceSompi = addressBalanceSompi,
                dismiss = QrPageDismiss.CLOSE,
                onDismiss = { showQr = false },
            )
        }
    }

    if (showAddressActions) {
        // no View Private Key: a cold address's key stays on the signer
        ColdAddressActionsSheet(
            onViewPublicKey = {
                showAddressActions = false
                showPublicKey = true
            },
            onViewInExplorer = {
                showAddressActions = false
                uriHandler.coldOpenUriOrToast(kaspaExplorer.addressUrl(address))
            },
            onDismiss = { showAddressActions = false },
        )
    }

    if (showPublicKey) {
        com.kachat.app.ui.screens.AddressPublicKeyOverlay(
            address = address,
            note = tr(R.string.kp_this_is_the_public_half_of_c5f98),
            onDismiss = { showPublicKey = false },
        )
    }

    transactionActionTarget?.let { tapped ->
        ColdTransactionActionsSheet(
            tx = tapped,
            onOpenExplorer = {
                transactionActionTarget = null
                uriHandler.coldOpenUriOrToast(kaspaExplorer.txUrl(tapped.txId))
            },
            onAddToPortfolio = {
                transactionActionTarget = null
                portfolioCandidate = tapped
            },
            onDismiss = { transactionActionTarget = null },
        )
    }

    portfolioCandidate?.let { candidate ->
        com.kachat.app.ui.screens.portfolio.AddToPortfolioSheet(
            tx = candidate,
            address = address,
            viewModel = portfolioViewModel,
            onDismiss = { portfolioCandidate = null },
            onAdded = { addedPortfolioName = it },
        )
    }
    addedPortfolioName?.let { name ->
        com.kachat.app.ui.screens.portfolio.PortfolioAddedSnackbar(name) { addedPortfolioName = null }
    }
}

@Composable
private fun ColdUtxoRow(utxo: ColdStorageAddressDiscovery.AddressUtxo, label: String? = null, onRenameClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!label.isNullOrBlank()) {
                Text(
                    label,
                    color = KaspaTeal,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "${KaspaUnit.groupedExact(utxo.amountSompi)} ${KaspaUnit.symbol}",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${utxo.transactionId}:${utxo.index}",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (utxo.isCoinbase) {
            Text(
                stringResource(R.string.coinbase),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        IconButton(onClick = onRenameClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, stringResource(R.string.rename), tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
    }
}

/** "Fee 0.0001 KAS" for a history row, or null when the fee cannot be known (a coinbase, or an
 *  input the API did not resolve). Every transaction pays one, whoever sent it; on a received
 *  one it is what the sender paid. Mirrors iOS 9839906. */
private fun coldTransactionFeeText(feeSompi: Long?): String? {
    val fee = feeSompi ?: return null
    val kas = fee / 100_000_000.0
    val text = if (kas >= 0.001) "%.4f".format(kas) else "%.8f".format(kas)
    // "Fee %@" in the chosen language (iOS 3d126db KaspaRESTModels `feeText`)
    return tr(R.string.kp_fee_amount, "$text ${KaspaUnit.symbol}")
}

@Composable
private fun ColdTxHistoryRow(tx: ColdStorageAddressDiscovery.AddressTransaction, onClick: () -> Unit) {
    val dateStr = tx.blockTimeMillis?.let {
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(it))
    } ?: "Pending"
    val directionColor = if (tx.sent) LocalAppColors.current.danger else LocalAppColors.current.success

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(directionColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (tx.sent) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.CallReceived,
                null,
                tint = directionColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (tx.sent) tr(R.string.kp_sent_35f49) else tr(R.string.kp_received_27548), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            Text(dateStr, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            // What this transaction paid the network (iOS 9839906).
            coldTransactionFeeText(tx.feeSompi)?.let {
                Text(it, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                tx.txId,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${if (tx.sent) "-" else "+"}${KaspaUnit.groupedExact(tx.amountSompi)} ${KaspaUnit.symbol}",
            color = directionColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}


/**
 * The half sheet behind "Address Actions" on a watch-only account. Mirrors iOS's
 * `addressActionsSheet`.
 *
 * Discovery reports its position as it walks, and that lands HERE rather than dismissing: a scan
 * is one network call per address until the gap limit is reached, so it runs for a while, and a
 * closed sheet with a toast at the end said nothing about whether anything was happening.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColdStorageAddressActionsSheet(
    isDiscovering: Boolean,
    progress: ColdStorageAddressDiscovery.DiscoveryProgress?,
    summary: String?,
    onGenerate: () -> Unit,
    onDiscover: () -> Unit,
    onVisibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            // Dismissing mid-scan would abandon the only progress readout, and the work keeps running
            // either way - so the sheet holds until it is done.
            // Freely dismissable mid-scan now, by the swipe as well as by the button above: the scan
            // belongs to the ViewModel rather than to the sheet, so closing it abandons nothing - the
            // Address Actions button keeps its spinner and the result still arrives. Holding the
            // sheet open was only ever protecting a progress readout.
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.address_actions),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )

                    if (isDiscovering) {
                        Spacer(Modifier.height(8.dp))
                        com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        Text(
                            tr(R.string.kp_checking_address_lld_5b84c, progress?.checkingIndex ?: 0),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            if ((progress?.foundCount ?: 0) == 0) tr(R.string.kp_no_used_addresses_yet)
                            else tr(R.string.kp_lld_found_so_far_c6c1d, progress?.foundCount),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        Text(
                            tr(R.string.kp_checks_the_first_thousand_addresses_whatever_90acf),
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        // The scan runs in the ViewModel's own scope, not the sheet's, so closing the
                        // sheet does not stop it - it keeps running and reports what it found. Holding
                        // the sheet open for the length of a thousand-address sweep was the only reason
                        // to sit and watch it.
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Text(tr(R.string.kp_close_and_keep_scanning_b66cc), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        ActionSheetRow(
                            icon = Icons.Default.AddCircleOutline,
                            title = stringResource(R.string.generate_more_addresses),
                            subtitle = tr(R.string.kp_reveals_next_unused_address_account_kc),
                            onClick = onGenerate,
                        )
                        ActionSheetRow(
                            icon = Icons.Default.Search,
                            title = stringResource(R.string.discover_addresses),
                            subtitle = stringResource(R.string.finds_addresses_balance_or_domain),
                            onClick = onDiscover,
                        )
                        ActionSheetRow(
                            icon = Icons.Default.Checklist,
                            title = tr(R.string.kp_address_visibility_9c9a7),
                            subtitle = tr(R.string.kp_address_visibility_subtitle),
                            onClick = onVisibility,
                        )
                        if (summary != null) {
                            Text(summary, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}


// MARK: - KaPay's address pieces, which KaChat's own screens keep private

/** The options button at the top right of a Cold Storage address's page (KaPay's
 *  `AddressOptionsButton`, iOS 2da5f4b). */
@Composable
private fun ColdAddressOptionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = com.kachat.app.util.rememberHaptics()
    SheetBarIconButton(
        Icons.Default.MoreHoriz,
        stringResource(R.string.address_actions),
        onClick = {
            haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
            onClick()
        },
        modifier = modifier,
    )
}

/** Address Actions on a Cold Storage address (iOS 2da5f4b `AddressActionsSheet`): no View Private
 *  Key - a cold address's key stays on the signer. */
@Composable
private fun ColdAddressActionsSheet(
    onViewPublicKey: () -> Unit,
    onViewInExplorer: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(
        title = stringResource(R.string.address_actions),
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.Default.Numbers,
            title = tr(R.string.kp_view_public_key),
            subtitle = tr(R.string.kp_public_half_for_anyone),
            onClick = onViewPublicKey,
        )
        ActionSheetRow(
            icon = Icons.Default.Public,
            title = stringResource(R.string.view_in_explorer),
            subtitle = tr(R.string.kp_opens_address_on_explorer),
            onClick = onViewInExplorer,
        )
    }
}

/** What to do with a tapped transaction: look at it, or record it in Portfolio (KaPay's
 *  `TransactionActionsSheet`). */
@Composable
private fun ColdTransactionActionsSheet(
    tx: ColdStorageAddressDiscovery.AddressTransaction,
    onOpenExplorer: () -> Unit,
    onAddToPortfolio: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(
        title = tr(R.string.kp_transaction_1a7b7),
        subtitle = coldTransactionSummary(tx, com.kachat.app.util.is24HourClock()),
        detail = com.kachat.app.services.AddressActivityNotifier.shortAddress(tx.txId),
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = tr(R.string.kp_open_in_explorer),
            subtitle = tr(R.string.kp_opens_transaction_on_explorer),
            onClick = onOpenExplorer,
        )
        ActionSheetRow(
            icon = Icons.Default.PieChart,
            title = tr(R.string.kp_add_to_portfolio_9eb23),
            subtitle = tr(R.string.kp_records_buy_or_sell_in_portfolio),
            onClick = onAddToPortfolio,
        )
    }
}

/** "Sent 12.5 KAS on Sep 3, 2026, 2:02 PM" - what is about to be acted on, in one line. */
private fun coldTransactionSummary(tx: ColdStorageAddressDiscovery.AddressTransaction, is24Hour: Boolean): String {
    val direction = if (tx.sent) tr(R.string.kp_sent_35f49) else tr(R.string.kp_received_27548)
    val amount = com.kachat.app.repository.ChatRepository.formatKas(tx.amountSompi)
    val time = tx.blockTimeMillis?.let { com.kachat.app.util.IosDateStyle.mediumDateShortTime(it, is24Hour) }
    if (time == null) return "$direction $amount ${KaspaUnit.symbol}"
    return tr(
        if (tx.sent) R.string.kp_sent_amount_on_date else R.string.kp_received_amount_on_date,
        "$amount ${KaspaUnit.symbol}",
        time,
    )
}

/** Opens [url], or says no app can open it (KaPay's `openUriOrToast`). */
private fun androidx.compose.ui.platform.UriHandler.coldOpenUriOrToast(url: String) {
    try {
        openUri(url)
    } catch (e: Exception) {
        IosToasts.error(tr(R.string.no_app_to_open_link))
    }
}

/**
 * An address's UTXO tab with no coins to list, as iOS's insetGrouped list shows it: the
 * [UtxoLoadFailureRow] when the node could not be asked (its Try Again runs [retry]), otherwise
 * the row "No UTXOs.".
 */
@Composable
private fun ColdUtxoListEmptyRow(loadError: String?, retry: () -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val card = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface)
        if (loadError != null) {
            UtxoLoadFailureRow(detail = loadError, retry = retry, modifier = card)
        } else {
            Text(
                stringResource(R.string.no_utxos),
                color = colors.textSecondary,
                fontSize = 17.sp,
                modifier = card.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * A Cold Storage address's receive page (iOS 3d126db `ColdStorageAddressQRView`) on the shared
 * receive screen, Kaspa only: its name and balance, the cold-storage note, and that kUSDT can't
 * be received here.
 */
@Composable
private fun ColdStorageReceivePage(
    address: String,
    label: String,
    balanceSompi: Long,
    dismiss: QrPageDismiss,
    onDismiss: () -> Unit,
) {
    AddressReceivePage(dismiss = dismiss, onDismiss = onDismiss) {
        AddressReceiveView(
            title = stringResource(R.string.receive_kaspa),
            address = address,
            addressLabel = label,
            balanceLine = KaspaUnit.groupedAmount(balanceSompi),
            privacyNote = ReceiveNote(ReceiveNote.Kind.INFO, tr(R.string.kp_receive_note_cold_storage)),
            notes = listOf(
                ReceiveNotes.kaspaOnly(),
                ReceiveNote(ReceiveNote.Kind.WARNING, tr(R.string.kp_receive_note_no_kusdt_cold_storage)),
            ),
            icon = { KaspaLogoIcon() },
        )
    }
}
