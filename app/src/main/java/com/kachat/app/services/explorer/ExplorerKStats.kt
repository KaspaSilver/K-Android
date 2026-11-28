package com.kachat.app.services.explorer

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.capResponseBodies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * K Activity from K's indexer, and the network's fees from Kaspalytics (iOS 961fc05a
 * `ExplorerKStats`; owner, 2026-11-25: keep what the indexer tracks small and use everything else).
 * `GET /stats` counts K's protocols for the last 24 hours, 7 days and all time; `GET
 * /stats/activity?period=` adds the window before and the bars (K-Digital-Freedom-Indexer
 * docs/EXPLORER_STATS.md), used as soon as it's served. Whatever isn't served is left off the card.
 * Its state is read and written on the main thread.
 */
@Singleton
class ExplorerKStats @Inject constructor(
    private val settings: AppSettingsRepository,
) {
    /** Each period's numbers, as last fetched. */
    private val _activity = MutableStateFlow<Map<ExplorerKPeriod, ExplorerKActivity>>(emptyMap())
    val activity: StateFlow<Map<ExplorerKPeriod, ExplorerKActivity>> = _activity.asStateFlow()
    private val _periods = MutableStateFlow(CARD_PERIODS)
    val periods: StateFlow<List<ExplorerKPeriod>> = _periods.asStateFlow()

    /** Kaspalytics' daily fees (owner, 2026-11-25): every day since October 2023, all of Kaspa,
     *  in KAS. Its undocumented chart API, refreshed hourly on their side; mainnet only. */
    private val _dailyFees = MutableStateFlow<List<ExplorerChartPoint>>(emptyList())
    val dailyFees: StateFlow<List<ExplorerChartPoint>> = _dailyFees.asStateFlow()
    /** Kaspalytics' average fee per transaction on the last full day, in sompi. */
    private val _lastDayAverageFee = MutableStateFlow<Long?>(null)
    val lastDayAverageFee: StateFlow<Long?> = _lastDayAverageFee.asStateFlow()

    private var fetchedAt = 0L
    /** `/stats/activity` answered 404: not served by this indexer, so not asked again this
     *  session. Any other failure only waits an hour ([richRetryAt]; audit 2026-11-25 C11). */
    private var richUnavailable = false
    private var richRetryAt = 0L
    private var kaspalyticsAt = 0L
    /** The network these numbers are for (audit 2026-11-25 C5). */
    private var network: KaspaNetwork.Type? = null
    private var generation = 0

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .capResponseBodies("K Activity") { request ->
            if (request.url.host.endsWith("kaspalytics.com")) 4L * 1024 * 1024 else 2L * 1024 * 1024
        }
        .build()

    /** Where a fee number came from, so the screen can say so. */
    enum class FeeSource { INDEXER, KASPALYTICS, DEMO }

    /** The fees chart's points for [period] (7 Days, 30 Days, All Time), all from Kaspalytics. */
    fun feeSeries(period: ExplorerKPeriod): Pair<List<ExplorerChartPoint>, FeeSource>? {
        val daily = _dailyFees.value
        if (daily.isEmpty()) return null
        return when (period) {
            ExplorerKPeriod.DAY -> null
            ExplorerKPeriod.WEEK -> daily.takeLast(7) to FeeSource.KASPALYTICS
            ExplorerKPeriod.MONTH -> daily.takeLast(30) to FeeSource.KASPALYTICS
            ExplorerKPeriod.ALL_TIME -> ExplorerKStatsParsing.monthly(daily) to FeeSource.KASPALYTICS
        }
    }

    /** At most once a minute (Kaspalytics at most once an hour). Main thread. */
    suspend fun refresh() {
        resetIfNetworkChanged()
        val now = System.currentTimeMillis()
        if (now - fetchedAt <= 60_000) return
        fetchedAt = now
        if (KaspaNetwork.launch == KaspaNetwork.Type.MAINNET && now - kaspalyticsAt > 3_600_000) loadKaspalytics()
        val base = runCatchingNonCancel { settings.indexerUrl.first() }?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return
        loadActivity(base)
    }

    /** Forgets everything fetched for another network: mainnet's fees and K Activity must not show
     *  on testnet, nor the other way round. */
    fun resetIfNetworkChanged() {
        val current = KaspaNetwork.launch
        val previous = network
        network = current
        if (previous == null || previous == current) return
        generation++
        _activity.value = emptyMap()
        _periods.value = CARD_PERIODS
        fetchedAt = 0L
        richUnavailable = false
        richRetryAt = 0L
        _dailyFees.value = emptyList()
        _lastDayAverageFee.value = null
        kaspalyticsAt = 0L
    }

    private suspend fun loadKaspalytics() {
        val started = generation
        val base = "https://www.kaspalytics.com/api/charts/transactions/accepted/fees"
        val total = fetch("$base/total").first ?: return
        val points = withContext(Dispatchers.Default) { ExplorerKStatsParsing.chartSeries(total, "Fees") }
        if (points.isEmpty() || started != generation) return
        _dailyFees.value = points.sortedBy { it.date }
        val average = fetch("$base/average").first
        if (average != null && started == generation) {
            ExplorerKStatsParsing.lastValue(average, "Fee")?.let { _lastDayAverageFee.value = ExplorerMath.sompi(it) }
        }
        kaspalyticsAt = System.currentTimeMillis()
    }

    private suspend fun loadActivity(base: String) {
        val started = generation
        if (!richUnavailable && System.currentTimeMillis() >= richRetryAt) {
            val rich = mutableMapOf<ExplorerKPeriod, ExplorerKActivity>()
            var notServed = false
            for (period in CARD_PERIODS) {
                val (json, status) = fetch("$base/stats/activity?period=${period.apiName}")
                val value = json?.let { ExplorerKStatsParsing.activity(it, period) }
                if (value == null) {
                    notServed = status == 404
                    break
                }
                rich[period] = value
            }
            if (started != generation) return
            if (rich.size == CARD_PERIODS.size) {
                _activity.value = rich
                _periods.value = CARD_PERIODS
                return
            }
            // not served (yet): /stats; asked again next session after a 404, else in an hour
            if (notServed) richUnavailable = true else richRetryAt = System.currentTimeMillis() + 3_600_000
        }
        val stats = fetch("$base/stats").first ?: return
        if (started != generation) return
        val next = ExplorerKStatsParsing.stats(stats)
        if (next.isEmpty()) return
        _activity.value = next
        _periods.value = CARD_PERIODS.filter { next[it] != null }
    }

    /** The answer, or null with the HTTP status when there was one (null status: no answer at all). */
    private suspend fun fetch(url: String): Pair<JsonElement?, Int?> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null to response.code
                val body = response.body?.string() ?: return@use null to response.code
                (runCatching { JsonParser.parseString(body) }.getOrNull()) to response.code
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null to null
        }
    }

    companion object {
        /** K Activity's periods: 24 Hours, 7 Days and All Time (owner, 2026-11-25: no 30 Days). */
        val CARD_PERIODS: List<ExplorerKPeriod> = listOf(ExplorerKPeriod.DAY, ExplorerKPeriod.WEEK, ExplorerKPeriod.ALL_TIME)
    }
}

/** The pure parts of [ExplorerKStats]: reading `/stats`, `/stats/activity` and Kaspalytics' charts. */
object ExplorerKStatsParsing {
    /**
     * `/stats` keys -> what the card calls them. `selfStash` and `groupUpdates` are machinery (a
     * handshake's saved copy, group control), not left out by accident.
     */
    val CATEGORIES: List<Pair<String, ExplorerTxKind>> = listOf(
        "messages" to ExplorerTxKind.KACHAT_MESSAGE, "groupMessages" to ExplorerTxKind.KACHAT_MESSAGE,
        "handshakes" to ExplorerTxKind.HANDSHAKE, "payments" to ExplorerTxKind.PAYMENT,
        "paymentNotices" to ExplorerTxKind.PAYMENT_NOTICE, "kaposts" to ExplorerTxKind.KAPOST,
        "kapostActions" to ExplorerTxKind.KAPOST, "publicChats" to ExplorerTxKind.PUBLIC_CHAT,
        "chessMoves" to ExplorerTxKind.CHESS_MOVE, "kachatNames" to ExplorerTxKind.KACHAT_NAME,
        "kusdtTransfers" to ExplorerTxKind.KUSDT_TRANSFER, "kusdtSwaps" to ExplorerTxKind.KUSDT_SWAP,
        "arcadeScores" to ExplorerTxKind.ARCADE_STAMP,
    )

    fun kinds(values: Map<String, Int>): Map<ExplorerTxKind, Int> {
        val counts = mutableMapOf<ExplorerTxKind, Int>()
        for ((key, kind) in CATEGORIES) {
            // a server's numbers never wrap, and never count down (iOS d88891ea, audit 2026-11-27 L4)
            values[key]?.let { counts[kind] = ExplorerMath.addInt(counts[kind] ?: 0, maxOf(0, it)) }
        }
        return counts
    }

    private fun int(element: JsonElement?): Int? =
        ExplorerParsing.long(element)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

    private fun intMap(element: JsonElement?): Map<String, Int>? {
        val o = element as? JsonObject ?: return null
        val out = mutableMapOf<String, Int>()
        for ((key, value) in o.entrySet()) {
            // one key that isn't a count makes the whole answer unreadable, as iOS's decoder
            out[key] = int(value) ?: return null
        }
        return out
    }

    /** `/stats/activity?period=` (proposed): one period, complete; null when it doesn't read. */
    fun activity(json: JsonElement, period: ExplorerKPeriod): ExplorerKActivity? {
        val o = json as? JsonObject ?: return null
        val categories = intMap(o.get("categories")) ?: return null
        val previousElement = o.get("previous")
        val previous = if (previousElement == null || previousElement.isJsonNull) null else (intMap(previousElement) ?: return null)
        val seriesElement = o.get("series")
        val bars: List<Int>? = if (seriesElement == null || seriesElement.isJsonNull) null else {
            val array = seriesElement as? JsonArray ?: return null
            val parsed = array.map { bar ->
                val b = bar as? JsonObject ?: return null
                val t = ExplorerParsing.double(b.get("t")) ?: return null
                val count = int(b.get("count")) ?: return null
                t to count
            }
            parsed.sortedBy { it.first }.map { it.second }
        }
        return ExplorerKActivity(
            period = period,
            counts = kinds(categories),
            bars = bars,
            previousTotal = previous?.let { kinds(it).values.fold(0) { s, v -> ExplorerMath.addInt(s, v) } },
        )
    }

    /** `/stats`: 24 Hours, 7 Days and All Time from each category's counts; the windows with
     *  nothing served are left out. */
    fun stats(json: JsonElement): Map<ExplorerKPeriod, ExplorerKActivity> {
        val categories = (json as? JsonObject)?.get("categories") as? JsonObject ?: return emptyMap()
        fun window(field: String): Map<ExplorerTxKind, Int>? {
            val values = mutableMapOf<String, Int>()
            for ((key, counts) in categories.entrySet()) {
                int((counts as? JsonObject)?.get(field))?.let { values[key] = it }
            }
            return if (values.isEmpty()) null else kinds(values)
        }
        val next = mutableMapOf<ExplorerKPeriod, ExplorerKActivity>()
        window("last24h")?.let { next[ExplorerKPeriod.DAY] = ExplorerKActivity(ExplorerKPeriod.DAY, it) }
        window("last7d")?.let { next[ExplorerKPeriod.WEEK] = ExplorerKActivity(ExplorerKPeriod.WEEK, it) }
        window("total")?.let { next[ExplorerKPeriod.ALL_TIME] = ExplorerKActivity(ExplorerKPeriod.ALL_TIME, it) }
        return next
    }

    private fun dataset(json: JsonElement, label: String): Pair<List<String>, List<Double?>>? {
        val o = json as? JsonObject ?: return null
        val labels = (o.get("labels") as? JsonArray)?.map { ExplorerParsing.string(it) ?: "" } ?: return null
        val set = (o.get("datasets") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { ExplorerParsing.string(it.get("label")) == label } ?: return null
        val data = (set.get("data") as? JsonArray)?.map { if (it.isJsonNull) null else ExplorerParsing.double(it) } ?: return null
        return labels to data
    }

    /** A Kaspalytics chart's [label] dataset as points (days with no value left out). */
    fun chartSeries(json: JsonElement, label: String): List<ExplorerChartPoint> {
        val (labels, data) = dataset(json, label) ?: return emptyList()
        return labels.zip(data).mapNotNull { (text, value) ->
            val date = kaspalyticsDate(text) ?: return@mapNotNull null
            value?.let { ExplorerChartPoint(date, it) }
        }
    }

    /** A Kaspalytics chart's [label] dataset's last value. */
    fun lastValue(json: JsonElement, label: String): Double? =
        dataset(json, label)?.second?.lastOrNull { it != null }

    /** Kaspalytics' labels: ISO 8601 with or without fractional seconds, or a plain day (audit
     *  2026-11-25 C11). Epoch milliseconds. */
    fun kaspalyticsDate(label: String): Long? {
        val text = label.trim()
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        return null
    }

    /** Daily totals summed into calendar months (UTC), for All Time. */
    fun monthly(daily: List<ExplorerChartPoint>): List<ExplorerChartPoint> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val sums = linkedMapOf<Long, Double>()
        for (point in daily) {
            calendar.timeInMillis = point.date
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
            val month = calendar.timeInMillis
            sums[month] = (sums[month] ?: 0.0) + point.value
        }
        return sums.map { ExplorerChartPoint(it.key, it.value) }.sortedBy { it.date }
    }
}
