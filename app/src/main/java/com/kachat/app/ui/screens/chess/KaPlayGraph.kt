package com.kachat.app.ui.screens.chess

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kachat.app.services.chess.ChessBotChallenge
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.arcade.arcadeGraph

/**
 * The pages the KaPlay tab pushes (iOS 148a5331 phase 4: GamesView's NavigationStack). The tab
 * itself is [GamesScreen], placed by the dock / Kaspa Hub like any other tab. The leaderboards
 * are on Chess's own screen ([ChessHomeScreen]'s cycle button; iOS 518fdc8c - the separate
 * leaderboard pages were deleted, audit 2026-11-24 #41).
 */
object KaPlayRoutes {
    /** KaPlay > Chess: Play and the leaderboards ([ChessHomeScreen]). */
    const val HOME = "chess_home"
    const val MODE = "chess_mode/{mode}"
    const val TOURNAMENT = "chess_tournament/{tournamentId}"
    const val TOURNAMENT_GAME = "chess_tournament_game/{tournamentId}/{gameId}"
    /** Both bot routes can carry a challenge from a post ([ChessBotChallenge.routeArg]). */
    const val BOTS = "chess_bots?challenge={challenge}"
    const val BOT = "chess_bot/{level}?challenge={challenge}"
    const val BOT_GAME = "chess_bot_game/{level}/{side}"
    const val PLAYER = "chess_player/{address}"

    /** The game boards. While the player is locked into one ([ChessGameDock.lockedIn]) the dock
     *  hides, as iOS hides the tab bar then; someone watching, or a game that is over, keeps it. */
    val GAME_ROUTES: Set<String> = setOf(TOURNAMENT_GAME, BOT_GAME)

    /** A player's card: iOS presents it as a sheet over the tab bar, so it has no dock. */
    val FULL_SCREEN_ROUTES: Set<String> = setOf(PLAYER)

    /** Pushed pages that keep the dock (everything but the boards and the player's card). */
    val DOCKED_ROUTES: Set<String> = setOf(
        HOME, MODE, TOURNAMENT, BOTS, BOT,
        // the arcade games' homes (Snake, 2048)
        com.kachat.app.ui.screens.arcade.ArcadeRoutes.HOME,
    )

    /**
     * The KaPlay tab's own route, set by whoever registers [GamesScreen]: a game's result Done
     * goes back to the 1v1 / Tournaments screen, and a game opened with nothing behind it falls
     * back to the KaPlay tab (iOS popToRoot), then plain back.
     */
    @Volatile
    var gamesTabRoute: String? = null
}

/** Whether the dock shows over [route] (the current destination's route): every KaPlay page but
 *  a board the player is locked into and the player's card. */
fun kaPlayShowsDock(route: String?): Boolean = when (route) {
    null -> true
    in KaPlayRoutes.GAME_ROUTES -> !ChessGameDock.lockedIn
    in KaPlayRoutes.FULL_SCREEN_ROUTES -> false
    else -> true
}

/**
 * Registers every page KaPlay pushes. [dockInset] is the dock's height: the
 * pages run on under it, as iOS's content runs on under its tab bar.
 */
fun NavGraphBuilder.kaPlayGraph(navController: NavController, dockInset: Dp) {
    composable(KaPlayRoutes.HOME) {
        CompositionLocalProvider(LocalDockInset provides dockInset) { ChessHomeScreen(navController) }
    }
    composable(KaPlayRoutes.MODE) { entry ->
        val mode = runCatching {
            ChessLobbyMode.valueOf(entry.arguments?.getString("mode").orEmpty())
        }.getOrDefault(ChessLobbyMode.DUEL)
        CompositionLocalProvider(LocalDockInset provides dockInset) {
            ChessTournamentsScreen(mode, navController, onBack = { navController.popBackStack() })
        }
    }
    composable(KaPlayRoutes.TOURNAMENT) { entry ->
        CompositionLocalProvider(LocalDockInset provides dockInset) {
            ChessTournamentScreen(entry.arguments?.getString("tournamentId").orEmpty(), navController)
        }
    }
    composable(KaPlayRoutes.TOURNAMENT_GAME) { entry ->
        CompositionLocalProvider(LocalDockInset provides if (ChessGameDock.lockedIn) 0.dp else dockInset) {
            ChessTournamentGameScreen(
                entry.arguments?.getString("tournamentId").orEmpty(),
                entry.arguments?.getString("gameId").orEmpty(),
                navController,
            )
        }
    }
    val challengeArgument = listOf(
        navArgument("challenge") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
    )
    composable(KaPlayRoutes.BOTS, arguments = challengeArgument) { entry ->
        CompositionLocalProvider(LocalDockInset provides dockInset) {
            ChessBotsScreen(navController, ChessBotChallenge.fromRouteArg(entry.arguments?.getString("challenge")))
        }
    }
    composable(KaPlayRoutes.BOT, arguments = challengeArgument) { entry ->
        CompositionLocalProvider(LocalDockInset provides dockInset) {
            ChessBotDetailScreen(
                entry.arguments?.getString("level")?.toIntOrNull() ?: 1,
                navController,
                ChessBotChallenge.fromRouteArg(entry.arguments?.getString("challenge")),
            )
        }
    }
    composable(KaPlayRoutes.BOT_GAME) { entry ->
        CompositionLocalProvider(LocalDockInset provides if (ChessGameDock.lockedIn) 0.dp else dockInset) {
            ChessBotGameScreen(
                entry.arguments?.getString("level")?.toIntOrNull() ?: 1,
                entry.arguments?.getString("side").orEmpty(),
                navController,
            )
        }
    }
    // A tapped player, for their card (iOS ChessPlayerView, a sheet with Done).
    composable(KaPlayRoutes.PLAYER) { entry ->
        ChessPlayerScreen(
            address = entry.arguments?.getString("address").orEmpty(),
            onDone = { navController.popBackStack() },
        )
    }
    // KaPlay > Snake / 2048 (iOS 518fdc8c ArcadeGameHomeView).
    arcadeGraph(navController, dockInset)
}
