package com.kachat.app.services

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * What the in-app browser (Kasshi in Kaspa Hub, Kaspa Websites, the faucet, the browser in .kachat)
 * keeps: only while it is open, as iOS's `.nonPersistent()` data store (security audit 2026-11-27
 * S1). Each browser ([newSession]) gets a WebView profile of its own ([attach]), wiped and deleted
 * when it closes ([release], [endSession]); a view that replaces one whose renderer died takes
 * over the same profile, so a sign-in survives that. A WebView without profiles uses the shared
 * one, wiped when the last browser closes.
 * Log Out (an account switch too) and Delete Account wipe it all ([clearAll]), so no site session
 * of one account is there for the next, and Kasshi can't tie two accounts to one browser. Kasshi's
 * "Sign In to Kasshi" sheet runs on every open, so its sign-in is unchanged.
 *
 * Also the pause of the pages while K is in the background (battery audit 2026-11-27 R4):
 * [pauseTimers] / [resumeTimers] for the whole process, put back before the last browser goes.
 *
 * Main thread only, as every WebView call.
 */
object InAppBrowserData {
    private const val TAG = "InAppBrowserData"

    /** The browser's profiles: this prefix, the process, and a count. */
    internal const val PROFILE_PREFIX = "kachat-inapp-"

    private var made = 0
    /** The profile each open browser uses (null: the shared one). */
    private val live = java.util.WeakHashMap<WebView, String?>()
    /** Whether the leftovers of an earlier run were wiped in this process. */
    private var swept = false
    /** [pauseTimers] is on. */
    private var timersPaused = false

    private fun profilesSupported(): Boolean =
        try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Throwable) { false }

    /** A new browser's profile name, for every view it shows ([attach]). */
    fun newSession(): String = profileName(android.os.Process.myPid(), made++)

    /** The name of a browser's profile. */
    internal fun profileName(pid: Int, count: Int): String = "$PROFILE_PREFIX$pid-$count"

    /** The in-app browser's profiles in [all] that no open browser uses: wiped and deleted. */
    internal fun staleProfiles(all: Collection<String>, inUse: Collection<String>): List<String> =
        all.filter { it.startsWith(PROFILE_PREFIX) && it !in inUse }

    /**
     * A new browser's [view] gets its own profile, before anything else is done with it. Called
     * straight after the WebView is made. The leftovers of an earlier run go first (K killed with
     * a page open), and the shared profile's cookies and storage (the browser's from before
     * it had profiles, and an older WebView's from the last run).
     */
    @SuppressLint("RequiresFeature")
    fun attach(view: WebView, session: String) {
        val ownProfile = profilesSupported()
        if (!swept) {
            swept = true
            sweep(ownProfile, wipeShared = true)
        }
        var name: String? = null
        if (ownProfile) {
            try {
                WebViewCompat.setProfile(view, session)
                name = session
            } catch (e: Exception) {
                Log.w(TAG, "the browser's profile couldn't be set: ${e.javaClass.simpleName}")
            }
        }
        live[view] = name
    }

    /**
     * The browser closed: its page goes, and everything it kept: its profile's cookies, storage
     * and cache, then the profile. On the shared profile, its cookies and storage once no other
     * browser is open. [keepData]: a view whose renderer died, replaced by a new one that keeps
     * its data ([endSession] wipes it when the browser closes).
     */
    @SuppressLint("RequiresFeature")
    fun release(view: WebView, keepData: Boolean = false) {
        val tracked = live.containsKey(view)
        val name = live.remove(view)
        try {
            if (keepData) {
                // the replacing view has it
            } else if (name != null) {
                val profile = WebViewCompat.getProfile(view)
                if (profile.name == name) {
                    profile.cookieManager.removeAllCookies(null)
                    profile.webStorage.deleteAllData()
                }
            } else if (tracked && live.isEmpty()) {
                wipeShared()
            }
            if (!keepData) view.clearCache(true)
            view.clearHistory()
        } catch (e: Exception) {
            Log.w(TAG, "clearing the browser's data: ${e.javaClass.simpleName}")
        }
        // the last page going while K is in the background: the timers come back for the other
        // web views (Trestly, the kUSDT builder)
        if (live.isEmpty() && timersPaused) {
            timersPaused = false
            runCatching { view.resumeTimers() }
        }
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
        if (name != null && !keepData) {
            // gone once no view uses it; if it's still held, the next sweep deletes it
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { ProfileStore.getInstance().deleteProfile(name) } catch (e: Exception) {}
                }
            }
        }
    }

    /** The browser [session] closed: its profile is wiped and deleted, whatever views came and
     *  went in it ([release] with keepData). */
    @SuppressLint("RequiresFeature")
    fun endSession(session: String) {
        if (!profilesSupported()) return
        runCatching {
            // after its last view's release, which can come later in the same frame
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (live.values.any { it == session }) return@post
                try {
                    val store = ProfileStore.getInstance()
                    store.getProfile(session)?.let { profile ->
                        profile.cookieManager.removeAllCookies(null)
                        profile.webStorage.deleteAllData()
                    }
                    store.deleteProfile(session)
                } catch (e: Exception) {
                    // still held: the next sweep deletes it
                }
            }
        }
    }

    /** K went to the background with a page open: every page's JavaScript timers stop until
     *  [resumeTimers] (they are the process's, one call for all). */
    fun pauseTimers(view: WebView) {
        if (timersPaused || !live.containsKey(view)) return
        timersPaused = true
        runCatching { view.pauseTimers() }
    }

    fun resumeTimers(view: WebView) {
        if (!timersPaused) return
        timersPaused = false
        runCatching { view.resumeTimers() }
    }

    /**
     * Log Out (an account switch too) and Delete Account: every site's cookies and storage from
     * the in-app browser go, the shared profile's and the browser's own profiles'. Posted to the
     * main thread; safe to call from anywhere, and a no-op where there is no WebView.
     */
    fun clearAll() {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    sweep(profilesSupported(), wipeShared = true)
                } catch (e: Throwable) {
                    Log.w(TAG, "clearing the browser's data: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    @SuppressLint("RequiresFeature")
    private fun sweep(ownProfile: Boolean, wipeShared: Boolean) {
        if (wipeShared) wipeShared()
        if (!ownProfile) return
        try {
            val store = ProfileStore.getInstance()
            val inUse = live.values.filterNotNull()
            for (name in store.allProfileNames.filter { it.startsWith(PROFILE_PREFIX) }) {
                try {
                    store.getProfile(name)?.let { profile ->
                        profile.cookieManager.removeAllCookies(null)
                        profile.webStorage.deleteAllData()
                    }
                } catch (e: Exception) {}
            }
            for (name in staleProfiles(store.allProfileNames, inUse)) {
                try { store.deleteProfile(name) } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "the browser's profiles couldn't be listed: ${e.javaClass.simpleName}")
        }
    }

    /** The shared (default) profile's cookies and storage. Nothing else of K's keeps any there:
     *  Trestly has its own profile (or clears its own site), the kUSDT builder has no storage. */
    private fun wipeShared() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {}
        try { WebStorage.getInstance().deleteAllData() } catch (e: Exception) {}
    }
}
