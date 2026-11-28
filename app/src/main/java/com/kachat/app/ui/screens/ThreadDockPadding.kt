package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset

/**
 * A chat thread's composer clear of the dock: [dock] of bottom padding less whatever the keyboard
 * already covers (the thread clears the keyboard itself with imePadding(), so reserving both left a
 * dead gap above a full keyboard; a floating keyboard or a hardware keyboard's small bar is
 * shorter than the dock, which stays on screen and must not cover the message box).
 *
 * The keyboard's height is read while laying out, not composing (audit 2026-11-27 Smoothness S8):
 * read in composition, each of the 15-20 frames of the keyboard's slide recomposed the thread's
 * whole nav entry and composer. A fixed modifier: nothing is swapped in or out as the keyboard
 * comes and goes (the swap that froze the public room).
 */
@Composable
fun Modifier.threadDockPadding(dock: Dp): Modifier {
    val ime = WindowInsets.ime
    return this.layout { measurable, constraints ->
        val pad = (dock.roundToPx() - ime.getBottom(this)).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(vertical = -pad))
        val height = (placeable.height + pad).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
}
