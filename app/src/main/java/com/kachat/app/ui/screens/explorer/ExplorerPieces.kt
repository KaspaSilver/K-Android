package com.kachat.app.ui.screens.explorer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.explorer.ExplorerTxKind
import com.kachat.app.ui.screens.ContactAvatar
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.SavedAccountAvatar
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.delay

/**
 * True where the Explorer's live pieces (the BlockDAG card, the live dot, the DAA counter, the
 * rows' ages) must stand still: its Hub section is hidden or the app isn't in front (iOS
 * `explorerPaused`, audit 2026-11-25 B2, E3).
 */
val LocalExplorerPaused = compositionLocalOf { false }

/** True while the home has no numbers yet: its texts draw as grey bars (iOS `.redacted(.placeholder)`). */
val LocalExplorerRedacted = compositionLocalOf { false }

/** iOS's tabular figures. */
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** iOS `.system(design: .rounded)`. */
internal val Rounded: FontFamily get() = com.kachat.app.ui.theme.IosRoundedFontFamily

/** Draws a grey bar in place of the content while [LocalExplorerRedacted] is on. */
@Composable
internal fun Modifier.explorerRedacted(): Modifier {
    if (!LocalExplorerRedacted.current) return this
    val bar = LocalAppColors.current.textSecondary.copy(alpha = 0.22f)
    return this.drawWithContent {
        drawRoundRect(bar, cornerRadius = CornerRadius(4.dp.toPx()))
    }
}

/** A grouped card: the cell colour, 16 corners (iOS `ExplorerCard`). */
@Composable
internal fun ExplorerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface),
        content = content,
    )
}

/** A card's hairline between rows, indented [start]. */
@Composable
internal fun ExplorerDivider(start: Dp) {
    HorizontalDivider(color = LocalAppColors.current.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = start))
}

@Composable
internal fun ExplorerSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        color = LocalAppColors.current.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 20.dp),
    )
}

