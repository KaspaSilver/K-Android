package com.kachat.app.ui.screens.tokens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kachat.app.R
import com.kachat.app.services.WalletManager
import com.kachat.app.services.tokens.CardBuy
import com.kachat.app.services.tokens.CardBuyEthereumProvider
import com.kachat.app.services.tokens.CardBuyTracker
import com.kachat.app.services.tokens.EthereumAccount
import com.kachat.app.services.tokens.PermitRequest
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenWallet
import com.kachat.app.services.tokens.TrestlyProviderFactory
import com.kachat.app.services.tokens.TrestlySite
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosToastWhile
import com.kachat.app.ui.screens.SelectablePill
import com.kachat.app.ui.screens.SendStepQuestion
import com.kachat.app.ui.screens.SendStepScreen
import com.kachat.app.ui.screens.SheetBarIconButton
import com.kachat.app.ui.screens.WebPageRefreshButton
import com.kachat.app.ui.screens.WebPageReloader
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.AppText
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reaches Buy Kaspa's services from its composables. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BuyKaspaDeps {
    fun cardBuyTracker(): CardBuyTracker
    fun walletManager(): WalletManager
    fun tokenWallet(): TokenWallet
    fun trestlyProviderFactory(): TrestlyProviderFactory
}

private fun buyKaspaDeps(context: android.content.Context): BuyKaspaDeps =
    dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, BuyKaspaDeps::class.java)

/** The app's [CardBuyTracker], for the Buy chooser's subtitle and the Wallet tab's start. */
@Composable
fun rememberCardBuyTracker(): CardBuyTracker {
    val context = LocalContext.current
    return remember { buyKaspaDeps(context).cardBuyTracker() }
}

/**
 * Buy Kaspa with Debit Card from the Wallet tab's Buy chooser (iOS 824a30bb `DebitCardBuy`, owner
 * 2026-11-26: the card on the Wallet tab is gone); its order's stage shows there under it
 * ([BuyKaspaStatus.line] of [shownOrder]).
 */
object DebitCardBuy {
    /** Trestly sells by card on mainnet only. */
    val isOffered: Boolean get() = KaspaNetwork.launch == KaspaNetwork.Type.MAINNET

    /**
     * Buy Kaspa with Debit Card tapped: a paid order whose kUSDT arrived goes on to Buy Kaspa with
     * that kUSDT ([openSwap]: `SwapService.prepareKusdtSell(target, units)`, then Swap's order
     * form); one still on its way, or refunded, opens on its page; anything else starts a new
     * order ([openPage] with null).
     */
    fun open(tracker: CardBuyTracker, openSwap: (target: TrestlyTarget, units: Long) -> Unit, openPage: (CardBuy?) -> Unit) {
        val buy = shownOrder(tracker.currentBuy())
        if (buy != null) {
            val units = buy.mintedUnits
            if (buy.stage == CardBuy.Stage.ARRIVED && units != null) {
                val target = buy.target
                tracker.clear()
                openSwap(target, units)
                return
            }
            // a refunded one too: its page is where the USDC is bridged again (iOS d88891ea,
            // audit 2026-11-27 M7)
            if (!buy.isFinished || buy.stage == CardBuy.Stage.REFUNDED) {
                openPage(buy)
                return
            }
            tracker.clear()
        }
        openPage(null)
    }

    /** The order to show: only one that was paid (one never paid is dropped once Buy Kaspa closes,
     *  and never shows, even before that check runs). A refused one shows too. */
    fun shownOrder(buy: CardBuy?): CardBuy? =
        buy?.takeIf { it.paidSeen == true || it.stage == CardBuy.Stage.REJECTED }
}

/** What a purchase's stage reads as, in the Buy chooser and over the page (iOS `BuyKaspaStatus`). */
object BuyKaspaStatus {
    fun line(buy: CardBuy): String = when (buy.stage) {
        CardBuy.Stage.WAITING_PAYMENT -> tr(R.string.buykaspa_waiting_payment)
        CardBuy.Stage.PAID -> tr(R.string.buykaspa_paid)
        CardBuy.Stage.BRIDGING -> tr(R.string.buykaspa_bridging)
        CardBuy.Stage.ARRIVED -> {
            val amount = buy.mintedUnits?.let { TokenAmount.amount(it, WalletToken.KUSDT) } ?: WalletToken.KUSDT.symbol
            tr(R.string.buykaspa_arrived, amount)
        }
        CardBuy.Stage.SIGN_AGAIN -> tr(R.string.buykaspa_sign_again)
        CardBuy.Stage.REJECTED -> tr(R.string.buykaspa_rejected)
        CardBuy.Stage.REFUNDED -> tr(R.string.buykaspa_refunded)
        CardBuy.Stage.EXPIRED -> tr(R.string.buykaspa_expired)
    }
}

