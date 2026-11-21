package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.viewmodels.KasPriceViewModel

/** "1,234.5 KAS": [amount], with the thousands grouped the way this phone writes them (iOS
 *  5b7c772c `KaspaUnit.groupedAmount`). */
fun KaspaUnit.groupedAmount(sompi: Long): String = "${KaspaAmountGrouping.grouped(plain(sompi))} $symbol"

/** "1,234.50000000": all eight decimals, grouped (iOS 5b7c772c `KaspaUnit.groupedExact`, Manage
 *  Addresses' balances). */
fun KaspaUnit.groupedExact(sompi: Long): String =
    KaspaAmountGrouping.grouped("${sompi / 100_000_000L}." + String.format(java.util.Locale.US, "%08d", sompi % 100_000_000L))

/**
 * The balance at the top of an address's own page (Chatting Address, a Manage Addresses address,
 * a kUSDT address): the address's name, its balance large, and a line under it (iOS 5b7c772c
 * `AddressBalanceCard`, from KaPay). The same accent card as Wallet's balance card.
 *
 * [detail] is under the amount: worth in your currency, or what a kUSDT address holds with it.
 * [footer] goes under that.
 */
@Composable
fun AddressBalanceCard(
    label: String,
    amount: String,
    detail: String?,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.32f), KaspaTeal.copy(alpha = 0.08f))))
            .border(0.8.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = colors.textSecondary, fontSize = 15.sp, maxLines = 1)
        KachatFitText(
            amount,
            color = colors.textPrimary,
            fontSize = LargeTitleSize,
            fontWeight = FontWeight.Bold,
            minScale = 0.5f,
        )
        if (detail != null) {
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
        }
        footer()
    }
}

/**
 * A Kaspa address's card (iOS 5b7c772c `KaspaAddressBalanceCard`): [sompi] exact and grouped, with
 * its worth in your currency on mainnet (testnet coins aren't worth anything).
 */
@Composable
fun KaspaAddressBalanceCard(
    label: String,
    sompi: Long,
    modifier: Modifier = Modifier,
    priceViewModel: KasPriceViewModel = hiltViewModel(),
) {
    val price by priceViewModel.currentPriceUsd.collectAsState()
    val currency by priceViewModel.currency.collectAsState()
    val fiat = price?.takeIf { !KaspaNetwork.isTestnet && it > 0.0 }?.let { p ->
        formatFiatAmount(sompi / 100_000_000.0 * p, currency)
    }
    AddressBalanceCard(
        label = label,
        amount = "${KaspaUnit.groupedExact(sompi)} ${KaspaUnit.symbol}",
        detail = fiat,
        modifier = modifier,
    )
}
