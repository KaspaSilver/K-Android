package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kachat.app.R
import com.kachat.app.ui.screens.ActionSheetContainer
import com.kachat.app.ui.screens.ActionSheetRow
import com.kachat.app.ui.screens.IosToastHost
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaNetwork

/**
 * Buy Kaspa with Debit Card is offered on mainnet only: Trestly sells by card on mainnet (iOS
 * `DebitCardBuy.isOffered`). Elsewhere Buy goes straight to crypto.
 */
object DebitCardBuyOffer {
    fun isOffered(isTestnet: Boolean = KaspaNetwork.isTestnet): Boolean = !isTestnet
}

/** What Buy opens first: the chooser of crypto or debit card (mainnet), or crypto at once. */
internal enum class BuyOpening { CHOOSER, CRYPTO }

internal fun buyOpening(isTestnet: Boolean): BuyOpening =
    if (DebitCardBuyOffer.isOffered(isTestnet)) BuyOpening.CHOOSER else BuyOpening.CRYPTO

/**
 * The Wallet tab's Buy button, a half sheet (iOS 824a30bb `BuyKaspaSheet`): Buy Kaspa with Crypto
 * or with Debit Card. [debitCardSubtitle] is the card order's stage when there is one (as the
 * Wallet card it replaced showed); null shows what paying by card does. Each choice closes the
 * sheet itself ([onDismiss] is for a swipe or a tap outside).
 */
@Composable
fun BuyKaspaSheet(
    onDismiss: () -> Unit,
    onBuyWithCrypto: () -> Unit,
    onBuyWithDebitCard: () -> Unit,
    debitCardSubtitle: String?,
) {
    ActionSheetContainer(
        title = stringResource(R.string.buysell_buy_kaspa),
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.Outlined.CurrencyBitcoin,
            title = stringResource(R.string.buysell_buy_with_crypto),
            subtitle = stringResource(R.string.buysell_buy_with_crypto_sub),
            onClick = onBuyWithCrypto,
        )
        ActionSheetRow(
            icon = Icons.Outlined.CreditCard,
            title = stringResource(R.string.buysell_buy_with_debit_card),
            subtitle = debitCardSubtitle ?: stringResource(R.string.buysell_debit_card_sub),
            onClick = onBuyWithDebitCard,
        )
    }
}

/**
 * A funding card's Buy Kaspa flow is on screen (iOS 824a30bb `BuyKaspaFlowPresence`). The card's
 * hosts (the chat composers, the "not enough funds" popups) keep the card while it is: the card
 * goes the moment the account is funded, and the flow's screens (deposit, Purchase Complete, Add
 * to Portfolio) would close with it.
 */
object BuyKaspaFlowPresence {
    private var holders by mutableIntStateOf(0)

    /** Snapshot state: reading it in composition redraws when it changes. */
    val isOpen: Boolean get() = holders > 0

    /** Counts the caller as an open flow while [active] and in composition. */
    @Composable
    fun Hold(active: Boolean) {
        if (!active) return
        DisposableEffect(Unit) {
            holders++
            onDispose { holders-- }
        }
    }
}

/** The state of one [BuyKaspaFlow]: [open] starts it. */
@Stable
class BuyKaspaFlowState {
    internal var showSheet by mutableStateOf(false)
    internal var swapStart by mutableStateOf<SwapStart?>(null)
    /** The debit card's own flow is showing (iOS `buyKaspaCover`). */
    internal var debitCardOpen by mutableStateOf(false)
    /** The order the debit card's flow opens on (one still on its way), null for a new one. */
    internal var debitCardResume by mutableStateOf<com.kachat.app.services.tokens.CardBuy?>(null)

    val isOpen: Boolean get() = showSheet || swapStart != null || debitCardOpen

    /** The chooser on mainnet, straight to Buy Kaspa with Crypto elsewhere (iOS `BuyKaspaFlow`). */
    fun open(isTestnet: Boolean = KaspaNetwork.isTestnet) {
        when (buyOpening(isTestnet)) {
            BuyOpening.CHOOSER -> showSheet = true
            BuyOpening.CRYPTO -> swapStart = SwapStart.BUY
        }
    }
}

@Composable
fun rememberBuyKaspaFlowState(): BuyKaspaFlowState = remember { BuyKaspaFlowState() }

/**
 * Buy Kaspa from a chat's "Fund your chatting address" card (iOS 824a30bb `BuyKaspaFlow`,
 * `.buyKaspaFlow(isPresented:toChatting:)`): the half sheet of Buy Kaspa with Crypto or with Debit
 * Card on mainnet, straight to crypto elsewhere, and the screens each opens, full screen in their
 * own window (the card sits inside a chat's composer). [toChatting]: bought Kaspa is set to land on
 * the Chatting Address. Holds [BuyKaspaFlowPresence] while anything of it shows.
 */
@Composable
fun BuyKaspaFlow(state: BuyKaspaFlowState, toChatting: Boolean = false) {
    BuyKaspaFlowPresence.Hold(state.isOpen)
    val tracker = rememberCardBuyTracker()
    val swapService = androidx.hilt.navigation.compose.hiltViewModel<com.kachat.app.viewmodels.SwapViewModel>().service
    val followedBuy by tracker.buy.collectAsState()
    val openSwapForm: (com.kachat.app.services.tokens.TrestlyTarget, Long) -> Unit = { target, units ->
        swapService.prepareKusdtSell(target, units)
        state.swapStart = SwapStart.FORM
    }
    if (state.showSheet) {
        BuyKaspaSheet(
            onDismiss = { state.showSheet = false },
            onBuyWithCrypto = {
                state.showSheet = false
                state.swapStart = SwapStart.BUY
            },
            onBuyWithDebitCard = {
                state.showSheet = false
                DebitCardBuy.open(tracker, openSwap = openSwapForm, openPage = { resume ->
                    state.debitCardResume = resume
                    state.debitCardOpen = true
                })
            },
            debitCardSubtitle = DebitCardBuy.shownOrder(followedBuy)?.let { BuyKaspaStatus.line(it) },
        )
    }
    state.swapStart?.let { start ->
        FullScreenCoverDialog(onDismiss = { state.swapStart = null }) {
            SwapScreen(start = start, onDone = { state.swapStart = null }, buyToChatting = toChatting)
        }
    }
    if (state.debitCardOpen) {
        FullScreenCoverDialog(onDismiss = { state.debitCardOpen = false }) {
            BuyKaspaScreen(
                resume = state.debitCardResume,
                onClose = { state.debitCardOpen = false },
                // the kUSDT landed: Buy Kaspa with it takes the page's place
                onSwapToKaspa = { target, units ->
                    state.debitCardOpen = false
                    openSwapForm(target, units)
                },
                preferChatting = toChatting,
            )
        }
    }
}

/**
 * An iOS `.fullScreenCover` from somewhere that isn't a whole screen (a chat's funding card): its
 * own full-screen window, the app's background, a toast host of its own, back closes it.
 */
@Composable
fun FullScreenCoverDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        FullScreenDialogWindow()
        Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
            content()
            IosToastHost()
        }
    }
}

/** The dialog's window covers the whole screen and resizes for the keyboard, its content padding
 *  itself with the real insets (as KaPosts' full-screen dialogs do). */
@Composable
private fun FullScreenDialogWindow() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        }
    }
}
