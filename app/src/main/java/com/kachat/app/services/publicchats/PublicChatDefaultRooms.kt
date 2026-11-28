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
 *  - joined, and once on the indexer's list: left, pin too, once missing [AUTO_GRACE_MS], two of
 *    the hourly reads (iOS 65f8a765 `droppedAutoRoomGrace`): nobody indexes it any more, so its
 *    history and pushes would stop with no explanation. Since iOS 35b112ce (owner, 2026-11-26)
 *    that's any such room, even one joined by name or posted in; a room the indexer never listed
 *    (one the person made, or one with its own indexer) stays.
 */
object DroppedDefaultRooms {
    const val GRACE_MS = 7L * 86_400_000
    const val AUTO_GRACE_MS = 2L * 3_600_000

    /** What one pass leaves: since when each room has been missing, and the rooms missing long
     *  enough to be forgotten (and left). */
    data class Step(val missingSince: Map<String, Long>, val dropped: Set<String>)

    /**
     * [saved]: since when each room was missing, as saved. [hidden]: the switched-off rooms.
     * [indexerListed]: the joined rooms the indexer's list had. [curated]: today's default rooms.
     * A room back on the list, or switched on again, stops its clock; one newly missing starts it
     * at [now]. A switched-off room keeps the long grace even when the indexer listed it.
     */
    fun step(saved: Map<String, Long>, hidden: Set<String>, curated: Set<String>, now: Long, indexerListed: Set<String> = emptySet()): Step {
        val absentHidden = hidden - curated
        val absent = absentHidden + (indexerListed - curated)
        val missing = saved.filterKeys { it in absent }.toMutableMap()
        for (name in absent) if (name !in missing) missing[name] = now
        val dropped = missing.filter { (name, since) -> now - since >= if (name in absentHidden) GRACE_MS else AUTO_GRACE_MS }.keys.toSet()
        for (name in dropped) missing.remove(name)
        return Step(missing, dropped)
    }

