package com.kachat.app.ui.screens.tokens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.R
import com.kachat.app.services.tokens.TokenActivity
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.AddressReceivePage
import com.kachat.app.ui.screens.AddressReceiveView
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.LargeTitleSize
import com.kachat.app.ui.screens.MiddleEllipsisText
import com.kachat.app.ui.screens.QrPageDismiss
import com.kachat.app.ui.screens.ReceiveNote
import com.kachat.app.ui.screens.ReceiveNotes
import com.kachat.app.ui.screens.SelectablePill
import com.kachat.app.ui.screens.amount
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.mirrorInRtl
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.viewmodels.TokenViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// iOS 5b7c772c `Views/Tokens/TokenViews.swift`: the Wallet tab's kUSDT card and its pager, a
// history row, and Receive.

// MARK: - The Wallet tab's balance card

/**
 * A token's page of the Wallet tab's balance card (swiped to from Total Balance, iOS
 * `TokenBalanceCard`): the same card, in the token's green, with its balance, dollar value, where
 * it's held and the KAS held with it. The caller makes it tappable (it opens the token's screen).
 */
@Composable
fun TokenBalanceCard(
    token: WalletToken,
    modifier: Modifier = Modifier,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val units = remember(holdingsMap, token) { tokenViewModel.balance(token) }
    val kasHeld = remember(holdingsMap, token) { tokenViewModel.kasHeld(token) }
    val heldOn by produceState("--", holdingsMap, token) { value = tokenViewModel.heldOnText(token) }
    LaunchedEffect(Unit) { tokenViewModel.refreshIfStale() }
    val shape = RoundedCornerShape(22.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(TokenIconGreen.copy(alpha = 0.32f), TokenIconGreen.copy(alpha = 0.08f))))
            .border(0.8.dp, TokenIconGreen.copy(alpha = 0.35f), shape)
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenIcon(token, size = 22.dp)
                Text(
                    stringResource(R.string.token_balance_title, token.symbol),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                TapToManageLabel()
            }
            KachatFitText(
                units?.let { TokenAmount.amount(it, token) } ?: "--",
                color = colors.textPrimary,
                fontSize = LargeTitleSize,
                fontWeight = FontWeight.Bold,
                minScale = 0.6f,
            )
            val dollars = units?.let { TokenAmount.dollars(it, token) }
            if (dollars != null) {
                Text(dollars, color = colors.textSecondary, fontSize = 15.sp)
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TokenCardPart(stringResource(R.string.token_held_on), heldOn, Modifier.weight(1f))
            TokenCardPart(stringResource(R.string.token_kas_held_with_it), KaspaUnit.amount(kasHeld), Modifier.weight(1f))
        }
    }
}

@Composable
private fun TokenCardPart(title: String, value: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp)
        KachatFitText(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, minScale = 0.7f)
    }
}

/** "Tap to Manage ›" in a balance card's top corner: the card opens its screen (Manage Addresses,
 *  or the token's). iOS `TapToManageLabel`. */
@Composable
fun TapToManageLabel(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        Text(stringResource(R.string.tap_to_manage), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp).mirrorInRtl())
    }
}

/** The spring every change of page slides with (iOS `SlidingCardPager.slide`: response 0.38,
 *  damping 0.86). */
object SlidingCardPagerDefaults {
    val slide: AnimationSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 273f)
}

/**
 * The Wallet tab's balance cards side by side, one showing (iOS `SlidingCardPager`): a swipe drags
 * them with the finger and lets go onto the next or the previous one, and every change of page (a
 * swipe, a flick, a pill) slides with the same spring. Only a sideways drag is taken, so the tab
 * still scrolls up and down from the card.
 */
