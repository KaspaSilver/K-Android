package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has been read in each public room, per wallet - the Public Chats list's unread counts.
 *
 * A room's marker is the block time up to which the reader has seen it: messages from other people
 * newer than that are unread. A room seen for the first time counts from now rather than from the
 * start of its history, so joining #kaspa does not arrive with thirty days of badge. Rooms can also
 * be marked unread by hand, which shows a badge with nothing new in them. Mirrors iOS's
 * BroadcastService read state.
 */
@Singleton
class BroadcastReadStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class State(
        val lastReadByChannel: Map<String, Long> = emptyMap(),
        val manuallyUnread: Set<String> = emptySet(),
        /** Default rooms switched off in Public Chats settings: out of the list, never
         *  notifying, never counted. */
        val hiddenCurated: Set<String> = emptySet(),
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var wallet: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Points the store at [address]'s read state, loading it. Safe to call on every emission. */
    fun setCurrentWallet(address: String?) = synchronized(lock) {
        val key = address?.lowercase()?.takeIf { it.isNotEmpty() }
        if (key == wallet) return@synchronized
        wallet = key
        _state.value = if (key == null) State() else load(key)
    }

    /** Starts a marker at [nowMs] for every room that has none - "counts from now". */
    fun seedIfMissing(channels: Collection<String>, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = _state.value
        val missing = channels.filter { it !in current.lastReadByChannel }
        if (missing.isEmpty() || wallet == null) return@synchronized
        save(current.copy(lastReadByChannel = current.lastReadByChannel + missing.associateWith { nowMs }))
    }

    /** Everything in [channel] up to [upToMs] has been seen, and any hand-set unread is cleared. */
    fun markRead(channel: String, upToMs: Long) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        val marker = maxOf(upToMs, current.lastReadByChannel[channel] ?: 0L)
        save(
            current.copy(
                lastReadByChannel = current.lastReadByChannel + (channel to marker),
                manuallyUnread = current.manuallyUnread - channel,
            )
        )
    }

    /** Puts a badge back on [channel] so it is come across again. */
    fun markUnread(channel: String) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(manuallyUnread = current.manuallyUnread + channel))
    }

    /** Shows or hides one of the default rooms in Public Chats. */
    fun setCuratedShown(channel: String, shown: Boolean) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(hiddenCurated = if (shown) current.hiddenCurated - channel else current.hiddenCurated + channel))
    }

    /** The account this store is pointed at (lowercased), or null. */
    val currentWallet: String? get() = synchronized(lock) { wallet }

    /** Changes the switched-off default rooms in one step: a retired room's switched-off state
     *  moving to its replacement, or rooms the indexer dropped being forgotten (iOS 961fc05a
     *  `updateSavedHiddenCuratedChannels`). */
    fun updateHiddenCurated(change: (Set<String>) -> Set<String>) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        val next = change(current.hiddenCurated)
        if (next != current.hiddenCurated) save(current.copy(hiddenCurated = next))
    }

    /**
     * Since when each switched-off room has been missing from the default rooms (millis), for this
     * account (iOS 961fc05a `kachat_broadcast_hidden_missing_<wallet>`): a room the indexer stops
     * listing is forgotten only after a grace period ([com.kachat.app.services.publicchats.DroppedDefaultRooms]).
     */
    fun hiddenMissingSince(): Map<String, Long> = synchronized(lock) {
        val key = wallet ?: return@synchronized emptyMap()
        runCatching {
            val json = JSONObject(prefs.getString("hidden_missing_$key", null) ?: "{}")
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
    }

    fun setHiddenMissingSince(map: Map<String, Long>) = synchronized(lock) {
        val key = wallet ?: return@synchronized
        if (map.isEmpty()) {
            prefs.edit().remove("hidden_missing_$key").apply()
        } else {
            val json = JSONObject().apply { map.forEach { (name, ms) -> put(name, ms) } }
            prefs.edit().putString("hidden_missing_$key", json.toString()).apply()
        }
    }

    /** Whether this account's rooms were silenced once on this build (iOS ef760cfa
     *  `kachat_rooms_silenced_once_v1_<wallet>`). True with no account, so nothing happens. */
    fun roomsSilencedOnce(): Boolean = synchronized(lock) {
        val key = wallet ?: return@synchronized true
        prefs.getBoolean("rooms_silenced_once_v1_$key", false)
    }

    fun markRoomsSilencedOnce() = synchronized(lock) {
        val key = wallet ?: return@synchronized
        prefs.edit().putBoolean("rooms_silenced_once_v1_$key", true).apply()
    }

    /**
     * The rooms K joined on this account's behalf (default rooms, built in or from the indexer's
     * list; iOS 65f8a765 `kachat_rooms_auto_joined_v1_<wallet>`), or null before this build first
     * saved them. A room leaves it once the person joins it by name or posts in it
     * ([keepRoomAsOwn]); one the indexer drops is left
     * ([com.kachat.app.services.publicchats.PublicChatDefaultRooms]).
     */
    fun autoJoinedRooms(): Set<String>? = synchronized(lock) {
        val key = wallet ?: return@synchronized null
        prefs.getStringSet("rooms_auto_joined_v1_$key", null)?.toSet()
    }

    /** Changes the auto-joined rooms; [change] gets the saved set (empty before the first save).
     *  [force] saves even when nothing changed (the first run's record). */
    fun updateAutoJoined(force: Boolean = false, change: (Set<String>) -> Set<String>) = synchronized(lock) {
        val key = wallet ?: return@synchronized
        val stored = prefs.getStringSet("rooms_auto_joined_v1_$key", null)?.toSet()
        val next = change(stored ?: emptySet())
        if (next == stored && !force) return@synchronized
        if (stored == null && next.isEmpty() && !force) return@synchronized
        prefs.edit().putStringSet("rooms_auto_joined_v1_$key", next).apply()
    }

    /** The person made [channel] their own (joined it by name, from a link, or posted in it): it
     *  stays when the indexer drops it (iOS 65f8a765 `keepRoomAsOwn`). */
    fun keepRoomAsOwn(channel: String) {
        val name = com.kachat.app.util.MessageProtocol.normalizeChannelName(channel)
        if (autoJoinedRooms()?.contains(name) != true) return
        updateAutoJoined { it - name }
    }

    /** A room left for good takes its read state with it. */
    fun forget(channel: String) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(lastReadByChannel = current.lastReadByChannel - channel, manuallyUnread = current.manuallyUnread - channel))
    }

    private fun load(key: String): State {
        val markers = runCatching {
            val json = JSONObject(prefs.getString("last_read_$key", null) ?: "{}")
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
        val unread = prefs.getStringSet("manual_unread_$key", emptySet())?.toSet().orEmpty()
        val hidden = prefs.getStringSet("hidden_curated_$key", emptySet())?.toSet().orEmpty()
        return State(markers, unread, hidden)
    }

    private fun save(state: State) {
        val key = wallet ?: return
        _state.value = state
        val json = JSONObject().apply { state.lastReadByChannel.forEach { (channel, ms) -> put(channel, ms) } }
        prefs.edit()
            .putString("last_read_$key", json.toString())
            .putStringSet("manual_unread_$key", state.manuallyUnread)
            .putStringSet("hidden_curated_$key", state.hiddenCurated)
            .apply()
    }

    private companion object {
        const val PREFS = "kachat_broadcast_read_state"
    }
}
