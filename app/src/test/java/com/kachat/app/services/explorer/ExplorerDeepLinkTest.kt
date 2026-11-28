package com.kachat.app.services.explorer

import com.kachat.app.models.KaspaExplorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS bfb902b7: K Explorer's links and the one-time move to K's own Explorer. */
class ExplorerDeepLinkTest {
    private val tx = "a".repeat(32) + "0123456789abcdef0123456789abcdef"
    private val address = "kaspa:qqtk9u6alm8vz7tp5a425x9cacad7nezhpwmvz890yndpt9f07e0udw59l300"

    @Test fun txLinkRoundTrips() {
        assertEquals(ExplorerRoute.Transaction(tx), ExplorerDeepLink.route(KaspaExplorer.K.txUrl(tx)))
        assertEquals(ExplorerRoute.Transaction(tx), ExplorerDeepLink.route("KACHAT://EXPLORER-TX/${tx.uppercase()}"))
    }

    @Test fun addressLinkRoundTrips() {
        assertEquals(ExplorerRoute.Address(address), ExplorerDeepLink.route(KaspaExplorer.K.addressUrl(address)))
    }

    @Test fun badLinksAreRefused() {
        assertNull(ExplorerDeepLink.route("kachat://explorer-tx/abc"))
        assertNull(ExplorerDeepLink.route("kachat://explorer-tx/" + "g".repeat(64)))
        assertNull(ExplorerDeepLink.route("kachat://explorer-tx/$tx/more"))
        assertNull(ExplorerDeepLink.route("kachat://explorer-address/kaspa:qqnotanaddress"))
        assertNull(ExplorerDeepLink.route("https://explorer-tx/$tx"))
        assertNull(ExplorerDeepLink.route("kachat://profile/$tx"))
        assertNull(ExplorerDeepLink.route("not a url"))
    }

    @Test fun websitesKeepTheirLinks() {
        assertEquals("https://explorer.kaspa.org/txs/$tx", KaspaExplorer.KASPA_ORG.txUrl(tx))
        assertEquals("https://kaspa.stream/addresses/$address", KaspaExplorer.KASPA_STREAM.addressUrl(address))
    }

    @Test fun everyoneMovesToKOnceThenAPickSticks() {
        assertEquals(KaspaExplorer.K, KaspaExplorer.default)
        assertEquals(KaspaExplorer.K, KaspaExplorer.entries.first())
        // a pick made before this update, or none: K
        assertEquals(KaspaExplorer.K, KaspaExplorer.stored("KASPA_STREAM", pickedSinceKExplorer = false))
        assertEquals(KaspaExplorer.K, KaspaExplorer.stored(null, pickedSinceKExplorer = false))
        // a pick made since: kept
        assertEquals(KaspaExplorer.KASPA_ORG, KaspaExplorer.stored("KASPA_ORG", pickedSinceKExplorer = true))
        assertEquals(KaspaExplorer.K, KaspaExplorer.stored("gone", pickedSinceKExplorer = true))
    }

    @Test fun theBrowserTakesOnlyWebsites() {
        // M9: a kachat:// link showed a blank page in the in-app browser
        assertEquals(true, ExplorerDeepLink.isWebLink("https://explorer.kaspa.org/txs/$tx"))
        assertEquals(true, ExplorerDeepLink.isWebLink("HTTP://kaspa.stream/x"))
        assertEquals(false, ExplorerDeepLink.isWebLink(KaspaExplorer.K.txUrl(tx)))
        assertEquals(false, ExplorerDeepLink.isWebLink("javascript:alert(1)"))
        assertEquals(false, ExplorerDeepLink.isWebLink("not a url"))
    }

    @Test fun aLinkFromOutsideWaitsForALockedSheet() {
        // L6: any app can send one; it never closes a send under way
        assertEquals(true, ExplorerDeepLink.opensNow(fromOutside = false, lockedSheets = 2))
        assertEquals(true, ExplorerDeepLink.opensNow(fromOutside = true, lockedSheets = 0))
        assertEquals(false, ExplorerDeepLink.opensNow(fromOutside = true, lockedSheets = 1))
    }

    @Test fun theOwnKeyGuardKnowsTheWalletsKeys() {
        // L5: one of this wallet's keys is refused, anything else may be looked up on Search
        val key = "1".repeat(64)
        val owned = ExplorerKeyGuard.addressesFor(key)
        assertEquals(2, owned.size)
        assertEquals(true, ExplorerKeyGuard.isOwnKey(key, listOf(owned[0].uppercase())))
        assertEquals(true, ExplorerKeyGuard.isOwnKey(key.uppercase(), listOf(owned[1])))
        assertEquals(false, ExplorerKeyGuard.isOwnKey("2".repeat(64), owned))
        assertEquals(false, ExplorerKeyGuard.isOwnKey(key, emptyList()))
        assertEquals(emptyList<String>(), ExplorerKeyGuard.addressesFor("abc"))
        // zero isn't a key
        assertEquals(emptyList<String>(), ExplorerKeyGuard.addressesFor("0".repeat(64)))
        assertEquals(true, ExplorerKeyGuard.isLookedUpOnlyOnSearch(" $tx "))
        assertEquals(false, ExplorerKeyGuard.isLookedUpOnlyOnSearch("kaspa:qq"))
    }
}
