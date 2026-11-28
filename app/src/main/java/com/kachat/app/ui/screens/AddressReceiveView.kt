package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.TokenViewModel

/**
 * One line under a receive screen's address, or in its notes card: an icon in a colour, and the
 * text, already localized (iOS 5b7c772c `ReceiveNote`).
 */
data class ReceiveNote(val kind: Kind, val text: String) {
    enum class Kind { ALLOWED, WARNING, INFO, PRIVATE_ADDRESS, PUBLIC_ADDRESS, QUANTUM }

    /** SF Symbols checkmark.circle.fill, xmark.octagon.fill, info.circle.fill, eye.slash.fill,
     *  person.crop.circle.fill, shield.fill. */
    val icon: ImageVector
        get() = when (kind) {
            Kind.ALLOWED -> Icons.Filled.CheckCircle
            Kind.WARNING -> Icons.Filled.Cancel
            Kind.INFO -> Icons.Filled.Info
            Kind.PRIVATE_ADDRESS -> Icons.Filled.VisibilityOff
            Kind.PUBLIC_ADDRESS -> Icons.Filled.AccountCircle
            Kind.QUANTUM -> Icons.Filled.Shield
        }

    @Composable
    fun color(): Color = when (kind) {
        Kind.ALLOWED, Kind.PRIVATE_ADDRESS -> IosGreen
        Kind.WARNING -> LocalAppColors.current.danger
        Kind.INFO -> LocalAppColors.current.textSecondary
        Kind.PUBLIC_ADDRESS -> IosOrange
        Kind.QUANTUM -> KaspaTeal
    }

    private companion object {
        val IosGreen = Color(0xFF34C759)
        val IosOrange = Color(0xFFFF9500)
    }
}

/** The notes every Kaspa receive screen shares, worded once (iOS 5b7c772c `ReceiveNotes`). */
object ReceiveNotes {
    @Composable fun freshAddress(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.PRIVATE_ADDRESS, stringResource(R.string.receive_note_fresh_address))
    @Composable fun spendingAddress(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.PRIVATE_ADDRESS, stringResource(R.string.receive_note_spending_address))
    @Composable fun chattingAddress(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.PUBLIC_ADDRESS, stringResource(R.string.receive_note_chatting_address))
    @Composable fun quantumAddress(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.QUANTUM, stringResource(R.string.receive_note_quantum_address))
    @Composable fun kaspaAndKusdt(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.ALLOWED, stringResource(R.string.receive_note_kaspa_and_kusdt))
    @Composable fun kaspaOnly(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.ALLOWED, stringResource(R.string.receive_note_kaspa_only))
    @Composable fun noKusdtOnQuantum(): ReceiveNote = ReceiveNote(ReceiveNote.Kind.WARNING, stringResource(R.string.receive_note_no_kusdt_on_quantum))
}

/** The round Kaspa logo, sized for a receive screen's title (iOS 5b7c772c `KaspaLogoIcon`). */
@Composable
fun KaspaLogoIcon(size: Dp = 34.dp) {
    Image(
        painter = painterResource(R.drawable.ic_kaspa_logo),
        contentDescription = null,
        modifier = Modifier.size(size).clip(CircleShape),
    )
}

/**
 * Every receive screen in KaChat, as in KaPay (iOS 5b7c772c `AddressReceiveView`): the coin's icon
 * and [title], optional [choices] (Fresh / Chatting), the address's name, its QR on a white card,
 * the whole address, what it means for privacy, then what may be sent to it. A tap anywhere copies
 * the address (no Copy or Share buttons). No bar of its own: the screen that shows it adds Done or
 * Close ([AddressReceivePage]).
 *
 * [addressLabel]: "Address #4", "Chatting Address". [balanceLine]: the address's balance under
 * its name, where the screen shows one. [onHandedOut]: the address was copied (a fresh one goes on
 * the list).
 */
@Composable
fun AddressReceiveView(
    title: String,
    address: String,
    addressLabel: String? = null,
    balanceLine: String? = null,
    privacyNote: ReceiveNote? = null,
    notes: List<ReceiveNote> = emptyList(),
    onHandedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    choices: @Composable () -> Unit = {},
) {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val tokenViewModel: TokenViewModel = hiltViewModel()
    // a token sent here is noticed by kUSDT's regular look (it watches shown addresses)
    LaunchedEffect(address) {
        if (address.isNotEmpty()) tokenViewModel.noteReceiveAddressShown(address)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // the whole screen copies; the choice pills are buttons and still switch
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (address.isEmpty()) return@clickable
                clipboard.setText(AnnotatedString(address))
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                onHandedOut()
                com.kachat.app.util.showAddressCopiedToast(context, address, haptic = false)
            }
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Text(title, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        choices()

        if (addressLabel != null || balanceLine != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (addressLabel != null) {
                    Text(addressLabel, color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                if (balanceLine != null) {
                    Text(
                        balanceLine,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp)
                .size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (address.isNotEmpty()) {
                Image(
                    painter = rememberQrBitmapPainter(address),
                    contentDescription = stringResource(R.string.qr_code),
                    modifier = Modifier.size(240.dp),
                )
            } else {
                com.kachat.app.ui.theme.IosActivityIndicator(color = Color.Gray)
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // the whole address, always: it wraps rather than truncates, with no hyphen
            // (`wrappingAnywhere`, iOS 824a30bb). The whole screen copies it, so no text
            // selection, which would copy the invisible break marks too.
            Text(
                address.wrappingAnywhere(),
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = address },
            )
            if (privacyNote != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(privacyNote.icon, contentDescription = null, tint = privacyNote.color(), modifier = Modifier.size(16.dp))
                    Text(privacyNote.text, color = colors.textSecondary, fontSize = 13.sp)
                }
            }
        }

        Text(stringResource(R.string.tap_anywhere_to_copy), color = colors.textSecondary, fontSize = 13.sp)

        if (notes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sendKaspaGlass(18.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                notes.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(note.icon, contentDescription = null, tint = note.color(), modifier = Modifier.size(18.dp))
                        Text(note.text, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * [AddressReceiveView] with the bar the screen that shows it adds: Back, Close or Done
 * ([dismiss]), on the app's background. For a pushed page ([PushedQrPage] with `white = false`)
 * or a sheet ([QrPageSheet] with `white = false`).
 */
@Composable
fun AddressReceivePage(
    dismiss: QrPageDismiss,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (dismiss == QrPageDismiss.BACK) 52.dp else 60.dp)
                .padding(horizontal = 16.dp)
        ) {
            when (dismiss) {
                QrPageDismiss.BACK -> SheetBarBackButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart))
                QrPageDismiss.CLOSE -> SheetBarButton(stringResource(R.string.close), onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart))
                QrPageDismiss.DONE -> SheetBarButton(stringResource(R.string.done), onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}
