package com.kachat.app.ui.screens

import com.kachat.app.ui.theme.IosSymbols
import com.kachat.app.ui.components.CycleCapsuleButton
import androidx.compose.ui.layout.layoutId
import com.kachat.app.ui.components.CycleFade
import com.kachat.app.ui.components.PageCycleBar
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.iosShadow
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

import com.kachat.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Flag
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.TimeInput
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.SwapVert
import com.kachat.app.util.currencySymbolFor
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.util.formatKasAmount
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import com.kachat.app.services.kachatnames.KachatNamesService
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.viewmodels.ChatViewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.models.KaPostDraft
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaPostsMarkdown
import com.kachat.app.util.KaspaUnit
import com.kachat.app.services.PostTranslationService
import com.kachat.app.viewmodels.KaPostsViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Badge
import androidx.compose.ui.text.style.TextAlign

/**
 * Cross-screen deep-link handoff: MainActivity (kachat://kapost/<txid>, universal links) and
 * notification taps set this; KaPostsScreen consumes it and opens the post's thread.
 */
object KaPostsDeepLink {
    val pendingPostTxId = MutableStateFlow<String?>(null)

    /**
     * Whether a tap on a KaPosts push opens the action itself - a reply or a quote - rather than
     * the post it was made on (iOS 093fe4d `kaPostsPushOpensAction`): the landing rule opens its
     * parent's thread with it already in, before the indexer lists it. Read from the push's
     * `kaposts_kind` when the server sends one, and otherwise from its server-written wording.
     */
    fun pushOpensAction(kind: String?, body: String?): Boolean {
        if (kind != null) return kind == "reply" || kind == "quote"
        val lowered = body.orEmpty().lowercase()
        return lowered.contains("replied to your") || lowered.contains("quoted your")
    }

    /** A post or an action is a transaction id: 64 hex characters. */
    fun isTxId(value: String?): Boolean = value != null && value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    /** For reply notifications: the reply's own txid, so the opened PARENT thread (which is
     *  what [pendingPostTxId] carries) can scroll to the new comment. */
    val pendingFocusReplyTxId = MutableStateFlow<String?>(null)

    /** A pre-written post from another app (`kaposts://compose`, iOS 9e22075), for the new-post
     *  composer. Pending for a cold start or a link that arrives before sign-in. */
    val pendingComposeText = MutableStateFlow<String?>(null)

    /** Set alongside a blank [pendingPostTxId] when a KaPosts notification names no post to open
     *  (a follow, or a push whose payload carried no post id): KaPostsScreen opens its
     *  Notifications list instead of just dropping the user on the feed with no idea what the
     *  ping was about. Mirrors iOS's `KaPostsDeepLink.pendingOpenNotifications`. */
    val pendingOpenNotifications = MutableStateFlow(false)

    /** The scheduled-post reminder tapped: KaPostsScreen opens its Scheduled list on mount, or
     *  at once when it is already showing. Mirrors iOS's `KaPostsDeepLink.pendingOpenScheduled`. */
    val pendingOpenScheduled = MutableStateFlow(false)

    /** Someone's KaPosts profile to open over the feed: a kachat://profile or kaposts://profile
     *  link, or View Profile on a public chat member (iOS KaPosts `pendingProfileAddress`). */
    val pendingProfileAddress = MutableStateFlow<String?>(null)

    /** The signed-in account's chatting address, kept by the shell (MainShell), for
     *  [openProfile]'s "is it you". */
    @Volatile var signedInAddress: String? = null

    /**
     * Someone's KaPost profile, from anywhere (an @mention, a room member, the bell). Your own
     * opens the dock's Profile tab instead (iOS d089011f `KaPostsDeepLink.openProfile`, owner
     * 2026-11-25), not a visitor's copy over KaPost: every KaPost cover closes and the shell shows
     * Profile.
     */
    fun openProfile(address: String, myAddress: String? = signedInAddress) {
        if (isOwnAddress(address, myAddress)) {
            KaPostsDock.select(com.kachat.app.ui.AppTab.PROFILE.route)
            return
        }
        pendingProfileAddress.value = address
    }

    /** Whether [address] is [myAddress] (any case). Pure. */
    fun isOwnAddress(address: String, myAddress: String?): Boolean =
        !myAddress.isNullOrBlank() && address.isNotBlank() && myAddress.trim().equals(address.trim(), ignoreCase = true)
}

/**
 * Properties for KaPosts' full-screen overlays.
 *
 * `decorFitsSystemWindows = false` is load-bearing, not cosmetic. The app targets SDK 36, where
 * edge-to-edge is enforced: the framework ignores a window's request to fit the system bars while
 * its DecorView still CONSUMES the insets on the way in. A Compose `Dialog` left on the default
 * (`true`) therefore ends up drawing under the status and navigation bars while every inset
 * modifier inside it resolves to zero - which is why the thread overlay's reply composer sat half
 * under the gesture-navigation bar. Turning it off stops the decor consuming, so the real insets
 * reach the content and [KaPostsOverlayInsets] below is the single source of truth. It is also the
 * documented prerequisite for the IME inset being reported inside a dialog at all.
 */
private val KaPostsFullScreenDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

/**
 * Status bar + navigation bar (gesture AND 3-button) + display cutout + the IME while it is up,
 * as ONE union. Applying them as a union rather than chaining `.imePadding()` after
 * `.navigationBarsPadding()` matters: the IME inset already spans the navigation bar, so the chain
 * double-counted the bottom and shoved the composer up by an extra nav-bar height whenever the
 * keyboard opened.
 */
private val KaPostsOverlayInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

/**
 * Forces a Compose `Dialog`'s window to actually be full-screen. Call it as the first thing inside
 * the dialog's content.
 *
 * `usePlatformDefaultWidth = false` does NOT give the dialog a MATCH_PARENT window: `DialogLayout`
 * measures its content against `Configuration.screenHeightDp` and then calls
 * `window.setLayout(child.measuredWidth, child.measuredHeight)`, so the window is only ever as big
 * as the content it just measured. Under this app's enforced edge-to-edge (targetSdk 36) that
 * lands the window short of the content, and anything at the bottom of the content gets clipped
 * off the screen - which is what was cutting the thread overlay's reply composer in half. Asserting
 * MATCH_PARENT (plus ADJUST_RESIZE so the IME resizes the window, and no decor fitting so the real
 * insets reach the content) keeps these overlays whole.
 *
 * The thread overlay does not use this - it was moved out of a Dialog entirely, which is the
 * sturdier fix. These secondary overlays keep the Dialog because they carry no bottom-pinned
 * composer to lose.
 */
@Composable
private fun ForceFullScreenDialogWindow() {
    val view = LocalView.current
    // LaunchedEffect, NOT SideEffect: these window attributes only need setting once per
    // dialog. As a SideEffect this re-ran after EVERY recomposition — in the composer
    // dialogs that meant a WindowManager relayout + soft-input re-assert per keystroke,
    // which is real typing lag under an attached IME.
    LaunchedEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        // Deprecated from API 30, where setDecorFitsSystemWindows(false) below plus the IME inset
        // supersede it - still the only thing that resizes the window for the keyboard on this
        // app's minSdk 26..29 range, so both are set.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // A dialog window is still fitted between the status and navigation bars by default
        // (frame 132..2337 on a 2400 px screen) while its content is laid out for the whole
        // screen, so everything sat a status bar lower and the dock copy at the bottom went half
        // off the screen (Settings > KaPost, a poster's profile; 2026-11-25 emulator sweep). The
        // window covers the bars, as the app's own window does, and the content pads itself
        // with the real insets ([KaPostsOverlayInsets], the dock's navigationBarsPadding).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        }
    }
}

/**
 * Feed order, matching iOS's `FeedTab.allCases`: Following | Feed | Popular - the order the feed
 * button beside the title steps through, and the index of each feed's hoisted list state.
 */
private val KaPostsFeedTabs = listOf(
    KaPostsViewModel.FeedTab.FOLLOWING,
    KaPostsViewModel.FeedTab.FEED,
    KaPostsViewModel.FeedTab.POPULAR,
)

@Composable
private fun KaPostsViewModel.FeedTab.label(): String = when (this) {
    KaPostsViewModel.FeedTab.FOLLOWING -> stringResource(R.string.l53_feed_tab_following)
    KaPostsViewModel.FeedTab.FEED -> stringResource(R.string.l53_feed_tab_feed)
    KaPostsViewModel.FeedTab.POPULAR -> stringResource(R.string.l53_feed_tab_popular)
}

// MARK: - Endless scrolling
//
// Every list in KaPosts pages the same way: a trigger that fires as the reader NEARS the end (not
// at the last row - by then the stall is already visible), and a footer that shows what the fetch
// is doing. The view model owns the "is one already in flight / have we hit the end" decision, so
// [EndlessScroll] can fire freely and [KaPostsViewModel.loadMore*] just no-ops when it shouldn't run.

/**
 * Calls [onLoadMore] whenever the last visible row comes within
 * [KaPostsViewModel.LOAD_MORE_THRESHOLD] of the end of [listState]'s list.
 *
 * Re-fires after an append too (the total grows, the check runs again), which is what fills a tall
 * screen when the KaChat-marker filter leaves a page with only a couple of visible rows.
 */
/**
 * Whether a pull-to-refresh indicator is in use: a pull under way or a refresh running. Read
 * through [derivedStateOf], so the scope calling it recomposes only when that flips, not on every
 * frame of a drag: the offset read in composition recomposed the whole feed Box, its LazyColumn
 * call included, each frame of a pull (audit 2026-11-27 S10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun pullIndicatorShown(state: androidx.compose.material3.pulltorefresh.PullToRefreshState): Boolean {
    val shown by remember(state) { derivedStateOf { state.verticalOffset > 0f || state.isRefreshing } }
    return shown
}

/** The feed list's first row: one pixel, never drawn, with a key of its own (iOS
 *  `feedTopAnchor`). See [FEED_TOP_ANCHOR_INDEX]. */
private const val FEED_TOP_ANCHOR_KEY = "feed-top-anchor"

/**
 * Index of the first post in a feed list, below its top anchor.
 *
 * A LazyColumn keeps its place by the key of its first visible row. While page one loads, that
 * row is the "Loading more" footer, and Popular's sweep shows the footer again the moment page one
 * is in (Feed on Wi-Fi): the list then arrived as posts + footer, followed the footer's key down
 * past every post, and opened partway down with Back to Top up and no pill (audit 2026-11-27 E1).
 * A fresh page with newer posts on top did the same from the old first post. With an anchor row
 * that never moves, a reader at the top stays at the newest post whatever arrives above; one who
 * has scrolled keeps their post, and newer ones wait in the pill, as before.
 */
internal const val FEED_TOP_ANCHOR_INDEX = 0

/** Back to Top shows once the first post has scrolled away, as before the anchor row. */
internal fun feedAwayFromTop(firstVisibleItemIndex: Int): Boolean = firstVisibleItemIndex > FEED_TOP_ANCHOR_INDEX + 1

@Composable
private fun EndlessScroll(
    listState: LazyListState,
    key: Any? = Unit,
    onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, key) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 1 - KaPostsViewModel.LOAD_MORE_THRESHOLD) {
                    loadMore()
                }
            }
    }
}

/**
 * Bottom-of-list status row: a spinner while a page is being fetched, or a retry row when one
 * failed. A failure never clears what is already loaded - the reader keeps their list and their
 * place in it, and taps Retry to resume from the same cursor. Renders nothing once the surface has
 * reached the end, which is how the list stops asking.
 */
private fun LazyListScope.pagingFooter(
    state: KaPostsViewModel.PagingState,
    keySuffix: String,
    /** A profile's footer slides with its posts (iOS 804cbcb7). */
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    if (!state.isLoadingMore && state.error == null && !state.stalled) return
    item(key = "paging-footer-$keySuffix") {
        Box(modifier) { PagingFooterContent(state = state, onLoadMore = onRetry) }
    }
}

/**
 * The footer's three states, as iOS's KaPostsLoadMoreFooter draws them: a spinner with
 * "Loading more...", a tappable "Couldn't load more" block carrying the error and "Tap to retry",
 * or - after a whole request budget produced nothing visible - an explicit "Load more" button.
 * Shared by the lazy lists and the inline reply expansions, which are not lists of their own.
 */
@Composable
private fun PagingFooterContent(
    state: KaPostsViewModel.PagingState,
    onLoadMore: () -> Unit,
) {
    val colors = LocalAppColors.current
    when {
        state.isLoadingMore -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textSecondary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.kpui_loading_more), color = colors.textSecondary, fontSize = 12.sp)
        }
        state.error != null -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoadMore() }
                .padding(vertical = 16.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.kpui_couldnt_load_more), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(state.error, color = colors.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(stringResource(R.string.tap_to_retry), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        state.stalled && state.hasMore -> Text(
            stringResource(R.string.kpui_load_more),
            color = KaspaTeal,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoadMore() }
                .padding(vertical = 16.dp),
        )
    }
}

// MARK: - Per-key state selection
//
// The KaPosts view model keeps per-address / per-post data in whole-map StateFlows. Collecting
// one of those maps INSIDE a list cell subscribes every visible cell to every entry: one avatar
// or probe result arriving re-ran the whole viewport - and profile fetches fire per row as it
// scrolls in, so the invalidation landed exactly mid-scroll (the primary feed jank cause; the
// iOS twin had the identical bug). These helpers subscribe a composable to ONE derived slice,
// so a cell recomposes only when ITS value actually changes.

/** What a post's text draws as cards, and the text left without them ([kaPostTextParts]). */
private data class KaPostTextParts(
    val paymentRequestLink: KaChatLinkMatch?,
    /** A Kaspa Hub card's link (`kachat.app/hub/<slug>`, iOS 961fc05a): drawn as a card
     *  ([KaspaHubLinkCard]) under the text, and left out of the text itself. */
    val hubLink: KaChatLinkMatch?,
    val botChallengeLink: KaPlayBotChallenge.Match?,
    /** An arcade score challenge (`kachatarcade://`, iOS 518fdc8c): drawn as a card
     *  (KaPostArcadeChallengeCard) under the text, and left out of the text itself. */
    val arcadeChallengeLink: com.kachat.app.services.arcade.ArcadeChallenge.Match?,
    val siteLink: KaChatSiteLink.Match?,
    val webLink: com.kachat.app.services.KaPostLinkPreviewPolicy.Match?,
    val bodyText: String,
)

/** The newest answers of [kaPostTextParts], by text and whether the poster has a .kachat name. */
private val kaPostTextPartsCache = android.util.LruCache<Pair<String, Boolean>, KaPostTextParts>(256)

/**
 * The links a post's [displayedText] draws as cards, and the text as drawn without them. Kept per
 * text, so the link, site and challenge matchers run over a post once, not each time its row
 * scrolls back in (audit M11).
 */
private fun kaPostTextParts(displayedText: String, posterHasKachatName: Boolean): KaPostTextParts {
    val key = displayedText to posterHasKachatName
    val parts = kaPostTextPartsCache.get(key) ?: run {
        // A payment request link in the post: drawn as a card (KaPostPaymentRequestCard) under the
        // text, and left out of the text itself (iOS KaPosts 0b9fd28).
        val internalLink = KaChatLink.findFirst(displayedText)
        val paymentRequestLink = internalLink?.takeIf { it.ref is KaChatLinkRef.PaymentRequest }
        // A Kaspa Hub card's link: drawn as a card with Open (iOS 961fc05a `hubLink`).
        val hubLink = internalLink?.takeIf { it.ref is KaChatLinkRef.HubSection }
        // A KaPlay bot challenge link in the post: drawn as a card (KaPostBotChallengeCard) under the
        // text, and left out of the text itself (iOS 9e22075).
        val botChallengeLink = KaPlayBotChallenge.match(displayedText)
        val arcadeChallengeLink = com.kachat.app.services.arcade.ArcadeChallenge.match(displayedText)
        // A link to KaChat's own site in the post: drawn as a preview card under the text, and left
        // out of the text itself (iOS f81c467). Only for a poster with a .kachat name: anyone else's
        // links never preview.
        val siteLink = if (posterHasKachatName) KaChatSiteLink.match(displayedText) else null
        // The post's first web link, drawn as its preview card in place of the link's text when the
        // poster has a .kachat name and the site is on the allowed list (KaPostLinkPreviewPolicy); a
        // preview that can't load shows the link itself. Links KaPost draws its own way (posts, rooms,
        // payment requests) and KaChat's site are left to their own cards (iOS b78fdb0 `webLink`).
        val webLink = if (!posterHasKachatName || siteLink != null) null else {
            com.kachat.app.services.KaPostLinkPreviewPolicy.firstLink(displayedText)?.takeIf {
                KaChatLink.parse(it.url) == null && com.kachat.app.services.KaPostLinkPreviewPolicy.allows(it.url)
            }
        }
        // The text as drawn: the post without the links drawn as cards.
        var text = displayedText
        paymentRequestLink?.let { text = text.replace(it.raw, "") }
        hubLink?.let { text = text.replace(it.raw, "") }
        botChallengeLink?.let { text = text.replace(it.matchedText, "") }
        arcadeChallengeLink?.let { text = text.replace(it.matchedText, "") }
        // Only this one: the same text can also be the start of a longer link to another page.
        siteLink?.let { text = text.replaceFirst(it.matchedText, "") }
        // the previewed link: its card stands in for it
        webLink?.let { text = text.replaceFirst(it.matchedText, "") }
        val bodyText = if (paymentRequestLink != null || hubLink != null || botChallengeLink != null || arcadeChallengeLink != null || siteLink != null || webLink != null) text.trim() else displayedText
        KaPostTextParts(paymentRequestLink, hubLink, botChallengeLink, arcadeChallengeLink, siteLink, webLink, bodyText).also { kaPostTextPartsCache.put(key, it) }
    }
    // Its card's fetch, redirects and image are then held to the allow-lists (iOS); marked on
    // every use, as the marked set keeps only the newest links.
    parts.webLink?.let { com.kachat.app.services.KaPostLinkPreviewPolicy.markForPreview(it.url) }
    return parts
}

/** How long a post's row stays on screen before its language is checked for Translate (audit M11). */
private const val TRANSLATION_CHECK_VISIBLE_MS = 300L

/** How long a feed row stays on screen before it asks whether it heads a thread (audit M13; 0.6 s
 *  as iOS `ThreadProbeClaims.probeDwellNanos`, its audit 2026-11-21 #35). */
private const val THREAD_PROBE_VISIBLE_MS = 600L

/** A cell's undo countdowns (deadline ms, null when none), as one value so it can be one collector. */
private data class KaPostCellDeadlines(val repost: Long?, val like: Long?, val dislike: Long?, val pollVote: Long?)

/** The three translation flags a cell reads, as one value so it can be one collector. */
private data class KaPostTranslationSlice(
    val state: com.kachat.app.services.PostTranslationService.TranslationState?,
    val showingOriginal: Boolean,
    val canTranslate: Boolean,
)

/** Compose state for one derived [selector] slice of [this]. [keys] must cover the selector's captures. */
@Composable
private fun <T, R> StateFlow<T>.collectSelectedAsState(vararg keys: Any?, selector: (T) -> R): State<R> {
    val sliced = remember(this, *keys) { map(selector).distinctUntilChanged() }
    return sliced.collectAsState(initial = selector(value))
}

/**
 * The poster display chain (contact alias > .kachat name > shortened address, iOS 3d6fb7c) as
 * LIVE per-address state: recomposes its reader when THIS address's alias or .kachat name lands
 * (the registry's identity cache is snapshot state). .kachat is the only name service K reads
 * (iOS 518fdc8c). Mirrors [KaPostsViewModel.posterDisplayName], which stays the one-shot
 * non-reactive variant.
 */
@Composable
private fun posterDisplayNameState(viewModel: KaPostsViewModel, address: String): String {
    val alias by viewModel.contactAliases.collectSelectedAsState(address) { it[address] }
    val kachat = if (address.isEmpty()) null else com.kachat.app.services.kachatnames.KachatNamesRegistry.kachatName(address)
    val unknown = stringResource(R.string.unknown)
    return remember(alias, kachat, address, unknown) {
        KaPostsViewModel.posterName(address, alias, kachat, unknown)
    }
}

/** The live paging state for one surface, as Compose state - sliced per key, so a page load on
 *  one surface no longer recomposes every other open surface (feed tabs, thread, profile tabs). */
@Composable
private fun pagingStateOf(viewModel: KaPostsViewModel, key: String): KaPostsViewModel.PagingState {
    val state by viewModel.paging.collectSelectedAsState(key) { it[key] ?: KaPostsViewModel.PagingState() }
    return state
}

// MARK: - Main screen

/**
 * Where back should land after closing a thread that was opened FROM a profile overlay.
 * The profile Dialog has to close before the in-composition thread can show (see the
 * close-then-open comments at the overlay call sites), so without remembering it, backing
 * out of that thread dumped the user on the main feed instead of the profile they came from.
 */
private sealed interface KaPostsProfileReturn {
    /** My own profile (the side menu's Profile entry). */
    object Mine : KaPostsProfileReturn

    /** Another poster's profile. */
    data class Poster(val address: String, val pubkey: String?) : KaPostsProfileReturn
}

/**
 * What a KaPosts screen is the root of: the feed (the Feed tab) or your own profile (the Profile
 * tab). Both share every handler and presenter, so a post tapped on your profile opens, quotes
 * and replies exactly as it does in the feed (iOS KaPosts `KaPostsView.RootMode`).
 */
enum class KaPostsRootMode { FEED, MY_PROFILE }

/**
 * KaPosts, or - on a network with no KaPost indexer (testnet) - a page saying so, with none of
 * the feed work started (iOS KaPostsUnavailableView, 0bda85e).
 */
@Composable
fun KaPostsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    handlesDeepLinks: Boolean = true,
    rootMode: KaPostsRootMode = KaPostsRootMode.FEED,
    /** Your profile's See All Posts: show the KaPost tab, wherever it lives (iOS 148a5331). */
    onSeeAllPosts: (() -> Unit)? = null,
    kaPostsSettings: com.kachat.app.viewmodels.KaPostsSettingsViewModel = hiltViewModel(),
) {
    val kapostIndexerUrl by kaPostsSettings.kapostIndexerUrl.collectAsState()
    when {
        kapostIndexerUrl == null -> Box(Modifier.fillMaxSize().background(LocalAppColors.current.background))
        kapostIndexerUrl.isNullOrBlank() -> KaPostsUnavailable()
        else -> KaPostsFeedScreen(
            navController = navController,
            walletViewModel = walletViewModel,
            // The feed, scheduled posts and the links are the KaPost tab's.
            handlesDeepLinks = handlesDeepLinks && rootMode == KaPostsRootMode.FEED,
            rootMode = rootMode,
            onSeeAllPosts = onSeeAllPosts,
        )
    }
}

/**
 * The dock's Profile tab (iOS 148a5331 `KaPostsView(rootMode: .myProfile)`): your KaPost profile -
 * banner, avatar, name, follows and bio, then your posts and replies (Posts / Replies on the
 * cycle button). Under the header Edit Profile, Share Profile and Log Out; beside the avatar See All
 * Posts ([onSeeAllPosts]: show the KaPost tab); the compose button; and every notification behind
 * the bell top right ([AllNotificationsSheet]).
 */
@Composable
fun KaPostsMyProfileScreen(
    navController: NavController,
    onSeeAllPosts: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    KaPostsScreen(
        navController = navController,
        walletViewModel = walletViewModel,
        handlesDeepLinks = false,
        rootMode = KaPostsRootMode.MY_PROFILE,
        onSeeAllPosts = onSeeAllPosts,
    )
}

