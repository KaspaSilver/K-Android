package com.kachat.app.ui.screens.coldstorage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.ui.screens.AddressCardActionButton
import com.kachat.app.ui.screens.InlineRenameField
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.balanceCardBackground
import com.kachat.app.ui.screens.kaspaFiatText
import com.kachat.app.ui.screens.managedKasAmount
import com.kachat.app.ui.screens.rememberQrBitmapPainter
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.mirrorInRtl
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.tr

/**
 * One Cold Storage account on Manage Addresses' card (iOS b49a021d `ColdStorageAccountCard`, owner
 * 2026-11-27): its name, balance ([sompi], "--" until known) and worth, then Copy kpub, kpub QR,
 * Rename and its receive notifications right on the card, a red trash at the top right
 * ([onRemove] asks first), and "Tap to View Addresses" (the whole card opens the account,
 * [onOpen]). Rename is in place: the name becomes a text field with the keyboard up, saved once
 * when the rename ends ([onRename]; an empty name keeps the old one; iOS d88891ea, audit
 * 2026-11-27 M6). There is no ⋮.
 */
@Composable
internal fun ColdStorageAccountCard(
    account: ColdStorageManager.ColdAccount,
    sompi: Long?,
    onOpen: () -> Unit,
    onCopyKpub: () -> Unit,
    onShowKpubQr: () -> Unit,
    onToggleAlerts: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val focusManager = LocalFocusManager.current
    var isRenaming by remember(account.id) { mutableStateOf(false) }
    var draft by remember(account.id) { mutableStateOf("") }
    val latestAccount by rememberUpdatedState(account)
    val latestOnRename by rememberUpdatedState(onRename)
    // The name is saved once, when the rename ends. Saving each keystroke left a cleared name as
    // its first letter ("C") and restarted the cold-address cache on every key (iOS d88891ea,
    // audit 2026-11-27 M6). An empty field keeps the name it had.
    val finishRename: () -> Unit = {
        if (isRenaming) {
            isRenaming = false
            com.kachat.app.ui.screens.InlineRenameRules.accountNameToSave(draft, latestAccount.name)?.let { latestOnRename(it) }
        }
    }
    DisposableEffect(account.id) { onDispose { finishRename() } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .balanceCardBackground()
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
            if (isRenaming) {
                InlineRenameField(
                    placeholder = account.name,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onEnd = finishRename,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    account.name,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // Remove, on the card with the account's other actions (owner, 2026-11-27)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cold_remove_account), tint = Color.Red, modifier = Modifier.size(20.dp))
            }
        }
        KachatFitText(
            managedKasAmount(sompi),
            color = colors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            minScale = 0.5f,
        )
        if (sompi != null) {
            kaspaFiatText(sompi)?.let { Text(it, color = colors.textSecondary, fontSize = 15.sp) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            AddressCardActionButton(stringResource(R.string.copy_kpub), Icons.Filled.ContentCopy, onClick = onCopyKpub)
            AddressCardActionButton(stringResource(R.string.cold_kpub_qr), Icons.Filled.QrCode, onClick = onShowKpubQr)
            if (isRenaming) {
                AddressCardActionButton(stringResource(R.string.done), Icons.Filled.Check) {
                    focusManager.clearFocus()
                    finishRename()
                }
            } else {
                AddressCardActionButton(stringResource(R.string.rename), Icons.Filled.Edit) {
                    draft = account.name
                    isRenaming = true
                }
            }
            // Per account: this account's receives alone.
            AddressCardActionButton(
                stringResource(if (account.notifyOnReceive) R.string.cold_alerts_on else R.string.cold_alerts_off),
                if (account.notifyOnReceive) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                onClick = onToggleAlerts,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cold_tap_to_view_addresses), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp).mirrorInRtl())
        }
    }
}

/**
 * An account's kpub QR (iOS b49a021d `ColdStorageKpubQRView`, "Watch This Account"), laid out as
 * every receive screen (`AddressReceiveView`, owner 2026-11-27): an icon and title, the account's
 * name, the QR on a white card, what it means, then the notes. No kpub text (owner, 2026-11-22):
 * only the QR, for scanning. A tap anywhere copies it, marked sensitive and cleared after 30 s.
 */
@Composable
internal fun ColdStorageKpubQrView(account: ColdStorageManager.ColdAccount) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = rememberHaptics()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                com.kachat.app.util.copyPrivateKeyWithAutoWipe(context, account.kpub, label = "kpub")
                haptic(IosHaptic.SUCCESS)
                IosToasts.show(tr(R.string.cold_kpub_copied))
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
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).background(KaspaTeal, CircleShape),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.Black, modifier = Modifier.size(17.dp))
            }
            Text(stringResource(R.string.cold_watch_this_account), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Text(account.name, color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp)
                .size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberQrBitmapPainter(account.kpub),
                contentDescription = stringResource(R.string.qr_code),
                modifier = Modifier.size(240.dp),
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
            Text(tr(R.string.kp_watch_only_this_cannot_spend_but_95314), color = colors.textSecondary, fontSize = 13.sp)
        }

        Text(stringResource(R.string.tap_anywhere_to_copy), color = colors.textSecondary, fontSize = 13.sp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sendKaspaGlass(18.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KpubQrNote(Icons.Filled.CheckCircle, Color(0xFF34C759), tr(R.string.kp_scan_it_into_another_device_kc))
            KpubQrNote(Icons.Filled.Cancel, colors.danger, stringResource(R.string.cold_kpub_qr_warning))
        }
    }
}

@Composable
private fun KpubQrNote(icon: ImageVector, tint: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, color = LocalAppColors.current.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(0.dp))
    }
}
