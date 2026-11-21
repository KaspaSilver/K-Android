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
 * its key. Cold Storage accounts and portfolios stay, as on iOS 148a5331 (KaChat iOS doesn't
 * remove them; they follow the signed-in account).
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
) {
    /**
     * [variant] is one of the deleted account's addresses (any network's form). [allDeleted] is
     * every address being deleted with it, to tell whether any other account is left.
     */
    suspend fun removeAccount(variant: String, allDeleted: Collection<String>) {
        step("KaPost") { kaPostsAccountCleanup.get().removeAccount(variant) }
        // its bot games' records and stamped wins (both networks' addresses, any case)
        step("chess bot records") { chessBotProgress.get().removeAll(variant) }
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
    }
}
