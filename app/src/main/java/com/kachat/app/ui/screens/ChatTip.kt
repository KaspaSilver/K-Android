package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.repository.KaPostsTipAsset
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.util.KaspaUnit

/**
 * Tip in a 1:1 chat (iOS 2026-11-23 `ChatDetailView.tipFromChat`): the same as a KaPost's Tip, by
 * Tip In and the instant tip (Settings > Customization > Tipping). A Kaspa tip with a default
 * amount waits in the composer as a Tip card until the send button (owner: never straight from
 * the +; the trash takes it back); with no default the tip sheet asks the amount; kUSDT is the
 * chat's Send Dollars. It is an ordinary payment in the chat: no post, nothing counted.
 */
object ChatTip {
    /** What a tap on Tip does with Kaspa (iOS `KaPostsSettingsView.instantTip`). */
    sealed class Instant {
        /** No default amount: the tip sheet asks it. */
        object None : Instant()
        /** Over the instant limit (10 KAS): the tip sheet, saying why. */
        object OverLimit : Instant()
        /** Under what the network takes: a toast. */
        object TooSmall : Instant()
        /** A default set in a currency with no recent price: a toast naming it. */
        data class NoPrice(val fiatText: String) : Instant()
        /** Waits in the composer as the Tip card. */
        data class Send(val sompi: Long, val fiatText: String?) : Instant()
    }

    /**
     * The instant tip, from the saved default ([defaultSompi], or [fiatAmount] in [fiatText]'s
     * currency at [fiatPrice], a recent price or null) and the limits. [convert] turns a currency
     * amount into sompi, null when it is no real amount (KaPost's checked conversion).
     */
    fun decide(
        defaultSompi: Long?,
        fiatAmount: Double?,
        fiatText: String?,
        fiatPrice: Double?,
        minimumSompi: Long,
        limitSompi: Long,
        convert: (Double, Double) -> Long?,
    ): Instant {
        if (fiatAmount != null) {
            val price = fiatPrice?.takeIf { it > 0 } ?: return Instant.NoPrice(fiatText.orEmpty())
            val converted = convert(fiatAmount, price) ?: return Instant.OverLimit
            return limited(converted, fiatText, minimumSompi, limitSompi)
        }
        val amount = defaultSompi?.takeIf { it > 0 } ?: return Instant.None
        return limited(amount, null, minimumSompi, limitSompi)
    }

    private fun limited(amount: Long, fiatText: String?, minimumSompi: Long, limitSompi: Long): Instant = when {
        amount <= 0 -> Instant.None
        amount < minimumSompi -> Instant.TooSmall
        amount > limitSompi -> Instant.OverLimit
        else -> Instant.Send(amount, fiatText)
    }

    /** The default tip waiting in the composer (iOS `PendingTip`). */
    data class Pending(val sompi: Long, val fiatText: String?)

    /** The tip screen to show (iOS `ChatTipTarget`): Kaspa's amount, or "What are you tipping in?". */
    data class Target(val asset: KaPostsTipAsset, val failure: String? = null)

    /** "1.5 KAS", and "1.5 KAS ($0.20)" for a default set in a currency (iOS `amountText`). */
    fun amountText(sompi: Long, fiatText: String?): String {
        val kas = "${KaspaUnit.plain(sompi)} ${KaspaUnit.symbol}"
        return fiatText?.let { "$kas ($it)" } ?: kas
    }
}

/**
 * The default tip waiting in the composer (iOS `pendingTipRow`): the amount (and the currency
 * amount it came from), sent by the send button beside it, taken back by the trash.
 */
@Composable
internal fun ChatPendingTipRow(tip: ChatTip.Pending, isSending: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    Row(
        modifier = modifier
            .iosGlass(cornerRadius = 20.dp)
            .border(1.dp, KaspaTeal.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.kaposts_tip_title), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("${KaspaUnit.plain(tip.sompi)} ${KaspaUnit.symbol}", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            tip.fiatText?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp) }
        }
        if (isSending) {
            CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            IconButton(onClick = {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                onRemove()
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove), tint = colors.danger)
            }
        }
    }
}

/**
 * The + sheet's Tip when it needs a screen (iOS `KaPostTipFlow` from a chat): Kaspa's amount
 * ([KaPostTipSheet], naming no post), or "What are you tipping in?" first; kUSDT goes to the
 * chat's Send Dollars ([onPickKusdt]) once this is gone.
 */
@Composable
internal fun ChatTipFlow(
    address: String,
    displayName: String,
    target: ChatTip.Target,
    onPickKusdt: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember(address) { mutableStateOf(target.asset) }
    when (choice) {
        KaPostsTipAsset.KASPA -> KaPostTipSheet(
            address = address,
            displayName = displayName,
            onDismiss = onDismiss,
            tip = null,
            initialError = target.failure,
        )
        KaPostsTipAsset.KUSDT -> LaunchedEffect(Unit) {
            onDismiss()
            onPickKusdt()
        }
        KaPostsTipAsset.ASK -> KaPostTipAssetChooser(
            viewModel = hiltViewModel<com.kachat.app.viewmodels.KaPostsViewModel>(),
            onPick = { choice = it },
            onDismiss = onDismiss,
            // no post: kUSDT is just "kUSDT", nothing is counted (iOS 518fdc8c)
            forPost = false,
        )
    }
}
