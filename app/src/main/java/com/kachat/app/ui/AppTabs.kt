package com.kachat.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import com.kachat.app.R
import com.kachat.app.ui.screens.AllNotificationsBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Every tab KaChat has (iOS `AppTab`). Since 2026-11-21 KaChat is the all-in-one app again: the
 * dock is fixed (iOS 518fdc8c: Kaspa Hub, Wallet, KaChat, Profile, Settings), and every other tab
 * is a tile in Kaspa Hub's grid, where it opens full screen ([DockLayout]). A tab's screen is the
 * same in either place, and routes open a tab wherever it lives (`MainShell.show`). A new feature
 * is a new case; it lands in Kaspa Hub.
 *
 * [key] is iOS's raw value, which is what the saved Hub order holds. [route] is the tab's own
 * navigation route when it sits in the dock. [dockIcon] is drawn on the dock (filled, as the
 * iPhone's tab bar fills its symbols), [hubIcon] in Kaspa Hub (outlined).
 */
enum class AppTab(
    val key: String,
    val route: String,
    @StringRes val labelRes: Int,
    val dockIcon: ImageVector,
    val hubIcon: ImageVector,
) {
    CHATS("chats", "chats", R.string.kachat, Icons.Default.Forum, Icons.Outlined.Forum),
    WALLET("wallet", "wallet", R.string.wallet_title, Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet),
    // "Contacts" (iOS bc3e1ff3); the route keeps its old name.
    ADDRESS_BOOK("addressBook", "address_book", R.string.ab_address_book, com.kachat.app.ui.screens.ContactsFilledIcon, com.kachat.app.ui.screens.ContactsFilledIcon),
    // Your KaPost profile since 2026-11-21 (iOS `KaPostsView(rootMode: .myProfile)`).
    PROFILE("profile", "profile", R.string.profile, Icons.Default.AccountCircle, Icons.Outlined.AccountCircle),
    SETTINGS("settings", "settings", R.string.settings_title, Icons.Default.Settings, Icons.Outlined.Settings),
    // Drawn as the Kaspa mark (iOS `KaspaDockIcon` / `AppTabIcon`); the vectors are fallbacks.
    HUB("hub", "kaspa_hub", R.string.shell_kaspa_hub, Icons.Default.GridView, Icons.Default.GridView),
    PORTFOLIO("portfolio", "portfolio", R.string.portfolio, Icons.Filled.PieChart, Icons.Outlined.PieChart),
    COLD_STORAGE("coldStorage", "cold_storage", R.string.cold_storage, com.kachat.app.ui.theme.IosSymbols.LockShield, com.kachat.app.ui.theme.IosSymbols.LockShield),
    // "Usernames", its icon the ".kachat" wordmark (owner's call, iOS 518fdc8c `AppTab.label`).
    KACHAT_NAMES("kachatNames", "kachat_names", R.string.shell54_usernames, Icons.Filled.AlternateEmail, Icons.Filled.AlternateEmail),
    // iOS square.text.square (KaPost's Feed tab)
    KAPOSTS("kaposts", "kaposts", R.string.shell_tab_kapost, Icons.AutoMirrored.Filled.Article, Icons.AutoMirrored.Outlined.Article),
    // KaPlay's Play tab. Its leaderboards are inside KaPlay > Chess since iOS 428a797b (owner's
    // call 2026-11-22): the old "leaderboard" tab is gone, and a saved arrangement naming it drops
    // it as it is read ([fromKey] knows no such key), as iOS's `AppTab(rawValue:)` does.
    GAMES("games", "games", R.string.kaplay_tab_games, Icons.Filled.SportsEsports, Icons.Outlined.SportsEsports),
    KACHAT_STATS("kachatStats", "kachat_stats", R.string.shell_tab_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    APPS("apps", "kaspa_websites", R.string.shell_tab_websites, Icons.Filled.Public, Icons.Outlined.Language);

    companion object {
        /** The most tabs the dock shows (iOS `AppTab.maxDockItems`). */
        const val MAX_DOCK_ITEMS = 5

        /** Always in the dock: Kaspa Hub (the way to everything else) and Profile (iOS
         *  `AppTab.pinnedToDock`, owner's call 2026-11-21). */
        val PINNED_TO_DOCK: List<AppTab> = listOf(HUB, PROFILE)

        /** The dock, always, in this order (iOS `AppTab.defaultDock`; no Customize Dock since
         *  iOS 518fdc8c). */
        val DEFAULT_DOCK: List<AppTab> = listOf(HUB, WALLET, CHATS, PROFILE, SETTINGS)

        fun fromKey(key: String): AppTab? = entries.firstOrNull { it.key == key }
        fun fromRoute(route: String?): AppTab? = route?.let { r -> entries.firstOrNull { it.route == r } }
    }
}

/**
 * What the dock holds and the order of Kaspa Hub's grid, on this device (iOS `DockLayout`). The
 * dock is fixed (owner's call, iOS 518fdc8c, 2026-11-23: no Customize Dock): Kaspa Hub, Wallet,
 * KaChat, Profile, Settings ([AppTab.DEFAULT_DOCK]), so the connection dot on Settings is always
 * there. An arrangement saved before keeps only its Hub order (`kachat_hub_tabs_v2`); the saved
 * dock (`kachat_dock_tabs_v2`) is dropped as it is read.
 */
object DockLayout {
    private const val PREFS = "kachat_dock_layout"
    private const val DOCK_KEY = "kachat_dock_tabs_v2"
    private const val HUB_KEY = "kachat_hub_tabs_v2"

    private var prefs: android.content.SharedPreferences? = null

    private val _dock = MutableStateFlow(normalized(AppTab.DEFAULT_DOCK, emptyList()).first)
    /** The dock, in order: always [AppTab.DEFAULT_DOCK]. */
    val dock: StateFlow<List<AppTab>> = _dock.asStateFlow()

    private val _hub = MutableStateFlow(normalized(AppTab.DEFAULT_DOCK, emptyList()).second)
    /** Kaspa Hub's grid, in order: every other tab. */
    val hub: StateFlow<List<AppTab>> = _hub.asStateFlow()

    /** Reads the saved Hub order once (the shell calls it as it first shows). */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val storedHub = p.getString(HUB_KEY, null).orEmpty().split(',').mapNotNull { AppTab.fromKey(it.trim()) }
        val (d, h) = arrangement(storedHub)
        _dock.value = d
        _hub.value = h
        // The dock Customize Dock saved is gone for good (iOS removes `dockKey` as it loads).
        if (p.contains(DOCK_KEY)) p.edit().remove(DOCK_KEY).apply()
    }

    /** The fixed dock and the Hub in [storedHub]'s order, every other tab after it. */
    internal fun arrangement(storedHub: List<AppTab>): Pair<List<AppTab>, List<AppTab>> =
        normalized(AppTab.DEFAULT_DOCK, storedHub)

    /**
     * A stored arrangement made whole: the pinned tabs in the dock, no tab twice, at most five in
     * the dock, and every tab not in the dock in the Hub (a feature added in an update lands at
     * the end of the Hub).
     */
    private fun normalized(dock: List<AppTab>, hub: List<AppTab>): Pair<List<AppTab>, List<AppTab>> {
        val seen = mutableSetOf<AppTab>()
        val newDock = dock.filter { seen.add(it) }.toMutableList()
        // A pinned tab missing from an older arrangement goes back where the default dock has it.
        for (pinned in AppTab.PINNED_TO_DOCK) {
            if (pinned !in newDock) {
                val at = AppTab.DEFAULT_DOCK.indexOf(pinned).takeIf { it >= 0 } ?: newDock.size
                newDock.add(minOf(at, newDock.size), pinned)
                seen.add(pinned)
            }
        }
        while (newDock.size > AppTab.MAX_DOCK_ITEMS) {
            val drop = newDock.indexOfLast { it !in AppTab.PINNED_TO_DOCK }
            if (drop < 0) break
            seen.remove(newDock.removeAt(drop))
        }
        val inDock = newDock.toSet()
        val newHub = hub.filter { it !in inDock && seen.add(it) }.toMutableList()
        for (tab in AppTab.entries) if (tab !in inDock && tab !in newHub) newHub.add(tab)
        return newDock to newHub
    }
}

/**
 * Which Hub section is open (iOS `HubRouter`). Shared, so a link or a notification for a tab that
 * lives in the Hub can open it there. Tapping the Kaspa Hub tab again goes back to the grid.
 */
object HubRouter {
    private val _openSection = MutableStateFlow<AppTab?>(null)
    val openSection: StateFlow<AppTab?> = _openSection.asStateFlow()

    fun open(tab: AppTab) {
        if (tab == AppTab.HUB) return
        _openSection.value = tab
    }

    fun close() {
        _openSection.value = null
    }
}

/**
 * The app icon's badge (iOS `ChatService.updateAppBadge`): the Chats tab's count (chats, groups,
 * and public rooms with their bell on) plus KaPost's unseen activity. The shell keeps it current
 * while it is on screen; whoever posts the launcher badge collects [count].
 */
object AppIconBadge {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    fun update(value: Int) {
        _count.value = value.coerceAtLeast(0)
    }
}

/** The dock's badge sources outside the chat view model (iOS `AppTabBadge`, `DockBadgeSync`). */
@HiltViewModel
class ShellBadgesViewModel @Inject constructor(
    allNotifications: AllNotificationsBadge,
    publicChats: com.kachat.app.services.publicchats.PublicChatUnreadBadge,
    kaPostsUnseen: com.kachat.app.services.KaPostsUnseenStore,
) : ViewModel() {
    /** Profile: every notification behind its bell, KaPost's and the wallet's. */
    val profileCount: StateFlow<Int> = allNotifications.count
    /** The rooms' part of the Chats badge: rooms with their bell on. */
    val roomsCount: StateFlow<Int> = publicChats.chatsBadgeCount
    /** KaPost's unseen activity, for the app icon. */
    val kaPostsUnseen: StateFlow<Int> = kaPostsUnseen.unseenCount
}
