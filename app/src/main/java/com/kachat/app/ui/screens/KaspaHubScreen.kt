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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * goes back to the grid ([HubRouter.close]); so does the system Back, before anything else.
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

    // a section moved into the dock meanwhile must not stay open here too
    LaunchedEffect(hub, openSection) {
        val section = openSection
        if (section != null && section !in hub) HubRouter.close()
    }

    if (open != null) {
        // Registered before the section, so a section's own BackHandler (a sheet, a page inside
        // it) still comes first.
        BackHandler { HubRouter.close() }
        CompositionLocalProvider(LocalInHubSection provides true) {
            // keyed, so one section's remembered state never carries into the next
            androidx.compose.runtime.key(open) { content(open) }
        }
    } else {
        KaspaHubGrid(hub = hub, badgeCount = badgeCount, onOpen = { HubRouter.open(it) })
    }
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
