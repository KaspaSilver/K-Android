package com.kachat.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.KaPostsService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.KaPostsViewModel

/**
 * User Info's top (iOS 2251f15c `ChatInfoView.profileHeader`, owner 2026-11-26), laid out as
 * Profile's header: banner, avatar with See All Posts beside it, the name (still typed in place,
 * the pencil beside it, kept by Save, with or without a contact), Following and Followers (tap
 * for the lists), the bio, then Add as Contact (Edit Contact once saved) and Share. It replaced
 * the big Share Profile button and the plain avatar-and-name card.
 */
@Composable
internal fun UserInfoProfileHeader(
    contactId: String,
    isSelf: Boolean,
    isBot: Boolean,
    bannerUrl: String?,
    avatarUrl: String?,
    bio: String?,
    linktree: String?,
    /** How this address reads everywhere (iOS `contactsManager.displayName(for:)`): your own name
     *  when [isSelf], else the field's prompt until you type a name of your own. */
    displayName: String,
    contactName: String,
    onContactNameChange: (String) -> Unit,
    followingCount: Int?,
    followersCount: Int?,
    /** Someone who never posted has no KaPost key, so no list to show. */
    followListsEnabled: Boolean,
    /** true = Followers, false = Following. */
    onOpenFollowList: (Boolean) -> Unit,
    onSeeAllPosts: () -> Unit,
    savedContact: Boolean,
    onEditContact: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    val haptic = com.kachat.app.util.rememberHaptics()
    Column(Modifier.fillMaxWidth()) {
        val shape = RoundedCornerShape(14.dp)
        if (bannerUrl != null) {
            // Whole, at its own proportions (iOS KNSBannerImageView fitsWidth, 140 high, 14 corners).
            WholeBanner(model = bannerUrl, placeholderHeight = 140.dp, shape = shape) {
                Box(Modifier.fillMaxSize().background(colors.surfaceVariant))
            }
        } else {
            // iOS: the accent at 55% fading to 15%, top leading to bottom trailing, 120 high.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.55f), KaspaTeal.copy(alpha = 0.15f))))
            )
        }

        // The 76 avatar rides 38 up over the banner with a 3 ring in the page's colour; See All
        // Posts sits at the row's bottom on the right (iOS HStack(alignment: .bottom)).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.width(76.dp).height(38.dp)) {
                Box(
                    modifier = Modifier
                        .offset(y = (-38).dp)
                        // unbounded, or size() is clamped to the 38 slot and the circle squashes
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(76.dp),
                ) {
                    ContactAvatar(
                        imageUrl = avatarUrl,
                        fallbackText = displayName,
                        size = 76.dp,
                        address = contactId,
                    )
                    Box(Modifier.size(76.dp).border(3.dp, colors.background, CircleShape))
                }
            }
            Spacer(Modifier.weight(1f).width(12.dp))
            if (!isBot) {
                UserInfoHeaderButton(
                    title = stringResource(R.string.kaposts_see_all_posts),
                    icon = Icons.Outlined.Newspaper,
                    // iOS .accessibilityHint
                    onClickLabel = stringResource(R.string.userinfo_opens_posts_and_replies),
                ) {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    onSeeAllPosts()
                }
            }
        }

        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UserInfoNameRow(isSelf, displayName, contactName, onContactNameChange)
            if (!isBot) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UserInfoFollowCount(followingCount, stringResource(R.string.l53_feed_tab_following), followListsEnabled) { onOpenFollowList(false) }
                    UserInfoFollowCount(followersCount, stringResource(R.string.kpui_followers), followListsEnabled) { onOpenFollowList(true) }
                }
            }
            if (bio != null && bio.isNotBlank()) {
                Box(Modifier.padding(top = 2.dp)) { UserInfoBioText(bio) }
                linktree?.let { link ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { runCatching { uriHandler.openUri(link) } },
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(link.removePrefix("https://"), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    // iOS ChatInfoView.formatAddress: the first 12 and last 8.
                    text = if (contactId.length > 20) "${contactId.take(12)}...${contactId.takeLast(8)}" else contactId,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (!isBot) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isSelf) {
                        UserInfoHeaderButton(
                            title = stringResource(if (savedContact) R.string.ab_edit_address else R.string.userinfo_add_as_contact),
                            icon = if (savedContact) ContactsFilledIcon else ContactsIcon,
                            onClick = onEditContact,
                        )
                    }
                    UserInfoHeaderButton(
                        title = stringResource(R.string.share),
                        icon = Icons.Outlined.IosShare,
                        accessibilityLabel = stringResource(R.string.share_this_profile),
                        onClick = onShare,
                    )
                }
            }
        }
    }
}

/**
 * Your own name as it reads everywhere; anyone else's is the name you give them, typed here (the
 * pencil focuses it) and kept by Save. The field is only as wide as the name, so the pencil sits
 * right beside it (owner, iOS 2251f15c): a hidden copy of the text sets the width, a long name
 * still truncates to the row. Their name shows in full until you type one of your own.
 */
