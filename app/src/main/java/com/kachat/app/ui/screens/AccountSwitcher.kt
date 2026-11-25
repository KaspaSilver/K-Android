package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.SavedAccountAvatars
import com.kachat.app.services.WalletManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Switching accounts (iOS 8cb4c88e, owner 2026-11-24): the glass chip beside Profile's title opens
// a half sheet of the accounts saved on this phone; tap one to switch. The sign-in screen shows
// each account's picture beside its name. The pictures are each account's own avatar, saved on
// the phone while that account was signed in (SavedAccountAvatars), so neither screen looks up
// another address.

/** The activity's WalletViewModel: the one whose sign-in state picks the sign-in screen or the
 *  app (Log Out uses it too). */
@Composable
private fun activityWalletViewModel(): WalletViewModel {
    val context = LocalContext.current
    return hiltViewModel(
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .first { it is androidx.activity.ComponentActivity } as androidx.activity.ComponentActivity
    )
}

/** A saved account's picture, or the person glyph until one is known (iOS `SavedAccountAvatar`). */
@Composable
fun SavedAccountAvatar(address: String, name: String, size: Dp) {
    val context = LocalContext.current
    val revision by SavedAccountAvatars.revision.collectAsState()
    val publicKey = remember(address) { SavedAccountAvatars.publicKeyOf(address) }
    val picture by produceState<ImageBitmap?>(
        initialValue = publicKey?.let { SavedAccountAvatars.cached(it) }?.asImageBitmap(),
        publicKey,
        revision,
    ) {
        value = publicKey?.let { key -> withContext(Dispatchers.IO) { SavedAccountAvatars.image(context, key) } }?.asImageBitmap()
    }
    val shown = picture
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        ContactAvatar(imageUrl = null, fallbackText = name, size = size, includeAssignedPhoto = false)
    }
}

/**
 * Keeps the signed-in account's picture for the sign-in screen and the account switcher (iOS
 * 8cb4c88e `DockAvatar.rememberForSignIn`), at a size those rows can use: the photo you gave your
 * own address in Contacts, else your `.kachat` avatar ([avatarUrl], what the dock shows). When the
 * profile is known and has no picture, the saved copy goes too (not while it is still loading,
 * which also reads as no picture). Called by the dock's Profile icon, which shows while signed in.
 */
@Composable
internal fun SavedAccountAvatarRecorder(address: String?, avatarUrl: String?) {
    if (address.isNullOrEmpty()) return
    val context = LocalContext.current
    val book = com.kachat.app.services.AddressBookManager.shared
    val photoVersion = book?.photoVersion ?: 0
    val identityRevision by com.kachat.app.services.kachatnames.KachatNamesRegistry.identityRevision.collectAsState()
    androidx.compose.runtime.LaunchedEffect(address, avatarUrl, photoVersion, identityRevision) {
        val publicKey = SavedAccountAvatars.publicKeyOf(address) ?: return@LaunchedEffect
        val assigned = withContext(Dispatchers.IO) { book?.cachedPhoto(address) ?: book?.photo(address) }
        if (assigned != null) {
            val source = "$publicKey|assigned|$photoVersion"
            if (source == lastRecordedAvatar && SavedAccountAvatars.cached(publicKey) != null) return@LaunchedEffect
            withContext(Dispatchers.IO) { SavedAccountAvatars.save(context, assigned, publicKey) }
            lastRecordedAvatar = source
            return@LaunchedEffect
        }
        if (!avatarUrl.isNullOrBlank()) {
            val source = "$publicKey|url|$avatarUrl"
            if (source == lastRecordedAvatar && SavedAccountAvatars.cached(publicKey) != null) return@LaunchedEffect
            val request = coil.request.ImageRequest.Builder(context)
                .data(avatarUrl)
                .size(SAVED_AVATAR_PIXELS)
                .allowHardware(false)
                .build()
            val result = com.kachat.app.services.LinkPreviewService.profileImageLoader(context).execute(request)
            val drawable = (result as? coil.request.SuccessResult)?.drawable ?: return@LaunchedEffect
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: runCatching {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: SAVED_AVATAR_PIXELS
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: SAVED_AVATAR_PIXELS
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { out ->
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(android.graphics.Canvas(out))
                    }
                }.getOrNull()
                ?: return@LaunchedEffect
            withContext(Dispatchers.IO) { SavedAccountAvatars.save(context, bitmap, publicKey) }
            lastRecordedAvatar = source
            return@LaunchedEffect
        }
        // the profile is known and has no picture
        val identity = com.kachat.app.services.kachatnames.KachatNamesRegistry.cachedIdentityOf(address)
        if (com.kachat.app.services.kachatnames.KachatNamesService.isEnabled && identity != null && identity.profile?.avatar.isNullOrBlank()) {
            withContext(Dispatchers.IO) { SavedAccountAvatars.remove(context, publicKey) }
            lastRecordedAvatar = null
        }
    }
}

