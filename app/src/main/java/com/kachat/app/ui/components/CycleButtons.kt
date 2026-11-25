package com.kachat.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics

/**
 * The pure parts of the cycle button (iOS `CycleCapsuleButton`, 518fdc8c), kept apart so they
 * can be unit tested.
 */
object CycleButtons {
    /** The fade every cycle step uses: iOS `withAnimation(.easeInOut(duration: 0.25))`. */
    const val FADE_MILLIS = 250

    /** Above this many stops the dots give way to a "3/14" count. */
    const val MAX_DOTS = 6

    /** The stop after [selection], round again after the last; the first when [selection] is not
     *  one of [options]; null when there are none. */
    fun <T> next(options: List<T>, selection: T): T? {
        if (options.isEmpty()) return null
        val index = options.indexOf(selection).coerceAtLeast(0)
        return options[(index + 1) % options.size]
    }

    /** Whether [count] stops are shown as dots (six or fewer) rather than "3/14". */
    fun showsDots(count: Int): Boolean = count <= MAX_DOTS

    /** "3/14": the one-based position of [selection] among [options]. */
    fun <T> positionLabel(options: List<T>, selection: T): String =
        "${options.indexOf(selection).coerceAtLeast(0) + 1}/${options.size}"
}

/**
 * iOS `CycleCapsuleButton`: one see-through capsule with an accent border that steps through a
 * few choices. Each tap plays the selection tick and fades to the next choice (and round again);
 * dots say which one it is, or "3/14" when there are more than six. KaPost's feeds, the
 * leaderboards and every in-screen page switch ([PageCycleBar]) use it in place of tabs.
 *
 * [title] is the label shown for a choice (already localized); [hint] is what a tap does, read
 * by TalkBack as the click action ("Switches to the next page").
 *
 * The content the selection drives should fade too: wrap it in [CycleFade].
 */
@Composable
fun <T> CycleCapsuleButton(
    options: List<T>,
    selection: T,
    onSelect: (T) -> Unit,
    title: @Composable (T) -> String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val label = title(selection)
    val tap: () -> Unit = {
        haptic(IosHaptic.SELECTION)
        CycleButtons.next(options, selection)?.let(onSelect)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = 0.12f), CircleShape)
            .border(1.5.dp, KaspaTeal, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = hint,
                onClick = tap,
            )
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Sync, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
        AnimatedContent(
            targetState = selection,
            transitionSpec = {
                fadeIn(tween(CycleButtons.FADE_MILLIS, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(CycleButtons.FADE_MILLIS, easing = FastOutSlowInEasing))
            },
            label = "cycleTitle",
        ) { shown ->
            KachatFitText(
                title(shown),
                color = KaspaTeal,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                minScale = 0.75f,
                textAlign = TextAlign.Start,
            )
        }
        if (CycleButtons.showsDots(options.size)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                options.forEach { option ->
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(KaspaTeal.copy(alpha = if (option == selection) 1f else 0.3f), CircleShape)
                    )
                }
            }
        } else {
            // too many for dots: where it is, as "3/14"
            Text(
                CycleButtons.positionLabel(options, selection),
                color = KaspaTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alpha(0.7f),
            )
        }
    }
}

/**
 * iOS `PageCycleBar`: the switch between pages inside a screen (an address's History / UTXOs /
 * Domains, a Cold Storage account, Stats, a profile's Posts / Replies) - the [CycleCapsuleButton],
 * leading, each tap fading to the next page. It replaced the underline tabs (owner, 2026-11-24).
 *
 * Index based, like the tab bar it replaced: [titles] are the pages' (localized) names.
 */
@Composable
fun PageCycleBar(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.cycle_hint_next_page),
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CycleCapsuleButton(
            options = titles.indices.toList(),
            selection = selectedIndex,
            onSelect = onSelect,
            title = { titles.getOrElse(it) { "" } },
            hint = hint,
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The fade a cycle step gives the content it switches (iOS: the selection changes inside
 * `withAnimation(.easeInOut(duration: 0.25))`, so the page swaps with a cross-fade, never a slide).
 */
@Composable
fun <T> CycleFade(
    target: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Crossfade(
        targetState = target,
        animationSpec = tween(CycleButtons.FADE_MILLIS, easing = FastOutSlowInEasing),
        modifier = modifier,
        label = "cycleFade",
        content = content,
    )
}
