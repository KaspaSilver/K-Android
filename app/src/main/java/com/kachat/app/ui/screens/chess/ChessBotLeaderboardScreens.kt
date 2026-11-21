package com.kachat.app.ui.screens.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.services.chess.ChessBotLeaderboardService
import com.kachat.app.services.chess.ChessBotProgress
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessBot
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.kachat.app.ui.screens.LocalDockInset

// The Leaderboard tab's rows and the bots' boards (iOS ChessBotLeaderboardViews.swift, ddec987).

@HiltViewModel
class ChessBotBoardsViewModel @Inject constructor(
    val service: ChessBotLeaderboardService,
    val progress: ChessBotProgress,
) : ViewModel()

/**
 * A row on the Leaderboard tab: the board's icon in a circle, its name and a chevron, opening that
 * board's own page. The same card as Backup Hygiene's and an address's Domains rows
 * (iOS 32e6392 `ChessLeaderboardLink`, in place of the squares).
 */
@Composable
fun ChessLeaderboardLink(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    // headline, shrinking to 0.8 of it before it would cut a word (minimumScaleFactor).
    val maxSize = 17.sp
    val minSize = maxSize * 0.8f
    var fontSize by remember(title) { mutableStateOf(maxSize) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .clickable {
                haptic(IosHaptic.SELECTION)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = fontSize,
            maxLines = 1, softWrap = false,
            modifier = Modifier.weight(1f),
            onTextLayout = { result ->
                if (result.hasVisualOverflow && fontSize.value > minSize.value) {
                    val next = fontSize * 0.94f
                    fontSize = if (next.value < minSize.value) minSize else next
                }
            },
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Pull to refresh over a board, as iOS's `.refreshable` list; the board fills in when it lands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChessRefreshBox(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
        // The spinner stays for as long as [onRefresh] runs (iOS 32e6392 `.refreshable` awaiting).
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

/** Leaderboard > Bots: the bots that have been played, each opening its own board. */
@Composable
fun ChessBotLeaderboardsScreen(navController: NavController) {
    val vm: ChessBotBoardsViewModel = hiltViewModel()
    val colors = LocalAppColors.current
    val boards by vm.service.boards.collectAsState()
    val records by vm.progress.records.collectAsState()
    val wins by vm.progress.stampedWins.collectAsState()
    val played = ChessBot.all.filter { vm.service.hasBeenPlayed(it, boards, records) }
    val serverUnreachable by vm.service.serverUnreachable.collectAsState()
    LaunchedEffect(Unit) { vm.service.refresh() }
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = { navController.popBackStack() }, showConnectionDot = false, title = com.kachat.app.util.ChessText.string("Bots")) },
    ) { padding ->
        ChessRefreshBox(onRefresh = { vm.service.refresh(force = true); kotlinx.coroutines.delay(600) }, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp + LocalDockInset.current)) {
                if (serverUnreachable) {
                    item {
                        ChessServerDownCard("Chess server unreachable. Showing what this phone has.")
                        Spacer(Modifier.height(16.dp))
                    }
                }
                item {
                    ChessCard {
                        if (played.isEmpty()) {
                            Text(
                                com.kachat.app.util.ChessText.string("No bot has been played yet."),
                                color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp),
                            )
                        }
                        played.forEachIndexed { index, bot ->
                            if (index > 0) ChessRowDivider(start = 72.dp)
                            val best = vm.service.rows(bot, ChessLeaderboardPeriod.ALL_TIME, boards, wins).firstOrNull()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate("chess_bot_board/${bot.level}") }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChessBotAvatar(bot, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(bot.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(best?.let { com.kachat.app.util.ChessText.format("Best: %d moves", it.moves) } ?: com.kachat.app.util.ChessText.string("Not beaten yet"), color = colors.textSecondary, fontSize = 12.sp)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Text(
                        com.kachat.app.util.ChessText.string("Ranked by the fewest moves to win."),
                        color = colors.textSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** One bot's board: each player's fewest moves to beat it, this month or all time. */
@Composable
fun ChessBotLeaderboardScreen(level: Int, navController: NavController) {
    val bot = ChessBot.forLevel(level) ?: return
    val vm: ChessBotBoardsViewModel = hiltViewModel()
    val names: ChessTournamentViewModel = hiltViewModel()
    val colors = LocalAppColors.current
    val boards by vm.service.boards.collectAsState()
    val wins by vm.progress.stampedWins.collectAsState()
    val contacts by names.contacts.collectAsState()
    val knsNames by names.service.knsNames.collectAsState()
    var period by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ChessLeaderboardPeriod.MONTH) }
    val rows = vm.service.rows(bot, period, boards, wins)
    val me = vm.progress.address
    val serverUnreachable by vm.service.serverUnreachable.collectAsState()
    LaunchedEffect(Unit) { vm.service.refresh() }
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = { navController.popBackStack() }, showConnectionDot = false, title = bot.name) },
    ) { padding ->
        ChessRefreshBox(onRefresh = { vm.service.refresh(force = true); kotlinx.coroutines.delay(600) }, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 44.dp, bottom = 8.dp + LocalDockInset.current)) {
                if (serverUnreachable) {
                    item {
                        ChessServerDownCard("Chess server unreachable. Showing what this phone has.")
                        Spacer(Modifier.height(16.dp))
                    }
                }
                item {
                    ChessSegmentedPicker(
                        titles = listOf(
                            stringResource(R.string.kaplay_leaderboard_this_month),
                            stringResource(R.string.kaplay_leaderboard_all_time),
                        ),
                        selected = if (period == ChessLeaderboardPeriod.MONTH) 0 else 1,
                        onSelect = { period = if (it == 0) ChessLeaderboardPeriod.MONTH else ChessLeaderboardPeriod.ALL_TIME },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    if (period == ChessLeaderboardPeriod.MONTH) {
                        Text(
                            stringResource(R.string.kaplay_leaderboard_resets_monthly),
                            color = colors.textSecondary, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp).padding(top = 6.dp, bottom = 10.dp),
                        )
                    }
                    ChessSectionHeader(com.kachat.app.util.ChessText.format("Fewest moves to beat %s", bot.name))
                }
                if (rows.isEmpty()) {
                    item {
                        ChessCard {
                            Text(
                                if (period == ChessLeaderboardPeriod.MONTH) com.kachat.app.util.ChessText.format("Nobody has beaten %s this month yet.", bot.name)
                                else com.kachat.app.util.ChessText.format("Nobody has beaten %s yet.", bot.name),
                                color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                // One card, 26 round, its rows split by hairlines, your own row tinted, as on the
                // 1v1 board (ChessLeaderboardRows).
                itemsIndexed(rows, key = { _, row -> row.address }) { index, row ->
                    val corner = 26.dp
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) corner else 0.dp, topEnd = if (index == 0) corner else 0.dp,
                        bottomStart = if (index == rows.lastIndex) corner else 0.dp, bottomEnd = if (index == rows.lastIndex) corner else 0.dp,
                    )
                    val isMe = row.address == me
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (isMe) KaspaTeal.copy(alpha = 0.12f).compositeOver(colors.surface) else colors.surface),
                    ) {
                        if (index > 0) ChessRowDivider(start = 34.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("chess_player/${row.address}") }
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val digits = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
                            Text("${index + 1}", color = colors.textSecondary, style = digits, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
                            Spacer(Modifier.width(12.dp))
                            ChessAvatar(row.address, contacts)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                middleEllipsis(chessName(row.address, contacts, knsNames), 20),
                                color = colors.textPrimary, fontSize = 15.sp,
                                fontWeight = if (isMe) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(com.kachat.app.util.ChessText.format("%d moves", row.moves), color = colors.success, style = digits, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
