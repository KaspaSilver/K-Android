package com.kachat.app.ui.screens.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import com.kachat.app.R
import com.kachat.app.services.WalletManager
import com.kachat.app.services.explorer.ExplorerApi
import com.kachat.app.services.explorer.ExplorerBlock
import com.kachat.app.services.explorer.ExplorerChartMetric
import com.kachat.app.services.explorer.ExplorerDemo
import com.kachat.app.services.explorer.ExplorerKActivity
import com.kachat.app.services.explorer.ExplorerKPeriod
import com.kachat.app.services.explorer.ExplorerKStats
import com.kachat.app.services.explorer.ExplorerLive
import com.kachat.app.services.explorer.ExplorerNetworkStats
import com.kachat.app.services.explorer.ExplorerParsing
import com.kachat.app.services.explorer.ExplorerRoute
import com.kachat.app.services.explorer.ExplorerSearchMatch
import com.kachat.app.services.explorer.ExplorerTransaction
import com.kachat.app.services.explorer.ExplorerTxKind
import com.kachat.app.ui.components.CycleCapsuleButton
import com.kachat.app.ui.components.PageCycleBar
import com.kachat.app.ui.screens.BlockDagAnimation
import com.kachat.app.ui.screens.ContactAvatar
import com.kachat.app.ui.screens.HubSectionTopBar
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.OnScreenPresence
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.IosAlertDialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

// The Explorer tab in Kaspa Hub (iOS 961fc05a `ExplorerView`): search, the network live, the
// newest blocks and transactions, and a page for every transaction, block and address, all from
// kaspa.stream's API. Its strings are English on the iPhone too, until the owner settles the
// design (iOS CLAUDE.md), so strings_explorer.xml has no translations yet.

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    val api: ExplorerApi,
    val live: ExplorerLive,
    val kStats: ExplorerKStats,
    private val walletManager: WalletManager,
) : ViewModel() {
    val ownAddress: StateFlow<String?> = walletManager.activeAddressFlow

    /** The signed-in account's name, for its picture's fallback. */
    fun ownName(): String = runCatching { walletManager.getActiveAccount()?.name }.getOrNull().orEmpty()

    /** [hex] is the chatting key or a revealed spending address's key: never sent anywhere (iOS
     *  d88891ea `isOwnPrivateKey`, audit 2026-11-27 L5). Off the main thread (the address list
     *  can derive). */
    suspend fun isOwnPrivateKey(hex: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (!com.kachat.app.services.explorer.ExplorerParsing.isHexId(hex.trim())) return@withContext false
        val own = runCatching {
            listOfNotNull(walletManager.getActiveAccount()?.address) + walletManager.allSpendingAddresses()
        }.getOrDefault(emptyList())
        com.kachat.app.services.explorer.ExplorerKeyGuard.isOwnKey(hex, own)
    }

    /** What was searched for, newest first, at most six (iOS `recentSearches`, this session only). */
    val recentSearches = mutableStateListOf<String>()

    /** Each open page's loaded data, by its route, so going back to it doesn't load it again (the
     *  iPhone's navigation stack keeps its pages). */
    val pages = mutableStateMapOf<String, Any>()

    fun rememberSearch(text: String) {
        recentSearches.remove(text)
        recentSearches.add(0, text)
        while (recentSearches.size > 6) recentSearches.removeAt(recentSearches.size - 1)
    }
}

/** What the text typed so far matches, looked up as you type (iOS `TypedMatch`). */
private sealed class TypedMatch {
    data object None : TypedMatch()
    data object LookingUp : TypedMatch()
    data class Username(val name: String, val address: String) : TypedMatch()
    data class NoUsername(val text: String) : TypedMatch()
    /** A valid address, with its .kachat name and balance once known. */
    data class Address(val address: String, val name: String?, val balance: Long?) : TypedMatch()
    data object NotAnAddress : TypedMatch()
    data class Transaction(val id: String, val kind: ExplorerTxKind) : TypedMatch()
    data class Block(val hash: String) : TypedMatch()
    data object NotFound : TypedMatch()
    /** kaspa.stream couldn't be asked, so it isn't known whether the id exists. */
    data object Unreachable : TypedMatch()
    /** A 64-character id: asked about only on Search or a tap, never as you type, since it could
     *  be a pasted private key (iOS d88891ea, audit 2026-11-27 L5). */
    data class IdToLookUp(val id: String) : TypedMatch()
    /** One of this wallet's own private keys: never sent anywhere. */
    data object PrivateKey : TypedMatch()
}

