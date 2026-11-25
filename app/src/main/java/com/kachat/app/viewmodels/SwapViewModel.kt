package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.SwapService
import com.kachat.app.services.WalletService
import com.kachat.app.services.tokens.TokenWallet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * The Swap screen's way to the app-wide [SwapService] (iOS `SwapService.shared`) and what its pickers
 * read: the spending addresses (Choose Address, Swap From), the kUSDT holdings and the explorer.
 */
@HiltViewModel
class SwapViewModel @Inject constructor(
    val service: SwapService,
    private val walletService: WalletService,
    val tokenWallet: TokenWallet,
    settings: AppSettingsRepository,
) : ViewModel() {
    val kaspaExplorer: Flow<KaspaExplorer> = settings.kaspaExplorer

    /** Every spending address with its balance and whether it was ever used; empty when the live
     *  check failed (iOS `getSpendingAddressList`). */
    suspend fun spendingAddresses(): List<WalletService.SpendingAddressEntry> = walletService.getSpendingAddressList()

    /** One more address on the spending chain (Choose Address's Generate New Address). */
    fun generateNextSpendingAddress(): Int = walletService.generateNextSpendingAddress()
}
