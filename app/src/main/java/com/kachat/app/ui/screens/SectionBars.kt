package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The toolbar iOS gives a main page with no large title in its bar (the Address Book): the
 * [principal] item (the balance, unless the page has its own) centred, and [actions] on one piece
 * of glass trailing. With [onBack], the back chevron leads. No connection dot: since iOS 518fdc8c
 * it is only at the top of Settings and on the dock's Settings icon.
 */
@Composable
internal fun HubSectionTopBar(
    onBack: (() -> Unit)?,
    principal: @Composable () -> Unit = { BalanceTopBarLabel() },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    // The principal item placed as UIKit places it (see IosToolbarRow).
    IosToolbarRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null && !LocalInHubSection.current) {
                    // iOS 26: the chevron in the label colour on its glass.
                    ToolbarGlassGroup {
                        ToolbarIconButton(Icons.Filled.ArrowBackIosNew, androidx.compose.ui.res.stringResource(com.kachat.app.R.string.back), onBack, tint = colors.textPrimary, iconSize = 20.dp)
                    }
                }
            }
        },
        principal = principal,
        trailing = { ToolbarGlassGroup(content = actions) },
    )
}

// The underline tab bar (iOS UnderlineTabBar) is gone: every page switch inside a screen is the
// cycle button (`com.kachat.app.ui.components.PageCycleBar`, iOS 518fdc8c).
