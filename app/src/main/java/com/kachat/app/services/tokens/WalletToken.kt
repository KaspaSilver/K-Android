package com.kachat.app.services.tokens

import com.kachat.app.ui.screens.KaspaAmountGrouping
import java.util.Locale

// Tokens on Kaspa L1 (KCC-20), starting with Trestly's kUSDT (owner's call, 2026-11-19). KaPay is
// where tokens are bought, swapped and sold; KaChat, on the same words, holds, receives and sends
// them (iOS `Views/Tokens/WalletTokens.swift`, 5b7c772c: its model types; the screens are built
// on top of these).

/**
 * A token KaChat can hold (iOS `WalletToken`). Amounts are whole base units ([decimals] places:
 * kUSDT has 6). Two tokens are the same token when their [id]s match.
 */
class WalletToken(
    val id: String,
    val symbol: String,
    /** What people read under the symbol ("Bridged USDT"). English: the screens localize it
     *  (iOS key "Bridged USDT"). */
    val name: String,
    val decimals: Int,
    /** Worth one US dollar (a stablecoin): the screens show its dollar value without a price feed. */
    val isDollarStable: Boolean,
    /** Who issues it on Kaspa. */
    val issuer: String,
    val issuerSite: String,
    /** Where its backing can be checked on chain. */
    val backingUrl: String,
    /** KAS every token coin holds (kUSDT's `tokenValue`, 1.5 KAS): a send carries it to the
     *  recipient's new coin. */
    val kasPerCoinSompi: Long,
) {
    override fun equals(other: Any?): Boolean = other is WalletToken && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = symbol

    /** "12.5" -> "12.50": the amount with a dot, no grouping, at least two decimals for a dollar
     *  token. What a chat payment bubble's text carries, so the bubble can read it back. */
    fun plainAmount(units: Long): String = TokenAmount.typed(units, this, keepCents = isDollarStable)

    companion object {
        /** kUSDT: USDT from Ethereum and BNB Chain, held 1:1 in Trestly's vault and issued as a
         *  KCC-20 token on Kaspa L1 (kusdt-open, mainnet launched 2026-11-15). */
        val KUSDT = WalletToken(
            id = "kusdt",
            symbol = "kUSDT",
            name = "Bridged USDT",
            decimals = Kusdt.DECIMALS,
            isDollarStable = true,
            issuer = "Trestly",
            issuerSite = "https://trestly.io",
            backingUrl = "https://etherscan.io/address/0xfa9869982542FB20baD929789B252b7b06EeE10e",
            kasPerCoinSompi = 150_000_000L,
        )

        /** Every token the app knows, for reading a token id or symbol back (chat payment bubbles). */
        val ALL: List<WalletToken> = listOf(KUSDT)

        fun byId(id: String?): WalletToken? = ALL.firstOrNull { it.id == id }
    }
}

/** Turning token base units into text and back (iOS `TokenAmount`). */
object TokenAmount {
    /** "125.50", "0.25", "1,250", "0.000001": grouped the way this phone writes numbers, at least
     *  two decimals for a dollar token, trailing zeros past that dropped. */
    fun plain(units: Long, token: WalletToken): String =
        KaspaAmountGrouping.grouped(typed(units, token, keepCents = token.isDollarStable))

    /** "125.5" (or "125.50" with [keepCents]): no grouping, "." the decimal point - what the
     *  keypad types (Max). */
    fun typed(units: Long, token: WalletToken, keepCents: Boolean = false): String {
        val scale = pow10(token.decimals)
        val safe = if (units < 0) 0L else units
        val whole = safe / scale
        var frac = (safe % scale).toString().padStart(token.decimals, '0')
        val keep = if (keepCents) 2 else 0
        while (frac.length > keep && frac.endsWith("0")) frac = frac.dropLast(1)
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }

    /** "125.50 kUSDT". */
    fun amount(units: Long, token: WalletToken): String = "${plain(units, token)} ${token.symbol}"

    /** "$125.50" for a dollar token (two decimals, this phone's currency format), null for others. */
    fun dollars(units: Long, token: WalletToken): String? {
        if (!token.isDollarStable) return null
        val value = java.math.BigDecimal.valueOf(units).movePointLeft(token.decimals)
        return dollarFormat(Locale.getDefault()).format(value)
    }

