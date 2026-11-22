package com.kachat.app.ui.screens

import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.iosGlass
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsOff
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.addressDisplayName
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.MessageReply
import com.kachat.app.util.TextLinkify
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.BroadcastViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Broadcast room share links (`kachat://broadcast/<channel>`,
 * `https://kachat.app/broadcast/<channel>`) land here — from an inbound system intent
 * (MainActivity) or from a tap on an in-app invite card ([openKaChatLink]). MainShell watches
 * [pendingChannel] and navigates; the room screen itself consumes [consumeJoinRequest] so a room
 * the user isn't in yet is created/joined before it opens, landing in their own channel list.
 */
object BroadcastDeepLink {
    private val _pendingChannel = MutableStateFlow<String?>(null)
    val pendingChannel: StateFlow<String?> = _pendingChannel.asStateFlow()

    /** Channels a link asked for that aren't curated — joined on open so they stick around. */
    private val joinRequests = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Gates an untrusted channel name through [com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel] (which is
     * [MessageProtocol.normalizeChannelName] + [MessageProtocol.isValidChannelName] plus the
     * route/rendering safety rules). Returns false — and does nothing at all — for a malformed or
     * hostile name, rather than joining it.
     */
    fun request(rawName: String): Boolean {
        val name = com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel(rawName) ?: return false
        // Curated rooms are permanent fixtures that always exist, so they just open.
        if (name !in FeaturedBroadcastChannels.CURATED_NAMES) joinRequests.add(name)
        _pendingChannel.value = name
        return true
    }

    fun consumePending() {
        _pendingChannel.value = null
    }

    fun consumeJoinRequest(channelName: String): Boolean = joinRequests.remove(channelName)
}
/**
 * The customize sheet, behind the button at the top left of the Public Chats tab: every default
 * room with a switch. On means the room is in the list (switching it on joins it); off takes it
 * out and silences it. The Languages switch turns every language room off at once; while it is
 * on, each language has its own switch. Mirrors iOS KaPosts' PublicChatsSettingsView (d37f64d).
 */
@Composable
fun PublicChatsSettingsSheet(
    onDismiss: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
) {
    val hidden by broadcastViewModel.hiddenCuratedRooms.collectAsState()
    val joinedChannels by broadcastViewModel.joinedChannels.collectAsState()
    val joinedNames = remember(joinedChannels) { joinedChannels.map { it.channelName }.toSet() }
    // iOS presents it as a full-height sheet holding a Form (PublicChatsSettingsView): an inline
    // bar with the title and Done on glass, each section's rooms on an inset-grouped card with
    // inset separators, the headers in the iOS 26 Form style and the footer under its card.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        IosSheetPage(grouped = true) {
            val colors = LocalAppColors.current
            SheetNavBar(
                title = stringResource(R.string.pc_broadcasts),
                trailing = { SheetBarButton(stringResource(R.string.done), onClick = onDismiss) },
            )

            @Composable
            fun roomSwitch(name: String, showDivider: Boolean) {
                val language = com.kachat.app.models.FeaturedBroadcastChannels.languageDisplayName(name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("#$name", color = colors.textPrimary, fontSize = 17.sp)
                        if (language != null) Text(language, color = colors.textSecondary, fontSize = 12.sp)
                    }
                    com.kachat.app.ui.theme.IosSwitch(
                        // On only when the room really is in the list: joined and not switched
                        // off. Every default room is joined, so in practice "not switched off".
                        checked = name !in hidden && name in joinedNames,
                        onCheckedChange = { shown -> broadcastViewModel.setCuratedRoomShown(name, shown) }
                    )
                }
                if (showDivider) SettingsDivider()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column {
                    SettingsSection(title = stringResource(R.string.l53_feed_tab_popular)) {
                        val names = com.kachat.app.models.FeaturedBroadcastChannels.NAMES
                        names.forEachIndexed { i, name -> roomSwitch(name, showDivider = i < names.size - 1) }
                    }
                    Text(
                        stringResource(R.string.kpui_room_switched_off_footer),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                Column {
                    val names = com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES
                    // On while at least one language room is.
                    val anyLanguageOn = names.any { it !in hidden && it in joinedNames }
                    SettingsSection(title = null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.kpui_languages), color = colors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            com.kachat.app.ui.theme.IosSwitch(
                                checked = anyLanguageOn,
                                onCheckedChange = { on -> broadcastViewModel.setAllLanguageRoomsShown(on) },
                            )
                        }
                        if (anyLanguageOn) {
                            SettingsDivider()
                            names.forEachIndexed { i, name -> roomSwitch(name, showDivider = i < names.size - 1) }
                        }
                    }
                    Text(
                        stringResource(R.string.kpui_languages_footer),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                Spacer(Modifier.navigationBarsPadding().height(24.dp))
            }
        }
    }
}

