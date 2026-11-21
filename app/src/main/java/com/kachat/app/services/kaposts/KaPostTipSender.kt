package com.kachat.app.services

import android.content.Context
import com.kachat.app.models.KaPostTip
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaPostsProtocol
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.MessageProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A KaPost tip, sent the way a payment in that person's chat is (iOS 148a5331: tips go through
 * `ChatService.sendPayment(tip:)`): KaChat's [ChatPaymentSender], so the destination, the funding
 * source, the pending bubble in your chat with them and the retry while coins confirm are exactly
 * a chat payment's. From the chatting address the payment carries a signed `kchat:1:tip:` payload
 * naming the post, so the post counts it (KAPOSTS_INDEXER.md 5.11); from a spending address it is a
 * private tip, a plain payment that names no post.
 */
@Singleton
class KaPostTipSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatPaymentSender: ChatPaymentSender,
    private val chatRepository: ChatRepository,
    private val walletManager: WalletManager,
    private val kaPostsService: KaPostsService,
) {
    sealed class Outcome {
        /** Sent; [txId] is null when it waits for coins to confirm (its bubble is in the chat). */
        data class Sent(val txId: String?) : Outcome()
        /** It may have gone: nothing may offer to send it again. */
        data class Unknown(val message: String) : Outcome()
        data class Failed(val message: String?) : Outcome()
    }

    /**
     * Tips [amountSompi] to [address]. [tip] names the post (null: something not on the network
     * yet); it is dropped from a spending address. [expectedFeeSompi] is the fee the Review showed,
     * with the tip's larger payload priced in ([tipPayloadExtraFeeSompi]).
     */
    suspend fun send(
        address: String,
        amountSompi: Long,
        sourceSpendingIndex: Int?,
        tip: KaPostTip?,
        /** The Review's memo, sealed to the poster as a chat payment's is. */
        memo: String = "",
        extraFeeSompi: Long = 0L,
        manualUtxos: List<UtxoEntry>? = null,
        expectedFeeSompi: Long? = null,
    ): Outcome {
        if (amountSompi <= 0) return Outcome.Failed("Enter a valid amount.")
        KaspaAddress.otherNetworkMessageRes(address)?.let { return Outcome.Failed(context.getString(it)) }
        val own = runCatching { walletManager.getAddress() }.getOrNull()
        if (!address.equals(own, ignoreCase = true)) {
            // Paying someone is reaching out to them, as writing to them is (iOS 1eb4708).
            chatRepository.acceptChat(address)
            chatRepository.watchPaymentDestination(address)
        }
        val postTip = if (sourceSpendingIndex == null) tip else null
        val outcome = chatPaymentSender.send(
            ChatPaymentSender.Request(
                contactId = address,
                amountSompi = amountSompi,
                memo = memo.trim(),
                sourceSpendingIndex = sourceSpendingIndex,
                feeRateOverride = null,
                extraFeeSompi = extraFeeSompi,
                manualUtxos = manualUtxos,
                expectedFeeSompi = expectedFeeSompi,
                tip = postTip,
                tipPayload = postTip?.let { t ->
                    { sealedHex: String -> kaPostsService.tipPayload(postId = t.postId, authorPubkey = t.authorPubkey, sealedHex = sealedHex) }
                },
            )
        )
        return when (outcome) {
            is ChatPaymentSender.Outcome.Sent -> Outcome.Sent(outcome.txId)
            ChatPaymentSender.Outcome.Deferred -> Outcome.Sent(null)
            is ChatPaymentSender.Outcome.Failed ->
                if (outcome.unknownTxId != null) Outcome.Unknown(outcome.message ?: context.getString(com.kachat.app.R.string.l53_send_outcome_unknown))
                else Outcome.Failed(outcome.message)
        }
    }

    /**
     * What a tip's `kchat:1:tip:` payload adds to the fee over the `kchat:1:pay:` one KaChat's
     * payment Review prices (iOS `estimatePaymentFee(tip:)`): its extra bytes at the network's rate.
     * Zero from a spending address, which carries no payload either way.
     */
    fun tipPayloadExtraFeeSompi(address: String, amountSompi: Long, tip: KaPostTip?, sourceSpendingIndex: Int?): Long {
        if (tip == null || sourceSpendingIndex != null || amountSompi <= 0) return 0L
        return runCatching {
            val sealed = MessageProtocol.sealedPaymentHex(
                note = "",
                amountSompi = amountSompi,
                recipientXOnlyPubKey = KaspaAddress.decode(address).second,
            )
            val pubkey = runCatching { kaPostsService.requesterPubkey() }.getOrNull() ?: "0".repeat(66)
            val tipSize = KaPostsProtocol.tipPayload(
                pubkey = pubkey, signature = "0".repeat(128), postId = tip.postId,
                authorPubkey = tip.authorPubkey, sealedHex = sealed,
            ).toByteArray(Charsets.UTF_8).size
            val paySize = MessageProtocol.estimatedPaymentPayloadSize("", amountSompi)
            maxOf(0, tipSize - paySize).toLong() * KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        }.getOrDefault(0L)
    }
}
