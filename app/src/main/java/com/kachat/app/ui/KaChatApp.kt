package com.kachat.app.ui

import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.navigation.navArgument
import com.kachat.app.ui.screens.*
import com.kachat.app.ui.screens.chess.GamesScreen
import com.kachat.app.ui.screens.chess.KaPlayRoutes
import com.kachat.app.ui.screens.chess.kaPlayGraph
import com.kachat.app.ui.screens.chess.kaPlayShowsDock
import com.kachat.app.ui.screens.coldstorage.ColdStorageTab
import com.kachat.app.ui.screens.coldstorage.isOwnColdAddress
import com.kachat.app.ui.screens.portfolio.PortfolioPriceChartScreen
import com.kachat.app.ui.screens.portfolio.PortfolioRoutes
import com.kachat.app.ui.screens.portfolio.PortfolioTab
import com.kachat.app.ui.screens.portfolio.PortfolioValueChartScreen
import com.kachat.app.ui.screens.PublicChatRoutes
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.screens.collectAsStateWhileStarted
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.CheckCircle

/**
 * The routes of the five tabs the dock had before Kaspa Hub came back. The tabs themselves are
 * [AppTab] now (dock or Kaspa Hub, iOS 148a5331); these names stay for the screens that check
 * which route a reselect was for.
 */
sealed class Screen(val route: String, @androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector) {
    object Chats       : Screen(AppTab.CHATS.route,        R.string.tab_chats,          Icons.Default.Forum)
    object Wallet      : Screen(AppTab.WALLET.route,       R.string.wallet_title,    Icons.Filled.AccountBalanceWallet)
    object AddressBook : Screen(AppTab.ADDRESS_BOOK.route, R.string.ab_address_book, ContactsFilledIcon)
    object Profile     : Screen(AppTab.PROFILE.route,      R.string.profile,         Icons.Default.AccountCircle)
    object Settings    : Screen(AppTab.SETTINGS.route,     R.string.settings_title,  Icons.Default.Settings)
}

/**
 * The pages pushed inside the Settings tab, each a NavigationLink in iOS's SettingsView, with its
 * tab bar still showing. What it presents as a sheet instead (the 12 or 24 words, the backup steps,
 * the NFC card backup, the licenses) stays without the dock.
 */
private val settingsPushedRoutes = setOf(
    "settings_section/{sectionKey}",
    "storage_nextcloud",
    "cache_settings",
    "connection_settings",
    "kaspa_explorer_settings",
    "notification_settings",
    "wallet_notification_settings",
    "quick_reaction_settings",
    // Settings > Chats > Who Can Message You? (iOS 2026-11-23)
    com.kachat.app.ui.screens.WhoCanMessageRoutes.SETTINGS,
    // Settings > Help (moved from the old Profile, iOS 148a5331) and its kUSDT Guide;
    // Settings > Notifications > KaPost.
    "help",
    SettingsShellRoutes.TOKEN_GUIDE,
    SettingsShellRoutes.KAPOSTS_NOTIFICATIONS,
)

/** Routes the shell adds for Settings' new rows (iOS 148a5331 SettingsView). */
object SettingsShellRoutes {
    /** Settings > Help > kUSDT Guide ([com.kachat.app.ui.screens.tokens.TokenGuideScreen]). */
    const val TOKEN_GUIDE = "token_guide"
    /** Settings > Help > .kachat Names Guide ([KachatSetupGuideScreen]), full screen. */
    const val KACHAT_NAMES_GUIDE = "kachat_names_guide"
    /** Settings > About > Terms of Use ([TermsOfUseScreen]). */
    const val TERMS_OF_USE = "terms_of_use"
    /** Settings > Notifications > KaPost ([KaPostsNotificationSettingsScreen]). */
    const val KAPOSTS_NOTIFICATIONS = "kaposts_notification_settings"
}

/** Wallet's pushed pages (Manage Addresses and its address pages, Your Domains). */
private val walletPushedRoutes = setOf(
    "manage_addresses", "identity_address_detail", "spending_address_detail/{index}", "kns_domains",
)

/** Portfolio's chart pages (iOS NavigationLinks inside PortfolioView: the tab bar stays). */
private val portfolioPushedRoutes = setOf(
    PortfolioRoutes.PRICE_CHART, PortfolioRoutes.VALUE_CHART,
)

/** A public room and its Room Info, pushed inside Chats with the dock kept (iOS). */
private val roomPushedRoutes = setOf(PublicChatRoutes.ROOM, PublicChatRoutes.ROOM_INFO)

/**
 * The tab a pushed route belongs to, for the dock to light while you are on it. Null for a route
 * no tab owns. When that tab lives in Kaspa Hub, the Hub lights instead.
 */
private fun dockOwnerTab(route: String?): AppTab? = when (route) {
    null -> null
    "chat/{contactId}?paymentMode={paymentMode}", "group_chat/{groupId}",
    PublicChatRoutes.ROOM, PublicChatRoutes.ROOM_INFO, PublicChatRoutes.HIDDEN_USERS -> AppTab.CHATS
    in walletPushedRoutes -> AppTab.WALLET
    in settingsPushedRoutes -> AppTab.SETTINGS
    in portfolioPushedRoutes -> AppTab.PORTFOLIO
    in KaPlayRoutes.DOCKED_ROUTES, in KaPlayRoutes.GAME_ROUTES -> AppTab.GAMES
    else -> AppTab.fromRoute(route)
}

/**
 * Switches to the tab at [route] the way a dock tap does: back to it when it is already on the
 * back stack (a navigate() + popUpTo to the start destination alone is a no-op for the start
 * destination in Navigation Compose), else a first visit that keeps each tab's own stack.
 */
