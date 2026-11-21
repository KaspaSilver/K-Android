package com.kachat.app.ui.screens.tokens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.tokens.KusdtTransfer
import com.kachat.app.services.tokens.TokenAmount
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.ui.screens.SentTransaction
import com.kachat.app.ui.screens.SwipeUpToSendScreen
import com.kachat.app.ui.screens.amount
import com.kachat.app.ui.screens.sendKaspaGlass
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.rememberHaptics
import com.kachat.app.viewmodels.TokenViewModel
import kotlinx.coroutines.launch

/**
 * Combine Coins (iOS 5b7c772c `TokenCombineView`), from an address's ⋮ (Manage kUSDT) or its
 * page's options: the address's kUSDT coins (up to 3 at a time) become one, sent to itself. Every
 * coin holds 1.5 KAS, so each coin merged away gives 1.5 KAS back to the address as plain KAS, less
 * the network fee, which that KAS pays. The review builds when it opens (exact fee and what comes
 * back), then swipe up. Presents itself (a sheet); [onDismiss] once it has gone.
 */
@Composable
fun TokenCombineScreen(
    token: WalletToken,
    holding: TokenHolding,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        TokenCombineContent(token, holding, onClose = close, tokenViewModel = tokenViewModel)
    }
}

@Composable
private fun TokenCombineContent(
    token: WalletToken,
    holding: TokenHolding,
    onClose: () -> Unit,
    tokenViewModel: TokenViewModel,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val holdingsMap by tokenViewModel.holdingsFlow.collectAsState()
    val explorer by tokenViewModel.kaspaExplorer.collectAsState()
    val label = rememberHoldingLabel(holding, tokenViewModel)

    var prepared by remember { mutableStateOf<KusdtTransfer.Prepared?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<SentTransaction?>(null) }
    /** The last submit's answer was lost: Try Again resends the same signed transaction. */
    var outcomeUnknown by remember { mutableStateOf(false) }

    val coinCount = remember(holdingsMap, holding) { tokenViewModel.coins(holding).size }
    /** How many this round combines (3 at most per transaction). */
    val merging = prepared?.spentCoins?.size ?: minOf(coinCount, KusdtTransfer.MAX_TOKEN_INPUTS)

    suspend fun prepare() {
        if (isPreparing) return
        isPreparing = true
        prepareError = null
        try {
            val result = tokenViewModel.prepareCombine(holding)
            prepared = result.getOrNull()
            if (prepared == null) prepareError = result.exceptionOrNull()?.message ?: context.getString(R.string.kusdt_build_failed)
        } finally {
            isPreparing = false
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
                // a review left open a while is built again; the swipe sends only what it showed
                when (val outcome = tokenViewModel.send(reviewed, outcomeUnknown, fromId = null, recipient = null, amount = null, chatContact = null, token = token)) {
                    is TokenViewModel.SendOutcome.Sent -> {
                        outcomeUnknown = false
                        prepared = outcome.prepared
                        haptic(IosHaptic.SUCCESS)
                        sent = SentTransaction(txId = outcome.txid, amountSompi = null, recipient = null)
                    }
                    is TokenViewModel.SendOutcome.NotConfirmed -> {
                        // its coins stay: a rebuild picks one of them first, so at most one send lands
                        prepared = outcome.prepared
                        outcomeUnknown = true
                        failureMessage = outcome.message
                    }
                    is TokenViewModel.SendOutcome.Changed -> {
                        outcome.rebuilt?.let { prepared = it }
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

    // builds as it opens (iOS onAppear)
    LaunchedEffect(Unit) {
        if (prepared == null && sent == null && !isPreparing) prepare()
    }

    SwipeUpToSendScreen(
        screenTitle = stringResource(R.string.token_combine_coins),
        onCancel = onClose,
        title = stringResource(R.string.token_swipe_up_to_combine),
        isBusy = isSending,
        isEnabled = prepared != null && !isPreparing,
        amountSompi = null,
        recipient = null,
        sent = sent,
        failureMessage = failureMessage,
        explorer = explorer,
        onDone = onClose,
        action = ::send,
        // iOS SwipeUpLabels.transaction(done: "Coins Combined")
        releaseLabel = stringResource(R.string.swipe_release_to_confirm),
        doneLabel = stringResource(R.string.coins_combined),
        failedFallback = stringResource(R.string.transaction_didnt_go_out),
    ) {
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
                TokenIcon(token, size = 44.dp)
                Text(label, color = colors.textSecondary, fontSize = 15.sp)
                Text(
                    stringResource(R.string.token_coins_into_one, merging),
                    color = colors.textPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = IosRoundedFontFamily,
                )
            }

            Text(
                stringResource(R.string.token_combine_explainer, token.symbol, KaspaUnit.amount(token.kasPerCoinSompi), token.symbol),
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().sendKaspaGlass(18.dp).padding(16.dp),
            ) {
                TokenReviewLine(
                    stringResource(R.string.token_combined),
                    prepared?.let { TokenAmount.amount(it.amount, token) } ?: "--",
                )
                TokenReviewLine(
                    stringResource(R.string.token_kas_held_with_it),
                    "${KaspaUnit.amount(merging.toLong() * token.kasPerCoinSompi)} → ${KaspaUnit.amount(token.kasPerCoinSompi)}",
                )
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                TokenReviewLine(
                    stringResource(R.string.network_fee),
                    prepared?.let { KaspaUnit.amount(it.fee) } ?: if (isPreparing) "…" else "--",
                )
                TokenReviewLine(
                    stringResource(R.string.token_back_to_this_address),
                    prepared?.let { KaspaUnit.amount(it.kasBack) } ?: if (isPreparing) "…" else "--",
                    bold = true,
                )
            }

            if (coinCount > KusdtTransfer.MAX_TOKEN_INPUTS) {
                TokenNoteLabel(Icons.Filled.Info, stringResource(R.string.token_combine_limit, KusdtTransfer.MAX_TOKEN_INPUTS), colors.textSecondary)
            }

            val error = prepareError
            if (isPreparing) {
                TokenProgressLine(stringResource(R.string.token_building_transaction))
            } else if (error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TokenNoteLabel(Icons.Filled.Warning, error, colors.danger, bold = true)
                    Text(
                        stringResource(R.string.try_again),
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button) { scope.launch { prepare() } }.padding(4.dp),
                    )
                }
            }
        }
    }
}
