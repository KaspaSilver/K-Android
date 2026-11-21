package com.kachat.app.ui.screens.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.WalletService
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenWallet
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosSheetNavBar
import com.kachat.app.ui.screens.MiddleEllipsisText
import com.kachat.app.ui.screens.SendContinueButton
import com.kachat.app.ui.screens.SendStepHeader
import com.kachat.app.ui.screens.SendStepQuestion
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.util.AppText
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.tr
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch

/** iOS's orange (the Chatting Address's person) and green (a private address's eye). */
private val ChattingOrange = Color(0xFFFF9500)
private val PrivateGreen = Color(0xFF34C759)

/**
 * The Wallet tab's Swap Stablecoins (iOS 148a5331 `BuyStablecoinsSheet`, from KaPay; owner's design
 * 2026-11-20): "Which stablecoin?" first, one row per stablecoin KaChat holds (kUSDT today), then
 * that coin's own place to buy it. kUSDT goes straight on to Trestly: the address it should use
 * ([TrestlyConnectPage], iOS `TrestlyConnectSheet`), then Trestly's site inside KaChat
 * ([TrestlyBrowserScreen], iOS's full-screen cover), all in this one sheet. This is the one way into
 * Trestly. Done in the browser closes the sheet and sends [TrestlyRoute.done], so the Wallet tab
 * goes back to its top.
 */
@Composable
fun SwapStablecoinsSheet(
    walletViewModel: WalletViewModel,
    onDismissed: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    remember { AppText.init(context) }
    val tokenWallet = tokenViewModel.wallet
    IosFullSheet(onDismissed = onDismissed, swipeToDismiss = false) { close ->
        var pickedToken by remember { mutableStateOf<WalletToken?>(null) }
        var openTarget by remember { mutableStateOf<TrestlyTarget?>(null) }
        val step = when {
            openTarget != null -> 2
            pickedToken != null -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(tween(350)) { it } togetherWith slideOutHorizontally(tween(350)) { -it })
            },
            label = "swapStablecoins",
        ) { shown ->
            when (shown) {
                0 -> StablecoinChooser(tokenWallet, onPick = { pickedToken = it }, onCancel = close)
                1 -> TrestlyConnectPage(
                    token = pickedToken ?: WalletToken.KUSDT,
                    tokenWallet = tokenWallet,
                    walletViewModel = walletViewModel,
                    onCancel = close,
                    onOpen = { openTarget = it },
                )
                else -> openTarget?.let { target ->
                    TrestlyBrowserScreen(target) {
                        // straight back to the Wallet tab, not the screen Trestly was opened from
                        close()
                        TrestlyRoute.done.tryEmit(Unit)
                    }
                }
            }
        }
    }
}

