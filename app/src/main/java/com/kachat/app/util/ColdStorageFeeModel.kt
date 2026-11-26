package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * Cold Storage's coin selection and fee preview, exactly as KaPay builds a KasSigner send (iOS
 * 148a5331 `ColdStorageSendEngine`, from KaPay): largest coins first, a "Not confirmed" send's
 * coin leading ([leadingOutpoint]), a fold held back while another coin might keep the change
 * (iOS 2da5f4b `foldIfNothingBetter`), and too little left for a change output priced without one
 * ([ColdSelection.changeOutputPriced]). KaChat's own hot-wallet selector ([KaspaUtxoSelector]) has
 * drifted from KaPay's, so Cold Storage keeps KaPay's here rather than change it.
 */
object ColdUtxoSelector {
    /** The first coins that could send by giving their change to the fee, held back while a
     *  further coin might let that change stay: how many, their total and their fee. */
    private data class HeldFold(val inputCount: Int, val total: Long, val fee: Long)

    data class ColdSelection(
        val selectedUtxos: List<UtxoEntry>,
        val totalSelected: Long,
        val estimatedFee: Long,
        val finalAmount: Long,
        val changeAmount: Long,
        val requiredAmount: Long,
        /** Funds were enough, but no input set fit storage mass without paying real change away
         *  as fee. The send must be refused. */
        val storageMassBlocked: Boolean = false,
        /** False when the fee was priced for the recipient's output alone: what is left over is
         *  too little to pay for a change output, so there is none and it goes to the fee. */
        val changeOutputPriced: Boolean = true,
    ) {
        /** Change too small to keep: there is no output for it, so it goes to the fee (iOS 953b255
         *  `SendFeeEstimate.foldedChange`). */
        val foldedChangeSompi: Long
            get() {
                if (changeAmount <= 0 || changeAmount > KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI) return 0L
                if (!changeOutputPriced) return changeAmount
                val recipient = if (finalAmount > 0) listOf(finalAmount) else emptyList()
                val kept = KaspaMass.fitsStorageMass(selectedUtxos.map { it.utxoEntry.amount }, recipient + changeAmount)
                return if (kept) 0L else changeAmount
            }
    }

    fun select(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        extraFeeSompi: Long = 0L,
        /** "txid:index" of a coin this selection must spend first: one a "Not confirmed" send also
         *  spent, so this send and that one can't both land (iOS 5848fb0 `leadingOutpoint`). Null,
         *  or a coin not among [utxos], changes nothing. */
        leadingOutpoint: String? = null,
        /** Each input's signature-script size: a KasSigner quantum (KCC-3) source's carry the
         *  101-byte script ([KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN], iOS 961fc05a). */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
    ): ColdSelection {
        var totalSelected = 0L
        val selectedUtxos = mutableListOf<UtxoEntry>()
        var estimatedFee = 0L
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)