@Composable
private fun KaPostsUnavailable() {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(androidx.compose.material.icons.Icons.Default.Forum, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.kaposts_not_on_testnet),
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.kaposts_not_on_testnet_sub),
            color = colors.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KaPostsFeedScreen(
    navController: NavController,
    viewModel: KaPostsViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    settingsViewModel: com.kachat.app.viewmodels.KaPostsSettingsViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    /**
     * Whether this instance opens posts named by a notification or a shared link. Exactly one
     * may: the KaPosts route that MainShell navigates to on every such tap. The copy inside the
     * Kaspa Hub must not - it is the one being navigated AWAY from, so a link it took was opened
     * in a coroutine that was cancelled a frame later, and the tap landed on the feed with no
     * post. That was every tap made while KaPosts was open in the Hub, which is where KaPosts
     * lives by default.
     */
    handlesDeepLinks: Boolean = true,
    rootMode: KaPostsRootMode = KaPostsRootMode.FEED,
    /** Your profile's See All Posts: the KaPost tab, wherever it lives (iOS 148a5331). */
    onSeeAllPosts: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    /** Profile's bell: every notification, KaPost's and the wallet's, in one sheet (iOS 148a5331). */
    var showAllNotifications by remember { mutableStateOf(false) }
    if (rootMode == KaPostsRootMode.MY_PROFILE) {
        // A push that names no post, or a wallet notification's tap, opens the bell
        // (iOS 148a5331 `NotificationsRoute.pending` / `.openAllNotifications`).
        val routePending by NotificationsRoute.pending.collectAsState()
        val kaPostsPending by KaPostsDeepLink.pendingOpenNotifications.collectAsState()
        LaunchedEffect(routePending, kaPostsPending) {
            if (routePending || kaPostsPending) {
                NotificationsRoute.pending.value = false
                KaPostsDeepLink.pendingOpenNotifications.value = false
                showAllNotifications = true
            }
        }
    }
    val selectedFeed by viewModel.selectedFeed.collectAsState()
    val visiblePosts by viewModel.visiblePosts.collectAsState()
    val visibleFollowingPosts by viewModel.visibleFollowingPosts.collectAsState()
    val isLoading by viewModel.isLoadingFeed.collectAsState()
    val feedError by viewModel.feedError.collectAsState()
    // Toasts and the connection dot deliberately collect inside their own composables
    // (KaPostsToastLayer / KaPostsConnectionDotButton): every toast tick and node-health color change
    // used to recompose this ENTIRE screen body, feed pager included, mid-scroll.

    var showComposer by remember { mutableStateOf(false) }
    /// Text handed back by Undo, for the composer that is about to reopen.
    var restoredComposerText by remember { mutableStateOf("") }
    /// Thread segments handed back by Undo, stacked above the restored text.
    var restoredComposerSegments by remember { mutableStateOf(emptyList<String>()) }
    /// An undone poll's options, handed to the reopened composer.
    var restoredPollOptions by remember { mutableStateOf(emptyList<String>()) }
    // Zero-balance funding gate — tapping "New post" while the chatting balance is a confirmed
    // 0 KAS opens the shared funding card as a dialog instead of the post composer (replies get
    // the same treatment inside KaPostThreadOverlay). See ZeroBalanceFundingUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    /** Thread stack: each entry is a post's LOCAL id; tapping nested comments pushes deeper. */
    var threadStack by remember { mutableStateOf(listOf<String>()) }
    /** One-shot: remoteId of a reply the NEXT opened thread should scroll to (reply
     *  notifications open the parent's thread and land the reader on the new comment). */
    var threadFocusReplyId by remember { mutableStateOf<String?>(null) }
    // Keyed by the thread-stack INDEX of the entry that was opened from a profile, so nested
    // threads pushed on top pop normally and only closing that exact entry re-opens the
    // profile it came from (a thread opened from the feed never restores anything).
    var profileReturns by remember { mutableStateOf(mapOf<Int, KaPostsProfileReturn>()) }
    var quoteTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    /**
     * Answering a SPECIFIC reply opens the composer with that reply under the editor, as on iOS
     * and desktop. The inline box under the post you opened stays for replying to the post
     * itself - X has both shapes, each where it fits.
     */
    var replyComposerTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    /** The post a reopened reply or quote draft was about, once resolved from its stored txid. */
    var draftSourcePost by remember { mutableStateOf<KaPostDraft?>(null) }
    /** The stored txid that could not be resolved, so Post stays off and the composer says why. */
    var draftSourceFailedId by remember { mutableStateOf<String?>(null) }
    /** The reply box under the opened post. Lives here rather than in the thread overlay so it
     *  survives walking up and down the thread stack; cleared only when the thread closes (iOS
     *  replyText / closeThread). Handed down as the state itself and read only by the box: read
     *  here, every keystroke recomposed this whole screen and the thread (iOS 01f71184 P2
     *  `KaPostThreadReplyBar`). */
    val threadReplyText = remember { mutableStateOf(TextFieldValue("")) }
    // Anything handed back by Undo reopens its composer with the words still in it - a post or
    // thread reopens the composer, a quote reopens the quote composer on its target, and an
    // undone comment reopens the Reply composer with its parent attached (iOS restoreDraft).
    // Deferred a beat so the toast's own dismissal animation has somewhere to land.
    val restoredDraft by viewModel.restoredDraft.collectAsState()
    LaunchedEffect(restoredDraft) {
        val draft = restoredDraft ?: return@LaunchedEffect
        // Cleared LAST: this effect is keyed on the draft, so clearing it first would cancel
        // the very coroutine waiting out the delay.
        delay(300)
        if (draft.isComment) {
            // The parent comes from the toast: the reply has already left its tree (iOS ee5ec98).
            val parent = draft.commentParent
            if (parent != null) {
                restoredComposerText = draft.text
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
                replyComposerTarget = parent
            }
        } else {
            restoredComposerText = draft.text
            restoredComposerSegments = draft.threadSegments
            restoredPollOptions = draft.pollOptions
            // The target rides whole on the toast (iOS): it may have left memory since.
            val target = draft.quoteTarget
            if (target != null) quoteTarget = target else showComposer = true
        }
        viewModel.clearRestoredDraft()
    }
    var engagementTarget by remember { mutableStateOf<KaPostDraft?>(null) }
    // The X-style repost menu's Quote choice can be raised from ANY cell (feed, thread,
    // profile, bookmarks) - the VM relays it here where the quote composer lives.
    val quoteRequest by viewModel.quoteRequest.collectAsState()
    LaunchedEffect(quoteRequest) {
        quoteRequest?.let {
            quoteTarget = it
            viewModel.consumeQuoteRequest()
        }
    }
    var showMyProfile by remember { mutableStateOf(false) }
    // Notifications are the dock's Notifications tab (KaPostsNotificationsTab), not a screen
    // over the feed (iOS KaPosts 865c9ab).
    var showSearch by remember { mutableStateOf(false) }
    var followListKind by remember { mutableStateOf<Boolean?>(null) } // true = followers
    // The profile whose follow list is open: null = my own list, non-null = another user's.
    var followListPubkey by remember { mutableStateOf<String?>(null) }
    // Tip sheet target: the poster, the post tipped (null only for something that isn't on the
    // network yet) and, when an instant tip failed, why (iOS fae6686 `TipTarget.failure`).
    var tipTarget by remember { mutableStateOf<KaPostTipTarget?>(null) }
    val defaultTipSompi by settingsViewModel.kaPostsDefaultTipSompi.collectAsState()
    val defaultTipFiat by settingsViewModel.kaPostsDefaultTipFiat.collectAsState()
    // Tip In (KaPost > Settings, iOS f954738): what Tip sends.
    val tipAssetInUse by settingsViewModel.kaPostsTipAssetInUse.collectAsState()
    val tipHaptic = com.kachat.app.util.rememberHaptics()
    /**
     * Tipping a poster. With a default amount set (KaPosts Settings) it goes out at once,
     * through exactly the path the tip sheet's Send uses - the same contact creation, the same
     * funding source and destination rules as a payment in that person's chat. Without one, the
     * amount screen opens. An instant tip that doesn't go out says why in a toast
     * ([instantTipFailed]), never the tip screen (iOS b78fdb0).
     */
    val tipFailedContext = LocalContext.current
    /** An instant (default) tip that didn't go out says why in a toast, on the post: the tip
     *  screen, whose From and amount steps the default already settled, stays shut (iOS b78fdb0
     *  `instantTipFailed`). */
    fun instantTipFailed(message: String) {
        tipHaptic(com.kachat.app.util.IosHaptic.ERROR)
        viewModel.showTipToast(message, "", isFailure = true)
    }
    val tip: (KaPostDraft) -> Unit = tip@{ post ->
        // A second tap while this post's instant tip is going out would send a second tip, and
        // so would one while a tip that got no answer can still land (iOS ee5ec98).
        if (KaPostsTipHolds.isSending(post.id)) return@tip
        KaPostsTipHolds.heldMessage(post.id)?.let { held ->
            viewModel.showTipToast(held, "", isFailure = true)
            return@tip
        }
        val address = post.posterAddress
        val name = viewModel.posterDisplayName(address)
        // A tip names the post it is for, so the post counts it (KAPOSTS_INDEXER.md 5.11).
        val target = com.kachat.app.models.KaPostTip.of(post)
        // Tip In (KaPost > Settings, iOS f954738): kUSDT opens its send, Ask Each Time asks
        // first. Only a Kaspa tip can be instant.
        if (tipAssetInUse != com.kachat.app.repository.KaPostsTipAsset.KASPA) {
            tipTarget = KaPostTipTarget(address, name, target, asset = tipAssetInUse)
            return@tip
        }
        // A default tip set in a currency sends what it is worth in KAS right now (iOS KaPosts
        // 4c0d5a8); with no price yet, a toast says why.
        val fiat = defaultTipFiat
        val fiatText = fiat?.let { formatFiatAmount(it.first, it.second) }
        // Only a recent price: a tip that goes out without asking can't rest on an old one.
        val fiatPrice = fiat?.let { settingsViewModel.freshPriceIn(it.second) }?.takeIf { it > 0 }
        // Checked conversion: a huge saved amount at a tiny price is no real amount (iOS audit
        // 2026-11-21 #10).
        val converted = if (fiat != null && fiatPrice != null) kaPostTipSompi(fiat.first, fiatPrice) else null
        val amount = when {
            fiat != null && fiatPrice != null -> converted
            fiat != null -> null
            else -> defaultTipSompi
        }
        if (fiat != null && fiatPrice == null) {
            instantTipFailed(tipFailedContext.getString(R.string.feed54_tip_no_price, fiatText.orEmpty()))
        } else if (fiat != null && converted == null) {
            tipTarget = KaPostTipTarget(address, name, target, failure = KAPOST_INSTANT_TIP_LIMIT_NOTICE)
        } else if (amount == null || amount <= 0) {
            tipTarget = KaPostTipTarget(address, name, target)
        } else if (amount < KAPOST_INSTANT_TIP_MINIMUM_SOMPI) {
            // Under what the network takes, say from an older build that saved any amount
            // (iOS b78fdb0 `instantTipTooSmall`).
            instantTipFailed(tipFailedContext.getString(R.string.feed54_tip_too_small, kaPostInstantTipMinimumText()))
        } else if (amount > KAPOST_INSTANT_TIP_LIMIT_SOMPI) {
            // Instant tips go on one tap, with no swipe, so they stay small: a bigger one goes
            // through the swipe (iOS 369794e).
            tipTarget = KaPostTipTarget(address, name, target, failure = KAPOST_INSTANT_TIP_LIMIT_NOTICE)
        } else {
            chatViewModel.addContact(address, null)
            val kasText = kasAmountText(amount)
            KaPostsTipHolds.beginSending(post.id)
            scope.launch {
                // Tips From (KaPost Settings): another address than the chatting one tips
                // privately, so the payment names no post and nothing is counted (iOS 071e901).
                val sourceIndex = try {
                    val tipFrom = kaPostDefaultTipSource(settingsViewModel.kaPostsTipSourceIndex(), settingsViewModel, walletViewModel)
                    if (tipFrom == null) {
                        // One that can't be read must not quietly become a public tip from the
                        // chatting address: nothing is sent, and a toast says why (iOS b78fdb0).
                        KaPostsTipHolds.finish(post.id)
                        instantTipFailed(tipFailedContext.getString(R.string.feed54_tip_from_unreadable))
                        return@launch
                    }
                    tipFrom.spendingIndex
                } catch (e: kotlinx.coroutines.CancellationException) {
                    KaPostsTipHolds.finish(post.id)
                    throw e
                } catch (e: Exception) {
                    // Tips From couldn't be read: nothing is sent, rather than guessing the address.
                    KaPostsTipHolds.finish(post.id)
                    instantTipFailed(tipFailedContext.getString(R.string.feed54_tip_from_unreadable))
                    return@launch
                }
                val countsOnPost = sourceIndex == null
                viewModel.sendTip(
                    address = address,
                    amountSompi = amount,
                    sourceSpendingIndex = sourceIndex,
                    tip = if (countsOnPost) target else null,
                ) { outcome ->
                    val ok = outcome is com.kachat.app.services.KaPostTipSender.Outcome.Sent
                    val txId = (outcome as? com.kachat.app.services.KaPostTipSender.Outcome.Sent)?.txId
                    val error = (outcome as? com.kachat.app.services.KaPostTipSender.Outcome.Failed)?.message
                    if (outcome is com.kachat.app.services.KaPostTipSender.Outcome.Unknown) {
                        // It may have gone through: no amount screen, which would invite a second
                        // tip, and the post stays blocked after the send has returned.
                        val unconfirmed = tipFailedContext.getString(R.string.l53_send_outcome_unknown)
                        KaPostsTipHolds.hold(post.id, unconfirmed)
                        viewModel.showTipToast(unconfirmed, "")
                    } else if (ok) {
                        KaPostsTipHolds.finish(post.id)
                        if (countsOnPost) viewModel.countTip(target)
                        val amountText = fiatText?.let { "$kasText ${KaspaUnit.symbol} ($it)" } ?: "$kasText ${KaspaUnit.symbol}"
                        viewModel.showTipToast(
                            tipFailedContext.getString(
                                if (countsOnPost) R.string.feed54_tipped else R.string.feed54_tipped_privately,
                                amountText, name,
                            ),
                            txId.orEmpty(),
                        )
                    } else {
                        KaPostsTipHolds.finish(post.id)
                        // "Tip didn't send: " and the reason, as UserFacingError words it, in a
                        // toast (iOS b78fdb0).
                        instantTipFailed(
                            tipFailedContext.getString(
                                R.string.kaposts_tip_didnt_send,
                                com.kachat.app.util.UserFacingError.message(error, tipFailedContext.getString(R.string.something_went_wrong)),
                            ),
                        )
                    }
                }
            }
        }
    }
    var moderationKind by remember { mutableStateOf<Boolean?>(null) } // true = blocked
    var showBookmarks by remember { mutableStateOf(false) }
    var showKaPostsSettings by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<KaPostSavedDraft?>(null) }
    val draftContext = LocalContext.current
    // Drafts are per account and follow the open one (iOS f954738 `KaPostsDraftStore`): every
    // read and write goes by the account open at that moment, never one captured before a switch.
    val draftsAccount by viewModel.activeAddress.collectAsState()
    var drafts by remember { mutableStateOf(emptyList<KaPostSavedDraft>()) }
    fun reloadDrafts() { drafts = KaPostDraftStore.load(draftContext, viewModel.myAddress().orEmpty()) }
    // read off the main thread when the screen opens (audit L12)
    LaunchedEffect(draftsAccount) {
        val account = viewModel.myAddress().orEmpty()
        drafts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { KaPostDraftStore.load(draftContext, account) }
    }

    val posterProfile by viewModel.posterProfile.collectAsState()
    val deepLinkTxId by KaPostsDeepLink.pendingPostTxId.collectAsState()

    // One LazyListState per feed, hoisted here rather than remembered inside the faded page, so
    // each feed keeps its scroll offset across switches.
    val followingListState = rememberLazyListState()
    val feedListState = rememberLazyListState()
    val popularListState = rememberLazyListState()
    val feedListStates = remember(followingListState, feedListState, popularListState) {
        listOf(followingListState, feedListState, popularListState)
    }
    // Feed tapped on a dock: back to the top of the feed being read (iOS 45de945). Collected
    // rather than keyed on, since clearing the flag would restart a keyed effect and cancel the
    // scroll it had just begun.
    LaunchedEffect(rootMode) {
        if (rootMode != KaPostsRootMode.FEED) return@LaunchedEffect
        KaPostsDock.scrollFeedToTop.collect { pending ->
            if (!pending) return@collect
            KaPostsDock.scrollFeedToTop.value = false
            feedListStates[KaPostsFeedTabs.indexOf(viewModel.selectedFeed.value).coerceAtLeast(0)].animateScrollToItem(0)
        }
    }

    // The thread overlay renders inside this screen's own composition (not a Dialog window), so
    // the real dock floats over it, as it stays in reach on every KaPosts screen (iOS KaPosts
    // 865c9ab); the thread keeps its reply box clear of it (LocalDockInset).
    val threadOpen = threadStack.isNotEmpty()
    LaunchedEffect(threadOpen) {
        // Out of the thread entirely: the reply box starts empty next time (iOS closeThread).
        if (!threadOpen) threadReplyText.value = TextFieldValue("")
    }

    /** Already reading a thread: push, so Back returns here. Re-opening the post already on top
     *  is a no-op, so a double tap cannot stack it twice (iOS openDetail). */
    fun openThread(post: KaPostDraft) {
        if (threadStack.lastOrNull() != post.id) threadStack = threadStack + post.id
    }

    /** Opens where a resolved post lands: its own thread, or - for a reply - its parent's, scrolled
     *  to the reply. */
    fun openLanding(landing: KaPostsViewModel.ThreadLanding, focusReplyTxId: String? = null) {
        threadFocusReplyId = landing.scrollToRemoteId ?: focusReplyTxId
        openThread(landing.post)
    }

    /** Pops the topmost thread; if that entry was opened from a profile, re-opens the profile. */
    fun closeTopThread() {
        val closingIndex = threadStack.size - 1
        if (closingIndex < 0) return
        threadStack = threadStack.dropLast(1)
        threadFocusReplyId = null // never let a stale focus scroll some later thread
        val returnTo = profileReturns[closingIndex] ?: return
        profileReturns = profileReturns - closingIndex
        when (returnTo) {
            // On the Profile tab your profile never closed: it is the page under the thread.
            KaPostsProfileReturn.Mine -> if (rootMode == KaPostsRootMode.FEED) {
                showMyProfile = true
                viewModel.loadMyProfile()
            }
            is KaPostsProfileReturn.Poster -> viewModel.openPosterProfile(returnTo.address, returnTo.pubkey)
        }
    }

    /** Close-then-open (the profile Dialog covers the in-composition thread), remembering the way back. */
    fun openLandingFromProfile(returnTo: KaPostsProfileReturn, landing: KaPostsViewModel.ThreadLanding) {
        profileReturns = profileReturns + (threadStack.size to returnTo)
        when (returnTo) {
            KaPostsProfileReturn.Mine -> showMyProfile = false
            is KaPostsProfileReturn.Poster -> viewModel.closePosterProfile()
        }
        openLanding(landing)
    }

    /** A post tapped on a profile: a reply opens the post it answers, with the reply scrolled
     *  into view, the rule notifications and shared links follow (iOS openProfileDetail). */
    fun openThreadFromProfile(returnTo: KaPostsProfileReturn, post: KaPostDraft) {
        scope.launch { openLandingFromProfile(returnTo, viewModel.landingFor(post)) }
    }

    fun openShared(txId: String, focusReplyTxId: String? = null) {
        scope.launch {
            val landing = viewModel.openSharedPost(txId)
            if (landing != null) openLanding(landing, focusReplyTxId) else viewModel.showPostNotFound(txId)
        }
    }

    /** [openShared] for quoted embeds tapped inside a profile overlay: resolves the post first,
     *  then closes the profile and opens the thread with the way back remembered - without this
     *  the thread composed invisibly behind the profile's Dialog window. Not found: the profile
     *  just stays open (the feed's toast would be hidden behind the Dialog anyway). */
    fun openSharedFromProfile(returnTo: KaPostsProfileReturn, txId: String) {
        scope.launch {
            val landing = viewModel.openSharedPost(txId)
            if (landing != null) openLandingFromProfile(returnTo, landing)
        }
    }

    /** Jump to a rung of the chain above the open post: unwind the stack to it when it is on the
     *  path already, otherwise start a fresh stack there - the rungs above what was walked were
     *  resolved from the loaded tree, not navigated to (iOS jumpToAncestor). */
    fun jumpToAncestor(ancestor: KaPostDraft) {
        threadFocusReplyId = null
        val index = threadStack.indexOf(ancestor.id)
        if (index >= 0) {
            threadStack = threadStack.take(index + 1)
        } else {
            profileReturns = emptyMap()
            threadStack = listOf(ancestor.id)
            viewModel.reloadThread(ancestor)
        }
    }

    // Popular's sweep pages only while the Feed tab's feed is on screen and the dock is on Feed
    // (iOS b78fdb0 `popularSweepAllowed`, checked between passes). Before the load below, which
    // is what starts the sweep.
    DisposableEffect(viewModel, rootMode) {
        viewModel.setFeedOnScreen(rootMode == KaPostsRootMode.FEED)
        onDispose { viewModel.setFeedOnScreen(false) }
    }
    LaunchedEffect(viewModel) {
        // KaPost on screen: its dock slot, or open in Kaspa Hub (iOS 148a5331 `kaPostsOnScreen`).
        KaPostsDock.onScreen.collect { onScreen -> viewModel.setDockOnFeed(onScreen) }
    }
    LaunchedEffect(Unit) {
        // This view model shows a feed (Feed or Profile): it reloads on the indexer's return and
        // merges the chain feed, which the Notifications tab's own instance never does (iOS).
        viewModel.startFeedWork()
        // The Profile tab loads its own posts; the feed and the scheduled posts are the Feed tab's.
        if (rootMode == KaPostsRootMode.MY_PROFILE) {
            viewModel.loadMyProfile()
            viewModel.refreshTranslationLanguages()
            return@LaunchedEffect
        }
        // Posts a kill left unfinished are drafts now (iOS ee5ec98).
        viewModel.recoverHeldPosts()
        // Coming back to the tab keeps the feed and the place in it: page one is pulled only when
        // nothing is loaded or it has gone stale, otherwise just newer posts are looked for
        // (iOS c18fb77).
        viewModel.loadFeedIfNeeded()
        viewModel.refreshTranslationLanguages()
        // A post scheduled for a moment that has already passed, which the indexer never took,
        // goes out now - opening KaPosts is what sends it (iOS sends these on becoming active).
        viewModel.refreshScheduledPosts()
    }
    LaunchedEffect(deepLinkTxId, handlesDeepLinks) {
        if (!handlesDeepLinks) return@LaunchedEffect
        val txId = deepLinkTxId ?: return@LaunchedEffect
        val focusReplyTxId = KaPostsDeepLink.pendingFocusReplyTxId.value
        KaPostsDeepLink.pendingPostTxId.value = null
        KaPostsDeepLink.pendingFocusReplyTxId.value = null
        // "" is the tab-only sentinel (a notification with no target txid): MainShell opens the
        // Notifications tab for it (iOS KaPosts KaPostsTabView), so there is nothing to open here.
        if (txId.isNotEmpty()) {
            KaPostsDeepLink.pendingOpenNotifications.value = false
            openShared(txId, focusReplyTxId)
        }
    }

    // A pre-written post from another app opens the new-post composer. A composer already open
    // keeps what is being written, and the link's post opens when it closes. A confirmed 0 KAS
    // gets the funding card, as the compose button does (iOS 9e22075 presentPendingComposeLink).
    val pendingComposeText by KaPostsDeepLink.pendingComposeText.collectAsState()
    LaunchedEffect(pendingComposeText, handlesDeepLinks, showComposer, quoteTarget, replyComposerTarget) {
        if (!handlesDeepLinks) return@LaunchedEffect
        val text = pendingComposeText ?: return@LaunchedEffect
        if (showComposer || quoteTarget != null || replyComposerTarget != null) return@LaunchedEffect
        KaPostsDeepLink.pendingComposeText.value = null
        if (fundingGate.active) {
            showFundingGate = true
        } else {
            restoredComposerText = text
            restoredComposerSegments = emptyList()
            restoredPollOptions = emptyList()
            showComposer = true
        }
    }

    // Someone's profile from outside the feed - a profile link, or View Profile on a public chat
    // member - opens over the feed (iOS KaPosts `.openKaPostsProfile`).
    val pendingProfileAddress by KaPostsDeepLink.pendingProfileAddress.collectAsState()
    LaunchedEffect(pendingProfileAddress, handlesDeepLinks) {
        if (!handlesDeepLinks) return@LaunchedEffect
        val address = pendingProfileAddress ?: return@LaunchedEffect
        KaPostsDeepLink.pendingProfileAddress.value = null
        threadStack = emptyList()
        viewModel.openPosterProfile(address, null)
    }

    // The scheduled-post reminder tapped: the Scheduled list, which sends what is due as it
    // opens (iOS KaPostsView `pendingOpenScheduled` -> `menuSheet = .scheduled`).
    val pendingOpenScheduled by KaPostsDeepLink.pendingOpenScheduled.collectAsState()
    LaunchedEffect(pendingOpenScheduled, handlesDeepLinks) {
        if (!handlesDeepLinks || !pendingOpenScheduled) return@LaunchedEffect
        KaPostsDeepLink.pendingOpenScheduled.value = false
        showScheduled = true
    }

    // A tab tapped on the dock, or on its copy over one of these screens (KaPostsDock): every
    // full-screen KaPosts screen closes at once, so the tab is what shows. A reply typed under a
    // thread is kept, as it is when the thread is closed with Back (iOS closeAllCovers).
    LaunchedEffect(Unit) {
        KaPostsDock.closeCovers.collect {
            threadStack = emptyList()
            profileReturns = emptyMap()
            threadFocusReplyId = null
            showMyProfile = false
            viewModel.closePosterProfile()
            showSearch = false
            followListKind = null
            engagementTarget = null
            moderationKind = null
            showKaPostsSettings = false
            showBookmarks = false
            showDrafts = false
            showScheduled = false
        }
    }

    // The repost icon's plain tap only reaches here for a post with no txid yet (on-chain posts
    // open the Repost/Quote sheet instead): that just flips the flag locally, as on iOS.
    val repostHandler: (KaPostDraft) -> Unit = { post -> viewModel.scheduleRepost(post) }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = colors.background,
        // As its own tab the feed runs on under the dock (LocalDockInset) and pads its own end;
        // inside the Hub the shell lays it out above the dock and the inset is 0. The status bar
        // stays the Scaffold's to clear: the feed's header has no padding of its own for it.
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = fab@{
            // On your profile too (the dock's Profile tab, owner's call, iOS 148a5331): post from there.
            if (rootMode != KaPostsRootMode.FEED && rootMode != KaPostsRootMode.MY_PROFILE) return@fab
            // iOS createPostButton: a 56pt circle in the material fill with an outlined
            // square.and.pencil in accent (22pt semibold, so drawn a size up from the icon row's),
            // not a filled accent button - the chat list's create-chat button shape.
            //
            // iOS sits it 16pt above the tab bar: lifted by the dock the feed runs under, or with
            // the dock hidden (a thread is open) by the system bar alone.
            val dockHidden by walletViewModel.hideBottomBar.collectAsState()
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = if (dockHidden) navBarBottom else dockInset),
            ) {
                // Back to the top of the feed (iOS 5d731967 `backToTopButton`, owner 2026-11-27):
                // the chat's jump-to-newest button turned up, above the compose button, once the
                // feed's top has scrolled away.
                if (rootMode == KaPostsRootMode.FEED) {
                    val feedState = feedListStates[KaPostsFeedTabs.indexOf(selectedFeed).coerceAtLeast(0)]
                    val awayFromTop by remember(feedState) { derivedStateOf { feedAwayFromTop(feedState.firstVisibleItemIndex) } }
                    val backToTopScope = rememberCoroutineScope()
                    val backToTopHaptic = com.kachat.app.util.rememberHaptics()
                    androidx.compose.animation.AnimatedVisibility(
                        visible = awayFromTop,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
                    ) {
                        val backToTopLabel = stringResource(R.string.kpui_back_to_top)
                        Box(
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .size(40.dp)
                                .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                                .clip(CircleShape)
                                .background(colors.surface.copy(alpha = 0.92f))
                                .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable {
                                    backToTopHaptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                    backToTopScope.launch { feedState.animateScrollToItem(0) }
                                }
                                .semantics { contentDescription = backToTopLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                com.kachat.app.ui.theme.IosGlassFab(
                    onClick = { if (fundingGate.active) showFundingGate = true else showComposer = true },
                    icon = SquareAndPencilLarge,
                    contentDescription = stringResource(R.string.kpui_new_post),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (rootMode == KaPostsRootMode.MY_PROFILE) {
                // The Profile tab: your own profile at the root, under the dot and the balance.
                Column(modifier = Modifier.fillMaxSize()) {
                    // Every notification, KaPost's and the wallet's, behind the bell top right
                    // (iOS 148a5331 `AllNotificationsBell`); Help moved to Settings.
                    // The account switcher top left, your picture with a switch symbol (iOS
                    // 824a30bb, owner 2026-11-26: swapped back); Edit Profile and Log Out stay
                    // under your name
                    var showTitleShare by remember { mutableStateOf(false) }
                    val myAddress = viewModel.myAddress() ?: ""
                    KaPostsOverlayStatusBar(
                        leading = if (myAddress.isEmpty()) null else ({
                            ProfileAccountChip(address = myAddress)
                        }),
                        trailing = { AllNotificationsBell(onClick = { showAllNotifications = true }) },
                    )
                    // Share Profile beside the title (iOS 824a30bb `ProfileShareChip`)
                    KaPostsFixedTitle(
                        stringResource(R.string.profile),
                        onOpenBackup = { navController.navigate("account_backup") },
                        accessory = if (myAddress.isEmpty()) null else ({
                            ProfileShareChip(onClick = { showTitleShare = true })
                        }),
                    )
                    if (showTitleShare && myAddress.isNotEmpty()) {
                        ShareProfileSheet(
                            address = myAddress,
                            shareUrl = KaChatLink.profileWebUrl(myAddress),
                            onDismiss = { showTitleShare = false },
                        )
                    }
                    KaPostsProfilePage(
                        address = viewModel.myAddress() ?: "",
                        pubkey = null,
                        isMine = true,
                        viewModel = viewModel,
                        navController = navController,
                        onClose = {},
                        onOpenThread = { openThreadFromProfile(KaPostsProfileReturn.Mine, it) },
                        onRepostTap = { repostHandler(it) },
                        onViewEngagement = { engagementTarget = it },
                        onOpenQuoted = { openSharedFromProfile(KaPostsProfileReturn.Mine, it) },
                        onOpenFollowList = { followListPubkey = null; followListKind = it },
                        onReply = { replyComposerTarget = it },
                        bottomInset = dockInset,
                        onSeeAllPosts = onSeeAllPosts,
                        shareInHeaderRow = false,
                    )
                }
            } else Column(modifier = Modifier.fillMaxSize()) {
                // Top chrome mirrors iOS's KaPostsPageView navigation bar: centered balance +
                // clickable connection dot trailing (ConnectionStatusIndicator / BalanceToolbarLabel
                // toolbar items), then the bold left-aligned large title, then the hamburger
                // inline with the three feed tabs (KaPostsView.feedTabBar).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    // No connection dot (iOS 518fdc8c, owner 2026-11-23: only at the top of
                    // Settings and on the dock's Settings icon); the bar keeps its 44 height.
                    Spacer(Modifier.size(44.dp).align(Alignment.CenterStart))
                    BalanceTopBarLabel(modifier = Modifier.align(Alignment.Center))
                }
                // No backup strip here: it's on Profile only (iOS 518fdc8c, owner 2026-11-23).
                // The hamburger before the title, which is fixed chrome above the feed so a
                // pull-to-refresh moves nothing but the feed (iOS KaPosts feedMenuButton), and the
                // feed button after it (iOS `feedCycleButton`).
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    KaPostsFeedMenuButton(
                        onSelect = { item ->
                            when (item) {
                                KaPostsFeedMenuItem.SEARCH -> showSearch = true
                                KaPostsFeedMenuItem.DRAFTS -> {
                                    drafts = KaPostDraftStore.load(draftContext, viewModel.myAddress().orEmpty())
                                    showDrafts = true
                                }
                                KaPostsFeedMenuItem.BOOKMARKS -> showBookmarks = true
                                KaPostsFeedMenuItem.MUTED -> moderationKind = false
                                KaPostsFeedMenuItem.BLOCKED -> moderationKind = true
                                // The overlay's own LaunchedEffect sends and refreshes (iOS `.task`).
                                KaPostsFeedMenuItem.SCHEDULED -> showScheduled = true
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.tab_posts),
                        color = colors.textPrimary,
                        fontSize = LargeTitleSize,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    // One button for the three feeds (owner's call, iOS 518fdc8c: it replaced the
                    // Following / Feed / Popular tabs): each tap fades to the next.
                    CycleCapsuleButton(
                        options = KaPostsFeedTabs,
                        selection = selectedFeed,
                        onSelect = { viewModel.selectFeed(it) },
                        title = { it.label() },
                        hint = stringResource(R.string.cycle_hint_next_feed),
                    )
                }
                HorizontalDivider(color = colors.surfaceVariant)
                // The indexer is what lists posts: while it's away the feed says why it isn't
                // complete (iOS ee5ec98 `indexerDownNotice`).
                val indexerDown by viewModel.indexerIsDown.collectAsState()
                if (indexerDown) {
                    Text(
                        stringResource(R.string.kaposts_server_not_answering),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.background)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                // Following, Feed and Popular: the one picked by the feed button beside the title,
                // fading in (owner's call, iOS 518fdc8c: no sideways slide or swipe). Each keeps
                // its place (the list states are hoisted above) and its loaded posts (the view
                // model's), so a feed comes back where it was left.
                CycleFade(
                    target = selectedFeed,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { tab ->
                    val page = KaPostsFeedTabs.indexOf(tab).coerceAtLeast(0)
                    // Each tab reads only its own stream; Popular arrives already ranked
                    // ([KaPostsViewModel.popularPosts], sorted on Dispatchers.Default). Collected
                    // only while its page is composed, so the 300-post rank doesn't run on every
                    // feed change while another tab is on screen.
                    val pageFeed = when (tab) {
                        KaPostsViewModel.FeedTab.FOLLOWING -> visibleFollowingPosts
                        KaPostsViewModel.FeedTab.FEED -> visiblePosts
                        KaPostsViewModel.FeedTab.POPULAR -> viewModel.popularPosts.collectAsState().value
                    }
                    val feedPaging = pagingStateOf(
                        viewModel,
                        if (tab == KaPostsViewModel.FeedTab.FOLLOWING) {
                            KaPostsViewModel.PAGE_FOLLOWING_FEED
                        } else {
                            KaPostsViewModel.PAGE_GLOBAL_FEED
                        },
                    )
                    // Pull-to-refresh replaces the old header refresh button, wired to the same
                    // page-one reload. Await-then-endRefresh pattern (the pull joins its own
                    // load's completion; loadFeed has no throwing path out, so the spinner
                    // always ends) rather than keying off isLoadingFeed, which background
                    // reloads also drive.
                    val pullRefreshState = rememberPullToRefreshState()
                    LaunchedEffect(pullRefreshState.isRefreshing) {
                        if (pullRefreshState.isRefreshing) {
                            viewModel.loadFeed(tab)
                            pullRefreshState.endRefresh()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // An idle PullToRefreshContainer "hides" by translating a full
                            // container-height above its own position without clipping - on this
                            // screen that band is the tab row, so clip the indicator to this Box:
                            // invisible at rest, revealed only by a real pull.
                            .clipToBounds()
                            .nestedScroll(pullRefreshState.nestedScrollConnection),
                    ) {
                    val pendingNew by viewModel.pendingNewPosts.collectAsState()
                    val newPostsScope = rememberCoroutineScope()
                    // Only while this feed is the one on screen: the effect is torn down when the
                    // feed button moves on, when KaPost is a hidden Hub section and while the app
                    // is in the background, so nothing polls for a feed nobody is looking at. It
                    // wakes on the shared beat (audit 2026-11-25 R3, B10).
                    val feedRunning = com.kachat.app.ui.screens.rememberScreenRunning()
                    if (tab == selectedFeed && feedRunning) {
                        LaunchedEffect(tab) {
                            var lastCheck = System.currentTimeMillis()
                            while (true) {
                                com.kachat.app.services.ForegroundBeat.sleepUntilNextBeat(offsetMs = 1_000L)
                                val now = System.currentTimeMillis()
                                if (com.kachat.app.ui.screens.dueOnBeat(lastCheck, now, viewModel.newPostsCheckIntervalMs)) {
                                    lastCheck = now
                                    viewModel.checkForNewPosts(tab)
                                }
                            }
                        }
                    }
                    if (feedError != null && pageFeed.isEmpty()) {
                        // Wrapped in a LazyColumn purely so pull-to-refresh works on an error
                        // tab too (same reason iOS wraps its empty feeds in a ScrollView).
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize()) {
                                    FeedEmptyState(
                                        title = stringResource(R.string.kpui_couldnt_load_feed),
                                        body = feedError ?: "",
                                        actionLabel = stringResource(R.string.kaposts_retry),
                                        onAction = { viewModel.refresh() },
                                    )
                                }
                            }
                        }
                    } else if (pageFeed.isEmpty() && !isLoading) {
                        // Same LazyColumn wrapper: an empty feed must still be pullable - the
                        // common bootstrap case while feeds are sparse.
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxHeight(0.8f).fillMaxWidth()) {
                                    FeedEmptyState(
                                        // iOS emptyStateIcon: person.2, square.and.pencil and flame,
                                        // all outline symbols.
                                        icon = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> Icons.Outlined.Group
                                            KaPostsViewModel.FeedTab.FEED -> SquareAndPencil
                                            KaPostsViewModel.FeedTab.POPULAR -> Icons.Outlined.LocalFireDepartment
                                        },
                                        title = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> stringResource(R.string.kpui_nothing_from_following)
                                            KaPostsViewModel.FeedTab.FEED -> stringResource(R.string.l53_no_posts_yet)
                                            KaPostsViewModel.FeedTab.POPULAR -> stringResource(R.string.kpui_nothing_trending)
                                        },
                                        body = when (tab) {
                                            KaPostsViewModel.FeedTab.FOLLOWING -> stringResource(R.string.kpui_following_empty_body)
                                            KaPostsViewModel.FeedTab.FEED -> stringResource(R.string.kpui_feed_empty_body)
                                            KaPostsViewModel.FeedTab.POPULAR -> stringResource(R.string.kpui_popular_empty_body)
                                        },
                                        actionLabel = null,
                                        onAction = {},
                                    )
                                }
                            }
                            // Everything fetched so far was filtered away (all muted, or - on
                            // Following - none of it from accounts you follow locally) while the
                            // server still has older pages. No auto-sentinel here: with nothing on
                            // screen it would walk the whole history unattended, so this stays an
                            // explicit tap (iOS "Load older posts").
                            item {
                                if (!feedPaging.isLoadingMore && feedPaging.hasMore && feedPaging.cursor != null) {
                                    Text(
                                        stringResource(R.string.kpui_load_older_posts),
                                        color = KaspaTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.loadMoreFeed(tab, manual = true) }
                                            .padding(vertical = 12.dp),
                                    )
                                } else if (feedPaging.isLoadingMore) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KaspaTeal)
                                    }
                                }
                            }
                        }
                    } else {
                        // Endless scroll, per tab. Each tab keeps its own cursor in the view
                        // model, so switching away and back resumes exactly where it was. The feed
                        // fading out is drawn for a moment too; only the feed being read pulls in
                        // more.
                        EndlessScroll(listState = feedListStates[page], key = tab) {
                            if (tab == viewModel.selectedFeed.value) viewModel.loadMoreFeed(tab)
                        }
                        NewPostsPill(
                            count = pendingNew.size,
                            onClick = {
                                viewModel.showPendingNewPosts(tab)
                                newPostsScope.launch { feedListStates[page].animateScrollToItem(0) }
                            }
                        )
                        LazyColumn(
                            // Hoisted per tab so each feed keeps its own scroll position when you
                            // switch away and back (the fade disposes the feed it leaves).
                            state = feedListStates[page],
                            modifier = Modifier.fillMaxSize(),
                            // The last post scrolls clear of the dock the feed runs under.
                            contentPadding = PaddingValues(bottom = dockInset),
                        ) {
                            // Holds a reader at the top on the newest post (E1, see FEED_TOP_ANCHOR_INDEX).
                            item(key = FEED_TOP_ANCHOR_KEY, contentType = FEED_TOP_ANCHOR_KEY) {
                                Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { 1.toDp() }))
                            }
                            items(pageFeed, key = { it.id }, contentType = { "feed-post" }) { post ->
                                // Thread-root probe: once per commented post, so "View thread"
                                // can appear on other people's threads too. Only in the feed being
                                // read: the pages beside it are drawn too (iOS e5a9b11). The tab is
                                // watched here, not read in the row, so switching tabs doesn't
                                // redraw every row (audit L14); and only a row that stays on
                                // screen half a second asks, so a fling past sends nothing (M13).
                                LaunchedEffect(post.remoteId) {
                                    viewModel.selectedFeed.map { it == tab }.distinctUntilChanged().collectLatest { isReadFeed ->
                                        if (!isReadFeed) return@collectLatest
                                        delay(THREAD_PROBE_VISIBLE_MS)
                                        viewModel.probeThreadRoot(post)
                                    }
                                }
                                KaPostCell(
                                    post = post,
                                    viewModel = viewModel,
                                    onOpenThread = { openThread(post) },
                                    onRepostTap = { repostHandler(post) },
                                    onOpenProfile = { viewModel.openPosterProfile(post.posterAddress, post.posterPubkey) },
                                    onOpenQuoted = { txId -> openShared(txId) },
                                    onViewEngagement = { engagementTarget = post },
                                    truncatesLongText = true,
                                    // The reply bubble opens the Reply composer; the card opens
                                    // the thread (iOS feed cells).
                                    onReply = { replyComposerTarget = post },
                                    onTip = { tip(post) },
                                )
                                // X-style "View thread" under a thread root - opens the detail,
                                // where the full continuation renders as a connected section.
                                // Sliced per row (see collectSelectedAsState): a probe result
                                // arriving for ONE post recomposes that row alone, not every
                                // visible row - the previous per-tab collection still put the
                                // whole maps into every item lambda's captures, so each probe
                                // hit re-ran the whole viewport mid-scroll.
                                val isThreadRoot by remember(post.id, post.remoteId) {
                                    combine(viewModel.localThreadRoots, viewModel.threadRootFlags) { locals, flags ->
                                        post.id in locals ||
                                            (post.remoteId != null && flags[post.remoteId] == true)
                                    }.distinctUntilChanged()
                                }.collectAsState(initial = viewModel.isThreadRoot(post))
                                if (isThreadRoot) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable { openThread(post) }
                                            .padding(start = 68.dp, top = 2.dp, bottom = 8.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = null,
                                            tint = KaspaTeal,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        // iOS: 6pt apart, the label in caption semibold.
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.kpui_view_thread), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(
                                    color = colors.surfaceVariant,
                                    modifier = Modifier.padding(start = 68.dp),
                                )
                            }
                            pagingFooter(feedPaging, keySuffix = "feed-$page") {
                                viewModel.loadMoreFeed(tab, manual = true)
                            }
                        }
                    }
                    // Hard guarantee on top of the clipToBounds above: the indicator only
                    // composes while a pull is in progress or a refresh runs, so no layout
                    // change can ever park the resting circle over the feed.
                    if (pullIndicatorShown(pullRefreshState)) {
                        PullToRefreshContainer(
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    }
                }
            }

            // Bottom toast stack: undo countdown above the on-chain confirmation (iOS pads 84).
            KaPostsToastLayer(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
            )
        }
    }

    // Also conditioned on the gate itself so the dialog vanishes reactively the moment the
    // chatting balance confirms as funded (funds land while it's open).
    // ...but not under its own Buy Kaspa flow, which would close with it (iOS 824a30bb)
    if (showFundingGate && (fundingGate.active || com.kachat.app.ui.screens.tokens.BuyKaspaFlowPresence.isOpen)) {
        ZeroBalanceFundingDialog(
            walletAddress = fundingGate.chattingAddress,
            onDismiss = { showFundingGate = false },
        )
    }

    if (showComposer) {
        val context = LocalContext.current
        val is24Hour = com.kachat.app.util.is24HourClock()
        KaPostComposerDialog(
            title = stringResource(R.string.kpui_new_post),
            quoted = null,
            initialText = restoredComposerText,
            initialThreadSegments = restoredComposerSegments,
            initialPollOptions = restoredPollOptions,
            onDismiss = {
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
            },
            onSubmit = { text ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
                viewModel.schedulePost(text)
            },
            viewModel = viewModel,
            onSubmitThread = { segments ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
                viewModel.scheduleThread(segments)
            },
            onSubmitPoll = { question, options, closesAtMs ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
                viewModel.createPoll(question, options, closesAtMs)
            },
            onSchedulePost = { text, notBeforeMs ->
                showComposer = false
                restoredComposerText = ""
                restoredComposerSegments = emptyList()
                restoredPollOptions = emptyList()
                val whenText = formatScheduledTime(notBeforeMs, is24Hour)
                viewModel.schedulePostForLater(
                    text,
                    notBeforeMs,
                    scheduled = context.getString(R.string.kaposts_scheduled_for_toast, whenText),
                    scheduledOnPhone = context.getString(R.string.kaposts_scheduled_for_on_phone_toast, whenText),
                    couldNotSchedule = context.getString(R.string.kaposts_couldnt_schedule_toast),
                )
            },
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(draftContext, viewModel.myAddress().orEmpty(), null, draftText, segments)
                reloadDrafts()
            },
        )
    }

    // Reopening a draft, as whatever it was written as. A reply or quote draft keeps only the
    // txid of the post it was about (never a stale copy of someone else's post), so that post
    // is resolved here - memory first, then the indexer, then the chain - and handed to the
    // composer (iOS draftComposer). Posting it removes it; re-saving updates it in place.
    editingDraft?.let { draft ->
        val sourceId = draft.replyRemoteId?.takeIf { it.isNotEmpty() } ?: draft.quotedRemoteId?.takeIf { it.isNotEmpty() }
        LaunchedEffect(draft.id, sourceId) {
            draftSourcePost = null
            draftSourceFailedId = null
            if (sourceId != null) {
                val resolved = viewModel.resolveAnyPost(sourceId)
                draftSourcePost = resolved
                if (resolved == null) draftSourceFailedId = sourceId
            }
        }
        val isReplyDraft = !draft.replyRemoteId.isNullOrEmpty()
        val isQuoteDraft = !isReplyDraft && !draft.quotedRemoteId.isNullOrEmpty()
        val hasSource = sourceId != null
        // Only the post this draft names: the previous draft's can linger for a moment.
        val source = draftSourcePost?.takeIf { hasSource && it.remoteId == sourceId }
        // A reply or quote is never posted as a plain post: Post stays off until its source loads
        // (iOS ee5ec98).
        val sourceNotice: String? = when {
            !hasSource || source != null -> null
            draftSourceFailedId == sourceId -> stringResource(R.string.kaposts_draft_source_missing)
            else -> stringResource(R.string.kaposts_draft_source_loading)
        }
        KaPostComposerDialog(
            title = when {
                isReplyDraft -> stringResource(R.string.kpui_reply_to_post)
                isQuoteDraft && source != null -> stringResource(R.string.kpui_quote_post)
                else -> stringResource(R.string.kpui_new_post)
            },
            quoted = source,
            quotedDisplayName = source?.let { viewModel.posterDisplayName(it.posterAddress) } ?: "",
            isReply = isReplyDraft,
            submitLabel = if (isReplyDraft) stringResource(R.string.reply) else null,
            onDismiss = { editingDraft = null; draftSourcePost = null },
            onSubmit = onSubmit@{ text ->
                if (hasSource && source == null) return@onSubmit
                KaPostDraftStore.delete(draftContext, viewModel.myAddress().orEmpty(), draft.id)
                editingDraft = null
                when {
                    source != null && isReplyDraft -> viewModel.submitReply(source, text)
                    source != null && isQuoteDraft -> viewModel.scheduleQuote(source, text)
                    else -> viewModel.schedulePost(text)
                }
                draftSourcePost = null
            },
            sourceNotice = sourceNotice,
            viewModel = viewModel,
            // A reply or a quote is one post about one other, so it never stacks into a thread.
            onSubmitThread = if (isReplyDraft || isQuoteDraft) null else { segments ->
                KaPostDraftStore.delete(draftContext, viewModel.myAddress().orEmpty(), draft.id)
                editingDraft = null
                viewModel.scheduleThread(segments)
            },
            editingDraftId = draft.id,
            initialText = draft.text,
            initialThreadSegments = draft.threadSegments,
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, viewModel.myAddress().orEmpty(), draft.id, draftText, segments,
                    replyRemoteId = draft.replyRemoteId,
                    quotedRemoteId = draft.quotedRemoteId,
                )
                reloadDrafts()
            },
        )
    }

    if (showScheduled) {
        KaPostsScheduledOverlay(viewModel = viewModel, onClose = { showScheduled = false })
    }

    if (showDrafts) {
        KaPostsOverlayScaffold(title = stringResource(R.string.kpui_drafts), onClose = { showDrafts = false }) {
            if (drafts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Edit, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.kpui_no_drafts), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.kpui_no_drafts_body),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            } else {
                LazyColumn {
                    items(drafts, key = { it.id }) { draft ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDrafts = false
                                    editingDraft = draft
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    draft.preview,
                                    color = LocalAppColors.current.textPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // When it was saved, and how many posts it stacks (iOS row).
                                    remember(draft.savedAt) { relativePostTime(draft.savedAt) } +
                                        (if (draft.segmentCount > 1) " " + stringResource(R.string.kpui_dot_n_posts, draft.segmentCount) else ""),
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.kpui_delete_draft),
                                tint = LocalAppColors.current.textSecondary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        KaPostDraftStore.delete(draftContext, viewModel.myAddress().orEmpty(), draft.id)
                                        reloadDrafts()
                                    },
                            )
                        }
                        HorizontalDivider(color = LocalAppColors.current.surfaceVariant)
                    }
                }
            }
        }
    }

    replyComposerTarget?.let { target ->
        KaPostComposerDialog(
            title = stringResource(R.string.kpui_reply_to_post),
            // The post being answered renders under the editor - the same card a quote shows.
            quoted = target,
            quotedDisplayName = viewModel.posterDisplayName(target.posterAddress),
            isReply = true,
            submitLabel = stringResource(R.string.reply),
            initialText = restoredComposerText,
            onDismiss = { replyComposerTarget = null; restoredComposerText = "" },
            onSubmit = { text ->
                replyComposerTarget = null
                restoredComposerText = ""
                viewModel.submitReply(target, text)
            },
            viewModel = viewModel,
            // A reply draft remembers WHAT it answers, so reopening it brings the post back.
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, viewModel.myAddress().orEmpty(), null, draftText, segments,
                    replyRemoteId = target.remoteId,
                )
                reloadDrafts()
            },
        )
    }

    quoteTarget?.let { target ->
        KaPostComposerDialog(
            title = stringResource(R.string.kpui_quote_post),
            quoted = target,
            quotedDisplayName = viewModel.posterDisplayName(target.posterAddress),
            initialText = restoredComposerText,
            onDismiss = { quoteTarget = null; restoredComposerText = "" },
            onSubmit = { text ->
                quoteTarget = null
                restoredComposerText = ""
                viewModel.scheduleQuote(target, text)
            },
            // A quote is never a thread, so no segments to carry here.
            viewModel = viewModel,
            // A quote draft remembers WHAT it quotes, so reopening it brings the post back (iOS).
            onSaveDraft = { draftText, segments ->
                KaPostDraftStore.save(
                    draftContext, viewModel.myAddress().orEmpty(), null, draftText, segments,
                    quotedRemoteId = target.remoteId,
                )
                reloadDrafts()
            },
        )
    }

    // Thread stack - the topmost id renders; back pops. The overlay resolves the id against the
    // live post tree itself (it must recompose as replies land), so only the id is handed over.
    val fetchedAncestorChains by viewModel.fetchedAncestors.collectAsState()
    // Which way the thread stack last moved: opening a comment from inside a thread slides the
    // new level in over the one beneath, while backing out simply reveals the level below rather
    // than sliding it in from the right again (iOS 5e1da72).
    var lastThreadDepth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val openingDeeper = threadStack.size > lastThreadDepth
    LaunchedEffect(threadStack.size) { lastThreadDepth = threadStack.size }

    threadStack.lastOrNull()?.let { topId ->
        val topPost = viewModel.findPost(topId)
        // The chain get-thread returns, which is every level above this post - exact whether
        // you tapped down to it, opened it from a profile, or landed on it from a link. The
        // navigation stack plus an in-memory parent walk is the fallback for the moment before
        // that fetch answers (and for a local post that has no txid yet). Memoised: the walk runs
        // findParent - a recursive search of every loaded list - several times, and this screen
        // recomposes far more often than the inputs change (iOS AncestorChainMemo).
        val ancestors = remember(topPost, threadStack, fetchedAncestorChains) {
            topPost?.remoteId
                ?.let { fetchedAncestorChains[it] }
                ?.takeIf { it.isNotEmpty() }
                ?: topPost?.let { ancestorsFromMemory(viewModel, it, threadStack) }
                ?: emptyList()
        }
        key(topId) {
        KaPostThreadOverlay(
            postId = topId,
            slidesIn = openingDeeper,
            viewModel = viewModel,
            ancestors = ancestors,
            onJumpToAncestor = { ancestor -> jumpToAncestor(ancestor) },
            onReplyToComment = { target -> replyComposerTarget = target },
            onClose = { closeTopThread() },
            onOpenNested = { nested -> openThread(nested) },
            onOpenProfile = { address, pubkey -> viewModel.openPosterProfile(address, pubkey) },
            onOpenShared = { txId -> openShared(txId) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            focusReplyRemoteId = threadFocusReplyId,
            onFocusReplyHandled = { threadFocusReplyId = null },
            replyText = threadReplyText,
        )
        }
    }

    if (showMyProfile) {
        KaPostsProfileOverlay(
            address = viewModel.myAddress() ?: "",
            pubkey = null,
            isMine = true,
            viewModel = viewModel,
            navController = navController,
            onClose = { showMyProfile = false },
            // Close the profile dialog FIRST: the thread overlay composes inside the screen,
            // which a Dialog window always covers — without this the thread opened invisibly
            // behind the profile (same close-then-open pattern as the bookmarks overlay).
            // openThreadFromProfile also remembers the way back, so closing that thread
            // returns here instead of dumping the user on the feed.
            onOpenThread = { openThreadFromProfile(KaPostsProfileReturn.Mine, it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openSharedFromProfile(KaPostsProfileReturn.Mine, it) },
            onOpenFollowList = { followListPubkey = null; followListKind = it },
            onReply = { replyComposerTarget = it },
        )
    }

    posterProfile?.let { profile ->
        KaPostsProfileOverlay(
            address = profile.address,
            pubkey = profile.pubkey,
            isMine = false,
            viewModel = viewModel,
            navController = navController,
            onClose = { viewModel.closePosterProfile() },
            // Same close-then-open as the my-profile/bookmarks overlays: the thread composes
            // behind this Dialog window, so commenting from a profile showed nothing. The
            // helper also remembers the way back, so closing that thread returns to this
            // profile instead of dumping the user on the feed.
            onOpenThread = { openThreadFromProfile(KaPostsProfileReturn.Poster(profile.address, profile.pubkey), it) },
            onRepostTap = { repostHandler(it) },
            onViewEngagement = { engagementTarget = it },
            onOpenQuoted = { openSharedFromProfile(KaPostsProfileReturn.Poster(profile.address, profile.pubkey), it) },
            onOpenFollowList = { followListPubkey = profile.pubkey; followListKind = it },
            onTip = { tip(it) },
            onReply = { replyComposerTarget = it },
        )
    }

    tipTarget?.let { target ->
        KaPostTipFlow(
            address = target.address,
            displayName = target.name,
            tip = target.tip,
            initialError = target.failure,
            asset = target.asset,
            viewModel = viewModel,
            onTipped = { viewModel.countTip(target.tip) },
            onDismiss = { tipTarget = null },
        )
    }
    KaPostsActionErrorAlert(viewModel)

    if (showSearch) {
        KaPostsSearchOverlay(
            viewModel = viewModel,
            onClose = { showSearch = false },
            onOpenPost = { txId ->
                showSearch = false
                // The same route a shared link takes: a search result can be older than the
                // loaded feed, and openShared is what knows how to go and find one.
                openShared(txId)
            },
            onOpenProfile = { address, pubkey ->
                showSearch = false
                // the pubkey from the author's posts: without it the profile can't load their
                // posts, replies or counts, nor follow on chain
                viewModel.openPosterProfile(address, pubkey)
            },
        )
    }

    followListKind?.let { followers ->
        KaPostsFollowListOverlay(
            followers = followers,
            targetPubkey = followListPubkey,
            viewModel = viewModel,
            onClose = { followListKind = null },
        )
    }

    engagementTarget?.let { post ->
        KaPostEngagementOverlay(
            post = post,
            viewModel = viewModel,
            onClose = { engagementTarget = null },
        )
    }

    moderationKind?.let { blocked ->
        KaPostsModerationOverlay(
            blocked = blocked,
            viewModel = viewModel,
            onClose = { moderationKind = null },
        )
    }

    if (showKaPostsSettings) {
        KaPostsSettingsOverlay(onClose = { showKaPostsSettings = false })
    }
    if (showAllNotifications) {
        // a sheet, so it has no back arrow (owner's call, iOS 148a5331)
        AllNotificationsSheet(
            navController = navController,
            onDismiss = { showAllNotifications = false },
            // a follower opens over Profile itself, not on the Posts feed (iOS 2251f15c)
            onOpenProfile = { address ->
                threadStack = emptyList()
                viewModel.openPosterProfile(address, null)
            },
        )
    }

    if (showBookmarks) {
        KaPostsBookmarksOverlay(
            viewModel = viewModel,
            onClose = { showBookmarks = false },
            // Bookmarks can reply; it has no thread surface of its own, so the card tap stays
            // inert and the reply bubble opens the Reply composer (iOS).
            onReply = { replyComposerTarget = it },
            // Post Activity and the repost icon were silent no-ops in bookmarks (the cell's
            // defaults); the overlays they raise are Dialog windows, which stack above the
            // bookmarks Dialog, so they open in place.
            onViewEngagement = { engagementTarget = it },
            onRepostTap = { repostHandler(it) },
            onTip = { tip(it) },
        )
    }
}

/**
 * The chain above [post] when get-thread has not answered: the navigation stack (only trusted
 * when its tail really is this post's parent - a deep link can land mid-thread with a stack that
 * drifted) plus an upward walk through the loaded tree, at most eight hops, with a cycle guard.
 * Mirrors iOS ancestorChain.
 */
private fun ancestorsFromMemory(
    viewModel: KaPostsViewModel,
    post: KaPostDraft,
    threadStack: List<String>,
): List<KaPostDraft> {
    var walked = threadStack.dropLast(1).mapNotNull { viewModel.findPost(it) }
    val last = walked.lastOrNull()
    if (last != null &&
        viewModel.findParent(post.id)?.id != last.id &&
        last.comments.none { it.id == post.id }
    ) {
        walked = emptyList()
    }
    val above = mutableListOf<KaPostDraft>()
    var cursor: KaPostDraft? = walked.firstOrNull() ?: post
    var hops = 0
    while (cursor != null && hops < 8) {
        val parent = viewModel.findParent(cursor.id) ?: break
        if (above.any { it.id == parent.id } || walked.any { it.id == parent.id }) break
        above.add(parent)
        cursor = parent
        hops++
    }
    return above.reversed() + walked
}

// MARK: - Side menu



/**
 * The header's clickable connection dot (same look as the chat-thread and broadcast-room
 * headers), collecting the live node-health color INSIDE its own restart scope: color ticks
 * repaint this 32dp circle only, instead of recomposing the whole KaPosts screen body.
 */
@Composable
internal fun KaPostsConnectionDotButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** False inside a [ToolbarGlassGroup], which iOS 26 draws as one capsule with its neighbours. */
    ownGlass: Boolean = true,
) {
    val colors = LocalAppColors.current
    val dotColorHex by hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>().dotColorHex.collectAsState()
    // iOS ConnectionStatusIndicator as a toolbar item: a 44pt glass disc, a 12pt status dot.
    Box(
        modifier = modifier
            .size(44.dp)
            .then(if (ownGlass) Modifier.toolbarGlass() else Modifier)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(12.dp).background(Color(dotColorHex), CircleShape))
    }
}

