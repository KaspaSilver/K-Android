package com.kachat.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dock's icons (iOS 518fdc8c `MainTabView.dockTabView`): Kaspa Hub is the Kaspa mark, Settings
 * the gear with the connection dot (`SettingsDockIcon`: green connected, orange connecting, red
 * after 8 s disconnected; the only place besides the top of Settings it shows), Profile your own
 * picture (`DockAvatar`, the person glyph until you have one) with a yellow dot while the account's
 * backup is owed (`ProfileDockIcon`), and every other tab its symbol.
 *
 * Each dot and the avatar collect their own state inside their own small composables, so a color
 * change, a backup finished or a picture landing redraws that icon alone, never the dock or the
 * shell around it - and only when what it shows really changed (iOS audit 2026-11-24 #25, #29).
 */
@Composable
internal fun DockTabIcon(
    tab: AppTab,
    selected: Boolean,
    hubPainter: Painter,
    label: String,
    walletViewModel: WalletViewModel,
) {
    val tint = if (selected) KaspaTeal else LocalAppColors.current.textPrimary
    when (tab) {
        AppTab.PROFILE -> Box {
            DockProfilePicture(walletViewModel = walletViewModel, label = label, tint = tint)
            ProfileBackupDot(walletViewModel, Modifier.align(Alignment.TopEnd))
        }
        AppTab.SETTINGS -> Box {
            Icon(rememberVectorPainter(tab.dockIcon), contentDescription = label, tint = tint, modifier = Modifier.size(DOCK_SYMBOL_SIZE))
            SettingsConnectionDot(Modifier.align(Alignment.TopEnd))
        }
        else -> Icon(
            painter = if (tab == AppTab.HUB) hubPainter else rememberVectorPainter(tab.dockIcon),
            contentDescription = label,
            tint = tint,
            // the Kaspa mark at iOS's 24-point dock size
            modifier = Modifier.size(if (tab == AppTab.HUB) 25.dp else DOCK_SYMBOL_SIZE),
        )
    }
}

/** A Material symbol on the dock. */
private val DOCK_SYMBOL_SIZE = 28.dp

/** iOS `systemYellow`: the backup dot. */
private val BackupDotYellow = Color(0xFFFFCC00)

/**
 * A status dot on a dock icon's top right (iOS `DockDotIcon`): 9 across, ringed 1.5 in the dock's
 * own black or white so it stands off the symbol under it.
 */
@Composable
private fun DockDot(color: Color, modifier: Modifier = Modifier) {
    val ring = if (LocalAppColors.current.isDark) Color.Black else Color.White
    Box(
        modifier = modifier
            .offset(x = 3.dp, y = (-3).dp)
            .size(12.dp)
            .background(ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
    }
}

/** The connection's color on the dock's Settings icon (iOS `ConnectionHealth`, `SettingsDockIcon`). */
@Composable
private fun SettingsConnectionDot(modifier: Modifier) {
    val dotColorHex by hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>().dotColorHex.collectAsState()
    DockDot(Color(dotColorHex), modifier)
}

/** The yellow dot on Profile while the account is owed its backup (iOS `ProfileDockIcon`). */
@Composable
private fun ProfileBackupDot(walletViewModel: WalletViewModel, modifier: Modifier) {
    val address by walletViewModel.address.collectAsState()
    val pending by walletViewModel.accountBackupStatus.pendingAddresses.collectAsState()
    val owed = address?.let { it in pending } == true
    if (owed) DockDot(BackupDotYellow, modifier)
}

/**
 * Your own picture for the dock's Profile icon (iOS `DockAvatar`): the photo you gave your own
 * address in Contacts, else your `.kachat` avatar - the same picture the Profile screen shows.
 * The person glyph, tinted like the other symbols, until there is one (and while it loads).
 */
@Composable
private fun DockProfilePicture(walletViewModel: WalletViewModel, label: String, tint: Color) {
    val address by walletViewModel.address.collectAsState()
    val own = address?.takeIf { it.isNotEmpty() }
    val book = com.kachat.app.services.AddressBookManager.shared
    // read as state: a new or removed photo redraws this icon
    val photoVersion = book?.photoVersion ?: 0
    val assigned by produceState<ImageBitmap?>(initialValue = null, own, photoVersion, book) {
        value = if (own == null || book == null) {
            null
        } else {
            (book.cachedPhoto(own) ?: withContext(Dispatchers.IO) { book.photo(own) })?.asImageBitmap()
        }
    }
    val avatarUrl = if (own != null && com.kachat.app.services.kachatnames.KachatNamesService.isEnabled) {
        com.kachat.app.ui.screens.kachatAvatarUrl(own)
    } else {
        null
    }
    // kept on the phone for the sign-in screen and the account switcher (iOS 8cb4c88e)
    com.kachat.app.ui.screens.SavedAccountAvatarRecorder(own, avatarUrl)
    val glyph: @Composable () -> Unit = {
        Icon(rememberVectorPainter(AppTab.PROFILE.dockIcon), contentDescription = label, tint = tint, modifier = Modifier.size(DOCK_SYMBOL_SIZE))
    }
    val shownAssigned = assigned
    when {
        shownAssigned != null -> DockAvatarFrame {
            Image(
                bitmap = shownAssigned,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(DOCK_AVATAR_SIZE).clip(CircleShape),
            )
        }
        !avatarUrl.isNullOrBlank() -> {
            var loaded by remember(avatarUrl) { mutableStateOf(false) }
            Box(Modifier.size(DOCK_SYMBOL_SIZE), contentAlignment = Alignment.Center) {
                coil.compose.AsyncImage(
                    model = com.kachat.app.ui.screens.rememberProfileImageModel(avatarUrl),
                    contentDescription = if (loaded) label else null,
                    imageLoader = com.kachat.app.services.LinkPreviewService.profileImageLoader(LocalContext.current),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(DOCK_AVATAR_SIZE).clip(CircleShape).alpha(if (loaded) 1f else 0f),
                    onSuccess = { loaded = true },
                    onError = { loaded = false },
                )
                if (!loaded) glyph()
            }
        }
        else -> glyph()
    }
}

/** The tab bar draws its pictures at about 26 points (iOS `DockAvatar.side`). */
private val DOCK_AVATAR_SIZE: Dp = 26.dp

/** The avatar centred in the symbol's box, so the label under it doesn't move. */
@Composable
private fun DockAvatarFrame(content: @Composable () -> Unit) {
    Box(Modifier.size(DOCK_SYMBOL_SIZE), contentAlignment = Alignment.Center) { content() }
}
