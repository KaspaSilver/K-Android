package com.kachat.app.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics

/**
 * The page's reload, for the refresh button at the top right of every in-app website (iOS
 * 896e8194 `WebPageReloader`, owner 2026-11-26): [InAppBrowserScreen] (Websites, Kasshi, the
 * explorer, the faucet), Trestly's browser and Buy Kaspa's page. The web view registers itself
 * when it's made, and lets go when it's released.
 */
class WebPageReloader {
    var webView: WebView? = null
    /** Where the page started, for a first load that never got anywhere (`reload()` needs a page). */
    var startUrl: String? = null

    fun reload() {
        val view = webView ?: return
        val start = startUrl
        val shown = view.url
        if (start != null && (shown.isNullOrEmpty() || shown.startsWith("about:", ignoreCase = true))) {
            view.loadUrl(start)
        } else {
            view.reload()
        }
    }
}

/**
 * The top right of an in-app website (iOS 896e8194 `WebPageRefreshButton`): reload, showing a
 * spinner while the page loads (still a button then, so a page stuck loading can be loaded again).
 */
@Composable
fun WebPageRefreshButton(
    isLoading: Boolean,
    size: Dp = 34.dp,
    tint: Color = LocalAppColors.current.textPrimary,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val label = stringResource(R.string.buykaspa_reload)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            // iOS's .regularMaterial circle
            .background(if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f))
            .clickable(role = Role.Button) {
                haptic(IosHaptic.IMPACT_LIGHT)
                onClick()
            }
            .semantics { contentDescription = label },
    ) {
        if (isLoading) {
            IosActivityIndicator(color = tint, modifier = Modifier.size(18.dp))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
