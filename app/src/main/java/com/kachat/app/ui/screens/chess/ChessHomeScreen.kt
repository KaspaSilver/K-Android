package com.kachat.app.ui.screens.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kachat.app.services.chess.ChessBotLeaderboardService
import com.kachat.app.services.chess.ChessBotLinkRouter
import com.kachat.app.services.chess.ChessBotProgress
import com.kachat.app.ui.components.CycleCapsuleButton
import com.kachat.app.ui.components.CycleFade
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.ActionSheetRow
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessBoardChoice
import com.kachat.app.util.ChessBot
import com.kachat.app.util.ChessHomeBoards
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.ChessText
import com.kachat.app.util.ChessTournament
import com.kachat.app.util.ChessTournamentEngine
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaPlayText
import com.kachat.app.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// KaPlay > Chess (iOS 518fdc8c ChessHomeView, owner's call 2026-11-24): laid out as the arcade's
// game homes - the Play card, whose Play opens a half sheet (1v1, Tournament, Bots), then the
// leaderboard, whose cycle button steps through every board (1v1, Tournaments, each bot; each this
// month and all time). The separate leaderboard screens are gone (audit 2026-11-24 #41).

@HiltViewModel
class ChessBotBoardsViewModel @Inject constructor(
    val service: ChessBotLeaderboardService,
    val progress: ChessBotProgress,
) : ViewModel()

/** Pull to refresh over a screen, as iOS's `.refreshable`; the boards fill in when they land. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChessRefreshBox(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
        // The spinner stays for as long as [onRefresh] runs (iOS `.refreshable` awaiting).
        LaunchedEffect(Unit) {
            try {
                onRefresh()
            } finally {
                state.endRefresh()
            }
        }
    }
    Box(modifier.fillMaxSize().clipToBounds().nestedScroll(state.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** What Play's half sheet picked. */
private enum class ChessPlayChoice { DUEL, TOURNAMENT, BOTS }

/**
 * KaPlay > Chess. If the player is already waiting or playing somewhere, that is offered first.
 * A bot challenge from a post is taken here, only while this screen is on top, and never pops a
 * board or a waiting room (iOS `takeChallenge`).
 */
@Composable
fun ChessHomeScreen(navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val bots: ChessBotBoardsViewModel = hiltViewModel()
    val service = vm.service
    val botBoards = bots.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    // Worked out by the service when the rooms or the time change it, never on every tick.
    val mine = service.myActiveTournament.collectAsState().value

    fun open(mode: ChessLobbyMode) = navController.navigate("chess_mode/${mode.name}")

    val challenge by ChessBotLinkRouter.challenge.collectAsState()
    val busyOnline by service.isBusyOnline.collectAsState()
    val ownEntry = androidx.compose.ui.platform.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry
    val top by navController.currentBackStackEntryAsState()
    val isVisible = ownEntry != null && top?.id == ownEntry.id
    LaunchedEffect(challenge, busyOnline, isVisible) {
        val taken = challenge ?: return@LaunchedEffect
        if (!isVisible || busyOnline) return@LaunchedEffect
        ChessBotLinkRouter.challenge.value = null
        // A bot still locked: the ladder, which says who to beat first.
        navController.navigate(
            if (vm.botProgress.isUnlocked(taken.bot)) "chess_bot/${taken.bot.level}?challenge=${taken.routeArg()}"
            else "chess_bots?challenge=${taken.routeArg()}"
        )
    }

    var boardIndex by rememberSaveable { mutableIntStateOf(0) }
    val board = ChessHomeBoards.choices.getOrElse(boardIndex) { ChessHomeBoards.choices[0] }
    // On appear and on every board switch (iOS onAppear / onChange(of: board)).
    LaunchedEffect(board) {
        service.refreshIndexerLeaderboards()
        botBoards.refresh()
    }
    var showPlaySheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        // iOS: an inline "Chess" and the back button, nothing else in the bar.
        topBar = {
            ChessHomeBar(
                onBack = { navController.popBackStack() },
                showConnectionDot = false,
                centerTitle = ChessText.string("Chess"),
                showsBalance = false,
            )
        },
    ) { padding ->
        ChessRefreshBox(
            onRefresh = {
                botBoards.refresh(force = true)
                service.refreshIndexerLeaderboardsNow()
            },
            modifier = Modifier.padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .padding(bottom = LocalDockInset.current),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (mine != null) {
                    ChessWhereYouAreRow(mine) { open(if (mine.isDuel) ChessLobbyMode.DUEL else ChessLobbyMode.TOURNAMENT) }
                }
                ChessPlayCard(vm) { showPlaySheet = true }
                ChessHomeLeaderboard(
                    vm = vm,
                    bots = bots,
                    board = board,
                    onNext = { next -> boardIndex = ChessHomeBoards.choices.indexOf(next).coerceAtLeast(0) },
                    onPlayer = { navController.navigate("chess_player/$it") },
                )
            }
        }
    }

    if (showPlaySheet) {
        ChessPlaySheet(onDismiss = { showPlaySheet = false }) { choice ->
            showPlaySheet = false
            when (choice) {
                ChessPlayChoice.DUEL -> open(ChessLobbyMode.DUEL)
                ChessPlayChoice.TOURNAMENT -> open(ChessLobbyMode.TOURNAMENT)
                ChessPlayChoice.BOTS -> navController.navigate("chess_bots")
            }
        }
    }
}

