package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.services.KaPostsUnseenStore
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.KaPostsViewModel
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens Profile's bell (iOS 148a5331 `NotificationsRoute`): a KaPost push that names no post, or a
 * wallet notification's tap, sets [pending]; your profile opens the list as it shows (the shell
 * shows the Profile tab first).
 */
object NotificationsRoute {
    val pending = MutableStateFlow(false)
}

/**
 * Every unseen notification behind Profile's bell: KaPost's unseen activity plus the wallet's
 * entries newer than when the list was last opened (iOS 148a5331: "the dock badge on Profile adds
 * both unseen counts up"). The shell reads [count] for the Profile tab's badge.
 */
@Singleton
class AllNotificationsBadge @Inject constructor(
    kaPostsUnseen: KaPostsUnseenStore,
    walletCenter: GlobalNotificationCenterStore,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val count: StateFlow<Int> = combine(
        kaPostsUnseen.unseenCount, walletCenter.entries, walletCenter.lastSeenAt,
    ) { kaPosts, entries, seenAt ->
        kaPosts + entries.count { it.timestampMs > seenAt }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        runCatching { walletCenter.reloadIfNeeded() }
        runCatching { kaPostsUnseen.reloadIfNeeded() }
    }

    /** "99+" past 99, as the dock draws it. */
    fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()
}

/** Compose bridge to [AllNotificationsBadge] and the wallet's notification store. */
@HiltViewModel
class AllNotificationsViewModel @Inject constructor(
    val badge: AllNotificationsBadge,
    val walletCenter: GlobalNotificationCenterStore,
    private val kaPostsPoller: dagger.Lazy<com.kachat.app.services.KaPostsNotificationPoller>,
) : ViewModel() {
    val count: StateFlow<Int> = badge.count
    init {
        walletCenter.reloadIfNeeded()
    }

    /** Looking at Profile catches the count up, as X does when you look at its bell: with push the
     *  KaPost poll is otherwise only every 5 minutes (iOS 961fc05a `pollForBell`). Built off the
     *  main thread if it isn't yet. */
    fun pollForBell() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { kaPostsPoller.get().pollForBell() }
        }
    }
}

