package com.kachat.app.services.explorer

import androidx.annotation.StringRes
import com.kachat.app.R
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// K's own block explorer (iOS 961fc05a, owner 2026-11-25): blocks, transactions and addresses inside
// the app, so nothing opens a website. kaspa.stream's REST API ([ExplorerApi]) for search and every
// page, the newest blocks live from K's own node ([ExplorerLive]), K Activity from K's indexer and
// the network's fees from Kaspalytics ([ExplorerKStats]). Amounts are sompi in a Long (the whole
// supply fits with room to spare); times are epoch milliseconds.

/** What a transaction is, as K reads it: Kaspa's plain kinds and K's own protocols (iOS `ExplorerTxKind`). */
enum class ExplorerTxKind(
    @StringRes val title: Int,
    /** The kind counted ("KaChat Messages"), for K's activity card. */
    @StringRes val plural: Int,
    /** What the explorer can say about it without anyone's keys. */
    @StringRes val explanation: Int,
) {
    PAYMENT(R.string.ex_kind_payment, R.string.ex_kind_payments, R.string.ex_kind_payment_about),
    KACHAT_MESSAGE(R.string.ex_kind_message, R.string.ex_kind_messages, R.string.ex_kind_message_about),
    HANDSHAKE(R.string.ex_kind_handshake, R.string.ex_kind_handshakes, R.string.ex_kind_handshake_about),
    PAYMENT_NOTICE(R.string.ex_kind_payment_notice, R.string.ex_kind_payment_notices, R.string.ex_kind_payment_notice_about),
    KAPOST(R.string.ex_kind_kapost, R.string.ex_kind_kaposts, R.string.ex_kind_kapost_about),
    /** A message in a public chat room (`kchat:1:bcast:`), readable by anyone. */
    PUBLIC_CHAT(R.string.ex_kind_public_chat, R.string.ex_kind_public_chats, R.string.ex_kind_public_chat_about),
    KACHAT_NAME(R.string.ex_kind_username, R.string.ex_kind_usernames, R.string.ex_kind_username_about),
    KUSDT_TRANSFER(R.string.ex_kind_kusdt, R.string.ex_kind_kusdts, R.string.ex_kind_kusdt_about),
    KUSDT_SWAP(R.string.ex_kind_swap, R.string.ex_kind_swaps, R.string.ex_kind_swap_about),
    CHESS_MOVE(R.string.ex_kind_chess, R.string.ex_kind_chesses, R.string.ex_kind_chess_about),
    ARCADE_STAMP(R.string.ex_kind_arcade, R.string.ex_kind_arcades, R.string.ex_kind_arcade_about),
    COINBASE(R.string.ex_kind_coinbase, R.string.ex_kind_coinbases, R.string.ex_kind_coinbase_about),
    OTHER(R.string.ex_kind_other, R.string.ex_kind_others, R.string.ex_kind_other_about);

    /** One of K's own protocols (the K Activity page lists only these). */
    val isK: Boolean get() = this != PAYMENT && this != COINBASE && this != OTHER
}

data class ExplorerIO(val index: Int, val address: String, val amount: Long)

data class ExplorerTransaction(
    val id: String,
    val kind: ExplorerTxKind,
    val time: Long,
    /** null while it's in the mempool. */
    val blockHash: String?,
    val confirmations: Int,
    /** null when the inputs' amounts aren't known (a transaction read from a block). */
    val fee: Long?,
    val mass: Long,
    val inputs: List<ExplorerIO>,
    val outputs: List<ExplorerIO>,
    val payloadHex: String,
    /** The kUSDT it moved, in base units (6 decimals). */
    val tokenUnits: Long?,
    /** A public post's text, a room message: what K can show of the payload (its own page only). */
    val decoded: String?,
    /**
     * Whether the network accepted it; null when not known (a block seconds old, read off the
     * node). A block a miner finds is merged by many later blocks, each carrying a coinbase paying
     * for it: only the chain block's is accepted, the rest moved nothing.
     */
    val isAccepted: Boolean? = null,
) {
    val isNotAccepted: Boolean get() = isAccepted == false
    val isInMempool: Boolean get() = blockHash == null

    /**
     * What it moved, change excluded: the outputs that don't go back to the first input's address.
     * Null when that can't be told (audit 2026-11-25 C4): a transaction read off the node has no
     * input addresses, so its change can't be picked out and its total would count it (a 1 KAS
     * payment from a 10,000 KAS coin would read about 10,000). One output has no change.
     */
    val movedAmount: Long?
        get() {
            val addressesKnown = inputs.isEmpty() || inputs.any { it.address.isNotEmpty() }
            if (!addressesKnown) return if (outputs.size == 1) outputs[0].amount else null
            val from = inputs.firstOrNull()?.address
            val away = outputs.filter { it.address != from }
            // a self-send (a message, a post) moves its first output's amount
            if (away.isEmpty()) return outputs.firstOrNull()?.amount ?: 0L
            return away.fold(0L) { sum, io -> ExplorerMath.add(sum, io.amount) }
        }

    /** What it did to [address]: what came to it less what it spent, as a direction and an amount
     *  (an address page's rows and its Received / Sent). */
    fun net(address: String): Pair<Boolean, Long> {
        val into = outputs.filter { it.address == address }.fold(0L) { s, io -> ExplorerMath.add(s, io.amount) }
        val out = inputs.filter { it.address == address }.fold(0L) { s, io -> ExplorerMath.add(s, io.amount) }
        return if (into >= out) true to (into - out) else false to (out - into)
    }
}

