package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.BroadcastViewModel

/** The pages Add Room pushes (iOS 824a30bb `PublicRoomBrowserView.Page`). */
private enum class AddRoomPage { LANGUAGES, CREATE_INFO, CREATE_NAME }

/**
 * The rooms Add Room lists (iOS 824a30bb `PublicRoomBrowserView.rooms`): the featured rooms, then
 * the rooms the indexer lists, so the list grows with it. Pure, for tests.
 */
internal fun addRoomBrowserRooms(featured: List<String>, indexerRooms: List<String>): List<String> {
    val list = featured.toMutableList()
    for (name in indexerRooms) if (name !in list) list.add(name)
    return list
}

/**
 * Add Room (iOS 824a30bb `PublicRoomBrowserView`, owner 2026-11-26): the Public Chats page's + and
 * its empty page's Add Room. Public Chats starts empty, so this is how a room gets there: every
 * room the indexer keeps, each with Join (Open once it's in the list); the language rooms behind
 * Other Languages; and Create at the top right, which first says how a room nobody indexes works
 * ([PublicRoomCreateInfoPage]), then takes its name ([PublicRoomNamePage]).
 */
@Composable
internal fun PublicRoomBrowserPage(
    broadcastViewModel: BroadcastViewModel,
    /** A room to open: Open on a joined one, or a room just created. */
    onOpenRoom: (String) -> Unit,
    onClose: () -> Unit,
) {
    val path = remember { mutableStateListOf<AddRoomPage>() }
    fun pop() { if (path.isNotEmpty()) path.removeAt(path.lastIndex) }
    androidx.activity.compose.BackHandler(enabled = path.isNotEmpty()) { pop() }
    // a room just added on the indexer shows here within a minute
    LaunchedEffect(Unit) { broadcastViewModel.refreshIndexerRoomsSoon() }
    val joinedChannels by broadcastViewModel.joinedChannels.collectAsState()
    val joined = remember(joinedChannels) { joinedChannels.map { it.channelName }.toSet() }
    val trackedRoomsVersion by com.kachat.app.services.IndexerTrackedRooms.changes.collectAsState()
    val rooms = remember(trackedRoomsVersion) {
        addRoomBrowserRooms(FeaturedBroadcastChannels.NAMES, FeaturedBroadcastChannels.indexerDefaultNames())
    }
    // Join waits for the account (right after launch a join would fail with nothing said).
    // Observed, so Join turns on once the account is known.
    val myAddress by broadcastViewModel.myAddressFlow.collectAsState()
    val ready = myAddress != null
    val haptic = com.kachat.app.util.rememberHaptics()

    @Composable
    fun roomList(names: List<String>, withLanguage: Boolean) {
        SettingsSection(title = null) {
            names.forEachIndexed { i, name ->
                AddRoomRow(
                    name = name,
                    subtitle = if (withLanguage) FeaturedBroadcastChannels.languageDisplayName(name) else null,
                    joined = name in joined,
                    ready = ready,
                    onOpen = { onOpenRoom(name) },
                    onJoin = {
                        broadcastViewModel.joinFromRoomBrowser(name) { haptic(com.kachat.app.util.IosHaptic.SUCCESS) }
                    },
                )
                if (i < names.size - 1) SettingsDivider()
            }
        }
    }

    val colors = LocalAppColors.current
    when (path.lastOrNull()) {
        null -> Column(Modifier.fillMaxSize()) {
            SheetNavBar(
                title = stringResource(R.string.roomsoptin_add_room),
                leading = { SheetBarButton(stringResource(R.string.done), onClick = onClose) },
                trailing = { SheetBarButton(stringResource(R.string.create), onClick = { path.add(AddRoomPage.CREATE_INFO) }) },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column {
                    roomList(rooms, withLanguage = false)
                    Text(
                        stringResource(R.string.roomsoptin_indexer_rooms_footer),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                }
                SettingsSection(title = null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable { path.add(AddRoomPage.LANGUAGES) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.roomsoptin_other_languages), color = colors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        AddRoomPage.LANGUAGES -> Column(Modifier.fillMaxSize()) {
            SheetNavBar(
                title = stringResource(R.string.roomsoptin_other_languages),
                leading = { SheetBarBackButton(onClick = { pop() }) },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                roomList(FeaturedBroadcastChannels.LANGUAGE_NAMES, withLanguage = true)
            }
        }
        AddRoomPage.CREATE_INFO -> PublicRoomCreateInfoPage(
            onBack = { pop() },
            onContinue = { path.add(AddRoomPage.CREATE_NAME) },
        )
        AddRoomPage.CREATE_NAME -> PublicRoomNamePage(
            broadcastViewModel = broadcastViewModel,
            onBack = { pop() },
            onCreated = onOpenRoom,
        )
    }
}

/** "#name" (and its language), with Join, or Open once it's in the list (iOS `roomRow`). */
@Composable
private fun AddRoomRow(
    name: String,
    subtitle: String?,
    joined: Boolean,
    ready: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val enabled = joined || ready
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("#$name", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
        Text(
            stringResource(if (joined) R.string.kp55_open else R.string.pc_join),
            color = if (joined) KaspaTeal else Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .clip(CircleShape)
                .background(if (joined) KaspaTeal.copy(alpha = 0.15f) else KaspaTeal)
                .clickable(enabled = enabled) {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    if (joined) onOpen() else onJoin()
                }
                .padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

/** Create, first (iOS 824a30bb `PublicRoomCreateInfoView`): how a room nobody indexes works,
 *  said plainly before it's made. */
@Composable
private fun PublicRoomCreateInfoPage(onBack: () -> Unit, onContinue: () -> Unit) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    Column(Modifier.fillMaxSize()) {
        SheetNavBar(
            title = stringResource(R.string.create),
            leading = { SheetBarBackButton(onClick = onBack) },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.roomsoptin_create_title),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            CreateInfoPoint(Icons.Default.Tag, stringResource(R.string.roomsoptin_create_point_name))
            CreateInfoPoint(Icons.Outlined.Inbox, stringResource(R.string.roomsoptin_create_point_history))
            CreateInfoPoint(Icons.Outlined.NotificationsOff, stringResource(R.string.roomsoptin_create_point_bell))
            CreateInfoPoint(Icons.Outlined.Visibility, stringResource(R.string.roomsoptin_create_point_public))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(KaspaTeal)
                .clickable {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    onContinue()
                },
        ) {
            Text(stringResource(R.string.continue_label), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CreateInfoPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        }
        Text(text, color = LocalAppColors.current.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Create, then (iOS 824a30bb `PublicRoomNameView`): the room's name, the room list's own rules,
 * an error said in place. A name the indexer keeps joins that room. [onCreated] opens it.
 */
@Composable
private fun PublicRoomNamePage(
    broadcastViewModel: BroadcastViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val haptic = com.kachat.app.util.rememberHaptics()
    val isEmpty = name.trim().isEmpty()
    fun create() {
        if (isEmpty) return
        broadcastViewModel.joinFromCreateSheet(
            rawName = name,
            onError = { error = it },
            onJoined = { joined ->
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                onCreated(joined)
            },
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier = Modifier.fillMaxSize()) {
        SheetNavBar(
            title = stringResource(R.string.roomsoptin_name_your_room),
            leading = { SheetBarBackButton(onClick = onBack) },
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                TextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    placeholder = { Text("channel-name", color = colors.textTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { create() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = KaspaTeal,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            error?.let {
                Text(it, color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.roomsoptin_create_room),
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KaspaTeal.copy(alpha = if (isEmpty) 0.4f else 1f), RoundedCornerShape(16.dp))
                    .clickable(enabled = !isEmpty) { create() }
                    .padding(vertical = 14.dp),
            )
            Spacer(Modifier.size(0.dp))
        }
    }
}
