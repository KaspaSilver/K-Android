package com.kachat.app.ui.screens.arcade

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.kachat.app.R
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.displayName
import com.kachat.app.services.WalletManager
import com.kachat.app.services.arcade.ArcadeChallenge
import com.kachat.app.services.arcade.ArcadeLinkRouter
import com.kachat.app.services.arcade.ArcadeMessages
import com.kachat.app.services.arcade.ArcadeService
import com.kachat.app.services.arcade.ArcadeStampJob
import com.kachat.app.services.chess.ChessTournamentService
import com.kachat.app.ui.components.CycleCapsuleButton
import com.kachat.app.ui.components.CycleFade
import com.kachat.app.ui.screens.ContactAvatar
import com.kachat.app.ui.screens.IosToastHost
import com.kachat.app.ui.screens.IosToastStyle
import com.kachat.app.ui.screens.IosToasts
import com.kachat.app.ui.screens.KaPostQuickComposer
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.LocalDockInset
import com.kachat.app.ui.screens.ToolbarGlassGroup
import com.kachat.app.ui.screens.ToolbarIconButton
import com.kachat.app.ui.screens.ToolbarTextButton
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ArcadeDirection
import com.kachat.app.util.ArcadeGame
import com.kachat.app.util.ArcadeRandom
import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.Game2048Engine
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaPlayText
import com.kachat.app.util.SnakeEngine
import com.kachat.app.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

// KaPlay's arcade screens (ARCADE.md; iOS 518fdc8c `Views/Games/ArcadeViews.swift`): a game's home
// (Play, your best, the boards), the game itself (Snake, 2048), and the game-over sheet that stamps
// the score on Kaspa and shares it to KaPost.

// MARK: - Route

/** KaPlay > Snake / 2048: a game's home, pushed inside KaPlay with the dock kept (iOS
 *  `navigationDestination` to `ArcadeGameHomeView`). */
object ArcadeRoutes {
    const val HOME = "arcade_home/{game}?challenge={challenge}"

    /** [challenge] rides along as its link and whether its `by` is the post's author. */
    fun home(game: ArcadeGame, challenge: ArcadeChallenge? = null): String {
        val base = "arcade_home/${game.raw}"
        val arg = challenge ?: return base
        return "$base?challenge=" + Uri.encode((if (arg.byIsAuthor) "1|" else "0|") + arg.url)
    }

    /** Every page KaPlay pushes over its tab: Chess's, and the arcade games' homes. */
    val kaPlayPages: Set<String> by lazy { chessRoutes + HOME }

    internal fun challenge(arg: String?): ArcadeChallenge? {
        val raw = arg?.takeIf { it.length > 2 } ?: return null
        val challenge = ArcadeChallenge.parse(raw.substring(2)) ?: return null
        return if (raw.startsWith("1|")) challenge.copy(byIsAuthor = true) else challenge
    }
}