data class ExplorerBlock(
    val id: String,
    val blueScore: Long,
    val daaScore: Long,
    val time: Long,
    val isChainBlock: Boolean,
    val difficulty: Double,
    val parents: List<String>,
    val children: List<String>,
    val mergeSetBlues: Int,
    val mergeSetReds: Int,
    val transactionIds: List<String>,
    /** The block's transactions, when they came with it. */
    val transactions: List<ExplorerTransaction> = emptyList(),
)

data class ExplorerAddress(
    val address: String,
    val username: String?,
    val balance: Long,
    val kusdtUnits: Long?,
    val received30d: Long,
    val sent30d: Long,
    /** Whether the history loaded reaches back 30 days, so received / sent are complete. */
    val summaryCoversMonth: Boolean = true,
    /** Every transaction the address was in. */
    val transactionCount: Int? = null,
    val transactions: List<ExplorerTransaction>,
    /** Where the next page of history starts; null when there is none. */
    val nextPageBefore: Long? = null,
)

data class ExplorerNetworkStats(
    val daaScore: Long,
    val blueScore: Long,
    val blocksPerSecond: Double,
    /** Hashes per second. */
    val hashrate: Double,
    val mempoolSize: Int,
    /** Sompi per gram of mass. */
    val normalFeerate: Double,
    val priorityFeerate: Double,
    /** The average fee a transaction paid over the last 24 hours, in sompi. */
    val averageFee24h: Long,
    val circulatingSupply: Long,
    val maxSupply: Long,
    val priceUSD: Double,
    /** New KAS a block pays its miner, in sompi. It steps down every month (halving yearly). */
    val blockReward: Long,
    /** When the reward takes its next monthly step down. */
    val nextRewardDrop: Long,
) {
    companion object {
        /** Before kaspa.stream's first answer: drawn as placeholders. */
        val EMPTY = ExplorerNetworkStats(0, 0, 0.0, 0.0, 0, 0.0, 0.0, 0, 0, 1, 0.0, 0, 0)
    }
}

/** The windows K's activity card and the charts cycle through (owner, 2026-11-25). */
enum class ExplorerKPeriod(
    /** The cycle button's label. */
    @StringRes val title: Int,
    /** What the change compares with; all time has nothing before it. */
    @StringRes val comparison: Int?,
    /** The bar chart's two ends. */
    @StringRes val chartStart: Int,
    @StringRes val chartEnd: Int,
    /** What one bar is, for TalkBack. */
    @StringRes val barMeaning: Int,
    /** `/stats/activity?period=`'s name for it. */
    val apiName: String,
) {
    DAY(R.string.ex_period_day, R.string.ex_vs_day, R.string.ex_bars_day_start, R.string.ex_bars_now, R.string.ex_bars_per_hour, "day"),
    WEEK(R.string.ex_period_week, R.string.ex_vs_week, R.string.ex_bars_week_start, R.string.ex_bars_today, R.string.ex_bars_per_day, "week"),
    MONTH(R.string.ex_period_month, R.string.ex_vs_month, R.string.ex_bars_month_start, R.string.ex_bars_today, R.string.ex_bars_per_day, "month"),
    ALL_TIME(R.string.ex_period_all, null, R.string.ex_bars_launch, R.string.ex_bars_today, R.string.ex_bars_per_month, "all"),
}

