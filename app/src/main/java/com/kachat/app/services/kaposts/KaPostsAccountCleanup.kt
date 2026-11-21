package com.kachat.app.services

import android.content.Context
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delete Account, for KaPost (iOS ee5ec98, as KaPost's `WalletViewModel.deleteWallet` does it):
 * everything one account kept on this phone for KaPost - drafts, the bell's count, held post text,
 * bookmarks and reposts, follows, mutes, blocks, Tips From, the follow sync, scheduled posts and
 * their reminders, and the saved pages any account read. Call it before the keys go.
 */
@Singleton
class KaPostsAccountCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unseenStore: KaPostsUnseenStore,
    private val bookmarkStore: KaPostsBookmarkStore,
    private val repostStore: KaPostsRepostStore,
    private val settings: AppSettingsRepository,
) {
    suspend fun removeAccount(address: String) {
        for (variant in com.kachat.app.util.KaspaNetwork.accountAddressVariants(address)) {
            com.kachat.app.ui.screens.KaPostDraftStore.deleteAll(context, variant)
            unseenStore.removeAllData(variant)
            KaPostsHeldPostStore.removeAllData(context, variant)
            KaPostsFollowSyncStore.removeAllData(context, variant)
            bookmarkStore.removeAllData(variant)
            repostStore.removeAllData(variant)
            runCatching { settings.removeKaPostsAccountData(variant) }
        }
        // Nothing an account read stays behind (iOS ee5ec98 `KaPostsPageCache.removeAll`).
        KaPostsPageCache.removeAll(context)
        // Its scheduled posts and their reminders (every network's form of the address).
        KaPostsScheduledStore.deleteAccount(context, address)
    }
}
