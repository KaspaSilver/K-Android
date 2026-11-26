package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Tag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.BroadcastViewModel

/**
 * Public Chats in the Chats list (iOS 148a5331 `ChatListView`, rooms moved in from their own tab
 * on 2026-11-21; the list's second page since iOS 961fc05a): the room rows, their swipes, the leave
 * confirmation, the page button, and the New sheet's New Public Chat page. The Chats screen only
 * places them.
 */
object PublicChatRoutes {
    /** A room's screen ([BroadcastChannelScreen]); the navigation registers "broadcast/{channelName}". */
    const val ROOM = "broadcast/{channelName}"

    /** Room Info ([BroadcastRoomInfoScreen]); the room screen navigates to it. */
    const val ROOM_INFO = "broadcast_room_info/{channelName}"

    /** A room's Hidden Users ([BroadcastHiddenUsersScreen]); Room Info pushes it. */
    const val HIDDEN_USERS = "broadcast_hidden_users/{channelName}"

    // The name is one encoded path segment: a room name may hold "/", "#", "?" or "%" (the
    // protocol forbids only whitespace and ":"), which raw would split the route, end it early or
    // turn into a query - a crash or the wrong room. Navigation decodes the argument again.
    fun room(channelName: String): String = "broadcast/${android.net.Uri.encode(channelName)}"

    fun hiddenUsers(channelName: String): String = "broadcast_hidden_users/${android.net.Uri.encode(channelName)}"

    fun roomInfo(channelName: String): String = "broadcast_room_info/${android.net.Uri.encode(channelName)}"

    /**
     * The room a pushed route names (its already-decoded "channelName" argument), or null. Every
     * room reaching a route is one this app joined or opened by name, so the protocol's own rule
     * is the check here, as iOS opens any joined room; links and pushes passed the stricter
     * `PublicChatLink.normalizeAndValidateChannel` gate before they got here.
     */
    fun channelArgument(arguments: android.os.Bundle?): String? {
        val name = com.kachat.app.util.MessageProtocol.normalizeChannelName(arguments?.getString("channelName") ?: return null)
        return name.takeIf { com.kachat.app.util.MessageProtocol.isValidChannelName(it) }
    }
}

/**
 * The Chats list's two pages, switched by the cycle button beside the KaChat title (iOS 961fc05a
 * `ListPage`, owner 2026-11-25): your chats and groups (with the bots and Message Requests), or the
 * public rooms.
 */
internal enum class ChatsListPage { CHATS, PUBLIC_CHATS }

/** The rooms picked in Select mode on the Public Chats page, by name (iOS `selectedRoomNames`). */
internal fun selectedRoomNames(selectedEntryIds: Set<String>): List<String> =
    selectedEntryIds.mapNotNull { if (it.startsWith("r:")) it.removePrefix("r:") else null }.sorted()

/**
 * When a room last had a message: where it sits among the chats (iOS `roomActivity`). A room
 * nobody has written in yet goes to the bottom - the default rooms are all joined at once, on
 * first launch, and by their join time they sat above every chat.
 */
internal fun publicRoomActivity(summary: BroadcastRepository.RoomSummary?): Long =
    summary?.lastMessage?.blockTimestamp ?: 0L

/** The rooms the list shows, filtered by the search on the room's name (iOS `displayedRooms`). */
internal fun displayedPublicRooms(rooms: List<BroadcastChannelEntity>, searchQuery: String): List<BroadcastChannelEntity> {
    val query = searchQuery.trim()
    if (query.isEmpty()) return rooms
    return rooms.filter { it.channelName.contains(query, ignoreCase = true) }
}

/**
 * One room on the Public Chats page (iOS `roomRow`): drawn like a chat ([PublicChatRow]), swipe left
 * for Delete and Mute (no full swipe, so a long swipe can't start a delete), right for Mark as Read
 * or Mark as Unread. In Select mode a tap picks it for the bar's read, unread, pin and delete (iOS
 * 961fc05a).
 */