fun navigateToTab(navController: NavController, route: String) {
    val poppedToExisting = navController.popBackStack(route = route, inclusive = false, saveState = true)
    if (!poppedToExisting) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

fun navigateToTab(navController: NavController, tab: Screen) = navigateToTab(navController, tab.route)

/**
 * Root composable: the Terms of Use until agreed (iOS 148a5331 `ContentView`), then onboarding
 * when no wallet exists, else the dock and its tabs.
 */
@Composable
fun KaChatApp(
    walletViewModel: WalletViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {},
    pendingOwnAddress: String? = null,
    onPendingOwnAddressHandled: () -> Unit = {}
) {
    // The Terms of Use (KaPost and Public Chats are back: App Store guideline 1.2 on iOS): the
    // first screen until the current version is agreed to on this device, before any account.
    val termsContext = androidx.compose.ui.platform.LocalContext.current
    var termsAccepted by remember { mutableStateOf(KaChatTerms.isAccepted(termsContext)) }
    if (!termsAccepted) {
        TermsAcceptanceScreen(onAgree = {
            KaChatTerms.accept(termsContext)
            termsAccepted = true
        })
        return
    }

    val isLoggedIn by walletViewModel.isLoggedIn.collectAsState()
    val startupResolved by walletViewModel.startupResolved.collectAsState()
    val pendingAccountSwitch by walletViewModel.pendingAccountSwitch.collectAsState()

    if (!startupResolved) {
        // Cold start, routing decision still loading (the async biometrics-for-login read that
        // gates auto-login into the last-used account). Never the Onboarding/accounts screen as a
        // transient default, which flashed the account list for a few frames before jumping into
        // the account: the launch's K globe instead, where the splash screen drew it (iOS
        // 518fdc8c `LoadingView`).
        LaunchGlobeView()
    } else if (!isLoggedIn && pendingAccountSwitch != null) {
        // Between one account's Log Out and the next one's sign-in (Profile's account chip): the
        // globe, never the sign-in screen (iOS 961fc05a `isSwitchingAccount`, owner 2026-11-25:
        // "a seamless transfer to another profile"). The old account's tabs are gone here, so the
        // new one still gets new tabs; a failed sign-in ends on the sign-in screen with why.
        LaunchGlobeView()
        androidx.compose.runtime.LaunchedEffect(pendingAccountSwitch) {
            walletViewModel.finishPendingAccountSwitch()
        }
    } else if (!isLoggedIn) {
        OnboardingScreen(walletViewModel)
    } else {
        MainShell(
            walletViewModel = walletViewModel,
            pendingContactId = pendingContactId,
            onPendingContactHandled = onPendingContactHandled,
            pendingGroupId = pendingGroupId,
            onPendingGroupHandled = onPendingGroupHandled,
            pendingOpenGroups = pendingOpenGroups,
            onPendingOpenGroupsHandled = onPendingOpenGroupsHandled,
            pendingWalletActivityKind = pendingWalletActivityKind,
            onPendingWalletActivityHandled = onPendingWalletActivityHandled,
            pendingOwnAddress = pendingOwnAddress,
            onPendingOwnAddressHandled = onPendingOwnAddressHandled
        )
    }
}

/**
 * Portfolio's chart pages share the tab's [com.kachat.app.viewmodels.PortfolioViewModel]: the one
 * scoped to the Portfolio tab's route when it sits in the dock, or to Kaspa Hub's when it opened
 * there (KaPay: `hiltViewModel(navController.getBackStackEntry(<portfolio route>))`).
 */
@Composable
private fun sharedPortfolioViewModel(navController: NavController): com.kachat.app.viewmodels.PortfolioViewModel {
    val owner = remember(navController) {
        runCatching { navController.getBackStackEntry(AppTab.PORTFOLIO.route) }.getOrNull()
            ?: runCatching { navController.getBackStackEntry(AppTab.HUB.route) }.getOrNull()
    }
    return if (owner != null) hiltViewModel(owner) else hiltViewModel()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainShell(
    walletViewModel: WalletViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {},
    pendingOwnAddress: String? = null,
    onPendingOwnAddressHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // The whole stack, for the dock to light the tab a pushed screen sits in.
    val dockBackStack by navController.currentBackStack.collectAsState()
    val currentDestination = navBackStackEntry?.destination

    // The fixed dock and Kaspa Hub's grid (iOS `DockLayout`).
    val shellContext = androidx.compose.ui.platform.LocalContext.current
    remember { DockLayout.init(shellContext) }
    val dockTabs by DockLayout.dock.collectAsState()
    val hubTabs by DockLayout.hub.collectAsState()
    val hubOpenSection by HubRouter.openSection.collectAsState()
    // The app opens on Chats (iOS `selectedTab = 1`): its dock slot, or open in Kaspa Hub.
    val startRoute = remember {
        if (AppTab.CHATS in DockLayout.dock.value) {
            AppTab.CHATS.route
        } else {
            HubRouter.open(AppTab.CHATS)
            AppTab.HUB.route
        }
    }
    val tabRoutes = remember { AppTab.entries.map { it.route }.toSet() }

    val onTabRoute = currentDestination?.hierarchy?.any { dest ->
        dest.route in tabRoutes ||
            // A chat keeps the tab bar too: iOS's ChatDetailView / GroupChatDetailView sit inside
            // the Chats tab with its bar showing, the composer above it. So do public rooms.
            dest.route == "chat/{contactId}?paymentMode={paymentMode}" ||
            dest.route == "group_chat/{groupId}" ||
            dest.route in roomPushedRoutes ||
            // Manage Addresses with its address pages and Your Domains (Wallet), Portfolio's
            // charts - NavigationLinks on iOS, all keeping the tab bar.
            dest.route in walletPushedRoutes ||
            dest.route in portfolioPushedRoutes ||
            // KaPlay's pages keep it, but a board the player is locked into and a player's card.
            (dest.route in KaPlayRoutes.DOCKED_ROUTES) ||
            (dest.route in KaPlayRoutes.GAME_ROUTES && kaPlayShowsDock(dest.route)) ||
            // Settings' pushed pages keep the bar too. Connection Settings only from Settings: the
            // connection dot's sheet opens the same page, and on iOS that push stays in the sheet.
            (dest.route in settingsPushedRoutes && (
                dest.route != "connection_settings" ||
                    dockBackStack.dropLast(1).lastOrNull()?.destination?.route == "settings_section/{sectionKey}"
            ))
    } == true

    val hideBottomBar by walletViewModel.hideBottomBar.collectAsState()
    val localTabOrder = dockTabs
    val currentTopRoute = currentDestination?.route

    /**
     * Puts [tab] on screen: its dock slot, or Kaspa Hub with it open there (iOS
     * `MainTabView.show`). Reads the dock and the route as they are now, so a collector started
     * once can call it.
     */
    fun show(tab: AppTab) {
        val target = if (tab in DockLayout.dock.value) {
            tab
        } else {
            HubRouter.open(tab)
            AppTab.HUB
        }
        val onOwnRoute = navController.currentDestination?.hierarchy?.any { it.route == target.route } == true
        if (!onOwnRoute) navigateToTab(navController, target.route)
    }

    // Blocking progress for the account's first full sync, which the setup wizard deferred.
    // Escapable on purpose: a sync can stall on a slow indexer, and a dialog with no way out
    // would be worse than an incomplete chat list.
    val initialSyncPhase by chatViewModel.initialSyncPhase.collectAsState()
    if (initialSyncPhase != null) {
        val finished = initialSyncPhase == ChatViewModel.InitialSyncPhase.Finished
        var canSkip by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(20_000); canSkip = true }
        Dialog(
            onDismissRequest = { if (finished) chatViewModel.clearInitialSyncPhase() },
            properties = DialogProperties(dismissOnBackPress = finished, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (finished) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LocalAppColors.current.success, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.first_sync_all_set), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.first_sync_up_to_date), color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    // iOS's .borderedProminent: white on an accent capsule.
                    Button(
                        onClick = { chatViewModel.clearInitialSyncPhase() },
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.White),
                    ) {
                        Text(stringResource(R.string.done), color = Color.White)
                    }
                } else {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.first_sync_setting_up), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    // Looked up as iOS does (4f8e14bb). Groups and Balance are Android's own phases,
                    // with no iOS text to translate them from.
                    Text(
                        when (initialSyncPhase) {
                            null -> stringResource(R.string.initial_sync_starting)
                            ChatViewModel.InitialSyncPhase.Messages -> stringResource(R.string.downloading_message_history)
                            ChatViewModel.InitialSyncPhase.Finished -> stringResource(R.string.initial_sync_finished)
                            else -> initialSyncPhase?.label.orEmpty()
                        },
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.first_sync_downloading_footer),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    if (canSkip) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { chatViewModel.clearInitialSyncPhase() }) {
                            Text(stringResource(R.string.first_sync_continue_anyway), color = KaspaTeal)
                        }
                    }
                }
            }
        }
    }

    // Tapping a notification for a message/handshake/payment jumps straight to that
    // conversation, matching what a real chat app does — otherwise you'd land on the
    // chat list and have to go find it yourself.
    LaunchedEffect(pendingContactId) {
        if (pendingContactId != null) {
            navController.navigate("chat/$pendingContactId")
            onPendingContactHandled()
        }
    }

    // Same idea for a group chat notification: Chats, then the group on top of it (iOS d1844582 -
    // group notification taps land on the list the groups share with the chats).
    LaunchedEffect(pendingGroupId) {
        if (pendingGroupId != null) {
            show(AppTab.CHATS)
            navController.navigate("group_chat/$pendingGroupId")
            onPendingGroupHandled()
        }
    }

    // A group notification that names no local group (a "you were added" push, or a group push
    // this device could not resolve or ingest): the Chats list, where the new group appears once
    // the next sync lands - as specific as that tap can get (iOS d1844582). Better than a
    // group_chat/<blinded id> route that resolves to an empty, unusable thread.
    LaunchedEffect(pendingOpenGroups) {
        if (pendingOpenGroups) {
            show(AppTab.CHATS)
            onPendingOpenGroupsHandled()
        }
    }

    // Wallet address-activity receipt ("Received X KAS" on a spending address) that names no one
    // address: Wallet, which shows the addresses and balances (iOS `.openProfile` -> show(.wallet)).
    LaunchedEffect(pendingWalletActivityKind) {
        val kind = pendingWalletActivityKind
        if (kind != null) {
            // "Received kUSDT": that token's Manage screen, on the Wallet tab (iOS 5b7c772c
            // TokenRoute / .openToken). The Wallet screen opens it once it is up.
            if (kind == com.kachat.app.services.tokens.TokenWallet.NOTIFICATION_KIND) {
                walletViewModel.openTokenRoute(com.kachat.app.services.tokens.WalletToken.KUSDT.id)
            }
            show(AppTab.WALLET)
            onPendingWalletActivityHandled()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Links and pushes for the features back since iOS 148a5331: each shows its tab wherever it
    // lives, and the tab's screen consumes what is pending (iOS `KaPostsRoutes`,
    // `consumePendingNotificationRoute`).
    // ---------------------------------------------------------------------------------------

    // Who is signed in, for "your own @ opens the Profile tab" (iOS d089011f
    // `KaPostsDeepLink.openProfile`).
    val shellWalletAddress by walletViewModel.address.collectAsState()
    LaunchedEffect(shellWalletAddress) { com.kachat.app.ui.screens.KaPostsDeepLink.signedInAddress = shellWalletAddress }
    // A group @mention tapped in Profile's bell: Chats, then the group on top of it (iOS d089011f
    // `pendingGroupNavigation` + `.openGroup`).
    val pendingMentionGroup by com.kachat.app.ui.screens.MentionGroupRoute.pending.collectAsState()
    LaunchedEffect(pendingMentionGroup) {
        val groupId = pendingMentionGroup ?: return@LaunchedEffect
        com.kachat.app.ui.screens.MentionGroupRoute.pending.value = null
        com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
        show(AppTab.CHATS)
        navController.navigate("group_chat/$groupId")
    }

    // KaPost: a post, a profile, a pre-written post or the scheduled list opens the KaPost tab.
    val pendingKaPostTxId by com.kachat.app.ui.screens.KaPostsDeepLink.pendingPostTxId.collectAsState()
    val pendingKaPostProfile by com.kachat.app.ui.screens.KaPostsDeepLink.pendingProfileAddress.collectAsState()
    val pendingKaPostCompose by com.kachat.app.ui.screens.KaPostsDeepLink.pendingComposeText.collectAsState()
    val pendingKaPostScheduled by com.kachat.app.ui.screens.KaPostsDeepLink.pendingOpenScheduled.collectAsState()
    LaunchedEffect(pendingKaPostTxId, pendingKaPostProfile, pendingKaPostCompose, pendingKaPostScheduled) {
        // "" is the tab-only sentinel (a notification with no post): Profile's bell takes those.
        if (pendingKaPostTxId == "") com.kachat.app.ui.screens.KaPostsDeepLink.pendingPostTxId.value = null
        val wantsKaPost = !pendingKaPostTxId.isNullOrEmpty() || pendingKaPostProfile != null ||
            pendingKaPostCompose != null || pendingKaPostScheduled
        if (wantsKaPost) {
            // A thread or profile open over KaPost closes first, so the new one shows.
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            show(AppTab.KAPOSTS)
        }
    }
    // A push naming no post, or a wallet row asking for the bell: Profile, whose bell opens.
    val pendingBell by com.kachat.app.ui.screens.NotificationsRoute.pending.collectAsState()
    val pendingKaPostBell by com.kachat.app.ui.screens.KaPostsDeepLink.pendingOpenNotifications.collectAsState()
    LaunchedEffect(pendingBell, pendingKaPostBell) {
        if (pendingBell || pendingKaPostBell) {
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            show(AppTab.PROFILE)
        }
    }
    // A public room from a link, a banner or a push: Chats, whose list joins it if need be and
    // opens it (`PublicRoomHandoffEffect`). Covers over KaPost close first (iOS `.openPublicChat`).
    val pendingRoom by BroadcastDeepLink.pendingChannel.collectAsState()
    LaunchedEffect(pendingRoom) {
        if (pendingRoom != null) {
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            show(AppTab.CHATS)
        }
    }
    // A Kaspa Hub card's link (`kachat.app/hub/<slug>`, iOS 961fc05a `.openHubSection`): that
    // card, wherever it lives. A post or profile open over KaPost, where most card links are
    // tapped, closes first, or the card opened under it (audit 2026-11-25 L1).
    val pendingHubCard by com.kachat.app.ui.screens.HubLinkRouter.pending.collectAsState()
    LaunchedEffect(pendingHubCard) {
        val tab = pendingHubCard ?: return@LaunchedEffect
        com.kachat.app.ui.screens.HubLinkRouter.consume()
        com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
        show(tab)
    }
    // A bot challenge (a KaPost card, a kachatchess:// link): KaPlay, which opens Chess and the
    // bot. Covers over KaPost close first (iOS `.openGames`). A challenge waiting from a cold
    // start is taken the same way.
    LaunchedEffect(Unit) {
        if (com.kachat.app.services.chess.ChessBotLinkRouter.challenge.value != null) show(AppTab.GAMES)
        com.kachat.app.services.chess.ChessBotLinks.openGames.collect {
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            show(AppTab.GAMES)
        }
    }
    // An arcade score challenge (a KaPost card's Play, a kachatarcade:// link; iOS 518fdc8c
    // `ArcadeLinkRouter.open`): KaPlay, which opens the game. One waiting from a cold start too.
    LaunchedEffect(Unit) {
        if (com.kachat.app.services.arcade.ArcadeLinkRouter.challenge.value != null) show(AppTab.GAMES)
        com.kachat.app.services.arcade.ArcadeLinkRouter.openGames.collect {
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            // Already on one of KaPlay's pages: going to the tab would pop them, a game included;
            // ArcadeChallengeRouting below takes it from there (audit 2026-11-24 #11).
            if (navController.currentDestination?.route !in com.kachat.app.ui.screens.arcade.ArcadeRoutes.kaPlayPages) show(AppTab.GAMES)
        }
    }
    // A challenge past a page over KaPlay's tab: closes an arcade game's home or Chess's pages
    // for it, or holds it while a game is on (iOS 518fdc8c GamesView, audit 2026-11-24 #11).
    com.kachat.app.ui.screens.arcade.ArcadeChallengeRouting(navController)
    // The .kachat tab: Claim a Name / Get a .kachat name (the tab itself), kapay://market (its
    // Marketplace) and a name from a notification (its page). The screen takes the last two.
    val pendingKachatTab by KachatDeepLink.pendingTab.collectAsState()
    val pendingKachatMarket by KachatDeepLink.pendingMarketplace.collectAsState()
    val pendingKachatName by KachatDeepLink.pendingName.collectAsState()
    LaunchedEffect(pendingKachatTab, pendingKachatMarket, pendingKachatName) {
        if (pendingKachatTab) KachatDeepLink.pendingTab.value = false
        val wantsNames = pendingKachatTab || pendingKachatMarket ||
            (pendingKachatName != null && com.kachat.app.services.kachatnames.KachatNamesService.isLaunched)
        if (wantsNames) {
            com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
            show(AppTab.KACHAT_NAMES)
        }
    }
    // A tab tapped on the dock copy over KaPost's full-screen screens (iOS `.kaPostsSelectDockTab`).
    LaunchedEffect(Unit) {
        com.kachat.app.ui.screens.KaPostsDock.tabRequests.collect { route ->
            AppTab.fromRoute(route)?.let { show(it) }
        }
    }
    // Done in Trestly: back to the top of the Wallet tab (iOS: Manage kUSDT, Manage Addresses and
    // the sheet close). WalletScreen closes its own; a page pushed over Wallet goes here.
    LaunchedEffect(Unit) {
        com.kachat.app.ui.screens.tokens.TrestlyRoute.done.collect {
            if (navController.currentDestination?.route in walletPushedRoutes) {
                val home = if (AppTab.WALLET in DockLayout.dock.value) AppTab.WALLET.route else AppTab.HUB.route
                navController.popBackStack(home, inclusive = false)
            }
        }
    }
    // KaPost is on screen (its dock slot, or open in Kaspa Hub): its background sweep pages only then.
    LaunchedEffect(currentTopRoute, hubOpenSection) {
        com.kachat.app.ui.screens.KaPostsDock.onScreen.value =
            currentTopRoute == AppTab.KAPOSTS.route ||
                (currentTopRoute == AppTab.HUB.route && hubOpenSection == AppTab.KAPOSTS)
    }
    // A game's Done falls back to the KaPlay tab, wherever it lives.
    SideEffect {
        KaPlayRoutes.gamesTabRoute = if (AppTab.GAMES in dockTabs) AppTab.GAMES.route else AppTab.HUB.route
    }
    // The tab on screen left the dock: Kaspa Hub, where it now lives (iOS `onChange(layout.dock)`).
    LaunchedEffect(dockTabs) {
        val onTab = AppTab.fromRoute(navController.currentDestination?.route) ?: return@LaunchedEffect
        if (onTab !in dockTabs) {
            HubRouter.open(onTab)
            navigateToTab(navController, AppTab.HUB.route)
        }
    }

    // A "Received" receipt that named one of your addresses: that address's History, in a sheet
    // over whatever tab is showing (iOS 13046ad). MainActivity holds the address until this shell
    // exists, so a cold start (still on the loading route when the tap arrived) lands here too -
    // iOS's OwnAddressRoute.pending.
    var ownAddressSheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingOwnAddress) {
        val address = pendingOwnAddress
        if (address != null) {
            ownAddressSheet = address
            onPendingOwnAddressHandled()
        }
    }
    // A bell "Received" / "Balance increased" row that carries its address: the same sheet
    // (iOS 95e2cba, OwnAddressRoute.pending + .openOwnAddress).
    val pendingBellOwnAddress by OwnAddressRoute.pending.collectAsState()
    LaunchedEffect(pendingBellOwnAddress) {
        val address = pendingBellOwnAddress ?: return@LaunchedEffect
        ownAddressSheet = address
        OwnAddressRoute.pending.value = null
    }
    ownAddressSheet?.let { address ->
        // keyed so a second tap on another address resolves afresh rather than reusing the first
        key(address) {
            // One of a KasSigner account's addresses (Cold Storage, back from KaPay): its own
            // History sheet; the chatting and spending addresses keep theirs.
            val coldStorageVm: com.kachat.app.viewmodels.ColdStorageViewModel = hiltViewModel()
            var isCold by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(address) {
                isCold = runCatching { coldStorageVm.isOwnColdAddress(address) }.getOrDefault(false)
            }
            when (isCold) {
                true -> com.kachat.app.ui.screens.coldstorage.ColdStorageAddressHistorySheet(
                    address = address,
                    onDone = { ownAddressSheet = null },
                    viewModel = coldStorageVm,
                )
                false -> OwnAddressHistorySheet(
                    address = address,
                    walletViewModel = walletViewModel,
                    onOpenChat = { contact ->
                        ownAddressSheet = null
                        navController.navigate("chat/$contact")
                    },
                    onDismiss = { ownAddressSheet = null },
                )
                null -> Unit
            }
        }
    }

    // Incoming system-share (text/link/image shared from another app — see MainActivity.
    // handleShareIntent): a direct-share pick carries the chosen conversation and opens the
    // in-place compose sheet directly; a plain "KaChat" share target lands on the Chats list,
    // whose banner asks the user to pick the chat, and the thread they open hands the share to
    // the same sheet.
    val pendingShare by com.kachat.app.services.ShareIntake.pending.collectAsState()
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        if (share.targetContactId != null) {
            // Direct-share pick: the conversation is already known, so go straight to the in-place
            // compose sheet (matching iOS's share extension) instead of dropping the user into the
            // chat with a silently pre-filled composer.
            com.kachat.app.services.ShareIntake.pending.value = null
            if (!share.isExpired()) {
                com.kachat.app.services.ShareIntake.compose.value = com.kachat.app.services.ShareCompose(
                    contactId = share.targetContactId,
                    text = share.text,
                    imageUris = share.imageUris
                )
            }
        } else {
            // Untargeted: surface the Chats list wherever the user currently is; whichever chat
            // the user opens next hands the share to the same compose sheet.
            show(AppTab.CHATS)
        }
    }

    // The in-place share compose sheet, rendered above the whole shell so it survives whatever
    // screen is underneath (see ShareComposeSheet).
    val shareCompose by com.kachat.app.services.ShareIntake.compose.collectAsState()
    shareCompose?.let { compose ->
        ShareComposeSheet(
            share = compose,
            onOpenChat = { contactId -> navController.navigate("chat/$contactId") },
            onDismiss = { com.kachat.app.services.ShareIntake.compose.value = null },
            chatViewModel = chatViewModel
        )
    }

    // Someone's profile link (kachat://profile/<address>, https://kachat.app/u/<address>, or a
    // tapped KaChat Profile card). It opens User Info for anyone - contact or not - with Open Chat
    // one tap away and their name and avatar to check who the link is for before writing to them
    // (iOS 81df734). Your own shows your own card, and you are never added as your own contact
    // (iOS 95f50ca); a stranger's card or link saves nothing. A link from outside the app with
    // `chat=1` (KaPost's Chat button) opens the 1:1 chat itself, started if need be, the way the
    // Addresses screen's Chat does (iOS 724d477) - but not for your own address, which has no
    // chat with itself.
    val pendingProfile by com.kachat.app.ui.screens.ProfileDeepLink.pending.collectAsState()
    LaunchedEffect(pendingProfile) {
        val link = pendingProfile ?: return@LaunchedEffect
        com.kachat.app.ui.screens.ProfileDeepLink.consumePending()
        // KaPost's screens are full-screen covers: they close so the chat or card shows.
        com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
        val address = link.address
        val mine = walletViewModel.address.value
        // A link from outside may open (and so save) only someone already known: a saved
        // contact's first message rings as an ordinary chat, skipping Message Requests and Who
        // Can Message You?. A stranger's link opens User Info and saves nothing (iOS audit
        // 2026-11-24 #20); KaPost's own Chat button opens the chat as before.
        val known = chatViewModel.isKnownChatPartner(address)
        when {
            mine != null && mine.equals(address, ignoreCase = true) ->
                navController.navigate("chat_info/$mine")
            link.openChat && (known || !link.external) -> chatViewModel.openProfile(address) { navController.navigate("chat/$it") }
            known -> chatViewModel.openProfile(address) { navController.navigate("chat_info/$it") }
            else -> navController.navigate("chat_info/$address")
        }
    }

    // A payment request tapped, pasted or opened from outside the app (iOS c6851d6
    // PaymentRequestSheetPresenter): Send Kaspa with it filled in, or your own to copy or share.
    com.kachat.app.ui.screens.PaymentRequestSheetHost(walletViewModel = walletViewModel, chatViewModel = chatViewModel)

    // Shows the Welcome Guide automatically the first time this shell renders after an account is
    // added — whether created or imported — see `WalletViewModel.pendingWelcomeGuide`. Always shown
    // on create/import, independent of the "show setup guides" setting.
    val pendingWelcomeGuide by walletViewModel.pendingWelcomeGuide.collectAsState()
    // Guards the interrupted-run re-presenter below against racing the auto-present:
    // markOnboardingWizardPending() persists BEFORE the navigation composes, so the marker
    // flow could re-fire that effect while the top route was still the old tab page - which
    // stacked a SECOND welcome_guide under the first and made a fresh import walk the whole
    // wizard twice. Once auto-presented, re-presenting is only ever a next-launch concern.
    var welcomeGuidePresentedThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(pendingWelcomeGuide) {
        if (pendingWelcomeGuide) {
            welcomeGuidePresentedThisSession = true
            // Onboarding runs are unskippable end to end - a run only completes at Finish, and
            // this persisted marker re-presents an interrupted run at next launch.
            walletViewModel.markOnboardingWizardPending()
            navController.navigate("welcome_guide?onboarding=true")
            walletViewModel.consumePendingWelcomeGuide()
        }
    }

    // Interrupted first run: the auto-present trigger above is in-memory only and lost on
    // relaunch, so the persisted marker re-presents the guide until it's genuinely finished,
    // replaying the whole onboarding run from the top (still unskippable).
    val onboardingWizardPendingMarker by walletViewModel.onboardingWizardPending.collectAsState()
    // A hold with no wizard behind it would strand the account unsynced forever. Once the guide
    // is neither pending nor on screen, lift it.
    LaunchedEffect(pendingWelcomeGuide, currentTopRoute) {
        if (!pendingWelcomeGuide && currentTopRoute?.startsWith("welcome_guide") != true) {
            chatViewModel.releaseOnboardingHoldIfIdle()
        }
    }

    LaunchedEffect(onboardingWizardPendingMarker, pendingWelcomeGuide, currentTopRoute) {
        if (pendingWelcomeGuide || welcomeGuidePresentedThisSession) return@LaunchedEffect
        if (currentTopRoute == null) return@LaunchedEffect
        // Finish clears the marker asynchronously and pops at once, so the marker can still read
        // true when the top route changes back: a guide this session has shown (re-presented
        // below, or restored with a killed app's back stack) must count, or Finish starts it again.
        if (currentTopRoute.startsWith("welcome_guide")) {
            if (onboardingWizardPendingMarker == true) welcomeGuidePresentedThisSession = true
            return@LaunchedEffect
        }
        if (onboardingWizardPendingMarker == true) {
            welcomeGuidePresentedThisSession = true
            navController.navigate("welcome_guide?onboarding=true")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Badges (iOS `AppTabBadge`): Profile adds up every notification behind its bell (KaPost's
    // and the wallet's), Chats its chats' and groups' unread messages (not the rooms': their count
    // is on the Chats / Public Chats button beside KaChat's title, iOS 961fc05a, owner
    // 2026-11-25), and Kaspa Hub what the tabs inside it hold. The rest show none. The app icon is
    // Chats' count plus bell-on rooms plus KaPost's unseen activity (iOS `AppTabBadge.appIconCount`).
    // ---------------------------------------------------------------------------------------
    val badgesVm: ShellBadgesViewModel = hiltViewModel()
    val profileBadge by badgesVm.profileCount.collectAsState()
    val roomsBadge by badgesVm.roomsCount.collectAsState()
    val kaPostsUnseen by badgesVm.kaPostsUnseen.collectAsState()
    // Not in the background: these re-run the whole chat and group lists on every database
    // write, for a dock nobody sees (audit 2026-11-25 R6). Nothing else reads the icon count.
    val dockChatsUnread by chatViewModel.chatListUnreadCount.collectAsStateWhileStarted()
    val dockGroupsUnread by chatViewModel.groupsUnreadCount.collectAsStateWhileStarted()
    // The wallet's notification store is a singleton keyed per wallet internally; reload whenever
    // the active account changes so a switch never leaves the previous account's count showing.
    val dockNotifVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
    val dockNotifAddress by walletViewModel.address.collectAsState()
    LaunchedEffect(dockNotifAddress) {
        dockNotifVm.store.reloadIfNeeded()
    }
    fun badgeCount(tab: AppTab): Int = when (tab) {
        AppTab.PROFILE -> profileBadge
        AppTab.CHATS -> dockChatsUnread + dockGroupsUnread
        AppTab.HUB -> hubTabs.sumOf { if (it == AppTab.HUB) 0 else badgeCount(it) }
        else -> 0
    }
    val chatsBadgeTotal = dockChatsUnread + dockGroupsUnread
    LaunchedEffect(chatsBadgeTotal, roomsBadge, kaPostsUnseen) {
        AppIconBadge.update(chatsBadgeTotal + roomsBadge + kaPostsUnseen)
    }

    // Which tab the glass pill sits under. The tab you are IN, the way iOS's tab bar keeps the tab
    // that pushed a screen lit: a dock tab's own route lights itself; a pushed screen lights the
    // nearest dock tab under it on the stack, else the tab it belongs to - and when that tab lives
    // in Kaspa Hub, the Hub.
    val selectedDockIndex = run {
        val direct = localTabOrder.indexOfFirst { tab ->
            currentDestination?.hierarchy?.any { it.route == tab.route } == true
        }
        if (direct >= 0) return@run direct
        val underneath = dockBackStack.asReversed().drop(1).firstNotNullOfOrNull { entry ->
            localTabOrder.indexOfFirst { it.route == entry.destination.route }.takeIf { it >= 0 }
        }
        if (underneath != null) return@run underneath
        val owner = dockOwnerTab(currentTopRoute) ?: return@run -1
        localTabOrder.indexOf(owner).takeIf { it >= 0 } ?: localTabOrder.indexOf(AppTab.HUB)
    }

    /** What a tap on a dock tab does (iOS `handleTabSelectionChange`) - shared by the dock, a
     *  slide along it, and the copy of it over KaPost's screens. */
    fun goToDockTab(tab: AppTab) {
        // Every KaPost full-screen screen closes, so the tab is what shows.
        com.kachat.app.ui.screens.KaPostsDock.closeCovers.tryEmit(Unit)
        // KaPost tapped: the feed back to its top.
        com.kachat.app.ui.screens.KaPostsDock.tabTapped(kaPostsTab = tab == AppTab.KAPOSTS)
        val onOwnRoute = navController.currentDestination?.hierarchy?.any { it.route == tab.route } == true
        // Kaspa Hub tapped again: back to its grid, the way out of a section opened there.
        if (tab == AppTab.HUB && (onOwnRoute || localTabOrder.getOrNull(selectedDockIndex) == AppTab.HUB)) {
            HubRouter.close()
        }
        if (onOwnRoute) {
            // Already there - let that screen know it was re-tapped (so it can dismiss its own
            // transient UI) rather than navigating, which would land on the start destination.
            walletViewModel.notifyTabReselected(tab.route)
            return
        }
        navigateToTab(navController, tab.route)
    }

    // Frosted glass under the floating dock: the NavHost below is the backdrop, and the dock
    // draws a blurred copy of whatever sits behind it (RenderEffect on Android 12+, a
    // translucent scrim below that) - the way iOS's bar is a material rather than a colour.
    val dockHaze = remember { HazeState() }
    val dockShown = onTabRoute && !hideBottomBar && !com.kachat.app.ui.screens.DockCover.active

    /** The dock itself: the floating glass bar with its tabs and badges. */
    val dockBar: @Composable () -> Unit = {
        // Over a white page (Profile's QR pages) iOS's tab bar glass turns light, in dark mode
        // too: the glass takes on what is behind it.
        CompositionLocalProvider(
            LocalAppColors provides if (com.kachat.app.ui.screens.DockOverLightPage.active) com.kachat.app.ui.theme.LightAppColors else LocalAppColors.current
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // navigationBarsPadding() first so the visual margin sits above the system nav
                    // bar (gesture pill or 3-button bar) rather than being eaten by it.
                    .navigationBarsPadding()
                    // iOS 26's tab bar: 21pt in from each side (measured on the iPhone).
                    .padding(bottom = 12.dp, start = 21.dp, end = 21.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Sliding a finger along the bar carries the lens; releasing lands on the tab
                // under it, the way the glass follows a finger on iOS. Null while nobody is
                // dragging, so the lens follows the selection instead.
                var dockDragIndex by remember { mutableStateOf<Int?>(null) }

                // The slide handler below is created once and outlives every navigation; this
                // always holds the current tap action.
                val latestGoToDockTab by androidx.compose.runtime.rememberUpdatedState<(AppTab) -> Unit> {
                    goToDockTab(it)
                }

                // A new dock arrangement builds a fresh bar (iOS keys its TabView by the
                // arrangement), so every icon, label and the glass lens start from the tabs the bar
                // now holds rather than from whichever tab held that slot before.
                val dockArrangementKey = localTabOrder.joinToString(",") { it.key }
                key(dockArrangementKey) {
                // iOS's bar is 60pt tall (measured on the iPhone 17).
                BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
                // The pill slides between tabs rather than appearing under the new one, the way
                // iOS's glass tab bar moves: one piece of glass that travels.
                val dockItemWidth = (maxWidth - 4.dp) / localTabOrder.size.coerceAtLeast(1)
                val pillIndex = dockDragIndex ?: selectedDockIndex
                val pillOffset by animateDpAsState(
                    targetValue = 2.dp + dockItemWidth * pillIndex.coerceAtLeast(0),
                    // Under a finger it keeps up; left to itself it settles with a spring.
                    animationSpec = if (dockDragIndex != null) tween(90) else spring(dampingRatio = 0.75f, stiffness = 420f),
                    label = "dockPill",
                )
                // The bar itself: the surface colour at 92% over the page. It used to blur the
                // page under that tint on every frame the page moved (a scroll, a spinner), for
                // the 8% that shows through; a flat fill of the same colour over the page's
                // background looks the same and costs no offscreen pass (audit 2026-11-25 R10).
                // The drag lens below still samples the blurred page while a finger slides.
                val barFill = com.kachat.app.ui.screens.toolbarGlassFill().copy(alpha = 0.92f)
                    .compositeOver(LocalAppColors.current.background)
                Box(
                    Modifier
                        .height(60.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        // iOS's bar on black reads #191919 (the toolbar glass)
                        .background(barFill)
                        .border(0.5.dp, com.kachat.app.ui.screens.toolbarGlassEdge(), RoundedCornerShape(30.dp)),
                )
                if (dockDragIndex == null && selectedDockIndex >= 0) {
                    // At rest iOS 26 marks the tab with a filled capsule - #3A3A3A on the #191919
                    // bar, 4pt inside its height - and no rim.
                    val restShape = RoundedCornerShape(percent = 50)
                    Box(
                        Modifier
                            .offset(x = pillOffset)
                            .padding(vertical = 4.dp)
                            .width(dockItemWidth)
                            .height(52.dp)
                            .clip(restShape)
                            .background(
                                if (LocalAppColors.current.isDark) Color(0xFF3A3A3A)
                                else Color.Black.copy(alpha = 0.07f)
                            ),
                    )
                } else if (dockDragIndex != null) {
                    // While a finger slides, the lens: a nearly clear bubble with a bright rim
                    // that stands a little proud of the bar, as iOS's does.
                    val pillShape = RoundedCornerShape(percent = 50)
                    Box(
                        Modifier
                            .offset(x = pillOffset - 3.dp, y = (-4).dp)
                            .width(dockItemWidth + 6.dp)
                            .height(68.dp)
                            .clip(pillShape)
                            .hazeChild(
                                state = dockHaze,
                                shape = pillShape,
                                style = HazeStyle(tint = Color.White.copy(alpha = 0.06f), blurRadius = 30.dp),
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.55f),
                                        KaspaTeal.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.30f),
                                    ),
                                ),
                                shape = pillShape,
                            ),
                    )
                }
                val dockDensity = LocalDensity.current
                val dockIsRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
                Row(
                    modifier = Modifier
                        .height(60.dp)
                        .fillMaxWidth()
                        // Gesture navigation reserves a strip at each screen edge for Back; a slide
                        // starting on the outermost tab began inside it, and Android took it.
                        .systemGestureExclusion()
                        .pointerInput(localTabOrder, dockItemWidth, dockIsRtl) {
                            val slotPx = with(dockDensity) { dockItemWidth.toPx() }
                            val startPadPx = with(dockDensity) { 2.dp.toPx() }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var sliding = false
                                try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (sliding) {
                                            dockDragIndex?.let { index ->
                                                localTabOrder.getOrNull(index)?.let { latestGoToDockTab(it) }
                                            }
                                        }
                                        dockDragIndex = null
                                        break
                                    }
                                    val movedX = kotlin.math.abs(change.position.x - down.position.x)
                                    if (!sliding && movedX > viewConfiguration.touchSlop) sliding = true
                                    if (sliding) {
                                        change.consume()
                                        // The tabs run from the leading edge, the right in RTL.
                                        val fromLeadingX = if (dockIsRtl) size.width - change.position.x else change.position.x
                                        dockDragIndex = (((fromLeadingX - startPadPx) / slotPx).toInt())
                                            .coerceIn(0, localTabOrder.lastIndex.coerceAtLeast(0))
                                    }
                                }
                                } finally {
                                    // A slide the system takes away cancels this rather than
                                    // delivering a release; the lens must not stay stranded.
                                    dockDragIndex = null
                                }
                            }
                        }
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    val hubPainter = rememberKaspaDockPainter()
                    localTabOrder.forEach { tab ->
                        // Keyed by tab, so a tab's own state stays with that tab when the
                        // arrangement changes rather than with whichever slot renders it.
                        key(tab.key) {
                            // Lit by the same answer the glass pill sits under.
                            val selected = localTabOrder.getOrNull(selectedDockIndex) == tab
                            val label = stringResource(tab.labelRes)
                            Box(
                                modifier = Modifier
                                    .height(52.dp)
                                    .weight(1f)
                                    // Not clipped, so the badge can stand above the icon in
                                    // full; no ripple either, as on iOS, where the moving pill is
                                    // the only press feedback.
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { goToDockTab(tab) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        // Settings carries the connection dot, Profile your
                                        // picture and the backup dot (iOS 518fdc8c, DockIcons.kt).
                                        DockTabIcon(
                                            tab = tab,
                                            selected = selected,
                                            hubPainter = hubPainter,
                                            label = label,
                                            walletViewModel = walletViewModel,
                                        )
                                        // How many are waiting, not just that some are (iOS
                                        // AppTabBadge.dockLabel): "99+" past 99.
                                        val badgeCount = badgeCount(tab)
                                        if (badgeCount > 0) {
                                            // A box with a minimum square size, so one digit is a
                                            // circle and only a longer label stretches it into a pill.
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 8.dp, y = (-6).dp)
                                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(LocalAppColors.current.danger)
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = label,
                                        color = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                        // Longer labels ("Leaderboard", "Kaspa Hub") shrink in a
                                        // five-tab dock rather than clip.
                                        fontSize = when {
                                            label.length > 11 -> 7.sp
                                            label.length > 9 -> 8.sp
                                            else -> 10.sp
                                        },
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        // iOS 26's tab labels are medium weight, selected or not.
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                }
                } // key(dockArrangementKey)
                }
            }
        }
    }

    // KaPost's full-screen screens draw the real dock over themselves (iOS `KaPostsDockCopy`).
    val latestDockBar by rememberUpdatedState(dockBar)
    DisposableEffect(Unit) {
        com.kachat.app.ui.screens.KaPostsDock.dockContent.value = {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { latestDockBar() }
        }
        onDispose { com.kachat.app.ui.screens.KaPostsDock.dockContent.value = null }
    }

    /** A tab's screen, the same whether it sits in the dock or opens from Kaspa Hub (iOS
     *  `AppTabContent`). */
    val tabContent: @Composable (AppTab) -> Unit = { tab ->
        when (tab) {
            AppTab.CHATS -> ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
            AppTab.WALLET -> WalletScreen(viewModel = walletViewModel, navController = navController, chatViewModel = chatViewModel)
            AppTab.ADDRESS_BOOK -> AddressBookScreen(
                onBack = null,
                onOpenChat = { address -> navController.navigate("chat/$address") },
                walletViewModel = walletViewModel,
            )
            // Your KaPost profile (iOS `KaPostsView(rootMode: .myProfile)`); See All Posts shows the
            // KaPost tab wherever it lives.
            AppTab.PROFILE -> KaPostsMyProfileScreen(
                navController = navController,
                onSeeAllPosts = { show(AppTab.KAPOSTS) },
                walletViewModel = walletViewModel,
            )
            AppTab.SETTINGS -> SettingsScreen(navController, walletViewModel = walletViewModel)
            // drawn by the Hub's own route, around the other tabs' screens
            AppTab.HUB -> {}
            AppTab.PORTFOLIO -> PortfolioTab(navController = navController, walletViewModel = walletViewModel)
            AppTab.COLD_STORAGE -> ColdStorageTab(
                walletViewModel = walletViewModel,
                onOpenAccountBackup = { navController.navigate("account_backup") },
            )
            // No backup strip on any of these: it's on Profile only, the dock's Profile icon
            // carrying a yellow dot meanwhile (iOS 518fdc8c, owner 2026-11-23).
            AppTab.KACHAT_NAMES -> KachatMarketScreen(onBack = null)
            // The one KaPost instance that opens links and pushes (it is in one place at a time).
            AppTab.KAPOSTS -> KaPostsScreen(navController = navController, walletViewModel = walletViewModel)
            AppTab.GAMES -> GamesScreen(navController = navController, walletViewModel = walletViewModel)
            // K's own block explorer (iOS 961fc05a); it replaced KaChat Stats.
            AppTab.EXPLORER -> com.kachat.app.ui.screens.explorer.ExplorerScreen()
            AppTab.APPS -> KaspaWebsitesScreen()
            // kasshi.io in the in-app browser, its X back to the grid (iOS 35b112ce). Android's
            // browser never pauses the node, so iOS's `powersDown: false` needs nothing here.
            AppTab.KASSHI -> com.kachat.app.ui.screens.InAppBrowserScreen(
                url = "https://kasshi.io",
                title = stringResource(R.string.kasshi),
                onClose = { HubRouter.close() },
            )
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        bottomBar = {
            // Only on the tabs and the screens pushed inside them that keep iOS's tab bar; other
            // pushed screens fill the whole screen with their own Scaffold.
            if (dockShown) dockBar()
        }
    ) { innerPadding ->
        // The green dot's half sheet, hosted once for the whole app rather than by each of the
        // headers that draw a dot - so it comes up OVER whatever you were reading.
        ConnectionStatusOverlayHost(onOpenConnectionSettings = { navController.navigate("connection_settings") })
        // A .kachat registration in flight: its progress half sheet, back up after a relaunch
        // (iOS 428a797b `KachatRegistrationPresenter` on MainTabView; `KachatNamesActions.resume`
        // sets it on activation). Launched networks only; draws nothing otherwise.
        com.kachat.app.ui.screens.KachatRegistrationPresenter()

        val dockInset = innerPadding.calculateBottomPadding()
        // Only the tabs and the pages that keep the dock reserve room for it (LocalDockInset);
        // innerPadding is never applied to the NavHost as a whole.
        NavHost(
            navController = navController,
            startDestination = startRoute,
            // What the dock's glass samples.
            modifier = Modifier.haze(state = dockHaze),
            // A short, snappy fade reads as instant without the jarring hard-cut of none.
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            // Every tab has its route, used while it sits in the dock; an instant swap, as a tab
            // bar's switch is. Kaspa Hub draws the tabs inside it with the same [tabContent].
            AppTab.entries.forEach { tab ->
                composable(
                    tab.route,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    // Runs on under the dock, as iOS's content runs on under its tab bar.
                    CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                        if (tab == AppTab.HUB) {
                            KaspaHubScreen(badgeCount = { badgeCount(it) }, content = { section -> tabContent(section) })
                        } else {
                            tabContent(tab)
                        }
                    }
                }
            }
            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("account_backup") {
                com.kachat.app.ui.screens.AccountBackupScreen(
                    viewModel = walletViewModel,
                    onClose = { navController.popBackStack() }
                )
            }
            composable("nfc_card_backup") {
                com.kachat.app.ui.screens.NfcCardBackupScreen(
                    viewModel = walletViewModel,
                    onClose = { navController.popBackStack() }
                )
            }
            composable("backup_hygiene") {
                com.kachat.app.ui.screens.BackupHygieneScreen(
                    viewModel = walletViewModel,
                    onClose = { navController.popBackStack() }
                )
            }

            // ---- Back since iOS 148a5331 -------------------------------------------------
            // Portfolio's charts, on the tab's own view model.
            composable(PortfolioRoutes.PRICE_CHART) {
                val portfolioVm = sharedPortfolioViewModel(navController)
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    PortfolioPriceChartScreen(navController, portfolioVm)
                }
            }
            composable(PortfolioRoutes.VALUE_CHART) {
                val portfolioVm = sharedPortfolioViewModel(navController)
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    PortfolioValueChartScreen(navController, portfolioVm)
                }
            }
            // A public room, pushed inside Chats with the dock kept and its composer just above it.
            composable(PublicChatRoutes.ROOM) { entry ->
                val channel = PublicChatRoutes.channelArgument(entry.arguments) ?: return@composable
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides threadDockInset(dockInset)) {
                    BroadcastChannelScreen(
                        channelName = channel,
                        onBack = { navController.popBackStack() },
                        navController = navController,
                        // A sender's View Profile: their KaPost profile.
                        // Your own (View Profile, an @ of you): the Profile tab (iOS d089011f).
                        onOpenKaPostProfile = { address ->
                            com.kachat.app.ui.screens.KaPostsDeepLink.openProfile(address)
                            if (com.kachat.app.ui.screens.KaPostsDeepLink.pendingProfileAddress.value == address) show(AppTab.KAPOSTS)
                        },
                    )
                }
            }
            composable(PublicChatRoutes.ROOM_INFO) { entry ->
                val channel = PublicChatRoutes.channelArgument(entry.arguments) ?: return@composable
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    BroadcastRoomInfoScreen(
                        channelName = channel,
                        onBack = { navController.popBackStack() },
                        onOpenHiddenUsers = { navController.navigate(PublicChatRoutes.hiddenUsers(channel)) },
                    )
                }
            }
            composable(PublicChatRoutes.HIDDEN_USERS) { entry ->
                val channel = PublicChatRoutes.channelArgument(entry.arguments) ?: return@composable
                BroadcastHiddenUsersScreen(channelName = channel, onBack = { navController.popBackStack() })
            }
            // KaPlay's and the Leaderboard tab's pages.
            kaPlayGraph(navController, dockInset)
            // Settings > Help's guides, Settings > About > Terms of Use, Settings > Notifications > KaPost.
            composable(SettingsShellRoutes.TOKEN_GUIDE) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    com.kachat.app.ui.screens.tokens.TokenGuideScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(SettingsShellRoutes.KACHAT_NAMES_GUIDE) {
                KachatSetupGuideScreen(onClose = { navController.popBackStack() })
            }
            composable(SettingsShellRoutes.TERMS_OF_USE) {
                TermsOfUseScreen(onBack = { navController.popBackStack() })
            }
            composable(SettingsShellRoutes.KAPOSTS_NOTIFICATIONS) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    KaPostsNotificationSettingsScreen(onBack = { navController.popBackStack() })
                }
            }
            // --------------------------------------------------------------------------------

            composable(
                "spending_address_detail/{index}",
                arguments = listOf(navArgument("index") { type = NavType.IntType })
            ) { backStackEntry ->
                // Deliberately its OWN route/screen rather than reusing
                // "cold_storage_tx_history/{address}" (which Manage Addresses used to route
                // through) — that screen's Send button unconditionally opens the external-QR-
                // signer flow for a watch-only key, which is broken for a spending address: its
                // private key already lives in this wallet, so it needs direct signing via
                // WalletViewModel.withdrawFromSpendingAddress instead. Index (not address) is the
                // route arg since that's what withdrawFromSpendingAddress signs by.
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    SpendingAddressTxHistoryScreen(
                        index = backStackEntry.arguments?.getInt("index") ?: 0,
                        onBack = { navController.popBackStack() },
                        viewModel = walletViewModel,
                        onOpenChat = { address -> navController.navigate("chat/$address") },
                    )
                }
            }

            // The chatting/identity address's "Manage Address" row - field-for-field the same
            // screen as spending_address_detail, just for the single fixed identity address.
            composable("identity_address_detail") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    IdentityAddressDetailScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = walletViewModel,
                        onOpenChat = { address -> navController.navigate("chat/$address") },
                    )
                }
            }

            composable("manage_addresses") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    ManageAddressesScreen(
                        viewModel = walletViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToTxHistory = { index -> navController.navigate("spending_address_detail/$index") },
                        onOpenChattingAddress = { navController.navigate("identity_address_detail") },
                        onNavigateToHidden = { navController.navigate("manage_addresses_hidden") },
                        onNavigateToVisibility = { navController.navigate("manage_addresses_visibility") }
                    )
                }
            }

            composable("manage_addresses_visibility") {
                AddressVisibilityScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("manage_addresses_hidden") {
                ManageAddressesHiddenScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToTxHistory = { index -> navController.navigate("spending_address_detail/$index") }
                )
            }

            composable("edit_kachat_profile") {
                com.kachat.app.ui.screens.KachatProfileEditorScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                "welcome_guide?onboarding={onboarding}",
                arguments = listOf(
                    // Explicit presentation context (never inferred from persisted markers):
                    // true only for auto-presented onboarding runs, which are unskippable end
                    // to end. Help replays keep the default false and stay skippable.
                    navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                WelcomeGuideScreen(
                    walletViewModel = walletViewModel,
                    isOnboardingRun = backStackEntry.arguments?.getBoolean("onboarding") ?: false,
                    onFinished = {
                        // The import run is over — a later Help replay of this same guide must not
                        // offer "Change Chatting Address" again (see WelcomeGuideFundingStep).
                        walletViewModel.clearJustImportedWallet()
                        // Everything the account owns starts syncing now, behind a dialog: a
                        // half-populated chat list invites taps on chats whose history has not
                        // landed yet.
                        chatViewModel.runInitialSyncAfterOnboarding()
                        navController.popBackStack()
                    }
                )
            }

            composable("kns_domains") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    // Your Usernames (Wallet's card; iOS 518fdc8c)
                    com.kachat.app.ui.screens.YourUsernamesScreen(
                        viewModel = walletViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable("settings_section/{sectionKey}") { backStackEntry ->
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    SettingsScreen(
                        navController = navController,
                        sectionKey = backStackEntry.arguments?.getString("sectionKey"),
                        // The shell's: Account Name saves as its page closes, which would cancel a save
                        // run from a view model scoped to this page.
                        walletViewModel = walletViewModel,
                    )
                }
            }

            // Settings > Storage sub-pages, one per backup provider (see StorageScreens.kt) -
            // the Storage section itself is just the hub of rows pointing here, like iOS.
            composable("storage_nextcloud") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    NextcloudStorageScreen(onBack = { navController.popBackStack() })
                }
            }

            // Settings > Help (moved from the old Profile, iOS 148a5331 `ProfileHelpView` in
            // SettingsView): the Welcome Guide, the .kachat Names Guide and the kUSDT Guide.
            composable("help") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    HelpScreen(
                        onBack = { navController.popBackStack() },
                        onWelcomeGuide = { navController.navigate("welcome_guide") },
                        onKachatNamesGuide = { navController.navigate(SettingsShellRoutes.KACHAT_NAMES_GUIDE) },
                        onKusdtGuide = { navController.navigate(SettingsShellRoutes.TOKEN_GUIDE) },
                    )
                }
            }

            composable("cache_settings") {
                val cacheManager: com.kachat.app.services.CacheManager = hiltViewModel<com.kachat.app.viewmodels.CacheViewModel>().cacheManager
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    CacheSettingsScreen(
                        onBack = { navController.popBackStack() },
                        cacheManager = cacheManager,
                    )
                }
            }

            composable("open_source_licenses") {
                OpenSourceLicensesScreen(onBack = { navController.popBackStack() })
            }

            composable("connection_settings") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    ConnectionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable("language_settings") {
                LanguageSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("currency_settings") {
                CurrencySettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("kaspa_explorer_settings") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    KaspaExplorerSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable("notification_settings") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    NotificationSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable("wallet_notification_settings") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    WalletNotificationSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable("quick_reaction_settings") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    QuickReactionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(com.kachat.app.ui.screens.WhoCanMessageRoutes.SETTINGS) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides dockInset) {
                    com.kachat.app.ui.screens.WhoCanMessageSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            // `group` is an optional query-style arg (defaults false) so the create button can
            // open this screen straight into group-builder mode.
            composable(
                "create_chat?group={group}",
                arguments = listOf(navArgument("group") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val startInGroupMode = backStackEntry.arguments?.getBoolean("group") ?: false
                // Back to Chats underneath: its dock slot, or Kaspa Hub with Chats open there.
                val chatsHome = if (AppTab.CHATS in DockLayout.dock.value) AppTab.CHATS.route else AppTab.HUB.route
                CreateChatScreen(
                    onBack = { navController.popBackStack() },
                    onChatCreated = { address ->
                        navController.navigate("chat/$address") {
                            popUpTo(chatsHome)
                        }
                    },
                    onGroupCreated = { groupId ->
                        navController.navigate("group_chat/$groupId") {
                            popUpTo(chatsHome)
                        }
                    },
                    startInGroupMode = startInGroupMode,
                    chatViewModel = chatViewModel
                )
            }

            composable("group_chat/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                // The messages run on under the dock, as iOS's do under its tab bar; the composer
                // sits just above it (LocalDockInset, see threadDockInset).
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides threadDockInset(dockInset)) {
                    GroupChatThreadScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel, walletViewModel = walletViewModel)
                }
            }

            composable("group_chat_info/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatInfoScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel)
            }

            // Message Requests - people who wrote first and aren't accepted (iOS f7ca401).
            composable("message_requests") {
                com.kachat.app.ui.screens.MessageRequestsScreen(navController = navController, chatViewModel = chatViewModel)
            }

            // A group you were added to, waiting for your answer (iOS bfe9f022).
            composable("group_invite/{inviteId}") { backStackEntry ->
                val inviteId = backStackEntry.arguments?.getString("inviteId") ?: return@composable
                com.kachat.app.ui.screens.GroupInviteRequestScreen(navController = navController, inviteId = inviteId, chatViewModel = chatViewModel)
            }

            // Chat thread — navigated to from ChatsScreen. paymentMode is an optional query-style
            // arg (defaults false) so a "Pay in Kaspa" shortcut elsewhere (e.g. a group sender's
            // avatar menu) can land the user straight in payment-entry mode.
            composable(
                "chat/{contactId}?paymentMode={paymentMode}",
                arguments = listOf(navArgument("paymentMode") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val startInPaymentMode = backStackEntry.arguments?.getBoolean("paymentMode") ?: false
                // The messages run on under the dock and the composer sits just above it, as the
                // group thread's does (see there).
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides threadDockInset(dockInset)) {
                    ChatThreadScreen(
                        navController = navController,
                        contactId = contactId,
                        chatViewModel = chatViewModel,
                        walletViewModel = walletViewModel,
                        startInPaymentMode = startInPaymentMode
                    )
                }
            }

            composable("chess_game/{contactId}/{gameId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val gameId = backStackEntry.arguments?.getString("gameId") ?: return@composable
                ChessGameScreen(
                    navController = navController,
                    contactId = contactId,
                    gameId = gameId,
                    chatViewModel = chatViewModel
                )
            }

            composable("chat_info/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ChatInfoScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
                    // Replaces this screen rather than stacking on it, so Back from the chat
                    // returns where User Info was opened from instead of to User Info again.
                    onOpenChat = { id ->
                        navController.popBackStack()
                        navController.navigate("chat/$id")
                    },
                    onNavigateToNotificationSettings = { id -> navController.navigate("contact_notification_settings/$id") },
                )
            }

            composable(
                "contact_notification_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactNotificationSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // iOS's in-app toast for every screen: over a tab it sits above the dock, as iOS's sits
        // above the tab bar; a pushed screen has it above the navigation bar and keyboard.
        com.kachat.app.ui.screens.IosToastHost(
            modifier = Modifier.padding(bottom = if (dockShown) dockInset else 0.dp),
            bottomInsets = if (dockShown) {
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            } else {
                androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom)
            },
        )
    }
}