@Composable
private fun FeedEmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    icon: ImageVector? = null,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = KaspaTeal, fontWeight = FontWeight.Bold) }
        }
    }
}

// MARK: - Scheduled posts

/**
 * What is waiting to go out, what went out and what failed. A post still waiting can be
 * cancelled, which releases the coins it was holding; one that has gone out or failed can be
 * cleared off the list. Mirrors iOS's Scheduled screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KaPostsScheduledOverlay(viewModel: KaPostsViewModel, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val entries by viewModel.scheduledPosts.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshScheduledPosts() }
    // Why Cancel didn't, with the post still scheduled (iOS ee5ec98).
    val cancelError by viewModel.scheduledCancelError.collectAsState()
    KaPostsOverlayScaffold(title = stringResource(R.string.kpui_scheduled), onClose = onClose) {
        cancelError?.let { messageRes ->
            com.kachat.app.ui.theme.IosAlertDialog(
                onDismissRequest = { viewModel.clearScheduledCancelError() },
                // "Posts", not the old name (iOS 01f71184 release audit)
                title = { Text(stringResource(R.string.tab_posts), color = colors.textPrimary) },
                text = { Text(stringResource(messageRes), color = colors.textSecondary) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearScheduledCancelError() }) {
                        Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
            )
        }
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Schedule, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.kpui_no_scheduled_posts), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.kpui_no_scheduled_body),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            return@KaPostsOverlayScaffold
        }
        // Pull to refresh: send what is due from this phone, then take the indexer's word on
        // the rest (iOS KaPostsView.swift scheduledSheet `.refreshable`).
        val pullRefreshState = rememberPullToRefreshState()
        LaunchedEffect(pullRefreshState.isRefreshing) {
            if (pullRefreshState.isRefreshing) {
                viewModel.refreshScheduledPostsNow()
                pullRefreshState.endRefresh()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(entries, key = { it.txId }) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        entry.text,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    // iOS scheduledIcon / scheduledTint: the icon and the line share the tint.
                    val statusTint = when (entry.status) {
                        com.kachat.app.services.KaPostScheduledEntry.STATUS_SUBMITTED -> colors.success
                        com.kachat.app.services.KaPostScheduledEntry.STATUS_FAILED -> colors.danger
                        com.kachat.app.services.KaPostScheduledEntry.STATUS_CANCELLED -> colors.textSecondary
                        else -> KaspaTeal
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (entry.status) {
                                com.kachat.app.services.KaPostScheduledEntry.STATUS_SUBMITTED -> Icons.Default.CheckCircle
                                com.kachat.app.services.KaPostScheduledEntry.STATUS_FAILED -> Icons.Default.Error
                                com.kachat.app.services.KaPostScheduledEntry.STATUS_CANCELLED -> Icons.Outlined.Cancel
                                // Only this phone holds it (iOS "iphone"); the indexer's clock otherwise.
                                else -> if (entry.onServer) Icons.Default.Schedule else Icons.Default.PhoneIphone
                            },
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            // Past its time and not out yet: on its way as soon as the network answers.
                            if (entry.status == com.kachat.app.services.KaPostScheduledEntry.STATUS_SCHEDULED && entry.notBeforeMs <= System.currentTimeMillis()) {
                                stringResource(R.string.kaposts_scheduled_sending_soon)
                            } else {
                                scheduledStatusLine(entry, com.kachat.app.util.is24HourClock())
                            },
                            color = statusTint,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.status == com.kachat.app.services.KaPostScheduledEntry.STATUS_SCHEDULED) {
                            Text(
                                stringResource(R.string.cancel),
                                color = colors.danger,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable { viewModel.cancelScheduledPost(entry) },
                            )
                        } else {
                            Text(
                                stringResource(R.string.remove),
                                color = colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable { viewModel.dismissScheduledPost(entry) },
                            )
                        }
                    }
                    // Only on a failed post (iOS: caption2, secondary).
                    entry.error?.takeIf {
                        it.isNotBlank() && entry.status == com.kachat.app.services.KaPostScheduledEntry.STATUS_FAILED
                    }?.let { error ->
                        Spacer(Modifier.height(4.dp))
                        Text(error, color = colors.textSecondary, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = colors.surfaceVariant)
            }
        }
        if (pullIndicatorShown(pullRefreshState)) {
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
        }
    }
}

/** "Goes out Sep 24, 2026, 2:30 PM" and the outcomes that follow - iOS's scheduledLine. */
private fun scheduledStatusLine(entry: com.kachat.app.services.KaPostScheduledEntry, is24Hour: Boolean): String = when (entry.status) {
    com.kachat.app.services.KaPostScheduledEntry.STATUS_SUBMITTED ->
        com.kachat.app.util.tr(R.string.kpui_sched_posted_at, formatScheduledTime(entry.submittedAtMs ?: entry.notBeforeMs, is24Hour))
    com.kachat.app.services.KaPostScheduledEntry.STATUS_FAILED ->
        com.kachat.app.util.tr(R.string.kpui_sched_failed_at, formatScheduledTime(entry.notBeforeMs, is24Hour))
    com.kachat.app.services.KaPostScheduledEntry.STATUS_CANCELLED -> com.kachat.app.util.tr(R.string.kpui_sched_cancelled)
    else -> {
        val whenText = formatScheduledTime(entry.notBeforeMs, is24Hour)
        if (entry.onServer) com.kachat.app.util.tr(R.string.kpui_sched_goes_out, whenText) else com.kachat.app.util.tr(R.string.kpui_sched_goes_out_phone, whenText)
    }
}


/** How a scheduled time reads wherever one is shown: iOS's `.medium` date and `.short` time in
 *  the app's locale and the device's clock - "Sep 24, 2026, 2:30 PM" in English (US). */
internal fun formatScheduledTime(ms: Long, is24Hour: Boolean): String =
    com.kachat.app.util.IosDateStyle.mediumDateShortTime(ms, is24Hour)

/**
 * Picks when a post goes out: one sheet with the date and the time together, and one button that
 * says the moment it will be sent - iOS's schedule sheet, which never asks you to press Next.
 * The window is five minutes to thirty days from now, which is what the indexer accepts
 * (KAPOSTS_INDEXER.md section 5.10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KaPostSchedulePicker(initialMs: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val colors = LocalAppColors.current
    val earliest = System.currentTimeMillis() + 5 * 60 * 1000L
    val latest = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L
    val start = initialMs.coerceIn(earliest, latest)
    val startCalendar = remember(start) { java.util.Calendar.getInstance().apply { timeInMillis = start } }
    // The calendar works in UTC midnights; the time is the phone's own, and they are put back
    // together in [chosenMs] below.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                startCalendar.get(java.util.Calendar.YEAR),
                startCalendar.get(java.util.Calendar.MONTH),
                startCalendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }.timeInMillis,
    )
    val timeState = rememberTimePickerState(
        initialHour = startCalendar.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = startCalendar.get(java.util.Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    val chosenMs = remember(dateState.selectedDateMillis, timeState.hour, timeState.minute) {
        val day = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateState.selectedDateMillis ?: start
        }
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, day.get(java.util.Calendar.YEAR))
            set(java.util.Calendar.MONTH, day.get(java.util.Calendar.MONTH))
            set(java.util.Calendar.DAY_OF_MONTH, day.get(java.util.Calendar.DAY_OF_MONTH))
            set(java.util.Calendar.HOUR_OF_DAY, timeState.hour)
            set(java.util.Calendar.MINUTE, timeState.minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val tooSoon = chosenMs < earliest
    val tooFar = chosenMs > latest

    // A full-height sheet with the grabber, as iOS presents it (.large, drag indicator visible):
    // swiping it down is how it is left - iOS has no Cancel here.
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.kaposts_schedule_post),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        Text(
                            stringResource(R.string.kaposts_schedule_post_note),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            DatePicker(
                                state = dateState,
                                title = null,
                                headline = null,
                                showModeToggle = false,
                            )
                            Spacer(Modifier.height(4.dp))
                            // iOS's graphical picker carries the time under the month; Material's calendar
                            // has no time, so the compact clock input sits under it - one sheet still.
                            TimeInput(state = timeState)
                            // iOS's picker cannot leave its five-minutes-to-thirty-days range at all;
                            // Material's time input can, so this says when it has.
                            if (tooSoon || tooFar) {
                                Text(
                                    if (tooSoon) stringResource(R.string.kpui_sched_too_soon) else stringResource(R.string.kpui_sched_too_far),
                                    color = colors.danger,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.kpui_schedule_for, formatScheduledTime(chosenMs, com.kachat.app.util.is24HourClock())),
                            color = if (tooSoon || tooFar) colors.textSecondary else Color.Black,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (tooSoon || tooFar) colors.surfaceVariant else KaspaTeal)
                                .clickable(enabled = !tooSoon && !tooFar) { onPicked(chosenMs) }
                                .padding(vertical = 12.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    com.kachat.app.ui.screens.IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

// MARK: - Polls

/** How long a poll can stay open, exactly the choices iOS offers. */
private val POLL_LENGTHS = listOf(
    R.string.kpui_poll_1_hour to 1,
    R.string.kpui_poll_6_hours to 6,
    R.string.kpui_poll_12_hours to 12,
    R.string.kpui_poll_1_day to 24,
    R.string.kpui_poll_3_days to 72,
    R.string.kpui_poll_7_days to 168,
)

/**
 * The poll under a post's question. While it is open and you have not voted, the options are
 * buttons; once you have voted, or once it closes, they are bars with percentages, your own
 * choice marked. Voting goes through the same five-second undo as every other action, so the
 * card shows a clock until the vote is actually on its way. Mirrors iOS's `KaPostPollCard`.
 */