/** Everything K put on chain in a window: each of its protocols' transactions. */
data class ExplorerKActivity(
    val period: ExplorerKPeriod,
    /** Transactions per kind, K's own kinds only. */
    val counts: Map<ExplorerTxKind, Int>,
    /** Transactions per bar (hour, day or month), oldest first, the last one still going. Null
     *  until K's indexer serves the series (`/stats/activity`). */
    val bars: List<Int>? = null,
    /** The window before this one, for the change; null for all time or when not served. */
    val previousTotal: Int? = null,
    /** Addresses that sent at least one K transaction; null when not served. */
    val activeAddresses: Int? = null,
    /** Network fees K's transactions paid, in sompi; null when not served. */
    val fees: Long? = null,
    /** Made-up numbers (shown only until K's indexer first answers). */
    val isDemo: Boolean = false,
) {
    val total: Int get() = counts.values.fold(0) { s, v -> ExplorerMath.addInt(s, v) }

    /** Percent up (or down) on the window before. */
    val change: Double?
        get() {
            val previous = previousTotal ?: return null
            if (previous <= 0) return null
            return (total.toDouble() - previous) / previous * 100
        }

    /** Kinds with their counts, busiest first. */
    val ranked: List<Pair<ExplorerTxKind, Int>> get() = counts.toList().sortedByDescending { it.second }
}

/** A number the Explorer can chart (iOS `ExplorerChartMetric`). */
enum class ExplorerChartMetric(
    @StringRes val title: Int,
    /** What the number is, under the chart. */
    @StringRes val explanation: Int,
) {
    HASHRATE(R.string.ex_hashrate, R.string.ex_chart_about_hashrate),
    PRICE(R.string.kp_kas_price_bb9bb, R.string.ex_chart_about_price),
    BLOCK_REWARD(R.string.kp_block_reward, R.string.ex_chart_about_reward),
    MARKET_CAP(R.string.kp_market_cap_d5533, R.string.ex_chart_about_market_cap),
    /** Total fees paid, a bar per day or month (the tile shows a transaction's average fee). */
    NETWORK_FEES(R.string.ex_network_fees, R.string.ex_chart_about_fees);

    /** The reward changes in steps, the others move freely. */
    val isStepped: Boolean get() = this == BLOCK_REWARD

    /** Totals per hour, day or month, drawn as bars; the headline is the period's total. */
    val isBars: Boolean get() = this == NETWORK_FEES

    /** Network Fees has no 24 Hours: Kaspalytics' totals are daily, and K's indexer doesn't count
     *  fees (owner, 2026-11-25). */
    val periods: List<ExplorerKPeriod>
        get() = if (this == NETWORK_FEES) listOf(ExplorerKPeriod.WEEK, ExplorerKPeriod.MONTH, ExplorerKPeriod.ALL_TIME)
        else ExplorerKPeriod.entries
}

data class ExplorerChartPoint(val date: Long, val value: Double)

/** What the explorer can open: a page each. Written as text so the page stack survives in saved state. */
sealed class ExplorerRoute {
    data class Transaction(val id: String) : ExplorerRoute()
    data class Block(val hash: String) : ExplorerRoute()
    data class Address(val address: String) : ExplorerRoute()
    /** A home tile's full chart. */
    data class Chart(val metric: ExplorerChartMetric) : ExplorerRoute()

    fun encode(): String = when (this) {
        is Transaction -> "tx:$id"
        is Block -> "block:$hash"
        is Address -> "address:$address"
        is Chart -> "chart:${metric.name}"
    }

    companion object {
        fun decode(text: String): ExplorerRoute? {
            val kind = text.substringBefore(':')
            val rest = text.substringAfter(':', "")
            if (rest.isEmpty()) return null
            return when (kind) {
                "tx" -> Transaction(rest)
                "block" -> Block(rest)
                "address" -> Address(rest)
                "chart" -> ExplorerChartMetric.entries.firstOrNull { it.name == rest }?.let { Chart(it) }
                else -> null
            }
        }
    }
}

/** What a typed search turns out to be. */
sealed class ExplorerSearchMatch {
    data class Route(val route: ExplorerRoute) : ExplorerSearchMatch()
    data object NotFound : ExplorerSearchMatch()
}

/** Never-wrapping arithmetic for numbers from APIs and nodes. */
object ExplorerMath {
    fun add(a: Long, b: Long): Long {
        val sum = a + b
        // both non-negative here; a wrap shows as a negative sum
        return if (a >= 0 && b >= 0 && sum < 0) Long.MAX_VALUE else sum
    }

