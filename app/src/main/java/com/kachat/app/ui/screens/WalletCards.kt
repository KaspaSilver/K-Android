package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass

/** The caption chevron at a card's end (SF Symbols chevron.right, caption, secondary). */
@Composable
internal fun CardChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = LocalAppColors.current.textSecondary,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * A card in Wallet's list (iOS 5b7c772c `WalletView.chattingAddressSection`, `notificationsSection`,
 * `YourDomainsCard`, as KaPay's Wallet tab has them): a Label - the outline symbol and the body
 * title - then [trailing] (a count) and the chevron, padded 16, on the glass card (18, hairline,
 * soft shadow).
 */
@Composable
fun WalletLinkCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(8.dp))
        CardChevron()
    }
}

/** Your Domains' count and Notifications' unread count: the number in secondary (iOS
 *  `.foregroundColor(.secondary)`). */
@Composable
fun WalletCardCount(value: String) {
    Text(value, color = LocalAppColors.current.textSecondary, fontSize = 17.sp, maxLines = 1)
}

/**
 * A card like Connection Settings' (iOS 5b7c772c `ConnectionSettingsView.connectionCard`, as KaPay
 * has it): the [icon] in its accent circle, [title] (headline) with [subtitle] under it, then
 * [trailing] and the chevron. Also Address Visibility's chain cards and Your Domains' service cards.
 */
@Composable
fun ConnectionStyleCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            subtitle()
        }
        Spacer(Modifier.width(8.dp))
        trailing()
        Spacer(Modifier.width(8.dp))
        CardChevron()
    }
}

/** A [ConnectionStyleCard]'s caption line: monospaced, secondary, one line cut in the middle
 *  (iOS `.font(.caption.monospaced())`, `.truncationMode(.middle)`). */
@Composable
fun ConnectionCardCaption(text: String) {
    MiddleEllipsisText(
        text,
        color = LocalAppColors.current.textSecondary,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}

/**
 * Share Profile (iOS 5b7c772c `ShareProfileSheet`): your Chatting Address's QR on white, the
 * address, and Copy Share Link, which puts your profile link (the one User Info > Share sends: it
 * previews with your name and avatar) on the clipboard and says so.
 */
@Composable
fun ShareProfileSheet(address: String, shareUrl: String, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    QrPageSheet(onDismiss = onDismiss, white = false) {
        Column(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.profile_share_profile),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                SheetBarButton(stringResource(R.string.done), onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp)
                        .size(240.dp),
                ) {
                    Image(
                        painter = rememberQrBitmapPainter(address),
                        contentDescription = stringResource(R.string.qr_code),
                        modifier = Modifier.size(240.dp),
                    )
                }
                Text(
                    stringResource(R.string.chatting_address),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                SelectionContainer {
                    Text(
                        address,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(KaspaTeal)
                        .clickable {
                            clipboard.setText(AnnotatedString(shareUrl))
                            haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                            IosToasts.show(context.getString(R.string.share_link_copied))
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_share_link), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
