package com.kachat.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.AppTab
import com.kachat.app.ui.CustomizeDock
import com.kachat.app.ui.DockLayout
import com.kachat.app.ui.HubRouter
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The Kaspa Hub tab (iOS 148a5331 `KaspaHubView`): a grid of everything that isn't in the dock,
 * and the section picked from it. The section owns the whole screen, exactly as it looks from its
 * own dock slot ([content] draws the same screen the dock would). Tapping the Kaspa Hub tab again
 * goes back to the grid ([HubRouter.close]); so does the system Back, after the section's own.
 *
 * A section opened here stays alive, hidden, once left (iOS 428a797b `keptSections`, audit
 * 2026-11-21 #28): KaPost, Portfolio, KaPlay, .kachat, Stats and Cold Storage come back exactly
 * as they were - feed, scroll position, the page inside them - instead of loading again
 * ([KEPT_ALIVE]). The others (Chats, Wallet, Contacts, Settings, Kaspa Websites) still close when
 * left: an open chat marks itself read until it leaves, and a web page would keep playing. A
 * hidden section is composed but neither measured, drawn nor touchable, its Back handlers are
 * parked, and [LocalHubSectionHidden] tells what runs inside it ([OnScreenPresence]). Kept
 * sections live as long as this screen does; their saved state outlives it (a dock tab switch
 * disposes the Hub's route), so one opened again after that still comes back where it was left.
 *
 * [badgeCount] is what the dock would show on a tab, so moving one here never hides its badge.
 */
@Composable
fun KaspaHubScreen(
    badgeCount: (AppTab) -> Int,
    content: @Composable (AppTab) -> Unit,
) {
    val hub by DockLayout.hub.collectAsState()
    val openSection by HubRouter.openSection.collectAsState()
    val open = openSection?.takeIf { it in hub }

    // Sections opened since the Hub came up that stay alive (hidden) once left, in the order they
    // were first opened (iOS `keptSections`).
    var keptSections by remember { mutableStateOf<List<AppTab>>(emptyList()) }
    // Each kept section's remembered-and-saved state (scroll positions, a page open inside it),
    // under its own key, so it never carries into another section.
    val sectionStates = rememberSaveableStateHolder()

    // a section moved into the dock meanwhile must not stay open (or kept) here too
    LaunchedEffect(hub, openSection) {
        val section = openSection
        if (section != null && section !in hub) HubRouter.close()
        val gone = keptSections.filter { it !in hub }
        if (gone.isNotEmpty()) {
            keptSections = keptSections - gone.toSet()
            gone.forEach { sectionStates.removeState(it.key) }
        }
    }
    // Opened: kept from now on (iOS `keep`).
    LaunchedEffect(open) {
        if (open != null && open in KEPT_ALIVE && open !in keptSections) keptSections = keptSections + open
    }
    // Short of memory: the hidden sections go; they load again when next opened (iOS
    // didReceiveMemoryWarning).
    val context = LocalContext.current
    val latestOpen by rememberUpdatedState(open)
    DisposableEffect(context) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            fun dropHidden() {
                val keep = latestOpen
                val dropped = keptSections.filter { it != keep }
                if (dropped.isEmpty()) return
                keptSections = keptSections.filter { it == keep }
                dropped.forEach { sectionStates.removeState(it.key) }
            }
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                    level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) dropHidden()
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = dropHidden()
        }
        val app = context.applicationContext
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    // The kept sections to draw. The one being opened is in the list from its first frame, before
    // the effect above stores it, so its place (and state) never changes between the two.
    val alive = if (open != null && open in KEPT_ALIVE && open !in keptSections) keptSections + open else keptSections

    // Registered once, before any section, so a section's own BackHandler (a sheet, a page inside
    // it) still comes first - a kept section's are registered again each time it shows.
    BackHandler(enabled = open != null) { HubRouter.close() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.hubLayerHidden(open != null)) {
            HubSectionPresence(hidden = open != null) {
                KaspaHubGrid(hub = hub, badgeCount = badgeCount, onOpen = { HubRouter.open(it) })
            }
        }
        for (tab in alive) {
            // keyed, so one section's remembered state never carries into the next
            androidx.compose.runtime.key(tab) {
                val hidden = tab != open
                Box(Modifier.hubLayerHidden(hidden)) {
                    sectionStates.SaveableStateProvider(tab.key) {
                        HubSectionPresence(hidden = hidden) {
                            CompositionLocalProvider(LocalInHubSection provides true) { content(tab) }
                        }
                    }
                }
            }
        }
        if (open != null && open !in KEPT_ALIVE) {
            androidx.compose.runtime.key(open) {
                CompositionLocalProvider(LocalInHubSection provides true) { content(open) }
            }
        }
    }
}