    fun addInt(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * A Double from an API as a non-negative Long, never trapping: NaN and negatives are 0,
     * anything past Long's range is its largest value (audit 2026-11-25 C11).
     */
    fun clampedLong(value: Double): Long {
        if (!value.isFinite() || value <= 0) return 0
        if (value >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return floor(value).toLong()
    }

    /** KAS from an API as sompi, never trapping. */
    fun sompi(fromKas: Double): Long = clampedLong(floor(fromKas * 100_000_000 + 0.5))
}

// MARK: - Demo data

/**
 * What K shows while a number isn't served yet (iOS `ExplorerDemo`): K Activity until K's indexer
 * first answers, and the fee chart when Kaspalytics doesn't answer, each saying so. Also the
 * emission schedule the Block Reward chart draws, which is real, and the market cap worked back
 * from a price history.
 */
object ExplorerDemo {
    /** A deterministic generator: the same seed, the same numbers (FNV-1a, then xorshift). */
    class Seeded(seed: String) {
        private var state: ULong

        init {
            var h = 0xcbf29ce484222325UL
            for (b in seed.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toUByte().toULong())
                h *= 0x100000001b3UL
            }
            state = if (h == 0UL) 1UL else h
        }

        fun next(): ULong {
            state = state xor (state shl 13)
            state = state xor (state shr 7)
            state = state xor (state shl 17)
            return state
        }

        fun int(range: IntRange): Int {
            val count = (range.last - range.first + 1).toULong()
            return range.first + (next() % count).toInt()
        }
    }

    /** When the demo's counts start creeping, as a live feed would. */
    val launchedAt: Long = System.currentTimeMillis()

    /** Kaspa's mainnet launch, where All Time starts (2021-11-08). */
    const val MAINNET_LAUNCH = 1_636_329_600_000L
    /** Ten blocks a second from here (Crescendo, 2025-05-05). */
    const val CRESCENDO = 1_746_403_200_000L
    /** The chromatic emission phase (2022-05-03): 440 KAS a second, falling every month to halve each year. */
    const val CHROMATIC_START = 1_651_536_000_000L

    private const val DAY_MS = 86_400_000L

    /** The demo's average fee a transaction paid, in sompi (until a real one is known). */
    fun averageFee24h(now: Long): Long {
        val wobble = sin(now / 1000.0 / 7)
        return 31_400L + (max(0.0, wobble) * 1_800).toLong()
    }

    /** K's activity in [period]. Counts creep up while the screen is open, as a live feed would. */
    fun kActivity(period: ExplorerKPeriod, now: Long): ExplorerKActivity {
        val elapsed = max(0L, (now - launchedAt) / 1000).toInt()
        // a day's worth of each kind
        val base = mapOf(
            ExplorerTxKind.KACHAT_MESSAGE to 61_204.0, ExplorerTxKind.HANDSHAKE to 1_318.0,
            ExplorerTxKind.PAYMENT_NOTICE to 2_406.0, ExplorerTxKind.KAPOST to 8_917.0,
            ExplorerTxKind.KACHAT_NAME to 264.0, ExplorerTxKind.KUSDT_TRANSFER to 3_752.0,
            ExplorerTxKind.KUSDT_SWAP to 941.0, ExplorerTxKind.CHESS_MOVE to 12_480.0,
            ExplorerTxKind.ARCADE_STAMP to 1_633.0,
        )
        // per second, roughly each kind's share of the day
        val perSecond = mapOf(
            ExplorerTxKind.KACHAT_MESSAGE to 0.7, ExplorerTxKind.HANDSHAKE to 0.015,
            ExplorerTxKind.PAYMENT_NOTICE to 0.03, ExplorerTxKind.KAPOST to 0.1,
            ExplorerTxKind.KACHAT_NAME to 0.003, ExplorerTxKind.KUSDT_TRANSFER to 0.04,
            ExplorerTxKind.KUSDT_SWAP to 0.01, ExplorerTxKind.CHESS_MOVE to 0.14,
            ExplorerTxKind.ARCADE_STAMP to 0.02,
        )
        // how many days' worth each window holds (all time: K's history, growing)
        val days = when (period) {
            ExplorerKPeriod.DAY -> 1.0
            ExplorerKPeriod.WEEK -> 6.7
            ExplorerKPeriod.MONTH -> 27.4
            ExplorerKPeriod.ALL_TIME -> 212.0
        }
        val counts = base.mapValues { (kind, perDay) -> (perDay * days).toInt() + (elapsed * (perSecond[kind] ?: 0.0)).toInt() }
        val r = Seeded("k-${iosRaw(period)}")
        val bars: List<Int> = when (period) {
            ExplorerKPeriod.DAY -> {
                // quieter overnight (UTC), busier in the evening
                val hourly = (0 until 24).map { hour ->
                    val curve = 0.55 + 0.45 * sin((hour - 6).toDouble() / 24 * 2 * Math.PI)
                    ((3_200 + r.int(0..900)) * curve).toInt() + 1_400
                }.toMutableList()
                hourly[23] = 900 + elapsed / 2
                hourly
            }
            ExplorerKPeriod.WEEK, ExplorerKPeriod.MONTH -> {
                val count = if (period == ExplorerKPeriod.WEEK) 7 else 30
                val daily = (0 until count).map { index ->
                    // a slow climb with weekend dips
                    val trend = 78_000 + index * (if (period == ExplorerKPeriod.WEEK) 1_900 else 520)
                    val weekend = if (index % 7 == 5 || index % 7 == 6) 0.86 else 1.0
                    ((trend + r.int(-6_000..6_000)) * weekend).toInt()
                }.toMutableList()
                daily[count - 1] = 41_000 + elapsed
                daily
            }
            ExplorerKPeriod.ALL_TIME -> {
                // monthly since launch: a fast start, then steady growth
                val monthly = (0 until 14).map { index ->
                    val growth = 1 - exp(-(index + 1).toDouble() / 4)
                    (2_400_000 * growth).toInt() + r.int(-90_000..90_000)
                }.toMutableList()
                monthly[13] = 2_050_000 + elapsed * 5
                monthly
            }
        }
        val previous = when (period) {
            ExplorerKPeriod.DAY -> 85_310
            ExplorerKPeriod.WEEK -> 584_900
            ExplorerKPeriod.MONTH -> 2_212_000
            ExplorerKPeriod.ALL_TIME -> null
        }
        val addresses = when (period) {
            ExplorerKPeriod.DAY -> 9_874
            ExplorerKPeriod.WEEK -> 31_206
            ExplorerKPeriod.MONTH -> 74_532
            ExplorerKPeriod.ALL_TIME -> 212_418
        }
        return ExplorerKActivity(
            period = period,
            counts = counts,
            bars = bars,
            previousTotal = previous,
            activeAddresses = addresses + elapsed / 20,
            fees = (2_418 * days).toLong() * 100_000_000L + elapsed.toLong() * 2_000_000L,
            isDemo = true,
        )
    }

    /** iOS's raw value, which seeds the demo: the same numbers on both phones. */
    private fun iosRaw(period: ExplorerKPeriod): String = when (period) {
        ExplorerKPeriod.DAY -> "day"
        ExplorerKPeriod.WEEK -> "week"
        ExplorerKPeriod.MONTH -> "month"
        ExplorerKPeriod.ALL_TIME -> "allTime"
    }

    /** The Block Reward chart over [period], oldest first: Kaspa's emission schedule. */
    fun rewardSeries(period: ExplorerKPeriod, now: Long): List<ExplorerChartPoint> {
        val (start, count) = when (period) {
            ExplorerKPeriod.DAY -> (now - DAY_MS) to 96
            ExplorerKPeriod.WEEK -> (now - 7 * DAY_MS) to 168
            ExplorerKPeriod.MONTH -> (now - 30 * DAY_MS) to 120
            ExplorerKPeriod.ALL_TIME -> MAINNET_LAUNCH to 200
        }
        val step = (now - start).toDouble() / (count - 1)
        return (0 until count).map { i ->
            val date = start + (i * step).toLong()
            ExplorerChartPoint(date, rewardPerBlock(date))
        }
    }

    /**
     * Fees paid per hour (24 hours), day (7 and 30 days) or month (all time), in KAS, oldest
     * first; each point's date is its bucket's start, the last bucket still filling. Made up:
     * only when Kaspalytics doesn't answer, and the chart says so.
     */
    fun feeTotals(period: ExplorerKPeriod, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<ExplorerChartPoint> {
        val r = Seeded("fees-${iosRaw(period)}")
        val elapsed = max(0L, now - launchedAt) / 1000.0
        val calendar = java.util.Calendar.getInstance(zone)
        when (period) {
            ExplorerKPeriod.DAY -> {
                calendar.timeInMillis = now
                calendar.set(java.util.Calendar.MINUTE, 0); calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
                val hour = calendar.timeInMillis
                return (0 until 24).map { index ->
                    val start = hour + (index - 23) * 3_600_000L
                    calendar.timeInMillis = start
                    val h = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                    // quieter overnight (UTC), busier in the evening, as K's activity
                    val curve = 0.55 + 0.45 * sin((h - 6).toDouble() / 24 * 2 * Math.PI)
                    var kas = (26 + r.int(0..80) / 10.0) * curve + 6
                    if (index == 23) kas = kas * min(1.0, (now - start) / 3_600_000.0) + elapsed * 0.0004
                    ExplorerChartPoint(start, kas)
                }
            }
            ExplorerKPeriod.WEEK, ExplorerKPeriod.MONTH -> {
                val days = if (period == ExplorerKPeriod.WEEK) 7 else 30
                calendar.timeInMillis = now
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
                calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
                val today = calendar.timeInMillis
                return (0 until days).map { index ->
                    calendar.timeInMillis = today
                    calendar.add(java.util.Calendar.DAY_OF_MONTH, index - (days - 1))
                    val start = calendar.timeInMillis
                    val weekday = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                    val weekend = if (weekday == java.util.Calendar.SUNDAY || weekday == java.util.Calendar.SATURDAY) 0.84 else 1.0
                    var kas = (560 + index * 4 + r.int(-60..60)).toDouble() * weekend
                    if (index == days - 1) kas = kas * min(1.0, (now - start) / DAY_MS.toDouble()) + elapsed * 0.0004
                    ExplorerChartPoint(start, kas)
                }
            }
            ExplorerKPeriod.ALL_TIME -> {
                // monthly since mainnet: tiny at first, then the 2024 surge, then K's traffic
                val points = mutableListOf<ExplorerChartPoint>()
                calendar.timeInMillis = MAINNET_LAUNCH
                calendar.set(java.util.Calendar.DAY_OF_MONTH, 1); calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calendar.set(java.util.Calendar.MINUTE, 0); calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
                while (calendar.timeInMillis <= now) {
                    val month = calendar.timeInMillis
                    val years = (month - MAINNET_LAUNCH) / (365.0 * DAY_MS)
                    val surge = 1 / (1 + exp(-(years - 2.6) * 3))
                    val kas = 40 + 15_500 * surge * (1 + r.int(-120..120) / 1000.0)
                    points.add(ExplorerChartPoint(month, kas))
                    calendar.add(java.util.Calendar.MONTH, 1)
                }
                points.removeLastOrNull()?.let { last ->
                    val share = min(1.0, (now - last.date) / (30.44 * DAY_MS))
                    points.add(ExplorerChartPoint(last.date, last.value * share))
                }
                return points
            }
        }
    }

    /**
     * Market cap from a price history: each price times the supply as it stood then, worked back
     * from today's supply along the emission schedule.
     */
    fun marketCap(prices: List<ExplorerChartPoint>, supplyNow: Double, now: Long = System.currentTimeMillis()): List<ExplorerChartPoint> {
        if (supplyNow <= 0 || prices.isEmpty()) return emptyList()
        var supply = supplyNow
        val result = ArrayList<ExplorerChartPoint>(prices.size)
        var later = now
        for (point in prices.asReversed()) {
            // KAS mined between this point and the next: the emission rate (reward x blocks a second)
            val mid = point.date + (later - point.date) / 2
            val perSecond = rewardPerBlock(mid) * (if (mid < CRESCENDO) 1 else 10)
            supply -= perSecond * max(0L, later - point.date) / 1000.0
            result.add(ExplorerChartPoint(point.date, point.value * max(supply, 0.0)))
            later = point.date
        }
        return result.asReversed()
    }

    /** KAS a block pays: 500 a second before the chromatic phase, then 440 falling by 2^(1/12)
     *  every month; one block a second before Crescendo, ten after. */
    fun rewardPerBlock(date: Long): Double {
        val perSecond = if (date < CHROMATIC_START) {
            500.0
        } else {
            val months = floor((date - CHROMATIC_START) / (30.44 * DAY_MS))
            440 * 0.5.pow(months / 12)
        }
        return if (date < CRESCENDO) perSecond else perSecond / 10
    }
}
