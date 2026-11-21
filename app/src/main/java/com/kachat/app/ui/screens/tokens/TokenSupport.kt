package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.services.WalletManager
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.TokenViewModel

// Small shared pieces the kUSDT screens use (iOS 5b7c772c `Views/Tokens/TokenSupport.swift` and
// the UI parts of `WalletTokens.swift`: TokenIcon), as KaPay has them.

/**
 * A bare `kaspa:` / `kaspatest:` address in upper case ("KASPA:QR...", what a compact QR code
 * carries) in lower case: bech32 is case-insensitive, but only in one case, so mixed case stays
 * as typed and fails the check. Anything else (a name, a link with a query) is returned as is
 * (iOS `lowercasedKaspaAddress`).
 */
fun lowercasedKaspaAddress(text: String): String {
    val lower = text.lowercase()
    if (!(lower.startsWith("kaspa:") || lower.startsWith("kaspatest:")) || text.contains('?')) return text
    val colon = text.indexOf(':')
    if (colon < 0) return text
    val payload = text.substring(colon + 1)
    return if (payload == payload.uppercase() || payload == payload.lowercase()) lower else text
}

/** Tokens go only to a key's own address: sent to a script address (a quantum-safe one among
 *  them) kUSDT could be stuck or lost (kusdt-open SPEC section 4; iOS `ChatService.isScriptAddress`). */
internal fun isTokenScriptAddress(address: String?): Boolean =
    com.kachat.app.repository.ChatRepository.isScriptAddress(address)

/** A history row's fee as Kaspa's rows write one: four decimals, eight under 0.001 KAS (iOS
 *  `TokenActivityRow.feeText`). */
fun tokenFeeText(sompi: Long, symbol: String = com.kachat.app.util.KaspaUnit.symbol): String {
    val kas = sompi / 100_000_000.0
    val number = if (kas >= 0.001) String.format(java.util.Locale.US, "%.4f", kas) else String.format(java.util.Locale.US, "%.8f", kas)
    return "$number $symbol"
}

/**
 * The token a payment bubble's text names ("Sent 12.50 kUSDT", Send Dollars; iOS 5b7c772c
 * `PaymentCardParts.token`): its icon and symbol stand in for Kaspa's. Null for a Kaspa payment.
 * A token row is stored with no `amountSompi`, so only its text says what it is - never KAS.
 */
fun paymentCardToken(content: String?): WalletToken? {
    if (content == null || content.length > 512) return null
    val words = content.split(" — ").first().split(Regex("\\s+"))
    return WalletToken.ALL.firstOrNull { it.symbol in words }
}

/** "kaspa:qpz8x4rm7gk0f3...d3e5u7": an address cut the way Manage Addresses' rows cut it. */
internal fun shortTokenAddress(address: String, head: Int = 14, separator: String = "..."): String =
    if (address.length <= head + 6) address else "${address.take(head)}$separator${address.takeLast(6)}"

/** What people read under a token's symbol ("Bridged USDT"), localized (the model keeps English). */
@Composable
fun localizedTokenName(token: WalletToken): String =
    if (token.name == WalletToken.KUSDT.name) stringResource(R.string.bridged_usdt) else token.name

/**
 * A holding's name (iOS `TokenHolding.label`): "Chatting Address", the name the person gave the
 * address, or "Address #3". Worked out off the main thread (it may derive addresses); [refreshKey]
 * asks again (a rename).
 */
@Composable
internal fun rememberHoldingLabel(holding: TokenHolding, tokenViewModel: TokenViewModel, refreshKey: Any? = null): String {
    val fallback = holding.spendingIndex?.let { WalletManager.spendingAddressTitle(it) }
        ?: stringResource(R.string.chatting_address)
    val label by produceState(fallback, holding.id, holding.address, refreshKey) {
        value = runCatching { tokenViewModel.label(holding) }.getOrDefault(fallback)
    }
    return label
}

/** USDT green (iOS `TokenIcon.green`). */
val TokenIconGreen = Color(0xFF26A17B)

/**
 * A token's round icon (iOS `TokenIcon`). A dollar token bridged from USDT shows the USDT mark (the
 * white "T" with its ring, on USDT green), drawn here rather than shipped as an image, with the
 * small Kaspa logo at the corner: it's the bridged token on Kaspa, not USDT itself.
 */
@Composable
fun TokenIcon(token: WalletToken, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val background = LocalAppColors.current.background
    Box(modifier = modifier.size(size).clearAndSetSemantics { }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).background(TokenIconGreen),
        ) {
            if (token.isDollarStable) {
                Canvas(Modifier.size(size)) {
                    val w = this.size.width
                    val h = this.size.height
                    // the bar
                    drawRect(Color.White, topLeft = Offset(w * 0.23f, h * 0.24f), size = Size(w * 0.54f, h * 0.13f))
                    // the stem, through the ring and below it
                    drawRect(Color.White, topLeft = Offset(w * 0.425f, h * 0.37f), size = Size(w * 0.15f, h * 0.42f))
                    // the ring: a flat ellipse round the stem, drawn as a band
                    drawOval(
                        Color.White,
                        topLeft = Offset(w * 0.17f, h * 0.415f),
                        size = Size(w * 0.66f, h * 0.17f),
                        style = Stroke(width = w * 0.05f),
                    )
                }
            } else {
                Icon(Icons.Filled.Hexagon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            }
        }
        // the small Kaspa logo: it lives on Kaspa L1
        Image(
            painter = painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = size * 0.06f, y = size * 0.06f)
                .size(size * 0.38f)
                .clip(CircleShape)
                .border(1.5.dp, background, CircleShape),
        )
    }
}
