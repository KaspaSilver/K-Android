package com.kachat.app.services.tokens

import android.util.Log
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.PaymentNoticeOutbox
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.util.PaymentPoolProtocol
import com.kachat.app.util.StartupSafety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token payments in a chat (Send Dollars, 2026-11-20; MESSAGING.md "Token Payments in a Chat").
 *
 * A token transfer (kUSDT) is a covenant transaction with no chat payload, so the payee can't tell
 * which chat it belongs to. Right after it goes out, the payer's chatting address sends a
 * `token_payment_notice` in the 1:1 chat. This is the payer's side (iOS
 * `ChatService.recordTokenPayment`); the payee's (and the payer's other devices') is
 * [ChatRepository.handleTokenPaymentNotice].
 */
@Singleton
class TokenChatPayments @Inject constructor(
    private val chatRepository: ChatRepository,
    private val walletManager: WalletManager,
    /** Lazy: heavy to build, and only a send needs it. */
    private val walletServiceLazy: dagger.Lazy<WalletService>,
    private val paymentNoticeOutbox: PaymentNoticeOutbox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + StartupSafety.logOnlyHandler(TAG))

    /**
     * A dollar send from a chat went out (or, after "Not confirmed", was seen to land): the payer's
     * bubble now ("Sent 12.50 kUSDT"), and the notice for the payee. Once per payment: a send whose
     * outcome was unknown can report in twice. If the notice can't go (an empty chatting address),
     * it is kept, under the account that paid, and sent once there's KAS. [index] is the payee's
     * coin's output (0 for a kUSDT send). Returns at once; the work runs in the background.
     */
    fun recordTokenPayment(contactAddress: String, token: WalletToken, txId: String, index: Int, amount: Long) {
        val owner = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (walletManager.isLoggedOut) return
        val id = txId.trim().lowercase()
        scope.launch(NonCancellable) {
            val fresh = chatRepository.insertOwnTokenBubble(
                myAddress = owner,
                contactId = contactAddress,
                txId = id,
                text = PaymentPoolProtocol.tokenPaymentText(token.plainAmount(amount), token.symbol, isOutgoing = true),
                blockTime = System.currentTimeMillis(),
            )
            if (!fresh) return@launch
            val payload = PaymentPoolProtocol.encode(
                PaymentPoolProtocol.TokenPaymentNoticeContent(token = token.id, txId = id, index = index, amount = amount, address = contactAddress)
            )
            try {
                // Sent by the account that paid: never signed by another (iOS 4f8e14bb).
                // (kept below, for when it's back)
                if (walletManager.getAddress() != owner) throw IllegalStateException("another account is active")
                // Untagged, like iOS sendEnvelopeInternal: kchat:1:comm:, never a first-contact dm.
                walletServiceLazy.get().sendKasiaMessage(contactAddress, payload, firstContactTag = false)
                Log.i(TAG, "Sent token_payment_notice for ${id.take(12)}")
            } catch (e: Exception) {
                Log.w(TAG, "token_payment_notice send failed for ${id.take(12)}, kept for later: ${e.message}")
                // kept under the account that paid, never the one active now (audit #7): it goes
                // out once that account is back and its chatting address has KAS
                paymentNoticeOutbox.keep(
                    PaymentNoticeOutbox.UnsentPaymentNotice(contactAddress = contactAddress, payload = payload, txId = id),
                    owner = owner,
                )
            }
        }
    }

    private companion object {
        const val TAG = "TokenChatPayments"
    }
}
