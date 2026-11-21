package com.kachat.app.ui.screens.tokens

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.tokens.WalletToken
import com.kachat.app.ui.screens.IosFullSheet
import com.kachat.app.viewmodels.TokenViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * A kUSDT tip (Tip In, iOS 148a5331 `TokenSendView(token:tipRecipient:name:)`): the kUSDT send
 * with the poster filled in, opening on From with them shown by [name]. [recipient] is the poster's
 * chatting address. A kUSDT tip is a plain kUSDT send: it isn't counted on the post, doesn't list
 * the tipper, and makes no chat bubble (no `chatRecipient`). Presents itself; [onDismiss] once it
 * has gone.
 */
@Composable
fun KusdtTipSend(
    recipient: String,
    name: String,
    onDismiss: () -> Unit,
    tokenViewModel: TokenViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        TokenSendContent(
            token = WalletToken.KUSDT,
            startFrom = null,
            prefillRecipient = recipient,
            prefillName = name,
            chatRecipient = null,
            chatName = null,
            onSent = null,
            onClose = close,
            tokenViewModel = tokenViewModel,
            walletViewModel = walletViewModel,
        )
    }
}