/** Profile's bell, top right (iOS 148a5331 `AllNotificationsBell`): the unseen count on it. */
@Composable
fun AllNotificationsBell(onClick: () -> Unit, viewModel: AllNotificationsViewModel = hiltViewModel()) {
    val count by viewModel.count.collectAsState()
    LaunchedEffect(viewModel) { viewModel.pollForBell() }
    val label = stringResource(R.string.notifications)
    Box(modifier = Modifier.semantics { contentDescription = if (count > 0) "$label, $count" else label }) {
        ToolbarGlassGroup {
            ToolbarIconButton(Icons.Outlined.Notifications, null, onClick = onClick, iconSize = 22.dp)
        }
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-2).dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFE0245E))
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(viewModel.badge.badgeText(count), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/**
 * Profile's bell (iOS 148a5331 `AllNotificationsView` = `KaPostsNotificationsView(allInOne: true)`):
 * one list, newest first, of KaPost activity, the wallet's (Kaspa and kUSDT received) and every
 * room or group @mention of you (iOS d089011f; a mention row opens the room at the message, or the
 * group), in a
 * sheet with a large "Notifications" title and Done - no tabs, no settings gear, no back arrow
 * (owner's calls). Opening it is seeing it: both unseen counts clear. A KaPost row closes the
 * sheet, then its post opens on the KaPost tab, and a follow's profile over Profile (iOS 2251f15c); a wallet
 * row opens what the Wallet's own list opened.
 */
@Composable
fun AllNotificationsSheet(
    navController: NavController,
    onDismiss: () -> Unit,
    viewModel: KaPostsViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    allViewModel: AllNotificationsViewModel = hiltViewModel(),
    /** A follower's profile, opened right where the bell is (Profile), sliding in over it,
     *  instead of switching to the Posts feed first (iOS 2251f15c `onOpenProfile`, owner
     *  2026-11-26). Null: the KaPost tab opens it. */
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val walletEntries by allViewModel.walletCenter.entries.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.markNotificationsSeen()
        viewModel.setNotificationsScreenVisible(true)
        allViewModel.walletCenter.markAllSeen()
    }
    // The indexer's back: the real list, not the saved one (iOS ee5ec98).
    LaunchedEffect(Unit) {
        viewModel.indexerAvailabilityChanged.collect {
            if (!viewModel.indexerIsDown.value) viewModel.reloadNotificationsAfterOutage()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.setNotificationsScreenVisible(false) } }
    // Seen while it is open: what lands is read as it arrives.
    val unseen by viewModel.unseenNotifications.collectAsState()
    LaunchedEffect(unseen) { if (unseen > 0) viewModel.markNotificationsSeen() }
    val walletLastSeen by allViewModel.walletCenter.lastSeenAt.collectAsState()
    val newestWallet = remember(walletEntries) { walletEntries.maxOfOrNull { it.timestampMs } ?: 0L }
    LaunchedEffect(newestWallet, walletLastSeen) {
        if (newestWallet > walletLastSeen) allViewModel.walletCenter.markAllSeen()
    }

    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true) { close ->
        androidx.activity.compose.BackHandler { close() }
        /** Closes the sheet, then runs [then] (iOS: the sheet closes first, then the route). */
        fun closeThen(then: () -> Unit) {
            close()
            then()
        }
        Column(Modifier.fillMaxSize().background(colors.background).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SheetBarButton(stringResource(R.string.done), onClick = close, modifier = Modifier.align(Alignment.CenterEnd))
            }
            Text(
                stringResource(R.string.notifications),
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = LargeTitleSize,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                KaPostsNotificationsList(
                    viewModel = viewModel,
                    onOpenPost = { txId ->
                        closeThen {
                            KaPostsDeepLink.pendingOpenNotifications.value = false
                            KaPostsDeepLink.pendingPostTxId.value = txId
                        }
                    },
                    // A follow has no post: the follower's KaPost profile, over Profile itself when
                    // the bell is Profile's (iOS 2251f15c), otherwise on the KaPost tab; yours is
                    // the Profile tab.
                    onOpenProfile = { address ->
                        closeThen {
                            val open = onOpenProfile
                            if (open != null && !KaPostsDeepLink.isOwnAddress(address, viewModel.myAddress())) {
                                open(address)
                            } else {
                                KaPostsDeepLink.openProfile(address, viewModel.myAddress())
                            }
                        }
                    },
                    bottomInset = 0.dp,
                    walletEntries = walletEntries,
                    onOpenWalletEntry = { entry ->
                        openGlobalNotificationEntry(entry, walletViewModel, navController) { then -> closeThen(then) }
                    },
                )
            }
        }
    }
}

/**
 * A wallet notification (Kaspa or kUSDT received) or a room or group @mention of you in Profile's bell, drawn like the KaPost rows
 * (iOS 148a5331 `walletRow`): the Kaspa logo (or the token's mark) on an accent disc, the title,
 * the body, how long ago, and a chevron.
 */
@Composable
internal fun KaPostsWalletNotificationRow(entry: GlobalNotificationCenterStore.Entry, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.source == "wallet") {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                // dollarsign.circle for a token, at for a mention or a .kachat name (iOS `entry.source.icon`)
                val icon = when (entry.source) {
                    GlobalNotificationCenterStore.SOURCE_MENTION, GlobalNotificationCenterStore.SOURCE_KACHAT -> Icons.Outlined.AlternateEmail
                    else -> Icons.Outlined.MonetizationOn
                }
                Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.body.isNotEmpty()) {
                Text(entry.body, color = colors.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(
                remember(entry.timestampMs) { relativePostTime(entry.timestampMs) },
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}
