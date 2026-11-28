package com.kachat.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.AppTab
import com.kachat.app.ui.DockLayout
import com.kachat.app.ui.HubRouter
import com.kachat.app.ui.theme.KaspaTeal

/**
 * The Kaspa Hub tab (iOS `KaspaHubView`): the section picked from Kaspa Hub's menu
 * ([KaspaHubMenu]). The section owns the whole screen, exactly as it looks from its own dock slot
 * ([content] draws the same screen the dock would). The system Back closes it
 * ([HubRouter.close]), after the section's own.
 *
 * A section opened here stays alive, hidden, once left (iOS 428a797b `keptSections`, audit
 * 2026-11-21 #28): KaPost, Portfolio, KaPlay, .kachat, the Explorer and Cold Storage come back exactly
 * as they were - feed, scroll position, the page inside them - instead of loading again
 * ([KEPT_ALIVE]). The others (Chats, Wallet, Contacts, Settings, Kaspa Websites) still close when
 * left: an open chat marks itself read until it leaves, and a web page would keep playing. A
 * hidden section is composed but neither measured, drawn nor touchable, its Back handlers are
 * parked, and [LocalHubSectionHidden] tells what runs inside it ([OnScreenPresence]). Kept
 * sections live as long as this screen does; their saved state outlives it (a dock tab switch
 * disposes the Hub's route), so one opened again after that still comes back where it was left.
 */
@Composable
fun KaspaHubScreen(
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
    // Left over a minute: the section was forgotten ([HubRouter.forgetIfStale]), and the kept
    // ones go with it, so nothing comes back as it was left (iOS b49a021d `forgetCount`). The
    // forget happens while this screen is off the back stack's top (its saved state kept by the
    // tab switch), so it's applied here when the screen next draws, before any section restores
    // its saved state.
    val forgetCount by HubRouter.forgetCount.collectAsState()
    var forgetsApplied by rememberSaveable { mutableStateOf(0) }
    if (forgetCount != forgetsApplied) {
        val keep = HubRouter.openSection.value
        AppTab.entries.filter { it != keep }.forEach { sectionStates.removeState(it.key) }
        keptSections = keptSections.filter { it == keep }
        forgetsApplied = forgetCount
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
        // No grid: Kaspa Hub is a menu (owner, 2026-11-26, [KaspaHubMenu]), the way into every
        // section; the grid and its hold sheet were removed (iOS d88891ea, audit 2026-11-27).
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
    AppTab.KAPOSTS, AppTab.PORTFOLIO, AppTab.GAMES, AppTab.KACHAT_NAMES, AppTab.COLD_STORAGE, AppTab.EXPLORER,
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

/**
 * A tab's icon at a size, as the Hub draws it (iOS `AppTabIcon`): Kaspa Hub is the Kaspa logo,
 * every other tab its symbol in the accent colour, Usernames included: its "@" (owner, iOS
 * 522fd9f4; the ".kachat" wordmark until then). A Material glyph is inset in its box, so it is drawn
 * a little larger than the iOS point size to match.
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
