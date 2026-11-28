package com.kachat.app.ui.screens

import com.kachat.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.util.showAddressCopiedToast

/**
 * The guide's steps (iOS 824a30bb, owner 2026-11-26): no Fund Your Chatting Address step ("5 Kaspa
 * lets you send around 2,500 messages" is on the fees step) and no Connect to a Node step (a
 * newcomer wouldn't know what it means, so K starts on the default node and the choice lives in
 * Settings > Connection Settings > Node). The last step shares the chatting address.
 */
private enum class WelcomeGuideStep { WELCOME, LANGUAGE, CURRENCY, FEES, ADDRESS_EXPLAINER, SHARE_ADDRESS }

/**
 * First-run guided walkthrough shown automatically right after an account is added — whether
 * created or imported (see `WalletViewModel.pendingWelcomeGuide`) — and replayable any time from
 * the Profile screen. One screen driven by a step enum ([WelcomeGuideStep]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeGuideScreen(
    walletViewModel: WalletViewModel,
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    /**
     * Explicit presentation context, supplied by the presenter and NEVER inferred from persisted
     * markers (matches iOS): true only for auto-presented onboarding runs (create AND import,
     * including re-presentations of an interrupted run) — those are fully unskippable end to
     * end, from Welcome through Finish. Help replays leave this false and keep Skip and
     * back-dismiss.
     */
    isOnboardingRun: Boolean = false,
    onFinished: () -> Unit
) {
    var step by remember { mutableStateOf(WelcomeGuideStep.WELCOME) }
    val chattingAddress by walletViewModel.address.collectAsState()
    val justImportedWallet by walletViewModel.justImportedWallet.collectAsState()

    // Skip gating: an onboarding run is unskippable END TO END (top-bar Skip hidden and system back
    // swallowed until Finish); a replay skips any time.
    val canSkip = !isOnboardingRun
    androidx.activity.compose.BackHandler(enabled = !canSkip) {
        // Swallowed - onboarding runs must reach Finish.
    }

    // One step back. Null at Welcome so the shared button row renders Previous disabled there.
    val goBack: (() -> Unit)? = if (step == WelcomeGuideStep.WELCOME) null else {
        { step = WelcomeGuideStep.entries[step.ordinal - 1] }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            // Nothing to put in the bar on an onboarding run: just iOS's 44 under the status bar.
            if (!canSkip) Spacer(Modifier.statusBarsPadding().height(44.dp))
            else TopAppBar(
                title = {},
                navigationIcon = {
                    // Skip exists only on replays - an onboarding run has no way out until
                    // Finish (back-dismiss swallowed too).
                    if (canSkip) {
                        SheetBarButton(stringResource(R.string.skip), onClick = onFinished, modifier = Modifier.padding(start = 12.dp))
                    }
                    // Previous-step navigation lives in the fixed bottom button row next
                    // to Next (see WelcomeGuideButtonRow) - only skipping forward stays
                    // forbidden.
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        containerColor = LocalAppColors.current.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                WelcomeGuideStep.WELCOME -> WelcomeGuideStepScaffold(
                    icon = Icons.Default.WavingHand,
                    title = stringResource(R.string.welcome_to_kachat),
                    body = stringResource(R.string.lets_walk_through_the_basics_so),
                    buttonLabel = stringResource(R.string.next),
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.LANGUAGE }
                )
                WelcomeGuideStep.LANGUAGE -> WelcomeGuideLanguageStep(
                    walletViewModel = walletViewModel,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.CURRENCY }
                )
                WelcomeGuideStep.CURRENCY -> WelcomeGuideCurrencyStep(
                    walletViewModel = walletViewModel,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.FEES }
                )
                WelcomeGuideStep.FEES -> WelcomeGuideFeesStep(
                    // "Change Chatting Address" is offered ONLY on import onboarding runs: a
                    // freshly created wallet has nothing but index 0, and a Help replay must never
                    // let the user re-pick an identity behind their existing conversations. It
                    // lived on the funding step until that step left (iOS 824a30bb).
                    walletViewModel = walletViewModel,
                    canChangeChattingAddress = isOnboardingRun && justImportedWallet,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.ADDRESS_EXPLAINER }
                )
                WelcomeGuideStep.ADDRESS_EXPLAINER -> WelcomeGuideAddressExplainerStep(
                    chattingAddress = chattingAddress,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.SHARE_ADDRESS }
                )
                // The guide's final step (iOS 824a30bb): share the chatting address so people
                // can reach you.
                WelcomeGuideStep.SHARE_ADDRESS -> WelcomeGuideShareStep(
                    chattingAddress = chattingAddress,
                    onPrevious = goBack,
                    onNext = {
                        // Reaching Finish is what completes an onboarding run — clear the
                        // persisted re-presentation marker (no-op on replays, which never set it).
                        walletViewModel.clearOnboardingWizardPending()
                        onFinished()
                    }
                )
            }
        }
    }
    }
}

