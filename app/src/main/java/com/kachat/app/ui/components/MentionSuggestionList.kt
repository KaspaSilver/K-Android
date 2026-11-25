package com.kachat.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.services.NameServicesClient
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.ui.screens.ContactAvatar
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Someone who can be @'d: their address and the username the @ inserts (iOS `MentionCandidate`). */
data class MentionCandidate(val address: String, val handle: String) {
    val id: String get() = address.lowercase()
}

/** Reaches name lookups from a screen that has no view model for them. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface UsernameMentionDeps {
    fun nameServices(): NameServicesClient
}

/** Name lookups for mentions (iOS `UsernameMention.openProfile` and the rooms' typed-name lookup). */
object UsernameMentionLookup {
    private val scope = MainScope()

    private fun nameServices(context: Context): NameServicesClient =
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, UsernameMentionDeps::class.java)
            .nameServices()

    /** Whoever holds the username [typed] (any case, with or without the ending), with the name
     *  as the app shows it; null for an unknown or invalid name. */
    suspend fun lookUp(context: Context, typed: String): MentionCandidate? {
        val name = KachatNames.Codec.normalize(typed)
        if (!KachatNames.Codec.isValid(name)) return null
        val results = runCatching { nameServices(context).resolveEverywhere(name) }.getOrNull() ?: return null
        val found = NameServicesClient.primary(results, name) ?: return null
        val address = found.address ?: return null
        return MentionCandidate(address, com.kachat.app.models.KachatNameLabel.display(KachatNames.Codec.normalize(found.fullName)))
    }

    /** Opens the profile of whoever [name] belongs to (KaPost's profile, through [open]). Unknown
     *  names do nothing. */
    fun openProfile(context: Context, name: String, open: (String) -> Unit) {
        scope.launch {
            val found = lookUp(context, name) ?: return@launch
            open(found.address)
        }
    }
}

private val mentionRowHeight = 44.dp
private const val visibleMentionRows = 5

/**
 * The list of people to @ above a composer: tap one to insert their username (iOS
 * `MentionSuggestionList`): a 28 avatar and "@name" per 44 row, five rows high at most and
 * scrolling past that, on a 14-rounded card. Nothing when there's no one to suggest.
 *
 * Worked out in the list itself from [source], which watches the names registry, so people show
 * as their usernames arrive (iOS 157790b0).
 */
@Composable
fun MentionSuggestionList(
    source: () -> List<MentionCandidate>,
    onPick: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // names landing redraw the list (usernames are looked up as people are considered)
    com.kachat.app.services.kachatnames.KachatNamesRegistry.identityRevision.collectAsState().value
    val candidates = source()
    if (candidates.isEmpty()) return
    val haptic = rememberHaptics()
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .height(mentionRowHeight * minOf(candidates.size, visibleMentionRows))
            .background(colors.surface, shape)
            .border(1.dp, colors.textPrimary.copy(alpha = 0.08f), shape)
            .verticalScroll(rememberScrollState()),
    ) {
        candidates.forEachIndexed { index, candidate ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mentionRowHeight)
                    .clickable {
                        haptic(IosHaptic.SELECTION)
                        onPick(candidate)
                    }
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "@${candidate.handle}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ContactAvatar(
                    imageUrl = null,
                    fallbackText = candidate.handle,
                    size = 28.dp,
                    fontSize = 12.sp,
                    address = candidate.address,
                )
                Text(
                    "@${candidate.handle}",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (index != candidates.lastIndex) {
                HorizontalDivider(
                    color = colors.textPrimary.copy(alpha = 0.08f),
                    modifier = Modifier.padding(start = 50.dp),
                )
            }
        }
    }
}
