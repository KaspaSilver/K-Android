package com.kachat.app.services.publicchats

import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.BroadcastReadStateStore
import com.kachat.app.services.IndexerTrackedRooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A room the indexer stops listing as a default (pure, for tests):
 *  - switched off: forgotten (and left) only after it has been missing for [GRACE_MS], so one
 *    short `/broadcast-channels` answer doesn't forget it was switched off and bring it back on
 *    (iOS 961fc05a `droppedRoomGrace`, audit 2026-11-25 C10);
 *  - joined by K for the person (auto-joined): left, pin too, once missing [AUTO_GRACE_MS], two of
 *    the hourly reads (iOS 65f8a765 `droppedAutoRoomGrace`): nobody indexes it any more, so its
 *    history and pushes would stop with no explanation. Rooms the person joined by name or posted
 *    in aren't auto-joined, and stay.
 */
object DroppedDefaultRooms {
    const val GRACE_MS = 7L * 86_400_000
    const val AUTO_GRACE_MS = 2L * 3_600_000

    /** What one pass leaves: since when each room has been missing, and the rooms missing long
     *  enough to be forgotten (and left). */
    data class Step(val missingSince: Map<String, Long>, val dropped: Set<String>)

    /**
     * [saved]: since when each room was missing, as saved. [hidden]: the switched-off rooms.
     * [autoJoined]: the auto-joined rooms still joined. [curated]: today's default rooms. A room
     * back on the list, or switched on again, stops its clock; one newly missing starts it at
     * [now]. A switched-off room keeps the long grace even when it was auto-joined.
     */
    fun step(saved: Map<String, Long>, hidden: Set<String>, curated: Set<String>, now: Long, autoJoined: Set<String> = emptySet()): Step {
        val absentHidden = hidden - curated
        val absent = absentHidden + (autoJoined - curated)
        val missing = saved.filterKeys { it in absent }.toMutableMap()
        for (name in absent) if (name !in missing) missing[name] = now
        val dropped = missing.filter { (name, since) -> now - since >= if (name in absentHidden) GRACE_MS else AUTO_GRACE_MS }.keys.toSet()
        for (name in dropped) missing.remove(name)
        return Step(missing, dropped)
    }

    /**
     * The rooms counted as auto-joined on the first run of this build (iOS 65f8a765
     * `recordAutoJoined`): the indexer-listed rooms already joined - K joined those itself, and
     * nobody had a way to tell them apart - except ones the person has posted in.
     */
    fun firstRunAutoJoined(joinedBefore: Set<String>, indexerRooms: Set<String>, postedIn: Set<String>): Set<String> =
        (joinedBefore intersect indexerRooms) - postedIn

    /** The room pins after a retired room's move to [new] (iOS `moveRetiredChannels`): the old
     *  pin takes the new name in its place, or goes when the new room is pinned already. Null
     *  when [old] isn't pinned. */
    fun movedPins(pins: List<String>, old: String, new: String): List<String>? {
        val index = pins.indexOf(old)
        if (index < 0) return null
        return if (new in pins) pins.filterIndexed { i, _ -> i != index }
        else pins.toMutableList().also { it[index] = new }
    }
}

/**
 * Every default room in this account's list (iOS 961fc05a `PublicChatService
 * .ensureDefaultChannelsJoined`), run as the account's rooms load and whenever the app-wide
 * indexer's list of rooms changes (iOS `IndexerTrackedRooms.didChange`: a room just added on the
 * indexer joins at once, bell off):
 *  - an account that had a retired default room (#kachat-bugs) is moved to its replacement
 *    (#report-bugs): the bell, the pin in the Chats circles and the switched-off state carry
 *    over, and the old room is left;
 *  - a switched-off room the indexer no longer lists is left after [DroppedDefaultRooms.GRACE_MS];
 *  - a room K joined for the person (remembered per account, iOS 65f8a765) that the indexer no
 *    longer lists is left, pin too, after [DroppedDefaultRooms.AUTO_GRACE_MS]; one the person
 *    joined by name or posted in is theirs and stays (`BroadcastReadStateStore.keepRoomAsOwn`);
 *  - every default room not switched off is joined (silenced, as every room starts).
 */
