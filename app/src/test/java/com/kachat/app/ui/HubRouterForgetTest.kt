package com.kachat.app.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Kaspa Hub forgets a section left over a minute ago (iOS b49a021d `HubRouter.rememberFor`). */
class HubRouterForgetTest {
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        HubRouter.now = { clock }
        HubRouter.close()
        HubRouter.noteEntered()
    }

    @After
    fun tearDown() {
        HubRouter.close()
        HubRouter.noteEntered()
        HubRouter.now = { android.os.SystemClock.elapsedRealtime() }
    }

    private fun anySection(): AppTab = AppTab.entries.first { it != AppTab.HUB }

    @Test
    fun remembersForAMinute() {
        HubRouter.open(anySection())
        HubRouter.noteLeft()
        clock += HubRouter.REMEMBER_FOR_MS
        assertFalse(HubRouter.isStale)
        assertFalse(HubRouter.forgetIfStale())
        assertEquals(anySection(), HubRouter.openSection.value)
    }

    @Test
    fun forgetsAfterAMinuteAway() {
        HubRouter.open(anySection())
        HubRouter.noteLeft()
        val before = HubRouter.forgetCount.value
        clock += HubRouter.REMEMBER_FOR_MS + 1
        assertTrue(HubRouter.isStale)
        assertTrue(HubRouter.forgetIfStale())
        assertNull(HubRouter.openSection.value)
        assertEquals(before + 1, HubRouter.forgetCount.value)
        assertFalse(HubRouter.forgetIfStale())
    }

    @Test
    fun comingBackStopsTheClock() {
        HubRouter.open(anySection())
        HubRouter.noteLeft()
        clock += 30_000
        HubRouter.noteEntered()
        clock += 120_000
        assertFalse(HubRouter.isStale)
    }

    @Test
    fun nothingOpenIsNeverStale() {
        HubRouter.noteLeft()
        clock += 10 * HubRouter.REMEMBER_FOR_MS
        assertFalse(HubRouter.isStale)
    }

    @Test
    fun openedOffTheHubStartsItsClock() {
        // a link or a push opens a section while another tab shows (audit 2026-11-27 L15)
        HubRouter.noteLeft()
        HubRouter.open(anySection())
        clock += HubRouter.REMEMBER_FOR_MS + 1
        assertTrue(HubRouter.isStale)
        assertTrue(HubRouter.forgetIfStale())
    }

    @Test
    fun aSectionWithNoClockOffTheHubCountsAsLeftNow() {
        HubRouter.open(anySection())
        // left without the clock starting (the shell's note came before the section opened)
        HubRouter.noteLeft()
        HubRouter.noteEntered()
        HubRouter.noteLeft()
        clock += 10 * HubRouter.REMEMBER_FOR_MS
        HubRouter.noteEntered()
        // on the Hub: never stale
        assertFalse(HubRouter.forgetIfStale())
    }

    @Test
    fun signOutForgetsTheSection() {
        HubRouter.open(anySection())
        val before = HubRouter.forgetCount.value
        HubRouter.reset()
        assertNull(HubRouter.openSection.value)
        assertEquals(before + 1, HubRouter.forgetCount.value)
    }

    @Test
    fun leavingAgainKeepsTheFirstTime() {
        HubRouter.open(anySection())
        HubRouter.noteLeft()
        clock += 40_000
        HubRouter.noteLeft()
        clock += 30_000
        assertTrue(HubRouter.isStale)
    }
}