    /**
     * The indexer-listed rooms after one pass (iOS 35b112ce `noteIndexerListedRooms`): the rooms on
     * the indexer's list now ([listed]) and the rooms the build before kept as auto-joined
     * ([legacy], read once) are added; a room off the list that isn't [joined] is forgotten.
     */
    fun notedIndexerListed(saved: Set<String>, listed: Set<String>, legacy: Set<String>, joined: Set<String>): Set<String> =
        (saved + listed + legacy).filter { it in listed || it in joined }.toSet()

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
 * Public Chats starts empty (iOS 824a30bb `PublicChatService.clearAutoAddedRoomsOnceIfNeeded`,
 * owner 2026-11-26: nothing may feel added without the person's say). Pure, for tests.
 */
object AutoAddedRooms {
    /**
     * The rooms the one-time clear leaves: every joined room older builds joined for everyone
     * ([curated]: the default and language rooms and the rooms the indexer lists now, plus the
     * [switchedOff] ones), unless the person showed they want it - a bell they turned on or a pin
     * in [pins]. A switched-off room goes even with a bell or pin. Rooms joined by name stay
     * (unless the indexer lists that name now). Machinery rooms ([service]) are never touched.
     * [joined]: each joined room's name and whether its bell is on.
     */
    fun toLeave(
        joined: List<Pair<String, Boolean>>,
        curated: Set<String>,
        switchedOff: Set<String>,
        pins: Set<String>,
        service: Set<String>,
    ): List<String> {
        val autoAdded = curated + switchedOff
        return joined.filter { (name, notify) ->
            if (name !in autoAdded || name in service) return@filter false
            val wanted = name !in switchedOff && (notify || name in pins)
            !wanted
        }.map { it.first }
    }

    /**
     * Whether a join turns the room's bell on (iOS 824a30bb `joinChannel(notify:)`): a room the
     * person adds starts with its bell ON (they chose it); a room already joined keeps the bell it
     * has, and machinery rooms (chess, the bots, the arcade) stay silent.
     */
    fun bellOnJoin(name: String, alreadyJoined: Boolean, notify: Boolean): Boolean =
        !alreadyJoined && notify && name !in FeaturedBroadcastChannels.SERVICE_CHANNELS &&
            name !in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS
}

/**
 * The account's room upkeep (iOS 961fc05a `PublicChatService.ensureDefaultChannelsJoined`), run
 * as the account's rooms load and whenever the app-wide indexer's list of rooms changes (iOS
 * `IndexerTrackedRooms.didChange`). Since iOS 824a30bb (owner, 2026-11-26) no room is joined for
 * anyone: Public Chats starts empty and fills only with rooms the person adds.
 *  - an account that had a retired default room (#kachat-bugs) is moved to its replacement
 *    (#report-bugs): the bell, the pin in the Chats circles and the switched-off state carry
 *    over, and the old room is left;
 *  - a switched-off room the indexer no longer lists is left after [DroppedDefaultRooms.GRACE_MS];
 *  - any joined room the indexer listed (remembered per account) that it no longer lists is left,
 *    pin too, after [DroppedDefaultRooms.AUTO_GRACE_MS], even one joined by name or posted in
 *    (iOS 35b112ce); a room the indexer never listed stays;
 *  - once per account, the rooms older builds joined for everyone leave ([AutoAddedRooms]),
 *    after the one-time silencing, so a bell an old build turned on by itself doesn't count.
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
        // A room the operator dropped on the indexer leaves (one just added joins nothing).
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
        noteIndexerListedRooms(joined)
        val indexerListed = readState.indexerListedRooms() intersect joined
        val step = DroppedDefaultRooms.step(saved, readState.state.value.hiddenCurated, curated.toSet(), System.currentTimeMillis(), indexerListed)
        if (step.missingSince != saved) readState.setHiddenMissingSince(step.missingSince)
        if (step.dropped.isNotEmpty()) {
            readState.updateHiddenCurated { it - step.dropped }
            readState.updateIndexerListed { it - step.dropped }
            for (name in step.dropped) {
                if (name !in joined) continue
                android.util.Log.i("PublicChatDefaultRooms", "#$name left: the indexer no longer tracks it")
                leave(name)
                removeRoomPin(address, name)
            }
        }
        // No room is joined for the person any more (iOS 824a30bb): Public Chats starts empty.
        silenceRoomsOnceIfNeeded()
        clearAutoAddedRoomsOnceIfNeeded(address)
    }

    /**
     * Once per account, on the first launch of this build (iOS 824a30bb
     * `clearAutoAddedRoomsOnceIfNeeded`, `kachat_rooms_opt_in_v1_<wallet>`): the rooms older builds
     * joined for everyone leave, unless their bell is on or they're pinned ([AutoAddedRooms.toLeave]).
     * The switched-off record goes with them: there are no switches any more.
     */
    private suspend fun clearAutoAddedRoomsOnceIfNeeded(address: String) {
        if (readState.roomsOptInDone()) return
        val pins = appSettings.roomPins(address).first().toSet()
        val joined = repository.joinedChannelsNow().map { it.channelName to it.notifyEnabled }
        val left = AutoAddedRooms.toLeave(
            joined = joined,
            curated = FeaturedBroadcastChannels.CURATED_NAMES.toSet(),
            switchedOff = readState.state.value.hiddenCurated,
            pins = pins,
            service = FeaturedBroadcastChannels.SERVICE_CHANNELS + FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS,
        )
        for (name in left) {
            leave(name)
            removeRoomPin(address, name)
        }
        readState.updateHiddenCurated { emptySet() }
        readState.markRoomsOptInDone()
        if (left.isNotEmpty()) android.util.Log.i("PublicChatDefaultRooms", "Public Chats starts empty: left ${left.size} room(s) K had added")
    }

    /** Adds the rooms on the indexer's list now; forgets ones off it that aren't joined (iOS
     *  35b112ce `noteIndexerListedRooms`, [DroppedDefaultRooms.notedIndexerListed]). */
    private fun noteIndexerListedRooms(joined: Set<String>) {
        val listed = FeaturedBroadcastChannels.indexerDefaultNames().toSet()
        val legacy = readState.takeLegacyAutoJoined()
        readState.updateIndexerListed { DroppedDefaultRooms.notedIndexerListed(it, listed, legacy, joined) }
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
