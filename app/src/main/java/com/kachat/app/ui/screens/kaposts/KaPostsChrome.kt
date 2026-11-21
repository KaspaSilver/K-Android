package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.ui.theme.IosSymbols
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/** A KaPost page's title, fixed above its scrolling content like the feed's (iOS
 *  `kaPostsFixedTitle`). The backup strip sits above the title (iOS e5a9b11); [onOpenBackup]
 *  opens the backup steps. */
@Composable
fun KaPostsFixedTitle(title: String, onOpenBackup: (() -> Unit)? = null) {
    val colors = LocalAppColors.current
    if (onOpenBackup != null) {
        val walletViewModel: WalletViewModel = hiltViewModel()
        AccountBackupBanner(viewModel = walletViewModel, onOpenBackup = onOpenBackup)
    }
    Text(
        text = title,
        color = colors.textPrimary,
        fontSize = LargeTitleSize,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
    )
    androidx.compose.material3.HorizontalDivider(color = colors.surfaceVariant)
}

/** Repost as-is, or quote it with your own words (iOS `RepostActionsSheet`). */
@Composable
fun RepostActionsSheet(
    isReposted: Boolean,
    onDismiss: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
) {
    ActionSheetContainer(
        title = if (isReposted) stringResource(R.string.kpui_reposted) else stringResource(R.string.kpui_repost),
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = if (isReposted) Icons.AutoMirrored.Filled.Undo else Icons.Default.Repeat,
            title = if (isReposted) stringResource(R.string.kpui_undo_repost) else stringResource(R.string.kpui_repost),
            subtitle = if (isReposted) stringResource(R.string.kpui_removes_from_profile) else stringResource(R.string.kpui_shares_to_followers),
            tint = if (isReposted) LocalAppColors.current.danger else KaspaTeal,
        ) { onDismiss(); onRepost() }
        ActionSheetRow(
            icon = IosSymbols.QuoteBubble,
            title = stringResource(R.string.kpui_quote),
            subtitle = stringResource(R.string.kpui_adds_your_own_words),
        ) { onDismiss(); onQuote() }
    }
}

/**
 * The connection dot inside a [ToolbarGlassGroup] ([ownGlass] false: iOS 26 draws it as one
 * capsule with its neighbours) - KaPost's form of [ConnectionDotButton], which Public Chats'
 * screens call too. [ownGlass] has no default, so a call without it is KaChat's own dot.
 */
@Composable
internal fun ConnectionDotButton(onClick: () -> Unit, modifier: Modifier = Modifier, ownGlass: Boolean) =
    KaPostsConnectionDotButton(onClick = onClick, modifier = modifier, ownGlass = ownGlass)
