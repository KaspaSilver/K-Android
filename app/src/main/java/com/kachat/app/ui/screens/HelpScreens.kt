package com.kachat.app.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import com.kachat.app.services.KaChatSiteWallet
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Full-screen in-app browser for the Apps bubbles - the only way out is the X top-left,
 * matching iOS's InAppBrowserScreen. The header names the page on screen now (its host, with
 * "http://" when it isn't https), not the site it opened on ([title] is no longer shown, as on
 * iOS). It stays on the site it opened (that host and its subdomains, "www." aside): a link or
 * redirect to another http(s) site opens in the phone's browser, other schemes are refused
 * (iOS audit 2026-11-21 #2; security audit L3). A site [KaChatSiteWallet] allows (Kasshi) gets K
 * as its wallet, `window.kachat`, and its requests as sheets over the page (iOS 48f5c357).
 *
 * [signInFirst] (Kasshi from Kaspa Hub, iOS b49a021d, owner 2026-11-27) asks which address to sign
 * in with before the page does: the site's connect then answers at once and its sign-in is signed
 * with no second sheet ([KaChatSiteWallet.preConnect]). Cancel browses signed out.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(
    url: String,
    title: String,
    notice: String? = null,
    signInFirst: Boolean = false,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    var currentUrl by remember(url) { mutableStateOf(url) }
    var isLoading by remember(url) { mutableStateOf(true) }
    /** The header's refresh button (iOS 896e8194 `WebPageReloader`). */
    val reloader = remember(url) { WebPageReloader() }
    /** K as the page's wallet (`window.kachat`), on the sites [KaChatSiteWallet] allows (Kasshi). */
    val siteWallet = rememberSiteWallet(url)
    /** K's own sign-in sheet, up when the page opens ([signInFirst]); asked once per browser. */
    var showSignIn by remember(url) { mutableStateOf(false) }
    var signInAsked by remember(url) { mutableStateOf(false) }
    /** The sign-in sheet is on its way (iOS 01f71184 `signInWaiting`): the page's own requests
     *  wait for it. */
    var signInWaiting by remember(url) { mutableStateOf(false) }
    val signInScope = androidx.compose.runtime.rememberCoroutineScope()
    /** The picked sign-in address couldn't connect: said, not a silent sign-in that never happened
     *  (iOS d88891ea, audit 2026-11-27 L1). */
    var signInFailed by remember(url) { mutableStateOf(false) }
    // Only reopened here (iOS b49a021d): a browser hidden and brought back keeps its page; it is
    // closed for good when the web view goes (onRelease below).
    OnScreenPresence(
        appear = {
            siteWallet?.reopen()
            if (signInFirst && !signInAsked && siteWallet != null && siteWallet.connection.value == null) {
                signInAsked = true
                // a moment after the screen is up, not in the same pass that builds it and the web
                // view, so the Hub's close and the sheet's rise don't stall each other (iOS
                // 01f71184, smoothness audit T1)
                signInWaiting = true
                signInScope.launch {
                    kotlinx.coroutines.delay(400)
                    signInWaiting = false
                    showSignIn = true
                }
            }
        },
        disappear = {},
    )
    // The page pauses while it isn't in view (battery audit 2026-11-27 R4): hidden in a kept Hub
    // section, or K in the background; in the background its JavaScript timers stop too.
    val hiddenInHub = LocalHubSectionHidden.current
    val browserLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var appStarted by remember(browserLifecycle) {
        mutableStateOf(browserLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }
    androidx.compose.runtime.DisposableEffect(browserLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            appStarted = browserLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        browserLifecycle.addObserver(observer)
        onDispose { browserLifecycle.removeObserver(observer) }
    }
    /** This browser's own web data (S1): one profile for every view it shows, gone when it
     *  closes. */
    val browserSession = remember(url) { com.kachat.app.services.InAppBrowserData.newSession() }
    androidx.compose.runtime.DisposableEffect(browserSession) {
        onDispose { com.kachat.app.services.InAppBrowserData.endSession(browserSession) }
    }
    /** The page's WebView now (a new one after its renderer died). */
    var pageView by remember(url) { mutableStateOf<WebView?>(null) }
    val pageInView = appStarted && !hiddenInHub
    androidx.compose.runtime.LaunchedEffect(pageInView, pageView) {
        val page = pageView ?: return@LaunchedEffect
        runCatching { if (pageInView) page.onResume() else page.onPause() }
    }
    androidx.compose.runtime.LaunchedEffect(appStarted, pageView) {
        val page = pageView ?: return@LaunchedEffect
        if (appStarted) com.kachat.app.services.InAppBrowserData.resumeTimers(page)
        else com.kachat.app.services.InAppBrowserData.pauseTimers(page)
    }
    val headerHost = remember(currentUrl) {
        val shown = runCatching { android.net.Uri.parse(currentUrl) }.getOrNull()
        val host = shown?.host.orEmpty()
        if (shown?.scheme?.lowercase() == "https") host else "http://$host"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.kachat.app.R.string.close), tint = KaspaTeal)
            }
            Text(
                headerHost,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // reload, a spinner while the page loads (iOS 896e8194 `WebPageRefreshButton`)
            WebPageRefreshButton(isLoading = isLoading) { reloader.reload() }
        }
        // Optional strip under the top bar (Claim Testnet Kaspa: "your address is copied",
        // iOS 2a81767).
        if (notice != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KaspaTeal.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(notice, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        // No WebView on this phone, or one mid-update (MissingWebViewPackageException /
        // AndroidRuntimeException): the page opens in the phone's browser instead of crashing.
        var webViewMissing by remember { mutableStateOf(false) }
        val hostContext = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(webViewMissing) {
            if (!webViewMissing) return@LaunchedEffect
            try {
                hostContext.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // nothing can open it: the screen just closes
            }
            onClose()
        }
        // The page's renderer died (killed for memory, or crashed): that WebView can't be used
        // again, so it is thrown away and a new one loads the site; a third death in one visit
        // closes the browser. Without this Android kills K along with the renderer.
        var browserGeneration by remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
        val goneViews = remember(url) {
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
        }
        androidx.compose.runtime.key(browserGeneration) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val created = try { WebView(context) } catch (e: Exception) { null }
                if (created == null) {
                    webViewMissing = true
                    android.widget.FrameLayout(context)
                } else created.apply {
                    // its own web data, only while it's open (iOS `.nonPersistent()`, security
                    // audit 2026-11-27 S1): first, before anything else is done with the view
                    com.kachat.app.services.InAppBrowserData.attach(this, browserSession)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // before the first load, so the script is there at document start
                    siteWallet?.install(this)
                    reloader.webView = this
                    reloader.startUrl = url
                    pageView = this
                    webViewClient = InAppBrowserClient(
                        startUrl = url,
                        onPage = { now -> if (!now.startsWith("about:", ignoreCase = true)) currentUrl = now },
                        onLoading = { isLoading = it },
                        onStarted = { view, started -> siteWallet?.injectOnPageStarted(view, started) },
                        // signed in from K's own sheet before the page was there: tell the page now
                        onFinished = { siteWallet?.pageLoaded() },
                        onGone = { dead ->
                            goneViews.add(dead)
                            if (reloader.webView === dead) reloader.webView = null
                            // a sheet the dead page asked for is declined; the new page is answered
                            siteWallet?.close()
                            siteWallet?.reopen()
                            isLoading = true
                            if (browserGeneration < 2) browserGeneration++ else onClose()
                        },
                    )
                    loadUrl(url)
                }
            },
            // Closing the browser frees the page (its renderer, timers and media) instead of
            // leaving it to the garbage collector.
            onRelease = { view ->
                // the browser is gone: a sheet still up is declined, and the page gets no more
                // answers (iOS b49a021d `dismantleUIView`)
                val rebuilt = (view as? WebView)?.let { it in goneViews } == true
                if (!rebuilt) siteWallet?.close()
                (view as? WebView)?.let { webView ->
                    if (reloader.webView === webView) reloader.webView = null
                    if (pageView === webView) pageView = null
                    // its cookies, storage and cache go with it, then the view (S1); one whose
                    // renderer died leaves them to the view that replaces it (a sign-in stays)
                    com.kachat.app.services.InAppBrowserData.release(webView, keepData = rebuilt)
                }
            },
        )
        }
    }
    // a request the page makes while K's own sign-in sheet is up shows once that closes
    siteWallet?.let { SiteWalletSheetHost(it, held = showSignIn || signInWaiting) }
    if (showSignIn && siteWallet != null) {
        val signInHost = remember(url) { runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty() }
        SiteSignInSheet(siteWallet, host = signInHost, onDone = { showSignIn = false }, onFailed = { signInFailed = true })
    }
    if (signInFailed) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { signInFailed = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.kachat.app.R.string.audit_money_signin_failed_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.kachat.app.R.string.audit_money_signin_failed_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { signInFailed = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.kachat.app.R.string.ok), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

/**
 * [InAppBrowserScreen]'s navigation rules (iOS `InAppWebView.Coordinator`): the browser stays on
 * the site it opened ([startUrl]'s host and its subdomains, "www." aside). A link or redirect to
 * another http(s) site opens in the phone's browser - only for a tapped link or the very first
 * load's redirect, so a page's script can't keep throwing the person out - and is never shown
 * under this site's name. Frames inside the page load as they like; `about:` and `blob:` pages
 * are allowed, `data:` and every other scheme refused in the main frame. A plain-http page on the
 * site loads over https (security audit 2026-11-27 S4).
 */
private class InAppBrowserClient(
    startUrl: String,
    private val onPage: (String) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    /** A main-frame page started loading: the site wallet's script on a WebView without
     *  document-start scripts ([KaChatSiteWallet.injectOnPageStarted]). */
    private val onStarted: ((WebView, String?) -> Unit)? = null,
    /** A main-frame page finished loading ([KaChatSiteWallet.pageLoaded]). */
    private val onFinished: (() -> Unit)? = null,
    /** The page's renderer process died: this WebView is unusable and must be replaced. */
    private val onGone: ((WebView) -> Unit)? = null,
) : WebViewClient() {
    /** The site the browser was opened on, without a leading "www.". */
    private val siteHost: String = run {
        val host = runCatching { android.net.Uri.parse(startUrl).host }.getOrNull()?.lowercase().orEmpty()
        host.removePrefix("www.")
    }

    /** The last http page loaded over https instead ([InAppBrowserRules.httpsUpgrade]). */
    private var lastUpgrade: String? = null

    private fun isOnSite(target: android.net.Uri): Boolean {
        val host = target.host?.lowercase() ?: return false
        if (siteHost.isEmpty()) return false
        return host == siteHost || host.endsWith(".$siteHost")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
        val target = request.url
        // a frame inside the page (a captcha, an embed): it can't replace the page or its header
        if (!request.isForMainFrame) return false
        return when (target.scheme?.lowercase()) {
            "http", "https" -> {
                if (isOnSite(target)) {
                    if (target.scheme?.lowercase() == "https") return false
                    // plain http on the site itself: loaded over https instead, never in the clear
                    // under K's header (iOS's ATS refuses it; security audit 2026-11-27 S4). A
                    // site whose https page redirects straight back to http is refused.
                    val secure = InAppBrowserRules.httpsUpgrade(target.toString()) ?: return true
                    if (request.isRedirect && secure == lastUpgrade) return true
                    lastUpgrade = secure
                    view.loadUrl(secure)
                    return true
                }
                val firstLoad = view.copyBackForwardList().currentItem == null
                if (request.hasGesture() || firstLoad) {
                    try {
                        view.context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        // no browser on the phone: the link just doesn't open
                    }
                }
                true
            }
            "about", "blob" -> false
            else -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        onLoading(true)
        url?.let(onPage)
        onStarted?.invoke(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onLoading(false)
        (view.url ?: url)?.let(onPage)
        onFinished?.invoke()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let(onPage)
    }

    /** The renderer was killed or crashed: returning true keeps K alive; the screen drops this
     *  view (destroyed in its onRelease) and builds a new one, or closes ([onGone]). */
    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        android.util.Log.w("InAppBrowser", "page renderer gone (crash=${detail.didCrash()})")
        onGone?.invoke(view)
        return true
    }
}

/** The in-app browser's rules that need no WebView (unit-tested). */
internal object InAppBrowserRules {
    /** [url] (plain http) as https: the same host, path and query, the default port. Null for
     *  anything else, or an http URL on its own port (an https page isn't served there). */
    fun httpsUpgrade(url: String): String? {
        val uri = try { java.net.URI(url) } catch (e: Exception) { return null }
        if (uri.scheme?.lowercase() != "http" || uri.host.isNullOrEmpty()) return null
        if (uri.port != -1 && uri.port != 80) return null
        // no user:password@ carried over
        return "https://" + uri.host + (uri.rawPath ?: "") +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }
}

/**
 * Help hub - Settings > Help since iOS 148a5331 (it was Profile > Help), matching iOS's
 * ProfileHelpView: the Welcome Guide, replayable here, then the .kachat Names and kUSDT guides.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onWelcomeGuide: () -> Unit,
    /** KaPay's .kachat Names Guide (iOS 148a5331 `KachatSetupGuideView`), full screen. */
    onKachatNamesGuide: (() -> Unit)? = null,
    /** KaPay's kUSDT Guide (iOS 148a5331 `TokenGuideView`), pushed. */
    onKusdtGuide: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        // A large title, pushed inside the Profile tab (iOS ProfileHelpView,
        // `.navigationBarTitleDisplayMode(.large)`): the dock stays, like KaChat Bots.
        topBar = { MainPageHeader(title = androidx.compose.ui.res.stringResource(com.kachat.app.R.string.help), onBack = onBack, showStatus = false) },
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = LocalDockInset.current),
        ) {
            androidx.compose.material3.Surface(
                color = colors.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    HelpRow(
                        androidx.compose.material.icons.Icons.Default.AutoAwesome,
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.welcome_guide),
                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_welcome_body),
                        onWelcomeGuide,
                    )
                    // KaPay's two guides (iOS 148a5331, all-in-one), beside the Welcome Guide.
                    if (onKachatNamesGuide != null) {
                        androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))
                        HelpRow(
                            // iOS person.text.rectangle
                            androidx.compose.material.icons.Icons.Outlined.Badge,
                            androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_names_help_title),
                            androidx.compose.ui.res.stringResource(com.kachat.app.R.string.kg_names_help_body),
                            onKachatNamesGuide,
                        )
                    }
                    if (onKusdtGuide != null) {
                        androidx.compose.material3.HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))
                        HelpRow(
                            com.kachat.app.ui.screens.tokens.KusdtGuideHelpRow.icon,
                            com.kachat.app.ui.screens.tokens.KusdtGuideHelpRow.title(),
                            com.kachat.app.ui.screens.tokens.KusdtGuideHelpRow.subtitle(),
                            onKusdtGuide,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpRow(icon: ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // iOS helpRow: a 20pt icon in a 30pt frame, 14pt apart; subheadline semibold over caption.
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
    }
}

