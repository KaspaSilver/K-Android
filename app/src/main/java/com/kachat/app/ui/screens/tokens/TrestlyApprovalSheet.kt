package com.kachat.app.ui.screens.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParser
import com.kachat.app.R
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TrestlyApproval
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.IosSheetNavBar
import com.kachat.app.ui.screens.SendContinueButton
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.tr
import kotlinx.coroutines.delay

/** iOS's orange for the requote line and Trestly's warnings. */
private val WarningOrange = Color(0xFFFF9500)

/**
 * KaChat's approval for a trade on Trestly's site (iOS 148a5331 `TrestlyApprovalSheet`, from KaPay): what goes in
 * and comes out, the minimum the site set, the network fee, and Trestly's warnings. Nothing is
 * signed until Approve; Decline, Back or closing the sheet declines (4001). Approve wakes 0.8 s after
 * the sheet appears, so a tap meant for the page behind can't land on it, and stays off while
 * Trestly's code says the trade would be refused (`blocked`).
 */
@Composable
fun TrestlyApprovalSheet(request: TrestlyApproval, onAnswer: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(800)
        armed = true
    }
    BackHandler { onAnswer(false) }
    val blocked = request.flag("blocked")
    val isBuy = request.string("side") == "buy"

    Column(Modifier.fillMaxSize()) {
        IosSheetNavBar(
            title = tr(R.string.kp_approve),
            leading = { IosBarTextButton(stringResource(R.string.decline), onClick = { onAnswer(false) }) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenIcon(WalletToken.KUSDT, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr(R.string.kp_trestly_asks_kachat_to_kc), color = colors.textSecondary, fontSize = 12.sp)
                    Text(approvalTitle(request.method, isBuy), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (request.seen != null) {
                WarningLine(tr(R.string.kp_trestly_pool_moved_requote), requote = true)
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .iosGlass(18.dp)
                    .padding(16.dp),
            ) {
                ApprovalRows(request, isBuy)
                HorizontalDivider(color = colors.divider)
                ApprovalLine(stringResource(R.string.from), request.from)
                ApprovalLine(stringResource(R.string.network_fee), tr(R.string.kp_about_amount, kasText(request.number("feeAbout"))))
            }

            approvalWarnings(request).forEach { WarningLine(it) }

            Text(
                if (request.method == "kusdt_swap") tr(R.string.kp_trestly_swap_note_kc) else tr(R.string.kp_trestly_pool_note_kc),
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
        SendContinueButton(
            isEnabled = !blocked && armed,
            title = tr(R.string.kp_approve),
            action = { onAnswer(true) },
        )
    }
}

private fun kasText(sompi: Long?): String = sompi?.let { "${KaspaUnit.plain(it)} ${KaspaUnit.symbol}" } ?: "--"

private fun kusdtText(units: Long?): String = units?.let { TokenAmount.amount(it, WalletToken.KUSDT) } ?: "--"

private fun approvalTitle(method: String, isBuy: Boolean): String = when (method) {
    "kusdt_addLiquidity" -> tr(R.string.kp_add_to_the_pool)
    "kusdt_removeLiquidity" -> tr(R.string.kp_remove_from_the_pool)
    else -> if (isBuy) tr(R.string.kp_buy_kusdt) else tr(R.string.kp_sell_kusdt)
}

/** iOS `rows`: each trade's own numbers. */
@Composable
private fun ApprovalRows(request: TrestlyApproval, isBuy: Boolean) {
    // a swap's two sides: KAS in and kUSDT out for a buy, the other way round for a sell
    fun paid(v: Long?) = if (isBuy) kasText(v) else kusdtText(v)
    fun received(v: Long?) = if (isBuy) kusdtText(v) else kasText(v)
    when (request.method) {
        "kusdt_addLiquidity" -> {
            ApprovalLine(tr(R.string.kp_you_add), "${kasText(request.number("kasIn"))} + ${kusdtText(request.number("tokensIn"))}", bold = true)
            if (request.number("kasIn") != request.number("askedKasIn") || request.number("tokensIn") != request.number("askedTokensIn")) {
                ApprovalLine(tr(R.string.kp_the_site_asked), "${kasText(request.number("askedKasIn"))} + ${kusdtText(request.number("askedTokensIn"))}")
            }
            ApprovalLine(tr(R.string.kp_pool_shares), request.number("quote")?.toString() ?: "--")
            TrestlyApproval.number(request.seen?.get("quote"))?.let { before ->
                ApprovalLine(tr(R.string.kp_you_saw), before.toString())
            }
            ApprovalLine(tr(R.string.kp_kept_in_the_position), kasText(request.number("positionValue")))
        }
        "kusdt_removeLiquidity" -> {
            ApprovalLine(tr(R.string.kp_pool_shares), request.number("shares")?.toString() ?: "--")
            ApprovalLine(tr(R.string.kp_you_receive_about), "${kasText(request.number("kasOut"))} + ${kusdtText(request.number("tokensOut"))}", bold = true)
            request.seen?.let { seen ->
                ApprovalLine(
                    tr(R.string.kp_you_saw),
                    "${kasText(TrestlyApproval.number(seen.get("kasOut")))} + ${kusdtText(TrestlyApproval.number(seen.get("tokensOut")))}",
                )
            }
        }
        else -> {
            ApprovalLine(tr(R.string.kp_you_pay), paid(request.number("amountIn")))
            ApprovalLine(tr(R.string.kp_you_receive), received(request.number("quote")), bold = true)
            TrestlyApproval.number(request.seen?.get("quote"))?.let { before ->
                ApprovalLine(tr(R.string.kp_you_saw), received(before))
            }
            ApprovalLine(tr(R.string.kp_minimum_the_site_set), received(request.number("minOut")))
            if (isBuy) ApprovalLine(tr(R.string.kp_kept_in_your_token), kasText(request.number("tokenValue")))
        }
    }
}

/** iOS `warnings`: what Trestly's `prepare` flagged. */
private fun approvalWarnings(request: TrestlyApproval): List<String> {
    val list = ArrayList<String>()
    if (request.flag("paused")) list += tr(R.string.kp_trestly_pool_paused)
    request.string("breaker")?.let { text ->
        val b = try { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject } catch (e: Exception) { null }
        if (b != null) {
            val fit = TrestlyApproval.number(b.get("fit")) ?: 0L
            list += tr(R.string.kp_trestly_circuit_breaker, kasText(TrestlyApproval.number(b.get("maxKasOut"))))
            list += if (fit > 0) tr(R.string.kp_trestly_most_that_fits, kusdtText(fit)) else tr(R.string.kp_trestly_no_sell_fits)
        }
    }
    if (request.flag("belowMinimum")) list += tr(R.string.kp_trestly_below_minimum)
    if (request.flag("offRatio")) list += tr(R.string.kp_trestly_off_ratio)
    if (request.flag("unread")) list += tr(R.string.kp_trestly_position_unread)
    if (request.flag("feeShare")) list += tr(R.string.kp_trestly_fee_share)
    return list
}

@Composable
private fun WarningLine(text: String, requote: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (requote) Icons.Filled.Autorenew else Icons.Filled.Warning,
            contentDescription = null,
            tint = WarningOrange,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = WarningOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** iOS `line`: the title in secondary, the value trailing, monospaced digits, bold for the main number. */
@Composable
private fun ApprovalLine(title: String, value: String, bold: Boolean = false) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}
