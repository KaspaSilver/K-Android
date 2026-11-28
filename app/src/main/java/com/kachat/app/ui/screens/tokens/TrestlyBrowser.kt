package com.kachat.app.ui.screens.tokens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kachat.app.R
import com.kachat.app.services.tokens.CardBuyEthereumProvider
import com.kachat.app.services.tokens.TrestlyProvider
import com.kachat.app.services.tokens.TrestlyProviderFactory
import com.kachat.app.services.tokens.TrestlySite
import com.kachat.app.services.tokens.TrestlyTarget
import com.kachat.app.ui.screens.IosBarTextButton
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.IosToastWhile
import com.kachat.app.ui.screens.WebPageRefreshButton
import com.kachat.app.ui.screens.WebPageReloader
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.rememberHaptics
import com.kachat.app.util.tr
import kotlinx.coroutines.flow.MutableSharedFlow

/** Reaches the provider factory from the browser's composable. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TrestlyDeps {
    fun trestlyProviderFactory(): TrestlyProviderFactory
}

/**
 * Done in Trestly's browser (iOS 148a5331 `Notification.Name.trestlyDone`): everything over the
 * Wallet tab closes (the browser, the address picker and any pushed Manage kUSDT screens), back to
 * its top. The Wallet tab listens (integration).
 */
object TrestlyRoute {
    val done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** The WebView profile the site runs in: its own cookies, storage and cache, wiped before and
 *  after every session (iOS's `WKWebsiteDataStore.nonPersistent()`). */
private const val PROFILE_NAME = "kachat-trestly"
private const val TAG = "Trestly"

/**
 * Trestly's site inside KaChat, full height, working with one address (iOS 148a5331
 * `TrestlyBrowserView`, from KaPay): Done, the site's host with a lock, and a strip saying which
 * address it's connected to. The page gets KaChat's wallet provider ([TrestlyProvider], kusdt-open SPEC §14) for
 * [target] only, and can't leave Trestly's site. A trade waiting for an answer is the Approve sheet
 * over it; a finished swap is a banner over the page. Done declines what's pending and goes back to
 * the top of the Wallet tab ([onDone]). Android's Back goes back in the page's history (iOS's swipe
 * back), and acts as Done where there is none.
 */
@Composable
fun TrestlyBrowserScreen(target: TrestlyTarget, onDone: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    /** Settings > Connection Settings > Trestly (this network's own by default). */
    val siteUrl = remember { TrestlySite.siteUrl(context).trim().trimEnd('/') }
    /** Its Swap page (KAS <-> kUSDT), what most people come for first (owner's call, 2026-11-19). */
    val startUrl = "$siteUrl/swap"
    val provider = remember(target) {
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, TrestlyDeps::class.java)
            .trestlyProviderFactory()
            .make(target, siteUrl)
    }
    // Done or any other way out: a sheet still up is declined and the page can't ask again
    DisposableEffect(provider) { onDispose { provider.close() } }

    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    PauseTrestlyPageInBackground(webView)
    var unavailable by remember { mutableStateOf(false) }
    /** The header's refresh button (iOS 896e8194 `WebPageReloader`). */
    val reloader = remember { WebPageReloader() }
    val latestDone by rememberUpdatedState(onDone)
    val done: () -> Unit = remember(provider) {
        {
            provider.close()
            latestDone()
        }
    }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else done()
    }

