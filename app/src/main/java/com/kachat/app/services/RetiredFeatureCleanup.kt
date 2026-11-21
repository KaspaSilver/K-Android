package com.kachat.app.services

import android.content.Context

/**
 * What the features that left the app kept on this phone outside the database is deleted once
 * (iOS `RetiredFeatureCleanup`):
 * - passes v1 and v2 once deleted KaPosts', Public Chats', Chess Online's, Portfolio's, Cold
 *   Storage's and the .kachat names' files (iOS e2f6732 / d54ea87). Since iOS 148a5331 KaChat is
 *   the all-in-one app again and every one of them is back, so both delete nothing: they only
 *   mark themselves done, and a fresh install keeps what those features write;
 * - Simple Mode (iOS eb2ccac): its password record and attempt counters;
 * - voice and video calls (iOS 7c5c22e3): the handled call ids and the call notification channels.
 * Their DataStore keys go with [com.kachat.app.repository.AppSettingsRepository.removeRetiredFeatureKeys],
 * and their database tables with the Room migrations that dropped them.
 */
object RetiredFeatureCleanup {
    private const val MARKER_PREFS = "kachat_prefs"

    private class Pass(val doneKey: String, val prefsFiles: List<String>, val extra: (Context) -> Unit = {})

    private val PASSES = listOf(
        // KaPosts, Public Chats, Chess Online (v1) and Portfolio, Cold Storage, the .kachat names
        // (v2) are back (iOS 148a5331): nothing of theirs is deleted any more.
        Pass("retired_feature_files_cleaned_v1", emptyList()),
        Pass("retired_feature_files_cleaned_v2", emptyList()),
        // Simple Mode (iOS eb2ccac): its salted-hash password record and attempt counters
        Pass("retired_feature_files_cleaned_v3", listOf("kachat_child_mode_prefs")),
        // Voice and video calls (iOS 7c5c22e3 RetiredFeatureCleanup.runOnceV4): the handled call
        // ids, and the two call notification channels. Each account's Nextcloud Talk check goes
        // with NextcloudService's next load of that account.
        Pass("retired_feature_files_cleaned_v4", listOf("kachat_calls")) { context ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.getSystemService(android.app.NotificationManager::class.java)?.let { manager ->
                    manager.deleteNotificationChannel("kachat_incoming_call")
                    manager.deleteNotificationChannel("kachat_calls")
                }
            }
        },
    )

    fun deleteLocalFiles(context: Context) {
        val marker = context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        for (pass in PASSES) {
            if (marker.getBoolean(pass.doneKey, false)) continue
            for (name in pass.prefsFiles) {
                context.deleteSharedPreferences(name)
            }
            runCatching { pass.extra(context) }
            marker.edit().putBoolean(pass.doneKey, true).apply()
        }
    }
}
