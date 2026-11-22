package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * KaPost's link to KaChat's dock (iOS 148a5331 `KaPostsDockRouter`, which `MainTabView` keeps in
 * step): KaPost's full-screen screens (threads, profiles, the menu's screens) are Dialog windows,
 * which Android draws over the real dock, so they draw the dock again ([KaPostsDockCopy]).
 *
 * The shell (KaChatApp, the integration's) keeps it in step:
 * - [dockContent]: the real dock, drawn again as the copy (KaChat's dock, its tabs and badges).
 * - [onScreen]: KaPost is showing - its dock slot, or its tile open in Kaspa Hub.
 * - [tabTapped]: a tab tapped on the real dock; KaPost's own tab tapped again sets [scrollFeedToTop].
 * - [select] (called by the copy): every KaPost cover closes ([closeCovers]) and the shell shows
 *   the tab ([tabRequests]).
 */
object KaPostsDock {
    /** The real dock, for the copy over KaPost's full-screen screens. Null draws no copy. */
    val dockContent = MutableStateFlow<(@Composable BoxScope.() -> Unit)?>(null)

    /** KaPost is on screen: its dock slot, or open in Kaspa Hub (iOS `kaPostsOnScreen`). Popular's
     *  background sweep pages only then. */
    val onScreen = MutableStateFlow(false)

    /** Every KaPost full-screen screen closes at once (iOS `.kaPostsCloseCovers`): a tab was
     *  tapped on the dock or on its copy, and the tab is what should show. */
    val closeCovers = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A tab tapped on the copy, by its route, for the shell to show (iOS `.kaPostsSelectDockTab`). */
    val tabRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The KaPost tab tapped again on a dock: the feed jumps to its top (iOS
     *  `.kaPostsScrollFeedToTop`). A flag, so it still lands when the feed composes later. */
    val scrollFeedToTop = MutableStateFlow(false)

    /** A tab tapped on the real dock: [kaPostsTab] when it is KaPost's (it was showing, or came
     *  to the front). */
    fun tabTapped(kaPostsTab: Boolean) {
        if (kaPostsTab) scrollFeedToTop.value = true
    }

    /** A tab tapped on the copy: the covers close and the shell shows [route]. */
    fun select(route: String) {
        closeCovers.tryEmit(Unit)
        tabRequests.tryEmit(route)
    }
}

/** The dock's height, as the shell's real one. */
private val DockHeight = 60.dp

/** The gap under the dock, above the system navigation bar, as the shell's real one. */
private val DockBottomGap = 12.dp

/** How far content on a screen with the dock copy over it keeps clear of the bottom, on top of
 *  the system bars it already keeps clear of, so its last rows and its reply box sit above the
 *  dock rather than under it. Zero while the keyboard is up: the dock steps aside for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun kaPostsDockCopyInsetAboveNavBar(): Dp =
    if (WindowInsets.isImeVisible) 0.dp else DockHeight + DockBottomGap

/**
 * The dock, drawn again on top of KaPost's full-screen screens (iOS `KaPostsDockCopy`): the
 * shell's own dock ([KaPostsDock.dockContent]), so its tabs, icons and badges are the real ones.
 * Call it in the Dialog's root Box, outside the screen's slide, so it holds still while screens
 * slide under it. Hidden while the keyboard is up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.KaPostsDockCopy() {
    if (WindowInsets.isImeVisible) return
    val content by KaPostsDock.dockContent.collectAsState()
    content?.invoke(this)
}
