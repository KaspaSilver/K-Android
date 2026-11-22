package com.kachat.app.services.kachatnames

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * One Compose snapshot state per key over a cache that is otherwise replaced whole (the `.kachat`
 * identities, the social profiles). Held as one state, every avatar and name on screen read the
 * whole map, so each answer for ONE address redrew every row (audit H6). Read through [read], a
 * composable is subscribed to its own key only: it redraws when that key's value changes, and
 * not for anyone else's.
 *
 * [current] is the cache's plain value for a key. The owner calls [publish] after every change
 * to the cache, having made the change first.
 */
internal class PerKeyState<V : Any>(private val current: (String) -> V?) {
    private val states = ConcurrentHashMap<String, MutableState<V?>>()
    private val lock = Any()

    /**
     * [key]'s value. Read inside a composition (or any snapshot that observes reads), the reader
     * is subscribed to this key alone. Outside one nothing observes the read, so no state is made:
     * a state made in the global snapshot while a composition's snapshot is open can't be read by
     * that composition.
     */
    fun read(key: String): V? {
        val snapshot = Snapshot.current
        if (snapshot === Snapshot.global { Snapshot.current }) return current(key)
        val state = states[key] ?: synchronized(lock) {
            // made under the lock [publish] holds, so a change can't land between reading the
            // cache here and the state existing
            states.getOrPut(key) { mutableStateOf(current(key), structuralEqualityPolicy()) }
        }
        return state.value
    }

    /** After the cache changed: every key someone reads whose value differs now is updated, and
     *  only its readers redraw. */
    fun publish() {
        synchronized(lock) {
            for ((key, state) in states) {
                val now = current(key)
                if (Snapshot.withoutReadObservation { state.value } != now) state.value = now
            }
        }
    }
}
