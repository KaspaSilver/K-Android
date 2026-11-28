package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.KaChatSiteWallet
import com.kachat.app.services.KaChatSiteWalletFactory
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/** Reaches the site wallet factory from the browser's composable. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface KaChatSiteWalletDeps {
    fun kaChatSiteWalletFactory(): KaChatSiteWalletFactory
}

/** One [KaChatSiteWallet] for this browser, or null when [url]'s host isn't a site that gets one
 *  (iOS 48f5c357 `KaChatSiteWallet.isAllowed(host: url.host) ? siteWallet : nil`). The browser
 *  closes it ([KaChatSiteWallet.close]) when its web view is torn down, not when it is merely
 *  hidden (iOS b49a021d `InAppWebView.dismantleUIView`), and reopens it whenever it shows. */
@Composable
fun rememberSiteWallet(url: String): KaChatSiteWallet? {
    val context = LocalContext.current
    val wallet = remember(url) {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        if (!KaChatSiteWallet.isAllowed(host)) null
        else runCatching {
            dagger.hilt.android.EntryPointAccessors
                .fromApplication(context.applicationContext, KaChatSiteWalletDeps::class.java)
                .kaChatSiteWalletFactory()
                .make()
        }.getOrNull()
    }
    return wallet
}

/**
 * The site's request on screen (iOS 48f5c357 `InAppBrowserScreen`'s `.sheet(item: $siteSheet)`):
 * kept apart from [KaChatSiteWallet.pending] so a send's Sent screen stays up after the txid went
 * back to the page. Closed without an answer, it's declined.
 */
@Composable
fun SiteWalletSheetHost(
    wallet: KaChatSiteWallet,
    /** K's own sign-in sheet is up ([SiteSignInSheet]): it answers a connect itself, and anything
     *  else shows once it closes (iOS d88891ea, audit 2026-11-27 L1). */
    held: Boolean = false,
) {
    val pending by wallet.pending.collectAsState()
    var sheet by remember { mutableStateOf<KaChatSiteWallet.Request?>(null) }
    LaunchedEffect(pending?.id, held) {
        if (held) return@LaunchedEffect
        // the sign-in sheet just closed: its key check may still answer the page's connect
        wallet.awaitPreConnect()
        wallet.pending.value?.let {
            wallet.sheetOpen = true
            sheet = it
        }
    }
    val request = sheet ?: return
    key(request.id) {
        IosFullSheet(
            onDismissed = {
                // closed without an answer: declined
                if (wallet.pending.value != null) wallet.decline()
                wallet.sheetOpen = false
                sheet = null
            },
            // a send is only closed by its Cancel or Done, as every send screen
            swipeToDismiss = request.kind !is KaChatSiteWallet.Kind.Send,
        ) { close ->
            SiteWalletSheet(wallet, request, close)
        }
    }
}

/**
 * The sheets a website opened in K's browser asks through [KaChatSiteWallet] (iOS 48f5c357
 * `SiteWalletSheet`, Kasshi): Connect (which address), Sign (a message) and Send (K's own Send
 * Kaspa review). Built from the Send Kaspa pieces so they look like every other send step.
 */
