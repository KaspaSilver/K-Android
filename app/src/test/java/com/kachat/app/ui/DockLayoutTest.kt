package com.kachat.app.ui

import android.app.UiModeManager
import androidx.appcompat.app.AppCompatDelegate
import com.kachat.app.models.AppAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixed dock (iOS 518fdc8c: no Customize Dock) and the window's night mode. */
class DockLayoutTest {

    @Test
    fun `the dock is always Kaspa Hub, Wallet, KaChat, Profile, Settings`() {
        val (dock, _) = DockLayout.arrangement(emptyList())
        assertEquals(listOf(AppTab.HUB, AppTab.WALLET, AppTab.CHATS, AppTab.PROFILE, AppTab.SETTINGS), dock)
        // a Hub order saved before can't put anything into the dock
        val (dockAgain, _) = DockLayout.arrangement(listOf(AppTab.GAMES, AppTab.PORTFOLIO))
        assertEquals(AppTab.DEFAULT_DOCK, dockAgain)
    }

    @Test
    fun `the Hub keeps a saved order and lists every other tab once`() {
        val (dock, hub) = DockLayout.arrangement(listOf(AppTab.GAMES, AppTab.WALLET, AppTab.PORTFOLIO, AppTab.GAMES))
        // its own order first; a dock tab named in it (Customize Dock had moved it) is left out
        assertEquals(listOf(AppTab.GAMES, AppTab.PORTFOLIO), hub.take(2))
        assertTrue(dock.none { it in hub })
        assertEquals(hub.size, hub.toSet().size)
        assertEquals(AppTab.entries.toSet(), (dock + hub).toSet())
    }

    @Test
    fun `a tab added in an update lands at the end of the Hub`() {
        val (_, hub) = DockLayout.arrangement(listOf(AppTab.KACHAT_STATS))
        assertEquals(AppTab.KACHAT_STATS, hub.first())
        assertEquals(AppTab.entries.size - AppTab.DEFAULT_DOCK.size, hub.size)
    }

    @Test
    fun `the Appearance setting maps to the window's night mode`() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, WindowAppearance.uiModeNightMode(AppAppearance.SYSTEM))
        assertEquals(UiModeManager.MODE_NIGHT_NO, WindowAppearance.uiModeNightMode(AppAppearance.LIGHT))
        assertEquals(UiModeManager.MODE_NIGHT_YES, WindowAppearance.uiModeNightMode(AppAppearance.DARK))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, WindowAppearance.appCompatNightMode(AppAppearance.SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, WindowAppearance.appCompatNightMode(AppAppearance.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, WindowAppearance.appCompatNightMode(AppAppearance.DARK))
    }
}
