package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------------------------
// Kaspa Hub > KaChat Stats - KaChat's transactions on Kaspa by kind (iOS f38cac2, b8dd56f). Back
// since iOS 148a5331 (phase 5), restored from KaChat-Android d6ff6f17^.
// ---------------------------------------------------------------------------------------------

/** One kind of KaChat transaction the stats screen counts. [key] is the key in the indexer's
 *  `GET /stats` response (STATS_INDEXER.md), so it is part of that contract. */
enum class KaChatStatCategory(val key: String, val title: Int, val detail: Int, val icon: ImageVector, val color: Color) {
    MESSAGES("messages", R.string.shell_ks_messages, R.string.shell_ks_messages_detail, Icons.Default.Forum, Color(0xFF0A84FF)),
    HANDSHAKES("handshakes", R.string.shell_ks_handshakes, R.string.shell_ks_handshakes_detail, Icons.Default.WavingHand, Color(0xFF64D2FF)),
    PAYMENTS("payments", R.string.shell_ks_payments, R.string.shell_ks_payments_detail, Icons.AutoMirrored.Filled.Send, Color(0xFF30D158)),
    GROUP_MESSAGES("groupMessages", R.string.shell_ks_group_messages, R.string.shell_ks_group_messages_detail, Icons.Default.Groups, Color(0xFF5E5CE6)),
    GROUP_UPDATES("groupUpdates", R.string.shell_ks_group_updates, R.string.shell_ks_group_updates_detail, Icons.Default.PersonAdd, Color(0xFF40C8E0)),
    PUBLIC_CHATS("publicChats", R.string.shell_ks_public_chats, R.string.shell_ks_public_chats_detail, Icons.Default.Sensors, Color(0xFFFF9F0A)),
    KAPOSTS("kaposts", R.string.shell_ks_kaposts, R.string.shell_ks_kaposts_detail, Icons.Default.ChatBubble, Color(0xFFFF375F)),
    KAPOST_ACTIONS("kapostActions", R.string.shell_ks_kapost_actions, R.string.shell_ks_kapost_actions_detail, Icons.Default.ThumbUp, Color(0xFFBF5AF2)),
    CHESS_MOVES("chessMoves", R.string.shell_ks_chess_moves, R.string.shell_ks_chess_moves_detail, Icons.Default.GridOn, Color(0xFFAC8E68)),
    CHESS_GAMES("chessGames", R.string.shell_ks_chess_games, R.string.shell_ks_chess_games_detail, Icons.Default.EmojiEvents, Color(0xFFFFD60A)),
    // .kachat names (registry transactions, iOS b2d108b). An indexer reports them only where names
    // are live, so on a network without the registry yet (mainnet before launch) they stay hidden.
    KACHAT_REGISTRATIONS("kachatRegistrations", R.string.shell_ks_kachat_registrations, R.string.shell_ks_kachat_registrations_detail, KachatSymbols.AtBadgePlus, Color(0xFF63E6E2)),
    KACHAT_RENEWALS("kachatRenewals", R.string.shell_ks_kachat_renewals, R.string.shell_ks_kachat_renewals_detail, Icons.Default.ChangeCircle, Color(0xFF008C80)),
    KACHAT_SALES("kachatSales", R.string.shell_ks_kachat_sales, R.string.shell_ks_kachat_sales_detail, Icons.Default.ShoppingCart, Color(0xFFFF453A)),
    KACHAT_OFFERS("kachatOffers", R.string.shell_ks_kachat_offers, R.string.shell_ks_kachat_offers_detail, Icons.Default.PanTool, Color(0xFFD98C1A)),
    KACHAT_ACTIVITY("kachatActivity", R.string.shell_ks_kachat_activity, R.string.shell_ks_kachat_activity_detail, Icons.Default.LocalOffer, Color(0xFF7373BF)),
    SELF_STASH("selfStash", R.string.shell_ks_self_stash, R.string.shell_ks_self_stash_detail, Icons.Default.Inventory2, Color(0xFF8E8E93));

    /** One of the .kachat registry kinds - shown together as the .kachat Domains card (iOS 4924787). */
    val isKachat: Boolean
        get() = this == KACHAT_REGISTRATIONS || this == KACHAT_RENEWALS || this == KACHAT_SALES ||
            this == KACHAT_OFFERS || this == KACHAT_ACTIVITY
}