/** Registers the arcade's page in KaPlay's graph ([com.kachat.app.ui.screens.chess.kaPlayGraph]). */
fun NavGraphBuilder.arcadeGraph(navController: NavController, dockInset: Dp) {
    composable(
        ArcadeRoutes.HOME,
        arguments = listOf(
            navArgument("game") { type = NavType.StringType },
            navArgument("challenge") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        val game = ArcadeGame.fromRaw(entry.arguments?.getString("game")) ?: ArcadeGame.SNAKE
        val challenge = ArcadeRoutes.challenge(entry.arguments?.getString("challenge"))
        androidx.compose.runtime.CompositionLocalProvider(LocalDockInset provides dockInset) {
            ArcadeGameHomeScreen(game, challenge, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Takes a challenge past a page open over KaPlay's tab (iOS 518fdc8c `GamesView`
 * `takeArcadeChallenge` / `takeChessChallenge`, audit 2026-11-24 #11), from the app's root, where
 * it is always composed: the tab's own screen ([com.kachat.app.ui.screens.chess.GamesScreen])
 * isn't while a page is over it.
 *
 * - A score challenge closes an arcade game's home or Chess's pages and opens that game's home,
 *   unless an arcade round is up, a bot game is on (its board's token, or its route), or Chess is
 *   open while an online game is on: then it waits in [ArcadeLinkRouter] until that is over.
 * - A bot challenge closes an arcade game's home (not a round on it), so KaPlay's tab opens Chess.
 */
@Composable
fun ArcadeChallengeRouting(navController: NavController) {
    val vm: ArcadeViewModel = hiltViewModel()
    val arcade by ArcadeLinkRouter.challenge.collectAsState()
    val chess by com.kachat.app.services.chess.ChessBotLinkRouter.challenge.collectAsState()
    val roundOpen by ArcadeLinkRouter.arcadeRoundOpen.collectAsState()
    val botGame by ArcadeLinkRouter.botGameToken.collectAsState()
    // only this one value: observing the whole arena would redraw on every move
    val busyOnline by vm.chess.isBusyOnline.collectAsState()
    val top by navController.currentBackStackEntryAsState()
    val route = top?.destination?.route

    LaunchedEffect(chess, route, roundOpen) {
        if (chess != null && !roundOpen && route == ArcadeRoutes.HOME) navController.popBackStack()
    }

    LaunchedEffect(arcade, route, roundOpen, botGame, busyOnline) {
        val challenge = arcade ?: return@LaunchedEffect
        if (roundOpen || botGame != null) return@LaunchedEffect
        when (route) {
            ArcadeRoutes.HOME -> {
                ArcadeLinkRouter.challenge.value = null
                navController.popBackStack()
                navController.navigate(ArcadeRoutes.home(challenge.game, challenge))
            }
            in chessRoutes -> {
                // a bot board holds it until Done or Back; an online game while it's on
                if (route == com.kachat.app.ui.screens.chess.KaPlayRoutes.BOT_GAME || busyOnline) return@LaunchedEffect
                val tab = com.kachat.app.ui.screens.chess.KaPlayRoutes.gamesTabRoute ?: return@LaunchedEffect
                if (!navController.popBackStack(tab, inclusive = false)) return@LaunchedEffect
                ArcadeLinkRouter.challenge.value = null
                navController.navigate(ArcadeRoutes.home(challenge.game, challenge))
            }
        }
    }
}

/** Chess's pages over KaPlay's tab. */
private val chessRoutes: Set<String> by lazy {
    val kaPlay = com.kachat.app.ui.screens.chess.KaPlayRoutes
    (kaPlay.DOCKED_ROUTES - ArcadeRoutes.HOME) + kaPlay.GAME_ROUTES + kaPlay.FULL_SCREEN_ROUTES
}

// MARK: - View model

@HiltViewModel
class ArcadeViewModel @Inject constructor(
    val service: ArcadeService,
    chatRepository: com.kachat.app.repository.ChatRepository,
    walletManager: WalletManager,
    /** For KaPlay's tab: an online chess game holds an arcade challenge back (audit 2026-11-24 #11). */
    val chess: ChessTournamentService,
) : ViewModel() {
    val contacts: StateFlow<Map<String, ContactEntity>> = chatRepository.getContacts()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val address: StateFlow<String?> = walletManager.activeAddressFlow
}

// MARK: - Text

/** The arcade's sentences in the picked language. */
object ArcadeText {
    fun title(context: Context, game: ArcadeGame): String = when (game) {
        ArcadeGame.SNAKE -> context.getString(R.string.arcade_snake)
        // the number itself, in every language
        ArcadeGame.G2048 -> "2048"
    }

    @Composable
    fun title(game: ArcadeGame): String = title(LocalContext.current, game)

    @Composable
    fun detail(game: ArcadeGame): String = when (game) {
        ArcadeGame.SNAKE -> stringResource(R.string.arcade_snake_detail)
        ArcadeGame.G2048 -> stringResource(R.string.arcade_2048_detail)
    }

    /** A score as iOS's `formatted()`: grouped in the picked language ("1,240"). */
    fun number(value: Int): String = java.text.NumberFormat.getIntegerInstance(KaPlayText.locale).format(value)

    /** A service's English sentence ([ArcadeMessages], or a send error's), shown in the picked
     *  language. */
    fun message(context: Context, key: String): String = when (key) {
        ArcadeMessages.OTHER_ACCOUNT -> context.getString(R.string.arcade_other_account)
        ArcadeMessages.NOT_SENT -> context.getString(R.string.arcade_score_not_sent)
        else -> {
            KaPlayText.init(context)
            KaPlayText.string(key)
        }
    }

    /** "Beat 1,240 in Snake", or "Challenge: play Snake" without a score. */
    fun challengeTitle(context: Context, challenge: ArcadeChallenge): String {
        val game = title(context, challenge.game)
        val score = challenge.score ?: return context.getString(R.string.arcade_challenge_play, game)
        return context.getString(R.string.arcade_beat_in, number(score), game)
    }

    /** The post Share to KaPost writes: the boast and the challenge link (the composer shows the
     *  link as its card). Null when no account is signed in. */
    fun shareText(context: Context, game: ArcadeGame, score: Int, gameId: String, address: String?): String? {
        if (address.isNullOrEmpty()) return null
        val link = ArcadeChallenge.url(game, score, address, gameId)
        val boast = context.getString(R.string.arcade_share_boast, number(score), title(context, game))
        return "$boast\n\n$link"
    }
}

/** The app's naming rule: your name for the address, else its .kachat name, else the short
 *  address (iOS `contactsManager.displayName(for:)`). */
private fun arcadeName(address: String, contacts: Map<String, ContactEntity>): String =
    contacts[address]?.displayName ?: com.kachat.app.models.addressDisplayName(address)

// MARK: - The game's mark

/** iOS `scribble.variable`: Material has no snake. */
private val ArcadeSnakeGlyph: ImageVector by lazy {
    ImageVector.Builder("ArcadeSnake", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3.5f, 15.5f)
            curveTo(5.5f, 9.5f, 8.5f, 6.5f, 10.5f, 8.0f)
            curveTo(12.5f, 9.5f, 9.0f, 15.5f, 11.5f, 17.0f)
            curveTo(14.0f, 18.5f, 15.5f, 9.0f, 18.0f, 8.5f)
            curveTo(19.8f, 8.1f, 20.2f, 11.0f, 20.5f, 12.5f)
        }
    }.build()
}

/** A game's mark: Snake's symbol, and 2048 as the number itself (owner, 2026-11-24; iOS
 *  `ArcadeGameIcon`). [size] is the symbol's point size; the number is drawn to fit the same space. */
@Composable
fun ArcadeGameIcon(game: ArcadeGame, size: Dp, tint: Color) {
    when (game) {
        ArcadeGame.G2048 -> {
            val fontSize = with(LocalDensity.current) { (size * 0.62f).toSp() }
            Box(Modifier.width(size * 1.7f), contentAlignment = Alignment.Center) {
                KachatFitText(
                    "2048",
                    color = tint,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    minScale = 0.5f,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // A Material-sized glyph is inset in its box: 1.2 draws it the size of the iOS symbol.
        ArcadeGame.SNAKE -> Icon(ArcadeSnakeGlyph, contentDescription = null, tint = tint, modifier = Modifier.size(size * 1.2f))
    }
}

// MARK: - A game's home

/** The bar of a pushed arcade page: back, and the title inline (iOS `.inline` title mode). */
@Composable
private fun ArcadeBar(title: String, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 44.dp),
    ) {
        ToolbarGlassGroup(Modifier.align(Alignment.CenterStart)) {
            ToolbarIconButton(
                Icons.AutoMirrored.Filled.ArrowBackIos,
                stringResource(R.string.back),
                onBack,
                iconSize = 20.dp,
                tint = colors.textPrimary,
            )
        }
        Text(
            title,
            color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
        )
    }
}

/** Pull to refresh over a page, as iOS's `.refreshable` ScrollView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcadeRefreshBox(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
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

/** A game's home (iOS `ArcadeGameHomeView`): the challenge to beat, Play, your best, the boards. */
@Composable
fun ArcadeGameHomeScreen(
    game: ArcadeGame,
    /** Someone's score to beat, from a challenge card. */
    challenge: ArcadeChallenge?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    KaPlayText.init(context)
    val vm: ArcadeViewModel = hiltViewModel()
    val service = vm.service
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val contacts by vm.contacts.collectAsState()
    val me by vm.address.collectAsState()
    val boards by service.boards.boards.collectAsState()
    val unreachable by service.boards.unreachable.collectAsState()
    val revision by service.progress.revision.collectAsState()
    var period by remember { mutableStateOf(ChessLeaderboardPeriod.MONTH) }
    var playing by remember { mutableStateOf(false) }
    val title = ArcadeText.title(game)

    LaunchedEffect(game) { service.boards.refresh(game) }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { ArcadeBar(title, onBack) },
    ) { padding ->
        ArcadeRefreshBox(onRefresh = { service.boards.refreshNow(game) }, modifier = Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .padding(bottom = LocalDockInset.current),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                challenge?.let { challengeText(context, it, game, contacts) }?.let { banner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.warning.copy(alpha = 0.14f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.warning, modifier = Modifier.size(22.dp))
                        Text(banner, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(KaspaTeal),
                        contentAlignment = Alignment.Center,
                    ) { ArcadeGameIcon(game, 40.dp, Color.White) }
                    Text(ArcadeText.detail(game), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    // read so a new stamp shows at once
                    revision.let { _ -> service.progress.best(game) }?.let { best ->
                        Text(
                            stringResource(R.string.arcade_your_best, ArcadeText.number(best.score)),
                            color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
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
                                playing = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.kpui_play), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        stringResource(R.string.arcade_fee_note),
                        color = colors.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
                    )
                }

                // Leaderboard
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.kaplay_tab_leaderboard),
                            color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        CycleCapsuleButton(
                            options = ChessLeaderboardPeriod.entries,
                            selection = period,
                            onSelect = { period = it },
                            title = { periodTitle(it) },
                            hint = stringResource(R.string.arcade_hint_period),
                        )
                    }
                    CycleFade(period) { shownPeriod ->
                        val rows = revision.let { _ -> service.boards.rows(game, shownPeriod, boards) }
                        if (rows.isEmpty()) {
                            Text(
                                stringResource(
                                    if (service.boards.serverUnreachable(game, unreachable)) R.string.arcade_board_unreachable
                                    else R.string.arcade_board_empty,
                                ),
                                color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            )
                        } else {
                            ArcadeBoard(rows, contacts, me)
                        }
                    }
                }
            }
        }
    }

    if (playing) {
        ArcadePlayDialog(game, service) {
            playing = false
            service.boards.refresh(game, force = true)
        }
    }
}

@Composable
private fun periodTitle(period: ChessLeaderboardPeriod): String = when (period) {
    ChessLeaderboardPeriod.MONTH -> stringResource(R.string.kaplay_leaderboard_this_month)
    ChessLeaderboardPeriod.ALL_TIME -> stringResource(R.string.kaplay_leaderboard_all_time)
}

/** Named only when the link's `by` is the post's author (audit 2026-11-24 #21). */
private fun challengeText(
    context: Context,
    challenge: ArcadeChallenge,
    game: ArcadeGame,
    contacts: Map<String, ContactEntity>,
): String? {
    val score = challenge.score ?: return null
    val title = ArcadeText.title(context, game)
    val who = challenge.confirmedBy?.let { arcadeName(it, contacts) }
    return if (who != null) {
        context.getString(R.string.arcade_challenge_named, who, ArcadeText.number(score), title)
    } else {
        context.getString(R.string.arcade_challenge_someone, ArcadeText.number(score), title)
    }
}

@Composable
private fun ArcadeBoard(
    rows: List<com.kachat.app.services.arcade.ArcadeBoardRow>,
    contacts: Map<String, ContactEntity>,
    me: String?,
) {
    val colors = LocalAppColors.current
    val shown = rows.take(100)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
        shown.forEachIndexed { index, row ->
            key(row.address) {
                val name = arcadeName(row.address, contacts)
                val mine = me != null && row.address.equals(me, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${index + 1}",
                        color = if (index < 3) KaspaTeal else colors.textSecondary,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(28.dp),
                    )
                    ContactAvatar(imageUrl = null, fallbackText = name, size = 32.dp, address = row.address)
                    Text(
                        name,
                        color = colors.textPrimary, fontSize = 15.sp,
                        fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        ArcadeText.number(row.score),
                        color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (index < shown.size - 1) {
                    HorizontalDivider(thickness = 0.5.dp, color = colors.divider, modifier = Modifier.padding(start = 98.dp))
                }
            }
        }
    }
}

// MARK: - Playing

/** iOS's `systemBackground` / `secondarySystemBackground` / `tertiarySystemFill`: the game is on
 *  the plain palette, not the grouped one. */
private object ArcadePalette {
    @Composable
    fun background(): Color = if (LocalAppColors.current.isDark) Color.Black else Color.White

    @Composable
    fun secondary(): Color = if (LocalAppColors.current.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

    @Composable
    fun tertiaryFill(): Color = if (LocalAppColors.current.isDark) Color(0x3D767680) else Color(0x1F767680)
}

/** The full-screen window every game plays in (iOS `fullScreenCover`). */
private val ArcadeDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
    dismissOnClickOutside = false,
)

/**
 * The game full screen, then its game-over sheet (iOS `ArcadePlayView`). Share to KaPost stays in
 * KaPlay (owner, 2026-11-24): KaPost's composer slides up over the game with the post written.
 * Post sends it; the X closes it. Either way the game and its game-over sheet are right where they
 * were.
 */
@Composable
private fun ArcadePlayDialog(game: ArcadeGame, service: ArcadeService, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptic = rememberHaptics()
    var round by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf<ArcadeStampJob?>(null) }
    /** Share to KaPost was tapped: the composer is up, and the game-over sheet comes back when
     *  it closes. */
    var shareJob by remember { mutableStateOf<ArcadeStampJob?>(null) }
    var composing by remember { mutableStateOf<Pair<ArcadeStampJob, String>?>(null) }
    val latestFinished by rememberUpdatedState(finished)

    // A round on screen holds challenge links until it closes (audit 2026-11-24 #11).
    DisposableEffect(Unit) {
        ArcadeLinkRouter.arcadeRoundOpen.value = true
        onDispose { ArcadeLinkRouter.arcadeRoundOpen.value = false }
    }

    fun over(score: Int, seed: Long, moves: String) {
        haptic(IosHaptic.ERROR)
        // a game of nothing isn't worth a fee
        finished = if (score <= 0) ArcadeStampJob.notStamped(game) else service.stamp(game, score, seed, moves)
    }

    Dialog(onDismissRequest = onDismiss, properties = ArcadeDialogProperties) {
        ArcadeFullScreenWindow()
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ArcadePalette.background())
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 44.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    ToolbarTextButton(stringResource(R.string.done), onClick = onDismiss)
                }
                Text(
                    ArcadeText.title(game),
                    color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            key(round) {
                when (game) {
                    ArcadeGame.SNAKE -> SnakePlay(onOver = ::over)
                    ArcadeGame.G2048 -> Game2048Play(onOver = ::over)
                }
            }
        }
        IosToastHost()
        }

        finished?.takeIf { composing == null }?.let { job ->
            ArcadeGameOverSheet(
                job = job,
                service = service,
                onPlayAgain = {
                    finished = null
                    round += 1
                },
                onDone = {
                    finished = null
                    onDismiss()
                },
                onShare = {
                    val text = ArcadeText.shareText(context, job.game, job.score, job.id, job.playerAddress)
                    if (text != null) {
                        shareJob = job
                        finished = null
                        composing = job to text
                    }
                },
            )
        }

        composing?.let { (job, text) ->
            // Where the post stands lives on the job and shows on the game-over sheet, which is back
            // over the game by the time it is known: a toast there was hidden under it, and Share
            // stayed on, so a second tap could post it twice (audit 2026-11-24 #8). The toast is
            // only for when the sheet has gone.
            KaPostQuickComposer(
                text = text,
                onPosting = { job.share.value = ArcadeStampJob.ShareState.Posting },
                onResult = { result ->
                    result.fold(
                        onSuccess = {
                            job.share.value = ArcadeStampJob.ShareState.Shared
                            if (latestFinished == null) IosToasts.show(context.getString(R.string.arcade_posted))
                        },
                        onFailure = { error ->
                            if (error is com.kachat.app.services.SendOutcomeUnknownException) {
                                job.share.value = ArcadeStampJob.ShareState.Unconfirmed
                            } else {
                                val message = ChessTournamentService.friendlySendErrorMessage(error)
                                job.share.value = ArcadeStampJob.ShareState.Failed(message)
                                if (latestFinished == null) {
                                    IosToasts.show(ArcadeText.message(context, message), IosToastStyle.Error)
                                }
                            }
                        },
                    )
                },
                onClose = {
                    composing = null
                    shareJob?.let {
                        shareJob = null
                        finished = it
                    }
                },
            )
        }
    }
}

