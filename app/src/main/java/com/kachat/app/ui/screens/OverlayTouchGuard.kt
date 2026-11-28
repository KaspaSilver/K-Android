package com.kachat.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * The tapjacking guard (security audit 2026-11-27 S3), for as long as the calling screen is
 * composed: a send review (Swipe up to Send), a site's Connect, Sign or Send sheet, and the seed
 * and private-key reveals.
 * - Android 12 and later: other apps' overlays are hidden ([Window.setHideOverlayWindows], the
 *   `HIDE_OVERLAY_WINDOWS` permission), so nothing can be drawn over the screen to steer a swipe
 *   or a tap.
 * - Older phones, which can't hide them: a touch that reached K through another app's window
 *   ([android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED]) is dropped in the window this screen is
 *   drawn in ([View.setFilterTouchesWhenObscured]), so a decoy drawn over the Swipe up to Send bar
 *   or the Sign and Reveal buttons can't press them.
 * Both are counted, so one guarded screen opening over another (a site's Send, then its Swipe up
 * to Send) keeps the guard until the last one goes.
 */
@Composable
fun OverlayTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val activity = view.context.findActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity?.window?.let { window -> OverlayGuardCounts.hideOverlays(window, true) }
        } else {
            OverlayGuardCounts.filterTouches(root, true)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity?.window?.let { window -> OverlayGuardCounts.hideOverlays(window, false) }
            } else {
                OverlayGuardCounts.filterTouches(root, false)
            }
        }
    }
}

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity

/** Who holds the guard, per window or view. Main thread only (composition). */
private object OverlayGuardCounts {
    private val windows = HeldCount<Window>()
    private val views = HeldCount<View>()
    /** A root view's own setting before the guard turned it on, put back when the guard goes. */
    private val viewsBefore = java.util.WeakHashMap<View, Boolean>()

    fun hideOverlays(window: Window, on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val change = if (on) windows.acquire(window) else windows.release(window)
        if (!change) return
        try {
            window.setHideOverlayWindows(on)
        } catch (e: Exception) {
            // without the permission it only logs; nothing else is changed here
        }
    }

    fun filterTouches(view: View, on: Boolean) {
        if (on) {
            if (!views.acquire(view)) return
            viewsBefore[view] = view.filterTouchesWhenObscured
            view.filterTouchesWhenObscured = true
        } else {
            if (!views.release(view)) return
            view.filterTouchesWhenObscured = viewsBefore.remove(view) ?: false
        }
    }
}

/**
 * A count of holders per key: [acquire] is true for the first holder (turn the thing on),
 * [release] true for the last (turn it off). A release with no holder changes nothing.
 */
internal class HeldCount<K : Any> {
    private val counts = java.util.WeakHashMap<K, Int>()

    fun acquire(key: K): Boolean {
        val n = counts[key] ?: 0
        counts[key] = n + 1
        return n == 0
    }

    fun release(key: K): Boolean {
        val n = counts[key] ?: return false
        return if (n <= 1) {
            counts.remove(key)
            true
        } else {
            counts[key] = n - 1
            false
        }
    }

    fun holders(key: K): Int = counts[key] ?: 0
}