/**
 * "Claim Testnet Kaspa", on Wallet, testnet only (iOS 182ae68, 2a81767, bfe9f022).
 * The official TN10 faucet sits behind a Cloudflare check, so it can't be claimed in the
 * background: the button copies the chatting address in its `kaspatest:` form and opens the
 * faucet in the in-app browser, with a strip saying it's copied. The faucet allows one claim a
 * day, so once the chatting balance goes up after a visit the button locks for 24 hours (kept
 * per address, with a countdown). No rise leaves it free to try again.
 */
@Composable
fun TestnetFaucetClaimButton(
    address: String,
    balanceSompi: () -> Long,
    refreshBalance: suspend () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("kachat_tn10_faucet", android.content.Context.MODE_PRIVATE) }
    val key = "claimed_${address.lowercase()}"
    var claimedAt by remember(key) { mutableStateOf(prefs.getLong(key, 0L).takeIf { it > 0L }) }
    var showFaucet by remember { mutableStateOf(false) }
    var balanceBefore by remember { mutableStateOf<Long?>(null) }
    var checkingClaim by remember { mutableStateOf(false) }
    // TimelineView(.periodic(by: 60)): re-read the clock every minute for the countdown.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val until = claimedAt?.let { it + FAUCET_LOCK_MS }?.takeIf { it > now }
    val locked = until != null

    /** After the faucet closes: the payment usually lands within seconds, so watch the chatting
     *  balance for up to a minute. A rise means the claim went through - lock for 24 hours. */
    suspend fun checkForClaim() {
        val before = balanceBefore ?: return
        checkingClaim = true
        try {
            repeat(12) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(5_000L)
                runCatching { refreshBalance() }
                if (balanceSompi() > before) {
                    val at = System.currentTimeMillis()
                    claimedAt = at
                    now = at
                    prefs.edit().putLong(key, at).apply()
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    return
                }
            }
        } finally {
            checkingClaim = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(KaspaTeal.copy(alpha = if (locked) 0.06f else 0.15f))
            .clickable(enabled = !locked && !checkingClaim) {
                // The faucet's address field takes the TN10 form; the wallet already shows it
                // that way on testnet - this makes sure of it.
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.kachat.app.util.KaspaNetwork.reencode(address, "kaspatest")))
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                balanceBefore = balanceSompi()
                showFaucet = true
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                checkingClaim -> Icons.Default.HourglassTop
                locked -> Icons.Default.CheckCircle
                else -> Icons.Default.WaterDrop
            },
            contentDescription = null,
            tint = if (locked) colors.textSecondary else KaspaTeal,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.faucet_claim_title),
                color = if (locked) colors.textSecondary else colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    checkingClaim -> stringResource(R.string.faucet_waiting)
                    until != null -> stringResource(R.string.faucet_claimed_again_in, faucetRemaining(until - now))
                    else -> stringResource(R.string.faucet_claim_subtitle)
                },
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        if (!locked && !checkingClaim) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }

    if (showFaucet) {
        val close = {
            showFaucet = false
            scope.launch { checkForClaim() }
            Unit
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = close,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            InAppBrowserScreen(
                url = FAUCET_URL,
                title = "faucet-tn10.kaspanet.io",
                notice = stringResource(R.string.faucet_address_copied_notice),
                onClose = close,
            )
        }
    }
}

private const val FAUCET_URL = "https://faucet-tn10.kaspanet.io"
private const val FAUCET_LOCK_MS = 24L * 60 * 60 * 1000

/** "5h 12m" / "37m" - iOS's abbreviated DateComponentsFormatter, at least a minute. */
private fun faucetRemaining(ms: Long): String {
    val minutes = maxOf(1L, ms / 60_000L)
    return if (ms >= 3_600_000L) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