/**
 * The sections kept alive when left (iOS `KaspaHubView.keptAlive`). Each was checked for what it
 * does while on screen: KaPost's feed pages only while it shows ([KaPostsDock.onScreen]), KaPlay's
 * pages are the shell's own routes, and the rest load once and refresh on a pull.
 */
private val KEPT_ALIVE: Set<AppTab> = setOf(
    AppTab.KAPOSTS, AppTab.PORTFOLIO, AppTab.GAMES, AppTab.KACHAT_NAMES, AppTab.KACHAT_STATS, AppTab.COLD_STORAGE,
)

/**
 * True inside a Kaspa Hub section that is kept alive but hidden (another section or the grid is
 * showing; iOS `hubSectionHidden`). Such a section stays composed, so anything that should run
 * only while it shows reads this, usually through [OnScreenPresence].
 */
val LocalHubSectionHidden = androidx.compose.runtime.compositionLocalOf { false }

/**
 * [appear] while the caller is composed and its Kaspa Hub section shows, [disappear] when either
 * stops (iOS `onScreenPresence(appear:disappear:)`): a kept-alive, hidden section counts as gone.
 * Always in pairs, never twice in a row. Use it, not a bare DisposableEffect, for a timer or poll
 * in anything a Hub section can show.
 */
@Composable
fun OnScreenPresence(appear: () -> Unit, disappear: () -> Unit) {
    val hidden = LocalHubSectionHidden.current
    val currentAppear by rememberUpdatedState(appear)
    val currentDisappear by rememberUpdatedState(disappear)
    DisposableEffect(hidden) {
        if (!hidden) currentAppear()
        onDispose { if (!hidden) currentDisappear() }
    }
}

/**
 * A Hub layer's hidden flag for what is inside it, and its Back handlers parked while it is hidden:
 * a [BackHandler] registers with the dispatcher it is given, so a hidden section's (a page open in
 * Cold Storage, a sheet) never takes the system Back from the one showing, and comes back on top
 * of the Hub's own when the section shows again.
 */
@Composable
private fun HubSectionPresence(hidden: Boolean, content: @Composable () -> Unit) {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val parked = remember(lifecycleOwner) {
        object : androidx.activity.OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher()
            override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val real = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current ?: parked
    // One call either way: a second call site would start the section over on every switch.
    CompositionLocalProvider(
        LocalHubSectionHidden provides hidden,
        androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides (if (hidden) parked else real),
    ) { content() }
}

/**
 * A Hub layer that is kept but not showing (iOS `hubLayerHidden`): composed, but neither measured
 * nor placed, so it draws nothing, takes no touch and is left out of what TalkBack reads. Shown,
 * it fills the Hub.
 */
private fun Modifier.hubLayerHidden(hidden: Boolean): Modifier =
    if (hidden) {
        this.layout { _, _ -> layout(0, 0) {} }
    } else {
        this.fillMaxSize()
    }

@Composable
private fun KaspaHubGrid(
    hub: List<AppTab>,
    badgeCount: (AppTab) -> Int,
    onOpen: (AppTab) -> Unit,
) {
    val colors = LocalAppColors.current
    val dockInset = LocalDockInset.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Scaffold(
        containerColor = colors.background,
        // The grid runs on under the dock; it pads its own end.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = stringResource(AppTab.HUB.labelRes),
                actions = {
                    // iOS slider.horizontal.3: Customize Dock, as a sheet over the tabs
                    ToolbarIconButton(Icons.Outlined.Tune, stringResource(R.string.shell_customize_dock), onClick = { CustomizeDock.open() })
                },
            )
        },
    ) { padding ->
        if (hub.isEmpty()) {
            HubEmptyState(Modifier.padding(padding))
            return@Scaffold
        }
        // Three across, so the tiles line up the same way whatever the count.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // iOS's 16 sides and 8 top as content padding: a right-column badge sits 6 past its tile.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + dockInset),
        ) {
            items(hub, key = { it.key }) { tab ->
                HubTile(
                    tab = tab,
                    badgeCount = badgeCount(tab),
                    onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onOpen(tab)
                    },
                )
            }
        }
    }
}