@Singleton
class PublicChatDefaultRooms @Inject constructor(
    private val repository: BroadcastRepository,
    private val readState: BroadcastReadStateStore,
    private val appSettings: AppSettingsRepository,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + com.kachat.app.util.StartupSafety.logOnlyHandler("PublicChatDefaultRooms")
    )
    private val mutex = Mutex()

    init {
        // A room the operator just added (or dropped) on the indexer joins (or leaves).
        scope.launch { IndexerTrackedRooms.changes.drop(1).collect { ensureJoined() } }
    }

    /** See the class comment. Does nothing until the read state is this account's (it holds the
     *  switched-off rooms). */
    suspend fun ensureJoined() = mutex.withLock {
        val address = repository.myAddress() ?: return@withLock
        if (readState.currentWallet != address.lowercase()) return@withLock
        moveRetiredRooms(address)
        val curated = FeaturedBroadcastChannels.CURATED_NAMES
        val saved = readState.hiddenMissingSince()
        val joined = repository.joinedChannelsNow().map { it.channelName }.toSet()
        val autoJoined = (readState.autoJoinedRooms() ?: emptySet()) intersect joined
        val step = DroppedDefaultRooms.step(saved, readState.state.value.hiddenCurated, curated.toSet(), System.currentTimeMillis(), autoJoined)
        if (step.missingSince != saved) readState.setHiddenMissingSince(step.missingSince)
        if (step.dropped.isNotEmpty()) {
            readState.updateHiddenCurated { it - step.dropped }
            readState.updateAutoJoined { it - step.dropped }
            for (name in step.dropped) {
                if (name !in joined) continue
                android.util.Log.i("PublicChatDefaultRooms", "#$name left: the indexer no longer tracks it")
                leave(name)
                removeRoomPin(address, name)
            }
        }
        val joinedBefore = repository.joinedChannelsNow().map { it.channelName }.toSet()
        val hidden = readState.state.value.hiddenCurated
        repository.ensureDefaultChannelsJoined(hidden, curated)
        val joinedNow = curated.filter { it !in hidden && it !in joinedBefore }.toSet()
        recordAutoJoined(address, joinedNow, joinedBefore)
        silenceRoomsOnceIfNeeded()
    }

    /** Notes the default rooms just joined for the person; on the first run, the indexer rooms
     *  already joined too ([DroppedDefaultRooms.firstRunAutoJoined]). */
    private suspend fun recordAutoJoined(address: String, joinedNow: Set<String>, joinedBefore: Set<String>) {
        val firstRun = readState.autoJoinedRooms() == null
        val earlier = if (!firstRun) emptySet() else {
            val indexerRooms = FeaturedBroadcastChannels.indexerDefaultNames().toSet()
            val candidates = joinedBefore intersect indexerRooms
            val postedIn = if (candidates.isEmpty()) emptySet() else repository.channelsWithMessagesFrom(candidates, address)
            DroppedDefaultRooms.firstRunAutoJoined(joinedBefore, indexerRooms, postedIn)
        }
        if (!firstRun && joinedNow.isEmpty()) return
        readState.updateAutoJoined(force = firstRun) { it + joinedNow + earlier }
    }

    /** Takes a room out of the Chats list's room circles. */
    private suspend fun removeRoomPin(address: String, name: String) {
        val pins = appSettings.roomPins(address).first()
        if (name in pins) appSettings.setRoomPins(address, pins - name)
    }

    /**
     * Every room starts silenced (iOS ef760cfa `silenceRoomsOnceIfNeeded`, owner 2026-11-25): the
     * first time an account's rooms load on this build, every bell that's on goes off, including
     * the #kaspa and #kachat-bugs bells that builds before 2026-11-24 turned on by themselves. Once
     * per account; after that the bells are whatever the person chooses, kept across updates. The
     * push registration and the block scan follow the bells' flow by themselves.
     */
    private suspend fun silenceRoomsOnceIfNeeded() {
        if (readState.roomsSilencedOnce()) return
        val ringing = repository.joinedChannelsNow().filter { it.notifyEnabled }.map { it.channelName }
        for (name in ringing) repository.setNotifyEnabled(name, false)
        readState.markRoomsSilencedOnce()
        if (ringing.isNotEmpty()) android.util.Log.i("PublicChatDefaultRooms", "Silenced ${ringing.size} room(s) once for this update")
    }

    /** Moves the account off each retired default room (iOS `moveRetiredChannels`): the new room
     *  gets the old one's bell, its pin and its switched-off state, then the old room is left
     *  (its messages go from this phone; they stay on chain). Nothing to do once it's gone. */
    private suspend fun moveRetiredRooms(address: String) {
        val joined = repository.joinedChannelsNow()
        for ((old, new) in FeaturedBroadcastChannels.RETIRED) {
            val oldRoom = joined.firstOrNull { it.channelName == old } ?: continue
            if (old in readState.state.value.hiddenCurated) {
                readState.updateHiddenCurated { it - old + new }
            } else {
                val hadNew = joined.any { it.channelName == new }
                repository.joinDefaultChannel(new)
                if (oldRoom.notifyEnabled && !hadNew) repository.setNotifyEnabled(new, true)
            }
            // The Chats list's room circles. They're read as a flow, so the list follows at once.
            DroppedDefaultRooms.movedPins(appSettings.roomPins(address).first(), old, new)?.let {
                appSettings.setRoomPins(address, it)
            }
            leave(old)
            android.util.Log.i("PublicChatDefaultRooms", "Moved from #$old to #$new")
        }
    }

    private suspend fun leave(name: String) {
        repository.leaveChannel(name)
        readState.forget(name)
    }
}