private sealed class BuyStep {
    object Address : BuyStep()
    data class Page(val target: TrestlyTarget, val session: String?) : BuyStep()
}

/**
 * The flow, full screen from the Buy chooser (iOS bc0e6ba9 / 6d1971c8 / 824a30bb `BuyKaspaView`):
 * the address step ("Where should your kUSDT land?": Fresh Address by default, or the Chatting
 * Address), Trestly's buy page ([CardBuyBrowserScreen]), then Swap: once the kUSDT is in, Swap to
 * Kaspa clears the order and calls [onSwapToKaspa] with where it landed and how much, for the
 * caller to set up Swap as kUSDT for KAS (`SwapService.prepareKusdtSell`) and show it in place of
 * this screen. [resume] reopens an order still on its way (or refunded) on its page. [preferChatting] (a chat's
 * "Fund your chatting address") picks the Chatting Address to start with.
 *
 * Draws edge to edge and keeps clear of the system bars itself, so it can sit in a full-screen
 * dialog or a full sheet.
 */
@Composable
fun BuyKaspaScreen(
    resume: CardBuy?,
    onClose: () -> Unit,
    onSwapToKaspa: (target: TrestlyTarget, units: Long) -> Unit,
    preferChatting: Boolean = false,
) {
    val context = LocalContext.current
    remember { AppText.init(context) }
    val colors = LocalAppColors.current
    val deps = remember { buyKaspaDeps(context) }
    val tracker = remember { deps.cardBuyTracker() }
    val walletManager = remember { deps.walletManager() }
    val tokenWallet = remember { deps.tokenWallet() }

    var step by remember { mutableStateOf<BuyStep>(resume?.let { BuyStep.Page(it.target, it.session) } ?: BuyStep.Address) }
    /** Fresh Address (owner, 2026-11-26: "so kUSDT lands in a fresh address") or the Chatting Address. */
    var useFresh by remember { mutableStateOf(!preferChatting) }
    /** The next unused regular address ([TokenWallet.freshReceiveAddress]); listed only once paid. */
    var fresh by remember { mutableStateOf<Pair<Int, String>?>(null) }
    /** K's Ethereum address for this account; null when its words aren't on this phone. */
    var ethereumAddress by remember { mutableStateOf<String?>(null) }
    var checkedWords by remember { mutableStateOf(false) }

    DisposableEffect(tracker) {
        tracker.screenOpen = true
        tracker.ensurePolling()
        onDispose { tracker.screenOpen = false }
    }
    LaunchedEffect(Unit) {
        if (!checkedWords) {
            ethereumAddress = withContext(Dispatchers.Default) { EthereumAccount.address(walletManager) }
            checkedWords = true
        }
        if (fresh == null) fresh = withContext(Dispatchers.Default) { runCatching { tokenWallet.freshReceiveAddress() }.getOrNull() }
    }

    val swap: (CardBuy) -> Unit = { buy ->
        buy.mintedUnits?.let { units ->
            val target = buy.target
            tracker.clear()
            onSwapToKaspa(target, units)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { slideInHorizontally(tween(350)) { it } togetherWith slideOutHorizontally(tween(350)) { -it } },
            label = "buyKaspa",
        ) { shown ->
            when (shown) {
                BuyStep.Address -> BuyAddressStep(
                    walletManager = walletManager,
                    useFresh = useFresh,
                    onUseFresh = { useFresh = it },
                    fresh = fresh,
                    ethereumAddress = ethereumAddress,
                    checkedWords = checkedWords,
                    onCancel = onClose,
                    onContinue = { target ->
                        // a fresh address isn't put on Manage Addresses' list yet: only once an order
                        // to it is paid ([CardBuyTracker]), so opening Buy Kaspa and leaving doesn't
                        // use up an address
                        tokenWallet.noteReceiveAddressShown(target.address)
                        step = BuyStep.Page(target, null)
                    },
                )
                is BuyStep.Page -> {
                    val address = ethereumAddress
                    when {
                        address != null -> CardBuyBrowserScreen(
                            target = shown.target,
                            session = shown.session,
                            ethereumAddress = address,
                            onClose = onClose,
                            onSwap = swap,
                        )
                        checkedWords -> NoWords(onDone = onClose)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IosActivityIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyAddressStep(
    walletManager: WalletManager,
    useFresh: Boolean,
    onUseFresh: (Boolean) -> Unit,
    fresh: Pair<Int, String>?,
    ethereumAddress: String?,
    checkedWords: Boolean,
    onCancel: () -> Unit,
    onContinue: (TrestlyTarget) -> Unit,
) {
    val colors = LocalAppColors.current
    val chatting by walletManager.activeAddressFlow.collectAsState()
    BackHandler { onCancel() }
    val freshTitle = fresh?.let { tr(R.string.address_number, WalletManager.addressNumber(it.first)) }
    SendStepScreen(
        title = stringResource(R.string.buykaspa_title),
        onCancel = onCancel,
        canContinue = ethereumAddress != null && (!useFresh || fresh != null),
        onContinue = cont@{
            val target = if (useFresh) {
                val picked = fresh ?: return@cont
                TrestlyTarget(tr(R.string.address_number, WalletManager.addressNumber(picked.first)), picked.second, picked.first)
            } else {
                val address = chatting ?: return@cont
                TrestlyTarget(tr(R.string.chatting_address), address, null)
            }
            onContinue(target)
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            SendStepQuestion(stringResource(R.string.buykaspa_where_land))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SelectablePill(stringResource(R.string.buykaspa_fresh_address), selected = useFresh, modifier = Modifier.weight(1f)) { onUseFresh(true) }
                SelectablePill(stringResource(R.string.chatting_address), selected = !useFresh, modifier = Modifier.weight(1f)) { onUseFresh(false) }
            }
            if (useFresh) {
                BuyAddressCard(
                    title = freshTitle ?: stringResource(R.string.buykaspa_fresh_address),
                    address = fresh?.second,
                    note = stringResource(R.string.buykaspa_fresh_note),
                )
            } else {
                chatting?.let {
                    BuyAddressCard(stringResource(R.string.chatting_address), it, stringResource(R.string.buykaspa_chatting_note))
                }
            }
            if (checkedWords && ethereumAddress == null) {
                Text(stringResource(R.string.buykaspa_needs_words), color = Color(0xFFFF9500), fontSize = 13.sp)
            }
            Text(stringResource(R.string.buykaspa_stripe_note), color = colors.textSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BuyAddressCard(title: String, address: String?, note: String) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(16.dp)
            .padding(14.dp),
    ) {
        Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (address != null) {
            Text(shortAddress(address), color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        } else {
            IosActivityIndicator(modifier = Modifier.size(14.dp))
        }
        Text(note, color = colors.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun NoWords(onDone: () -> Unit) {
    val colors = LocalAppColors.current
    BackHandler { onDone() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Text(stringResource(R.string.buykaspa_needs_words), color = colors.textPrimary, fontSize = 17.sp, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.done),
            color = KaspaTeal,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun shortAddress(address: String): String = "${address.take(14)}...${address.takeLast(6)}"

/**
 * Trestly's buy page inside K (iOS bc0e6ba9 / 896e8194 `CardBuyBrowserView`): Close, the site, the
 * refresh button, which address the kUSDT lands on, the page with K's Kaspa side
 * ([com.kachat.app.services.tokens.TrestlyProvider], for that address) and Ethereum side
 * ([CardBuyEthereumProvider]), and once the order is paid, where it is, with Swap to Kaspa when
 * the kUSDT is in. Android's Back goes back in the page's history, and closes where there is none.
 */
@Composable
internal fun CardBuyBrowserScreen(
    target: TrestlyTarget,
    session: String?,
    ethereumAddress: String,
    onClose: () -> Unit,
    onSwap: (CardBuy) -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val deps = remember { buyKaspaDeps(context) }
    val tracker = remember { deps.cardBuyTracker() }
    val siteUrl = remember { TrestlySite.siteUrl(context).trim().trimEnd('/') }
    /** `/buy`, or an order already on its way (`/buy?session=`), which the page shows as it goes. */
    val url = remember(session) {
        val buy = "$siteUrl/buy"
        if (session == null) buy else Uri.parse(buy).buildUpon().appendQueryParameter("session", session).build().toString()
    }
    val provider = remember(target) { deps.trestlyProviderFactory().make(target, siteUrl) }
    val ethereum = remember(target, ethereumAddress) {
        CardBuyEthereumProvider(target, siteUrl, ethereumAddress, deps.trestlyProviderFactory().iconDataUrl, tracker, deps.walletManager())
    }
    DisposableEffect(provider, ethereum) {
        onDispose {
            provider.close()
            ethereum.release()
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    PauseTrestlyPageInBackground(webView)
    var unavailable by remember { mutableStateOf(false) }
    val reloader = remember { WebPageReloader() }
    val latestClose by rememberUpdatedState(onClose)
    val close: () -> Unit = remember(provider, ethereum) {
        {
            provider.close()
            ethereum.close()
            latestClose()
        }
    }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else close()
    }

    // Stripe's identity check asks for the camera in the page: Android's own permission prompt
    // first when K doesn't hold it yet (iOS's WebKit asks)
    var cameraAsk by remember { mutableStateOf<PermissionRequest?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = cameraAsk
        cameraAsk = null
        if (request != null) {
            if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny()
        }
    }
    val askCamera: (PermissionRequest) -> Unit = { request ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else if (cameraAsk != null) {
            request.deny()
        } else {
            cameraAsk = request
            try { cameraLauncher.launch(Manifest.permission.CAMERA) } catch (e: Exception) { cameraAsk = null; request.deny() }
        }
    }
    val latestAskCamera by rememberUpdatedState(askCamera)

    val haptic = rememberHaptics()
    val banner by provider.banner.collectAsState()
    IosToastWhile(banner)
    LaunchedEffect(banner) { if (banner != null) haptic(IosHaptic.SUCCESS) }

    /** The order this page made, once it's paid (before that the page itself is the whole story). */
    val tracked by tracker.buy.collectAsState()
    val followed = tracked?.takeIf { it.address == target.address && it.paidSeen == true }
    var lastStage by remember { mutableStateOf(followed?.stage) }
    LaunchedEffect(followed?.stage) {
        val stage = followed?.stage
        // iOS `Haptics.success()` when the kUSDT lands
        if (stage == CardBuy.Stage.ARRIVED && lastStage != null && lastStage != CardBuy.Stage.ARRIVED) haptic(IosHaptic.SUCCESS)
        lastStage = stage
    }

    // the keyboard moves the page once ([trestlyKeyboardPadding]): the order's bar rides above it
    Column(Modifier.fillMaxSize().background(colors.background).trestlyKeyboardPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SheetBarIconButton(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
                onClick = close,
                modifier = Modifier.size(40.dp),
                iconSize = 18.dp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    Uri.parse(siteUrl).host ?: "trestly.io",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WebPageRefreshButton(isLoading = isLoading, size = 40.dp, tint = KaspaTeal) { reloader.reload() }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(KaspaTeal.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Image(painterResource(R.drawable.ic_kaspa_logo), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                tr(R.string.buykaspa_lands_on_format, target.label),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                shortAddress(target.address),
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (unavailable) {
            // English until iOS has a text for it, as Trestly's browser says it
            Text(
                "Trestly can't open in K on this phone: its Android System WebView is too old. Update it from the Play Store and try again.",
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    val view = makeTrestlyWebView(
                        ctx, provider, url,
                        onLoading = { isLoading = it },
                        onGone = close,
                        ethereum = ethereum,
                        reloader = reloader,
                        onCameraRequest = { request -> latestAskCamera(request) },
                    )
                    if (view == null) {
                        unavailable = true
                        isLoading = false
                        android.widget.FrameLayout(ctx)
                    } else {
                        webView = view
                        view
                    }
                },
                onRelease = { view ->
                    if (reloader.webView === view) reloader.webView = null
                    if (view is WebView) releaseTrestlyWebView(view, siteUrl)
                    webView = null
                },
            )
        }

        AnimatedVisibility(
            visible = followed != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            // the last order shown stays drawn while the bar slides away
            var shownOrder by remember { mutableStateOf(followed) }
            if (followed != null) shownOrder = followed
            shownOrder?.let { CardBuyStatusBar(it, onSwap) }
        }
    }

    // the permit waiting for an answer (iOS `.sheet(item: $ethereum.pending)`): closing it declines
    val pending by ethereum.pending.collectAsState()
    pending?.let { request ->
        key(request.id) {
            IosFullSheet(onDismissed = { request.respond(false) }, swipeToDismiss = true) { dismiss ->
                CardBuySignSheet(request) { approved ->
                    request.respond(approved)
                    dismiss()
                }
            }
        }
    }

    // a trade from the page's Kaspa side (iOS `.sheet(item: $provider.approval)`)
    val approval by provider.approval.collectAsState()
    approval?.let { request ->
        key(request.id) {
            IosFullSheet(onDismissed = { request.respond(false) }, swipeToDismiss = true) { dismiss ->
                TrestlyApprovalSheet(request) { approved ->
                    request.respond(approved)
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun CardBuyStatusBar(buy: CardBuy, onSwap: (CardBuy) -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (buy.stage == CardBuy.Stage.ARRIVED) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
            } else if (!buy.isFinished) {
                IosActivityIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                BuyKaspaStatus.line(buy),
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        if (buy.stage == CardBuy.Stage.ARRIVED) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .clickable(role = Role.Button) {
                        haptic(IosHaptic.IMPACT_LIGHT)
                        onSwap(buy)
                    },
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.buykaspa_swap_to_kaspa), color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (!buy.isFinished) {
            Text(stringResource(R.string.buykaspa_can_close), color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

/** The sign sheet's "Ends" needs a date: [deadlineMs] isn't on [nowMs]'s day in [zone]. */
internal fun permitEndsShowDate(deadlineMs: Long, nowMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean {
    val deadline = java.util.Calendar.getInstance(zone).apply { timeInMillis = deadlineMs }
    val now = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    return deadline.get(java.util.Calendar.YEAR) != now.get(java.util.Calendar.YEAR) ||
        deadline.get(java.util.Calendar.DAY_OF_YEAR) != now.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * The one Ethereum signature Buy Kaspa asks for (iOS bc0e6ba9 `CardBuySignSheet`, "Approve the
 * Bridge"): the USDC permit that lets Trestly's bridge move the USDC to Kaspa. Shown only once
 * [PermitRequest.parse] has checked it; Cancel and Back decline.
 */
@Composable
internal fun CardBuySignSheet(request: PermitRequest, onAnswer: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    BackHandler { onAnswer(false) }
    // The time alone when it ends today; a permit that runs past midnight needs its day too.
    val ends = remember(request.id) {
        if (permitEndsShowDate(request.deadlineMs, System.currentTimeMillis())) {
            android.text.format.DateUtils.formatDateTime(
                context, request.deadlineMs,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
            )
        } else {
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(request.deadlineMs))
        }
    }
    SendStepScreen(
        title = stringResource(R.string.buykaspa_approve_bridge),
        onCancel = { onAnswer(false) },
        canContinue = true,
        onContinue = { onAnswer(true) },
        continueTitle = stringResource(R.string.kp_approve),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sendKaspaGlass(16.dp)
                    .padding(14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
                ) {
                    Icon(Icons.Filled.Draw, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                }
                Text(stringResource(R.string.buykaspa_sign_explainer), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sendKaspaGlass(16.dp)
                    .padding(horizontal = 14.dp),
            ) {
                SignRow(stringResource(R.string.buykaspa_up_to)) {
                    Text(request.amountText, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                SignRow(stringResource(R.string.buykaspa_lands_on)) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(request.target.label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(shortAddress(request.target.address), color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                SignRow(stringResource(R.string.buykaspa_ends)) {
                    Text(ends, color = colors.textPrimary, fontSize = 15.sp)
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                // the whole deposit address, never cut (audit 2026-11-26): K can't check it itself
                // (Trestly's factory names it from fee caps the permit doesn't carry)
                SignRow(stringResource(R.string.buykaspa_bridge)) {
                    Text(
                        request.spender,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }

            Text(stringResource(R.string.buykaspa_eth_note), color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SignRow(title: String, value: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Text(title, color = LocalAppColors.current.textSecondary, fontSize = 15.sp)
        Spacer(Modifier.width(12.dp).weight(1f))
        value()
    }
}
