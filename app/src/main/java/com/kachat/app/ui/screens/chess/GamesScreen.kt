package com.kachat.app.ui.screens.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kachat.app.services.arcade.ArcadeLinkRouter
import com.kachat.app.services.chess.ChessBotLinkRouter
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.arcade.ArcadeGameIcon
import com.kachat.app.ui.screens.arcade.ArcadeRoutes
import com.kachat.app.ui.screens.arcade.ArcadeText
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ArcadeGame
import com.kachat.app.util.KaPlayText
import com.kachat.app.viewmodels.WalletViewModel

/**
 * The KaPlay tab (iOS `.games`, KaPlay's Play tab; iOS 518fdc8c `GamesView`): a card per game.
 * Chess, then the arcade (Snake and 2048, since 2026-11-23, ARCADE.md); a new game is one more
 * card here. Its title is "KaPlay", written as is in every language. No connection dot and no
 * backup strip (iOS 518fdc8c: the dot is on Settings only, the strip on Profile only).
 *
 * A bot challenge from a post (`ChessBotLinks.open`, [ChessBotLinkRouter]) is for chess: this
 * screen opens Chess, and [ChessHomeScreen] takes the challenge from the router. A score challenge
 * ([ArcadeLinkRouter]) opens that game's home with the score to beat. A challenge never pops a
 * game: while a bot game or an online game is on, or an arcade round is up, it waits in its router
 * until that is over (audit 2026-11-24 #11; pages over this screen are
 * [com.kachat.app.ui.screens.arcade.ArcadeChallengeRouting]'s).
 */
@Composable
fun GamesScreen(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // The chess sentences are looked up by their English (normally set up at launch).
    KaPlayText.init(context)
    val chessChallenge by ChessBotLinkRouter.challenge.collectAsState()
    val arcadeChallenge by ArcadeLinkRouter.challenge.collectAsState()
    val roundOpen by ArcadeLinkRouter.arcadeRoundOpen.collectAsState()
    val botGame by ArcadeLinkRouter.botGameToken.collectAsState()
    // This screen's own entry: a challenge is followed only from here, never from under a page
    // already open over it unless that page may be closed for it.
    val ownEntry = androidx.compose.ui.platform.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry
    val top by navController.currentBackStackEntryAsState()
    // Kept alive but hidden in Kaspa Hub (iOS 428a797b): not on screen, so it waits until it shows.
    val hubHidden = com.kachat.app.ui.screens.LocalHubSectionHidden.current

    // A bot challenge opens Chess from here; an arcade round on screen keeps it waiting (audit
    // 2026-11-24 #11). With a page over this screen, [ArcadeChallengeRouting] closes an arcade
    // game's home for it, and ChessHomeScreen takes it when Chess is open.
    LaunchedEffect(chessChallenge, top, hubHidden, roundOpen) {
        if (chessChallenge != null && !hubHidden && !roundOpen && ownEntry != null && top?.id == ownEntry.id) {
            navController.navigate(KaPlayRoutes.HOME)
        }
    }

    // A score challenge from a post: that game's home, with the score to beat - from here, unless
    // a bot game or an arcade round holds it. A page over this screen is
    // [ArcadeChallengeRouting]'s to close.
    LaunchedEffect(arcadeChallenge, top, hubHidden, roundOpen, botGame) {
        val challenge = arcadeChallenge ?: return@LaunchedEffect
        if (hubHidden || ownEntry == null || top?.id != ownEntry.id || roundOpen || botGame != null) return@LaunchedEffect
        ArcadeLinkRouter.challenge.value = null
        navController.navigate(ArcadeRoutes.home(challenge.game, challenge))
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ChessHomeBar(onBack = null, title = "KaPlay") },
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
            for (game in ArcadeGame.entries) {
                ChessGameCard(
                    title = ArcadeText.title(game),
                    detail = ArcadeText.detail(game),
                    onClick = { navController.navigate(ArcadeRoutes.home(game)) },
                ) {
                    ArcadeGameIcon(game, 26.dp, Color.White)
                }
            }
        }
    }
}
