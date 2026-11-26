package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosShadow

// Kaspa Websites (iOS 148a5331 phase 5, `.apps` / `ProfileAppsView`): restored from KaChat-Android
// 21e9d74d^ (KaspaAppsScreens.kt), as it was before it left on 2026-10-24.

/**
 * One bubble in the Kaspa Websites grid. [name] is iOS's display name ("Kaspa Stream", not the
 * domain); [icon] its SF Symbol's closest Material glyph, unless [usesKaspaLogo] draws the Kaspa
 * mark instead.
 */
data class KaspaWebsite(
    val name: String,
    val url: String,
    val icon: ImageVector = Icons.Outlined.Language,
    val usesKaspaLogo: Boolean = false,
)

/** Same curated list, names, icons and order as iOS's ProfileAppsView. */
val KASPA_WEBSITES = listOf(
    KaspaWebsite("Kaspa.org", "https://kaspa.org", usesKaspaLogo = true),
    KaspaWebsite("Kaspa Stream", "https://kaspa.stream", Icons.Outlined.MonitorHeart),          // waveform.path.ecg
    KaspaWebsite("Kaspa Explorer", "https://explorer.kaspa.org", Icons.Outlined.Search),        // magnifyingglass
    KaspaWebsite("KasMap", "https://kasmap.org", Icons.Outlined.Map),                           // map
    KaspaWebsite("Kaspa News", "https://kaspa.news", Icons.Outlined.Newspaper),                 // newspaper
    KaspaWebsite("KasPlay", "https://kasplay.fun", Icons.Outlined.SportsEsports),               // gamecontroller
    KaspaWebsite("KasMart", "https://kasmart.org", Icons.Outlined.ShoppingCart),                // cart
    KaspaWebsite("KasMedia", "https://kasmedia.com", Icons.Outlined.Article),                   // doc.text.image
    KaspaWebsite("Kaspalytics", "https://www.kaspalytics.com", Icons.Outlined.Leaderboard),     // chart.bar
    KaspaWebsite("Kas-Smiths", "https://kas-smiths.org", Icons.Outlined.Hardware),              // hammer
    KaspaWebsite("Kaspa Core R&D", "https://t.me/kasparnd", Icons.Outlined.Science),            // atom
    // Free testnet-10 KAS for the app's testnet mode (Settings > Connection > Testnet uses TN10).
    KaspaWebsite("TN10 Faucet", "https://faucet-tn10.kaspanet.io", Icons.Filled.WaterDrop),     // drop.fill
)

/**
 * The Kaspa Websites tab (iOS `ProfileAppsView` in `AppTabContent(.apps)`): a large "Kaspa
 * Websites" title under the connection dot and balance, then the bubbles; each site opens full
 * screen in the in-app browser ([InAppBrowserScreen]), whose X is the only way out.
 */
@Composable
fun KaspaWebsitesScreen() {
    val colors = LocalAppColors.current
    var openUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var openTitle by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = { MainPageHeader(title = stringResource(R.string.shell_kaspa_websites)) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = LocalDockInset.current),
        ) {
            KaspaWebsitesGrid(onOpen = { site ->
                openTitle = site.name
                openUrl = site.url
            })
        }
    }
    openUrl?.let { url ->
        // iOS .fullScreenCover
        Dialog(
            onDismissRequest = { openUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            InAppBrowserScreen(url = url, title = openTitle, onClose = { openUrl = null })
        }
    }
}

/**
 * The bubble grid, laid out as iOS's `LazyVGrid(columns: [GridItem(.adaptive(minimum: 96),
 * spacing: 20)], spacing: 24)`: as many 96-wide columns as fit 20 apart (three on a phone), the
 * rows 24 apart, each bubble centred in its column.
 */
@Composable
private fun KaspaWebsitesGrid(onOpen: (KaspaWebsite) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val perRow = maxOf(1, ((maxWidth + 20.dp) / (96.dp + 20.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            KASPA_WEBSITES.chunked(perRow).forEach { rowSites ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    rowSites.forEach { site ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            KaspaWebsiteBubble(site, onClick = { onOpen(site) })
                        }
                    }
                    // A short last row keeps its columns where the full rows have them.
                    repeat(perRow - rowSites.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One site: iOS's 68pt `.regularMaterial` circle with a 0.8 white hairline at 18% and a soft
 * shadow (black at 12%, radius 8, 4 down), the 28pt semibold symbol in the accent colour (the
 * Kaspa mark 34 square), and the name 8 below in caption semibold on one line.
 */
@Composable
private fun KaspaWebsiteBubble(site: KaspaWebsite, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .iosShadow(34.dp, Color.Black.copy(alpha = 0.12f), 8.dp, 4.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (site.usesKaspaLogo) {
                Image(painter = painterResource(R.drawable.ic_kaspa_logo), contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                // A Material glyph is inset in its box; 32 draws it the size of a 28pt symbol.
                Icon(site.icon, null, tint = KaspaTeal, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            site.name,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