@Composable
private fun WelcomeGuideStepScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    buttonLabel: String,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                body,
                color = LocalAppColors.current.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext, nextLabel = buttonLabel)
    }
}

/**
 * The fixed two-button row every guide step ends with: Previous on the left, Next (or Finish)
 * on the right, always in the exact same position so the guide can be tapped through without
 * chasing buttons (matches iOS WelcomeGuideView.guideBottomBar). On the Welcome step
 * [onPrevious] is null and Previous renders disabled and dimmed rather than vanishing, so
 * Next never shifts.
 */
@Composable
private fun WelcomeGuideButtonRow(
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
    nextLabel: String = stringResource(R.string.next),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onPrevious?.invoke() },
            enabled = onPrevious != null,
            shape = RoundedCornerShape(14.dp),
            // iOS systemGray5, the whole button at 0.35 on the first step.
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalAppColors.current.incomingBubble,
                disabledContainerColor = LocalAppColors.current.incomingBubble.copy(alpha = 0.35f)
            ),
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text(
                stringResource(R.string.previous),
                color = LocalAppColors.current.textPrimary.copy(alpha = if (onPrevious != null) 1f else 0.35f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = onNext,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text(nextLabel, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Picking a language here applies it immediately via [applyAppLanguage], which recreates the
 * Activity (see [applyAppLanguage]'s doc). [WalletViewModel.markPendingWelcomeGuide] is re-armed
 * first so the guide automatically restarts from the beginning - this time actually rendered in
 * the new language - once the recreated Activity's `MainShell` observes the flag again.
 */
@Composable
private fun WelcomeGuideLanguageStep(walletViewModel: WalletViewModel, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    val current = currentAppLanguage()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.choose_your_language),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.select_the_language_youd_like_to),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable {
                            // No-op re-tap of the already-active language (most commonly "System",
                            // the pre-checked top row) must not re-arm the restart flag below:
                            // setApplicationLocales() only recreates the Activity on an actual
                            // locale change, so with no change this composable never gets torn
                            // down to consume the flag itself - it would loop this same still-alive
                            // MainShell back to a fresh "welcome_guide" destination on every tap,
                            // resetting the wizard to WELCOME with no way to progress past here.
                            if (language != current) {
                                walletViewModel.markPendingWelcomeGuide()
                                applyAppLanguage(language)
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (language == AppLanguage.SYSTEM) stringResource(R.string.system_default) else language.displayName,
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (current == language) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

/**
 * Only affects Portfolio's live KAS price/value display (see `CoinGeckoApi`/`PortfolioRepository`)
 * - unlike language, this takes effect immediately with no Activity recreation, so the guide
 * simply continues to Fees on tap with no restart/reload concern.
 */
@Composable
private fun WelcomeGuideCurrencyStep(walletViewModel: WalletViewModel, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    val currencyCode by walletViewModel.currency.collectAsState()
    val current = AppCurrency.fromCode(currencyCode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.choose_your_currency),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.select_the_currency_youd_like_prices),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            AppCurrency.entries.forEach { currency ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable { walletViewModel.setCurrency(currency.code) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        currency.displayName,
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (current == currency) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

/**
 * How K Uses Kaspa (iOS 824a30bb): the fees, with "5 Kaspa lets you send around 2,500 messages",
 * and on an import's onboarding run, Change Chatting Address (an imported seed may hold its real
 * identity at a nonzero index). The picker takes over the step while open and pops back here on
 * Back or after a switch.
 */
@Composable
private fun WelcomeGuideFeesStep(
    walletViewModel: WalletViewModel,
    canChangeChattingAddress: Boolean,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit
) {
    var showChattingAddressPicker by remember { mutableStateOf(false) }
    if (showChattingAddressPicker) {
        ChattingAddressPickerScreen(
            walletViewModel = walletViewModel,
            onBack = { showChattingAddressPicker = false },
            onSwitched = { showChattingAddressPicker = false }
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.how_kachat_uses_kaspa),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.welcome2_fees_body),
                color = LocalAppColors.current.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (canChangeChattingAddress) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    walletViewModel.resetChattingAddressScan()
                    showChattingAddressPicker = true
                }) {
                    Icon(Icons.Outlined.Badge, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.change_chatting_address), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

/**
 * Share Your Chatting Address, the guide's last step (iOS 824a30bb `shareAddressStep`): its QR,
 * the address, Share (the system share sheet) and Copy Share Link, as Profile's Share Profile has
 * them (the same kachat.app link), then Finish.
 */
@Composable
private fun WelcomeGuideShareStep(chattingAddress: String?, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    val colors = LocalAppColors.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val address = chattingAddress.orEmpty()
    val shareUrl = remember(address) { if (address.isEmpty()) null else KaChatLink.profileWebUrl(address) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.IosShare, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.welcome2_share_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.welcome2_share_body),
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (address.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp)
                        .size(180.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = rememberQrBitmapPainter(address),
                        contentDescription = stringResource(R.string.qr_code),
                        modifier = Modifier.size(180.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    address,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            if (shareUrl != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(KaspaTeal)
                            .clickable {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareUrl)
                                }
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.IosShare, contentDescription = null, tint = Color.Black, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.share), color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(KaspaTeal.copy(alpha = 0.15f))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(shareUrl))
                                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                IosToasts.show(context.getString(R.string.share_link_copied))
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.copy_share_link), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext, nextLabel = stringResource(R.string.finish))
    }
}

@Composable
private fun WelcomeGuideAddressExplainerStep(chattingAddress: String?, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.welcome_your_chatting_address),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.welcome_chatting_address_phone_number),
            fontSize = 15.sp,
            color = LocalAppColors.current.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        AddressMockRow(
            icon = Icons.Filled.Forum,
            title = stringResource(R.string.welcome_how_people_reach_you),
            address = chattingAddress.orEmpty(),
            caption = stringResource(R.string.welcome_how_people_reach_you_caption)
        )
        Spacer(Modifier.height(12.dp))
        // a username makes the address easy to find (iOS 824a30bb)
        AddressMockRow(
            icon = Icons.Filled.AlternateEmail,
            title = stringResource(R.string.welcome2_get_username),
            address = "",
            caption = stringResource(R.string.welcome2_get_username_caption)
        )
        Spacer(Modifier.height(12.dp))
        AddressMockRow(
            icon = Icons.AutoMirrored.Filled.Send,
            title = stringResource(R.string.welcome_paying_for_messages),
            address = "",
            caption = stringResource(R.string.welcome_paying_for_messages_caption)
        )
        Spacer(Modifier.height(12.dp))
        AddressMockRow(
            icon = Icons.Filled.Visibility,
            title = stringResource(R.string.welcome_what_others_can_see),
            address = "",
            caption = stringResource(R.string.shell54_what_others_can_see_caption)
        )
    }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

@Composable
private fun AddressMockRow(icon: ImageVector, title: String, address: String, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.width(28.dp).height(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.textPrimary, fontSize = 15.sp)
            if (address.isNotEmpty()) {
                MiddleEllipsisText(
                    address,
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
    }
}
