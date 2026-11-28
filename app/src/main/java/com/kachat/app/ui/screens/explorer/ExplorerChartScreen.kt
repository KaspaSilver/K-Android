package com.kachat.app.ui.screens.explorer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.explorer.ExplorerChartMetric
import com.kachat.app.services.explorer.ExplorerChartPick
import com.kachat.app.services.explorer.ExplorerChartPoint
import com.kachat.app.services.explorer.ExplorerDemo
import com.kachat.app.services.explorer.ExplorerKPeriod
import com.kachat.app.services.explorer.ExplorerKStats
import com.kachat.app.services.explorer.ExplorerNetworkStats
import com.kachat.app.services.explorer.runCatchingNonCancel
import com.kachat.app.ui.components.CycleCapsuleButton
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// The Explorer's full charts (iOS 961fc05a `ExplorerChartView`): tapping Hashrate, KAS Price, Block
// Reward, Market Cap or Network Fee on the Explorer's home opens its history. Hashrate is
// kaspa.stream's history, price the feed Portfolio uses (CoinGecko, then Gate.io), market cap that
// price times the supply as it stood then, block reward Kaspa's emission schedule, fees Kaspalytics'.

/**
 * One metric's history, full screen: the number (or the one under your finger), its change over
 * the range, the chart (drag to read any point), the range's high, low and average, and what the
 * number means.
 */
