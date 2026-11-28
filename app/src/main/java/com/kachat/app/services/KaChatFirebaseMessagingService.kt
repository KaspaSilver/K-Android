package com.kachat.app.services

import com.kachat.app.R
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository.GroupControlPush
import com.kachat.app.repository.GroupRepository.GroupMessagePush
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Base64
import javax.inject.Inject

/**
 * Receives FCM messages and turns them into notifications via [NotificationHelper].
 *
 * Two shapes arrive from the server (`kasia-indexer` `push.rs`):
 *  - **Public content** (broadcast / KaPosts) is sent WITH a `notification` block, so the OS shows
 *    it even when the app is dead. Both are back in the app (iOS 148a5331): while it runs, a room
 *    push shows for a room still joined with its bell on, and a KaPost push by the KaPost
 *    switches and block list; a tap on one the OS drew opens the room or the post (MainActivity).
 *  - **Encrypted DM/group content** is sent **data-only** (no `notification` block) so this handler
 *    runs and can DECRYPT the body locally with the wallet key — mirroring iOS's Notification
 *    Service Extension. (Data-only needs the app wake-able; a force-closed app under OEM battery
 *    optimization may not fire — set the app's battery usage to Unrestricted.)
 *
 * Data schema:
 *   chat/DM   : type(contextual|payment|handshake|group_message|group_control), sender, title,
 *               body, tx_id, timestamp, daa_score, [amount], [enc_payload], [blinded_group_id]
 */
@AndroidEntryPoint
class KaChatFirebaseMessagingService : FirebaseMessagingService() {

    // All through Lazy: each needs the wallet store, directly or not, and Hilt injects in
    // onCreate - so with the account stores unreadable every push would end the process in the
    // background. Nothing is built until a handler has checked the stores open.
    @Inject lateinit var notificationHelperLazy: dagger.Lazy<NotificationHelper>
    @Inject lateinit var pushRegistrationManagerLazy: dagger.Lazy<PushRegistrationManager>
    @Inject lateinit var walletManagerLazy: dagger.Lazy<WalletManager>
    @Inject lateinit var chatRepositoryLazy: dagger.Lazy<ChatRepository>
    @Inject lateinit var groupRepositoryLazy: dagger.Lazy<com.kachat.app.repository.GroupRepository>
    @Inject lateinit var settingsRepositoryLazy: dagger.Lazy<com.kachat.app.repository.AppSettingsRepository>
    // Public Chats and KaPost (iOS 148a5331).
    @Inject lateinit var broadcastRepositoryLazy: dagger.Lazy<com.kachat.app.repository.BroadcastRepository>
    @Inject lateinit var broadcastNotifierLazy: dagger.Lazy<com.kachat.app.services.publicchats.BroadcastNotifier>
    @Inject lateinit var kaPostsNotifierLazy: dagger.Lazy<KaPostsNotifier>
    @Inject lateinit var kaPostsNotificationPollerLazy: dagger.Lazy<KaPostsNotificationPoller>
    // Received KAS pushes (iOS 428a797b).
    @Inject lateinit var addressActivityNotifierLazy: dagger.Lazy<AddressActivityNotifier>
    // A mention push from a room you haven't joined: its bell row (iOS audit 2026-11-27 L13).
    @Inject lateinit var roomMentionBellLazy: dagger.Lazy<RoomMentionBell>
    private val notificationHelper: NotificationHelper get() = notificationHelperLazy.get()
    private val pushRegistrationManager: PushRegistrationManager get() = pushRegistrationManagerLazy.get()
    private val walletManager: WalletManager get() = walletManagerLazy.get()
    private val chatRepository: ChatRepository get() = chatRepositoryLazy.get()
    private val groupRepository: com.kachat.app.repository.GroupRepository get() = groupRepositoryLazy.get()
    private val settingsRepository: com.kachat.app.repository.AppSettingsRepository get() = settingsRepositoryLazy.get()

    /** When the push being handled arrived: its chain reads retry until [CHAIN_READ_DEADLINE_MS]
     *  after it. Pushes are handled one at a time. */
    @Volatile private var pushReceivedAtMs = 0L

    /** [handleIntent]'s estimate of when the push it is handling reached the phone (audit
     *  2026-11-25 P4): one queued behind others arrived before its handling starts. */
    @Volatile private var intentArrivalMs = 0L

    /** The push being handled came before, by its txid or id ([PushArrivalRecord.record]): a
     *  KaPost tip's second push (iOS NotificationService `pushSeenBefore`). */
    @Volatile private var pushSeenBefore = false

    /**
     * Every push is recorded as it reaches the phone, before the system or [onMessageReceived]
     * draws it (iOS NotificationService `recordPushArrival`, 2026-11-22): the app then never
     * banners the same message again once it is open. Here rather than in [onMessageReceived],
     * which a push with a `notification` block (rooms, KaPost, Received KAS) never reaches while
     * the app is away - the system draws those itself, and the one tapped to open the app has left
     * the shade by the time the app looks.
     */
    override fun handleIntent(intent: android.content.Intent) {
        // FCM hands intents over one at a time, so in a burst (a voice note's parts, a busy group)
        // a push can wait seconds before its handling starts, and FCM's ~20 s window runs from its
        // arrival, not from here. Pushes handled back to back count as arrived no later than the
        // first of the run started, or than their own send time if that is later (P4).
        val startedAtMs = System.currentTimeMillis()
        if (startedAtMs - lastHandlerEndMs > QUEUE_GAP_MS) queueStartMs = startedAtMs
        val sentTimeMs = runCatching { intent.extras?.let { RemoteMessage(it).sentTime } }.getOrNull() ?: 0L
        intentArrivalMs = arrivalEstimate(startedAtMs, queueStartMs, sentTimeMs)
        try {
            val action = intent.action
            val messageType = runCatching { intent.getStringExtra("message_type") }.getOrNull()
            val isMessage = (action == "com.google.android.c2dm.intent.RECEIVE" || action == "com.google.firebase.messaging.RECEIVE_DIRECT_BOOT") &&
                (messageType == null || messageType == "gcm")
            if (isMessage) {
                pushSeenBefore = runCatching {
                    intent.extras?.let { PushArrivalRecord.record(applicationContext, RemoteMessage(it)) } ?: false
                }.getOrDefault(false)
                // A push the system would draw itself while the app is away that a switch has turned
                // off since the registration: dropped here, before it is drawn - Android's version of
                // iOS's push extension silencing it (iOS 8cb4c88e, audit 2026-11-24 notifications).
                // Recorded above all the same.
                val dropped = runCatching { intent.extras?.let { dropsBeforeTheSystemDraws(RemoteMessage(it)) } ?: false }
                    .getOrDefault(false)
                if (dropped) return
            }
            super.handleIntent(intent)
            // A KaPost tip push the system just drew (app away) after the tip's payment notice.
            runCatching { intent.extras?.let { afterSystemDrawnKaPostTip(RemoteMessage(it)) } }
            // A room push that @'s you from a room you haven't joined, now shown: its bell row.
            if (isMessage) runCatching { intent.extras?.let { recordUnjoinedRoomMention(RemoteMessage(it)) } }
        } finally {
            lastHandlerEndMs = System.currentTimeMillis()
        }
    }