/** A row that dims while pressed, as the Chats list's do (iOS `ExplorerRowStyle`). */
@Composable
internal fun Modifier.explorerRow(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedColor = if (LocalAppColors.current.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    return this
        .background(if (pressed) pressedColor else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Monospaced, cut in the middle when [middle] (iOS `ExplorerMonoText`). */
@Composable
internal fun ExplorerMonoText(text: String, middle: Boolean = false, modifier: Modifier = Modifier, color: Color = LocalAppColors.current.textSecondary) {
    Text(
        if (middle) ExplorerFormat.short(text) else text,
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
internal fun ExplorerChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = LocalAppColors.current.textSecondary,
        modifier = Modifier.size(18.dp),
    )
}

/** The kind's SF symbol, as Material draws it. */
internal fun ExplorerTxKind.icon(): ImageVector = when (this) {
    ExplorerTxKind.PAYMENT -> Icons.Filled.SwapHoriz
    ExplorerTxKind.KACHAT_MESSAGE -> Icons.Filled.Lock
    ExplorerTxKind.HANDSHAKE -> Icons.Filled.WavingHand
    ExplorerTxKind.PAYMENT_NOTICE -> Icons.Filled.Notifications
    ExplorerTxKind.KAPOST -> Icons.AutoMirrored.Filled.Article
    ExplorerTxKind.PUBLIC_CHAT -> Icons.Filled.Tag
    ExplorerTxKind.KACHAT_NAME -> Icons.Filled.AlternateEmail
    ExplorerTxKind.KUSDT_TRANSFER -> Icons.Filled.AttachMoney
    ExplorerTxKind.KUSDT_SWAP -> Icons.Filled.Autorenew
    ExplorerTxKind.CHESS_MOVE -> Icons.Filled.GridOn
    ExplorerTxKind.ARCADE_STAMP -> Icons.Filled.SportsEsports
    ExplorerTxKind.COINBASE -> Icons.Filled.Gavel
    ExplorerTxKind.OTHER -> ExplorerSymbols.CubeFill
}

/** The kind's icon, white on a teal disc: every kind is the app's teal (owner, 2026-11-25), the
 *  icon tells them apart. */
@Composable
internal fun ExplorerKindBadge(kind: ExplorerTxKind, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(KaspaTeal)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A capsule: an icon and a word in a colour, on that colour at 14%. */
@Composable
internal fun ExplorerPill(text: String, icon: ImageVector, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Where a transaction stands: Not Accepted, In the Mempool, In a Block, or Confirmed with its count. */
@Composable
internal fun ExplorerStatusPill(confirmations: Int, inMempool: Boolean, accepted: Boolean?) {
    val colors = LocalAppColors.current
    when {
        accepted == false -> ExplorerPill(stringResource(R.string.ex_not_accepted), Icons.Filled.Block, colors.textSecondary)
        inMempool -> ExplorerPill(stringResource(R.string.ex_in_mempool), Icons.Filled.HourglassTop, colors.warning)
        // seconds old: in a block, not yet counted by kaspa.stream
        confirmations == 0 -> ExplorerPill(stringResource(R.string.ex_in_block), Icons.Filled.Verified, colors.success)
        else -> ExplorerPill(
            stringResource(R.string.ex_confirmed_count, ExplorerFormat.grouped(confirmations.coerceAtLeast(0).toLong())),
            Icons.Filled.Verified,
            colors.success,
        )
    }
}

/** A detail row: the title, secondary, and its value trailing (iOS `ExplorerDetailRow`). */
@Composable
internal fun ExplorerDetailRow(
    title: String,
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
    value: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = LocalAppColors.current.textSecondary, fontSize = 15.sp, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
        if (chevron) ExplorerChevron()
    }
}

/** A detail row's plain value. */
@Composable
internal fun ExplorerValueText(text: String) {
    Text(text, color = LocalAppColors.current.textPrimary, fontSize = 15.sp, maxLines = 1, style = Tabular)
}

/** A long id cut in the middle, with a copy button. */
@Composable
internal fun ExplorerCopyValue(value: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptics()
    val copied = stringResource(R.string.ab_copied)
    val hint = stringResource(R.string.ex_copies_it)
    Row(
        Modifier.clickable(onClickLabel = hint) {
            clipboard.setText(AnnotatedString(value))
            haptic(IosHaptic.SUCCESS)
            IosToasts.show(copied)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExplorerMonoText(value, middle = true)
        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
    }
}

/** A chip under an address: an icon and a word, in the accent, on a capsule. */
@Composable
internal fun ExplorerActionChip(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(15.dp))
        Text(title, color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A home tile: its icon on a disc (or the Kaspa logo), title, value and a caption; a chevron when
 *  it opens a chart (iOS `ExplorerStatTile`). */
@Composable
internal fun ExplorerStatTile(
    icon: ImageVector?,
    title: String,
    value: String,
    detail: String?,
    modifier: Modifier = Modifier,
    showsChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val base = modifier
        .clip(RoundedCornerShape(16.dp))
        .background(colors.surface)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = 98.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon == null) {
                // the Kaspa logo is its own teal disc
                Image(painterResource(R.drawable.ic_kaspa_logo), contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Box(Modifier.size(22.dp).clip(CircleShape).background(KaspaTeal), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
            // All the row's free width, as iOS's title before its Spacer: a weighted Spacer for the
            // chevron took half of it, which cut "Network Fee" to "Networ…" on a Pixel 8 (release
            // audit 2026-11-27 E2). A translation too long for one line wraps to a second.
            Box(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.explorerRedacted(),
                )
            }
            if (showsChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Box(Modifier.explorerRedacted()) {
            KachatFitText(value, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Rounded, minScale = 0.6f)
        }
        if (detail != null) {
            Text(
                detail,
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.explorerRedacted(),
            )
        }
    }
}

/** An input or output with no address to open: an input read off the node (its coin's address
 *  isn't in the block) or an output to a script with none (audit 2026-11-25 C11). [amount] null:
 *  not known. */
@Composable
internal fun ExplorerUnknownIORow(label: String, amount: Long?) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colors.textSecondary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.QuestionMark, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        Text(label, color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            amount?.let { ExplorerFormat.kas(it) } ?: "–",
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            style = Tabular,
        )
    }
}

/** This account's own picture, as the account switcher shows it. */
@Composable
internal fun ExplorerOwnAvatar(address: String?, name: String, size: Dp) {
    if (address != null) SavedAccountAvatar(address = address, name = name, size = size)
    else ContactAvatar(imageUrl = null, fallbackText = "K", size = size, includeAssignedPhoto = false)
}

/** "12 secs. ago", run on once a second while the Explorer shows; one small text per row, so the
 *  home itself doesn't redraw every second (audit 2026-11-25 E3). */
@Composable
internal fun ExplorerAgoText(date: Long, color: Color = LocalAppColors.current.textSecondary, fontSize: TextUnit = 12.sp) {
    val context = LocalContext.current
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
    Text(ExplorerFormat.ago(context, date, now.longValue), color = color, fontSize = fontSize, maxLines = 1, style = Tabular)
}

/** The live dot: green, its ring pulsing every 1.2 s while the Explorer shows and still (no ring)
 *  while it doesn't, a frame clock that pauses rather than an endless animation (audit B2). */
@Composable
internal fun ExplorerLiveDot() {
    val paused = LocalExplorerPaused.current
    val green = Color(0xFF34C759)
    val phase = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    LaunchedEffect(paused) {
        if (paused) {
            phase.floatValue = 1f
            return@LaunchedEffect
        }
        while (true) {
            androidx.compose.runtime.withFrameMillis { now ->
                // 0 to 1 over each 1.2 s, eased out
                val linear = (now % 1200L) / 1200f
                phase.floatValue = 1 - (1 - linear) * (1 - linear)
            }
            delay(33)
        }
    }
    // The ring is drawn, not laid out: the phase is read only in the draw lambda, so each pulse
    // frame redraws the dot instead of recomposing and re-measuring it (audit 2026-11-25 R9).
    // Same picture as before: the inner half of a 2 dp stroke on a circle 8 dp x (1 + 1.2 p)
    // across, at 60% green faded by 1 - p.
    Box(
        Modifier
            .size(8.dp)
            .drawBehind {
                val p = phase.floatValue
                if (p >= 1f) return@drawBehind
                val stroke = 1.dp.toPx()
                val outer = size.minDimension / 2f * (1 + 1.2f * p)
                drawCircle(
                    green.copy(alpha = 0.6f * (1 - p)),
                    radius = outer - stroke / 2f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(green))
    }
}

/** A list still waiting for its first answer. */
@Composable
internal fun ExplorerLoadingRow() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        com.kachat.app.ui.theme.IosActivityIndicator()
    }
}

/** Provides the redaction flag for the home's numbers. */
@Composable
internal fun ExplorerRedaction(redacted: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExplorerRedacted provides redacted, content = content)
}
