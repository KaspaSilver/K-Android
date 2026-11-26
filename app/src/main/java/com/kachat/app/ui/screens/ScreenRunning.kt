package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the calling screen is really in front: its lifecycle is at least [atLeast] (RESUMED by
 * default: not behind another screen, the app not in the background) and its Kaspa Hub section,
 * if any, shows ([LocalHubSectionHidden]). A `delay` loop in a `LaunchedEffect` does not stop by
 * itself in the background or in a hidden Hub section (the composition stays alive); key it on
 * this instead (audit 2026-11-25 R2, R3, B8, B10).
 */
@Composable
fun rememberScreenRunning(atLeast: Lifecycle.State = Lifecycle.State.RESUMED): Boolean {
    val hidden = LocalHubSectionHidden.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(atLeast)) }
    DisposableEffect(lifecycle, atLeast) {
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(atLeast) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return active && !hidden
}

/**
 * [collectAsState] that stops collecting while the screen's lifecycle is below STARTED (the app in
 * the background), so a `WhileSubscribed` upstream (Room queries behind the chat list, the dock
 * badges, an open thread's messages) tears down after its timeout instead of re-running on every
 * background database write (audit 2026-11-25 R6). On return it collects again; the StateFlow's
 * last value shows meanwhile. Same contract as lifecycle-runtime-compose's
 * `collectAsStateWithLifecycle`, without the extra dependency.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateWhileStarted(): State<T> {
    val owner = LocalLifecycleOwner.current
    return produceState(initialValue = value, this, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { value = it }
        }
    }
}

/** [collectAsStateWhileStarted] for a plain Flow (a thread's Room query): [initial] until its
 *  first value; while stopped the last value stays, and collecting again re-runs the query once. */
@Composable
fun <T> Flow<T>.collectAsStateWhileStarted(initial: T): State<T> {
    val owner = LocalLifecycleOwner.current
    return produceState(initialValue = initial, this, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { value = it }
        }
    }
}

/** Slack for [dueOnBeat]: a beat that lands a little early still counts. Below the 30 s beat. */
internal const val BEAT_SLACK_MS = 5_000L

/**
 * Whether a loop that wakes on every [com.kachat.app.services.ForegroundBeat] but wants about
 * [everyMs] between runs should run now ([lastRunMs] its last run): a 60 s check runs on every
 * other 30 s Wi-Fi beat and on every 60 s metered one, always with the other polls.
 */
internal fun dueOnBeat(lastRunMs: Long, nowMs: Long, everyMs: Long): Boolean =
    nowMs - lastRunMs >= everyMs - BEAT_SLACK_MS
