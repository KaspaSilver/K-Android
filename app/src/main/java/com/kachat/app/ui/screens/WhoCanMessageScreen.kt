package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.services.WhoCanMessage
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/** Settings > Chats > Who Can Message You? (iOS `WhoCanMessage`): Anyone, or Only Users with a Username. */
object WhoCanMessageRoutes {
    const val SETTINGS = "who_can_message_settings"
}

/** The choice's title, as the row and the page show it (iOS `WhoCanMessage.title`). */
@Composable
fun whoCanMessageTitle(choice: WhoCanMessage): String = stringResource(
    when (choice) {
        WhoCanMessage.ANYONE -> R.string.c54_who_anyone
        WhoCanMessage.USERNAMES -> R.string.c54_who_usernames
    }
)

/**
 * The Chats settings page's first card (iOS `WhoCanMessageRow`): the row, with the current choice
 * at its end, and the card's footer.
 */
@Composable
fun WhoCanMessageSettingsSection(onOpen: () -> Unit) {
    val context = LocalContext.current
    val choice by remember { WhoCanMessage.flow(context) }.collectAsState()
    SettingsFormSection(title = null, footer = stringResource(R.string.c54_who_row_footer), first = true) {
        SettingsNavigationItem(
            stringResource(R.string.c54_who_title),
            Icons.Outlined.PersonSearch,
            whoCanMessageTitle(choice ?: WhoCanMessage.ANYONE),
            onClick = onOpen,
        )
    }
}

/** Settings > Chats > Who Can Message You?: Anyone, or Only Users with a Username (iOS `WhoCanMessageView`). */
@Composable
fun WhoCanMessageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val choice by remember { WhoCanMessage.flow(context) }.collectAsState()
    val current = choice ?: WhoCanMessage.ANYONE
    SettingsFormPage(title = stringResource(R.string.c54_who_title), onBack = onBack) {
        SettingsFormSection(
            title = null,
            first = true,
            footer = stringResource(
                if (current == WhoCanMessage.ANYONE) R.string.c54_who_anyone_footer else R.string.c54_who_usernames_footer
            ),
        ) {
            WhoCanMessage.entries.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (option != current) {
                                haptic(com.kachat.app.util.IosHaptic.SELECTION)
                                // Message Requests and the Chats row's count follow the flow.
                                WhoCanMessage.set(context, option)
                            }
                        }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        whoCanMessageTitle(option),
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == current) {
                        Icon(Icons.Default.Check, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    }
                }
                if (index < WhoCanMessage.entries.lastIndex) SettingsDivider()
            }
        }
    }
}
