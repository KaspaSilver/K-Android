package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.PendingKnsCommit
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KnsInscriptionScript
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KNS left K on 2026-11-23 (.kachat only, iOS 518fdc8c), but a KNS commit whose reveal failed
 * before then still holds the user's coins at a script address that only the saved redeem script
 * can spend. This is the part of the old KNS commit/reveal engine that finishes those reveals,
 * which returns the coins to the address that funded them (iOS `KNSRevealRecovery`, audit
 * 2026-11-24 #4). It starts nothing new and runs only while a saved record is left
 * ([AppSettingsRepository.pendingKnsCommit]); once that is settled it does nothing. Remove it a
 * few releases after no record is left in the field.
 */
@Singleton
class KnsRevealRecovery @Inject constructor(
    private val networkService: NetworkService,
    private val nodePoolManager: NodePoolManager,
    private val settings: AppSettingsRepository,
    /** When the pending commit was saved, kept beside it: [PendingKnsCommit] has no field for it. */
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    /** The reveal of a commit: what it spends and what the redeem script holds. */
    private class CommitContext(
        val commitTxId: String,
        val redeemScript: ByteArray,
        val commitScriptPubKeyHex: String,
        val commitAmountSompi: Long,
        val revealAmountSompi: Long,
    )

    // One reveal at a time.
    private val mutex = Mutex()

    private val recoveryPrefs get() = appContext.getSharedPreferences(RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)

    private suspend fun clearPending() {
        settings.clearPendingKnsCommit()
        recoveryPrefs.edit().clear().apply()
    }

    /** How long ago [commitTxId] was saved. One saved before this was kept counts from now. */
    private fun pendingAgeMs(commitTxId: String): Long {
        val now = System.currentTimeMillis()
        if (recoveryPrefs.getString(KEY_SAVED_ID, null) != commitTxId) {
            recoveryPrefs.edit().clear().putString(KEY_SAVED_ID, commitTxId).putLong(KEY_SAVED_AT, now).apply()
            return 0L
        }
        return now - recoveryPrefs.getLong(KEY_SAVED_AT, now)
    }

    /** The resume bookkeeping kept beside the pending commit (iOS `commitSeen`, `emptyChecks`):
     *  whether its coin was ever seen unspent, and how many looks in a row since found none. Read
     *  after [pendingAgeMs], which ties the record to the commit. */
    private fun commitSeen(): Boolean = recoveryPrefs.getBoolean(KEY_COMMIT_SEEN, false)
    private fun emptyChecks(): Int = recoveryPrefs.getInt(KEY_EMPTY_CHECKS, 0)
    private fun setResumeState(seen: Boolean, emptyChecks: Int) {
        recoveryPrefs.edit().putBoolean(KEY_COMMIT_SEEN, seen).putInt(KEY_EMPTY_CHECKS, emptyChecks).apply()
    }

    /** The commit whose reveal hasn't been accepted yet, left by an earlier version. */
    suspend fun pendingCommit(): PendingKnsCommit? = settings.pendingKnsCommit.first()

    /**
     * Finishes the reveal of [pending]. Spending the commit output is the reveal, whatever the
     * outputs (the redeem script holds the inscription), so there is no separate refund: finishing
     * the reveal is what returns the coins, to the address that funded them, minus the fee (iOS
     * `KNSRevealRecovery.resume`). True when it needs no further look: finished, or dropped as
     * gone.
     */
    suspend fun resumeReveal(pending: PendingKnsCommit, ownerPrivateKey: ByteArray): Boolean = mutex.withLock {
        val label = pending.commitTxId.take(12)
        try {
            val redeemScript = pending.redeemScriptHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val hrp = pending.revealTargetAddress.substringBefore(":")
            val commitAddress = KnsInscriptionScript.commitAddress(redeemScript, hrp)
            val utxos = nodePoolManager.getUtxosByAddress(commitAddress) ?: return@withLock false
            val isLive = utxos.any { it.outpoint.transactionId.equals(pending.commitTxId, ignoreCase = true) && it.outpoint.index == 0 }
            val ageMs = pendingAgeMs(pending.commitTxId)
            val seen = commitSeen()
            val emptyChecks = emptyChecks()
            if (!isLive) {
                // The pending commit is the only copy of the redeem script that unlocks the coin,
                // so it is dropped only when the commit is surely spent.
                val counted = emptyChecks + 1
                if (missingCommitVerdict(seen, counted, ageMs) == MissingCommit.KEEP) {
                    setResumeState(seen, counted)
                    return@withLock false
                }
                Log.i(TAG, if (seen) "Commit $label was spent: nothing to finish" else "Commit $label never appeared: dropped")
                clearPending()
                return@withLock true
            }
            // seen unspent: any earlier empty answers were a lagging node
            if (!seen || emptyChecks > 0) setResumeState(seen = true, emptyChecks = 0)
            val commit = CommitContext(pending.commitTxId, redeemScript, pending.commitScriptPubKeyHex, pending.commitAmountSompi, pending.revealAmountSompi)
            val signedTx = buildSignedReveal(
                commit, pending.revealTargetAddress, pending.changeAddress ?: pending.revealTargetAddress, ownerPrivateKey, REVEAL_PRIORITY_FEE_SOMPI
            )
            val revealTxId = submitRevealRetrying(signedTx)
            Log.i(TAG, "Finished commit $label with reveal ${revealTxId.take(12)}")
            clearPending()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.w(TAG, "Could not finish commit $label: ${e.message}")
            false
        }
    }

    /** A few tries with a pause between: the commit may still be reaching the node (iOS
     *  `submitRetrying`). An unknown outcome is passed on at once. */
    private suspend fun submitRevealRetrying(signedTx: RawTransaction): String {
        var last: Exception = IllegalStateException("KNS reveal was not submitted")
        for (attempt in 0 until REVEAL_ATTEMPTS) {
            if (attempt > 0) delay(minOf(attempt * 2, 8) * 1_000L)
            try {
                return submitRevealAllowingOrphan(signedTx)
            } catch (unknown: SendOutcomeUnknownException) {
                throw unknown
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "Reveal attempt ${attempt + 1} failed: ${e.message}")
                last = e
            }
        }
        throw last
    }

    private suspend fun submitRevealAllowingOrphan(signedTx: RawTransaction): String =
        nodePoolManager.submitConfirmingKnown(signedTx, networkService.kaspaRestApi.value) {
            try {
                // an orphan rejection first waits for the commit to reach that node
                nodePoolManager.submitWaitingForParent(signedTx, allowOrphan = false)
            } catch (e: Exception) {
                if (e.message?.contains("orphan", ignoreCase = true) == true) {
                    Log.w(TAG, "Reveal rejected as orphan, retrying with allowOrphan=true", e)
                    nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = true)
                } else {
                    throw e
                }
            }
        }

    /** The reveal of [commit], signed (iOS `KaChatTransactionBuilder.buildKNSAddProfileRevealTx`):
     *  the commit output in, the reveal amount (if any) to [revealTargetAddress], the rest less the
     *  fee back to [changeAddress] when it stands on its own. */
    private fun buildSignedReveal(
        commit: CommitContext,
        revealTargetAddress: String,
        changeAddress: String,
        ownerPrivateKey: ByteArray,
        priorityFeeSompi: Long
    ): RawTransaction {
        val targetScriptHex = KaspaAddress.getScriptPublicKey(revealTargetAddress)
        val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress)
        // The network minimum, the priority fee on top.
        val feeRateSompiPerGram = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM

        val recipientOutput = if (commit.revealAmountSompi > 0) {
            RawOutputWithVersion(amount = commit.revealAmountSompi, scriptPublicKey = ScriptPublicKeyWithVersion(targetScriptHex, 0))
        } else null
        val baseOutputs = listOfNotNull(recipientOutput)

        // Signature script is push(sig+hashtype) + push(redeemScript) — much bigger than the
        // standard 66-byte Schnorr push, so mass/fee must account for the real size.
        val sigScriptSize = 66L + KnsInscriptionScript.canonicalPushSize(commit.redeemScript.size)

        require(commit.commitAmountSompi >= commit.revealAmountSompi) {
            "KNS reveal amount cannot exceed the commit amount"
        }

        val massWithChange = KaspaMass.calculateMass(
            numInputs = 1,
            outputScriptLens = (baseOutputs.map { it.scriptPublicKey.scriptPublicKey.length / 2 }) + (changeScriptHex.length / 2),
            payloadSize = 0,
            sigScriptLens = listOf(sigScriptSize)
        )
        val feeWithChange = KaspaMass.calculateFee(massWithChange, feeRateSompiPerGram) + priorityFeeSompi

        val outputs = baseOutputs.toMutableList()
        val availableForChangeAndFee = commit.commitAmountSompi - commit.revealAmountSompi

        require(availableForChangeAndFee >= feeWithChange) { "KNS reveal amount cannot be covered by commit output" }
        val changeWithFee = availableForChangeAndFee - feeWithChange

        // The reveal spends exactly one input, the commit output, so the shape is fully known
        // here: that input against the reveal output (if any) plus this change. Decided by KIP-9
        // storage mass (see KaspaMass.storageMass), not a flat floor.
        val revealInputs = listOf(commit.commitAmountSompi)
        val baseAmounts = baseOutputs.map { it.amount }
        if (changeWithFee > 0 && KaspaMass.fitsStorageMass(revealInputs, baseAmounts + changeWithFee)) {
            outputs.add(RawOutputWithVersion(amount = changeWithFee, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
        } else {
            // Change would not stand on its own — recompute the fee for a transaction with no
            // change output (lower mass, so a lower fee), and see if THAT leaves enough instead.
            val massNoChange = KaspaMass.calculateMass(
                numInputs = 1,
                outputScriptLens = baseOutputs.map { it.scriptPublicKey.scriptPublicKey.length / 2 },
                payloadSize = 0,
                sigScriptLens = listOf(sigScriptSize)
            )
            val feeNoChange = KaspaMass.calculateFee(massNoChange, feeRateSompiPerGram) + priorityFeeSompi
            require(availableForChangeAndFee >= feeNoChange) { "Insufficient commit amount for KNS reveal fee" }
            val changeNoChange = availableForChangeAndFee - feeNoChange
            if (changeNoChange > 0 && KaspaMass.fitsStorageMass(revealInputs, baseAmounts + changeNoChange)) {
                outputs.add(RawOutputWithVersion(amount = changeNoChange, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
            }
        }
        require(outputs.isNotEmpty()) { "KNS reveal transaction has no spendable outputs after fees" }

        val commitUtxo = UtxoEntry(
            address = changeAddress,
            outpoint = Outpoint(transactionId = commit.commitTxId, index = 0),
            utxoEntry = UtxoData(
                amount = commit.commitAmountSompi,
                scriptPublicKey = ScriptPublicKey(commit.commitScriptPubKeyHex),
                blockDaaScore = 0,
                isCoinbase = false
            )
        )

        val rawTx = RawTransaction(
            inputs = listOf(RawInput(previousOutpoint = commitUtxo.outpoint, signatureScript = "")),
            outputs = outputs
        )
        return KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, commit.redeemScript, ownerPrivateKey)
    }

    companion object {
        private const val TAG = "KnsRevealRecovery"

        /** What the reveal pays on top of the network minimum, as the old engine did. */
        const val REVEAL_PRIORITY_FEE_SOMPI = 2_000_000L

        private const val REVEAL_ATTEMPTS = 5
        /** A commit never seen is dropped only after this long (and [MISSING_COMMIT_CHECKS] empty
         *  looks): the pending record is the only copy of its redeem script (iOS
         *  `missingCommitLifetime`, 30 days). */
        internal const val MISSING_COMMIT_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
        private const val RECOVERY_PREFS = "kns_reveal_recovery"
        private const val KEY_SAVED_ID = "commit_id"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_COMMIT_SEEN = "commit_seen"
        private const val KEY_EMPTY_CHECKS = "empty_checks"

        /** Empty looks in a row before a seen commit counts as spent: one lagging node's empty
         *  answer isn't enough. */
        private const val SPENT_COMMIT_CHECKS = 2
        /** Empty looks a never-seen commit also needs, past [MISSING_COMMIT_LIFETIME_MS]. */
        private const val MISSING_COMMIT_CHECKS = 2

        internal enum class MissingCommit { KEEP, DROP }

        /**
         * What a resume does with a commit whose coin it didn't find (iOS
         * `KNSRevealRecovery.resume`): one seen unspent before and now gone was spent - by its
         * reveal, normally - and is dropped after [SPENT_COMMIT_CHECKS] empty looks in a row; one
         * never seen is dropped only once it is older than [MISSING_COMMIT_LIFETIME_MS] and has
         * had [MISSING_COMMIT_CHECKS] empty looks - a slow commit or a node that missed it must
         * not lose it. [emptyChecks] counts this look.
         */
        internal fun missingCommitVerdict(seen: Boolean, emptyChecks: Int, ageMs: Long): MissingCommit = when {
            seen -> if (emptyChecks >= SPENT_COMMIT_CHECKS) MissingCommit.DROP else MissingCommit.KEEP
            ageMs > MISSING_COMMIT_LIFETIME_MS && emptyChecks >= MISSING_COMMIT_CHECKS -> MissingCommit.DROP
            else -> MissingCommit.KEEP
        }
    }
}
