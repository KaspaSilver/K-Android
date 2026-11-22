package com.kachat.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How far a profile's posts sit sideways while a Posts/Replies swipe moves them (iOS 804cbcb7
 * `ProfileTabSwipe`'s `offset`), and which tab is on screen, so a tap on the tab bar knows which
 * side to slide the new tab in from and a swipe's own change is not slid twice.
 */
@Stable
class ProfileTabSlide internal constructor(internal val scope: CoroutineScope, shownTab: Int) {
    internal val offset = Animatable(0f)
    internal var width by mutableFloatStateOf(1f)
    internal var shown by mutableIntStateOf(shownTab)

    /** What sits under the header moves with this; the header stays put. */
    val itemModifier: Modifier
        get() = Modifier.graphicsLayer { translationX = offset.value }

    /** Puts the new tab's posts just off one side, then slides them to rest. */
    internal suspend fun slideIn(fromTrailing: Boolean) {
        offset.snapTo(if (fromTrailing) width else -width)
        offset.animateTo(0f, tween(220, easing = EaseOut))
    }
}

/** The slide for a profile page; a tab picked by a tap slides in from its side (iOS 804cbcb7). */
@Composable
fun rememberProfileTabSlide(selected: Int): ProfileTabSlide {
    val scope = rememberCoroutineScope()
    val slide = remember { ProfileTabSlide(scope, selected) }
    LaunchedEffect(selected) {
        if (selected != slide.shown) {
            val fromTrailing = selected > slide.shown
            slide.shown = selected
            slide.slideIn(fromTrailing)
        }
    }
    return slide
}

/**
 * A profile's Posts and Replies, changed by sliding a finger left or right anywhere on the page
 * (owner's call, iOS 804cbcb7 `ProfileTabSwipe`). The page is one scroll (the header travels up
 * with the posts), so the posts under the header move with the finger ([ProfileTabSlide]) instead
 * of a pager: let go far enough (or flick) and they slide off while the other tab's slide in from
 * the other side; otherwise they settle back. An up-and-down scroll never moves them sideways, and
 * once a swipe is clearly sideways the list doesn't scroll under it.
 */
fun Modifier.profileTabSwipe(
    selected: Int,
    tabCount: Int,
    slide: ProfileTabSlide,
    onSelect: (Int) -> Unit,
): Modifier = this
    .onSizeChanged { slide.width = it.width.toFloat().coerceAtLeast(1f) }
    .pointerInput(selected, tabCount) {
        val decideAt = 10.dp.toPx()
        fun neighbor(step: Int): Int? = (selected + step).takeIf { it in 0 until tabCount }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            // null until the drag picks a direction; true once it is clearly sideways
            var horizontal: Boolean? = null
            var dx = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                dx = change.position.x - start.x
                val dy = change.position.y - start.y
                if (!change.pressed) break
                if (horizontal == null && (abs(dx) > decideAt || abs(dy) > decideAt)) {
                    horizontal = abs(dx) > abs(dy) * 1.2f
                }
                if (horizontal == true) {
                    change.consume()
                    // no tab that way: the posts give a little and stop
                    val to = if (neighbor(if (dx < 0) 1 else -1) == null) dx / 4 else dx
                    slide.scope.launch { slide.offset.snapTo(to) }
                }
            }
            if (horizontal != true) return@awaitEachGesture
            val width = slide.width
            // where a flick would carry it, as iOS's predictedEndTranslation
            val flung = dx + tracker.calculateVelocity().x * 0.25f
            val step = if (dx < 0) 1 else -1
            val next = neighbor(step)
            if (next == null || (abs(dx) <= width * 0.25f && abs(flung) <= width * 0.6f)) {
                slide.scope.launch { slide.offset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 440f)) }
                return@awaitEachGesture
            }
            slide.scope.launch {
                // slide this tab off toward the finger's side, then the next one in
                slide.offset.animateTo(if (step > 0) -width else width, tween(140, easing = EaseIn))
                slide.shown = next
                onSelect(next)
                slide.slideIn(fromTrailing = step > 0)
            }
        }
    }