/**
 * One room, laid out like a chat in the Chats list: a # avatar, the room name, the newest message
 * with who sent it and when, a bell-off mark, and the unread badge. Mirrors iOS's PublicChatRow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PublicChatRow(
    channelName: String,
    notifyOff: Boolean,
    preview: String?,
    emptyText: String,
    timeText: String?,
    unread: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    /** Non-null in Select mode: whether this room is picked. */
    selected: Boolean? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // iOS: the row's 4 inside a List row's own insets, 88pt from one room to the next.
            .padding(horizontal = 16.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) KaspaTeal else LocalAppColors.current.textSecondary,
                modifier = Modifier.padding(end = 12.dp).size(24.dp)
            )
        }
        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 24.sp, fontFamily = IosRoundedFontFamily)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // bidi controls stripped (iOS audit 2026-11-21 #22 `PublicChatRoomSummary.displayName`)
                    "#${strippingRoomBidiControls(channelName)}",
                    color = colors.textPrimary,
                    // iOS .headline
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notifyOff) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.NotificationsOff, contentDescription = stringResource(R.string.kpui_notifications_off), tint = colors.textSecondary, modifier = Modifier.size(13.dp))
                }
                }
                if (timeText != null) Text(timeText, color = colors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview ?: emptyText,
                    color = colors.textSecondary,
                    // iOS .subheadline
                    fontSize = 15.sp,
                    fontStyle = if (preview == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    // iOS PublicChatRow's badge: the count in white caption2 bold on a teal
                    // capsule, 6 either side and 2 above and below, the number uncapped (only
                    // the tab badges say 99+).
                    Text(
                        unread.toString(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(KaspaTeal, RoundedCornerShape(percent = 50))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** One line of a room's newest message: replies, voice notes and links read as what they are,
 *  never as their raw JSON or a tappable link (the same rules as the chat lists). */
internal fun publicRoomPreview(raw: String): String {
    // A reply reads as its own text (iOS `MessageReplyCodec.previewText`), then the rules below.
    val content = com.kachat.app.util.MessageReply.parseOrNull(raw)?.text ?: raw
    com.kachat.app.util.InlineMediaSniff.mimeType(content)?.let { mime ->
        return when {
            mime.startsWith("audio/") -> com.kachat.app.util.tr(R.string.audio_message)
            mime.startsWith("image/") -> com.kachat.app.util.tr(R.string.photo_2)
            mime.startsWith("video/") -> com.kachat.app.util.tr(R.string.video_emoji)
            else -> com.kachat.app.util.tr(R.string.file_emoji)
        }
    }
    // link-safe, and bidi controls stripped (iOS audit 2026-11-21 #22 `PublicChatRoomSummary.lastPreview`)
    return strippingRoomBidiControls(com.kachat.app.util.NextcloudShareSniff.linkSafePreview(content))
}

/** A clock time today, "Yesterday", or the month and day, the way the chat lists read it. */
internal fun publicRoomTime(ms: Long, yesterday: String): String {
    val now = System.currentTimeMillis()
    return when {
        // The clock the message time lines use (iOS SharedFormatting.chatTime, both places).
        com.kachat.app.util.ChatTimeFormat.isSameDay(ms, now) -> com.kachat.app.util.ChatTimeFormat.formatMessageTime(ms)
        com.kachat.app.util.ChatTimeFormat.isSameDay(ms, now - 24L * 60 * 60 * 1000) -> yesterday
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BroadcastChannelScreen(
    channelName: String,
    onBack: () -> Unit,
    navController: NavController,
    /** A sender's View Profile: their KaPost profile (iOS `viewProfile`), wired by the navigation. */
    onOpenKaPostProfile: (String) -> Unit = {},
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    settingsViewModel: com.kachat.app.viewmodels.SettingsViewModel = hiltViewModel()
) {
    // A room the indexer tracks (the curated ones, or one added on the indexer): history comes
    // from the public chat indexer and retention is fixed at 30 days. Any other room reaches only
    // whoever is in it at the time, which the info button in the header says (iOS 21c480d).
    val trackedRoomsVersion by com.kachat.app.services.IndexerTrackedRooms.changes.collectAsState()
    val isIndexedRoom = remember(channelName, trackedRoomsVersion) {
        com.kachat.app.models.FeaturedBroadcastChannels.isIndexerTracked(channelName)
    }
    var showOwnRoomExplainer by remember { mutableStateOf(false) }
    /** The bell in the top-right corner: this room's notifications, on a sheet. */
    var showNotifySheet by remember { mutableStateOf(false) }
    val joinedRooms by broadcastViewModel.joinedChannels.collectAsState()
    val roomNotifyEnabled = joinedRooms.firstOrNull { it.channelName == channelName }?.notifyEnabled == true
    // The bell's sheet: what notifications do in this room, and the switch (iOS eff5c09).
    if (showNotifySheet) {
        ActionSheetContainer(
            title = "#$channelName",
            subtitle = if (roomNotifyEnabled) {
                if (isIndexedRoom) stringResource(R.string.kpui_room_notified_closed)
                else stringResource(R.string.kpui_room_notified_open)
            } else {
                stringResource(R.string.kpui_room_not_notified)
            },
            onDismiss = { showNotifySheet = false },
        ) {
            ActionSheetRow(
                icon = if (roomNotifyEnabled) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                title = if (roomNotifyEnabled) stringResource(R.string.kpui_turn_off_notifications) else stringResource(R.string.kpui_turn_on_notifications),
                subtitle = if (roomNotifyEnabled) {
                    stringResource(R.string.kpui_no_notification_room)
                } else if (isIndexedRoom) {
                    stringResource(R.string.kpui_notifies_closed)
                } else {
                    stringResource(R.string.kpui_notifies_open)
                },
            ) {
                broadcastViewModel.setNotifyEnabled(channelName, !roomNotifyEnabled)
                showNotifySheet = false
            }
        }
    }
    if (showOwnRoomExplainer) {
        ActionSheetContainer(
            title = stringResource(R.string.kpui_about_this_room),
            subtitle = null,
            onDismiss = { showOwnRoomExplainer = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.kpui_own_room_explainer_1),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 15.sp,
                )
                Text(
                    stringResource(R.string.kpui_own_room_explainer_2),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 15.sp,
                )
            }
        }
    }

    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    // Each flow built once per room: built inside composition, every recomposition (a sheet, a
    // reaction landing) would restart its query.
    val messageWindowFlow = remember(channelName) { broadcastViewModel.getMessageWindow(channelName) }
    // Null until the store's first answer: drawn as nothing, so opening a room doesn't flash
    // "No messages yet" before its messages arrive.
    val loadedWindow by messageWindowFlow
        .collectAsState(initial = null as com.kachat.app.repository.BroadcastRepository.RoomWindow?)
    val messageWindow = loadedWindow ?: com.kachat.app.repository.BroadcastRepository.RoomWindow(emptyList(), false)
    val messages = messageWindow.messages
    val loadingEarlierFlow = remember(channelName) { broadcastViewModel.isLoadingEarlier(channelName) }
    val isLoadingEarlier by loadingEarlierFlow.collectAsState(initial = false)
    // Reactions aggregated per message txId — same shape as GroupChatScreen's groupReactionsByTxId.
    val channelReactionsFlow = remember(channelName) { broadcastViewModel.getReactions(channelName) }
    val channelReactions by channelReactionsFlow.collectAsState(initial = emptyList())
    // Translate from the long-press menu (iOS 03e5128) - read once here, not per row.
    val translationStates by broadcastViewModel.translations.collectAsState()
    val translationOriginals by broadcastViewModel.showingOriginal.collectAsState()
    val translatableKeys by broadcastViewModel.translatable.collectAsState()
    LaunchedEffect(Unit) { broadcastViewModel.refreshTranslationLanguages() }
    // Reactions on a long voice note's hidden parts (sent by a client that shows parts one by one)
    // are shown on the note's bubble, keyed by its anchor (iOS 369794e `reactions(forChannel:)`).
    val anchorIdByPartId = messageWindow.anchorIdByPartId
    val reactionsByTxId = remember(channelReactions, anchorIdByPartId) {
        val byTarget = channelReactions.groupBy { it.targetTxId }
        val merged = byTarget.toMutableMap()
        for ((partId, anchorId) in anchorIdByPartId) {
            val partReactions = byTarget[partId]
            if (partReactions.isNullOrEmpty()) continue
            merged.remove(partId)
            val onAnchor = merged[anchorId].orEmpty().toMutableList()
            for (reaction in partReactions) {
                if (onAnchor.none { it.reactorAddress == reaction.reactorAddress }) onAnchor.add(reaction)
            }
            merged[anchorId] = onAnchor
        }
        merged
    }
    val quickReactionEmojis by settingsViewModel.quickReactionEmojis.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    // Zero-balance funding gate — same behavior as the 1:1/group chat threads (confirmed 0 KAS
    // only, on-entry refresh + 10s re-poll while gated); see ZeroBalanceFundingUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    val sendState by broadcastViewModel.sendBroadcastState.collectAsState()
    val voiceRecordingState by broadcastViewModel.voiceRecordingState.collectAsState()
    // The composer's text, kept as the State itself and read only in the text field (inside
    // ComposerTextScope), in lambdas that run on a tap, and through messageTextIsEmpty - so a
    // keystroke recomposes just the field, not the composer around it (iOS 0977a5b).
    val messageTextState = broadcastViewModel.messageText.collectAsState()
    val messageTextIsEmpty by remember(messageTextState) { derivedStateOf { messageTextState.value.isEmpty() } }
    // What was typed in this room last time comes back, as in 1:1 chats (iOS 360e5d2).
    DisposableEffect(channelName) {
        broadcastViewModel.openDraft(channelName)
        onDispose { broadcastViewModel.closeDraft(channelName) }
    }
    val estimatedFee by broadcastViewModel.estimatedFeeSompi.collectAsState()
    val isEstimatingFee by broadcastViewModel.isEstimatingFee.collectAsState()
    val senderProfiles by broadcastViewModel.senderProfiles.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val replyingTo by broadcastViewModel.replyingTo.collectAsState()
    // The newest edit per message (see MessageEdit), applied as each bubble is drawn - the
    // envelope itself is never a message row.
    val editsFlow = remember(channelName) { broadcastViewModel.getEdits(channelName) }
    val editsByTxId by editsFlow.collectAsState(initial = emptyMap())
    // Edits that never left the phone, waiting for Retry: their messages read as before (iOS ee5ec98).
    val failedEdits by broadcastViewModel.failedEdits.collectAsState()
    val editingMessage by broadcastViewModel.editingMessage.collectAsState()
    val kaspaExplorer by broadcastViewModel.kaspaExplorer.collectAsState()
    val networkFeeRate by broadcastViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by broadcastViewModel.feeRateOverride.collectAsState()
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    // The sender whose avatar was tapped. One sheet serves every row - the parent presents it
    // for whichever sender was tapped, rather than a menu attached to each avatar in the list.
    var senderSheetTarget by remember { mutableStateOf<RoomSenderSheetTarget?>(null) }
    // A report that couldn't go to a mail app.
    var unsentReport by remember { mutableStateOf(false) }
    val reportContext = LocalContext.current
    /** Reporting ([ContentReport], iOS 9e22075): the message's id, room, sender and text. */
    fun reportRoomMessage(messageId: String, senderAddress: String, text: String) {
        val report = ContentReport(
            buildString {
                appendLine("I want to report this public chat message.")
                appendLine()
                appendLine("Room: #$channelName")
                appendLine("Message: $messageId")
                appendLine("Sender: $senderAddress")
                appendLine("Text: ${text.take(500)}")
                appendLine()
                appendLine("What is wrong with it:")
                appendLine()
            }
        )
        unsentReport = !report.send(reportContext)
    }
    /** Send Kaspa from a member's menu: the send screen with their address filled in. */
    var sendTarget by remember { mutableStateOf<String?>(null) }
    // Same trick as 1:1 chat's fee pill — recover the mass implied by whatever's currently being
    // composed (text vs. voice) by dividing the live fee preview back out by the rate that
    // produced it, instead of duplicating estimatedFeeSompi's own calculation here.
    val effectiveRate = feeRateOverride?.toDouble() ?: networkFeeRate
    val openFeeEditor: (Long) -> Unit = { currentFeeSompi ->
        feeEditorInput = "%.8f".format(java.util.Locale.US, currentFeeSompi / 100_000_000.0)
        showFeeEditor = true
        // The shown fee may lag the text by a pause (see TypingFeeGate): price the exact text
        // now, so the rate Save derives from the fee matches what is being sent.
        broadcastViewModel.refreshFeePreview()
    }
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    // "Load earlier messages" is the list's first item while there is more: every message sits
    // one further down the list than its index in [messages].
    val listOffset = if (messageWindow.hasMore) 1 else 0
    val jumpToReply: (String) -> Unit = { rawTargetId ->
        // A reply to a hidden part of a long voice note goes to the note's one bubble (iOS 369794e).
        val targetId = messageWindow.anchorIdByPartId[rawTargetId] ?: rawTargetId
        val index = messages.indexOfFirst { it.id == targetId }
        if (index < 0) {
            // A room's toast: up for 3 s, as iOS PublicChatChannelView.showToast.
            IosToasts.show(com.kachat.app.util.tr(R.string.original_message_not_available), durationMs = IosToastDuration.LONG)
        } else {
            coroutineScope.launch {
                listState.animateScrollToItem(index + listOffset)
                highlightedMessageId = targetId
                delay(1200)
                if (highlightedMessageId == targetId) highlightedMessageId = null
            }
        }
    }

    val micContext = LocalContext.current
    // With a Nextcloud server linked, the mic asks on chain or via Nextcloud, mirroring the
    // 1:1/group "+" sheet (iOS 8b13460): via Nextcloud, the recording uploads to the server and
    // the room only carries the share link. The choice has to survive the permission prompt.
    val nextcloudAccount by broadcastViewModel.nextcloudAccount.collectAsState()
    var showVoiceRouteSheet by remember { mutableStateOf(false) }
    var voiceViaNextcloud by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) broadcastViewModel.startVoiceRecording(channelName, viaNextcloud = voiceViaNextcloud)
        // iOS PublicChatAudioRecorder's failure, shown as the room's toast.
        else IosToasts.show(com.kachat.app.util.tr(R.string.kpui_microphone_access_denied), durationMs = IosToastDuration.LONG)
    }
    val startVoiceRecordingIfPermitted = { viaNextcloud: Boolean ->
        voiceViaNextcloud = viaNextcloud
        if (broadcastViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                broadcastViewModel.startVoiceRecording(channelName, viaNextcloud = viaNextcloud)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val startVoiceMessage = {
        if (nextcloudAccount != null) showVoiceRouteSheet = true else startVoiceRecordingIfPermitted(false)
    }

    // First fill JUMPS to the newest message instantly - featured rooms hold the whole 30-day
    // window, and the old unconditional animateScrollToItem crawled the full history on every
    // open. After that, follow new arrivals only when the reader is already at the bottom, so
    // indexer backfill and live inserts never yank someone reading history (same policy as 1:1
    // chats). Keyed on the NEWEST message, not the count: each backfill page of OLDER history
    // grew the count and ran this again, so a filling room animated to the bottom once per page
    // (iOS 9c9e3e0).
    var hasPositionedAtLatest by remember(channelName) { mutableStateOf(false) }
    // Older messages land above the one the reader was at the top of: put them back on it
    // (iOS `keepInPlaceAfterOlderLoad`), rather than at the start of the new batch. Declared here:
    // the scroll to the newest below stands aside while it is set.
    var keepInPlaceId by remember(channelName) { mutableStateOf<String?>(null) }
    LaunchedEffect(channelName, messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!hasPositionedAtLatest) {
            listState.scrollToItem(messages.size - 1 + listOffset)
            hasPositionedAtLatest = true
        } else {
            // Earlier messages are loading: a new one arriving meanwhile doesn't pull the reader
            // down (iOS PublicChatChannelView's `guard keepInPlaceAfterOlderLoad == nil`).
            if (keepInPlaceId != null) return@LaunchedEffect
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Someone reading further up stays where they are; your own message still brings
            // you down to it (iOS e5a9b11).
            val mine = myAddress != null && messages.last().senderAddress == myAddress
            if (mine || (lastVisible >= messages.size - 3 + listOffset && !listState.isScrollInProgress)) {
                listState.animateScrollToItem(messages.size - 1 + listOffset)
            }
        }
    }
    // History paging in above a freshly opened room: someone at the bottom stays there instead
    // of being left in the history (iOS 33095ac). Keyed on the OLDEST message, so only a page of
    // older history runs it; the newest message showing is what "at the bottom" means.
    LaunchedEffect(channelName, messages.firstOrNull()?.id) {
        if (messages.isEmpty() || !hasPositionedAtLatest || keepInPlaceId != null) return@LaunchedEffect
        val lastVisibleKey = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key
        if (lastVisibleKey == messages.last().id && !listState.isScrollInProgress) {
            listState.scrollToItem(messages.size - 1 + listOffset)
        }
    }

    // Tapping into the composer scrolls to the newest message rather than letting the keyboard
    // rise over wherever you happened to be reading. You opened the composer to reply to the
    // room as it stands now.
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(composerFocused) {
        if (composerFocused && messages.isNotEmpty()) {
            // The keyboard is still animating up and the list has not been resized yet, so
            // scrolling immediately lands short of the bottom.
            kotlinx.coroutines.delay(120)
            listState.animateScrollToItem(messages.size - 1 + listOffset)
        }
    }

    // Put back on the anchor once the widened window's first answer is in, then cleared whether
    // or not that window added an earlier message: a stale anchor would jump the list on some
    // later change (iOS clears it on the first change after the load). Keyed on the load ending
    // too, since a window that came back the same as before is not a new value.
    LaunchedEffect(loadedWindow, isLoadingEarlier) {
        val anchor = keepInPlaceId ?: return@LaunchedEffect
        // Still the old window on screen; the view model's flag is the up-to-date one.
        if (broadcastViewModel.isLoadingEarlierNow(channelName)) return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == anchor }
        if (index > 0) listState.scrollToItem(index + listOffset)
        keepInPlaceId = null
    }

    // Only show the "jump to latest" button once the last message isn't even partially
    // visible — a real, deliberate scroll away from the bottom to read history — not just a
    // transient viewport shrink. Tolerates a 1-item gap for the same reason as 1:1 chat's
    // equivalent check (see MessageBubble's screen in Screens.kt).
    // The newest list size as state: a plain `messages` read here would stay the first (empty)
    // window forever, and the button would never show.
    val latestLastIndex by rememberUpdatedState(messages.lastIndex + listOffset)
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex < latestLastIndex - 1
        }
    }

    // Fee preview needs real UTXOs/fee-rate data, not just whatever was last fetched (which
    // could be empty/stale) — refresh once on entry, matching how 1:1 chats always have this
    // available rather than gating it behind a separate "payment mode" (broadcasts have none).
    LaunchedEffect(Unit) {
        broadcastViewModel.refreshUtxos()
    }

    // Live messages appear while this screen is open even if this channel isn't marked
    // always-listen — bounded to exactly as long as this composable is on screen. Every room
    // additionally backfills from its broadcast indexer (once + every 8s) so history sent while
    // the app was closed shows up too.
    DisposableEffect(channelName) {
        broadcastViewModel.startLiveViewing(channelName)
        broadcastViewModel.startIndexerBackfill(channelName)
        // Opening a room is reading it. Marked again on the way out, so what arrived while it
        // was open does not come back as unread on the list behind it.
        broadcastViewModel.markRoomRead(channelName)
        onDispose {
            broadcastViewModel.markRoomRead(channelName)
            broadcastViewModel.stopLiveViewing()
            broadcastViewModel.stopIndexerBackfill()
            // Leaving the room mid-recording: nothing is sent, the file goes, and the audio is
            // given back (iOS b78fdb0).
            broadcastViewModel.cancelVoiceRecordingOnLeave()
            // "Load earlier messages" starts over next time (iOS f954738): every room ever
            // opened doesn't keep its widened window all session.
            broadcastViewModel.resetRoomWindow(channelName)
        }
    }
    // Voice-note playback is owned per bubble; the room going away is what ends it.
    DisposableEffect(Unit) { onDispose { VoicePlayback.stopAllAfterChildrenDispose() } }

    // Opened from a share link for a room the user isn't in: create/join it first so it lands in
    // "Your Channels" instead of vanishing the moment they navigate away. Curated rooms never
    // register a join request (they always exist), so they just open. joinChannel is idempotent
    // (INSERT IGNORE) and re-validates the name itself, so a stale request can't do damage.
    LaunchedEffect(channelName) {
        if (BroadcastDeepLink.consumeJoinRequest(channelName)) {
            broadcastViewModel.joinChannel(channelName)
        }
    }

    LaunchedEffect(myAddress) {
        myAddress?.let { broadcastViewModel.ensureSenderProfileFetched(it) }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            // iOS 26's room header (PublicChatChannelView): no bar of its own - Back and the
            // connection dot each on a glass disc leading, the bell on glass trailing, and the
            // room's "#" circle over a "#name >" capsule in the middle, all floating over the
            // messages, which scroll on underneath and fade out under the status bar. The list
            // takes this header's height as its top content padding, not as a clip.
            val headerTapBand = remember { ChatHeaderTapBandState() }
            val headerBackground = LocalAppColors.current.background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to headerBackground.copy(alpha = 0.95f),
                            0.55f to headerBackground.copy(alpha = 0.7f),
                            1f to Color.Transparent,
                        )
                    )
                    // Icon and name open Room Info - and so does the 24dp either side of them;
                    // the space further out jumps to the very first message (iOS 1d5a555). The
                    // back button, the connection dot and the bell keep their own taps.
                    .chatHeaderTapBand(
                        headerTapBand,
                        onChip = { navController.navigate(PublicChatRoutes.roomInfo(channelName)) },
                        onBand = { coroutineScope.launch { listState.scrollToItem(0) } },
                    )
            ) {
                // The chat thread's bar (ChatNavBar): Back on the left, the room's connection dot
                // far right after the bell, like every KaPosts screen (iOS KaPosts 865c9ab).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetBarBackButton(onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    // A room you made yourself: what "public" does and does not mean here. Rooms
                    // the indexer tracks keep their history there, so they need no such warning
                    // (iOS ac34790, 21c480d). iOS 26 puts neighbouring trailing items - the
                    // bell and the connection dot - on one piece of glass.
                    ToolbarGlassGroup {
                        if (!isIndexedRoom) {
                            ToolbarIconButton(Icons.Outlined.Info, stringResource(R.string.kpui_about_this_room), onClick = { showOwnRoomExplainer = true })
                        }
                        // This room's notifications, one tap away - the same switch the Public
                        // Chats list offers in its room menu (iOS eff5c09).
                        ToolbarIconButton(
                            if (roomNotifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            if (roomNotifyEnabled) stringResource(R.string.kpui_notifications_on) else stringResource(R.string.kpui_notifications_off),
                            onClick = { showNotifySheet = true },
                        )
                        ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
                    }
                }
                // iOS PublicChatRoomTitleChip: the 1:1/group header chip measure for measure, the
                // circle carrying the room glyph (accent at 20%, "number" 18 semibold) where they
                // carry a photo. The title itself is the way in to everything about the room.
                ChatHeaderChipLayout(
                    // bidi controls stripped, as in the Chats list (iOS audit 2026-11-21 #22)
                    name = "#${strippingRoomBidiControls(channelName)}",
                    onClick = { navController.navigate(PublicChatRoutes.roomInfo(channelName)) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .chatHeaderChip(headerTapBand)
                        .statusBarsPadding(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(LocalAppColors.current.background)
                            .background(KaspaTeal.copy(alpha = 0.2f)),
                    ) {
                        Text("#", color = KaspaTeal, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        bottomBar = {
            // iOS: the compose bar floats over the list as a bottom safe-area inset - the
            // messages run on underneath it - with the zero-balance card right above it (padded
            // 16 either side, 4 below), never over the room. Only the composer under the card
            // dims and goes inert (Modifier.zeroBalanceComposerGate in ZeroBalanceFundingUi.kt).
            //
            // With the dock showing, the messages run on under it too and the composer rests just
            // above it (LocalDockInset, which already holds the system bar under the dock); with
            // the keyboard up the dock is behind it, the inset is 0 and the composer sits on the
            // keyboard as before.
            //
            // One fixed modifier chain: the bottom padding and imePadding() never come or go.
            // Swapping navigationBarsPadding() in and out ahead of imePadding() when the keyboard
            // opened froze the app (an endless consumed-insets loop - an ANR seen while typing in
            // a room). The navigation bar needs nothing of its own: without the keyboard the dock
            // inset already holds it, and with the keyboard up the IME inset covers it.
            val composerBackground = LocalAppColors.current.background
            val dockInset = LocalDockInset.current
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to composerBackground.copy(alpha = 0.85f),
                        )
                    )
                    .padding(bottom = dockInset)
                    .imePadding()
                    .padding(bottom = 2.dp)
            ) {
            if (fundingGate.active) {
                ZeroBalanceFundingCard(
                    walletAddress = fundingGate.chattingAddress,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            // iOS composeBar: VStack(spacing: 8), padded 16 by 8.
            Column(
                modifier = Modifier
                    .zeroBalanceComposerGate(fundingGate.active)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A failed send, edit or voice note says so in a toast (iOS showToast), not a
                // line above the composer.
                // Editing and replying are mutually exclusive - the banner says which one is in
                // progress (iOS editBanner / replyBanner: caption lines on secondarySystemBackground
                // in a 12-rounded box padded 12 by 8, inset a further 16 and 8 below the top).
                editingMessage?.let { editing ->
                    RoomComposerBanner(
                        icon = Icons.Default.Edit,
                        title = stringResource(R.string.editing_message),
                        // iOS editBanner: previewText of the current text.
                        preview = BroadcastViewModel.replyPreviewText(
                            editsByTxId[editing.id]?.text
                                ?: com.kachat.app.util.MessageEdit.unwrappedText(editing.content)
                        ),
                        cancelDescription = stringResource(R.string.cancel_editing),
                        onCancel = {
                            broadcastViewModel.cancelEditing()
                            broadcastViewModel.setMessageText("")
                        },
                    )
                }
                if (editingMessage == null) replyingTo?.let { reply ->
                    RoomComposerBanner(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        title = stringResource(R.string.replying_to, addressDisplayName(reply.senderAddress, contactAliases[reply.senderAddress], senderKnsNames[reply.senderAddress])),
                        // The quote the reply will carry (iOS replyBanner: MessageReplyCodec.previewText),
                        // bidi controls stripped (iOS audit 2026-11-21 #22).
                        preview = strippingRoomBidiControls(BroadcastViewModel.replyPreviewText(reply.content)),
                        cancelDescription = stringResource(R.string.cancel_reply),
                        onCancel = { broadcastViewModel.cancelReply() },
                    )
                }
                if (voiceRecordingState.status != BroadcastViewModel.VoiceRecordingStatus.IDLE) {
                    val encoding = voiceRecordingState.status == BroadcastViewModel.VoiceRecordingStatus.ENCODING
                    if (showFeeEstimate && estimatedFee != null) {
                        ComposerFeePill(
                            feeSompi = estimatedFee,
                            estimating = false,
                            onTap = { openFeeEditor(it) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // iOS 1b15521 recordingBar, laid out as KaChat's 1:1 recording bar: a ring
                    // filling toward the limit, the time against it ("Recording... 00:10 / 02:50"),
                    // the trash at the end, all on the field's glass; "Encoding…" while the note
                    // becomes its parts; the send glyph on its own 36 glass square, 12 apart.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .iosGlass(cornerRadius = 20.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (encoding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = LocalAppColors.current.textSecondary,
                                )
                                Text(
                                    text = stringResource(R.string.pc_room_voice_encoding),
                                    color = LocalAppColors.current.textPrimary,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                )
                            } else {
                                RoomRecordingRing(
                                    fraction = if (voiceRecordingState.maxMs > 0) {
                                        voiceRecordingState.elapsedMs.toFloat() / maxOf(1_000L, voiceRecordingState.maxMs)
                                    } else 0f,
                                )
                                Text(
                                    // After a call or another app took the microphone: stopped
                                    // where it got to, still there to send or throw away (iOS b78fdb0).
                                    text = "${stringResource(if (voiceRecordingState.wasInterrupted) R.string.pc_rooms54_recording_stopped else R.string.pc_room_voice_recording)} " +
                                        "${formatRecordingTime(voiceRecordingState.elapsedMs)} / ${formatRecordingTime(voiceRecordingState.maxMs)}",
                                    color = LocalAppColors.current.textPrimary,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.cancel_recording),
                                tint = LocalAppColors.current.danger,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(enabled = !encoding) { broadcastViewModel.cancelVoiceRecording() },
                            )
                        }
                        RoomComposerSquareButton(
                            contentDescription = stringResource(R.string.send),
                            enabled = !encoding,
                            onClick = { broadcastViewModel.stopAndSendVoiceRecording(channelName) },
                        ) {
                            RoomSendGlyph(enabled = !encoding)
                        }
                    }
                } else {
                    if (showFeeEstimate && !messageTextIsEmpty && (estimatedFee != null || isEstimatingFee)) {
                        // iOS feeBubble: the fee, or - while a re-price waits for its pause - the shimmering
                        // placeholder (TypingFeeGate).
                        ComposerFeePill(
                            feeSompi = estimatedFee,
                            estimating = isEstimatingFee,
                            onTap = { openFeeEditor(it) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    // iOS: the field on glassBackground(cornerRadius: 20) - a capsule at one line -
                    // and the mic or send on its own 36 x 36 glassBackground(cornerRadius: 12),
                    // 12 apart.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The one place the text itself is read while composing - see
                        // messageTextState.
                        ComposerTextScope {
                            val composerText = messageTextState.value
                            androidx.compose.foundation.text.BasicTextField(
                                value = composerText,
                                onValueChange = {
                                    // Someone's payment request pasted in opens Send Kaspa instead of
                                    // landing in the message (iOS c6851d6).
                                    if (!PaymentRequestPaste.interceptInsert(messageTextState.value, it)) {
                                        broadcastViewModel.setMessageText(it, fromTyping = true)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .chatComposerFieldModifier()
                                    .onFocusChanged { composerFocused = it.isFocused },
                                textStyle = chatComposerTextStyle(),
                                cursorBrush = SolidColor(KaspaTeal),
                                // Load-bearing, same value as the 1:1 composer. Without a cap this
                                // field grows a line per line of text; the Scaffold measures its
                                // bottom bar against the screen height, so past ~a screenful the
                                // Column stopped growing and the newest lines - the ones with the
                                // caret - overflowed past its bottom edge and under the keyboard.
                                // Capped, the field scrolls internally and the caret stays put.
                                maxLines = 4,
                                decorationBox = { inner ->
                                    RoomComposerFieldDecoration(
                                        placeholder = stringResource(R.string.kpui_message_room_placeholder, channelName),
                                        showPlaceholder = composerText.isEmpty(),
                                        innerTextField = inner,
                                    )
                                },
                            )
                        }
                        val sending = sendState.status == BroadcastViewModel.SendBroadcastStatus.SENDING
                        if (messageTextIsEmpty) {
                            // iOS: mic.fill at title3 in the accent colour.
                            RoomComposerSquareButton(
                                contentDescription = stringResource(R.string.kpui_record_voice_message),
                                onClick = { startVoiceMessage() },
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                            }
                            if (showVoiceRouteSheet) {
                                ComposerMediaRouteSheet(
                                    kind = ComposerMediaKind.VOICE,
                                    onChoose = { viaNextcloud ->
                                        showVoiceRouteSheet = false
                                        startVoiceRecordingIfPermitted(viaNextcloud)
                                    },
                                    onBack = { showVoiceRouteSheet = false },
                                    onDismiss = { showVoiceRouteSheet = false },
                                )
                            }
                        } else {
                            // iOS: arrow.up.circle.fill at title3, the accent colour - secondary
                            // while a send is in flight.
                            RoomComposerSquareButton(
                                contentDescription = stringResource(R.string.send),
                                enabled = !sending,
                                onClick = {
                                    val messageText = messageTextState.value
                                    if (!sending && messageText.isNotBlank()) {
                                        // Editing: the composer's text replaces the message being
                                        // edited - one edit broadcast, no new bubble.
                                        val editing = editingMessage
                                        if (editing != null) {
                                            broadcastViewModel.sendEdit(channelName, editing.id, messageText)
                                        } else {
                                            broadcastViewModel.sendBroadcast(channelName, messageText)
                                        }
                                        broadcastViewModel.setMessageText("")
                                    }
                                },
                            ) {
                                RoomSendGlyph(enabled = !sending)
                            }
                        }
                    }
                }
            }
            }
        }
    ) { padding ->
        // Full height, NOT padded by the header and composer: the messages scroll on underneath
        // both (iOS), and the list takes their heights as content padding instead.
        Column(modifier = Modifier.fillMaxSize()) {
        // The in-room retention banner was removed to keep the chat clean; the retention note
        // now lives next to the "Popular" header on the broadcast list (matches iOS).
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
        if (loadedWindow == null) {
            // Not read yet: nothing, rather than a flash of the empty state.
        } else if (messages.isEmpty()) {
            // iOS emptyState: the radio-waves glyph at 48 in secondary, "No messages yet" in
            // headline and the invitation in subheadline secondary, 16 apart, centred.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.Default.Sensors,
                    contentDescription = null,
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(52.dp),
                )
                Text(
                    stringResource(R.string.pc_no_messages_yet),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.kpui_be_first_to_post_in, channelName),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            sendTarget?.let { recipient ->
                val myAddress by walletViewModel.address.collectAsState()
                val myBalanceSompi by walletViewModel.balanceSompi.collectAsState()
                IosFullSheet(onDismissed = { sendTarget = null }, swipeToDismiss = false) { close ->
                    SpendingAddressSendFlow(
                        fromAddress = myAddress.orEmpty(),
                        balanceSompi = myBalanceSompi,
                        title = stringResource(R.string.chats_send_kaspa),
                        viewModel = walletViewModel,
                        onDone = close,
                        prefillAddress = recipient,
                    )
                }
            }
            if (unsentReport) ContentReportFallback(onDismiss = { unsentReport = false })
            senderSheetTarget?.let { target ->
                val address = target.address
                val sheetClipboard = LocalClipboardManager.current
                val sheetContext = LocalContext.current
                RoomSenderActionsSheet(
                    address = address,
                    displayName = addressDisplayName(address, contactAliases[address], senderKnsNames[address]),
                    isOwnMessage = target.isOwnMessage,
                    onDismiss = { senderSheetTarget = null },
                    // Their KaPost profile (iOS PublicChatChannelView `viewProfile`): the KaPosts
                    // tab, wired by the navigation.
                    onViewProfile = { onOpenKaPostProfile(address) },
                    // The send screen with their address filled in.
                    onPayInKaspa = { sendTarget = address },
                    onCopyAddress = {
                        sheetClipboard.setText(AnnotatedString(address))
                        // A room's toasts stay up 3 s (iOS PublicChatChannelView.showToast).
                        com.kachat.app.util.showAddressCopiedToast(sheetContext, address, haptic = false)
                    },
                    // Per-room since 4.0: hides this sender in THIS room only.
                    onHide = { broadcastViewModel.hideSender(address, channelName) },
                    hideSubtitle = stringResource(R.string.kpui_hide_sender_room_subtitle),
                    onReport = { reportRoomMessage(target.messageId, target.address, target.messageText) },
                    onBlock = {
                        broadcastViewModel.blockSender(address)
                        IosToasts.show(com.kachat.app.util.tr(R.string.kpui_blocked_undo_toast), durationMs = IosToastDuration.LONG)
                    },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().closesQuickReactionBar().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // iOS: the LazyVStack padded 8 top and bottom, inside the header's and the
                // compose bar's insets.
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                )
            ) {
                // At the top of the room, above the oldest message the window reaches, exactly
                // where iOS puts it (02a4f58).
                if (messageWindow.hasMore) {
                    item(key = "load_earlier") {
                        // iOS: a plain button - caption semibold in the accent colour, the full
                        // width, 8 above and below; while it loads, a small spinner and
                        // "Loading…", and a tap does nothing.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!isLoadingEarlier) {
                                        keepInPlaceId = messages.firstOrNull()?.id
                                        broadcastViewModel.loadEarlier(channelName)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isLoadingEarlier) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = LocalAppColors.current.textSecondary,
                                )
                            }
                            Text(
                                if (isLoadingEarlier) stringResource(R.string.loading_ellipsis) else stringResource(R.string.pc_load_earlier_messages),
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val showDateDivider = index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)
                    if (showDateDivider) {
                        // iOS dateDivider: caption2 semibold in secondary, padded 12 by 4 on
                        // secondarySystemBackground in a 12-rounded box, 8 above and below.
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                ChatTimeFormat.formatDateDivider(message.blockTimestamp, stringResource(R.string.chat_day_today), stringResource(R.string.chat_day_yesterday)),
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(roomSecondarySystemBackground())
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        // The divider sits in the same list row as the message under it; iOS
                        // spaces them 8 apart in its stack.
                        Spacer(Modifier.height(8.dp))
                    }
                    // An edited message reads with its newest text; the stored row is untouched.
                    val editForRow = editsByTxId[message.id]
                    @Suppress("NAME_SHADOWING")
                    val message = remember(message, editForRow) {
                        if (editForRow == null) message
                        else message.copy(content = com.kachat.app.util.MessageEdit.apply(editForRow.text, message.content))
                    }
                    val isMine = message.senderAddress == myAddress
                    // The long voice note this row stands for (it is the note's first part), or
                    // null for any other message (iOS 1b15521).
                    val voiceNote = messageWindow.voiceNotes[message.id]
                    // The status under the bubble carries the edit's send state, or a long voice
                    // note's one status for all its parts; `message` keeps the row's own for the
                    // actions (iOS ee5ec98, 1b15521 `shownStatusMessage`).
                    val failedEditText = failedEdits[channelName]?.get(message.id)
                    val shownDeliveryStatus = when {
                        voiceNote != null -> voiceNote.deliveryStatus
                        failedEditText != null -> "failed"
                        editForRow?.deliveryStatus == "unconfirmed" -> "unconfirmed"
                        else -> message.deliveryStatus
                    }
                    // Retry on a long voice note resends only its failed parts, in order (iOS
                    // 1b15521 `makeRetryAction`).
                    val retryMessage: () -> Unit = {
                        if (voiceNote != null) broadcastViewModel.retryVoiceParts(voiceNote.failedParts)
                        else if (failedEditText != null) broadcastViewModel.retryFailedEdit(channelName, message.id)
                        else broadcastViewModel.retryBroadcast(message)
                    }
                    val replyContent = remember(message.content) { MessageReply.parseOrNull(message.content) }
                    val displayContent = replyContent?.text ?: message.content
                    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
                    // Same lone-URL detection 1:1's MessageBubble uses: nothing but a link means
                    // the preview card replaces the plain-text bubble entirely; a link mixed with
                    // other text keeps the bubble and stacks the card below it (like groups).
                    // Both checked only for content short enough to lay out inline — scanning a
                    // huge wall of text for links is wasted work (see MessageBubble's same guard).
                    val isEntirelyLinkMessage = remember(displayContent, voiceContent) {
                        voiceContent == null && displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD && TextLinkify.isEntirelyLink(displayContent)
                    }
                    // Rooms follow KaPost's preview rules (owner's call, 2026-11-18; iOS b78fdb0
                    // `PublicChatMessageRow.previews`): a link previews, by itself with no tap, only
                    // when its sender has a .kachat name and it is the first link written as one, to
                    // KaChat's own site or a popular site on the allowed list with no adult words.
                    // Any other link stays plain text in the bubble, so an unknown server is never
                    // contacted. The registry's identity cache is snapshot state: a sender's name
                    // landing turns their previews on.
                    val senderHasKachatName =
                        com.kachat.app.services.kachatnames.KachatNamesRegistry.kachatName(message.senderAddress) != null
                    val previewableLink = remember(displayContent, voiceContent, senderHasKachatName) {
                        if (senderHasKachatName && voiceContent == null && displayContent.firstOrNull() != '{' &&
                            displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                            com.kachat.app.services.KaPostLinkPreviewPolicy.firstPreviewableLink(displayContent)
                        } else null
                    }
                    val loneLinkPreviewUrl = previewableLink?.takeIf { isEntirelyLinkMessage }
                    val separateLinkPreviewUrl = previewableLink?.takeIf { !isEntirelyLinkMessage }
                    // An in-app KaChat link (shared KaPosts post / broadcast room invite) takes
                    // priority over the generic link path: it previews from local data only and
                    // opens in-app. Detected separately from TextLinkify because the kachat://
                    // form isn't a web URL at all and so is never linkified.
                    val internalLinkMatch = remember(displayContent, voiceContent) {
                        if (voiceContent == null && displayContent.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                            KaChatLink.findFirst(displayContent)
                        } else null
                    }
                    // Worth considering for Translate: someone else's plain text. Voice notes, KaChat
                    // link cards and structured payloads have nothing to translate.
                    val translationKey = remember(message.id, displayContent) {
                        broadcastViewModel.translationKey(message.id, displayContent)
                    }
                    val considersTranslation = !isMine && voiceContent == null &&
                        !displayContent.trimStart().startsWith("{") && internalLinkMatch == null
                    LaunchedEffect(translationKey, considersTranslation) {
                        if (considersTranslation) broadcastViewModel.considerTranslation(translationKey, displayContent)
                    }
                    val translationState = translationStates[translationKey]
                    // The text the bubble draws: the translation while one is showing, otherwise the
                    // message. Everything that decides WHAT the message is still reads displayContent.
                    val shownText = strippingRoomBidiControls(
                        (translationState as? com.kachat.app.services.PostTranslationService.TranslationState.Translated)
                            ?.takeIf { translationKey !in translationOriginals }?.text ?: displayContent
                    )
                    val isEntirelyInternalLinkMessage =
                        // The card is the WHOLE message wherever a KaChat link appears - see
                        // 1:1's identical rule. Copy and the full-text dialog keep everything.
                        internalLinkMatch != null
                    val messageReactions = reactionsByTxId[message.id] ?: emptyList()
                    var showMenu by remember { mutableStateOf(false) }
                    // The link a long-press landed on, for its own Open / Copy sheet.
                    var linkMenuUrl by remember { mutableStateOf<String?>(null) }
                    // Who reacted to this message, when the reader asks from the long-press menu.
                    var showReactions by remember { mutableStateOf(false) }
                    val clipboardManager = LocalClipboardManager.current
                    val menuContext = LocalContext.current

                    LaunchedEffect(message.senderAddress) {
                        broadcastViewModel.ensureSenderProfileFetched(message.senderAddress)
                    }

                    // The sender's avatar. Tapping it opens the sender half sheet presented by
                    // the screen (see SenderActionsSheet above the list); the row itself no
                    // longer owns a menu.
                    val avatar: @Composable () -> Unit = {
                        ContactAvatar(
                            imageUrl = senderProfiles[message.senderAddress],
                            fallbackText = message.senderAddress.takeLast(8),
                            size = 32.dp,
                            address = message.senderAddress,
                            modifier = Modifier.clickable {
                                senderSheetTarget = RoomSenderSheetTarget(message.senderAddress, isOwnMessage = isMine, messageId = message.id, messageText = displayContent)
                            }
                        )
                    }

                    val highlightColor by animateColorAsState(
                        if (message.id == highlightedMessageId) KaspaTeal.copy(alpha = 0.18f) else Color.Transparent,
                        label = "messageHighlight"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(highlightColor, RoundedCornerShape(12.dp))
                    ) {
                        // iOS PublicChatMessageRow: HStack(alignment: .bottom, spacing: 8) - the 32
                        // avatar on the sender's side, at least 40 left free on the other, and a
                        // VStack spacing 3 of name, quote, bubble, extras and the time line.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                        if (!isMine) {
                            avatar()
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(40.dp))
                        }
                        Column(
                            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            // The double-tap bar sits directly above the message, as iOS's does.
                            InlineQuickReactionBar(
                                messageId = message.id,
                                onReact = { emoji ->
                                    // Tapping your active emoji removes it; any other emoji
                                    // adds/replaces — same toggle rule as 1:1/group chats.
                                    val existing = messageReactions.firstOrNull { it.reactorAddress == myAddress }
                                    val action = if (existing?.emoji == emoji) "remove" else "add"
                                    broadcastViewModel.sendReaction(channelName, message.id, emoji, action)
                                },
                                onReply = { broadcastViewModel.startReplyTo(message) },
                                emojis = quickReactionEmojis,
                            )
                            // iOS: caption semibold in the accent colour, flush with the bubble's
                            // edge - "You" over your own messages.
                            Text(
                                if (isMine) stringResource(R.string.reactions_you)
                                else addressDisplayName(message.senderAddress, contactAliases[message.senderAddress], senderKnsNames[message.senderAddress]),
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (replyContent != null) {
                                // What the quote shows (iOS audit 2026-11-21 #18 `replyQuoteDisplay`):
                                // the sender and text a reply envelope carries are whatever its
                                // author typed, so the quote is drawn from the quoted message itself,
                                // found by replyToId among the room's loaded messages (with its edit).
                                // When it isn't loaded the quote names nobody: "Original message not
                                // loaded" over the text the reply claims, in italics.
                                val original = messageWindow.byId[replyContent.replyToId]
                                val youText = stringResource(R.string.reactions_you)
                                val quoteSender: String? = original?.let {
                                    if (it.senderAddress == myAddress) youText
                                    else addressDisplayName(it.senderAddress, contactAliases[it.senderAddress], senderKnsNames[it.senderAddress])
                                }
                                val quotePreview = remember(original, editsByTxId[original?.id], replyContent.replyToPreview) {
                                    if (original == null) {
                                        strippingRoomBidiControls(replyContent.replyToPreview)
                                    } else {
                                        val edited = editsByTxId[original.id]?.let {
                                            com.kachat.app.util.MessageEdit.apply(it.text, original.content)
                                        }
                                        strippingRoomBidiControls(MessageReply.previewText(edited ?: original.content).take(160))
                                    }
                                }
                                // iOS replyQuoteView: caption2 semibold sender over a two-line
                                // caption preview, 2 apart, padded 8 on tertiarySystemBackground in
                                // a 10-rounded box at most 240 wide.
                                Column(
                                    horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier
                                        .widthIn(max = 240.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(roomTertiarySystemBackground())
                                        .clickable { jumpToReply(replyContent.replyToId) }
                                        .padding(8.dp)
                                ) {
                                    if (quoteSender != null) {
                                        Text(
                                            quoteSender,
                                            color = KaspaTeal,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    } else {
                                        // The quoted message isn't here to check: nobody is named.
                                        Text(
                                            stringResource(R.string.pc_original_message_not_loaded),
                                            color = LocalAppColors.current.textSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        quotePreview,
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        fontStyle = if (quoteSender == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = if (isMine) TextAlign.End else TextAlign.Start,
                                    )
                                }
                            }
                            Box {
                                if (voiceNote != null) {
                                    // One bubble for the whole note: gapless across its parts,
                                    // one scrubber, "Receiving… N of M" (iOS 1b15521).
                                    RoomVoiceNoteBubble(
                                        note = voiceNote,
                                        isMine = isMine,
                                        onLongPress = { showMenu = true },
                                        onDoubleClick = { QuickReactionBarState.activeId = message.id },
                                    )
                                } else if (voiceContent != null) {
                                    AudioBubble(
                                        voiceContent,
                                        isSent = isMine,
                                        onLongPress = { showMenu = true },
                                        onDoubleClick = { QuickReactionBarState.activeId = message.id }
                                    )
                                } else if (displayContent.length > MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                                    // See MESSAGE_TEXT_TRUNCATION_THRESHOLD's doc comment in Screens.kt -
                                    // broadcast rooms are public/unencrypted, so a huge wall of text (e.g.
                                    // stray base64) landing here is if anything more likely than in a
                                    // private chat.
                                    var showFullText by remember { mutableStateOf(false) }
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = 280.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(roomBubbleColor(isMine))
                                            .combinedClickable(
                                                onClick = { showFullText = true },
                                                onLongClick = { showMenu = true },
                                                onDoubleClick = { QuickReactionBarState.activeId = message.id }
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            shownText.take(MESSAGE_TEXT_PREVIEW_LENGTH) + "…",
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary,
                                            fontSize = 17.sp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.show_more),
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble.copy(alpha = 0.85f) else KaspaTeal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (showFullText) {
                                        FullMessageTextDialog(
                                            // Every way a room's body is drawn reads shownText (iOS ee5ec98).
                                            text = shownText,
                                            onDismiss = { showFullText = false },
                                            // iOS PublicChatChannelView's onCopyMessage: "Message copied." for 3 s.
                                            onCopy = {
                                                clipboardManager.setText(AnnotatedString(displayContent))
                                                IosToasts.show(com.kachat.app.util.tr(R.string.kpui_message_copied), durationMs = IosToastDuration.LONG)
                                            }
                                        )
                                    }
                                } else if (isEntirelyInternalLinkMessage) {
                                    // Nothing but an in-app KaChat link: the invite/post card IS
                                    // the message. Built locally, so no autoFetch gate applies.
                                    KaChatInternalLinkCard(
                                        ref = internalLinkMatch!!.ref,
                                        url = internalLinkMatch.raw,
                                        txId = message.id,
                                        kaspaExplorer = kaspaExplorer,
                                        onDoubleTap = { QuickReactionBarState.activeId = message.id }
                                    )
                                } else if (loneLinkPreviewUrl != null) {
                                    // Message is nothing but a link the room may preview
                                    // ([previewableLink]) — the shared preview card (bare media
                                    // for image/video, attachment card for audio/files) replaces
                                    // the plain-text bubble entirely, exactly like 1:1/group
                                    // bubbles, and loads by itself. `fallbackText` keeps the raw
                                    // link visible if no preview data is found.
                                    LinkPreviewCard(
                                        url = loneLinkPreviewUrl,
                                        txId = message.id,
                                        kaspaExplorer = kaspaExplorer,
                                        fallbackText = displayContent,
                                        onDoubleTap = { QuickReactionBarState.activeId = message.id },
                                        isOutgoing = isMine,
                                        autoFetch = true,
                                    )
                                } else {
                                    // A plain tap on a link here does nothing: a room's senders are
                                    // anonymous, so a link opens only from the long-press menu's
                                    // Open Link (iOS `tapOpensLink: false`, PublicChatChannelView).
                                    // Sent bubbles are teal with black text/links for contrast —
                                    // matches 1:1/group chats' treatment of the same case.
                                    val linkColor = if (isMine) LocalAppColors.current.onOutgoingBubble else KaspaTeal
                                    val annotatedBody = remember(shownText, isMine) {
                                        buildAnnotatedString {
                                            append(shownText)
                                            for (match in TextLinkify.findUrls(shownText)) {
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                                            }
                                            // kachat:// isn't a web URL, so TextLinkify never
                                            // sees it - style/annotate it here so it's tappable
                                            // inline too.
                                            KaChatLink.findFirst(shownText)?.let { internal ->
                                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), internal.range.first, internal.range.last + 1)
                                                addStringAnnotation("URL", internal.raw, internal.range.first, internal.range.last + 1)
                                            }
                                        }
                                    }
                                    // iOS: body text padded 14 by 10 on secondarySystemBackground (the
                                    // outgoing teal for your own) in a 16-rounded bubble, 280 at most.
                                    // Where the text's links sit, for a long-press on one.
                                    var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = 280.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(roomBubbleColor(isMine))
                                    ) {
                                        Text(
                                            annotatedBody,
                                            color = if (isMine) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary,
                                            fontSize = 17.sp,
                                            onTextLayout = { bodyLayout = it },
                                            modifier = Modifier
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                                .pointerInput(annotatedBody) {
                                                    detectTapGestures(
                                                        // On a link: Open / Copy for that link (iOS
                                                        // onLinkLongPress → LinkActionsSheet).
                                                        // Anywhere else: the message's menu.
                                                        onLongPress = { position ->
                                                            val url = bodyLayout?.let { layout -> linkAt(layout, annotatedBody, position) }
                                                            if (url != null) linkMenuUrl = url else showMenu = true
                                                        },
                                                        onDoubleTap = { QuickReactionBarState.activeId = message.id },
                                                    )
                                                },
                                        )
                                    }
                                }

                                linkMenuUrl?.let { url ->
                                    // iOS PublicChatChannelView's link sheet: open it anywhere
                                    // (a KaChat link opens its screen), or copy it.
                                    LinkActionsSheet(
                                        url = url,
                                        onDismiss = { linkMenuUrl = null },
                                        onOpen = {
                                            val internal = KaChatLink.parse(url)
                                            if (internal != null) openKaChatLink(menuContext, internal)
                                            else uriHandler.openUri(url)
                                        },
                                        onCopy = { clipboardManager.setText(AnnotatedString(url)) },
                                    )
                                }

                                if (showMenu) {
                                    // The app's half sheet, as everywhere else (iOS 1bc6ba6).
                                    MessageActionsSheet(
                                        title = if (voiceContent != null) stringResource(R.string.composer_voice_message) else stringResource(R.string.message),
                                        preview = displayContent.takeIf { voiceContent == null },
                                        onDismiss = { showMenu = false },
                                    ) {
                                        ActionSheetRow(
                                            icon = Icons.AutoMirrored.Filled.Reply,
                                            title = stringResource(R.string.reply),
                                            subtitle = stringResource(R.string.quotes_this_message_above_your_reply),
                                        ) {
                                            broadcastViewModel.startReplyTo(message)
                                            showMenu = false
                                        }
                                        // Your own delivered text messages only - the same gate
                                        // 1:1 and group chats use.
                                        if (isMine && message.deliveryStatus == "sent" &&
                                            com.kachat.app.util.MessageEdit.isEditable(message.content)) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Edit,
                                                title = stringResource(R.string.ab_edit),
                                                subtitle = stringResource(R.string.changes_the_text_everyone_sees_the_new),
                                            ) {
                                                broadcastViewModel.startEditing(message)
                                                broadcastViewModel.setMessageText(
                                                    com.kachat.app.util.MessageEdit.unwrappedText(message.content)
                                                )
                                                showMenu = false
                                            }
                                        }
                                        // The message's first web link, opened or copied from here
                                        // only - a plain tap on it does nothing (iOS bubbleActions,
                                        // `firstLink`).
                                        val firstLink = remember(displayContent, voiceContent) {
                                            if (voiceContent != null) null
                                            else TextLinkify.findUrls(displayContent).firstOrNull {
                                                it.uri.startsWith("http://", ignoreCase = true) ||
                                                    it.uri.startsWith("https://", ignoreCase = true)
                                            }?.uri
                                        }
                                        if (firstLink != null) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Language,
                                                title = stringResource(R.string.open_link),
                                                subtitle = stringResource(R.string.kpui_opens_in_browser),
                                            ) {
                                                showMenu = false
                                                val internal = KaChatLink.parse(firstLink)
                                                if (internal != null) openKaChatLink(menuContext, internal)
                                                else uriHandler.openUri(firstLink)
                                            }
                                            // Not for a Nextcloud share: the link is the address of
                                            // someone's file, which the preview card won't hand out
                                            // either (iOS, same classifier).
                                            if (com.kachat.app.services.LinkPreviewService.nextcloudShareEndpoints(firstLink) == null) {
                                                ActionSheetRow(
                                                    icon = Icons.Default.Link,
                                                    title = stringResource(R.string.copy_link),
                                                    subtitle = stringResource(R.string.copy_link_subtitle),
                                                ) {
                                                    clipboardManager.setText(AnnotatedString(firstLink))
                                                    showMenu = false
                                                }
                                            }
                                        }
                                        // Not for a voice note: its text is the encoded audio (iOS
                                        // offers it only with no voice payload).
                                        if (voiceContent == null) {
                                        ActionSheetRow(
                                            icon = Icons.Default.ContentCopy,
                                            title = stringResource(R.string.copy_message),
                                            subtitle = stringResource(R.string.copies_the_text_to_your_clipboard),
                                        ) {
                                            clipboardManager.setText(AnnotatedString(displayContent))
                                            showMenu = false
                                            // iOS PublicChatChannelView: "Message copied." for 3 s.
                                            IosToasts.show(com.kachat.app.util.tr(R.string.kpui_message_copied), durationMs = IosToastDuration.LONG)
                                        }
                                        }
                                        if (!isMine) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Flag,
                                                title = stringResource(R.string.pc_report),
                                                subtitle = stringResource(R.string.pc_report_subtitle),
                                            ) {
                                                showMenu = false
                                                reportRoomMessage(message.id, message.senderAddress, displayContent)
                                            }
                                        }
                                        when (translationState) {
                                            is com.kachat.app.services.PostTranslationService.TranslationState.Translated ->
                                                if (translationKey in translationOriginals) {
                                                    ActionSheetRow(
                                                        icon = Icons.Default.Translate,
                                                        title = stringResource(R.string.pc_show_translation),
                                                        subtitle = stringResource(R.string.pc_translation_show_translation_subtitle),
                                                    ) {
                                                        broadcastViewModel.showTranslation(translationKey)
                                                        showMenu = false
                                                    }
                                                } else {
                                                    ActionSheetRow(
                                                        icon = Icons.AutoMirrored.Filled.Undo,
                                                        title = stringResource(R.string.pc_show_original),
                                                        subtitle = stringResource(R.string.pc_translation_show_original_subtitle),
                                                    ) {
                                                        broadcastViewModel.showOriginal(translationKey)
                                                        showMenu = false
                                                    }
                                                }
                                            com.kachat.app.services.PostTranslationService.TranslationState.Translating,
                                            is com.kachat.app.services.PostTranslationService.TranslationState.Unavailable -> Unit
                                            null, com.kachat.app.services.PostTranslationService.TranslationState.Failed ->
                                                if (considersTranslation && translationKey in translatableKeys) {
                                                    ActionSheetRow(
                                                        icon = Icons.Default.Translate,
                                                        title = stringResource(R.string.pc_translate),
                                                        subtitle = stringResource(R.string.kpui_shows_message_in, broadcastViewModel.readerLanguageName()),
                                                    ) {
                                                        broadcastViewModel.translateMessage(translationKey, displayContent)
                                                        showMenu = false
                                                    }
                                                }
                                        }
                                        ActionSheetRow(
                                            icon = Icons.Default.Public,
                                            title = stringResource(R.string.view_in_explorer),
                                            subtitle = stringResource(R.string.payment_explorer_subtitle),
                                        ) {
                                            uriHandler.openUri(kaspaExplorer.txUrl(message.id))
                                            showMenu = false
                                        }
                                        // Retry, then Reactions last, as iOS bubbleActions orders them.
                                        if (isMine && shownDeliveryStatus == "failed") {
                                            ActionSheetRow(
                                                icon = Icons.Default.Refresh,
                                                title = stringResource(R.string.retry_send),
                                                subtitle = stringResource(R.string.sends_this_message_again),
                                            ) {
                                                retryMessage()
                                                showMenu = false
                                            }
                                        }
                                        // The pill on the bubble shows WHICH emoji; it has no
                                        // room to say how many or from whom. This does.
                                        if (messageReactions.isNotEmpty()) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Favorite,
                                                title = stringResource(R.string.reactions, messageReactions.size),
                                                subtitle = stringResource(R.string.who_reacted_and_with_what),
                                            ) {
                                                showMenu = false
                                                showReactions = true
                                            }
                                        }
                                    }
                                }

                                if (showReactions) {
                                    ChatReactionsSheet(
                                        reactions = messageReactions,
                                        nameFor = { addr ->
                                            addressDisplayName(addr, contactAliases[addr], senderKnsNames[addr])
                                        },
                                        isMe = { it == myAddress },
                                        // Rooms already fetch every sender's KNS profile for the
                                        // bubbles, so the faces are in hand.
                                        avatarFor = { senderProfiles[it] },
                                        onDismiss = { showReactions = false },
                                    )
                                }

                                // Anchored INSIDE the bubble's own wrap-content Box, exactly like
                                // 1:1's MessageBubble - so BottomStart/BottomEnd resolve against
                                // the bubble itself. It used to live below this Box inside a
                                // `Box(Modifier.fillMaxWidth())`, which resolved the alignment
                                // against the full row width instead: the pill flew to the screen
                                // edge (up to ~280dp from a short bubble), and stretching the
                                // Column to full width also moved the double-tap QuickReactionBar
                                // to the left edge on any message that already had a reaction.
                                if (messageReactions.isNotEmpty()) {
                                    ReactionPill(
                                        reactions = messageReactions,
                                        myAddress = myAddress,
                                        modifier = Modifier
                                            .align(if (isMine) Alignment.BottomStart else Alignment.BottomEnd)
                                            .offset(y = 10.dp)
                                    )
                                }

                                // iOS: a small "edited" chip on the bubble's top inner corner
                                // (10pt secondary on a systemBackground capsule, 8 up), the mark
                                // every platform shows on an edited message.
                                if (editForRow != null) {
                                    Text(
                                        text = stringResource(R.string.pc_l53_edited),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(if (isMine) Alignment.TopStart else Alignment.TopEnd)
                                            .offset(y = (-8).dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(if (LocalAppColors.current.isDark) Color.Black else Color.White)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            // The pill is offset ~10dp below the bubble Box and offset reserves no
                            // layout space, so reserve it here - otherwise the pill overlaps the
                            // link preview card / next message below it. iOS pads the bubble 16
                            // below; the column's 3 spacing makes up the rest.
                            if (messageReactions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(13.dp))
                            }

                            // "Translating...", "Translated from Spanish - Show original", or why it
                            // could not be - the same line and words KaPosts uses, under the bubble
                            // rather than inside its Box, so the reaction pill stays on the bubble.
                            // Nothing at all until Translate is picked from the long-press menu.
                            if (translationState != null) {
                                TranslateAffordance(
                                    state = translationState,
                                    canTranslate = false,
                                    showingOriginal = translationKey in translationOriginals,
                                    onTranslate = { broadcastViewModel.translateMessage(translationKey, displayContent) },
                                    onShowOriginal = { broadcastViewModel.showOriginal(translationKey) },
                                    onShowTranslation = { broadcastViewModel.showTranslation(translationKey) },
                                )
                            }

                            // A link mixed with other text keeps the bubble above and stacks the
                            // shared preview card below it, as its own sibling — same placement
                            // (and same reasoning) as 1:1's MessageBubble/group's GroupMessageBubble.
                            // An internal link is always claimed above as the message itself,
                            // so only an external link can still want a card down here.
                            separateLinkPreviewUrl?.takeIf { internalLinkMatch == null }?.let { url ->
                                // Only a link the room may preview ([previewableLink]), and it
                                // loads by itself, with no tap.
                                LinkPreviewCard(url = url, txId = message.id, kaspaExplorer = kaspaExplorer, onDoubleTap = { QuickReactionBarState.activeId = message.id }, isOutgoing = isMine, autoFetch = true)
                            }


                            // The time under every message, exactly as in a 1:1 chat, and under your own
                            // the status after it: "Sending", "Sent" with the green check, "Failed · Tap
                            // to retry" (iOS fc6aec6), or "Not confirmed" with no Retry for a send whose
                            // outcome is unknown (iOS 33095ac). Rooms are long feeds, so other days carry the day.
                            MessageTimeLine(
                                timestampMs = message.blockTimestamp,
                                showsDay = true,
                            ) {
                                if (isMine) {
                                    DeliveryStatusLabel(
                                        status = if (shownDeliveryStatus == "unconfirmed") DeliveryStatus.UNCONFIRMED else deliveryStatusOf(shownDeliveryStatus, isOutgoing = true),
                                        onRetry = if (shownDeliveryStatus == "failed") retryMessage else null,
                                    )
                                }
                            }

                            // A reaction (not the message) that failed to send: red "Retry" under the
                            // message, paired with the error icon on the reaction pill. Aligned with
                            // ColumnScope.align (NOT a fillMaxWidth Box) so it sits under the pill's
                            // side of the bubble without stretching this Column to the full row width.
                            // Only this account's own: Retry resends as the open account, and does
                            // nothing for another's (audit L2).
                            messageReactions.firstOrNull { it.deliveryStatus == "failed" && it.reactorAddress == myAddress }?.let { failedReaction ->
                                Text(
                                    text = stringResource(R.string.retry),
                                    color = LocalAppColors.current.danger,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(if (isMine) Alignment.Start else Alignment.End)
                                        .clickable { broadcastViewModel.retryReaction(failedReaction) }
                                )
                            }
                        }
                        if (isMine) {
                            Spacer(Modifier.width(8.dp))
                            avatar()
                        } else {
                            Spacer(Modifier.width(40.dp))
                        }
                        }
                    }
                }
            }
        }

        if (showScrollToBottom && messages.isNotEmpty()) {
            val scrollToLatestLabel = stringResource(R.string.scroll_to_latest)
            // iOS: a 36 disc of regularMaterial edged white at 18% with a soft shadow, holding
            // chevron.down 14 semibold in the label colour - 12 in from the trailing edge and 12
            // above the compose bar.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = padding.calculateBottomPadding() + 12.dp)
                    .size(36.dp)
                    .iosGlass(cornerRadius = 18.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        coroutineScope.launch { listState.animateScrollToItem(messages.size - 1 + listOffset) }
                    }
                    .semantics { contentDescription = scrollToLatestLabel },
            ) {
                RoomChevronDown()
            }
        }

        }
    }


    if (showFeeEditor) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showFeeEditor = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.adjust_network_fee), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.if_the_network_is_busy_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    com.kachat.app.ui.theme.IosTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(KaspaUnit.label(stringResource(R.string.fee_kas))) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The one exact parser for typed KAS (iOS 16b64bc): "1,5" or "1.5", never a crash.
                    val desiredFeeSompi = KaspaUnit.sompiFromUserText(feeEditorInput)
                    val currentFeeSompi = estimatedFee ?: 0L
                    if (desiredFeeSompi != null && desiredFeeSompi > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
                        broadcastViewModel.setFeeRateOverride(kotlin.math.ceil(desiredFeeSompi / impliedMass).toLong())
                    } else {
                        broadcastViewModel.setFeeRateOverride(null)
                    }
                    showFeeEditor = false
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { broadcastViewModel.setFeeRateOverride(null); showFeeEditor = false }) {
                        Text(stringResource(R.string.use_default), color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
    }
}


