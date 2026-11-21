package com.kachat.app.ui.screens.chess

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kachat.app.R
import com.kachat.app.services.chess.ChessBotLinkRouter
import com.kachat.app.ui.screens.AccountBackupBanner
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.SettingsActionItem
import com.kachat.app.ui.screens.SettingsDivider
import com.kachat.app.ui.screens.SettingsFormSection
import com.kachat.app.ui.screens.SheetBarButton
import com.kachat.app.ui.screens.SheetNavBar
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.KaPlayText
import com.kachat.app.viewmodels.WalletViewModel

// KaPlay's own tab screens, back in KaChat (iOS 148a5331, phase 4: Views/Games/GamesView.swift,
// Views/Chess/ChessLeaderboardTabView.swift and ChessPlayerView.swift): the KaPlay tab (`.games`)
// and the Leaderboard tab (`.leaderboard`), both starting in Kaspa Hub, and a player's card.

/**
 * The KaPlay tab (iOS `.games`, KaPlay's Play tab): a card per game. Chess is the only one so far;
 * a new game is one more card here. Its title is "KaPlay", written as is in every language.
 *
 * A bot challenge from a post (`ChessBotLinks.open`, [ChessBotLinkRouter]) is for chess: this
 * screen opens Chess, and [ChessHomeScreen] takes the challenge from the router (iOS GamesView).
 */
@Composable
fun GamesScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    // The chess sentences are looked up by their English (normally set up at launch).
    KaPlayText.init(androidx.compose.ui.platform.LocalContext.current)
    val challenge by ChessBotLinkRouter.challenge.collectAsState()
    // This screen's own entry: a challenge is followed only while it is the one on top, never
    // from under Chess (or a board) already open over it.
    val ownEntry = androidx.compose.ui.platform.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry
    val top by navController.currentBackStackEntryAsState()
    LaunchedEffect(challenge, top) {
        if (challenge != null && ownEntry != null && top?.id == ownEntry.id) {
            navController.navigate(KaPlayRoutes.HOME)
        }
    }
    KaPlayBackupBanner(walletViewModel, navController) {
        Scaffold(
            containerColor = LocalAppColors.current.background,
            contentWindowInsets = WindowInsets(0),
            topBar = { ChessHomeBar(onBack = null, title = "KaPlay", showsBackupBanner = true) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = LocalDockInset.current + 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ChessGameCard(
                    title = KaPlayText.string("Chess"),
                    detail = KaPlayText.string("1v1, tournaments and five bots. Online moves and bot results go on Kaspa."),
                    onClick = { navController.navigate(KaPlayRoutes.HOME) },
                ) {
                    Icon(
                        chessTabIconPainter(),
                        contentDescription = null, tint = Color.White,
                        modifier = chessTabIconModifier(30.dp),
                    )
                }
            }
        }
    }
}

/**
 * The Leaderboard tab (iOS `.leaderboard`, `ChessLeaderboardTabView`): three rows, each opening its
 * own page. 1v1 and Tournaments are a board for this month or all time
 * ([ChessLeaderboardPageScreen]); Bots is a board per bot ([ChessBotLeaderboardsScreen]).
 */
@Composable
fun ChessLeaderboardScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    KaPlayText.init(androidx.compose.ui.platform.LocalContext.current)
    KaPlayBackupBanner(walletViewModel, navController) {
        Scaffold(
            containerColor = LocalAppColors.current.background,
            contentWindowInsets = WindowInsets(0),
            topBar = { ChessHomeBar(onBack = null, title = KaPlayText.string("Leaderboard"), showsBackupBanner = true) },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChessLeaderboardLink(KaPlayText.string("1v1"), Icons.Default.People) {
                    navController.navigate(KaPlayRoutes.leaderboard(ChessLobbyMode.DUEL))
                }
                ChessLeaderboardLink(KaPlayText.string("Tournaments"), Icons.Default.EmojiEvents) {
                    navController.navigate(KaPlayRoutes.leaderboard(ChessLobbyMode.TOURNAMENT))
                }
                ChessLeaderboardLink(KaPlayText.string("Bots"), Icons.Default.Memory) {
                    navController.navigate(KaPlayRoutes.BOT_BOARDS)
                }
            }
        }
    }
}