@Composable
private fun UserInfoNameRow(
    isSelf: Boolean,
    displayName: String,
    contactName: String,
    onContactNameChange: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    // iOS .title3.weight(.bold)
    val nameStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
    if (isSelf) {
        Text(displayName, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val focus = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f, fill = false)) {
            Text(
                (contactName.ifEmpty { displayName }) + " ",
                style = nameStyle,
                maxLines = 1,
                modifier = Modifier.alpha(0f),
            )
            BasicTextField(
                value = contactName,
                onValueChange = onContactNameChange,
                singleLine = true,
                textStyle = nameStyle,
                cursorBrush = SolidColor(KaspaTeal),
                modifier = Modifier.matchParentSize().focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (contactName.isEmpty()) {
                            Text(displayName, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
        }
        // iOS: a 15pt semibold pencil in the accent colour, 30 x 30.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { runCatching { focus.requestFocus() } },
        ) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_name), tint = KaspaTeal, modifier = Modifier.size(17.dp))
        }
    }
}

/** A follow count and its word; tapping opens the list (iOS `followCountButton`). */
@Composable
private fun UserInfoFollowCount(count: Int?, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ),
    ) {
        Text("${count ?: 0}", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = colors.textSecondary, fontSize = 15.sp)
    }
}

/** Profile's teal capsule (iOS `KaPostsView.profileHeaderButtonLabel`, as KaPosts'
 *  `KaPostsProfileHeaderButton`). */
@Composable
private fun UserInfoHeaderButton(
    title: String,
    icon: ImageVector,
    onClickLabel: String? = null,
    accessibilityLabel: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(KaspaTeal.copy(alpha = 0.15f))
            .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Profile's bio (iOS `ExpandableBioText`, as KaPosts' own): three lines, then "more" opens the
 *  whole of it in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserInfoBioText(bio: String) {
    val colors = LocalAppColors.current
    var isTruncated by remember(bio) { mutableStateOf(false) }
    var showFullBio by remember { mutableStateOf(false) }
    Column {
        Text(
            bio,
            color = colors.textSecondary,
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { isTruncated = it.hasVisualOverflow },
        )
        if (isTruncated) {
            Text(
                stringResource(R.string.kpui_more),
                color = KaspaTeal,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp).clickable { showFullBio = true },
            )
        }
    }
    if (showFullBio) {
        com.kachat.app.ui.theme.IosSheetColors {
            val sheetColors = LocalAppColors.current
            ModalBottomSheet(
                scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
                shape = com.kachat.app.ui.theme.IosSheetShape,
                tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
                windowInsets = WindowInsets.statusBars,
                onDismissRequest = { showFullBio = false },
                containerColor = sheetColors.background,
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.bio), color = sheetColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            bio,
                            color = sheetColors.textPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface UserInfoKaPostsDeps {
    fun kaPostsService(): KaPostsService
}

/** User Info's KaPost numbers (iOS 2251f15c `ChatInfoView.loadFollowCounts`). */
internal object UserInfoKaPosts {
    /** [pubkey] null: they have never posted (or replied), so there is no list to open. */
    data class Counts(val pubkey: String?, val followers: Int?, val following: Int?)

    /**
     * Your own key from the wallet, anyone else's as their posts name it; then the indexer's
     * user details. Your own Following is the follow list on this phone, which the caller reads.
     */
    suspend fun load(context: Context, address: String, isSelf: Boolean): Counts {
        val service = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, UserInfoKaPostsDeps::class.java)
            .kaPostsService()
        val pubkey = if (isSelf) {
            try { service.requesterPubkey() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        } else {
            resolvePubkey(service, address)
        } ?: return Counts(null, null, null)
        val details = try {
            service.fetchUserDetails(pubkey)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Counts(pubkey, null, null)
        }
        // Never counted as your own follower: Profile shows it without a stale on-chain
        // self-follow (KaPostsViewModel.loadMyProfile), so User Info does too.
        val followers = details.followersCount?.let { if (isSelf && details.followedUser == true) (it - 1).coerceAtLeast(0) else it }
        return Counts(pubkey, followers, if (isSelf) null else details.followingCount)
    }

    /**
     * The K pubkey behind [address], as the indexer's data names it: the stored key on their
     * posts, else their replies, in either parity (iOS `KaPostsView.resolveKaPostsPubkey`, the
     * same lookup as KaPostsViewModel's). Never the probe or get-user-details, which echo the key
     * they were asked with. Null when nothing they posted names it.
     */
    private suspend fun resolvePubkey(service: KaPostsService, address: String): String? {
        val even = KaPostsService.kapostPubkeyFromAddress(address) ?: return null
        val xOnly = even.drop(2).lowercase()
        val candidates = listOf(even.lowercase(), "03$xOnly")
        for (replies in listOf(false, true)) {
            for (candidate in candidates) {
                val page = try {
                    if (replies) service.fetchUserRepliesPage(candidate, limit = 1) else service.fetchUserPostsPage(candidate, limit = 1)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                page?.items?.firstNotNullOfOrNull { KaPostsViewModel.kaPostsKeyNamed(it.userPublicKey, xOnly) }?.let { return it }
            }
        }
        return null
    }
}
