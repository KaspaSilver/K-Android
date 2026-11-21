package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A per-account, persisted list of post ids (txids) this account has marked: the base of
 * [KaPostsBookmarkStore] and [KaPostsRepostStore] (iOS f954738 `KaPostsPostMarkStore`).
 *
 * One instance for the whole app, so the Feed and Profile tabs (each its own KaPostsViewModel)
 * read and write the same marks. Kept per account like [KaPostsWatchStore]: each account is its
 * own KaPost identity. Follows the open account by itself ([WalletManager.activeAddressFlow]), and
 * every read checks it too, so one account's marks are never read or written as another's.
 */
open class KaPostsPostMarkStore(
    context: Context,
    private val walletManager: WalletManager,
    private val keyPrefix: String,
    /** Oldest marks fall off past this, so the list can't grow without bound. */
    private val capacity: Int,
) {
    /** One mark set or cleared: view models holding copies of posts bring their flags in step. */
    data class Change(val postId: String, val isOn: Boolean)

    private val prefs = context.getSharedPreferences("kaposts_post_marks", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /** Bumped on every change or account switch, for a screen that lists the marks. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** The account [order] was read for (lowercased), null when logged out. */
    private var loadedWallet: String? = null
    /** Oldest first. */
    private var order: MutableList<String> = mutableListOf()
    private var marked: MutableSet<String> = mutableSetOf()

    init {
        load(normalized(walletManager.activeAddressFlow.value))
        scope.launch {
            walletManager.activeAddressFlow.collect { address ->
                val wallet = normalized(address)
                synchronized(this@KaPostsPostMarkStore) {
                    if (wallet == loadedWallet) return@collect
                    load(wallet)
                }
                _revision.value++
            }
        }
    }

    private fun normalized(address: String?): String? =
        address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun key(wallet: String) = "${keyPrefix}_$wallet"

    private fun load(wallet: String?) {
        loadedWallet = wallet
        order = wallet?.let { read(key(it)) }?.toMutableList() ?: mutableListOf()
        marked = order.toMutableSet()
    }

    private fun read(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Reloads quietly when the open account isn't the one loaded (the collector runs first in
     *  practice; this covers a read that comes before it). */
    private fun syncToCurrentWallet() {
        val current = normalized(runCatching { walletManager.getAddress() }.getOrNull())
        if (current != loadedWallet) load(current)
    }

    @Synchronized
    fun contains(postId: String?): Boolean {
        if (postId.isNullOrEmpty()) return false
        syncToCurrentWallet()
        return postId in marked
    }

    /** Every marked id, newest mark first. */
    @Synchronized
    fun ids(): List<String> {
        syncToCurrentWallet()
        return order.reversed()
    }

    fun set(postId: String, isOn: Boolean) {
        if (postId.isEmpty()) return
        synchronized(this) {
            syncToCurrentWallet()
            val wallet = loadedWallet ?: return
            if ((postId in marked) == isOn) return
            if (isOn) {
                marked.add(postId)
                order.add(postId)
                if (order.size > capacity) {
                    val dropped = order.removeAt(0)
                    marked.remove(dropped)
                }
            } else {
                marked.remove(postId)
                order.removeAll { it == postId }
            }
            prefs.edit().putString(key(wallet), JSONArray(order).toString()).apply()
        }
        _revision.value++
        _changes.tryEmit(Change(postId, isOn))
    }

    /** Delete Account: one wallet's marks, from this device. */
    fun removeAllData(walletAddress: String) {
        val wallet = normalized(walletAddress) ?: return
        synchronized(this) {
            prefs.edit().remove(key(wallet)).apply()
            if (loadedWallet == wallet) {
                order = mutableListOf()
                marked = mutableSetOf()
            }
        }
        _revision.value++
    }
}

/**
 * Bookmarked posts and replies, per account: the bookmark on every card and the Bookmarks
 * screen. Private to this phone; nothing goes on chain (iOS f954738 `KaPostsBookmarkStore`).
 */
@Singleton
class KaPostsBookmarkStore @Inject constructor(
    @ApplicationContext context: Context,
    walletManager: WalletManager,
) : KaPostsPostMarkStore(context, walletManager, "kachat_kaposts_bookmarks", 1000)

/**
 * Posts this account has reposted (a plain repost or a quote) from this phone, per account
 * (iOS f954738 `KaPostsRepostStore`).
 *
 * The indexer's post objects say whether you liked or disliked a post but not whether you
 * reposted it, so after a refresh Repost was offered again and a second tap paid for a second
 * repost. Recorded when a repost goes out (or may have), cleared when Remove repost does, and
 * merged into every post as it is mapped.
 */
@Singleton
class KaPostsRepostStore @Inject constructor(
    @ApplicationContext context: Context,
    walletManager: WalletManager,
) : KaPostsPostMarkStore(context, walletManager, "kachat_kaposts_reposted", 5000)
