package com.kachat.app.ui.screens.coldstorage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.util.AppText
import com.kachat.app.viewmodels.ColdStorageViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * The Cold Storage tab (iOS 148a5331 `Views/ColdStorage/ColdStorageView.swift`, back from KaPay;
 * it starts as a tile in Kaspa Hub): the KasSigner accounts, each account's addresses, an
 * address's history, coins and domains, Address Visibility, and the send round trip through the
 * KasSigner device (Sign with KasSigner, then the signed one scanned back and swiped up).
 *
 * The tab keeps its own little stack - list, account, address, visibility - with one
 * [ColdStorageViewModel] for all of it (KaPay shared one across its routes for the same reason: an
 * address's page reads the account's rows for its "Address #N" title). Android Back walks back
 * down that stack; at the list it is left to whatever hosts the tab.
 *
 * [walletViewModel] is the shell's one, for the backup banner over the title (iOS
 * `pinnedTabHeader("Cold Storage")` with `AccountBackupBanner`); [onOpenAccountBackup] opens Back
 * Up Account from it. The host provides `LocalDockInset` so the lists run on under the dock.
 */
@Composable
fun ColdStorageTab(
    walletViewModel: WalletViewModel,
    onOpenAccountBackup: () -> Unit,
    viewModel: ColdStorageViewModel = hiltViewModel(),
) {
    AppText.init(LocalContext.current)
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf<String?>(null) }
    var showsVisibility by rememberSaveable { mutableStateOf(false) }

    val openAccount = accountId
    val openAddress = address
    BackHandler(enabled = openAccount != null) {
        when {
            showsVisibility -> showsVisibility = false
            address != null -> address = null
            else -> accountId = null
        }
    }

    when {
        openAccount == null -> ColdStorageListScreen(
            onOpenAccount = { accountId = it },
            onOpenAccountBackup = onOpenAccountBackup,
            walletViewModel = walletViewModel,
            viewModel = viewModel,
        )
        showsVisibility -> ColdStorageAddressVisibilityScreen(
            accountId = openAccount,
            viewModel = viewModel,
            onBack = { showsVisibility = false },
        )
        openAddress != null -> ColdStorageTxHistoryScreen(
            address = openAddress,
            onBack = { address = null },
            viewModel = viewModel,
        )
        else -> ColdStorageDetailScreen(
            accountId = openAccount,
            viewModel = viewModel,
            onBack = { accountId = null },
            onOpenAddress = { address = it },
            onOpenVisibility = { showsVisibility = true },
        )
    }
}

/**
 * The History sheet a tapped "Received" notification opens for a Cold Storage address (iOS 13046ad
 * `OwnAddressHistorySheet`, as KaPay opens it): the address's page with Done top left, its row
 * ("Address #N", balance) found across every KasSigner account. [isOwnColdAddress] tells the
 * caller whether an address is a cold one at all.
 */
@Composable
fun ColdStorageAddressHistorySheet(
    address: String,
    onDone: () -> Unit,
    viewModel: ColdStorageViewModel = hiltViewModel(),
) {
    AppText.init(LocalContext.current)
    val row by produceState<ColdStorageViewModel.AddressRow?>(initialValue = null, address) {
        value = viewModel.resolveOwnColdAddress(address)
    }
    ColdStorageTxHistoryScreen(
        address = address,
        onBack = onDone,
        viewModel = viewModel,
        resolvedRow = row,
        closesWithDone = true,
    )
}

/** Whether [address] is one of this account's KasSigner addresses (any account, up to each one's
 *  revealed index) - for routing a "Received" notification to [ColdStorageAddressHistorySheet]. */
suspend fun ColdStorageViewModel.isOwnColdAddress(address: String): Boolean = resolveOwnColdAddress(address) != null