@Composable
internal fun ExplorerChartScreen(viewModel: ExplorerViewModel, metric: ExplorerChartMetric, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val periods = metric.periods
    var period by rememberSaveable { mutableStateOf(ExplorerKPeriod.MONTH) }
    // Held, not read here: only the headline and the chart read it, so a drag doesn't recompose
    // the whole screen (the summary, the Mining Estimate; iOS 01f71184, smoothness audit L7).
    val selection = remember { mutableStateOf<ExplorerChartPoint?>(null) }
    var points by remember { mutableStateOf<List<ExplorerChartPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** Where the fees chart's points came from (the credit under it, or the demo pill). */
    var feeSource by remember { mutableStateOf<ExplorerKStats.FeeSource?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val stats by viewModel.live.stats.collectAsState()

    LaunchedEffect(period, attempt) {
        selection.value = null
        loading = true
        val wanted = period
        val now = System.currentTimeMillis()
        val fresh: List<ExplorerChartPoint> = when (metric) {
            ExplorerChartMetric.HASHRATE -> runCatchingNonCancel { viewModel.api.hashrateHistory(wanted) }.orEmpty()
            ExplorerChartMetric.PRICE -> viewModel.api.priceHistory(wanted)
            ExplorerChartMetric.MARKET_CAP -> {
                val prices = viewModel.api.priceHistory(wanted)
                val supplyNow = (viewModel.live.stats.value?.circulatingSupply ?: 0L) / 100_000_000.0
                ExplorerDemo.marketCap(prices, supplyNow)
            }
            ExplorerChartMetric.NETWORK_FEES -> {
                // Kaspalytics' daily history; the demo last, and saying so - in debug builds only:
                // a release build says the chart couldn't load (iOS 01f71184, release audit R8)
                viewModel.kStats.refresh()
                val real = viewModel.kStats.feeSeries(wanted)
                if (real != null) {
                    feeSource = real.second
                    real.first
                } else if (ExplorerDemo.allowed) {
                    feeSource = ExplorerKStats.FeeSource.DEMO
                    ExplorerDemo.feeTotals(wanted, now)
                } else {
                    feeSource = null
                    emptyList()
                }
            }
            ExplorerChartMetric.BLOCK_REWARD -> ExplorerDemo.rewardSeries(wanted, now)
        }
        if (wanted != period) return@LaunchedEffect
        points = fresh
        loading = false
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ExplorerPageBar(stringResource(metric.title), onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = LocalDockInset.current + 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (metric == ExplorerChartMetric.NETWORK_FEES && feeSource == ExplorerKStats.FeeSource.DEMO) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) { DemoPill() }
            }
            ExplorerRedaction(redacted = points.isEmpty()) {
                Headline(metric, period, points, selection)
            }
            ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        when {
                            points.isEmpty() && loading -> IosActivityIndicator()
                            points.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.ex_chart_failed), color = colors.textSecondary, fontSize = 15.sp)
                                Text(
                                    stringResource(R.string.try_again),
                                    color = KaspaTeal,
                                    fontSize = 17.sp,
                                    modifier = Modifier.clickable { attempt++ },
                                )
                            }
                            else -> MetricChart(
                                metric = metric,
                                period = period,
                                points = points,
                                selection = selection,
                                modifier = Modifier.fillMaxSize().alpha(if (loading) 0.4f else 1f),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CycleCapsuleButton(
                            options = periods,
                            selection = period,
                            onSelect = { period = it },
                            title = { stringResource(it.title) },
                            hint = stringResource(R.string.ex_switches_period),
                        )
                    }
                }
            }
            ExplorerRedaction(redacted = points.isEmpty()) {
                Summary(metric, period, points)
            }
            if (metric == ExplorerChartMetric.HASHRATE) {
                // moved here from Portfolio's hashrate screen (owner, 2026-11-25)
                Box(Modifier.padding(horizontal = 16.dp)) { ExplorerMiningEstimate(stats) }
            }
            if (metric == ExplorerChartMetric.NETWORK_FEES && feeSource == ExplorerKStats.FeeSource.KASPALYTICS) {
                Text(
                    stringResource(R.string.ex_fees_credit),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
            Text(
                KaspaUnit.label(stringResource(metric.explanation)),
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
    }
}

/** Says the chart shows made-up numbers. */
@Composable
private fun DemoPill() {
    val orange = LocalAppColors.current.warning
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(orange.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Filled.Brush, contentDescription = null, tint = orange, modifier = Modifier.size(13.dp))
        Text(stringResource(R.string.ex_demo_data), color = orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun pattern(skeleton: String): SimpleDateFormat {
    val locale = com.kachat.app.util.fiatNumberLocale()
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** A bar's name: "Mon 3 PM", "Mon, Nov 24", "November 2025". */
private fun bucketName(period: ExplorerKPeriod, date: Long): String = when (period) {
    ExplorerKPeriod.DAY -> pattern("EEE j")
    ExplorerKPeriod.WEEK, ExplorerKPeriod.MONTH -> pattern("EEE MMM d")
    ExplorerKPeriod.ALL_TIME -> pattern("MMMM y")
}.format(Date(date))

/** An x axis label. */
private fun axisDate(period: ExplorerKPeriod, date: Long): String = when (period) {
    ExplorerKPeriod.DAY -> pattern("j")
    ExplorerKPeriod.WEEK -> pattern("EEE")
    ExplorerKPeriod.MONTH -> pattern("MMM d")
    ExplorerKPeriod.ALL_TIME -> pattern("y")
}.format(Date(date))

/** The point under the finger, as a date line: the day, with its time unless All Time. */
private fun pointDate(period: ExplorerKPeriod, date: Long): String {
    val locale = com.kachat.app.util.fiatNumberLocale()
    val format = if (period == ExplorerKPeriod.ALL_TIME) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
    else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale)
    return format.format(Date(date))
}

@Composable
private fun Headline(metric: ExplorerChartMetric, period: ExplorerKPeriod, points: List<ExplorerChartPoint>, selection: androidx.compose.runtime.State<ExplorerChartPoint?>) {
    val colors = LocalAppColors.current
    val selected = selection.value
    val shown = selected ?: points.lastOrNull()
    val first = points.firstOrNull()?.value ?: 0.0
    val change = if (first > 0) ((shown?.value ?: first) - first) / first * 100 else 0.0
    // bars: the period's total, or the bar under your finger
    val total = points.sumOf { it.value }
    val big = if (metric.isBars) ExplorerFormat.metric(metric, selected?.value ?: total)
    else shown?.let { ExplorerFormat.metric(metric, it.value) } ?: "–"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.explorerRedacted()) {
            KachatFitText(big, color = colors.textPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = Rounded, minScale = 0.5f)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.explorerRedacted()) {
            if (metric.isBars) {
                if (selected != null) {
                    Text(bucketName(period, selected.date), color = colors.textSecondary, fontSize = 15.sp)
                } else {
                    Text(stringResource(R.string.total), color = colors.textSecondary, fontSize = 15.sp)
                    Text(stringResource(period.title), color = colors.textSecondary, fontSize = 15.sp)
                }
            } else {
                Icon(if (change >= 0) Icons.Filled.NorthEast else Icons.Filled.SouthEast, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(15.dp))
                Text(ExplorerFormat.percent(change), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                Text(
                    if (selected != null) pointDate(period, selected.date) else stringResource(period.title),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/** "Nice" axis values across [low, high], about [count] of them. */
internal fun niceTicks(low: Double, high: Double, count: Int = 4): List<Double> {
    val span = high - low
    if (!span.isFinite() || span <= 0) return listOf(low)
    val rough = span / max(1, count - 1)
    val magnitude = 10.0.pow(floor(log10(rough)))
    val residual = rough / magnitude
    val step = magnitude * when {
        residual > 5 -> 10.0
        residual > 2 -> 5.0
        residual > 1 -> 2.0
        else -> 1.0
    }
    val first = ceil(low / step) * step
    val ticks = mutableListOf<Double>()
    var value = first
    while (value <= high + step * 1e-9 && ticks.size < 12) {
        ticks.add(value)
        value += step
    }
    return ticks
}

/**
 * The chart itself: an area under a line (steps for Block Reward), or bars from zero (Network
 * Fees); the value axis trailing, three dates under it; drag along it to read any point.
 */
@Composable
private fun MetricChart(
    metric: ExplorerChartMetric,
    period: ExplorerKPeriod,
    points: List<ExplorerChartPoint>,
    /** The point under the finger. Read only where it's drawn: the bars, and the rule and dot
     *  over a line, so a drag never rebuilds the line's path. */
    selection: androidx.compose.runtime.MutableState<ExplorerChartPoint?>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
    val values = points.map { it.value }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val (domainLow, domainHigh) = if (metric.isBars) {
        0.0 to (high * 1.08).coerceAtLeast(1e-12)
    } else {
        val pad = max((high - low) * 0.08, high * 0.0005)
        max(0.0, low - pad) to (high + pad).coerceAtLeast(max(0.0, low - pad) + 1e-12)
    }
    val ticks = niceTicks(domainLow, domainHigh).filter { it in domainLow..domainHigh }
    val tickLabels = ticks.map { ExplorerFormat.axisLabel(metric, it) }
    val axisWidth = with(density) {
        (tickLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0).toDp() + 6.dp
    }
    val start = points.first().date
    val end = points.last().date.coerceAtLeast(start + 1)
    val currentPoints by rememberUpdatedState(points)
    val title = stringResource(metric.title)

    fun pick(x: Float, plotWidth: Float) {
        val pts = currentPoints
        if (pts.isEmpty() || plotWidth <= 0) return
        val date = start + ((x / plotWidth).coerceIn(0f, 1f) * (end - start)).toLong()
        // the bar whose span holds the finger, else the nearest point (binary searches)
        val hit = if (metric.isBars) ExplorerChartPick.spanning(pts, date) else ExplorerChartPick.nearest(pts, date)
        if (hit != selection.value) {
            selection.value = hit
            haptic(IosHaptic.SELECTION)
        }
    }

    Column(modifier.semantics { contentDescription = title }) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(points, metric) {
                    val plotWidth = size.width - axisWidth.toPx()
                    // touch and drag along the chart to read any point; letting go clears it
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x, plotWidth)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            pick(change.position.x, plotWidth)
                            change.consume()
                        }
                        selection.value = null
                    }
                },
        ) {
            val plotWidth = size.width - axisWidth.toPx()
            val plotHeight = size.height
            fun x(date: Long) = ((date - start).toDouble() / (end - start) * plotWidth).toFloat()
            fun y(value: Double) = (plotHeight - (value - domainLow) / (domainHigh - domainLow) * plotHeight).toFloat()
            // grid lines and the value axis
            ticks.forEachIndexed { i, tick ->
                val ty = y(tick)
                drawLine(colors.textSecondary.copy(alpha = 0.15f), Offset(0f, ty), Offset(plotWidth, ty), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(tickLabels[i], labelStyle)
                drawText(layout, topLeft = Offset(plotWidth + 6.dp.toPx(), (ty - layout.size.height / 2f).coerceIn(0f, plotHeight - layout.size.height)))
            }
            if (metric.isBars) {
                val slot = plotWidth / points.size
                val barWidth = (slot * 0.72f).coerceAtLeast(1f)
                // the bars dim around the one under the finger; there are only a few dozen
                val selected = selection.value
                points.forEachIndexed { i, point ->
                    val top = y(point.value)
                    val alpha = if (selected == null || selected == point) 1f else 0.35f
                    drawRoundRect(
                        KaspaTeal.copy(alpha = alpha),
                        topLeft = Offset(i * slot + (slot - barWidth) / 2, top),
                        size = Size(barWidth, (plotHeight - top).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            } else {
                val line = Path()
                points.forEachIndexed { i, point ->
                    val px = x(point.date)
                    val py = y(point.value)
                    when {
                        i == 0 -> line.moveTo(px, py)
                        metric.isStepped -> {
                            // steps: level until the next point, then the change
                            line.lineTo(px, y(points[i - 1].value))
                            line.lineTo(px, py)
                        }
                        else -> {
                            val prevX = x(points[i - 1].date)
                            val prevY = y(points[i - 1].value)
                            val midX = (prevX + px) / 2
                            line.cubicTo(midX, prevY, midX, py, px, py)
                        }
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(points.last().date), plotHeight)
                    lineTo(x(points.first().date), plotHeight)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(KaspaTeal.copy(alpha = 0.35f), KaspaTeal.copy(alpha = 0.02f)), startY = 0f, endY = plotHeight))
                drawPath(line, KaspaTeal, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // The dashed rule and dot under the finger, drawn over the line: only this redraws as the
        // finger moves (iOS 01f71184 `ExplorerChartScrub`).
        if (!metric.isBars) {
            Canvas(Modifier.matchParentSize()) {
                val point = selection.value ?: return@Canvas
                val plotWidth = size.width - axisWidth.toPx()
                val plotHeight = size.height
                val sx = ((point.date - start).toDouble() / (end - start) * plotWidth).toFloat()
                val sy = (plotHeight - (point.value - domainLow) / (domainHigh - domainLow) * plotHeight).toFloat()
                drawLine(
                    colors.textSecondary.copy(alpha = 0.5f),
                    Offset(sx, 0f),
                    Offset(sx, plotHeight),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
                drawCircle(KaspaTeal, radius = 5.dp.toPx(), center = Offset(sx, sy))
            }
        }
      }
        // three dates under the plot
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = axisWidth)) {
            val marks = listOf(start, start + (end - start) / 2, end)
            marks.forEachIndexed { i, date ->
                Text(
                    axisDate(period, date),
                    style = labelStyle,
                    maxLines = 1,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        1 -> TextAlign.Center
                        else -> TextAlign.End
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Summary(metric: ExplorerChartMetric, period: ExplorerKPeriod, points: List<ExplorerChartPoint>) {
    val colors = LocalAppColors.current
    // the bar still filling (this hour, today, this month) would pull Low and Average down
    val values = (if (metric.isBars) points.dropLast(1) else points).map { it.value }
    val average = if (values.isEmpty()) 0.0 else values.sum() / values.size
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(stringResource(R.string.photo_quality_high), ExplorerFormat.metric(metric, values.maxOrNull() ?: 0.0), Modifier.weight(1f))
            SummaryTile(stringResource(R.string.photo_quality_low), ExplorerFormat.metric(metric, values.minOrNull() ?: 0.0), Modifier.weight(1f))
            SummaryTile(stringResource(R.string.ex_average), ExplorerFormat.metric(metric, average), Modifier.weight(1f))
        }
        if (metric.isBars) {
            Text(
                stringResource(
                    when (period) {
                        ExplorerKPeriod.DAY -> R.string.ex_per_hour
                        ExplorerKPeriod.ALL_TIME -> R.string.kp_mining_per_month
                        else -> R.string.kp_mining_per_day
                    }
                ),
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun SummaryTile(title: String, value: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.explorerRedacted())
        Box(Modifier.explorerRedacted()) {
            KachatFitText(value, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, minScale = 0.6f)
        }
    }
}

/**
 * "If I point this much hashrate at Kaspa, what do I earn?" (iOS `ExplorerMiningEstimate`, moved
 * from Portfolio's hashrate screen to the Explorer's hashrate chart, owner 2026-11-25). Straight
 * proportional share: your hashrate over the network's, times what the network pays out a day
 * (block reward x 10 blocks a second). Deliberately no pool fee, power cost or luck.
 */
@Composable
private fun ExplorerMiningEstimate(stats: ExplorerNetworkStats?) {
    val colors = LocalAppColors.current
    // Miners talk in TH/s (one KS5 Pro is about 21), so that is the default. The unit is part of
    // the input because typing 21 and meaning PH/s is a thousandfold error.
    val units = listOf("GH/s" to 1e9, "TH/s" to 1e12, "PH/s" to 1e15)
    // empty, not a sample figure: a prefilled number reads as YOUR earnings
    var amountText by rememberSaveable { mutableStateOf("") }
    var unitIndex by rememberSaveable { mutableIntStateOf(1) }
    val network = stats?.hashrate?.takeIf { it > 0 }
    val rewardKas = stats?.blockReward?.let { it / 100_000_000.0 }?.takeIf { it > 0 }
    val amount = com.kachat.app.util.DecimalInputFormat.value(amountText)
    val dailyKas = if (network != null && rewardKas != null && amount != null && amount > 0) {
        rewardKas * 10 * 86_400 * (amount * units[unitIndex].second) / network
    } else {
        null
    }
    val price = stats?.priceUSD
    ExplorerCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.kp_mining_estimate_f1a76), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val style = TextStyle(color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.textPrimary.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (amountText.isEmpty()) Text("0", style = style.copy(color = colors.textTertiary))
                    BasicTextField(
                        value = amountText,
                        // grouped as you type: "1200000" is a number you'd have to count
                        onValueChange = { amountText = com.kachat.app.util.DecimalInputFormat.grouped(it) },
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SegmentedPicker(units.map { it.first }, unitIndex, { unitIndex = it }, Modifier.width(190.dp))
            }
            if (dailyKas != null) {
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayoutRow(stringResource(R.string.kp_mining_per_day), dailyKas, price)
                    HorizontalDivider(color = colors.divider)
                    PayoutRow(stringResource(R.string.kp_mining_per_week), dailyKas * 7, price)
                    HorizontalDivider(color = colors.divider)
                    // 30 days, not a calendar month: the reward steps down monthly anyway
                    PayoutRow(stringResource(R.string.kp_mining_per_month), dailyKas * 30, price)
                }
                Text(
                    stringResource(
                        R.string.kp_mining_estimate_basis,
                        ExplorerFormat.hashrate(network!!),
                        "${String.format(Locale.US, "%.4f", rewardKas!!)} ${KaspaUnit.symbol}",
                    ),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            } else {
                Text(stringResource(R.string.kp_enter_your_hashrate_to_estimate_earnings_90f5d), color = colors.textSecondary, fontSize = 15.sp)
            }
        }
    }
}

/** iOS's segmented Picker: the segments on a grey track, the chosen one on a raised thumb. */
@Composable
private fun SegmentedPicker(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val track = Color(0xFF767680).copy(alpha = if (colors.isDark) 0.24f else 0.12f)
    val thumb = if (colors.isDark) Color(0xFF636366) else Color.White
    Row(modifier.clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) thumb else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PayoutRow(title: String, kas: Double, price: Double?) {
    val colors = LocalAppColors.current
    val amount = java.text.NumberFormat.getNumberInstance(com.kachat.app.util.fiatNumberLocale()).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 4
    }.format(kas)
    Row(Modifier.fillMaxWidth()) {
        Text(title, color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f).alignByBaseline())
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.alignByBaseline()) {
            Text("$amount ${KaspaUnit.symbol}", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            if (price != null && price > 0) {
                Text(ExplorerFormat.usdValue(kas * price), color = colors.textSecondary, fontSize = 12.sp)
            }
        }
    }
}