    /**
     * A push that @'s you from a room you haven't joined (iOS audit 2026-11-27 L13): nothing loads
     * that room's messages, so the push itself becomes the bell's row once it's shown, whether the
     * system drew it (app away) or [onMessageReceived] did. Not for the other network, while
     * signed out, for an edit or delete, or for a message one of this phone's accounts sent.
     */
    private fun recordUnjoinedRoomMention(message: RemoteMessage) {
        val data = message.data
        val threadId = data["thread_id"].orEmpty()
        if (data["type"] != "broadcast" && !threadId.startsWith("broadcast:")) return
        if (!isTruePushFlag(data["mention"])) return
        if (!com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return
        if (com.kachat.app.util.PushNetworkFilter.isOtherNetwork(data, com.kachat.app.util.KaspaNetwork.launch)) return
        val raw = message.notification?.body?.takeIf { it.isNotBlank() } ?: data["body"].orEmpty()
        if (com.kachat.app.util.BroadcastPushPreview.isEditEnvelope(raw) || com.kachat.app.util.MessageDelete.isDeleteEnvelope(raw)) return
        val channel = data["channel"] ?: threadId.removePrefix("broadcast:")
        val senderAddress = data["sender"]?.trim().orEmpty()
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(PRE_DRAW_BUDGET_MS) {
                if (walletManager.isLoggedOut || walletManager.getActiveAccount() == null) return@withTimeoutOrNull
                if (senderAddress.isNotEmpty() && broadcastRepositoryLazy.get().isLocalAccountAddress(senderAddress)) return@withTimeoutOrNull
                val sender = data["subtitle"]?.takeIf { it.isNotBlank() }
                    ?: senderAddress.takeIf { it.isNotEmpty() }?.let { com.kachat.app.models.addressDisplayName(it) }
                    ?: ""
                roomMentionBellLazy.get().recordMentionPush(
                    rawChannel = channel,
                    txId = data["tx_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: message.collapseKey,
                    sender = sender,
                    preview = com.kachat.app.util.BroadcastPushPreview.clean(raw),
                    mention = true,
                )
            }
        }
    }

    /**
     * A KaPost "tipped your post" push the system draws itself while the app is away (it has a
     * `notification` block, so [onMessageReceived] never sees it): its post goes into the tip
     * ledger, and when the tip's payment push came first (its "Received a tip" notice is up) the
     * system's copy is taken down again - one notice per tip (iOS 2026-11-22: the extension words
     * the second quietly; Android can't reword a push the system draws).
     */
    private fun afterSystemDrawnKaPostTip(message: RemoteMessage) {
        val notification = message.notification ?: return
        val data = message.data
        if (data["type"] != "kaposts" && data["thread_id"] != "kaposts") return
        if (KaPostTipLedger.kaPostsPushKind(data["kaposts_kind"], notification.body ?: data["body"].orEmpty()) != "tip") return
        val txId = data["tx_id"]?.trim()?.lowercase()?.takeIf { it.length == 64 } ?: return
        POST_ID_KEYS.firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf { it.length == 64 } }
            ?.let { KaPostTipLedger.record(applicationContext, txId, it) }
        // (In the foreground the system draws nothing: [onMessageReceived] has it.)
        if (!pushSeenBefore) return
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        val tag = notification.tag ?: txId
        runCatching {
            manager.activeNotifications.filter { it.tag.equals(tag, ignoreCase = true) }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /**
     * Whether a push with a `notification` block (rooms, KaPost, Received KAS, .kachat names),
     * which the system draws by itself while the app is away, is one this phone no longer wants
     * (iOS 8cb4c88e NotificationService, audit 2026-11-24 notifications): Push Notifications off
     * or signed out, a room whose bell is off unless the push @'s you (`mention: true`), a
     * KaPost kind switched off or its actor muted or blocked, a Received KAS address that no
     * longer notifies. The push service can still send these until the registration update
     * lands. While the app is open [onMessageReceived] decides, as before. Unreadable stores, or
     * no answer in time: drawn as sent.
     */
    private fun dropsBeforeTheSystemDraws(message: RemoteMessage): Boolean {
        if (message.notification == null) return false
        if (!com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return false
        if (notificationHelper.isAppInForeground) return false
        val data = message.data
        val wanted = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(PRE_DRAW_BUDGET_MS) {
                systemDrawnPushWanted(data, message.notification?.body ?: data["body"].orEmpty())
            }
        } ?: true
        if (!wanted) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            Log.i(TAG, "Dropped a ${data["type"].orEmpty()} push the system would have drawn: switched off")
        }
        return !wanted
    }

    private suspend fun systemDrawnPushWanted(data: Map<String, String>, body: String): Boolean {
        if (!settingsRepository.notificationsEnabled.first()) return false
        if (walletManager.isLoggedOut || walletManager.getActiveAccount() == null) return false
        val type = data["type"].orEmpty()
        val threadId = data["thread_id"].orEmpty()
        return when {
            type == "broadcast" || threadId.startsWith("broadcast:") -> {
                val channel = (data["channel"] ?: threadId.removePrefix("broadcast:"))
                    .let { com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel(it) }
                    ?: return true
                // An edit or a delete for everyone is never announced, even while the app is away
                // (iOS NotificationService empties both, 2026-11-25).
                if (com.kachat.app.util.BroadcastPushPreview.isEditEnvelope(body) ||
                    com.kachat.app.util.MessageDelete.isDeleteEnvelope(body)) return false
                roomPushWanted(channel, data)
            }
            type == "kaposts" || threadId.startsWith("kaposts") ->
                !kaPostsActorHidden(data["actor_pubkey"]) &&
                    settingsRepository.shouldNotifyKaPostsPush(data["kaposts_kind"], body.ifEmpty { data["title"].orEmpty() })
            type == "address_activity" -> addressActivityWatched(data["address"])
            else -> true
        }
    }

    /**
     * Whether this account wants a room's push (iOS 8cb4c88e `willPresent` / NotificationService
     * `isRoomBellOn`): a joined room with its bell on, not a service room. Except a push that @'s
     * you (`mention: true`, INDEXER_HANDOFF 2026-11-24): the indexer sends those whatever the
     * bell, and with every room silenced on a new install it's the only way a mention reaches a
     * closed app. Never for a service room.
     */
    private suspend fun roomPushWanted(channel: String, data: Map<String, String>): Boolean =
        broadcastRepositoryLazy.get().wantsRoomPush(channel) ||
            (isTruePushFlag(data["mention"]) &&
                com.kachat.app.util.MessageProtocol.normalizeChannelName(channel) !in com.kachat.app.models.FeaturedBroadcastChannels.SERVICE_CHANNELS)

    /** A KaPost actor (`actor_pubkey`) this account muted or blocked on KaPost. */
    private suspend fun kaPostsActorHidden(actorPubkey: String?): Boolean {
        val actor = actorPubkey?.let { KaPostsService.kaspaAddressFromPubkey(it) } ?: return false
        val wallet = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        return actor in settingsRepository.kapostsMuted(wallet).first() ||
            actor in settingsRepository.kapostsBlocked(wallet).first()
    }

    /**
     * Whether a Received KAS push's address still notifies (iOS 8cb4c88e `willPresent`): one of
     * the registration's `watch_only_addresses` as they are now - its switch, its Cold Storage
     * account's, or Address Activity may have been turned off since the registration, and the
     * push service can still send until the update lands. None at all with Address Activity off.
     */
    private suspend fun addressActivityWatched(address: String?): Boolean {
        val lowered = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val watched = runCatching { addressActivityNotifierLazy.get().pushWatchOnlyAddresses() }.getOrNull() ?: return true
        return watched.any { it.lowercase() == lowered }
    }

    /** The account stores open: the app's problem screen covers the rest; a push or a token
     *  rotation meanwhile is dropped rather than building the wallet and ending the process. */
    private fun accountStoresOpen(what: String): Boolean {
        if (com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return true
        Log.w(TAG, "Account stores unreadable, $what ignored")
        return false
    }

    override fun onNewToken(token: String) {
        if (!accountStoresOpen("FCM token rotation")) return
        // FCM rotated the token — re-register it with the indexer (signed with the wallet key).
        Log.i(TAG, "FCM token rotated, re-registering with the push service")
        pushRegistrationManager.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!accountStoresOpen("push")) return
        val data = message.data
        if (data.isEmpty()) {
            Log.d(TAG, "push received with empty data payload, ignoring")
            return
        }

        val type = data["type"].orEmpty()
        val body = data["body"].orEmpty()
        // From its arrival as handleIntent estimated it (P4); now, if that is missing or odd.
        pushReceivedAtMs = System.currentTimeMillis().let { now ->
            val arrival = intentArrivalMs
            intentArrivalMs = 0L
            if (arrival <= 0L) now else arrival.coerceIn(now - HANDLER_BUDGET_MS * 4, now)
        }
        Log.i(TAG, "push received: type=$type txId=${data["tx_id"].orEmpty().take(16)}")

        // NotificationHelper.show* are suspend and quick; block so the work completes before the
        // service is torn down.
        runBlocking {
            try {
                // Any push about the other network - the service this device left when it moved
                // between mainnet and testnet still pushing it - stays silent, whatever its type:
                // DMs, payments and handshakes as before, and now group and .kachat name pushes
                // too (iOS d946a65 rule, audit AND-006). Not filed either: the
                // same key would decrypt it into the wrong account (iOS df52425).
                if (com.kachat.app.util.PushNetworkFilter.isOtherNetwork(data, com.kachat.app.util.KaspaNetwork.launch)) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                    Log.i(TAG, "Silenced a $type push from the other network")
                    return@runBlocking
                }
                // Signed out: the key stays on the device for signing back in, but this phone
                // shows nothing until then. A failed unregister at logout can keep pushes coming
                // (iOS NotificationService, bfe9f022).
                if (walletManager.isLoggedOut || walletManager.getActiveAccount() == null) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                    Log.i(TAG, "Silenced a $type push while signed out")
                    return@runBlocking
                }
                // Our own transaction, sent from another device of this seed: never a banner (iOS
                // NotificationService). A message is read back with the seed and stored
                // (iOS addOutgoingMessageFromPush, bfe9f022).
                val ownAddress = runCatching { walletManager.getAddress() }.getOrNull()
                if (type in OWN_SEND_PUSH_TYPES && data["sender"].orEmpty().equals(ownAddress, ignoreCase = true)) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
                        notificationHelper.claimWithoutNotifying(txId)
                        if (type == "contextual") chatRepository.noteOwnSentPush(txId)
                    }
                    return@runBlocking
                }
                when (type) {
                    // .kachat name events (offers, sales, renewal reminders): shown as sent since
                    // the market came back to K (iOS 428a797b NotificationService, kachat-indexer
                    // docs/K_ALL_IN_ONE_PUSH.md); signed out, nothing (checked above). A tap opens
                    // .kachat at the name (MainActivity), as one the OS drew does.
                    "name_event" -> notificationHelper.showNameEvent(
                        title = data["title"].orEmpty(),
                        text = body,
                        name = data["name"],
                        dedupeKey = data["tx_id"]?.takeIf { it.isNotBlank() } ?: data["dedup"]?.takeIf { it.isNotBlank() },
                    )

                    // Received KAS on a spending or Cold Storage address (iOS 428a797b; the
                    // registration's watch_only_addresses).
                    "address_activity" -> handleAddressActivityPush(data, body)

                    // a room push's collapse id is its message's txid (iOS 961fc05a)
                    "broadcast" -> handleRoomPush(data, body, message.collapseKey)

                    "kaposts" -> handleKaPostPush(data, body)

                    "group_message" -> handleGroupMessagePush(data)

                    "group_control" -> handleGroupControlPush(data)

                    "contextual" -> handleDirectMessage(data)

                    // Payment / handshake bodies are already meaningful ("Payment received",
                    // "Started a conversation") — no decryption needed.
                    "payment", "handshake" -> {
                        val sender = data["sender"] ?: return@runBlocking
                        // Possibly for the account signed in before this one (audit C6): silent.
                        if (notificationHelper.pushMayBeForPreviousAccount(data["receiver"] ?: data["recipient"], ownAddress)) {
                            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                            Log.i(TAG, "Silenced a $type push that may be for the previous account")
                            return@runBlocking
                        }
                        // A blocked address rings nothing, payments included: the request rule
                        // below skips payments (iOS 8cb4c88e, audit 2026-11-24 notifications).
                        if (type == "payment" && chatRepository.isChatBlocked(sender)) {
                            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                            return@runBlocking
                        }
                        // Payments land in your own chat and ring as before; a handshake is
                        // first contact like any other (Message Requests).
                        if (type == "handshake" && applyMessageRequestRule(sender, data)) return@runBlocking
                        // A KaPost tip arrives as a payment push too (same txid as its "tipped
                        // your post" push): it says "Received a tip" (iOS 2026-11-22).
                        val tipText = if (type == "payment") tipPaymentPushBody(data) else null
                        notificationHelper.show(
                            contactId = sender,
                            title = contactTitle(sender, sender),
                            // A payment: "<who> - Sent you 1.5 KAS" (iOS dcb329a).
                            text = tipText ?: if (type == "payment") paymentPushBody(data["amount"]) else body.ifEmpty { getString(R.string.dm_notif_new_message) },
                            // The contact's own Notifications choice (Off, No Sound, Sound), as
                            // for their messages (iOS NotificationService `effectiveMode`).
                            notificationOverride = contactOverride(sender),
                            dedupeTxId = data["tx_id"]?.takeIf { it.isNotBlank() },
                            fromPush = true,
                            // the tip's KaPost push the system drew came first: replaced quietly
                            quiet = tipText != null && (replaceSystemDrawnPush(data["tx_id"]) || pushSeenBefore),
                        )
                    }

                    // A KaPosts push with no type still carries thread_id "kaposts" (iOS's thread).
                    else -> if (data["thread_id"] == "kaposts") {
                        handleKaPostPush(data, body)
                    } else {
                        Log.d(TAG, "Ignoring push of unknown type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle push (type=$type): ${e.message}")
            }
        }
    }

    /**
     * A Public Chats room push (PUSH_EXTENSIONS.md 2; iOS 148a5331 `kaPostsOrRoomPresentation` and
     * NotificationService): nothing for a room deleted, muted or switched off (the push service
     * can still send for it until the registration update lands), nothing for an edit (it changes
     * an earlier message in place), and the room's raw on-chain body shown as a friendly preview.
     * The banner itself stays down for the room on screen ([com.kachat.app.services.publicchats.BroadcastNotifier]).
     */
    private suspend fun handleRoomPush(data: Map<String, String>, body: String, collapseKey: String? = null) {
        val txId = data["tx_id"]?.takeIf { it.isNotBlank() }
        // Through the same gate as a room link's name, before it can show anything.
        val channel = data["channel"]
            ?.let { com.kachat.app.services.publicchats.PublicChatLink.normalizeAndValidateChannel(it) }
            ?: return
        // A push that @'s you passes a bell-off room (iOS 8cb4c88e, see [roomPushWanted]).
        if (!roomPushWanted(channel, data)) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            Log.i(TAG, "Room push dropped: the room isn't joined, or its bell is off")
            return
        }
        // A room message one of this phone's own accounts sent never rings here (audit M5; the
        // block-scan path does the same through BroadcastScanningService).
        val roomSender = data["sender"]?.takeIf { it.isNotBlank() }
        if (roomSender != null && broadcastRepositoryLazy.get().isLocalAccountAddress(roomSender)) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            Log.i(TAG, "Room push dropped: our own message")
            return
        }
        if (com.kachat.app.util.BroadcastPushPreview.isEditEnvelope(body)) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            Log.i(TAG, "Edit push dropped: an edit is never announced")
            return
        }
        // A delete for everyone takes an earlier message away: nothing to announce either (iOS
        // 2026-11-25 NotificationService `isDeleteEnvelope`).
        if (com.kachat.app.util.MessageDelete.isDeleteEnvelope(body)) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            Log.i(TAG, "Delete push dropped: a delete is never announced")
            return
        }
        // A long voice note's (or multi-part photo's) later parts while the app is open (iOS
        // 148a5331 `RoomVoicePushBursts`): the indexer pushes every part as its own "Voice
        // message", so a media push from the same sender in the same room within a minute of the
        // last is a later part - no new banner or sound; the room's banner from the first stays.
        if (notificationHelper.isAppInForeground && body.trim() in ROOM_MEDIA_PREVIEWS &&
            !notificationHelper.claimPartBurst("room:$channel|${data["subtitle"].orEmpty().lowercase()}")
        ) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // A room push while the app is open: the message lands in the room and the Chats list
        // first, then the banner (at most 4 s later), never the other way round (iOS 961fc05a
        // `fetchBeforePushBanner`; an indexer marked down isn't asked, audit 2026-11-25 C9).
        if (notificationHelper.isAppInForeground) {
            runCatching { broadcastRepositoryLazy.get().fetchBeforePushBanner(channel, txId ?: collapseKey) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        broadcastNotifierLazy.get().showBroadcast(
            channelName = channel,
            title = "#$channel",
            text = com.kachat.app.util.BroadcastPushPreview.clean(body),
            dedupeTxId = txId,
            fromPush = true,
        )
    }

    /**
     * A Received KAS push (`address_activity`, iOS 428a797b): one notice per transaction - none
     * when the app's own "Received" notice for it showed already ([AddressActivityNotifier] claims
     * the same transaction id). Its tap opens that address's history; a Cold Storage address has
     * none here, so Wallet. Nothing with Address Activity off (the registration may not have
     * caught up yet).
     */
    private suspend fun handleAddressActivityPush(data: Map<String, String>, body: String) {
        val txId = data["tx_id"]?.trim()?.takeIf { it.isNotEmpty() }
        // Address Activity off, or an address that no longer notifies (its switch, its Cold
        // Storage account's): the registration may not have caught up yet (iOS 8cb4c88e).
        if (!settingsRepository.addressActivityNotificationsEnabled.first() || !addressActivityWatched(data["address"])) {
            txId?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        if (txId != null && !notificationHelper.claimForBanner(txId)) return
        val address = data["address"]?.trim()?.takeIf { com.kachat.app.util.KaspaAddress.isValid(it) }
        val opensHistory = address != null && addressActivityNotifierLazy.get().opensHistory(address)
        notificationHelper.showAddressActivity(
            title = data["title"]?.takeIf { it.isNotBlank() } ?: "Funds received",
            text = body,
            dedupeKey = txId ?: "push-${address.orEmpty().takeLast(12)}-${System.currentTimeMillis()}",
            kind = if (opensHistory) NotificationHelper.KIND_SPENDING else NotificationHelper.KIND_COLD,
            address = address.takeIf { opensHistory },
        )
    }

    /**
     * A KaPosts push (PUSH_EXTENSIONS.md 3; iOS 148a5331): nothing from someone muted or blocked
     * when the push names who acted (`actor_pubkey`), nothing a KaPost switch turned off, and no
     * banner while KaPost's notifications are on screen. A reply or a quote opens itself, else the
     * post it was made on (only transaction ids: the payload is the push service's word), else
     * Profile's bell.
     */
    private suspend fun handleKaPostPush(data: Map<String, String>, body: String) {
        val title = data["title"].orEmpty()
        val text = body.ifEmpty { title }
        val txId = data["tx_id"].orEmpty()
        // The bell's count comes from the poll: a push while the app is open asks for one now
        // (iOS 428a797b `pollSoon`).
        if (notificationHelper.isAppInForeground) kaPostsNotificationPollerLazy.get().pollSoon()
        if (kaPostsActorHidden(data["actor_pubkey"])) {
            Log.i(TAG, "KaPosts push from a muted or blocked account dropped")
            return
        }
        if (!settingsRepository.shouldNotifyKaPostsPush(data["kaposts_kind"], text)) {
            Log.i(TAG, "KaPosts push suppressed by notification settings")
            return
        }
        // A tip is a payment in the tipper's chat: one "Received a tip" notice that opens that
        // chat, worded as the tip's payment push (iOS 2026-11-22 `applyKaPostsTip`).
        if (KaPostTipLedger.kaPostsPushKind(data["kaposts_kind"], body) == "tip" && showKaPostTipAsPayment(data, body)) return
        // KaPost's notifications on screen show it already: no banner, but the action is claimed
        // so the poller doesn't draw it later.
        if (notificationHelper.isAppInForeground && kaPostsNotificationPollerLazy.get().isNotificationsScreenVisible) {
            kaPostsNotifierLazy.get().claim(txId)
            return
        }
        val deepLink = com.kachat.app.ui.screens.KaPostsDeepLink
        val postTxId = POST_ID_KEYS.firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf { deepLink.isTxId(it) } }?.lowercase()
        val actionTarget = txId.trim().takeIf { deepLink.isTxId(it) && deepLink.pushOpensAction(data["kaposts_kind"], body) }?.lowercase()
        // Titled "KaPost" (the server still sends "KaPosts"), the person named as the app names
        // them, else the server's subtitle (iOS NotificationService, owner 2026-11-22).
        kaPostsNotifierLazy.get().showKaPosts(
            text = text,
            actionTxId = txId,
            postTxId = actionTarget ?: postTxId,
            subtitle = kaPostsActorName(data["actor_pubkey"]) ?: data["subtitle"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Who a KaPost push's actor is on this phone (iOS NotificationService `kaPostsActorName`,
     * 2026-11-22): `actor_pubkey` as an address, then the name the app shows for a contact (yours,
     * an Address Book name, their .kachat name, else their short address), else their .kachat name
     * from the names the app already knows ([KnownKachatNames]; nothing is looked up). Null leaves
     * the server's subtitle (a KNS name or a short address).
     */
    private suspend fun kaPostsActorName(actorPubkey: String?): String? {
        val address = actorPubkey?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { KaPostsService.kaspaAddressFromPubkey(it) } ?: return null
        val contact = runCatching { chatRepository.getContact(address) }.getOrNull()
        contact?.alias?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        AddressBookManager.nameFor(address)?.let { return it }
        KnownKachatNames.name(applicationContext, address)?.let { return it }
        return if (contact != null) com.kachat.app.util.KaspaAddress.shortDisplay(address) else null
    }

    /**
     * A KaPost "tipped your post" push becomes the tipper's chat notice, exactly as the tip's
     * payment push reads: titled with their name, "Received a tip: 0.2 KAS", opening their chat,
     * under the tip's txid (iOS 2026-11-22 `applyKaPostsTip`). Whichever of the two comes second
     * is not a second notice: the txid is claimed once. False when the push doesn't name the
     * tipper or the tip, so it shows as sent.
     */
    private suspend fun showKaPostTipAsPayment(data: Map<String, String>, body: String): Boolean {
        val txId = data["tx_id"]?.trim()?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return false
        val tipper = data["actor_pubkey"]?.let { KaPostsService.kaspaAddressFromPubkey(it) } ?: return false
        POST_ID_KEYS.firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf { it.length == 64 } }
            ?.let { KaPostTipLedger.record(applicationContext, txId, it) }
        // no KaPost banner for it from the poll either
        kaPostsNotifierLazy.get().claim(txId)
        // It reads as a payment in the tipper's chat, so it follows that chat's rules: nothing
        // from a blocked address, nothing for a contact set to Off, no sound for No Sound (iOS
        // 8cb4c88e `applyKaPostsTip`, audit 2026-11-24 notifications). Handled either way.
        if (chatRepository.isChatBlocked(tipper)) {
            notificationHelper.claimWithoutNotifying(txId)
            return true
        }
        val kas = KaPostTipLedger.amountFromTipPushBody(body) ?: KaPostTipLedger.kasText(data["amount"])
        notificationHelper.show(
            contactId = tipper,
            title = contactTitle(tipper, tipper),
            text = tipWording(kas),
            notificationOverride = contactOverride(tipper),
            dedupeTxId = txId,
            // the push's own notice ([PushArrivalRecord] holds back only the app's banners)
            fromPush = true,
            // the second of the tip's two pushes (iOS `pushSeenBefore`)
            quiet = pushSeenBefore,
        )
        return true
    }

    /** A payment push's "Received a tip: 0.2 KAS" when the payment is a KaPost tip: known from
     *  the ledger, or its transaction read once (iOS 2026-11-22 NotificationService "payment").
     *  Null for any other payment. */
    private suspend fun tipPaymentPushBody(data: Map<String, String>): String? {
        val txId = data["tx_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The push's own payload (raw hex), when it carries one, answers without a request (iOS
        // 961fc05a, audit 2026-11-25 B18 / M10). The indexer doesn't send it yet.
        val pushed = data["payload"]?.trim()?.takeIf { it.isNotEmpty() }
        if (pushed != null) {
            val postId = KaPostTipLedger.tipPostIdFromPayloadHex(pushed) ?: return null
            KaPostTipLedger.record(applicationContext, txId, postId)
            return tipWording(KaPostTipLedger.kasText(data["amount"]))
        }
        // Else a short look at the chain (payload only): most payments aren't tips, and the tip's
        // own KaPost push words it too, so a payment notice isn't held while the API catches up.
        KaPostTipLedger.postId(applicationContext, txId)
            ?: kotlinx.coroutines.withTimeoutOrNull(TIP_CHECK_BUDGET_MS) { KaPostTipLedger.tipPostId(applicationContext, txId) }
            ?: return null
        return tipWording(KaPostTipLedger.kasText(data["amount"]))
    }

    /** "Received a tip: 0.2 KAS" (TKAS on testnet), or "Received a tip" with no amount. */
    private fun tipWording(kas: String?): String =
        if (kas != null) com.kachat.app.util.KaspaUnit.label(getString(R.string.received_a_tip_kas, kas))
        else getString(R.string.received_a_tip)

    /** Removes the notification the system drew for [txId]'s push (its tag is the push's collapse
     *  id; the app's own carry none): true when there was one, so the notice replacing it is
     *  quiet - the second of a tip's two pushes (iOS 2026-11-22 `pushSeenBefore`). */
    private fun replaceSystemDrawnPush(txId: String?): Boolean {
        val id = txId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return false
        val drawn = runCatching { manager.activeNotifications.filter { it.tag.equals(id, ignoreCase = true) } }
            .getOrNull().orEmpty()
        drawn.forEach { runCatching { manager.cancel(it.tag, it.id) } }
        return drawn.isNotEmpty()
    }

    /**
     * A group message. While push is active this is the only banner it gets, and it has to come
     * from here whatever the sync does: the sync may run out of time, or have stored this very
     * message on an earlier pass. So it is read straight off the push with the group secrets held
     * here, as iOS's notification service extension does (iOS handleGroupPushResolved), and the
     * banner goes up before the catch-up starts. A push with no payload (a photo: over the push's
     * 3.5 KB) is read off chain first.
     */
    private suspend fun handleGroupMessagePush(data: Map<String, String>) {
        val groupId = data["blinded_group_id"] ?: return
        val txId = data["tx_id"].orEmpty()
        // Read off chain (a photo or voice note part is over the push's size), retried while the
        // API catches up with the transaction (iOS ca013fc8 fetchTransactionPayload).
        val readOffChain = data["enc_payload"].isNullOrBlank()
        val payload = data["enc_payload"]?.takeIf { it.isNotBlank() }?.let { "kchat:1:gcomm:$it" }
            ?: txId.takeIf { it.isNotBlank() }?.let { id ->
                readOffChainWithRetry("group:${groupId.lowercase()}", { groupRepository.readChainPayload(id) }) { it != null }
            }
        // Back-to-back pushes from one sender in one group that couldn't be read ring once (iOS
        // ca013fc8 `part_push_bursts`; the blinded id is per group and sender). A part that was
        // read goes by its note's tag alone (iOS 83806b3d).
        val burstOwner = "group:${groupId.lowercase()}"
        val read = groupRepository.readGroupMessagePush(payload)
        if (read is GroupMessagePush.Banner) postGroupBanner(read, txId)
        // Stores the message: a read one with its own group's lanes only; an unreadable one with
        // the full catch-up, coalesced, as it may bring the keys of a group this device has only
        // just heard of (audit 2026-11-25 N10, S6). Bounded: FCM gives this handler about 20 s.
        // A sync still running at 8 s - or at what is left of the handler's time after the reads
        // above - is left behind and the generic fallback below posts in time (audit AND-008).
        try {
            syncBudgetMs().takeIf { it > 0L }?.let { budget ->
                kotlinx.coroutines.withTimeoutOrNull(budget) { groupRepository.catchUpAfterGroupMessagePush(groupId, read) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Push-triggered group sync failed: ${e.message}")
        }
        if (read !is GroupMessagePush.Unreadable) return
        // The catch-up may have just brought the group this message belongs to.
        when (val reread = groupRepository.readGroupMessagePush(payload)) {
            is GroupMessagePush.Banner -> {
                postGroupBanner(reread, txId)
                return
            }
            GroupMessagePush.Silent -> return
            GroupMessagePush.Unreadable -> Unit
        }
        // The generic fallback can't know whether the unread message mentions the user, so in a
        // group with "Only Notify if I'm Mentioned" on it can't be posted correctly. Resolve the
        // per-sender blinded id back to the local group and consult the toggle: mentions-only means
        // suppress. Tradeoff: a missed banner for a non-mention is exactly what the toggle asks
        // for, while a missed banner for an actual mention is the cost of the read failure; the
        // message itself still lands on the next successful sync, and with no push active that
        // path still banners the mention (which is why the txId is deliberately NOT claimed here).
        // When the blinded id matches no local group the toggle can't be consulted, so the generic
        // banner fires as before.
        val resolvedGroup = try {
            groupRepository.findGroupByBlindedId(groupId)
        } catch (e: Exception) {
            null
        }
        // When the blinded id resolves, silent and mentions-only both suppress. When it does NOT
        // resolve we cannot tell which group it is, so the only honest question is whether the
        // answer would be the same for all of them - if every known group is silenced or
        // mentions-only, no group here would have allowed this banner.
        val suppressed = if (resolvedGroup != null) {
            groupRepository.isGroupSilent(resolvedGroup.groupId) ||
                groupRepository.isGroupMentionsOnly(resolvedGroup.groupId)
        } else {
            groupRepository.allGroupsSuppressUnidentifiedPushes()
        }
        if (suppressed) {
            Log.i(TAG, "Generic group fallback suppressed by notification settings")
            return
        }
        // While K is open it banners the group messages it reads itself, so a group push it
        // couldn't read is never shown as the generic line (iOS KaChatApp `rawGroupPush`,
        // 2026-11-24). The txId stays unclaimed, so the app's own banner can follow.
        if (notificationHelper.isAppInForeground) {
            // This push is already recorded as arrived, which would stop the app's own banner for
            // it (NotificationHelper.showGroup): handed over to the app explicitly (audit P2).
            notificationHelper.deferToApp(txId)
            Log.i(TAG, "Generic group fallback skipped: the app is open")
            return
        }
        // Read off chain and still unreadable (most often: not on the API yet after every
        // retry): back-to-back ones from this sender in this group ring once, as parts would
        // (iOS ca013fc8, 83806b3d).
        val quiet = readOffChain && !notificationHelper.claimPartBurst(burstOwner)
        notificationHelper.showGroup(
            // Prefer the resolved real group id/name: the tap intent then opens the actual thread
            // and the open-thread suppression keys correctly.
            groupId = resolvedGroup?.groupId ?: groupId,
            // Never the push's own title: the service sends the sender's raw address there.
            title = resolvedGroup?.name ?: getString(R.string.group_chat),
            text = getString(R.string.new_group_message),
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            // Unresolved: `groupId` here is the per-sender BLINDED id, which is not a route
            // argument any thread can be found by. Send the tap to the Chats list instead.
            targetGroupId = resolvedGroup?.groupId,
            quiet = quiet,
            fromPush = true,
        )
    }

    /** A later part of a long voice note or multi-part photo is delivered quietly - the note
     *  already rang (iOS 7c5c22e3 `isLaterPartPush`). A part that was read goes by its tag alone,
     *  never by the burst rule (iOS 83806b3d). */
    private suspend fun postGroupBanner(banner: GroupMessagePush.Banner, txId: String) {
        notificationHelper.showGroup(
            groupId = banner.groupId,
            title = banner.title,
            text = banner.text,
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            quiet = isLaterPartPush(banner.partKey, txId, burstOwner = null),
            fromPush = true,
        )
    }

    /** True when this push is a later part of a note or burst that already rang: a tagged part
     *  whose note was announced, or an unreadable payload-less push that follows another from the
     *  same owner within a minute. Only one of the two is ever set for a push. Claims its ledger,
     *  so call it once per push (iOS ca013fc8, 83806b3d). */
    private fun isLaterPartPush(partKey: String?, txId: String, burstOwner: String?): Boolean {
        val firstOfNote = partKey?.let { notificationHelper.claimPartBanner(it, txId) } ?: true
        val firstOfBurst = burstOwner?.let { notificationHelper.claimPartBurst(it) } ?: true
        return !firstOfNote || !firstOfBurst
    }

    /**
     * A transaction read off chain, retried until [CHAIN_READ_DEADLINE_MS] after the push
     * arrived (iOS ca013fc8 `fetchTransactionPayload`): the push comes the moment the
     * transaction is sent, often before the API has it, and a photo's or voice note's parts go
     * back to back. With one try every part could come back unreadable and ring as its own "New
     * message". [done] says when a result is final; the last result is returned at the deadline.
     */
    /** How long a catch-up after the push's own banner may run: [GROUP_SYNC_BUDGET_MS], but never
     *  past [HANDLER_BUDGET_MS] from the push's arrival, so a fallback banner after it still
     *  posts while the handler runs. Zero or less: no time for one. */
    private fun syncBudgetMs(): Long {
        val left = HANDLER_BUDGET_MS - (System.currentTimeMillis() - pushReceivedAtMs) - FALLBACK_RESERVE_MS
        return minOf(GROUP_SYNC_BUDGET_MS, left)
    }

    /**
     * [burstOwner] is who sent it where ("dm:<sender>", "group:<blinded id>"): once a read for
     * the same owner finished, read or not, within [BURST_READ_WINDOW_MS], this one makes a single
     * attempt - the parts of one photo or voice note are sent together, so the API has them all
     * by then, or is not answering for any (audit 2026-11-25 N11). A push already past its read
     * time (queued behind others, P4) also makes one short attempt only.
     */
    private suspend fun <T> readOffChainWithRetry(burstOwner: String?, read: suspend () -> T?, done: (T?) -> Boolean): T? {
        val singleAttempt = burstOwner != null && recentBurstRead(burstOwner, System.currentTimeMillis())
        var attempt = 0
        var result: T? = null
        try {
            while (true) {
                val remaining = CHAIN_READ_DEADLINE_MS - (System.currentTimeMillis() - pushReceivedAtMs)
                val late = remaining <= 1_000L
                if (late && attempt > 0) return result
                val budget = if (late) LATE_CHAIN_READ_BUDGET_MS else minOf(CHAIN_READ_BUDGET_MS, remaining)
                result = runCatching { kotlinx.coroutines.withTimeoutOrNull(budget) { read() } }.getOrNull()
                if (done(result) || late || singleAttempt) return result
                // Not on the API yet, busy, or no answer: try again shortly, while there's time.
                val delayMs = minOf(1_000L + attempt * 750L, 3_000L)
                val left = CHAIN_READ_DEADLINE_MS - (System.currentTimeMillis() - pushReceivedAtMs)
                if (left <= delayMs + 1_000L) return result
                kotlinx.coroutines.delay(delayMs)
                attempt++
            }
        } finally {
            if (burstOwner != null) noteBurstRead(burstOwner, System.currentTimeMillis())
        }
    }

    /**
     * A group control: usually "you were added to a group". Read straight off the push with the
     * wallet key, needing no network, as iOS's extension does (iOS handleGroupPushResolved): being
     * added is only a request, announced once, so nothing for a group you're already in (a new root
     * only rotates its keys) and otherwise "You were invited", whose tap opens Message Requests.
     * Only that invite announces itself. Everything else a control carries - a key rotation when
     * members change, a rename or photo, an invite accept or reject, a delete marker - updates the
     * group quietly, as does one that can't be read: no banner (iOS 83806b3d). A control too big
     * for the push (a long member list, a group photo) is read off chain first.
     */
    private suspend fun handleGroupControlPush(data: Map<String, String>) {
        val txId = data["tx_id"]?.takeIf { it.isNotBlank() }
        val payload = data["enc_payload"]?.takeIf { it.isNotBlank() }
            ?: txId?.let { id ->
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(CHAIN_READ_BUDGET_MS) { groupRepository.readChainControlPayload(id) }
                }.getOrNull()
            }
        val read = groupRepository.readGroupControlPush(payload, data["sender"].orEmpty(), pushSentAtMs(data), txId)
        when (read) {
            is GroupControlPush.Invite -> notificationHelper.showGroupInvite(read.inviteKey, read.groupName, dedupeTxId = txId)
            GroupControlPush.Update -> notificationHelper.claimWithoutNotifying(txId)
            GroupControlPush.Quiet, GroupControlPush.Unreadable -> Unit
        }
        // Brings in whatever else it can see, through the one coalesced catch-up (audit
        // 2026-11-25 S10). Bounded: a push handler has seconds, not minutes.
        runCatching {
            syncBudgetMs().takeIf { it > 0L }?.let { budget ->
                kotlinx.coroutines.withTimeoutOrNull(budget) { groupRepository.runGroupCatchUp() }
            }
        }
        if (read !is GroupControlPush.Unreadable) return
        // Couldn't be read from here, but the sync may have: an invite it held waits for this
        // push to announce it. Anything else stays quiet; the sync has applied it (iOS 83806b3d).
        // The txId is not claimed: with no push active the sync's own invite banner can still
        // follow once the control is readable.
        if (txId != null) {
            groupRepository.inviteHeldByTx(txId)?.let { invite ->
                notificationHelper.showGroupInvite(invite.inviteKey, invite.groupName, dedupeTxId = txId)
            }
        }
    }

    /**
     * 1:1 message: decrypt `enc_payload` locally (stateless ECDH — only needs our wallet key and
     * the ephemeral key embedded in the sealed blob) and show the real sender + text. Falls back to
     * the server's generic text on any failure (no wallet, tag mismatch, unknown format).
     */
    private suspend fun handleDirectMessage(data: Map<String, String>) {
        val sender = data["sender"] ?: return
        // Always hand the message to the app's ingest, whatever the banner below ends up being
        // (iOS 60c9fd0): it is stored now, in the background, instead of waiting for a sync to
        // stumble on it - a photo, voice or long message stayed off the chat list until that
        // chat was opened. Queued, so duplicates are ignored; blocked senders are dropped there.
        data["tx_id"]?.takeIf { it.isNotBlank() }?.let { chatRepository.notePushedMessage(it, sender) }
        // Media/large messages exceed FCM's 4KB cap, so the server can't attach the encrypted body
        // (enc_payload absent) — the server's generic body is used for those. Small text messages
        // carry enc_payload and are decrypted here for the real preview.
        val fallback = data["body"].orEmpty().ifEmpty { getString(R.string.dm_notif_new_message) }

        // No enc_payload means the message was too big for FCM - which is EVERY photo and voice
        // message, since those run to tens of kilobytes. Read it off chain so the preview below
        // can say what it is instead of falling back to the server's generic wording.
        // Bounded so the fallback text still posts when the REST API is slow (audit AND-008).
        // Retried while the API catches up with the transaction (iOS ca013fc8).
        val pushedPlaintext = decryptDirectMessage(data["enc_payload"])
        val chainRead = if (pushedPlaintext != null) null else data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
            readOffChainWithRetry("dm:${sender.lowercase()}", { chatRepository.readChainMessage(txId) }) { it != null && it !is ChatRepository.ContextualRead.Unreadable }
        }
        // The indexer picks devices by who sent a message, not who it's for, so a contact's
        // message to someone else can reach this phone: it isn't ours, so it says nothing and
        // isn't queued. Being unable to read one (no key, no network) still rings (iOS bfe9f022).
        if (chainRead is ChatRepository.ContextualRead.NotForThisAccount) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
                notificationHelper.claimWithoutNotifying(txId)
                chatRepository.forgetPushedMessage(txId)
            }
            return
        }
        val plaintext = pushedPlaintext ?: (chainRead as? ChatRepository.ContextualRead.Message)?.text
        // A 1:1 reaction rings as "Reacted 👍 to your message" (dmNotificationText), as iOS's
        // notification service shows it; one whose emoji isn't one emoji is dropped silently, as
        // the app reads it as a removal (iOS 5b7c772c NotificationService.isInvalidReaction).
        if (plaintext != null && MessageReaction.isInvalidReaction(plaintext)) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // Nor is an edit: it changes an earlier bubble in place (MESSAGING.md "Message Edits").
        if (plaintext != null && com.kachat.app.util.MessageEdit.isEditEnvelope(plaintext)) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // Payment-pool envelopes are invisible protocol traffic - no banner at all, as on iOS's
        // notification service extension (isSilentPoolEnvelope, 927322b): the retired addr_pool /
        // addr_pool_request, and a payment_notice naming anything but our chatting address (an
        // old app's payment to one of our former pool addresses, which the app drops - XP-023).
        // A payment_notice for a payment to our chatting address (a chat payment sent From the
        // contact's spending address) IS a payment the reader should see, so it falls through and
        // reads "Received payment" below (dmNotificationText).
        val silentPoolEnvelope = when (val pool = com.kachat.app.util.PaymentPoolProtocol.parse(plaintext)) {
            null -> false
            is com.kachat.app.util.PaymentPoolProtocol.Envelope.Notice -> {
                val myAddress = runCatching { walletManager.getAddress() }.getOrNull()
                myAddress.isNullOrBlank() || !pool.content.address.trim().equals(myAddress, ignoreCase = true)
            }
            // a token payment's notice (Send Dollars) is announced the same way (iOS 5b7c772c)
            is com.kachat.app.util.PaymentPoolProtocol.Envelope.TokenNotice -> {
                val myAddress = runCatching { walletManager.getAddress() }.getOrNull()
                myAddress.isNullOrBlank() || !pool.content.address.trim().equals(myAddress, ignoreCase = true)
            }
            else -> true
        }
        if (silentPoolEnvelope) {
            Log.i(TAG, "Payment pool envelope push kept silent")
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // KaChat has no calls: another client's call message is dropped silently, as the app
        // drops it (iOS 7c5c22e3 NotificationService.isCallSignal).
        if (plaintext != null && com.kachat.app.util.CallSignal.isCallSignal(plaintext)) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }

        // Message Requests (NO_HANDSHAKE_MESSAGING.md §4): one "New message request" per new
        // sender, then nothing from them until accepted; nothing at all from a blocked address.
        if (applyMessageRequestRule(sender, data)) return

        val text = plaintext?.let { notificationPreview(it) } ?: fallback
        // A long voice note or multi-part photo is ONE message: the first of its parts to arrive
        // rings, a later one is delivered quietly (iOS 7c5c22e3). A payload-less push that
        // couldn't be read in time joins its sender's burst: back-to-back unreadable ones ring
        // once (iOS ca013fc8). A part that was read goes by its note's tag alone (iOS 83806b3d).
        val txId = data["tx_id"].orEmpty()
        val partKey = com.kachat.app.util.VoicePartGrouping.partKey(plaintext, sender)
        val readOffChain = pushedPlaintext == null && txId.isNotBlank()
        val burstOwner = "dm:${sender.lowercase()}".takeIf { readOffChain && plaintext == null }
        notificationHelper.show(
            contactId = sender,
            title = contactTitle(sender, data["title"].orEmpty().ifEmpty { sender }),
            text = text,
            notificationOverride = contactOverride(sender),
            // Collapses with the in-app poll's banner when the app is foregrounded and both
            // paths see the same message.
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            quiet = isLaterPartPush(partKey, txId, burstOwner),
            fromPush = true,
        )
    }

    /** Sompi as KAS text: up to 8 decimals, trailing zeros dropped (iOS kasText). */
    private fun kasText(sompi: Long): String =
        String.format(java.util.Locale.US, "%.8f", sompi / 100_000_000.0).trimEnd('0').trimEnd('.')

    private suspend fun applyMessageRequestRule(sender: String, data: Map<String, String>): Boolean {
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        if (sender.equals(myAddress, ignoreCase = true)) return false
        val txId = data["tx_id"]?.takeIf { it.isNotBlank() }
        val silent = when {
            chatRepository.isChatBlocked(sender) -> true
            else -> {
                val contact = runCatching { chatRepository.getContact(sender) }.getOrNull()
                if (contact != null) {
                    if (!chatRepository.isMessageRequest(contact, myAddress)) return false
                    true
                } else if (
                    WhoCanMessage.current(applicationContext) == WhoCanMessage.USERNAMES &&
                    KnownKachatNames.name(applicationContext, sender) == null
                ) {
                    // Who Can Message You? set to usernames only (iOS NotificationService, audit
                    // 2026-11-24 #13): a first message from someone with no .kachat name this phone
                    // knows rings nothing and isn't recorded as rung. The name may only be unknown
                    // HERE (nothing is looked up), so the sender is filed for the app, which rings
                    // the request once when it learns their name.
                    HiddenRequestSenders.add(applicationContext, myAddress, sender)
                    true
                } else if (chatRepository.markRequestNotified(sender)) {
                    // Rung here now: the app has nothing left to announce.
                    HiddenRequestSenders.remove(applicationContext, myAddress, sender)
                    notificationHelper.showMessageRequest(sender, dedupeTxId = txId, fromPush = true)
                    runCatching {
                        syncBudgetMs().takeIf { it > 0L }?.let { budget ->
                            kotlinx.coroutines.withTimeoutOrNull(budget) { chatRepository.syncInbox() }
                        }
                    }
                    return true
                } else {
                    true
                }
            }
        }
        if (silent) txId?.let { notificationHelper.claimWithoutNotifying(it) }
        return silent
    }

    /**
     * When the message this push is about was sent, in milliseconds: the server's timestamp is
     * the honest answer, and arrival time is the fallback when it is missing or unreadable.
     * Seconds are accepted too - the field has been written both ways.
     */
    private fun pushSentAtMs(data: Map<String, String>): Long {
        val raw = data["timestamp"]?.trim()?.toLongOrNull() ?: return System.currentTimeMillis()
        if (raw <= 0) return System.currentTimeMillis()
        return if (raw < 100_000_000_000L) raw * 1000 else raw
    }

    /** Base64 → EncryptedMessage → ChaCha20-Poly1305 decrypt with the wallet key. Null on failure
     * (no encrypted body attached, e.g. an oversized/media message; or a tag/key mismatch). An
     * iPhone photo or voice note in the binary media frame opens as its media-file JSON here too
     * (KasiaCipher.decrypt, iOS NotificationService.mediaFrameEnvelope), so its banner is "Sent a
     * photo" / "Sent a voice message" as before. */
    private fun decryptDirectMessage(encPayload: String?): String? {
        if (encPayload.isNullOrEmpty()) return null
        return try {
            val sealed = Base64.getDecoder().decode(encPayload)
            val encrypted = KasiaCipher.EncryptedMessage.fromBytes(sealed) ?: return null
            MessageProtocol.decrypt(encrypted, walletManager.getPrivateKeyBytes())
        } catch (e: Exception) {
            Log.d(TAG, "DM decrypt failed, using fallback: ${e.message}")
            null
        }
    }

    /** The banner body for a decrypted 1:1 message - the same rule the in-app poller uses
     *  (ChatRepository), so both paths word one message alike (audit AND-007). */
    private fun notificationPreview(plaintext: String): String =
        com.kachat.app.util.dmNotificationText(plaintext, notificationHelper.dmNotificationLabels())

    private suspend fun contactTitle(senderId: String, default: String): String {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        // Who they are in the app: a name you gave them (chat or Address Book), else their
        // .kachat name, else the short address - so a push from someone with a .kachat name says
        // it, not their address (iOS dcb329a, ContactsManager.displayName).
        if (contact != null) {
            return com.kachat.app.models.addressDisplayName(senderId, alias = contact.alias)
        }
        return AddressBookManager.nameFor(senderId) ?: default.takeLast(12)
    }

    /**
     * A chat payment's body under the sender's name: "Sent you 1.5 KAS", the amount without
     * trailing zeros; with no amount on the push, "Sent you a payment" (iOS dcb329a
     * NotificationService.handlePayment). TKAS on testnet.
     */
    private fun paymentPushBody(amount: String?): String {
        val text = amount?.trim().orEmpty()
        val sompi = text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
        if (sompi == null || sompi <= 0) return getString(com.kachat.app.R.string.push_sent_you_payment)
        return com.kachat.app.util.KaspaUnit.label(
            getString(com.kachat.app.R.string.push_sent_you_kas, com.kachat.app.util.KaspaUnit.plain(sompi))
        )
    }

    private suspend fun contactOverride(senderId: String): ContactNotificationMode? {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        return ContactNotificationMode.fromName(contact?.notificationOverride)
    }

    companion object {
        // Same tag as PushRegistrationManager: `adb logcat -s KaChatPush` shows registrations,
        // token rotations, and every received push in one stream.
        private const val TAG = PushRegistrationManager.TAG

        /** Every spelling the KaPosts push has used for "the content that was acted on" (the
         *  published contract's `postId`, and `post_id` / `content_id`). Kept in sync with
         *  MainActivity.FCM_KEYS_POST_ID, which reads the same payload when FCM drew it. */
        private val POST_ID_KEYS = listOf("post_id", "postId", "content_id")

        /** The indexer's previews for a room media envelope (kachat-indexer
         *  docs/ROOM_VOICE_PART_PUSHES.md; iOS `RoomVoicePushBursts.mediaPreviews`). */
        private val ROOM_MEDIA_PREVIEWS = setOf("Voice message", "Photo")

        /** How long a push may spend on a group catch-up before the generic banner posts. */
        private const val GROUP_SYNC_BUDGET_MS = 8_000L

        /** How long one read off chain may take (iOS NotificationService fetchChainPreview,
         *  timeoutInterval 8). */
        private const val CHAIN_READ_BUDGET_MS = 8_000L

        /** A payment push's tip check reads the chain only this long: one or two tries, not what
         *  a message preview may wait (iOS 961fc05a `tipCheckDeadline`, audit 2026-11-25 B18). */
        private const val TIP_CHECK_BUDGET_MS = 4_000L

        /** How long a payload-less push's chain reads keep trying, from the push's arrival (iOS
         *  ca013fc8 `chainReadDeadline`: 20 s of the extension's 30). FCM gives this handler about
         *  20 s in all, so the reads stop sooner and leave the banner and a short catch-up their
         *  time - at 20 s a fallback banner came after the window and could be lost. */
        private const val CHAIN_READ_DEADLINE_MS = 13_000L

        /** All a push may take, from its arrival: under FCM's ~20 s for onMessageReceived. */
        private const val HANDLER_BUDGET_MS = 18_000L

        /** One read for a push already past its read time (P4), or a later part of a burst (N11). */
        private const val LATE_CHAIN_READ_BUDGET_MS = 3_000L

        /** A push handled within this long of the previous one ending was queued behind it (P4). */
        private const val QUEUE_GAP_MS = 1_000L

        /** How long a finished read makes later reads for the same sender single attempts (N11). */
        internal const val BURST_READ_WINDOW_MS = 60_000L

        // Process-wide, like FCM's one handler thread: when the last handling ended, when the
        // current run of back-to-back pushes began, and each sender's last finished read.
        @Volatile private var lastHandlerEndMs = 0L
        @Volatile private var queueStartMs = 0L
        private val burstReadsDoneAt = HashMap<String, Long>()

        private fun recentBurstRead(owner: String, nowMs: Long): Boolean =
            synchronized(burstReadsDoneAt) { singleBurstRead(burstReadsDoneAt[owner], nowMs) }

        private fun noteBurstRead(owner: String, nowMs: Long) = synchronized(burstReadsDoneAt) {
            burstReadsDoneAt.entries.removeAll { nowMs - it.value >= BURST_READ_WINDOW_MS }
            burstReadsDoneAt[owner] = nowMs
        }

        /** Whether a read for an owner whose last read finished at [lastDoneAtMs] makes one attempt (N11). */
        internal fun singleBurstRead(lastDoneAtMs: Long?, nowMs: Long): Boolean =
            lastDoneAtMs != null && nowMs >= lastDoneAtMs && nowMs - lastDoneAtMs < BURST_READ_WINDOW_MS

        /**
         * When a push reached the phone, as best known (P4): handled at [nowMs] in a run of
         * back-to-back pushes that began at [queueStartMs], it came no earlier than that, nor than
         * FCM's [sentTimeMs] (0 when unknown), and no later than now. A push handled on its own
         * ([queueStartMs] == [nowMs]) arrived now.
         */
        internal fun arrivalEstimate(nowMs: Long, queueStartMs: Long, sentTimeMs: Long): Long =
            maxOf(queueStartMs, sentTimeMs).coerceAtMost(nowMs)

        /** Kept back from a catch-up for the fallback banner that may follow it. */
        private const val FALLBACK_RESERVE_MS = 1_500L

        /** How long [dropsBeforeTheSystemDraws] may take to decide before the push is drawn as sent. */
        private const val PRE_DRAW_BUDGET_MS = 3_000L

        /** A push flag as the server may send it: "true" or "1" (FCM data values are strings;
         *  iOS `isTrue`). */
        internal fun isTruePushFlag(value: String?): Boolean {
            val text = value?.trim() ?: return false
            return text.equals("true", ignoreCase = true) || text == "1"
        }

        /** The 1:1 push types whose sender can be this wallet itself. */
        private val OWN_SEND_PUSH_TYPES = setOf("contextual", "payment", "handshake")
    }
}