@Composable
private fun KaPostPollCard(
    poll: KaPostDraft.KaPostPoll,
    onVote: ((Int) -> Unit)?,
    votePending: Boolean,
) {
    val colors = LocalAppColors.current
    val showsResults = poll.myVote != null || poll.isClosed || onVote == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.textPrimary.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        poll.options.forEachIndexed { index, option ->
            if (showsResults) {
                val count = KaPostDraft.KaPostPoll.clampedCount(poll.counts.getOrElse(index) { 0 })
                val total = poll.total
                val share = if (total > 0) minOf(1f, count.toFloat() / total.toFloat()) else 0f
                val mine = poll.myVote == index
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(share.coerceIn(0.02f, 1f))
                            .background(
                                if (mine) KaspaTeal.copy(alpha = 0.35f) else colors.textPrimary.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp),
                            ),
                    )
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (mine) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.kpui_your_vote),
                                tint = KaspaTeal,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${kotlin.math.round(share * 100).toInt()}%",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.2.dp, KaspaTeal.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !votePending) { onVote?.invoke(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option,
                        color = KaspaTeal,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (votePending) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Text(
            kaPostPollFooter(LocalContext.current, poll),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

/** "12 votes · 2d left", or "· Final results" once voting has closed (iOS's footer, word for
 *  word, each piece in the app's language: iOS 369794e). */
private fun kaPostPollFooter(context: android.content.Context, poll: KaPostDraft.KaPostPoll): String {
    val votes = if (poll.total == 1) context.getString(R.string.kaposts_poll_one_vote)
    else context.getString(R.string.kaposts_poll_votes, poll.total)
    if (poll.isClosed) return context.getString(R.string.kaposts_poll_final_results, votes)
    val left = poll.closesAtMs - System.currentTimeMillis()
    val remaining = when {
        left >= 86_400_000L -> context.getString(R.string.kaposts_poll_days_left, (left / 86_400_000L).toInt())
        left >= 3_600_000L -> context.getString(R.string.kaposts_poll_hours_left, (left / 3_600_000L).toInt())
        else -> context.getString(R.string.kaposts_poll_minutes_left, kotlin.math.max(1L, left / 60_000L).toInt())
    }
    return "$votes · $remaining"
}

// MARK: - Post cell

@Composable
fun KaPostCell(
    post: KaPostDraft,
    viewModel: KaPostsViewModel,
    onOpenThread: () -> Unit,
    onRepostTap: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenQuoted: (String) -> Unit = {},
    onViewEngagement: () -> Unit = {},
    isRoot: Boolean = false,
    /**
     * Feed cells fold very long posts behind "Show more" (which opens the full thread);
     * detail/comment/profile/bookmark cells show everything. Matches iOS KaPostCellView.
     */
    truncatesLongText: Boolean = false,
    /**
     * In-thread cells pass this: the comment bubble then aims the thread's reply composer at THIS
     * post instead of opening its thread (desktop's `data-kaposts-reply-to`). Null everywhere else,
     * where the bubble keeps its "open the thread" meaning.
     */
    onReply: (() -> Unit)? = null,
    /** "Tip": opens the 1:1 chat with the poster in KAS-send mode. Hidden on your own posts. */
    onTip: (() -> Unit)? = null,
    /** A reply shown outside its conversation (a profile's Replies, Bookmarks) says who it
     *  answers: "Replying to Alice" (iOS 961fc05a `replyingTo`). */
    showsReplyingTo: Boolean = false,
) {
    val kaChatLinkContext = LocalContext.current
    val colors = LocalAppColors.current
    val context = LocalContext.current
    // A report that couldn't go to a mail app.
    var unsentReport by remember { mutableStateOf(false) }
    if (unsentReport) ContentReportFallback(onDismiss = { unsentReport = false })
    // Per-address / per-post SLICES of the view model's whole-map stores (see
    // collectSelectedAsState): this cell recomposes only when ITS avatar, name, follow state
    // or countdowns change. Collecting the whole maps here subscribed every visible cell to
    // every entry, so each author profile arriving mid-scroll re-ran the entire viewport.
    val contactPhoto by viewModel.contactPhotos.collectSelectedAsState(post.posterAddress) { it[post.posterAddress] }
    val name = posterDisplayNameState(viewModel, post.posterAddress)
    val isFollowingPoster by viewModel.following.collectSelectedAsState(post.posterAddress) { post.posterAddress in it }
    // The bell beside Follow: notified when this author posts (KaPostsWatchStore, iOS a6bd60a).
    val isWatchingPoster by viewModel.watchedAuthors.collectSelectedAsState(post.posterAddress) { post.posterAddress.lowercase() in it }
    // What the bell just did, said for a moment under the name.
    var bellNote by remember(post.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(bellNote) {
        if (bellNote != null) {
            delay(2_800)
            bellNote = null
        }
    }
    // The post whose tippers are listed (the tip count's sheet).
    var showTippers by remember(post.id) { mutableStateOf(false) }
    val hapticView = LocalView.current
    fun lightHaptic() { hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
    // The cell's countdowns as one collector, not two (audit M12). A poll vote rides the same
    // five-second countdown; the card shows a clock until it goes.
    val cellDeadlineSlice by viewModel.undoDeadlines.collectSelectedAsState(post.id) {
        KaPostCellDeadlines(it["repost:${post.id}"], it["like:${post.id}"], it["dislike:${post.id}"], it["pollvote:${post.id}"])
    }
    val cellDeadlines = Triple(cellDeadlineSlice.repost, cellDeadlineSlice.like, cellDeadlineSlice.dislike)
    val cellPollVoteDeadline = cellDeadlineSlice.pollVote
    var showOverflow by remember { mutableStateOf(false) }
    // The edit composer is opened from the cell itself, so editing works wherever a post is
    // shown: the feed, a thread, your profile, bookmarks.
    var editing by remember { mutableStateOf(false) }
    // Tapped link awaiting the Copy/Open choice (iOS parity: links never auto-open).
    var tappedLinkUrl by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val isMine = post.posterAddress == viewModel.myAddress()

    // On-device translation, X-style. Per-post slices like everything else in this cell, so one
    // reader translating one post never re-renders the viewport. Language identification is async,
    // so the cell asks once and the affordance appears when the answer arrives.
    val translationKey = remember(post.remoteId, post.id, post.editedAt, post.text) { viewModel.translationKey(post) }
    // One collector for the three translation slices rather than three.
    //
    // collectSelectedAsState builds a map+distinctUntilChanged flow and collects it, so every
    // call is a coroutine per cell. A fling recycles rows constantly, and at eight-ish collectors
    // a row that is a lot of launches and cancellations per second - which is felt as the list
    // not keeping up rather than as any one thing being slow. Combined into a single flow, the
    // per-row precision is unchanged: distinctUntilChanged on the triple still only emits when
    // THIS post's translation state moves.
    val translationSlice by remember(
        viewModel.translations, viewModel.showingOriginal, viewModel.translatable, translationKey
    ) {
        combine(
            viewModel.translations,
            viewModel.showingOriginal,
            viewModel.translatable,
        ) { translations, originals, translatable ->
            KaPostTranslationSlice(
                state = translations[translationKey],
                showingOriginal = translationKey in originals,
                canTranslate = translationKey in translatable,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = KaPostTranslationSlice(
            state = viewModel.translations.value[translationKey],
            showingOriginal = translationKey in viewModel.showingOriginal.value,
            canTranslate = translationKey in viewModel.translatable.value,
        )
    )
    val translationState = translationSlice.state
    val showingOriginal = translationSlice.showingOriginal
    val canTranslate = translationSlice.canTranslate
    // Only for a row that stays on screen a moment: a fling past doesn't run the language
    // check on every post it passes (audit M11).
    LaunchedEffect(translationKey) {
        delay(TRANSLATION_CHECK_VISIBLE_MS)
        viewModel.considerTranslation(post)
    }
    val displayedText = viewModel.displayText(post, translationState, showingOriginal)
    // The poster has a .kachat name: only then do the post's links preview (owner's call,
    // 2026-11-18; iOS b78fdb0 `posterHasKachatName`). Snapshot state, so a name landing redraws.
    val posterHasKachatName = post.posterAddress.isNotEmpty() &&
        com.kachat.app.services.kachatnames.KachatNamesRegistry.kachatName(post.posterAddress) != null
    // The links drawn as cards, and the text left: worked out once per text and kept, so a row
    // scrolling back in doesn't run the link matchers over the whole post again (audit M11).
    val textParts = remember(displayedText, posterHasKachatName) { kaPostTextParts(displayedText, posterHasKachatName) }
    val paymentRequestLink = textParts.paymentRequestLink
    val botChallengeLink = textParts.botChallengeLink
    val siteLink = textParts.siteLink
    val webLink = textParts.webLink
    val bodyText = textParts.bodyText

    // Measured on what is actually rendered, so a translation that runs longer than its original
    // still folds. Same numbers as iOS's KaPostCellView.isLongPost.
    // The newline count only decides short posts, so it is only counted for them.
    val isLongPost = bodyText.length > 280 || bodyText.take(281).count { it == '\n' } >= 8
    // Show more expands the post IN PLACE. It used to open the thread, so the only way to read a
    // long post in a feed was to leave the feed - and on an ancestor it did nothing useful at all.
    // Opening the post is what tapping the post itself is for.
    var textExpanded by remember(post.id) { mutableStateOf(false) }
    val foldText = truncatesLongText && isLongPost && !textExpanded

    // The cell's parts, laid out as a feed row or, for the post a thread is focused on, as X
    // draws the post you opened (iOS 961fc05a `rowLayout` / `focusedLayout`).
    val avatarBlock: @Composable () -> Unit = {
        Box(modifier = Modifier.clickable { onOpenProfile() }) {
            ContactAvatar(
                imageUrl = null,
                fallbackText = name,
                size = 40.dp,
                // A saved contact's own photo overrides the .kachat avatar, as everywhere else.
                backupPhotoBase64 = contactPhoto?.backupPhotoBase64,
                address = post.posterAddress,
            )
        }
    }
    // The name line (Follow, the bell, the overflow menu) and the bell's note under it.
    val headerBlock: @Composable () -> Unit = {
        // iOS: name (subheadline bold), Follow (caption bold) and the bell (caption bold),
        // 6pt apart, then a Spacer that puts the "..." at the line's far right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The name, Follow and the bell share what the "..." leaves, so a long name
            // truncates instead of pushing the menu off the line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = name,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // When the post was made, and whether it was edited, sit in their own row
                // under the actions now - this line carries only Follow / Following (iOS).
                if (!isMine) {
                    Spacer(modifier = Modifier.width(6.dp))
                    val isFollowing = isFollowingPoster
                    Text(
                        text = if (isFollowing) stringResource(R.string.l53_feed_tab_following) else stringResource(R.string.kpui_follow),
                        color = if (isFollowing) colors.textSecondary else KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            lightHaptic()
                            viewModel.toggleFollow(post.posterAddress, post.posterPubkey)
                        },
                    )
                    // Notified when this person posts - its own switch, beside Follow. On says
                    // "you'll be notified when they post", off says it won't, for a moment,
                    // right under the name.
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        if (isWatchingPoster) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                        contentDescription = stringResource(
                            if (isWatchingPoster) R.string.kaposts_bell_stop else R.string.kaposts_bell_notify
                        ),
                        tint = KaspaTeal,
                        // iOS's caption-bold bell is ~12pt across; this glyph is 2/3 of its box.
                        modifier = Modifier
                            .size(17.dp)
                            .clickable {
                                lightHaptic()
                                val on = viewModel.toggleWatch(post.posterAddress)
                                bellNote = context.getString(
                                    if (on) R.string.kaposts_bell_on_note else R.string.kaposts_bell_off_note,
                                    name,
                                )
                            },
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            // iOS: the subheadline "ellipsis" in a 30 x 24pt tap frame at the line's end.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 30.dp, height = 24.dp)
                    .clickable { showOverflow = true },
            ) {
                Icon(
                    Icons.Default.MoreHoriz,
                    contentDescription = stringResource(R.string.kpui_more),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (editing) {
                // Edit mode: no threading and no draft prompt - this is a change to
                // something already posted, not a new one being written.
                KaPostComposerDialog(
                    title = stringResource(R.string.kpui_edit_post),
                    quoted = null,
                    submitLabel = stringResource(R.string.save),
                    onDismiss = { editing = false },
                    onSubmit = { newText ->
                        editing = false
                        viewModel.editPost(post, newText)
                    },
                    viewModel = viewModel,
                    initialText = post.text,
                    // the text stays as posted (iOS `!editingPost`)
                    attachesChallenge = false,
                )
            }
            // The three-dots menu is a half sheet, like every other menu in the app,
            // so each option can say what it does; the popup had room for a verb and
            // nothing else. Share lives in the action row itself, next to bookmark,
            // matching iOS - no duplicate entry here.
            if (showOverflow) {
                ActionSheetContainer(
                    // iOS: "Your post", or the author's name.
                    title = if (isMine) stringResource(R.string.kpui_your_post) else name,
                    subtitle = null,
                    onDismiss = { showOverflow = false },
                ) {
                    // Editable only while the window is open, and only our own: the
                    // indexer enforces the same rules, so anything else would be
                    // written to the chain and then ignored. Edit first, as iOS.
                    val editLeftMs = if (isMine) post.editTimeRemainingMs else null
                    if (editLeftMs != null) {
                        ActionSheetRow(
                            icon = Icons.Default.Edit,
                            title = stringResource(R.string.ab_edit),
                            subtitle = stringResource(R.string.kpui_edit_window_subtitle, editWindowLeftText(editLeftMs)),
                        ) {
                            showOverflow = false
                            editing = true
                        }
                    }
                    if (post.remoteId != null) {
                        ActionSheetRow(
                            icon = Icons.Outlined.BarChart,
                            title = stringResource(R.string.kpui_post_activity),
                            subtitle = stringResource(R.string.kpui_post_activity_subtitle),
                        ) {
                            showOverflow = false
                            onViewEngagement()
                        }
                    }
                    // Any age, unlike an edit: what you posted is yours to withdraw.
                    if (isMine && post.remoteId != null && post.deliveryStatus == KaPostDraft.Delivery.SENT && !post.pendingDeletion) {
                        ActionSheetRow(
                            icon = Icons.Default.Delete,
                            title = stringResource(R.string.delete),
                            subtitle = stringResource(R.string.kaposts_delete_post_subtitle),
                            tint = LocalAppColors.current.danger,
                        ) {
                            showOverflow = false
                            viewModel.deletePost(post)
                        }
                    }
                    if (!isMine) {
                        // Reporting: the post's id, author and text go to KaChat by email,
                        // where a person reads them. Posts are on a public chain and
                        // nobody can take one down, so the remedies that act at once are
                        // the two rows under this one - Mute and Block remove the author
                        // from this reader's KaPosts on the spot (iOS dd58e26).
                        ActionSheetRow(
                            icon = Icons.Default.Flag,
                            title = stringResource(R.string.pc_report),
                            subtitle = stringResource(R.string.kpui_report_post_subtitle),
                            tint = LocalAppColors.current.danger,
                        ) {
                            showOverflow = false
                            unsentReport = !reportPost(post).send(context)
                        }
                        // Named, as on iOS: "Mute alice" says who this lands on.
                        ActionSheetRow(
                            icon = Icons.Default.VolumeOff,
                            title = stringResource(R.string.kpui_mute_name, name),
                            subtitle = stringResource(R.string.kpui_mute_subtitle),
                        ) {
                            showOverflow = false
                            viewModel.mute(post.posterAddress)
                        }
                        ActionSheetRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.pc_block_name, name),
                            subtitle = stringResource(R.string.pc_block_subtitle),
                            tint = LocalAppColors.current.danger,
                        ) {
                            showOverflow = false
                            viewModel.block(post.posterAddress)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = bellNote != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                bellNote.orEmpty(),
                color = KaspaTeal,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
    }
    // What the post says: text, link cards, poll, quote.
    val contentBlock: @Composable () -> Unit = {
        // ClickableText (not Text): tapping an @mention resolves the .kachat name and
        // opens that user's profile, tapping a link opens the Copy/Open dialog (iOS
        // parity - links never auto-open). ClickableText consumes EVERY tap on the
        // body though, so plain-text taps must fall through to the row's open-thread
        // action by hand - the body covers most of the cell, and without this
        // "tap the post to open its thread" only worked on the padding around it.
        // Root cells keep body taps inert, matching their disabled row clickable.
        // What actually gets laid out. A folded cell shows eight lines at most, yet it
        // was handed the whole post - up to twenty-five thousand characters, styled and
        // measured per cell on every pass. Eight lines never need more than the first
        // few hundred, so a folded cell renders a prefix and the full text only once it
        // is expanded (iOS a3a7524).
        val layoutText = remember(bodyText, foldText) { foldedLayoutText(bodyText, foldText) }
        val postAnnotated = remember(layoutText) { annotatedPostText(layoutText) }
        val postBody = @Composable {
            androidx.compose.foundation.text.ClickableText(
                text = postAnnotated,
                // The post a thread is FOCUSED on reads larger than the posts around it,
                // the way X sizes the one you opened against its ancestors and replies.
                // iOS: body (17/22) in a feed, title3 (20/25) for the focused post.
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = if (isRoot) 20.sp else 17.sp,
                    lineHeight = if (isRoot) 25.sp else 22.sp,
                ),
                maxLines = if (foldText) 8 else Int.MAX_VALUE,
                overflow = if (foldText) TextOverflow.Ellipsis else TextOverflow.Clip,
                onClick = { offset ->
                    val mention = postAnnotated.getStringAnnotations(MENTION_ANNOTATION_TAG, offset, offset).firstOrNull()
                    val link = postAnnotated.getStringAnnotations(LINK_ANNOTATION_TAG, offset, offset).firstOrNull()
                    when {
                        // the post's signed mentions ride along, so the tap opens who
                        // the author meant (iOS e493c97, IOS-068)
                        mention != null -> viewModel.openMentionProfile(mention.item, post.mentionedPubkeys)
                        link != null -> tappedLinkUrl = link.item
                        !isRoot -> onOpenThread()
                    }
                },
            )
        }
        // Long-press selects text only in the post you OPENED. Making every cell
        // selectable turned a long-press anywhere in a feed into a selection handle
        // fight, and scrolling past a post is far more common than quoting one; the
        // focused post is the one you came to read. Matches iOS's selectableText(isRoot).
        // A post that is only a payment request link has no text left to draw.
        if (layoutText.isNotEmpty()) {
            if (isRoot) {
                androidx.compose.foundation.text.selection.SelectionContainer { postBody() }
            } else {
                postBody()
            }
        }
        // Half sheet rather than an alert dialog - see LinkActionsSheet.
        tappedLinkUrl?.let { url ->
            val linkHaptic = com.kachat.app.util.rememberHaptics()
            LinkActionsSheet(
                url = url,
                onDismiss = { tappedLinkUrl = null },
                onOpen = {
                    // A KaChat link goes straight to the post or room it names, rather
                    // than out to the browser and back - same as the chat screens.
                    val internal = KaChatLink.parse(url)
                    if (internal != null) openKaChatLink(kaChatLinkContext, internal) else uriHandler.openUri(url)
                },
                onCopy = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                    // iOS's post link copy: a haptic, no toast.
                    linkHaptic(com.kachat.app.util.IosHaptic.SUCCESS)
                },
            )
        }
        if (truncatesLongText && isLongPost) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (textExpanded) stringResource(R.string.kpui_show_less) else stringResource(R.string.kpui_show_more),
                color = KaspaTeal,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.clickable { textExpanded = !textExpanded },
            )
        }
        // A payment request link: a card with a Pay button, not the link's text.
        (paymentRequestLink?.ref as? KaChatLinkRef.PaymentRequest)?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            KaPostPaymentRequestCard(request = link.request)
        }
        // A Kaspa Hub card's link: a card with Open, not the link's text (iOS 961fc05a).
        (textParts.hubLink?.ref as? KaChatLinkRef.HubSection)?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            KaspaHubLinkCard(tab = link.tab)
        }
        // A KaPlay bot challenge: a card with a Challenge button, not the link's text.
        botChallengeLink?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            KaPostBotChallengeCard(challenge = link.challenge)
        }
        // An arcade score to beat: a card with a Play button, not the link's text. The
        // post's signer, so the card names `by` only when it's them (audit 2026-11-24 #21).
        textParts.arcadeChallengeLink?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            KaPostArcadeChallengeCard(challenge = link.challenge, author = post.posterAddress)
        }
        // A link to KaChat's site: its preview, or the link itself if that can't load.
        siteLink?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            LinkPreviewCard(url = link.url, txId = post.remoteId.orEmpty(), fallbackText = link.matchedText, fillsWidth = true)
        }
        // Another site's link from a poster with a .kachat name: its preview in place of
        // the link, or the link itself if the preview can't load (iOS b78fdb0).
        webLink?.let { link ->
            Spacer(modifier = Modifier.height(10.dp))
            LinkPreviewCard(url = link.url, txId = post.remoteId.orEmpty(), fallbackText = link.matchedText, fillsWidth = true)
        }
        TranslateAffordance(
            state = translationState,
            canTranslate = canTranslate,
            showingOriginal = showingOriginal,
            onTranslate = { viewModel.translatePost(post) },
            onShowOriginal = { viewModel.showOriginal(post) },
            onShowTranslation = { viewModel.showTranslation(post) },
        )
        post.poll?.let { poll ->
            // The stack's 6pt plus the card's own 6pt top padding (iOS).
            Spacer(modifier = Modifier.height(12.dp))
            KaPostPollCard(
                poll = poll,
                // A poll of someone else's is votable; a closed one, or one still on its
                // way to the chain, only shows its numbers.
                onVote = if (post.remoteId != null && !poll.isClosed) {
                    { index -> lightHaptic(); viewModel.votePoll(post, index) }
                } else null,
                votePending = cellPollVoteDeadline != null,
            )
        }
        post.quoted?.let { quoted ->
            Spacer(modifier = Modifier.height(6.dp))
            // A plain repost is a quote with no words, so a repost OF a repost used to show the
            // middle reposter's name over an empty card. A card whose post has no words shows
            // the post that one reposted instead, as X does, looked up once
            // ([com.kachat.app.services.KaPostRepostOrigins]); a tap opens that original
            // (iOS 01f71184 `KaPostQuotedEmbed`).
            var original by remember(quoted.remoteId) { mutableStateOf<KaPostDraft.QuotedRef?>(null) }
            var looking by remember(quoted.remoteId) { mutableStateOf(false) }
            LaunchedEffect(quoted.remoteId) {
                if (original != null || !com.kachat.app.services.KaPostRepostOrigins.isPlainRepost(quoted) ||
                    viewModel.cachedRepostOrigin(quoted) != null
                ) return@LaunchedEffect
                looking = true
                original = viewModel.repostOrigin(quoted)
                looking = false
            }
            val shown = original ?: viewModel.cachedRepostOrigin(quoted) ?: quoted
            // The feeds drop a hidden person's own posts, but someone else quoting them
            // put their text and avatar back in front of you (iOS 369794e). Changes only
            // on a mute or block. The author checked is the one the card shows.
            val quotedMuted by viewModel.muted.collectSelectedAsState(shown.posterAddress) { shown.posterAddress in it }
            val quotedBlocked by viewModel.blocked.collectSelectedAsState(shown.posterAddress) { shown.posterAddress in it }
            if (quotedMuted || quotedBlocked) {
                // Someone you muted or blocked: no text, name or avatar, and no way in.
                Text(
                    stringResource(R.string.kaposts_quoted_hidden),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .border(1.dp, colors.textSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                )
            } else {
                // Same per-address slices for the quoted author as for the cell's own.
                val quotedName = posterDisplayNameState(viewModel, shown.posterAddress)
                Box(
                    modifier = Modifier.clickable(enabled = shown.remoteId != null) {
                        shown.remoteId?.let(onOpenQuoted)
                    },
                ) {
                    QuotedEmbedCard(
                        quoted = shown,
                        displayName = quotedName,
                        loading = looking && com.kachat.app.services.KaPostRepostOrigins.isPlainRepost(shown),
                    )
                }
            }
        }
    }
    // The action row, and the tippers' sheet it opens.
    val actionsBlock: @Composable () -> Unit = {
        EngagementRow(
            post = post,
            commentCount = viewModel.commentCount(post),
            repostDeadline = cellDeadlines.first,
            likeDeadline = cellDeadlines.second,
            dislikeDeadline = cellDeadlines.third,
            // The bubble replies (feed cells open the Reply composer; the card opens the
            // thread). The focused post of a thread has no bubble at all - the box under
            // it is its reply (iOS threadCell isRoot: onComment nil).
            onComment = if (isRoot) null else (onReply ?: onOpenThread),
            onRepost = onRepostTap,
            onLike = { lightHaptic(); viewModel.toggleLike(post) },
            onDislike = { lightHaptic(); viewModel.toggleDislike(post) },
            onBookmark = { lightHaptic(); viewModel.toggleBookmark(post) },
            onCancelCountdown = { lightHaptic(); viewModel.cancelUndoable(it) },
            onShare = {
                viewModel.shareText(post)?.let { link ->
                    // Shared as a link and nothing else, which is what a paste target
                    // wants: the link unfurls the post's preview by itself.
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_TITLE, link)
                    }
                    context.startActivity(Intent.createChooser(intent, com.kachat.app.util.tr(R.string.kpui_share_post)))
                }
            },
            onTip = if (!isMine) onTip?.let { tip -> { lightHaptic(); tip() } } else null,
            // Your own post's tip count lists who tipped (Post Activity > Tips for any post).
            onShowTippers = if (post.remoteId != null) {
                { lightHaptic(); showTippers = true }
            } else null,
            // X-style repost sheet; quote routes through the VM's quoteRequest flow so
            // the main screen's composer opens from any cell. "Undo Repost" submits the
            // unquote counter-action rather than a second repost.
            onRepostConfirm = {
                if (post.repostedByMe) viewModel.scheduleUnrepost(post) else viewModel.scheduleRepost(post)
            },
            onQuote = { viewModel.requestQuote(post) },
            onRetry = { lightHaptic(); viewModel.retryPost(post) },
            onHaptic = { lightHaptic() },
        )
        if (showTippers) {
            post.remoteId?.let { remoteId ->
                KaPostTippersSheet(postId = remoteId, viewModel = viewModel, onDismiss = { showTippers = false })
            }
        }
    }
    // "Replying to Alice" above a reply shown outside its conversation (a profile's Replies, a
    // bookmark), as X has it (iOS 961fc05a `replyingToLine`); inside a conversation the post it
    // answers is right above it.
    val replyingToAddress = if (showsReplyingTo && !isRoot) viewModel.replyingToAddress(post) else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Dimmed while a delete waits out its undo countdown: the post is on its way out,
            // and Undo is right there.
            .alpha(if (post.pendingDeletion) 0.4f else 1f)
            .clickable(enabled = !isRoot) { onOpenThread() }
            // iOS KaPostCellView: 16pt horizontal, 12pt vertical, 40pt avatar. The thread line
            // drawn behind ancestor cells depends on exactly these numbers.
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (isRoot) {
            // The post you opened, as X draws it (iOS 961fc05a `focusedLayout`, owner 2026-11-25:
            // "match X"): the avatar and name on one line, the text full width and larger under
            // them, the full time ("12:00 PM · Nov 25, 2026"), then the actions between two rules.
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatarBlock()
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) { headerBlock() }
            }
            Spacer(modifier = Modifier.height(10.dp))
            contentBlock()
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = remember(post.timestamp) { focusedPostTimestampLabel(post.timestamp) } +
                    if (post.editedAt != null) " " + stringResource(R.string.kpui_dot_edited) else "",
                // iOS: subheadline (15pt), secondary.
                color = colors.textSecondary,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = colors.surfaceVariant)
            actionsBlock()
            HorizontalDivider(color = colors.surfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.Top) {
                avatarBlock()
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    replyingToAddress?.let { address ->
                        val replyName = posterDisplayNameState(viewModel, address)
                        val line = stringResource(R.string.replying_to, replyName)
                        Text(
                            text = remember(line, replyName) {
                                // the name in the accent colour
                                androidx.compose.ui.text.buildAnnotatedString {
                                    append(line)
                                    val at = line.lastIndexOf(replyName)
                                    if (at >= 0 && replyName.isNotEmpty()) {
                                        addStyle(androidx.compose.ui.text.SpanStyle(color = KaspaTeal), at, at + replyName.length)
                                    }
                                }
                            },
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    headerBlock()
                    // iOS stacks the cell's rows 6pt apart.
                    Spacer(modifier = Modifier.height(6.dp))
                    contentBlock()
                    // iOS: the stack's 6pt plus the row's 2pt top padding (applied inside the row) -
                    // less the 4dp the actions here keep above their icons as touch padding.
                    Spacer(modifier = Modifier.height(2.dp))
                    actionsBlock()
                    // When the post was made - the full date and time, in its own row under the
                    // actions, with "· edited" after it when the text was changed (iOS b31ff59).
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        // Remembered per timestamp: formatting allocates a SimpleDateFormat, which is
                        // not something to redo on every cell recomposition mid-scroll.
                        text = remember(post.timestamp) { postTimestampLabel(post.timestamp) } +
                            if (post.editedAt != null) " " + stringResource(R.string.kpui_dot_edited) else "",
                        // iOS: caption (12pt), secondary.
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** How much of a quoted post's text a quote card renders (iOS `markdownPreviewMaxCharacters`). */
private const val MARKDOWN_PREVIEW_MAX_CHARACTERS = 1_200

/**
 * A quoted post's text as the reader should see it: bold, italic, underline, strikethrough and
 * subtext kept, links inert and unstyled - the card is itself tappable through to the quoted
 * post, and a live link inside it would compete with that (iOS markdownPreview).
 */
private fun markdownPreview(source: String): androidx.compose.ui.text.AnnotatedString {
    // A card shows five lines at most, so only the start of the text is rendered: a 25,000-
    // character quoted post isn't parsed in full on the main thread for every card (iOS audit
    // 2026-11-21 #15 `markdownPreviewMaxCharacters`).
    val rendered = KaPostsMarkdown.render(source.take(MARKDOWN_PREVIEW_MAX_CHARACTERS))
    return androidx.compose.ui.text.buildAnnotatedString {
        append(rendered.text)
        for (span in rendered.spans) {
            if (span.start >= span.end || span.end > rendered.text.length) continue
            val style = span.style
            val decorations = buildList {
                if (style.underline) add(androidx.compose.ui.text.style.TextDecoration.Underline)
                if (style.strikethrough) add(androidx.compose.ui.text.style.TextDecoration.LineThrough)
            }
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (style.bold) FontWeight.Bold else null,
                    fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    fontSize = if (style.subtext) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    color = if (style.subtext) Color(0xFF8A8A8E) else Color.Unspecified,
                    textDecoration = if (decorations.isEmpty()) null
                    else androidx.compose.ui.text.style.TextDecoration.combine(decorations),
                ),
                span.start,
                span.end,
            )
        }
    }
}

@Composable
private fun QuotedEmbedCard(
    quoted: KaPostDraft.QuotedRef,
    displayName: String,
    /** 20 inside a cell's embed, 22 under the composer's editor (iOS). */
    avatarSize: androidx.compose.ui.unit.Dp = 20.dp,
    /** The post a plain repost reposted is being looked up: a spinner where its words go. */
    loading: Boolean = false,
    /** The composer's card carries the quoted post's time on its name line (iOS
     *  `quotedPostCard`); the feed's doesn't. */
    timeMs: Long? = null,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.textSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        // iOS parity: small avatar + bold name + relative time header row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(
                imageUrl = null,
                fallbackText = displayName,
                size = avatarSize,
                address = quoted.posterAddress,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = displayName,
                color = colors.textPrimary,
                // iOS caption bold
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // No time on a feed quote card's name line - the quote is context, and its own card
            // carries the moment it was written (iOS b31ff59). The composer's card has one.
            if (timeMs != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = remember(timeMs) { relativePostTime(timeMs) },
                    color = colors.textSecondary,
                    // iOS caption2
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (loading) {
            com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(18.dp))
        } else {
            Text(
                // Styled markdown preview, links inert.
                text = remember(quoted.text) { markdownPreview(quoted.text) },
                color = colors.textPrimary,
                // iOS subheadline
                fontSize = 15.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - Engagement row with in-icon undo countdowns

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EngagementRow(
    post: KaPostDraft,
    commentCount: Int,
    // THIS post's live undo deadlines only (nullable = no countdown running). Passing the whole
    // undoDeadlines map made every cell's row recompose whenever any post anywhere started or
    // finished a countdown; primitives keep strong skipping effective.
    repostDeadline: Long?,
    likeDeadline: Long?,
    dislikeDeadline: Long?,
    /** Null hides the bubble entirely (the focused post of a thread). */
    onComment: (() -> Unit)?,
    onRepost: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onBookmark: () -> Unit,
    onCancelCountdown: (String) -> Unit,
    // Share sits in the action row itself, as on iOS, rather than only behind the overflow menu.
    onShare: (() -> Unit)? = null,
    onTip: (() -> Unit)? = null,
    /** Lists who tipped the post (the tip count's tap); null while it isn't on the network. */
    onShowTippers: (() -> Unit)? = null,
    // X-style repost menu: when BOTH are set (and the post is on-chain), tapping repost opens
    // a compact anchored two-row menu (Repost / Quote) instead of the old dialog.
    onRepostConfirm: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
    /** Re-submits a post that failed to reach the network (the red Retry at the row's end). */
    onRetry: (() -> Unit)? = null,
    onHaptic: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    // iOS order and colours: comment, like (red), dislike (orange), repost (accent), bookmark,
    // share, Tip, then the delivery state at the trailing edge. Fixed 18pt gaps rather than
    // evenly spread, so the row reads the same on every width.
    val likeRed = LocalAppColors.current.danger
    val dislikeOrange = Color(0xFFFF9500)
    var likeBurst by remember(post.id) { mutableStateOf(false) }
    // The Kaspa-logo burst plays when the like actually lands - after the countdown fires, not
    // on the tap that armed it (iOS runLikeBurst).
    var previousLiked by remember(post.id) { mutableStateOf(post.likedByMe) }
    LaunchedEffect(post.likedByMe) {
        if (post.likedByMe && !previousLiked) {
            likeBurst = true
            delay(750)
            likeBurst = false
        }
        previousLiked = post.likedByMe
    }
    // Seven actions - comment, like, dislike, repost, bookmark, share, Tip - plus the delivery
    // mark, on screens from 320dp up and at whatever font scale the reader has set. iOS spaces
    // them 18pt apart; each action here carries 4dp of touch padding either side of its icon,
    // which is already 8 of those 18, and the symbols' own frames run a little wider than these
    // glyphs, so a 10dp gap puts the icons as far apart as on the iPhone (measured side by side).
    // The gaps give way when the row's own contents don't fit - big counts beside Tip, a large
    // font, Retry - rather than by screen width: 10, then 4, then 0dp, iOS's 18, 12 and 8pt once
    // the 4dp touch padding is counted. One pass over items built once ([KaPostActionRowLayout],
    // iOS 518fdc8c `KaPostActionRowLayout`, audit 2026-11-24 #24): if even the tightest gap
    // doesn't fit, the last items give up width (their counts truncate), so the row never grows
    // wider than the post and pushes it sideways.
    Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            KaPostActionRowLayout(
                gaps = listOf(10.dp, 4.dp, 0.dp),
                // iOS's buttons have no padding, so the comment bubble starts flush with the post
                // text above it; pulled back by this row's 4dp touch padding to do the same.
                leadingInset = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
        if (onComment != null) {
            EngagementAction(
                countdownKey = null,
                deadline = null,
                // Icon boxes sized so each glyph comes out as wide as its subheadline SF symbol.
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, tint = colors.textSecondary, modifier = Modifier.size(19.dp)) },
                count = commentCount,
                onTap = { onHaptic(); onComment() },
                onCancel = onCancelCountdown,
                // Not on chain yet (sending, failed, not confirmed): nothing to reply to (iOS 33095ac).
                enabled = post.remoteId != null,
            )
        }
        Box {
            EngagementAction(
                countdownKey = "like:${post.id}",
                deadline = likeDeadline,
                icon = {
                    Icon(
                        if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                        tint = if (post.likedByMe) likeRed else colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                count = post.likes,
                countTint = if (post.likedByMe) likeRed else null,
                // iOS's like button keeps 5pt between heart and count; the others 4pt.
                countGap = 5.dp,
                onTap = onLike,
                onCancel = onCancelCountdown,
            )
            LikeBurst(visible = likeBurst, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
        }
        EngagementAction(
            countdownKey = "dislike:${post.id}",
            deadline = dislikeDeadline,
            icon = {
                Icon(
                    if (post.dislikedByMe) Icons.Outlined.ThumbDown else Icons.Outlined.ThumbDownOffAlt, null,
                    tint = if (post.dislikedByMe) dislikeOrange else colors.textSecondary,
                    modifier = Modifier.size(17.dp),
                )
            },
            count = post.dislikes,
            countTint = if (post.dislikedByMe) dislikeOrange else null,
            onTap = onDislike,
            onCancel = onCancelCountdown,
        )
        Box {
            var repostMenuOpen by remember { mutableStateOf(false) }
            EngagementAction(
                countdownKey = "repost:${post.id}",
                deadline = repostDeadline,
                icon = {
                    Icon(
                        Icons.Default.Repeat, null,
                        tint = if (post.repostedByMe) KaspaTeal else colors.textSecondary,
                        // arrow.2.squarepath runs wide; the Material arrows are narrower.
                        modifier = Modifier.size(20.dp),
                    )
                },
                count = post.reposts,
                countTint = if (post.repostedByMe) KaspaTeal else null,
                onTap = {
                    onHaptic()
                    // X-style: a compact menu floating over the tapped button.
                    if (onRepostConfirm != null && onQuote != null && post.remoteId != null) {
                        repostMenuOpen = true
                    } else {
                        onRepost()
                    }
                },
                onCancel = onCancelCountdown,
            )
            // Half sheet rather than a dropdown, so the two choices get room to say what they
            // do - "Repost" and "Quote" as bare words are only obvious once you already know
            // the difference. See RepostActionsSheet.
            if (repostMenuOpen) {
                RepostActionsSheet(
                    isReposted = post.repostedByMe,
                    onDismiss = { repostMenuOpen = false },
                    onRepost = { onRepostConfirm?.invoke() },
                    onQuote = { onQuote?.invoke() },
                )
            }
        }
        EngagementAction(
            countdownKey = null,
            deadline = null,
            icon = {
                Icon(
                    if (post.bookmarkedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null,
                    tint = if (post.bookmarkedByMe) KaspaTeal else colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            count = null,
            onTap = onBookmark,
            onCancel = onCancelCountdown,
        )
        // Share: only for posts that exist on chain, matching iOS, which hides it until the
        // post has a remote id to link to.
        if (onShare != null && post.remoteId != null) {
            EngagementAction(
                countdownKey = null,
                deadline = null,
                icon = {
                    // iOS square.and.arrow.up: the box with an arrow out of its top.
                    Icon(
                        Icons.Outlined.IosShare, null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                count = null,
                onTap = { onHaptic(); onShare() },
                onCancel = onCancelCountdown,
            )
        }
        // How many times it was tipped, the way likes and comments show theirs: a gray number
        // beside its mark - against the Tip button on someone else's post, the Kaspa mark and the
        // count on your own. A tap lists who tipped (Post Activity > Tips lists them too; iOS 0f241ce).
        val tippersLabel = stringResource(R.string.kaposts_who_tipped)
        val tipCount: @Composable (withMark: Boolean) -> Unit = { withMark ->
            if (post.tips > 0 && onShowTippers != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onShowTippers() }
                        .semantics { contentDescription = tippersLabel }
                        .padding(start = if (withMark) 4.dp else 1.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    if (withMark) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("${post.tips}", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (onTip != null) {
            // The count sits against the Tip button like a like count against its heart.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTip() }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    // The real Kaspa logo, matching iOS's Tip button.
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    // iOS: subheadline semibold.
                    Text(stringResource(R.string.kaposts_tip_title), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                }
                tipCount(false)
            }
        } else {
            // Your own post (no Tip button): the Kaspa mark and the count.
            tipCount(true)
        }
            // The trailing edge: on-chain delivery state, mirroring chat bubbles - green check
            // once the K transaction is on the network (for a minute), spinner while submitting,
            // red Retry when it didn't go through (iOS). The layout puts it at the right edge.
            Box(Modifier.layoutId(KaPostActionRowTrailing)) {
                DeliveryState(post = post, onRetry = onRetry)
            }
            }
    }
}

/** Marks the action row's item that sits at its right edge (the delivery state). */
private object KaPostActionRowTrailing

/**
 * A post's action row (iOS 518fdc8c `KaPostActionRowLayout`, audit 2026-11-24 #24): one pass over
 * items built once. Each item's natural width is read (its intrinsic width, so nothing is built
 * twice) and the row takes the widest of [gaps] that fits the width it's offered; font size, long
 * counts and Retry are all in the reading. If even the last gap doesn't fit, it keeps that one and
 * the last items (the leading ones from the end, the delivery state only after them) give up
 * width - their counts truncate - so the row never grows wider than the post. Items marked
 * [KaPostActionRowTrailing] sit at the right edge, the rest from the left, starting [leadingInset]
 * before it (the touch padding that would otherwise push the first icon in).
 */
@Composable
private fun KaPostActionRowLayout(
    gaps: List<androidx.compose.ui.unit.Dp>,
    modifier: Modifier = Modifier,
    leadingInset: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val inset = leadingInset.roundToPx()
        val isTrailing = measurables.map { it.layoutId == KaPostActionRowTrailing }
        val widths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }.toMutableList()
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth + inset else Int.MAX_VALUE
        val gapPx = gaps.map { it.roundToPx() }
        val plan = KaPostActionRowPlan.plan(
            widths = widths,
            trailing = isTrailing,
            gaps = gapPx,
            available = available,
            smallest = { i -> measurables[i].minIntrinsicWidth(constraints.maxHeight).coerceAtMost(widths[i]) },
        )
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(androidx.compose.ui.unit.Constraints(maxWidth = plan.widths[i].coerceAtLeast(0), maxHeight = constraints.maxHeight))
        }
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else
            (plan.widths.sum() + plan.gap * (plan.widths.count { it > 0 } - 1).coerceAtLeast(0) - inset).coerceAtLeast(0)
        layout(width, height) {
            var x = -inset
            placeables.forEachIndexed { i, p ->
                if (isTrailing[i]) return@forEachIndexed
                p.placeRelative(x, (height - p.height) / 2)
                if (p.width > 0) x += p.width + plan.gap
            }
            var trailingX = width
            for (i in placeables.indices.reversed()) {
                if (!isTrailing[i]) continue
                val p = placeables[i]
                p.placeRelative(trailingX - p.width, (height - p.height) / 2)
                if (p.width > 0) trailingX -= p.width + plan.gap
            }
        }
    }
}

/** The pure arithmetic of [KaPostActionRowLayout], kept apart for unit tests. */
internal object KaPostActionRowPlan {
    data class Plan(val gap: Int, val widths: List<Int>)

    /**
     * The gap and each item's width: the first of [gaps] at which every item fits [available] at
     * its natural width ([widths]); else the last gap, with the leading items from the end, then
     * the trailing ones from the end, shrinking toward [smallest] until the row fits. Items of no
     * width take no gap.
     */
    fun plan(widths: List<Int>, trailing: List<Boolean>, gaps: List<Int>, available: Int, smallest: (Int) -> Int): Plan {
        val sized = widths.toMutableList()
        val gapCount = (sized.count { it > 0 } - 1).coerceAtLeast(0)
        val natural = sized.sum()
        gaps.firstOrNull { natural.toLong() + it.toLong() * gapCount <= available }?.let { return Plan(it, sized) }
        val gap = gaps.lastOrNull() ?: 0
        var overflow = natural + gap * gapCount - available
        val order = sized.indices.filter { !trailing[it] }.reversed() + sized.indices.filter { trailing[it] }.reversed()
        for (i in order) {
            if (overflow <= 0) break
            if (sized[i] <= 0) continue
            val give = minOf((sized[i] - smallest(i)).coerceAtLeast(0), overflow)
            if (give <= 0) continue
            sized[i] -= give
            overflow -= give
        }
        return Plan(gap, sized)
    }
}

/**
 * The full date and time a post was made, e.g. "Sep 24, 2026 at 9:27 AM" - in the reader's own
 * locale and 12/24-hour setting (iOS's fullTimestamp: medium date, short time).
 */
private fun postTimestampLabel(timestampMs: Long): String {
    val locale = java.util.Locale.getDefault()
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
    val moment = java.util.Date(timestampMs)
    return date.format(moment) + com.kachat.app.util.IosDateStyle.dateTimeJoiner(locale) + time.format(moment)
}

/** X's time on the post you opened: "12:00 PM · Nov 25, 2026", in the reader's locale (iOS
 *  961fc05a `focusedTimestamp`). */
private fun focusedPostTimestampLabel(timestampMs: Long): String {
    val locale = java.util.Locale.getDefault()
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
    val moment = java.util.Date(timestampMs)
    return time.format(moment) + " · " + date.format(moment)
}

@Composable
private fun DeliveryState(post: KaPostDraft, onRetry: (() -> Unit)?) {
    val colors = LocalAppColors.current
    // iOS: "Not confirmed", with the hint that it may have gone through.
    val unconfirmedDescription = stringResource(R.string.delivery_not_confirmed) + ". " + stringResource(R.string.kpui_may_have_gone_through)
    when (post.deliveryStatus) {
        KaPostDraft.Delivery.SENT -> {
            if (post.remoteId == null) return
            // An edit's transaction landing restarts the minute, so a change to an old post
            // gets its own check rather than none at all.
            val sentReference = post.sentAt ?: post.timestamp
            val age = System.currentTimeMillis() - sentReference
            var expired by remember(sentReference) { mutableStateOf(age >= 60_000L) }
            if (!expired) {
                LaunchedEffect(sentReference) {
                    val remaining = 60_000L - (System.currentTimeMillis() - sentReference)
                    if (remaining > 0) delay(remaining)
                    expired = true
                }
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.kpui_posted),
                    tint = LocalAppColors.current.success,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        KaPostDraft.Delivery.PENDING -> com.kachat.app.ui.theme.IosActivityIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.textSecondary,
        )
        // It may have gone through: no Retry, which could post and pay twice (iOS e5a9b11).
        KaPostDraft.Delivery.UNCONFIRMED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .padding(2.dp)
                .semantics { contentDescription = unconfirmedDescription },
        ) {
            Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, tint = LocalAppColors.current.warning, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.delivery_not_confirmed), color = LocalAppColors.current.warning, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        KaPostDraft.Delivery.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = onRetry != null) { onRetry?.invoke() }
                .padding(2.dp),
        ) {
            Icon(Icons.Default.Error, contentDescription = null, tint = LocalAppColors.current.danger, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.kaposts_retry), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/**
 * The Kaspa logo popping in with a spring, spinning once and dissolving back down to the heart -
 * iOS runLikeBurst's keyframes, on Animatables.
 */
@Composable
private fun LikeBurst(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val scale = remember { androidx.compose.animation.core.Animatable(0.2f) }
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(1.5f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f))
        }
        launch { alpha.animateTo(1f, androidx.compose.animation.core.tween(150)) }
        launch { rotation.animateTo(360f, androidx.compose.animation.core.tween(550)) }
        delay(550)
        launch { alpha.animateTo(0f, androidx.compose.animation.core.tween(180)) }
        scale.animateTo(0.5f, androidx.compose.animation.core.tween(180))
    }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
        contentDescription = null,
        modifier = modifier
            .size(18.dp)
            .scale(scale.value)
            .rotate(rotation.value)
            .alpha(alpha.value),
    )
}

@Composable
private fun EngagementAction(
    countdownKey: String?,
    deadline: Long?,
    icon: @Composable () -> Unit,
    count: Int?,
    onTap: () -> Unit,
    onCancel: (String) -> Unit,
    /** The count takes the icon's active colour (iOS tints the whole button). */
    countTint: Color? = null,
    /** Space between the icon and its count: iOS's HStack spacing (4pt; the like button's 5pt). */
    countGap: androidx.compose.ui.unit.Dp = 4.dp,
    /** Off: dimmed to 0.4 and not tappable (iOS `.disabled` + `.opacity(0.4)`). */
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) {
                if (deadline != null && countdownKey != null) onCancel(countdownKey) else onTap()
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (deadline != null) {
            UndoBadge()
        } else {
            icon()
        }
        if (count != null && count > 0) {
            Spacer(modifier = Modifier.width(countGap))
            // Raw integers, as on iOS - no "1.2K". Never wraps; truncates only when even the
            // row's tightest gap can't fit it (KaPostActionRowLayout, audit 2026-11-24 #24).
            Text(
                text = "$count",
                color = countTint ?: colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Takes an icon's place while its undo window runs. Tap = cancel.
 *
 * Deliberately NO seconds and no ring. The ticking number belongs in the undo toast, which is the
 * one place it can be read without hunting for the row it came from; on the icon it turned a
 * static engagement row into digits counting down, and cost every pending cell a recomposition
 * ten times a second to do it. The row just says "you can still take this back".
 */
@Composable
private fun UndoBadge() {
    // iOS countdownButton: the undo arrow inside an orange-outlined capsule.
    val orange = Color(0xFFFF9500)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .border(1.dp, orange.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = stringResource(R.string.l53_undo),
            tint = orange,
            modifier = Modifier.size(13.dp),
        )
    }
}

// MARK: - Composer with the 25k ring meter

/**
 * Keeps the text caret inside [scroll]'s viewport.
 *
 * Compose foundation 1.6's legacy `BasicTextField` only asks an ancestor scroller to reveal the
 * caret ONCE, when the field gains focus (`CoreTextField` -> `bringSelectionEndIntoView`). After
 * that the caret is kept visible purely by the field's OWN internal scroller, and that scroller
 * skips its `coerceOffset` unless the caret rect itself moved - a shrinking container just clamps
 * the stale offset. So anything that shrinks the composer without the user typing (the IME
 * animating in, the @mention list appearing, a thread segment being stacked, a quote card being
 * attached) leaves the caret below the visible area, behind the keyboard.
 *
 * Running the field unbounded inside a real scroll container and driving that container from BOTH
 * the caret offset and [ScrollState.viewportSize] closes the hole: a viewport change is as good a
 * reason to re-reveal the caret as a keystroke. No keyboard-height arithmetic is involved - the
 * viewport already shrank because the composer is inset-padded for the IME.
 *
 * @param textTopPaddingPx distance from the top of the scroll content to the first text line
 *        (the field's own top padding), since the caret rect is relative to the text.
 */
@Composable
private fun KeepCaretVisible(
    scroll: ScrollState,
    value: TextFieldValue,
    layout: TextLayoutResult?,
    textTopPaddingPx: Int,
) {
    // The IME inset is read as a key so the reveal re-runs while the keyboard ANIMATES in, not
    // just once when it is already up. Without this the effect could compute its target against
    // a viewport that was still shrinking and land short.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(value.selection, value.text.length, layout, scroll.viewportSize, scroll.maxValue, imeBottom) {
        val result = layout ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        // Writing at the very end - which is what composing a post IS - always pins the view to
        // the bottom. This does not depend on the caret rect, the text height or the padding
        // arithmetic below being fresh in the same frame, which is the fragile part when several
        // of those settle across different frames while the keyboard is opening.
        if (value.selection.collapsed && value.selection.end >= value.text.length) {
            if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
            return@LaunchedEffect
        }
        val caret = runCatching {
            result.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        }.getOrNull() ?: return@LaunchedEffect
        val caretTop = caret.top + textTopPaddingPx
        val caretBottom = caret.bottom + textTopPaddingPx
        val current = scroll.value.toFloat()
        // Same padding again as breathing room, so the line being typed never sits flush against
        // the edge of the card (the scroll content already reserves it at both ends).
        val target = when {
            caretBottom > current + viewport -> caretBottom + textTopPaddingPx - viewport
            caretTop < current -> caretTop - textTopPaddingPx
            else -> return@LaunchedEffect
        }
        val max = scroll.maxValue
        if (max <= 0) return@LaunchedEffect
        scroll.scrollTo(target.roundToInt().coerceIn(0, max))
    }
}

@Composable
fun KaPostComposerDialog(
    title: String,
    quoted: KaPostDraft?,
    quotedDisplayName: String = "",
    /** Overrides the submit button's wording. A reply says "Reply", not "Post". */
    submitLabel: String? = null,
    /** The card under the editor is the post being ANSWERED, not quoted: "Post your reply". */
    isReply: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    /** Enables @mention autocomplete (contacts' .kachat names) when provided. */
    viewModel: KaPostsViewModel? = null,
    /** Enables X-style thread posting (+ stacks segments; Post All submits the chain). */
    onSubmitThread: ((List<String>) -> Unit)? = null,
    /** Set when opened from a saved draft, so re-saving updates it rather than piling up copies. */
    editingDraftId: String? = null,
    initialText: String = "",
    initialThreadSegments: List<String> = emptyList(),
    /** An undone poll's options: the composer reopens with Poll on and these filled in (iOS
     *  2c9e1ec). */
    initialPollOptions: List<String> = emptyList(),
    /** Called with the composer's contents when the user chooses Save Draft on close. */
    onSaveDraft: ((String, List<String>) -> Unit)? = null,
    /** Posts a poll instead of a plain post: the question, its options, and when voting closes
     *  (unix ms). Only passed by the new-post composer - a reply, quote, edit or thread has no
     *  poll (iOS's `extrasAvailable`). */
    onSubmitPoll: ((String, List<String>, Long) -> Unit)? = null,
    /** Sends the post later instead of now: the text and when it goes out (unix ms). Same
     *  fresh-post-only rule as the poll. */
    onSchedulePost: ((String, Long) -> Unit)? = null,
    /** A draft's source post hasn't loaded: why, and Post is off until it has (iOS ee5ec98). */
    sourceNotice: String? = null,
    /** Off when editing a post: its text stays as it is (iOS `!editingPost`). */
    attachesChallenge: Boolean = true,
) {
    val colors = LocalAppColors.current
    // A KaPlay challenge link (a bot's or an arcade score's) the post carries: taken out of the
    // text and shown as the card the feed will draw; it goes back on the end when posted (iOS
    // 518fdc8c `attachedChallenge`, KaPostComposerChallenge).
    val initialChallenge = remember { if (attachesChallenge) KaPostComposerChallenge.detach(initialText) else null }
    var attachedChallenge by remember { mutableStateOf(initialChallenge?.first) }
    // TextFieldValue rather than String: the caret offset is what [KeepCaretVisible] below needs
    // to scroll the editor to, and the String overload never exposes it.
    var text by remember { mutableStateOf(TextFieldValue(initialChallenge?.second ?: initialText)) }
    // A challenge link pasted into the text becomes the post's card too, a moment after typing
    // stops (one challenge a post: the feed draws only the first).
    LaunchedEffect(text.text) {
        if (!attachesChallenge || attachedChallenge != null) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        KaPostComposerChallenge.detach(text.text)?.let { (link, rest) ->
            attachedChallenge = link
            text = TextFieldValue(rest)
        }
    }
    var threadSegments by remember { mutableStateOf(initialThreadSegments) }
    // Poll: two to four options and how long voting stays open (iOS's composer state).
    var pollEnabled by remember { mutableStateOf(initialPollOptions.isNotEmpty()) }
    var pollOptions by remember {
        mutableStateOf(
            when {
                initialPollOptions.isEmpty() -> listOf("", "")
                initialPollOptions.size >= 2 -> initialPollOptions.take(4)
                else -> initialPollOptions + List(2 - initialPollOptions.size) { "" }
            }
        )
    }
    var pollDurationHours by remember { mutableIntStateOf(24) }
    // Scheduling: when this post goes out instead of now, and the picker that sets it.
    var scheduledAtMs by remember { mutableStateOf<Long?>(null) }
    var showSchedulePicker by remember { mutableStateOf(false) }
    // Closing with something written used to throw it away silently, which is the whole reason
    // drafts exist.
    var showCloseOptions by remember { mutableStateOf(false) }
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    // Counted in UTF-8 bytes: a post is paid for and capped by its bytes (iOS 369794e postLength).
    val charCount = remember(text.text) { KaPostDraft.postLength(text.text) }
    val totalSegments = threadSegments.size + (if (text.text.isNotBlank() || attachedChallenge != null) 1 else 0)
    // A poll is offered on a fresh post only - never a reply, a quote, an edit or a thread.
    val pollAvailable = onSubmitPoll != null && quoted == null && !isReply && threadSegments.isEmpty()
    val scheduleAvailable = onSchedulePost != null && quoted == null && !isReply && threadSegments.isEmpty()
    val validPollOptions = pollOptions.map { it.trim() }.filter { it.isNotEmpty() }
    val pollIsValid = validPollOptions.size in com.kachat.app.services.KaPostsService.POLL_MIN_OPTIONS..com.kachat.app.services.KaPostsService.POLL_MAX_OPTIONS &&
        validPollOptions.toSet().size == validPollOptions.size &&
        validPollOptions.all { com.kachat.app.util.KaPostsProtocol.pollOptionScalarCount(it) <= com.kachat.app.util.KaPostsProtocol.POLL_OPTION_MAX_SCALARS }
    val canPost = when {
        sourceNotice != null -> false
        pollEnabled -> text.text.isNotBlank() && charCount <= limit && pollIsValid
        else -> totalSegments > 0 && charCount <= limit
    }
    // Not while a time is set or for a reply (iOS): a scheduled post is one post, and the
    // stacked segments would be lost.
    val threadingEnabled = onSubmitThread != null && quoted == null && !pollEnabled && scheduledAtMs == null && !isReply
    val editorFocus = remember { FocusRequester() }
    var editorFocused by remember { mutableStateOf(false) }
    val hapticView = LocalView.current
    val showFeeEstimate by (viewModel?.showFeeEstimate?.collectAsState() ?: remember { mutableStateOf(false) })

    /** Anything written is worth asking about (iOS hasUnsavedContent). */
    fun requestClose() {
        val hasContent = (listOf(KaPostComposerChallenge.postBody(text.text, attachedChallenge)) + threadSegments).any { it.isNotBlank() }
        if (hasContent && onSaveDraft != null) showCloseOptions = true else onDismiss()
    }

    // The editor is what this screen is for: it takes focus as it appears (iOS isFocused = true).
    LaunchedEffect(Unit) { runCatching { editorFocus.requestFocus() } }
    // The @token being typed at the END of the text ("" right after "@"), or null.
    // Only with mentions on: off, there are no suggestions to look anything up for (audit M15).
    val mentionQuery = remember(text.text) {
        if (!com.kachat.app.util.MentionsFeature.ENABLED) return@remember null
        com.kachat.app.util.KaPostsMentionToken.query(text.text)
    }
    // Anyone with a .kachat name: debounce-resolve the typed query live (iOS 518fdc8c).
    var resolvedAnyDomain by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mentionQuery) {
        resolvedAnyDomain = null
        val query = mentionQuery ?: return@LaunchedEffect
        if (query.length < 2 || viewModel == null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        resolvedAnyDomain = viewModel.resolveMentionQuery(query)
    }
    // Follows, recent posters and contacts with a username, plus the looked-up name (iOS
    // KaPostMentionSources, 157790b0). Worked out on every draw, not remembered: names landing
    // (the registry's revision) redraw the list, so people show as their usernames arrive.
    // (read here so this draw follows both: the follow set and the names registry)
    viewModel?.following?.collectAsState()?.value
    com.kachat.app.services.kachatnames.KachatNamesRegistry.identityRevision.collectAsState().value
    val mentionSuggestions = run {
        val query = mentionQuery
        if (query == null || viewModel == null) emptyList()
        else viewModel.kachatMentionCandidates(query, resolvedAnyDomain)
    }

    Dialog(
        // System back goes through the same "Save this post?" question as the X - a dismiss that
        // throws written text away is the one thing the drafts flow exists to prevent.
        onDismissRequest = { requestClose() },
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(KaPostsOverlayInsets),
        ) {
            if (showCloseOptions) {
                ActionSheetContainer(
                    title = stringResource(R.string.kpui_save_this_post),
                    subtitle = null,
                    onDismiss = { showCloseOptions = false },
                ) {
                    ActionSheetRow(
                        icon = Icons.Default.Save,
                        title = stringResource(R.string.kpui_save_draft),
                        subtitle = stringResource(R.string.kpui_save_draft_subtitle),
                    ) {
                        showCloseOptions = false
                        onSaveDraft?.invoke(
                            if (attachedChallenge == null) text.text else KaPostComposerChallenge.postBody(text.text, attachedChallenge),
                            threadSegments,
                        )
                        onDismiss()
                    }
                    ActionSheetRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.kpui_discard),
                        subtitle = stringResource(R.string.kpui_discard_subtitle),
                        tint = LocalAppColors.current.danger,
                    ) {
                        showCloseOptions = false
                        onDismiss()
                    }
                }
            }
            // Same dot/balance row every other KaPosts overlay carries, above the X. A post
            // costs KAS, so this is the one screen where the balance is not just reassurance,
            // and the composer covers the feed that would otherwise be showing both.
            KaPostsOverlayStatusBar()
            // Header, matching iOS/desktop's composer card: X in a rounded square, bold title,
            // character meter, teal capsule Post button.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .clickable { requestClose() }
                        .padding(10.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (threadSegments.isNotEmpty() && quoted == null) stringResource(R.string.kpui_new_thread) else title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                KaPostCharacterMeter(count = charCount)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    when {
                        pollEnabled -> stringResource(R.string.kpui_post_poll)
                        scheduledAtMs != null -> stringResource(R.string.kpui_schedule)
                        totalSegments > 1 -> stringResource(R.string.kpui_post_all, totalSegments)
                        else -> submitLabel ?: stringResource(R.string.kpui_post)
                    },
                    color = if (canPost) Color.Black else colors.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (canPost) KaspaTeal else colors.surface)
                        .clickable(enabled = canPost) {
                            // with the attached challenge link on the end
                            val trimmed = KaPostComposerChallenge.postBody(text.text, attachedChallenge)
                            if (pollEnabled && onSubmitPoll != null) {
                                if (trimmed.isEmpty() || !pollIsValid) return@clickable
                                onSubmitPoll(
                                    trimmed,
                                    validPollOptions,
                                    com.kachat.app.util.KaPostsProtocol.pollClosesAtMs(System.currentTimeMillis(), pollDurationHours),
                                )
                                return@clickable
                            }
                            val whenMs = scheduledAtMs
                            if (whenMs != null && onSchedulePost != null) {
                                if (trimmed.isEmpty()) return@clickable
                                onSchedulePost(trimmed, whenMs)
                                return@clickable
                            }
                            val segments = threadSegments + (if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList())
                            if (segments.isEmpty()) return@clickable
                            if (segments.size > 1 && onSubmitThread != null) onSubmitThread(segments)
                            else onSubmit(segments.first())
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            // Already-stacked thread segments (X-style), numbered and removable.
            if (threadSegments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    threadSegments.forEachIndexed { index, segment ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surface)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "${index + 1}",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(KaspaTeal.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                segment,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "×",
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .clickable {
                                        threadSegments = threadSegments.filterIndexed { i, _ -> i != index }
                                    }
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.surfaceVariant)
            }
            // @mention autocomplete: a SCROLLABLE vertical list of the usernames of people you
            // follow, recent posters and contacts (plus a live-resolved match), iOS/group-chat style.
            // Above the editor so the keyboard can never hide it.
            if (com.kachat.app.util.MentionsFeature.ENABLED && mentionSuggestions.isNotEmpty()) {
                KaPostMentionSuggestionList(
                    suggestions = mentionSuggestions,
                    onPick = { domain ->
                        val replaced = text.text.replace(MENTION_REPLACE_REGEX, "@$domain ")
                        // Caret to the end of the inserted mention, otherwise it would
                        // snap back to offset 0 and the editor would scroll to the top.
                        text = TextFieldValue(replaced, TextRange(replaced.length))
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Bordered editor card with the X-style + floating in its corner: tapping + stacks
            // the current text as a thread segment and clears the editor for the next post.
            //
            // The card is the SCROLL VIEWPORT and the field inside it grows with the text, rather
            // than the field being fillMaxSize() and relying on BasicTextField's own scroller.
            // That internal scroller (foundation's TextFieldScrollerPosition.update) only re-scrolls
            // to the caret when the CARET rect moves - when the container shrinks under it the old
            // offset is merely clamped, so every shrink that is not caused by typing (the IME
            // opening, the @mention list appearing, a thread segment being stacked, the quote card
            // arriving) left the caret parked below the visible area, i.e. behind the keyboard.
            // KeepCaretVisible below re-runs on viewport changes as well as caret changes.
            val editorScroll = rememberScrollState()
            var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val editorDensity = LocalDensity.current
            KeepCaretVisible(
                scroll = editorScroll,
                value = text,
                layout = editorLayout,
                textTopPaddingPx = with(editorDensity) { 12.dp.roundToPx() },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // A modest box at the top that grows with what you write, matching iOS's
                    // 120pt floor - NOT the whole screen. weight(1f) here made the editor eat
                    // every remaining pixel: an empty composer was one enormous empty box, and
                    // when quoting it pushed the quoted post off the bottom edge so you could
                    // not see what you were replying to. Capped so a long post scrolls inside
                    // the box rather than walking the toolbar off the screen.
                    // iOS: a 120pt floor that grows with the text, whatever is under it (a quoted
                    // or answered post included); capped so a long post scrolls inside the box.
                    .heightIn(min = 120.dp, max = if (quoted != null) 220.dp else 300.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(editorScroll),
                ) {
                    BasicTextField(
                        value = text,
                        // Hard cap at the limit, X-style: a paste that overflows is clipped to
                        // the first 25,000 characters rather than dropped (iOS prefix).
                        onValueChange = {
                            // Someone's payment request pasted in opens Send Kaspa instead (iOS
                            // c6851d6 PaymentRequestAwareTextView).
                            if (!PaymentRequestPaste.interceptInsert(text.text, it.text)) {
                                text = clampToCharacterLimit(it, limit)
                            }
                        },
                        onTextLayout = { editorLayout = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Short drafts still fill the whole card, so a tap anywhere inside
                            // the border lands in the field. A CONSTANT, deliberately, not the
                            // box's measured height: the box now sizes to its content, so
                            // feeding its height back in as the content's minimum would ratchet
                            // it up a frame at a time until it hit the cap. 120dp box minus its
                            // 12dp padding top and bottom.
                            .heightIn(min = 96.dp)
                            .padding(12.dp)
                            .focusRequester(editorFocus)
                            .onFocusChanged { editorFocused = it.isFocused },
                        decorationBox = { inner ->
                            if (text.text.isEmpty()) {
                                Text(
                                    when {
                                        isReply -> stringResource(R.string.kpui_post_your_reply)
                                        quoted != null -> stringResource(R.string.kpui_add_a_comment)
                                        threadSegments.isEmpty() -> stringResource(R.string.kpui_whats_happening)
                                        else -> stringResource(R.string.kpui_add_another_post)
                                    },
                                    color = colors.textSecondary,
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
            // The attached challenge as the card the feed will draw, with an x that takes it off.
            attachedChallenge?.let { link ->
                KaPostComposerChallengeCard(link, onRemove = { attachedChallenge = null })
            }
            // A Kaspa Hub card's link in the text: the card the post will show, with an x that
            // takes the link out (iOS 961fc05a). Drawn without Open, so a tap can't leave the
            // post being written (audit 2026-11-25 L1).
            val composerHubLink = remember(text.text) {
                KaChatLink.findFirst(text.text)?.takeIf { it.ref is KaChatLinkRef.HubSection }
            }
            composerHubLink?.let { match ->
                val removeLabel = stringResource(R.string.remove)
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp)) {
                    KaspaHubLinkCard(tab = (match.ref as KaChatLinkRef.HubSection).tab, interactive = false)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                val rest = text.text.replaceFirst(match.raw, "").trim()
                                text = TextFieldValue(rest, androidx.compose.ui.text.TextRange(rest.length))
                            }
                            .semantics { contentDescription = removeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            // Poll and Schedule, as chips under the editor - only on a fresh post (iOS's extrasRow).
            if (pollAvailable || scheduleAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (pollEnabled) KaspaTeal else KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                pollEnabled = !pollEnabled
                                // A poll goes out now: it can't keep a scheduled time (iOS), and
                                // the options typed before stay.
                                if (pollEnabled) scheduledAtMs = null
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = null,
                            tint = if (pollEnabled) Color.Black else KaspaTeal,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (pollEnabled) stringResource(R.string.kpui_remove_poll) else stringResource(R.string.kpui_poll),
                            color = if (pollEnabled) Color.Black else KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                    if (scheduleAvailable) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (scheduledAtMs != null) KaspaTeal else KaspaTeal.copy(alpha = 0.15f))
                                .then(if (pollEnabled) Modifier else Modifier.clickable { showSchedulePicker = true })
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                .alpha(if (pollEnabled) 0.4f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = if (scheduledAtMs != null) Color.Black else KaspaTeal,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (scheduledAtMs == null) stringResource(R.string.kpui_schedule) else stringResource(R.string.kpui_reschedule),
                                color = if (scheduledAtMs != null) Color.Black else KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
            // "Scheduled for ...", with a way to take it back.
            scheduledAtMs?.let { whenMs ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.kaposts_scheduled_for_toast, formatScheduledTime(whenMs, com.kachat.app.util.is24HourClock())),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.kpui_post_now_instead),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp).clickable { scheduledAtMs = null },
                    )
                }
            }
            if (showSchedulePicker) {
                KaPostSchedulePicker(
                    initialMs = scheduledAtMs ?: (System.currentTimeMillis() + 60 * 60 * 1000L),
                    onDismiss = { showSchedulePicker = false },
                    onPicked = { picked ->
                        scheduledAtMs = picked
                        pollEnabled = false
                        showSchedulePicker = false
                    },
                )
            }
            // The poll's options and how long it stays open, under the question.
            if (pollEnabled) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pollOptions.forEachIndexed { index, option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = option,
                                onValueChange = { fresh ->
                                    pollOptions = pollOptions.toMutableList().also {
                                        it[index] = com.kachat.app.util.KaPostsProtocol.prefixPollOptionScalars(fresh)
                                    }
                                },
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(KaspaTeal),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, colors.textPrimary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                decorationBox = { inner ->
                                    if (option.isEmpty()) {
                                        Text(stringResource(R.string.kpui_option_n, index + 1), color = colors.textSecondary, fontSize = 15.sp)
                                    }
                                    inner()
                                },
                            )
                            if (pollOptions.size > com.kachat.app.services.KaPostsService.POLL_MIN_OPTIONS) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = stringResource(R.string.kpui_remove_option),
                                    tint = colors.textSecondary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            pollOptions = pollOptions.toMutableList().also { it.removeAt(index) }
                                        },
                                )
                            }
                        }
                    }
                    if (pollOptions.size < com.kachat.app.services.KaPostsService.POLL_MAX_OPTIONS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { pollOptions = pollOptions + "" },
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.kpui_add_option), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.kpui_poll_length), color = colors.textPrimary, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        var showLengths by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.surface)
                                    .clickable { showLengths = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(POLL_LENGTHS.first { it.second == pollDurationHours }.first),
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = showLengths, onDismissRequest = { showLengths = false }) {
                                POLL_LENGTHS.forEach { (label, hours) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label), color = colors.textPrimary) },
                                        onClick = { pollDurationHours = hours; showLengths = false },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.kpui_poll_footer),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            // Below the field, not floating inside it. As an overlay it covered the bottom-right
            // of the text area, so a post long enough to reach that corner ran underneath it -
            // the one place in the composer where your own words could be hidden.
            if (threadingEnabled && text.text.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                threadSegments = threadSegments + text.text.trim()
                                text = TextFieldValue("")
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = KaspaTeal,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.kpui_add_to_thread), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            // A draft's source post hasn't loaded: why Post is off (iOS ee5ec98).
            sourceNotice?.let { notice ->
                Text(
                    notice,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            }
            // Live network-fee estimate while typing (Settings > Show Fee Estimate), matching
            // the chat composer's behaviour (iOS feeEstimateRow).
            // Worked out once typing pauses (the first letter at once), and when the challenge
            // or the setting changes: it encodes the post and prices it, which ran on every
            // keystroke (iOS 01f71184 P6 `refreshFeeEstimate`).
            val trimmedForFee = KaPostComposerChallenge.postBody(text.text, attachedChallenge)
            var feeEstimate by remember { mutableStateOf<Long?>(null) }
            var feeSourceSeen by remember { mutableStateOf(false) }
            fun refreshFeeEstimate() {
                val body = KaPostComposerChallenge.postBody(text.text, attachedChallenge)
                val estimate = if (showFeeEstimate && body.isNotEmpty() && viewModel != null) viewModel.estimatePostFeeSompi(body) else null
                if (estimate != feeEstimate) feeEstimate = estimate
            }
            LaunchedEffect(text.text) {
                if (!feeSourceSeen && text.text.isNotEmpty()) {
                    feeSourceSeen = true
                    refreshFeeEstimate()
                    return@LaunchedEffect
                }
                delay(500)
                refreshFeeEstimate()
            }
            LaunchedEffect(attachedChallenge, showFeeEstimate) { refreshFeeEstimate() }
            val fee = if (showFeeEstimate && trimmedForFee.isNotEmpty()) feeEstimate else null
            if (fee != null) {
                Text(
                    KaspaUnit.label(stringResource(R.string.kpui_est_fee, "%.8f".format(java.util.Locale.US, fee / 100_000_000.0))),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            quoted?.let {
                // Quoting a plain repost (no words of its own) shows the post it reposted, as the
                // feed's quote card does (iOS 01f71184 `quotedPostCard`).
                val reposted = if (it.text.isBlank()) it.quoted else null
                val address = reposted?.posterAddress ?: it.posterAddress
                val name = reposted?.let { r -> viewModel?.posterDisplayName(r.posterAddress) } ?: quotedDisplayName
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    QuotedEmbedCard(
                        quoted = KaPostDraft.QuotedRef(
                            remoteId = reposted?.remoteId ?: it.remoteId,
                            text = reposted?.text ?: it.text,
                            posterAddress = address,
                            timestamp = if (reposted == null) it.timestamp else reposted.timestamp,
                        ),
                        displayName = name,
                        avatarSize = 22.dp,
                        timeMs = if (reposted == null) it.timestamp else reposted.timestamp,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // Last in the column, so it rides directly on top of the keyboard, where a formatting
            // bar belongs - and only while the editor has focus, as on iOS: a permanent row of
            // eight icons with nothing to act on is clutter.
            if (editorFocused) {
                MarkdownFormattingToolbar { action ->
                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val edit = KaPostsMarkdown.apply(
                        action,
                        text.text,
                        text.selection.min,
                        text.selection.max,
                    )
                    text = clampToCharacterLimit(
                        TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)),
                        limit,
                    )
                }
            }
        }
    }
}

/** Clips [value] to the post limit in UTF-8 bytes ([KaPostDraft.postLength]), at a character
 *  boundary (no half characters), keeping the caret inside the text (iOS 369794e
 *  `clampedToPostLimit`). */
private fun clampToCharacterLimit(value: TextFieldValue, limit: Int): TextFieldValue {
    val text = value.text
    // A UTF-16 unit is at most 3 UTF-8 bytes: anything this short can't be over.
    if (text.length * 3 <= limit) return value
    if (KaPostDraft.postLength(text) <= limit) return value
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var bytes = 0
    var start = 0
    var end = 0
    while (true) {
        val next = iterator.next()
        if (next == java.text.BreakIterator.DONE) break
        bytes += KaPostDraft.postLength(text.substring(start, next))
        if (bytes > limit) break
        end = next
        start = next
    }
    val clipped = text.substring(0, end)
    return TextFieldValue(
        clipped,
        TextRange(value.selection.start.coerceAtMost(clipped.length), value.selection.end.coerceAtMost(clipped.length)),
    )
}

/**
 * The @mention list above an editor: the usernames of people you follow, recent posters and
 * contacts plus a live-resolved match ([com.kachat.app.util.KaPostMentionSources]). Short lists hug their content; longer ones scroll at a fixed
 * height (~4.5 rows, so it visibly reads as scrollable). iOS KaPostMentionSuggestionBar.
 */
@Composable
private fun KaPostMentionSuggestionList(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 280.dp)
            .then(if (suggestions.size > 4) Modifier.height(168.dp) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .border(1.dp, colors.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        suggestions.forEachIndexed { index, domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(domain) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text("@", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(domain, color = colors.textPrimary, fontSize = 14.sp)
            }
            if (index != suggestions.lastIndex) {
                HorizontalDivider(color = colors.surfaceVariant)
            }
        }
    }
}

/**
 * The formatting buttons above the keyboard.
 *
 * Exists because the syntax is only discoverable if you already know it: someone who has never
 * typed `~~` cannot guess that it means strikethrough. Every button toggles, and every one of them
 * writes the same markers a person could have typed by hand, so the two ways of formatting a post
 * produce identical text.
 */
@Composable
private fun MarkdownFormattingToolbar(onAction: (KaPostsMarkdown.ToolbarAction) -> Unit) {
    val colors = LocalAppColors.current
    // Order runs from the formatting people reach for most to the least.
    val items = listOf(
        Triple(KaPostsMarkdown.ToolbarAction.BOLD, Icons.Default.FormatBold, stringResource(R.string.kpui_md_bold)),
        Triple(KaPostsMarkdown.ToolbarAction.ITALIC, Icons.Default.FormatItalic, stringResource(R.string.kpui_md_italic)),
        Triple(KaPostsMarkdown.ToolbarAction.UNDERLINE, Icons.Default.FormatUnderlined, stringResource(R.string.kpui_md_underline)),
        Triple(KaPostsMarkdown.ToolbarAction.STRIKETHROUGH, Icons.Default.StrikethroughS, stringResource(R.string.kpui_md_strikethrough)),
        Triple(KaPostsMarkdown.ToolbarAction.BULLET_LIST, Icons.Default.FormatListBulleted, stringResource(R.string.kpui_md_bulleted_list)),
        Triple(KaPostsMarkdown.ToolbarAction.NUMBERED_LIST, Icons.Default.FormatListNumbered, stringResource(R.string.kpui_md_numbered_list)),
        Triple(KaPostsMarkdown.ToolbarAction.SUBTEXT, Icons.Default.FormatSize, stringResource(R.string.kpui_md_small_text)),
        Triple(KaPostsMarkdown.ToolbarAction.LINK, Icons.Default.Link, stringResource(R.string.link_menu_title)),
    )
    HorizontalDivider(color = colors.textPrimary.copy(alpha = 0.08f))
    // Scrolls rather than squeezing: eight buttons at a comfortable tap size do not fit a narrow
    // phone, and shrinking them to fit would make them hard to hit.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (action, icon, label) ->
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * X-style ring meter: fills toward the 25,000 limit, flips orange in the final 10% with a live
 * remaining count, red at the wall. Hidden while empty. [count] is [KaPostDraft.postLength] of
 * the text: bytes, one per English character.
 */
@Composable
fun KaPostCharacterMeter(count: Int) {
    val colors = LocalAppColors.current
    val limit = KaPostDraft.POST_CHARACTER_LIMIT
    val progress = (count.toFloat() / limit).coerceIn(0f, 1f)
    val remaining = limit - count
    val nearLimit = progress >= 0.9f
    val ringColor = when {
        remaining <= 0 -> Color(0xFFE53935)
        nearLimit -> Color(0xFFFFA726)
        else -> KaspaTeal
    }
    // Invisible rather than absent while empty, so the header does not shift when the first
    // character is typed (iOS keeps the slot with opacity 0).
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (count == 0) 0f else 1f)) {
        if (nearLimit) {
            Text(
                text = "$remaining",
                color = ringColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Canvas(modifier = Modifier.size(20.dp)) {
            drawCircle(
                color = colors.surfaceVariant,
                style = Stroke(width = 2.5.dp.toPx()),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

// MARK: - Thread overlay (ancestors + root + conversation + comments, X-style)

/**
 * Rendered as a full-screen overlay INSIDE the KaPosts composition, deliberately not as a
 * `Dialog`.
 *
 * A Compose `Dialog` with `usePlatformDefaultWidth = false` never gets a MATCH_PARENT window:
 * `DialogLayout` measures its content against `Configuration.screenHeightDp` and then calls
 * `window.setLayout(child.measuredWidth, child.measuredHeight)` on every layout pass. Under this
 * app's enforced edge-to-edge (targetSdk 36) the resulting window ends up shorter than the content
 * being laid out inside it, so the bottom of that content - the reply composer - was clipped off
 * the bottom of the screen, and no amount of inset padding could fix it because the window itself
 * was the wrong size. In the ordinary composition the overlay is measured by the same layout path
 * as every other screen in the app, which lays out correctly.
 *
 * Layout mirrors iOS's thread sheet: the chain above (full cells joined by a thread line), the
 * focused post, the author's continuation ("Thread") or an unbranched exchange ("Conversation"),
 * a "Comments (N)" header, the comments with one level of inline expansion, and a small reply box
 * pinned under it all.
 */
@Composable
fun KaPostThreadOverlay(
    postId: String,
    /** Whether this level slides in from the right: true when it was opened from the level
     *  beneath, false when it was revealed by backing out of a deeper one (iOS 5e1da72). */
    slidesIn: Boolean = true,
    /** The chain above this post, oldest first - see the call site. */
    ancestors: List<KaPostDraft> = emptyList(),
    onJumpToAncestor: (KaPostDraft) -> Unit = {},
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    /**
     * Answering a SPECIFIC post in this thread - an ancestor above it, or one of the replies
     * below. Opens the "Reply to Post" composer with that post under the editor, as on iOS and
     * desktop. The inline box at the bottom stays for answering the post you opened.
     */
    onReplyToComment: (KaPostDraft) -> Unit = {},
    /** RemoteId of a reply to scroll to once it lands (reply landings open the PARENT's thread
     *  and hand the reply's txid through here). */
    focusReplyRemoteId: String? = null,
    onFocusReplyHandled: () -> Unit = {},
    /** The reply box's text, hoisted so it survives walking up and down the thread (iOS); read
     *  only inside the box ([ThreadReplyComposer]). */
    replyText: androidx.compose.runtime.MutableState<TextFieldValue>,
) {
    val colors = LocalAppColors.current
    // Resolving against the post tree (rather than taking a KaPostDraft parameter) is what
    // makes the thread live: fetched replies, inline-expanded sub-threads and optimistic
    // replies all land inside the view model's post lists, and this re-reads them. The tree
    // is deliberately NOT read in composition: it re-emits on every background mutation
    // (feed polls, sender-profile fetches, undo timers anywhere in KaPosts), and each
    // emission recomposed this whole overlay — which is what made the reply box need several
    // taps before it would focus (a tap landing mid-recomposition gets its press cancelled).
    // Collecting in an effect and republishing only STRUCTURALLY CHANGED posts means the
    // overlay recomposes exactly when this thread's content actually changed.
    var postState by remember(postId) { mutableStateOf(viewModel.findPost(postId)) }
    LaunchedEffect(postId) {
        viewModel.postTree.collect {
            val fresh = viewModel.findPost(postId)
            if (fresh != postState) postState = fresh
        }
    }
    val post = postState
    // Deleting the post this thread is about leaves nothing to read here, so the thread closes
    // itself rather than sitting on the "couldn't find it" state (iOS closeThread).
    var everResolved by remember(postId) { mutableStateOf(postState != null) }
    LaunchedEffect(post) {
        if (post != null) everResolved = true else if (everResolved) onClose()
    }
    // System back closes this level of the thread, matching the Back button in the header.
    // Nested pushes each get their own overlay instance, so back walks the thread stack down
    // one level at a time.
    BackHandler(onBack = onClose)
    if (post == null) {
        // An id this view cannot resolve (the feed refresh dropped it, the network never
        // answered): say so rather than showing a blank screen (iOS).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> } },
        ) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(KaPostsOverlayInsets)) {
                CompositionLocalProvider(LocalKaPostsBack provides onClose) { KaPostsOverlayStatusBar() }
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.kpui_post_not_loaded), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.kpui_post_not_loaded_body),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }
    // Zero-balance funding gate — tapping the reply composer while the chatting balance is a
    // confirmed 0 KAS opens the shared funding card instead of the reply field/keyboard.
    val fundingGate = rememberZeroBalanceFundingGate()
    var showFundingGate by remember { mutableStateOf(false) }
    var expandedIds by remember(postId) { mutableStateOf(setOf<String>()) }
    val muted by viewModel.muted.collectAsState()
    val blocked by viewModel.blocked.collectAsState()
    val hidden = remember(muted, blocked) { muted + blocked }
    // Not a muted or blocked person's posts: the reply lists already leave them out, and the
    // chain above put them back (iOS 369794e).
    val visibleAncestors = remember(ancestors, hidden) { ancestors.filter { it.posterAddress !in hidden } }
    // The author's own continuation renders as a connected Thread section under the root;
    // its segments are excluded from the comment list (segment 2 IS a direct reply).
    // Sliced per post: another thread's chain landing no longer recomposes this overlay.
    val threadChain by viewModel.threadChains.collectSelectedAsState(post.id) { it[post.id].orEmpty() }
    val chainRemoteIds = remember(threadChain) { threadChain.mapNotNull { it.remoteId }.toSet() }
    // Everyone else's replies in X's order (iOS 961fc05a `commentsExcludingThread`, owner
    // 2026-11-25: "match X"): the poster's own first, then yours, then people you follow, then
    // the rest, the most liked first within each. Ranked when they first show and then kept while
    // this post is open ([CommentOrderMemo], audit 2026-11-25 L4), so rows don't move as pages and
    // likes land; closed, the memo goes with this screen and the next opening ranks afresh.
    val commentOrder = remember(postId) { com.kachat.app.viewmodels.CommentOrderMemo() }
    val following by viewModel.following.collectAsState()
    val me = remember(viewModel) { viewModel.myAddress().orEmpty() }
    val visibleComments = remember(post, hidden, chainRemoteIds, following) {
        val replies = post.comments.filter {
            it.posterAddress !in hidden && (it.remoteId == null || it.remoteId !in chainRemoteIds)
        }
        // who you follow, looked up once here rather than inside the ranking
        val followed = replies.mapTo(HashSet()) { it.posterAddress }.filterTo(HashSet()) { it in following }
        val now = System.currentTimeMillis()
        commentOrder.ordered(
            replies,
            post.id,
            rank = { arrived -> com.kachat.app.viewmodels.KaPostsConversation.rankReplies(arrived, post.posterAddress, me, followed) },
            goesUp = { com.kachat.app.viewmodels.KaPostsConversation.goesUp(it, me, now) },
            isTopTier = { com.kachat.app.viewmodels.KaPostsConversation.isTopTier(it, post.posterAddress, me) },
        )
    }

    LaunchedEffect(post.remoteId) {
        viewModel.loadReplies(post)
        viewModel.loadSelfThreadChain(post)
        // The real chain above this post, not just the levels tapped through to reach it.
        viewModel.loadAncestors(post)
    }

    // Answering a reply inside this thread (someone's reply to the post): open its replies, so
    // yours shows right under it (iOS KaPosts da3e953).
    val latestPost by rememberUpdatedState(post)
    LaunchedEffect(viewModel) {
        viewModel.repliedTo.collect { id ->
            if (id == latestPost.id || id in expandedIds) return@collect
            fun find(list: List<KaPostDraft>): KaPostDraft? =
                list.firstNotNullOfOrNull { if (it.id == id) it else find(it.comments) }
            val comment = find(latestPost.comments) ?: return@collect
            viewModel.expandReplies(comment)
            expandedIds = expandedIds + id
        }
    }

    // Endless scroll through the thread's replies. Keyed on the root's txid, so pushing a nested
    // comment as a new thread root starts a fresh surface rather than inheriting this one's cursor.
    val threadListState = rememberLazyListState()
    val threadPaging = pagingStateOf(
        viewModel,
        post.remoteId?.let { KaPostsViewModel.pageThread(it) } ?: "thread:none",
    )
    EndlessScroll(listState = threadListState, key = post.remoteId) {
        viewModel.loadMoreReplies(post)
    }

    // Scroll-to-the-reply for reply landings. Re-keys on the comment list until the focused
    // reply is actually among the loaded comments (page one may still be in flight), then
    // scrolls once and clears the focus so nothing re-scrolls later. Index math mirrors the
    // LazyColumn below: ancestors, root-context, root, the optional chain section (segments +
    // divider), then the comments.
    if (focusReplyRemoteId != null) {
        LaunchedEffect(visibleComments, threadChain, visibleAncestors.size, focusReplyRemoteId) {
            val commentIndex = visibleComments.indexOfFirst { it.remoteId == focusReplyRemoteId }
            if (commentIndex >= 0) {
                val chainItems = if (threadChain.isEmpty()) 0 else threadChain.size + 1
                val target = visibleAncestors.size + 2 + chainItems + commentIndex
                delay(500)
                runCatching { threadListState.animateScrollToItem(target) }
                onFocusReplyHandled()
            }
        }
    }

    val threadSlide = rememberKaPostsCoverSlideState(onClose, slidesIn = slidesIn)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Slides in from the right and pulls out with the finger, like every other cover.
            .then(threadSlide.modifier)
            .background(colors.background)
            // Claim ONLY horizontal drags (which the ancestor feed pager would otherwise read
            // as a tab swipe). The previous blanket every-unconsumed-change consumer here also
            // ate MOVE events — and a descendant TextField's tap detector cancels the moment it
            // sees a consumed change mid-gesture, so any tap on the reply box with a pixel of
            // finger drift silently did nothing (the "tap several times before I can type" bug).
            // Taps never fall through: sibling content behind this opaque overlay loses the
            // hit test, and the pager ignores taps.
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, _ -> }
            },
    ) {
        // Header (wrap) / thread (weight 1f, the only scrolling region) / composer (wrap, pinned
        // to the bottom). The composer is the LAST non-weighted child, so it always gets its
        // intrinsic height and the list absorbs whatever is left - including the shrink when the
        // keyboard opens, which is what keeps the composer riding above the IME instead of being
        // pushed off-screen.
        // The real dock floats over the thread (it is not a Dialog window): the reply box sits
        // above it, and right on the keyboard once the dock steps aside for it.
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val threadDockPad = (LocalDockInset.current - navBarBottom).coerceAtLeast(0.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(KaPostsOverlayInsets)
                .padding(bottom = threadDockPad),
        ) {
            // ONE control, and it always means back: up a level while the thread has history,
            // out of it at the root (iOS).
            CompositionLocalProvider(LocalKaPostsBack provides threadSlide.back) { KaPostsOverlayStatusBar() }
            LazyColumn(state = threadListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The chain above this post, oldest first, each rung tappable to jump straight
                // to that level - X stacks these over the focal post. Full post cells, not
                // summaries: the parents ARE posts, so they behave like posts and the screen
                // reads as one conversation. Long ones truncate so the post you actually opened
                // still owns the screen.
                items(visibleAncestors, key = { "ancestor-${it.id}" }) { ancestor ->
                    // X's thread line, and no divider: a rule between two posts separates them,
                    // while the line running down the avatar column from one into the next is
                    // what makes the chain read as one conversation. Drawn over the cell rather
                    // than beside it, so the post keeps the same full width as every other cell.
                    // The geometry follows the cell's own: 16dp leading plus a 40dp avatar puts
                    // its centre at 36, and 12dp top padding plus that avatar ends it at 52. It
                    // runs 12dp past the cell's bottom, the next cell's top padding, so it meets
                    // the next avatar instead of stopping short of it (iOS 961fc05a, audit L6;
                    // cells are see-through, so it shows there).
                    Box(Modifier.drawBehind { drawAvatarRail(colors.textSecondary.copy(alpha = 0.3f)) }) {
                        KaPostCell(
                            post = ancestor,
                            viewModel = viewModel,
                            onOpenThread = { onJumpToAncestor(ancestor) },
                            onRepostTap = { onRepostTap(ancestor) },
                            onOpenProfile = { onOpenProfile(ancestor.posterAddress, ancestor.posterPubkey) },
                            onOpenQuoted = onOpenShared,
                            onViewEngagement = { onViewEngagement(ancestor) },
                            truncatesLongText = true,
                            onReply = { onReplyToComment(ancestor) },
                        )
                    }
                }
                item(key = "root-context") {
                    // The step ABOVE whatever the chain could reach: this post is a reply to
                    // something we have not loaded, so the txid is all there is to offer.
                    if (ancestors.isEmpty()) {
                        post.parentRemoteId?.let { parentId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenShared(parentId) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.kpui_replying_to_post_view), color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(color = colors.surfaceVariant)
                        }
                    }
                }
                item(key = post.id) {
                    KaPostCell(
                        post = post,
                        viewModel = viewModel,
                        onOpenThread = {},
                        onRepostTap = { onRepostTap(post) },
                        onOpenProfile = { onOpenProfile(post.posterAddress, post.posterPubkey) },
                        onOpenQuoted = onOpenShared,
                        onViewEngagement = { onViewEngagement(post) },
                        // The focused post has no reply bubble: the box under the thread is its
                        // reply (iOS threadCell isRoot).
                        isRoot = true,
                    )
                    HorizontalDivider(color = colors.surfaceVariant)
                }
                // The poster's own continuation, as X draws it (iOS 961fc05a, owner 2026-11-25):
                // their posts one under another, joined by the line down the avatar column, no
                // header. Everyone else's replies follow below.
                if (threadChain.isNotEmpty()) {
                    itemsIndexed(threadChain, key = { _, segment -> "chain-${segment.id}" }) { index, segment ->
                        val joinsNext = index < threadChain.size - 1
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // down to the next segment's avatar, as above
                                .then(
                                    if (joinsNext) Modifier.drawBehind { drawAvatarRail(colors.textSecondary.copy(alpha = 0.3f)) }
                                    else Modifier
                                ),
                        ) {
                            KaPostCell(
                                post = segment,
                                viewModel = viewModel,
                                onOpenThread = { onOpenNested(segment) },
                                onRepostTap = { onRepostTap(segment) },
                                onOpenProfile = { onOpenProfile(segment.posterAddress, segment.posterPubkey) },
                                onOpenQuoted = onOpenShared,
                                onViewEngagement = { onViewEngagement(segment) },
                                onReply = { onReplyToComment(segment) },
                            )
                        }
                    }
                    item(key = "thread-chain-divider") {
                        HorizontalDivider(color = colors.surfaceVariant)
                    }
                }
                // Replies follow straight on, as on X: no heading, and nothing at all while there
                // are none (iOS 961fc05a).
                items(visibleComments, key = { it.id }) { comment ->
                    ThreadCommentNode(
                        comment = comment,
                        expanded = comment.id in expandedIds,
                        hidden = hidden,
                        viewModel = viewModel,
                        onToggleExpand = { id ->
                            expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                        },
                        onOpenNested = onOpenNested,
                        onOpenProfile = onOpenProfile,
                        onOpenShared = onOpenShared,
                        onRepostTap = onRepostTap,
                        onViewEngagement = onViewEngagement,
                        // A specific reply is answered in the composer, where it renders under the
                        // editor - not by silently re-aiming the box at the bottom of the screen.
                        onReplyTo = { target -> onReplyToComment(target) },
                    )
                    HorizontalDivider(
                        color = colors.surfaceVariant,
                        modifier = Modifier.padding(start = 68.dp),
                    )
                }
                pagingFooter(threadPaging, keySuffix = "thread") {
                    viewModel.loadMoreReplies(post, manual = true)
                }
            }
            HorizontalDivider(color = colors.surfaceVariant)
            // X's shape: a small reply box directly under the post you opened, with the replies
            // above it. Answering a SPECIFIC reply still opens the composer (see the comment
            // cells' onReply), where that reply renders under the editor.
            //
            // While the funding gate is active the reply row renders dimmed and any tap on it
            // opens the funding card instead of focusing the field — same "no composer until
            // funded" rule as the New Post button.
            ThreadReplyComposer(
                viewModel = viewModel,
                textState = replyText,
                fundingGateActive = fundingGate.active,
                onShowFundingGate = { showFundingGate = true },
                onSubmit = { text -> viewModel.submitReply(post, text) },
            )
        }
        // The main screen's toast layer sits BEHIND this opaque overlay — without a copy in
        // here, a like/repost/reply made from an open thread showed no undo toast and no
        // network confirmation, which read as the buttons doing nothing at all for the whole
        // 5-second undo window (and made Undo unreachable). Its flows are collected inside
        // KaPostsToastLayer's own restart scope so toast emissions never recompose the overlay.
        KaPostsToastLayer(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
        )
    }

    // Also conditioned on the gate itself so the dialog vanishes reactively the moment the
    // chatting balance confirms as funded (funds land while it's open).
    // ...but not under its own Buy Kaspa flow, which would close with it (iOS 824a30bb)
    if (showFundingGate && (fundingGate.active || com.kachat.app.ui.screens.tokens.BuyKaspaFlowPresence.isOpen)) {
        ZeroBalanceFundingDialog(
            walletAddress = fundingGate.chattingAddress,
            onDismiss = { showFundingGate = false },
        )
    }
}

