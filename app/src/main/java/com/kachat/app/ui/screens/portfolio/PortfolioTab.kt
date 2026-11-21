package com.kachat.app.ui.screens.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.util.AppText
import com.kachat.app.viewmodels.PortfolioViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * The full-screen charts the Portfolio tab pushes (KAS price, portfolio value, network hashrate).
 * Each is registered in the NavHost and should get the same [PortfolioViewModel] the tab shows,
 * so the price history, range and summary are already loaded (KaPay took it from the tab's back
 * stack entry).
 */
object PortfolioRoutes {
    const val PRICE_CHART = "portfolio_price_chart"
    const val VALUE_CHART = "portfolio_value_chart"
    const val HASHRATE_CHART = "portfolio_hashrate_chart"
}

/**
 * Portfolio (iOS `PortfolioView`, back from KaPay in 148a5331): the `.portfolio` tab, which starts
 * in Kaspa Hub. Kept on the phone: KaChat's Nextcloud backs up contacts only (the CSV export and
 * import to a Nextcloud folder stay, as on iOS).
 *
 * [walletViewModel] is MainShell's one WalletViewModel, for the backup banner above the title.
 */
@Composable
fun PortfolioTab(
    navController: NavController,
    walletViewModel: WalletViewModel,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    AppText.init(LocalContext.current)
    PortfolioScreen(navController = navController, walletViewModel = walletViewModel, viewModel = viewModel)
}