        var storageMassBlocked = false
        var changeOutputPriced = true
        var foldIfNothingBetter: HeldFold? = null
        fun feeWithoutChange(): Long = KaspaMass.calculateFee(
            KaspaMass.calculateMass(
                numInputs = selectedUtxos.size,
                outputScriptLens = listOf(recipientScriptLen),
                payloadSize = 0,
                sigScriptLens = KaspaMass.sigScriptLensFor(selectedUtxos.size, signatureScriptSize)
            ),
            feeRateSompiPerGram
        ) + extraFeeSompi
        // Largest first to keep the input count (and the mass) low; the leading coin, if any, first.
        val ordered = utxos.sortedByDescending { it.utxoEntry.amount }.let { sorted ->
            val lead = leadingOutpoint?.trim()?.lowercase()
            val index = if (lead == null) -1 else sorted.indexOfFirst {
                "${it.outpoint.transactionId}:${it.outpoint.index}".lowercase() == lead
            }
            if (index <= 0) sorted else listOf(sorted[index]) + sorted.filterIndexed { i, _ -> i != index }
        }
        for (utxo in ordered) {
            // One transaction can't take more inputs: the fold held back is the best there is.
            if (foldIfNothingBetter != null && selectedUtxos.size >= KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION) break
            selectedUtxos.add(utxo)
            // Node-supplied: a nonsense amount is refused, never wrapped into a total (IOS-020).
            totalSelected = UtxoMath.add(totalSelected, utxo.utxoEntry.amount)

            val mass = KaspaMass.calculateMass(
                numInputs = selectedUtxos.size,
                outputScriptLens = outputScriptLens,
                payloadSize = 0,
                sigScriptLens = KaspaMass.sigScriptLensFor(selectedUtxos.size, signatureScriptSize)
            )
            estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + extraFeeSompi

            if (totalSelected >= (amountSompi + estimatedFee)) {
                val change = totalSelected - amountSompi - estimatedFee
                val inputAmounts = selectedUtxos.map { it.utxoEntry.amount }
                if (amountSompi <= 0 || KaspaMass.fitsStorageMass(inputAmounts, listOf(amountSompi, change))) {
                    foldIfNothingBetter = null
                    storageMassBlocked = false
                    break
                }
                if (KaspaUtxoSelector.changeIsKeptOrFoldable(inputAmounts, listOf(amountSompi), change)) {
                    // Worth less than this transaction's fee it really is dust; worth more, a
                    // further coin may let the change stay (iOS 2da5f4b).
                    if (totalSelected - amountSompi - feeWithoutChange() <= estimatedFee) {
                        foldIfNothingBetter = null
                        storageMassBlocked = false
                        break
                    }
                    if (foldIfNothingBetter == null) foldIfNothingBetter = HeldFold(selectedUtxos.size, totalSelected, estimatedFee)
                    continue
                }
                storageMassBlocked = true
            } else if (amountSompi > 0) {
                // Too little left to pay for a change output (a Max send): priced without one, the
                // leftover goes to the fee. The recipient's amount is never cut to fit.
                val feeNoChange = feeWithoutChange()
                if (totalSelected >= amountSompi + feeNoChange) {
                    val leftover = totalSelected - amountSompi - feeNoChange
                    if (leftover <= KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI &&
                        KaspaMass.fitsStorageMass(selectedUtxos.map { it.utxoEntry.amount }, listOf(amountSompi))
                    ) {
                        estimatedFee = feeNoChange
                        changeOutputPriced = false
                        foldIfNothingBetter = null
                        storageMassBlocked = false
                        break
                    }
                    storageMassBlocked = true
                }
            }
        }

        // No further coin let the change stay: the first set that could send, as it was.
        foldIfNothingBetter?.let { fold ->
            selectedUtxos.subList(fold.inputCount, selectedUtxos.size).clear()
            totalSelected = fold.total
            estimatedFee = fold.fee
            changeOutputPriced = true
            storageMassBlocked = false
        }

        val requiredAmount = amountSompi + estimatedFee
        val changeAmount = totalSelected - amountSompi - estimatedFee
        return ColdSelection(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = amountSompi,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount,
            storageMassBlocked = storageMassBlocked,
            changeOutputPriced = changeOutputPriced,
        )
    }

    /** Coin control: prices and validates a picked, fixed set of coins, with the same pricing as
     *  [select]; the amount itself is never cut. */
    fun selectManual(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        extraFeeSompi: Long = 0L,
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
    ): ColdSelection {
        val totalSelected = UtxoMath.total(utxos.map { it.utxoEntry.amount })
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)
        val mass = KaspaMass.calculateMass(
            numInputs = utxos.size,
            outputScriptLens = outputScriptLens,
            payloadSize = 0,
            sigScriptLens = KaspaMass.sigScriptLensFor(utxos.size, signatureScriptSize)
        )
        var estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + extraFeeSompi
        var changeOutputPriced = true
        if (amountSompi > 0 && totalSelected < amountSompi + estimatedFee) {
            val feeNoChange = KaspaMass.calculateFee(
                KaspaMass.calculateMass(
                    numInputs = utxos.size,
                    outputScriptLens = listOf(recipientScriptLen),
                    payloadSize = 0,
                    sigScriptLens = KaspaMass.sigScriptLensFor(utxos.size, signatureScriptSize)
                ),
                feeRateSompiPerGram
            ) + extraFeeSompi
            if (totalSelected >= amountSompi + feeNoChange) {
                estimatedFee = feeNoChange
                changeOutputPriced = false
            }
        }
        val requiredAmount = amountSompi + estimatedFee
        val changeAmount = totalSelected - amountSompi - estimatedFee
        // Real change that can't stand, or a recipient output that doesn't fit alone, refuses the
        // send (iOS 0799cec).
        val storageMassBlocked = amountSompi > 0 && totalSelected >= requiredAmount && changeAmount >= 0 &&
            !KaspaUtxoSelector.changeIsKeptOrFoldable(utxos.map { it.utxoEntry.amount }, listOf(amountSompi), changeAmount)
        return ColdSelection(
            selectedUtxos = utxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = amountSompi,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount,
            storageMassBlocked = storageMassBlocked,
            changeOutputPriced = changeOutputPriced,
        )
    }
}