/** Posts an instant tip is out on, by post id. Shared by every KaPosts screen (the Feed and
 *  Profile tabs each have one), so no screen can start a second tip on the same post (iOS
 *  ee5ec98 `KaPostsTipHolds`). */
internal object KaPostsTipHolds {
    /** A tip with no answer can still land for at least this long. */
    private const val HOLD_MS = 2 * 60 * 1000L

    private val sending = mutableSetOf<String>()
    private val held = mutableMapOf<String, Pair<Long, String>>()

    @Synchronized fun isSending(postId: String): Boolean = postId in sending

    /** What to say while [postId] is still held; null once it can be tipped again. */
    @Synchronized fun heldMessage(postId: String): String? {
        val entry = held[postId] ?: return null
        if (entry.first <= System.currentTimeMillis()) {
            held.remove(postId)
            return null
        }
        return entry.second
    }

    @Synchronized fun beginSending(postId: String) { sending.add(postId) }

    /** Settled either way: sent, or failed with nothing on the network. */
    @Synchronized fun finish(postId: String) { sending.remove(postId) }

    /** Not settled: the post stays blocked after the send has returned. */
    @Synchronized fun hold(postId: String, message: String) {
        sending.remove(postId)
        held[postId] = (System.currentTimeMillis() + HOLD_MS) to message
    }
}

/** Who the tip sheet is for: the poster, the post tipped and, when an instant tip failed, why. */
private data class KaPostTipTarget(
    val address: String,
    val name: String,
    val tip: com.kachat.app.models.KaPostTip?,
    val failure: String? = null,
    /** What the tip is in (Tip In, iOS f954738): ASK asks Kaspa or kUSDT first. */
    val asset: com.kachat.app.repository.KaPostsTipAsset = com.kachat.app.repository.KaPostsTipAsset.KASPA,
)

/**
 * Tip, by Tip In (KaPost > Settings, iOS f954738 `KaPostTipFlow`): Kaspa opens [KaPostTipSheet];
 * kUSDT opens the kUSDT send to the poster's chatting address
 * ([com.kachat.app.ui.screens.tokens.KusdtTipSend]); Ask Each Time asks "What are you tipping in?"
 * first, as the Wallet's Send asks what you are sending. A kUSDT tip is a plain kUSDT send and
 * isn't counted on the post: Trestly's builder sets the transaction's payload, so it can't carry
 * KaPost's tip record (KAPOSTS_INDEXER.md 5.11).
 *
 * iOS slides from the chooser to the send inside one sheet; here each send is its own
 * [IosFullSheet], so the chooser's sheet gives way to the send's.
 */
@Composable
private fun KaPostTipFlow(
    address: String,
    displayName: String,
    tip: com.kachat.app.models.KaPostTip?,
    initialError: String?,
    asset: com.kachat.app.repository.KaPostsTipAsset,
    viewModel: KaPostsViewModel,
    onTipped: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember(address) { mutableStateOf(asset) }
    when (choice) {
        com.kachat.app.repository.KaPostsTipAsset.KASPA -> KaPostTipSheet(
            address = address,
            displayName = displayName,
            tip = tip,
            initialError = initialError,
            onTipped = onTipped,
            onDismiss = onDismiss,
        )
        com.kachat.app.repository.KaPostsTipAsset.KUSDT -> {
            // A tip on a post goes to its author only once the chain agrees who that is, as a KAS
            // tip does (audit B1): a lying indexer could name itself the author of a popular post.
            var authorChecked by remember(address, tip) { mutableStateOf(tip == null) }
            if (tip != null && !authorChecked) {
                LaunchedEffect(address, tip) {
                    viewModel.verifyTipAuthor(address, tip) { ok -> if (ok) authorChecked = true else onDismiss() }
                }
            }
            if (authorChecked) {
                com.kachat.app.ui.screens.tokens.KusdtTipSend(
                    recipient = address,
                    name = displayName,
                    onDismiss = onDismiss,
                )
            }
        }
        com.kachat.app.repository.KaPostsTipAsset.ASK -> KaPostTipAssetChooser(
            viewModel = viewModel,
            onPick = { choice = it },
            onDismiss = onDismiss,
        )
    }
}

