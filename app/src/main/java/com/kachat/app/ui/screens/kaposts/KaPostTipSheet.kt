package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.KaPostTipSender
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaUnit
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.KaPostsSettingsViewModel
import com.kachat.app.viewmodels.KaPostsViewModel
import com.kachat.app.viewmodels.KasPriceViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Tipping a poster in Kaspa (iOS 148a5331 `KaPostTipSheet`): the same steps as every send - here
 * KaChat's own Send KAS sheet ([SendKasSheet]), a chat payment's - without To, since the recipient
 * is the poster. From starts on Tips From (KaPost Settings): from the chatting address the tip
 * names the post and counts on it, from another address it goes privately. The amount on the
 * keypad with Max, then the Review with a memo, the fee card and Swipe up to Send. It goes through
 * the chat payment path ([KaPostTipSender], iOS `ChatService.sendPayment(tip:)`).
 *
 * The fee and Max are the chat payment's ([ChatViewModel]'s quote), plus what the tip's larger
 * `kchat:1:tip:` payload adds ([KaPostTipSender.tipPayloadExtraFeeSompi]); the Review's total is
 * what the send is held to.
 */
@Composable
fun KaPostTipSheet(
    address: String,
    displayName: String,
    onDismiss: () -> Unit,
    /** The post being tipped: from the chatting address the tip names it and counts on it. */
    tip: com.kachat.app.models.KaPostTip? = null,
    /** Why an instant tip just failed, if that is what opened this sheet (iOS fae6686). */
    initialError: String? = null,
    /** Called once the tip has gone out: the post's count goes up at once (iOS 0f241ce). */
    onTipped: () -> Unit = {},
    viewModel: KaPostsViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    priceViewModel: KasPriceViewModel = hiltViewModel(),
    kaPostsSettings: KaPostsSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val scope = rememberCoroutineScope()
    val paymentAmount by chatViewModel.paymentAmount.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val priceInCurrency by priceViewModel.currentPriceUsd.collectAsState()
    val currencyCode by priceViewModel.currency.collectAsState()
    val explorer by chatViewModel.kaspaExplorer.collectAsState()
    val baseFeeSompi by chatViewModel.estimatedFeeSompi.collectAsState()
    val builtFeeSompi by chatViewModel.paymentBuiltFeeSompi.collectAsState()
    val feeError by chatViewModel.paymentFeeError.collectAsState()

    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    var outcomeUnknown by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<SentTransaction?>(null) }
    var from by remember { mutableStateOf<SendSource>(SendSource.Chatting) }
    var feeTier by remember { mutableStateOf(FeeTier.NORMAL) }
    var customExtraFeeSompi by remember { mutableStateOf<Long?>(null) }
    var editingFee by remember { mutableStateOf(false) }
    var customFeeText by remember { mutableStateOf("") }
    var manualUtxos by remember { mutableStateOf<List<UtxoEntry>?>(null) }
    var showCoinControl by remember { mutableStateOf(false) }

    /** Only a tip from the chatting address names the post (KAPOSTS_INDEXER.md 5.11). */
    val postTip = if (from is SendSource.Chatting) tip else null

    val resetPayment: () -> Unit = {
        chatViewModel.setPaymentAmount("")
        chatViewModel.setPaymentNote("")
        chatViewModel.setPaymentManualUtxos(null)
        chatViewModel.setPaymentSource(null)
        chatViewModel.setPaymentFeeSpeed(1L, null)
    }
    LaunchedEffect(address) {
        // No contact is made here: opening the tip and cancelling leaves no trace in Chats. The
        // chat is made by the send, right before the payment goes out.
        resetPayment()
        chatViewModel.refreshSpendingUtxos()
        // Starts on Tips From (KaPost Settings); one that can't be read waits for a real choice.
        val picked = runCatching { kaPostDefaultTipSource(kaPostsSettings.kaPostsTipSourceIndex(), kaPostsSettings, walletViewModel) }
            .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e else null }
        from = picked ?: SendSource.Chatting
        chatViewModel.setPaymentSource(from.spendingIndex)
    }
    DisposableEffect(Unit) { onDispose { resetPayment() } }
    // The memo rides in the payload, so the fee prices it (as in a chat).
    LaunchedEffect(note) { chatViewModel.setPaymentNote(note) }
    LaunchedEffect(feeTier, customExtraFeeSompi) { chatViewModel.setPaymentFeeSpeed(feeTier.multiplier, customExtraFeeSompi) }

    val amountSompi = KaspaUnit.sompiFromUserText(paymentAmount) ?: 0L
    /** What the tip's own payload adds to the chat payment's fee. */
    val tipExtraSompi = remember(address, amountSompi, postTip, from) {
        viewModel.tipPayloadExtraFeeSompi(address, amountSompi, postTip, from.spendingIndex)
    }
    val baseWithTip = baseFeeSompi?.let { it + tipExtraSompi }
    /** The extra over the base fee the send pays (the speed, or a custom fee), priced as the
     *  chat payment's quote prices it. */
    val extraFeeSompi = baseFeeSompi?.let { base -> customExtraFeeSompi ?: (base * (feeTier.multiplier - 1)) } ?: 0L
    val totalFeeSompi = baseFeeSompi?.let { builtFeeSompi?.let { it + tipExtraSompi } }
    val fromBalanceSompi = rememberSendSourceBalance(from, walletViewModel)

    val close: () -> Unit = {
        resetPayment()
        onDismiss()
    }

    SendKasSheet(
        recipientName = displayName,
        title = stringResource(R.string.kaposts_tip_title),
        amountSompi = amountSompi,
        onAmountKasChange = { chatViewModel.setPaymentAmount(it); error = null },
        priceInCurrency = priceInCurrency,
        currencyCode = currencyCode,
        note = note,
        onNoteChange = { note = it },
        source = from,
        onSourceChange = { picked ->
            if (picked != from && !sending) {
                from = picked
                chatViewModel.setPaymentSource(picked.spendingIndex)
                // coin control and a custom fee belong to the address they were set for
                manualUtxos = null
                chatViewModel.setPaymentManualUtxos(null)
                customExtraFeeSompi = null
                editingFee = false
            }
        },
        sourceBalanceSompi = fromBalanceSompi,
        sourceAddress = from.address(myAddress),
        walletViewModel = walletViewModel,
        // what the From means for the tip: counted on the post, or private (iOS 071e901)
        fromNote = stringResource(
            if (from is SendSource.Chatting) R.string.kaposts_tip_from_chatting_note else R.string.kaposts_tip_from_private_note
        ),
        maxSompi = {
            chatViewModel.paymentMaxSompi(feeTier.multiplier, customExtraFeeSompi).let { max ->
                (max.amountSompi - tipExtraSompi * feeTier.multiplier).coerceAtLeast(0L) to max.cappedAtInputLimit
            }
        },
        maxRefreshKey = listOf(feeTier, customExtraFeeSompi, from, fromBalanceSompi, note, manualUtxos, tipExtraSompi),
        feeControls = { onEditFee ->
            SendFeeControls(
                feeTier = feeTier,
                onFeeTierChange = {
                    feeTier = it
                    customExtraFeeSompi = null
                    editingFee = false
                },
                isEditingFee = editingFee,
                customFeeText = customFeeText,
                onCustomFeeTextChange = { customFeeText = it },
                isEstimatingFee = false,
                feeText = totalFeeSompi?.let { "${KaspaUnit.plain(it)} ${KaspaUnit.symbol}" },
                onStartEditing = {
                    totalFeeSompi?.let { total ->
                        customFeeText = KaspaUnit.plain(total)
                        editingFee = true
                    }
                },
                onCommit = {
                    val base = baseWithTip
                    val typed = KaspaUnit.sompiFromUserText(customFeeText)
                    if (base != null && typed != null) customExtraFeeSompi = (typed - base).coerceAtLeast(0L)
                    editingFee = false
                },
                coinControlSummary = coinControlSummary(manualUtxos),
                onCoinControl = { if (!sending) showCoinControl = true },
                onEditFee = onEditFee,
            )
        },
        baseFeeSompi = baseWithTip,
        totalFeeSompi = totalFeeSompi,
        onFeeTyped = { typed -> baseWithTip?.let { base -> customExtraFeeSompi = (typed - base).coerceAtLeast(0L) } },
        error = error,
        errorOutcomeUnknown = outcomeUnknown,
        feeError = feeError,
        onRetryFee = { chatViewModel.refreshSpendingUtxos() },
        isSending = sending,
        sent = sent,
        explorer = explorer,
        onSend = {
            if (amountSompi > 0 && !sending) {
                sending = true
                error = null
                outcomeUnknown = false
                val sentAmount = amountSompi
                val countsOnPost = postTip != null
                viewModel.sendTip(
                    address = address,
                    amountSompi = sentAmount,
                    memo = note,
                    sourceSpendingIndex = from.spendingIndex,
                    tip = postTip,
                    extraFeeSompi = extraFeeSompi,
                    manualUtxos = manualUtxos,
                    // what the Review showed: a send rebuilt at the swipe that would pay more doesn't go
                    expectedFeeSompi = totalFeeSompi,
                ) { outcome ->
                    sending = false
                    when (outcome) {
                        is KaPostTipSender.Outcome.Sent -> {
                            if (countsOnPost) onTipped()
                            val txId = outcome.txId
                            if (!txId.isNullOrEmpty()) {
                                sent = SentTransaction(txId = txId, amountSompi = sentAmount, recipient = displayName)
                            } else {
                                // waits for coins to confirm: its bubble is in your chat with them
                                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                close()
                            }
                        }
                        is KaPostTipSender.Outcome.Unknown -> {
                            outcomeUnknown = true
                            error = outcome.message
                        }
                        is KaPostTipSender.Outcome.Failed -> {
                            // as UserFacingError words it, as an instant tip's (audit L6)
                            error = com.kachat.app.util.UserFacingError.message(outcome.message, context.getString(R.string.something_went_wrong))
                            chatViewModel.refreshSpendingUtxos()
                        }
                    }
                }
            }
        },
        onDismiss = close,
    )

    // Coin control on the address the tip comes from, over the sheet.
    if (showCoinControl) {
        val coinAddress = from.address(myAddress)
        if (coinAddress != null) {
            CoinControlSheet(
                fromAddress = coinAddress,
                fetchUtxos = { addr -> walletViewModel.fetchUtxosForCoinControl(addr) },
                initialSelection = manualUtxos,
                chattingAddress = myAddress,
                onDone = { selection ->
                    manualUtxos = selection?.takeIf { it.isNotEmpty() }
                    chatViewModel.setPaymentManualUtxos(manualUtxos)
                },
                onDismiss = { showCoinControl = false },
            )
        }
    }
}

/**
 * Where tips leave from unless picked otherwise (KaPost Settings > Tips From; iOS 071e901
 * `SendSource.defaultTip`): the chatting address, or a spending address, whose tips are private.
 * Null when the saved index isn't one of this account's revealed addresses: nothing should go out
 * on its own then, since a tip from the chatting address isn't private (iOS ee5ec98).
 */
internal suspend fun kaPostDefaultTipSource(
    index: Int?,
    kaPostsSettings: KaPostsSettingsViewModel,
    walletViewModel: WalletViewModel,
): SendSource? {
    if (index == null) return SendSource.Chatting
    if (!kaPostsSettings.isRevealedSpendingIndex(index)) return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val address = walletViewModel.spendingAddressAt(index) ?: return@withContext null
        val label = walletViewModel.ownAddressLabel(address)
            ?: com.kachat.app.services.WalletManager.spendingAddressTitle(index)
        SendSource.Spending(index, address, label)
    }
}
