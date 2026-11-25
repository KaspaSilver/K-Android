package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaspaNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Delete Account also removes for the features KaChat took back from KaPost, KaPay and KaPlay
 * (iOS 148a5331 `WalletManager.removeLocalData`): KaPost's drafts, unseen activity, follows, mutes,
 * blocks, tips from, held posts, bookmarks and scheduled posts; the account's Public Chats rooms,
 * hidden senders and unsent rows (the rooms' messages too when no account is left); its bot
 * games' records; the arena's rows still waiting for the chain; and what Trestly's code kept for
 * its key. Since iOS 428a797b (audit 2026-11-21 #9) every ported store too: Cold Storage,
 * Portfolio, KaPost's watched authors and last-seen mark, the rooms' read marks, and .kachat's
 * claims in progress, profile copy, market addresses, notifier record and owned-name caches.
 *
 * Called by `WalletViewModel.wipeDeletedAccountData` for each of the account's addresses, after
 * the account's record is marked for wiping, so a wipe the app is closed in the middle of is
 * finished on the next launch. Every step is on its own: one that fails is logged, the rest go on.
 * Everything is reached through [dagger.Lazy], so nothing here is built until an account goes.
 */
@Singleton
class AccountFeatureCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val database: KaChatDatabase,
    private val kaPostsAccountCleanup: dagger.Lazy<KaPostsAccountCleanup>,
    private val broadcastRepository: dagger.Lazy<com.kachat.app.repository.BroadcastRepository>,
    private val chessBotProgress: dagger.Lazy<com.kachat.app.services.chess.ChessBotProgress>,
    private val kusdtStoreRelay: dagger.Lazy<com.kachat.app.services.tokens.KusdtStoreRelay>,
    // Every ported store goes too (iOS 428a797b `removeLocalData`, audit 2026-11-21 #9).
    private val coldStorageManager: dagger.Lazy<ColdStorageManager>,
    private val portfolioLedgerStore: dagger.Lazy<PortfolioLedgerStore>,
    private val kaPostsNotificationPoller: dagger.Lazy<KaPostsNotificationPoller>,
    // KaPlay's arcade (iOS 518fdc8c `ArcadeProgress.removeAll(forAddress:)`).
    private val arcadeProgress: dagger.Lazy<com.kachat.app.services.arcade.ArcadeProgress>,
) {
    /**
     * [variant] is one of the deleted account's addresses (any network's form). [allDeleted] is
     * every address being deleted with it, to tell whether any other account is left.
     */
    suspend fun removeAccount(variant: String, allDeleted: Collection<String>) {
        step("KaPost") { kaPostsAccountCleanup.get().removeAccount(variant) }
        // its bot games' records and stamped wins (both networks' addresses, any case)
        step("chess bot records") { chessBotProgress.get().removeAll(variant) }
        // its arcade scores, stamped and waiting (Snake, 2048; iOS 518fdc8c WalletManager)
        step("arcade records") { arcadeProgress.get().removeAll(variant) }
        // Public Chats (iOS `PublicChatStore.destroyLocalStore`): the rooms it joined, the senders
        // it hid, the rows it never got out; then rows of rooms no account is in any more.
        step("public chat rooms") {
            val dao = database.broadcastDao()
            dao.deleteChannelsForWallet(variant)
            dao.deleteHiddenSendersForWallet(variant)
            dao.deleteUnsentMessagesFrom(variant)
            dao.deleteMessagesOfUnjoinedChannels()
            val othersLeft = walletManager.getAllAccounts().any { account ->
                allDeleted.none { KaspaNetwork.isSameAccount(it, account.address) }
            }
            if (!othersLeft) {
                dao.deleteAllMessages()
                broadcastRepository.get().resetAfterRoomWipe()
            }
        }
        // The arena's rows it sent and is still waiting to see on chain (iOS
        // `publicChatLocalStampSentAt.<address>`, filed under the lowercased address too).
        step("arena stamps") {
            context.getSharedPreferences(LOCAL_STAMPS_PREFS, Context.MODE_PRIVATE).edit()
                .remove("$LOCAL_STAMPS_KEY.$variant")
                .remove("$LOCAL_STAMPS_KEY.${variant.lowercase()}")
                .apply()
        }
        // Trestly's stored data for the account's key (iOS `TokenWallet.forgetLocalData` ->
        // `KusdtStoreRelay.forget`).
        step("Trestly store") {
            com.kachat.app.services.tokens.Kusdt.schnorrOwner(variant)?.let {
                kusdtStoreRelay.get().forget(com.kachat.app.services.tokens.Kusdt.hex(it))
            }
        }
        // From KaPay (iOS 428a797b `removeLocalData`, audit #9): Cold Storage (kpubs, labels,
        // hidden addresses, snapshots) and Portfolio (portfolios, rows, fees). Importing the same
        // seed no longer brings them back.
        step("Cold Storage") { coldStorageManager.get().removeAllData(variant) }
        step("Portfolio") { portfolioLedgerStore.get().removeAllData(variant) }
        // KaPost: whose posts ring (`KaPostsWatchStore`) and the last notification already
        // surfaced (iOS `kachat_kaposts_watch_`, `kaposts_notifications_last_seen_`).
        step("KaPost watch and seen") {
            context.getSharedPreferences(KAPOSTS_WATCH_PREFS, Context.MODE_PRIVATE).edit()
                .remove("kachat_kaposts_watch_${variant.lowercase()}")
                .remove("kachat_kaposts_watch_$variant")
                .apply()
            kaPostsNotificationPoller.get().removeAllData(variant)
        }
        // Public rooms: read marks, rooms marked unread, hidden curated rooms, default bells (iOS
        // `kachat_broadcast_*_<address>`, filed under the lowercased address here too).
        step("public room marks") {
            val lowered = variant.lowercase()
            context.getSharedPreferences(ROOM_READ_STATE_PREFS, Context.MODE_PRIVATE).edit()
                .remove("last_read_$lowered")
                .remove("manual_unread_$lowered")
                .remove("hidden_curated_$lowered")
                .remove("featured_notify_default_$lowered")
                .apply()
        }
        // .kachat (iOS 428a797b `KachatNamesActions.removeAllLocalData`): claims in progress
        // (`pending-<wallet>.json`), the own-profile copy, the market's addresses, the names
        // notifier's record, the expired-names banner's dismissal and the owned-names caches of
        // the wallet and its spending addresses, on both networks.
        step(".kachat") { removeKachatLocalData(variant, walletManager.cachedSpendingAddresses(variant)) }
        // The pushes that reached the phone and the .kachat names a KaPost push is named by (iOS
        // 2026-11-22 `clearAllSharedData`: `push_arrivals_v1`, `shared_identity_names`).
        step("push arrivals and known names") {
            PushArrivalRecord.clear(context)
            KnownKachatNames.clear(context)
            // the request senders Who Can Message You? silenced (iOS `chat_hidden_request_addresses`)
            HiddenRequestSenders.clear(context)
        }
        // Which chat payments were KaPost tips (iOS 2026-11-22 `KaPostTipLedger.clear`).
        step("KaPost tips") { KaPostTipLedger.clear(context) }
        // Swap: its history (ChangeNOW ids, payout addresses) and the accepted agreements, both
        // filed under the lowercased address (iOS 518fdc8c `SwapService.removeAllData`, audit
        // 2026-11-24 #5).
        step("Swap") { SwapService.removeAllData(context, variant) }
    }

    /** iOS 428a797b `KachatNamesActions.removeAllLocalData(forWallet:addresses:)`, in this app's
     *  file and key formats. Commit salts and reminders have their own removers (WalletManager). */
    private fun removeKachatLocalData(wallet: String, addresses: List<String>) {
        val lowered = wallet.lowercase()
        if (lowered.isEmpty()) return
        val market = context.getSharedPreferences(KACHAT_MARKET_PREFS, Context.MODE_PRIVATE)
        val marketKeys = listOf("kachat_market_addresses.$wallet", "kachat_market_addresses.$lowered")
        val marketAddresses = marketKeys.flatMap { key ->
            runCatching {
                market.getString(key, null)?.let { com.google.gson.JsonParser.parseString(it).asJsonArray.map { e -> e.asString } }
            }.getOrNull().orEmpty()
        }
        market.edit().apply { marketKeys.forEach { remove(it) } }.apply()
        context.getSharedPreferences(KACHAT_NOTIFIER_PREFS, Context.MODE_PRIVATE).edit()
            .remove("kachatNamesNotifier.$wallet").remove("kachatNamesNotifier.$lowered").apply()
        context.getSharedPreferences(KACHAT_EXPIRED_BANNER_PREFS, Context.MODE_PRIVATE).edit()
            .remove("kachatExpiredBannerDismissed.$wallet").remove("kachatExpiredBannerDismissed.$lowered").apply()
        val owned = context.getSharedPreferences(KACHAT_OWNED_NAMES_PREFS, Context.MODE_PRIVATE).edit()
        for (address in (listOf(lowered) + (addresses + marketAddresses).map { it.lowercase() }).distinct()) {
            for (network in listOf("mainnet", "testnet")) owned.remove("kachat_owned_names_${network}_$address")
        }
        owned.apply()
        val suffix = com.kachat.app.services.kachatnames.KachatNamesRegistry.walletSuffix(lowered)
        for (network in listOf("mainnet", "testnet-10")) {
            val dir = java.io.File(java.io.File(context.filesDir, "KachatNames"), network)
            for (name in listOf("pending-$suffix.json", "profile-$suffix.json")) {
                runCatching { java.io.File(dir, name).delete() }
            }
        }
    }

    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what not removed for a deleted account", e)
        }
    }

    private companion object {
        const val TAG = "AccountFeatureCleanup"
        // BroadcastRepository's arena stamps (its LOCAL_STAMPS_PREFS / LOCAL_STAMPS_KEY).
        const val LOCAL_STAMPS_PREFS = "broadcast_local_stamps"
        const val LOCAL_STAMPS_KEY = "publicChatLocalStampSentAt"
        // BroadcastReadStateStore's file.
        const val ROOM_READ_STATE_PREFS = "kachat_broadcast_read_state"
        // KaPostsWatchStore's file.
        const val KAPOSTS_WATCH_PREFS = "kaposts_watch"
        // .kachat's files: KachatNamesActions' market addresses, KachatNamesNotifier's record,
        // the expired-names banner's dismissal, KachatOwnedNamesCache.
        const val KACHAT_MARKET_PREFS = "kachat_market_addresses"
        const val KACHAT_NOTIFIER_PREFS = "kachat_names_notifier"
        const val KACHAT_EXPIRED_BANNER_PREFS = "kachat_expired_banner"
        const val KACHAT_OWNED_NAMES_PREFS = "kachat_owned_names"
    }
}
