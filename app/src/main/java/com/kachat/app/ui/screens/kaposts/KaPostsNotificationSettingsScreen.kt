package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import com.kachat.app.viewmodels.KaPostsSettingsViewModel

/**
 * Settings > Notifications > KaPost (iOS 148a5331 `KaPostsNotificationSettingsView`): which KaPost
 * activity notifies. A kind switched off is skipped, never queued for later; the push registration
 * tells the server the switches too (PUSH_EXTENSIONS.md section 3).
 */
@Composable
fun KaPostsNotificationSettingsScreen(onBack: () -> Unit, settingsViewModel: KaPostsSettingsViewModel = hiltViewModel()) {
    val likes by settingsViewModel.kaPostsNotifyLikes.collectAsState(true)
    val reposts by settingsViewModel.kaPostsNotifyReposts.collectAsState(true)
    val follows by settingsViewModel.kaPostsNotifyFollows.collectAsState(true)
    val dislikes by settingsViewModel.kaPostsNotifyDislikes.collectAsState(true)
    val comments by settingsViewModel.kaPostsNotifyComments.collectAsState(true)
    val mentions by settingsViewModel.kaPostsNotifyMentions.collectAsState(true)
    SettingsFormPage(title = "KaPost", onBack = onBack) {
        SettingsFormSection(title = null) {
            SettingsSwitchItem(stringResource(R.string.kaposts_likes), likes) { settingsViewModel.setKaPostsNotifyLikes(it) }
            SettingsDivider()
            SettingsSwitchItem(stringResource(R.string.kaposts_reposts), reposts) { settingsViewModel.setKaPostsNotifyReposts(it) }
            SettingsDivider()
            SettingsSwitchItem(stringResource(R.string.kpui_follows), follows) { settingsViewModel.setKaPostsNotifyFollows(it) }
            SettingsDivider()
            SettingsSwitchItem(stringResource(R.string.kaposts_dislikes), dislikes) { settingsViewModel.setKaPostsNotifyDislikes(it) }
            SettingsDivider()
            SettingsSwitchItem(stringResource(R.string.kpui_comments), comments) { settingsViewModel.setKaPostsNotifyComments(it) }
            SettingsDivider()
            SettingsSwitchItem(stringResource(R.string.kpui_mentions), mentions) { settingsViewModel.setKaPostsNotifyMentions(it) }
        }
    }
}
