package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.UtxoMath
import javax.inject.Inject
import javax.inject.Singleton

/** A signed transaction that has NOT been submitted: its id, its bytes, and what it spends. */
data class KaPostSignedPayloadTx(
    val txId: String,
    val transaction: RawTransaction,
    val spentOutpoints: List<String>,
)

/**
 * A scheduled KaPost's transaction: signed now, sent later (KAPOSTS_INDEXER.md section 5.10; iOS
 * 148a5331 `KaPostsAPIClient.buildScheduledTransaction`). KaPost built it inside its wallet engine;
 * here it is built from the engine's public pieces, so KaChat's engine stays as it is.
 *
 * Confirmed, non-coinbase coins only (a transaction that may wait hours must not chain onto an
 * unconfirmed change output), and the SMALLEST single coin that can carry it. When the only way to
 * carry it would hold every confirmed coin until the post's time and they hold more than 2 KAS,
 * 1 KAS is first sent to the address itself and the post scheduled on that new coin.
 * The coins are held out of every other pick at once, and [KaPostsScheduledStore] keeps holding
 * them while the post waits.
 */
@Singleton
class KaPostScheduledTxBuilder @Inject constructor(
    private val walletManager: WalletManager,
    private val walletEngine: KaspaWalletEngine,
    private val walletService: WalletService,
) {
    suspend fun buildSignedPayloadSelfSend(payloadBytes: ByteArray): Result<KaPostSignedPayloadTx> {
        var key: ByteArray? = null
        try {
            val fromAddress = walletManager.getAddress()
            val confirmed = KaPostsScheduledStore.filterReserved(walletEngine.fetchUtxosOrThrow(fromAddress))
                .filter { it.utxoEntry.blockDaaScore > 0L && !it.utxoEntry.isCoinbase && it.utxoEntry.covenantId == null }
                .sortedBy { it.utxoEntry.amount }
            if (confirmed.isEmpty()) {
                return Result.failure(IllegalStateException("No confirmed coins are available to schedule a post with. Wait for a confirmation and try again."))
            }
            val feeRate = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
            val scriptHex = KaspaAddress.getScriptPublicKey(fromAddress)
            val privateKey = walletManager.getPrivateKeyBytes().also { key = it }

            // the smallest single coin that can carry it
            val single = confirmed.firstNotNullOfOrNull { coin ->
                payloadSelfSendOnExactly(listOf(coin), payloadBytes, feeRate, scriptHex)?.let { it to coin }
            }

            // The only way to carry it would hold every confirmed coin until the post's time: split
            // a small coin off first and schedule on that.
            val holdsEverything = single == null || confirmed.size == 1
            val total = UtxoMath.total(confirmed.map { it.utxoEntry.amount })
            if (holdsEverything && total > SCHEDULED_POST_COIN_SOMPI * 2) {
                val coin = try {
                    splitOffCoin(fromAddress, scriptHex)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't split a coin off for a scheduled post: ${e.message}")
                    null
                }
                val onCoin = coin?.let { payloadSelfSendOnExactly(listOf(it), payloadBytes, feeRate, scriptHex) }
                if (coin != null && onCoin != null) return Result.success(signAndHold(onCoin, listOf(coin), privateKey))
            }

            single?.let { (tx, coin) -> return Result.success(signAndHold(tx, listOf(coin), privateKey)) }

            // Every coin it needs, as a post picks them (one transaction's worth).
            val selection = KaspaUtxoSelector.selectUtxosAndCalculateFee(
                utxos = confirmed,
                amountSompi = 0,
                feeRateSompiPerGram = feeRate,
                payloadBytes = payloadBytes,
                recipientScriptLen = scriptHex.length / 2,
                changeScriptLen = scriptHex.length / 2,
            )
            if (selection.totalSelected < selection.requiredAmount) {
                return Result.failure(IllegalStateException("Insufficient funds for payment"))
            }
            if (selection.selectedUtxos.size > KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION) {
                return Result.failure(IllegalStateException(
                    "This needs more than ${KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION} inputs. Compound (consolidate) this address's coins first."
                ))
            }
            val inputAmounts = selection.selectedUtxos.map { it.utxoEntry.amount }
            if (selection.changeAmount <= 0 || !KaspaMass.fitsStorageMass(inputAmounts, listOf(selection.changeAmount))) {
                return Result.failure(IllegalStateException("Insufficient funds to cover the network fee"))
            }
            val rawTx = payloadSelfSendTx(selection.selectedUtxos, selection.changeAmount, payloadBytes, scriptHex)
            return Result.success(signAndHold(rawTx, selection.selectedUtxos, privateKey))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not build a scheduled transaction", e)
            return Result.failure(e)
        } finally {
            key?.fill(0)
        }
    }

    /** Submits a scheduled post signed earlier, from this phone (the indexer never took it). */
    suspend fun submitSignedTransaction(transaction: RawTransaction): String {
        val txId = walletEngine.submitSignedTransaction(transaction)
        try { walletService.refreshBalance() } catch (_: Exception) {}
        return txId
    }

    /** The unsigned payload self-send spending exactly [coins], or null when they can't carry it. */
    private fun payloadSelfSendOnExactly(coins: List<UtxoEntry>, payloadBytes: ByteArray, feeRate: Long, scriptHex: String): RawTransaction? {
        val mass = KaspaMass.calculateMass(
            numInputs = coins.size,
            outputScriptLens = listOf(scriptHex.length / 2),
            payloadSize = payloadBytes.size,
        )
        if (mass > KaspaMass.MAX_STANDARD_MASS) return null
        val fee = KaspaMass.calculateFee(mass, feeRate)
        val change = UtxoMath.total(coins.map { it.utxoEntry.amount }) - fee
        if (change <= 0 || !KaspaMass.fitsStorageMass(coins.map { it.utxoEntry.amount }, listOf(change))) return null
        return payloadSelfSendTx(coins, change, payloadBytes, scriptHex)
    }

    private fun payloadSelfSendTx(coins: List<UtxoEntry>, change: Long, payloadBytes: ByteArray, scriptHex: String) = RawTransaction(
        inputs = coins.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
        outputs = listOf(RawOutputWithVersion(amount = change, scriptPublicKey = ScriptPublicKeyWithVersion(scriptHex, 0))),
        gas = 0,
        payload = payloadBytes.joinToString("") { "%02x".format(it) },
    )

    /** Signs [rawTx] and holds its coins out of every pick right away. */
    private fun signAndHold(rawTx: RawTransaction, coins: List<UtxoEntry>, privateKey: ByteArray): KaPostSignedPayloadTx {
        val signedTx = KaspaTransactionSigner.signTransaction(rawTx = rawTx, utxos = coins, privateKey = privateKey)
        walletEngine.reserveSpentOutpoints(signedTx.inputs.map { it.previousOutpoint })
        return KaPostSignedPayloadTx(
            txId = com.kachat.app.util.KaspaTransactionId.compute(signedTx),
            transaction = signedTx,
            spentOutpoints = signedTx.inputs.map { "${it.previousOutpoint.transactionId}:${it.previousOutpoint.index}" },
        )
    }

    /** Sends [SCHEDULED_POST_COIN_SOMPI] to [address] itself (a plain send, its output 0) and
     *  returns that output as a coin. Unconfirmed for a second or so, which is fine: the post
     *  spending it goes out later. */
    private suspend fun splitOffCoin(address: String, scriptHex: String): UtxoEntry {
        val txId = walletService.sendKaspa(toAddress = address, amountSompi = SCHEDULED_POST_COIN_SOMPI)
        Log.i(TAG, "Split a coin off for a scheduled post: ${txId.take(12)}")
        return UtxoEntry(
            address = address,
            outpoint = Outpoint(transactionId = txId, index = 0),
            utxoEntry = UtxoData(
                amount = SCHEDULED_POST_COIN_SOMPI,
                scriptPublicKey = ScriptPublicKey(scriptHex),
                blockDaaScore = 0,
                isCoinbase = false,
            ),
        )
    }

    companion object {
        private const val TAG = "KaPostScheduledTx"
        /** The coin a scheduled post is split onto: 1 KAS (iOS 369794e). */
        const val SCHEDULED_POST_COIN_SOMPI = 100_000_000L
    }
}

/** How a failed submit of a KaPost transaction reads (KaPost's `NodeRejectedTransactionException`,
 *  which KaChat's node pool doesn't throw). */
object KaPostSubmitErrors {
    /**
     * Whether [e] is a node's verdict on the transaction (turned down), rather than no answer: an
     * offline phone, a connection that never opened, a lost answer or a timeout keep a scheduled
     * post waiting for the next try.
     */
    fun isNodeVerdict(e: Throwable): Boolean {
        if (e is SendOutcomeUnknownException) return false
        if (SubmitConfirmation.isLostAnswer(e)) return false
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 16) {
            if (current is java.io.IOException || current is kotlinx.coroutines.TimeoutCancellationException ||
                current is io.grpc.StatusException || current is io.grpc.StatusRuntimeException
            ) return false
            if (current.cause === current) break
            current = current.cause
            depth++
        }
        return true
    }
}