/** "You're waiting in Public 1v1 #4 · Tap to go back to it". */
@Composable
private fun ChessWhereYouAreRow(mine: ChessTournament, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(KaspaTeal.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS: hourglass / play.fill in title3, in a 32-wide slot, 12 from the text.
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (mine.status == ChessTournament.Status.OPEN) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow,
                contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                ChessText.whereYouAre(mine),
                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp,
            )
            Text(ChessText.string("Tap to go back to it"), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * iOS's playCard: the rook and knight in white on an 84 accent tile (radius 22), the line about
 * the games, your online record, the Play capsule (52 tall, black on the accent), the note.
 */
@Composable
private fun ChessPlayCard(vm: ChessTournamentViewModel, onPlay: () -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val allTime by vm.service.board(ChessLeaderboardPeriod.ALL_TIME).collectAsState()
    val me = vm.service.myAddress
    // Your games won and lost online, all time (1v1s and tournament games alike).
    val record = me?.let { address -> allTime.firstOrNull { it.address.equals(address, ignoreCase = true) } }
        ?.takeIf { it.wins + it.losses > 0 }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(KaspaTeal),
            contentAlignment = Alignment.Center,
        ) {
            // White, as on KaPlay's Chess card: tinted teal it vanished on the teal tile.
            Icon(chessTabIconPainter(), contentDescription = null, tint = Color.White, modifier = chessTabIconModifier(40.dp))
        }
        Text(
            ChessText.string("1v1, tournaments and five bots. Online moves and bot results go on Kaspa."),
            color = colors.textSecondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        if (record != null) {
            val number = java.text.NumberFormat.getIntegerInstance(KaPlayText.locale)
            Text(
                ChessText.format("Your record: %s won, %s lost", number.format(record.wins), number.format(record.losses)),
                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(KaspaTeal)
                .clickable {
                    haptic(IosHaptic.IMPACT_MEDIUM)
                    onPlay()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(ChessText.string("Play"), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Text(
            ChessText.string("Online, five minutes a side and every move is a Kaspa transaction. Bot games stamp their result on Kaspa when they end."),
            color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
        )
    }
}

/** Play's half sheet: which kind of game (iOS playSheet, ActionSheetRows). */
@Composable
private fun ChessPlaySheet(onDismiss: () -> Unit, onPick: (ChessPlayChoice) -> Unit) {
    ActionSheetContainer(title = ChessText.string("Choose your game"), subtitle = null, onDismiss = onDismiss) {
        ActionSheetRow(
            icon = Icons.Default.People,
            title = ChessText.string("1v1"),
            subtitle = ChessText.string("Play the next person who joins, or a friend by code."),
        ) { onPick(ChessPlayChoice.DUEL) }
        ActionSheetRow(
            icon = Icons.Default.EmojiEvents,
            title = ChessText.string("Tournament"),
            subtitle = ChessText.string("Eight players, single elimination."),
        ) { onPick(ChessPlayChoice.TOURNAMENT) }
        ActionSheetRow(
            icon = Icons.Default.Memory,
            title = ChessText.string("Bots"),
            subtitle = ChessText.string("Five bots, level 1 to 5. Beat one to unlock the next."),
        ) { onPick(ChessPlayChoice.BOTS) }
    }
}

/** One row of a board: who, and the board's own figures. */
private class ChessHomeBoardRow(val address: String, val trailing: @Composable () -> Unit)

/**
 * The leaderboard: "Leaderboard", the cycle button through all 14 boards ("3/14"), then the top
 * ten of the board picked, each opening that player's card.
 */
@Composable
private fun ChessHomeLeaderboard(
    vm: ChessTournamentViewModel,
    bots: ChessBotBoardsViewModel,
    board: ChessBoardChoice,
    onNext: (ChessBoardChoice) -> Unit,
    onPlayer: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val service = vm.service
    val contacts by vm.contacts.collectAsState()
    val onlineBoard by service.board(board.period).collectAsState()
    val botBoards by bots.service.boards.collectAsState()
    val stampedWins by bots.progress.stampedWins.collectAsState()
    val arenaDown by service.arenaServerDown.collectAsState()
    val botsUnreachable by bots.service.serverUnreachable.collectAsState()
    val me = service.myAddress
    val serverDown = if (board.kind is ChessBoardChoice.Kind.Bot) botsUnreachable else arenaDown
    val digits = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

    // Sorted when the board or its source changes, not on every tick of the service's clock.
    val rows: List<ChessHomeBoardRow> = remember(board, onlineBoard, botBoards, stampedWins) {
        when (val kind = board.kind) {
            ChessBoardChoice.Kind.Duel -> ChessTournamentEngine.duelLeaderboard(onlineBoard).map { row ->
                ChessHomeBoardRow(row.address) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(ChessText.format("%d W", row.wins), color = LocalAppColors.current.success, style = digits, maxLines = 1)
                        Text(ChessText.format("%d L", row.losses), color = LocalAppColors.current.danger, style = digits, maxLines = 1)
                    }
                }
            }
            ChessBoardChoice.Kind.Tournament -> ChessTournamentEngine.tournamentLeaderboard(onlineBoard).map { row ->
                ChessHomeBoardRow(row.address) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Orange, not yellow: yellow can't be read on a light row.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = LocalAppColors.current.warning, modifier = Modifier.size(16.dp))
                            Text("${row.tournamentsWon}", color = LocalAppColors.current.warning, style = digits, maxLines = 1)
                        }
                        Text(ChessText.format("%d L", row.tournamentsLost), color = LocalAppColors.current.danger, style = digits, maxLines = 1)
                    }
                }
            }
            is ChessBoardChoice.Kind.Bot -> {
                val bot = ChessBot.forLevel(kind.level)
                if (bot == null) emptyList() else bots.service.rows(bot, board.period, botBoards, stampedWins).map { row ->
                    ChessHomeBoardRow(row.address) {
                        Text(ChessText.format("%d moves", row.moves), color = LocalAppColors.current.success, style = digits, maxLines = 1)
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ChessText.string("Leaderboard"), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        CycleCapsuleButton(
            options = ChessHomeBoards.choices,
            selection = board,
            onSelect = onNext,
            title = { ChessHomeBoards.title(it) },
            hint = ChessText.string("Switches to the next leaderboard"),
        )
        CycleFade(target = board to rows) { (shownBoard, shownRows) ->
            if (shownRows.isEmpty()) {
                Text(
                    if (serverDown) ChessText.string("Chess server unreachable. Showing what this phone has.")
                    else ChessHomeBoards.emptyText(shownBoard),
                    color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                )
            } else {
                val shown = shownRows.take(10)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
                    shown.forEachIndexed { index, row ->
                        val isMe = me != null && row.address.equals(me, ignoreCase = true)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPlayer(row.address) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                color = if (index < 3) KaspaTeal else colors.textSecondary,
                                style = digits.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(28.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            ChessAvatar(row.address, contacts, size = 32)
                            Spacer(Modifier.width(12.dp))
                            // The numbers keep their full width; a long name gives way, cut in
                            // the middle as iOS's .truncationMode(.middle).
                            Text(
                                middleEllipsis(chessName(row.address, contacts), 20),
                                color = colors.textPrimary, fontSize = 15.sp,
                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            row.trailing()
                        }
                        if (index < shown.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = colors.divider, modifier = Modifier.padding(start = 98.dp))
                        }
                    }
                }
            }
        }
    }
}