@Composable
internal fun PublicRoomChatsRow(
    channel: BroadcastChannelEntity,
    summary: BroadcastRepository.RoomSummary?,
    myAddress: String?,
    contactAliases: Map<String, String>,
    isSelectionMode: Boolean,
    selected: Boolean,
    openSwipeRow: Any?,
    onOpenSwipeRowChange: (Any?) -> Unit,
    entryId: String,
    broadcastViewModel: BroadcastViewModel,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    /** Pinned up into the circles row (iOS 2026-11-23 `roomPins`); only the search lists it here. */
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val name = channel.channelName
    val unread = summary?.unreadCount ?: 0
    val youLabel = stringResource(R.string.pc_you)
    val yesterday = stringResource(R.string.pc_yesterday)
    val last = summary?.lastMessage
    // Worked out once per message (iOS `PublicChatRoomSummaries.previewMemo`): by id and content.
    // An edited message previews with its newest text, as in the room (iOS 961fc05a).
    val editText = summary?.lastEditText
    val lastPreview = remember(last?.id, last?.content, editText) {
        last?.let { publicRoomPreview(if (editText != null) com.kachat.app.util.MessageEdit.apply(editText, it.content) else it.content) }
    }
    val preview = last?.let { message ->
        val sender = when {
            myAddress != null && message.senderAddress.equals(myAddress, ignoreCase = true) -> youLabel
            else -> com.kachat.app.models.addressDisplayName(message.senderAddress, contactAliases[message.senderAddress])
        }
        "$sender: $lastPreview"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(colors.background),
    ) {
        if (isSelectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = stringResource(R.string.pc_select_room),
                tint = if (selected) KaspaTeal else colors.textSecondary,
                modifier = Modifier.padding(start = 16.dp).size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            val muted = !channel.notifyEnabled
            SwipeActionsRow(
                key = entryId,
                openKey = openSwipeRow,
                onOpenKeyChange = onOpenSwipeRowChange,
                enabled = !isSelectionMode,
                leadingActions = listOf(
                    if (unread > 0) {
                        SwipeAction(Icons.Outlined.MarkEmailRead, stringResource(R.string.mark_as_read), KaspaTeal) {
                            broadcastViewModel.markRoomRead(name)
                        }
                    } else {
                        SwipeAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.mark_as_unread), KaspaTeal) {
                            broadcastViewModel.markRoomUnread(name)
                        }
                    }
                ),
                // Delete, Mute and Pin (iOS 2026-11-23); no full swipe, so a long swipe can't
                // start a delete.
                trailingActions = listOfNotNull(
                    SwipeAction(Icons.Outlined.Delete, stringResource(R.string.delete), colors.danger) { onDelete() },
                    SwipeAction(
                        icon = if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                        title = if (muted) stringResource(R.string.pc_unmute) else stringResource(R.string.pc_mute),
                        tint = colors.warning,
                    ) {
                        togglePublicRoomNotify(context, broadcastViewModel, name, on = muted)
                    },
                    onTogglePin?.let { toggle ->
                        SwipeAction(
                            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            title = stringResource(if (isPinned) R.string.chats_circle_unpin else R.string.pin),
                            tint = KaspaTeal,
                        ) { toggle() }
                    },
                ),
            ) {
                PublicChatRow(
                    channelName = name,
                    notifyOff = muted,
                    preview = preview,
                    emptyText = stringResource(R.string.pc_no_messages_yet),
                    timeText = last?.let { publicRoomTime(it.blockTimestamp, yesterday) },
                    unread = unread,
                    onClick = { if (isSelectionMode) onToggleSelection() else onOpen() },
                    onLongClick = null,
                )
            }
            // From the text's start (16 + 50 + 12) to 16 short of the edge, as the chat rows.
            HorizontalDivider(
                modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                color = colors.textTertiary.copy(alpha = 0.5f),
            )
        }
    }
}

/** Mute or Unmute a room from the Chats list, with the toast saying what it means now (iOS
 *  `toggleRoomNotify`): a row's swipe and a pinned room's half sheet. */
internal fun togglePublicRoomNotify(
    context: android.content.Context,
    broadcastViewModel: BroadcastViewModel,
    channelName: String,
    on: Boolean,
) {
    broadcastViewModel.setNotifyEnabled(channelName, on)
    val indexed = FeaturedBroadcastChannels.isIndexerTracked(channelName)
    IosToasts.show(
        context.getString(
            when {
                !on -> R.string.pc_notifications_off_toast
                indexed -> R.string.pc_notifications_on_closed_toast
                else -> R.string.pc_notifications_on_open_toast
            }
        )
    )
}

/**
 * Delete on a room (iOS `deleteRoom`): a default room is never really deleted - it is switched
 * off, like its switch in Edit Default Public Chats, which brings it back; a room you added asks
 * first ([LeavePublicRoomSheet]). Returns true when the caller should ask.
 */
internal fun deletePublicRoomNeedsConfirmation(
    context: android.content.Context,
    channelName: String,
    broadcastViewModel: BroadcastViewModel,
): Boolean {
    if (channelName in FeaturedBroadcastChannels.CURATED_NAMES) {
        broadcastViewModel.removeFromList(channelName)
        IosToasts.show(context.getString(R.string.pc_default_room_off_toast, channelName))
        return false
    }
    return true
}

/** "Delete #room?" (iOS `leaveRoomSheet`): every message cached for it on this device goes. */
@Composable
internal fun LeavePublicRoomSheet(
    channelName: String,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    ActionSheetContainer(
        title = stringResource(R.string.pc_delete_room_title, channelName),
        subtitle = stringResource(R.string.pc_delete_room_message),
        onDismiss = onKeep,
    ) {
        ActionSheetRow(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.delete),
            subtitle = stringResource(R.string.pc_delete_room_row_subtitle),
            tint = LocalAppColors.current.danger,
        ) { onDelete() }
        ActionSheetRow(
            icon = Icons.Default.Close,
            title = stringResource(R.string.pc_keep),
            subtitle = stringResource(R.string.pc_keep_subtitle),
        ) { onKeep() }
    }
}

