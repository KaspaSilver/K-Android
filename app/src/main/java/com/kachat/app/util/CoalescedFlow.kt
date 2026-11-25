package com.kachat.app.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Chats list's rebuild rule (iOS `ChatListView.scheduleFilteredConversationsRefresh`, CLAUDE.md
 * "Smooth scrolling", audit 2026-11-24 #27): a sync files messages one by one, so a change waits
 * [quietMs] for the next one before the list is rebuilt - but never longer than [maxWaitMs] after
 * the last rebuild, or a long sync would hold the list (and its search) stale until it paused.
 */
object CoalescePolicy {
    const val CHAT_LIST_QUIET_MS = 180L
    const val CHAT_LIST_MAX_WAIT_MS = 1_000L

    /** Whether a change at [nowMs] rebuilds at once: the last rebuild ([lastEmitMs], null for
     *  none yet) was more than [maxWaitMs] ago. Otherwise it waits out the quiet period. */
    fun emitsNow(nowMs: Long, lastEmitMs: Long?, maxWaitMs: Long): Boolean =
        lastEmitMs == null || nowMs - lastEmitMs > maxWaitMs
}

/**
 * [CoalescePolicy] as a flow operator: each value either goes on at once or replaces the one
 * waiting out [quietMs]; the newest value always goes on in the end.
 */
fun <T> Flow<T>.coalesced(
    quietMs: Long = CoalescePolicy.CHAT_LIST_QUIET_MS,
    maxWaitMs: Long = CoalescePolicy.CHAT_LIST_MAX_WAIT_MS,
    nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
): Flow<T> = channelFlow {
    val lock = Mutex()
    var lastEmit: Long? = null
    var pending: Job? = null
    collect { value ->
        lock.withLock {
            pending?.cancel()
            pending = null
            val now = nowMs()
            if (CoalescePolicy.emitsNow(now, lastEmit, maxWaitMs)) {
                lastEmit = now
                send(value)
            } else {
                pending = launch {
                    delay(quietMs)
                    lock.withLock {
                        lastEmit = nowMs()
                        send(value)
                    }
                }
            }
        }
    }
}