@Composable
fun SlidingCardPager(
    pages: List<@Composable () -> Unit>,
    page: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val pageCount = pages.size
        val base = remember { Animatable(-page * width) }
        var drag by remember { mutableFloatStateOf(0f) }
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(page, width) {
            val target = -page * width
            if (!settled) {
                base.snapTo(target)
                settled = true
            } else {
                base.animateTo(target, SlidingCardPagerDefaults.slide)
            }
        }
        /** Past the first or the last card it gives, but only a little. */
        fun effective(dx: Float): Float {
            val atEdge = (page == 0 && dx > 0) || (page == pageCount - 1 && dx < 0)
            return if (atEdge) dx / 3 else dx
        }
        var rawDrag by remember { mutableFloatStateOf(0f) }
        val dragState = rememberDraggableState { delta ->
            rawDrag += delta
            drag = effective(rawDrag)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { rawDrag = 0f },
                    onDragStopped = { velocity ->
                        val current = drag
                        // where a flick would carry it
                        val predicted = current + velocity * 0.25f
                        var next = page
                        if (current < -width * 0.25f || predicted < -width * 0.5f) next = minOf(page + 1, pageCount - 1)
                        if (current > width * 0.25f || predicted > width * 0.5f) next = maxOf(page - 1, 0)
                        scope.launch {
                            base.snapTo(base.value + current)
                            drag = 0f
                            rawDrag = 0f
                            if (next != page) {
                                haptic(IosHaptic.IMPACT_LIGHT)
                                onPageChange(next)
                            }
                            base.animateTo(-next * width, SlidingCardPagerDefaults.slide)
                        }
                    },
                ),
        ) {
            val pageWidth = with(androidx.compose.ui.platform.LocalDensity.current) { width.toDp() }
            pages.forEachIndexed { index, content ->
                Box(
                    contentAlignment = Alignment.TopStart,
                    modifier = Modifier
                        .width(pageWidth)
                        .offset { IntOffset((index * width + base.value + drag).roundToInt(), 0) },
                ) { content() }
            }
        }
    }
}

/**
 * The pills under the Wallet tab's balance card, one per card ("Kaspa", "kUSDT"): the card showing
 * is filled, and a tap slides to that card (iOS `BalancePagePills`, owner's design 2026-11-19).
 * [titles] are already localized.
 */