/** A Compose Dialog's window is only as big as what it measured: asserted full screen, with no
 *  decor fitting so the real insets reach the content (as KaPost's overlays do). */
@Composable
private fun ArcadeFullScreenWindow() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

/** Game over: the score, where its stamp stands, Share to KaPost once it is on Kaspa, Play Again
 *  (iOS `ArcadeGameOverView`, a sheet that can't be swiped away). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcadeGameOverSheet(
    job: ArcadeStampJob,
    service: ArcadeService,
    onPlayAgain: () -> Unit,
    onDone: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val state by job.state.collectAsState()
    val share by job.share.collectAsState()
    val revision by service.progress.revision.collectAsState()
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp

    val shareEnabled = when (share) {
        ArcadeStampJob.ShareState.Posting, ArcadeStampJob.ShareState.Shared, ArcadeStampJob.ShareState.Unconfirmed -> false
        else -> state is ArcadeStampJob.State.Stamped || state is ArcadeStampJob.State.Failed
    }
    val shareTitle = when (share) {
        ArcadeStampJob.ShareState.Posting -> stringResource(R.string.arcade_posting)
        ArcadeStampJob.ShareState.Shared -> stringResource(R.string.arcade_shared)
        ArcadeStampJob.ShareState.Unconfirmed -> stringResource(R.string.swipe_not_confirmed)
        else -> if (state is ArcadeStampJob.State.Failed) stringResource(R.string.arcade_retry_stamp)
        else KaPlayText.string("Share to KaPost")
    }

    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        containerColor = ArcadePalette.background(),
        dragHandle = null,
        windowInsets = WindowInsets(0),
        properties = ModalBottomSheetProperties(
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.Inherit,
            isFocusable = true,
            shouldDismissOnBackPress = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = halfScreen)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                stringResource(R.string.arcade_game_over),
                color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(ArcadeText.number(job.score), color = KaspaTeal, fontSize = 56.sp, fontWeight = FontWeight.Black)
            revision.let { _ -> service.progress.best(job.game) }?.takeIf { it.gameId != job.id }?.let { best ->
                Text(
                    stringResource(R.string.arcade_your_best, ArcadeText.number(best.score)),
                    color = colors.textSecondary, fontSize = 15.sp,
                )
            }
            // the stamp's line
            ArcadeStatusLine {
                when (val current = state) {
                    ArcadeStampJob.State.Sending -> {
                        CircularProgressIndicator(Modifier.size(16.dp), color = colors.textSecondary, strokeWidth = 2.dp)
                        StatusText(stringResource(R.string.arcade_stamping))
                    }
                    ArcadeStampJob.State.Stamped -> {
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
                        StatusText(stringResource(R.string.arcade_stamped))
                    }
                    is ArcadeStampJob.State.Failed -> {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
                        StatusText(ArcadeText.message(context, current.message))
                    }
                    is ArcadeStampJob.State.Unconfirmed -> {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
                        StatusText(ArcadeText.message(context, current.message))
                    }
                    ArcadeStampJob.State.NotSent -> StatusText(stringResource(R.string.arcade_no_score))
                }
            }
            // Why Share to KaPost is off or back on, under the stamp's line (audit 2026-11-24 #8).
            // Posting and shared are the button's own title.
            when (val current = share) {
                ArcadeStampJob.ShareState.Unconfirmed -> ArcadeStatusLine {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
                    StatusText(ArcadeText.message(context, ArcadeMessages.OUTCOME_UNKNOWN))
                }
                is ArcadeStampJob.ShareState.Failed -> ArcadeStatusLine {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
                    StatusText(ArcadeText.message(context, current.message))
                }
                else -> Unit
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (job.score > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(CircleShape)
                            .background(KaspaTeal.copy(alpha = if (shareEnabled) 1f else 0.4f))
                            .clickable(enabled = shareEnabled) {
                                if (state is ArcadeStampJob.State.Failed) {
                                    job.retry()
                                } else {
                                    haptic(IosHaptic.IMPACT_LIGHT)
                                    onShare()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(shareTitle, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(KaspaTeal.copy(alpha = 0.12f))
                        .border(1.5.dp, KaspaTeal, CircleShape)
                        .clickable {
                            haptic(IosHaptic.IMPACT_LIGHT)
                            onPlayAgain()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.arcade_play_again), color = KaspaTeal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    stringResource(R.string.done),
                    color = KaspaTeal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDone)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The game-over sheet's small status lines (the stamp, the post). */