    /** One formatter per language, not one per call (the Wallet card and Manage kUSDT redraw often). */
    @Volatile private var cachedDollarFormat: Pair<Locale, java.text.NumberFormat>? = null

    private fun dollarFormat(locale: Locale): java.text.NumberFormat {
        cachedDollarFormat?.let { (cachedLocale, format) -> if (cachedLocale == locale) return format.clone() as java.text.NumberFormat }
        val f = java.text.NumberFormat.getCurrencyInstance(locale)
        f.currency = java.util.Currency.getInstance("USD")
        f.maximumFractionDigits = 2
        f.minimumFractionDigits = 2
        cachedDollarFormat = locale to f
        // NumberFormat isn't thread-safe: each caller formats with its own copy
        return f.clone() as java.text.NumberFormat
    }

    /** Typed text ("12.5", "1,250.5") to base units; null for nothing, zero, more decimals than
     *  the token has, or more than fits. */
    fun unitsFromTyped(text: String, token: WalletToken): Long? {
        val clean = text.replace(",", "")
        if (clean.isEmpty()) return null
        val parts = clean.split(".")
        if (parts.size > 2) return null
        val wholeText = parts[0].ifEmpty { "0" }
        if (!wholeText.all { it in '0'..'9' }) return null
        var fracText = if (parts.size == 2) parts[1] else ""
        if (!fracText.all { it in '0'..'9' } || fracText.length > token.decimals) return null
        fracText += "0".repeat(token.decimals - fracText.length)
        val whole = wholeText.toLongOrNull() ?: return null
        val frac = if (fracText.isEmpty()) 0L else fracText.toLongOrNull() ?: return null
        val scaled = try { Math.multiplyExact(whole, pow10(token.decimals)) } catch (e: ArithmeticException) { return null }
        val total = try { Math.addExact(scaled, frac) } catch (e: ArithmeticException) { return null }
        return total.takeIf { it > 0 }
    }

    fun pow10(n: Int): Long {
        var v = 1L
        repeat(n) { v *= 10 }
        return v
    }
}

/**
 * One line of a token's activity (iOS `TokenActivity`). Kept on this phone per account and token
 * (Trestly's API keeps no history), newest first.
 */
data class TokenActivity(
    /** "txid:holding:kind" for what this phone records, so a send noted twice (a lost answer,
     *  then Try Again) is one line. */
    val id: String = java.util.UUID.randomUUID().toString(),
    /** [Kind.raw]: "received", "sent", "swapped", "bridged" or "combined". */
    val kind: String,
    val units: Long,
    /** The other side: an address or a name. For "combined", how many coins were merged. Empty
     *  for a receipt whose sender isn't known. */
    val counterparty: String,
    /** Epoch millis. */
    val date: Long,
    val pending: Boolean = false,
    /** Which of your addresses it happened on ([TokenHolding.id]). */
    val holdingId: String = TokenHolding.CHATTING_ID,
    /** The network fee (sompi), when this phone paid it (a send or Combine Coins). */
    val fee: Long? = null,
) {
    enum class Kind(val raw: String) { RECEIVED("received"), SENT("sent"), SWAPPED("swapped"), BRIDGED("bridged"), COMBINED("combined") }

    val kindValue: Kind? get() = Kind.values().firstOrNull { it.raw == kind }

    /** The transaction id, for a line this phone recorded ("txid:holding:kind"). */
    val txid: String?
        get() {
            val first = id.substringBefore(':')
            return first.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        }
}

/**
 * Where some of a token is held (iOS `TokenHolding`): the Chatting Address or one of the regular
 * (legacy, key) spending addresses. Never a quantum-safe address: sending a token from one
 * reveals its key (its KAS has to be spent with it), and Trestly's builder and pool don't take one.
 */
data class TokenHolding(
    /** Null: the Chatting Address; else the legacy spending index. */
    val spendingIndex: Int?,
    val address: String,
    val units: Long,
    /** Coins (UTXOs) holding it here: each holds the token's `kasPerCoinSompi` of KAS. */
    val coins: Int,
) {
    val id: String get() = spendingIndex?.let { "s$it" } ?: CHATTING_ID
    val isChatting: Boolean get() = spendingIndex == null

    companion object {
        const val CHATTING_ID = "chatting"
    }
}
