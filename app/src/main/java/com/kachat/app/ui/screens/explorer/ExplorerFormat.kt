package com.kachat.app.ui.screens.explorer

import android.content.Context
import android.icu.text.CompactDecimalFormat
import com.kachat.app.R
import com.kachat.app.services.explorer.ExplorerChartMetric
import com.kachat.app.services.explorer.ExplorerTransaction
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.util.KaspaUnit
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** How the Explorer writes its numbers (iOS 961fc05a `ExplorerFormat`). */
object ExplorerFormat {
    private fun locale(): Locale = com.kachat.app.util.fiatNumberLocale()

    fun grouped(value: Long): String = NumberFormat.getIntegerInstance(locale()).apply { isGroupingUsed = true }.format(value)

    /** An address split into two even lines. */
    fun twoLines(text: String): String {
        if (text.length <= 30) return text
        val half = (text.length + 1) / 2
        return text.substring(0, half) + "\n" + text.substring(half)
    }

    /** "kaspa:qzkw...q6w9", "a805f2…9eb9ae". */
    fun short(text: String): String {
        if (text.length <= 18) return text
        val colon = text.indexOf(':')
        val prefix = if (colon >= 0) text.substring(0, colon + 1) else ""
        val body = if (colon >= 0) text.substring(colon + 1) else text
        if (body.length <= 14) return text
        // an address reads "kaspa:qzkw...q6w9" (owner, 2026-11-25); ids keep six each side
        if (prefix.isNotEmpty()) return "$prefix${body.take(4)}...${body.takeLast(4)}"
        return "${body.take(6)}…${body.takeLast(6)}"
    }

    /** "12 secs. ago", "just now" under five seconds. */
    fun ago(context: Context, date: Long, now: Long): String {
        val millis = (now - date).coerceAtLeast(0)
        if (millis < 5_000) return context.getString(R.string.ex_just_now)
        return android.text.format.DateUtils.getRelativeTimeSpanString(
            date.coerceAtMost(now), now, android.text.format.DateUtils.SECOND_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }

    fun hashrate(hashesPerSecond: Double): String {
        val units = listOf("H/s", "kH/s", "MH/s", "GH/s", "TH/s", "PH/s", "EH/s", "ZH/s")
        var value = hashesPerSecond
        var index = 0
        while (value >= 1000 && index < units.size - 1) {
            value /= 1000
            index++
        }
        return String.format(Locale.US, "%.2f %s", value, units[index])
    }

    fun difficulty(value: Double): String = String.format(Locale.US, "%.3e", value)

    private fun decimal(value: Double, minFraction: Int, maxFraction: Int): String =
        NumberFormat.getNumberInstance(locale()).apply {
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
        }.format(value)

    /** A block reward: 2 to 4 decimals ("1.9446 KAS"). */
    fun rewardKAS(sompi: Long): String = "${decimal(sompi / 100_000_000.0, 2, 4)} ${KaspaUnit.symbol}"

    fun compact(value: Double, maxFraction: Int = 2): String =
        CompactDecimalFormat.getInstance(locale(), CompactDecimalFormat.CompactStyle.SHORT).apply {
            maximumFractionDigits = maxFraction
        }.format(value)

    fun compactUSD(dollars: Double): String = "$" + compact(dollars)

    /** "26.18B KAS". */
    fun compactKAS(sompi: Long): String = "${compact(sompi / 100_000_000.0)} ${KaspaUnit.symbol}"

    /** "6 days", "1 day" or "hours" until [date]. */
    fun daysUntil(context: Context, date: Long, now: Long): String {
        val days = ((date - now).coerceAtLeast(0) / 86_400_000L).toInt()
        return when (days) {
            0 -> context.getString(R.string.ex_hours)
            1 -> context.getString(R.string.ex_one_day)
            else -> context.getString(R.string.ex_n_days, days)
        }
    }

    fun usdValue(dollars: Double): String =
        NumberFormat.getCurrencyInstance(locale()).apply { currency = Currency.getInstance("USD") }.format(dollars)

    /** [sompi] in dollars at [price]. */
    fun usd(sompi: Long, price: Double): String = usdValue(sompi / 100_000_000.0 * price)

    /** "35 TKAS", "0.2 TKAS": exact, trailing zeros dropped (iOS `KaspaUnit.amount`). */
    fun kas(sompi: Long): String = "${KaspaUnit.plain(sompi)} ${KaspaUnit.symbol}"

    /** What a transaction moved: kUSDT for a token transaction, else KAS; "–" when it can't be
     *  told (a transaction read off the node, whose change isn't known). */
    fun amountLine(tx: ExplorerTransaction, short: Boolean = false): String {
        tx.tokenUnits?.let { return TokenAmount.amount(it, WalletToken.KUSDT) }
        val sompi = tx.movedAmount ?: return "–"
        return if (short) shortKAS(sompi) else kas(sompi)
    }

    /** A row's amount: "1.25 KAS", "12.3K KAS". */
    fun shortKAS(sompi: Long): String {
        val kas = sompi / 100_000_000.0
        val text = if (kas >= 1000) compact(kas, 1) else decimal(kas, 0, 2)
        return "$text ${KaspaUnit.symbol}"
    }

    /** "4.2%", "15.1%", "27,743%": big changes lose their decimals. */
    fun percent(change: Double): String {
        val size = kotlin.math.abs(change)
        return decimal(size, if (size >= 100) 0 else 1, if (size >= 100) 0 else 1) + "%"
    }

    /** A chart's number in full. */
    fun metric(metric: ExplorerChartMetric, value: Double): String = when (metric) {
        ExplorerChartMetric.HASHRATE -> hashrate(value)
        ExplorerChartMetric.PRICE -> if (value < 0.01) String.format(Locale.US, "$%.5f", value) else String.format(Locale.US, "$%.4f", value)
        ExplorerChartMetric.BLOCK_REWARD -> "${decimal(value, 2, 4)} ${KaspaUnit.symbol}"
        ExplorerChartMetric.MARKET_CAP -> compactUSD(value)
        // a single fee is tiny, a month's total is thousands
        ExplorerChartMetric.NETWORK_FEES ->
            if (value >= 1) "${decimal(value, 1, 1)} ${KaspaUnit.symbol}" else "${decimal(value, 2, 6)} ${KaspaUnit.symbol}"
    }

    /** Shorter, for the chart's axis. */
    fun axisLabel(metric: ExplorerChartMetric, value: Double): String {
        if (value == 0.0) return if (metric == ExplorerChartMetric.PRICE || metric == ExplorerChartMetric.MARKET_CAP) "$0" else "0"
        return when (metric) {
            ExplorerChartMetric.HASHRATE -> hashrate(value)
            ExplorerChartMetric.PRICE -> String.format(Locale.US, if (value < 0.01) "$%.4f" else "$%.3f", value)
            ExplorerChartMetric.BLOCK_REWARD -> decimal(value, 0, 2)
            ExplorerChartMetric.MARKET_CAP -> compactUSD(value)
            ExplorerChartMetric.NETWORK_FEES -> compact(value, 1)
        }
    }

    /** A detail page's time: date and time to the second, as this phone writes them. */
    fun dateTime(ms: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM, locale()).format(java.util.Date(ms))
}