// MARK: - Room screen pieces (iOS PublicChatChannelView)

private fun isRoomBidiControl(c: Char): Boolean = c.code in 0x202A..0x202E || c.code in 0x2066..0x2069

/** [text] without the bidi embedding, override and isolate controls: anyone can post them, and one
 *  reverses the order the rest of the text is read in. The plain direction marks (U+200E, U+200F)
 *  stay: right-to-left writing uses them (iOS ee5ec98 `strippingBidiControls`). */
private fun strippingRoomBidiControls(text: String): String =
    if (text.none { isRoomBidiControl(it) }) text else text.filterNot { isRoomBidiControl(it) }

/** iOS secondarySystemBackground: the room's incoming bubbles, its day dividers and the
 *  composer's reply/edit banners. */
@Composable
private fun roomSecondarySystemBackground(): Color =
    if (LocalAppColors.current.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

/** iOS tertiarySystemBackground: the reply quote above a room message. */
@Composable
private fun roomTertiarySystemBackground(): Color =
    if (LocalAppColors.current.isDark) Color(0xFF2C2C2E) else Color.White

/** A room bubble's fill: OutgoingBubble.color for your own, secondarySystemBackground for
 *  everyone else's (the room's own colour, a shade darker than the 1:1 thread's). */
@Composable
private fun roomBubbleColor(isMine: Boolean): Color =
    if (isMine) LocalAppColors.current.outgoingBubble else roomSecondarySystemBackground()

/** iOS editBanner / replyBanner: a caption glyph in the accent colour, a caption semibold accent
 *  title over a one-line caption preview in secondary, and xmark.circle.fill trailing - padded 12
 *  by 8 on secondarySystemBackground in a 12-rounded box, inset 16 and 8 below the bar's top. */
@Composable
private fun RoomComposerBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    preview: String,
    cancelDescription: String,
    onCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(roomSecondarySystemBackground())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = KaspaTeal,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.Cancel,
            contentDescription = cancelDescription,
            tint = colors.textSecondary,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
        )
    }
}

