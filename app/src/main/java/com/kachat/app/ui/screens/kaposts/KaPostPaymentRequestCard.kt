package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.PaymentRequest
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A payment request link in a KaPost, drawn as a card instead of the link's long text (iOS
 * `KaPostPaymentRequestCard`, KaPosts 0b9fd28): the amount (and what it comes to in the app's
 * currency), the memo, who signed it, and a Pay button. A tap opens it from the card itself
 * ([PaymentRequestRouter]): Send Kaspa filled in, or your own request (iOS 148a5331: every request
 * is paid in KaChat). Long-press copies the link.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun KaPostPaymentRequestCard(request: PaymentRequest, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val haptic = com.kachat.app.util.rememberHaptics()
    var showMenu by remember { mutableStateOf(false) }

    // Whether it is yours, worked out off the main thread (the wallet's addresses may need
    // deriving once).
    val isOwn by androidx.compose.runtime.produceState(initialValue = false, request) {
        value = withContext(Dispatchers.Default) { request.isOwn }
    }

    val priceViewModel: com.kachat.app.viewmodels.KasPriceViewModel = hiltViewModel()
    val currency by priceViewModel.currency.collectAsState()
    // Read so the card redraws when the live price lands.
    val livePrice by priceViewModel.currentPriceUsd.collectAsState()
    val fiatText = remember(request.amountSompi, currency, livePrice) {
        livePrice?.takeIf { it > 0 }?.let { price ->
            "≈ " + com.kachat.app.util.formatFiatAmount(request.amountSompi / 100_000_000.0 * price, currency)
        }
    }

    // The maker, as the signature in the link proves: their name, else their short address.
    val creator = request.creator
    var creatorName by remember(creator) { mutableStateOf(creator?.let { com.kachat.app.models.addressDisplayName(it) }) }
    if (creator != null) {
        val resolver: AddressResolutionViewModel = hiltViewModel()
        LaunchedEffect(creator) {
            val contact = resolver.contactFor(creator)
            creatorName = com.kachat.app.models.addressDisplayName(creator, contact?.alias)
        }
    }

    // Every request is paid here since KaPay retired (iOS 148a5331).
    val hint = if (isOwn) stringResource(R.string.kpui_pr_shows_your_request) else stringResource(R.string.kpui_pr_opens_send_kaspa)

    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(KaspaTeal.copy(alpha = 0.08f))
                .border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = { showMenu = true },
                    onClick = {
                        haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                        // from the card itself: Send Kaspa filled in, or your own request (iOS 148a5331)
                        PaymentRequestRouter.open(request, fromChat = true)
                    },
                )
                .semantics(mergeDescendants = true) { contentDescription = hint }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.pr_payment_request).uppercase(),
                        color = KaspaTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${request.amountText} ${KaspaUnit.symbol}",
                            color = colors.textPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = IosRoundedFontFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                        )
                        fiatText?.let {
                            Text(
                                it,
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                    if (request.memo.isNotEmpty()) {
                        Text(
                            request.memo,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = creatorName
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (creator != null && name != null) {
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.success, modifier = Modifier.size(14.dp))
                        MiddleEllipsisText(
                            name,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp))
                        Text(
                            stringResource(R.string.pr_card_not_signed),
                            color = colors.warning,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isOwn) KaspaTeal.copy(alpha = 0.15f) else KaspaTeal)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val tint = if (isOwn) KaspaTeal else Color.White
                    Icon(
                        if (isOwn) Icons.Outlined.Visibility else Icons.Filled.ArrowCircleUp,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(if (isOwn) R.string.pr_card_view else R.string.pr_card_pay),
                        color = tint,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_link)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    showMenu = false
                    clipboard.setText(AnnotatedString(request.shareUrlString))
                    haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                },
            )
        }
    }
}