/** A tab's first screen shows KaChat's backup strip above its title (iOS `fixedScreenTitle(_:
 *  showsBackupBanner: true)` → `AccountBackupBanner`). */
@Composable
private fun KaPlayBackupBanner(
    walletViewModel: WalletViewModel,
    navController: NavController,
    content: @Composable () -> Unit,
) {
    val banner: @Composable () -> Unit = {
        AccountBackupBanner(walletViewModel) { navController.navigate("account_backup") }
    }
    CompositionLocalProvider(LocalBackupBanner provides banner) { content() }
}

/** Leaderboard > 1v1 or Tournaments: that board, on a page of its own. */
@Composable
fun ChessLeaderboardPageScreen(mode: ChessLobbyMode, navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    // Reads the arena while the board is up, the same as the Play screens do.
    HoldArena(vm.service)
    Scaffold(
        containerColor = LocalAppColors.current.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = { navController.popBackStack() }, showConnectionDot = false, title = KaPlayText.string(mode.label)) },
    ) { padding ->
        // Past the 20 s throttle, and the spinner stays until the boards are in (iOS 32e6392).
        ChessRefreshBox(onRefresh = { vm.service.refreshIndexerLeaderboardsNow() }, modifier = Modifier.padding(padding)) {
            ChessLeaderboardRows(mode, navController)
        }
    }
}

/**
 * A player's card, opened by tapping a player on a leaderboard: who they are (avatar, name,
 * address) and their record here, this month and all time. It saves nothing for the address
 * (iOS ChessPlayerView, a sheet with Done).
 */
@Composable
fun ChessPlayerScreen(address: String, onDone: () -> Unit) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    val colors = LocalAppColors.current
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val month by service.board(ChessLeaderboardPeriod.MONTH).collectAsState()
    val allTime by service.board(ChessLeaderboardPeriod.ALL_TIME).collectAsState()
    val explorer by vm.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val haptic = com.kachat.app.util.rememberHaptics()
    val name = chessName(address, contacts, knsNames)

    fun row(board: List<com.kachat.app.util.ChessLeaderboardRow>) =
        board.firstOrNull { it.address.equals(address, ignoreCase = true) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            SheetNavBar(
                title = KaPlayText.string("Player"),
                modifier = Modifier.statusBarsPadding(),
                trailing = { SheetBarButton(stringResource(R.string.done), onClick = onDone) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChessAvatar(address, contacts, size = 88)
                Text(
                    name,
                    color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SettingsFormSection(title = KaPlayText.string("Address")) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                            haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                            copied = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        address,
                        color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp),
                    )
                }
                SettingsDivider()
                SettingsActionItem(KaPlayText.string("View in Explorer"), Icons.Default.Explore, KaspaTeal) {
                    uriHandler.openUri(explorer.addressUrl(address))
                }
            }
            for ((title, board) in listOf(KaPlayText.string("This month") to month, KaPlayText.string("All time") to allTime)) {
                val record = row(board)
                SettingsFormSection(title = title) {
                    ChessRecordLine(KaPlayText.string("1v1 games"), record?.wins ?: 0, record?.losses ?: 0)
                    SettingsDivider()
                    ChessRecordLine(KaPlayText.string("Tournaments"), record?.tournamentsWon ?: 0, record?.tournamentsLost ?: 0)
                }
            }
        }
    }
}

@Composable
private fun ChessRecordLine(title: String, wins: Int, losses: Int) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(KaPlayText.format("%d W", wins), color = colors.success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text(KaPlayText.format("%d L", losses), color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