/** Every tab is in the dock: nothing left for the grid (iOS `emptyState`). */
@Composable
private fun HubEmptyState(modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = null,
            modifier = Modifier.size(44.dp).alpha(0.5f),
        )
        Text(stringResource(R.string.shell_hub_empty_title), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(
            stringResource(R.string.shell_hub_empty_body),
            color = colors.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * One tile: a square from the grid column, the icon and label laid over it, so every tile is the
 * same size whatever it is called. iOS: `.regularMaterial` in an 18pt rounded square with a 0.8
 * white hairline at 15%, the icon in a 30pt-tall slot, 10 above a caption-semibold label, and the
 * dock's badge on the top-right corner, 6 out each way.
 */
@Composable
private fun HubTile(tab: AppTab, badgeCount: Int, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(18.dp)
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surface)
                .border(0.8.dp, Color.White.copy(alpha = 0.15f), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                    AppTabIcon(tab, 26.dp)
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(tab.labelRes),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.danger)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A tab's icon at a size, as the Hub and Customize Dock draw it (iOS `AppTabIcon`): Kaspa Hub is
 * the Kaspa logo, every other tab its symbol in the accent colour. A Material glyph is inset in
 * its box, so it is drawn a little larger than the iOS point size to match.
 */
@Composable
fun AppTabIcon(tab: AppTab, size: Dp) {
    if (tab == AppTab.HUB) {
        Image(
            painter = painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(tab.hubIcon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(size * 1.2f))
    }
}

/**
 * Kaspa Hub's dock icon (iOS `KaspaDockIcon`): the Kaspa logo as a template, so the dock tints it
 * selected or not like the symbols beside it. A tint colours a painter by its alpha alone and the
 * logo's white K is opaque, so the K is cut out of the disc (light pixels made clear), or it would
 * be a plain circle. Made once.
 */
@Composable
fun rememberKaspaDockPainter(): Painter {
    val context = LocalContext.current
    return remember {
        val cut = KaspaDockIconCache.bitmap ?: run {
            val source = BitmapFactory.decodeResource(context.resources, R.drawable.ic_kaspa_logo)
            val side = 96
            val scaled = Bitmap.createScaledBitmap(source, side, side, true).copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(side * side)
            scaled.getPixels(pixels, 0, side, 0, 0, side, side)
            for (i in pixels.indices) {
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // the lightness of the darkest channel: the teal stays, the white K goes
                val lightest = minOf(r, g, b) / 255.0
                val keep = 1.0 - ((lightest - 0.75) / 0.2).coerceIn(0.0, 1.0)
                val na = (a * keep).toInt().coerceIn(0, 255)
                pixels[i] = (na shl 24) or (r shl 16) or (g shl 8) or b
            }
            scaled.setPixels(pixels, 0, side, 0, 0, side, side)
            scaled.also { KaspaDockIconCache.bitmap = it }
        }
        BitmapPainter(cut.asImageBitmap())
    }
}

private object KaspaDockIconCache {
    @Volatile
    var bitmap: Bitmap? = null
}