@Composable
fun BalancePagePills(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = index == selected
            val shape = RoundedCornerShape(50)
            Text(
                title,
                color = if (isSelected) Color.Black else colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) KaspaTeal else colors.surface)
                    .border(1.dp, KaspaTeal.copy(alpha = if (isSelected) 0f else 0.3f), shape)
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) {
                        if (isSelected) return@clickable
                        haptic(IosHaptic.IMPACT_LIGHT)
                        onSelect(index)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// MARK: - Activity

/**
 * One line of a token's history, drawn like a transaction on a Kaspa address's page (iOS
 * `TokenActivityRow`): the circled arrow, Received or Sent in its colour, the transaction id, the
 * date and time; on the right the signed amount, the fee when this phone paid it, and the explorer
 * mark. A tap opens the transaction in the explorer Settings names.
 */
@Composable
fun TokenActivityRow(
    token: WalletToken,
    row: TokenActivity,
    modifier: Modifier = Modifier,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val uriHandler = LocalUriHandler.current
    val explorer by tokenViewModel.kaspaExplorer.collectAsState()
    val kind = row.kindValue ?: TokenActivity.Kind.RECEIVED
    val isIn = kind == TokenActivity.Kind.RECEIVED || kind == TokenActivity.Kind.BRIDGED || kind == TokenActivity.Kind.SWAPPED
    // A combine moves nothing in or out: no sign.
    val sign = if (kind == TokenActivity.Kind.COMBINED) "" else if (isIn) "+" else "-"
    val tint = when (kind) {
        TokenActivity.Kind.RECEIVED, TokenActivity.Kind.BRIDGED, TokenActivity.Kind.SWAPPED -> colors.success
        TokenActivity.Kind.SENT -> colors.danger
        TokenActivity.Kind.COMBINED -> KaspaTeal
    }
    val icon = when (kind) {
        TokenActivity.Kind.RECEIVED, TokenActivity.Kind.BRIDGED -> Icons.Filled.ArrowCircleDown
        TokenActivity.Kind.SENT -> Icons.Filled.ArrowCircleUp
        TokenActivity.Kind.SWAPPED -> Icons.Filled.SwapHorizontalCircle
        TokenActivity.Kind.COMBINED -> Icons.Filled.Merge
    }
    val title = stringResource(
        when (kind) {
            TokenActivity.Kind.RECEIVED -> R.string.payment_received
            TokenActivity.Kind.SENT -> R.string.sent
            TokenActivity.Kind.SWAPPED -> R.string.token_activity_swapped
            TokenActivity.Kind.BRIDGED -> R.string.token_activity_bridged_in
            TokenActivity.Kind.COMBINED -> R.string.token_activity_combined_coins
        }
    )
    val txid = row.txid
    val dateText = remember(row.date) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(row.date))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = txid != null) {
                val id = txid ?: return@clickable
                haptic(IosHaptic.IMPACT_LIGHT)
                runCatching { uriHandler.openUri(explorer.txUrl(id)) }
            }
            .padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (row.pending) {
                    Text(stringResource(R.string.token_pending), color = colors.warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            MiddleEllipsisText(
                txid ?: row.counterparty,
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(dateText, color = colors.textSecondary, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                sign + TokenAmount.amount(row.units, token),
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            row.fee?.let { fee ->
                Text(stringResource(R.string.token_fee, tokenFeeText(fee)), color = colors.textSecondary, fontSize = 11.sp)
            }
            if (txid != null) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// MARK: - Receive

/**
 * Receive a token, on the shared receive screen ([AddressReceiveView]; iOS `TokenReceiveSheet`).
 * Fresh Address first (owner's call, 2026-11-19: privacy from the start): a brand-new regular
 * address of yours, so the payment can't be tied to your name; or the Chatting Address, which
 * shows your .kachat name and profile. From an address's own page ([fixedHolding]) it shows that
 * address only. A fresh address goes on Manage Addresses' list once it's copied or shared.
 *
 * Presents itself (a sheet with Done); [onDismiss] once it has gone.
 */
@Composable
fun TokenReceiveSheet(
    token: WalletToken,
    chattingAddress: String,
    fixedHolding: TokenHolding? = null,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    var useFresh by remember { mutableStateOf(true) }
    val fresh by produceState<Pair<Int, String>?>(null, fixedHolding) {
        if (fixedHolding == null) value = tokenViewModel.freshReceiveAddress()
    }
    val fixedLabel = fixedHolding?.let { rememberHoldingLabel(it, tokenViewModel) }
    val shownAddress = when {
        fixedHolding != null -> fixedHolding.address
        useFresh -> fresh?.second.orEmpty()
        else -> chattingAddress
    }
    val privacyNote = when {
        fixedHolding != null -> if (fixedHolding.isChatting) ReceiveNotes.chattingAddress() else ReceiveNotes.spendingAddress()
        useFresh -> ReceiveNotes.freshAddress()
        else -> ReceiveNotes.chattingAddress()
    }
    val notes = listOf(
        ReceiveNote(ReceiveNote.Kind.ALLOWED, stringResource(R.string.token_receive_only, token.symbol)),
        ReceiveNote(ReceiveNote.Kind.WARNING, stringResource(R.string.token_receive_no_usdt_warning)),
    )
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        AddressReceivePage(dismiss = QrPageDismiss.DONE, onDismiss = close) {
            AddressReceiveView(
                title = stringResource(R.string.token_receive_title, token.symbol),
                address = shownAddress,
                addressLabel = fixedLabel,
                privacyNote = privacyNote,
                notes = notes,
                // A fresh address handed out goes on the list (and is watched) from now on.
                onHandedOut = {
                    val picked = fresh
                    if (fixedHolding == null && useFresh && picked != null) {
                        tokenViewModel.viewModelScope.launch { tokenViewModel.revealFreshReceiveAddress(picked.first) }
                    }
                },
                icon = { TokenIcon(token, size = 34.dp) },
                choices = {
                    if (fixedHolding == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            SelectablePill(stringResource(R.string.pr_fresh_address), selected = useFresh, modifier = Modifier.weight(1f)) { useFresh = true }
                            SelectablePill(stringResource(R.string.chatting_address), selected = !useFresh, modifier = Modifier.weight(1f)) { useFresh = false }
                        }
                    }
                },
            )
        }
    }
}
