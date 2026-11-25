package com.kachat.app.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.kachat.app.models.AppAppearance

/**
 * The Appearance setting (System / Light / Dark) on the app's windows, not only on what Compose
 * draws (iOS 518fdc8c `WindowAppearance`).
 *
 * Compose's theme followed the setting, but the window under it - its background, the system bar
 * icons before the first frame, Android 12's splash screen - followed the phone, so a phone in
 * Light Mode running K in Dark showed a white launch and white edges around the app, and the
 * other way round. Now the night mode itself is the app's:
 * - Android 12+: `UiModeManager.setApplicationNightMode`, which the system keeps for the app, so
 *   even the next cold launch's splash screen (white or black, the K globe on it) matches.
 * - Older: `AppCompatDelegate.setDefaultNightMode`, set again as each launch starts
 *   ([applySaved], from a copy of the setting kept here) before the first window shows.
 *
 * MainActivity handles `uiMode` changes itself, so a switch in Settings > Customization redraws in
 * place rather than recreating the screen.
 */
object WindowAppearance {
    private const val PREFS = "kachat_window_appearance"
    private const val KEY = "appearance"

    /** The night mode for [appearance] on Android 12+ (`UiModeManager.MODE_NIGHT_*`). */
    internal fun uiModeNightMode(appearance: AppAppearance): Int = when (appearance) {
        AppAppearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        AppAppearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
        AppAppearance.DARK -> UiModeManager.MODE_NIGHT_YES
    }

    /** The night mode for [appearance] before Android 12 (`AppCompatDelegate.MODE_NIGHT_*`). */
    internal fun appCompatNightMode(appearance: AppAppearance): Int = when (appearance) {
        AppAppearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppAppearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppAppearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun saved(context: Context): AppAppearance? = runCatching {
        AppAppearance.fromName(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )
    }.getOrNull()

    /** As a launch starts, before the first window: the setting last seen (Android 11 and older;
     *  Android 12+ keeps the app's night mode itself). */
    fun applySaved(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val appearance = saved(context) ?: return
        AppCompatDelegate.setDefaultNightMode(appCompatNightMode(appearance))
    }

    /** The setting as stored (each launch, and on every change): applied when it differs from
     *  what the windows already have. */
    fun set(context: Context, appearance: AppAppearance) {
        if (saved(context) == appearance && appliedThisLaunch == appearance) return
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, appearance.name).apply()
        }
        appliedThisLaunch = appearance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The same mode again changes no configuration, so nothing redraws.
            runCatching {
                context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(uiModeNightMode(appearance))
            }
        } else if (AppCompatDelegate.getDefaultNightMode() != appCompatNightMode(appearance)) {
            AppCompatDelegate.setDefaultNightMode(appCompatNightMode(appearance))
        }
    }

    @Volatile
    private var appliedThisLaunch: AppAppearance? = null
}
