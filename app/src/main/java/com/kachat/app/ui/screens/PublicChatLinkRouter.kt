package com.kachat.app.ui.screens

import android.content.Context
import com.kachat.app.R
import com.kachat.app.services.publicchats.PublicChatLink
import com.kachat.app.util.findFragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reaches the Public Chats store from link routing, which runs outside any screen. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PublicChatLinkDeps {
    fun broadcastRepository(): com.kachat.app.repository.BroadcastRepository
}

/**
 * Opens a Public Chats room from a link or a tapped push (iOS 148a5331 `KaChatLinkRouter
 * .openPublicChatRoom` and `AppDelegate.openRoomFromPush`). The room itself opens through
 * [BroadcastDeepLink], which the Chats list's `PublicRoomHandoffEffect` takes.
 */
object PublicChatLinkRouter {
    private val scope = MainScope()

    private fun deps(context: Context): PublicChatLinkDeps =
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, PublicChatLinkDeps::class.java)

    /** True when [channel] is already in this account's list (waits for the account on a cold
     *  start, up to 10 s, as iOS waits for the store). Default rooms too since iOS 824a30bb: no
     *  room is in the list unasked. */
    suspend fun isListed(context: Context, channel: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching { deps(context).broadcastRepository().isJoined(channel) }.getOrDefault(false)
        }
    }

    /**
     * A room link (a tapped card, a system link). Re-validated here, the last gate before a name
     * from a pasted link can create a row. A room already listed just opens; anything else joins
     * the user's list first, once they say so: any app or post can hand over a link, and the name
     * is whatever it likes. Default rooms too since iOS 824a30bb (owner, 2026-11-26).
     */
    fun open(context: Context, channel: String) {
        val name = PublicChatLink.normalizeAndValidateChannel(channel) ?: return
        val activity = context.findFragmentActivity()
        scope.launch {
            if (isListed(context, name)) {
                BroadcastDeepLink.request(name)
                return@launch
            }
            val host = activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@launch
            confirmJoin(host, name) { joinThenOpen(context, name) }
        }
    }

    /**
     * A room handed to the Chats list that isn't in it (an old @mention in Profile's bell for a
     * room since left): asked first, as a link is (iOS 824a30bb `askToJoinRoom`, owner 2026-11-26:
     * no room is added unasked).
     */
    fun askToJoinRoom(context: Context, name: String) {
        val host = context.findFragmentActivity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        confirmJoin(host, name, host.getString(R.string.roomsoptin_not_in_list_add)) { joinThenOpen(context, name) }
    }

    /** Joins (its bell on, as every room the person adds) and opens. */
    private fun joinThenOpen(context: Context, name: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val repository = deps(context).broadcastRepository()
                    if (!repository.isJoined(name)) repository.joinChannel(name)
                }.onFailure { android.util.Log.w("PublicChatLinkRouter", "Could not join #$name", it) }
            }
            BroadcastDeepLink.request(name)
        }
    }

    /**
     * A tapped room push: the room opens only while it is in the list. A room deleted since (or
     * never joined here) is not joined back by a tap.
     */
    fun openFromPush(context: Context, channel: String) {
        val name = PublicChatLink.normalizeAndValidateChannel(channel) ?: return
        scope.launch {
            if (!isListed(context, name)) {
                android.util.Log.i("PublicChatLinkRouter", "[Push] Ignored a tap on a push for a room that isn't joined")
                return@launch
            }
            BroadcastDeepLink.request(name)
        }
    }

    /** Asks before a link adds a room (iOS `confirmJoin`); [askMessage] in place of the link's. */
    private fun confirmJoin(activity: androidx.fragment.app.FragmentActivity, name: String, askMessage: String? = null, onJoin: () -> Unit) {
        var message = askMessage ?: activity.getString(R.string.pl_join_room_message)
        if (hasMixedScripts(name)) message += "\n\n" + activity.getString(R.string.pl_join_room_mixed_scripts)
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.pl_join_room_title, name))
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pc_join) { _, _ -> onJoin() }
            .show()
    }

    /** Letters from more than one of the alphabets that share look-alike shapes, the way a
     *  Cyrillic "а" passes for a Latin "a" in a well-known room's name. */
    private fun hasMixedScripts(name: String): Boolean {
        val seen = mutableSetOf<String>()
        var index = 0
        while (index < name.length) {
            val point = name.codePointAt(index)
            index += Character.charCount(point)
            if (!Character.isLetter(point)) continue
            when (point) {
                in 0x41..0x5A, in 0x61..0x7A, in 0xC0..0x24F -> seen += "latin"
                in 0x370..0x3FF, in 0x1F00..0x1FFF -> seen += "greek"
                in 0x400..0x52F, in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> seen += "cyrillic"
                in 0x530..0x58F -> seen += "armenian"
                in 0x13A0..0x13FF, in 0xAB70..0xABBF -> seen += "cherokee"
            }
        }
        return seen.size > 1
    }
}