/** The inside of the room's text field: the 1:1 composer's ([ChatComposerFieldDecoration]) with
 *  the room's own placeholder, "Message #name". */
@Composable
private fun RoomComposerFieldDecoration(
    placeholder: String,
    showPlaceholder: Boolean,
    innerTextField: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showPlaceholder) {
            Text(
                placeholder,
                color = LocalAppColors.current.textTertiary,
                style = chatComposerTextStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        innerTextField()
    }
}

/** The room composer's mic / send: a 36 x 36 glassBackground(cornerRadius: 12) beside the field. */
@Composable
private fun RoomComposerSquareButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .iosGlass(cornerRadius = 12.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
}

/** SF Symbols' arrow.up.circle.fill at title3: an accent disc (secondary while a send is in
 *  flight) with the arrow cut out of it in the glass behind. */
@Composable
private fun RoomSendGlyph(enabled: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .background(if (enabled) KaspaTeal else LocalAppColors.current.textSecondary, CircleShape),
    ) {
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = LocalAppColors.current.surface,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** SF Symbols' chevron.down at 14 semibold in the label colour, drawn as a thin open chevron -
 *  the Material arrow carries padding iOS's glyph does not. */
@Composable
private fun RoomChevronDown() {
    val color = LocalAppColors.current.textPrimary
    androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 14.dp, height = 8.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(inset, inset)
            lineTo(size.width / 2f, size.height - inset)
            lineTo(size.width - inset, inset)
        }
        drawPath(
            path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/**
 * The "URL" annotation under [position] in a message's text, or null when the press landed off
 * every link - past the end of a line counts as off, not as the line's last character.
 */
private fun linkAt(layout: TextLayoutResult, text: AnnotatedString, position: Offset): String? {
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line)) return null
    if (position.x < layout.getLineLeft(line) || position.x > layout.getLineRight(line)) return null
    val offset = layout.getOffsetForPosition(position)
    return text.getStringAnnotations("URL", offset, offset).firstOrNull()?.item
}

