package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.PaymentRequest
import com.kachat.app.services.AddressBookManager
import com.kachat.app.services.NameResolution
import com.kachat.app.services.NameServiceTLD
import com.kachat.app.services.NameServicesClient
import com.kachat.app.services.PaymentRequestWallet
import com.kachat.app.services.tokens.KusdtTransfer
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.AddressResolutionCard
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.KachatFitText
import com.kachat.app.ui.screens.KaspaKeypad
import com.kachat.app.ui.screens.KeypadAmountText
import com.kachat.app.ui.screens.MiddleEllipsisText
import com.kachat.app.ui.screens.QrScannerSheet
import com.kachat.app.ui.screens.SendRecipientCard
import com.kachat.app.ui.screens.SendStepChip
import com.kachat.app.ui.screens.SendStepQuestion
import com.kachat.app.ui.screens.SendStepScreen
import com.kachat.app.ui.screens.SendStepTransition
import com.kachat.app.ui.screens.SentTransaction
import com.kachat.app.ui.screens.SpendingAddressSendFlow
import com.kachat.app.ui.screens.SwipeUpToSendScreen
import com.kachat.app.ui.screens.amount
import com.kachat.app.ui.screens.rememberDismissSystemKeyboard
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Send a token (iOS 5b7c772c `Views/Tokens/TokenSendView.swift`), in Send Kaspa's steps and shared
// pieces (SendKaspaComponents.kt): To (an address or a name, Paste, Scan QR, Contacts), From (which
// of your addresses holding it: the Chatting Address or a regular spending address; skipped when
// only one holds it), How much (on the app's keypad, in the token), then the review with swipe up.
// The KAS it takes (the 1.5 KAS that goes with the new coin, and the network fee) comes from the
// same address, so the send links no other address of yours.

/** The steps, in order (iOS `TokenSendView.Step`). */
internal enum class TokenSendStep { RECIPIENT, FROM, AMOUNT, REVIEW }

/**
 * Send a token (iOS `TokenSendView(token:startFrom:prefillRecipient:prefillName:)`). [startFrom]:
 * sent from this address (its own page's Send), no From step. [prefillRecipient]: the To already
 * filled in (Manage kUSDT's Send to Chatting Address), so it opens on How much (on From when more
 * than one address could send), [prefillName] shown in place of the address; Back still reaches
 * the To step to change it. Presents itself (a sheet); [onDismiss] once it has gone.
 */
@Composable
fun TokenSendScreen(
    token: WalletToken,
    startFrom: TokenHolding? = null,
    prefillRecipient: String? = null,
    prefillName: String? = null,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        TokenSendContent(
            token = token,
            startFrom = startFrom,
            prefillRecipient = prefillRecipient,
            prefillName = prefillName,
            chatRecipient = null,
            chatName = null,
            onSent = null,
            onClose = close,
            tokenViewModel = tokenViewModel,
            walletViewModel = walletViewModel,
        )
    }
}

/**
 * From a 1:1 chat's Send Dollars (iOS `TokenSendView(token:chatRecipient:name:onSent:)`,
 * 2026-11-20): the recipient is the chat, so there is no To step, as in the chat's Send KAS sheet.
 * It opens on From with a "To <name>" chip. The chat's payment bubble and its
 * `token_payment_notice` are made by [TokenViewModel.send] (`chatContact`), also when a "Not
 * confirmed" send lands later; [onSent] (txid, the recipient coin's output, the amount) is told
 * as well. Presents itself; [onDismiss] once it has gone.
 */
@Composable
fun TokenSendScreen(
    token: WalletToken,
    chatRecipient: String,
    name: String,
    onSent: (txId: String, index: Int, amount: Long) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        TokenSendContent(
            token = token,
            startFrom = null,
            prefillRecipient = null,
            prefillName = null,
            chatRecipient = chatRecipient,
            chatName = name,
            onSent = onSent,
            onClose = close,
            tokenViewModel = tokenViewModel,
            walletViewModel = walletViewModel,
        )
    }
}

/** The steps themselves, for [TokenSendScreen] and [SendAssetChooser] (which shows them in its
 *  own sheet). [onClose]: Cancel, or Done after the send. */
