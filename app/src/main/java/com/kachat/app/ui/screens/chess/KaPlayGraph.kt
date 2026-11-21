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

/**
 * The pages the KaPlay and Leaderboard tabs push (iOS 148a5331 phase 4: GamesView's and
 * ChessLeaderboardTabView's NavigationStacks). The two tabs themselves are [GamesScreen] and
 * [ChessLeaderboardScreen], placed by the dock / Kaspa Hub like any other tab.
 */
object KaPlayRoutes {
    /** KaPlay > Chess: choose your game ([ChessHomeScreen]). */
    const val HOME = "chess_home"
    const val MODE = "chess_mode/{mode}"
    const val TOURNAMENT = "chess_tournament/{tournamentId}"
    const val TOURNAMENT_GAME = "chess_tournament_game/{tournamentId}/{gameId}"
    /** Both bot routes can carry a challenge from a post ([ChessBotChallenge.routeArg]). */
    const val BOTS = "chess_bots?challenge={challenge}"
    const val BOT = "chess_bot/{level}?challenge={challenge}"
    const val BOT_GAME = "chess_bot_game/{level}/{side}"
    const val LEADERBOARD = "chess_leaderboard/{mode}"
    const val BOT_BOARDS = "chess_bot_boards"
    const val BOT_BOARD = "chess_bot_board/{level}"
    const val PLAYER = "chess_player/{address}"

    fun leaderboard(mode: ChessLobbyMode) = "chess_leaderboard/${mode.name}"

    /** The game boards. While the player is locked into one ([ChessGameDock.lockedIn]) the dock
     *  hides, as iOS hides the tab bar then; someone watching, or a game that is over, keeps it. */
    val GAME_ROUTES: Set<String> = setOf(TOURNAMENT_GAME, BOT_GAME)

    /** A player's card: iOS presents it as a sheet over the tab bar, so it has no dock. */
    val FULL_SCREEN_ROUTES: Set<String> = setOf(PLAYER)

    /** Pushed pages that keep the dock (everything but the boards and the player's card). */
    val DOCKED_ROUTES: Set<String> = setOf(
        HOME, MODE, TOURNAMENT, BOTS, BOT, LEADERBOARD, BOT_BOARDS, BOT_BOARD,
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
 * Registers every page KaPlay and the Leaderboard tab push. [dockInset] is the dock's height: the
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
    composable(KaPlayRoutes.LEADERBOARD) { entry ->
        val mode = runCatching {
            ChessLobbyMode.valueOf(entry.arguments?.getString("mode").orEmpty())
        }.getOrDefault(ChessLobbyMode.DUEL)
        CompositionLocalProvider(LocalDockInset provides dockInset) { ChessLeaderboardPageScreen(mode, navController) }
    }
    composable(KaPlayRoutes.BOT_BOARDS) {
        CompositionLocalProvider(LocalDockInset provides dockInset) { ChessBotLeaderboardsScreen(navController) }
    }
    composable(KaPlayRoutes.BOT_BOARD) { entry ->
        CompositionLocalProvider(LocalDockInset provides dockInset) {
            ChessBotLeaderboardScreen(entry.arguments?.getString("level")?.toIntOrNull() ?: 1, navController)
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
}
