package com.kachat.app.repository

import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.models.ReactionEntity
import com.kachat.app.services.NetworkService
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the chess arena's indexer is answering (iOS da59726 IndexerHealth). It only counts as down
 * after several failures in a row, so one timeout never moves the arena off it; failures under 5 s
 * apart (the fast poll, the room's poll and a repage failing together) count as one. While it's
 * down, one request a minute is let through to find out when it's back, and it is back after two
 * answers in a row (the second asked for right away) or one answer after a long outage - so one
 * lucky answer from a flaky indexer doesn't bring it back, and page the arena in again, only to
 * fail the next minute (iOS 077d777).
 */
internal class ArenaIndexerHealth(private val clock: () -> Long = System::currentTimeMillis) {
    private var downSince: Long? = null
    private var failuresInARow = 0
    private var lastFailureAt = Long.MIN_VALUE / 2
    private var lastCountedFailureAt = Long.MIN_VALUE / 2
    private var retryAt = Long.MIN_VALUE / 2
    private var answersInARow = 0

    @get:Synchronized
    val isDown: Boolean get() = downSince != null

    /** The next read goes through, down or not (a chess screen just opened). */
    @Synchronized
    fun allowProbeNow() {
        retryAt = Long.MIN_VALUE / 2
    }

    @Synchronized
    fun reset() {
        downSince = null
        failuresInARow = 0
        answersInARow = 0
        lastCountedFailureAt = Long.MIN_VALUE / 2
        retryAt = Long.MIN_VALUE / 2
    }

    @Synchronized
    fun shouldSkipIndexer(): Boolean {
        if (downSince == null) return false
        val now = clock()
        // Moved here rather than a "probing" flag, which could stick if the caller never asks.
        if (now < retryAt) return true
        retryAt = now + RETRY_INTERVAL_MS
        return false
    }

    @Synchronized
    fun recordFailure() {
        val now = clock()
        answersInARow = 0
        if (now - lastFailureAt > FAILURE_WINDOW_MS) failuresInARow = 0
        lastFailureAt = now
        // Part of a failure counted under 5 s ago: the same one.
        if (now - lastCountedFailureAt < FAILURE_MERGE_WINDOW_MS) return
        lastCountedFailureAt = now
        failuresInARow += 1
        if (downSince == null && failuresInARow >= FAILURES_TO_GO_DOWN) {
            downSince = now
            retryAt = now + RETRY_INTERVAL_MS
        }
    }

    @Synchronized
    fun recordAnswer() {
        failuresInARow = 0
        val since = downSince
        if (since == null) {
            answersInARow = 0
            return
        }
        answersInARow += 1
        if (answersInARow < ANSWERS_TO_COME_BACK && clock() - since < LONG_OUTAGE_MS) {
            // One answer: the next read goes through at once to confirm it.
            retryAt = Long.MIN_VALUE / 2
            return
        }
        downSince = null
        answersInARow = 0
    }

    private companion object {
        const val FAILURES_TO_GO_DOWN = 3
        /** Failures further apart than this aren't a run. */
        const val FAILURE_WINDOW_MS = 120_000L
        /** Failures closer together than this are one. */
        const val FAILURE_MERGE_WINDOW_MS = 5_000L
        const val RETRY_INTERVAL_MS = 60_000L
        const val ANSWERS_TO_COME_BACK = 2
        /** Down at least this long, one answer is enough. */
        const val LONG_OUTAGE_MS = 5L * 60_000
    }
}

/** A send asked for by one account, when another is signed in: nothing went out (iOS 077d777
 *  `PublicChatService.SenderChangedError`). */
class SenderChangedException : IllegalStateException("The account changed, so nothing was sent.")

/**
 * No coin to spend now, but one that waiting frees up: every coin is being mined or was spent by
 * a send still in flight (iOS 33095ac `sendPublicChatInternal`, `isNoConfirmedInputsError`). A
 * room message stays Sending and goes again by itself; a reaction or an edit fails at once.
 */
class NoCoinFreeYetException(message: String = "No spendable funds available yet") : IllegalStateException(message)

/**
 * Broadcast rooms — public, unencrypted, one-to-many channels identified by a plaintext channel
 * name (see MessageProtocol's bcast payload functions). Kept separate from [ChatRepository]:
 * channels/messages here have no contact concept, no per-account message scoping (the message
 * cache is a raw capture of public chain data, shared across whichever account is active), and no
 * encryption, so folding this into ChatRepository would mostly add conditionals rather than reuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BroadcastRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val networkService: NetworkService,
    /** The rooms' indexer settings and KaPost's block list (iOS `AppSettings.publicChatIndexerURL`). */
    private val settings: com.kachat.app.services.publicchats.PublicChatSettings,
    /** The public chat indexer clients: the message indexer's, and each room's own. */
    private val broadcastIndexers: com.kachat.app.services.publicchats.BroadcastIndexerClients,
    // Public-chat authorship (audit XP-012): the indexer's senderAddress is only its guess, so
    // every row it serves is verified (input 0's address == output 0's) before it is stored.
    private val senderVerifier: com.kachat.app.services.BroadcastSenderVerifier,
    // Whether the phone has a connection at all: offline says nothing about an indexer.
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    // The unconfirmed arena-row check asks the nodes' mempools and coins (iOS 4922804).
    private val nodePoolManager: com.kachat.app.services.NodePoolManager,
    // A long voice note's balance check reads the coins a room message can spend (iOS 1b15521).
    private val walletEngine: com.kachat.app.services.KaspaWalletEngine,
    // Messages their sender deleted for everyone, per account (iOS 2026-11-25 `recordDelete`).
    private val roomDeletes: com.kachat.app.services.publicchats.RoomDeleteStore,
    // Which rooms K joined for the account: posting in one makes it the person's own (iOS 65f8a765).
    private val readState: com.kachat.app.services.BroadcastReadStateStore,
    // A new message that @'s you: a row in Profile's bell (iOS d089011f `insertRoomMessages`).
    private val roomMentionBell: dagger.Lazy<com.kachat.app.services.RoomMentionBell>,
) {
    /**
     * Where a room's sends run - messages, edits, reactions, retries and voice notes - so they
     * finish after the room is closed, as iOS runs them on the shared PublicChatService. A room's
     * view model dies on Back, and a send cancelled with it would be marked failed even when the
     * transaction already went out, and a Retry would then post and pay twice.
     */
    val sendScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate +
            com.kachat.app.util.StartupSafety.logOnlyHandler("BroadcastRepository")
    )

    /** The active account's address - the scanner refreshes the indexer's tracked rooms on a switch. */
    val activeAddressFlow: kotlinx.coroutines.flow.StateFlow<String?> get() = walletManager.activeAddressFlow

    /** Channels joined by whichever account is currently active — re-emits automatically on account switch. */
    fun getJoinedChannels(): Flow<List<BroadcastChannelEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getJoinedChannels(address)
        }
    }

    /** "Joining" and "creating" a channel are the same action — there's no ownership/membership protocol. */
    suspend fun joinChannel(rawName: String) {
        val name = MessageProtocol.normalizeChannelName(rawName)
        require(MessageProtocol.isValidChannelName(name)) { "Invalid channel name" }
        database.broadcastDao().joinChannel(
            BroadcastChannelEntity(channelName = name, walletAddress = walletManager.getAddress())
        )
    }

    /** Removes the channel from the joined list AND permanently deletes every cached message for it — the UI must confirm this with the user first, since it's destructive and can't be undone (rejoining later starts with no history). Featured rooms can't be left (matches iOS). */
    suspend fun leaveChannel(channelName: String) {
        // Curated rooms (Popular and the language rooms alike) are permanent fixtures of the
        // list screen, so no UI offers Leave; guard against stray paths. A room the indexer
        // tracks that you added yourself stays one you can leave (iOS 21c480d).
        if (channelName in FeaturedBroadcastChannels.CURATED_NAMES) return
        database.broadcastDao().leaveChannel(channelName, walletManager.getAddress())
        // The message table is shared by every account on this device (iOS keeps one store per
        // account): the history goes only once no other account still has the room.
        if (database.broadcastDao().countJoinedAccounts(channelName) == 0) {
            // Its messages, reactions and edits are all rows of this table, so they go together
            // (iOS 369794e `leaveChannel`). Hidden senders stay, in case the room is joined again.
            database.broadcastDao().deleteMessagesForChannel(channelName)
        }
        // A failed edit's text waits in memory for Retry, and what was fetched and applied is
        // forgotten, so a rejoin this session pages the room in again (iOS 369794e).
        _failedEdits.value = _failedEdits.value - channelName
        deepBackfilledChannels.remove(channelName)
        deepBackfillResume.remove(channelName)
        deepBackfillFloor.remove(channelName)
        indexerNewestSeen.remove(channelName)
        deepUnverifiedRetries.remove(channelName)
        failedBackfillResumes.remove(channelName)
        indexerCoveredUntil.remove(channelName)
    }

    /**
     * Every default room is in Public Chats from the start (iOS KaPosts 865c9ab
     * `ensureDefaultChannelsJoined`): #kaspa, #report-bugs, the language rooms and the rooms the
     * indexer lists (iOS 961fc05a), auto-joined with the FIXED retention their indexer backfill
     * serves - except any in [hidden], switched off with the customize button. They join with
     * notifications off, which is what keeps the language rooms cheap: the background scan and
     * the push registration only cover rooms whose bell is on. Idempotent - joinChannel inserts
     * with IGNORE, so an already-joined row (and its bell/listen toggles) is left completely
     * untouched. [com.kachat.app.services.publicchats.PublicChatDefaultRooms] runs it.
     */
    suspend fun ensureDefaultChannelsJoined(
        hidden: Set<String>,
        names: List<String> = FeaturedBroadcastChannels.CURATED_NAMES,
    ) {
        for (name in names) {
            if (name in hidden) continue
            joinDefaultChannel(name)
        }
    }

    /** Joins one default room as [ensureDefaultChannelsJoined] does; an already-joined row is
     *  left untouched. */
    suspend fun joinDefaultChannel(name: String) {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return }
        database.broadcastDao().joinChannel(
            BroadcastChannelEntity(
                channelName = name,
                walletAddress = address,
                retentionMillis = BroadcastRetention.MAX_MILLIS,
            )
        )
    }

    /** The open account's joined rooms, read once (none signed out). */
    suspend fun joinedChannelsNow(): List<BroadcastChannelEntity> {
        val address = myAddress() ?: return emptyList()
        return database.broadcastDao().getJoinedChannels(address).first()
    }

    /**
     * Per-channel opt-in to background scanning — the user chooses exactly which channels stay
     * live while the app is backgrounded, rather than one all-or-nothing setting. Turning this
     * off also turns notifications off for the channel (a channel that isn't being scanned has
     * no way to know about new messages to notify about).
     */
    suspend fun setAlwaysListen(channelName: String, alwaysListen: Boolean) {
        if (alwaysListen) {
            database.broadcastDao().setAlwaysListen(channelName, walletManager.getAddress(), true)
        } else {
            database.broadcastDao().disableAlwaysListenAndNotify(channelName, walletManager.getAddress())
        }
    }

    /** Per-channel opt-in to a notification for new messages — enabling this also turns always-listen on for the channel, since notifications depend on it actually being scanned. */
    suspend fun setNotifyEnabled(channelName: String, notifyEnabled: Boolean) {
        val address = walletManager.getAddress()
        if (notifyEnabled) {
            database.broadcastDao().enableNotifyAndAlwaysListen(channelName, address)
        } else {
            database.broadcastDao().disableNotify(channelName, address)
        }
    }

    /** Per-channel override of local message retention, set via the settings icon next to a channel — clamped to [1 second, BroadcastRetention.MAX_MILLIS] so the UI's 3-day cap can't be bypassed by a bad input. */
    suspend fun setRetentionMillis(channelName: String, retentionMillis: Long) {
        // Rooms the indexer tracks have a FIXED 30-day retention (the indexer serves 30 days of
        // history and the prune keeps all of it) - no per-room override.
        if (FeaturedBroadcastChannels.isIndexerTracked(channelName)) return
        val clamped = retentionMillis.coerceIn(1_000L, BroadcastRetention.MAX_MILLIS)
        database.broadcastDao().setRetentionMillis(channelName, walletManager.getAddress(), clamped)
    }

    /** The active account's always-listen channel names — non-empty drives whether background scanning runs at all, and membership decides which channels' messages actually get cached while it's running. */
    fun getAlwaysListenChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getAlwaysListenChannelNames(address).map { it.toSet() }
        }
    }

    /** The active account's notify-enabled channel names — drives which channels' new messages fire a system notification. */
    fun getNotifyEnabledChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getNotifyEnabledChannelNames(address).map { it.toSet() }
        }
    }

    /**
     * What the Public Chats list shows for one room: its newest message and how many messages
     * from other people are newer than the read marker. [lastMessage] is null for a room with
     * nothing in it yet.
     */
    data class RoomSummary(
        val lastMessage: BroadcastMessageEntity?,
        val unreadCount: Int,
        /** The newest edit's text of [lastMessage], when its sender edited it: the row previews
         *  with it, as in the room (iOS 961fc05a `loadLastMessageEdits`, audit 2026-11-25 C10). */
        val lastEditText: String? = null,
    )

    /**
     * Newest message and unread count for [channelName], respecting the senders hidden in this
     * room and never counting reactions - they are not messages anyone reads. [lastReadMs] null
     * means the room has no marker yet, so nothing in it counts as unread.
     */
    fun roomSummary(channelName: String, myAddress: String?, lastReadMs: Long?): Flow<RoomSummary> {
        // Every write to the room table re-runs each joined room's summary, so it is read in SQL
        // (audit H4): the newest plain row and the plain unread count there, and only the rows
        // that may be reactions, edits or voice-note parts come back to be parsed.
        val dao = database.broadcastDao()
        val network = networkSenderPattern()
        // No account: nothing is "mine" (a null would make every SQL comparison false).
        val me = myAddress ?: ""
        return getHiddenSenders()
            .map { hiddenAddressesIn(channelName, it).toList() }
            .distinctUntilChanged()
            .flatMapLatest { hidden ->
                val lastMessage = dao.summaryNewestPlain(channelName, network, me, hidden)
                    .distinctUntilChanged()
                    .flatMapLatest { plain ->
                        dao.summaryMarkedAfter(channelName, network, me, hidden, plain?.blockTimestamp ?: Long.MIN_VALUE, SUMMARY_MARKED_LIMIT)
                            .map { marked -> marked.firstOrNull { isReadableContent(it.content) } ?: plain }
                    }
                val unread = if (lastReadMs == null) flowOf(0) else combine(
                    dao.summaryUnreadPlainCount(channelName, network, me, hidden, lastReadMs),
                    dao.summaryUnreadMarked(channelName, network, me, hidden, lastReadMs),
                ) { plainCount, marked ->
                    // A long voice note's parts count once (iOS 1b15521 `unreadCount`).
                    val seenNotes = HashSet<String>()
                    plainCount + marked.count {
                        isReadableContent(it.content) &&
                            (com.kachat.app.util.BroadcastVoiceParts.noteKey(it)?.let { note -> seenNotes.add(note) } ?: true)
                    }
                }
                // Its newest edit, read with it, so a room never opened still previews the edit.
                val lastWithEdit = lastMessage.distinctUntilChanged().flatMapLatest { last ->
                    if (last == null || !com.kachat.app.util.MessageEdit.isEditable(last.content)) flowOf(last to null)
                    else dao.summaryEditsOf(channelName, last.senderAddress, last.id).map { rows -> last to newestEditText(rows, last.id) }
                }
                combine(lastWithEdit, unread) { (last, edit), count -> RoomSummary(lastMessage = last, unreadCount = count, lastEditText = edit) }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /** The text of the newest edit among [rows] (newest first) that names [targetTxId]. */
    private fun newestEditText(rows: List<BroadcastMessageEntity>, targetTxId: String): String? =
        rows.firstNotNullOfOrNull { row ->
            com.kachat.app.util.MessageEdit.parseOrNull(row.content)?.takeIf { it.targetTxId == targetTxId }?.text
        }

    /** The SQL LIKE pattern for the running network's senders, "kaspa:%" or "kaspatest:%": the
     *  room table holds both networks' rows, and every room read keeps to the running one
     *  (audit M2; iOS keeps one store per wallet address, so per network). */
    fun networkSenderPattern(): String = "${com.kachat.app.util.KaspaNetwork.hrp}:%"

    /** A room's newest [ROOM_WINDOW_SIZE] rows (widened by "Load earlier messages"), and whether
     *  the read filled the window — which is the only way to know there is more behind it. */
    data class RoomWindow(
        val messages: List<BroadcastMessageEntity>,
        val hasMore: Boolean,
        /** Long voice notes in the window by their lowest-index part's id (iOS 1b15521
         *  `voiceNotesByChannel`): one bubble each, worked out with the window so the room doesn't
         *  sniff every row on every pass. Their other parts are left out of [messages]. */
        val voiceNotes: Map<String, com.kachat.app.util.BroadcastVoiceNote> = emptyMap(),
        /** Each hidden part's anchor id: a reply or reaction aimed at a hidden part (another
         *  client that shows parts one by one) goes to the note's bubble (iOS 369794e). */
        val anchorIdByPartId: Map<String, String> = emptyMap(),
        /** The window's messages by id (not the arena's), long voice notes' hidden parts
         *  included: what a reply quotes is drawn from the message itself, never from the sender
         *  and text the reply claims (iOS audit 2026-11-21 #18 `message(id:inChannel:)`). */
        val byId: Map<String, BroadcastMessageEntity> = emptyMap(),
    )

    /** Never includes messages from a sender hidden IN THIS ROOM (or via a legacy every-room hide) — including ones already cached from before the hide (see BroadcastScanningService for the future-side enforcement). Reaction messages (see [getReactions]) never render as a message row, so they're filtered out here too. */
    fun getMessages(channelName: String): Flow<List<BroadcastMessageEntity>> =
        getMessageWindow(channelName, limit = null).map { it.messages }

    /**
     * The room read as a window. A curated room keeps thirty days of history, and the screen used
     * to load every row of it on open and again on every change (iOS 02a4f58). [limit] null reads
     * the whole room, which is what the summary paths and the chess arena's reducer need.
     *
     * `hasMore` is measured on the RAW rows, before reactions and edits are filtered out: those
     * are rows in the same table, so a window of N raw rows renders fewer than N bubbles, and
     * counting the rendered ones would call a full window exhausted.
     */
    fun getMessageWindow(channelName: String, limit: Int?): Flow<RoomWindow> {
        val network = networkSenderPattern()
        val rows = if (limit == null) {
            database.broadcastDao().getMessagesForChannel(channelName, network)
        } else {
            // DESC + LIMIT takes the NEWEST n off the (channelName, blockTimestamp) index; the
            // screen wants them oldest-first, as the unwindowed query already returns them.
            database.broadcastDao().getLatestMessagesForChannel(channelName, network, limit).map { it.asReversed() }
        }
        return combine(rows, getHiddenSenders(), walletManager.activeAddressFlow) { messages, hidden, me ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            // And never two rows with one id: the room's LazyColumn is keyed by it, and a
            // duplicate key crashes Compose (iOS d0b819f - there it left a blank gap). The table's
            // primary key is the id, so this is a guard, not a cleanup.
            val seen = HashSet<String>(messages.size)
            val fresh = messages.filter {
                seen.add(it.id) &&
                    it.senderAddress !in hiddenHere && !isOtherAccountsUnsent(it, me) && isReadableContent(it.content)
            }
            // A long voice note is one bubble at its lowest-index part; its other parts aren't drawn.
            val voiceNotes = com.kachat.app.util.BroadcastVoiceParts.group(fresh)
            RoomWindow(
                messages = if (voiceNotes.hiddenIds.isEmpty()) fresh else fresh.filter { it.id !in voiceNotes.hiddenIds },
                hasMore = limit != null && messages.size >= limit,
                voiceNotes = voiceNotes.notesByAnchorId,
                anchorIdByPartId = voiceNotes.anchorIdByPartId,
                byId = if (channelName in com.kachat.app.models.FeaturedBroadcastChannels.SERVICE_CHANNELS) emptyMap()
                else fresh.associateBy { it.id },
            )
        // The reaction/edit sniff runs over every row on every change - through a room's first
        // backfill that is once per page. Collected from the screen it ran on the main thread
        // (iOS 9c9e3e0, 5463ccc).
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Reactions in [channelName], derived from the cached broadcast message rows themselves —
     * a reaction is just a broadcast whose content is the [MessageReaction] JSON (same wire
     * format as 1:1/group chats), so the rows the block scanner / indexer backfill already
     * persist ARE the reaction storage: they survive restarts, load with the channel's history,
     * and dedupe by txId, which keeps re-processing the indexer's repeated 200-row pages
     * harmless (unlike replaying add/remove events into a separate table would be).
     *
     * Aggregation matches group chat's semantics exactly: reactor = sender address, one reaction
     * per (target, reactor) with the newest blockTime winning, and a "remove" deleting the chip —
     * except a FAILED remove, which is restored marked failed (so it isn't silently lost and the
     * Retry under the message can re-attempt it), mirroring GroupRepository.sendGroupReaction's
     * failure handling. Emitted as in-memory [ReactionEntity] values (never persisted to the
     * `reactions` table — walletAddress is left blank) purely so the existing ReactionPill UI is
     * reused as-is; `reactionTxId` carries the reaction message row's own id for [retryReactionMessage].
     */
    fun getReactions(channelName: String): Flow<List<ReactionEntity>> {
        // Only rows carrying the reaction marker are read (iOS `loadReactions` reads its own
        // store on change): the whole room, parsed, on every insert into any room was the cost.
        val rows = database.broadcastDao().getReactionRowsForChannel(channelName, networkSenderPattern()).distinctUntilChanged()
        return combine(rows, getHiddenSenders(), walletManager.activeAddressFlow) { messages, hidden, me ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            val newestPerReactor = LinkedHashMap<Pair<String, String>, Pair<BroadcastMessageEntity, com.kachat.app.util.MessageReactionContent>>()
            for (row in messages) {
                if (row.senderAddress in hiddenHere || isOtherAccountsUnsent(row, me)) continue
                val parsed = MessageReaction.parseOrNull(row.content) ?: continue
                val key = parsed.targetTxId to row.senderAddress
                val existing = newestPerReactor[key]
                // >= so a tie is broken by row order (DAO orders by blockTimestamp ASC).
                if (existing == null || row.blockTimestamp >= existing.first.blockTimestamp) {
                    newestPerReactor[key] = row to parsed
                }
            }
            newestPerReactor.values.mapNotNull { (row, parsed) ->
                if (parsed.action != "add" && row.deliveryStatus != "failed") return@mapNotNull null
                ReactionEntity(
                    targetTxId = parsed.targetTxId,
                    walletAddress = "", // in-memory value object only, never inserted into the reactions table
                    reactorAddress = row.senderAddress,
                    emoji = parsed.emoji,
                    reactionTxId = row.id,
                    blockTimestamp = row.blockTimestamp,
                    deliveryStatus = row.deliveryStatus,
                    failedAction = if (row.deliveryStatus == "failed") parsed.action else null
                )
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /**
     * The newest edit per message in [channelName], derived from the cached broadcast rows the
     * same way [getReactions] derives reactions: an edit is just a broadcast whose content is the
     * [com.kachat.app.util.MessageEdit] JSON, so the stored rows ARE the storage — they survive
     * restarts, load with the room's history and dedupe by txId.
     *
     * An edit counts only if its sender sent the message it names and that message is text; the
     * newest by block time wins. Keyed by the target's txId, ready for the screen to apply as it
     * draws each bubble.
     */
    fun getEdits(channelName: String): Flow<Map<String, BroadcastEdit>> {
        // Only rows carrying the edit marker are read (iOS `loadEdits`), then just the messages
        // they name. Room re-runs the query on any change to the table, so a target arriving
        // after its edit still lands - which is why these rows are not deduplicated (the result
        // is, below).
        val rows = database.broadcastDao().getEditRowsForChannel(channelName, networkSenderPattern())
        return combine(rows, getHiddenSenders(), walletManager.activeAddressFlow) { editRows, hidden, me ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            val edits = editRows.mapNotNull { row ->
                if (row.senderAddress in hiddenHere || isOtherAccountsUnsent(row, me)) return@mapNotNull null
                // A failed edit never reached the chain (only an older build left one, iOS ee5ec98).
                if (row.deliveryStatus == "failed") return@mapNotNull null
                com.kachat.app.util.MessageEdit.parseOrNull(row.content)?.let { row to it }
            }
            val senders = HashMap<String, BroadcastMessageEntity>(edits.size)
            for (chunk in edits.map { it.second.targetTxId }.distinct().chunked(500)) {
                for (row in database.broadcastDao().getMessagesByIds(chunk)) {
                    if (row.channelName == channelName) senders[row.id] = row
                }
            }
            val newestPerTarget = LinkedHashMap<String, BroadcastEdit>()
            for ((row, parsed) in edits) {
                val target = senders[parsed.targetTxId] ?: continue
                if (target.senderAddress != row.senderAddress) continue
                if (!com.kachat.app.util.MessageEdit.isEditable(target.content)) continue
                val existing = newestPerTarget[parsed.targetTxId]
                // >= so a tie is broken by row order (DAO orders by blockTimestamp ASC).
                if (existing == null || row.blockTimestamp >= existing.blockTimestamp) {
                    newestPerTarget[parsed.targetTxId] = BroadcastEdit(
                        targetTxId = parsed.targetTxId,
                        text = parsed.text,
                        editMessageId = row.id,
                        blockTimestamp = row.blockTimestamp,
                        deliveryStatus = row.deliveryStatus
                    )
                }
            }
            newestPerTarget
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /** One message's newest edit in a room — see [getEdits]. */
    data class BroadcastEdit(
        val targetTxId: String,
        val text: String,
        val editMessageId: String,
        val blockTimestamp: Long,
        val deliveryStatus: String
    )

    /** Re-attempts a reaction message whose send previously failed — [reactionMessageId] is the reaction's own broadcast message row id (see [getReactions]'s `reactionTxId`). */
    suspend fun retryReactionMessage(reactionMessageId: String?) {
        val row = reactionMessageId?.let { database.broadcastDao().getMessage(it) } ?: return
        retryBroadcast(row)
    }

    /** The rooms' own hides, for a room's Hidden Users list (per-room since 4.0; channelName "" =
     *  every room). Blocked accounts are left out: they are undone on the Blocked screen. */
    fun getRoomHiddenSenders(): Flow<List<HiddenBroadcastSenderEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getHiddenSenders(address)
        }
    }

    /**
     * Everyone whose messages and pushes are left out: the rooms' hides, plus accounts blocked in
     * KaPost, as every-room rows (iOS 9e22075 `PublicChatStore.blockedSenders`). Not stored rows:
     * the block list owns them, per account. Re-emits on account switch.
     */
    fun getHiddenSenders(): Flow<List<HiddenBroadcastSenderEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else combine(
                database.broadcastDao().getHiddenSenders(address),
                settings.kapostsBlocked(address),
            ) { rows, blocked ->
                rows + blocked.map { HiddenBroadcastSenderEntity(it, address, "") }
            }
        }
    }

    /** Blocks an account from a room's sender sheet: the same list as a post's Block, which also
     *  supersedes a mute. */
    suspend fun blockSender(senderAddress: String) {
        val wallet = walletManager.activeAddressFlow.value ?: return
        settings.setKapostsBlocked(wallet, settings.kapostsBlocked(wallet).first() + senderAddress)
        settings.setKapostsMuted(wallet, settings.kapostsMuted(wallet).first() - senderAddress)
    }

    companion object {
        /** How many of a room's newest reaction- or edit-looking rows the Public Chats list parses
         *  to see whether one of them, newer than the newest plain message, is a message itself. */
        private const val SUMMARY_MARKED_LIMIT = 50

        /** How much of a room the thread screen reads at once, and how much each "Load earlier
         *  messages" adds. iOS's `roomWindowSize`. */
        const val ROOM_WINDOW_SIZE = 400

        /** More rows than any indexer page is asked for: the rest of an answer is ignored (iOS
         *  `PublicChatIndexerClient.maxRowsPerPage`). */
        private const val MAX_INDEXER_ROWS_PER_PAGE = 500
        /** One indexer row's content, in UTF-8 bytes: twice what a transaction's payload can
         *  carry (iOS `maxContentBytes`). */
        private const val MAX_INDEXER_CONTENT_BYTES = 200_000

        /** See [wellFormedPage] (iOS `PublicChatIndexerClient.isWellFormed`). */
        internal fun isWellFormedIndexerRow(
            row: com.kachat.app.services.BroadcastHistoryRow,
            channel: String,
            addressPrefix: String,
            now: Long,
        ): Boolean {
            val txId = row.txId ?: return false
            if (txId.length != 64 || !txId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
            val sender = row.senderAddress ?: return false
            if (!sender.startsWith(addressPrefix) || !com.kachat.app.util.KaspaAddress.isValid(sender)) return false
            val content = row.content ?: return false
            val bytes = utf8Length(content)
            if (bytes <= 0 || bytes > MAX_INDEXER_CONTENT_BYTES) return false
            // A day of clock skew either way is allowed; past that the time is nonsense.
            val blockTime = row.blockTime ?: return false
            if (blockTime <= 0 || blockTime > now + 24L * 3600 * 1000) return false
            val rowChannel = row.channel
            if (!rowChannel.isNullOrEmpty() && com.kachat.app.util.MessageProtocol.normalizeChannelName(rowChannel) != channel) return false
            return true
        }

        /** [text]'s length in UTF-8 bytes, without encoding it. */
        private fun utf8Length(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return bytes
        }

        /** Failures in a row that confirm an indexer is down, and how close they must follow one
         *  another (iOS ee5ec98 `noteIndexerResult`: three inside two minutes). */
        private const val INDEXER_DOWN_FAILURES = 3
        private const val INDEXER_FAILURE_WINDOW_MS = 120_000L

        /** A handoff fails every open room's poll at the same moment, and that is one failure. */
        private const val INDEXER_FAILURE_COALESCE_MS = 5_000L

        /** How far before an outage a room's re-read starts (iOS ee5ec98: ten minutes). */
        private const val OUTAGE_REWIND_MS = 600_000L

        /** What a long voice note's balance check leaves over, iOS's `dustThreshold` (0.2 KAS,
         *  iOS 1b15521 `ensureSufficientBalanceForVoiceParts`). */
        private const val VOICE_NOTE_DUST_THRESHOLD_SOMPI = 20_000_000L

        /** The indexer [channelName] reads from: its own override, or the app-wide one. Blank when
         *  neither is set (iOS `indexerBaseURL(forChannel:)`). An http:// override an older build
         *  saved is ignored, as [roomIndexerOverride] ignores it. */
        fun indexerBaseUrl(channelName: String, appWide: String, overrides: Map<String, String>): String {
            val own = overrides[channelName.trim().lowercase()]
                ?.takeUnless { isCleartextHttp(it) }?.trim().orEmpty()
            return own.ifEmpty { appWide.trim() }
        }

        /** What a room indexer typed as http:// is refused with (iOS SettingsView's words). */
        const val HTTPS_REQUIRED = "Use https. Unencrypted connections are not supported."

        /** True for an http:// address, which no room indexer is ever asked over. */
        fun isCleartextHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

        /**
         * A room indexer as typed, by the app's rule for every server address
         * (`ConnectionViewModel.httpsUrlOrNull`): trimmed, without a trailing slash; null for
         * http://, and a bare host gets https://. Blank stays blank.
         */
        fun httpsUrlOrNull(value: String): String? {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            if (isCleartextHttp(trimmed)) return null
            return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed else "https://$trimmed"
        }

        /** How often the app-wide indexer is asked which rooms it tracks (iOS: hourly). */
        private const val TRACKED_ROOMS_CHECK_INTERVAL_MS = 60L * 60 * 1000
        /** How long a room push's banner waits for its message at most (iOS 961fc05a: 4 s). */
        const val PUSH_BANNER_FETCH_TIMEOUT_MS = 4_000L

        /** Deep backfill re-runs while some rows' senders are still unverified (XP-012). */
        private const val DEEP_UNVERIFIED_RETRIES = 3

        /** Launches [reverifyCachedSenders] may wait for the REST API before giving up (XP-012). */
        const val REVERIFY_MAX_ATTEMPTS = 3

        /** Cached rows checked per pass step - one REST search request. */
        private const val REVERIFY_CHUNK = 100

        /** How far before a leftover pending row its delivered copy may sit: the row carries this
         *  phone's clock, the copy the chain's (iOS e5a9b11, 300_000). */
        private const val SETTLE_DELIVERED_WINDOW_MS = 5L * 60 * 1000

        // The chess arena (iOS 148a5331: KaPlay's arena merged into PublicChatService).
        private const val LOCAL_STAMPS_PREFS = "broadcast_local_stamps"
        private const val LOCAL_STAMPS_KEY = "publicChatLocalStampSentAt"
        /** Per account, like iOS's, whose store they live beside (iOS da59726). */
        private fun localStampsWalletKey(wallet: String) = "$LOCAL_STAMPS_KEY.$wallet"
        /** The polls stopped this long: what they missed may be past the newest page (iOS da59726). */
        private const val POLL_LAPSE_MS = 20_000L
        private const val REPAGE_MARGIN_MS = 60_000L
        /** A row a node says it doesn't hold still waits this long from sending, for a REST API
         *  running behind - and never more than [REST_LAG_PAST_LOOK_UP_MS] past the caller's
         *  look-up (`olderThanMs`): the arena's is 60 s, so a move that never landed is the
         *  player's to play again by about 75 s (iOS 077d777). */
        private const val REST_LAG_GRACE_MS = 120_000L
        private const val REST_LAG_PAST_LOOK_UP_MS = 15_000L
        /** See [failedBackfillResumes]. */
        private const val MAX_FAILED_BACKFILL_RESUMES = 3
        /** A row the store couldn't take yet leaves the answer not fresh only for this long from
         *  its block: a row that never verifies can't hold the arena's freshness back for good. */
        private const val UNKNOWN_ROW_FRESH_MS = 120_000L
        private const val CHAIN_PAGE_SIZE = 100
        private const val CHAIN_MAX_PAGES = 20
        private const val ARENA = FeaturedBroadcastChannels.ARENA_CHANNEL

        /** Which senders are hidden in [channelName]: room-scoped rows plus legacy every-room ("") rows. */
        fun hiddenAddressesIn(channelName: String, rows: List<HiddenBroadcastSenderEntity>): Set<String> =
            rows.filter { it.channelName.isEmpty() || it.channelName == channelName }
                .map { it.senderAddress }
                .toSet()

        /** A message row, not a reaction or an edit (both are rows of the same table). A cheap
         *  substring check first: the type marker has to be there for either to parse, and most
         *  rows - photos and voice parts with ~20 KB of base64 among them - never reach Gson. */
        /** The message a reaction or edit row names, or null when [content] is neither. */
        fun controlTarget(content: String?): String? =
            MessageReaction.parseOrNull(content)?.targetTxId
                ?: com.kachat.app.util.MessageEdit.parseOrNull(content)?.targetTxId

        fun isReadableContent(content: String): Boolean {
            if (content.contains("\"reaction\"") && MessageReaction.parseOrNull(content) != null) return false
            if (content.contains("\"edit\"") && com.kachat.app.util.MessageEdit.parseOrNull(content) != null) return false
            // A delete for everyone is never a row (iOS 2026-11-25); one stored by an older build
            // isn't shown either.
            if (content.contains("\"delete\"") && com.kachat.app.util.MessageDelete.parseOrNull(content) != null) return false
            return true
        }

        /** Another local account's send that hasn't landed (pending, failed or unconfirmed). The
         *  room table is shared by every account on the phone; iOS keeps one store per account,
         *  so these are never shown as somebody's post (audit L1). */
        fun isOtherAccountsUnsent(row: BroadcastMessageEntity, myAddress: String?): Boolean =
            row.deliveryStatus != "sent" && row.senderAddress != myAddress
    }

    /** Hides a sender in ONE room - their messages and notifications from that room disappear; other rooms are unaffected. */
    suspend fun hideSender(senderAddress: String, channelName: String) {
        database.broadcastDao().hideSender(
            HiddenBroadcastSenderEntity(senderAddress, walletManager.getAddress(), channelName)
        )
    }

    suspend fun unhideSender(senderAddress: String, channelName: String) {
        database.broadcastDao().unhideSender(senderAddress, walletManager.getAddress(), channelName)
    }

    // MARK: - Indexer backfill (4.0): the featured rooms are backed by the KaChat broadcast
    // indexer, so history sent while the app was closed appears on room open. Merge is
    // dedupe-by-txId via the DAO's REPLACE insert; hidden-sender filtering happens at read.

    /** Channels whose full 30-day history was already paged in this process — the deep backfill
     *  runs once per room per launch; the 8s poll then only needs the newest page. */
    private val deepBackfilledChannels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Where an interrupted deep backfill picks up: the `before` cursor of the next page to ask
     *  for, and how many pages of the safety valve are left. Session-only, like the completed
     *  set above. Without this a single thrown page - one timeout on page 30 of a busy room -
     *  restarted the whole pager from page 1 on the next 8s tick, and kept doing so until every
     *  page happened to succeed in one go (iOS deepBackfillResume). */
    private val deepBackfillResume = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    /** Oldest a room's next backfill needs to reach after an outage (ms): the indexer gets the
     *  last word on that stretch, without paging its whole 30 days again (iOS ee5ec98). */
    private val deepBackfillFloor = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The newest block time the indexer has shown for each room this session. A steady poll
     *  whose whole page is newer than this has a gap behind it (iOS ee5ec98). */
    private val indexerNewestSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Backfill attempts in a row that failed without paging anything, per channel; at
     *  [MAX_FAILED_BACKFILL_RESUMES] the failing page is passed over (iOS 077d777). */
    private val failedBackfillResumes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** When the newest answered poll of a room was asked for (wall ms). Anything older than this
     *  that a newest-rows poll can't reach (the room closed, polls failing) is paged in again
     *  ([repageIfPollingLapsed], iOS da59726). */
    private val indexerCoveredUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // MARK: - The arena's indexer (iOS da59726, merged into Public Chats in iOS 148a5331)

    /** Whether the arena's indexer is answering. Every arena read reports here, and only here:
     *  never the message indexer's health ([noteIndexerResult]), nor it the arena's. */
    private val arenaHealth = ArenaIndexerHealth()

    /** Guards the arena's backfill bookkeeping below, which iOS keeps on the main actor. */
    private val arenaLock = Any()

    private val _arenaIndexerDown = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while the chess arena's indexer has stopped answering (iOS `arenaIndexerDown`,
     *  `.arenaIndexerAvailabilityChanged`). The arena then lives on the block stream, with the
     *  REST API filling in the player's own games ([readArenaFromChain]). */
    val arenaIndexerDown: kotlinx.coroutines.flow.StateFlow<Boolean> = _arenaIndexerDown

    /** When the newest indexer answer for the arena was asked for (wall ms): rows up to then are
     *  in the store, so it only counts once any paging-in that was due has finished. */
    @Volatile
    var arenaIndexerAnsweredAt: Long = 0L
        private set

    /** Whether the arena has an indexer configured at all, as of the last read that asked. */
    @Volatile
    var arenaHasIndexer: Boolean = true
        private set

    /** The indexer has answered an arena page: what the lobby's "Loading rooms…" waits for, the
     *  first page and not the whole backfill (iOS da59726 `indexerFetchedChannels`). */
    @Volatile
    var arenaIndexerAnswered: Boolean = false
        private set

    /** The arena's history is paged in, as far as this session owes it. */
    val arenaBackfillDone: Boolean get() = ARENA in deepBackfilledChannels

    /** This room's own indexer where it has one, the app-wide client otherwise. */
    private suspend fun indexerApi(channelName: String): com.kachat.app.services.BroadcastIndexerApi? {
        val override = roomIndexerOverride(channelName)
        val api = override?.let { broadcastIndexers.broadcastIndexerApiFor(it) } ?: broadcastIndexers.broadcastIndexerApi.value
        if (channelName == ARENA) arenaHasIndexer = api != null
        return api
    }

    /** Whether a room has an indexer to ask (iOS `indexerBaseURL(forChannel:)` not empty). */
    suspend fun indexerConfigured(channelName: String): Boolean = indexerApi(channelName) != null

    suspend fun arenaIndexerConfigured(): Boolean = indexerConfigured(ARENA)

    /** The base URL [channelName] reads from, without a trailing slash; null when none is set. */
    suspend fun indexerBaseUrlOrNull(channelName: String): String? =
        indexerBaseUrl(channelName).trim().trimEnd('/').takeIf { it.isNotEmpty() }

    /** Whether the arena has an indexer address set, read from the settings: the app-wide client
     *  only comes up a moment after launch, which mustn't read as "there is none". */
    suspend fun arenaIndexerAddressSet(): Boolean = indexerBaseUrl(ARENA).isNotBlank()

    /** The newest page of the arena answered at [arenaNewestAnsweredAt] (wall ms), and the rows
     *  from [arenaCoveredFrom] (block time) up to then are all in the store (iOS 077d777).
     *  Guarded by [arenaLock]. */
    private var arenaNewestAnsweredAt: Long = 0L
    private var arenaCoveredFrom: Long = Long.MAX_VALUE

    /** [coveredFrom]: how far back the store runs without a gap as of this answer, or null when
     *  the answer says nothing about that (a newest-rows sweep). */
    private fun noteArenaAnswered(channelName: String, askedAt: Long, coveredFrom: Long?) {
        if (channelName != ARENA) return
        synchronized(arenaLock) {
            if (coveredFrom != null) {
                arenaNewestAnsweredAt = maxOf(arenaNewestAnsweredAt, askedAt)
                arenaCoveredFrom = coveredFrom
            }
            if (channelName !in deepBackfilledChannels) return
            arenaIndexerAnsweredAt = maxOf(arenaIndexerAnsweredAt, askedAt)
        }
    }

    /** When the newest indexer answer that holds every arena row since [since] (block time) was
     *  asked for (wall ms), or 0 (iOS 077d777 `arenaAnsweredAt(covering:)`). */
    fun arenaAnsweredAt(since: Long): Long = synchronized(arenaLock) {
        if (arenaBackfillDone) return@synchronized arenaIndexerAnsweredAt
        if (since >= arenaCoveredFrom) arenaNewestAnsweredAt else 0L
    }

    /** Clears every arena freshness mark. Call with [arenaLock] held. */
    private fun clearArenaAnsweredLocked() {
        arenaIndexerAnsweredAt = 0L
        arenaNewestAnsweredAt = 0L
        arenaCoveredFrom = Long.MAX_VALUE
    }

    /** When the arena's indexer was last seen going down (wall ms). */
    @Volatile
    private var arenaDownSince: Long? = null

    /** The latest word on the arena's indexer, after every read: state, not transitions, so a
     *  missed or reordered change can't leave it stuck either way. */
    @Synchronized
    private fun arenaHealthObserved() {
        val down = arenaHealth.isDown
        if (down == _arenaIndexerDown.value) return
        android.util.Log.w(
            "BroadcastRepository",
            if (down) "Arena indexer isn't answering: reading from the block stream and the Kaspa REST API" else "Arena indexer is answering again",
        )
        // Back: the arena is paged in again, since the steady poll's newest rows can't reach back
        // over an outage. Only the outage is paged again when the history before it was all in
        // (iOS 077d777). Down: on a metered network the block stream comes back on
        // (BroadcastScanningService).
        if (down) {
            arenaDownSince = System.currentTimeMillis()
        } else {
            val since = arenaDownSince
            if (ARENA in deepBackfilledChannels && since != null) {
                requestRepage(ARENA, since - REPAGE_MARGIN_MS)
            } else {
                synchronized(arenaLock) {
                    deepBackfilledChannels.remove(ARENA)
                    deepBackfillResume.remove(ARENA)
                    deepBackfillFloor.remove(ARENA)
                    clearArenaAnsweredLocked()
                }
            }
            arenaDownSince = null
        }
        _arenaIndexerDown.value = down
    }

    /** The indexer's address was cleared while it was down: nothing would ever report it back
     *  (iOS `resyncArenaIndexerIfUnset`). */
    suspend fun resyncArenaIndexerIfUnset() {
        if (arenaIndexerConfigured() || !_arenaIndexerDown.value) return
        arenaHealth.reset()
        arenaHealthObserved()
    }

    /** Opening a chess screen asks at once whether the arena's indexer is back. */
    fun allowArenaProbeNow() = arenaHealth.allowProbeNow()

    private fun skipsIndexer(channelName: String): Boolean =
        channelName == ARENA && arenaHealth.shouldSkipIndexer()

    /**
     * [page] with only the rows the app can believe the shape of (iOS audit 2026-11-21 #19
     * `PublicChatIndexerClient.isWellFormed`): a 64-hex txid, a valid address on this network,
     * content within what a transaction can carry, a block time that isn't zero or far in the
     * future, and, when the row names its room, the room asked for; and no more rows than any
     * page is asked for. Who sent a row is still checked on chain (BroadcastSenderVerifier).
     */
    private fun wellFormedPage(
        page: com.kachat.app.services.BroadcastHistoryResponse,
        channelName: String,
    ): com.kachat.app.services.BroadcastHistoryResponse {
        val rows = page.messages ?: return page
        val wanted = com.kachat.app.util.MessageProtocol.normalizeChannelName(channelName)
        val prefix = "${com.kachat.app.util.KaspaNetwork.hrp}:"
        val now = System.currentTimeMillis()
        val kept = rows.take(MAX_INDEXER_ROWS_PER_PAGE).filter { isWellFormedIndexerRow(it, wanted, prefix, now) }
        if (kept.size == rows.size) return page
        android.util.Log.w("BroadcastRepository", "Dropped ${rows.size - kept.size} malformed indexer row(s) for #$wanted")
        return page.copy(messages = kept)
    }

    /** One page of a room's history. Every arena read reports how it went: any status that isn't
     *  a success, or a body that doesn't decode, gave no rows, and to the arena that's the same as
     *  no answer. [isBackfill]: an older page paged in right after the newest page answered; its
     *  failure doesn't count toward an outage (iOS 077d777). */
    private suspend fun indexerPage(
        api: com.kachat.app.services.BroadcastIndexerApi,
        channelName: String,
        limit: Int,
        before: Long?,
        isBackfill: Boolean = false,
    ): com.kachat.app.services.BroadcastHistoryResponse {
        if (channelName != ARENA) return wellFormedPage(api.getBroadcasts(channel = channelName, limit = limit, before = before), channelName)
        try {
            val page = api.getBroadcasts(channel = channelName, limit = limit, before = before)
            // Gson fills a missing field with null where iOS's Decodable throws: a 200 without the
            // expected shape is a body that doesn't decode (iOS da59726 `IndexedPublicChat`).
            val rows = page.messages ?: throw com.google.gson.JsonParseException("Arena page has no messages")
            if (rows.any { it.txId == null || it.senderAddress == null || it.content == null || it.blockTime == null }) {
                throw com.google.gson.JsonParseException("Arena row is missing a field")
            }
            arenaHealth.recordAnswer()
            arenaHealthObserved()
            arenaIndexerAnswered = true
            return wellFormedPage(page, channelName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val saysDown = when (e) {
                is java.io.IOException -> !phoneIsOffline()
                // Retrofit's own NullPointerException is a 200 with an empty or "null" body.
                is retrofit2.HttpException, is com.google.gson.JsonParseException, is NullPointerException -> true
                else -> false
            }
            if (saysDown && !isBackfill) arenaHealth.recordFailure()
            arenaHealthObserved()
            throw e
        }
    }

    /** The polls stopped for a while: the rows since then may be past what the newest page holds. */
    private fun repageIfPollingLapsed(channelName: String, now: Long) {
        val covered = indexerCoveredUntil[channelName] ?: return
        if (channelName !in deepBackfilledChannels || now - covered <= POLL_LAPSE_MS) return
        requestRepage(channelName, covered - REPAGE_MARGIN_MS)
    }

    /** The next poll pages the room back to [since]. A backfill already running to an older point,
     *  or the whole window, is left to go there. */
    private fun requestRepage(channelName: String, since: Long) {
        synchronized(arenaLock) {
            if (deepBackfilledChannels.remove(channelName)) {
                deepBackfillFloor[channelName] = since
                deepBackfillResume.remove(channelName)
            } else {
                deepBackfillFloor[channelName]?.let { floor -> deepBackfillFloor[channelName] = minOf(floor, since) }
            }
            if (channelName == ARENA) clearArenaAnsweredLocked()
        }
    }

    /** Pages the arena back to [since] on the next poll: one of this player's games shows a move
     *  missing, or a claim didn't take (iOS `repageArena(since:)`). Until that's done no answer
     *  counts as fresh. */
    fun repageArena(since: Long) = requestRepage(ARENA, since - REPAGE_MARGIN_MS)

    /**
     * The last account left and every room's rows went with it: nothing here is paged in any more.
     * Without this the next account's polls only ask for the newest rows, and a paid join can go
     * to a room long since moved past (iOS da59726 `setCurrentWallet`).
     */
    fun resetAfterRoomWipe() {
        synchronized(arenaLock) {
            deepBackfilledChannels.clear()
            deepBackfillResume.clear()
            failedBackfillResumes.clear()
            deepUnverifiedRetries.clear()
            indexerCoveredUntil.clear()
            deepBackfillFloor.clear()
            indexerNewestSeen.clear()
            clearArenaAnsweredLocked()
            arenaIndexerAnswered = false
            localStampSentAt.clear()
            localStampNextCheck.clear()
            // Stamps are kept per account, and no account is left to keep any.
            localStampPrefs.edit().clear().apply()
        }
    }

    /** Indexers confirmed down (three failures in a row inside two minutes), and since when, by
     *  base URL. Their rooms go back on the block stream on cellular (iOS ee5ec98). */
    private val _unreachableIndexers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val unreachableIndexers: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> get() = _unreachableIndexers

    private class IndexerFailureRun(var count: Int, var lastAt: Long)

    /** Guards [indexerFailureRuns] and the writes to [_unreachableIndexers]: the open room's poll
     *  and the closed-room sweep report from different threads. */
    private val indexerHealthLock = Any()
    private val indexerFailureRuns = HashMap<String, IndexerFailureRun>()

    /** The app-wide indexer and the rooms' own, for the scanner's cellular gate. */
    fun roomIndexerBases(): Flow<Pair<String, Map<String, String>>> =
        combine(settings.broadcastIndexerUrl, settings.broadcastIndexerOverrides) { appWide, overrides ->
            appWide to overrides
        }

    private suspend fun indexerBaseUrl(channelName: String): String =
        indexerBaseUrl(channelName, settings.broadcastIndexerUrl.first(), settings.broadcastIndexerOverrides.first())

    /** Whether [base] is the app-wide public chat indexer, trailing slash and case aside (iOS
     *  b78fdb0 `isAppWideIndexer`). */
    private suspend fun isAppWideIndexer(base: String): Boolean {
        fun canonical(url: String): String = url.trim().lowercase().trimEnd('/')
        val appWide = canonical(settings.broadcastIndexerUrl.first())
        return appWide.isNotEmpty() && canonical(base) == appWide
    }

    /**
     * Every answer or failure from a room's indexer (iOS ee5ec98 `noteIndexerResult`). Null, or a
     * 4xx, means it answered: the count resets, and an indexer that was down has its rooms read
     * the outage again. Only a failure that says the indexer is at fault counts towards three.
     */
    private suspend fun noteIndexerResult(base: String, failed: Exception?) {
        // A 4xx is the indexer answering, whatever it thinks of this request; so is a body that
        // won't decode (iOS b78fdb0, CLAUDE.md "With the indexer down").
        if (failed == null || isClientRejection(failed) || isUndecodableBody(failed)) {
            val since = synchronized(indexerHealthLock) {
                indexerFailureRuns.remove(base)
                val downSince = _unreachableIndexers.value[base] ?: return
                _unreachableIndexers.value = _unreachableIndexers.value - base
                downSince
            }
            android.util.Log.i("BroadcastRepository", "Room indexer is answering again")
            rewindRoomsAfterOutage(base, since)
            return
        }
        if (!meansDown(failed) && !isServerFailure(failed)) return
        synchronized(indexerHealthLock) {
            val now = System.currentTimeMillis()
            val run = indexerFailureRuns.getOrPut(base) { IndexerFailureRun(count = 0, lastAt = 0L) }
            if (now - run.lastAt < INDEXER_FAILURE_COALESCE_MS) return
            if (now - run.lastAt > INDEXER_FAILURE_WINDOW_MS) run.count = 0
            run.count += 1
            run.lastAt = now
            if (run.count < INDEXER_DOWN_FAILURES || base in _unreachableIndexers.value) return
            _unreachableIndexers.value = _unreachableIndexers.value + (base to now)
        }
        android.util.Log.w("BroadcastRepository", "Room indexer isn't answering: rooms stay on the block stream")
    }

    /** A 429 or a 5xx is the indexer's trouble (iOS `KaPostsIndexerHealth.meansDown(statusCode:)`). */
    private fun statusMeansDown(code: Int): Boolean = code == 429 || code >= 500

    /** A 4xx is the indexer answering, whatever it thinks of this request. */
    private fun isClientRejection(error: Exception): Boolean =
        (error as? retrofit2.HttpException)?.code()?.let { it in 400..499 && !statusMeansDown(it) } == true

    /** The indexer answered with a body that won't decode (iOS `DecodingError`): Gson's parse
     *  failures, which Retrofit's converter throws once the whole body is read. */
    private fun isUndecodableBody(error: Exception): Boolean =
        error is com.google.gson.JsonParseException || error is com.google.gson.stream.MalformedJsonException

    private fun isServerFailure(error: Exception): Boolean =
        (error as? retrofit2.HttpException)?.code()?.let { statusMeansDown(it) } == true

    /** A transport failure while the phone has a connection. A phone that's offline says nothing
     *  about the indexer, and neither does a cancelled request (a body that won't decode is an
     *  answer, [isUndecodableBody]). */
    private fun meansDown(error: Exception): Boolean {
        if (error !is java.io.IOException) return false
        if (error is java.io.EOFException || error is com.google.gson.stream.MalformedJsonException) return false
        return !phoneIsOffline()
    }

    private fun phoneIsOffline(): Boolean {
        val manager = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Rooms this indexer serves read that stretch from it again, from ten minutes before it went
     *  away: what the block stream missed comes in, and the indexer has the final word (iOS
     *  ee5ec98). The next step of the poll that noticed it does the paging. */
    private suspend fun rewindRoomsAfterOutage(base: String, since: Long) {
        val floor = since - OUTAGE_REWIND_MS
        val appWide = settings.broadcastIndexerUrl.first()
        val overrides = settings.broadcastIndexerOverrides.first()
        for (channel in deepBackfilledChannels.toList()) {
            if (indexerBaseUrl(channel, appWide, overrides) == base) {
                deepBackfilledChannels.remove(channel)
                deepBackfillFloor[channel] = floor
            }
        }
    }

    /** Room edits whose send failed, by room then target txId: the text, for Retry. The message
     *  itself goes back to what it read before, since that text never reached the chain (iOS
     *  ee5ec98 `failedEditsByChannel`). In memory only, as there. */
    private val _failedEdits = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val failedEdits: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, String>>> get() = _failedEdits

    /** The text of this message's failed edit, when there is one to retry. */
    fun failedEditText(channelName: String, targetTxId: String): String? =
        _failedEdits.value[channelName]?.get(targetTxId)

    private fun setFailedEdit(channelName: String, targetTxId: String, text: String?) {
        val room = _failedEdits.value[channelName].orEmpty()
        val updated = if (text == null) room - targetTxId else room + (targetTxId to text)
        _failedEdits.value = if (updated.isEmpty()) _failedEdits.value - channelName
        else _failedEdits.value + (channelName to updated)
    }

    /**
     * Edits one of this wallet's own messages (iOS ee5ec98 `sendPublicChatEdit`). Never sent: its
     * row goes, which puts the message back to what it read, and the text waits for Retry. An
     * unknown outcome keeps its row, "unconfirmed", until the delivered copy turns it into a
     * normal edit, with no Retry, which could pay twice.
     */
    suspend fun sendEdit(channelName: String, targetTxId: String, text: String) {
        setFailedEdit(channelName, targetTxId, null)
        try {
            sendBroadcast(channelName, com.kachat.app.util.MessageEdit.encode(targetTxId, text), dropRowOnFailure = true)
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            setFailedEdit(channelName, targetTxId, text)
            throw e
        }
    }

    /** How many times a room's deep backfill was re-run because some rows' senders could not be
     *  verified yet (audit XP-012). Session-only. */
    private val deepUnverifiedRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Sends started in this run, by pending id. A row still pending that isn't one of these was
     *  left by a run that ended mid-send, and nothing is working on it (iOS e5a9b11). */
    private val pendingSendsThisRun: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Rooms already checked for those rows, once per account per run (iOS e5a9b11). */
    private val settledPendingChannels = mutableSetOf<String>()

    /** Rooms settled early because their indexer is down. They still get the full check once the
     *  backfill finishes, which drops a "Not confirmed" row whose delivered copy then arrived
     *  (iOS 369794e `settledWhileIndexerDown`). Guarded by [settledPendingChannels]' lock. */
    private val settledWhileIndexerDown = mutableSetOf<String>()

    /** The account [settledPendingChannels] was filled for. */
    private var settledForWallet: String? = null

    /** Bumped on every account change: work begun for one account checks it after each await
     *  and drops its result when the account is no longer the open one (iOS b78fdb0). */
    @Volatile
    private var walletGeneration = 0

    /** The account a fetch started under: [walletGeneration] and the address, with the phone
     *  signed in. Log Out keeps the address, so it is read from [WalletManager.isLoggedOut]. */
    private data class WalletTurn(val generation: Int, val address: String?)

    private fun currentWalletTurn(): WalletTurn = WalletTurn(walletGeneration, myAddress())

    /** Whether [turn]'s account is still the open one, signed in (iOS `isCurrentWallet`). */
    private fun isCurrentWallet(turn: WalletTurn): Boolean =
        turn.address != null && turn.generation == walletGeneration && myAddress() == turn.address &&
            !walletManager.isLoggedOut

    /** An account is open and signed in: the rooms' sweep and polls run only then (iOS b78fdb0,
     *  "Signed out: no rooms to sweep"). */
    fun isSignedIn(): Boolean = myAddress() != null && !walletManager.isLoggedOut

    /**
     * Whether a room push arriving while the app is in front is one this account still wants
     * (iOS b78fdb0 `PublicChatService.wantsRoomPush`): a room it has joined, with its bell on, not
     * a service room. A default room switched off in Public Chats settings has its bell off
     * ([com.kachat.app.viewmodels.BroadcastViewModel.setCuratedRoomShown]). The push service can
     * still send for a room deleted, muted or switched off until the registration update lands.
     */
    suspend fun wantsRoomPush(channelName: String): Boolean {
        val name = MessageProtocol.normalizeChannelName(channelName)
        if (name in com.kachat.app.models.FeaturedBroadcastChannels.SERVICE_CHANNELS) return false
        val address = myAddress() ?: return false
        return name in database.broadcastDao().getNotifyEnabledChannelNames(address).first()
    }

    /**
     * Whether [channelName] is in this account's room list - a tapped room push opens only such a
     * room and never joins one (iOS b78fdb0 `openRoomFromPush`). On a cold start the account may
     * still be landing, so this waits for it, up to 10 s, before deciding.
     */
    suspend fun isJoined(channelName: String): Boolean {
        val address = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            walletManager.activeAddressFlow.filterNotNull().first()
        } ?: return false
        val name = MessageProtocol.normalizeChannelName(channelName)
        return database.broadcastDao().getJoinedChannels(address).first().any { it.channelName == name }
    }

    /**
     * Called with the active account whenever it lands or changes. Another account's rows have
     * not been checked yet, so a real switch starts the set again (iOS e5a9b11 setCurrentWallet).
     * Every room screen's view model reports the same account, so only a change clears it.
     */
    fun onActiveWalletChanged(address: String?) {
        val normalized = address?.lowercase()
        synchronized(settledPendingChannels) {
            if (normalized == settledForWallet) return
            settledForWallet = normalized
            settledPendingChannels.clear()
            settledWhileIndexerDown.clear()
            // A fetch, backfill or read still out for the account being left drops its answer
            // ([isCurrentWallet]) instead of writing it into this one's state (iOS b78fdb0).
            walletGeneration += 1
        }
        // iOS ee5ec98 setCurrentWallet. The backfilled set stays: Android's room messages are one
        // table shared by every account, so what was fetched still holds for the next one.
        _failedEdits.value = emptyMap()
        deepBackfillFloor.clear()
        indexerNewestSeen.clear()
    }

    /**
     * Once the room's full history is in (after the deep backfill, or straight away for a room
     * with no indexer), so a send that did land has its delivered copy to match (iOS e5a9b11
     * `settlePendingSendsFromBeforeThisRun`). A pending row no send in this run is working on, or
     * an unconfirmed one whose id never became a transaction's, goes if its delivered copy is here
     * (same sender and content, sent, chain time no more than five minutes before the row's own).
     * The rest may have landed while the app was closed, so they show "Not confirmed" rather than
     * a Retry that could pay twice (iOS 33095ac `settlePendingMessages`).
     *
     * [indexerDown]: once while the room's indexer is confirmed down - no history is coming, and
     * the rows would otherwise say "Sending" with no Retry all session (iOS 369794e).
     *
     * A long voice note's parts go out one at a time, in index order, so when a run ended mid-note
     * only the earliest unsettled part can have been submitted. The parts after it never left the
     * phone: Failed, so the note's Retry sends them, instead of "Not confirmed" for good with the
     * room stuck on "Receiving 3 of 12" (iOS 369794e).
     */
    suspend fun settlePendingSendsFromBeforeThisRun(channelName: String, indexerDown: Boolean = false) {
        synchronized(settledPendingChannels) {
            if (channelName in settledPendingChannels) return
            if (indexerDown) {
                if (!settledWhileIndexerDown.add(channelName)) return
            } else {
                settledPendingChannels.add(channelName)
            }
        }
        val dao = database.broadcastDao()
        // Only this account's: the table is shared, and another account's sends are its own to
        // settle once it is the open one again (audit L1).
        val me = myAddress() ?: return
        val rows = dao.getUnsettledSends(channelName).filter { it.id !in pendingSendsThisRun && it.senderAddress == me }
        val earliestIndexByNote = HashMap<String, Int>()
        val partOf = HashMap<String, Pair<String, Int>>()
        for (row in rows) {
            if (row.content.firstOrNull() != '{') continue
            val tag = com.kachat.app.util.VoicePartTag.sniff(row.content) ?: continue
            val note = "${row.senderAddress.lowercase()}|${tag.id}|${tag.count}"
            partOf[row.id] = note to tag.index
            // Only parts still Sending count: one already "Not confirmed" (settled by an earlier
            // run) wasn't what this run's sending had in flight. After a Retry of the later parts
            // it is the earliest, and choosing it marked the part really in flight Failed - whose
            // Retry could pay twice (iOS b78fdb0).
            if (row.deliveryStatus != "pending") continue
            earliestIndexByNote[note] = minOf(earliestIndexByNote[note] ?: tag.index, tag.index)
        }
        // A delivered copy settles one pending row, not every identical one: two sends of the same
        // text with one copy on chain leave the other "Not confirmed" (iOS b78fdb0).
        val matchedDelivered = HashSet<String>()
        for (row in rows.sortedBy { it.blockTimestamp }) {
            val part = partOf[row.id]
            val earliest = part?.let { earliestIndexByNote[it.first] }
            if (part != null && earliest != null && part.second > earliest && row.id.startsWith("pending_") &&
                row.deliveryStatus == "pending") {
                dao.updateMessageStatus(row.id, "failed")
                continue
            }
            val copy = dao.deliveredCopyIds(
                channelName = channelName,
                senderAddress = row.senderAddress,
                content = row.content,
                sinceMillis = row.blockTimestamp - SETTLE_DELIVERED_WINDOW_MS,
            ).firstOrNull { it !in matchedDelivered }
            if (copy != null) {
                matchedDelivered.add(copy)
                dao.deleteMessage(row.id)
            } else if (row.deliveryStatus != "unconfirmed") {
                dao.updateMessageStatus(row.id, "unconfirmed")
            }
        }
    }

    /**
     * A send whose outcome is unknown (iOS 33095ac `markMessageUnconfirmed`): the row takes the
     * transaction's id, so its delivered copy settles it, and shows "Not confirmed" with no Retry.
     * A copy already here means it landed: the row just goes.
     */
    private suspend fun markMessageUnconfirmed(pendingId: String, txId: String) {
        pendingSendsThisRun.remove(pendingId)
        val dao = database.broadcastDao()
        val row = dao.getMessage(pendingId) ?: return
        // No id to match a copy by: the row stays as it is, "Not confirmed", for the settle.
        if (txId.isBlank()) {
            dao.updateMessageStatus(pendingId, "unconfirmed")
            return
        }
        dao.deleteMessage(pendingId)
        if (dao.getMessage(txId) != null) return
        // Signed here, so its sender is known when it comes back from the chain (XP-012).
        senderVerifier.rememberOwnBroadcast(txId, row.senderAddress)
        dao.insertMessage(row.copy(id = txId, deliveryStatus = "unconfirmed"))
    }

    /** Fetches history for [channelName] and merges it into the local cache. The FIRST call per
     *  channel per launch pages backwards (`before` = oldest blockTime seen) through the
     *  indexer's whole 30-day window — a single newest page (200 rows) meant busy rooms never
     *  loaded anywhere near what the indexer holds. Later calls fetch just the newest page.
     *  Returns the number of rows fetched, or -1 when the indexer is unreachable (callers treat
     *  that as "no backfill", nothing user-facing breaks). */
    /** This wallet's own address, or null before there is one. */
    fun myAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /** Whether [address] is one of the accounts on this phone, the open one or another (either
     *  network's form): a post from it is the user's own and never banners (audit M5). */
    fun isLocalAccountAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val mine = listOfNotNull(myAddress()) +
            runCatching { walletManager.getAllAccounts() }.getOrDefault(emptyList()).map { it.address }
        return mine.any { com.kachat.app.util.KaspaNetwork.isSameAccount(it, address) }
    }

    /**
     * One small newest-page fetch for a room nobody is looking at, merged like any other indexer
     * page. Returns the rows that were NEW to the store, so the caller can banner what just
     * arrived (iOS 3c14b46). Reactions never render as rows, so they are left out.
     */
    suspend fun fetchNewestFromIndexer(channelName: String, limit: Int = 40): List<BroadcastMessageEntity> =
        fetchNewestAndMerge(channelName, limit).orEmpty()

    /**
     * Asks the indexer for a room's newest rows right now and merges them: what the chess arena
     * does just before a player picks a room. False when there is no indexer for the room or the
     * request failed (iOS `refreshFromIndexerNow`).
     */
    suspend fun refreshFromIndexerNow(channelName: String): Boolean = fetchNewestAndMerge(channelName, 40) != null

    /** Where [fetchBeforePushBanner]'s fetches run: one the banner stopped waiting for keeps
     *  going and merges when it answers. */
    private val pushFetchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO +
            com.kachat.app.util.StartupSafety.logOnlyHandler("BroadcastRepository.pushFetch")
    )

    /**
     * A room push that came while the app is open: its message is fetched before the banner
     * shows, so the banner never announces a message the Chats list and the room don't have yet
     * (iOS 961fc05a `fetchBeforePushBanner`; the next sweep used to bring it). Asks again until the
     * push's transaction is in the store (the indexer can push a moment before it serves the
     * row), giving up after [timeoutMs] so the banner is never held long. Each fetch races the
     * time left (one can take 20 s; it keeps running and merges when it answers, the banner
     * doesn't wait), and an indexer marked down isn't asked at all (audit 2026-11-25 C9).
     */
    suspend fun fetchBeforePushBanner(rawChannel: String, txId: String?, timeoutMs: Long = PUSH_BANNER_FETCH_TIMEOUT_MS) {
        val channel = MessageProtocol.normalizeChannelName(rawChannel)
        if (joinedChannelsNow().none { it.channelName == channel }) return
        val base = indexerBaseUrl(channel)
        if (base.isEmpty() || base in _unreachableIndexers.value) return
        if (channel == ARENA && arenaIndexerDown.value) return
        val deadline = System.currentTimeMillis() + timeoutMs
        val wanted = txId?.trim()?.lowercase()?.takeIf { PushBannerFetch.isTxIdShaped(it) }
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            val job = pushFetchScope.async { refreshFromIndexerNow(channel) }
            val ok = kotlinx.coroutines.withTimeoutOrNull(left) { job.await() } ?: false
            if (!ok || wanted == null) return
            val dao = database.broadcastDao()
            if (dao.getMessage(wanted) != null || dao.getMessage(wanted.uppercase()) != null) return
            if (System.currentTimeMillis() + PushBannerFetch.RETRY_GAP_MS >= deadline) return
            kotlinx.coroutines.delay(PushBannerFetch.RETRY_GAP_MS)
        }
    }

    /** Null when there was no answer; otherwise the rows that were new to the store. */
    private suspend fun fetchNewestAndMerge(channelName: String, limit: Int): List<BroadcastMessageEntity>? {
        val api = indexerApi(channelName) ?: return null
        // Down: one request a minute finds out when it's back (iOS da59726).
        if (skipsIndexer(channelName)) return null
        val askedAt = System.currentTimeMillis()
        repageIfPollingLapsed(channelName, askedAt)
        val turn = currentWalletTurn()
        val hidden = hiddenAddressesIn(channelName, getHiddenSenders().first())
        val base = indexerBaseUrl(channelName)
        // The arena keeps its own health; a room's read tells the message indexer's (iOS 148a5331).
        val isArena = channelName == ARENA
        val page = try {
            indexerPage(api, channelName, limit, before = null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isArena) noteIndexerResult(base, e)
            android.util.Log.w("BroadcastRepository", "Sweep fetch failed for $channelName", e)
            return null
        }
        if (!isArena) noteIndexerResult(base, null)
        // An indexer only stores the rooms it tracks: rows back mean it keeps this room's history.
        // Only the app-wide indexer's rows count: "tracked" also means the app-wide push service
        // sends the room's pushes, which a room's own indexer says nothing about (iOS b78fdb0).
        if (!page.messages.isNullOrEmpty() && isAppWideIndexer(base)) {
            com.kachat.app.services.IndexerTrackedRooms.markServed(channelName)
        }
        // Asked for the account that has since logged out or switched: not this one's (iOS
        // b78fdb0). The arena's rows are the arena's, whoever is signed in.
        if (!isArena && !isCurrentWallet(turn)) return null
        // A message a moderator removed goes from closed rooms too, not only when one opens (iOS
        // 2026-11-25 `dropRowsTheIndexerNoLongerHas`).
        if (!isArena) dropRowsTheIndexerNoLongerHas(page.messages.orEmpty(), before = null, base = base, channelName = channelName, turn = turn)
        if (!isArena && !isCurrentWallet(turn)) return null
        // Neither a reaction nor an edit is a message anyone reads; the room's own backfill is
        // what persists them. A delete for everyone goes on to be applied (never stored).
        val wanted = page.messages.orEmpty().filter { message ->
            val content = message.content ?: return@filter false
            MessageReaction.parseOrNull(content) == null && com.kachat.app.util.MessageEdit.parseOrNull(content) == null
        }
        // Hidden is judged on the VERIFIED sender, never on the indexer's guess. A room's hidden
        // senders are stored all the same: the table is shared by every account on the phone,
        // and one account's hide must not cost another the posts (audit L3). Reads filter them,
        // and the rows handed back (the sweep's banners) leave them out. The arena's reducer
        // reads the table unfiltered, so there they stay out.
        val admitted = admitIndexerRows(
            channelName, wanted, skipSender = { isArena && it in hidden },
            stillWanted = { isArena || isCurrentWallet(turn) },
        )
        // The store is missing a row that just happened: as far as a claim or a join goes, the
        // indexer hasn't answered yet (on iOS an answered page is always stored whole).
        if (isArena && admitted.recentUnknown) return null
        noteArenaAnswered(channelName, askedAt, coveredFrom = null)
        return if (isArena) admitted.inserted
        else if (isCurrentWallet(turn)) admitted.inserted.filter { it.senderAddress !in hidden }
        else emptyList()
    }

    /** What [admitIndexerRows] stored, and how many rows it could not verify yet. */
    private data class Admitted(
        val inserted: List<BroadcastMessageEntity>,
        val unknown: Int,
        /** One of the rows it could not verify yet is from the last [UNKNOWN_ROW_FRESH_MS]. */
        val recentUnknown: Boolean = false,
    )

    /**
     * Merges indexer rows into the store with their sender VERIFIED (audit XP-012): the
     * indexer's `senderAddress` is ignored; a new row is stored only when input 0 spends from the
     * address output 0 pays, under that address. Forged rows are dropped; rows the REST API
     * can't answer for yet are skipped and asked again on a later poll.
     *
     * A row already in the store was stored by a verified path (this one, the live scan, or this
     * phone's own send; older cached rows are re-checked once by [reverifyCachedSenders]), so it
     * keeps its sender and only takes the indexer's chain time: a row this phone sent carries its
     * own clock until the chain's time reaches it, the block scan may never have seen the block,
     * and the chess arena orders seats and runs its clocks by the indexer's time (iOS 29bf054).
     */
    private suspend fun admitIndexerRows(
        channelName: String,
        rows: List<com.kachat.app.services.BroadcastHistoryRow>,
        skipSender: (String) -> Boolean = { false },
        /** False once the account the page was asked for has logged out or switched: the rest
         *  of the page is dropped (iOS b78fdb0 `mergeIndexerRows`' generation). */
        stillWanted: () -> Boolean = { true },
    ): Admitted {
        if (!stillWanted()) return Admitted(emptyList(), 0)
        val toVerify = ArrayList<com.kachat.app.services.BroadcastHistoryRow>()
        // Deletes for everyone on this page, by their verified sender (iOS 2026-11-25
        // `applyIncomingDeletes`): applied, never stored.
        val deletes = ArrayList<Pair<String, String>>()
        for (row in rows) {
            val txId = row.txId?.takeIf { it.isNotBlank() } ?: continue
            if (row.content == null) continue
            val existing = database.broadcastDao().getMessage(txId)
            val delete = com.kachat.app.util.MessageDelete.parseOrNull(row.content)
            if (existing != null && delete != null) {
                // An older build stored it as a row: applied by its stored (verified) sender, and
                // the row goes.
                deletes += delete.targetTxId to existing.senderAddress
                if (existing.deliveryStatus == "sent") database.broadcastDao().deleteMessage(txId)
                continue
            }
            if (existing != null) {
                val chainTime = row.blockTime
                // The chain's time reached an own service-room row (iOS da59726).
                if (chainTime != null) confirmLocalStamp(txId)
                // A send whose outcome was unknown: its delivered copy makes it sent (iOS 33095ac).
                if (existing.deliveryStatus == "unconfirmed") {
                    database.broadcastDao().settleUnconfirmed(txId, chainTime ?: existing.blockTimestamp)
                    continue
                }
                if (chainTime != null && (chainTime != existing.blockTimestamp || existing.deliveryStatus != "sent")) {
                    database.broadcastDao().insertMessage(existing.copy(blockTimestamp = chainTime, deliveryStatus = "sent"))
                }
                continue
            }
            toVerify += row
        }
        if (toVerify.isEmpty()) return Admitted(emptyList(), 0)
        val verdicts = senderVerifier.verifyRows(toVerify.mapNotNull { it.txId }, channelName)
        if (!stillWanted()) return Admitted(emptyList(), 0)
        val inserted = mutableListOf<BroadcastMessageEntity>()
        var unknown = 0
        var recentUnknown = false
        val now = System.currentTimeMillis()
        for (row in toVerify) {
            val txId = row.txId ?: continue
            val judged = verdicts[txId]
            when (judged.verdict) {
                com.kachat.app.services.BroadcastSenderVerifier.Verdict.VERIFIED -> {
                    val sender = judged.senderAddress ?: continue
                    val delete = com.kachat.app.util.MessageDelete.parseOrNull(row.content)
                    if (delete != null) {
                        deletes += delete.targetTxId to sender
                        continue
                    }
                    if (skipSender(sender)) continue
                    // Never a message its sender deleted for everyone.
                    if (isDeletedRow(channelName, txId, sender)) continue
                    val entity = BroadcastMessageEntity(
                        id = txId,
                        channelName = channelName,
                        senderAddress = sender,
                        content = row.content ?: continue,
                        blockTimestamp = row.blockTime ?: System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                    // Only a missing id (iOS): the block scan may have stored it meanwhile.
                    if (database.broadcastDao().insertMessageIfAbsent(entity) != -1L) inserted += entity
                }
                com.kachat.app.services.BroadcastSenderVerifier.Verdict.FORGED -> Unit // logged by the verifier
                com.kachat.app.services.BroadcastSenderVerifier.Verdict.UNKNOWN -> {
                    unknown += 1
                    val blockTime = row.blockTime
                    if (blockTime != null && kotlin.math.abs(now - blockTime) <= UNKNOWN_ROW_FRESH_MS) recentUnknown = true
                }
            }
        }
        if (deletes.isNotEmpty() && stillWanted()) applyIncomingDeletes(deletes, channelName)
        // each new message that @'s you: a row in Profile's bell (iOS d089011f)
        if (inserted.isNotEmpty() && stillWanted()) roomMentionBell.get().recordMentions(inserted)
        return Admitted(inserted, unknown, recentUnknown)
    }

    // MARK: - Deleted for everyone, and removed from the indexer (iOS 2026-11-25)

    /** Whether the open account holds a delete of this room's row from [sender]. */
    fun isDeletedRow(channelName: String, txId: String, sender: String): Boolean =
        roomDeletes.isDeleted(myAddress(), channelName, txId, sender)

    /**
     * Delete envelopes seen in a room (block scan or indexer page), as (target, sender) (iOS
     * `applyIncomingDeletes`). A delete counts only from the sender of the message it names:
     * remembered for that room and sender, so the message never comes back, and gone now when
     * this phone holds it, with its reactions and edits. A delete naming someone else's message
     * does nothing and isn't kept. Never in the machinery rooms (chess, the bots, the arcade): a
     * move or a result can't be taken back. True when a message went.
     */
    suspend fun applyIncomingDeletes(deletes: List<Pair<String, String>>, rawChannel: String): Boolean {
        val channel = MessageProtocol.normalizeChannelName(rawChannel)
        if (deletes.isEmpty() || channel in FeaturedBroadcastChannels.SERVICE_CHANNELS ||
            channel in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS) return false
        val wallet = myAddress() ?: return false
        val dao = database.broadcastDao()
        val gone = LinkedHashSet<String>()
        for ((target, sender) in deletes) {
            val held = (dao.getMessage(target) ?: dao.getMessage(target.lowercase()))?.takeIf { it.channelName == channel }
            if (held != null) {
                // someone else's message: not theirs to delete
                if (!held.senderAddress.equals(sender, ignoreCase = true)) continue
                gone += held.id
            }
            // held from that sender, or not here (yet): remembered either way
            roomDeletes.record(wallet, channel, target, sender)
        }
        return removeMessages(channel, gone)
    }

    /** Deletes these messages from a room with the reactions and edits naming them; true when
     *  any went (iOS `PublicChatStore.deleteMessages`). */
    private suspend fun removeMessages(channel: String, ids: Collection<String>): Boolean {
        if (ids.isEmpty()) return false
        val dao = database.broadcastDao()
        // as given and lowercased: another client may write a txid in capitals
        val all = ids.flatMap { listOf(it, it.lowercase()) }.distinct()
        var removed = 0
        for (chunk in all.chunked(500)) removed += dao.deleteMessagesIn(channel, chunk)
        // What hung off them: only rows that parse as a reaction or edit naming one of them, never
        // a reply that merely quotes the txid (iOS deletes by targetTxId, audit 2026-11-25 T4).
        val targets = all.map { it.lowercase() }.toSet()
        val controls = LinkedHashSet<String>()
        for (id in all) {
            for (row in dao.controlCandidatesNaming(channel, id)) {
                if (controlTarget(row.content)?.lowercase() in targets) controls += row.id
            }
        }
        for (chunk in controls.toList().chunked(500)) dao.deleteMessagesIn(channel, chunk)
        return removed > 0
    }

    /**
     * Deletes one of this account's own delivered messages in a room for everyone (iOS
     * `deletePublicChatMessage`): a delete envelope goes out, one transaction like an edit, and
     * once it has, the message goes from this phone (and is remembered as deleted). Nothing
     * changes here until the send went out, so a failure (thrown) leaves the message exactly as it
     * was. A send whose outcome is unknown leaves it too: if it landed, its own delete comes back
     * through the room's reads and removes it then. Never a row of its own.
     */
    suspend fun deletePublicChatMessage(message: BroadcastMessageEntity) {
        val channel = MessageProtocol.normalizeChannelName(message.channelName)
        if (channel in FeaturedBroadcastChannels.SERVICE_CHANNELS || channel in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS) return
        val turn = currentWalletTurn()
        val me = turn.address ?: return
        if (!message.senderAddress.equals(me, ignoreCase = true) || message.deliveryStatus != "sent" ||
            !PushBannerFetch.isTxIdShaped(message.id)) return
        try {
            // Sent only while the account that tapped is still the open one: another account's
            // delete is ignored by everyone and only costs a fee (audit 2026-11-25 T6).
            val txId = submitBroadcast(
                channel, com.kachat.app.util.MessageDelete.encode(message.id),
                stillWanted = { isCurrentWallet(turn) },
            )
            // Signed here, so its sender is known when it comes back from the chain (XP-012).
            senderVerifier.rememberOwnBroadcast(txId, me)
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            return
        } catch (e: SenderChangedException) {
            // the account changed before the send: nothing went, nothing changes here
            return
        }
        roomDeletes.record(me, channel, message.id, me)
        removeMessages(channel, listOf(message.id))
    }

    /** How many reads in a row each cached room message ("room|txid") was missing from (iOS
     *  `absenceStrikes`): a row goes on its second miss, or its first on a room's first read. */
    private val absenceStrikes = IndexerAbsence.Strikes()

    /**
     * For a room the indexer tracks, the indexer is the truth (iOS d089011f
     * `dropRowsTheIndexerNoLongerHas`, owner 2026-11-25: "work like KaPost"): a delivered message
     * this phone holds in the span the read answers for, that the read doesn't have, goes with its
     * reactions and edits - a moderator's removal, its sender's delete, anything the indexer no
     * longer serves. The cache stays for drawing at once, the Chats list, outages and rooms with
     * no indexer. See [IndexerAbsence]: the newest page answers up to 90 seconds ago, an older one
     * between its oldest row and [before]; only the app-wide indexer, never the machinery rooms, an
     * empty page or a failed read; this phone's own messages once 5 minutes old (pending and
     * unconfirmed sends never); a row goes on its second miss, or its first on the first read after
     * the room opens and in the Rooms list's check. No brake on how many go at once. A message
     * only missing, not deleted for everyone, comes back with the read that has it again.
     */
    private suspend fun dropRowsTheIndexerNoLongerHas(
        page: List<com.kachat.app.services.BroadcastHistoryRow>,
        before: Long?,
        base: String,
        channelName: String,
        turn: WalletTurn,
    ) {
        val channel = MessageProtocol.normalizeChannelName(channelName)
        if (channel in FeaturedBroadcastChannels.SERVICE_CHANNELS || channel in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS) return
        if (!FeaturedBroadcastChannels.isIndexerTracked(channel) || !isAppWideIndexer(base)) return
        val oldest = page.mapNotNull { it.blockTime }.minOrNull() ?: return
        val now = System.currentTimeMillis()
        val span = IndexerAbsence.span(oldest, before, now, BroadcastRetention.INDEXER_MILLIS) ?: return
        val onPage = page.mapNotNull { it.txId?.lowercase() }.toSet()
        val held = database.broadcastDao().deliveredInSpan(channel, networkSenderPattern(), span.first, span.last)
        if (!isCurrentWallet(turn)) return
        val considered = held
            .filter {
                PushBannerFetch.isTxIdShaped(it.id) &&
                    IndexerAbsence.considers(isLocalAccountAddress(it.senderAddress), it.blockTimestamp, now)
            }
            .map { it.id }
        // The first read after the room opens, or a Rooms list check, removes on one miss: the
        // reader is looking at it now. Background reads keep the second miss.
        val needed = IndexerAbsence.missesNeeded(firstMissRemovesChannels.remove(channel))
        val gone = absenceStrikes.strike(channel, considered, onPage, needed = needed)
        if (gone.isEmpty()) return
        if (!removeMessages(channel, gone) || !isCurrentWallet(turn)) return
        absenceStrikes.forget(channel, gone)
        android.util.Log.i("BroadcastRepository", "#$channel: ${gone.size} message(s) no longer on the indexer, removed here too")
    }

    /** Rooms whose next indexer read removes a missing message on the first miss (iOS
     *  `firstMissRemovesChannels`): a room just opened, and the Rooms list's check. */
    private val firstMissRemovesChannels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** How many screens have each room open (iOS `liveViewRefCounts`). */
    private val openRoomCounts = HashMap<String, Int>()

    /** A room's screen opened (iOS `acquire`): its first indexer read removes on one miss. */
    fun acquireRoom(channelName: String) {
        val channel = MessageProtocol.normalizeChannelName(channelName)
        synchronized(openRoomCounts) {
            val count = openRoomCounts[channel] ?: 0
            if (count == 0) firstMissRemovesChannels.add(channel)
            openRoomCounts[channel] = count + 1
        }
    }

    /** Pairs with [acquireRoom]. */
    fun releaseRoom(channelName: String) {
        val channel = MessageProtocol.normalizeChannelName(channelName)
        synchronized(openRoomCounts) {
            val count = openRoomCounts[channel] ?: return
            if (count <= 1) openRoomCounts.remove(channel) else openRoomCounts[channel] = count - 1
        }
    }

    private fun isRoomOpen(channel: String): Boolean = synchronized(openRoomCounts) { channel in openRoomCounts }

    /** When the Rooms list last checked each room against the indexer. */
    private val listCheckedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var listCheckJob: kotlinx.coroutines.Job? = null

    /**
     * The Rooms list is on screen (iOS d089011f `checkListedRoomsAgainstIndexer`): each indexed
     * room it shows that nothing else keeps current (bell off, not open) is read from the indexer
     * once, at most every 2 minutes, so a message deleted there leaves the row's preview too, and
     * new messages show. One at a time; bell-on rooms are on the foreground sweep already. Only
     * from the screen, so never in the background.
     */
    fun checkListedRoomsAgainstIndexer(rooms: List<BroadcastChannelEntity>) {
        if (!isSignedIn() || listCheckJob?.isActive == true) return
        val due = IndexerAbsence.roomsDueForListCheck(
            rooms = rooms.map { MessageProtocol.normalizeChannelName(it.channelName) to it.notifyEnabled },
            isOpen = ::isRoomOpen,
            isIndexed = FeaturedBroadcastChannels::isIndexerTracked,
            isExcluded = { it in FeaturedBroadcastChannels.SERVICE_CHANNELS || it in FeaturedBroadcastChannels.HIDDEN_INDEXER_ROOMS },
            lastCheckedAt = { listCheckedAt[it] },
            now = System.currentTimeMillis(),
        )
        if (due.isEmpty()) return
        listCheckJob = pushFetchScope.launch {
            for (name in due) {
                if (!isSignedIn()) break
                listCheckedAt[name] = System.currentTimeMillis()
                firstMissRemovesChannels.add(name)
                try {
                    fetchNewestAndMerge(name, 40)
                } finally {
                    // unless the room opened meanwhile: its own first read keeps the one miss
                    if (!isRoomOpen(name)) firstMissRemovesChannels.remove(name)
                }
            }
        }
    }
    /**
     * One pass over the cached public-chat rows stored before sender verification existed
     * (audit XP-012): those took output 0's address (live scan) or the indexer's guess as the
     * sender. Each is checked against the chain once: verified rows keep (or get corrected to)
     * the real sender, forged rows are deleted. Rows the REST API answers for but doesn't know are
     * deleted too (retry, then drop). If the REST API can't be reached the pass is tried again on
     * a later launch, up to [REVERIFY_MAX_ATTEMPTS] times, after which unverifiable rows go.
     * Returns true once the pass is complete.
     */
    suspend fun reverifyCachedSenders(attempt: Int): Boolean {
        val rows = database.broadcastDao().getAllSentMessages()
        if (rows.isEmpty()) return true
        // The last allowed attempt settles every row, reachable REST API or not.
        val giveUp = attempt >= REVERIFY_MAX_ATTEMPTS - 1
        var complete = true
        for (chunk in rows.chunked(REVERIFY_CHUNK)) {
            val verdicts = senderVerifier.verifyRows(chunk.map { it.id }, channel = "cached")
            if (verdicts.lookupFailed && !giveUp) {
                complete = false
                continue
            }
            for (row in chunk) {
                val judged = verdicts[row.id]
                when (judged.verdict) {
                    com.kachat.app.services.BroadcastSenderVerifier.Verdict.VERIFIED -> {
                        val sender = judged.senderAddress ?: continue
                        if (sender != row.senderAddress) {
                            database.broadcastDao().insertMessage(row.copy(senderAddress = sender))
                        }
                    }
                    com.kachat.app.services.BroadcastSenderVerifier.Verdict.FORGED -> database.broadcastDao().deleteMessage(row.id)
                    com.kachat.app.services.BroadcastSenderVerifier.Verdict.UNKNOWN -> {
                        if (!verdicts.wasAnswered(row.id) && !giveUp) complete = false
                        else database.broadcastDao().deleteMessage(row.id)
                    }
                }
            }
        }
        return complete
    }

    /** When the app-wide indexer was last asked which rooms it tracks: (base URL, millis). */
    @Volatile
    private var trackedRoomsCheckedAt: Pair<String, Long>? = null

    /**
     * Asks the app-wide public chat indexer which rooms it tracks (`/broadcast-channels`), at
     * most once an hour per indexer - on app open and wallet switch. An indexer without the
     * endpoint answers nothing and the set keeps what it learned from served rows
     * (iOS `PublicChatService.refreshIndexerTrackedRooms`, 21c480d). [maxAgeMs] is shorter where
     * a new room is looked for (Edit Default Public Chats: a minute, iOS 961fc05a); a new list
     * joins its rooms at once ([com.kachat.app.services.publicchats.PublicChatDefaultRooms]).
     */
    suspend fun refreshIndexerTrackedRooms(maxAgeMs: Long = TRACKED_ROOMS_CHECK_INTERVAL_MS) {
        val base = settings.broadcastIndexerUrl.first().trim()
        if (base.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            val last = trackedRoomsCheckedAt
            if (last != null && last.first == base && now - last.second < maxAgeMs) return
            trackedRoomsCheckedAt = base to now
        }
        val api = broadcastIndexers.broadcastIndexerApiFor(base) ?: return
        val channels: List<String>? = try {
            // Only names a room could have, and no more of them than an indexer could track (iOS
            // audit 2026-11-21 #19).
            api.getTrackedChannels().channels?.filterNotNull()
                ?.filter { com.kachat.app.util.MessageProtocol.isValidChannelName(com.kachat.app.util.MessageProtocol.normalizeChannelName(it)) }
                ?.take(1_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BroadcastRepository", "Tracked rooms not listed by $base: ${e.message}")
            null
        }
        if (channels == null) return
        com.kachat.app.services.IndexerTrackedRooms.setReported(channels)
    }

    /** The newest chain time stored for [channelName], or null for an empty room. */
    suspend fun newestBlockTimestamp(channelName: String): Long? =
        database.broadcastDao().newestBlockTimestamp(channelName, networkSenderPattern())

    /**
     * Room Info's check after a Save: asks [baseUrl] for one row of [channelName] and returns how
     * many came back (iOS `PublicChatRoomInfoView.saveIndexer`, limit 1). Throws when it can't be
     * reached. A URL Retrofit can't take is refused here: [NetworkService.broadcastIndexerApiFor]
     * would quietly fall back to the app-wide indexer and test that one instead.
     */
    suspend fun probeIndexer(baseUrl: String, channelName: String): Int {
        val trimmed = baseUrl.trim()
        // Never asked over http (the app's https-only rule for every server address).
        if (isCleartextHttp(trimmed)) throw IllegalArgumentException(HTTPS_REQUIRED)
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        if (withSlash.toHttpUrlOrNull() == null) throw IllegalArgumentException("unsupported URL")
        val api = broadcastIndexers.broadcastIndexerApiFor(trimmed) ?: throw IllegalArgumentException("unsupported URL")
        return api.getBroadcasts(channel = channelName, limit = 1).messages.orEmpty().size
    }

    /**
     * [channelName]'s own indexer, or null when it follows the app-wide one. An http:// override
     * an older build saved is ignored - the room reads from the app-wide indexer instead - since
     * Room Info no longer takes one.
     */
    private suspend fun roomIndexerOverride(channelName: String): String? =
        settings.broadcastIndexerOverrides.first()[channelName.trim().lowercase()]
            ?.takeUnless { isCleartextHttp(it) }

    suspend fun backfillFromIndexer(channelName: String): Int {
        // This room's own indexer where it has one, the app-wide client otherwise. A broadcast is
        // on-chain, so any indexer watching the same network serves the same room.
        val api = indexerApi(channelName) ?: return -1
        // The arena's indexer down: one request a minute finds out when it's back (iOS da59726).
        if (skipsIndexer(channelName)) return -1
        // The arena keeps its own health; any other room's read tells the message indexer's.
        val isArena = channelName == ARENA
        val base = indexerBaseUrl(channelName)
        val askedAt = System.currentTimeMillis()
        repageIfPollingLapsed(channelName, askedAt)
        var fetched = 0
        var unverified = 0
        var recentUnknown = false
        // Every answer below is for the account this started under: after a log out or switch it
        // is dropped, not merged into the next account's backfill state (iOS b78fdb0
        // `fetchFromIndexerAndMerge`). The arena's rows are the arena's, whoever is signed in. A
        // cancelled poll throws at its next suspension.
        val turn = currentWalletTurn()
        fun stillWanted(): Boolean = isArena || isCurrentWallet(turn)

        val appWideServes = isAppWideIndexer(base)

        /** Merges one page: rows back from the app-wide indexer mean it keeps this room's history
         *  (iOS 21c480d, b78fdb0), and each is stored only with a verified sender (audit XP-012). */
        suspend fun merge(messages: List<com.kachat.app.services.BroadcastHistoryRow>) {
            if (messages.isNotEmpty() && appWideServes) com.kachat.app.services.IndexerTrackedRooms.markServed(channelName)
            val admitted = admitIndexerRows(channelName, messages, stillWanted = { stillWanted() })
            unverified += admitted.unknown
            recentUnknown = recentUnknown || admitted.recentUnknown
            fetched += messages.size
        }

        // The newest page first, on its own: it is what says whether the indexer is answering.
        // The one-time deep backfill pages at 200; the recurring 8s poll only needs the newest
        // sliver (dedupe-by-txId makes overlap harmless).
        val response = try {
            indexerPage(api, channelName, if (channelName in deepBackfilledChannels) 30 else 200, before = null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort on top of live scanning - the loop just tries again next tick.
            if (!isArena) noteIndexerResult(base, e)
            android.util.Log.w("BroadcastRepository", "Indexer fetch failed for $channelName", e)
            // Confirmed down: no history is coming, so leftover sends are settled now rather than
            // saying "Sending" with no Retry all session (iOS 369794e).
            if (!isArena && stillWanted() && base in _unreachableIndexers.value) {
                settlePendingSendsFromBeforeThisRun(channelName, indexerDown = true)
            }
            return -1
        }
        if (!isArena) noteIndexerResult(base, null)
        if (!stillWanted()) return -1
        val newest = response.messages.orEmpty()
        // The newest page goes in FIRST, before any history is paged (iOS fetchFromIndexerAndMerge).
        merge(newest)
        if (!stillWanted()) return -1
        // What the indexer no longer has in this page's span goes (iOS 2026-11-25).
        if (!isArena) dropRowsTheIndexerNoLongerHas(newest, before = null, base = base, channelName = channelName, turn = turn)
        if (!stillWanted()) return -1
        // The newest page is in: the polling's cover reaches to when it was asked for.
        indexerCoveredUntil[channelName] = askedAt
        val newestTimes = newest.mapNotNull { it.blockTime }
        val oldestNewest = newestTimes.minOrNull()
        // How far back the rows in the store run without a gap, as of this answer (iOS 077d777).
        var coveredFrom: Long? = if (response.hasMore == true) (oldestNewest ?: askedAt) else 0L
        // More arrived since the last poll than the page holds (a busy room, or the app away
        // for a while): page back to the rows already seen (iOS ee5ec98).
        val seen = indexerNewestSeen[channelName]
        if (channelName in deepBackfilledChannels && response.hasMore == true &&
            seen != null && oldestNewest != null && oldestNewest > seen) {
            requestRepage(channelName, seen)
        }
        newestTimes.maxOrNull()?.let { newestTime ->
            indexerNewestSeen[channelName] = maxOf(newestTime, seen ?: newestTime)
        }
        var interrupted = false
        if (channelName !in deepBackfilledChannels) {
            val cutoff = System.currentTimeMillis() - BroadcastRetention.INDEXER_MILLIS
            // Picking up an interrupted pager: everything newer than the recorded cursor is
            // already in the store from the attempt that recorded it.
            val resume = deepBackfillResume[channelName]
            var hasMore = resume != null || response.hasMore == true
            // From the oldest millisecond again (see the cursor in the loop below).
            var oldest: Long? = resume?.first ?: oldestNewest?.let { it + 1 }
            var pagesLeft = resume?.second ?: 50 // 50 × 200 = 10k rows, far beyond any real room
            if (resume != null) coveredFrom = coveredFrom?.let { minOf(it, resume.first) }
            val floor = maxOf(cutoff, deepBackfillFloor[channelName] ?: cutoff)
            var pagedSome = false
            while (hasMore && pagesLeft > 0) {
                val before = oldest ?: break
                if (before <= floor) break // older pages are pruned anyway, or already right
                pagesLeft -= 1
                val page = try {
                    indexerPage(api, channelName, 200, before, isBackfill = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!stillWanted()) return -1
                    // Keep what this attempt did get: the cursor recorded after each landed
                    // page is where the next 8s tick resumes, not page 1.
                    android.util.Log.w("BroadcastRepository", "Indexer backfill paused for $channelName at before=$before", e)
                    interrupted = true
                    if (deepBackfillResume[channelName] == null) deepBackfillResume[channelName] = before to (pagesLeft + 1)
                    break
                }
                pagedSome = true
                if (!stillWanted()) return -1
                val messages = page.messages.orEmpty()
                if (messages.isEmpty()) break
                merge(messages)
                if (!stillWanted()) return -1
                if (!isArena) dropRowsTheIndexerNoLongerHas(messages, before = before, base = base, channelName = channelName, turn = turn)
                if (!stillWanted()) return -1
                hasMore = page.hasMore == true
                // The indexer's `before` is exclusive (`block_time < before`), and rows of one
                // block share a millisecond: those cut off at a page's edge were skipped. So the
                // next page asks from the oldest millisecond again (rows dedupe by txid). A page
                // that is all that millisecond made no progress, and steps past it instead
                // (iOS 369794e).
                val pageOldest = messages.mapNotNull { it.blockTime }.minOrNull()
                oldest = if (pageOldest != null && pageOldest + 1 < before) pageOldest + 1 else pageOldest
                if (coveredFrom != null) coveredFrom = if (hasMore) (pageOldest ?: before) else 0L
                val next = oldest
                if (hasMore && next != null) deepBackfillResume[channelName] = next to pagesLeft
            }
            if (interrupted) {
                // A page that fails attempt after attempt, with nothing paged past it, would hold
                // the backfill - and the arena's joins and timeout claims, which wait for it -
                // forever. After a few such attempts it's passed over: the rows older than it are
                // left out, and everything newer is in (iOS 077d777).
                val failures = if (pagedSome) 1 else (failedBackfillResumes[channelName] ?: 0) + 1
                failedBackfillResumes[channelName] = failures
                if (failures >= MAX_FAILED_BACKFILL_RESUMES) {
                    android.util.Log.w(
                        "BroadcastRepository",
                        "Deep backfill for $channelName gave up: older rows are left out this session",
                    )
                    interrupted = false
                }
            }
            // Marked done only once the pager actually finishes; a paused one keeps its resume
            // cursor and the next poll continues from there.
            if (!interrupted) {
                deepBackfillResume.remove(channelName)
                failedBackfillResumes.remove(channelName)
                // Rows whose sender could not be verified yet (the REST API had no answer) were
                // left out: page the history again on a later poll - rows already stored cost no
                // lookup - a few times before settling for what verified.
                val retries = deepUnverifiedRetries[channelName] ?: 0
                if (unverified > 0 && retries < DEEP_UNVERIFIED_RETRIES) {
                    deepUnverifiedRetries[channelName] = retries + 1
                } else {
                    synchronized(arenaLock) {
                        deepBackfilledChannels.add(channelName)
                        deepBackfillFloor.remove(channelName)
                    }
                    deepUnverifiedRetries.remove(channelName)
                    // The whole history is in, so a send that did land has its copy to match
                    // (iOS e5a9b11).
                    if (!isArena) settlePendingSendsFromBeforeThisRun(channelName)
                }
            }
        }
        // Rows left out for an unverified sender are a gap in the store: this answer says nothing
        // about how far back it runs. A row that just happened isn't in the store yet: not fresh
        // at all (see fetchNewestAndMerge).
        if (unverified > 0) coveredFrom = null
        if (!recentUnknown) noteArenaAnswered(channelName, askedAt, coveredFrom)
        return if (interrupted) -1 else fetched
    }

    // MARK: - Own service-room rows on this phone's clock (iOS 8d5f925, 4922804, da59726)

    /**
     * Rows this phone sent to a service room (the chess arena) whose block time is still this
     * phone's own clock: the chain's time has not reached us yet. The arena orders these by the
     * moves around them, never by the local stamp (iOS `locallyStampedTxIds`).
     */
    val locallyStampedTxIds: Set<String> get() = localStampSentAt.keys.toSet()

    private val localStampPrefs = appContext.getSharedPreferences(LOCAL_STAMPS_PREFS, android.content.Context.MODE_PRIVATE)

    /** The same rows, by when this phone started waiting for them (wall ms). Kept across launches,
     *  so a row the network dropped before a relaunch is still found out
     *  ([checkUnconfirmedServiceRows]); a relaunch restarts the wait (iOS 4922804). */
    private val localStampSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val localStampNextCheck = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val checkingLocalStamps = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The account [localStampSentAt] belongs to: stamps are per account (iOS da59726). */
    @Volatile
    private var localStampWallet: String? = null

    private fun setLocalStampWallet(wallet: String?) {
        if (wallet == localStampWallet) return
        localStampWallet = wallet
        localStampSentAt.clear()
        localStampNextCheck.clear()
        if (wallet == null) return
        val now = System.currentTimeMillis()
        localStampPrefs.getStringSet(localStampsWalletKey(wallet), emptySet()).orEmpty().forEach { localStampSentAt[it] = now }
    }

    private fun saveLocalStamps() {
        val wallet = localStampWallet ?: return
        localStampPrefs.edit().putStringSet(localStampsWalletKey(wallet), localStampSentAt.keys.toSet()).apply()
    }

    /** The row still carries this phone's clock: the chain's time has not reached it. */
    fun isLocallyStamped(txId: String): Boolean = localStampSentAt.containsKey(txId)

    private fun trackLocalStamp(txId: String) {
        localStampSentAt[txId] = System.currentTimeMillis()
        saveLocalStamps()
    }

    /** The chain (a scanned block or the indexer) showed the row: it carries the chain's time now. */
    fun confirmLocalStamp(txId: String) {
        if (localStampSentAt.remove(txId) == null) return
        localStampNextCheck.remove(txId)
        saveLocalStamps()
    }

    private sealed class TransactionLookup {
        data class Accepted(val blockTime: Long?) : TransactionLookup()
        data object NotAccepted : TransactionLookup()
        data object Unanswered : TransactionLookup()
    }

    /** The REST API's view of a transaction: accepted (with its block's time), unknown or not
     *  accepted, or no answer (iOS 4922804 `lookUpTransaction`). */
    private suspend fun lookUpTransaction(txId: String): TransactionLookup {
        val rest = networkService.kaspaRestApi.value ?: return TransactionLookup.Unanswered
        return try {
            val answer = kotlinx.coroutines.withTimeoutOrNull(8_000L) { rest.getTransactionAcceptance(txId) }
                ?: return TransactionLookup.Unanswered
            if (answer.isAccepted != true) return TransactionLookup.NotAccepted
            // The block's time, asked separately: the acceptance answer carries none here.
            val blockTime = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) { rest.getTransactionPayload(txId) }?.blockTime
            }.getOrNull()
            TransactionLookup.Accepted(blockTime)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) TransactionLookup.NotAccepted else TransactionLookup.Unanswered
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TransactionLookup.Unanswered
        }
    }

    /** Whether the REST API shows this transaction accepted (a bot stamp, iOS da59726). */
    suspend fun isTransactionAccepted(txId: String): Boolean = lookUpTransaction(txId) is TransactionLookup.Accepted

    /**
     * Own service-room rows the chain has not shown [olderThanMs] after they were sent (iOS
     * 4922804, da59726 `checkUnconfirmedServiceRows`, about 75 s for the arena). Each is looked up:
     * accepted (REST API), it takes the block's time there; in a mempool or spent to a coin the
     * address holds, it waits or counts as landed; found nowhere by a node that answered, once the
     * REST API has had [REST_LAG_GRACE_MS] to catch up, it is marked failed, so the arena's reducer
     * stops applying it. A row the network cannot be asked about is given up after
     * [giveUpAfterMs]. A failed row the chain shows later takes its block time and counts again
     * (BroadcastScanningService). Returns the ids marked failed.
     */
    suspend fun checkUnconfirmedServiceRows(olderThanMs: Long, giveUpAfterMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        val due = localStampSentAt.entries
            .filter { now - it.value > olderThanMs && (localStampNextCheck[it.key] ?: 0L) <= now }
            .map { it.key to it.value }
        if (due.isEmpty() || !checkingLocalStamps.compareAndSet(false, true)) return emptySet()
        val restLagGrace = minOf(REST_LAG_GRACE_MS, olderThanMs + REST_LAG_PAST_LOOK_UP_MS)
        try {
            val failed = mutableSetOf<String>()
            for ((txId, sentAt) in due) {
                val lookup = lookUpTransaction(txId)
                // The chain showed it while the lookup ran.
                if (localStampSentAt[txId] == null) continue
                val later = System.currentTimeMillis() + 15_000L
                // Asked again well before the grace runs out, so the drop lands at it (iOS 077d777).
                val nextCheck = minOf(later, sentAt + restLagGrace + 1_000L)
                when (lookup) {
                    is TransactionLookup.Accepted -> {
                        confirmLocalStamp(txId)
                        val blockTime = lookup.blockTime
                        val existing = database.broadcastDao().getMessage(txId)
                        if (blockTime != null && existing != null &&
                            (existing.blockTimestamp != blockTime || existing.deliveryStatus != "sent")) {
                            database.broadcastDao().insertMessage(existing.copy(blockTimestamp = blockTime, deliveryStatus = "sent"))
                        }
                        continue
                    }
                    TransactionLookup.Unanswered -> if (now - sentAt <= giveUpAfterMs) {
                        localStampNextCheck[txId] = later
                        continue
                    }
                    TransactionLookup.NotAccepted -> {
                        if (nodePoolManager.isInMempool(txId)) {
                            localStampNextCheck[txId] = later
                            continue
                        }
                        // No mempool entry also comes back when no node could be asked, and a 404
                        // from a REST API running behind: neither says the transaction is gone.
                        val utxos = myAddress()?.let { nodePoolManager.getUtxosByAddress(it) }
                        if (utxos != null) {
                            if (utxos.any { it.outpoint.transactionId == txId }) {
                                confirmLocalStamp(txId)
                                continue
                            }
                            if (now - sentAt <= restLagGrace) {
                                localStampNextCheck[txId] = maxOf(nextCheck, System.currentTimeMillis() + 1_000L)
                                continue
                            }
                        } else if (now - sentAt <= giveUpAfterMs) {
                            localStampNextCheck[txId] = later
                            continue
                        }
                    }
                }
                // The chain showed it, or the account changed, while the lookups ran.
                if (localStampSentAt[txId] == null) continue
                android.util.Log.w("BroadcastRepository", "Own service-room tx ${txId.take(12)} never reached the chain: dropped")
                localStampSentAt.remove(txId)
                localStampNextCheck.remove(txId)
                saveLocalStamps()
                // The DAO flow re-emits, and the arena's reducer only applies rows that are "sent".
                database.broadcastDao().updateMessageStatus(txId, "failed")
                failed += txId
            }
            return failed
        } finally {
            checkingLocalStamps.set(false)
        }
    }

    // MARK: - The arena from the REST API, while its indexer is down (iOS da59726)

    private data class ChainArenaRow(val txId: String, val sender: String, val content: String, val blockTime: Long)

    /**
     * One arena game's messages from its known players, read by address from the Kaspa REST API,
     * for while the indexer is down (iOS `readArenaFromChain`). A room has no address of its own,
     * so this can only fill in a game whose players this phone already knows. Only rows the store
     * doesn't hold go in, so a time the indexer gave is left alone. [tournamentId] is matched by
     * [belongsToGame] on each row's content (the chess codec's game id), so this service needs
     * no chess code. True when every player's address answered.
     */
    suspend fun readArenaFromChain(
        players: List<String>,
        since: Long,
        belongsToGame: (String) -> Boolean,
    ): Boolean {
        val found = mutableListOf<ChainArenaRow>()
        var complete = true
        for (address in players) {
            val rows = chainArenaRows(address, since, belongsToGame)
            if (rows == null) complete = false else found += rows
        }
        mergeChainArenaRows(found)
        return complete
    }

    private suspend fun mergeChainArenaRows(found: List<ChainArenaRow>) {
        if (found.isEmpty()) return
        val hidden = hiddenAddressesIn(ARENA, getHiddenSenders().first())
        val dao = database.broadcastDao()
        for (row in found) {
            if (row.sender in hidden) continue
            val inserted = dao.insertMessageIfAbsent(
                BroadcastMessageEntity(
                    id = row.txId,
                    channelName = ARENA,
                    senderAddress = row.sender,
                    content = row.content,
                    blockTimestamp = row.blockTime,
                    deliveryStatus = "sent",
                )
            ) != -1L
            if (inserted) continue
            // Our own rows still on this phone's clock, or given up on, take the chain's time.
            val existing = dao.getMessage(row.txId) ?: continue
            if (!isLocallyStamped(row.txId) && existing.deliveryStatus != "failed") continue
            confirmLocalStamp(row.txId)
            if (existing.blockTimestamp != row.blockTime || existing.deliveryStatus != "sent") {
                dao.insertMessage(existing.copy(blockTimestamp = row.blockTime, deliveryStatus = "sent"))
            }
        }
    }

    /**
     * [address]'s accepted posts to the arena since [since], newest pages first until they're
     * older than that. The author rule is the block scan's (XP-012): input 0 spends from the
     * address output 0 pays. Null when the REST API didn't answer.
     */
    private suspend fun chainArenaRows(address: String, since: Long, belongsToGame: (String) -> Boolean): List<ChainArenaRow>? {
        val rest = networkService.kaspaRestApi.value ?: return null
        val me = address.lowercase()
        val found = mutableListOf<ChainArenaRow>()
        for (page in 0 until CHAIN_MAX_PAGES) {
            val rows = try {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    rest.getAddressHistoryRows(address = me, limit = CHAIN_PAGE_SIZE, offset = page * CHAIN_PAGE_SIZE)
                } ?: return null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            var reachedSince = false
            for (row in rows) {
                val blockTime = runCatching { row.get("block_time")?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: continue
                if (blockTime < since) {
                    reachedSince = true
                    continue
                }
                val parsed = chainArenaRow(row, address, me, blockTime) ?: continue
                if (belongsToGame(parsed.content)) found += parsed
            }
            if (rows.size < CHAIN_PAGE_SIZE || reachedSince) break
        }
        return found
    }

    /** One raw REST row as an arena row, or null: not accepted, not this address's own post (by
     *  the author rule), or not the arena. Read defensively, a field at a time. */
    private fun chainArenaRow(row: com.google.gson.JsonObject, address: String, me: String, blockTime: Long): ChainArenaRow? = runCatching {
        if (row.get("is_accepted")?.takeIf { !it.isJsonNull }?.asBoolean != true) return@runCatching null
        val txId = row.get("transaction_id")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
        fun lowestIndex(array: com.google.gson.JsonArray?, field: String): String? = array
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.minByOrNull { it.get("index")?.takeIf { e -> !e.isJsonNull }?.asInt ?: 0 }
            ?.get(field)?.takeIf { !it.isJsonNull }?.asString?.lowercase()
        val inputs = row.get("inputs")?.takeIf { it.isJsonArray }?.asJsonArray
        val outputs = row.get("outputs")?.takeIf { it.isJsonArray }?.asJsonArray
        if (lowestIndex(inputs, "previous_outpoint_address") != me) return@runCatching null
        if (lowestIndex(outputs, "script_public_key_address") != me) return@runCatching null
        val hex = row.get("payload")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
        val payload = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val parsed = MessageProtocol.parseBcastPayload(payload) ?: return@runCatching null
        if (MessageProtocol.normalizeChannelName(parsed.channel) != ARENA) return@runCatching null
        ChainArenaRow(txId, address, parsed.content, blockTime)
    }.getOrNull()

    // MARK: - Sending

    /**
     * Builds and submits one room message: a zero-amount self-send carrying
     * `kchat:1:bcast:<room>:<content>`, from this account's chatting address. A send that finds no
     * coin free yet throws [NoCoinFreeYetException] (iOS `isNoConfirmedInputsError`). [stillWanted]
     * is asked right before the send: false (the account changed) sends nothing.
     */
    private suspend fun submitBroadcast(
        channelName: String,
        content: String,
        feeRateOverride: Long? = null,
        stillWanted: (() -> Boolean)? = null,
    ): String {
        if (stillWanted != null && !stillWanted()) throw SenderChangedException()
        val payloadBytes = MessageProtocol.buildBcastPayload(channelName, content)
        return try {
            walletService.sendKaspa(
                toAddress = walletManager.getAddress(),
                amountSompi = 0,
                payloadBytes = payloadBytes,
                feeRateOverride = feeRateOverride,
            )
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (com.kachat.app.services.ChatPaymentSender.isNoConfirmedInputsError(e.message)) throw NoCoinFreeYetException()
            throw e
        }
    }

    /**
     * A room message (iOS `sendPublicChat(channel:content:feeOverride:deferNoInputRetry:
     * expectedSender:)`). Returns the transaction id, or null when it found no coin free yet and
     * [deferNoInputRetry] left it Sending, to go again by itself.
     *
     * Inserts an optimistic "pending" placeholder immediately (before the network call) so the
     * message shows up in the room right away and stays visible even if the send fails. On success
     * the placeholder is swapped for the real message (real txId, "sent"); on failure it flips to
     * "failed" in place with a Retry option. [dropRowOnFailure]: an edit, whose row goes instead -
     * see [sendEdit]. Reactions and edits fail at once on no coin, as iOS.
     *
     * [expectedSender] (the chess sends): the account that asked for this send. When the signed-in
     * account is another one, nothing is sent ([SenderChangedException]), so a move, join, claim
     * or stamp never goes out signed by the next account (iOS 077d777). A service-room row (the
     * arena) is stamped with this phone's clock until the chain's time comes back
     * ([locallyStampedTxIds]), and one whose outcome is unknown is kept as sent - a move's ply stays
     * taken - for [checkUnconfirmedServiceRows] to settle (iOS 4922804).
     */
    suspend fun sendPublicChat(
        channelName: String,
        content: String,
        feeRateOverride: Long? = null,
        deferNoInputRetry: Boolean = false,
        expectedSender: String? = null,
        dropRowOnFailure: Boolean = false,
    ): String? {
        val myAddress = walletManager.getAddress()
        if (expectedSender != null && myAddress != expectedSender) throw SenderChangedException()
        val isServiceRoom = channelName in FeaturedBroadcastChannels.SERVICE_CHANNELS
        val pendingId = "pending_${java.util.UUID.randomUUID()}"
        // This run is working on it, so the leftover-pending settle leaves it alone (iOS e5a9b11).
        pendingSendsThisRun.add(pendingId)
        val pendingRow = BroadcastMessageEntity(
            id = pendingId,
            channelName = channelName,
            senderAddress = myAddress,
            content = content,
            blockTimestamp = System.currentTimeMillis(),
            deliveryStatus = "pending"
        )
        database.broadcastDao().insertMessage(pendingRow)
        // Asked again right before the send: an account switch meanwhile sends nothing (iOS 077d777).
        val stillWanted: (() -> Boolean)? = expectedSender?.let { expected ->
            { runCatching { walletManager.getAddress() }.getOrNull() == expected }
        }
        try {
            val txId = submitBroadcast(channelName, content, feeRateOverride, stillWanted)
            pendingSendsThisRun.remove(pendingId)
            // Signed here, so its sender is known when it comes back from the chain (XP-012).
            senderVerifier.rememberOwnBroadcast(txId, myAddress)
            if (isServiceRoom) trackLocalStamp(txId)
            database.broadcastDao().deleteMessage(pendingId)
            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = channelName,
                    senderAddress = myAddress,
                    content = content,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
            return txId
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            if (isServiceRoom && e.txId.isNotBlank()) {
                // A node may hold it: kept as sent on this phone's clock, and settled by
                // [checkUnconfirmedServiceRows] - the chain shows it, or it is dropped (iOS 4922804).
                pendingSendsThisRun.remove(pendingId)
                senderVerifier.rememberOwnBroadcast(e.txId, myAddress)
                trackLocalStamp(e.txId)
                database.broadcastDao().deleteMessage(pendingId)
                database.broadcastDao().insertMessage(pendingRow.copy(id = e.txId, deliveryStatus = "sent"))
                throw e
            }
            // It may have landed, so no Retry, which would post and pay twice. "Not confirmed"
            // until its delivered copy shows up (iOS 33095ac). A reaction or an edit is a row
            // here too, so it is marked the same way.
            markMessageUnconfirmed(pendingId, e.txId)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never "failed": it may have gone out, and a Retry could post and pay twice. Left
            // pending for the settle, like an unknown outcome.
            throw e
        } catch (e: NoCoinFreeYetException) {
            if (deferNoInputRetry && !dropRowOnFailure && expectedSender == null) {
                // Stays Sending and goes again once a coin is free (iOS `handleSendFailure`).
                scheduleNoCoinRetry(pendingRow, keepTime = false, delayMs = nextNoCoinRetryDelayMs(pendingId))
                return null
            }
            // No send is working on it any more, so its Retry may start one (iOS 369794e).
            pendingSendsThisRun.remove(pendingId)
            if (dropRowOnFailure) {
                database.broadcastDao().deleteMessage(pendingId)
            } else {
                database.broadcastDao().updateMessageStatus(pendingId, "failed")
            }
            throw e
        } catch (e: Exception) {
            // No send is working on it any more, so its Retry may start one (iOS 369794e).
            pendingSendsThisRun.remove(pendingId)
            if (dropRowOnFailure || e is SenderChangedException) {
                database.broadcastDao().deleteMessage(pendingId)
            } else {
                database.broadcastDao().updateMessageStatus(pendingId, "failed")
            }
            throw e
        }
    }

    /** [sendPublicChat] that never waits for a coin: a reaction, an edit, or a chess send.
     *  Always returns the transaction id, or throws. */
    suspend fun sendBroadcast(
        channelName: String,
        content: String,
        feeRateOverride: Long? = null,
        dropRowOnFailure: Boolean = false,
        expectedSender: String? = null,
    ): String = sendPublicChat(
        channelName = channelName,
        content = content,
        feeRateOverride = feeRateOverride,
        deferNoInputRetry = false,
        expectedSender = expectedSender,
        dropRowOnFailure = dropRowOnFailure,
    ) ?: throw NoCoinFreeYetException()

    /** Re-attempts a failed broadcast, reusing its same "pending_<uuid>" id/content — the same placeholder resurrected, not a new message, matching ChatViewModel.retrySendMessage. [deferNoCoinRetry]: a room message's Retry waits for a coin as its first send does (iOS `retryPublicChat` > `resendPending`); a reaction's doesn't. */
    suspend fun retryBroadcast(message: BroadcastMessageEntity, deferNoCoinRetry: Boolean = false) {
        // Only a failed send of this account's, and only once: a second tap before the list
        // re-emits would send it twice (iOS `retryPublicChatMessage` guards the same).
        if (message.senderAddress != walletManager.getAddress()) return
        // Pending again and worked on by this run, so the settle must not touch it (iOS e5a9b11).
        pendingSendsThisRun.add(message.id)
        if (database.broadcastDao().claimFailedForRetry(message.id) == 0) return
        try {
            sendPendingRow(message.copy(deliveryStatus = "pending"), keepTime = false, deferNoCoin = deferNoCoinRetry)
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            // May have landed: "Not confirmed" with no Retry (iOS 33095ac). Passed on so a room
            // message can say so; a reaction's retry says nothing, as iOS's `try?` does.
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Left pending, never "failed" (see [sendBroadcast]).
            throw e
        } catch (e: Exception) {
            // Failed again: [sendPendingRow] marked it, for the next Retry.
        }
    }

    /**
     * A long voice note (iOS 1b15521 `sendPublicChatVoiceParts`): each part a complete WebM-Opus
     * file sent as its own ordinary voice message, one transaction each, tagged `voicePart` with
     * one random id (KaChat's format, KaChat MESSAGING.md "Long voice notes (multi-part)"). Parts
     * belong together by (room, sender, id, count).
     *
     * The balance for every part is checked first. Every part then shows at once, Sending, a
     * millisecond apart so they keep their order, and they go out in index order, one after
     * another, so each spends the change of the one before. A part that finds no coin free yet
     * stays Sending and goes again by itself after a wait, the ones after it following the same way
     * (iOS: `isNoConfirmedInputsError` > `handleSendFailure`, `schedulePublicChatRetry`). When one
     * fails otherwise the ones after it are marked Failed too, for one Retry to send them in order.
     * Parts never carry a reply: the reply envelope would push a part past what one transaction
     * holds. [onQueued] runs once the parts show (the caller drops its reply then). A note of one
     * part goes as an ordinary voice message ([sendBroadcast]) instead.
     */
    suspend fun sendVoiceParts(channelName: String, parts: List<ByteArray>, onQueued: () -> Unit = {}) {
        if (parts.size < 2 || parts.size > com.kachat.app.util.VoicePartTag.MAX_PARTS || parts.any { it.isEmpty() }) {
            throw IllegalArgumentException("Audio file is empty")
        }
        val myAddress = walletManager.getAddress()
        val mimeType = "audio/webm"
        val noteId = com.kachat.app.util.VoicePartTag.newGroupId()
        // About 1 MB of base64 and JSON for 2:50, and the fee of each: off the main thread.
        val contents = kotlinx.coroutines.withContext(Dispatchers.Default) {
            val built = parts.mapIndexed { offset, data ->
                val tag = com.kachat.app.util.VoicePartTag.of(noteId, offset + 1, parts.size)
                    ?: throw IllegalArgumentException("Audio file is empty")
                com.kachat.app.util.BroadcastMediaFileEnvelope.json(
                    name = tag.fileName,
                    size = data.size,
                    mimeType = mimeType,
                    dataUrlContent = "data:$mimeType;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP),
                    voicePart = tag,
                )
            }
            // The whole note first, before a single part goes out.
            ensureSufficientBalanceForVoiceParts(channelName, built, myAddress)
            built
        }

        val dao = database.broadcastDao()
        val start = System.currentTimeMillis()
        val pending = contents.mapIndexed { offset, content ->
            BroadcastMessageEntity(
                id = "pending_${java.util.UUID.randomUUID()}",
                channelName = channelName,
                senderAddress = myAddress,
                content = content,
                blockTimestamp = start + offset,
                deliveryStatus = "pending",
            )
        }
        for (row in pending) {
            pendingSendsThisRun.add(row.id)
            dao.insertMessage(row)
        }
        onQueued()

        for ((offset, row) in pending.withIndex()) {
            // Logged out or switched away: the rest must not go out from this account under the
            // new one's session (iOS 369794e `isStillSignedIn`). Failed, for a Retry once it is
            // active again: the room table is shared, so a row left Sending would show so to the
            // next account all session.
            if (!isStillSignedIn(myAddress)) {
                failRemainingParts(pending.drop(offset))
                return
            }
            val sent = try {
                sendPendingRow(row, keepTime = true, deferNoCoin = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failRemainingParts(pending.drop(offset + 1))
                throw e
            }
            if (!sent) {
                // This part waits for a confirmed coin and retries itself; the rest follow it the
                // same way (iOS `sendPublicChatVoiceParts`).
                for (later in pending.drop(offset + 1)) {
                    scheduleNoCoinRetry(later, keepTime = true, delayMs = nextNoCoinRetryDelayMs(later.id))
                }
                return
            }
        }
    }

    /**
     * Retry on a long voice note's bubble (iOS 1b15521 `retryPublicChatVoiceParts`): only its
     * failed parts, one after another in index order, so each spends the change of the one
     * before. All of them show Sending at once; when one fails again, the ones after it go back to
     * Failed for the next Retry. One that finds no coin free yet waits and retries by itself, and
     * the rest follow it through their own waits, in order (iOS `resendPending`, b78fdb0). Says
     * nothing on a failure, as [retryBroadcast].
     */
    suspend fun retryVoiceParts(failedParts: List<BroadcastMessageEntity>) {
        val myAddress = walletManager.getAddress()
        val dao = database.broadcastDao()
        val claimed = ArrayList<BroadcastMessageEntity>()
        for (part in failedParts) {
            if (part.deliveryStatus != "failed" || part.senderAddress != myAddress) continue
            pendingSendsThisRun.add(part.id)
            // Only once: a second tap before the list re-emits would send it twice.
            if (dao.claimFailedForRetry(part.id) > 0) claimed.add(part.copy(deliveryStatus = "pending"))
        }
        for ((offset, part) in claimed.withIndex()) {
            // Sending stops between parts on log out or an account switch (iOS 369794e).
            if (!isStillSignedIn(myAddress)) {
                failRemainingParts(claimed.drop(offset))
                return
            }
            val went = try {
                sendPendingRow(part, keepTime = true, deferNoCoin = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BroadcastRepository", "Voice note part retry failed", e)
                failRemainingParts(claimed.drop(offset + 1))
                return
            }
            if (!went) {
                // This part waits for a confirmed coin and retries itself: the rest follow it the
                // same way, in order, as in [sendVoiceParts] - not straight away, ahead of it
                // (iOS b78fdb0 `retryPublicChatVoiceParts`).
                for (later in claimed.drop(offset + 1)) {
                    scheduleNoCoinRetry(later, keepTime = true, delayMs = nextNoCoinRetryDelayMs(later.id))
                }
                return
            }
        }
    }

    /**
     * Sends a row that is already pending, under its own id (iOS `resendPending`): true once it
     * went. With [deferNoCoin], one that finds no coin free yet ([NoCoinFreeYetException])
     * stays pending and is sent again by itself after a wait ([scheduleNoCoinRetry], iOS
     * `handleSendFailure`): false. Throws when it failed (the row is then Failed, or Not confirmed
     * for an unknown outcome). [keepTime]: the sent row keeps the pending row's time, so a voice
     * note's parts keep their order; otherwise it takes the send's.
     */
    private suspend fun sendPendingRow(row: BroadcastMessageEntity, keepTime: Boolean, deferNoCoin: Boolean): Boolean {
        val dao = database.broadcastDao()
        try {
            val txId = submitBroadcast(row.channelName, row.content)
            clearNoCoinRetryState(row.id)
            senderVerifier.rememberOwnBroadcast(txId, row.senderAddress)
            dao.deleteMessage(row.id)
            val sentAt = if (keepTime) row.blockTimestamp else System.currentTimeMillis()
            dao.insertMessage(row.copy(id = txId, blockTimestamp = sentAt, deliveryStatus = "sent"))
            return true
        } catch (e: com.kachat.app.services.SendOutcomeUnknownException) {
            clearNoCoinRetryState(row.id)
            markMessageUnconfirmed(row.id, e.txId)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Left pending, never "failed" (see [sendBroadcast]).
            throw e
        } catch (e: NoCoinFreeYetException) {
            if (deferNoCoin) {
                scheduleNoCoinRetry(row, keepTime, nextNoCoinRetryDelayMs(row.id))
                return false
            }
            clearNoCoinRetryState(row.id)
            // No send is working on it any more, so its Retry may start one (iOS 369794e).
            pendingSendsThisRun.remove(row.id)
            dao.updateMessageStatus(row.id, "failed")
            throw e
        } catch (e: Exception) {
            clearNoCoinRetryState(row.id)
            // No send is working on it any more, so its Retry may start one (iOS 369794e).
            pendingSendsThisRun.remove(row.id)
            dao.updateMessageStatus(row.id, "failed")
            throw e
        }
    }

    /** Tries so far per pending id of the wait for a free coin (iOS `noInputRetryCounts`). */
    private val noCoinRetryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Pending ids with a wait-and-retry scheduled (iOS `scheduledSendRetries`). */
    private val scheduledNoCoinRetries: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** iOS `nextNoInputRetryDelay`: 1, 2, 4 ... s, at most 60, each with 10-35 % added. */
    private fun nextNoCoinRetryDelayMs(pendingId: String): Long {
        val attempt = noCoinRetryCounts.merge(pendingId, 1) { old, one -> old + one } ?: 1
        val base = minOf(60.0, Math.pow(2.0, maxOf(0, attempt - 1).toDouble()))
        val jitter = kotlin.random.Random.nextDouble(0.10, 0.35)
        return ((base + base * jitter) * 1000.0).toLong()
    }

    /** iOS `clearNoInputRetryState`. */
    private fun clearNoCoinRetryState(pendingId: String) {
        noCoinRetryCounts.remove(pendingId)
        scheduledNoCoinRetries.remove(pendingId)
    }

    /**
     * iOS `schedulePublicChatRetry`: after [delayMs], sends [row] again if it is still pending
     * (nothing else settled it, and the same account is active, as iOS keeps the wallet it was
     * sent from); finding no coin again waits longer. Says nothing on a failure, as iOS's `try?`.
     * In [sendScope], so it outlives the room.
     */
    private fun scheduleNoCoinRetry(row: BroadcastMessageEntity, keepTime: Boolean, delayMs: Long) {
        if (!scheduledNoCoinRetries.add(row.id)) return
        android.util.Log.i("BroadcastRepository", "Deferred retry (no confirmed inputs) for ${row.id.take(12)} in ${delayMs / 1000}s")
        sendScope.launch {
            kotlinx.coroutines.delay(delayMs)
            scheduledNoCoinRetries.remove(row.id)
            val current = database.broadcastDao().getMessage(row.id)
            if (current == null || current.deliveryStatus != "pending") {
                clearNoCoinRetryState(row.id)
                return@launch
            }
            if (!isStillSignedIn(row.senderAddress)) {
                // Sent from another account, or logged out since (iOS 369794e
                // `isStillSignedIn`): Failed, for a Retry once it is active again.
                clearNoCoinRetryState(row.id)
                pendingSendsThisRun.remove(row.id)
                database.broadcastDao().updateMessageStatus(row.id, "failed")
                return@launch
            }
            try {
                sendPendingRow(current, keepTime, deferNoCoin = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BroadcastRepository", "Deferred room send failed", e)
            }
        }
    }

    /**
     * False once the account that started a send has logged out or been switched away from: the
     * rest of a long voice note must not go out from the old account under the new one's session
     * (iOS 369794e `isStillSignedIn`).
     */
    private fun isStillSignedIn(address: String): Boolean =
        myAddress() == address && !walletManager.isLoggedOut

    /** The parts after one that failed never went: Failed too, so one Retry sends them in order. */
    private suspend fun failRemainingParts(parts: List<BroadcastMessageEntity>) {
        val dao = database.broadcastDao()
        for (part in parts) {
            clearNoCoinRetryState(part.id)
            pendingSendsThisRun.remove(part.id)
            dao.updateMessageStatus(part.id, "failed")
        }
    }

    /**
     * What all of a note's parts cost together, against the coins there are, leaving the dust
     * threshold (0.2 KAS) over so the last part still has a change output to stand on (iOS
     * 1b15521 `ensureSufficientBalanceForVoiceParts`). A check that can't be made (the node
     * unreachable) lets the send go on and fail on its own.
     */
    private suspend fun ensureSufficientBalanceForVoiceParts(channelName: String, contents: List<String>, walletAddress: String) {
        var totalFee = 0L
        for (content in contents) {
            val payload = MessageProtocol.buildBcastPayload(channelName, content)
            val mass = com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34), payloadSize = payload.size)
            totalFee += com.kachat.app.util.KaspaMass.calculateFee(mass, null)
        }
        val utxos = try {
            walletEngine.fetchUtxosOrThrow(walletAddress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BroadcastRepository", "Voice note balance precheck unavailable, continuing send", e)
            return
        }
        val available = utxos.sumOf { it.utxoEntry.amount }
        if (available <= 0L) throw NoCoinFreeYetException()
        val required = totalFee + VOICE_NOTE_DUST_THRESHOLD_SOMPI
        if (available < required) {
            throw IllegalStateException(
                String.format(
                    java.util.Locale.US,
                    com.kachat.app.util.SendFailureHint.INSUFFICIENT_BALANCE_TEMPLATE,
                    kasAmount(required),
                    kasAmount(available),
                )
            )
        }
    }

    private fun kasAmount(sompi: Long): String =
        java.math.BigDecimal.valueOf(sompi).movePointLeft(8).stripTrailingZeros().toPlainString()

    // Last in the class: Kotlin runs initializers in order, and the collector reads the stamp maps
    // on another thread at once.
    init {
        // Before stamps were kept per account (iOS da59726).
        localStampPrefs.edit().remove(LOCAL_STAMPS_KEY).apply()
        setLocalStampWallet(walletManager.activeAddressFlow.value)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default).launch {
            walletManager.activeAddressFlow.collect { address -> setLocalStampWallet(address) }
        }
    }
}
