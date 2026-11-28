package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.PaymentPoolProtocol
import com.kachat.app.util.SendFailureHint
import com.kachat.app.util.SendFeeModel
import com.kachat.app.util.StartupSafety
import com.kachat.app.util.UtxoMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * A payment from a chat (iOS `ChatService.sendPayment` / `sendPaymentInternal`): it spends only
 * confirmed coins, so with none yet it is DEFERRED, not failed - the bubble stays pending and a
 * timer tries again, for as long as the bubble is there, however the screen that started it has
 * moved on. A singleton for that reason, as iOS's retry state lives on its `ChatService`.
 */
@Singleton
class ChatPaymentSender @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val chatRepository: ChatRepository,
    private val walletService: WalletService,
    private val walletEngine: KaspaWalletEngine,
    private val walletManager: WalletManager,
    private val addressActivityNotifier: AddressActivityNotifier,
    /** Payment notices the empty chatting address could not send yet (iOS 710fdc0, IOS-086). */
    private val paymentNoticeOutbox: PaymentNoticeOutbox,
    /** The REST API that says whether an owed notice's payment has landed (audit A1). */
    private val networkService: NetworkService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + StartupSafety.logOnlyHandler("ChatPaymentSender"))

    /** iOS `noInputRetryCounts`: how many times each pending payment found no confirmed coins. */
    private val noInputRetryCounts = ConcurrentHashMap<String, Int>()

    /** iOS `scheduledSendRetries`: the pending payments with a retry already on the timer. */
    private val scheduledSendRetries = ConcurrentHashMap.newKeySet<String>()

    /** Spending-address chat payments whose send got no answer, by txid: the notice each owes
     *  (iOS `owedSpendingNotices`, audit 2026-11-27 C5). Kept across an account switch; each
     *  names its owner. On disk (audit A1): Android ends a process often, and a notice lost
     *  with it left the payee with the KAS and no bubble or memo. */
    private val owedSpendingNotices = OwedSpendingNotices(OwedSpendingNotices.prefsStorage(context))

    /** The owed notices whose payment is being looked up right now ([watchOwedPayment]). */
    private val watchingOwed = ConcurrentHashMap.newKeySet<String>()

    init {
        // Kept from an earlier run: each payment is looked up again (audit A1).
        scope.launch { owedSpendingNotices.txIds().forEach { watchOwedPayment(it) } }
        // An account back in use: a notice it owes for a payment that landed meanwhile goes now,
        // and one still waiting is looked up again.
        scope.launch {
            walletManager.signedInAddressFlow.collect { address ->
                if (address == null) return@collect
                for (txId in owedSpendingNotices.txIdsOwnedBy(address)) {
                    val owed = owedSpendingNotices.get(txId) ?: continue
                    if (owed.landed) {
                        runCatching { sendOwedSpendingNoticeIfAny(ChatRepository.SettledSend(txId, owed.contactAddress, owed.owner)) }
                    } else {
                        watchOwedPayment(txId)
                    }
                }
            }
        }
        // A Not confirmed payment turned out Sent: the notice it owes goes now, from the account
        // that made it only (iOS `sendOwedSpendingNoticeIfAny`).
        scope.launch {
            chatRepository.settledSends.collect { settled ->
                try {
                    sendOwedSpendingNoticeIfAny(settled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Owed payment_notice for ${settled.txId.take(12)} not sent: ${e.message}")
                }
            }
        }
    }

    private suspend fun sendOwedSpendingNoticeIfAny(settled: ChatRepository.SettledSend) {
        // Landed, whoever is signed in: sent once its owner is (the account flow above).
        owedSpendingNotices.markLanded(settled.txId)
        if (walletManager.isLoggedOut) return
        val current = runCatching { walletManager.getAddress() }.getOrNull() ?: return
        if (!settled.walletAddress.equals(current, ignoreCase = true)) return
        val owed = owedSpendingNotices.due(settled.txId, settled.contactId, current, System.currentTimeMillis()) ?: return
        // a contact not loaded keeps it for a later settle
        if (chatRepository.getContact(owed.contactAddress, walletAddress = owed.owner) == null) return
        owedSpendingNotices.remove(settled.txId)
        Log.i(TAG, "Not confirmed payment ${settled.txId.take(12)} landed: sending its owed payment_notice")
        sendSpendingPaymentNotice(owed.contactAddress, settled.txId, owed.amountSompi, owed.memo, owed.owner)
    }

    /**
     * Looks up an owed notice's payment until the network has it accepted (audit A1): every
     * [OWED_CHECK_INTERVAL_MS] for [OWED_CHECK_WINDOW_MS] after the payment, and once more on
     * each start or sign-in after that. Nothing else settles it on Android: its inputs are on
     * the spending address, its change on a fresh slot and it carries no payload, so the chatting
     * address's history and the indexer never see it (iOS settles it from UTXO events). Landed,
     * its bubble turns Sent under its own account and its notice goes.
     */
    private fun watchOwedPayment(txId: String) {
        val key = txId.trim().lowercase()
        if (!watchingOwed.add(key)) return
        scope.launch {
            try {
                var first = true
                while (true) {
                    val owed = owedSpendingNotices.get(key) ?: return@launch
                    if (owed.landed) return@launch
                    val now = System.currentTimeMillis()
                    if (!OwedSpendingNotices.keepsChecking(owed.atMs, now, first)) return@launch
                    if (!first || now - owed.atMs < OWED_CHECK_INTERVAL_MS) delay(OWED_CHECK_INTERVAL_MS)
                    first = false
                    if (!isAccepted(key)) continue
                    Log.i(TAG, "Not confirmed payment ${key.take(12)} is accepted: settling it")
                    val settled = chatRepository.settleUnconfirmedSend(key, owed.owner)
                    // Its row went already (settled by its copy, or deleted): the notice is still owed.
                    if (!settled) sendOwedSpendingNoticeIfAny(ChatRepository.SettledSend(key, owed.contactAddress, owed.owner))
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Looking up an owed notice's payment failed: ${e.message}")
            } finally {
                watchingOwed.remove(key)
            }
        }
    }

    private suspend fun isAccepted(txId: String): Boolean = try {
        val rest = networkService.kaspaRestApi.value
        rest != null && kotlinx.coroutines.withTimeoutOrNull(8_000L) { rest.getTransactionAcceptance(txId).isAccepted == true } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    data class Request(
        val contactId: String,
        val amountSompi: Long,
        val memo: String,
        val sourceSpendingIndex: Int?,
        val feeRateOverride: Long?,
        val extraFeeSompi: Long,
        val manualUtxos: List<UtxoEntry>?,
        /** The fee the Review showed (iOS 4f8e14bb, as `sendWithdrawal`): a build that would pay
         *  more sends nothing. A deferred retry rebuilds from the coins of that later moment and is
         *  held to it too, as the swipe was (iOS 83806b3d `paymentExpectedFeeSompi`). Null holds it
         *  to nothing. */
        val expectedFeeSompi: Long? = null,
        /** A KaPost tip (iOS 148a5331 `sendPayment(tip:)`, KAPOSTS_INDEXER.md 5.11): from the
         *  chatting address the payment carries [tipPayload] of the sealed note - a signed
         *  `kchat:1:tip:` payload naming the post - instead of `kchat:1:pay:`. A retry keeps it,
         *  as the request does. From a spending address it's a private tip, a plain payment. */
        val tip: com.kachat.app.models.KaPostTip? = null,
        val tipPayload: ((sealedHex: String) -> ByteArray)? = null,
    )

    sealed class Outcome {
        data class Sent(val txId: String) : Outcome()

        /** No confirmed coins yet: the bubble is in the chat and the timer owns it. */
        data object Deferred : Outcome()

        /** Not sent, or [unknownTxId] set: it may have gone and the network never confirmed it
         *  (no Try Again: it could pay twice). Told apart by type, never by the message's text,
         *  which is translated (iOS d23e6f7a). */
        data class Failed(val message: String?, val unknownTxId: String? = null) : Outcome()
    }

    /** Sends [request]. A caller that goes away does not stop it. */
    suspend fun send(request: Request): Outcome = scope.async { firstAttempt(request) }.await()

    private suspend fun firstAttempt(request: Request): Outcome {
        val pendingId = "pending_${java.util.UUID.randomUUID()}"
        // The account this payment was queued under: it never signs as another one (iOS 4f8e14bb).
        val owner = try {
            walletManager.getAddress()
        } catch (e: Exception) {
            return Outcome.Failed(e.message)
        }
        try {
            balanceRefusal(request)?.let { return Outcome.Failed(it) }
            chatRepository.insertMessage(
                MessageEntity(
                    id = pendingId,
                    contactId = request.contactId,
                    walletAddress = owner,
                    type = "pay",
                    direction = "sent",
                    plaintextBody = bubbleText(request),
                    encryptedPayload = "",
                    amountSompi = request.amountSompi,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "pending"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting payment", e)
            return Outcome.Failed(e.message)
        }
        return attempt(request, pendingId, owner)
    }

    /** The memo goes in now: it is encrypted to the recipient, so this device can never read it
     *  back from the chain - the local bubble is the only place it can show. The amount as sent,
     *  not as typed ("1,5" or "01.50" both read "1.5"). */
    private fun bubbleText(request: Request): String =
        if (request.memo.isEmpty()) {
            KaspaUnit.label(context.getString(com.kachat.app.R.string.sent_amount_kas)).format(KaspaUnit.plain(request.amountSompi))
        } else {
            KaspaUnit.label(context.getString(com.kachat.app.R.string.sent_amount_kas_memo)).format(KaspaUnit.plain(request.amountSompi), request.memo)
        }

    /**
     * iOS `ensureSufficientBalanceForPaymentSend`, before any bubble exists: the reason a payment
     * can't go, or null to go on. With no confirmed coins but some on the way that is the deferral,
     * not a refusal; a read that fails says nothing about the balance, and the send itself will
     * report it. Coin control and the extra aren't priced here, as on iOS.
     */
    private suspend fun balanceRefusal(request: Request): String? {
        val sourceAddress = request.sourceSpendingIndex?.let { walletManager.deriveSpendingAddress(it) } ?: walletManager.getAddress()
        val utxos = try {
            walletEngine.fetchUtxosOrThrow(sourceAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Payment balance precheck unavailable, continuing send: ${e.message}")
            return null
        }
        val spendable = utxos.filter { it.utxoEntry.blockDaaScore > 0L && !it.utxoEntry.isCoinbase }
        val totalNonCoinbase = UtxoMath.checkedTotal(utxos.filterNot { it.utxoEntry.isCoinbase }.map { it.utxoEntry.amount }) ?: 0L
        val available = UtxoMath.checkedTotal(spendable.map { it.utxoEntry.amount }) ?: 0L
        val payloadSize = MessageProtocol.estimatedPaymentPayloadSize(request.memo, request.amountSompi)
        val recipientScriptLen = KaspaAddress.getScriptPublicKey(request.contactId).length / 2
        val senderScriptLen = KaspaAddress.getScriptPublicKey(sourceAddress).length / 2
        val estimate = runCatching {
            SendFeeModel.estimate(
                utxos = spendable,
                manual = null,
                amountSompi = request.amountSompi,
                payloadSize = payloadSize,
                recipientScriptLen = recipientScriptLen,
                changeScriptLen = senderScriptLen,
            )
        }
        // The fee when it can be priced, else iOS's estimateSendAllFee over the coins there are.
        val estimatedFee = estimate.getOrNull()?.networkFeeSompi ?: (
            KaspaMass.calculateFee(
                KaspaMass.calculateMass(spendable.size, listOf(recipientScriptLen, senderScriptLen), payloadSize),
                KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM,
            ) + KaspaUtxoSelector.FEE_BUFFER_SOMPI
            )
        val plannedSpend = request.amountSompi + estimatedFee
        val insufficient = String.format(
            Locale.US,
            KaspaUnit.label(SendFailureHint.INSUFFICIENT_BALANCE_TEMPLATE),
            KaspaUnit.plain(plannedSpend),
            KaspaUnit.plain(available),
        )
        if (spendable.isEmpty()) {
            if (totalNonCoinbase > 0L) {
                Log.i(TAG, "Payment send precheck deferred: $NO_SPENDABLE_FUNDS_YET")
                return null
            }
            return insufficient
        }
        val refused = estimate.exceptionOrNull() ?: return null
        return if (isInsufficientFundsError(refused)) insufficient else refused.message
    }

    /** One try of the payment whose bubble is [pendingId]: sent, deferred, or marked failed.
     *  [deferredRetry]: a timer's try ([scheduleRetry]), with no Review on screen. */
    private suspend fun attempt(request: Request, pendingId: String, owner: String, deferredRetry: Boolean = false): Outcome {
        try {
            val myAddress = walletManager.getAddress()
            // It runs when its turn comes, and the person may have switched accounts since: refused
            // rather than signed with the new account's key. Its row went with the old account's
            // messages, where the leftover check fails it at the next launch (iOS 4f8e14bb).
            if (myAddress != owner) {
                Log.i(TAG, "Refused a queued payment: the account changed")
                clearNoInputRetryState(pendingId)
                // Its bubble, under its own account, says so (audit A3): it stayed Sending for
                // the rest of the process. Nothing went out; payments never offer Retry.
                runCatching { chatRepository.updateMessageStatus(pendingId, "failed", walletAddress = owner) }
                return Outcome.Failed(context.getString(com.kachat.app.R.string.not_sent_you_switched_accounts))
            }
            // The owner's key, read once for the memo seal and the signature (audit A4/C5): a
            // switch from here on can't make another account sign or pay for it.
            val ownerKey = if (request.sourceSpendingIndex == null) {
                walletManager.getPrivateKeyBytes().also {
                    if (!com.kachat.app.util.AccountKey.belongsTo(it, owner)) throw SendAccountChangedException()
                }
            } else null
            val fromChattingAddress = request.sourceSpendingIndex == null
            // From the chatting address it is a chat payment, which always carries its
            // `kchat:1:pay:` payload - an empty memo too (iOS buildPaymentTx): that payload is
            // what opens our chat on the payee's side when they don't know us yet (iOS 1eb4708).
            // From a spending address it is a plain transfer, and the chatting address tells the
            // chat about it right after ([sendSpendingPaymentNotice]): the payee has never seen
            // that address, so a chat payment from it would open a stranger's Message Request on
            // their side instead of landing in this chat (iOS 927322b, XP-022).
            val tipPayload = request.tipPayload
            val payload = if (!fromChattingAddress) null else if (request.tip != null && tipPayload != null) {
                // A KaPost tip: the same sealed note, inside a signed payload naming the post.
                tipPayload(
                    MessageProtocol.sealedPaymentHex(
                        note = request.memo.trim(),
                        amountSompi = request.amountSompi,
                        recipientXOnlyPubKey = KaspaAddress.decode(request.contactId).second,
                        senderPrivateKey = ownerKey!!
                    )
                )
            } else MessageProtocol.paymentMemoPayload(
                memo = request.memo,
                amountSompi = request.amountSompi,
                recipientXOnlyPubKey = KaspaAddress.decode(request.contactId).second,
                // the seed reads the memo back on another device (iOS bfe9f022)
                senderPrivateKey = ownerKey!!
            )
            // Paid to the contact's chatting address, the memo sealed to its key (iOS 6333dd3: no
            // payment-pool destinations any more). FUNDING SOURCE - the From the person chose (iOS
            // sendPaymentInternal): the chatting address (null, the default) is funded with the
            // identity key, change back to it, sharing the exact same UTXO/outpoint bookkeeping
            // message sends use (KaspaWalletEngine's pending-spent tracking), so an immediately-
            // following message can't race onto the just-spent outpoints; a spending address is
            // signed with its own key and its change goes to a never-used address.
            val txId = if (request.sourceSpendingIndex != null) {
                walletService.payInKaspa(
                    toAddress = request.contactId,
                    amountSompi = request.amountSompi,
                    sourceSpendingIndex = request.sourceSpendingIndex,
                    feeRateOverride = request.feeRateOverride,
                    payloadBytes = payload,
                    manualUtxos = request.manualUtxos,
                    extraFeeSompi = request.extraFeeSompi,
                    // The Review's fee, held to from every From (iOS sendPaymentInternal).
                    expectedFeeSompi = request.expectedFeeSompi,
                )
            } else {
                // [WalletService.sendKaspa]'s chat-payment call, with the Review's fee held to.
                if (walletManager.getAddress() != owner) throw SendAccountChangedException()
                walletEngine.sendKaspa(
                    toAddress = request.contactId,
                    amountSompi = request.amountSompi,
                    payloadBytes = payload,
                    fromAddress = owner,
                    signingPrivateKey = ownerKey!!,
                    feeRateOverride = request.feeRateOverride,
                    manualUtxos = request.manualUtxos,
                    extraFeeSompi = request.extraFeeSompi,
                    excludeCoinbase = true,
                    confirmedOnly = true,
                    greedyAmongManual = true,
                    expectedFeeSompi = request.expectedFeeSompi,
                ).getOrThrow().also { walletService.refreshBalance() }
            }
            // A KaPost tip: the chat's card says "Sent tip" and opens the post (iOS 2026-11-22
            // sendPayment -> KaPostTipLedger.record).
            if (fromChattingAddress && request.tip != null && tipPayload != null) {
                KaPostTipLedger.record(context, txId, request.tip.postId)
            }
            if (!fromChattingAddress) sendSpendingPaymentNotice(request.contactId, txId, request.amountSompi, request.memo, owner)

            // The From's balance lags the send - retry it shortly after, then again once the
            // change output has typically landed (~1.5s and ~4s).
            scope.launch {
                delay(1_500)
                walletService.refreshSpendingBalance()
                walletService.refreshBalance()
                delay(2_500)
                walletService.refreshSpendingBalance()
                walletService.refreshBalance()
                addressActivityNotifier.requestRefresh()
            }

            chatRepository.finalizeProvisionalMessage(
                pendingId,
                MessageEntity(
                    id = txId,
                    contactId = request.contactId,
                    walletAddress = owner,
                    type = "pay",
                    direction = "sent",
                    plaintextBody = bubbleText(request),
                    encryptedPayload = "",
                    amountSompi = request.amountSompi,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
            clearNoInputRetryState(pendingId)
            return Outcome.Sent(txId)
        } catch (e: CancellationException) {
            throw e
        } catch (unknown: SendOutcomeUnknownException) {
            // It may have landed: never Failed with Retry, which could pay twice. Not confirmed
            // ("warning") under the transaction's own id, until the payment is read back
            // (iOS d23e6f7a `markSendUnconfirmed`).
            Log.w(TAG, "Payment outcome unknown: marked not confirmed")
            // From a spending address, its payment_notice goes once the payment shows up
            // (settleUnconfirmedSend): sent now it could name a payment that never lands, and
            // never sent, the payee has the KAS with no bubble or memo (audit 2026-11-27 C5).
            if (request.sourceSpendingIndex != null) {
                owedSpendingNotices.put(
                    unknown.txId,
                    OwedSpendingNotice(
                        contactAddress = request.contactId,
                        amountSompi = request.amountSompi,
                        memo = request.memo,
                        owner = owner,
                        atMs = System.currentTimeMillis(),
                    ),
                )
            }
            finalizeUnconfirmed(request, pendingId, unknown.txId, owner)
            if (request.sourceSpendingIndex != null) watchOwedPayment(unknown.txId)
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(context.getString(com.kachat.app.R.string.send_outcome_unknown), unknownTxId = unknown.txId)
        } catch (changed: SendFeeChangedException) {
            if (deferredRetry) {
                // A deferred retry: no Review is on screen to show the new fee, so the bubble
                // stays as a Failed payment rather than vanishing; nothing went out (iOS 83806b3d).
                Log.i(TAG, "Deferred payment not sent: the fee is now above the Review's")
                chatRepository.updateMessageStatus(pendingId, "failed", walletAddress = owner)
                clearNoInputRetryState(pendingId)
                return Outcome.Failed(KaspaUnit.label(context.getString(com.kachat.app.R.string.send_fee_changed, KaspaUnit.plain(changed.feeSompi))))
            }
            // Nothing went out and the Review shows the new fee: the next swipe makes its own
            // bubble, so this one goes rather than staying behind as a Failed payment (iOS 4f8e14bb).
            Log.i(TAG, "Payment not sent: the fee changed since the Review")
            chatRepository.deleteMessage(pendingId, walletAddress = owner)
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(KaspaUnit.label(context.getString(com.kachat.app.R.string.send_fee_changed, KaspaUnit.plain(changed.feeSompi))))
        } catch (e: Exception) {
            if (isNoConfirmedInputsError(e.message) && (noInputRetryCounts[pendingId] ?: 0) < MAX_DEFERRED_PAYMENT_RETRIES) {
                val delaySeconds = nextNoInputRetryDelay(pendingId)
                Log.i(TAG, "Payment deferred retry ${pendingId.take(12)} in ${"%.0f".format(Locale.US, delaySeconds)}s (no confirmed inputs)")
                chatRepository.holdDeferredPayment(pendingId)
                scheduleRetry(request, pendingId, delaySeconds, owner)
                // Deferred, not sent - nothing to confirm yet.
                return Outcome.Deferred
            }
            Log.e(TAG, "Error sending payment", e)
            // Past the balance check a payment that fails stays in the chat, marked failed (iOS
            // markPendingMessageFailed); the caller surfaces the reason on a first try.
            runCatching { chatRepository.updateMessageStatus(pendingId, "failed", walletAddress = owner) }
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(e.message)
        }
    }

    /** The payment bubble [pendingId] of a send whose outcome is unknown takes [txId] and
     *  "warning". A row already gone (deleted meanwhile) stays gone. */
    private suspend fun finalizeUnconfirmed(request: Request, pendingId: String, txId: String, owner: String) {
        try {
            val row = chatRepository.getMessage(pendingId, walletAddress = owner) ?: return
            chatRepository.finalizeProvisionalMessage(
                pendingId,
                row.copy(
                    id = txId,
                    plaintextBody = row.plaintextBody ?: bubbleText(request),
                    amountSompi = row.amountSompi ?: request.amountSompi,
                    deliveryStatus = "warning",
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't mark a payment not confirmed", e)
            runCatching { chatRepository.updateMessageStatus(pendingId, "warning", walletAddress = owner) }
        }
    }

    /** iOS `scheduleOutgoingRetry`: after [delaySeconds] the payment tries again, unless its
     *  bubble has gone (or belongs to another account now) or has been sent. */
    private fun scheduleRetry(request: Request, pendingId: String, delaySeconds: Double, owner: String) {
        if (!scheduledSendRetries.add(pendingId)) return
        scope.launch {
            delay((delaySeconds * 1_000).toLong())
            scheduledSendRetries.remove(pendingId)
            // Read under its own account (audit A3): after a switch the active account has no
            // such row, and the retry used to drop it, leaving the bubble on Sending for good.
            // The attempt below refuses a switched account and marks it failed.
            val row = chatRepository.getMessage(pendingId, walletAddress = owner)
            // Sent, or Not confirmed (it may have landed): never sent again from here.
            if (row == null || row.deliveryStatus == "sent" || row.deliveryStatus == "warning") {
                clearNoInputRetryState(pendingId)
                return@launch
            }
            chatRepository.updateMessageStatus(pendingId, "pending", walletAddress = owner)
            // Individual attempts decide whether to reschedule or fail. Held to the Review's fee,
            // as the swipe was (iOS 83806b3d).
            attempt(request, pendingId, owner, deferredRetry = true)
        }
    }

    private fun nextNoInputRetryDelay(pendingId: String): Double {
        val nextAttempt = (noInputRetryCounts[pendingId] ?: 0) + 1
        noInputRetryCounts[pendingId] = nextAttempt
        return noInputRetryDelaySeconds(nextAttempt, Random.nextDouble(JITTER_MIN, JITTER_MAX))
    }

    private fun clearNoInputRetryState(pendingId: String) {
        noInputRetryCounts.remove(pendingId)
        scheduledSendRetries.remove(pendingId)
        chatRepository.releaseDeferredPayment(pendingId)
    }

    /**
     * Tells the chat about a payment just sent From a spending address (iOS 927322b
     * `sendSpendingPaymentNotice`): an encrypted `payment_notice` from the chatting address, so
     * the payee's app files the payment (and its memo, which travels only here) in the chat with
     * us. Our own bubble is already there. Fire-and-forget: if it fails (usually an empty chatting
     * address) it is kept and sent once the chatting address has KAS ([PaymentNoticeOutbox], iOS
     * 710fdc0, IOS-086); meanwhile the payee still has the KAS, shown in their self-chat.
     */
    private fun sendSpendingPaymentNotice(contactId: String, txId: String, amountSompi: Long, memo: String, owner: String) {
        val payload = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.PaymentNoticeContent(
                txId = txId.lowercase(),
                amountSompi = amountSompi,
                address = contactId,
                memo = PaymentPoolProtocol.noticeMemo(memo)
            )
        )
        scope.launch(NonCancellable) {
            try {
                // Queued under the account that paid: never signed by another (iOS 4f8e14bb). An
                // account switched meanwhile keeps it under the payer, for when it's back (iOS 5b7c772c).
                if (walletManager.getAddress() != owner) {
                    paymentNoticeOutbox.keep(
                        PaymentNoticeOutbox.UnsentPaymentNotice(contactAddress = contactId, payload = payload, txId = txId),
                        owner = owner,
                    )
                    return@launch
                }
                // Untagged, like iOS sendEnvelopeInternal: kchat:1:comm:, never a first-contact dm.
                walletService.sendKasiaMessage(contactId, payload, firstContactTag = false, owner = owner)
                Log.i(TAG, "Sent payment_notice for ${txId.take(12)}")
            } catch (e: SendOutcomeUnknownException) {
                // It may have landed: not kept, so it is never sent twice (audit 2026-11-25 P6).
                Log.w(TAG, "payment_notice for ${txId.take(12)} not confirmed; not resent")
            } catch (e: Exception) {
                // usually an empty chatting address: kept, and sent once it has KAS (IOS-086)
                Log.w(TAG, "payment_notice send failed for ${txId.take(12)}, kept for later: ${e.message}")
                paymentNoticeOutbox.keep(
                    PaymentNoticeOutbox.UnsentPaymentNotice(
                        contactAddress = contactId, payload = payload, txId = txId
                    ),
                    owner = owner,
                )
            }
        }
    }

    /** A payment From a spending address whose send got no answer: the notice it owes until the
     *  payment shows up (iOS `OwedSpendingNotice`, audit 2026-11-27 C5). [landed]: the payment
     *  is known to have landed, the notice waits only for [owner] to be signed in (audit A1). */
    internal data class OwedSpendingNotice(
        val contactAddress: String,
        val amountSompi: Long,
        val memo: String,
        val owner: String,
        val atMs: Long,
        val landed: Boolean = false,
    )

    /** The owed notices by txid, a week at most (iOS `owedSpendingNotices`), kept in [storage]
     *  so they survive the process (audit A1); none = memory only. */
    internal class OwedSpendingNotices(private val storage: Storage? = null) {
        /** Where the list lives between runs. */
        interface Storage {
            fun load(): String?
            fun save(json: String?)
        }

        private val gson = com.google.gson.Gson()

        /** Memory only, without [storage]. */
        private val memory = LinkedHashMap<String, OwedSpendingNotice>()

        /** The list as stored now: read again each time, so a Delete Account wipe
         *  ([removeAllData]) from elsewhere is never written back. */
        private fun load(): LinkedHashMap<String, OwedSpendingNotice> {
            val storage = storage ?: return memory
            val json = storage.load() ?: return LinkedHashMap()
            val parsed = runCatching {
                gson.fromJson<Map<String, OwedSpendingNotice?>>(json, object : com.google.gson.reflect.TypeToken<Map<String, OwedSpendingNotice?>>() {}.type)
            }.getOrNull().orEmpty()
            val out = LinkedHashMap<String, OwedSpendingNotice>()
            for ((txId, notice) in parsed) {
                // an entry missing a field (Gson leaves it null) is dropped, not kept broken
                @Suppress("SENSELESS_COMPARISON")
                if (notice != null && notice.contactAddress != null && notice.owner != null && notice.memo != null) out[txId] = notice
            }
            return out
        }

        private fun save(map: LinkedHashMap<String, OwedSpendingNotice>) {
            val storage = storage ?: return
            storage.save(if (map.isEmpty()) null else gson.toJson(map))
        }

        @Synchronized
        fun put(txId: String, notice: OwedSpendingNotice) {
            val map = load()
            map[txId.trim().lowercase()] = notice
            save(map)
        }

        @Synchronized
        fun get(txId: String): OwedSpendingNotice? = load()[txId.trim().lowercase()]

        @Synchronized
        fun txIds(): List<String> = load().keys.toList()

        @Synchronized
        fun txIdsOwnedBy(owner: String): List<String> =
            load().filterValues { it.owner.equals(owner, ignoreCase = true) }.keys.toList()

        /** Its payment landed: the notice now waits only for its owner. */
        @Synchronized
        fun markLanded(txId: String) {
            val map = load()
            val key = txId.trim().lowercase()
            val owed = map[key] ?: return
            if (owed.landed) return
            map[key] = owed.copy(landed = true)
            save(map)
        }

        /** The notice [txId] owes, when it is for [contactAddress] and [current] is the account
         *  that made it; null otherwise (it stays). Ones older than a week go first. */
        @Synchronized
        fun due(txId: String, contactAddress: String, current: String, nowMs: Long): OwedSpendingNotice? {
            val map = load()
            if (map.entries.removeAll { nowMs - it.value.atMs >= OWED_NOTICE_LIFETIME_MS }) save(map)
            val owed = map[txId.trim().lowercase()] ?: return null
            if (!owed.contactAddress.equals(contactAddress, ignoreCase = true)) return null
            if (!owed.owner.equals(current, ignoreCase = true)) return null
            return owed
        }

        @Synchronized
        fun remove(txId: String) {
            val map = load()
            if (map.remove(txId.trim().lowercase()) != null) save(map)
        }

        /** Everything [owner] owes: the account is going off this device. */
        @Synchronized
        fun removeOwner(owner: String) {
            val map = load()
            if (map.entries.removeAll { it.value.owner.equals(owner, ignoreCase = true) }) save(map)
        }

        companion object {
            private const val PREFS_NAME = "kachat_owed_spending_notices"
            private const val KEY = "owed"

            fun prefsStorage(context: android.content.Context): Storage {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                return object : Storage {
                    override fun load(): String? = prefs.getString(KEY, null)
                    override fun save(json: String?) {
                        if (json == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, json).apply()
                    }
                }
            }

            /** Delete Account: [owner]'s owed notices (memos included) leave this device. */
            fun removeAllData(context: android.content.Context, owner: String) {
                OwedSpendingNotices(prefsStorage(context)).removeOwner(owner)
            }

            /** Whether the payment of a notice owed since [atMs] is looked up again at [nowMs]:
             *  within [OWED_CHECK_WINDOW_MS] of the payment, or once ([firstCheck]) on a start or
             *  sign-in after that, while the notice is still kept. */
            fun keepsChecking(atMs: Long, nowMs: Long, firstCheck: Boolean): Boolean =
                nowMs - atMs < OWED_NOTICE_LIFETIME_MS && (firstCheck || nowMs - atMs < OWED_CHECK_WINDOW_MS)
        }
    }

    companion object {
        private const val TAG = "ChatPaymentSender"

        /** How long an owed notice waits for its payment to show up (iOS: a week). */
        internal const val OWED_NOTICE_LIFETIME_MS = 7 * 24 * 3600 * 1000L

        /** An owed notice's payment is looked up this often... */
        internal const val OWED_CHECK_INTERVAL_MS = 30_000L

        /** ...for this long after the payment (audit A1). */
        internal const val OWED_CHECK_WINDOW_MS = 15 * 60_000L
        private const val JITTER_MIN = 0.10
        private const val JITTER_MAX = 0.35
        private const val MAX_BASE_DELAY_SECONDS = 60.0

        /** How many times a payment waiting for confirmed coins is tried again (about 8 minutes,
         *  the delays doubling to a minute) before it is marked Failed (iOS 4f8e14bb). */
        internal const val MAX_DEFERRED_PAYMENT_RETRIES = 12

        /** iOS `noSpendableFundsYetMessage`. */
        const val NO_SPENDABLE_FUNDS_YET = "No spendable funds available yet. Wait for confirmations and try again."

        /** iOS `nextNoInputRetryDelay`: 1, 2, 4 .. seconds, at most 60, plus [jitter] of it. */
        internal fun noInputRetryDelaySeconds(attempt: Int, jitter: Double): Double {
            val base = min(MAX_BASE_DELAY_SECONDS, 2.0.pow((attempt - 1).coerceAtLeast(0).toDouble()))
            return base + base * jitter
        }

        /** iOS `isNoConfirmedInputsError`. */
        internal fun isNoConfirmedInputsError(message: String?): Boolean {
            val lowered = message?.lowercase() ?: return false
            return lowered.contains("no spendable funds available yet") ||
                lowered.contains("no confirmed spendable utxos available") ||
                lowered.contains("no spendable utxos available") ||
                lowered.contains("no utxos available")
        }

        /** iOS `isInsufficientFundsError`. */
        internal fun isInsufficientFundsError(error: Throwable): Boolean =
            error.message?.lowercase()?.contains("insufficient funds") == true
    }
}