enum class KaChatStatRange(val title: Int, val caption: Int) {
    DAY(R.string.shell_ks_range_day, R.string.shell_ks_caption_day),
    WEEK(R.string.shell_ks_range_week, R.string.shell_ks_caption_week),
    ALL(R.string.shell_ks_range_all, R.string.shell_ks_caption_all),
}

/** One category's counts as an indexer reports them. Every field is optional: a server that
 *  only keeps an all-time counter still works, the shorter ranges just show a dash. */
data class KaChatStatCounts(val total: Long?, val last24h: Long?, val last7d: Long?) {
    fun value(range: KaChatStatRange): Long? = when (range) {
        KaChatStatRange.DAY -> last24h
        KaChatStatRange.WEEK -> last7d
        KaChatStatRange.ALL -> total
    }
}

/** Merges indexers' `GET /stats` `categories`: a category comes from the first response (in
 *  settings order) that reports it; keys this app doesn't know are skipped. */
fun mergeKaChatStatCategories(responses: List<JsonObject>): Map<KaChatStatCategory, KaChatStatCounts> {
    val counts = mutableMapOf<KaChatStatCategory, KaChatStatCounts>()
    for (response in responses) {
        val categories = response.get("categories")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        for ((key, value) in categories.entrySet()) {
            val category = KaChatStatCategory.entries.firstOrNull { it.key == key } ?: continue
            if (category in counts || !value.isJsonObject) continue
            val obj = value.asJsonObject
            fun long(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            counts[category] = KaChatStatCounts(long("total"), long("last24h"), long("last7d"))
        }
    }
    return counts
}

data class KaChatStatsSnapshot(
    val counts: Map<KaChatStatCategory, KaChatStatCounts>,
    val updatedAt: Long?,
    val indexedSince: Long?,
    val fetchedAt: Long,
)

/**
 * Fetches and holds the stats. A singleton so switching tabs and coming back does not refetch: a
 * snapshot younger than a minute is reused unless the user pulls to refresh (iOS KaChatStatsModel).
 */
@Singleton
class KaChatStatsStore @Inject constructor(private val settings: AppSettingsRepository) {
    enum class Failure { NO_INDEXER, UNAVAILABLE }

    private val _snapshot = MutableStateFlow<KaChatStatsSnapshot?>(null)
    val snapshot: StateFlow<KaChatStatsSnapshot?> = _snapshot.asStateFlow()
    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var sourceKey = ""
    private val gson = Gson()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun refresh(force: Boolean) {
        val bases = indexerBases()
        val key = bases.joinToString("|")
        if (key != sourceKey) {
            // Another network or indexer: the old numbers aren't this server's.
            sourceKey = key
            _snapshot.value = null
            _failure.value = null
        }
        if (bases.isEmpty()) {
            _failure.value = Failure.NO_INDEXER
            return
        }
        val current = _snapshot.value
        if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < FRESH_FOR_MS) return
        if (_loading.value) return
        _loading.value = true
        try {
            val fetched = fetch(bases)
            if (key != sourceKey) return
            if (fetched != null) {
                _snapshot.value = fetched
                _failure.value = null
            } else {
                // Keep showing the last numbers, if there are any; the screen says they're stale.
                _failure.value = Failure.UNAVAILABLE
            }
        } finally {
            _loading.value = false
        }
    }

    /** Every KaChat indexer this network is configured with, deduplicated - by default they are
     *  all the same server, so this is one request. */
    private suspend fun indexerBases(): List<String> {
        val seen = mutableSetOf<String>()
        // iOS: indexerURL, publicChatIndexerURL, kaPostIndexerURL. Public Chats follow the
        // message indexer here (PublicChatSettings), so it is listed once.
        return listOf(settings.indexerUrl.first(), settings.kapostIndexerUrl.first())
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** Asks each indexer for `/stats` and merges: a category comes from the first indexer (in
     *  settings order) that reports it. Null when none of them reports a category this app knows. */
    private suspend fun fetch(bases: List<String>): KaChatStatsSnapshot? = coroutineScope {
        val responses = bases.map { base -> async { fetchOne(base) } }.mapNotNull { it.await() }
        val counts = mergeKaChatStatCategories(responses)
        if (counts.isEmpty()) return@coroutineScope null
        fun long(obj: JsonObject, name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        KaChatStatsSnapshot(
            counts = counts,
            updatedAt = responses.mapNotNull { long(it, "updatedAt") }.maxOrNull(),
            indexedSince = responses.mapNotNull { long(it, "indexedSince") }.minOrNull(),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchOne(base: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$base/stats").build()).execute().use { response ->
                if (!response.isSuccessful) null
                else gson.fromJson(response.body?.string(), JsonObject::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val FRESH_FOR_MS = 60_000L
    }
}

@HiltViewModel
class KaChatStatsViewModel @Inject constructor(
    val store: KaChatStatsStore,
    /** The .kachat registry, for the Domains card (iOS f5d34f4). */
    private val kachatRegistry: com.kachat.app.services.kachatnames.KachatNamesRegistry,
) : ViewModel() {
    /** .kachat registry transactions and what they paid (sompi), over 24 hours, 7 days and all
     *  time - from the registry itself (its indexer, or the chain), so it shows wherever names
     *  are live (iOS 4924787). */
    private val _kachatTransactions = MutableStateFlow<com.kachat.app.services.kachatnames.KachatNamesRegistry.Totals?>(null)
    val kachatTransactions: StateFlow<com.kachat.app.services.kachatnames.KachatNamesRegistry.Totals?> = _kachatTransactions.asStateFlow()
    private val _kachatFees = MutableStateFlow<com.kachat.app.services.kachatnames.KachatNamesRegistry.Totals?>(null)
    val kachatFees: StateFlow<com.kachat.app.services.kachatnames.KachatNamesRegistry.Totals?> = _kachatFees.asStateFlow()
    private val _kachatLoaded = MutableStateFlow(false)
    val kachatLoaded: StateFlow<Boolean> = _kachatLoaded.asStateFlow()

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            store.refresh(force)
            loadKachat()
        }
    }

    suspend fun refreshAndAwait() {
        store.refresh(force = true)
        loadKachat()
    }

    private suspend fun loadKachat() {
        if (!KachatLive.isEnabled) {
            _kachatTransactions.value = null
            _kachatFees.value = null
            _kachatLoaded.value = true
            return
        }
        try {
            kachatRegistry.refreshIfStale()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            val totals = kachatRegistry.activityTotals()
            _kachatTransactions.value = totals.first
            _kachatFees.value = totals.second
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // keeps what it showed
        }
        _kachatLoaded.value = true
    }
}

/** KaChat Stats' two tabs (iOS 4924787). */
private enum class KaChatStatsPage { TRANSACTIONS, FEES }

/** iOS Color.mint: the .kachat Domains card. */
private val KachatDomainsMint = Color(0xFF00C7BE)

/**
 * Kaspa Hub > KaChat Stats: how many transactions KaChat has put on Kaspa, split by kind. The
 * numbers are the indexers' (`GET /stats`), never this phone's own traffic. A category no indexer
 * reports is left out rather than shown as zero; while loading, or when the indexer has no stats
 * yet, the cards keep their real names with the numbers blanked, so nothing invented is on screen.
 * Each kind is a card with its 24 Hours, 7 Days and All Time figures; .kachat's five kinds are the
 * one .kachat Domains card, and the Fees tab shows what .kachat transactions paid (iOS 4924787).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaChatStatsScreen(onBack: (() -> Unit)?, viewModel: KaChatStatsViewModel = hiltViewModel()) {
    if (onBack != null) BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    val snapshot by viewModel.store.snapshot.collectAsState()
    val failure by viewModel.store.failure.collectAsState()
    val loading by viewModel.store.loading.collectAsState()
    val kachatTransactions by viewModel.kachatTransactions.collectAsState()
    val kachatFees by viewModel.kachatFees.collectAsState()
    val kachatLoaded by viewModel.kachatLoaded.collectAsState()
    var page by remember { mutableStateOf(KaChatStatsPage.TRANSACTIONS) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh(force = false) }

    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            viewModel.refreshAndAwait()
            pullState.endRefresh()
        }
    }
    // The kinds with a card of their own. .kachat's five are one card, .kachat Domains.
    val chatCategories = KaChatStatCategory.entries.filterNot { it.isKachat }
    // The .kachat Domains counts: the registry's own, else the indexer's five .kachat kinds
    // added up.
    val kachatTotals = kachatTransactions ?: snapshot?.let { snap ->
        val counts = KaChatStatCategory.entries.filter { it.isKachat }.mapNotNull { snap.counts[it] }
        // saturating: an indexer's numbers must never wrap Stats (iOS 428a797b, audit 2026-11-21 #20)
        fun sum(value: (KaChatStatCounts) -> Long?): Long =
            counts.fold(0L) { acc, c -> com.kachat.app.services.kachatnames.KachatNames.clampedSum(acc, (value(c) ?: 0L).coerceAtLeast(0L)) }
        if (counts.isEmpty()) null
        else com.kachat.app.services.kachatnames.KachatNamesRegistry.Totals(
            last24h = sum { it.last24h },
            last7d = sum { it.last7d },
            total = sum { it.total },
        )
    }
    // Every KaChat transaction, all time: each kind's all-time count plus .kachat's. Saturating,
    // and a negative count is none (iOS 428a797b, audit 2026-11-21 #20).
    val allTimeTotal: Long? = run {
        var sum = 0L
        var any = false
        snapshot?.let { snap ->
            for (category in chatCategories) {
                snap.counts[category]?.total?.let {
                    sum = com.kachat.app.services.kachatnames.KachatNames.clampedSum(sum, it.coerceAtLeast(0L))
                    any = true
                }
            }
        }
        kachatTotals?.let {
            sum = com.kachat.app.services.kachatnames.KachatNames.clampedSum(sum, it.total.coerceAtLeast(0L))
            any = true
        }
        if (any) sum else null
    }

    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            // iOS's bar: the connection dot on glass, the balance centred (the "KaChat Stats" title
            // gives way to it) and "?" on a glass disc.
            HubSectionTopBar(onBack = onBack) {
                ToolbarIconButton(
                    Icons.Outlined.HelpOutline,
                    stringResource(R.string.shell_ks_what_counts),
                    onClick = { showInfo = true },
                    iconSize = HubHelpIconSize,
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(pullState.nestedScrollConnection)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalDockInset.current + 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Hero. The gaps are the iPhone's as drawn: the chart 35 below the bar's glass, the
                // count's digits 24 under it and the caption 20 under them.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatsChartGlyph(Modifier.padding(top = 11.dp))
                    Spacer(Modifier.height(12.dp))
                    when {
                        allTimeTotal != null -> KachatFitText(
                            kaChatStatsGrouped(allTimeTotal.coerceAtLeast(0L)),
                            color = colors.textPrimary,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = IosRoundedFontFamily,
                            minScale = 0.5f,
                        )
                        snapshot != null || failure != null -> Text("—", color = colors.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, fontFamily = IosRoundedFontFamily)
                        else -> Box(Modifier.padding(vertical = 8.dp)) { StatsRedacted(180, 40) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.shell_ks_caption_all), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                HubUnderlineTabBar(
                    titles = listOf(stringResource(R.string.transactions), stringResource(R.string.shell_ks_fees)),
                    selectedIndex = page.ordinal,
                    onSelect = { page = KaChatStatsPage.entries[it] },
                )
                when (page) {
                    KaChatStatsPage.TRANSACTIONS -> {
                        val snap = snapshot
                        if (snap != null) {
                            chatCategories.filter { snap.counts[it] != null }.forEach { category ->
                                val counts = snap.counts[category]
                                KaChatStatCard(
                                    icon = category.icon,
                                    color = category.color,
                                    title = stringResource(category.title),
                                    detail = KaspaUnit.label(stringResource(category.detail)),
                                    values = listOf(counts?.last24h, counts?.last7d, counts?.total).map { v -> v?.let { kaChatStatsGrouped(it.coerceAtLeast(0L)) } },
                                )
                            }
                        } else {
                            val shownFailure = failure
                            if (shownFailure != null && !loading) UnavailableCard(shownFailure) { viewModel.refresh(force = true) }
                            chatCategories.forEach { category ->
                                KaChatStatCard(
                                    icon = category.icon,
                                    color = category.color,
                                    title = stringResource(category.title),
                                    detail = KaspaUnit.label(stringResource(category.detail)),
                                    values = emptyList(),
                                    redacted = true,
                                )
                            }
                        }
                        kachatTotals?.let { kachat ->
                            KaChatStatCard(
                                icon = Icons.Default.AlternateEmail,
                                color = KachatDomainsMint,
                                title = stringResource(R.string.shell_ks_kachat_domains),
                                detail = stringResource(R.string.shell_ks_kachat_transactions),
                                values = listOf(kachat.last24h, kachat.last7d, kachat.total).map { kaChatStatsGrouped(it) },
                            )
                        }
                    }
                    KaChatStatsPage.FEES -> {
                        val fees = kachatFees
                        when {
                            fees != null -> KaChatStatCard(
                                icon = Icons.Default.AlternateEmail,
                                color = KachatDomainsMint,
                                title = stringResource(R.string.shell_ks_kachat_domains),
                                detail = stringResource(R.string.shell_ks_kachat_fees_detail),
                                values = listOf(fees.last24h, fees.last7d, fees.total).map { kaChatStatsKas(it) },
                            )
                            kachatLoaded -> Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(stringResource(R.string.shell_ks_fees_unavailable), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.shell_ks_fees_unavailable_body), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                            }
                            else -> KaChatStatCard(
                                icon = Icons.Default.AlternateEmail,
                                color = KachatDomainsMint,
                                title = stringResource(R.string.shell_ks_kachat_domains),
                                detail = stringResource(R.string.shell_ks_kachat_fees_detail),
                                values = emptyList(),
                                redacted = true,
                            )
                        }
                    }
                }
                snapshot?.let { snap ->
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (failure == KaChatStatsStore.Failure.UNAVAILABLE) {
                            Text(stringResource(R.string.shell_ks_stale), color = colors.textSecondary, fontSize = 13.sp)
                        }
                        val updated = minOf(snap.updatedAt ?: snap.fetchedAt, System.currentTimeMillis())
                        Text(
                            stringResource(R.string.shell_ks_updated, android.text.format.DateUtils.getRelativeTimeSpanString(updated).toString()),
                            color = colors.textSecondary,
                            fontSize = 13.sp
                        )
                        snap.indexedSince?.let { since ->
                            Text(
                                stringResource(R.string.shell_ks_counting_since, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(since))),
                                color = colors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    if (showInfo) {
        ActionSheetContainer(title = stringResource(R.string.shell_ks_what_counts), subtitle = stringResource(R.string.shell_ks_info_header), onDismiss = { showInfo = false }) {
            KaChatStatCategory.entries.forEach { category ->
                StatsHowRow(category.icon, stringResource(category.title), KaspaUnit.label(stringResource(category.detail)), tint = category.color)
            }
            Text(
                stringResource(R.string.shell_ks_info_footer),
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * The stats hero's chart: iOS's "chart.bar.xaxis" at 34pt semibold as the iPhone draws it - four
 * rounded 7pt bars (the second the tallest) standing over a rounded x-axis, 38 x 31 in all.
 * Material's BarChart has three thin bars and no axis, and came out at about half the size.
 */
@Composable
private fun StatsChartGlyph(modifier: Modifier = Modifier, color: Color = KaspaTeal) {
    androidx.compose.foundation.Canvas(modifier.size(width = 38.dp, height = 31.3.dp)) {
        val u = 1.dp.toPx()
        val bar = androidx.compose.ui.geometry.CornerRadius(1.6f * u, 1.6f * u)
        val barsBottom = 25.3f * u
        // left edge and top of each bar, in points from the glyph's top left
        listOf(2.0f to 7.7f, 11.0f to 0f, 20.3f to 4.3f, 29.3f to 11.7f).forEach { (x, top) ->
            drawRoundRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset(x * u, top * u),
                size = androidx.compose.ui.geometry.Size(7f * u, barsBottom - top * u),
                cornerRadius = bar,
            )
        }
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 28f * u),
            size = androidx.compose.ui.geometry.Size(size.width, 3.3f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.65f * u, 1.65f * u),
        )
    }
}