private enum class ExplorerHomePage { BLOCKS, TRANSACTIONS, K_ACTIVITY }

/**
 * The Explorer (Kaspa Hub > Explorer): its home, and the pages opened from it on a stack of their
 * own, so the section comes back exactly as left (it is kept alive in the Hub). The live numbers
 * tick only while it shows and the app is in front (audit 2026-11-25 B2, E3).
 */
@Composable
fun ExplorerScreen(viewModel: ExplorerViewModel = hiltViewModel()) {
    // the open pages, oldest first, written as text so they survive in saved state
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { stackText.split('\n').filter { it.isNotEmpty() } }
    val open: (ExplorerRoute) -> Unit = { route -> stackText = (stack + route.encode()).joinToString("\n") }
    val pop: () -> Unit = {
        stack.lastOrNull()?.let { viewModel.pages.remove("${stack.size - 1}|$it") }
        stackText = stack.dropLast(1).joinToString("\n")
    }

    // Running: on screen with its Hub section showing, and the app in front.
    var onScreen by remember { mutableStateOf(false) }
    OnScreenPresence(appear = { onScreen = true }, disappear = { onScreen = false })
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val running = onScreen && resumed
    LaunchedEffect(running) { if (running) viewModel.live.start() else viewModel.live.stop() }
    DisposableEffect(Unit) { onDispose { viewModel.live.stop() } }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    // a K Explorer link (iOS bfb902b7 `ExplorerDeepLink`): its transaction or address, on top of
    // the Explorer's home
    val deepLink by com.kachat.app.services.explorer.ExplorerDeepLink.pending.collectAsState()
    LaunchedEffect(deepLink) {
        val route = com.kachat.app.services.explorer.ExplorerDeepLink.take() ?: return@LaunchedEffect
        stack.forEachIndexed { index, text -> viewModel.pages.remove("$index|$text") }
        stackText = route.encode()
    }

    val states = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalExplorerPaused provides !running) {
        AnimatedContent(
            targetState = stack,
            transitionSpec = {
                val forward = targetState.size > initialState.size
                (slideInHorizontally(tween(250)) { if (forward) it else -it / 3 } + fadeIn(tween(250))) togetherWith
                    (slideOutHorizontally(tween(250)) { if (forward) -it / 3 else it } + fadeOut(tween(250)))
            },
            label = "explorerStack",
        ) { shown ->
            // drawn from its own stack, so a page leaving keeps its content while it slides out
            val depth = shown.size
            val routeText = shown.lastOrNull()
            val route = routeText?.let { ExplorerRoute.decode(it) }
            Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
                if (route == null) {
                    states.SaveableStateProvider("home") { ExplorerHome(viewModel, running, open) }
                } else {
                    states.SaveableStateProvider("$depth|$routeText") {
                        ExplorerPage(viewModel, route, cacheKey = "${depth - 1}|$routeText", onBack = pop, open = open)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerHome(viewModel: ExplorerViewModel, running: Boolean, open: (ExplorerRoute) -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val stats by viewModel.live.stats.collectAsState()
    val failed by viewModel.live.failed.collectAsState()
    val latestBlocks by viewModel.live.latestBlocks.collectAsState()
    val latestTransactions by viewModel.live.latestTransactions.collectAsState()
    val activity by viewModel.kStats.activity.collectAsState()
    val periods by viewModel.kStats.periods.collectAsState()
    val lastDayFee by viewModel.kStats.lastDayAverageFee.collectAsState()
    val ownAddress by viewModel.ownAddress.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(ExplorerHomePage.BLOCKS) }
    var kPeriod by rememberSaveable { mutableStateOf(ExplorerKPeriod.DAY) }
    var typedMatch by remember { mutableStateOf<TypedMatch>(TypedMatch.None) }

    fun openRoute(route: ExplorerRoute) {
        haptic(IosHaptic.SELECTION)
        focusManager.clearFocus()
        open(route)
    }

    fun openTyped(route: ExplorerRoute) {
        viewModel.rememberSearch(query.trim())
        focusManager.clearFocus()
        query = ""
        openRoute(route)
    }

    /** What a 64-character id is (light calls: not the whole transaction or block), once asked
     *  (iOS d88891ea `identifyTyped`). */
    fun identifyTyped(id: String) {
        scope.launch {
            if (viewModel.isOwnPrivateKey(id)) {
                typedMatch = TypedMatch.PrivateKey
                return@launch
            }
            typedMatch = TypedMatch.LookingUp
            val match = identifyId(viewModel.api, id) ?: return@launch
            // typed on meanwhile: not this text's answer
            if (query.trim().lowercase() != id) return@launch
            typedMatch = match
        }
    }

    fun runSearch(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || searching) return
        searching = true
        scope.launch {
            // one of your own private keys never leaves the phone (iOS d88891ea, audit L5)
            if (com.kachat.app.services.explorer.ExplorerKeyGuard.isLookedUpOnlyOnSearch(trimmed) && viewModel.isOwnPrivateKey(trimmed)) {
                searching = false
                typedMatch = TypedMatch.PrivateKey
                haptic(IosHaptic.ERROR)
                return@launch
            }
            val match = viewModel.api.search(trimmed)
            searching = false
            when (match) {
                is ExplorerSearchMatch.Route -> {
                    viewModel.rememberSearch(trimmed)
                    focusManager.clearFocus()
                    query = ""
                    openRoute(match.route)
                }
                ExplorerSearchMatch.NotFound -> {
                    haptic(IosHaptic.ERROR)
                    notFound = true
                }
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { HubSectionTopBar(onBack = null) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = LocalDockInset.current + 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // MARK: Search
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.ex_search_kaspa), color = colors.textTertiary, fontSize = 17.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                    )
                }
                when {
                    searching -> IosActivityIndicator(Modifier.size(20.dp))
                    query.isNotEmpty() -> Icon(
                        Icons.Filled.Cancel,
                        contentDescription = stringResource(R.string.clear),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { query = "" },
                    )
                    else -> Text(
                        stringResource(R.string.paste),
                        color = KaspaTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            if (pasted.isNotEmpty()) {
                                query = pasted
                                runSearch(pasted)
                            }
                        },
                    )
                }
            }
            if (failed) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ex_unreachable), color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            val trimmedQuery = query.trim()
            when {
                trimmedQuery.isNotEmpty() -> {
                    // looked up a moment after typing stops
                    LaunchedEffect(trimmedQuery.lowercase()) {
                        typedMatch = lookUpTyped(viewModel.api, trimmedQuery, isOwnKey = viewModel::isOwnPrivateKey) { typedMatch = it } ?: return@LaunchedEffect
                    }
                    LiveResults(typedMatch, trimmedQuery, onOpen = ::openTyped, onLookUp = ::identifyTyped)
                }
                searchFocused -> Suggestions(
                    ownAddress = ownAddress,
                    ownName = viewModel.ownName(),
                    recent = viewModel.recentSearches,
                    onOwn = { address -> openRoute(ExplorerRoute.Address(address)) },
                    onRecent = { item ->
                        query = item
                        runSearch(item)
                    },
                    onClear = { viewModel.recentSearches.clear() },
                )
                else -> {
                    val shownStats = stats ?: ExplorerNetworkStats.EMPTY
                    ExplorerRedaction(redacted = stats == null) {
                        LiveCard(viewModel.live, shownStats, running)
                        StatsGrid(
                            stats = shownStats,
                            lastDayFee = lastDayFee,
                            onOpen = { openRoute(ExplorerRoute.Chart(it)) },
                        )
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            SupplyCard(shownStats)
                        }
                        val shownPeriod = if (kPeriod in periods) kPeriod else (periods.firstOrNull() ?: ExplorerKPeriod.DAY)
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            // K's indexer's numbers; until they first arrive, the demo (debug) or "–"
                            // (release; iOS 01f71184 `kActivityFallback`)
                            ExplorerKActivityCard(
                                periods = periods,
                                activity = activity[shownPeriod] ?: kActivityFallback(shownPeriod),
                                onPeriod = { kPeriod = it },
                            )
                        }
                    }
                    PageCycleBar(
                        titles = listOf(stringResource(R.string.ex_blocks), stringResource(R.string.transactions), stringResource(R.string.ex_k_activity)),
                        selectedIndex = page.ordinal,
                        onSelect = { page = ExplorerHomePage.entries[it] },
                    )
                    LatestList(page, latestBlocks, latestTransactions, onOpen = ::openRoute)
                    Text(
                        stringResource(R.string.ex_footer),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 16.dp, end = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (notFound) {
        IosAlertDialog(
            onDismissRequest = { notFound = false },
            title = { Text(stringResource(R.string.ex_nothing_found)) },
            text = { Text(stringResource(R.string.ex_search_hint)) },
            confirmButton = { TextButton(onClick = { notFound = false }) { Text(stringResource(R.string.ok), color = KaspaTeal) } },
        )
    }
}

/**
 * Works out what's typed, a moment after typing stops: an address (checked, with its name and
 * balance), a transaction or block id, or a .kachat username. [show] puts up what's known so far;
 * the answer is returned (null when cancelled).
 */
private suspend fun lookUpTyped(
    api: ExplorerApi,
    text: String,
    isOwnKey: suspend (String) -> Boolean,
    show: (TypedMatch) -> Unit,
): TypedMatch? {
    var lower = text.lowercase()
    ExplorerParsing.addressIn(lower)?.let { lower = it }
    if (lower.isEmpty()) return null
    show(TypedMatch.LookingUp)
    delay(350)
    return try {
        when {
            lower.startsWith("kaspa:") || lower.startsWith("kaspatest:") -> {
                if (!KaspaAddress.isValid(lower)) return TypedMatch.NotAnAddress
                show(TypedMatch.Address(lower, api.cachedName(lower), null))
                coroutineScope {
                    val balance = async { api.balance(lower) }
                    val name = async { api.identityName(lower) }
                    TypedMatch.Address(lower, name.await(), balance.await())
                }
            }
            // nothing leaves the phone until it's asked for (Search, or the row's tap): it could
            // be a pasted private key (iOS d88891ea, audit 2026-11-27 L5)
            ExplorerParsing.isHexId(lower) -> if (isOwnKey(lower)) TypedMatch.PrivateKey else TypedMatch.IdToLookUp(lower)
            else -> api.username(text)?.let { (name, address) -> TypedMatch.Username(name, address) } ?: TypedMatch.NoUsername(text)
        }
    } catch (e: CancellationException) {
        null
    }
}

/** What a 64-character id is: light calls, not the whole transaction or block. Null when
 *  cancelled. */
private suspend fun identifyId(api: ExplorerApi, id: String): TypedMatch? = try {
    when (val match = api.identify(id)) {
        is ExplorerApi.IdMatch.Transaction -> TypedMatch.Transaction(id, match.kind)
        ExplorerApi.IdMatch.Block -> TypedMatch.Block(id)
        ExplorerApi.IdMatch.NotFound -> TypedMatch.NotFound
    }
} catch (e: CancellationException) {
    null
} catch (e: Exception) {
    TypedMatch.Unreachable
}

@Composable
private fun LiveResults(match: TypedMatch, text: String, onOpen: (ExplorerRoute) -> Unit, onLookUp: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
            when (match) {
                is TypedMatch.Username -> MatchRow(match.name, "@${match.name}", match.address, null) { onOpen(ExplorerRoute.Address(match.address)) }
                is TypedMatch.Address -> MatchRow(
                    name = match.name ?: "K",
                    title = match.name?.let { "@$it" } ?: stringResource(R.string.address),
                    detail = match.address,
                    trailing = match.balance?.let { ExplorerFormat.kas(it) },
                ) { onOpen(ExplorerRoute.Address(match.address)) }
                is TypedMatch.Transaction -> Row(
                    Modifier.fillMaxWidth().explorerRow { onOpen(ExplorerRoute.Transaction(match.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExplorerKindBadge(match.kind, 34.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(match.kind.title), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        ExplorerMonoText(match.id, middle = true)
                    }
                    ExplorerChevron()
                }
                is TypedMatch.Block -> Row(
                    Modifier.fillMaxWidth().explorerRow { onOpen(ExplorerRoute.Block(match.hash)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                        Icon(ExplorerSymbols.CubeFill, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.ex_block), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        ExplorerMonoText(match.hash, middle = true)
                    }
                    ExplorerChevron()
                }
                is TypedMatch.IdToLookUp -> Row(
                    Modifier.fillMaxWidth().explorerRow { onLookUp(match.id) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.ex_look_up_this_id), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        ExplorerMonoText(match.id, middle = true)
                    }
                    ExplorerChevron()
                }
                TypedMatch.PrivateKey -> TypedNote(stringResource(R.string.ex_own_private_key))
                is TypedMatch.NoUsername -> TypedNote(stringResource(R.string.ex_no_username, match.text))
                TypedMatch.NotAnAddress -> TypedNote(stringResource(R.string.ex_not_an_address))
                TypedMatch.NotFound -> TypedNote(stringResource(R.string.ex_no_such_id))
                TypedMatch.Unreachable -> TypedNote(stringResource(R.string.ex_unreachable))
                TypedMatch.LookingUp, TypedMatch.None -> Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IosActivityIndicator(Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.ex_looking_up, text),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(stringResource(R.string.ex_search_hint), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun TypedNote(text: String) {
    Text(
        text,
        color = LocalAppColors.current.textSecondary,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(14.dp),
    )
}

/** A found address: its picture, name (or "Address"), the address, and its balance. */
@Composable
private fun MatchRow(name: String, title: String, detail: String, trailing: String?, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().explorerRow(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(imageUrl = null, fallbackText = name, size = 34.dp, includeAssignedPhoto = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            ExplorerMonoText(detail, middle = true)
        }
        if (trailing != null) {
            Text(trailing, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = Tabular)
        }
        ExplorerChevron()
    }
}

@Composable
private fun Suggestions(
    ownAddress: String?,
    ownName: String,
    recent: List<String>,
    onOwn: (String) -> Unit,
    onRecent: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ownAddress != null) {
            ExplorerSectionTitle(stringResource(R.string.your_account))
            ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().explorerRow { onOwn(ownAddress) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExplorerOwnAvatar(ownAddress, ownName, 34.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.chatting_address), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        ExplorerMonoText(ownAddress, middle = true)
                    }
                    ExplorerChevron()
                }
            }
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ExplorerSectionTitle(stringResource(R.string.ex_recent), Modifier.weight(1f))
                Text(
                    stringResource(R.string.clear),
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 20.dp).clickable(onClick = onClear),
                )
            }
            ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
                recent.forEachIndexed { index, item ->
                    if (index > 0) ExplorerDivider(46.dp)
                    Row(
                        Modifier.fillMaxWidth().explorerRow { onRecent(item) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                        ExplorerMonoText(item, middle = true, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text(stringResource(R.string.ex_search_hint), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

// MARK: Live network

/** The live card: the BlockDAG drifting behind the live DAA score and blocks per second. The
 *  graph runs at 30 fps and stands still while the Explorer doesn't show (audit B2). */
@Composable
private fun LiveCard(live: ExplorerLive, stats: ExplorerNetworkStats, running: Boolean) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(KaspaTeal.copy(alpha = 0.55f), Color(0.02f, 0.16f, 0.16f)),
                    start = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
                    end = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                )
            ),
    ) {
        BlockDagAnimation(
            highlight = false,
            modifier = Modifier.fillMaxSize().alpha(0.55f),
            paused = !running,
            minimumIntervalMs = 33,
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.45f)))))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExplorerLiveDot()
                    Text(stringResource(R.string.ex_live).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                ExplorerDaaScore(live, hasStats = !LocalExplorerRedacted.current)
                Text(stringResource(R.string.ex_daa_score), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    String.format(Locale.US, "%.1f", stats.blocksPerSecond),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Rounded,
                    style = Tabular,
                    modifier = Modifier.explorerRedacted(),
                )
                Text(stringResource(R.string.ex_blocks_per_second), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

/** The live card's DAA score, run on from the newest block at the measured rate, once a second;
 *  it alone redraws every second (audit 2026-11-25 E3). */
@Composable
private fun ExplorerDaaScore(live: ExplorerLive, hasStats: Boolean) {
    val paused = LocalExplorerPaused.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused) {
        now.longValue = System.currentTimeMillis()
        if (paused) return@LaunchedEffect
        while (true) {
            delay(1_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    val score = if (hasStats) live.daaScore(now.longValue) else 0L
    Text(
        ExplorerFormat.grouped(score),
        color = Color.White,
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = Rounded,
        style = Tabular,
        maxLines = 1,
        modifier = Modifier.explorerRedacted(),
    )
}

/** K Activity before K's indexer answers: the demo in debug builds, "–" in release (iOS 01f71184
 *  `kActivityFallback`, release audit R8). */
internal fun kActivityFallback(period: ExplorerKPeriod, now: Long = System.currentTimeMillis()): ExplorerKActivity =
    if (ExplorerDemo.allowed) ExplorerDemo.kActivity(period, now)
    else ExplorerKActivity(period = period, counts = emptyMap(), unavailable = true)

@Composable
private fun StatsGrid(stats: ExplorerNetworkStats, lastDayFee: Long?, onOpen: (ExplorerChartMetric) -> Unit) {
    val context = LocalContext.current
    // What the Network Fee tile's number is: Kaspalytics' last full day, else the demo in debug
    // builds and "–" in release (iOS 01f71184 `feeTileValue`: a wallet doesn't show made-up numbers).
    val feeDetail = stringResource(
        when {
            lastDayFee != null -> R.string.ex_fee_yesterday
            ExplorerDemo.allowed -> R.string.ex_fee_demo
            else -> R.string.ex_fee_not_served
        },
    )
    val feeValue = when {
        lastDayFee != null -> ExplorerFormat.metric(ExplorerChartMetric.NETWORK_FEES, lastDayFee / 100_000_000.0)
        ExplorerDemo.allowed -> ExplorerFormat.metric(ExplorerChartMetric.NETWORK_FEES, stats.averageFee24h / 100_000_000.0)
        else -> "–"
    }
    val usDollars = stringResource(R.string.ex_us_dollars)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TileRow(
            {
                ExplorerStatTile(
                    Icons.Filled.Bolt, stringResource(R.string.ex_hashrate), ExplorerFormat.hashrate(stats.hashrate),
                    stringResource(R.string.ex_whole_network), it, showsChevron = true,
                ) { onOpen(ExplorerChartMetric.HASHRATE) }
            },
            {
                ExplorerStatTile(
                    Icons.Filled.Inbox, stringResource(R.string.ex_mempool), ExplorerFormat.grouped(stats.mempoolSize.toLong().coerceAtLeast(0)),
                    stringResource(R.string.ex_waiting), it,
                )
            },
        )
        TileRow(
            {
                ExplorerStatTile(
                    Icons.Filled.Speed, stringResource(R.string.network_fee),
                    feeValue, feeDetail, it, showsChevron = true,
                ) { onOpen(ExplorerChartMetric.NETWORK_FEES) }
            },
            {
                ExplorerStatTile(
                    null, stringResource(R.string.kp_kas_price_bb9bb), String.format(Locale.US, "$%.4f", stats.priceUSD),
                    usDollars, it, showsChevron = true,
                ) { onOpen(ExplorerChartMetric.PRICE) }
            },
        )
        TileRow(
            {
                ExplorerStatTile(
                    Icons.Filled.Gavel, stringResource(R.string.kp_block_reward), ExplorerFormat.rewardKAS(stats.blockReward),
                    stringResource(R.string.ex_reward_detail, ExplorerFormat.daysUntil(context, stats.nextRewardDrop, System.currentTimeMillis())),
                    it, showsChevron = true,
                ) { onOpen(ExplorerChartMetric.BLOCK_REWARD) }
            },
            {
                ExplorerStatTile(
                    Icons.Filled.BarChart, stringResource(R.string.kp_market_cap_d5533),
                    ExplorerFormat.compactUSD(stats.circulatingSupply / 100_000_000.0 * stats.priceUSD),
                    usDollars, it, showsChevron = true,
                ) { onOpen(ExplorerChartMetric.MARKET_CAP) }
            },
        )
    }
}

/** Two tiles side by side, the same height. */
@Composable
private fun TileRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        first(Modifier.weight(1f).fillMaxHeight())
        second(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SupplyCard(stats: ExplorerNetworkStats) {
    val colors = LocalAppColors.current
    val mined = (stats.circulatingSupply.toDouble() / stats.maxSupply.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    ExplorerCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Hive, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ex_circulating_supply),
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).explorerRedacted(),
                )
                Text(
                    String.format(Locale.US, "%.2f%%", mined * 100),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = Tabular,
                    modifier = Modifier.explorerRedacted(),
                )
            }
            LinearProgressIndicator(
                progress = { mined.toFloat() },
                color = KaspaTeal,
                trackColor = colors.textSecondary.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
            )
            Row {
                Text(
                    ExplorerFormat.compactKAS(stats.circulatingSupply),
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = Tabular,
                    modifier = Modifier.explorerRedacted(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.ex_of_supply, ExplorerFormat.compactKAS(stats.maxSupply)),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.explorerRedacted(),
                )
            }
        }
    }
}

/**
 * K's activity on the Explorer's home (it replaced KaChat Stats, owner 2026-11-25): every
 * transaction K's protocols made in the window the cycle button picks, how that compares with the
 * window before, a bar per hour, day or month, and each protocol's count. What K's indexer doesn't
 * serve is left off.
 */
@Composable
private fun ExplorerKActivityCard(periods: List<ExplorerKPeriod>, activity: ExplorerKActivity, onPeriod: (ExplorerKPeriod) -> Unit) {
    val colors = LocalAppColors.current
    ExplorerCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.k_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ex_k_activity),
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).explorerRedacted(),
                )
                CycleCapsuleButton(
                    options = periods,
                    selection = activity.period,
                    onSelect = onPeriod,
                    title = { stringResource(it.title) },
                    hint = stringResource(R.string.ex_switches_period),
                )
            }
            if (activity.isDemo) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Brush, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.ex_demo_numbers), color = colors.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (activity.unavailable) "–" else ExplorerFormat.grouped(activity.total.toLong()),
                        color = colors.textPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Rounded,
                        style = Tabular,
                        modifier = Modifier.explorerRedacted(),
                    )
                    Text(stringResource(R.string.ex_transactions_lower), color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.explorerRedacted())
                }
                val change = activity.change
                val comparison = activity.period.comparison
                if (change != null && comparison != null) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                if (change >= 0) Icons.Filled.NorthEast else Icons.Filled.SouthEast,
                                contentDescription = null,
                                tint = KaspaTeal,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                String.format(Locale.US, "%.1f%%", kotlin.math.abs(change)),
                                color = KaspaTeal,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = Tabular,
                            )
                        }
                        Text(stringResource(comparison), color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            // the bars, active addresses and fees once K's indexer serves them
            activity.bars?.let { bars -> ActivityBars(activity.period, bars) }
            ExplorerDivider(0.dp)
            ActivityKinds(activity)
            if (activity.activeAddresses != null || activity.fees != null) {
                ExplorerDivider(0.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    activity.activeAddresses?.let {
                        FooterStat(Icons.Filled.People, ExplorerFormat.grouped(it.toLong()), stringResource(R.string.ex_active_addresses))
                    }
                    Spacer(Modifier.weight(1f))
                    activity.fees?.let {
                        FooterStat(Icons.Filled.LocalFireDepartment, ExplorerFormat.compactKAS(it), stringResource(R.string.ex_paid_in_fees))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityBars(period: ExplorerKPeriod, bars: List<Int>) {
    val colors = LocalAppColors.current
    val peak = (bars.maxOrNull() ?: 1).coerceAtLeast(1)
    val meaning = stringResource(period.barMeaning)
    Column(
        Modifier.clearAndSetSemantics { contentDescription = meaning },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(if (bars.size > 24) 2.dp else 3.dp),
        ) {
            bars.forEachIndexed { index, count ->
                Box(
                    Modifier
                        .weight(1f)
                        .height((44f * count / peak).coerceAtLeast(3f).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(KaspaTeal.copy(alpha = if (index == bars.size - 1) 1f else 0.55f)),
                )
            }
        }
        Row {
            Text(stringResource(period.chartStart), color = colors.textSecondary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(period.chartEnd), color = colors.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActivityKinds(activity: ExplorerKActivity) {
    val colors = LocalAppColors.current
    val ranked = activity.ranked
    val top = (ranked.firstOrNull()?.second ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ranked.forEach { (kind, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExplorerKindBadge(kind, 26.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(stringResource(kind.plural), color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f).explorerRedacted())
                        Text(
                            ExplorerFormat.grouped(count.toLong()),
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = Tabular,
                            modifier = Modifier.explorerRedacted(),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth((count.toFloat() / top).coerceIn(0.02f, 1f))
                                .height(3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(KaspaTeal.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, title: String) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
        Column {
            Text(value, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            Text(title, color = colors.textSecondary, fontSize = 11.sp)
        }
    }
}

// MARK: Latest

@Composable
private fun LatestList(
    page: ExplorerHomePage,
    blocks: List<ExplorerBlock>,
    transactions: List<ExplorerTransaction>,
    onOpen: (ExplorerRoute) -> Unit,
) {
    val colors = LocalAppColors.current
    ExplorerCard(Modifier.padding(horizontal = 16.dp)) {
        when (page) {
            ExplorerHomePage.BLOCKS -> {
                if (blocks.isEmpty()) ExplorerLoadingRow()
                blocks.forEachIndexed { index, block ->
                    if (index > 0) ExplorerDivider(62.dp)
                    ExplorerBlockRow(block, Modifier.explorerRow { onOpen(ExplorerRoute.Block(block.id)) })
                }
            }
            ExplorerHomePage.TRANSACTIONS, ExplorerHomePage.K_ACTIVITY -> {
                val txs = (if (page == ExplorerHomePage.K_ACTIVITY) transactions.filter { it.kind.isK } else transactions).take(25)
                if (txs.isEmpty()) {
                    if (page == ExplorerHomePage.K_ACTIVITY && transactions.isNotEmpty()) {
                        Text(stringResource(R.string.ex_no_k_yet), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    } else {
                        ExplorerLoadingRow()
                    }
                }
                txs.forEachIndexed { index, tx ->
                    if (index > 0) ExplorerDivider(62.dp)
                    ExplorerTransactionRow(tx, Modifier.explorerRow { onOpen(ExplorerRoute.Transaction(tx.id)) })
                }
            }
        }
    }
}
