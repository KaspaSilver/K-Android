package com.kachat.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The arena's once-a-second clock, for the views that draw time: clock chips, countdowns, LIVE
 * pills. Kept apart from `ChessTournamentService` so a tick recomposes those views alone, not
 * every chess screen (and the 64-square board) with them. [now] is the same value as
 * `ChessTournamentService.now`, set by the service's tick (iOS 32e6392 `ChessClock.shared`).
 */
object ChessClock {
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** The service's tick, and only that (iOS `fileprivate(set)`). */
    internal fun set(ms: Long) {
        if (_now.value != ms) _now.value = ms
    }
}
