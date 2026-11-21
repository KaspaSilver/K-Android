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
 * `PublicChatService.unreadCount(forChannel:)` and `totalUnreadCount`):
 * - [chatsBadgeCount]: what the rooms add to the Chats tab's dock badge and the app icon - the
 *   rooms with their bell on (iOS `ChatService.updateAppBadge`: "chats, groups, and public rooms
 *   with their bell on").
 * - [totalUnreadCount]: every listed room's unread count.
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

    /** Each joined room's unread count, and whether its bell is on. */
    private data class RoomCount(val unread: Int, val bellOn: Boolean)

    private val roomCounts: Flow<List<RoomCount>> =
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
                        val unread = when {
                            name in read.hiddenCurated || name == viewing -> 0
                            name in read.manuallyUnread -> maxOf(summary.unreadCount, 1)
                            else -> summary.unreadCount
                        }
                        RoomCount(unread, channel.notifyEnabled)
                    }
                }) { it.toList() }
            }
        }.flowOn(Dispatchers.Default)

    /** The rooms' part of the Chats badge: unread messages in rooms whose bell is on. */
    val chatsBadgeCount: StateFlow<Int> = roomCounts
        .map { counts -> counts.filter { it.bellOn }.sumOf { it.unread } }
        .debounce(300)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Every listed room's unread messages (iOS `totalUnreadCount`). */
    val totalUnreadCount: StateFlow<Int> = roomCounts
        .map { counts -> counts.sumOf { it.unread } }
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
