package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import com.kachat.app.services.KaPostTipLedger

/**
 * A payment card's "Sent" / "Received" line, which says "Sent tip" / "Received tip" for a KaPost
 * tip ([KaPostTipLedger]; the card then opens the tipped post). Looks the payment up once (iOS
 * `PaymentCardHeading`, 2026-11-22). [canBeTip] is false for a token payment.
 */
@Composable
internal fun paymentCardHeading(txId: String?, isSent: Boolean, canBeTip: Boolean): String {
    val context = LocalContext.current
    val revision by KaPostTipLedger.revision.collectAsState()
    val isTip = remember(txId, canBeTip, revision) {
        canBeTip && txId != null && KaPostTipLedger.postId(context, txId) != null
    }
    if (canBeTip && txId != null) {
        LaunchedEffect(txId) { KaPostTipLedger.tipPostId(context, txId) }
    }
    return when {
        isTip && isSent -> stringResource(R.string.sent_tip)
        isTip -> stringResource(R.string.received_tip)
        isSent -> stringResource(R.string.sent)
        else -> stringResource(R.string.payment_received)
    }
}

/**
 * A payment card's tap (iOS ChatDetailView `onOpenPayment`): a KaPost tip's card opens the post it
 * tipped; any other payment its details ([openDetails]).
 */
internal fun openPaymentCard(context: android.content.Context, txId: String, openDetails: () -> Unit) {
    val postId = KaPostTipLedger.postId(context, txId)
    if (postId != null) {
        openKaChatLink(context, KaChatLinkRef.KaPost(txId = postId), fromChat = true)
    } else {
        openDetails()
    }
}
