package com.kachat.app.services.publicchats

import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.BroadcastReadStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The public rooms' unread counts, outside any screen (iOS 148a5331 `PublicChatUnreadBadge`,
 * `PublicChatService.unreadCount(forChannel:)`):
 * - [bellOnUnreadCount]: the rooms with their bell on - the red count on the KaChat title's
 *   Chats / Public Chats button, and what the app icon adds in; not the dock's KaChat badge, which
 *   counts chats and groups only (iOS 961fc05a badge split, owner 2026-11-25:
 *   `AppTabBadge.publicRoomUnreadCount`, `appIconCount`).
 *
 * A room counts messages from other people newer than its read marker, a long voice note once;
 * one marked unread by hand counts at least one; the room on screen and a default room switched
 * off count nothing. Service rooms (the chess arena) are never counted. Published only when the
 * number changes, after a short settle, so the dock doesn't redraw for every message.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class PublicChatUnreadBadge @Inject constructor(
    private val repository: BroadcastRepository,
    private val readState: BroadcastReadStateStore,
    private val notifier: BroadcastNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Each joined room's summary, with its unread count as the list and the badge show it, and
     *  whether its bell is on. */
    private data class RoomCount(val name: String, val summary: BroadcastRepository.RoomSummary, val bellOn: Boolean) {
        val unread: Int get() = summary.unreadCount
    }

    private val roomCounts: StateFlow<List<RoomCount>> =
        combine(repository.getJoinedChannels(), readState.state, notifier.activeChannelName) { channels, read, viewing ->
            Triple(channels, read, viewing)
        }.flatMapLatest { (channels, read, viewing) ->
            val me = repository.myAddress()
            val rooms = channels.filter { it.channelName !in FeaturedBroadcastChannels.SERVICE_CHANNELS }
            if (rooms.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(rooms.map { channel ->
                    val name = channel.channelName
                    repository.roomSummary(name, me, read.lastReadByChannel[name]).map { summary ->
                        // A default room switched off in Public Chats settings is never counted,
                        // nor the room on screen (iOS `unreadCount` while `isViewing`: the dock
                        // stays on screen inside a room); one marked unread by hand shows at
                        // least one.
                        val unread = when {
                            name in read.hiddenCurated || name == viewing -> 0
                            name in read.manuallyUnread -> maxOf(summary.unreadCount, 1)
                            else -> summary.unreadCount
                        }
                        RoomCount(name, summary.copy(unreadCount = unread), channel.notifyEnabled)
                    }
                }) { it.toList() }
            }
        }.flowOn(Dispatchers.Default)
            // One copy for the Chats list and both badges: each room's summary re-reads on every
            // write to the room table, and every collector used to run its own (audit H4).
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each joined room's newest message and unread count, for the Chats list's room rows
     *  (service rooms left out). Shared with the badges below. */
    val roomSummaries: StateFlow<Map<String, BroadcastRepository.RoomSummary>> = roomCounts
        .map { counts -> counts.associate { it.name to it.summary } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Unread messages in rooms whose bell is on (see the class comment). */
    val bellOnUnreadCount: StateFlow<Int> = roomCounts
        .map { counts -> counts.filter { it.bellOn }.sumOf { it.unread } }
        .debounce(300)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // The read markers follow the account even before a room screen or the Chats list has
        // been opened, so the badge is right from launch.
        scope.launch { repository.activeAddressFlow.collect { readState.setCurrentWallet(it) } }
    }

    /** The dock's text for a count (iOS `badgeText`). */
    fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()
}
