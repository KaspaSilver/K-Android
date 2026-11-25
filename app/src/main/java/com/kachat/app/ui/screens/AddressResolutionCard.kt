package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kachat.app.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.first
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Who is behind the address a screen has resolved - the card Create chat shows, for every other
 * place an address or a username goes in: a withdrawal, a send, the portfolio, a group invite. The
 * SCREEN does the resolving (.kachat only, see [com.kachat.app.services.NameServicesClient.resolveEverywhere]);
 * this card takes the outcome and shows the face and the name: the [domain] that was typed, else
 * the address's own .kachat name. Null address, no card - a half-typed address gets nothing rather
 * than a card flickering through wrong faces. Mirrors iOS's AddressResolutionCard (6ac48a7).
 */
@Composable
fun AddressResolutionCard(
    address: String?,
    domain: String? = null,
    modifier: Modifier = Modifier,
    viewModel: AddressResolutionViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val shown = address?.trim()?.takeIf { it.isNotEmpty() }
    // One of this wallet's own addresses: you are sending to yourself (iOS 56f004f).
    var ownLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown) {
        ownLabel = null
        if (shown == null) return@LaunchedEffect
        ownLabel = viewModel.ownAddressLabel(shown)
    }

    // The address's .kachat name (snapshot state: it recomposes when the identity lands), shown
    // once a node confirms the name record is this address's - this card sits where someone
    // decides to pay, and the indexer's reverse label alone is not proof (security audit M5).
    val cachedKachat = shown?.let { com.kachat.app.services.kachatnames.KachatNamesRegistry.cachedIdentityOf(it)?.label }
    var confirmedKachat by remember(shown) { mutableStateOf<String?>(null) }
    LaunchedEffect(shown, cachedKachat) {
        confirmedKachat = null
        val address = shown ?: return@LaunchedEffect
        val label = cachedKachat ?: return@LaunchedEffect
        val registry = com.kachat.app.services.kachatnames.KachatNamesRegistry.shared ?: return@LaunchedEffect
        if (registry.confirmedLabel(address, label)) confirmedKachat = label
    }

    if (shown == null) return
    // The name typed, else the address's confirmed .kachat name (iOS 518fdc8c: no KNS profile).
    val name = domain
        ?: confirmedKachat?.takeIf { it == cachedKachat }?.let(com.kachat.app.models.KachatNameLabel::display)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.textPrimary.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(imageUrl = null, fallbackText = name ?: shown.takeLast(8), size = 44.dp, address = shown)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name ?: stringResource(R.string.no_domain),
                color = if (name != null) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiddleEllipsisText(
                shown,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            ownLabel?.let { own ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.HowToReg,
                        contentDescription = null,
                        tint = KaspaTeal,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.your_own_address_value, own),
                        color = KaspaTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The card's own lookups, so any screen can show it without plumbing them through its own model. */
@HiltViewModel
class AddressResolutionViewModel @Inject constructor(
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val nameServices: com.kachat.app.services.NameServicesClient,
    private val walletManager: com.kachat.app.services.WalletManager,
    private val kaPostPreviewLoader: com.kachat.app.services.KaPostPreviewLoader,
) : ViewModel() {

    /** A KaPost link's post if this run already read it, for a card's first frame. */
    fun cachedKaPostPreview(txId: String): com.kachat.app.services.KaPostPreview? = kaPostPreviewLoader.cached(txId)

    /** A KaPost link's post from the indexer: who posted it and what it says (iOS a8a5807a). */
    suspend fun kaPostPreview(txId: String): com.kachat.app.services.KaPostPreview? = kaPostPreviewLoader.load(txId)

    /** "Chatting Address", "Address #3" or its name when [address] is one of this wallet's own,
     *  else null ([com.kachat.app.services.WalletManager.ownAddressLabel], iOS 56f004f). Off the
     *  main thread: a first call may derive the spending addresses. */
    suspend fun ownAddressLabel(address: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching { walletManager.ownAddressLabel(address) }.getOrNull()
    }

    /** What a typed name points to (.kachat only, iOS 518fdc8c) - for a field that has no model of
     *  its own (the Address Book editor). */
    suspend fun resolveEverywhere(input: String): List<com.kachat.app.services.NameResolution> =
        runCatching { nameServices.resolveEverywhere(input) }.getOrDefault(emptyList())

    /** The contact saved for [address] on this account, if there is one. */
    suspend fun contactFor(address: String): com.kachat.app.models.ContactEntity? = runCatching {
        chatRepository.getContacts().first().firstOrNull { it.id.equals(address, ignoreCase = true) }
    }.getOrNull()
}
