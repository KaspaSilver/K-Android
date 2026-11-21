package com.kachat.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A profile's Posts and Replies, changed by sliding a finger left or right anywhere on the page
 * (owner's call, iOS 148a5331 `ProfileTabSwipe`). The page is one scroll (the header travels up
 * with the posts), so it isn't a pager that moves with the finger: a clearly sideways swipe
 * switches the tab at its end, and an up-and-down scroll never does. It only watches the gesture,
 * never takes it, so the list scrolls and rows take taps as before.
 */
fun Modifier.profileTabSwipe(selected: Int, tabCount: Int, onSelect: (Int) -> Unit): Modifier =
    pointerInput(selected, tabCount) {
        val minDistance = 60.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            val start = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dx = change.position.x - start.x
                dy = change.position.y - start.y
                if (!change.pressed) break
            }
            // sideways, and far enough: more than 1.5 times as wide as tall, 60 points
            if (abs(dx) > minDistance && abs(dx) > abs(dy) * 1.5f) {
                val next = if (dx < 0) selected + 1 else selected - 1
                if (next in 0 until tabCount) onSelect(next)
            }
        }
    }