    // a completed swap, over the page (iOS `TrestlyProvider.announceSwap`, `.toast(message:)`)
    val haptic = rememberHaptics()
    val banner by provider.banner.collectAsState()
    IosToastWhile(banner)
    LaunchedEffect(banner) { if (banner != null) haptic(IosHaptic.SUCCESS) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Box(Modifier.width(76.dp)) {
                IosBarTextButton(stringResource(R.string.done), onClick = done, bold = true)
            }
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
            // reload, a spinner while the page loads (iOS 896e8194 `WebPageRefreshButton`)
            Box(Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                WebPageRefreshButton(isLoading = isLoading, tint = KaspaTeal) { reloader.reload() }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(KaspaTeal.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                tr(R.string.kp_connected_label, target.label),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${target.address.take(14)}...${target.address.takeLast(6)}",
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (unavailable) {
            // English until iOS has a text for it: iOS's WebKit always has what the provider needs
            Text(
                "Trestly can't open in K on this phone: its Android System WebView is too old. Update it from the Play Store and try again.",
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            AndroidView(
                // the keyboard moves the page once ([trestlyKeyboardPadding])
                modifier = Modifier.fillMaxWidth().weight(1f).trestlyKeyboardPadding(),
                factory = { ctx ->
                    val view = makeTrestlyWebView(ctx, provider, startUrl, onLoading = { isLoading = it }, onGone = done, reloader = reloader)
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
    }

    // the trade waiting for an answer (iOS `.sheet(item: $provider.approval)`): closing it declines
    val approval by provider.approval.collectAsState()
    approval?.let { request ->
        key(request.id) {
            IosFullSheet(onDismissed = { request.respond(false) }, swipeToDismiss = true) { close ->
                TrestlyApprovalSheet(request) { approved ->
                    request.respond(approved)
                    close()
                }
            }
        }
    }
}

/**
 * The page itself (iOS `TrestlyWebView`): a WebView in its own profile (nothing shared with the
 * app's other web pages, wiped before and after), with KaChat's wallet provider for the Trestly
 * origin only, and locked to that origin: a link the user taps to anywhere else opens in the
 * phone's browser, anything else off the site is stopped. Frames inside the page may load (they
 * can't reach the provider). Null when this WebView lacks the document-start script or the
 * origin-bound message listener the provider needs, or can't be made at all.
 */
/**
 * Whether a camera request from [scheme]://[host]:[port] may be asked about on Buy Kaspa's page:
 * https only, from the Trestly site ([trestlyHost], [trestlyPort]) or Stripe's own frames inside
 * it (`stripe.com` / `stripe.network` and their subdomains), where its identity check runs.
 * [port] -1 (or 0) is https's default, 443.
 */
internal fun trestlyCameraOriginAllowed(scheme: String?, host: String?, port: Int, trestlyHost: String, trestlyPort: Int): Boolean {
    if (scheme?.lowercase() != "https") return false
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    if (h.isEmpty()) return false
    val p = if (port <= 0) 443 else port
    if (h == trestlyHost.lowercase() && p == trestlyPort) return true
    return p == 443 && STRIPE_CAMERA_DOMAINS.any { h == it || h.endsWith(".$it") }
}

private val STRIPE_CAMERA_DOMAINS = listOf("stripe.com", "stripe.network")

/**
 * A Trestly page stops drawing, animating and playing while K is in the background, and picks up
 * again when K is back (release audit 2026-11-27 R4). JavaScript timers are the process's and stay
 * with [com.kachat.app.services.InAppBrowserData].
 */
@androidx.compose.runtime.Composable
internal fun PauseTrestlyPageInBackground(view: WebView?) {
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, view) {
        val page = view ?: return@DisposableEffect onDispose {}
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { page.onPause() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { page.onResume() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
internal fun makeTrestlyWebView(
    context: Context,
    provider: TrestlyProvider,
    startUrl: String,
    onLoading: (Boolean) -> Unit,
    onGone: () -> Unit,
    /** Buy Kaspa's page: K's Ethereum side too (iOS bc0e6ba9 `TrestlyWebView.ethereum`). */
    ethereum: CardBuyEthereumProvider? = null,
    /** The header's refresh button. */
    reloader: WebPageReloader? = null,
    /** Buy Kaspa's page: the page asked for the camera (Stripe's identity check, shown in the
     *  page); answer with [PermissionRequest.grant] or [PermissionRequest.deny]. Elsewhere the
     *  page gets no device access. */
    onCameraRequest: ((PermissionRequest) -> Unit)? = null,
): WebView? {
    val supported = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    } catch (e: Exception) {
        false
    }
    if (!supported || provider.allowedHost.isEmpty()) return null
    if (ethereum != null && ethereum.originRule != provider.originRule) return null
    val ownProfile = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Exception) { false }
    if (ownProfile) {
        // whatever an earlier session left (the app killed while it was open) goes first
        try { ProfileStore.getInstance().deleteProfile(PROFILE_NAME) } catch (e: Exception) {}
    }
    val view = try {
        WebView(context)
    } catch (e: Exception) {
        Log.w(TAG, "the Trestly WebView couldn't be made: ${e.javaClass.simpleName}")
        return null
    }
    try {
        // before anything else is done with the view
        if (ownProfile) WebViewCompat.setProfile(view, PROFILE_NAME)
    } catch (e: Exception) {
        Log.w(TAG, "the Trestly profile couldn't be set: ${e.javaClass.simpleName}")
    }
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    // The page is in a sheet whose own drag (Material's, which only snaps back here: swipe-down
    // is off) took every up and down swipe, so the page never scrolled. A touch on the page is
    // the page's from the start: the sheet is told not to take it, as iOS's full-screen cover
    // has no drag of its own.
    @Suppress("ClickableViewAccessibility")
    view.setOnTouchListener { v, event ->
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }
    view.settings.apply {
        javaScriptEnabled = true
        // the site keeps its own state while it's open; wiped when it closes
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        // Stripe's identity check (Buy Kaspa) plays the camera in the page (iOS bc0e6ba9
        // `allowsInlineMediaPlayback`; Android plays video inline already)
        mediaPlaybackRequiresUserGesture = ethereum == null
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    try {
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    } catch (e: Exception) {}

    val origin = provider.originRule
    try {
        WebViewCompat.addWebMessageListener(view, TrestlyProvider.BRIDGE_NAME, setOf(origin)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            provider.handle(message.data, sourceOrigin.scheme, sourceOrigin.host, sourceOrigin.port, isMainFrame) { answer ->
                replyProxy.postMessage(answer)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view, provider.script, setOf(origin))
        if (ethereum != null) {
            WebViewCompat.addWebMessageListener(view, CardBuyEthereumProvider.BRIDGE_NAME, setOf(origin)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                ethereum.handle(message.data, sourceOrigin.scheme, sourceOrigin.host, sourceOrigin.port, isMainFrame) { answer ->
                    replyProxy.postMessage(answer)
                }
            }
            WebViewCompat.addDocumentStartJavaScript(view, ethereum.script, setOf(origin))
        }
    } catch (e: Exception) {
        Log.w(TAG, "the Trestly provider couldn't be installed: ${e.javaClass.simpleName}")
        view.destroy()
        return null
    }

    fun isTrestly(url: Uri?): Boolean {
        if (url == null || url.scheme?.lowercase() != "https" || url.host?.lowercase() != provider.allowedHost) return false
        val port = if (url.port <= 0) 443 else url.port
        return port == provider.allowedPort
    }

    fun openOutside(url: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "no app opened an outside link")
        }
    }

    view.webViewClient = object : WebViewClient() {
        /** The page stays on Trestly's site (iOS audit, 2026-11-20): a link or redirect anywhere
         *  else would show another site under the Trestly bar, inside the wallet. A tapped link
         *  opens in the phone's browser instead; anything else off the site is stopped. */
        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (!request.isForMainFrame || isTrestly(url) || url.scheme == "about") return false
            if (request.hasGesture() && !request.isRedirect && url.scheme?.lowercase() == "https") openOutside(url)
            return true
        }

        override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // belt and braces for a main-frame load that never came through the check above
            val uri = url?.let { Uri.parse(it) }
            if (uri != null && !isTrestly(uri) && uri.scheme != "about") {
                v.stopLoading()
                v.loadUrl("about:blank")
                return
            }
            onLoading(true)
        }

        override fun onPageFinished(v: WebView, url: String?) = onLoading(false)

        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onLoading(false)
        }

        /** The page's process was killed: the view can't be used again, and returning true keeps
         *  the app alive. The browser closes, as Done. */
        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "the Trestly page was terminated")
            onGone()
            return true
        }
    }
    view.webChromeClient = object : WebChromeClient() {
        // no camera, microphone, location or other device access for the page; on Buy Kaspa's
        // page only the camera alone, for Stripe's identity check, and only once asked - and
        // only from the Trestly site itself or Stripe's frames inside it
        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin
            val cameraOnly = request.resources.isNotEmpty() &&
                request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE } &&
                trestlyCameraOriginAllowed(origin?.scheme, origin?.host, origin?.port ?: -1, provider.allowedHost, provider.allowedPort)
            if (onCameraRequest != null && cameraOnly) onCameraRequest(request) else request.deny()
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }
    // The keyboard moves the page once: the screen shrinks the view for it
    // ([trestlyKeyboardPadding]), so the view itself is never told about the keyboard as well.
    // Both at once made Trestly's page jump on every tap in Stripe's fields on iOS, so Paste,
    // AutoFill and a texted code couldn't land (iOS 896e8194, owner 2026-11-26).
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val withoutKeyboard = WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build()
        ViewCompat.onApplyWindowInsets(v, withoutKeyboard)
    }
    reloader?.webView = view
    reloader?.startUrl = startUrl
    view.loadUrl(startUrl)
    return view
}