@Composable
internal fun TokenSendContent(
    token: WalletToken,
    startFrom: TokenHolding?,
    prefillRecipient: String?,
    prefillName: String?,
    chatRecipient: String?,
    chatName: String?,
    onSent: ((String, Int, Long) -> Unit)?,
    onClose: () -> Unit,
    tokenViewModel: TokenViewModel,
    walletViewModel: WalletViewModel,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val dismissKeyboard = rememberDismissSystemKeyboard()

    /** The recipient is fixed (a chat): no To step to go back to. */
    val recipientLocked = chatRecipient != null
    var step by remember {
        mutableStateOf(
            when {
                chatRecipient != null -> TokenSendStep.FROM
                prefillRecipient != null -> if (startFrom != null) TokenSendStep.AMOUNT else TokenSendStep.FROM
                else -> TokenSendStep.RECIPIENT
            }
        )
    }

    // To
    var addressInput by remember { mutableStateOf(chatRecipient ?: prefillRecipient.orEmpty()) }
    var resolvedAddress by remember { mutableStateOf<String?>(null) }
    var resolvedName by remember { mutableStateOf(if (chatRecipient != null) chatName else if (prefillRecipient != null) prefillName else null) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var isResolving by remember { mutableStateOf(false) }
    var nameResolutions by remember { mutableStateOf<List<NameResolution>>(emptyList()) }
    var selectedTld by remember { mutableStateOf<NameServiceTLD?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }

    // From
    var fromId by remember { mutableStateOf<String?>(null) }

    // How much
    var amountText by remember { mutableStateOf("") }

    // Review
    var isSending by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    /** The transfer, built and signed when the review opens: the review shows its exact fee, and
     *  the swipe sends it. */
    var prepared by remember { mutableStateOf<KusdtTransfer.Prepared?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<SentTransaction?>(null) }
    /** The last submit's answer was lost: Try Again sends the same signed transaction (same id,
     *  so it can't pay twice), never a rebuilt one. */
    var outcomeUnknown by remember { mutableStateOf(false) }
    /** The sending address needs KAS (`Failure.NotEnoughKas`): about how much, for Fund This Address. */
    var fundShortfall by remember { mutableStateOf<Long?>(null) }
    /** Part of the shortfall is the 1.5 KAS of your change coin (a partial send). */
    var fundForChangeCoin by remember { mutableStateOf(false) }
    /** Fund This Address: Send Kaspa with a request for the shortfall to the sending address. */
    var fundingRequest by remember { mutableStateOf<PaymentRequest?>(null) }
    /** The funding send went out: the review is built again once the KAS has landed. */
    var funded by remember { mutableStateOf(false) }
    /** Waiting for the KAS a funding send put on its way (the review checks every few seconds). */
    var awaitingFunds by remember { mutableStateOf(false) }
    /** KAS was sent to this address for this send and hasn't shown up yet: the review offers Check
     *  Again rather than another top-up, so nobody pays it twice. */
    var fundingSent by remember { mutableStateOf(false) }

    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val isDiscovering by tokenViewModel.isDiscovering.collectAsState()
    val isRefreshing by tokenViewModel.isRefreshing.collectAsState()
    val explorer by tokenViewModel.kaspaExplorer.collectAsState()
    val chattingAddress by walletViewModel.address.collectAsState()
    val chattingBalance by walletViewModel.balanceSompi.collectAsState()

    val trimmedInput = addressInput.trim()
    val typedAddressIsValid = KaspaAddress.isValidOnActiveNetwork(lowercasedKaspaAddress(trimmedInput))
    /** Where it goes: a resolved name's address, or a valid typed one. */
    val recipient: String? = resolvedAddress ?: if (typedAddressIsValid) lowercasedKaspaAddress(trimmedInput) else null
    val recipientIsScript = isTokenScriptAddress(recipient)
    val canContinueFromRecipient = recipient != null && !recipientIsScript && !isResolving

    val holdings = remember(holdingsMap, token) { tokenViewModel.holdings(token) }
    /** The address it's sent from: the pick, or the only one holding it. Never another one in the
     *  pick's place if it drops off the list. */
    val from: TokenHolding? = when {
        fromId != null -> holdings.firstOrNull { it.id == fromId }
        startFrom != null -> holdings.firstOrNull { it.id == startFrom.id } ?: startFrom
        holdings.size == 1 -> holdings[0]
        else -> null
    }
    /** Only one address holds it, or the send started from one: no From step. */
    val skipsFrom = startFrom != null || holdings.size <= 1
    val balance = from?.units ?: 0L
    val amountUnits = TokenAmount.unitsFromTyped(amountText, token)
    val amountTooHigh = (amountUnits ?: 0L) > balance
    val fromLabel = from?.let { rememberHoldingLabel(it, tokenViewModel) }
    // What's on screen now, for a build that finishes after an await (iOS reads `self.from`).
    val latestFrom by rememberUpdatedState(from)
    val latestRecipient by rememberUpdatedState(recipient)
    val latestAmount by rememberUpdatedState(amountUnits)
    val screenTitle = stringResource(R.string.token_send_title, token.symbol)

    fun go(next: TokenSendStep) {
        dismissKeyboard()
        // leaving the review: what it built belongs to the old amount and recipient
        if (step == TokenSendStep.REVIEW || next == TokenSendStep.REVIEW) {
            prepared = null
            prepareError = null
            failureMessage = null
            outcomeUnknown = false
        }
        step = next
    }

    fun select(resolution: NameResolution) {
        val address = resolution.address
        if (address == null || !KaspaAddress.isValidOnActiveNetwork(address)) {
            resolvedAddress = null
            resolvedName = null
            selectedTld = null
            lookupError = NameServicesClient.notFoundMessage(context, resolution.display, emptyList())
            return
        }
        resolvedAddress = address
        resolvedName = resolution.display
        selectedTld = resolution.tld
        lookupError = null
    }

    // Recipient lookup (Send Kaspa's rules)
    fun handleInputChange(value: String) {
        val text = value.trim()
        // An upper-case address goes into the field in lower case.
        val lowered = lowercasedKaspaAddress(text)
        if (lowered != text) {
            addressInput = lowered
            handleInputChange(lowered)
            return
        }
        resolveJob?.cancel()
        resolvedAddress = null
        resolvedName = null
        lookupError = null
        nameResolutions = emptyList()
        selectedTld = null
        isResolving = false
        if (text.isEmpty()) return
        if (text.lowercase().startsWith("kaspa:") || text.lowercase().startsWith("kaspatest:")) return
        // A name, with or without its ending ("kaspasilver" looks up .kachat first), the way Send
        // Kaspa decides: every name service, after a short pause in the typing.
        if (!NameServicesClient.looksLikeName(text)) return
        isResolving = true
        resolveJob = scope.launch {
            delay(300)
            if (addressInput.trim() != text) return@launch
            val results = walletViewModel.resolveEverywhere(text)
            if (addressInput.trim() != text) return@launch
            nameResolutions = results
            isResolving = false
            val primary = NameServicesClient.primary(results, text)
            if (primary?.address != null) select(primary)
            else lookupError = NameServicesClient.notFoundMessage(context, text, results)
        }
    }

    fun onInput(value: String) {
        addressInput = value
        handleInputChange(value)
    }

    /** Builds and signs the transfer for what's on the review (no network yet but the coin reads). */
    suspend fun prepare() {
        val f = latestFrom
        val r = latestRecipient
        val a = latestAmount
        if (f == null || r == null || a == null || isPreparing) return
        isPreparing = true
        prepareError = null
        try {
            val label = resolvedName ?: AddressBookManager.shared?.entry(r)?.customName
            val result = tokenViewModel.prepare(f, r, a, label)
            val built = result.getOrNull()
            if (built != null) {
                // From, To or the amount changed while it was built (Back, edit, Review again):
                // that build is for another send; build this one instead
                if (!KusdtTransfer.matches(built, latestFrom?.id, latestRecipient, latestAmount)) {
                    prepared = null
                    isPreparing = false
                    if (step == TokenSendStep.REVIEW) prepare()
                    return
                }
                prepared = built
                fundShortfall = null
                fundingSent = false
            } else {
                val e = result.exceptionOrNull()
                prepared = null
                if (e is KusdtTransfer.Failure.NotEnoughKas) {
                    fundShortfall = e.short
                    fundForChangeCoin = e.forChangeCoin
                    prepareError = e.message
                } else {
                    fundShortfall = null
                    prepareError = e?.message ?: context.getString(R.string.kusdt_build_failed)
                }
            }
        } finally {
            isPreparing = false
        }
    }

    /** Funding closed: built again until the KAS has landed on the sending address. After a send,
     *  for up to a minute, showing "Waiting for your KAS" (no second top-up button); after a
     *  swipe-away, a couple of looks in case it was sent. */
    suspend fun prepareAfterFunding(didSend: Boolean) {
        awaitingFunds = didSend
        try {
            val attempts = if (didSend) 20 else 3
            for (attempt in 0 until attempts) {
                if (attempt > 0) delay(3_000)
                while (isPreparing) delay(200)
                prepare()
                if (prepared != null || fundShortfall == null) return
            }
        } finally {
            awaitingFunds = false
        }
    }

    /** Send Kaspa, already filled in: the sending address and the shortfall, as a request signed by
     *  your Chatting Address (so it reads as yours), with a note saying what it's for. */
    fun openFunding(amount: Long) {
        val f = from ?: return
        if (chattingAddress == null) return
        val memo = context.getString(R.string.token_kas_for_send, token.symbol)
        scope.launch {
            val unsigned = PaymentRequest.create(f.address, amount, memo, toChattingAddress = f.isChatting)
            fundingRequest = withContext(Dispatchers.Default) { PaymentRequestWallet.shared?.sign(unsigned) ?: unsigned }
        }
    }

    fun send() {
        if (isSending) return
        failureMessage = null
        isSending = true
        scope.launch {
            try {
                val reviewed = prepared
                if (reviewed == null) {
                    failureMessage = prepareError ?: context.getString(R.string.kusdt_build_failed)
                    return@launch
                }
                // A review left open a while is built again from the coins as they are now, and
                // only the build for what the review shows goes (TokenViewModel.send).
                when (
                    val outcome = tokenViewModel.send(
                        reviewed = reviewed,
                        outcomeUnknown = outcomeUnknown,
                        fromId = from?.id,
                        recipient = recipient,
                        amount = amountUnits,
                        chatContact = chatRecipient,
                        token = token,
                    )
                ) {
                    is TokenViewModel.SendOutcome.Sent -> {
                        // the recipient's coin is output 0 (`KusdtTransfer.Prepared.recipient`)
                        onSent?.invoke(outcome.txid, 0, outcome.prepared.amount)
                        outcomeUnknown = false
                        prepared = outcome.prepared
                        haptic(IosHaptic.SUCCESS)
                        sent = SentTransaction(
                            txId = outcome.txid,
                            amountSompi = null,
                            recipient = "${TokenAmount.amount(outcome.prepared.amount, token)} → ${resolvedName ?: recipient.orEmpty()}",
                        )
                    }
                    is TokenViewModel.SendOutcome.NotConfirmed -> {
                        // It may have landed: Try Again resends this same transaction.
                        prepared = outcome.prepared
                        outcomeUnknown = true
                        failureMessage = outcome.message
                    }
                    is TokenViewModel.SendOutcome.Changed -> {
                        prepared = outcome.rebuilt
                        failureMessage = outcome.message
                    }
                    is TokenViewModel.SendOutcome.Failed -> {
                        outcome.rebuilt?.let { prepared = it }
                        failureMessage = outcome.message
                    }
                }
            } finally {
                isSending = false
            }
        }
    }

    fun handleScanned(code: String) {
        var address = code.trim()
        if (address.lowercase().startsWith("kaspa:") || address.lowercase().startsWith("kaspatest:")) {
            address = lowercasedKaspaAddress(address.substringBefore('?'))
        }
        onInput(address)
    }

    // The review builds as it opens (iOS onAppear, not `.task`: the build runs to its end).
    LaunchedEffect(step) {
        if (step == TokenSendStep.REVIEW && prepared == null && sent == null && !isPreparing) prepare()
    }

    SendStepTransition(step = step, order = { it.ordinal }, modifier = Modifier.fillMaxSize()) { shown ->
        when (shown) {
            TokenSendStep.RECIPIENT -> SendStepScreen(
                title = screenTitle,
                onCancel = onClose,
                canContinue = canContinueFromRecipient,
                onContinue = { go(if (skipsFrom) TokenSendStep.AMOUNT else TokenSendStep.FROM) },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    SendStepQuestion(stringResource(R.string.send_step_who_to))
                    SendRecipientCard(
                        input = addressInput,
                        onInputChange = ::onInput,
                        isResolving = isResolving,
                        resolvedAddress = resolvedAddress,
                        resolvedName = resolvedName,
                        lookupError = lookupError,
                        isValidAddress = typedAddressIsValid,
                        onScan = { showScanner = true },
                        nameResolutions = nameResolutions,
                        selectedTld = selectedTld,
                        onSelectResolution = ::select,
                        focusOnAppear = true,
                    )
                    if (recipientIsScript) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Report, contentDescription = null, tint = colors.danger, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.token_script_address_error, token.symbol), color = colors.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            TokenSendStep.FROM -> SendStepScreen(
                title = screenTitle,
                onCancel = onClose,
                canContinue = from != null,
                onContinue = { go(TokenSendStep.AMOUNT) },
                onBack = if (recipientLocked) null else ({ go(TokenSendStep.RECIPIENT) }),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    if (recipientLocked && resolvedName != null) {
                        SendStepChip(stringResource(R.string.to), resolvedName.orEmpty())
                    }
                    SendStepQuestion(stringResource(R.string.send_step_where_from))
                    holdings.forEach { holding ->
                        TokenFromRow(
                            token = token,
                            holding = holding,
                            selected = from?.id == holding.id,
                            tokenViewModel = tokenViewModel,
                        ) {
                            haptic(IosHaptic.IMPACT_LIGHT)
                            fromId = holding.id
                            amountText = ""
                        }
                    }
                    if (holdings.isEmpty() && (isDiscovering || isRefreshing)) {
                        // Send Dollars' check of every address is still going
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IosActivityIndicator(modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.token_checking_addresses_for, token.symbol), color = colors.textSecondary, fontSize = 15.sp)
                        }
                    } else if (holdings.isEmpty()) {
                        Text(stringResource(R.string.token_none_of_your_addresses, token.symbol), color = colors.textSecondary, fontSize = 15.sp)
                        // buy some here: Swap Stablecoins (Trestly's site, inside KaChat since iOS 148a5331)
                        SwapStablecoinsButton(walletViewModel)
                    }
                    Text(stringResource(R.string.token_fee_same_address_note), color = colors.textSecondary, fontSize = 13.sp)
                }
            }

            TokenSendStep.AMOUNT -> SendStepScreen(
                title = screenTitle,
                onCancel = onClose,
                canContinue = amountUnits != null && !amountTooHigh,
                onContinue = { go(TokenSendStep.REVIEW) },
                onBack = { go(if (recipientLocked || !skipsFrom) TokenSendStep.FROM else TokenSendStep.RECIPIENT) },
                continueTitle = stringResource(R.string.review),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp),
                ) {
                    SendStepQuestion(stringResource(R.string.pr_step_how_much))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (recipient != null) {
                            Box(Modifier.weight(1f, fill = false)) {
                                SendStepChip(stringResource(R.string.to), resolvedName ?: recipient, isAddress = resolvedName == null)
                            }
                        }
                        if (from != null) {
                            Box(Modifier.weight(1f, fill = false)) {
                                SendStepChip(stringResource(R.string.from), fromLabel.orEmpty())
                            }
                        }
                    }
                }
                TokenKeypadAmountEntry(
                    token = token,
                    text = amountText,
                    onTextChange = { amountText = it },
                    footnote = if (amountTooHigh) stringResource(R.string.token_more_than_your, token.symbol)
                    else stringResource(R.string.token_available, TokenAmount.amount(balance, token)),
                    footnoteIsError = amountTooHigh,
                    onMax = { amountText = TokenAmount.typed(balance, token) },
                    modifier = Modifier.weight(1f),
                )
            }

            TokenSendStep.REVIEW -> SwipeUpToSendScreen(
                screenTitle = stringResource(R.string.review),
                onCancel = onClose,
                onBack = { go(TokenSendStep.AMOUNT) },
                title = stringResource(R.string.swipe_up_to_send),
                isBusy = isSending,
                isEnabled = prepared?.let { KusdtTransfer.matches(it, from?.id, recipient, amountUnits) } == true &&
                    !isPreparing && !amountTooHigh,
                amountSompi = null,
                recipient = resolvedName ?: recipient,
                sent = sent,
                failureMessage = failureMessage,
                explorer = explorer,
                onDone = onClose,
                action = ::send,
            ) {
                TokenSendReview(
                    token = token,
                    amountUnits = amountUnits,
                    recipient = recipient,
                    resolvedName = resolvedName,
                    fromLabel = fromLabel,
                    prepared = prepared,
                    isPreparing = isPreparing,
                    prepareError = prepareError,
                    awaitingFunds = awaitingFunds,
                    fundingSent = fundingSent,
                    fundShortfall = fundShortfall,
                    fundForChangeCoin = fundForChangeCoin,
                    onCheckAgain = {
                        haptic(IosHaptic.IMPACT_LIGHT)
                        scope.launch { prepareAfterFunding(true) }
                    },
                    onFund = { amount ->
                        haptic(IosHaptic.IMPACT_LIGHT)
                        openFunding(amount)
                    },
                    onTryAgain = { scope.launch { prepare() } },
                )
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onScanned = { code ->
                showScanner = false
                handleScanned(code)
            },
            onDismiss = { showScanner = false },
        )
    }

    val request = fundingRequest
    val fundFrom = chattingAddress
    if (request != null && fundFrom != null) {
        IosFullSheet(
            onDismissed = {
                // closed by Done after the send, or swiped away (maybe after sending): look either way
                fundingRequest = null
                val didSend = funded
                funded = false
                if (didSend) fundingSent = true
                scope.launch { prepareAfterFunding(didSend) }
            },
            swipeToDismiss = false,
        ) { close ->
            SpendingAddressSendFlow(
                fromAddress = fundFrom,
                balanceSompi = chattingBalance,
                title = stringResource(R.string.chats_send_kaspa),
                viewModel = walletViewModel,
                onDone = close,
                paymentRequest = request,
                onComplete = { funded = true },
            )
        }
    }
}

