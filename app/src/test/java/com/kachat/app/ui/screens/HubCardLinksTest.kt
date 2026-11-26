package com.kachat.app.ui.screens

import com.kachat.app.ui.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kaspa Hub card links (iOS 961fc05a `AppTab.linkSlug`): fixed words, the dock's tabs have none. */
class HubCardLinksTest {
    @Test
    fun `cards have their fixed words`() {
        assertEquals("contacts", HubCardLinks.slug(AppTab.ADDRESS_BOOK))
        assertEquals("cold-storage", HubCardLinks.slug(AppTab.COLD_STORAGE))
        assertEquals("usernames", HubCardLinks.slug(AppTab.KACHAT_NAMES))
        assertEquals("kapost", HubCardLinks.slug(AppTab.KAPOSTS))
        assertEquals("kaplay", HubCardLinks.slug(AppTab.GAMES))
        assertEquals("websites", HubCardLinks.slug(AppTab.APPS))
        assertEquals("explorer", HubCardLinks.slugForKey("explorer"))
    }

    @Test
    fun `the dock's tabs aren't Hub cards`() {
        for (tab in listOf(AppTab.CHATS, AppTab.WALLET, AppTab.PROFILE, AppTab.SETTINGS, AppTab.HUB)) {
            assertNull(HubCardLinks.slug(tab))
            assertNull(HubCardLinks.shareUrl(tab))
        }
    }

    @Test
    fun `a link's word names its card, any case, nothing else`() {
        assertEquals(AppTab.COLD_STORAGE, HubCardLinks.tab(" Cold-Storage "))
        assertEquals(AppTab.KAPOSTS, HubCardLinks.tab("KAPOST"))
        assertNull(HubCardLinks.tab("chats"))
        assertNull(HubCardLinks.tab("kaposts"))
        assertNull(HubCardLinks.tab(""))
    }

    @Test
    fun `copy link writes the kachat app address`() {
        assertEquals("https://kachat.app/hub/portfolio", HubCardLinks.shareUrl(AppTab.PORTFOLIO))
        for (tab in AppTab.entries) {
            val slug = HubCardLinks.slug(tab) ?: continue
            assertEquals(tab, HubCardLinks.tab(slug))
        }
    }
}