/**
 * New Public Chat, the Public Chats page's + (iOS `createJoinRoom`; the Chats page's New sheet has
 * no room row since ef760cfa): join or create a room by name - anyone who joins the same name sees
 * and posts there. Joining opens the room ([onJoined]).
 */
@Composable
internal fun NewPublicChatPage(
    broadcastViewModel: BroadcastViewModel,
    onBack: () -> Unit,
    onJoined: (String) -> Unit,
    /** The bar's leading button: Cancel since iOS ef760cfa (only the Public Chats page's + opens
     *  it, so it closes the sheet). */
    backLabel: String = stringResource(R.string.cancel),
) {
    val colors = LocalAppColors.current
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val haptic = com.kachat.app.util.rememberHaptics()
    val isEmpty = name.trim().isEmpty()
    fun join() {
        if (isEmpty) return
        broadcastViewModel.joinFromCreateSheet(
            rawName = name,
            onError = { error = it },
            onJoined = { joined ->
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                onJoined(joined)
            },
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize()) {
        SheetNavBar(
            title = stringResource(R.string.pc_new_public_chat),
            leading = { SheetBarButton(backLabel, onClick = onBack) },
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pc_new_public_chat_explainer),
                color = colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                TextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    placeholder = { Text("channel-name", color = colors.textTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { join() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = KaspaTeal,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            error?.let {
                Text(it, color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.pc_join),
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KaspaTeal.copy(alpha = if (isEmpty) 0.4f else 1f), RoundedCornerShape(16.dp))
                    .clickable(enabled = !isEmpty) { join() }
                    .padding(vertical = 14.dp),
            )
            Spacer(Modifier.size(0.dp))
        }
    }
}

/**
 * A room handed over from outside - a tapped room notification, or a room link - opens here, in
 * the Chats list's navigation (iOS `openRoomFromHandoff`, `pendingPublicChatNavigation`): the
 * name is checked again and a room with no row is joined first.
 */
@Composable
internal fun PublicRoomHandoffEffect(
    navController: NavController,
    broadcastViewModel: BroadcastViewModel,
    /** The list switches to its Public Chats page (iOS 961fc05a: a room link or push does). */
    onHandoff: () -> Unit = {},
) {
    val pending by BroadcastDeepLink.pendingChannel.collectAsState()
    LaunchedEffect(pending) {
        val raw = pending ?: return@LaunchedEffect
        BroadcastDeepLink.consumePending()
        broadcastViewModel.openRoomFromHandoff(raw) { name ->
            onHandoff()
            navController.navigate(PublicChatRoutes.room(name)) { launchSingleTop = true }
        }
    }
}

/**
 * Chats or Public Chats, beside the KaChat title (iOS 961fc05a `pageCycleButton`): a tap fades to
 * the other page. Its red count is the public chats' unread on either page (rooms with their bell
 * on); the dock's KaChat badge counts the chats and groups (owner, 2026-11-25).
 */
@Composable
internal fun ChatsPageCycleButton(
    page: ChatsListPage,
    roomUnread: Int,
    onSelect: (ChatsListPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Private Chats" (iOS 35b112ce); the page's large title stays "Chats"
    val chatsTitle = stringResource(R.string.chats_private_chats)
    val publicTitle = stringResource(R.string.pc_broadcasts)
    androidx.compose.foundation.layout.Box(modifier) {
        com.kachat.app.ui.components.CycleCapsuleButton(
            options = ChatsListPage.entries,
            selection = page,
            onSelect = onSelect,
            title = { if (it == ChatsListPage.CHATS) chatsTitle else publicTitle },
            hint = stringResource(R.string.r55_page_cycle_hint),
        )
        if (roomUnread > 0) {
            Text(
                if (roomUnread > 99) "99+" else roomUnread.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-7).dp)
                    .background(Color.Red, RoundedCornerShape(percent = 50))
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * The "Silenced" tag beside a chat's, group's or room's name in the Chats list (iOS ef760cfa
 * `SilencedPill`, owner 2026-11-25, teal since iOS 53303688: a pill, plainer than the old bell-slash icon). Never squeezed:
 * the name truncates first (give the name `weight(1f, fill = false)`).
 */
@Composable
internal fun SilencedPill(modifier: Modifier = Modifier) {
    // Teal, the app's accent (iOS 53303688: `.accentColor`, was gray).
    Text(
        stringResource(R.string.silenced),
        color = KaspaTeal,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(KaspaTeal.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The Public Chats page with every room switched off or left (iOS 961fc05a `emptyRoomsView`). */
@Composable
internal fun PublicChatsEmptyView(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Tag,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(60.dp),
        )
        Text(
            stringResource(R.string.r55_no_public_chats),
            color = colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.r55_no_public_chats_hint),
            color = colors.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
