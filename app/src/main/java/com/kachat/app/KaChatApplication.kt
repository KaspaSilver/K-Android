package com.kachat.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kachat.app.repository.GroupRepository
import com.kachat.app.services.CrashRecorder
import com.kachat.app.services.GroupScanningService
import com.kachat.app.services.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application class — required by Hilt for dependency injection.
 * All singleton services are initialized here via Hilt modules in the `di` package.
 *
 * Startup rule: nothing optional may stop the app from opening. A Play build 5.2 would not open
 * on a GrapheneOS phone (no crash log): 5.2 built more services eagerly, and on a hardened ROM
 * the Keystore behind EncryptedSharedPreferences can throw. So every service below is injected
 * as [dagger.Lazy] - Hilt's field injection (inside super.onCreate) creates none of them - and
 * onCreate then builds each one as its own [startupStep], in the same order as before: one that
 * throws is logged and the app opens without it. The lifecycle callbacks below are guarded the
 * same way. [hiltWorkerFactory] is the one plain field (WorkManager's configuration needs it, and
 * it is built from the graph alone).
 */
@HiltAndroidApp
class KaChatApplication : Application(), Configuration.Provider {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Before anything else - Hilt builds the whole object graph in onCreate, and a crash in
        // there is exactly the "closes on the splash screen" a user cannot describe. The record
        // is bundled into the diagnostics archive and offered for sharing on the next launch.
        CrashRecorder.install(this)
        // The network this launch runs on (Settings > Connection > Testnet), known before Hilt
        // starts any service - the node pool, wallet and indexers are all set up for it.
        // [base], not `this`: inside attachBaseContext the Application's own applicationContext
        // is still null, and reading prefs through it crashed every launch.
        com.kachat.app.util.KaspaNetwork.init(base)
        // The features back from KaPost, KaPay and KaPlay (iOS 148a5331): their text in the
        // language picked in Settings, from services as well as screens, and which public chat
        // rooms the message indexer tracks - each read before Hilt builds anything that asks.
        com.kachat.app.util.AppText.init(base)
        com.kachat.app.util.KaPlayText.init(base)
        com.kachat.app.services.IndexerTrackedRooms.init(base)
    }

    // @Singleton instances are otherwise only created lazily the first time something actually
    // requests them — field-injecting this here forces GroupScanningService to exist from app
    // startup so its group-count/pending-invite observers (see its init block) run for the app's
    // whole lifetime, not just after a group chat screen happens to be opened.
    @Inject
    lateinit var groupScanningService: dagger.Lazy<GroupScanningService>

    // Same lazy-singleton reasoning again: PushRegistrationManager's init block observes the
    // active account / contact set / silenced groups / notifications setting and
    // re-registers (or unregisters) the FCM token with the push service whenever any of it
    // changes. Field-injecting it here guarantees those observers run for the app's whole
    // lifetime, not just after the first screen that happens to request it.
    @Inject
    lateinit var pushRegistrationManager: dagger.Lazy<com.kachat.app.services.PushRegistrationManager>

    @Inject
    lateinit var nodePoolManager: dagger.Lazy<com.kachat.app.services.NodePoolManager>

    @Inject
    lateinit var groupRepository: dagger.Lazy<GroupRepository>

    // Address-activity notifications (external receipts on spending/cold addresses): per-wallet
    // baseline + diff engine, foreground poll + on-foreground catch-up. See its class doc.
    @Inject
    lateinit var addressActivityNotifier: dagger.Lazy<com.kachat.app.services.AddressActivityNotifier>

    // Continuous automatic Nextcloud sync — same lazy-singleton reasoning as the scanners
    // above: its init block observes the connected account + Automatic Sync toggle (schedules or
    // cancels the 6h WorkManager fallback) and the active wallet (silent one-time auto-restore
    // of the shared backup file). Field-injecting it here guarantees those observers run from
    // app startup, not only after the Nextcloud storage screen happens to be opened. The
    // lifecycle triggers below also feed it.
    @Inject
    lateinit var nextcloudSyncService: dagger.Lazy<com.kachat.app.services.NextcloudSyncService>

    // For mirroring the persisted "Verbose API Logging" toggle into ApiLogging.verbose (a plain
    // volatile flag the OkHttp logging interceptor reads per request) — see onCreate below.
    @Inject
    lateinit var appSettingsRepository: dagger.Lazy<com.kachat.app.repository.AppSettingsRepository>

    // Foreground-policy flag: the local poll/scan notification paths post banners while the app
    // is on screen (only the open conversation is suppressed, inside NotificationHelper itself),
    // and defer to remote push only while backgrounded. The lifecycle observer below keeps the
    // flag current.
    @Inject
    lateinit var notificationHelper: dagger.Lazy<com.kachat.app.services.NotificationHelper>

    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    // .kachat registrations in flight (testnet only) resume on every foreground, like iOS's
    // app-active (KaChat 5df42b4). Lazy, so a mainnet launch never even builds the names stack.
    @Inject
    lateinit var kachatNamesActions: dagger.Lazy<com.kachat.app.services.kachatnames.KachatNamesActions>

    // Identity is .kachat everywhere (iOS e52357d): every contact name and avatar reads the
    // registry's identity cache and the social image cache through their companions, so both are
    // built at startup - on mainnet too since iOS d36fc42, for address profiles (profilesEnabled).
    @Inject
    lateinit var kachatNamesRegistry: dagger.Lazy<com.kachat.app.services.kachatnames.KachatNamesRegistry>

    @Inject
    lateinit var kachatSocialImages: dagger.Lazy<com.kachat.app.services.kachatnames.KachatSocialImageResolver>

    /** The contacts the .kachat sweep below asks about (iOS 3214a2b). */
    @Inject
    lateinit var chatRepository: dagger.Lazy<com.kachat.app.repository.ChatRepository>

    // Temp files a crash or a dismissed share left behind are swept once per launch (iOS 83806b3d).
    @Inject
    lateinit var cacheManager: dagger.Lazy<com.kachat.app.services.CacheManager>

    // The Address Book (iOS 00767a4): built at startup because every display name reads it
    // through its companion (addressDisplayName), and it follows the active wallet on its own.
    @Inject
    lateinit var addressBookManager: dagger.Lazy<com.kachat.app.services.AddressBookManager>

    // Payment requests (iOS c6851d6): built at startup because a request card or a pasted link
    // asks whether it is your own through its companion (PaymentRequest.isOwn).
    @Inject
    lateinit var paymentRequestWallet: dagger.Lazy<com.kachat.app.services.PaymentRequestWallet>

    /** Sends the payment notices an empty chatting address could not, once it has KAS
     *  (iOS 710fdc0, IOS-086). */
    @Inject
    lateinit var paymentNoticeOutbox: dagger.Lazy<com.kachat.app.services.PaymentNoticeOutbox>

    /** Everyone's profile looked up again hourly while the app is open (iOS d1844582). */
    @Inject
    lateinit var contactProfileRescanner: dagger.Lazy<com.kachat.app.services.ContactProfileRescanner>

    /** kUSDT balances checked every 90 s while the app is open, so a token that arrives is
     *  announced (iOS 5b7c772c `TokenWallet.startPolling`, MainTabView). */
    @Inject
    lateinit var tokenWallet: dagger.Lazy<com.kachat.app.services.tokens.TokenWallet>

    // Public Chats (iOS 148a5331, from KaPost): built at startup so its observers - the
    // always-listen scan, the foreground sweep and the chess arena's stream gate - run for the
    // app's whole lifetime, not only once a room is opened.
    @Inject
    lateinit var broadcastScanningService: dagger.Lazy<com.kachat.app.services.BroadcastScanningService>

    // KaPost's scheduled posts: built at startup so the coins every waiting post will spend are
    // reserved before any send can pick them, and due posts go out on each foreground.
    @Inject
    lateinit var kaPostsScheduledStore: dagger.Lazy<com.kachat.app.services.KaPostsScheduledStore>

    // KaPost activity (likes, replies, quotes on your posts) while the app is open; the push
    // service covers it while closed (iOS 148a5331 `KaPostsNotificationService.start/stop`).
    @Inject
    lateinit var kaPostsNotificationPoller: dagger.Lazy<com.kachat.app.services.KaPostsNotificationPoller>

    // Profile's bell count (KaPost's unseen plus the wallet's): built once so both stores load.
    @Inject
    lateinit var allNotificationsBadge: dagger.Lazy<com.kachat.app.ui.screens.AllNotificationsBadge>

    // Portfolio rows still waiting for a price resume pricing whenever a wallet loads (its init
    // follows the active wallet) - built just after launch, off the main thread.
    @Inject
    lateinit var portfolioRepository: dagger.Lazy<com.kachat.app.repository.PortfolioRepository>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory()).build()

    /**
     * WorkManager initializes itself the first time anything asks for it - and a singleton built
     * early (NextcloudSyncService's init cancels or schedules its work on a background thread)
     * could ask before the [hiltWorkerFactory] field below had been injected,
     * which crashed with an uninitialized lateinit. The Hilt component already exists by then, so
     * the factory is read straight from it in that window.
     */
    private fun workerFactory(): HiltWorkerFactory =
        if (::hiltWorkerFactory.isInitialized) hiltWorkerFactory
        else dagger.hilt.android.EntryPointAccessors
            .fromApplication(this, WorkerFactoryEntryPoint::class.java)
            .hiltWorkerFactory()

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface WorkerFactoryEntryPoint {
        fun hiltWorkerFactory(): HiltWorkerFactory
    }

    /** One non-essential startup step: a failure is logged and the app opens without it. */
    private fun startupStep(what: String, block: () -> Unit) =
        com.kachat.app.util.StartupSafety.step(TAG, what, block)

    /** For the coroutines launched below: a failure is logged, never the end of the process. */
    private val logOnly = com.kachat.app.util.StartupSafety.logOnlyHandler(TAG)

    override fun onCreate() {
        super.onCreate()

        // How did the previous process end? A native crash or an OS kill never reaches the
        // exception handler installed in attachBaseContext; the system's own exit history does.
        startupStep("process exit history") { CrashRecorder.noteProcessExits(this) }

        // The always-on services, built now exactly as field injection used to build them (same
        // order), each on its own so one failing cannot take the others - or the app - down.
        // Every one of them is reached again through its Lazy below, and from screens.
        startupStep("group scanning") { groupScanningService.get() }
        startupStep("push registration") { pushRegistrationManager.get() }
        startupStep("node pool") { nodePoolManager.get() }
        startupStep("groups") { groupRepository.get() }
        startupStep("address activity") { addressActivityNotifier.get() }
        startupStep("Nextcloud sync") { nextcloudSyncService.get() }
        startupStep("settings") { appSettingsRepository.get() }
        startupStep("notifications") { notificationHelper.get() }
        // The Address Book's companion is what every display name reads, and its own init
        // follows each wallet load - so it is still built here, at launch, only guarded.
        startupStep("Address Book") { addressBookManager.get() }
        startupStep("payment requests") { paymentRequestWallet.get() }
        startupStep("payment notices") { paymentNoticeOutbox.get() }
        // KaPost and Public Chats (iOS 148a5331)
        startupStep("public chat scanning") { broadcastScanningService.get() }
        startupStep("scheduled KaPosts") { kaPostsScheduledStore.get() }
        // KaPost activity polling and the bell count load their caches (the KNS profile cache, the
        // notification stores) as they are built - nothing the first frame shows - so they are built
        // off the main thread; a screen that needs one first simply waits for it to finish.
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default + logOnly) {
            startupStep("KaPost notifications") { kaPostsNotificationPoller.get() }
            startupStep("notification bell count") { allNotificationsBadge.get() }
        }
        // Reserve the coins every still-waiting scheduled post is going to spend, before any
        // send can pick them.
        startupStep("scheduled KaPosts reload") { kaPostsScheduledStore.get().reloadIfNeeded() }
        // The portfolio's wallet-load price backfill: off the main thread, so a portfolio store
        // that will not open can never keep the app from opening.
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default + logOnly) {
            startupStep("portfolio price backfill") { portfolioRepository.get() }
        }

        // Swap and Simple Mode left the app (iOS d54ea87, eb2ccac), and calls with them (iOS
        // 7c5c22e3): what they kept on this phone is deleted once, off the main thread. KaPosts,
        // Public Chats, Chess, Portfolio, Cold Storage and the .kachat names are back (iOS
        // 148a5331), so nothing of theirs is deleted any more.
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO + logOnly) {
            startupStep("retired feature cleanup") {
                com.kachat.app.services.RetiredFeatureCleanup.deleteLocalFiles(this@KaChatApplication)
            }
            appSettingsRepository.get().removeRetiredFeatureKeys()
            // Payment notices a 5.3 b1 build stored as bubbles (Android only, once).
            val prefs = getSharedPreferences("kachat_upgrade_repairs", MODE_PRIVATE)
            if (!prefs.getBoolean("pool_envelope_rows_v1", false)) {
                runCatching { chatRepository.get().repairStoredPoolEnvelopes() }
                    .onSuccess { prefs.edit().putBoolean("pool_envelope_rows_v1", true).apply() }
                    .onFailure { android.util.Log.w("KaChatApplication", "pool envelope repair failed", it) }
            }
            // Temp files older than a day are nobody's any more (iOS 83806b3d `sweepStaleTempFiles`).
            runCatching { cacheManager.get().sweepStaleTempFiles() }
        }

        // The .kachat identity caches the display rules read (see the fields above). Every
        // network since iOS d36fc42 (`profilesEnabled`): mainnet has no registry yet, but address
        // profiles need none, so its identities are profile-only (avatars, banners, bios).
        startupStep(".kachat identity caches") {
            if (com.kachat.app.services.kachatnames.KachatNamesService.profilesEnabled) {
                startupStep(".kachat registry") { kachatNamesRegistry.get() }
                startupStep(".kachat social images") { kachatSocialImages.get() }
                // People shown as their address are asked again for a .kachat name, once a
                // minute while the app is open (iOS 3214a2b).
                startupStep(".kachat unnamed sweep") {
                    kachatNamesRegistry.get().startUnnamedIdentitySweep(
                        isActive = {
                            ProcessLifecycleOwner.get().lifecycle.currentState
                                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                        },
                        candidates = { unnamedContactAddresses() },
                    )
                }
            }
        }

        // Background catch-up for GROUP chat only (see SyncWorker's doc comment): groups have no
        // remote push (the push registration is the LegacyV1 shape with no watched_group_ids), so
        // this 15-minute WorkManager cadence is their sole closed-app notification path. All
        // push-covered surfaces (1:1 DMs) are delivered by FCM when the app
        // is backgrounded or killed. KEEP means re-registering on every app launch doesn't stack
        // duplicate periodic jobs.
        startupStep("group sync worker") {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                // UPDATE (not KEEP) so the CONNECTED constraint below reaches devices whose job was
                // enqueued by an older build without it; the schedule itself is unchanged.
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    // No network means the indexer catch-up inside can only fail and burn a run
                    // (WorkManager would still wake the process for it) — wait for connectivity.
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        // Keep ApiLogging.verbose (read by the OkHttp interceptors in AppModule on every request)
        // in sync with the persisted Verbose API Logging toggle for the process's whole lifetime.
        // A volatile flag instead of collecting the Flow inside the interceptor because that code
        // runs on OkHttp threads for every request of the 2s sync loop.
        ProcessLifecycleOwner.get().lifecycleScope.launch(logOnly) {
            appSettingsRepository.get().verboseApiLogging.collect { com.kachat.app.util.ApiLogging.verbose = it }
        }
        // Show .kachat Endings: the display rules (KachatNameLabel) read it synchronously from
        // plain functions like addressDisplayName, so it lives in snapshot state (iOS 94ed3a35).
        ProcessLifecycleOwner.get().lifecycleScope.launch(logOnly) {
            appSettingsRepository.get().showKachatNameEndings.collect { com.kachat.app.models.KachatNameLabel.showsEnding = it }
        }

        // Foreground/background transitions. There is deliberately NO mechanism here keeping the
        // process alive while backgrounded: FCM push (see PushRegistrationManager) is the only
        // delivery path for 1:1 DMs once the app leaves the foreground,
        // so a push-delivery problem surfaces as missing notifications instead of being silently
        // masked by a background poller. The in-process pollers normally just freeze with the
        // process and resume on foreground; on battery-exempted devices where the process stays
        // alive, ChatRepository's poll loop and NodePoolManager's probe loop additionally pause
        // themselves while backgrounded with push active (see their gates) so an exempted
        // process doesn't poll forever. The group block-stream scanner gates itself too: it runs
        // only with at least one group, foregrounded, on an unmetered network (indexer catch-up +
        // the 15-min SyncWorker cover the gaps; see its class doc) — a full block stream is the
        // app's single biggest data cost.
        val foregroundObserver = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Backgrounded: remote push takes over as the notification source for the
                // push-covered surfaces — the local paths stop posting their own banners.
                startupStep("notifications: background") { notificationHelper.get().setAppForeground(false) }
                startupStep("address activity: background") { addressActivityNotifier.get().onAppBackground() }
                startupStep("profile rescans: background") { contactProfileRescanner.get().stop() }
                startupStep("kUSDT poll: background") { tokenWallet.get().stopPolling() }
                // KaPost's in-app activity poller runs only while the app is active (iOS 148a5331).
                startupStep("KaPost notifications: stop") { kaPostsNotificationPoller.get().stop() }
                // Backgrounding is the natural "done chatting" moment — run the Nextcloud
                // automatic sync then: a catch-up when the last upload is an hour old or the
                // persisted dirty flag says a sync is owed (NEXTCLOUD_SYNC.md §6). autoBackupIfDue
                // no-ops unless the Automatic Sync toggle is on and an account is connected; it
                // swallows its own failures (the next trigger or the fallback worker retries).
                owner.lifecycleScope.launch(Dispatchers.IO + logOnly) {
                    nextcloudSyncService.get().autoBackupIfDue()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                // Foregrounded: local poll/scan banners fire again (only the conversation open
                // on screen is suppressed); a racing push for the same tx collapses via the
                // txId dedupe inside NotificationHelper.
                startupStep("notifications: foreground") { notificationHelper.get().setAppForeground(true) }
                // Immediate catch-up diff for address activity (external receipts while the app
                // was closed, first-run silent baseline seeding), then its foreground poll loop.
                startupStep("address activity: foreground") { addressActivityNotifier.get().onAppForeground() }
                // While the app is open, everyone's profile once an hour (iOS d1844582).
                startupStep("profile rescans: foreground") { contactProfileRescanner.get().start() }
                // kUSDT received while the app is open is announced (only with Address Activity on)
                startupStep("kUSDT poll: foreground") { tokenWallet.get().startPolling() }
                // KaPost activity while the app is active (iOS 148a5331).
                startupStep("KaPost notifications: start") { kaPostsNotificationPoller.get().start() }
                // A scheduled post only this phone holds goes out when its time has come, each
                // time the app comes to the front (iOS KaPostsScheduledStore on didBecomeActive).
                owner.lifecycleScope.launch(Dispatchers.IO + logOnly) {
                    try {
                        kaPostsScheduledStore.get().sendDueLocally()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("KaChatApplication", "Scheduled posts: local send failed", e)
                    }
                }
                // A batch of gRPC connections can die silently while backgrounded/asleep (the OS
                // tears down sockets, and each KaspadConnection's own self-reconnect can be
                // suspended along with the rest of the app) - reconnect any that are dead right
                // now instead of waiting for the next 5-30s probe cycle to notice and replace them.
                startupStep("node pool: reconnect") { nodePoolManager.get().reconnectStaleConnections() }
                // This wallet's own profile follows the chain on every network (a profile saved
                // on another device shows here; iOS 5d4ce87).
                startupStep(".kachat resume") {
                    if (com.kachat.app.services.kachatnames.KachatNamesService.profilesEnabled) {
                        kachatNamesActions.get().resume()
                    }
                }
                if (runCatching { com.kachat.app.services.kachatnames.KachatNamesService.isLaunched }.getOrDefault(false)) {
                    // .kachat news for the Profile bell (offers, sales, renewal, expiry): the
                    // refresh runs KachatNamesNotifier (iOS 86471dd). Where names are live
                    // (testnet-10, and mainnet since iOS ef6b21e).
                    owner.lifecycleScope.launch(Dispatchers.IO + logOnly) {
                        try {
                            kachatNamesRegistry.get().refreshIfStale()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("KaChatApplication", ".kachat registry refresh failed", e)
                        }
                    }
                }
                // Group invites (gctl_root) otherwise only surface via the 15-min SyncWorker
                // periodic job or the live block-scan - unlike 1:1 chat, which has its own
                // always-running poll loop, groups had no on-foreground catch-up at all, so a
                // brand-new invite could sit unseen well past 15 minutes. Mirrors iOS's
                // performCatchUpSync() on app foreground (KaChatApp.swift).
                owner.lifecycleScope.launch(Dispatchers.IO + logOnly) {
                    // Never let a foreground catch-up take down the app on launch — an uncaught
                    // throw here (e.g. no active account yet on a cold start) would otherwise crash
                    // the whole process. Same non-fatal treatment as the FGS start in onStop above.
                    try {
                        // Coalesced with the catch-up a group opened from a tapped notification
                        // runs at the same moment.
                        groupRepository.get().runGroupCatchUp()
                    } catch (e: Exception) {
                        android.util.Log.w("KaChatApplication", "Foreground group catch-up sync failed", e)
                    }
                }
                // Nextcloud sync catch-up on launch/foreground: covers users who never
                // background the app cleanly (force-kill, crash, days of disuse). The day-long
                // threshold keeps this from ever competing with the hourly on-background cadence.
                owner.lifecycleScope.launch(Dispatchers.IO + logOnly) {
                    nextcloudSyncService.get().autoBackupIfDue(
                        minIntervalMs = com.kachat.app.services.NextcloudSyncService.AUTO_BACKUP_CATCH_UP_INTERVAL_MS
                    )
                }
            }
        }
        startupStep("lifecycle observer") { ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver) }
    }

    private companion object {
        const val TAG = "KaChatApplication"
    }

    /**
     * The contacts the app shows as an address unless they have a .kachat name: no name you gave
     * them in the chat or the Address Book, not your own address - the people you talk to most
     * recently first (iOS KachatNamesRegistry.refreshUnnamedIdentities).
     */
    private suspend fun unnamedContactAddresses(): List<String> {
        val repo = chatRepository.get()
        val contacts = repo.getContacts().first()
        val latest = repo.getLatestMessages().first().associate { it.contactId to it.blockTimestamp }
        return contacts
            .filter { contact ->
                contact.alias.isNullOrBlank() &&
                    com.kachat.app.services.AddressBookManager.nameFor(contact.id) == null &&
                    // the self-chat: a contact row of the account's own address
                    !contact.id.equals(contact.walletAddress, ignoreCase = true)
            }
            .sortedByDescending { latest[it.id] ?: 0L }
            .map { it.id }
    }
}