/**
 * Trestly's and Buy Kaspa's page make room for the keyboard once, where it will end up (iOS
 * 896e8194 `.ignoresSafeArea(.keyboard)` on `TrestlyWebView`): one resize as the keyboard starts
 * to show or hide, never a resize on every frame of its slide, and the view itself doesn't move
 * the page for it too (its own IME insets are cleared in [makeTrestlyWebView]). The keyboard
 * stays over what the system bars already took (the sheet's navigation bar padding).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.trestlyKeyboardPadding(): Modifier = this.windowInsetsPadding(WindowInsets.imeAnimationTarget)

/** The browser closed: the page goes, and so does everything it kept (iOS's data store is
 *  non-persistent): its profile's cookies and storage, then the profile itself. Without profiles
 *  (an older WebView) the site's own cookies and storage are cleared in the shared one. */
@SuppressLint("RequiresFeature")
internal fun releaseTrestlyWebView(view: WebView, siteUrl: String) {
    val ownProfile = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Exception) { false }
    try {
        if (ownProfile) {
            val profile = WebViewCompat.getProfile(view)
            if (profile.name == PROFILE_NAME) {
                profile.cookieManager.removeAllCookies(null)
                profile.webStorage.deleteAllData()
            }
        } else {
            val cookies = CookieManager.getInstance()
            cookies.getCookie(siteUrl)?.split(";")?.forEach { pair ->
                val name = pair.substringBefore("=").trim()
                if (name.isNotEmpty()) cookies.setCookie(siteUrl, "$name=; Max-Age=0; Path=/")
            }
            cookies.flush()
            WebStorage.getInstance().deleteOrigin(siteUrl)
        }
        view.clearCache(true)
        view.clearHistory()
    } catch (e: Exception) {
        Log.w(TAG, "clearing the Trestly page's data: ${e.javaClass.simpleName}")
    }
    try {
        view.stopLoading()
        view.destroy()
    } catch (e: Exception) {}
    if (ownProfile) {
        // gone once no view uses it; if it's still held, the next session's start deletes it
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { ProfileStore.getInstance().deleteProfile(PROFILE_NAME) } catch (e: Exception) {}
        }
    }
}