/** One address on the From step: the check, its name and address, and how much it holds. */
@Composable
private fun TokenFromRow(
    token: WalletToken,
    holding: TokenHolding,
    selected: Boolean,
    tokenViewModel: TokenViewModel,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val label = rememberHoldingLabel(holding, tokenViewModel)
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(18.dp)
            .then(if (selected) Modifier.border(1.5.dp, KaspaTeal, shape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) KaspaTeal else colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(label, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            MiddleEllipsisText(holding.address, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            TokenAmount.amount(holding.units, token),
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** The review's body (iOS `reviewStep`'s content): what's sent, to whom, from where, the KAS it
 *  takes, and what to do when the address needs KAS. */
@Composable
private fun ColumnScope.TokenSendReview(
    token: WalletToken,
    amountUnits: Long?,
    recipient: String?,
    resolvedName: String?,
    fromLabel: String?,
    prepared: KusdtTransfer.Prepared?,
    isPreparing: Boolean,
    prepareError: String?,
    awaitingFunds: Boolean,
    fundingSent: Boolean,
    fundShortfall: Long?,
    fundForChangeCoin: Boolean,
    onCheckAgain: () -> Unit,
    onFund: (Long) -> Unit,
    onTryAgain: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.send_youre_sending), color = colors.textSecondary, fontSize = 15.sp)
            if (amountUnits != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TokenIcon(token, size = 34.dp)
                    KachatFitText(
                        TokenAmount.amount(amountUnits, token),
                        color = colors.textPrimary,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = IosRoundedFontFamily,
                        minScale = 0.5f,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                TokenAmount.dollars(amountUnits, token)?.let { dollars ->
                    Text(dollars, color = colors.textSecondary, fontSize = 15.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.to).uppercase(), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            AddressResolutionCard(address = recipient, domain = resolvedName)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().sendKaspaGlass(18.dp).padding(16.dp),
        ) {
            TokenReviewLine(stringResource(R.string.from), fromLabel ?: "--")
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            TokenReviewLine(stringResource(R.string.token_goes_with_it), KaspaUnit.amount(prepared?.kasToRecipient ?: token.kasPerCoinSompi))
            TokenReviewLine(
                stringResource(R.string.network_fee),
                prepared?.let { KaspaUnit.amount(it.fee) } ?: if (isPreparing) "…" else "--",
            )
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            TokenReviewLine(stringResource(R.string.token_total_kas), prepared?.let { KaspaUnit.amount(it.kasToRecipient + it.fee) } ?: "--", bold = true)
            TokenReviewLine(stringResource(R.string.token_kas_paid_from), fromLabel ?: "--")
        }

        if (prepared?.feeFromTokenKas == true) {
            TokenNoteLabel(Icons.Filled.Info, stringResource(R.string.token_fee_from_token_kas), colors.textSecondary)
        }

        when {
            awaitingFunds -> TokenProgressLine(stringResource(R.string.token_waiting_for_kas))
            isPreparing -> TokenProgressLine(stringResource(R.string.token_building_transaction))
            fundingSent && fundShortfall != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // KAS is on its way here already: look again, don't pay twice
                TokenNoteLabel(Icons.Filled.Schedule, stringResource(R.string.token_kas_not_arrived), colors.textSecondary, bold = true)
                TokenCapsuleButton(stringResource(R.string.token_check_again), icon = null, onClick = onCheckAgain)
                Text(
                    stringResource(R.string.token_add_more, KaspaUnit.amount(fundShortfall)),
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button) { onFund(fundShortfall) }.padding(4.dp),
                )
            }
            prepareError != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TokenNoteLabel(Icons.Filled.Warning, prepareError, colors.danger, bold = true)
                if (fundShortfall != null) {
                    // the sending address has too little KAS: top it up from another one
                    TokenCapsuleButton(
                        stringResource(
                            if (fundForChangeCoin) R.string.token_add_for_fee_and_change else R.string.token_add_for_network_fee,
                            KaspaUnit.amount(fundShortfall),
                        ),
                        icon = Icons.Filled.VerticalAlignBottom,
                        onClick = { onFund(fundShortfall) },
                    )
                    Text(
                        stringResource(R.string.token_fund_hint),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        stringResource(R.string.try_again),
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onTryAgain).padding(4.dp),
                    )
                }
            }
        }

        Text(
            stringResource(R.string.token_every_coin_goes_to_recipient, token.symbol, KaspaUnit.amount(token.kasPerCoinSompi), token.symbol),
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TokenReviewLine(title: String, value: String, bold: Boolean = false) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = colors.textSecondary, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** A footnote with its symbol in front (iOS `Label(..., systemImage:)` in .footnote). */
@Composable
internal fun TokenNoteLabel(icon: ImageVector, text: String, color: Color, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** "Building the transaction…" with a small spinner. */
@Composable
internal fun TokenProgressLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IosActivityIndicator(modifier = Modifier.size(14.dp))
        Text(text, color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
    }
}

/** A teal capsule, 46 high, black text (Check Again, Add ... for the Network Fee). */
@Composable
private fun TokenCapsuleButton(title: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(KaspaTeal)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Text(title, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A token amount on the app's keypad (iOS `TokenKeypadAmountEntry`): the big number in the token's
 * unit, Max, a footnote (Available, or why it can't be sent), then the keys. Up to the token's
 * decimals.
 */
@Composable
private fun TokenKeypadAmountEntry(
    token: WalletToken,
    text: String,
    onTextChange: (String) -> Unit,
    footnote: String,
    footnoteIsError: Boolean,
    onMax: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        KeypadAmountText(
            typed = text,
            unit = token.symbol,
            accessibilityName = stringResource(R.string.token_amount_accessibility, token.symbol),
        )
        Text(
            stringResource(R.string.max),
            color = KaspaTeal,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(top = 12.dp)
                .sendKaspaGlass(14.dp)
                .clickable(role = Role.Button, onClick = onMax)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
        Text(
            footnote,
            color = if (footnoteIsError) colors.danger else colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 10.dp).padding(horizontal = 20.dp),
        )
        Spacer(Modifier.weight(1f))
        KaspaKeypad(modifier = Modifier.padding(horizontal = 12.dp)) { key ->
            val next = KaspaKeypad.applying(key, text, maxDecimals = token.decimals)
            if (next == text) {
                haptic(IosHaptic.IMPACT_HEAVY)
            } else {
                onTextChange(next)
            }
        }
    }
}