/** "02:50", as KaChat's recording bar shows it (iOS 1b15521 `formatRecordingTime`). */
private fun formatRecordingTime(durationMs: Long): String {
    // Out of range shows "--:--" (iOS 369794e).
    if (durationMs < 0L || durationMs >= 360_000_000L) return "--:--"
    val totalSeconds = Math.round(durationMs / 1000.0)
    return "%02d:%02d".format(java.util.Locale.US, totalSeconds / 60, totalSeconds % 60)
}

/** The recording bar's ring (iOS 1b15521): a faint track and a red arc filling toward the limit
 *  from the top, 20 across with a 3 stroke, easing a second at a time. */
@Composable
private fun RoomRecordingRing(fraction: Float) {
    val track = LocalAppColors.current.textSecondary.copy(alpha = 0.25f)
    val red = Color.Red
    val shown by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000, easing = androidx.compose.animation.core.LinearEasing),
        label = "recordingRing",
    )
    androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        drawArc(red, -90f, 360f * shown, useCenter = false, topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/**
 * Plays a long voice note as one recording (iOS 1b15521 `PublicChatMultipartAudioPlayer`): every
 * part present is decoded and joined with no gap ([com.kachat.app.util.WebMOpusPartsDecoder]), so
 * the length is the total and seeking crosses parts. Parts that arrive later are picked up on the
 * next Play. Main thread only.
 */
private class RoomVoiceNotePlayer(private val context: android.content.Context) {
    var isPlaying by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    /** The length of the parts present (iOS `duration`). */
    var durationMs by mutableStateOf<Long?>(null)
    /** What the time under the scrubber says: where playback is, else the length. */
    var shownMs by mutableStateOf<Long?>(null)

    var player: android.media.MediaPlayer? = null
    var file: java.io.File? = null
    var playbackSignature: String? = null
    var loadedSignature: String? = null
    /** A seek made before playback started: where the next Play begins. */
    var pendingSeekFraction: Float? = null

    fun start(media: android.media.MediaPlayer) {
        // One note at a time: the one playing before pauses here (iOS f954738
        // `VoicePlaybackCoordinator`). Its own player, even after a scroll detached it.
        val started = com.kachat.app.util.VoicePlaybackCoordinator.willStart(context, this) {
            runCatching { if (media.isPlaying) media.pause() }
            if (player === media) isPlaying = false
        }
        if (!started) {
            stop()
            return
        }
        runCatching { media.start() }.onFailure { stop(); return }
        isPlaying = true
    }

    fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
        com.kachat.app.util.VoicePlaybackCoordinator.didStop(this)
    }

    fun stop() {
        com.kachat.app.util.VoicePlaybackCoordinator.didStop(this)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playbackSignature = null
        file?.delete()
        file = null
        isPlaying = false
        progress = 0f
        pendingSeekFraction = null
        shownMs = durationMs
    }

    /** The scrubber: 0...1 across the whole note. */
    fun seek(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        progress = clamped
        val media = player
        if (media != null) {
            val target = (clamped * media.duration).toInt()
            runCatching { media.seekTo(target) }
            shownMs = target.toLong()
        } else {
            pendingSeekFraction = clamped
            durationMs?.let { shownMs = (clamped * it).toLong() }
        }
    }
}