/**
 * Cold Storage's Send screen fee model, KaPay's (`SendFeeModel` there): a base fee at the quoted
 * rate on the real input count, plus Fast / Priority / a custom fee as a flat extra, paid exactly.
 * Unlike KaChat's hot-wallet [SendFeeModel], no 3-sompi buffer: the cold build adds none.
 */
object ColdSendFeeModel {
    private const val MAX_PREVIEW_PASSES = 16

    /** Max for a send that spends [totalSompi] over [inputCount] inputs: what is left after the
     *  base fee and the extra derived from it (or the custom extra). */
    fun maxAfterFees(
        totalSompi: Long,
        inputCount: Int,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        recipientScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
    ): Long {
        val base = SendFeeModel.baseFee(
            inputCount, feeRateSompiPerGram, 0,
            recipientScriptLen = recipientScriptLen, changeScriptLen = changeScriptLen,
            signatureScriptSize = signatureScriptSize,
        )
        val extra = SendFeeModel.extraFee(base, feeMultiplier, customExtraFeeSompi)
        return (totalSompi - base - extra).coerceAtLeast(0L)
    }

    /** What an automatic-selection send of a given amount spends, and what it pays. Passing
     *  [utxos] as coin control and [extraFeeSompi] as the extra reproduces exactly this fee. */
    data class Preview(
        val utxos: List<UtxoEntry>,
        val baseFeeSompi: Long,
        val extraFeeSompi: Long,
        val foldedChangeSompi: Long = 0L,
        /** No coin set fits storage mass for this amount: the send would be refused. */
        val storageMassBlocked: Boolean = false,
    ) {
        val totalFeeSompi: Long get() = baseFeeSompi + extraFeeSompi
    }

    /** Everything a coin-control send of exactly [utxos] pays: the fee as the build prices it, the
     *  extra, and change too small to keep. Null when the coins can't be priced ([UtxoMath]). */
    fun manualTotalFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        extraFeeSompi: Long,
        recipientScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
    ): Long? = try {
        ColdUtxoSelector.selectManual(
            utxos = utxos,
            amountSompi = amountSompi,
            feeRateSompiPerGram = feeRateSompiPerGram,
            recipientScriptLen = recipientScriptLen,
            changeScriptLen = changeScriptLen,
            extraFeeSompi = extraFeeSompi,
            signatureScriptSize = signatureScriptSize,
        ).let { it.estimatedFee + it.foldedChangeSompi }
    } catch (e: IllegalStateException) {
        null
    }

    /**
     * What automatic selection picks for [amountSompi], with the base fee priced on those inputs
     * and the extra on top - the extra changes how many inputs are needed, and a tier's extra
     * depends on the base, so the two are settled together. Null when the coins can't cover it, or
     * their amounts are not real values ([UtxoMath]).
     */
    fun previewAutomaticSelection(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        recipientScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = SendFeeModel.STANDARD_SCRIPT_LEN,
        leadingOutpoint: String? = null,
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
        /** Every coin in [utxos] is spent (a KasSigner quantum source, iOS 961fc05a: no coin may
         *  stay behind the key the spend reveals), priced as coin control is. */
        spendAll: Boolean = false,
    ): Preview? {
        if (amountSompi <= 0 || utxos.isEmpty()) return null
        var extra = customExtraFeeSompi?.coerceAtLeast(0L) ?: 0L
        var preview: Preview? = null
        repeat(MAX_PREVIEW_PASSES) {
            val selection = try {
                if (spendAll) {
                    ColdUtxoSelector.selectManual(
                        utxos = utxos,
                        amountSompi = amountSompi,
                        feeRateSompiPerGram = feeRateSompiPerGram,
                        recipientScriptLen = recipientScriptLen,
                        changeScriptLen = changeScriptLen,
                        extraFeeSompi = extra,
                        signatureScriptSize = signatureScriptSize,
                    )
                } else ColdUtxoSelector.select(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptLen,
                    changeScriptLen = changeScriptLen,
                    extraFeeSompi = extra,
                    leadingOutpoint = leadingOutpoint,
                    signatureScriptSize = signatureScriptSize,
                )
            } catch (e: IllegalStateException) {
                return null // invalid node data (UtxoMath): no preview rather than a wrong one
            }
            if (selection.totalSelected < selection.requiredAmount) return null
            val base = selection.estimatedFee - extra
            val settled = Preview(selection.selectedUtxos, base, extra, selection.foldedChangeSompi, selection.storageMassBlocked)
            preview = settled
            val next = SendFeeModel.extraFee(base, feeMultiplier, customExtraFeeSompi)
            if (next == extra) return settled
            extra = next
        }
        return preview
    }
}