/** One kind: its icon, name and what it counts, then its 24 Hours, 7 Days and All Time figures
 *  side by side. A figure the indexer doesn't keep shows a dash (iOS 4924787 `statCard`). */
@Composable
private fun KaChatStatCard(
    icon: ImageVector,
    color: Color,
    title: String,
    detail: String,
    values: List<String?>,
    redacted: Boolean = false,
) {
    val colors = LocalAppColors.current
    StatsCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(detail, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // As the iPhone draws it: from the text's start to 16 short of the card's edge.
        HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = colors.background)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            KaChatStatRange.entries.forEachIndexed { index, range ->
                if (index > 0) VerticalDivider(Modifier.height(34.dp), color = colors.background)
                KaChatStatColumn(stringResource(range.title), values.getOrNull(index), redacted)
            }
        }
    }
}

@Composable
private fun RowScope.KaChatStatColumn(title: String, value: String?, redacted: Boolean) {
    val colors = LocalAppColors.current
    Column(
        Modifier.weight(1f).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            redacted -> Box(Modifier.padding(vertical = 2.dp)) { StatsRedacted(44, 18) }
            value != null -> KachatFitText(value, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, minScale = 0.6f)
            else -> Text("—", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
        }
        Text(title, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(LocalAppColors.current.surface), content = content)
}

/** Every number on KaChat Stats with commas, whatever the language, and at most two decimals
 *  (iOS b6f55a5 `groupedFormatter`). A new one each time: DecimalFormat isn't thread-safe. */
private fun kaChatStatsFormatter(): java.text.DecimalFormat =
    java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(java.util.Locale.US)).apply {
        roundingMode = java.math.RoundingMode.HALF_EVEN
    }