/** iOS `BuyStablecoinsSheet.chooser`: "Which stablecoin?", a row per token, and Cancel. */
@Composable
private fun StablecoinChooser(tokenWallet: TokenWallet, onPick: (WalletToken) -> Unit, onCancel: () -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SendStepHeader(title = tr(R.string.kp_swap_stablecoins))
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            SendStepQuestion(tr(R.string.kp_which_stablecoin))
            tokenWallet.tokens.forEach { token ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .iosGlass(20.dp)
                        .clickable {
                            haptic(IosHaptic.IMPACT_LIGHT)
                            onPick(token)
                        }
                        .padding(16.dp),
                ) {
                    TokenIcon(token, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(token.symbol, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(tr(R.string.kp_on_trestly_inside_kachat_kc), color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                tr(R.string.kp_more_stablecoins_will_show_here_kc),
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // iOS: a Cancel capsule on the material, a hairline of the accent at 35%
        Box(Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, KaspaTeal.copy(alpha = 0.35f), CircleShape)
                    .clickable { onCancel() },
            ) {
                Text(stringResource(R.string.cancel), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Swap Stablecoins' kUSDT (iOS 3d126db `TrestlyConnectSheet`): which address the site works with,
 * then the site itself. Offered: the Chatting Address, every address holding the token, every
 * regular spending address holding KAS (hidden ones too; owner's call, 2026-11-20: every address
 * with a balance of either), then a fresh one. Never a quantum one: Trestly's code trades only from
 * plain Schnorr addresses. Each row shows its kUSDT and its KAS.
 */
@Composable
private fun TrestlyConnectPage(
    token: WalletToken,
    tokenWallet: TokenWallet,
    walletViewModel: WalletViewModel,
    onCancel: () -> Unit,
    onOpen: (TrestlyTarget) -> Unit,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    BackHandler { onCancel() }

    val chatting by walletViewModel.address.collectAsState()
    val chattingSompi by walletViewModel.balanceSompi.collectAsState()
    val holdingsByToken by tokenWallet.holdingsFlow.collectAsState()
    val held = remember(holdingsByToken, token) { tokenWallet.holdings(token) }
    /** Each holding's name ("Address #3" or its nickname), read off the main thread. */
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(held) {
        labels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { held.associate { it.id to tokenWallet.label(it) } }
    }
    var fresh by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(Unit) {
        if (fresh == null) fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { tokenWallet.freshReceiveAddress() }
    }
    /** Each spending address's KAS (Manage Addresses' list), shown under its kUSDT; null until read. */
    var entries by remember { mutableStateOf<List<WalletService.SpendingAddressEntry>?>(null) }
    LaunchedEffect(Unit) {
        walletViewModel.loadManageAddressesAndAwait()
        entries = walletViewModel.manageAddresses.value
    }
    var pickedID by remember { mutableStateOf<String?>(null) }

    val chattingLabel = stringResource(R.string.chatting_address)
    val freshLabel = stringResource(R.string.pr_fresh_address)
    val choices = remember(chatting, held, labels, entries, fresh, chattingLabel, freshLabel) {
        val list = ArrayList<TrestlyTarget>()
        chatting?.let { address ->
            list += TrestlyTarget(chattingLabel, address, null, held.firstOrNull { it.isChatting }?.units ?: 0L)
        }
        for (holding in held) {
            if (holding.isChatting || list.any { it.address == holding.address }) continue
            list += TrestlyTarget(labels[holding.id] ?: holding.address, holding.address, holding.spendingIndex, holding.units)
        }
        // and every regular address holding KAS, in address order
        entries.orEmpty()
            .filter { !it.isQuantum && it.balanceSompi > 0 }
            .sortedBy { it.index }
            .forEach { entry ->
                if (list.none { it.address == entry.address }) list += TrestlyTarget(entry.displayLabel, entry.address, entry.index)
            }
        // one row per address: a fresh one that somehow matched a listed address isn't offered twice
        fresh?.let { (index, address) ->
            if (list.none { it.address == address }) list += TrestlyTarget(freshLabel, address, index)
        }
        list
    }
    val picked = choices.firstOrNull { it.id == pickedID }

    /** An address's KAS: the wallet's own balance for the Chatting Address, the spending list's for
     *  the rest (a fresh address has none yet). */
    fun kasBalance(address: String): Long? {
        if (address == chatting) return chattingSompi
        val list = entries ?: return null
        return list.firstOrNull { it.address == address }?.balanceSompi ?: 0L
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        IosSheetNavBar(
            title = tr(R.string.kp_open_trestly),
            leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = onCancel) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            SendStepQuestion(tr(R.string.kp_which_address_should_trestly_use))
            Text(tr(R.string.kp_trestly_address_note), color = colors.textSecondary, fontSize = 13.sp)
            choices.forEach { choice ->
                ChoiceRow(
                    choice = choice,
                    token = token,
                    kas = kasBalance(choice.address),
                    selected = pickedID == choice.id,
                    isChatting = choice.address == chatting,
                    onClick = {
                        haptic(IosHaptic.IMPACT_LIGHT)
                        pickedID = choice.id
                    },
                )
            }
        }
        SendContinueButton(
            isEnabled = picked != null,
            title = tr(R.string.kp_open_trestly),
            action = {
                val target = picked ?: return@SendContinueButton
                val freshPick = fresh
                if (freshPick != null && target.address == freshPick.second) {
                    // handed out now: it goes on Manage Addresses' list and is watched
                    scope.launch { tokenWallet.revealFreshReceiveAddress(freshPick.first) }
                }
                onOpen(target)
            },
        )
    }
}

/** iOS `choiceRow`: the pick circle, the name with its badge over the address, then both balances
 *  (the kUSDT to trade, and the KAS to swap or pay the fee with). */
@Composable
private fun ChoiceRow(
    choice: TrestlyTarget,
    token: WalletToken,
    kas: Long?,
    selected: Boolean,
    isChatting: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                if (selected) 1.5.dp else 0.8.dp,
                if (selected) KaspaTeal else Color.White.copy(alpha = 0.18f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) KaspaTeal else colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (isChatting) Icons.Filled.AccountCircle else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = if (isChatting) ChattingOrange else PrivateGreen,
                    modifier = Modifier.size(12.dp),
                )
            }
            MiddleEllipsisText(
                text = choice.address,
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                TokenAmount.amount(choice.units, token),
                color = if (choice.units > 0) colors.textPrimary else colors.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                kas?.let { "${KaspaUnit.plain(it)} ${KaspaUnit.symbol}" } ?: "– ${KaspaUnit.symbol}",
                color = if ((kas ?: 0L) > 0) colors.textPrimary else colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Send kUSDT's "None of your addresses holds kUSDT yet." button (iOS 148a5331 TokenSendView, where
 * "Get KaPay" was): Swap Stablecoins, to buy some, over the send.
 */
@Composable
internal fun SwapStablecoinsButton(walletViewModel: WalletViewModel) {
    val haptic = rememberHaptics()
    var showSheet by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(KaspaTeal)
            .clickable {
                haptic(IosHaptic.IMPACT_LIGHT)
                showSheet = true
            },
    ) {
        // SF Symbols "dollarsign.circle"
        Icon(Icons.Outlined.MonetizationOn, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.kp_swap_stablecoins), color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    if (showSheet) SwapStablecoinsSheet(walletViewModel = walletViewModel, onDismissed = { showSheet = false })
}
