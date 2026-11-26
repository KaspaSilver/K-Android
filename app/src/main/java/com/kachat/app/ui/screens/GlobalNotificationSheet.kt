package com.kachat.app.ui.screens



import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import androidx.compose.runtime.getValue

/**
 * Routes a tapped row to its subject, the same way the matching notification's tap does (iOS
 * `GlobalNotificationListView.open`). [close] closes the list first, then runs what it is given.
 */
private fun routeNotificationEntry(
    entry: GlobalNotificationCenterStore.Entry,
    walletViewModel: WalletViewModel,
    navController: NavController,
    close: (then: () -> Unit) -> Unit,
) {
    val target = entry.targetId.orEmpty()
    when (entry.source) {
        "group" -> {
            if (target.isBlank()) return
            close { navController.navigate("group_chat/$target") }
        }
        // A receipt opens the History of the address that got the Kaspa (chatting or spending -
        // MainShell's own-address sheet works out which), as a tapped banner does (iOS
        // 95e2cba). Entries recorded before receipts carried their address just close the
        // list, on Wallet, which shows the wallet's addresses.
        "wallet" -> close {
            if (target.isNotEmpty()) com.kachat.app.ui.OwnAddressRoute.pending.value = target
        }
        // A room or group @mention of you: the room at the message, or the group (iOS d089011f).
        GlobalNotificationCenterStore.SOURCE_MENTION -> openMentionEntry(entry, close)
        // A token received: that token's Manage screen, on the Wallet tab (iOS 5b7c772c TokenRoute).
        "token" -> close {
            walletViewModel.openTokenRoute(target.ifEmpty { com.kachat.app.services.tokens.WalletToken.KUSDT.id })
            com.kachat.app.ui.navigateToTab(navController, com.kachat.app.ui.Screen.Wallet)
        }
    }
}

/** The feed, newest first, or the bell and "No notifications yet" with nothing in it. */
@Composable
private fun NotificationEntriesList(
    entries: List<GlobalNotificationCenterStore.Entry>,
    onOpen: (GlobalNotificationCenterStore.Entry) -> Unit,
) {
    val colors = LocalAppColors.current
    if (entries.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = null,
                tint = colors.textSecondary,
                // iOS's "bell" at 40pt draws about 36 wide and 38 tall; Material's
                // outlined bell fills 20/24 of its box, so 46.
                modifier = Modifier.size(46.dp),
            )
            Text(
                stringResource(R.string.notification_center_empty_title),
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Text(
                stringResource(R.string.notification_center_wallet_empty),
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { entry ->
                GlobalNotificationRow(entry, onClick = { onOpen(entry) })
                // Plain-list separator: from where the text starts to the trailing edge.
                HorizontalDivider(
                    modifier = Modifier.padding(start = 50.dp),
                    color = colors.divider,
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

/**
 * Notifications pushed from Wallet's Notifications card (iOS 5b7c772c `GlobalNotificationListView`
 * with `isPushed`, as KaPay has it): Back, Clear All on the right, the large "Notifications" title,
 * then the feed. Opening it marks everything seen (clears the Wallet tab's dot).
 */
@Composable
fun GlobalNotificationPage(
    store: GlobalNotificationCenterStore,
    walletViewModel: WalletViewModel,
    navController: NavController,
    onBack: () -> Unit,
) {
    val entries by store.entries.collectAsState()
    val colors = LocalAppColors.current
    LaunchedEffect(Unit) { store.markAllSeen() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
            SheetBarBackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
            SheetBarButton(
                stringResource(R.string.notification_center_clear_all),
                onClick = { store.clearAll() },
                enabled = entries.isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        Text(
            stringResource(R.string.notifications),
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = LargeTitleSize,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            NotificationEntriesList(entries, onOpen = { entry ->
                routeNotificationEntry(entry, walletViewModel, navController) { then ->
                    onBack()
                    then()
                }
            })
        }
    }
}

/** One row: the source's icon, then the title, the body (2 lines) and "source  time ago". */
@Composable
private fun GlobalNotificationRow(entry: GlobalNotificationCenterStore.Entry, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(24.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
            Icon(sourceIcon(entry.source), contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val body = entry.body
            if (body.isNotEmpty()) {
                Text(body, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(GlobalNotificationCenterStore.sourceLabel(entry.source), color = colors.textSecondary, fontSize = 11.sp)
                Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(entry.timestampMs).toString(),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * A row's icon (iOS `Entry.Source.icon`): arrow.down.circle for a wallet receipt, dollarsign.circle
 * for a token received; megaphone,
 * person.3 and dot.radiowaves.left.and.right for the sources older builds recorded.
 */
private fun sourceIcon(source: String): ImageVector = when (source) {
    "wallet" -> Icons.Outlined.ArrowCircleDown
    // dollarsign.circle (iOS 5b7c772c)
    "token" -> Icons.Outlined.MonetizationOn
    // at (iOS d089011f `.mention`)
    GlobalNotificationCenterStore.SOURCE_MENTION -> Icons.Outlined.AlternateEmail
    "kaposts" -> Icons.Outlined.Campaign
    "group" -> Icons.Outlined.Groups
    else -> Icons.Outlined.Sensors
}

/**
 * A wallet row tapped in Profile's bell ([AllNotificationsSheet], iOS 148a5331 `walletRow`): it
 * opens what the Wallet's own list opened. [close] closes the bell first, then runs what it is given.
 */
fun openGlobalNotificationEntry(
    entry: GlobalNotificationCenterStore.Entry,
    walletViewModel: WalletViewModel,
    navController: NavController,
    close: (then: () -> Unit) -> Unit,
) = routeNotificationEntry(entry, walletViewModel, navController, close)