@Composable
private fun SiteWalletSheet(wallet: KaChatSiteWallet, request: KaChatSiteWallet.Request, close: () -> Unit) {
    // no other app's overlay over Connect, Sign or Send (security audit 2026-11-27 S3)
    OverlayTouchGuard()
    val walletViewModel = rememberActivityWalletViewModel()
    if (walletViewModel == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    when (val kind = request.kind) {
        KaChatSiteWallet.Kind.Connect -> SiteConnectView(
            host = request.host,
            wallet = wallet,
            walletViewModel = walletViewModel,
            onConnect = { source ->
                wallet.approveConnect(source, request.host)
                close()
            },
            onDecline = close,
        )
        is KaChatSiteWallet.Kind.Sign -> {
            val connection by wallet.connection.collectAsState()
            SiteSignView(
                host = request.host,
                address = connection?.address.orEmpty(),
                message = kind.message,
                onSign = {
                    wallet.approveSign(kind.message)
                    close()
                },
                onDecline = close,
            )
        }
        is KaChatSiteWallet.Kind.Send -> {
            val connection = remember { wallet.connection.value }
            val chattingAddress by walletViewModel.address.collectAsState()
            val balanceSompi by walletViewModel.balanceSompi.collectAsState()
            val from = chattingAddress
            if (connection == null || from == null) {
                LaunchedEffect(Unit) { close() }
                return
            }
            SpendingAddressSendFlow(
                fromAddress = from,
                balanceSompi = balanceSompi,
                title = stringResource(R.string.chats_send_kaspa),
                viewModel = walletViewModel,
                onDone = close,
                paymentRequest = remember(kind) {
                    com.kachat.app.models.PaymentRequest.create(address = kind.to, amountSompi = kind.sompi, memo = "", toChattingAddress = false)
                },
                lockedSource = connection.source,
                siteHost = request.host,
                onSent = { txId -> wallet.sent(txId) },
                // its answer was lost: "may have been sent" (4002) with the txid, never "rejected"
                // (iOS d88891ea, audit 2026-11-27 M3)
                onOutcomeUnknown = { txId -> wallet.maybeSent(txId, request.host) },
            )
        }
    }
}

/**
 * K's own "Sign In to Kasshi" sheet, up as Kasshi opens from Kaspa Hub, before the page asks (iOS
 * b49a021d `InAppBrowserScreen(signInFirst:)`, owner 2026-11-27): which address signs you in. The
 * pick connects at once ([KaChatSiteWallet.preConnect]), so the site's connect answers with no
 * sheet and its sign-in is signed with no second sheet. Cancel (or a swipe down) browses signed out.
 */
@Composable
fun SiteSignInSheet(
    wallet: KaChatSiteWallet,
    host: String,
    onDone: () -> Unit,
    /** The picked address couldn't connect: nothing signed in (iOS d88891ea, audit 2026-11-27 L1). */
    onFailed: () -> Unit = {},
) {
    IosFullSheet(onDismissed = onDone, swipeToDismiss = true) { close ->
        OverlayTouchGuard()
        val walletViewModel = rememberActivityWalletViewModel()
        if (walletViewModel == null) {
            LaunchedEffect(Unit) { close() }
            return@IosFullSheet
        }
        SiteConnectView(
            host = host,
            wallet = wallet,
            walletViewModel = walletViewModel,
            signIn = true,
            onConnect = { source ->
                wallet.preConnect(source, host.lowercase(), onFailed = onFailed)
                close()
            },
            onDecline = close,
        )
    }
}

/** The app's own [WalletViewModel] (the activity's, the one MainActivity holds), or null. */
@Composable
private fun rememberActivityWalletViewModel(): WalletViewModel? {
    val activity = generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .firstOrNull { it is androidx.activity.ComponentActivity } as? androidx.activity.ComponentActivity
    return activity?.let { androidx.hilt.navigation.compose.hiltViewModel<WalletViewModel>(it) }
}

/** The site's name and what it is asking, at the top of Connect and Sign (iOS 48f5c357 `SiteHeader`). */
@Composable
private fun SiteHeader(host: String, note: String) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(16.dp)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(KaspaTeal.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(Icons.Filled.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
            }
            MiddleEllipsisText(host, modifier = Modifier.weight(1f), color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(note, color = colors.textSecondary, fontSize = 13.sp)
    }
}

/**
 * Connect: which address the site gets (iOS 48f5c357 `SiteConnectView`). The Chatting Address, or
 * another regular address of the account ([OwnAddressList], quantum ones left out). The address
 * picked last time is suggested.
 */
@Composable
private fun SiteConnectView(
    host: String,
    wallet: KaChatSiteWallet,
    walletViewModel: WalletViewModel,
    /** K's own "Sign In to Kasshi" step (before the site asks) words it as signing in (iOS b49a021d). */
    signIn: Boolean = false,
    onConnect: (SendSource) -> Unit,
    onDecline: () -> Unit,
) {
    var source by remember { mutableStateOf<SendSource>(SendSource.Chatting) }
    var choosing by remember { mutableStateOf(false) }
    val chattingBalance by walletViewModel.balanceSompi.collectAsState()
    LaunchedEffect(Unit) {
        // the address connected last time on this site
        val index = wallet.rememberedSpendingIndex(host) ?: return@LaunchedEffect
        walletViewModel.loadManageAddressesAndAwait()
        val entry = walletViewModel.manageAddresses.value.firstOrNull { it.index == index && !it.isQuantum } ?: return@LaunchedEffect
        source = SendSource.Spending(entry.index, entry.address, entry.displayLabel)
        choosing = true
    }
    SendStepScreen(
        title = stringResource(if (signIn) R.string.site_signin_title else R.string.site_connect_to_k),
        onCancel = onDecline,
        continueTitle = stringResource(if (signIn) R.string.site_signin_button else R.string.nc_connect),
        canContinue = !(choosing && source is SendSource.Chatting),
        onContinue = { onConnect(source) },
    ) {
        SendFromStepContent(
            source = source,
            onSourceChange = { source = it },
            choosingFrom = choosing,
            onChoosingFromChange = { choosing = it },
            balanceSompi = chattingBalance,
            walletViewModel = walletViewModel,
            header = { SiteHeader(host, stringResource(if (signIn) R.string.site_signin_note else R.string.site_connect_note)) },
            question = stringResource(if (signIn) R.string.site_signin_question else R.string.site_connect_question),
            pickNote = stringResource(R.string.site_connect_other_note),
            excludeQuantum = true,
        )
    }
}

/** Sign: the site's message in full, signed with the connected address's key (iOS 48f5c357
 *  `SiteSignView`). */
@Composable
private fun SiteSignView(
    host: String,
    address: String,
    message: String,
    onSign: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = LocalAppColors.current
    SendStepScreen(
        title = stringResource(R.string.site_sign_message),
        onCancel = onDecline,
        continueTitle = stringResource(R.string.site_sign),
        canContinue = true,
        onContinue = onSign,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            SiteHeader(host, stringResource(R.string.site_sign_note))
            SendStepChip(stringResource(R.string.address), address, isAddress = true)
            Text(
                stringResource(R.string.message).uppercase(),
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(
                    message,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sendKaspaGlass(16.dp)
                        .padding(14.dp),
                )
            }
        }
    }
}

/** On a site's send: who asked for it, in place of a payment request's card (iOS 48f5c357
 *  `SiteRequestNote`). */
@Composable
fun SiteRequestNote(host: String) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(16.dp)
            .padding(14.dp),
    ) {
        Text(
            stringResource(R.string.pr_requested_by).uppercase(),
            color = KaspaTeal,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(host, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.site_send_check_note), color = colors.textSecondary, fontSize = 12.sp)
    }
}
