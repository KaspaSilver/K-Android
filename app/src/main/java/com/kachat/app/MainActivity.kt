package com.kachat.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Close
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.PendingShare
import com.kachat.app.services.ShareIntake
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.KaChatApp
import com.kachat.app.ui.screens.KaChatLink
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Single Activity — all navigation is handled in Compose via NavHost.
 *
 * Extends [AppCompatActivity] (rather than plain `FragmentActivity`, which it's a superset of)
 * because the per-app language API (`AppCompatDelegate.setApplicationLocales`, used by the
 * Language setting in Settings) relies on `AppCompatActivity`'s `attachBaseContext` override to
 * apply the locale override on API levels below 33.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var accountStoresOpen = true
    @Inject lateinit var onboardingGate: com.kachat.app.services.OnboardingGate
    // Through Lazy: it needs the wallet store, and that's checked before anything builds it.
    @Inject lateinit var walletManagerLazy: dagger.Lazy<com.kachat.app.services.WalletManager>
    @Inject lateinit var shareShortcutsLazy: dagger.Lazy<com.kachat.app.services.ShareShortcutsManager>
    // A tapped Received KAS push: the address's history, or Wallet for Cold Storage (iOS 428a797b).
    @Inject lateinit var addressActivityNotifierLazy: dagger.Lazy<com.kachat.app.services.AddressActivityNotifier>

    // The KaChat sheet that explains the battery exemption before Android's own dialog. Re-derived
    // on every resume (see refreshBatteryRationale), so it follows the notification answer.
    private var batteryRationaleDue by mutableStateOf(false)

    private var pendingContactId by mutableStateOf<String?>(null)
    private var pendingGroupId by mutableStateOf<String?>(null)
    // "Open the Chats list" — a group notification with no resolvable local group id.
    private var pendingOpenGroups by mutableStateOf(false)
    // "cold" / "spending" — which wallet screen an address-activity receipt tap opens.
    private var pendingWalletActivityKind by mutableStateOf<String?>(null)
    // The one own address a "Received" receipt named: its History opens in a sheet (iOS 13046ad).
    // Held here until the shell is up and takes it, which is what covers a cold start (iOS's
    // OwnAddressRoute.pending).
    private var pendingOwnAddress by mutableStateOf<String?>(null)

    // Android 13+ requires an explicit runtime grant before ANY notification (local or FCM push)
    // can be shown. Registered here (during construction, as required) and requested by the Chats
    // list (ChatsScreen).
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The battery sheet waits for this answer: notifications off means there is nothing
            // for the exemption to deliver, so it is not worth asking.
            if (granted) refreshBatteryRationale()
        }

    /** Asked once, when the Chats list first appears, as iOS asks (its system prompt never comes
     *  back): an answer of "Don't allow" is kept, and Settings > Notifications is where to turn
     *  them on later. */
    fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE)
        if (granted || prefs.getBoolean("notification_permission_asked", false)) return
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * The battery-optimization exemption - Android's "Let KaChat always run in background?"
     * dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). Encrypted DM/group push is delivered
     * data-only so the app can decrypt it on the phone; an app frozen by aggressive OEM battery
     * management never wakes to receive it, and the notification never appears.
     *
     * It used to fire the system dialog straight from onCreate with no word of explanation - on
     * Android 13+ a launch AFTER notifications were granted, seemingly at random; below 13, the
     * first launch, over whatever was on screen. Now [BatteryExemptionRationale] says why first,
     * and Android's dialog only opens from its Allow. Asked once either way, as before.
     */
    private fun batteryExemptionDue(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val prefs = getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return false
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryRationale() {
        batteryRationaleDue = batteryExemptionDue()
    }

    private fun markBatteryExemptionAsked() {
        getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("battery_exemption_asked", true).apply()
        batteryRationaleDue = false
    }

    private fun acceptBatteryExemption() {
        markBatteryExemptionAsked()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // A few OEMs don't implement the direct dialog — fall back to the battery-optimization list.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers returning from the notification-permission dialog, and from system Settings.
        guarded("battery rationale") { refreshBatteryRationale() }
    }

    /** A side step of opening the app: a failure is logged, never the end of it. */
    private fun guarded(what: String, block: () -> Unit) =
        com.kachat.app.util.StartupSafety.step("MainActivity", what, block)

    /** The launching (or new) Intent's notification target, links and share. */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        guarded("notification target") { consumeNotificationTarget(intent) }
        guarded("profile link") { handleProfileDeepLink(intent) }
        guarded("payment request link") { handlePaymentRequestDeepLink(intent) }
        guarded("KaPost link") { handleKaPostDeepLink(intent) }
        guarded("public chat link") { handlePublicChatDeepLink(intent) }
        guarded("KaPost, KaPay and KaPlay links") { handleRetiredAppLinks(intent) }
        guarded("share") { handleShareIntent(intent) }
        guarded("clear intent payload") { clearConsumedIntentPayload(intent) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountStoresOpen = com.kachat.app.util.SecureStores.accountStoresOpen(this)
        if (!accountStoresOpen) {
            setContent {
                KaChatTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                    com.kachat.app.ui.screens.SecureStoreProblemScreen(
                        onTryAgain = ::restartApp,
                        onStartOver = {
                            com.kachat.app.util.SecureStores.startOver(this)
                            restartApp()
                        },
                    )
                }
            }
            return
        }
        // Phones lock to portrait; tablets (sw>=600dp, see res/values-sw600dp/bools.xml) rotate
        // freely - mirroring iOS (iPhone portrait-only, iPad any orientation).
        requestedOrientation = if (resources.getBoolean(R.bool.lock_portrait)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        // No privacy cover in the task switcher (iOS 83806b3d, the owner's call 2026-11-15): the
        // app shows there as it is. The seed and private-key screens still set FLAG_SECURE for
        // themselves, which keeps them out of the thumbnail.
        enableEdgeToEdge()
        // Everything up to setContent is a side step - a permission prompt, a notification or
        // link to route. Each is guarded on its own: one that throws is logged, and the app
        // still opens (KaChatApplication's startup rule).
        guarded("battery rationale") { refreshBatteryRationale() }
        handleLaunchIntent(intent)
        setContent {
            val walletViewModel: WalletViewModel = hiltViewModel()
            // Settings > Customization > Appearance: System follows the device, as on iOS.
            val appearance by walletViewModel.appearance.collectAsState()
            val isLoggedIn by walletViewModel.isLoggedIn.collectAsState()
            // Log Out and signing back in leave the active address as it was, so the share
            // targets hear about it here: signed out there are none, signed in they are this
            // account's chats (iOS d23e6f7a).
            androidx.compose.runtime.LaunchedEffect(isLoggedIn) {
                guarded("share targets") { shareShortcutsLazy.get().syncWithActiveAccount() }
            }
            KaChatTheme(darkTheme = appearance.isDark(androidx.compose.foundation.isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KaChatApp(
                        pendingContactId = pendingContactId,
                        onPendingContactHandled = { pendingContactId = null },
                        pendingGroupId = pendingGroupId,
                        onPendingGroupHandled = { pendingGroupId = null },
                        pendingOpenGroups = pendingOpenGroups,
                        onPendingOpenGroupsHandled = { pendingOpenGroups = false },
                        pendingWalletActivityKind = pendingWalletActivityKind,
                        onPendingWalletActivityHandled = { pendingWalletActivityKind = null },
                        pendingOwnAddress = pendingOwnAddress,
                        onPendingOwnAddressHandled = { pendingOwnAddress = null }
                    )
                    BatteryExemptionRationale(isLoggedIn = isLoggedIn)
                }
            }
        }
    }

    /**
     * Says why before Android asks. Only inside the app proper - logged in, no onboarding wizard
     * running.
     * Dismissing it counts as "Not now": asked once, as the system dialog always was.
     */
    @androidx.compose.runtime.Composable
    private fun BatteryExemptionRationale(isLoggedIn: Boolean) {
        val onboardingHeld by onboardingGate.held.collectAsState()
        if (!batteryRationaleDue || !isLoggedIn || onboardingHeld) return
        com.kachat.app.ui.screens.ActionSheetContainer(
            title = androidx.compose.ui.res.stringResource(R.string.battery_rationale_title),
            subtitle = androidx.compose.ui.res.stringResource(R.string.battery_rationale_body),
            onDismiss = { markBatteryExemptionAsked() },
        ) {
            com.kachat.app.ui.screens.ActionSheetRow(
                icon = androidx.compose.material.icons.Icons.Default.NotificationsActive,
                title = androidx.compose.ui.res.stringResource(R.string.battery_rationale_allow),
                subtitle = androidx.compose.ui.res.stringResource(R.string.battery_rationale_allow_detail),
                onClick = { acceptBatteryExemption() },
            )
            com.kachat.app.ui.screens.ActionSheetRow(
                icon = androidx.compose.material.icons.Icons.Default.Close,
                title = androidx.compose.ui.res.stringResource(R.string.not_now),
                subtitle = androidx.compose.ui.res.stringResource(R.string.battery_rationale_not_now_detail),
                onClick = { markBatteryExemptionAsked() },
            )
        }
    }

    private fun restartApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * Strips the link/share payload once the handlers above have read it.
     *
     * The Activity keeps its launching Intent, so a configuration change re-runs `onCreate`
     * against it. Settings > Language does exactly that (`AppCompatDelegate.setApplicationLocales`
     * recreates the Activity), and without this a language change re-opened the last post that
     * had been opened from a link, or re-staged the last thing that had been shared into the app.
     * The notification extras are stripped in [consumeNotificationTarget] for the same reason;
     * these two carry their payload in the action/data/stream instead of in our own extras.
     *
     * Safe to run immediately: every handler reads what it needs synchronously before any
     * suspending work.
     */
    private fun clearConsumedIntentPayload(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW ||
            intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_SEND_MULTIPLE
        ) {
            intent.action = null
            intent.data = null
            intent.type = null
            listOf(
                Intent.EXTRA_TEXT,
                Intent.EXTRA_STREAM,
                Intent.EXTRA_SHORTCUT_ID,
            ).forEach(intent::removeExtra)
        }
    }

    /**
     * Reads whichever notification target the launching Intent carries, then strips it.
     *
     * Two shapes arrive here:
     *  - The app's OWN notifications ([NotificationHelper]), which carry explicit extras.
     *  - A notification FCM drew itself. Any push carrying an FCM `notification` block is shown
     *    by the OS while the app is backgrounded or dead, and `onMessageReceived` never runs for
     *    it in that state: no [NotificationHelper] intent is ever built, and the tap instead
     *    launches this Activity with the push's DATA payload as plain string extras. Those keys
     *    are the server's (`type`, `sender`, ...), not ours, so without
     *    [applyFcmNotificationTarget] such a tap just restored the last screen the user was on.
     *    The two in-repo descriptions of the server's choice disagree (this app's
     *    `KaChatFirebaseMessagingService` header says some pushes ship a `notification` block;
     *    PUSH_ANDROID_SETUP.md says everything is data-only), so both shapes are handled here
     *    rather than betting on one.
     *
     * Stripping matters because a configuration change (Settings > Language recreates the
     * Activity) re-runs `onCreate` against the same Intent: without it, changing the language
     * jumped the user back into whatever conversation the last tapped notification was for.
     */
    private fun consumeNotificationTarget(intent: Intent) {
        // This Activity is exported, so any app can send these extras: only a well-formed value
        // is routed (a contact is an address, a group its hex id) - anything else would crash the
        // navigation or open a screen in a state the app never builds itself.
        pendingContactId = routableContactId(intent.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID))
        pendingGroupId = intent.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)?.takeIf { GROUP_ID_PATTERN.matches(it) }
        pendingOpenGroups = intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_GROUPS, false)
        // A group invite's notification: the Chats list opens Message Requests once it is up.
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_MESSAGE_REQUESTS, false)) {
            com.kachat.app.ui.screens.MessageRequestsDeepLink.request()
        }
        pendingWalletActivityKind = intent.getStringExtra(NotificationHelper.EXTRA_WALLET_ACTIVITY_KIND)
        // One address got the Kaspa: open that address's History rather than the wallet tab -
        // the kind is then only the fallback (iOS 13046ad).
        pendingOwnAddress = intent.getStringExtra(NotificationHelper.EXTRA_WALLET_ACTIVITY_ADDRESS)
            ?.takeIf { com.kachat.app.util.KaspaAddress.isValid(it) }
        if (pendingOwnAddress != null) pendingWalletActivityKind = null
        // KaPost, Public Chats and the .kachat names are back in KaChat (iOS 148a5331): their own
        // notifications open the post, the bell, the room or the name.
        val ownFeatureTarget = consumeFeatureNotificationTarget(intent)
        if (pendingContactId == null &&
            pendingGroupId == null && !pendingOpenGroups && pendingWalletActivityKind == null &&
            pendingOwnAddress == null && !ownFeatureTarget
        ) {
            applyFcmNotificationTarget(intent)
        }
        listOf(
            NotificationHelper.EXTRA_CONTACT_ID,
            NotificationHelper.EXTRA_GROUP_ID,
            NotificationHelper.EXTRA_OPEN_GROUPS,
            NotificationHelper.EXTRA_OPEN_MESSAGE_REQUESTS,
            NotificationHelper.EXTRA_WALLET_ACTIVITY_KIND,
            NotificationHelper.EXTRA_WALLET_ACTIVITY_ADDRESS,
            NotificationHelper.EXTRA_KACHAT_NAME,
            com.kachat.app.services.KaPostsIntents.EXTRA_OPEN_KAPOSTS,
            com.kachat.app.services.KaPostsIntents.EXTRA_KAPOST_TXID,
            com.kachat.app.services.KaPostsIntents.EXTRA_KAPOST_FOCUS_TXID,
            com.kachat.app.services.KaPostsIntents.EXTRA_INTERNAL_TOKEN,
            com.kachat.app.services.KaPostsScheduledReminder.EXTRA_OPEN_SCHEDULED,
            com.kachat.app.services.publicchats.BroadcastNotifier.EXTRA_CHANNEL_NAME,
            FCM_KEY_TYPE,
        ).forEach(intent::removeExtra)
    }

    /**
     * The app's own KaPost, room and .kachat notifications (iOS 148a5331 `didReceive`): true when
     * one was routed. KaPost's carry a per-install token ([com.kachat.app.services.KaPostsIntents]),
     * so another app can't open posts through them.
     */
    private fun consumeFeatureNotificationTarget(intent: Intent): Boolean {
        val kaPostsInternal = com.kachat.app.services.KaPostsIntents.isInternalIntent(this, intent)
        // The scheduled-post reminder (local fallback): into KaPost, which sends what is due and
        // shows the Scheduled screen.
        if (kaPostsInternal &&
            intent.getBooleanExtra(com.kachat.app.services.KaPostsScheduledReminder.EXTRA_OPEN_SCHEDULED, false)
        ) {
            com.kachat.app.ui.screens.KaPostsDeepLink.pendingOpenScheduled.value = true
            return true
        }
        // A KaPost notification: straight to the post (a reply's own id scrolls its parent's
        // thread to it); naming no post, Profile's bell.
        if (kaPostsInternal && intent.getBooleanExtra(com.kachat.app.services.KaPostsIntents.EXTRA_OPEN_KAPOSTS, false)) {
            val postTxId = intent.getStringExtra(com.kachat.app.services.KaPostsIntents.EXTRA_KAPOST_TXID)
                ?.takeIf { it.isNotBlank() }
            if (postTxId == null) {
                com.kachat.app.ui.screens.NotificationsRoute.pending.value = true
            } else {
                // The focus before the post id: the post id is what KaPost's screen keys on.
                com.kachat.app.ui.screens.KaPostsDeepLink.pendingFocusReplyTxId.value =
                    intent.getStringExtra(com.kachat.app.services.KaPostsIntents.EXTRA_KAPOST_FOCUS_TXID)
                com.kachat.app.ui.screens.KaPostsDeepLink.pendingPostTxId.value = postTxId
            }
            return true
        }
        // A room banner: the room, while it is in the list.
        intent.getStringExtra(com.kachat.app.services.publicchats.BroadcastNotifier.EXTRA_CHANNEL_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                com.kachat.app.ui.screens.PublicChatLinkRouter.openFromPush(this, it)
                return true
            }
        // A .kachat name notification (a local renewal reminder, or the indexer's name_event
        // push): that name on the .kachat tab; a name push naming none, .kachat itself (iOS
        // 428a797b).
        val kachatName = intent.getStringExtra(NotificationHelper.EXTRA_KACHAT_NAME)?.takeIf { it.isNotBlank() }
        val opensKachat = intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_KACHAT, false)
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_KACHAT)
        if (kachatName != null || opensKachat) {
            openKachat(kachatName)
            return true
        }
        return false
    }

    /** .kachat at [rawName] when it is a valid name, else .kachat itself (iOS 428a797b
     *  `PendingTabRoute.pending = .kachatNames`). */
    private fun openKachat(rawName: String?) {
        if (!com.kachat.app.services.kachatnames.KachatNamesService.isLaunched) return
        if (rawName != null) openKachatName(rawName)
        if (com.kachat.app.ui.screens.KachatDeepLink.pendingName.value == null) {
            com.kachat.app.ui.screens.KachatDeepLink.pendingTab.value = true
        }
    }

    /**
     * A tapped Received KAS push (`address_activity`, iOS 428a797b): that address's history, as
     * the app's own notice opens it; a Cold Storage address has none here, so Wallet.
     */
    private fun openReceivedPush(rawAddress: String?) {
        val address = rawAddress?.trim()?.takeIf { com.kachat.app.util.KaspaAddress.isValid(it) }
        if (address == null) {
            pendingWalletActivityKind = NotificationHelper.KIND_SPENDING
            return
        }
        lifecycleScope.launch {
            val opensHistory = runCatching { addressActivityNotifierLazy.get().opensHistory(address) }.getOrDefault(true)
            if (opensHistory) pendingOwnAddress = address else pendingWalletActivityKind = NotificationHelper.KIND_COLD
        }
    }

    /** [raw] as the .kachat tab's pending name: bare, normalized, and a valid name or nothing. */
    private fun openKachatName(raw: String) {
        val name = runCatching {
            com.kachat.app.services.kachatnames.KachatNames.Codec.normalize(raw.trim().lowercase().removeSuffix(".kachat"))
        }.getOrNull()?.takeIf { com.kachat.app.services.kachatnames.KachatNames.Codec.isValid(it) } ?: return
        // names not live on this network: nothing would take it now, and it would open later,
        // unexpectedly, after a network switch (audit LOW L13)
        if (!com.kachat.app.services.kachatnames.KachatNamesService.isLaunched) return
        com.kachat.app.ui.screens.KachatDeepLink.pendingName.value = name
    }

    /** [raw] when it is a Kaspa address, the only thing a chat route is made of; null otherwise. */
    private fun routableContactId(raw: String?): String? =
        raw?.takeIf { com.kachat.app.util.KaspaAddress.isValid(it) }

    /**
     * Routes a tap on a notification FCM drew from its own `notification` block, using the data
     * payload the OS handed us as Intent extras (schema documented on
     * `KaChatFirebaseMessagingService`). Every push type is mapped, not just the public ones, so
     * a server-side change to which of them carry a `notification` block cannot silently break
     * their taps.
     */
    private fun applyFcmNotificationTarget(intent: Intent) {
        // The push's own `type` names it; a KaPosts push also carries thread_id "kaposts" (the
        // same thread the iOS payload uses), which is taken when `type` is missing.
        val type = intent.getStringExtra(FCM_KEY_TYPE)
            ?: intent.getStringExtra("thread_id")?.takeIf { it == "kaposts" }
        when (type) {
            "contextual", "payment", "handshake" ->
                pendingContactId = routableContactId(intent.getStringExtra("sender"))
            // Only the blinded per-sender group id travels in a group push, and resolving it to a
            // local group needs the group secrets — not something this Activity can do. The Group
            // Chats list is the honest destination.
            "group_message", "group_control" -> pendingOpenGroups = true
            // A room push: the room, while it is in the list (iOS 148a5331 `openRoomFromPush`).
            "broadcast" -> intent.getStringExtra("channel")?.takeIf { it.isNotBlank() }?.let {
                com.kachat.app.ui.screens.PublicChatLinkRouter.openFromPush(this, it)
            }
            // A KaPost push (iOS 148a5331): a reply or a quote opens itself, else the post it was
            // made on, else Profile's bell. Only transaction ids: the payload is the push
            // service's word, not ours.
            "kaposts" -> {
                val deepLink = com.kachat.app.ui.screens.KaPostsDeepLink
                val actionTxId = intent.getStringExtra("tx_id")?.trim()?.takeIf {
                    deepLink.isTxId(it) &&
                        deepLink.pushOpensAction(intent.getStringExtra("kaposts_kind"), intent.getStringExtra("body"))
                }?.lowercase()
                val postId = FCM_KEYS_POST_ID.firstNotNullOfOrNull {
                    intent.getStringExtra(it)?.trim()?.takeIf { value -> deepLink.isTxId(value) }
                }?.lowercase()
                val target = actionTxId ?: postId
                deepLink.pendingFocusReplyTxId.value = null
                if (target != null) {
                    deepLink.pendingPostTxId.value = target
                } else {
                    com.kachat.app.ui.screens.NotificationsRoute.pending.value = true
                }
            }
            // A .kachat name push (offers, sales, renewal): that name, else .kachat (iOS 428a797b).
            "name_event" -> openKachat(intent.getStringExtra("name")?.takeIf { it.isNotBlank() })
            // A Received KAS push: that address's history, or Wallet (iOS 428a797b).
            "address_activity" -> openReceivedPush(intent.getStringExtra("address"))
        }
        // A KaPosts push names its post under any of these; stripped with the rest.
        FCM_KEYS_POST_ID.forEach(intent::removeExtra)
        // Stripped once read, so a configuration change cannot route the same tap twice.
        listOf("channel", "sender", "thread_id", "tx_id", "kaposts_kind", "body", "name", "address").forEach(intent::removeExtra)
    }

    /** The host a KaChat web link arrives on: kachat.app, the one the app writes. */
    private fun isKaChatWebHost(host: String?): Boolean {
        val clean = host?.lowercase()?.removePrefix("www.") ?: return false
        return clean == KaChatLink.WEB_HOST
    }

    /** kachat://profile/<address> or https://kachat.app/u/<address> - someone's shared profile
     *  (iOS 863b0e9). The address is validated, checksum and all, before anything routes. With
     *  `chat=1` (KaPosts' Chat button) it asks for the 1:1 chat rather than User Info (iOS
     *  724d477); a KaChat without that ignores the query. */
    private fun handleProfileDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val raw = when {
            // KaPay's own schemes too (registered here since KaPay retired, iOS 148a5331).
            isKaChatAppScheme(uri.scheme) &&
                uri.host.equals("profile", ignoreCase = true) -> uri.pathSegments.singleOrNull()
            uri.scheme.equals("https", ignoreCase = true) &&
                isKaChatWebHost(uri.host) &&
                uri.pathSegments.firstOrNull() == "u" -> uri.pathSegments.getOrNull(1)
            else -> null
        } ?: return
        val asksForChat = uri.queryParameterNames.any { name ->
            name.equals("chat", ignoreCase = true) && uri.getQueryParameter(name) == "1"
        }
        com.kachat.app.ui.screens.ProfileDeepLink.request(raw, openChat = asksForChat)
    }

    /** kachat://pay/<address>?... or https://kachat.app/pay/<address>?... - someone's payment
     *  request (iOS c6851d6). Everything in it is checked - the address on this network, the exact
     *  amount, the memo, a signature when it carries one - before Send Kaspa opens filled in (your
     *  own shows it instead); nothing is ever sent without slide to send. */
    private fun handlePaymentRequestDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val isPayLink = when {
            // kapay://pay/... an older KaPay or KaChat made opens here too (iOS 148a5331).
            isKaChatAppScheme(uri.scheme) -> uri.host.equals("pay", ignoreCase = true)
            uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true) ->
                isKaChatWebHost(uri.host) && uri.pathSegments.firstOrNull() == "pay"
            else -> false
        }
        if (!isPayLink) return
        val ref = com.kachat.app.ui.screens.KaChatLink.parse(uri.toString()) as? com.kachat.app.ui.screens.KaChatLinkRef.PaymentRequest
            ?: return
        com.kachat.app.ui.screens.PaymentRequestRouter.open(ref.request)
    }

    /** kachat://kapost/<txid>, kaposts://kapost/<txid> or https://kachat.app/post/<txid> - a post on
     *  KaPost, opened from outside the app: it opens in KaChat's own KaPost (iOS 148a5331). The id
     *  is checked before anything opens. */
    private fun handleKaPostDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val isPostLink = when {
            uri.scheme.equals("kachat", ignoreCase = true) || uri.scheme.equals("kaposts", ignoreCase = true) ->
                uri.host.equals("kapost", ignoreCase = true)
            uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true) ->
                isKaChatWebHost(uri.host) && uri.pathSegments.firstOrNull() == "post"
            else -> false
        }
        if (!isPostLink) return
        val ref = com.kachat.app.ui.screens.KaChatLink.parse(uri.toString()) as? com.kachat.app.ui.screens.KaChatLinkRef.KaPost
            ?: return
        com.kachat.app.ui.screens.openKaChatLink(this, ref)
    }

    /** kachat://broadcast/<room>, kaposts://broadcast/<room> or https://kachat.app/broadcast/<room> -
     *  a Public Chats room (iOS 148a5331): it opens, after asking, for one not yet in the list. The
     *  name passes the room gate before anything routes. */
    private fun handlePublicChatDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val ref = com.kachat.app.ui.screens.KaChatLink.parse(uri.toString()) as? com.kachat.app.ui.screens.KaChatLinkRef.PublicChatRoom
            ?: return
        com.kachat.app.ui.screens.openKaChatLink(this, ref)
    }

    /**
     * KaPost's, KaPay's and KaPlay's other links, their schemes registered here since they retired
     * (iOS 148a5331 `handleIncomingURL`): `kaposts://compose?text=` opens KaPost's composer with
     * the text, `kapay://market` the .kachat tab's Marketplace page, and a `kachatchess://bot/...`
     * challenge the game in KaPlay.
     */
    private fun handleRetiredAppLinks(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        com.kachat.app.ui.screens.KaChatLink.composeText(uri)?.let {
            com.kachat.app.ui.screens.KaPostsDeepLink.pendingComposeText.value = it
            return
        }
        if (com.kachat.app.ui.screens.KaChatLink.isMarketLink(uri)) {
            com.kachat.app.ui.screens.KachatDeepLink.pendingMarketplace.value = true
            return
        }
        if (com.kachat.app.services.chess.ChessBotLinks.challenge(uri) != null) {
            com.kachat.app.services.chess.ChessBotLinks.open(uri)
        }
    }

    /** KaChat's own scheme and KaPay's two (kachatwallet:// was KaPay's name before KaPay), whose
     *  profile and payment request links open here since KaPay retired (iOS 148a5331). */
    private fun isKaChatAppScheme(scheme: String?): Boolean =
        scheme.equals("kachat", ignoreCase = true) || scheme.equals("kapay", ignoreCase = true) ||
            scheme.equals("kachatwallet", ignoreCase = true)

    /**
     * System share sheet intake (ACTION_SEND / ACTION_SEND_MULTIPLE — text/plain and image MIME types).
     *
     * If the user picked a specific conversation on the share sheet (a direct-share shortcut
     * published by ShareShortcutsManager), the shortcut id — `"<account>|<contact address>"` —
     * arrives in [Intent.EXTRA_SHORTCUT_ID] and the share is targeted straight at that chat, but
     * only when the shortcut's account is the one signed in: sending it from another would message
     * that account's contact from this address, linking the two on chain (iOS d23e6f7a). A target
     * from another account, signed out, or from an old shortcut that names no account is dropped,
     * and the share arrives untargeted. Otherwise the share is untargeted: the Chats list shows a
     * "choose a chat" banner and whichever thread is opened next consumes it (see ShareIntake).
     *
     * Image streams are copied into app cache immediately: the sender's content-URI read grant
     * is tied to this delivery and can't be relied on later when the user actually hits send.
     */
    private fun handleShareIntent(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type ?: return
        val targetContactId = shareTargetForSignedInAccount(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val imageStreams: List<android.net.Uri> = when {
            action == Intent.ACTION_SEND && type.startsWith("image/") ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
            action == Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    ?.filterNotNull() ?: emptyList()
            else -> emptyList()
        }
        if (text.isNullOrBlank() && imageStreams.isEmpty()) return
        lifecycleScope.launch {
            val cachedImages = withContext(Dispatchers.IO) { imageStreams.mapNotNull(::copySharedImageToCache) }
            if (text.isNullOrBlank() && cachedImages.isEmpty()) return@launch
            ShareIntake.pending.value = PendingShare(
                text = text?.takeIf { it.isNotBlank() },
                imageUris = cachedImages,
                targetContactId = targetContactId
            )
        }
    }

    /** The chat a direct-share shortcut names, when it belongs to the signed-in account; null for
     *  no shortcut, or one made under another account (or before shortcuts named theirs). */
    private fun shareTargetForSignedInAccount(shortcutId: String?): String? {
        val target = com.kachat.app.services.ShareShortcutsManager.parseShortcutId(shortcutId) ?: return null
        val active = if (!accountStoresOpen) null else runCatching {
            walletManagerLazy.get().takeUnless { it.isLoggedOut }?.activeAddressFlow?.value
        }.getOrNull()
        val owner = target.owner
        if (owner == null || active == null || !com.kachat.app.util.KaspaNetwork.isSameAccount(owner, active)) {
            android.util.Log.i("MainActivity", "[Share] Dropped a share target made under another account")
            return null
        }
        return target.contactId
    }

    /** Copies a shared image stream into app cache, returning a file:// URI readable at any later point (or null on failure). */
    private fun copySharedImageToCache(uri: android.net.Uri): android.net.Uri? {
        // Shares arrive as content:// grants. A file:// "share" from another app could name one
        // of KaChat's own private files, which this process can read and the sender cannot.
        if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) return null
        return try {
            val dir = File(cacheDir, "shared_images").apply { mkdirs() }
            // Stale copies from earlier shares are tiny compared to photo caches, but don't let
            // them accumulate forever either.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }
                ?.forEach { it.delete() }
            val extension = when (contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val file = File(dir, "share_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.$extension")
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    // Capped: a photo is shrunk to well under 1 MB before sending anyway, and an
                    // unbounded stream could fill the cache.
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SHARED_IMAGE_BYTES) return@use false
                        output.write(buffer, 0, read)
                    }
                    true
                }
            } ?: return null
            if (!copied) {
                file.delete()
                return null
            }
            android.net.Uri.fromFile(file)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to copy shared image", e)
            null
        }
    }

    companion object {
        /** FCM data-payload keys that reach us as Intent extras when the OS drew the
         *  notification itself — see [applyFcmNotificationTarget]. */
        private const val FCM_KEY_TYPE = "type"
        /** A group id as the app writes it (the hex group id): no `/`, `?` or `&` to reshape the
         *  route it goes into. */
        private val GROUP_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val FCM_KEYS_POST_ID = listOf("post_id", "postId", "content_id")
        private const val MAX_SHARED_IMAGE_BYTES = 50L * 1024 * 1024
    }
}
