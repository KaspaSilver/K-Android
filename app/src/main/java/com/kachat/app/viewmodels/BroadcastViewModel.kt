package com.kachat.app.viewmodels

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import com.kachat.app.util.redactedForLog
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.BroadcastScanningService
import com.kachat.app.services.LinkPreviewService
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NextcloudService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.UtxoEntry
import com.kachat.app.services.VoiceRecorderService
import com.kachat.app.services.WalletManager
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import com.kachat.app.models.FeaturedBroadcastChannels
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val draftStore: com.kachat.app.services.DraftStore,
    private val broadcastRepository: BroadcastRepository,
    private val broadcastScanningService: BroadcastScanningService,
    private val voiceRecorderService: VoiceRecorderService,
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    /** The rooms' own settings (indexer overrides, avatars) and KaPost's block list. */
    private val settings: com.kachat.app.services.publicchats.PublicChatSettings,
    /** Which room is on screen, and the room banners. */
    private val broadcastNotifier: com.kachat.app.services.publicchats.BroadcastNotifier,
    private val chatRepository: ChatRepository,
    private val notificationHelper: NotificationHelper,
    private val nextcloudService: NextcloudService,
    /** What has been read in each room, per wallet - the Public Chats list's unread counts. */
    private val readState: com.kachat.app.services.BroadcastReadStateStore,
    /** The KaPosts translation service, reused as-is for public chat messages (iOS 03e5128). */
    private val translationService: com.kachat.app.services.PostTranslationService,
    /** The rooms' summaries and unread counts, shared with the badges. */
    private val unreadBadge: com.kachat.app.services.publicchats.PublicChatUnreadBadge,
    /** Every default room joined, retired ones moved, dropped ones left (iOS 961fc05a). */
    private val defaultRooms: com.kachat.app.services.publicchats.PublicChatDefaultRooms,
) : ViewModel() {

    // Address -> KNS avatar URL (or null if fetched but no avatar/domain exists) for whoever's
    // posted in a channel — broadcast senders usually aren't saved contacts, so this can't reuse
    // ChatRepository's per-contact knsAvatarUrl caching and instead looks up arbitrary addresses
    // directly via KnsService. A key's mere presence means "already fetched" (avoids re-fetching
    // on every recomposition), regardless of whether the value itself is null.
    private val _senderProfiles = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderProfiles: StateFlow<Map<String, String?>> = _senderProfiles.asStateFlow()

    // Address -> active KNS domain name (or null if fetched but the address owns no domain),
    // fetched alongside the avatar above — used as a fallback name label for senders the active
    // account hasn't saved a contact name for (see contactAliases below, which always wins first).
    private val _senderKnsNames = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderKnsNames: StateFlow<Map<String, String?>> = _senderKnsNames.asStateFlow()

    /**
     * Address -> locally-set contact alias, for whichever senders the active account has
     * renamed via "View Profile" — reactive (not a one-shot fetch like the KNS lookups above) so
     * editing a name on the chat-info screen and coming back to the broadcast reflects it
     * immediately. Takes priority over a sender's KNS name wherever both are shown, since a name
     * you deliberately set for someone should win over their public on-chain domain name.
     */
    val contactAliases: StateFlow<Map<String, String>> = chatRepository.getContacts()
        .map { contacts -> contacts.mapNotNull { c -> c.alias?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Every contact's address: the people a room's @ suggestions offer besides its recent
     *  senders (iOS `ContactsManager.activeContacts`, 2026-11-24). */
    val contactAddresses: StateFlow<List<String>> = chatRepository.getContacts()
        .map { contacts -> contacts.map { it.id } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Was the KNS name and avatar lookup for a room's sender. K reads no .kas names since
     * 2026-11-23 (iOS 518fdc8c, .kachat only): senders are named by their .kachat name, which the
     * rows read themselves, so there is nothing to look up here any more.
     */
    @Suppress("UNUSED_PARAMETER")
    fun ensureSenderProfileFetched(address: String) {}

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()
    /** The room whose draft the composer is editing - see [openDraft]. */
    @Volatile
    private var activeDraftRoom: String? = null

    /** A room was entered: its composer shows what was typed there last time, and keeps it as it
     *  changes (iOS 360e5d2, keyed "room:<name>"). Replaced outright, so nothing carries over. */
    fun openDraft(channelName: String) {
        activeDraftRoom = channelName
        _messageText.value = draftStore.draft(com.kachat.app.services.DraftStore.roomKey(channelName))
        _feePreviewForce.value++
    }

    fun closeDraft(channelName: String) {
        if (activeDraftRoom == channelName) activeDraftRoom = null
    }

    /** Bumped when code (not a keystroke) sets the composer's text, or the fee editor opens: the
     *  fee preview then prices the text at once instead of waiting for a pause. */
    private val _feePreviewForce = MutableStateFlow(0L)

    /** [fromTyping]: a keystroke in the composer, whose fee preview waits for a pause (see
     *  [typedTextPreviewBytes]); any other set is priced at once. */
    fun setMessageText(text: String, fromTyping: Boolean = false) {
        _messageText.value = text
        if (!fromTyping) _feePreviewForce.value++
        activeDraftRoom?.let { draftStore.setDraft(com.kachat.app.services.DraftStore.roomKey(it), text) }
        // The red failure line above the composer would otherwise sit there for the entire next
        // message being typed (it only cleared on the next send attempt). The failed bubble keeps
        // its own red icon + Retry, so once the user starts typing again the banner has done its
        // job — drop it rather than alarm them mid-composition.
        if (_sendBroadcastState.value.status == SendBroadcastStatus.FAILED) {
            _sendBroadcastState.value = SendBroadcastUiState()
        }
    }

    private val _currentUtxos = MutableStateFlow<List<UtxoEntry>>(emptyList())
    private val _networkFeeRate = MutableStateFlow(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble())
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

    // User-adjustable override for a busy fee market — set via the composer's clickable fee pill.
    // Applies to the next text send and clears itself afterward; a voice note never takes it (iOS).
    private val _feeRateOverride = MutableStateFlow<Long?>(null)
    val feeRateOverride: StateFlow<Long?> = _feeRateOverride.asStateFlow()

    fun setFeeRateOverride(rate: Long?) {
        _feeRateOverride.value = rate
    }

    // Declared here (ahead of the combine() below that references it) rather than down in the
    // "Voice messages" section further down — combine()'s flow arguments are evaluated
    // immediately when the enclosing val initializes, in textual property-declaration order, so
    // voiceRecordingState must already exist by the time previewPayloadSize is constructed.
    /** ENCODING: a room voice note stopped and being encoded into its parts (iOS 1b15521
     *  `PublicChatAudioRecorder.State.encoding`, "Encoding…" in the recording bar). */
    enum class VoiceRecordingStatus { IDLE, RECORDING, ENCODING }
    /** [maxMs]: the recording's ceiling, the bar's "/ 02:50" and its ring's full turn (iOS 1b15521). */
    data class VoiceRecordingState(
        val status: VoiceRecordingStatus = VoiceRecordingStatus.IDLE,
        val elapsedMs: Long = 0L,
        val maxMs: Long = 0L,
        /** A call or another app took the microphone: the recording stopped where it got to, and
         *  Send and the trash still work ("Recording stopped", iOS b78fdb0 `wasInterrupted`). */
        val wasInterrupted: Boolean = false,
    )

    private val _voiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val voiceRecordingState: StateFlow<VoiceRecordingState> = _voiceRecordingState.asStateFlow()

    /** This recording was started "via Nextcloud" from the mic's route step - the room then
     *  carries only the share link. Replaced the "Send Media via Nextcloud" setting (iOS 8b13460).
     *  Declared ahead of [previewPayloadSize], which reads it. */
    private val _nextcloudVoiceRequested = MutableStateFlow(false)
    /** The connected Nextcloud account, or null - with one, the mic asks on chain or via
     *  Nextcloud first. */
    val nextcloudAccount get() = nextcloudService.account
    private val voiceViaNextcloud: Boolean get() = _nextcloudVoiceRequested.value && nextcloudService.isConnected

    /** Re-prices the fee preview off the composer's exact text now - the fee editor opening, so
     *  the rate it derives from the shown fee isn't off by a pending keystroke or two. */
    fun refreshFeePreview() {
        _feePreviewForce.value++
    }

    /**
     * The composer's text bytes as the fee preview prices them: while typing, only after a 0.6 s
     * pause and not while within 24 bytes of the size already priced (iOS 0977a5b, see
     * [com.kachat.app.util.TypingFeeGate]). A reply, fee override, recording or Nextcloud route
     * change re-prices at once. `replyingTo` is declared further down, so it's read lazily.
     */
    private val typedTextPreview: SharedFlow<com.kachat.app.util.TypingFeeGate.Preview> = com.kachat.app.util.TypingFeeGate.preview(
        combine(
            _messageText,
            _feePreviewForce,
            kotlinx.coroutines.flow.flow { emitAll(replyingTo) },
            _feeRateOverride,
            combine(voiceRecordingState.map { it.status }.distinctUntilChanged(), _nextcloudVoiceRequested) { recording, voiceNextcloud -> recording to voiceNextcloud },
        ) { text, force, reply, override, voice ->
            com.kachat.app.util.TypingFeeGate.Input(text, listOf(force, reply?.id, override, voice))
        }
    ).shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val typedTextPreviewBytes: Flow<Int> = com.kachat.app.util.TypingFeeGate.payloadBytes(typedTextPreview)

    /** The fee pill shows iOS's shimmering placeholder while a re-price waits for its pause. */
    val isEstimatingFee: StateFlow<Boolean> = com.kachat.app.util.TypingFeeGate.estimating(typedTextPreview)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    /** The room a voice note is being recorded in - its name is in every part's payload. */
    @Volatile
    private var recordingChannelName: String = ""

    /** One payload size per transaction the composer would send: the text's one, or while
     *  recording on chain each part's (iOS 1b15521 `updateRecordingFeeEstimate`: the parts a
     *  recording this long goes as, 16 kbps CBR making their sizes exact to a few bytes). */
    private val previewPayloadSize = combine(typedTextPreviewBytes, voiceRecordingState, _nextcloudVoiceRequested) { textBytes, recording, voiceNextcloud ->
        if (recording.status != VoiceRecordingStatus.IDLE) {
            if (voiceNextcloud && nextcloudService.isConnected) listOf(NEXTCLOUD_LINK_PREVIEW_BYTES)
            else voiceNotePayloadSizes(recordingChannelName, maxOf(1.0, recording.elapsedMs / 1000.0))
        } else {
            if (textBytes == 0) emptyList() else listOf(textBytes)
        }
    }

    /**
     * A broadcast is always a zero-amount self-stash send, same shape as a 1:1 "comm" message —
     * so this is the same local KaspaMass calculation, just without any payment-amount branch.
     * Preview only, assuming a single 34-byte P2PK change output — KaspaWalletEngine skips the
     * zero-value recipient output for zero-amount sends (matches iOS's
     * estimateContextualMessageFee, which also prices off one output); the actual send path
     * computes this precisely against the real scriptPublicKey length.
     */
    val estimatedFeeSompi: StateFlow<Long?> = combine(previewPayloadSize, _currentUtxos, _networkFeeRate, _feeRateOverride) { payloadSizes, utxos, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (payloadSizes.isEmpty()) return@combine null

        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= 1000) break
        }

        // A long voice note is one transaction per part: their fees added up (iOS 1b15521
        // `estimatePublicChatVoiceFee`).
        payloadSizes.sumOf { payloadSize ->
            val mass = KaspaMass.calculateMass(
                numInputs = count.coerceAtLeast(1),
                outputScriptLens = listOf(34),
                payloadSize = payloadSize
            )
            KaspaMass.calculateFee(mass, rate.toLong())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Each part's payload bytes for a room voice note of [seconds]: one part is an untagged
     *  message, more are tagged parts (iOS 1b15521 `estimatePublicChatVoiceFee`). */
    private fun voiceNotePayloadSizes(channelName: String, seconds: Double): List<Int> {
        val policy = com.kachat.app.util.PublicChatVoiceNotePolicy.PUBLIC_CHAT
        val partSizes = com.kachat.app.util.VoiceNoteLayout.partSizes(
            durationSeconds = minOf(seconds, policy.maxSeconds),
            partSeconds = policy.partSeconds,
            bitrate = com.kachat.app.util.VoiceNotePolicy.BITRATE,
            frameMs = com.kachat.app.util.VoiceNotePolicy.FRAME_MS,
            maxBytes = policy.maxBytes,
        )
        val prefixBytes = MessageProtocol.buildBcastPayload(channelName, "").size
        return partSizes.mapIndexed { offset, size ->
            val tag = if (partSizes.size > 1) com.kachat.app.util.VoicePartTag.of("0".repeat(32), offset + 1, partSizes.size) else null
            val base64Length = 4 * ((maxOf(0, size) + 2) / 3)
            // The JSON with an empty file, plus the file's base64: every character of it one byte.
            val envelope = com.kachat.app.util.BroadcastMediaFileEnvelope.json(
                name = tag?.fileName ?: "voice.webm",
                size = size,
                mimeType = "audio/webm",
                dataUrlContent = "data:audio/webm;base64,",
                voicePart = tag,
            )
            prefixBytes + envelope.toByteArray(Charsets.UTF_8).size + base64Length
        }
    }

    fun refreshUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.getAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch
                // No fee quote: a room send pays the fixed network minimum (iOS KaspaFeePolicy), so
                // the preview stays at it.
                _currentUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Could not refresh UTXOs for fee estimate", e)
            }
        }
    }

    // Held only while a channel screen is actually on screen — see startLiveViewing(). A fresh
    // BroadcastViewModel instance is created per nav back-stack entry, so this never leaks across
    // different channel screens; onCleared() is the safety net if a screen never explicitly
    // calls stopLiveViewing() (e.g. process death skipping the DisposableEffect's onDispose).
    private var liveViewingHandle: AutoCloseable? = null
    private var liveViewingChannel: String? = null

    /** Lets messages appear live in a broadcast channel screen without requiring that channel to be marked always-listen — call from a DisposableEffect keyed on the channel, paired with [stopLiveViewing]. */
    fun startLiveViewing(channelName: String) {
        liveViewingHandle?.close()
        liveViewingHandle = broadcastScanningService.startLiveViewing(channelName)
        liveViewingChannel = channelName
        broadcastNotifier.setActiveChannel(channelName) // suppress a notification for the channel already on screen
        // And clear the ones already in the shade, ours and the ones a push drew (iOS acquire's
        // clearDeliveredNotifications).
        broadcastNotifier.clearDelivered(channelName)
    }

    fun stopLiveViewing() {
        liveViewingHandle?.close()
        liveViewingHandle = null
        // Only this screen's room: another room's screen may already have taken over.
        liveViewingChannel?.let { broadcastNotifier.clearActiveChannel(it) }
        liveViewingChannel = null
    }

    override fun onCleared() {
        roomClosed = true
        stopLiveViewing()
        acquiredRoom?.let { releaseRoom(it) }
        // Avoid leaking a live MediaRecorder if the screen/ViewModel is torn down mid-recording.
        // (One being encoded finishes and sends: it was already sent from the bar.)
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
            voiceRecorderService.cancelRecording()
        }
    }

    // Rooms the app uses as machinery (the chess arena) are never chats: out of the list, the
    // unread counts and every room summary (iOS BroadcastService.serviceChannels).
    val joinedChannels: StateFlow<List<BroadcastChannelEntity>> = broadcastRepository.getJoinedChannels()
        .map { channels -> channels.filter { it.channelName !in com.kachat.app.models.FeaturedBroadcastChannels.SERVICE_CHANNELS } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Each joined room's newest message and unread count, for the Public Chats list: the one
     * shared copy the dock badge also reads ([com.kachat.app.services.publicchats.PublicChatUnreadBadge.roomSummaries]),
     * so a write to the room table re-reads each room once, not once per screen (audit H4).
     */
    val roomSummaries: StateFlow<Map<String, BroadcastRepository.RoomSummary>> get() = unreadBadge.roomSummaries

    /** Unread messages in the rooms with their bell on: the red count on the KaChat title's
     *  Chats / Public Chats button (iOS 961fc05a `pageCycleButton`; not the dock's). */
    val bellOnRoomUnread: StateFlow<Int> get() = unreadBadge.bellOnUnreadCount

    /**
     * The rooms the Public Chats list shows, in its order: the two Popular rooms on top, then every
     * other room - the language rooms and your own - by latest activity. A language room nobody
     * has written in yet keeps its place in LANGUAGE_NAMES: they are all joined at the same
     * moment, so their join times would order them at random. Default rooms switched off with the
     * customize button are left out. Shared by the list and by its Select All (iOS KaPosts
     * PublicChatService.listedChannels, 865c9ab).
     */
    val listedChannels: StateFlow<List<BroadcastChannelEntity>> =
        kotlinx.coroutines.flow.combine(joinedChannels, roomSummaries, readState.state) { channels, summaries, read ->
            val featured = FeaturedBroadcastChannels.NAMES
            val languages = FeaturedBroadcastChannels.LANGUAGE_NAMES
            val shown = channels.filter { it.channelName !in read.hiddenCurated }
            // Highest first: (latest message, 0), else a language room's (0, -position), else
            // (join time, 0).
            fun sortKey(channel: BroadcastChannelEntity): Pair<Long, Int> {
                summaries[channel.channelName]?.lastMessage?.blockTimestamp?.let { return it to 0 }
                val index = languages.indexOf(channel.channelName)
                if (index >= 0) return 0L to -index
                return channel.joinedAt to 0
            }
            featured.mapNotNull { name -> shown.firstOrNull { it.channelName == name } } +
                shown.filter { it.channelName !in featured }
                    .sortedWith(compareByDescending<BroadcastChannelEntity> { sortKey(it).first }.thenByDescending { sortKey(it).second })
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * "Delete" from the room list. A room you added is left for good ([leaveChannel], its messages
     * go). A default room cannot really be deleted - it is simply switched off, the same as its
     * toggle in Public Chats settings, and that toggle brings it back (iOS e08c4cc).
     */
    fun removeFromList(channelName: String) {
        if (channelName in FeaturedBroadcastChannels.CURATED_NAMES) setCuratedRoomShown(channelName, false)
        else leaveChannel(channelName)
    }

    /** This wallet's own address, for "You" on the rows it sent. Null while there is none. */
    fun myAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /**
     * Opening a room is reading it (iOS 369794e `markChannelRead`): up to the newest message seen,
     * not up to now - a message sent just before you left the room but delivered after (the block
     * scan or the indexer lagging) has a block time earlier than "now" and was counted as read
     * without ever being shown. Never backward ([BroadcastReadStateStore.markRead]), and from now
     * only for a room with nothing stored. In the repository's long-lived scope, so a room closing
     * as the view model goes still counts as read.
     */
    fun markRoomRead(channelName: String) {
        val now = System.currentTimeMillis()
        broadcastRepository.sendScope.launch {
            val newest = runCatching { broadcastRepository.newestBlockTimestamp(channelName) }.getOrNull()
            readState.markRead(channelName, newest ?: now)
        }
    }

    fun markRoomUnread(channelName: String) {
        readState.markUnread(channelName)
    }

    /** Default rooms switched off in Public Chats settings, for this wallet. */
    val hiddenCuratedRooms: StateFlow<Set<String>> = readState.state
        .map { it.hiddenCurated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Public Chats settings: shows or hides one of the default rooms. Off takes the room out of
     * the list and turns its notifications off, which also takes it off the push service's watch
     * list. On brings it back silenced, as every room starts (iOS 8cb4c88e, owner 2026-11-24).
     */
    fun setCuratedRoomShown(channelName: String, shown: Boolean) {
        if (channelName !in FeaturedBroadcastChannels.CURATED_NAMES) return
        readState.setCuratedShown(channelName, shown)
        val joined = joinedChannels.value.any { it.channelName == channelName }
        if (!joined) {
            // A switch that is on means the room is in the Chats circles: switching on a room not
            // joined yet joins it (a local subscription - nothing on chain), so it appears at once,
            // with its bell off (iOS PublicChatService.setCuratedChannel).
            // Still a default room, so it goes if the indexer drops it: joined as auto-joined, not
            // as the person's own (iOS 65f8a765).
            if (shown) viewModelScope.launch {
                broadcastRepository.joinDefaultChannel(channelName)
                readState.updateAutoJoined { it + channelName }
            }
            return
        }
        if (!shown) setNotifyEnabled(channelName, false)
    }

    /**
     * The Languages switch on the customize sheet: every language room off at once (out of the
     * list, bells off), or all back on - each then switched off one by one if only some are
     * wanted (iOS KaPosts setAllLanguageChannels, d37f64d).
     */
    fun setAllLanguageRoomsShown(shown: Boolean) {
        val hidden = readState.state.value.hiddenCurated
        val joined = joinedChannels.value.map { it.channelName }.toSet()
        for (name in FeaturedBroadcastChannels.LANGUAGE_NAMES) {
            val on = name !in hidden && name in joined
            if (on != shown) setCuratedRoomShown(name, shown)
        }
    }

    /**
     * Every default room listed from the start, as soon as the wallet's read state is loaded
     * (iOS KaPosts ensureDefaultChannelsJoined, 865c9ab). Every room joins silenced, bell off
     * (iOS 8cb4c88e, owner 2026-11-24: new downloads start with public chats silenced; until then
     * #kaspa and #kachat-bugs had their bell switched on once per wallet, and accounts that
     * already got that keep the bells they have). A retired room is moved to its replacement and
     * the indexer's rooms join too (iOS 961fc05a, [com.kachat.app.services.publicchats.PublicChatDefaultRooms]).
     */
    private suspend fun ensureDefaultRoomsJoined() {
        defaultRooms.ensureJoined()
    }

    /**
     * Edit Default Public Chats opened: the app-wide indexer's rooms are asked for again if the
     * last answer is over a minute old, so a room just added there shows under More Rooms (and
     * joins) within a minute (iOS 961fc05a `refreshIndexerTrackedRooms(maxAge: 60)`).
     */
    fun refreshIndexerRoomsSoon() {
        viewModelScope.launch { runCatching { broadcastRepository.refreshIndexerTrackedRooms(maxAgeMs = 60_000L) } }
    }

    /**
     * Whether senders' KNS profile pictures render in broadcast rooms, and whether
     * [ensureSenderProfileFetched] is allowed to look up a sender's avatar at all — toggled from
     * the same gear icon; off shows fallback initials for everyone and never fetches an avatar.
     */
    val showKnsAvatarsEnabled: StateFlow<Boolean> = settings.broadcastShowKnsAvatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Which block explorer website "Go to Explorer" opens — shared preference, set in Settings > Kaspa Explorer. */
    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.kachat.app.models.KaspaExplorer.default)

    /** The active account's hidden-sender rows - PER ROOM since 4.0 ("" = legacy every-room hide). */
    val hiddenSenders: StateFlow<List<com.kachat.app.models.HiddenBroadcastSenderEntity>> =
        broadcastRepository.getRoomHiddenSenders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Senders hidden in [channelName] - room-scoped rows plus legacy every-room rows. */
    fun hiddenAddressesIn(channelName: String): Set<String> =
        com.kachat.app.repository.BroadcastRepository.hiddenAddressesIn(channelName, hiddenSenders.value)

    /** Hides a sender in ONE room: their messages and notifications from that room disappear; other rooms are unaffected. */
    fun hideSender(senderAddress: String, channelName: String) {
        viewModelScope.launch { broadcastRepository.hideSender(senderAddress, channelName) }
    }

    /** Blocked accounts leave every room, and their pushes stop (iOS 9e22075). */
    fun blockSender(senderAddress: String) {
        viewModelScope.launch { broadcastRepository.blockSender(senderAddress) }
    }

    fun unhideSender(senderAddress: String, channelName: String) {
        viewModelScope.launch { broadcastRepository.unhideSender(senderAddress, channelName) }
    }

    // MARK: - Indexer backfill (every open room): once on open, then every 8s while the room
    // stays open - messages sent while the app was closed appear, and the room stays fresh
    // even when live block scanning lags. Dedupe by txId happens in the DAO's REPLACE insert.

    init {
        // Read state follows the account, and every joined room gets a marker the first time it
        // is seen, so it counts from now rather than from the start of its history. Then every
        // default room is joined for that account (see ensureDefaultRoomsJoined).
        viewModelScope.launch {
            walletManager.activeAddressFlow.collect {
                readState.setCurrentWallet(it)
                // Leftover pending rows are checked once per room per account (iOS e5a9b11).
                broadcastRepository.onActiveWalletChanged(it)
                if (it != null) ensureDefaultRoomsJoined()
            }
        }
        viewModelScope.launch {
            joinedChannels.collect { channels ->
                readState.seedIfMissing(channels.map { it.channelName })
            }
        }
    }

    // Rooms join with notifications OFF and stay off until the user turns a bell on (iOS
    // 8cb4c88e: #kaspa and #kachat-bugs, now #report-bugs, no longer switched on once per wallet).

    private var indexerPollJob: kotlinx.coroutines.Job? = null
    /** The room [indexerPollJob] polls, so an account switch can start it again for the new one. */
    private var indexerPollChannel: String? = null

    init {
        // A log out or account switch cancels the room's poll and the deep backfill it runs, and
        // a late answer is dropped ([BroadcastRepository.backfillFromIndexer], iOS b78fdb0
        // `walletGeneration`). A room still on screen polls again as the new account.
        viewModelScope.launch {
            walletManager.activeAddressFlow.drop(1).collect {
                val channel = indexerPollChannel ?: return@collect
                // The switch is recorded first (idempotent), so the new poll starts as the new account.
                broadcastRepository.onActiveWalletChanged(it)
                startIndexerBackfill(channel)
            }
        }
    }

    /**
     * Every open room asks its indexer, not just the curated ones (iOS
     * `startIndexerPollingIfConfigured`): a room the operator adds on the indexer has its history
     * there, a room can point at its own indexer in Room Info, and rows coming back are how the
     * app learns the indexer tracks the room (IndexerTrackedRooms, iOS 21c480d). With no indexer
     * configured each pass is a no-op ([BroadcastRepository.backfillFromIndexer] returns -1).
     */
    fun startIndexerBackfill(channelName: String) {
        stopIndexerBackfill()
        indexerPollChannel = channelName
        indexerPollJob = viewModelScope.launch {
            // A room with no indexer has no history coming to wait for, so its leftover pending
            // sends are settled now; an indexer room's wait for its deep backfill (iOS e5a9b11).
            if (!FeaturedBroadcastChannels.isIndexerTracked(channelName)) {
                broadcastRepository.settlePendingSendsFromBeforeThisRun(channelName)
            }
            runCatching { broadcastScanningService.pruneIfDue(force = true) }
            var lastRoomPruneAt = System.currentTimeMillis()
            while (true) {
                // The room stays composed (and this loop alive) while the app is in the
                // background, but there is nobody to show a fresh row to - skip the network work
                // until the app is active again (iOS gates the same poll on applicationState).
                // Signed out: no rooms to poll (iOS b78fdb0).
                if (notificationHelper.isAppInForeground && broadcastRepository.isSignedIn()) {
                    broadcastRepository.backfillFromIndexer(channelName)
                    val now = System.currentTimeMillis()
                    if (now - lastRoomPruneAt >= ROOM_PRUNE_INTERVAL_MS) {
                        // Every five minutes while the room is open and the app in front, so a
                        // message goes soon after it expires (iOS PublicChatChannelView's
                        // pruneNowAndRefresh task).
                        lastRoomPruneAt = now
                        runCatching { broadcastScanningService.pruneIfDue(force = true) }
                    } else {
                        // gated by its own interval: a few minutes at most between runs
                        runCatching { broadcastScanningService.pruneIfDue() }
                    }
                } else if (!notificationHelper.isAppInForeground) {
                    // Asleep until the app is back, not waking every 8 s (audit 2026-11-25 B10).
                    notificationHelper.appForegroundFlow.first { it }
                    continue
                }
                kotlinx.coroutines.delay(8_000)
            }
        }
    }

    fun stopIndexerBackfill() {
        indexerPollJob?.cancel()
        indexerPollJob = null
        indexerPollChannel = null
    }

    /** Per-channel opt-in to background scanning — toggled via the speaker icon next to a channel. */
    fun setAlwaysListen(channelName: String, alwaysListen: Boolean) {
        viewModelScope.launch { broadcastRepository.setAlwaysListen(channelName, alwaysListen) }
    }

    /** Per-channel opt-in to a system notification for new messages — toggled via the bell icon next to a channel. */
    fun setNotifyEnabled(channelName: String, notifyEnabled: Boolean) {
        viewModelScope.launch { broadcastRepository.setNotifyEnabled(channelName, notifyEnabled) }
    }

    /** Per-channel local message retention override — set via the settings icon next to a channel, capped at 3 days. */
    fun setRetentionMillis(channelName: String, retentionMillis: Long) {
        viewModelScope.launch { broadcastRepository.setRetentionMillis(channelName, retentionMillis) }
    }

    /** Joins a room a share link asked for (the room screen's join request). The name is checked
     *  again; a failure is only logged, as the room opens either way. */
    fun joinChannel(rawName: String) {
        // a retired room's name opens the room that replaced it (iOS 961fc05a)
        val name = FeaturedBroadcastChannels.replacingRetired(MessageProtocol.normalizeChannelName(rawName))
        if (!MessageProtocol.isValidChannelName(name)) return
        viewModelScope.launch {
            try {
                broadcastRepository.joinChannel(name)
                // joined by the person (a link): theirs, whatever the indexer does (iOS 65f8a765)
                readState.keepRoomAsOwn(name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Could not join $name", e)
            }
        }
    }

    /**
     * New Public Chat in the Chats + sheet (iOS 148a5331 `ChatListView.joinRoomFromCreateSheet`):
     * the room list's own rules, an error said in place, and [onJoined] with the room's name once
     * it is in the list (the sheet then opens it).
     */
    fun joinFromCreateSheet(rawName: String, onError: (String) -> Unit, onJoined: (String) -> Unit) {
        // a retired room's name opens the room that replaced it (audit 2026-11-25 C2)
        val name = FeaturedBroadcastChannels.replacingRetired(MessageProtocol.normalizeChannelName(rawName))
        if (!MessageProtocol.isValidChannelName(name)) {
            onError(appContext.getString(com.kachat.app.R.string.pc_channel_name_rules, MessageProtocol.MAX_BROADCAST_CHANNEL_NAME_LENGTH))
            return
        }
        viewModelScope.launch {
            try {
                broadcastRepository.joinChannel(name)
                // joined by name in New Public Chat: the person's own (iOS 65f8a765)
                readState.keepRoomAsOwn(name)
                onJoined(name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Could not join $name", e)
                onError(appContext.getString(com.kachat.app.R.string.pc_join_failed))
            }
        }
    }

    /**
     * A room handed over from outside (a tapped notification, a room link): the name is checked
     * again, and a room with no row yet gets one first (iOS `openRoomFromHandoff`). [open] runs
     * with the checked name; nothing happens for a name that fails the gate.
     */
    fun openRoomFromHandoff(rawName: String, open: (String) -> Unit) {
        val name = com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel(rawName) ?: return
        viewModelScope.launch {
            if (joinedChannels.value.none { it.channelName == name }) {
                runCatching { broadcastRepository.joinChannel(name) }
                // joined from a link or a push: the person's own (iOS 65f8a765)
                readState.keepRoomAsOwn(name)
            }
            open(name)
        }
    }

    fun leaveChannel(channelName: String) {
        viewModelScope.launch { broadcastRepository.leaveChannel(channelName) }
        // A room left takes its read state with it; rejoining starts counting from then.
        readState.forget(channelName)
    }

    /** This room's own indexer, or "" when it follows the app-wide broadcast indexer. */
    fun indexerOverrideFor(channelName: String): Flow<String> =
        settings.broadcastIndexerOverrides.map { it[channelName.trim().lowercase()].orEmpty() }

    /** The app-wide broadcast indexer, shown as the placeholder a blank override falls back to. */
    val appWideBroadcastIndexer: StateFlow<String> =
        settings.broadcastIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    /** What Room Info's last Save found (iOS PublicChatRoomInfoView `IndexerCheck`). */
    sealed class IndexerCheck {
        object Idle : IndexerCheck()
        object Checking : IndexerCheck()
        data class Reachable(val count: Int) : IndexerCheck()
        data class Failed(val reason: String) : IndexerCheck()
    }

    private val _indexerCheck = MutableStateFlow<IndexerCheck>(IndexerCheck.Idle)
    val indexerCheck: StateFlow<IndexerCheck> = _indexerCheck.asStateFlow()

    /**
     * Room Info's Save: stores the override, says so, then asks the indexer the room now reads
     * from - the one just saved, or the app-wide one a cleared field falls back to - for one row.
     * A wrong URL is otherwise silent: the room just stops filling in (iOS `saveIndexer`).
     *
     * https only, by the app's rule for every server address: http:// is refused with the app's
     * words and nothing is saved, and a bare host gets https://. Returns what the field should
     * now show (the address as saved), or null when nothing was saved. A save while the last one
     * is still being checked goes ahead: that check is cancelled, and its late answer ignored.
     */
    fun saveIndexerOverride(channelName: String, typed: String, stored: String): String? {
        val trimmed = BroadcastRepository.httpsUrlOrNull(typed) ?: run {
            indexerProbeJob?.cancel()
            indexerCheckGeneration++
            _indexerCheck.value = IndexerCheck.Failed(BroadcastRepository.HTTPS_REQUIRED)
            return null
        }
        if (trimmed == stored.trim()) return trimmed
        indexerProbeJob?.cancel()
        val generation = ++indexerCheckGeneration
        _indexerCheck.value = IndexerCheck.Checking
        indexerProbeJob = viewModelScope.launch {
            settings.setBroadcastIndexerOverride(channelName, trimmed)
            com.kachat.app.ui.screens.IosToasts.show(
                // translated (iOS audit 2026-11-24 #38)
                if (trimmed.isEmpty()) appContext.getString(com.kachat.app.R.string.c54_room_follows_app_indexer)
                else appContext.getString(com.kachat.app.R.string.c54_room_indexer_updated, channelName),
                durationMs = com.kachat.app.ui.screens.IosToastDuration.LONG,
            )
            val target = trimmed.ifEmpty { settings.broadcastIndexerUrl.first().trim() }
            // Nothing to check: following the app-wide setting on a network without an indexer.
            if (target.isEmpty()) {
                if (generation == indexerCheckGeneration) _indexerCheck.value = IndexerCheck.Idle
                return@launch
            }
            val result = if (BroadcastRepository.isCleartextHttp(target)) {
                IndexerCheck.Failed(BroadcastRepository.HTTPS_REQUIRED)
            } else try {
                IndexerCheck.Reachable(broadcastRepository.probeIndexer(target, channelName))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                IndexerCheck.Failed(appContext.getString(com.kachat.app.R.string.c54_room_could_not_reach, e.message ?: e.toString()))
            }
            // A newer Save owns the row now.
            if (generation == indexerCheckGeneration) _indexerCheck.value = result
        }
        return trimmed
    }

    /** The check behind Room Info's last Save, cancelled by the next one. */
    private var indexerProbeJob: Job? = null
    /** Bumped by every Save: an older check's answer never lands over a newer one's. */
    private var indexerCheckGeneration = 0

    fun getMessages(channelName: String) = broadcastRepository.getMessages(channelName)

    // ------------------------------------------------------------------
    // Translation - someone else's text in another language, from its long-press menu (iOS 03e5128)
    // ------------------------------------------------------------------
    //
    // The same service, language detection and states KaPosts uses. Public chats only: 1:1 and
    // group messages are encrypted, and translating one would hand its plaintext to a server. A
    // message goes as bare text with no txid - the server caches by KaPost id and checks the text
    // against its own copy of the post, which a public chat message is not - so it is translated
    // and not cached. Nothing shows until Translate is picked; states live in memory only.

    private val _translations = MutableStateFlow<Map<String, com.kachat.app.services.PostTranslationService.TranslationState>>(emptyMap())
    val translations: StateFlow<Map<String, com.kachat.app.services.PostTranslationService.TranslationState>> = _translations.asStateFlow()
    private val _showingOriginal = MutableStateFlow<Set<String>>(emptySet())
    val showingOriginal: StateFlow<Set<String>> = _showingOriginal.asStateFlow()
    /** Messages worth offering Translate for: someone else's text, in a pair the service serves. */
    private val _translatable = MutableStateFlow<Set<String>>(emptySet())
    val translatable: StateFlow<Set<String>> = _translatable.asStateFlow()
    private val consideredTranslations = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var consideredLanguage: String? = null

    /** Per message AND per text: an edit changes the text, and a translation of the old text must
     *  not show over the new one. */
    fun translationKey(messageId: String, text: String): String = "publicchat:$messageId:${text.hashCode()}"

    /** What the service can serve, so Translate is offered only for a pair that can succeed. */
    fun refreshTranslationLanguages() {
        viewModelScope.launch { runCatching { translationService.refreshSupportedLanguages() } }
    }

    /** The reader's language by name ("English"), for "Shows this message in English." */
    fun readerLanguageName(): String =
        translationService.targetLanguage()?.let { translationService.displayName(it) } ?: "your language"

    /** Identifies the message's language once and records whether Translate is worth offering. */
    fun considerTranslation(key: String, text: String) {
        val language = translationService.targetLanguage()
        if (language != consideredLanguage) {
            consideredLanguage = language
            consideredTranslations.clear()
            _translatable.value = emptySet()
        }
        if (!consideredTranslations.add(key)) return
        viewModelScope.launch {
            val source = translationService.detectLanguage(text) ?: return@launch
            if (!translationService.canOfferTranslation(text, source)) return@launch
            _translatable.value = _translatable.value + key
        }
    }

    fun translateMessage(key: String, text: String) {
        _showingOriginal.value = _showingOriginal.value - key
        if (_translations.value[key] == com.kachat.app.services.PostTranslationService.TranslationState.Translating) return
        _translations.value = _translations.value + (key to com.kachat.app.services.PostTranslationService.TranslationState.Translating)
        viewModelScope.launch {
            val next = try {
                val result = translationService.translate(text, postId = null)
                com.kachat.app.services.PostTranslationService.TranslationState.Translated(
                    text = result.text,
                    sourceName = result.sourceLanguage?.let { translationService.displayName(it) } ?: "another language",
                )
            } catch (e: com.kachat.app.services.PostTranslationService.TranslationException) {
                if (e.terminal) com.kachat.app.services.PostTranslationService.TranslationState.Unavailable(e.readerMessage)
                else com.kachat.app.services.PostTranslationService.TranslationState.Failed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Translation failed", e)
                com.kachat.app.services.PostTranslationService.TranslationState.Failed
            }
            _translations.value = _translations.value + (key to next)
        }
    }

    fun showOriginal(key: String) { _showingOriginal.value = _showingOriginal.value + key }
    fun showTranslation(key: String) { _showingOriginal.value = _showingOriginal.value - key }

    /**
     * A tapped public-chat notification asks the indexer for the room's newest rows at once, in
     * parallel with the navigation, instead of waiting for the room to appear and start its own
     * backfill - so the message the notification announced is already there when the room opens
     * (iOS 609ade0). Runs in this view model's scope, not the caller's effect, so clearing the
     * pending channel as the navigation completes does not cancel it. The rows land in Room and
     * the room's own Flow picks them up.
     */
    // ------------------------------------------------------------------
    // Room window — "Load earlier messages"
    // ------------------------------------------------------------------
    //
    // A curated room holds thirty days of history and the thread screen read all of it, on open
    // and again on every change (iOS 02a4f58). It reads the newest ROOM_WINDOW_SIZE now, and the
    // control at the top of the room adds another window's worth. Per channel, and deliberately
    // NOT persisted: reopening a room starts from the newest again, as iOS does.
    private val roomWindows = MutableStateFlow<Map<String, Int>>(emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getMessageWindow(channelName: String): Flow<BroadcastRepository.RoomWindow> =
        roomWindows
            .map { it[channelName] ?: BroadcastRepository.ROOM_WINDOW_SIZE }
            .distinctUntilChanged()
            .flatMapLatest { limit ->
                // The widened window's first answer is what "Loading…" waits for.
                broadcastRepository.getMessageWindow(channelName, limit)
                    .onEach {
                        lastRoomWindows[channelName] = it
                        loadingEarlier.value = loadingEarlier.value - channelName
                    }
            }

    /** Rooms whose "Load earlier messages" is still being read (iOS `isLoadingOlder`). */
    private val loadingEarlier = MutableStateFlow<Set<String>>(emptySet())

    fun isLoadingEarlier(channelName: String): Flow<Boolean> =
        loadingEarlier.map { channelName in it }.distinctUntilChanged()

    /** The same, read now: cleared just before the widened window's first answer is handed on,
     *  so it is never behind the window the screen has. */
    fun isLoadingEarlierNow(channelName: String): Boolean = channelName in loadingEarlier.value

    /** Widens [channelName]'s window by another [BroadcastRepository.ROOM_WINDOW_SIZE]. A tap
     *  while the last one is still loading does nothing, as on iOS. */
    fun loadEarlier(channelName: String) {
        if (channelName in loadingEarlier.value) return
        loadingEarlier.value = loadingEarlier.value + channelName
        roomWindows.value = roomWindows.value.toMutableMap().apply {
            this[channelName] = (this[channelName] ?: BroadcastRepository.ROOM_WINDOW_SIZE) +
                BroadcastRepository.ROOM_WINDOW_SIZE
        }
    }

    /** The room closed: back to the newest [BroadcastRepository.ROOM_WINDOW_SIZE] next time it
     *  opens (iOS f954738 `release`: `windowSizeByChannel.removeValue`), two minutes later, not
     *  now (iOS d089011f `windowTrimWork`): a room only hidden behind another tab (a profile
     *  opened from it) comes back with its rows, so the reader's place holds. Opening the room
     *  again meanwhile ([keepRoomWindow]) calls it off. */
    fun resetRoomWindow(channelName: String) {
        roomWindowTrims.remove(channelName)?.cancel()
        roomWindowTrims[channelName] = viewModelScope.launch {
            delay(ROOM_WINDOW_TRIM_DELAY_MS)
            roomWindowTrims.remove(channelName)
            lastRoomWindows.remove(channelName)
            if (channelName !in roomWindows.value) return@launch
            roomWindows.value = roomWindows.value - channelName
            loadingEarlier.value = loadingEarlier.value - channelName
        }
    }

    /** The room is on screen again: a waiting trim ([resetRoomWindow]) is called off. */
    fun keepRoomWindow(channelName: String) {
        roomWindowTrims.remove(channelName)?.cancel()
    }

    /** The rows the room last showed, while its trim waits: drawn at once when it comes back
     *  from behind another tab, so the list is the same length under the reader. */
    fun cachedRoomWindow(channelName: String): BroadcastRepository.RoomWindow? = lastRoomWindows[channelName]

    private val roomWindowTrims = HashMap<String, kotlinx.coroutines.Job>()
    private val lastRoomWindows = java.util.concurrent.ConcurrentHashMap<String, BroadcastRepository.RoomWindow>()

    /** The room's screen opened or closed (iOS `acquire` / `release`): its first indexer read
     *  after opening removes a message the indexer no longer has on one miss. */
    private var acquiredRoom: String? = null

    fun acquireRoom(channelName: String) {
        acquiredRoom?.let { broadcastRepository.releaseRoom(it) }
        acquiredRoom = channelName
        broadcastRepository.acquireRoom(channelName)
    }

    fun releaseRoom(channelName: String) {
        if (acquiredRoom != channelName) return
        acquiredRoom = null
        broadcastRepository.releaseRoom(channelName)
    }

    /**
     * The Rooms page is on screen (iOS d089011f `checkListedRoomsAgainstIndexer`): each listed
     * indexed room nothing else keeps current (bell off, not open) is read from the indexer, at
     * most every 2 minutes, so a deleted message leaves its preview too. Waits briefly for the
     * list's first answer when the page has only just appeared.
     */
    fun checkListedRoomsAgainstIndexer() {
        viewModelScope.launch {
            val rooms = listedChannels.value.ifEmpty {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) { listedChannels.first { it.isNotEmpty() } } ?: return@launch
            }
            broadcastRepository.checkListedRoomsAgainstIndexer(rooms)
        }
    }

    // The message currently being replied to (double-tap on its bubble to set this), shown as a
    // banner above the compose field — cleared automatically once the reply actually sends.
    private val _replyingTo = MutableStateFlow<BroadcastMessageEntity?>(null)
    val replyingTo: StateFlow<BroadcastMessageEntity?> = _replyingTo.asStateFlow()

    fun startReplyTo(message: BroadcastMessageEntity) {
        _replyingTo.value = message
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    // The message whose text the composer is editing (one of your own), shown as a banner above
    // the compose field. Mutually exclusive with [_replyingTo], as on iOS.
    private val _editingMessage = MutableStateFlow<BroadcastMessageEntity?>(null)
    val editingMessage: StateFlow<BroadcastMessageEntity?> = _editingMessage.asStateFlow()

    fun startEditing(message: BroadcastMessageEntity) {
        _replyingTo.value = null
        _editingMessage.value = message
    }

    fun cancelEditing() {
        _editingMessage.value = null
    }

    /**
     * Delete for Everyone, confirmed (iOS 2026-11-25 `deletePublicChatMessage`): a delete envelope
     * goes out and the message goes from this phone once it has; a failure leaves it in the room
     * and [onFailed] says so. In the repository's long-lived scope, so leaving the room doesn't
     * cancel a delete already on its way.
     */
    fun deleteForEveryone(message: BroadcastMessageEntity, onFailed: () -> Unit) {
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.deletePublicChatMessage(message)
                if (_replyingTo.value?.id == message.id) _replyingTo.value = null
                if (_editingMessage.value?.id == message.id) _editingMessage.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Delete for everyone failed: ${e.message}")
                onFailed()
            }
        }
    }

    init {
        // Another account's composer targets don't carry over (iOS ee5ec98 setCurrentWallet). Here,
        // after the two flows it clears: their declarations run in order.
        viewModelScope.launch {
            walletManager.activeAddressFlow.drop(1).collect {
                _replyingTo.value = null
                _editingMessage.value = null
            }
        }
    }

    /** Edits that never left the phone, by room then message, waiting for Retry (iOS ee5ec98). */
    val failedEdits: StateFlow<Map<String, Map<String, String>>> get() = broadcastRepository.failedEdits

    /** The newest edit per message in [channelName], keyed by the edited message's txId - see
     *  [BroadcastRepository.getEdits]. */
    fun getEdits(channelName: String) = broadcastRepository.getEdits(channelName)

    /**
     * Edits one of this wallet's own text messages in a room: sent as an edit envelope exactly
     * like a reaction - one broadcast, no message row of its own (the envelope is filtered out of
     * the room's messages and applied to the message it names).
     */
    fun sendEdit(channelName: String, targetTxId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // A still-pending target has no real txId yet - an edit naming it could never be resolved
        // by anyone else (same guard as [sendReaction]).
        if (targetTxId.startsWith("pending_")) return
        // Only this message's: a Retry must not close another edit under way (iOS ee5ec98).
        if (_editingMessage.value?.id == targetTxId) _editingMessage.value = null
        launchEdit(channelName, targetTxId, clean)
    }

    /** Sends a failed edit's text again (iOS ee5ec98 `retryFailedEdit`). */
    fun retryFailedEdit(channelName: String, targetTxId: String) {
        val text = broadcastRepository.failedEditText(channelName, targetTxId) ?: return
        launchEdit(channelName, targetTxId, text)
    }

    private fun launchEdit(channelName: String, targetTxId: String, text: String) {
        // On the repository's scope, so Back doesn't cancel it (see [BroadcastRepository.sendScope]).
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.sendEdit(channelName, targetTxId, text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error sending broadcast edit", e)
                showEditFailure(e)
            }
        }
    }

    /** A toast, as iOS PublicChatChannelView.showEditFailure shows (3 s). */
    private fun showEditFailure(e: Exception) {
        // Not "Failed": it may well have gone through.
        if (e is com.kachat.app.services.SendOutcomeUnknownException) {
            if (!roomClosed) {
                com.kachat.app.ui.screens.IosToasts.show(
                    SEND_OUTCOME_UNKNOWN_MESSAGE,
                    durationMs = com.kachat.app.ui.screens.IosToastDuration.LONG,
                )
            }
        } else {
            showFailureToast("Failed to edit", e)
        }
    }

    enum class SendBroadcastStatus { IDLE, SENDING, FAILED }
    data class SendBroadcastUiState(val status: SendBroadcastStatus = SendBroadcastStatus.IDLE, val message: String? = null)

    private val _sendBroadcastState = MutableStateFlow(SendBroadcastUiState())
    val sendBroadcastState: StateFlow<SendBroadcastUiState> = _sendBroadcastState.asStateFlow()

    /** [isVoice]: a recorded voice note is never dropped because a text send is still going out
     *  (iOS doesn't block it on `isSending`); the send engine queues the two. [failurePrefix]
     *  starts the toast a failure shows (iOS: "Failed to send" / "Failed to send voice message"). */
    fun sendBroadcast(
        channelName: String,
        content: String,
        isVoice: Boolean = false,
        failurePrefix: String = "Failed to send",
    ) {
        if (content.isBlank()) return
        if (!isVoice && _sendBroadcastState.value.status == SendBroadcastStatus.SENDING) return
        val reply = _replyingTo.value
        // A voice note never takes the fee override, one part or many, and leaves it for the next
        // text (iOS sends voice without `feeOverride`).
        val feeRate = if (isVoice) null else _feeRateOverride.value
        if (!isVoice) _feeRateOverride.value = null
        val payload = if (reply != null) {
            // The quote iOS builds (`MessageReplyCodec.previewText`): a reply's own text, and a
            // photo, file, chess move or reaction by name - never its raw JSON.
            val preview = replyPreviewText(reply.content)
            MessageReply.encode(replyToId = reply.id, replyToSender = reply.senderAddress, replyToPreview = preview, text = content)
        } else {
            content
        }
        // Finishes after the room is closed, as iOS's send on the shared service does: Back
        // cancelling it would mark a send that went out failed, and its Retry would pay twice.
        broadcastRepository.sendScope.launch {
            _sendBroadcastState.value = SendBroadcastUiState(status = SendBroadcastStatus.SENDING)
            try {
                // Waiting for a coin to come free, it stays Sending and retries by itself, with no
                // toast (iOS `sendPublicChat` > `handleSendFailure`); the reply is used either way.
                broadcastRepository.sendPublicChat(channelName, payload, feeRateOverride = feeRate, deferNoInputRetry = true)
                // Only this reply: another may have been started while the send was out (iOS ee5ec98).
                if (reply != null && _replyingTo.value?.id == reply.id) _replyingTo.value = null
                _sendBroadcastState.value = SendBroadcastUiState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _sendBroadcastState.value = SendBroadcastUiState()
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error sending broadcast", e)
                _sendBroadcastState.value = SendBroadcastUiState()
                showFailureToast(failurePrefix, e)
            }
        }
    }

    /** Set once the room's view model is gone: a send still finishing then says nothing, as an
     *  iOS toast on a closed room shows nowhere. */
    @Volatile
    private var roomClosed = false

    /** iOS PublicChatChannelView.showToast: "<prefix>: <reason>", up for 3 s. Never for a cancel. */
    private fun showFailureToast(prefix: String, e: Exception) {
        if (roomClosed || e is kotlinx.coroutines.CancellationException) return
        // Translated, as iOS b78fdb0's room toasts ("Failed to send: %@" and the rest).
        val format = when (prefix) {
            "Failed to send voice message" -> com.kachat.app.R.string.pc_rooms54_failed_to_send_voice
            "Failed to send" -> com.kachat.app.R.string.pc_rooms54_failed_to_send
            "Failed to edit" -> com.kachat.app.R.string.pc_rooms54_failed_to_edit
            else -> null
        }
        val why = humanizeSendError(e)
        com.kachat.app.ui.screens.IosToasts.show(
            format?.let { appContext.getString(it, why) } ?: "$prefix: $why",
            durationMs = com.kachat.app.ui.screens.IosToastDuration.LONG,
        )
    }

    /**
     * Node-level rejection text ("transaction ... is an orphan, where orphan is disallowed",
     * "already spent", ...) means "the network hasn't caught up with your previous send yet" —
     * meaningless and alarming rendered raw above the composer. Map it (after the engine's own
     * orphan retry is exhausted) to plain language; anything unrecognized passes through as-is.
     */
    private fun humanizeSendError(e: Exception): String {
        // A send that may have landed says so, and its row shows "Not confirmed" with no Retry
        // (iOS 33095ac): never a "try again", which could post and pay twice.
        if (e is com.kachat.app.services.SendOutcomeUnknownException) return SEND_OUTCOME_UNKNOWN_MESSAGE
        // Every coin is being mined or held by a send in flight: waiting frees one, so it isn't
        // "Not enough KAS" (iOS 33095ac `noSpendableFundsYetMessage`).
        if (e is com.kachat.app.repository.NoCoinFreeYetException) {
            return appContext.getString(com.kachat.app.R.string.pc_room_no_spendable_funds_yet)
        }
        val raw = e.message ?: e.toString()
        val lower = raw.lowercase()
        return if (lower.contains("orphan") || lower.contains("already spent") || lower.contains("double spend")) {
            // iOS PublicChatService.friendlySendErrorMessage, word for word.
            appContext.getString(com.kachat.app.R.string.pc_l53_network_still_confirming)
        } else if (lower.contains("insufficient funds")) {
            // No coin on the chatting address (or too few): adding KAS is the fix, not waiting
            // (iOS d816404 `notEnoughKasMessage`). The send already fails at once here.
            appContext.getString(com.kachat.app.R.string.pc_room_not_enough_kas)
        } else {
            raw
        }
    }

    /** Re-attempts a failed broadcast — shown via a "Retry Send" option on a failed message's own dropdown menu, matching 1:1 chat's retry. */
    fun retryBroadcast(message: BroadcastMessageEntity) {
        // Not cancelled by Back (see [BroadcastRepository.sendScope]).
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.retryBroadcast(message, deferNoCoinRetry = true)
            } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
                // The retry may have landed: the row shows "Not confirmed" with no Retry (iOS
                // 33095ac). No toast, as iOS retryPublicChat (its handleSendFailure shows none).
                Log.w("BroadcastViewModel", "Broadcast retry outcome unknown", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error retrying broadcast", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voice messages — same VoiceMessage codec and VoiceRecorderService as 1:1 chats, embedded
    // directly as the broadcast's plaintext content (never encrypted, matching how broadcasts
    // work generally) rather than needing a separate transport. VoiceRecordingStatus/State and
    // _voiceRecordingState itself are declared further up, ahead of the previewPayloadSize
    // combine() that needs them.
    // -------------------------------------------------------------------------

    private var recordingTickerJob: Job? = null

    /** Recording (not playback) needs Android 10+ — the mic button should be disabled below that. */
    val voiceRecordingSupported: Boolean get() = voiceRecorderService.isSupported

    fun startVoiceRecording(channelName: String, viaNextcloud: Boolean = false) {
        if (_voiceRecordingState.value.status != VoiceRecordingStatus.IDLE) return
        _nextcloudVoiceRequested.value = viaNextcloud
        recordingChannelName = channelName
        // On chain, KaChat's long voice notes (iOS 1b15521 `VoiceNotePolicy.publicChat`): 16 kbps
        // CBR Opus in 60 ms frames, up to 2:50, sent as parts of at most 14.5 s. A "Record via
        // Nextcloud" note only needs to fit the server, so it runs to the app-wide Nextcloud ceiling.
        try {
            if (voiceViaNextcloud) {
                voiceRecorderService.startRecording(VoiceRecorderService.MAX_NEXTCLOUD_RECORDING_DURATION_MS)
            } else {
                voiceRecorderService.startOnChainRecording(com.kachat.app.util.PublicChatVoiceNotePolicy.PUBLIC_CHAT)
            }
        } catch (e: Exception) {
            Log.e("BroadcastViewModel", "Could not start voice recording", e)
            return
        }
        _voiceRecordingState.value = VoiceRecordingState(
            status = VoiceRecordingStatus.RECORDING,
            maxMs = if (voiceViaNextcloud) VoiceRecorderService.MAX_NEXTCLOUD_RECORDING_DURATION_MS
            else com.kachat.app.util.PublicChatVoiceNotePolicy.PUBLIC_CHAT.maxMs,
        )
        val startedAt = System.currentTimeMillis()
        recordingTickerJob = viewModelScope.launch {
            while (isActive && _voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
                val wallClock = System.currentTimeMillis() - startedAt
                // Only forward. The recorder stops itself at the note's length (its own sample
                // count, [VoiceRecorderService.reachedLimit]), the wall clock as a backstop.
                val elapsed = maxOf(wallClock, _voiceRecordingState.value.elapsedMs)
                _voiceRecordingState.value = _voiceRecordingState.value.copy(elapsedMs = elapsed)
                if (voiceRecorderService.reachedLimit(wallClock)) {
                    stopAndSendVoiceRecording(channelName)
                    break
                }
                delay(200)
            }
        }
    }

    /** Stops recording and sends it — unless it was too short to be a real message (a stray tap), in which case it's discarded silently, same as a cancel. */
    fun stopAndSendVoiceRecording(channelName: String) {
        if (_voiceRecordingState.value.status != VoiceRecordingStatus.RECORDING) return
        // Snapshot once, so a disconnect mid-send can't change the route (iOS
        // PublicChatChannelView.stopAndSendRecording).
        val viaNextcloud = voiceViaNextcloud
        _nextcloudVoiceRequested.value = false
        val elapsed = _voiceRecordingState.value.elapsedMs
        recordingTickerJob?.cancel()
        recordingTickerJob = null

        if (!viaNextcloud) {
            // A room voice note: "Encoding…" while it becomes its parts, then the send (iOS
            // 1b15521 `stopAndSendRecording`). The encode and the send outlive the room (see
            // [BroadcastRepository.sendScope]).
            _voiceRecordingState.value = _voiceRecordingState.value.copy(status = VoiceRecordingStatus.ENCODING)
            broadcastRepository.sendScope.launch {
                val tooShort = elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS
                val parts = try {
                    encodeRecordedNote()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed encode is said, never lost silently (iOS PublicChatChannelView
                    // stopAndSendRecording's toast); a stray tap still goes quietly.
                    Log.e("BroadcastViewModel", "Error encoding voice note", e)
                    if (!tooShort) showFailureToast("Failed to send voice message", e)
                    return@launch
                } finally {
                    _voiceRecordingState.value = VoiceRecordingState()
                }
                if (tooShort) return@launch
                if (parts.isEmpty()) {
                    // iOS's encoder throws for this (WebMOpusEncodingError.noSamples), word for word.
                    showFailureToast("Failed to send voice message", IllegalStateException("No audio samples to encode."))
                    return@launch
                }
                sendVoiceNote(channelName, parts)
            }
            return
        }
        _voiceRecordingState.value = VoiceRecordingState()

        val file = voiceRecorderService.stopRecording()
        if (file == null || elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            file?.delete()
            return
        }
        sendVoiceMessage(channelName, file, viaNextcloud)
    }

    /**
     * A room voice note's parts (iOS 1b15521 `stopAndSendRecording`): longer than one part, tagged
     * parts, one transaction each (2:50 at most) - no reply, and the reply being written is
     * dropped once they show; one part, an ordinary voice message as before.
     */
    private suspend fun sendVoiceNote(channelName: String, parts: List<ByteArray>) {
        if (parts.size == 1) {
            try {
                sendOnChainVoice(channelName, parts[0])
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error preparing voice message", e)
                showFailureToast("Failed to send voice message", e)
            }
            return
        }
        try {
            broadcastRepository.sendVoiceParts(channelName, parts) {
                // A part is never a reply, and the next message isn't one either.
                _replyingTo.value = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("BroadcastViewModel", "Error sending voice note parts", e)
            showFailureToast("Failed to send voice message", e)
        }
    }

    /**
     * Stops the on-chain recording and encodes it as the parts it goes as (iOS 1b15521
     * `stopAndEncodeParts`, [VoiceNoteCodec.encodeParts] at the room's 14.5 s parts): each a
     * complete WebM-Opus file, one when the note is that short. A device without the platform Opus
     * encoder recorded one part's length as a single file instead. Empty when nothing usable was
     * captured; a failed encode throws.
     */
    private suspend fun encodeRecordedNote(): List<ByteArray> =
        when (val recording = voiceRecorderService.stop()) {
            null -> emptyList()
            is VoiceRecorderService.Recording.Pcm -> withContext(Dispatchers.Default) {
                com.kachat.app.services.VoiceNoteCodec.encodeParts(recording.samples, com.kachat.app.util.PublicChatVoiceNotePolicy.PUBLIC_CHAT)
            }
            is VoiceRecorderService.Recording.SingleFile -> withContext(Dispatchers.IO) {
                try {
                    listOf(recording.file.readBytes())
                } finally {
                    recording.file.delete()
                }
            }
        }

    /** One voice note on chain, as iOS `sendPublicChatAudio` writes it: [BroadcastMediaFileEnvelope]'s
     *  field order, "voice.webm", the file base64'd in a data URL. */
    private fun sendOnChainVoice(channelName: String, bytes: ByteArray) {
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val json = com.kachat.app.util.BroadcastMediaFileEnvelope.json(
            name = "voice.webm",
            size = bytes.size,
            mimeType = "audio/webm",
            dataUrlContent = "data:audio/webm;base64,$base64",
        )
        sendBroadcast(channelName, json, isVoice = true, failurePrefix = "Failed to send voice message")
    }

    /** Retry on a long voice note's bubble: only its failed parts, in order (iOS 1b15521
     *  `retryPublicChatVoiceParts`). Not cancelled by Back (see [BroadcastRepository.sendScope]). */
    fun retryVoiceParts(failedParts: List<BroadcastMessageEntity>) {
        if (failedParts.isEmpty()) return
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.retryVoiceParts(failedParts)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error retrying voice note parts", e)
            }
        }
    }

    /** Leaving the room mid-recording: nothing is sent, the file goes, and the audio is given
     *  back (iOS b78fdb0 `onDisappear`). One being encoded is already on its way. */
    fun cancelVoiceRecordingOnLeave() {
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) cancelVoiceRecording()
    }

    fun cancelVoiceRecording() {
        // Being encoded, it is already on its way.
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.ENCODING) return
        _nextcloudVoiceRequested.value = false
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()
        voiceRecorderService.cancelRecording()
    }

    /** Recorded "via Nextcloud" (the mic's on-chain-or-Nextcloud step, iOS 8b13460) and still
     *  connected, the recorded file (exactly as captured — no re-encode, so the full relaxed-cap length ships byte-for-byte) uploads to
     *  the server and the broadcast is just the public share link, sent as plain text through the
     *  normal [sendBroadcast] pipeline; any upload/share failure falls back to the embedded
     *  on-chain audio envelope below, with a toast so the sender knows. Mirrors ChatViewModel's
     *  1:1/group Nextcloud voice gates exactly. */
    private fun sendVoiceMessage(channelName: String, file: java.io.File, viaNextcloud: Boolean) {
        // The upload and the send both outlive the room (see [BroadcastRepository.sendScope]).
        broadcastRepository.sendScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (viaNextcloud) {
                    try {
                        val extension = file.extension.ifEmpty { "webm" }
                        val mimeType = when (extension.lowercase()) {
                            "webm" -> "audio/webm"
                            "ogg", "opus" -> "audio/ogg"
                            "m4a", "mp4" -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        // The recorder already names files voice_<timestamp>.<ext> — keep that name.
                        val url = nextcloudService.uploadMediaAndShare(bytes, file.name, mimeType)
                        // A voice note: never dropped because a text send is still going out.
                        sendBroadcast(channelName, url, isVoice = true, failurePrefix = "Failed to send voice message")
                        // Warm the preview cache in the background so the sender's own bubble
                        // renders the audio attachment card immediately instead of after its
                        // LinkPreviewCard's lazy fetch round-trips.
                        launch { LinkPreviewService.fetchPreview(url) }
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("BroadcastViewModel", "Nextcloud broadcast voice upload failed, falling back to on-chain send", e)
                        // The public room's toast: iOS PublicChatChannelView's, success-styled and up for 3 s.
                        if (!roomClosed) com.kachat.app.ui.screens.IosToasts.show("Nextcloud upload failed — sending on-chain instead", durationMs = com.kachat.app.ui.screens.IosToastDuration.LONG)
                    }
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                // iOS `sendPublicChatAudio`'s bytes: MediaFileEnvelope's field order, "voice.webm",
                // no HTML escaping of the base64's "=".
                val json = com.kachat.app.util.BroadcastMediaFileEnvelope.json(
                    name = "voice.webm",
                    size = bytes.size,
                    mimeType = "audio/webm",
                    dataUrlContent = "data:audio/webm;base64,$base64",
                )
                sendBroadcast(channelName, json, isVoice = true, failurePrefix = "Failed to send voice message")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error preparing voice message", e)
                showFailureToast("Failed to send voice message", e)
            } finally {
                file.delete()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reactions — same MessageReaction JSON codec and pill/picker UI as 1:1/group chats, sent as
    // a normal plain-text broadcast through the existing sendBroadcast pipeline (never wrapped in
    // a reply envelope). Aggregation/persistence is derived from the cached broadcast message
    // rows themselves — see BroadcastRepository.getReactions.
    // -------------------------------------------------------------------------

    /** Reactions in [channelName], aggregated one-per-(target, reactor) — group by `targetTxId` at the call site, same as group chat's screen does. */
    fun getReactions(channelName: String) = broadcastRepository.getReactions(channelName)

    /**
     * Reacts to [targetTxId] with [emoji] ("add"), or removes this wallet's existing reaction on
     * it ("remove"). The reaction is just a broadcast whose content is the [MessageReaction]
     * JSON — the optimistic pending row sendBroadcast inserts is what makes the pill appear
     * immediately (it aggregates like any other cached reaction row), mirroring group chat's
     * optimistic apply.
     */
    fun sendReaction(channelName: String, targetTxId: String, emoji: String, action: String) {
        // A still-pending target has no real txId yet — reacting to it would put a useless
        // "pending_<uuid>" target on-chain that no other client could ever resolve.
        if (targetTxId.startsWith("pending_")) return
        // Not cancelled by Back (see [BroadcastRepository.sendScope]).
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.sendBroadcast(channelName, MessageReaction.encode(targetTxId, emoji, action))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The pending reaction row flips to "failed" inside sendBroadcast — the pill shows
                // the red error icon and a Retry appears under the message, same as groups. One
                // that may have landed is left pending, not flagged (iOS e5a9b11), and says
                // nothing, as iOS's `try?` does.
                Log.e("BroadcastViewModel", "Error sending broadcast reaction", e)
            }
        }
    }

    /** Retries a reaction whose send previously failed — re-attempts the stored reaction message row (see the pill's error icon + Retry, matching groups). */
    fun retryReaction(reaction: ReactionEntity) {
        broadcastRepository.sendScope.launch {
            try {
                broadcastRepository.retryReactionMessage(reaction.reactionTxId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error retrying broadcast reaction", e)
            }
        }
    }

    companion object {
        /** Rough on-chain payload size of a Nextcloud share link — the message is just the URL.
         *  Same figure as ChatViewModel's identically-named constant. */
        private const val NEXTCLOUD_LINK_PREVIEW_BYTES = 100

        /** What a send that may have landed says (iOS 953b255 `SendOutcomeUnknownError`). */
        private const val SEND_OUTCOME_UNKNOWN_MESSAGE =
            "This may have gone through, but the network didn't confirm it. Check your balance before sending again."

        /** How often an open room prunes expired rows (iOS PublicChatChannelView: 300 s). */
        private const val ROOM_PRUNE_INTERVAL_MS = 5L * 60 * 1000

        /** How long a closed room keeps its widened window and rows (iOS d089011f: 120 s). */
        private const val ROOM_WINDOW_TRIM_DELAY_MS = 120_000L

        /** The quote a reply carries, and the reply and edit banners' line: never raw JSON
         *  (iOS `MessageReplyCodec.previewText`, which a reply is built with). */
        fun replyPreviewText(content: String): String {
            val unwrapped = MessageReply.parseOrNull(content)?.text ?: content
            MessageReaction.parseOrNull(unwrapped)?.let { return "Reacted ${it.emoji}" }
            val mime = com.kachat.app.util.InlineMediaSniff.mimeType(unwrapped)?.lowercase()
            if (mime != null && mime.startsWith("audio/")) return "🎤 Audio message"
            if (mime != null && mime.startsWith("image/")) return "📷 Photo"
            com.kachat.app.util.ChessMessage.parseOrNull(unwrapped)?.let { chess ->
                val minutes = (chess as? com.kachat.app.util.ChessEnvelope.Invite)?.content?.tcMinutes
                if (chess is com.kachat.app.util.ChessEnvelope.Invite && minutes != null) {
                    return "♟️ Chess - $minutes | ${chess.content.tcIncSeconds ?: 0}"
                }
                return "♟️ Chess"
            }
            // Any other media envelope - never raw JSON in a preview.
            if (mime != null) return if (mime.startsWith("video/")) "🎬 Video" else "📎 File"
            if (FILE_ENVELOPE.containsMatchIn(unwrapped.take(2048).trim().takeIf { it.startsWith("{") }.orEmpty())) return "📎 File"
            // A Nextcloud share is the address of the sender's own server: its caption, or a label.
            com.kachat.app.util.NextcloudShareSniff.shareUrl(unwrapped)?.let { url ->
                return unwrapped.replace(url, "").trim().ifEmpty { "📎 Shared a file" }
            }
            return unwrapped
        }

        /** iOS `InlineMediaSniff.isFileEnvelope`: a `{type:"file"}` envelope in the head. */
        private val FILE_ENVELOPE = Regex("\"type\"\\s*:\\s*\"file\"")
    }
}