/** "What are you tipping in?": Kaspa with the chatting address's balance, kUSDT with its own
 *  (iOS f954738 `KaPostTipFlow.chooser`). Also a chat's Tip (ChatTip.kt). */
@Composable
internal fun KaPostTipAssetChooser(
    viewModel: KaPostsViewModel,
    onPick: (com.kachat.app.repository.KaPostsTipAsset) -> Unit,
    onDismiss: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
    /** A tip on a post (KaPost's Tip); false for a chat's Tip, where nothing is counted, so kUSDT
     *  is just "kUSDT" (iOS 518fdc8c `KaPostTipFlow`: `tip == nil`). */
    forPost: Boolean = true,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val chattingSompi by walletViewModel.balanceSompi.collectAsState()
    var kusdtText by remember { mutableStateOf(viewModel.kusdtBalanceText()) }
    LaunchedEffect(Unit) {
        viewModel.refreshKusdtIfStale()
        kusdtText = viewModel.kusdtBalanceText()
    }
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        androidx.activity.compose.BackHandler { close() }
        Column(Modifier.fillMaxSize().background(colors.background)) {
            SendStepHeader(title = stringResource(R.string.kp5_tip_title))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SendStepQuestion(stringResource(R.string.kp5_tip_what_in))
                KaPostTipAssetOption(
                    icon = {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_kaspa_logo),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(CircleShape),
                        )
                    },
                    title = stringResource(R.string.kp5_tip_in_kaspa),
                    subtitle = KaspaUnit.symbol,
                    amount = "${KaspaUnit.plain(chattingSompi)} ${KaspaUnit.symbol}",
                ) {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    onPick(com.kachat.app.repository.KaPostsTipAsset.KASPA)
                }
                KaPostTipAssetOption(
                    icon = {
                        com.kachat.app.ui.screens.tokens.TokenIcon(com.kachat.app.services.tokens.WalletToken.KUSDT, size = 44.dp)
                    },
                    title = stringResource(R.string.kp5_tip_in_kusdt),
                    subtitle = if (forPost) stringResource(R.string.kp5_tip_not_counted) else com.kachat.app.services.tokens.WalletToken.KUSDT.symbol,
                    amount = kusdtText,
                ) {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    onPick(com.kachat.app.repository.KaPostsTipAsset.KUSDT)
                }
                Text(
                    stringResource(R.string.kp5_tip_set_in_settings),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Cancel, a capsule at the bottom (iOS's safeAreaInset button).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, KaspaTeal.copy(alpha = 0.35f), CircleShape)
                    .clickable(onClick = close),
            ) {
                Text(stringResource(R.string.cancel), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }
    }
}

/** One choice on the tip chooser: icon, name over a caption, the balance, a chevron. */
@Composable
private fun KaPostTipAssetOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    amount: String?,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .sendKaspaGlass(20.dp)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
        Text(
            amount ?: "--",
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
        )
        Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** A delete or edit that failed after its countdown, as an alert (iOS fae6686 `actionError`), in
 *  its own restart scope. */
@Composable
private fun KaPostsActionErrorAlert(viewModel: KaPostsViewModel) {
    val actionError by viewModel.actionError.collectAsState()
    val unconfirmedThreadId by viewModel.unconfirmedThreadId.collectAsState()
    val colors = LocalAppColors.current
    // Part of a thread the network didn't confirm: offer what's left of it as a draft (iOS ee5ec98).
    if (unconfirmedThreadId != null) {
        com.kachat.app.ui.theme.IosAlertDialog(
            // Only its buttons close it, as iOS's alert: Back or a stray tap outside would drop
            // the held rest of the thread.
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                // "Posts", not the old name (iOS 01f71184 release audit)
                title = { Text(stringResource(R.string.tab_posts), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.kaposts_thread_not_confirmed), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.settleUnconfirmedThread(saveDraft = true) }) {
                    Text(stringResource(R.string.kaposts_save_as_draft), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.settleUnconfirmedThread(saveDraft = false) }) {
                    Text(stringResource(R.string.not_now), color = KaspaTeal)
                }
            },
        )
        return
    }
    val raw = actionError ?: return
    // A reply to a post not on chain yet (iOS 33095ac): the view model has no resources.
    val message = if (raw == KaPostsViewModel.REPLY_NOT_ON_NETWORK) stringResource(R.string.kaposts_reply_not_on_network) else raw
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = { viewModel.clearActionError() },
                // "Posts", not the old name (iOS 01f71184 release audit)
                title = { Text(stringResource(R.string.tab_posts), color = colors.textPrimary) },
        text = { Text(message, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = { viewModel.clearActionError() }) {
                Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** The undo/confirmation toast stack in its OWN restart scope (used by the main screen and the
 *  thread overlay): toast emissions recompose this layer only, never the screen or list behind. */
@Composable
private fun KaPostsToastLayer(viewModel: KaPostsViewModel, modifier: Modifier = Modifier) {
    val undoToasts by viewModel.undoToasts.collectAsState()
    val actionToast by viewModel.actionToast.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    KaPostsToastOverlay(
        undoToasts = undoToasts,
        actionToast = actionToast,
        onUndo = { viewModel.undoPendingPost(it) },
        onViewTx = { uriHandler.openUri(kaspaExplorer.txUrl(it)) },
        modifier = modifier,
    )
}

/**
 * The thread's pinned reply box, in its OWN restart scope so a keystroke recomposes only this
 * composable and not every visible comment above it.
 *
 * iOS's shape: the mention list above, a field of about four lines that then scrolls internally,
 * the character meter, and an arrow-up send button - no formatting toolbar, the composer has that.
 * The box always targets the opened post.
 */
@Composable
private fun ThreadReplyComposer(
    viewModel: KaPostsViewModel,
    textState: androidx.compose.runtime.MutableState<TextFieldValue>,
    fundingGateActive: Boolean,
    onShowFundingGate: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val text = textState.value
    val onTextChange: (TextFieldValue) -> Unit = { textState.value = it }
    val replyText = text.text
    val charCount = remember(replyText) { KaPostDraft.postLength(replyText) }
    val canSend = replyText.isNotBlank()
    val hapticView = LocalView.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (fundingGateActive) 0.45f else 1f),
        ) {
            // @mention autocomplete for COMMENTS - identical machinery to the post composer:
            // contacts' .kachat names, plus a live resolve of the typed query. Shown above the
            // input so the keyboard can never hide it.
            // only with mentions on (audit M15), as in the post composer
            val replyMentionQuery = remember(replyText) {
                if (!com.kachat.app.util.MentionsFeature.ENABLED) return@remember null
                com.kachat.app.util.KaPostsMentionToken.query(replyText)
            }
            var replyResolvedAnyDomain by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(replyMentionQuery) {
                replyResolvedAnyDomain = null
                val query = replyMentionQuery ?: return@LaunchedEffect
                if (query.length < 2) return@LaunchedEffect
                kotlinx.coroutines.delay(400)
                replyResolvedAnyDomain = viewModel.resolveMentionQuery(query)
            }
            // follows, recent posters and contacts with a username, as in the composer above:
            // worked out on every draw, so names landing redraw the list (iOS 157790b0)
            viewModel.following.collectAsState().value
            com.kachat.app.services.kachatnames.KachatNamesRegistry.identityRevision.collectAsState().value
            val replyMentionSuggestions = replyMentionQuery?.let { query ->
                viewModel.kachatMentionCandidates(query, replyResolvedAnyDomain)
            } ?: emptyList()
            if (com.kachat.app.util.MentionsFeature.ENABLED && replyMentionSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                KaPostMentionSuggestionList(
                    suggestions = replyMentionSuggestions,
                    onPick = { domain ->
                        // The completion lands at the caret: the token being completed is the
                        // one at the end of the text, so the caret goes to the end with it.
                        val completed = replyText.replace(MENTION_REPLACE_REGEX, "@$domain ")
                        onTextChange(TextFieldValue(completed, TextRange(completed.length)))
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicTextField(
                    value = text,
                    // Hard cap at the limit, X-style - clipped, never dropped (iOS prefix).
                    onValueChange = {
                        // Someone's payment request pasted in opens Send Kaspa instead (iOS
                        // c6851d6 PaymentRequestAwareTextView).
                        if (!PaymentRequestPaste.interceptInsert(text.text, it.text)) {
                            onTextChange(clampToCharacterLimit(it, KaPostDraft.POST_CHARACTER_LIMIT))
                        }
                    },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(KaspaTeal),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    // Roughly four lines: past that the field scrolls instead of pushing the post
                    // you are answering off the screen (iOS maxHeight 92).
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        // A fixed radius, not a capsule: a capsule's corners are half its height,
                        // so a grown multi-line field curves into its own text.
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.textSecondary.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        if (replyText.isEmpty()) {
                            Text(stringResource(R.string.kpui_post_your_reply), color = colors.textSecondary, fontSize = 15.sp)
                        }
                        inner()
                    },
                )
                KaPostCharacterMeter(count = charCount)
                // A 44dp target around the 28dp arrow, named Send (iOS 518fdc8c, audit 2026-11-24 #39).
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            enabled = canSend,
                            role = androidx.compose.ui.semantics.Role.Button,
                        ) {
                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            val trimmed = replyText.trim()
                            onTextChange(TextFieldValue(""))
                            focusManager.clearFocus()
                            onSubmit(trimmed)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ArrowCircleUp,
                        contentDescription = stringResource(R.string.send),
                        tint = if (canSend) KaspaTeal else colors.textSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        if (fundingGateActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onShowFundingGate() }
            )
        }
    }
}

/**
 * One comment with X-style inline expansion: "View N replies" loads its direct replies and draws
 * them under it with a connector down the avatar column. Exactly ONE inline level, as on iOS -
 * deeper conversation is reached by tapping a reply, which opens it as its own thread root.
 */
