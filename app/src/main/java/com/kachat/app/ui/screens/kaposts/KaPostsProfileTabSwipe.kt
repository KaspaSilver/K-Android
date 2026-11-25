package com.kachat.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kachat.app.ui.components.CycleButtons

/**
 * How a profile's posts fade when the cycle button switches Posts and Replies (iOS 518fdc8c
 * `profileFeedTabBar`: the change happens inside `withAnimation(.easeInOut(duration: 0.25))`, so
 * the rows under the header fade, exactly as KaPost's feeds do; owner, 2026-11-24: no sideways
 * swipe). The page is one scroll with the header as its first item, so the header stays put and
 * only the rows under it fade in.
 */
@Stable
class ProfileTabFade internal constructor(shownTab: Int) {
    internal val alpha = Animatable(1f)
    internal var shown by mutableIntStateOf(shownTab)

    /** What sits under the header fades with this; the header stays put. */
    val itemModifier: Modifier
        get() = Modifier.graphicsLayer { alpha = this@ProfileTabFade.alpha.value }
}

/** The fade for a profile page: a newly picked tab's rows fade in over 0.25 s. */
@Composable
fun rememberProfileTabFade(selected: Int): ProfileTabFade {
    val fade = remember { ProfileTabFade(selected) }
    LaunchedEffect(selected) {
        if (selected != fade.shown) {
            fade.shown = selected
            fade.alpha.snapTo(0f)
            fade.alpha.animateTo(1f, tween(CycleButtons.FADE_MILLIS, easing = FastOutSlowInEasing))
        }
    }
    return fade
}