/**
 * The one bubble of a long voice note in a room (iOS 1b15521 `PublicChatVoiceNoteBubble`): play,
 * one scrubber across every part, the total length, and while parts are still on their way,
 * "Receiving… 7 of 12". A note still playing when its bubble scrolls out plays on
 * ([VoicePlayback]), as a single voice note's does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomVoiceNoteBubble(
    note: com.kachat.app.util.BroadcastVoiceNote,
    isMine: Boolean,
    onLongPress: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Changes whenever a part arrives (iOS `PublicChatVoiceNoteInput.signature`); a part going
    // from Sending to Sent keeps its content, so it doesn't.
    val signature = remember(note.parts) { note.parts.joinToString(",") { it.content.length.toString() } }
    // The parts present, in index order, as their WebM files.
    val parts by produceState<List<ByteArray>?>(null, signature) {
        val contents = note.parts.map { it.content }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            contents.mapNotNull { content ->
                com.kachat.app.util.VoiceMessage.parseOrNull(content)?.let { voice ->
                    runCatching {
                        android.util.Base64.decode(com.kachat.app.util.VoiceMessage.base64Payload(voice), android.util.Base64.DEFAULT)
                    }.getOrNull()
                }
            }
        }
    }
    val player = remember { RoomVoiceNotePlayer(context.applicationContext) }
    val playbackKey = "voicenote:${note.key}"

    // A note left playing when its bubble scrolled out is taken back; anything else is released
    // when the bubble goes.
    DisposableEffect(playbackKey) {
        VoicePlayback.adopt(playbackKey)?.let { (media, file) ->
            player.player = media
            player.file = file
            player.playbackSignature = signature
            media.setOnCompletionListener { player.stop() }
            player.isPlaying = runCatching { media.isPlaying }.getOrDefault(false)
            player.durationMs = runCatching { media.duration.toLong() }.getOrNull()
        }
        onDispose {
            val media = player.player
            if (media != null && runCatching { media.isPlaying }.getOrDefault(false)) {
                VoicePlayback.detach(playbackKey, media, player.file)
                player.player = null
                player.file = null
            } else {
                player.stop()
            }
        }
    }

    // The length of the parts present, again whenever a part arrives (unless playing).
    LaunchedEffect(signature, parts) {
        val present = parts ?: return@LaunchedEffect
        if (player.loadedSignature == signature || player.isPlaying) return@LaunchedEffect
        player.loadedSignature = signature
        player.isLoading = player.durationMs == null
        // Kept by the audio, so a bubble scrolling back in costs nothing (iOS f954738).
        val durationKey = com.kachat.app.util.VoiceNoteDurationCache.key(note.parts.map { it.content })
        val length = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.kachat.app.util.VoiceNoteDurationCache.durationMs(durationKey) {
                com.kachat.app.util.WebMOpusPartsDecoder.durationMs(present)
            }
        }
        if (player.loadedSignature == signature) {
            if (length > 0) {
                player.durationMs = length
                if (player.player == null) player.shownMs = length
            }
            player.isLoading = false
        }
    }

    // Where playback is, ten times a second.
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            val media = player.player ?: break
            runCatching {
                val duration = media.duration
                val position = media.currentPosition
                if (duration > 0) player.progress = position.toFloat() / duration
                player.shownMs = position.toLong()
            }
            delay(100)
        }
    }

    val toggle: () -> Unit = toggle@{
        if (player.isPlaying) {
            player.pause()
            return@toggle
        }
        // Paused on the same parts: carry on from there.
        val existing = player.player
        if (existing != null && player.playbackSignature == signature) {
            player.pendingSeekFraction?.let { fraction ->
                runCatching { existing.seekTo((fraction * existing.duration).toInt()) }
                player.pendingSeekFraction = null
            }
            player.start(existing)
            return@toggle
        }
        val present = parts ?: return@toggle
        val seek = player.pendingSeekFraction
        player.stop()
        player.pendingSeekFraction = seek
        player.isLoading = true
        scope.launch {
            val out = java.io.File(context.cacheDir, "voice_note_playback_${System.nanoTime()}.wav")
            val decoded = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.kachat.app.util.WebMOpusPartsDecoder.decodePartsToWav(present, out)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete()
                throw e
            } catch (e: Exception) {
                android.util.Log.w("RoomVoiceNoteBubble", "Could not decode the voice note", e)
                null
            }
            player.isLoading = false
            if (decoded == null) {
                out.delete()
                return@launch
            }
            try {
                val media = android.media.MediaPlayer()
                media.setDataSource(decoded.file.absolutePath)
                media.prepare()
                media.setOnCompletionListener { player.stop() }
                player.player = media
                player.file = decoded.file
                player.playbackSignature = signature
                player.durationMs = decoded.durationMs
                player.pendingSeekFraction?.let { fraction ->
                    media.seekTo((fraction * media.duration).toInt())
                    player.pendingSeekFraction = null
                }
                player.start(media)
            } catch (e: Exception) {
                android.util.Log.w("RoomVoiceNoteBubble", "Could not play the voice note", e)
                player.stop()
                decoded.file.delete()
            }
        }
    }

    val foreground = if (isMine) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary
    Row(
        modifier = Modifier
            .widthIn(min = 180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(roomBubbleColor(isMine))
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clickable(enabled = !player.isLoading && parts != null, onClick = toggle),
        ) {
            if (player.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = foreground)
            } else {
                Icon(
                    if (player.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = if (player.isPlaying) stringResource(R.string.kpui_pause) else stringResource(R.string.kpui_play),
                    tint = if (isMine) foreground else KaspaTeal,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Column(
            modifier = Modifier.widthIn(min = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // One scrubber across every part: a tap or a drag seeks.
            val trackColor = (if (isMine) foreground else LocalAppColors.current.textSecondary).copy(alpha = 0.3f)
            val fillColor = if (isMine) foreground else KaspaTeal
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .height(16.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (size.width <= 0) return@awaitEachGesture
                            down.consume()
                            player.seek(down.position.x / size.width)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                change.consume()
                                player.seek(change.position.x / size.width)
                            }
                        }
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(trackColor)
                )
                Box(
                    Modifier
                        .fillMaxWidth(player.progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fillColor)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    com.kachat.app.util.VoiceMessage.formatDuration((player.shownMs ?: player.durationMs ?: 0L).toInt()),
                    color = foreground,
                    fontSize = 12.sp,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                )
                if (!note.isComplete) {
                    Text(
                        stringResource(R.string.pc_room_voice_receiving, note.parts.size, note.count),
                        color = foreground,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** The sender whose avatar was tapped in a room, with the message for Report; non-null presents
 *  [RoomSenderActionsSheet]. */