/** The picture [SavedAccountAvatarRecorder] saved last, so the same one isn't encoded again. */
@Volatile
private var lastRecordedAvatar: String? = null

/** What [SavedAccountAvatarRecorder] asks for: the saved file's 144-pixel square. */
private const val SAVED_AVATAR_PIXELS = 144

/**
 * The chip's words (iOS `ProfileAccountChip.label`): your username, cut in the middle past 18
 * characters so the chip stays beside the title, else your address short ("qzvr…l9ug").
 */
internal fun profileChipLabel(address: String, username: String?): String {
    if (address.isEmpty()) return ""
    if (username != null) {
        if (username.length <= 18) return username
        return "${username.take(9)}…${username.takeLast(7)}"
    }
    val body = address.substringAfter(':')
    if (body.length <= 10) return body
    return "${body.take(4)}…${body.takeLast(4)}"
}

/** The signed-in account first, then the rest as the sign-in screen lists them. */
internal fun accountsCurrentFirst(accounts: List<WalletManager.Account>, current: String?): List<WalletManager.Account> {
    if (current.isNullOrEmpty()) return accounts
    val (mine, rest) = accounts.partition { KaspaNetwork.isSameAccount(it.address, current) }
    return mine + rest
}

/**
 * The account chip beside Profile's title (iOS 8cb4c88e `ProfileAccountChip`): your picture and
 * your username (or your short address), in glass; a tap opens [AccountSwitcherSheet].
 */
@Composable
fun ProfileAccountChip(address: String) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    var showSwitcher by remember { mutableStateOf(false) }
    // re-read when a name lands
    val identityRevision by com.kachat.app.services.kachatnames.KachatNamesRegistry.identityRevision.collectAsState()
    val label = remember(address, identityRevision) {
        profileChipLabel(address, if (address.isEmpty()) null else com.kachat.app.util.UsernameMention.handle(address))
    }
    val hint = stringResource(R.string.switch_account)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .toolbarGlass()
            .clickable(role = Role.Button, onClickLabel = hint) {
                haptic(com.kachat.app.util.IosHaptic.SELECTION)
                showSwitcher = true
            }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ContactAvatar(imageUrl = null, fallbackText = label, size = 24.dp, address = address.takeIf { it.isNotEmpty() })
        Text(
            label,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
    if (showSwitcher) {
        AccountSwitcherSheet(onDismiss = { showSwitcher = false })
    }
}

/**
 * Profile's top-left Share button (iOS 8cb4c88e: swapped with the account chip): the icon and the
 * word both, on glass; TalkBack reads "Share Profile".
 */