/**
 * The own address a tapped Profile-bell wallet row asks [MainShell] to open in its History sheet
 * (iOS 95e2cba `OwnAddressRoute.pending`). MainShell takes it and clears it.
 */
object OwnAddressRoute {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/**
 * What a chat thread keeps clear for the dock: the part the keyboard doesn't already cover. The
 * thread's own bottomBar applies imePadding() to clear the keyboard, so reserving both left a dead
 * gap above a full keyboard; a floating keyboard or a hardware keyboard's small bar is shorter than
 * the dock, which stays on screen and must not cover the message box.
 */
@Composable
private fun threadDockInset(dockInset: Dp): Dp {
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return (dockInset - keyboard).coerceAtLeast(0.dp)
}

/**
 * One of your own addresses, opened from a tapped "Received" notification on its History tab
 * (iOS 13046ad `OwnAddressHistorySheet`): the chatting address or a spending address, each on the same screen you'd reach it through
 * (Profile > Chatting Address, Manage Addresses), resolved with its balance for the header. Those
 * screens already open on History; at the root of this sheet each puts Done top left in place of
 * its back arrow (closesWithDone), where iOS's sheet has its Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnAddressHistorySheet(
    address: String,
    walletViewModel: WalletViewModel,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var resolved by remember { mutableStateOf<OwnAddressResolution?>(null) }
    /** Bumped by Try Again: resolves again (iOS `resolved = nil; resolved = await resolve()`). */
    var resolveAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(address, resolveAttempt) {
        resolved = null
        resolved = when {
            walletViewModel.address.value?.equals(address, ignoreCase = true) == true -> OwnAddressResolution.Chatting
            else -> when (val own = walletViewModel.resolveOwnSpendingAddress(address)) {
                is WalletViewModel.OwnSpendingAddress.Found -> OwnAddressResolution.Spending(own.entry)
                WalletViewModel.OwnSpendingAddress.BalanceUnavailable -> OwnAddressResolution.BalanceUnavailable
                null -> OwnAddressResolution.Unknown
            }
        }
    }
    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAppColors.current.background,
            dragHandle = null,
            // Both screens it shows can reveal a private key (View Private Key / Export). The
            // sheet is a window of its own that the Activity's FLAG_SECURE doesn't cover, and the
            // key overlay can't reach it (material3 1.2's sheet window is not a
            // DialogWindowProvider), so the sheet itself is secure throughout.
            properties = androidx.compose.material3.ModalBottomSheetProperties(
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
                isFocusable = true,
                shouldDismissOnBackPress = true,
            ),
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.fillMaxSize()) {
                    when (val target = resolved) {
                        OwnAddressResolution.Chatting -> IdentityAddressDetailScreen(
                            onBack = onDismiss,
                            viewModel = walletViewModel,
                            onOpenChat = onOpenChat,
                            closesWithDone = true,
                        )
                        is OwnAddressResolution.Spending -> SpendingAddressTxHistoryScreen(
                            index = target.entry.index,
                            onBack = onDismiss,
                            viewModel = walletViewModel,
                            onOpenChat = onOpenChat,
                            fallbackEntry = target.entry,
                            closesWithDone = true,
                        )
                        OwnAddressResolution.Unknown, OwnAddressResolution.BalanceUnavailable, null -> Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                TextButton(onClick = onDismiss) {
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.done),
                                        color = KaspaTeal,
                                    )
                                }
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (target == null) {
                                    CircularProgressIndicator(color = KaspaTeal)
                                } else if (target == OwnAddressResolution.BalanceUnavailable) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(16.dp),
                                    ) {
                                        Text(
                                            androidx.compose.ui.res.stringResource(com.kachat.app.R.string.couldnt_load_address_balance),
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                        )
                                        TextButton(onClick = { resolveAttempt++ }) {
                                            Text(
                                                androidx.compose.ui.res.stringResource(com.kachat.app.R.string.try_again),
                                                color = KaspaTeal,
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.own_address_not_in_wallet),
                                        color = LocalAppColors.current.textSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Copies from these screens confirm in the sheet's own toast.
                    com.kachat.app.ui.screens.IosToastHost(bottomInsets = com.kachat.app.ui.screens.SheetToastInsets)
                }
            }
        }
    }
}

/** Which of your addresses a "Received" notification named (iOS 13046ad `Resolved`). */
private sealed interface OwnAddressResolution {
    data object Chatting : OwnAddressResolution
    data class Spending(val entry: com.kachat.app.services.WalletService.SpendingAddressEntry) : OwnAddressResolution
    /** One of your spending addresses whose balance couldn't be read: Try Again, never 0. */
    data object BalanceUnavailable : OwnAddressResolution
    data object Unknown : OwnAddressResolution
}