/** "774,086"; from a million on, short: "1M", "1.01M", "2.5B" (rounded down, so it never shows
 *  a figure not yet reached; iOS b6f55a5, 1df1bcb `grouped`). */
fun kaChatStatsGrouped(value: Long): String =
    kaChatStatsCompact(value) ?: kaChatStatsFormatter().format(value)

/** Sompi as KAS with commas and at most two decimals: "21,035.5 KAS" (short from a million KAS
 *  on, like the counts; iOS b6f55a5, 1df1bcb `kas`). */
fun kaChatStatsKas(sompi: Long): String {
    val value = java.math.BigDecimal.valueOf(sompi).movePointLeft(8)
    val text = kaChatStatsCompact(sompi / 100_000_000) ?: kaChatStatsFormatter().format(value)
    return "$text ${KaspaUnit.symbol}"
}

/** Null below a million (iOS 1df1bcb, 49b5e1c `compact`). Whole-number math: in floating point
 *  1,150,000 / 1e6 * 100 is 114.999..., which rounded down read "1.14M". */
private fun kaChatStatsCompact(value: Long): String? {
    val (size, suffix) = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M")
        .firstOrNull { value >= it.first } ?: return null
    val hundredths = value / (size / 100)
    val scaled = java.math.BigDecimal.valueOf(hundredths).movePointLeft(2)
    return kaChatStatsFormatter().format(scaled) + suffix
}

@Composable
private fun UnavailableCard(failure: KaChatStatsStore.Failure, onRetry: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.BarChart, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(28.dp))
        when (failure) {
            KaChatStatsStore.Failure.NO_INDEXER -> {
                Text(stringResource(R.string.shell_ks_no_indexer), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.shell_ks_no_indexer_body), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            KaChatStatsStore.Failure.UNAVAILABLE -> {
                Text(stringResource(R.string.shell_ks_unavailable), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.shell_ks_unavailable_body), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.shell_ks_try_again), color = KaspaTeal)
                }
            }
        }
    }
}


@Composable
private fun StatsHowRow(icon: ImageVector, title: String, detail: String, tint: Color = KaspaTeal) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(detail, color = LocalAppColors.current.textSecondary, fontSize = 14.sp)
        }
    }
}

/** A redacted text-shaped block: where a real value will go, with nothing invented in it. */
@Composable
private fun StatsRedacted(width: Int, height: Int = 14, color: Color = LocalAppColors.current.textSecondary.copy(alpha = 0.25f)) {
    Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(4.dp)).background(color))
}