@Composable
private fun ThreadCommentNode(
    comment: KaPostDraft,
    expanded: Boolean,
    hidden: Set<String>,
    viewModel: KaPostsViewModel,
    onToggleExpand: (String) -> Unit,
    onOpenNested: (KaPostDraft) -> Unit,
    onOpenProfile: (String, String?) -> Unit,
    onOpenShared: (String) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onReplyTo: (KaPostDraft) -> Unit,
) {
    val colors = LocalAppColors.current
    val replyCount = viewModel.commentCount(comment)

    Column {
        KaPostCell(
            post = comment,
            viewModel = viewModel,
            onOpenThread = { onOpenNested(comment) },
            onRepostTap = { onRepostTap(comment) },
            onOpenProfile = { onOpenProfile(comment.posterAddress, comment.posterPubkey) },
            onOpenQuoted = onOpenShared,
            onViewEngagement = { onViewEngagement(comment) },
            onReply = { onReplyTo(comment) },
        )
        if (replyCount > 0) {
            if (expanded) {
                val replies = comment.comments.filter { it.posterAddress !in hidden }
                val page = pagingStateOf(
                    viewModel,
                    comment.remoteId?.let { KaPostsViewModel.pageThread(it) } ?: "thread:none",
                )
                // Connector dropping from the comment's avatar column, drawn behind the replies
                // instead of as a sibling sized with IntrinsicSize.Min: a reply cell holds
                // SubcomposeLayouts (BoxWithConstraints, SubcomposeAsyncImage), which throw when
                // asked for intrinsic measurements - the crash on "View N replies".
                val connectorColor = colors.textSecondary.copy(alpha = 0.3f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind { drawThreadConnector(connectorColor, startX = 35.dp.toPx(), insetY = 0f) }
                        .padding(start = 37.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (replies.isEmpty()) {
                            // Emptiness alone used to mean "loading", so a comment whose reply
                            // count is a SUBTREE count while get-replies returns its direct
                            // replies (none) span forever. The page state knows the difference
                            // between still fetching, failed, and answered with nothing.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                when {
                                    page.isLoadingMore || !viewModel.repliesRequested(comment) -> {
                                        com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.textSecondary)
                                        Text(stringResource(R.string.kpui_loading_replies), color = colors.textSecondary, fontSize = 12.sp)
                                    }
                                    page.error != null -> {
                                        Text(page.error, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Text(
                                            stringResource(R.string.kaposts_retry),
                                            color = KaspaTeal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.clickable { viewModel.loadReplies(comment, force = true) },
                                        )
                                    }
                                    else -> Text(stringResource(R.string.l53_no_replies), color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        } else {
                            replies.forEach { reply ->
                                KaPostCell(
                                    post = reply,
                                    viewModel = viewModel,
                                    onOpenThread = { onOpenNested(reply) },
                                    onRepostTap = { onRepostTap(reply) },
                                    onOpenProfile = { onOpenProfile(reply.posterAddress, reply.posterPubkey) },
                                    onOpenQuoted = onOpenShared,
                                    onViewEngagement = { onViewEngagement(reply) },
                                    onReply = { onReplyTo(reply) },
                                )
                            }
                            // Inline chains page endlessly too (long comment threads). Not its
                            // own scroll container, so the next page loads on tap.
                            if (page.isLoadingMore || page.error != null || page.stalled) {
                                PagingFooterContent(state = page) { viewModel.loadMoreReplies(comment, manual = true) }
                            } else if (page.hasMore) {
                                Text(
                                    stringResource(R.string.kpui_load_more),
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.loadMoreReplies(comment, manual = true) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                ThreadToggleButton(stringResource(R.string.l53_hide_replies)) { onToggleExpand(comment.id) }
            } else {
                ThreadToggleButton(stringResource(if (replyCount == 1) R.string.l53_view_one_reply else R.string.l53_view_replies, replyCount)) {
                    viewModel.expandReplies(comment)
                    onToggleExpand(comment.id)
                }
            }
        }
    }
}

/** The "View N replies" / "Hide replies" row: a short rule then the caption, in accent (iOS). */
@Composable
private fun ThreadToggleButton(title: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clickable { onClick() }
            .padding(start = 36.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.textSecondary.copy(alpha = 0.3f)),
        )
        Text(title, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: - Profile overlay (mine + tapped poster)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KaPostsProfileOverlay(
    address: String,
    pubkey: String?,
    isMine: Boolean,
    viewModel: KaPostsViewModel,
    navController: NavController,
    onClose: () -> Unit,
    onOpenThread: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onOpenQuoted: (String) -> Unit,
    onOpenFollowList: ((Boolean) -> Unit)?,
    /** Quick-tip dialog opener; null tips nothing (your own posts). */
    onTip: ((KaPostDraft) -> Unit)? = null,
    /** The reply bubble on a profile row opens the Reply composer (iOS). */
    onReply: ((KaPostDraft) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Dialog(
        onDismissRequest = onClose,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        // Same window-sizing fix as the other overlays.
        ForceFullScreenDialogWindow()
        val profileSlide = rememberKaPostsCoverSlideState(onClose)
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(profileSlide.modifier)
                    .background(colors.background)
                    .windowInsetsPadding(KaPostsOverlayInsets),
            ) {
                // The bar - back chevron, dot, balance - with the banner starting BELOW it (iOS).
                CompositionLocalProvider(LocalKaPostsBack provides profileSlide.back) { KaPostsOverlayStatusBar() }
                KaPostsProfilePage(
                    address = address,
                    pubkey = pubkey,
                    isMine = isMine,
                    viewModel = viewModel,
                    navController = navController,
                    onClose = onClose,
                    onOpenThread = onOpenThread,
                    onRepostTap = onRepostTap,
                    onViewEngagement = onViewEngagement,
                    onOpenQuoted = onOpenQuoted,
                    onOpenFollowList = onOpenFollowList,
                    onTip = onTip,
                    onReply = onReply,
                    // The last posts scroll clear of the dock copy.
                    bottomInset = kaPostsDockCopyInsetAboveNavBar(),
                )
            }
            // The feed's toast layer sits behind this window, Undo included (iOS ee5ec98).
            KaPostsToastLayer(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
            )
            // The dock stays in reach over a profile, still while it slides (iOS KaPostsDockCopy),
            // with the fade under it coming and going with the screen.
            KaPostsDockCopy(fadeAlpha = profileSlide.inPlace)
        }
    }
}

/** The teal capsule the buttons under your own profile's name share (iOS KaPosts
 *  `profileHeaderButtonLabel`). */
@Composable
private fun KaPostsProfileHeaderButton(title: String, icon: ImageVector, tint: Color = KaspaTeal, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS profileHeaderButtonLabel: subheadline semibold, the symbol at the text's size.
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        // three buttons share the row: a long translation stays on one line (iOS ee5ec98)
        Text(title, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A KaPosts profile page - banner, avatar, name, counts, bio, then Posts / Replies - under
 * whatever bar its host draws: the profile overlay (back chevron, dot, balance), or the Profile
 * tab, which is your own profile at the root of the dock (iOS KaPosts `rootMode: .myProfile`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.KaPostsProfilePage(
    address: String,
    pubkey: String?,
    isMine: Boolean,
    viewModel: KaPostsViewModel,
    navController: NavController,
    onClose: () -> Unit,
    onOpenThread: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onOpenQuoted: (String) -> Unit,
    onOpenFollowList: ((Boolean) -> Unit)?,
    /** Quick-tip dialog opener; null tips nothing (your own posts). */
    onTip: ((KaPostDraft) -> Unit)? = null,
    /** The reply bubble on a profile row opens the Reply composer (iOS). */
    onReply: ((KaPostDraft) -> Unit)? = null,
    /** The dock's height, when the page is the Profile tab and runs on under it. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** Your profile (the dock's Profile tab): See All Posts beside the avatar opens the KaPost
     *  tab, wherever it lives (iOS 148a5331). */
    onSeeAllPosts: (() -> Unit)? = null,
    /** Share Profile in the row under your name; the Profile tab has it top left instead (iOS
     *  8cb4c88e), the profile overlay, which has no title, keeps it here. */
    shareInHeaderRow: Boolean = true,
) {
    val colors = LocalAppColors.current
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showShareProfile by remember { mutableStateOf(false) }
    // Banner and bio are the .kachat profile's, yours and a tapped poster's alike, on every
    // network (iOS 3d6fb7c KachatLive.profileBanner / profileBio; .kachat only since 518fdc8c).
    val kachatProfile = kachatProfileBannerAndBio(address)
    val profileBannerUrl = kachatProfile.first
    val profileBio = kachatProfile.second
    val profilePhoto by viewModel.contactPhotos.collectSelectedAsState(address) { it[address] }
    val following by viewModel.following.collectAsState()
    val posterProfile by viewModel.posterProfile.collectAsState()
    val myFollowersCount by viewModel.myFollowersCount.collectAsState()
    val isLoadingMyProfile by viewModel.isLoadingMyProfile.collectAsState()
    val myProfileReplies by viewModel.myProfileReplies.collectAsState()
    val posterPosts by viewModel.posterProfilePosts.collectAsState()
    val posterReplies by viewModel.posterProfileReplies.collectAsState()
    // Recompose against the live lists so engagement changes show immediately - but the
    // merge-and-sort only re-runs when one of its inputs actually changed, instead of on
    // every recomposition of the overlay.
    val localPosts by viewModel.localPosts.collectAsState()
    val myProfilePosts by viewModel.myProfilePosts.collectAsState()
    val myPostsList = if (isMine) {
        remember(localPosts, myProfilePosts) { viewModel.myCombinedPosts() }
    } else posterPosts
    val repliesList = if (isMine) myProfileReplies else posterReplies
    // Follow is gated on the ADDRESS, not the caller's flag: your own address opened as a
    // poster (from search, say) must not offer to follow yourself (iOS).
    val isOwnAddress = address == viewModel.myAddress()
    val hapticView = LocalView.current

    var selectedTab by remember { mutableStateOf(0) } // 0 = Posts, 1 = Replies
    // the posts under the header fade when the cycle button switches Posts and Replies (iOS
    // 518fdc8c: no sideways swipe)
    val tabFade = rememberProfileTabFade(selectedTab)
    val name = posterDisplayNameState(viewModel, address)
    // One list for the whole page, header included, rather than a pager holding two. The banner,
    // bio and tab row are the list's first item, so they scroll up out of the way while reading
    // instead of holding most of a phone screen for a name already read.
    val profileListState = rememberLazyListState()
    // Posts and Replies are separate paging surfaces (separate endpoints, separate cursors), so
    // each pager page carries its own load-more trigger and footer.
    val profilePubkey = remember(isMine, pubkey, posterProfile?.pubkey) { viewModel.profilePubkey(isMine) }


    val repliesPage = selectedTab == 1
    val pageItems = if (repliesPage) repliesList else myPostsList
    val pagePaging = pagingStateOf(
        viewModel,
        profilePubkey?.let { KaPostsViewModel.pageProfile(it, isMine, repliesPage) } ?: "profile:none",
    )
    EndlessScroll(listState = profileListState, key = selectedTab to profilePubkey) {
        viewModel.loadMoreProfile(isMine, replies = repliesPage)
    }
    // Pull-to-refresh reloads page one of the tab being looked at (iOS .refreshable).
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshProfileTab(isMine, replies = repliesPage)
            pullRefreshState.endRefresh()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .nestedScroll(pullRefreshState.nestedScrollConnection),
    ) {
    LazyColumn(
        state = profileListState,
        // Posts and Replies change only with the cycle button (owner, iOS 518fdc8c)
        modifier = Modifier.fillMaxSize(),
        // The last row scrolls clear of the dock when the page is the Profile tab.
        contentPadding = PaddingValues(bottom = bottomInset),
    ) {
        item(key = "profile-header") {
            Column {
                val bannerUrl = profileBannerUrl
                if (bannerUrl != null) {
                    // The whole banner at the full width (its own proportions, no crop).
                    WholeBanner(model = bannerUrl, placeholderHeight = 140.dp) {
                        Box(Modifier.fillMaxSize().background(colors.surfaceVariant))
                    }
                } else {
                    // No banner: iOS bannerFallback, the accent at 55% fading to 15% from the
                    // top leading corner to the bottom trailing one.
                    Box(
                        Modifier.fillMaxWidth().height(140.dp).background(
                            Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.55f), KaspaTeal.copy(alpha = 0.15f)))
                        )
                    )
                }
                // Avatar overlapping the banner, X-style, with a 3dp ring in the page
                // background so it reads as sitting on top of the banner. The slot is
                // only as tall as the part below the banner (82 - 38), so nothing under
                // it has to be offset back up. Someone else's Follow and Chat sit under the
                // follow counts (iOS 518fdc8c).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.height(44.dp)) {
                        Box(
                            modifier = Modifier
                                .offset(y = (-38).dp)
                                // Taller than the 44dp slot on purpose: unbounded, or size() is clamped to the slot
                                // and the circle comes out a squashed pill.
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .size(82.dp)
                                .clip(CircleShape)
                                .background(colors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            ContactAvatar(
                                imageUrl = null,
                                fallbackText = name,
                                size = 76.dp,
                                backupPhotoBase64 = profilePhoto?.backupPhotoBase64,
                                // The .kachat avatar - your own in your header too (iOS 3d6fb7c).
                                address = address,
                            )
                        }
                    }
                    if (isMine && onSeeAllPosts != null) {
                        // Beside the avatar, the way to everyone's posts (owner's call, iOS
                        // 148a5331: Profile is a dock tab, KaPost sits in Kaspa Hub).
                        Spacer(Modifier.weight(1f))
                        KaPostsProfileHeaderButton(stringResource(R.string.kaposts_see_all_posts), Icons.Outlined.Newspaper) {
                            onSeeAllPosts()
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        name,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val followingCount = if (isMine) following.size else posterProfile?.followingCount ?: 0
                        val followersCount = if (isMine) (myFollowersCount ?: 0) else posterProfile?.followersCount ?: 0
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(false) },
                        ) {
                            Text(java.text.NumberFormat.getIntegerInstance().format(followingCount), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(stringResource(R.string.l53_feed_tab_following), color = colors.textSecondary, fontSize = 15.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable(enabled = onOpenFollowList != null) { onOpenFollowList?.invoke(true) },
                        ) {
                            Text(java.text.NumberFormat.getIntegerInstance().format(followersCount), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(stringResource(R.string.kpui_followers), color = colors.textSecondary, fontSize = 15.sp)
                        }
                    }
                    if (!isMine) {
                        // Follow and Chat under the follow counts (owner's call, iOS 518fdc8c), so a
                        // long name never squeezes them; a size up from small (owner, 2026-11-24).
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!isOwnAddress) {
                                val isFollowing = address in following
                                Button(
                                    onClick = {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                        viewModel.toggleFollow(address, pubkey)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFollowing) colors.textSecondary.copy(alpha = 0.35f) else KaspaTeal,
                                        contentColor = if (isFollowing) colors.textPrimary else Color.Black,
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp),
                                ) {
                                    Text(if (isFollowing) stringResource(R.string.l53_feed_tab_following) else stringResource(R.string.kpui_follow), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                                }
                            }
                            val chatContext = LocalContext.current
                            OutlinedButton(
                                onClick = {
                                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                    openChatInKaChat(chatContext, address)
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, KaspaTeal.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = KaspaTeal),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.kpui_chat), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            }
                        }
                    }
                    profileBio?.takeIf { it.isNotBlank() }?.let { bio ->
                        Box(Modifier.padding(top = 2.dp)) { ExpandableBioText(bio) }
                    }
                    if (isMine) {
                        // Everything above this - avatar, banner, name, bio - comes from your
                        // profile, so the way to change any of it belongs here. Beside it, the
                        // Wallet: the total, Send, Receive, Request and the chatting address's
                        // history - pushed, so the dock stays underneath (iOS KaPosts 865c9ab,
                        // e5a9b11).
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            KaPostsProfileHeaderButton(stringResource(R.string.kpui_edit_profile), Icons.Default.Badge) {
                                onClose()
                                // KaChat's own profile (iOS 09e0403); .kas profiles are
                                // customized per domain in Your Domains.
                                navController.navigate("edit_kachat_profile")
                            }
                            if (address.isNotEmpty()) {
                                // Profile is the dock's Profile tab in KaChat: Edit Profile and Log
                                // Out, which lived on it; Share Profile sits top left there
                                // (iOS 8cb4c88e).
                                if (shareInHeaderRow) {
                                    KaPostsProfileHeaderButton(stringResource(R.string.profile_share_profile), Icons.Outlined.IosShare) {
                                        showShareProfile = true
                                    }
                                }
                                KaPostsProfileHeaderButton(stringResource(R.string.log_out), Icons.AutoMirrored.Outlined.Logout, tint = colors.danger) {
                                    showLogoutConfirmation = true
                                }
                            }
                        }
                        if (showShareProfile && address.isNotEmpty()) {
                            // your Chatting Address's QR and Copy Share Link
                            ShareProfileSheet(
                                address = address,
                                shareUrl = KaChatLink.profileWebUrl(address),
                                onDismiss = { showShareProfile = false },
                            )
                        }
                        if (showLogoutConfirmation) {
                            val profileContext = LocalContext.current
                            val logoutWalletViewModel: WalletViewModel = hiltViewModel(
                                generateSequence(profileContext) { (it as? android.content.ContextWrapper)?.baseContext }
                                    .first { it is androidx.activity.ComponentActivity } as androidx.activity.ComponentActivity
                            )
                            ConfirmActionSheet(
                                title = stringResource(R.string.log_out),
                                confirmTitle = stringResource(R.string.log_out),
                                confirmSubtitle = stringResource(R.string.signs_out_of_this_account_wallet_and),
                                confirmIcon = Icons.AutoMirrored.Outlined.Logout,
                                onConfirm = {
                                    showLogoutConfirmation = false
                                    logoutWalletViewModel.logout()
                                },
                                onDismiss = { showLogoutConfirmation = false },
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.surfaceVariant)
                // iOS profileFeedTabBar: Posts / Replies on the app's cycle button, fading to the
                // other page as KaPost's feed button does (owner, iOS 518fdc8c). No sideways swipe.
                val profileTabTitles = listOf(stringResource(R.string.kpui_posts), stringResource(R.string.kpui_replies))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    CycleCapsuleButton(
                        options = listOf(0, 1),
                        selection = selectedTab,
                        onSelect = { selectedTab = it },
                        title = { profileTabTitles[it] },
                        hint = stringResource(R.string.cycle_hint_next_page),
                    )
                }
            }
        }
        val items = pageItems
        if (items.isEmpty()) {
            item(key = "empty") {
                val loading = if (isMine) isLoadingMyProfile else posterProfile?.isLoading == true
                Column(
                    modifier = Modifier.then(tabFade.itemModifier).fillMaxWidth().padding(vertical = 40.dp, horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loading) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    } else {
                        // Your own profile says whose posts these would be; another
                        // person's carries the title alone (iOS).
                        // iOS profileFeedPage: square.and.pencil or bubble.left at 40, the title
                        // in headline and the line under it in subheadline, 12 apart.
                        Icon(
                            if (repliesPage) Icons.Outlined.ChatBubbleOutline else SquareAndPencil,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(if (repliesPage) R.string.l53_no_replies_yet else R.string.l53_no_posts_yet),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                        )
                        if (isMine) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                if (repliesPage) stringResource(R.string.kpui_replies_empty) else stringResource(R.string.kpui_posts_empty),
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        } else {
            items(items, key = { "profile-${it.id}" }) { post ->
                Column(tabFade.itemModifier) {
                KaPostCell(
                    post = post,
                    viewModel = viewModel,
                    onOpenThread = { onOpenThread(post) },
                    onRepostTap = { onRepostTap(post) },
                    onOpenQuoted = onOpenQuoted,
                    onViewEngagement = { onViewEngagement(post) },
                    onReply = onReply?.let { reply -> { reply(post) } },
                    // The fallback navigates the NAV HOST, which this profile Dialog
                    // window covers - close the profile first or the chat screen
                    // only becomes visible after the user closes it themselves.
                    // Tips go through the tip sheet; with none (your own posts) there is nothing to tip.
                    onTip = onTip?.let { open -> { open(post) } } ?: {},
                    // a reply here is out of its conversation: "Replying to <name>" (iOS 961fc05a)
                    showsReplyingTo = true,
                )
                HorizontalDivider(
                    color = colors.surfaceVariant,
                    modifier = Modifier.padding(start = 68.dp),
                )
                }
            }
            pagingFooter(pagePaging, keySuffix = "profile-$selectedTab", modifier = tabFade.itemModifier) {
                viewModel.loadMoreProfile(isMine, replies = repliesPage, manual = true)
            }
        }
    }
    if (pullIndicatorShown(pullRefreshState)) {
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    }
}

/**
 * The 1:1 chat with this poster, right here in KaChat (Chats): every KaPost screen closes first,
 * as for a tap on the dock copy (iOS 148a5331 `openInKaChat` -> `KaChatLinkRouter.openChat`). The
 * shell opens the chat from [ProfileDeepLink] with `openChat`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun openChatInKaChat(context: android.content.Context, address: String) {
    KaPostsDock.closeCovers.tryEmit(Unit)
    ProfileDeepLink.request(address.lowercase(), openChat = true)
}

// MARK: - Notifications overlay

/**
 * Search across KaPosts: posts by their text, and the people who wrote them.
 *
 * The depth line under the results is not decoration. This search is client-side (the K indexer
 * has no search endpoint), so it can only see as far back as it has paged - saying how far, and
 * offering to go further, is the difference between "no results" and "no results yet".
 */
@Composable
fun KaPostsSearchOverlay(
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    onOpenPost: (String) -> Unit,
    /** The author's address and K pubkey (from the posts scanned). */
    onOpenProfile: (String, String?) -> Unit,
) {
    val colors = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    var showPeople by remember { mutableStateOf(false) }
    val postResults by viewModel.searchPostResults.collectAsState()
    val peopleResults by viewModel.searchPeopleResults.collectAsState()
    val scannedCount by viewModel.searchScannedCount.collectAsState()
    val hasMore by viewModel.searchHasMore.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val loadFailed by viewModel.searchLoadFailed.collectAsState()

    LaunchedEffect(query) { viewModel.setSearchQuery(query) }
    // One page up front so the first search has something to answer with.
    LaunchedEffect(Unit) { if (scannedCount == 0) viewModel.searchLoadMore() }

    // The one KaPosts screen that says Done rather than Back (iOS).
    KaPostsOverlayScaffold(title = stringResource(R.string.kpui_search), onClose = onClose, doneBar = true) {
        Column(Modifier.fillMaxSize()) {
            // iOS: .searchable in the navigation bar's drawer, always shown.
            IosSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.kpui_posts_and_people),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // The cycle button every other page switch uses (iOS 518fdc8c, audit 2026-11-24 #37:
            // it replaced the segmented picker).
            PageCycleBar(
                titles = listOf(stringResource(R.string.kpui_posts), stringResource(R.string.kpui_people)),
                selectedIndex = if (showPeople) 1 else 0,
                onSelect = { showPeople = it == 1 },
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (query.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // iOS promptState: magnifyingglass at 40, headline, subheadline, 10 apart.
                    Icon(Icons.Default.Search, null, tint = colors.textSecondary, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.kpui_search_kapost), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.kpui_search_intro),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                return@KaPostsOverlayScaffold
            }

            val empty = if (showPeople) peopleResults.isEmpty() else postResults.isEmpty()
            if (empty && !isSearching) {
                // iOS emptyResults: what was read, and an offer to keep looking.
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.kpui_nothing_found_yet), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (hasMore) stringResource(R.string.kpui_searched_recent, scannedCount)
                        else stringResource(R.string.kpui_searched_every),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (hasMore) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.searchLoadMore() },
                            enabled = !isSearching,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        ) {
                            Text(stringResource(R.string.kpui_keep_looking), color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                return@KaPostsOverlayScaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (showPeople) {
                    items(peopleResults, key = { it.address }) { person ->
                        val personName = posterDisplayNameState(viewModel, person.address)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProfile(person.address, viewModel.searchAuthorPubkey(person.address)); onClose() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ContactAvatar(imageUrl = null, fallbackText = personName, size = 36.dp, address = person.address)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    personName,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                )
                                Text(
                                    if (person.postCount == 1) stringResource(R.string.l53_one_post_found) else stringResource(R.string.l53_posts_found, person.postCount),
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary)
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                } else {
                    items(postResults, key = { it.id }) { post ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { post.remoteId?.let { onOpenPost(it) }; onClose() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                posterDisplayNameState(viewModel, post.posterAddress),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                post.text,
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                remember(post.timestamp) { relativePostTime(post.timestamp) },
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }

                // Says how deep the search has gone, and offers to go deeper. Under the results
                // rather than only when empty: a handful of hits does not mean there are no
                // more (iOS depthFooter).
                item {
                    if (hasMore) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isSearching) { viewModel.searchLoadMore() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (isSearching) {
                                com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                if (isSearching) stringResource(R.string.kpui_reading_older_posts) else stringResource(R.string.kpui_search_older_posts),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(stringResource(R.string.kpui_n_read, scannedCount), color = colors.textSecondary, fontSize = 11.sp)
                        }
                    } else if (loadFailed) {
                        Text(
                            stringResource(R.string.kpui_could_not_read_further),
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A row of Profile's bell: a KaPost action, or (all-in-one only) a wallet notification
 *  (iOS 148a5331 `KaPostsNotificationsView.Row`). */
private sealed class KaPostsNotificationRow {
    abstract val key: String
    abstract val timestampMs: Long
    data class Post(val item: KaPostsViewModel.NotificationItem) : KaPostsNotificationRow() {
        override val key get() = "p:" + item.id
        override val timestampMs get() = item.timestampMs
    }
    data class Wallet(val entry: com.kachat.app.services.GlobalNotificationCenterStore.Entry) : KaPostsNotificationRow() {
        override val key get() = "w:" + entry.id
        override val timestampMs get() = entry.timestampMs
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KaPostsNotificationsList(
    viewModel: KaPostsViewModel,
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    bottomInset: Dp,
    /** Profile's bell (iOS 148a5331 `allInOne`): the wallet's notifications mixed in by time. */
    walletEntries: List<com.kachat.app.services.GlobalNotificationCenterStore.Entry>? = null,
    onOpenWalletEntry: (com.kachat.app.services.GlobalNotificationCenterStore.Entry) -> Unit = {},
) {
    val allInOne = walletEntries != null
    val colors = LocalAppColors.current
    val loadedItems by viewModel.notifications.collectAsState()
    val mutedSet by viewModel.muted.collectAsState()
    val blockedSet by viewModel.blocked.collectAsState()
    // Without anyone muted or blocked since they loaded: a load already leaves them out, and
    // this takes their rows away at once rather than at the next reload (iOS b78fdb0
    // `visibleItems`).
    val items = remember(loadedItems, mutedSet, blockedSet) {
        loadedItems.filterNot { viewModel.isHidden(it.actorAddress, mutedSet, blockedSet) }
    }
    val isLoading by viewModel.isLoadingNotifications.collectAsState()
    val haptic = com.kachat.app.util.rememberHaptics()

    val listState = rememberLazyListState()
    val paging = pagingStateOf(viewModel, KaPostsViewModel.PAGE_NOTIFICATIONS)
    /** The KaPost rows with the wallet's mixed in, newest first. While older KaPost pages are
     *  still to come, a wallet row older than the last loaded post waits for them, so the order
     *  stays right as the list grows (iOS 148a5331 `rows`). */
    val rows = remember(items, walletEntries, paging.hasMore, paging.error) {
        val posts = items.map { KaPostsNotificationRow.Post(it) }
        if (walletEntries == null) return@remember posts
        var wallet = walletEntries.map { KaPostsNotificationRow.Wallet(it) }
        val oldest = items.minOfOrNull { it.timestampMs }
        if (paging.hasMore && paging.error == null && oldest != null) wallet = wallet.filter { it.timestampMs >= oldest }
        (posts + wallet).sortedByDescending { it.timestampMs }
    }
    /** A tapped notification goes straight to what it is about: the post (a reply or quote
     *  itself, or your post for a like, repost or tip), on the Feed tab. A follow has no post,
     *  so it opens the follower's profile (iOS KaPosts 4c0d5a8). */
    fun open(item: KaPostsViewModel.NotificationItem) {
        val target = item.targetTxId
        when {
            target != null -> onOpenPost(target)
            item.actorAddress.isNotEmpty() -> onOpenProfile(item.actorAddress)
        }
    }

    LaunchedEffect(Unit) { viewModel.loadNotificationsOnAppear() }
    EndlessScroll(listState = listState) { viewModel.loadMoreNotifications() }
    // Pull-to-refresh reloads page one (iOS .refreshable on both the list and the empty state).
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadNotifications()
            pullRefreshState.endRefresh()
        }
    }

    Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
        if (isLoading && rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
            }
        } else if (rows.isEmpty()) {
            // Wrapped in a LazyColumn purely so the empty state can be pulled to refresh too.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    // iOS emptyState: 120 below the top, a 40pt bell, "Nothing yet" in headline and
                    // the line under it in subheadline, 12 apart, the line 40 in from each side.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 120.dp, start = 40.dp, end = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.NotificationsNone, null, tint = colors.textSecondary, modifier = Modifier.size(40.dp))
                        Text(stringResource(R.string.kpui_nothing_yet), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(
                            if (allInOne) stringResource(R.string.kpui_notif_empty_all)
                            else stringResource(R.string.kpui_notif_empty),
                            color = colors.textSecondary,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // The last row scrolls clear of the dock the tab runs under.
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                items(rows, key = { it.key }) { row ->
                    if (row is KaPostsNotificationRow.Wallet) {
                        KaPostsWalletNotificationRow(row.entry, onClick = {
                            haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                            onOpenWalletEntry(row.entry)
                        })
                        HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                        return@items
                    }
                    val item = (row as KaPostsNotificationRow.Post).item
                    // Per-actor slices: one avatar/name landing repaints its own row only.
                    val actorPhoto by viewModel.contactPhotos
                        .collectSelectedAsState(item.actorAddress) { it[item.actorAddress] }
                    val actorName = posterDisplayNameState(viewModel, item.actorAddress)
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The whole row opens the post; no explorer link.
                            .clickable {
                                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                open(item)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        // The avatar carries a small badge saying what KIND of action this is,
                        // bottom-trailing on a page-background disc (iOS itemRow).
                        Box {
                            ContactAvatar(
                                imageUrl = null,
                                fallbackText = actorName,
                                size = 38.dp,
                                backupPhotoBase64 = actorPhoto?.backupPhotoBase64,
                                address = item.actorAddress,
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item.kind == KaPostsViewModel.NotificationItem.Kind.TIP) {
                                    // the Tip button's own mark (iOS 09f6ae0)
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                    )
                                } else {
                                    Icon(
                                        notificationKindIcon(item.kind),
                                        contentDescription = null,
                                        tint = notificationKindTint(item.kind, colors.textSecondary),
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(actorName) }
                                    append(" ")
                                    append(notificationActionText(item.kind, item.tipAmountSompi))
                                },
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.snippet?.let {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(it, color = colors.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                remember(item.timestampMs) { relativePostTime(item.timestampMs) },
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
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
                pagingFooter(paging, keySuffix = "notifications") { viewModel.loadMoreNotifications(manual = true) }
            }
        }
        if (pullIndicatorShown(pullRefreshState)) {
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
        }
}

/**
 * The first part of a folded post, cut on whitespace so a markdown span or a link is never split
 * mid-token. Anything short enough, or not folded at all, is returned as it is.
 */
private fun foldedLayoutText(text: String, folded: Boolean): String {
    if (!folded || text.length <= FOLDED_POST_LAYOUT_CHARS) return text
    val cut = text.take(FOLDED_POST_LAYOUT_CHARS)
    val lastSpace = cut.indexOfLast { it.isWhitespace() }
    return (if (lastSpace > FOLDED_POST_LAYOUT_CHARS / 2) cut.take(lastSpace) else cut) + "\u2026"
}

/** Eight lines never need more than this, and a cell that lays out less scrolls better. */
private const val FOLDED_POST_LAYOUT_CHARS = 1_200

/**
 * A report about [post] ([ContentReport]): its id, author and text, and a line for what is wrong
 * with it. An email rather than a form because a person reads it, and nothing else can act on a
 * post that lives on a public chain.
 */
private fun reportPost(post: KaPostDraft) = ContentReport(
    buildString {
        appendLine("I want to report this post.")
        appendLine()
        appendLine("Post: ${post.remoteId ?: "not yet on chain"}")
        appendLine("Author: ${post.posterAddress}")
        appendLine("Text: ${post.text.take(500)}")
        appendLine()
        appendLine("What is wrong with it:")
        appendLine()
    }
)

/** "1h 12m left" / "8m left" - how long is left to edit, said the way a countdown is read. */
private fun editWindowLeftText(remainingMs: Long): String {
    // iOS `editWindowText`: hours and minutes, never under "1m".
    val minutes = (remainingMs / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) com.kachat.app.util.tr(R.string.kpui_edit_left_hm, minutes / 60, minutes % 60)
    else com.kachat.app.util.tr(R.string.kaposts_poll_minutes_left, maxOf(minutes, 1))
}

/** iOS Item.Kind.icon: the glyph stamped on the actor's avatar. */
private fun notificationKindIcon(kind: KaPostsViewModel.NotificationItem.Kind): ImageVector = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> Icons.Default.Favorite
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> Icons.Default.ThumbDown
    KaPostsViewModel.NotificationItem.Kind.REPLY -> Icons.Default.ChatBubble
    KaPostsViewModel.NotificationItem.Kind.QUOTE, KaPostsViewModel.NotificationItem.Kind.REPOST -> Icons.Default.Repeat
    KaPostsViewModel.NotificationItem.Kind.FOLLOW -> Icons.Default.PersonAdd
    KaPostsViewModel.NotificationItem.Kind.MENTION -> Icons.Default.AlternateEmail
    // drawn as the Kaspa logo in the row
    KaPostsViewModel.NotificationItem.Kind.TIP -> Icons.Default.MonetizationOn
    KaPostsViewModel.NotificationItem.Kind.OTHER -> Icons.Default.Notifications
}

/** iOS Item.Kind.tint. */
@Composable
private fun notificationKindTint(kind: KaPostsViewModel.NotificationItem.Kind, secondary: Color): Color = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> LocalAppColors.current.danger
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> Color(0xFFFF9500)
    KaPostsViewModel.NotificationItem.Kind.QUOTE, KaPostsViewModel.NotificationItem.Kind.REPOST -> LocalAppColors.current.success
    KaPostsViewModel.NotificationItem.Kind.OTHER -> secondary
    else -> KaspaTeal
}

/** @mention machinery, hoisted: these were compiled per keystroke inside remember blocks. */
private val MENTION_REPLACE_REGEX = Regex("@[a-z0-9-]*$", RegexOption.IGNORE_CASE)

/** Annotation tag carried by @mention ranges - ClickableText resolves it to a profile. */
const val MENTION_ANNOTATION_TAG = "mention"

/** Annotation tag carried by URL ranges - ClickableText opens a Copy/Open dialog (iOS parity:
 *  a link tap never auto-opens the browser). Value = normalized (https-prefixed) URL. */
const val LINK_ANNOTATION_TAG = "link"

/**
 * X-style translate link under the post text. Absent unless the post is confidently in another
 * language, so ordinary same-language feeds look exactly as they did.
 */
@Composable
internal fun TranslateAffordance(
    state: PostTranslationService.TranslationState?,
    canTranslate: Boolean,
    showingOriginal: Boolean,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
) {
    val colors = LocalAppColors.current
    when (state) {
        null -> if (canTranslate) {
            TranslateLink(stringResource(R.string.l53_translate_post), onTranslate)
        }
        PostTranslationService.TranslationState.Translating -> {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.kachat.app.ui.theme.IosActivityIndicator(
                    color = colors.textSecondary,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.l53_translating),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        is PostTranslationService.TranslationState.Translated -> if (showingOriginal) {
            TranslateLink(stringResource(R.string.l53_show_translation), onShowTranslation)
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.l53_translated_from, state.sourceName) + " -",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.l53_show_original),
                    color = KaspaTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onShowOriginal() },
                )
            }
        }
        // A dropped connection or a server that was briefly away - tapping again once there is a
        // connection is the fix, so this stays a live link rather than dead text.
        PostTranslationService.TranslationState.Failed ->
            TranslateLink(stringResource(R.string.l53_translation_unavailable_retry), onTranslate)
        // Nothing a second tap can change - the pair is not served, the post is too long, the post
        // was already in the reader's language. Say so instead of inviting a retry.
        is PostTranslationService.TranslationState.Unavailable -> {
            Spacer(modifier = Modifier.height(4.dp))
            // The service's own reasons are English keys; a server message falls through as is
            // (iOS 369794e `AppLocalization.string(reason)`).
            Text(
                when (state.reason) {
                    "Already in your language" -> stringResource(R.string.l53_already_in_your_language)
                    "Not available in your language" -> stringResource(R.string.l53_not_available_in_your_language)
                    "Translation unavailable" -> stringResource(R.string.l53_translation_unavailable)
                    else -> state.reason
                },
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
internal fun TranslateLink(title: String, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        title,
        color = KaspaTeal,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.clickable { onClick() },
    )
}

/**
 * [range] minus every protected range, in order. Returns `[range]` when nothing overlaps, which is
 * the case for the overwhelming majority of posts.
 */
private fun subtractRanges(protectedRanges: List<IntRange>, range: IntRange): List<IntRange> {
    val overlapping = protectedRanges
        .filter { it.first <= range.last && it.last >= range.first }
        .sortedBy { it.first }
    if (overlapping.isEmpty()) return listOf(range)
    val out = mutableListOf<IntRange>()
    var cursor = range.first
    for (blocked in overlapping) {
        if (blocked.first > cursor) out += cursor until blocked.first
        cursor = maxOf(cursor, blocked.last + 1)
    }
    if (cursor <= range.last) out += cursor..range.last
    return out.filter { !it.isEmpty() }
}

/**
 * Detected links in post text, the set iOS's NSDataDetector finds: http(s) URLs, www. hosts,
 * bare domains with a common top-level domain ("kaspa.org", optionally with a path), and email
 * addresses. A bare domain must not follow an @ (that is a mention, "@alice.kas") or sit inside
 * another word, hence the lookbehind.
 */
private val POST_URL_REGEX = Regex(
    """(?i)(?:https?://|www\.)\S+""" +
        """|(?<![@\w.])[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|fyi|xyz|app|dev|me|co|info|ai|network|finance|exchange|to|gg|tv|us|uk|de|fr|ru|cn|jp|in|eu|ch|nl|se|no|es|it|ca|au|link|site|online|tech|club|pro|money|cash|space|world|news|blog|wiki|shop|store|social|chat|zone|one|id|lol|is|be|at|pl|cz|br|mx|ar|kr|tw|hk|sg|nz|za|ie|fi|dk|pt|gr|tr|ua|il|ae|sa|edu|gov|mil|int|biz|name|mobi|tel|asia|cat|jobs|travel|xxx)\b(?:/\S*)?""" +
        """|[\w.+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+""",
)

/** The tappable form of a detected link: scheme added to bare hosts, mailto: to emails. */
private fun normalizedLinkUrl(raw: String): String = when {
    raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
    raw.contains('@') && !raw.contains('/') -> "mailto:$raw"
    else -> "https://$raw"
}

/** The newest [annotatedPostText] answers by source text: a row scrolling back in reuses its
 *  post's, rather than running the markdown, mention and URL passes over it again (audit M11). */
private val annotatedPostTextCache = android.util.LruCache<String, androidx.compose.ui.text.AnnotatedString>(256)

/** Post text with @mention tokens tinted teal AND annotated for tap-to-profile, and URLs
 *  tinted+underlined AND annotated for the tap-to-Copy/Open dialog. */
private fun annotatedPostText(source: String): androidx.compose.ui.text.AnnotatedString =
    annotatedPostTextCache.get(source) ?: buildAnnotatedPostText(source).also { annotatedPostTextCache.put(source, it) }

private fun buildAnnotatedPostText(source: String): androidx.compose.ui.text.AnnotatedString {
    // Markdown first: it decides what the text actually READS as (markers gone, bullets and
    // numbers materialised), and the linkifier's offsets have to be into that string, not the
    // source. Its spans are then layered on top.
    val rendered = KaPostsMarkdown.render(source)
    val text = rendered.text
    // A mention is an identity, not prose: it always looks the same so it stays recognisable at a
    // glance, and formatting never applies to it. "**@alice.kas** ships it" bolds "ships it" and
    // leaves the mention alone.
    // While mentions are off (MentionsFeature) @name.kas is plain text: no tint, no link.
    val mentionMatches = if (com.kachat.app.util.MentionsFeature.ENABLED) {
        KaPostsViewModel.MENTION_TOKEN_REGEX.findAll(text).toList()
    } else emptyList()
    val mentionRanges = mentionMatches.mapNotNull { match ->
        val domain = match.groups[2] ?: return@mapNotNull null
        val start = domain.range.first - 1 // include the '@'
        if (start < 0) null else start until (domain.range.last + 1)
    }.toList()
    val built = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for (span in rendered.spans) {
            if (span.start >= span.end || span.end > text.length) continue
            val style = span.style
            val decorations = buildList {
                if (style.underline) add(androidx.compose.ui.text.style.TextDecoration.Underline)
                if (style.strikethrough) add(androidx.compose.ui.text.style.TextDecoration.LineThrough)
                // An explicit [label](url) link is underlined like a detected one, so the two
                // kinds of link look the same to a reader.
                if (style.link != null) add(androidx.compose.ui.text.style.TextDecoration.Underline)
            }
            val spanStyle = androidx.compose.ui.text.SpanStyle(
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                fontSize = if (style.subtext) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = when {
                    style.link != null -> KaspaTeal
                    style.subtext -> Color(0xFF8A8A8E)
                    else -> Color.Unspecified
                },
                textDecoration = if (decorations.isEmpty()) {
                    null
                } else {
                    androidx.compose.ui.text.style.TextDecoration.combine(decorations)
                },
            )
            // A span crossing a mention is applied to the pieces either side of it, never over it.
            for (piece in subtractRanges(mentionRanges, span.start until span.end)) {
                addStyle(spanStyle, piece.first, piece.last + 1)
                if (style.link != null) {
                    addStringAnnotation(LINK_ANNOTATION_TAG, style.link, piece.first, piece.last + 1)
                }
            }
        }
        for (match in mentionMatches) {
            val domain = match.groups[2] ?: continue
            val start = domain.range.first - 1 // include the '@'
            if (start < 0) continue
            val end = domain.range.last + 1
            addStyle(
                androidx.compose.ui.text.SpanStyle(color = KaspaTeal, fontWeight = FontWeight.SemiBold),
                start,
                end,
            )
            addStringAnnotation(
                MENTION_ANNOTATION_TAG,
                // the whole token, ending included: "@bob.kas" stays a .kas mention, "@bob" and
                // "@bob.kachat" resolve .kachat first, a bare "@bob" then .kas
                // (KaPostsViewModel.openMentionProfile)
                domain.value.lowercase(),
                start,
                end,
            )
        }
        for (match in POST_URL_REGEX.findAll(text)) {
            // Trailing sentence punctuation isn't part of the link ("see https://kaspa.org.").
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            if (raw.isEmpty()) continue
            val start = match.range.first
            val end = start + raw.length
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = KaspaTeal,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
                start,
                end,
            )
            addStringAnnotation(LINK_ANNOTATION_TAG, normalizedLinkUrl(raw), start, end)
        }
    }
    return droppingKachatEndings(built, mentionMatches)
}

/**
 * "@alice.kachat" drawn as "@alice": the ending is never shown (owner, iOS 522fd9f4
 * `dropKachatEndings`). Only the mention's text; its annotation still names the whole token.
 */
private fun droppingKachatEndings(
    built: androidx.compose.ui.text.AnnotatedString,
    mentionMatches: List<MatchResult>,
): androidx.compose.ui.text.AnnotatedString {
    val ending = ".kachat"
    val cuts = mentionMatches.mapNotNull { match ->
        val domain = match.groups[2] ?: return@mapNotNull null
        if (!domain.value.lowercase().endsWith(ending)) return@mapNotNull null
        (domain.range.last + 1 - ending.length) until (domain.range.last + 1)
    }
    if (cuts.isEmpty()) return built
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        for (cut in cuts) {
            append(built.subSequence(cursor, cut.first))
            cursor = cut.last + 1
        }
        append(built.subSequence(cursor, built.length))
    }
}

/** "tipped your post: 1.5 KAS" for a tip with its amount, else the kind's own text (iOS 09f6ae0). */
private fun notificationActionText(kind: KaPostsViewModel.NotificationItem.Kind, tipAmountSompi: Long?): String {
    if (kind == KaPostsViewModel.NotificationItem.Kind.TIP && tipAmountSompi != null) {
        return "${notificationActionText(kind)}: ${KaspaUnit.plain(tipAmountSompi)} ${KaspaUnit.symbol}"
    }
    return notificationActionText(kind)
}

private fun notificationActionText(kind: KaPostsViewModel.NotificationItem.Kind): String = when (kind) {
    KaPostsViewModel.NotificationItem.Kind.LIKE -> com.kachat.app.util.tr(R.string.kpui_notif_liked)
    KaPostsViewModel.NotificationItem.Kind.DISLIKE -> com.kachat.app.util.tr(R.string.kpui_notif_disliked)
    KaPostsViewModel.NotificationItem.Kind.REPLY -> com.kachat.app.util.tr(R.string.kpui_notif_replied)
    KaPostsViewModel.NotificationItem.Kind.QUOTE -> com.kachat.app.util.tr(R.string.kpui_notif_quoted)
    KaPostsViewModel.NotificationItem.Kind.REPOST -> com.kachat.app.util.tr(R.string.kpui_notif_reposted)
    KaPostsViewModel.NotificationItem.Kind.FOLLOW -> com.kachat.app.util.tr(R.string.kpui_notif_followed)
    KaPostsViewModel.NotificationItem.Kind.MENTION -> com.kachat.app.util.tr(R.string.kpui_notif_mentioned)
    KaPostsViewModel.NotificationItem.Kind.TIP -> com.kachat.app.util.tr(R.string.kpui_notif_tipped)
    KaPostsViewModel.NotificationItem.Kind.OTHER -> com.kachat.app.util.tr(R.string.kpui_notif_other)
}

// MARK: - Follow list overlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaPostsFollowListOverlay(
    followers: Boolean,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    targetPubkey: String? = null,
) {
    val colors = LocalAppColors.current
    val following by viewModel.following.collectAsState()
    val entries by viewModel.followEntries.collectAsState()
    val listState = rememberLazyListState()
    val paging = pagingStateOf(viewModel, KaPostsViewModel.pageFollowList(followers))
    val myAddress = viewModel.myAddress()

    LaunchedEffect(followers, targetPubkey) { viewModel.loadFollowList(followers, targetPubkey) }
    EndlessScroll(listState = listState, key = followers) { viewModel.loadMoreFollowList(followers, targetPubkey) }
    // Pull-to-refresh reloads page one (iOS .refreshable).
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadFollowList(followers, targetPubkey)
            delay(400)
            pullRefreshState.endRefresh()
        }
    }

    KaPostsOverlayScaffold(title = if (followers) stringResource(R.string.kpui_followers) else stringResource(R.string.l53_feed_tab_following), onClose = onClose) {
        val list = entries
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection),
        ) {
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
            }
        } else if (list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (followers) Icons.Default.Group else Icons.Default.PersonAdd,
                    null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    if (followers) stringResource(R.string.kpui_no_followers_yet) else stringResource(R.string.kpui_not_following_anyone),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (followers) stringResource(R.string.kpui_followers_empty)
                    else stringResource(R.string.kpui_following_empty),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.address }) { entry ->
                    // Per-address slices: one avatar/name landing repaints its own row only.
                    val entryName = posterDisplayNameState(viewModel, entry.address)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = null,
                            fallbackText = entryName,
                            size = 38.dp,
                            address = entry.address,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entryName,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.timestampMs?.let {
                                Text(
                                    remember(it) { relativePostTime(it) },
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        // No follow control for yourself (you can appear on another user's list).
                        if (entry.address != myAddress) {
                            val isFollowing = entry.address in following
                            TextButton(onClick = { viewModel.toggleFollow(entry.address, entry.pubkey) }) {
                                Text(
                                    if (isFollowing) stringResource(R.string.kpui_unfollow) else (if (followers) stringResource(R.string.kpui_follow_back) else stringResource(R.string.kpui_follow)),
                                    color = if (isFollowing) colors.textSecondary else KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
                // targetPubkey passed through: a retry on someone else's list must page THEIR
                // list, not the signed-in user's.
                pagingFooter(paging, keySuffix = "follows") { viewModel.loadMoreFollowList(followers, targetPubkey, manual = true) }
            }
        }
        if (pullIndicatorShown(pullRefreshState)) {
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
        }
    }
}

// MARK: - Engagement overlay (who liked/disliked/reposted/quoted, and who tipped)

/** Post Activity's Tips card - after Likes, Dislikes, Reposts and Quotes (iOS 8015069). */
private const val TIPS_TAB = 4

/**
 * A Post Activity card's number (iOS 8ffa130 `countText`), 0 included. Likes, dislikes and tips
 * also have the post's own [counter], so the larger of that and the [loaded] people; reposts and
 * quotes (no counter) are counted from the list, with a "+" while [hasMore] pages remain. A dash
 * until the first page is in ([ready]) - unless the counter already has something to show.
 */
internal fun postActivityCount(counter: Int?, loaded: Int, ready: Boolean, hasMore: Boolean): String {
    if (counter != null) return if (ready || counter > 0) "${maxOf(counter, loaded)}" else "–"
    if (!ready) return "–"
    return if (hasMore && loaded > 0) "$loaded+" else "$loaded"
}

@Composable
fun KaPostEngagementOverlay(
    post: KaPostDraft,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    val lists by viewModel.engagementLists.collectAsState()
    val loaded by viewModel.engagementLoaded.collectAsState()
    // The kind whose card was tapped (iOS 8ffa130 - these used to be tabs), null on the cards.
    var openedKind by remember { mutableStateOf<Int?>(null) }
    val paging = pagingStateOf(
        viewModel,
        post.remoteId?.let { KaPostsViewModel.pageEngagement(it) } ?: "engagement:none",
    )

    LaunchedEffect(post.remoteId) { viewModel.loadEngagement(post) }

    // Who tipped the post (`get-post-tippers`, iOS 8015069): its own list, with amounts.
    var tips by remember(post.remoteId) { mutableStateOf<List<com.kachat.app.services.KTip>>(emptyList()) }
    var tipsCursor by remember(post.remoteId) { mutableStateOf<String?>(null) }
    var tipsHasMore by remember(post.remoteId) { mutableStateOf(false) }
    var tipsLoaded by remember(post.remoteId) { mutableStateOf(false) }
    var tipsMoreRequests by remember(post.remoteId) { mutableStateOf(0) }
    suspend fun loadTips(more: Boolean) {
        val postId = post.remoteId ?: run { tipsLoaded = true; return }
        if (more && tipsCursor == null) return
        try {
            val page = viewModel.fetchTippers(postId, before = if (more) tipsCursor else null)
            val known = tips.map { it.id }.toSet()
            tips = (if (more) tips else emptyList()) + page.items.filter { !more || it.id !in known }
            tipsCursor = page.cursor
            tipsHasMore = page.hasMore && page.cursor != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            tipsHasMore = false
        }
        tipsLoaded = true
    }
    LaunchedEffect(post.remoteId) { loadTips(more = false) }
    LaunchedEffect(tipsMoreRequests) { if (tipsMoreRequests > 0) loadTips(more = true) }

    val kindNames = listOf(
        stringResource(R.string.kaposts_likes),
        stringResource(R.string.kaposts_dislikes),
        stringResource(R.string.kaposts_reposts),
        stringResource(R.string.kaposts_quotes),
        stringResource(R.string.kaposts_tips),
    )

    KaPostsOverlayScaffold(title = stringResource(R.string.kpui_post_activity), onClose = onClose) {
        Column(modifier = Modifier.fillMaxSize()) {
            // One square per kind, the count big on it (0 included) - the Your Domains cards'
            // look, two across (iOS 8ffa130). Each opens that kind's people.
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                items(kindNames.size) { kind ->
                    val count = when (kind) {
                        0 -> postActivityCount(post.likes, lists?.likes?.size ?: 0, loaded, paging.hasMore)
                        1 -> postActivityCount(post.dislikes, lists?.dislikes?.size ?: 0, loaded, paging.hasMore)
                        2 -> postActivityCount(null, lists?.reposts?.size ?: 0, loaded, paging.hasMore)
                        3 -> postActivityCount(null, lists?.quotes?.size ?: 0, loaded, paging.hasMore)
                        else -> postActivityCount(post.tips, tips.size, tipsLoaded, tipsHasMore)
                    }
                    PostActivityCard(kind = kind, count = count, name = kindNames[kind], onClick = { openedKind = kind })
                }
            }

            HorizontalDivider(color = colors.surfaceVariant)
            post.remoteId?.let { txId ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(kaspaExplorer.txUrl(txId)) }
                        .padding(vertical = 14.dp),
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.kpui_view_post_tx),
                        color = KaspaTeal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }

    // The people behind one card, pushed over the cards and titled by the kind: tips with their
    // amounts, the rest with a link to the action's transaction. Back returns to the cards.
    openedKind?.let { kind ->
        KaPostsOverlayScaffold(title = kindNames[kind], onClose = { openedKind = null }) {
            val listState = rememberLazyListState()
            // The stream carries all four kinds at once, so the loop targets the OPEN kind. Tips
            // page on their own.
            EndlessScroll(listState = listState, key = post.remoteId to kind) {
                if (kind != TIPS_TAB) viewModel.loadMoreEngagement(post, kind)
            }

            val rows = lists?.let {
                when (kind) {
                    0 -> it.likes
                    1 -> it.dislikes
                    2 -> it.reposts
                    else -> it.quotes
                }
            } ?: emptyList()

            Box(modifier = Modifier.fillMaxSize()) {
                if (kind == TIPS_TAB) {
                    // Tips: who tipped, how much and when, newest first.
                    if (!tipsLoaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                        }
                    } else if (tips.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.kaposts_no_tips_yet), color = colors.textSecondary)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(tips, key = { it.id }) { tip ->
                                KaPostTipRow(tip = tip, viewModel = viewModel, amountColor = KaspaTeal)
                                HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                            }
                            if (tipsHasMore) {
                                item(key = "tips-more") {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                                    }
                                    LaunchedEffect(tips.size) { tipsMoreRequests += 1 }
                                }
                            }
                        }
                    }
                } else if (!loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                    }
                } else if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.BarChart, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(stringResource(R.string.kpui_nothing_here_yet), color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.kpui_engagement_empty),
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(state = listState) {
                        items(rows, key = { it.actionTxId }) { entry ->
                            // Per-actor slices: one avatar/name landing repaints its own row only.
                            val actorName = posterDisplayNameState(viewModel, entry.actorAddress)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                ContactAvatar(
                                    imageUrl = null,
                                    fallbackText = actorName,
                                    size = 38.dp,
                                    address = entry.actorAddress,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        actorName,
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        remember(entry.timestampMs) { relativePostTime(entry.timestampMs) },
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                Text(
                                    stringResource(R.string.pr_card_view),
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { uriHandler.openUri(kaspaExplorer.txUrl(entry.actionTxId)) },
                                )
                            }
                            HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                        }
                        pagingFooter(paging, keySuffix = "engagement") {
                            viewModel.loadMoreEngagement(post, kind, manual = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One Post Activity square (iOS 8ffa130 `kindCard`), the Your Domains cards' tile: the kind's
 * icon, its count big in the accent, the kind's name under it. Same fill, 18 radius and 0.8 white
 * hairline at 15%.
 */
@Composable
private fun PostActivityCard(kind: Int, count: String, name: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.surface)
            .border(0.8.dp, Color.White.copy(alpha = 0.15f), shape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The same symbols and tints as the post's action bar, 26 high.
            Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
                when (kind) {
                    0 -> Icon(Icons.Default.Favorite, null, tint = colors.danger, modifier = Modifier.size(26.dp))
                    1 -> Icon(Icons.Filled.ThumbDown, null, tint = Color(0xFFFF9500), modifier = Modifier.size(26.dp))
                    2 -> Icon(Icons.Default.Repeat, null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
                    3 -> Icon(IosSymbols.QuoteBubbleFill, null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
                    else -> androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_kaspa_logo),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            // iOS 40pt heavy rounded in the accent, shrinking to half before it is cut.
            KachatFitText(count, color = KaspaTeal, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = IosRoundedFontFamily, minScale = 0.5f)
            // iOS .subheadline semibold, secondary.
            KachatFitText(name, color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, minScale = 0.8f)
        }
    }
}

/**
 * Who tipped a post, newest first: name, avatar, amount and when - from the indexer's
 * `get-post-tippers` (KAPOSTS_INDEXER.md 5.11, iOS `KaPostTippersView`).
 */
@Composable
fun KaPostTippersSheet(
    postId: String,
    viewModel: KaPostsViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var tips by remember(postId) { mutableStateOf<List<com.kachat.app.services.KTip>>(emptyList()) }
    var nextCursor by remember(postId) { mutableStateOf<String?>(null) }
    var hasMore by remember(postId) { mutableStateOf(false) }
    var loading by remember(postId) { mutableStateOf(true) }
    var loadError by remember(postId) { mutableStateOf<String?>(null) }
    var loadMoreRequests by remember(postId) { mutableStateOf(0) }

    suspend fun load(more: Boolean) {
        if (more && nextCursor == null) return
        loading = true
        try {
            val page = viewModel.fetchTippers(postId, before = if (more) nextCursor else null)
            val known = tips.map { it.id }.toSet()
            tips = (if (more) tips else emptyList()) + page.items.filter { !more || it.id !in known }
            nextCursor = page.cursor
            hasMore = page.hasMore && page.cursor != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = com.kachat.app.util.UserFacingError.message(e, context.getString(R.string.something_went_wrong))
            hasMore = false
        } finally {
            loading = false
        }
    }
    LaunchedEffect(postId) { load(more = false) }
    LaunchedEffect(loadMoreRequests) { if (loadMoreRequests > 0) load(more = true) }

    ActionSheetContainer(title = stringResource(R.string.kaposts_tips), subtitle = null, onDismiss = onDismiss) {
        when {
            loading && tips.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
            }
            tips.isEmpty() -> Text(
                loadError ?: stringResource(R.string.kaposts_no_tips_yet),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
            // A lazy list (iOS `List`), so the spinner row - and the next page - comes only when it
            // is scrolled to; in a plain Column it composed at once and pulled every page. Capped
            // in height: the sheet's own column scrolls, and a lazy list needs a bounded height.
            else -> LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.7f).dp),
            ) {
                items(tips, key = { it.id }) { tip ->
                    KaPostTipRow(tip = tip, viewModel = viewModel, amountColor = colors.textPrimary)
                }
                if (hasMore) {
                    item(key = "tips-more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                        }
                        LaunchedEffect(tips.size) { if (!loading) loadMoreRequests += 1 }
                    }
                }
            }
        }
    }
}

/** One tip: who (avatar and name), when, and how much. */
@Composable
private fun KaPostTipRow(
    tip: com.kachat.app.services.KTip,
    viewModel: KaPostsViewModel,
    amountColor: Color,
) {
    val colors = LocalAppColors.current
    val address = remember(tip.userPublicKey) { com.kachat.app.services.KaPostsService.kaspaAddressFromPubkey(tip.userPublicKey).orEmpty() }
    val name = if (address.isEmpty()) tip.userPublicKey else posterDisplayNameState(viewModel, address)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        ContactAvatar(
            imageUrl = null,
            fallbackText = name,
            size = 38.dp,
            address = address.ifEmpty { null },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                remember(tip.timestamp) { relativePostTime(tip.timestamp) },
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "${KaspaUnit.plain(tip.amountSompi)} ${KaspaUnit.symbol}",
            color = amountColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** Instant tips go on one tap, with no swipe, so the most one may send: a bigger default tip
 *  opens the tip screen, whose swipe is the confirmation (iOS 369794e `instantTipLimitSompi`). */
internal const val KAPOST_INSTANT_TIP_LIMIT_SOMPI = 10L * 100_000_000L
internal val KAPOST_INSTANT_TIP_LIMIT_NOTICE: String get() = com.kachat.app.util.tr(R.string.kpui_instant_tip_limit)
/** The smallest default tip: Kaspa's storage mass (KIP-9) refuses a payment output much under
 *  0.1 KAS whatever coins pay for it, so 0.0021 KAS could never go out. 0.2 KAS, the wallet's
 *  dust threshold, leaves room for the change output too (iOS b78fdb0 `instantTipMinimumSompi`). */
internal const val KAPOST_INSTANT_TIP_MINIMUM_SOMPI = 20_000_000L

/** A currency amount in sompi at a KAS price, or null when the answer isn't a real amount: not
 *  finite, negative, or more than all the KAS there will ever be ([KaspaUnit.MAX_TYPED_SOMPI]).
 *  Every currency-to-sompi conversion of a tip goes through here (iOS audit 2026-11-21 #10
 *  `KaPostsSettingsView.sompi(fiat:price:)`). */
internal fun kaPostTipSompi(fiat: Double, price: Double): Long? {
    if (!fiat.isFinite() || !price.isFinite() || fiat < 0 || price <= 0) return null
    val value = fiat / price * 100_000_000.0
    if (!value.isFinite() || value < 0 || Math.round(value) > KaspaUnit.MAX_TYPED_SOMPI) return null
    return Math.round(value)
}
/** "0.2 KAS" (iOS `instantTipMinimumText`). */
internal fun kaPostInstantTipMinimumText(): String = "${KaspaUnit.plain(KAPOST_INSTANT_TIP_MINIMUM_SOMPI)} ${KaspaUnit.symbol}"

/** Which page of KaPost's settings (iOS 518fdc8c `KaPostsSettingsView.Part`). */
enum class KaPostsSettingsPart {
    /** Settings > KaPost: the cool down. */
    KAPOST,
    /** Settings > Customization > Tipping: what Tip sends, on a post and in a 1:1 chat's +. */
    TIPPING,
}

/**
 * Settings > KaPost (iOS 148a5331 `KaPostsSettingsView(embedded: true)`): KaPost's own settings -
 * the cool down (Tipping moved to Settings > Customization > Tipping, iOS 518fdc8c) - titled
 * "KaPost" with a back chevron. The feed's menu no longer lists it (owner's call, 2026-11-21).
 */
@Composable
fun KaPostsSettingsScreen(onBack: () -> Unit) = KaPostsSettingsOverlay(onClose = onBack, embedded = true)

/**
 * Settings > Customization > Tipping (iOS 518fdc8c `KaPostsSettingsView(part: .tipping)`): Tip In,
 * the instant tip and Tips From, shared by KaPost's Tip and a 1:1 chat's Tip. Titled "Tipping"
 * with a back chevron.
 */
@Composable
fun KaPostsTippingSettingsScreen(onBack: () -> Unit) =
    KaPostsSettingsOverlay(onClose = onBack, embedded = true, part = KaPostsSettingsPart.TIPPING)

/**
 * KaPosts' own settings, behind the gear in the feed's icon row: the cool down before post (the
 * 5-second Undo window every action waits out, or none), and the default tip - the amount a tap
 * on Tip sends at once, with no amount screen in between. The tip is set in KAS or, with the unit
 * tapped, in the app's currency (USD unless changed in Settings): then each tip sends that much
 * worth of KAS at the price of the moment. Mirrors iOS's KaPostsSettingsView (KaPosts 4c0d5a8).
 */
@Composable
fun KaPostsSettingsOverlay(
    onClose: () -> Unit,
    /** Settings > Posts: with a back chevron (both are titled "Posts" since iOS d88891ea). */
    embedded: Boolean = false,
    /** The cool down (Settings > KaPost) or Tipping (Settings > Customization > Tipping). */
    part: KaPostsSettingsPart = KaPostsSettingsPart.KAPOST,
    settingsViewModel: com.kachat.app.viewmodels.KaPostsSettingsViewModel = hiltViewModel(),
    portfolioViewModel: com.kachat.app.viewmodels.KasPriceViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val defaultTipSompi by settingsViewModel.kaPostsDefaultTipSompi.collectAsState()
    val defaultTipFiat by settingsViewModel.kaPostsDefaultTipFiat.collectAsState()
    val cooldownEnabled by settingsViewModel.kaPostsCooldownEnabled.collectAsState()
    // Tip In (iOS f954738): Kaspa, kUSDT, or Ask Each Time. Only Kaspa has the instant tip, and
    // Tips From shows unless it's kUSDT.
    // Read once as the page opens and then changed only by the picker, as iOS's @State: turning
    // the instant tip off doesn't move it (an older install's Kaspa came from that amount).
    val savedTipAsset by settingsViewModel.kaPostsTipAssetInUse.collectAsState()
    var pickedTipAsset by remember { mutableStateOf<com.kachat.app.repository.KaPostsTipAsset?>(null) }
    LaunchedEffect(Unit) {
        if (pickedTipAsset == null) pickedTipAsset = settingsViewModel.loadKaPostsTipAssetInUse()
    }
    val tipAsset = pickedTipAsset ?: savedTipAsset
    // Tips From (iOS 071e901 `tipSourceTitle`): the address tips leave from by default.
    // Per account (iOS ee5ec98 `KaPostsTipSourceStore`).
    val tipSourceAccount by walletViewModel.address.collectAsState()
    val tipSourceIndex by remember(tipSourceAccount) { settingsViewModel.kaPostsTipSourceFlow(tipSourceAccount) }.collectAsState(null)
    var tipSource by remember { mutableStateOf<SendSource?>(SendSource.Chatting) }
    LaunchedEffect(tipSourceIndex) { tipSource = kaPostDefaultTipSource(tipSourceIndex, settingsViewModel, walletViewModel) }
    var showTipSource by remember { mutableStateOf(false) }
    val appCurrency by portfolioViewModel.currency.collectAsState()
    // Read so the footer's KAS figure updates when a price lands.
    val livePrice by portfolioViewModel.currentPriceUsd.collectAsState()

    var instantTipEnabled by remember { mutableStateOf(false) }
    var tipText by remember { mutableStateOf("") }
    /** The default tip is typed in [tipCurrency] rather than KAS. */
    var tipInFiat by remember { mutableStateOf(false) }
    /** Null = the app's currency. */
    var pickedCurrency by remember { mutableStateOf<String?>(null) }
    val tipCurrency = pickedCurrency ?: appCurrency
    // Seeded from the stored tip once it loads; typing owns the field after that, so a re-read
    // cannot rewrite what is half-typed.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(defaultTipSompi, defaultTipFiat) {
        if (seeded) return@LaunchedEffect
        val fiat = defaultTipFiat
        val sompi = defaultTipSompi
        when {
            fiat != null -> {
                instantTipEnabled = true
                tipInFiat = true
                pickedCurrency = fiat.second
                tipText = kaPostsFiatTipText(fiat.first, fiat.second)
            }
            sompi != null -> {
                instantTipEnabled = true
                tipText = kasAmountText(sompi)
            }
            else -> return@LaunchedEffect
        }
        seeded = true
    }

    /** The tip amount is being typed: a checkmark beside it locks it in, then it's read-only with
     *  a pencil to change it (iOS b78fdb0 `editingTip`). */
    var editingTip by remember { mutableStateOf(false) }
    val tipFocus = remember { FocusRequester() }
    var tipFieldFocused by remember { mutableStateOf(false) }
    val tipFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // A turn later, once the field is enabled and can take focus.
    LaunchedEffect(editingTip) {
        if (editingTip) runCatching { tipFocus.requestFocus() }
    }

    /** The amount typed, as a number (either unit). */
    fun typedAmount(): Double? = KaspaUnit.sompiFromUserText(tipText)?.let { it / 100_000_000.0 }

    /** The KAS price a currency tip is measured at here: the fresh one tips are sent at when
     *  there is one, else the last known (iOS b78fdb0 `tipPrice`). */
    fun tipPrice(currency: String): Double? =
        settingsViewModel.freshPriceIn(currency) ?: settingsViewModel.priceIn(currency)

    /** The typed tip in sompi: as typed in KAS, or worked out at today's price for a currency
     *  (null without a price). */
    fun typedSompi(): Long? {
        val amount = typedAmount()?.takeIf { it > 0 } ?: return null
        if (!tipInFiat) return KaspaUnit.sompiFromUserText(tipText)
        val price = tipPrice(tipCurrency)?.takeIf { it > 0 } ?: return null
        return kaPostTipSompi(amount, price)
    }

    /** Under the smallest tip the network takes ([KAPOST_INSTANT_TIP_MINIMUM_SOMPI]): not saved. */
    fun tipBelowMinimum(): Boolean {
        val sompi = typedSompi() ?: return false
        return sompi < KAPOST_INSTANT_TIP_MINIMUM_SOMPI
    }

    /** A currency amount worth more KAS than can exist at today's price: not a tip, not saved
     *  (iOS audit 2026-11-21 #10 `tipOutOfRange`). */
    fun tipOutOfRange(): Boolean {
        if (!tipInFiat) return false
        val amount = typedAmount()?.takeIf { it > 0 } ?: return false
        val price = tipPrice(tipCurrency)?.takeIf { it > 0 } ?: return false
        return kaPostTipSompi(amount, price) == null
    }

    /** An amount that can be saved: above zero, not under the network's minimum, and a real amount. */
    fun tipAmountIsValid(): Boolean = (typedAmount() ?: 0.0) > 0 && !tipBelowMinimum() && !tipOutOfRange()

    /** The saved default tip, in the unit it was saved in. */
    fun showSavedTip() {
        val fiat = defaultTipFiat
        val sompi = defaultTipSompi
        if (fiat != null && fiat.first > 0) {
            tipInFiat = true
            pickedCurrency = fiat.second
            tipText = kaPostsFiatTipText(fiat.first, fiat.second)
        } else if (sompi != null && sompi > 0) {
            tipInFiat = false
            tipText = kasAmountText(sompi)
        }
    }

    fun commitTip() {
        if (!instantTipEnabled || tipBelowMinimum() || tipOutOfRange()) return
        seeded = true
        if (tipInFiat) {
            val amount = typedAmount()?.takeIf { it > 0 } ?: return
            settingsViewModel.setKaPostsDefaultTipFiat(amount, tipCurrency)
        } else {
            val sompi = KaspaUnit.sompiFromUserText(tipText)?.takeIf { it > 0 } ?: return
            settingsViewModel.setKaPostsDefaultTipSompi(sompi)
        }
    }

    /** The checkmark (or the keyboard's Done): saves the amount and closes the keyboard. One
     *  that can't go out (empty, zero, under the minimum) isn't saved: the saved tip comes back,
     *  so the keyboard can always be closed (iOS b78fdb0 `lockTip`). */
    fun lockTip() {
        haptic(com.kachat.app.util.IosHaptic.SELECTION)
        if (tipAmountIsValid()) commitTip() else showSavedTip()
        editingTip = false
        tipFocusManager.clearFocus()
    }

    /** Flips the tip between KAS and the app's currency, carrying the amount over at today's
     *  price (the number stays as typed when there is no price yet). Saved at once unless the
     *  amount is being typed (then the checkmark saves it). An amount that comes out under the
     *  network's minimum (a currency amount rounds to the cent) puts the saved tip back instead. */
    fun switchTipUnit() {
        haptic(com.kachat.app.util.IosHaptic.SELECTION)
        val toFiat = !tipInFiat
        val currency = if (toFiat) appCurrency else tipCurrency
        val price = tipPrice(currency)
        val amount = typedAmount()
        if (amount != null && amount > 0 && price != null && price > 0) {
            tipText = if (toFiat) kaPostsFiatTipText(amount * price, currency) else formatKasAmount(amount / price)
        }
        pickedCurrency = currency
        tipInFiat = toFiat
        if (tipBelowMinimum()) {
            showSavedTip()
            return
        }
        if (!editingTip) commitTip()
    }

    val pageTitle = when {
        part == KaPostsSettingsPart.TIPPING -> stringResource(R.string.kpui_tipping)
        // "Posts" either way, no longer "KaPost Settings" (iOS d88891ea)
        else -> stringResource(R.string.tab_posts)
    }
    KaPostsOverlayScaffold(title = pageTitle, onClose = onClose, backBar = embedded) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (part == KaPostsSettingsPart.KAPOST) Column {
                SettingsSection(title = null) {
                    SettingsSwitchItem(stringResource(R.string.kpui_cool_down), cooldownEnabled) { enabled ->
                        settingsViewModel.setKaPostsCooldownEnabled(enabled)
                    }
                }
                SettingsFormFooter(
                    if (cooldownEnabled) stringResource(R.string.kpui_cooldown_on)
                    else stringResource(R.string.kpui_cooldown_off)
                )
            }
            if (part == KaPostsSettingsPart.TIPPING) Column {
                SettingsSection(title = stringResource(R.string.kpui_tipping)) {
                    val tipAssetLabels = mapOf(
                        com.kachat.app.repository.KaPostsTipAsset.ASK to stringResource(R.string.kp5_tip_in_ask),
                        com.kachat.app.repository.KaPostsTipAsset.KASPA to stringResource(R.string.kp5_tip_in_kaspa),
                        com.kachat.app.repository.KaPostsTipAsset.KUSDT to stringResource(R.string.kp5_tip_in_kusdt),
                    )
                    SettingsPickerItem(
                        label = stringResource(R.string.kp5_tip_in),
                        options = listOf(
                            com.kachat.app.repository.KaPostsTipAsset.ASK,
                            com.kachat.app.repository.KaPostsTipAsset.KASPA,
                            com.kachat.app.repository.KaPostsTipAsset.KUSDT,
                        ),
                        selected = tipAsset,
                        optionLabel = { tipAssetLabels[it].orEmpty() },
                        onSelect = { asset ->
                            haptic(com.kachat.app.util.IosHaptic.SELECTION)
                            pickedTipAsset = asset
                            // an instant tip is always Kaspa: Ask Each Time and kUSDT turn it off
                            if (asset != com.kachat.app.repository.KaPostsTipAsset.KASPA) {
                                instantTipEnabled = false
                                editingTip = false
                                seeded = true
                            }
                            settingsViewModel.setKaPostsTipAsset(asset)
                        },
                    )
                    if (tipAsset == com.kachat.app.repository.KaPostsTipAsset.KASPA) {
                    SettingsDivider()
                    SettingsSwitchItem(stringResource(R.string.kpui_send_default_tip), instantTipEnabled) { enabled ->
                        instantTipEnabled = enabled
                        if (enabled) {
                            // No device lock: sends don't ask (owner's call, 2026-11-13; iOS 369794e).
                            if (defaultTipSompi == null && defaultTipFiat == null) {
                                // starts at 0.2 KAS (anything left from before that the network
                                // wouldn't take is replaced too; iOS b78fdb0)
                                if (tipText.isBlank() || (typedAmount() ?: 0.0) <= 0 || tipBelowMinimum()) {
                                    tipInFiat = false
                                    tipText = kasAmountText(KAPOST_INSTANT_TIP_MINIMUM_SOMPI)
                                }
                                editingTip = false
                                commitTip()
                            }
                        } else {
                            seeded = true
                            settingsViewModel.setKaPostsDefaultTipSompi(null)
                        }
                    }
                    if (instantTipEnabled) {
                        SettingsDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (tipInFiat) {
                                Text(
                                    currencySymbolFor(tipCurrency),
                                    color = colors.textSecondary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 22.dp),
                                )
                            } else {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            com.kachat.app.ui.theme.IosTextField(
                                value = tipText,
                                // Nothing is saved while typing: the checkmark saves, and so does
                                // the keyboard closing some other way, with an amount that can go
                                // out (iOS b78fdb0).
                                onValueChange = { input ->
                                    tipText = input.filter { it.isDigit() || it == '.' || it == ',' }
                                },
                                enabled = editingTip,
                                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                    color = if (editingTip) colors.textPrimary else colors.textSecondary,
                                ),
                                placeholder = { Text("1", color = colors.textSecondary) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { lockTip() }),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(tipFocus)
                                    .onFocusChanged { state ->
                                        val was = tipFieldFocused
                                        tipFieldFocused = state.isFocused
                                        if (!was || state.isFocused || !editingTip) return@onFocusChanged
                                        editingTip = false
                                        // left empty, zero or too small: show the saved tip again
                                        if (tipAmountIsValid()) commitTip() else showSavedTip()
                                    },
                            )
                            // Beside the value: a checkmark locks the amount in (or, with an amount
                            // that can't go out, puts the saved one back) and closes the keyboard,
                            // then a pencil opens it again (iOS b78fdb0).
                            val tipEditLabel = stringResource(if (editingTip) R.string.done else R.string.ab_edit)
                            val tipUnitLabel = if (tipInFiat) stringResource(R.string.kpui_tip_in_fiat, tipCurrency.uppercase())
                            else stringResource(R.string.kpui_tip_in_kas, appCurrency.uppercase())
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (editingTip) KaspaTeal else KaspaTeal.copy(alpha = 0.12f))
                                    .clickable {
                                        if (editingTip) {
                                            lockTip()
                                        } else {
                                            haptic(com.kachat.app.util.IosHaptic.SELECTION)
                                            editingTip = true
                                        }
                                    }
                                    .semantics { contentDescription = tipEditLabel },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (editingTip) Icons.Default.Check else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (editingTip) Color.Black else KaspaTeal,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            // Tap to set the tip in the other unit.
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(KaspaTeal.copy(alpha = 0.12f))
                                    .clickable { switchTipUnit() }
                                    .semantics { contentDescription = tipUnitLabel }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (tipInFiat) tipCurrency.uppercase() else KaspaUnit.symbol,
                                    color = KaspaTeal,
                                    fontSize = 15.sp,
                                )
                                Icon(
                                    Icons.Default.SwapVert,
                                    contentDescription = null,
                                    tint = KaspaTeal,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    }
                    // Tipping > Tips From (iOS 071e901): the default tip's address and
                    // the tip sheet's first choice. Not for kUSDT tips (iOS f954738).
                    if (tipAsset != com.kachat.app.repository.KaPostsTipAsset.KUSDT) {
                    SettingsDivider()
                    SettingsNavigationItem(
                        label = stringResource(R.string.kaposts_tips_from),
                        icon = null,
                        showIcon = false,
                        value = when (val source = tipSource) {
                            SendSource.Chatting -> stringResource(R.string.chatting_address)
                            is SendSource.Spending -> source.label
                            null -> stringResource(R.string.kaposts_tips_from_not_available)
                        },
                        onClick = { showTipSource = true },
                    )
                    }
                }
                if (tipAsset == com.kachat.app.repository.KaPostsTipAsset.ASK) {
                    SettingsFormFooter(stringResource(R.string.kp5_tip_in_ask_footer))
                } else if (tipAsset == com.kachat.app.repository.KaPostsTipAsset.KUSDT) {
                    SettingsFormFooter(stringResource(R.string.kp5_tip_in_kusdt_footer))
                } else if (instantTipEnabled) {
                    // What a tip sends: in KAS, or the currency amount with what it comes to in
                    // KAS today.
                    val amountFooter = if (!tipInFiat) {
                        stringResource(R.string.kpui_tap_unit)
                    } else {
                        val price = livePrice.let { tipPrice(tipCurrency) }
                        val amount = typedAmount()
                        if (amount != null && amount > 0 && price != null && price > 0) {
                            stringResource(R.string.kpui_each_tip_sends_fmt, formatFiatAmount(amount, tipCurrency), formatKasAmount(amount / price))
                        } else {
                            stringResource(R.string.kpui_each_tip_sends)
                        }
                    }
                    // Under the network's minimum: said in red above the usual footer (iOS b78fdb0).
                    if (livePrice.let { tipBelowMinimum() }) {
                        SettingsFormFooter(
                            stringResource(R.string.feed54_tip_minimum_footer, kaPostInstantTipMinimumText()),
                            color = colors.danger,
                        )
                    }
                    SettingsFormFooter("$amountFooter ${stringResource(R.string.kpui_up_to_10_kas)}")
                }
            }
            // Which pings reach you is Settings > Notifications > KaPost, behind the cog on the
            // Notifications tab (iOS KaPosts 865c9ab).
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showTipSource) {
        KaPostTipSourcePage(
            selected = tipSource,
            walletViewModel = walletViewModel,
            onPick = { picked ->
                val index = picked.spendingIndex
                if (index != tipSourceIndex) {
                    settingsViewModel.setKaPostsTipSourceIndex(index)
                    haptic(com.kachat.app.util.IosHaptic.SELECTION)
                }
            },
            onClose = { showTipSource = false },
        )
    }
}

/**
 * KaPost Settings > Tips From (iOS 071e901 `TipSourcePage`): the address tips leave from unless
 * the tip sheet picks another. The chatting address sends a tip that counts on the post; another
 * address sends it privately (`ChatViewModel.sendPayment`: no post named, nothing counted, nobody
 * listed as the tipper).
 */
@Composable
private fun KaPostTipSourcePage(
    selected: SendSource?,
    walletViewModel: WalletViewModel,
    onPick: (SendSource) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val chattingAddress by walletViewModel.address.collectAsState()
    KaPostsOverlayScaffold(title = stringResource(R.string.kaposts_tips_from), onClose = onClose, backBar = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.kaposts_tips_from_footnote),
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
            OwnAddressList(
                selected = selected?.address(chattingAddress),
                walletViewModel = walletViewModel,
                onPick = onPick,
            )
        }
    }
}

/** A currency amount for the field: the currency's decimals (8 for Bitcoin, else 2), trimmed
 *  ("1", "1.5", "0.25"; iOS f954738 `fiatText(_:currency:)`). */
private fun kaPostsFiatTipText(value: Double, currency: String): String {
    val decimals = if (currency.equals("btc", ignoreCase = true)) 8 else 2
    return String.format(java.util.Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
}

/** A sompi amount as KAS, with the trailing zeros off: "1", "0.5", "12.25". */
private fun kasAmountText(sompi: Long): String =
    java.math.BigDecimal(sompi).divide(java.math.BigDecimal(100_000_000)).stripTrailingZeros().toPlainString()

// MARK: - Muted/Blocked + Bookmarks overlays

@Composable
fun KaPostsModerationOverlay(
    blocked: Boolean,
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val mutedSet by viewModel.muted.collectAsState()
    val blockedSet by viewModel.blocked.collectAsState()
    val addresses = remember(blocked, mutedSet, blockedSet) {
        (if (blocked) blockedSet else mutedSet).sorted()
    }

    KaPostsOverlayScaffold(title = if (blocked) stringResource(R.string.blocked) else stringResource(R.string.kpui_muted), onClose = onClose) {
        if (addresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (blocked) Icons.Default.PanTool else Icons.Default.VolumeOff,
                    null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    stringResource(if (blocked) R.string.l53_no_blocked_accounts else R.string.l53_no_muted_accounts),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (blocked) stringResource(R.string.kpui_blocked_footer)
                    else stringResource(R.string.kpui_muted_footer),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn {
                items(addresses, key = { it }) { address ->
                    // Per-address slices: one avatar/name landing repaints its own row only.
                    val rowName = posterDisplayNameState(viewModel, address)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ContactAvatar(
                            imageUrl = null,
                            fallbackText = rowName,
                            size = 40.dp,
                            address = address,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            rowName,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val hapticView = LocalView.current
                        OutlinedButton(
                            onClick = {
                                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                if (blocked) viewModel.unblock(address) else viewModel.unmute(address)
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, KaspaTeal.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = KaspaTeal),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(stringResource(if (blocked) R.string.l53_unblock else R.string.l53_unmute), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
fun KaPostsBookmarksOverlay(
    viewModel: KaPostsViewModel,
    onClose: () -> Unit,
    /** The reply bubble opens the Reply composer; the card itself is inert here (iOS). */
    onReply: (KaPostDraft) -> Unit,
    onViewEngagement: (KaPostDraft) -> Unit,
    onRepostTap: (KaPostDraft) -> Unit,
    onTip: ((KaPostDraft) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // Recompute against the live lists so un-bookmarking updates immediately - but the
    // full-tree scan only re-runs when one of those lists actually changed.
    val tree by viewModel.postTree.collectAsState()
    // Saved per account (iOS f954738 `KaPostsBookmarkStore`): a tap or a switch redraws too.
    val bookmarksRevision by viewModel.bookmarksRevision.collectAsState()
    val bookmarks = remember(tree, bookmarksRevision) { viewModel.bookmarkedPosts() }
    val loadingBookmarks by viewModel.isLoadingBookmarks.collectAsState()
    // Bookmarked posts no list has loaded are fetched by id as the screen opens.
    LaunchedEffect(bookmarksRevision) { viewModel.loadMissingBookmarks() }

    KaPostsOverlayScaffold(title = stringResource(R.string.kpui_bookmarks), onClose = onClose) {
        Box(modifier = Modifier.fillMaxSize()) {
        if (bookmarks.isEmpty() && loadingBookmarks) {
            CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.align(Alignment.Center))
        } else if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.BookmarkBorder, null, tint = colors.textSecondary, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(14.dp))
                Text(stringResource(R.string.kpui_no_bookmarks), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.kpui_bookmarks_empty),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn {
                items(bookmarks, key = { "bookmark-${it.id}" }) { post ->
                    KaPostCell(
                        post = post,
                        viewModel = viewModel,
                        // Bookmarks has no thread surface of its own, so the card tap stays inert.
                        onOpenThread = {},
                        onRepostTap = { onRepostTap(post) },
                        onViewEngagement = { onViewEngagement(post) },
                        onReply = { onReply(post) },
                        onTip = onTip?.let { tip -> { tip(post) } },
                        // a bookmarked reply says who it answers (iOS 961fc05a)
                        showsReplyingTo = true,
                    )
                    HorizontalDivider(color = colors.surfaceVariant, modifier = Modifier.padding(start = 68.dp))
                }
            }
        }
        // The feed's toast layer sits behind this window, Undo included (iOS ee5ec98).
        KaPostsToastLayer(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
        )
        }
    }
}

/** A cover's slide ([modifier]) and its [back]: the same slide-out the edge swipe plays, then
 *  [rememberKaPostsCoverSlideState]'s onClose - what the bar's back chevron runs (iOS
 *  `KaPostsSlideHandle.back`). */
internal class KaPostsCoverSlideState(
    val modifier: Modifier,
    val back: () -> Unit,
    /** How far the screen is in place: 1 at rest, 0 off to the right. The fade under the dock
     *  copy follows it (iOS d089011f: it comes in with the screen and leaves with it). Read it
     *  where it's drawn, not in composition. */
    val inPlace: () -> Float = { 1f },
)

/**
 * A KaPosts cover, presented the way a pushed screen behaves: it slides in from the right, and a
 * drag from the left edge follows the finger - past a third of the way it leaves (calling
 * [onClose] once it is off screen), otherwise it settles back. Matches iOS's `kaPostsSlideCover`
 * (iOS 34c53da/08af09c), which gave posts, profiles and the menu screens this same expression.
 *
 * Applied as a modifier on the cover's own root, before its background, so the whole screen
 * travels. The edge gesture is watched on the Initial pass and only consumes once it has passed
 * touch slop, so a tap that starts near the left edge - the Back control lives there - still
 * reaches the content underneath.
 */
@Composable
internal fun rememberKaPostsCoverSlideState(onClose: () -> Unit, slidesIn: Boolean = true): KaPostsCoverSlideState {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    // A level revealed by backing out of a deeper one is already where it belongs; only a level
    // opened from the one beneath travels.
    val offsetX = remember { Animatable(if (slidesIn) widthPx else 0f) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(widthPx) {
        if (!leaving) offsetX.animateTo(0f, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    val shadowWidthPx = with(density) { 14.dp.toPx() }
    val currentOnClose by rememberUpdatedState(onClose)
    val back: () -> Unit = remember(widthPx) {
        {
            if (!leaving) {
                leaving = true
                scope.launch {
                    offsetX.animateTo(widthPx, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
                    currentOnClose()
                }
            }
        }
    }
    val modifier = Modifier
        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        // The edge shadow is a 14dp gradient strip along the leading edge, drawn only while the
        // screen is off its resting place - never a shadow on the whole cover, which would
        // re-rasterize the entire list on every frame of the drag (iOS 646398a).
        .drawWithContent {
            drawContent()
            if (offsetX.value > 0.5f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                        startX = 0f,
                        endX = shadowWidthPx,
                    ),
                    size = Size(shadowWidthPx, size.height),
                )
            }
        }
        .pointerInput(widthPx) {
            val edge = with(density) { 24.dp.toPx() }
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                var dragging = false
                var travelled = 0f
                val velocity = VelocityTracker()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position.x - change.previousPosition.x
                    travelled += delta
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!dragging && kotlin.math.abs(travelled) > slop) dragging = true
                    if (dragging) {
                        change.consume()
                        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceAtLeast(0f)) }
                    }
                }
                if (!dragging) return@awaitEachGesture
                val pulled = offsetX.value
                // Where the finger was headed, not only where it let go: a quick flick from the
                // edge leaves even though it never travelled a third of the way (iOS's
                // predictedEndTranslation test).
                val projected = pulled + velocity.calculateVelocity().x * 0.3f
                scope.launch {
                    if (pulled > widthPx / 3f || projected > widthPx * 0.6f) {
                        leaving = true
                        offsetX.animateTo(widthPx, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
                        onClose()
                    } else {
                        offsetX.animateTo(0f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
                    }
                }
            }
        }
    return KaPostsCoverSlideState(modifier, back) {
        if (widthPx <= 0f) 1f else (1f - offsetX.value / widthPx).coerceIn(0f, 1f)
    }
}

/**
 * Closes the KaPosts cover this screen is the root of, with the slide - null where there is no
 * cover to close (the feed, the composer). [KaPostsOverlayStatusBar] puts a back chevron in front
 * of the dot when it is set (iOS `kaPostsBack`, 8f0a86a).
 */
internal val LocalKaPostsBack = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The feed's own header indicators - clickable connection dot leading, chatting balance centred -
 * repeated on every overlay that covers the feed. Reading a thread or a profile is still "being in
 * KaPosts", and both answer questions that come up mid-read: whether a like that is not landing is
 * the network's fault, and whether there is enough KAS to reply at all. The overlays are separate
 * Dialog windows, so nothing behind them shows through.
 */
@Composable
internal fun KaPostsOverlayStatusBar(
    modifier: Modifier = Modifier,
    /** What sits top left, beside the dot, when the screen has no Back. */
    leading: (@Composable () -> Unit)? = null,
    /** What sits top right: Profile's bell (iOS 148a5331 `AllNotificationsBell`). */
    trailing: (@Composable () -> Unit)? = null,
) {
    // iOS KaPostsStatusChrome: ONE inline bar - a back chevron on the leading side on a screen
    // that is the root of a cover, then the connection dot (top left, as on every other KaChat
    // screen: owner's call, iOS 148a5331), the balance in the title's place. The principal item
    // takes precedence over the navigation title, so iOS draws no "Post" / "Profile" title there.
    val back = LocalKaPostsBack.current
    val haptic = com.kachat.app.util.rememberHaptics()
    // iOS 26: the chevron on a 44 glass disc 16 in, as MainPageHeader's (52 tall, 4 above/below).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (back != null) {
                // The chevron is its own bar button (semibold, in the accent), not the system back.
                ToolbarGlassGroup {
                    ToolbarIconButton(
                        Icons.Filled.ArrowBackIosNew,
                        stringResource(R.string.back),
                        onClick = {
                            haptic(com.kachat.app.util.IosHaptic.SELECTION)
                            back()
                        },
                        iconSize = 18.dp,
                    )
                }
            } else {
                leading?.invoke()
            }
            // No connection dot (iOS 518fdc8c: only at the top of Settings and on the dock's
            // Settings icon).
        }
        BalanceTopBarLabel(modifier = Modifier.align(Alignment.Center))
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** Shared full-screen overlay chrome: back arrow + bold title over the app background. */
@Composable
private fun KaPostsOverlayScaffold(
    title: String,
    onClose: () -> Unit,
    /** iOS presents it as a sheet: [title] inline with Done top right, rather than the pushed
     *  page's back, dot and balance (Search). */
    doneBar: Boolean = false,
    /** A page pushed inside a settings screen: [title] inline with the back chevron, as iOS's
     *  pushed page under a NavigationStack (Tips From, iOS 071e901). */
    backBar: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(
        onDismissRequest = onClose,
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        val slide = rememberKaPostsCoverSlideState(onClose)
        val dockPad = kaPostsDockCopyInsetAboveNavBar()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(slide.modifier)
                    .background(colors.background)
                    .windowInsetsPadding(KaPostsOverlayInsets)
                    // [title] names the screen to TalkBack; iOS's bar shows the balance in its place.
                    .semantics { paneTitle = title },
            ) {
                CompositionLocalProvider(LocalKaPostsBack provides slide.back) {
                    if (doneBar) {
                        SheetNavBar(
                            title = title,
                            trailing = { SheetBarButton(stringResource(R.string.done), onClick = slide.back) },
                        )
                    } else if (backBar) {
                        SheetNavBar(
                            title = title,
                            leading = { SheetBarBackButton(onClick = slide.back) },
                        )
                    } else {
                        KaPostsOverlayStatusBar()
                    }
                    // Clear of the dock copy below.
                    Box(modifier = Modifier.weight(1f).padding(bottom = dockPad)) { content() }
                }
            }
            // The dock stays in reach over this screen, still while it slides (iOS KaPostsDockCopy),
            // with the fade under it coming and going with the screen.
            KaPostsDockCopy(fadeAlpha = slide.inPlace)
        }
    }
}

// MARK: - Toast overlays

private const val KAPOSTS_VISIBLE_UNDO_TOASTS = 4

@Composable
fun KaPostsToastOverlay(
    undoToasts: List<KaPostsViewModel.UndoToast>,
    actionToast: KaPostsViewModel.ActionToast?,
    onUndo: (KaPostsViewModel.UndoToast) -> Unit,
    onViewTx: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One per held action, each with its own Undo (iOS ee5ec98, at most four shown).
        undoToasts.takeLast(KAPOSTS_VISIBLE_UNDO_TOASTS).forEach { toast ->
            key(toast.key) {
                // "Posting in 4s" ticking down, then an orange underlined Undo (iOS toastOverlay).
                var remainingMs by remember(toast.deadlineMs) { mutableLongStateOf(toast.deadlineMs - System.currentTimeMillis()) }
                LaunchedEffect(toast.deadlineMs) {
                    while (remainingMs > 0) {
                        delay(250)
                        remainingMs = toast.deadlineMs - System.currentTimeMillis()
                    }
                }
                val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        // Inside the screen's edges (iOS b78fdb0).
                        .padding(horizontal = 16.dp)
                        // iOS's toastCapsule: .regularMaterial, a white hairline at 18%, and a shadow
                        // (black at 15%, radius 10, 4 down). A rounded card rather than a capsule
                        // since b78fdb0, so a two-line toast's ends aren't cut off.
                        .iosShadow(20.dp, Color.Black.copy(alpha = 0.15f), 10.dp, 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.l53_undo_in_seconds, toast.label, seconds.toInt()),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.l53_undo),
                        color = Color(0xFFFF9500),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.clickable { onUndo(toast) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = actionToast != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            actionToast?.let { toast ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        // Inside the screen's edges (iOS b78fdb0).
                        .padding(horizontal = 16.dp)
                        // iOS's toastCapsule: .regularMaterial, a white hairline at 18%, and a shadow
                        // (black at 15%, radius 10, 4 down). A rounded card rather than a capsule
                        // since b78fdb0, so a two-line toast's ends aren't cut off.
                        .iosShadow(20.dp, Color.Black.copy(alpha = 0.15f), 10.dp, 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (toast.isFailure) Icons.Default.Error else Icons.Default.CheckCircle, null,
                        tint = if (toast.isFailure) LocalAppColors.current.danger else LocalAppColors.current.success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Localized where it is made; a long message wraps inside the screen instead
                    // of running off it (iOS b78fdb0).
                    Text(
                        toast.message,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // No transaction yet (a scheduled post), no View - as iOS's toast.
                    if (toast.txId.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.pr_card_view),
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable { onViewTx(toast.txId) },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Helpers

/**
 * Relative timestamp in the abbreviated form iOS's RelativeDateTimeFormatter produces: "5 sec.
 * ago", "3 min. ago", "2 hr. ago", "3 days ago", "1 wk. ago", "2 mo. ago", "1 yr. ago". In the
 * app's language, from the same CLDR data iOS's formatter reads (ICU's short style).
 */
fun relativePostTime(timestampMs: Long): String {
    val deltaSec = ((System.currentTimeMillis() - timestampMs) / 1000).coerceAtLeast(0)
    val (amount, unit) = when {
        deltaSec < 60 -> deltaSec to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
        deltaSec < 3600 -> deltaSec / 60 to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        deltaSec < 86_400 -> deltaSec / 3600 to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        deltaSec < 7 * 86_400 -> deltaSec / 86_400 to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        deltaSec < 30 * 86_400 -> deltaSec / (7 * 86_400) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        deltaSec < 365 * 86_400 -> deltaSec / (30 * 86_400) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        else -> deltaSec / (365 * 86_400) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    }
    val formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(com.kachat.app.util.AppText.locale),
        null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
    )
    return formatter.format(amount.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit)
}


/**
 * The floating "Show N new posts" pill, X-style.
 *
 * Floating rather than a row in the list: a row only exists where it was inserted, so a reader who
 * has scrolled past it never learns there is anything new. This stays put at the top of the feed.
 * Matches iOS.
 */
@Composable
private fun NewPostsPill(count: Int, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = count > 0,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier.fillMaxWidth().zIndex(1f)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(KaspaTeal)
                    .clickable { onClick() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count == 1) stringResource(R.string.kpui_show_1_new_post) else stringResource(R.string.kpui_show_n_new_posts, count),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}


/**
 * SF `square.and.pencil` - iOS's Drafts icon and its new-post button. Material has no outlined
 * square-with-pencil (EditNote is lines, Edit a bare pencil), so this draws one that reads the
 * same: a rounded square left open at its top-right corner, and an outlined pencil leaving it
 * there. Stroked in black on a 24 x 24 grid so an Icon tints it like any other icon; its strokes
 * match the outlined Material icons beside it in the row.
 */
private val SquareAndPencil: ImageVector by lazy { squareAndPencil(viewport = 24f) }

/**
 * The same glyph drawn larger in the same 24dp box: iOS's new-post button sets it at 22pt
 * semibold, a size up from the icon row's 20pt, and IosGlassFab draws every icon at 24dp.
 */
private val SquareAndPencilLarge: ImageVector by lazy { squareAndPencil(viewport = 21f) }

/** [viewport] below 24 zooms the glyph (and its strokes) in, keeping it centred. */
private fun squareAndPencil(viewport: Float): ImageVector =
    ImageVector.Builder("SquareAndPencil", 24.dp, 24.dp, viewport, viewport).apply {
        val shift = (viewport - 24f) / 2f
        addGroup(translationX = shift, translationY = shift)
        // The square: from the top edge's open end round to the right edge's, counter-clockwise.
        addPath(
            androidx.compose.ui.graphics.vector.PathBuilder().apply {
                moveTo(12f, 5f)
                lineTo(6f, 5f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 3f, y1 = 8f)
                lineTo(3f, 18f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6f, y1 = 21f)
                lineTo(16f, 21f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19f, y1 = 18f)
                lineTo(19f, 12f)
            }.nodes,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        // The pencil: point inside the square, body out past its open corner, a band near the end.
        addPath(
            androidx.compose.ui.graphics.vector.PathBuilder().apply {
                moveTo(9.5f, 14.5f)
                lineTo(10.28f, 11.6f)
                lineTo(18.34f, 3.54f)
                lineTo(20.46f, 5.66f)
                lineTo(12.4f, 13.72f)
                close()
                moveTo(16.64f, 5.24f)
                lineTo(18.76f, 7.36f)
            }.nodes,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        clearGroup()
    }.build()

/** One KaPosts destination in the header's icon row - see the row's own comment. */

/** What the feed's hamburger lists. Profile and Settings are dock tabs in KaPosts, so they are
 *  not repeated here (iOS KaPosts `SideMenuItem.feedMenu`). */
private enum class KaPostsFeedMenuItem(val title: Int, val icon: ImageVector) {
    SEARCH(R.string.kpui_search, Icons.Outlined.Search),
    DRAFTS(R.string.kpui_drafts, SquareAndPencil),
    BOOKMARKS(R.string.kpui_bookmarks, Icons.Outlined.BookmarkBorder),
    MUTED(R.string.kpui_muted, Icons.Outlined.VolumeOff),
    BLOCKED(R.string.blocked, Icons.Outlined.PanTool),
    SCHEDULED(R.string.kpui_scheduled, Icons.Outlined.Schedule),
}

/**
 * The hamburger beside the KaPosts title. It slides out a card over nearly the whole screen with
 * Search, Drafts, Bookmarks, Muted, Blocked and Scheduled ([KaPostsFeedDrawer]). Unseen
 * notifications are the Notifications tab's badge in the dock (iOS KaPosts 865c9ab).
 */
@Composable
private fun KaPostsFeedMenuButton(onSelect: (KaPostsFeedMenuItem) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    var open by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.kpui_menu)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                open = true
            }
            .semantics { contentDescription = menuLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Menu, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(26.dp))
    }
    if (open) {
        KaPostsFeedDrawer(
            onSelect = onSelect,
            onClose = { open = false },
        )
    }
}

/**
 * The feed's hamburger menu: a glass card that slides out from the left at the top of the
 * screen, only as tall as its rows (iOS b49a021d, owner 2026-11-27), with the feed dimmed around
 * it. Tap outside it, tap the close button or drag the card back to the left to close it (iOS
 * KaPosts `KaPostsFeedDrawer`).
 *
 * A Dialog window, so it sits above the dock. Picking a row closes the card and then opens the
 * item's screen, once the card has slid away.
 */
@Composable
private fun KaPostsFeedDrawer(
    onSelect: (KaPostsFeedMenuItem) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 0 = hidden off the left edge, 1 = fully out.
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    var dragPx by remember { mutableStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<KaPostsFeedMenuItem?>(null) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            shown.animateTo(0f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            onClose()
            picked?.let(onSelect)
        }
    }
    LaunchedEffect(Unit) {
        shown.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f))
    }

    Dialog(
        onDismissRequest = { close() },
        properties = KaPostsFullScreenDialogProperties,
    ) {
        ForceFullScreenDialogWindow()
        // The card draws its own dim over the feed: no window dim behind it.
        val view = LocalView.current
        LaunchedEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val closeMenuLabel = stringResource(R.string.kpui_close_menu)
            // A glass card from the top, only as tall as its rows (owner, 2026-11-27: "top to
            // halfway down the screen enough to fit everything"), with the feed showing around it.
            val panelWidth = minOf(maxWidth * 0.74f, 340.dp)
            val panelPx = with(density) { panelWidth.toPx() }
            val offsetPx = -(panelPx + with(density) { 32.dp.toPx() }) * (1f - shown.value) + dragPx.coerceAtMost(0f)
            val progress = (shown.value * (1f + dragPx.coerceAtMost(0f) / panelPx)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f * progress))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { close() }
                    .semantics { contentDescription = closeMenuLabel },
            )
            val cardShape = RoundedCornerShape(26.dp)
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 12.dp, top = 6.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(offsetPx.roundToInt(), 0) }
                    .width(panelWidth)
                    .shadow(18.dp, cardShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(cardShape)
                    .background(com.kachat.app.ui.screens.toolbarGlassFill().copy(alpha = 0.94f).compositeOver(colors.background))
                    .border(0.8.dp, com.kachat.app.ui.screens.toolbarGlassEdge(), cardShape)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (-dragPx > panelPx / 3) {
                                    close()
                                } else {
                                    scope.launch {
                                        androidx.compose.animation.core.animate(dragPx, 0f) { value, _ -> dragPx = value }
                                    }
                                }
                            },
                            onDragCancel = { dragPx = 0f },
                        ) { change, amount ->
                            if (closing) return@detectHorizontalDragGestures
                            change.consume()
                            dragPx = (dragPx + amount).coerceAtMost(0f)
                        }
                    }
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tab_posts),
                        color = colors.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(colors.textPrimary.copy(alpha = 0.1f))
                            .clickable {
                                haptic(com.kachat.app.util.IosHaptic.SELECTION)
                                close()
                            }
                            .semantics { contentDescription = closeMenuLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                KaPostsFeedMenuItem.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .clickable {
                                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                                picked = item
                                close()
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // As the Kaspa Hub menu draws its rows: the icon in a tinted circle, then the name.
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Text(stringResource(item.title), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * A profile bio that always shows up to three lines, with a More button when there is more.
 *
 * Truncation is MEASURED rather than guessed from length: the same string wraps to a different
 * number of lines depending on width and text size, so a character threshold would both hide More
 * on a long-but-narrow bio and show it on a short one that already fits. Compose reports this
 * directly through `hasVisualOverflow`. Mirrors iOS's `ExpandableBioText`, which has to measure
 * two hidden copies to learn the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandableBioText(bio: String) {
    val colors = LocalAppColors.current
    var isTruncated by remember(bio) { mutableStateOf(false) }
    var showFullBio by remember { mutableStateOf(false) }

    Column {
        Text(
            bio,
            color = colors.textSecondary,
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { isTruncated = it.hasVisualOverflow },
        )
        if (isTruncated) {
            Text(
                stringResource(R.string.kpui_more),
                color = KaspaTeal,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { showFullBio = true },
            )
        }
    }

    if (showFullBio) {
        com.kachat.app.ui.theme.IosSheetColors {
            val colors = LocalAppColors.current
            ModalBottomSheet(
                scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
                shape = com.kachat.app.ui.theme.IosSheetShape,
                tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
                onDismissRequest = { showFullBio = false },
                containerColor = colors.background,
            ) {
                // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
                // its content stays above it.
                Column(Modifier.navigationBarsPadding()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.bio), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            bio,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** A thread connector: a 2dp rounded rule at [startX], the full height less [insetY] at each end. */
/**
 * X's thread line down the avatar column (iOS 961fc05a): from under a cell's 40dp avatar (centre
 * 36dp in, its bottom 52dp down) to 12dp past the cell's bottom, the next cell's top padding, so it
 * meets the next avatar.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAvatarRail(color: androidx.compose.ui.graphics.Color) {
    val top = 52.dp.toPx()
    val height = size.height + 12.dp.toPx() - top
    if (height <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(35.dp.toPx(), top),
        size = Size(2.dp.toPx(), height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThreadConnector(
    color: androidx.compose.ui.graphics.Color,
    startX: Float,
    insetY: Float,
) {
    val height = size.height - 2 * insetY
    if (height <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(startX, insetY),
        size = Size(2.dp.toPx(), height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
    )
}