private data class RoomSenderSheetTarget(
    val address: String,
    val isOwnMessage: Boolean,
    val messageId: String = "",
    val messageText: String = "",
)

/**
 * A room sender's half sheet (iOS 148a5331 `PublicChatChannelView.senderSheet`): View Profile
 * (their KaPost profile), Send Kaspa, Copy Address, Hide User, Report and Block. No Open Chat, as
 * on iPhone. Where an option opens something of its own, it runs after [onDismiss].
 */
@Composable
private fun RoomSenderActionsSheet(
    address: String,
    displayName: String,
    isOwnMessage: Boolean,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onPayInKaspa: () -> Unit,
    onCopyAddress: () -> Unit,
    onHide: () -> Unit,
    hideSubtitle: String,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    val colors = LocalAppColors.current
    ActionSheetContainer(
        title = if (isOwnMessage) stringResource(R.string.pc_you) else displayName,
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        Text(
            address,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        ActionSheetRow(
            icon = Icons.Default.Person,
            title = stringResource(R.string.view_profile),
            subtitle = stringResource(R.string.pc_sender_view_profile_subtitle),
        ) { onDismiss(); onViewProfile() }
        if (!isOwnMessage) {
            ActionSheetRow(
                icon = painterResource(R.drawable.ic_kaspa_logo),
                title = stringResource(R.string.chats_send_kaspa),
                subtitle = stringResource(R.string.pc_sender_send_kaspa_subtitle),
            ) { onDismiss(); onPayInKaspa() }
        }
        ActionSheetRow(
            icon = Icons.Default.ContentCopy,
            title = stringResource(R.string.copy_address),
            subtitle = stringResource(R.string.pc_sender_copy_address_subtitle),
        ) { onDismiss(); onCopyAddress() }
        if (!isOwnMessage) {
            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.hide_user),
                subtitle = hideSubtitle,
                tint = colors.danger,
            ) { onDismiss(); onHide() }
            ActionSheetRow(
                icon = Icons.Default.Flag,
                title = stringResource(R.string.pc_report),
                subtitle = stringResource(R.string.pc_report_subtitle),
                tint = colors.danger,
            ) { onDismiss(); onReport() }
            ActionSheetRow(
                icon = Icons.Default.Block,
                title = stringResource(R.string.pc_block_name, displayName),
                subtitle = stringResource(R.string.pc_block_subtitle),
                tint = colors.danger,
            ) { onDismiss(); onBlock() }
        }
    }
}
