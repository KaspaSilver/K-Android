package com.kachat.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.AppTab
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kaspa Hub card links (iOS 961fc05a, 2026-11-25): holding a Hub card copies
 * `https://kachat.app/hub/<slug>`, which opens that card for whoever taps it ([KaChatLinkRef.HubSection]).
 * The slugs are fixed words (iOS `AppTab.linkSlug`), never the enum's name or key, so renaming a
 * case can't break links already shared. Matched on the tab's [AppTab.key] (iOS's raw value), so a
 * card added or retired (Stats left for the Explorer) needs no change here beyond its word.
 */
object HubCardLinks {
    /** A Hub card's word in its link; null for the dock's tabs, which aren't Hub cards. */
    fun slug(tab: AppTab): String? = slugForKey(tab.key)

    internal fun slugForKey(key: String): String? = when (key) {
        "addressBook" -> "contacts"
        "portfolio" -> "portfolio"
        "coldStorage" -> "cold-storage"
        "kachatNames" -> "usernames"
        "kaposts" -> "kapost"
        "games" -> "kaplay"
        "apps" -> "websites"
        "explorer" -> "explorer"
        "kasshi" -> "kasshi"
        else -> null
    }

    /** The card a link names, case-insensitively; null for anything unknown. */
    fun tab(slug: String): AppTab? {
        val wanted = slug.trim().lowercase()
        if (wanted.isEmpty()) return null
        return AppTab.entries.firstOrNull { slug(it) == wanted }
    }

    /** What a hold on [tab]'s card copies; null for a tab that isn't a Hub card. */
    fun shareUrl(tab: AppTab): String? = slug(tab)?.let { "https://${KaChatLink.WEB_HOST}/hub/$it" }

    /** One line on what the card holds, for its link's card (iOS `AppTab.hubBlurb`). */
    @StringRes
    fun blurbRes(tab: AppTab): Int? = when (tab.key) {
        "addressBook" -> R.string.kp55_hub_blurb_contacts
        "portfolio" -> R.string.kp55_hub_blurb_portfolio
        "coldStorage" -> R.string.kp55_hub_blurb_cold_storage
        "kachatNames" -> R.string.kp55_hub_blurb_usernames
        "kaposts" -> R.string.kp55_hub_blurb_kapost
        "games" -> R.string.kp55_hub_blurb_kaplay
        "apps" -> R.string.kp55_hub_blurb_websites
        "explorer" -> R.string.kp55_hub_blurb_explorer
        "kasshi" -> R.string.hub_blurb_kasshi
        else -> null
    }
}

/**
 * A Hub card's link waiting to open (iOS `PendingTabRoute.pending` + `.openHubSection`). Pending
 * state rather than an event, so a link that cold-starts K opens its card once the shell is up.
 * The shell closes KaPost's screens first (audit 2026-11-25 L1: a card link tapped in an opened
 * post or profile switched the tab under KaPost's cover, so nothing seemed to happen), then shows
 * the card wherever it lives.
 */
object HubLinkRouter {
    private val _pending = MutableStateFlow<AppTab?>(null)
    val pending: StateFlow<AppTab?> = _pending.asStateFlow()

    fun open(tab: AppTab) {
        if (HubCardLinks.slug(tab) == null) return
        _pending.value = tab
    }

    fun consume() {
        _pending.value = null
    }
}

/**
 * A held Hub card's half sheet (iOS `KaspaHubView.cardActionSheet`, `KaspaHubLinkCard.actionSheet`):
 * Copy Link, whose `https://kachat.app/hub/<slug>` opens this card in K for whoever taps it, and
 * Open. [onCopied] runs after the link is on the clipboard (the Hub shows "Link copied").
 */
@Composable
fun HubCardActionSheet(tab: AppTab, onDismiss: () -> Unit, onOpen: () -> Unit, onCopied: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    TileActionSheet(
        title = stringResource(tab.labelRes),
        onDismiss = onDismiss,
        height = ActionSheetTileMetrics.sheetHeight(tiles = 2),
    ) {
        ActionSheetRow(
            icon = Icons.Default.Link,
            title = stringResource(R.string.copy_link),
            subtitle = stringResource(R.string.kp55_hub_link_subtitle),
        ) {
            onDismiss()
            val url = HubCardLinks.shareUrl(tab) ?: return@ActionSheetRow
            clipboard.setText(AnnotatedString(url))
            haptic(IosHaptic.SUCCESS)
            onCopied()
        }
        ActionSheetRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = stringResource(R.string.kp55_open),
            subtitle = stringResource(R.string.kp55_opens_this_card),
        ) {
            onDismiss()
            onOpen()
        }
    }
}

/**
 * A Kaspa Hub card's link (`kachat.app/hub/<slug>`) drawn in a KaPost and its composer, in KaPost's
 * own card style (as [KaPostPaymentRequestCard]): the card's icon, "Kaspa Hub", its name and what it
 * holds, and Open, which opens that section in K. Holding it opens a half sheet of tiles (Copy
 * Link, Open), as every long-press menu in the app. Chats draw the same link as their internal link
 * card ([KaChatInternalLinkCard]). iOS `KaspaHubLinkCard`.
 *
 * [interactive] false is the composer's preview: drawn the same, but nothing opens or copies, so a
 * tap there can't leave the post being written (audit 2026-11-25 L1).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun KaspaHubLinkCard(tab: AppTab, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    var showsActions by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val copyLabel = stringResource(R.string.copy_link)
    val opensLabel = stringResource(R.string.kp55_opens_it_in_k)
    fun open() {
        haptic(IosHaptic.IMPACT_LIGHT)
        openKaChatLink(context, KaChatLinkRef.HubSection(tab))
    }

    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(KaspaTeal.copy(alpha = 0.08f))
        .border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
    val gestures = if (interactive) {
        base
            .combinedClickable(
                role = Role.Button,
                onClickLabel = opensLabel,
                onLongClick = {
                    haptic(IosHaptic.IMPACT_MEDIUM)
                    showsActions = true
                },
                onClick = { if (!showsActions) open() },
            )
            .semantics(mergeDescendants = true) {
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) {
                        HubCardLinks.shareUrl(tab)?.let { clipboard.setText(AnnotatedString(it)) }
                        true
                    },
                )
            }
    } else {
        base.semantics(mergeDescendants = true) {}
    }

    Row(
        modifier = gestures.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KaspaTeal.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            AppTabIcon(tab, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.shell_kaspa_hub).uppercase(),
                color = KaspaTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                stringResource(tab.labelRes),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HubCardLinks.blurbRes(tab)?.let { blurb ->
                Text(
                    stringResource(blurb),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.kp55_open),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(KaspaTeal)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (interactive && showsActions) {
        HubCardActionSheet(
            tab = tab,
            onDismiss = { showsActions = false },
            onOpen = { open() },
        )
    }
}