@Composable
fun ProfileShareToolbarButton(onClick: () -> Unit) {
    val haptic = com.kachat.app.util.rememberHaptics()
    val description = stringResource(R.string.profile_share_profile)
    ToolbarGlassGroup {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    onClick()
                }
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.IosShare, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(17.dp))
            Text(
                stringResource(R.string.share),
                color = KaspaTeal,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private const val SWITCHER_ROW_HEIGHT = 64
private const val SWITCHER_VISIBLE_ROWS = 4

/**
 * The half sheet behind Profile's account chip (iOS 8cb4c88e `AccountSwitcherSheet`): every
 * account saved on this phone, the signed-in one checked; tap another to switch to it (Log Out,
 * then sign in, as the sign-in screen does). Add Another Account logs out to the sign-in screen.
 */
@Composable
fun AccountSwitcherSheet(onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val walletViewModel = activityWalletViewModel()
    val allAccounts by walletViewModel.accounts.collectAsState()
    val currentAddress by walletViewModel.address.collectAsState()
    val biometricLogin by walletViewModel.biometricAccountLoginEnabled.collectAsState()
    val isAccountActionRunning by walletViewModel.isAccountActionRunning.collectAsState()
    val accounts = remember(allAccounts, currentAddress) { accountsCurrentFirst(allAccounts, currentAddress) }
    var switching by remember { mutableStateOf(false) }
    var keysMissing by remember { mutableStateOf(false) }
    var showLogOutConfirm by remember { mutableStateOf(false) }
    val fallbackName = stringResource(R.string.saved_account)

    fun isCurrent(account: WalletManager.Account): Boolean =
        currentAddress?.let { KaspaNetwork.isSameAccount(account.address, it) } == true

    fun switchTo(account: WalletManager.Account) {
        if (switching || isAccountActionRunning) return
        if (isCurrent(account)) {
            onDismiss()
            return
        }
        if (!walletViewModel.hasKeysOnThisPhone(account.address)) {
            keysMissing = true
            return
        }
        haptic(com.kachat.app.util.IosHaptic.SELECTION)
        val go = {
            switching = true
            // dismissed first: the tabs this sheet sits on are rebuilt for the new account
            onDismiss()
            walletViewModel.switchToSavedAccount(account.address)
        }
        if (biometricLogin) {
            val alias = account.name.trim().ifEmpty { fallbackName }
            context.authenticateWithDeviceCredential(
                title = context.getString(R.string.unlock_to_sign_in_to, alias),
                onSuccess = { go() },
                onFailure = {},
            )
        } else {
            go()
        }
    }

    ActionSheetContainer(
        title = stringResource(R.string.accounts_title),
        subtitle = null,
        onDismiss = { if (!switching) onDismiss() },
    ) {
        val rows = minOf(maxOf(accounts.size, 1), SWITCHER_VISIBLE_ROWS)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (rows * (SWITCHER_ROW_HEIGHT + 8)).dp)
                .verticalScroll(rememberScrollState(), enabled = accounts.size > SWITCHER_VISIBLE_ROWS),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            accounts.forEach { account ->
                val current = isCurrent(account)
                val alias = account.name.trim().ifEmpty { fallbackName }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SWITCHER_ROW_HEIGHT.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.5.dp, if (current) KaspaTeal.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(enabled = !switching) { switchTo(account) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SavedAccountAvatar(address = account.address, name = alias, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            alias,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MiddleEllipsisText(
                            account.address,
                            color = colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (current) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.account_signed_in),
                            tint = KaspaTeal,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !switching) { showLogOutConfirm = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.add_another_account),
                color = KaspaTeal,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }

    if (keysMissing) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { keysMissing = false },
            title = { Text(stringResource(R.string.recovery_phrase_needed), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.recovery_phrase_needed_message), color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = { keysMissing = false }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    if (showLogOutConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showLogOutConfirm = false },
            title = { Text(stringResource(R.string.add_another_account_confirm_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.add_another_account_confirm_message), color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    showLogOutConfirm = false
                    onDismiss()
                    walletViewModel.logout()
                }) {
                    Text(stringResource(R.string.log_out), color = KaspaTeal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogOutConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}
