package com.kachat.app.services.explorer

import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The K Explorer setting's links (iOS bfb902b7 `ExplorerDeepLink`, `KaspaExplorer.K`):
 * `kachat://explorer-tx/<64 hex>` and `kachat://explorer-address/<kaspa address>`. K's own "View
 * in Explorer" buttons open them (the shell's UriHandler takes them, and MainActivity if one comes
 * back as an intent), and they open Kaspa Hub's Explorer on that transaction or address (owner,
 * 2026-11-26). Only K writes them, but any app can send them: one from outside K never closes a
 * sheet that can't be swiped away ([open]).
 */
object ExplorerDeepLink {
    const val TX_HOST = "explorer-tx"
    const val ADDRESS_HOST = "explorer-address"

    fun txUrl(txId: String): String = "kachat://$TX_HOST/$txId"
    fun addressUrl(address: String): String = "kachat://$ADDRESS_HOST/$address"

    /** The page to open, from one of these links; null for anything else or a bad payload. */
    fun route(url: String): ExplorerRoute? {
        val uri = try { java.net.URI(url.trim()) } catch (e: Exception) { return null }
        if (uri.scheme?.lowercase() != "kachat") return null
        val host = uri.host?.lowercase() ?: return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val payload = parts.singleOrNull()?.trim()?.lowercase() ?: return null
        return when (host) {
            TX_HOST -> payload.takeIf { id -> id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' } }
                ?.let { ExplorerRoute.Transaction(it) }
            ADDRESS_HOST -> payload.takeIf { KaspaAddress.isValid(it) }?.let { ExplorerRoute.Address(it) }
            else -> null
        }
    }

    private val _pending = MutableStateFlow<ExplorerRoute?>(null)
    /** The page waiting for the Explorer to show (a cold start, or the section not open yet). */
    val pending = _pending.asStateFlow()

    private val _opens = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The shell puts the Explorer on screen (sheets and covers closed first) on each of these. */
    val opens = _opens.asSharedFlow()

    /** Sheets on screen that can't be swiped away (a send under way, the backup steps, a site's
     *  payment: [com.kachat.app.ui.screens.IosFullSheet] with `swipeToDismiss` false). */
    private val lockedSheets = java.util.concurrent.atomic.AtomicInteger(0)
    /** A link from outside K waits for those sheets to close. */
    @Volatile private var waitingForSheets = false

    fun sheetLocked() {
        lockedSheets.incrementAndGet()
    }

    fun sheetUnlocked() {
        if (lockedSheets.decrementAndGet() > 0) return
        lockedSheets.set(0)
        // the last such sheet closed: a link that came meanwhile shows now
        // (the Explorer may have taken the page already, hidden in Kaspa Hub: it's shown anyway)
        if (waitingForSheets) {
            waitingForSheets = false
            _opens.tryEmit(Unit)
        }
    }

    /**
     * Opens Kaspa Hub's Explorer on [route]. [fromOutside]: a link another app sent, which any
     * app can: it never closes a sheet that can't be swiped away. The Explorer waits and shows once
     * that sheet is closed (iOS d88891ea `isModalInPresentation`, audit 2026-11-27 L6).
     */
    fun open(route: ExplorerRoute, fromOutside: Boolean = false) {
        _pending.value = route
        if (opensNow(fromOutside, lockedSheets.get())) _opens.tryEmit(Unit) else waitingForSheets = true
    }

    /** Whether a link puts the Explorer on screen now (closing what's open), or waits. */
    fun opensNow(fromOutside: Boolean, lockedSheets: Int): Boolean = !fromOutside || lockedSheets <= 0

    /** [url] is one of these links: it's opened in K's Explorer and true comes back. */
    fun openIfLink(url: String, fromOutside: Boolean = false): Boolean {
        val route = route(url) ?: return false
        open(route, fromOutside)
        return true
    }

    /** A website the in-app browser can show (http or https): never a `kachat://` link, which
     *  showed a blank page there (iOS d88891ea, audit 2026-11-27 M9). */
    fun isWebLink(url: String): Boolean {
        val scheme = runCatching { java.net.URI(url.trim()).scheme }.getOrNull()?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** The waiting page, taken by the Explorer. */
    fun take(): ExplorerRoute? = _pending.value.also { _pending.value = null }
}