@Composable
private fun ArcadeStatusLine(content: @Composable () -> Unit) {
    Row(
        Modifier.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) { content() }
}

@Composable
private fun StatusText(text: String) {
    Text(text, color = LocalAppColors.current.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
}

/** Whether K is in front and active: Control Center's Android cousins (the notification shade, a
 *  call, another app) pause Snake (audit 2026-11-24 #12, iOS `scenePhase != .active`). */
@Composable
private fun rememberAppActive(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val focused = LocalWindowInfo.current.isWindowFocused
    return resumed && focused
}

/** A swipe of at least 14 (iOS `DragGesture(minimumDistance: 14)`), by where it ended. */
private fun Modifier.arcadeSwipe(minimum: Float, onSwipe: (dx: Float, dy: Float) -> Unit): Modifier =
    pointerInput(Unit) {
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { total = Offset.Zero },
            onDragEnd = {
                if (hypot(total.x, total.y) >= minimum) onSwipe(total.x, total.y)
            },
        ) { change, amount ->
            change.consume()
            total += amount
        }
    }

private fun swipeDirection(dx: Float, dy: Float): ArcadeDirection =
    if (abs(dx) > abs(dy)) {
        if (dx > 0) ArcadeDirection.RIGHT else ArcadeDirection.LEFT
    } else {
        if (dy > 0) ArcadeDirection.DOWN else ArcadeDirection.UP
    }

