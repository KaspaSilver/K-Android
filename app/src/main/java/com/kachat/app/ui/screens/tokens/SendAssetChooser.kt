package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.KaspaLogoIcon
import com.kachat.app.ui.screens.SendStepHeader
import com.kachat.app.ui.screens.SendStepQuestion
import com.kachat.app.ui.screens.SendStepTransition
import com.kachat.app.ui.screens.SpendingAddressSendFlow
import com.kachat.app.ui.screens.amount
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.mirrorInRtl
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel

/** What the Wallet tab's Send picked: Kaspa, or a token. */
private sealed class SendAssetChoice {
    object Kaspa : SendAssetChoice()
    data class Token(val token: WalletToken) : SendAssetChoice()
}

/**
 * The Wallet tab's Send (iOS 5b7c772c `SendAssetChooser`, owner's design 2026-11-19): "What are you
 * sending?" first, Kaspa or a token (kUSDT), each with its balance; a tap goes on to that one's
 * steps in the same sheet (Send Kaspa, or [TokenSendScreen]'s). [kaspaTotal]: every hot address
 * together in sompi, as the Wallet tab's balance card shows it; null until known. Presents itself
 * (a sheet); [onDismiss] once it has gone.
 */
@Composable
fun SendAssetChooser(
    kaspaTotal: Long?,
    onDismiss: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val haptic = rememberHaptics()
    val chattingAddress by walletViewModel.address.collectAsState()
    val chattingBalance by walletViewModel.balanceSompi.collectAsState()
    var choice by remember { mutableStateOf<SendAssetChoice?>(null) }
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        SendStepTransition(
            step = choice,
            order = { if (it == null) 0 else 1 },
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            when (shown) {
                null -> SendAssetChooserList(
                    kaspaTotal = kaspaTotal,
                    tokenViewModel = tokenViewModel,
                    onCancel = close,
                    onPick = { next ->
                        haptic(IosHaptic.IMPACT_LIGHT)
                        choice = next
                    },
                )
                SendAssetChoice.Kaspa -> {
                    val from = chattingAddress
                    if (from != null) {
                        SpendingAddressSendFlow(
                            fromAddress = from,
                            balanceSompi = chattingBalance,
                            title = stringResource(R.string.chats_send_kaspa),
                            viewModel = walletViewModel,
                            onDone = close,
                        )
                    }
                }
                is SendAssetChoice.Token -> TokenSendContent(
                    token = shown.token,
                    startFrom = null,
                    prefillRecipient = null,
                    prefillName = null,
                    chatRecipient = null,
                    chatName = null,
                    onSent = null,
                    onClose = close,
                    tokenViewModel = tokenViewModel,
                    walletViewModel = walletViewModel,
                )
            }
        }
    }
}

@Composable
private fun SendAssetChooserList(
    kaspaTotal: Long?,
    tokenViewModel: TokenViewModel,
    onCancel: () -> Unit,
    onPick: (SendAssetChoice) -> Unit,
) {
    val colors = LocalAppColors.current
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    LaunchedEffect(Unit) { tokenViewModel.refreshIfStale() }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SendStepHeader(stringResource(R.string.send))
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            SendStepQuestion(stringResource(R.string.token_what_are_you_sending))
            SendAssetOption(
                icon = { KaspaLogoIcon(size = 44.dp) },
                title = stringResource(R.string.kaspa),
                subtitle = KaspaUnit.symbol,
                amount = kaspaTotal?.let { KaspaUnit.amount(it) },
            ) { onPick(SendAssetChoice.Kaspa) }
            tokenViewModel.tokens.forEach { token ->
                val balance = remember(holdingsMap, token) { tokenViewModel.balance(token) }
                SendAssetOption(
                    icon = { TokenIcon(token, size = 44.dp) },
                    title = token.symbol,
                    subtitle = localizedTokenName(token),
                    amount = balance?.let { TokenAmount.amount(it, token) },
                ) { onPick(SendAssetChoice.Token(token)) }
            }
        }
        // Cancel, pinned at the bottom on the bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (colors.isDark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.03f))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, KaspaTeal.copy(alpha = 0.35f), CircleShape)
                    .clickable(role = Role.Button, onClick = onCancel),
            ) {
                Text(stringResource(R.string.cancel), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One choice: the coin's icon, its name and unit, the balance, and a chevron. */
@Composable
private fun SendAssetOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    amount: String?,
    trailingBusy: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(20.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
        if (trailingBusy) {
            IosActivityIndicator(modifier = Modifier.size(14.dp))
        }
        KachatFitText(amount ?: "--", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, minScale = 0.7f)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp).mirrorInRtl())
    }
}

/**
 * A 1:1 chat's Send Dollars (iOS `DollarTokenChooser`, owner's design 2026-11-20), the chat "+"
 * sheet's step: "Which stablecoin are you sending?", each dollar token with its balance. Only kUSDT
 * today; built as a list so other stablecoins join it later. A pick ([onPick]) goes on to
 * [TokenSendScreen] with the chat as the recipient. Opening it runs Discover for each one (every
 * address through 20 past the last), unless one ran in the last 5 minutes, so kUSDT that arrived
 * anywhere since is found on the spot. Its own half sheet: [onBack] to the "+" options,
 * [onDismiss] when swiped away.
 */
@Composable
fun DollarTokenChooser(
    onPick: (WalletToken) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val haptic = rememberHaptics()
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val isDiscovering by tokenViewModel.isDiscovering.collectAsState()
    val isRefreshing by tokenViewModel.isRefreshing.collectAsState()
    val dollarTokens = remember { tokenViewModel.tokens.filter { it.isDollarStable } }
    // The lookups run on the wallet's own scope (fire and forget): picking closes this sheet
    // without cutting them off, and the From step after it waits on the same check.
    LaunchedEffect(Unit) {
        dollarTokens.forEach { tokenViewModel.discoverIfStale(it) }
    }
    ActionSheetContainer(
        title = stringResource(R.string.send_dollars),
        subtitle = stringResource(R.string.token_which_stablecoin),
        onDismiss = onDismiss,
    ) {
        dollarTokens.forEach { token ->
            val balance = remember(holdingsMap, token) { tokenViewModel.balance(token) }
            SendAssetOption(
                icon = { TokenIcon(token, size = 40.dp) },
                title = token.symbol,
                subtitle = localizedTokenName(token),
                amount = balance?.let { TokenAmount.amount(it, token) },
                trailingBusy = isDiscovering || isRefreshing,
            ) {
                haptic(IosHaptic.IMPACT_LIGHT)
                onPick(token)
            }
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}