// MARK: - Snake

/** Slow to start, faster as it grows: 240 ms a step, 5 ms less per apple, down to 85 (owner,
 *  2026-11-24: 150 ms felt too fast to start on). */
internal fun snakeStepMillis(score: Int): Long = max(85, 240 - score * 5).toLong()

@Composable
private fun SnakePlay(onOver: (score: Int, seed: Long, moves: String) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val seed = remember { ArcadeRandom.newSeed() }
    val engine = remember { SnakeEngine(seed) }
    /** Bumped on every tick: the engine changes in place. */
    var frame by remember { mutableIntStateOf(0) }
    /** Turns asked for, taken one per tick. */
    val queued = remember { ArrayDeque<ArcadeDirection>() }
    var started by remember { mutableStateOf(false) }
    val loop = remember { arrayOfNulls<Job>(1) }
    val latestOnOver by rememberUpdatedState(onOver)
    val density = LocalDensity.current
    val minimumSwipe = with(density) { 14.dp.toPx() }
    val active = rememberAppActive()

    fun run() {
        loop[0]?.cancel()
        loop[0] = scope.launch {
            while (isActive && !engine.isOver) {
                delay(snakeStepMillis(engine.score))
                if (!isActive) return@launch
                // the first queued turn that can be taken now
                var turn: ArcadeDirection? = null
                while (queued.isNotEmpty()) {
                    val next = queued.removeFirst()
                    if (engine.canTurn(next)) {
                        turn = next
                        break
                    }
                }
                val ateBefore = engine.score
                engine.tick(turn)
                frame += 1
                if (engine.score > ateBefore) haptic(IosHaptic.IMPACT_LIGHT)
            }
            if (engine.isOver) latestOnOver(engine.score, seed, engine.movesText)
        }
    }

    fun ask(direction: ArcadeDirection) {
        if (engine.isOver) return
        if (queued.size < 3) queued.addLast(direction)
        haptic(IosHaptic.SELECTION)
        if (!started) {
            started = true
            run()
        }
    }

    // The shade, a call or the background pause it: the snake waits for the next swipe or arrow,
    // with the "Swipe ... to start" hint back (audit 2026-11-24 #12).
    LaunchedEffect(active) {
        if (!active && started && !engine.isOver) {
            loop[0]?.cancel()
            loop[0] = null
            queued.clear()
            started = false
        }
    }
    DisposableEffect(Unit) { onDispose { loop[0]?.cancel() } }

    val coin = ImageBitmapCache.coin()
    val kaspa = ImageBitmapCache.kaspa()
    val boardColor = ArcadePalette.secondary()

    Column(
        Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        frame.let { _ ->
            Text(
                stringResource(R.string.arcade_score_n, ArcadeText.number(engine.score)),
                color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(boardColor)
                .border(1.dp, KaspaTeal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .arcadeSwipe(minimumSwipe) { dx, dy -> ask(swipeDirection(dx, dy)) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                frame.let { _ -> }
                val cell = size.width / SnakeEngine.WIDTH
                // Kaspa eats Bitcoin (owner, 2026-11-24): the apple is the Bitcoin logo and the
                // snake's head the Kaspa logo.
                engine.food?.let { food ->
                    val inset = cell * 0.08f
                    val left = food.x * cell + inset
                    val top = food.y * cell + inset
                    val side = cell - inset * 2
                    // the coin image has white corners, not see-through ones: drawn inside its circle
                    val ring = side * 0.03f
                    val circle = Path().apply {
                        addOval(androidx.compose.ui.geometry.Rect(left + ring, top + ring, left + side - ring, top + side - ring))
                    }
                    clipPath(circle) {
                        drawImage(
                            coin,
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(side.toInt(), side.toInt()),
                        )
                    }
                }
                // tail first, so the head draws over the body
                val pad = 1.dp.toPx()
                for (index in engine.body.indices.reversed()) {
                    val part = engine.body[index]
                    val left = part.x * cell + pad
                    val top = part.y * cell + pad
                    val side = cell - pad * 2
                    if (index == 0) {
                        drawImage(
                            kaspa,
                            dstOffset = androidx.compose.ui.unit.IntOffset((left - 0.5f).toInt(), (top - 0.5f).toInt()),
                            dstSize = IntSize((side + 1).toInt(), (side + 1).toInt()),
                        )
                    } else {
                        drawRoundRect(
                            KaspaTeal.copy(alpha = 0.75f),
                            topLeft = Offset(left, top),
                            size = Size(side, side),
                            cornerRadius = CornerRadius(cell * 0.25f, cell * 0.25f),
                        )
                    }
                }
            }
        }
        if (!started) {
            Text(stringResource(R.string.arcade_snake_start_hint), color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SnakeArrow(ArcadeDirection.UP) { ask(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                SnakeArrow(ArcadeDirection.LEFT) { ask(it) }
                SnakeArrow(ArcadeDirection.RIGHT) { ask(it) }
            }
            SnakeArrow(ArcadeDirection.DOWN) { ask(it) }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The images Snake draws, decoded once. */
private object ImageBitmapCache {
    @Composable
    fun coin() = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.ic_coin_btc)

    @Composable
    fun kaspa() = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.ic_kaspa_logo)
}

@Composable
private fun SnakeArrow(direction: ArcadeDirection, onTap: (ArcadeDirection) -> Unit) {
    val icon = when (direction) {
        ArcadeDirection.UP -> Icons.Filled.KeyboardArrowUp
        ArcadeDirection.DOWN -> Icons.Filled.KeyboardArrowDown
        ArcadeDirection.LEFT -> Icons.Filled.KeyboardArrowLeft
        ArcadeDirection.RIGHT -> Icons.Filled.KeyboardArrowRight
    }
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KaspaTeal.copy(alpha = 0.12f))
            .clickable { onTap(direction) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(32.dp))
    }
}

// MARK: - 2048

/** A tile as drawn, with its own identity, so a move slides every tile to where it lands and two
 *  equal tiles run into each other before they become one (owner, 2026-11-24: no fading). */
private data class VisualTile(
    val id: Long,
    val value: Int,
    val cell: Int,
    /** Bumped when it doubles: the tile pops. */
    val pops: Int = 0,
    /** Merged into another tile: it slides there, then goes. */
    val consumed: Boolean = false,
    val isNew: Boolean = false,
)

private const val SLIDE_MILLIS = 110

private val tileIds = java.util.concurrent.atomic.AtomicLong()

private fun tilesFrom(engine: Game2048Engine): List<VisualTile> =
    engine.tiles.mapIndexedNotNull { index, value ->
        if (value == 0) null else VisualTile(tileIds.incrementAndGet(), value, index)
    }

@Composable
private fun Game2048Play(onOver: (score: Int, seed: Long, moves: String) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val seed = remember { ArcadeRandom.newSeed() }
    val engine = remember { Game2048Engine(seed) }
    var frame by remember { mutableIntStateOf(0) }
    var reported by remember { mutableStateOf(false) }
    /** Kept equal to `engine.tiles` between moves. */
    var shown by remember { mutableStateOf(tilesFrom(engine)) }
    /** The second half of a move (merges and the new tile), run early if the next swipe comes
     *  before it has. */
    val settle = remember { arrayOfNulls<() -> Unit>(1) }
    var settleToken by remember { mutableIntStateOf(0) }
    val latestOnOver by rememberUpdatedState(onOver)
    val minimumSwipe = with(LocalDensity.current) { 14.dp.toPx() }

    fun settleNow() {
        val pending = settle[0] ?: return
        settle[0] = null
        pending()
    }

    fun swipe(dx: Float, dy: Float) {
        if (engine.isOver) return
        // the last move's merges land now, so this one starts from the board as it is
        settleNow()
        val direction = swipeDirection(dx, dy)
        if (!engine.move(direction)) return
        frame += 1
        haptic(IosHaptic.SELECTION)

        // Where each tile goes, line by line, the way the engine slides them: equal neighbours
        // meet in one cell, nearest the wall first.
        val moved = shown.toMutableList()
        val merges = mutableListOf<Pair<Long, Long>>()
        for (line in 0 until Game2048Engine.SIZE) {
            val cells = Game2048Engine.lineIndices(line, direction)
            val inLine = cells.mapNotNull { cell -> moved.indexOfFirst { it.cell == cell && !it.consumed }.takeIf { it >= 0 } }
            var target = 0
            var i = 0
            while (i < inLine.size) {
                val a = inLine[i]
                if (i + 1 < inLine.size && moved[a].value == moved[inLine[i + 1]].value) {
                    val b = inLine[i + 1]
                    moved[a] = moved[a].copy(cell = cells[target])
                    moved[b] = moved[b].copy(cell = cells[target], consumed = true)
                    merges.add(moved[a].id to moved[b].id)
                    i += 2
                } else {
                    moved[a] = moved[a].copy(cell = cells[target])
                    i += 1
                }
                target += 1
            }
        }
        shown = moved.map { it.copy(isNew = false) }

        // Then they clash: the pair becomes one tile of double the value, and the new tile appears.
        val after = engine.copy()
        settleToken += 1
        val token = settleToken
        settle[0] = {
            var tiles = shown.filter { !it.consumed }.toMutableList()
            for ((keep, _) in merges) {
                val index = tiles.indexOfFirst { it.id == keep }
                if (index >= 0) tiles[index] = tiles[index].copy(value = tiles[index].value * 2, pops = tiles[index].pops + 1)
            }
            val spawn = after.lastSpawn
            if (spawn != null && after.tiles[spawn] != 0) {
                tiles.add(VisualTile(tileIds.incrementAndGet(), after.tiles[spawn], spawn, isNew = true))
            }
            // the board as drawn must be the engine's; if it isn't, the engine wins
            val check = IntArray(16)
            for (tile in tiles) check[tile.cell] = tile.value
            if (check.toList() != after.tiles) tiles = tilesFrom(after).toMutableList()
            shown = tiles
        }
        scope.launch {
            delay(SLIDE_MILLIS.toLong())
            if (settleToken == token) settleNow()
        }

        if (engine.isOver && !reported) {
            reported = true
            scope.launch {
                delay(600)
                latestOnOver(engine.score, seed, engine.moves)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        frame.let { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreBox(stringResource(R.string.arcade_score), engine.score, Modifier.weight(1f))
                ScoreBox(stringResource(R.string.arcade_best_tile), engine.bestTile, Modifier.weight(1f))
            }
        }
        BoxWithConstraints(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .arcadeSwipe(minimumSwipe) { dx, dy -> swipe(dx, dy) },
        ) {
            val side = maxWidth
            val gap = 8.dp
            val cell = (side - gap * 5) / 4
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(ArcadePalette.secondary()))
            val fill = ArcadePalette.tertiaryFill()
            for (index in 0 until 16) {
                Box(
                    Modifier
                        .offset(x = gap + (cell + gap) * (index % 4), y = gap + (cell + gap) * (index / 4))
                        .size(cell)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fill),
                )
            }
            // consumed tiles under the ones they merge into
            for (tile in shown.sortedBy { if (it.consumed) 0 else 1 }) {
                key(tile.id) {
                    val x by animateDpAsState(
                        gap + (cell + gap) * (tile.cell % 4),
                        tween(SLIDE_MILLIS, easing = FastOutSlowInEasing),
                        label = "tileX",
                    )
                    val y by animateDpAsState(
                        gap + (cell + gap) * (tile.cell / 4),
                        tween(SLIDE_MILLIS, easing = FastOutSlowInEasing),
                        label = "tileY",
                    )
                    Game2048Tile(tile.value, tile.pops, tile.isNew, Modifier.offset(x = x, y = y).size(cell))
                }
            }
        }
        Text(stringResource(R.string.arcade_2048_hint), color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ScoreBox(title: String, value: Int, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ArcadePalette.secondary())
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            ArcadeText.number(value),
            color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
    }
}

/** SwiftUI's `spring(response:dampingFraction:)` as a Compose spring. */
private fun <T> swiftSpring(response: Float, damping: Float) =
    spring<T>(dampingRatio = damping, stiffness = (2 * Math.PI / response).let { (it * it).toFloat() })

/** One 2048 tile: its colour by value, a pop when it doubles, a grow-in when it's new. */
@Composable
private fun Game2048Tile(value: Int, pops: Int, isNew: Boolean, modifier: Modifier) {
    val colors = LocalAppColors.current
    val level = if (value > 0) log2(value.toDouble()).toInt() else 0
    val scale = remember { Animatable(if (isNew) 0.2f else 1f) }
    LaunchedEffect(Unit) {
        if (isNew) scale.animateTo(1f, swiftSpring(0.22f, 0.7f))
    }
    LaunchedEffect(pops) {
        if (pops == 0) return@LaunchedEffect
        // the clash: a quick swell as the two become one
        scale.snapTo(1.18f)
        scale.animateTo(1f, swiftSpring(0.2f, 0.55f))
    }
    Box(
        modifier
            .scale(scale.value)
            .clip(RoundedCornerShape(10.dp))
            .background(KaspaTeal.copy(alpha = min(1.0, 0.25 + level * 0.07).toFloat()))
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KachatFitText(
            "$value",
            color = if (level >= 7) Color.Black else colors.textPrimary,
            fontSize = if (value >= 1000) 22.sp else 28.sp,
            fontWeight = FontWeight.Black,
            minScale = 0.5f,
            textAlign = TextAlign.Center,
        )
    }
}
