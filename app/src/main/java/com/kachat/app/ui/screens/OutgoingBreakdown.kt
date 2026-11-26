package com.kachat.app.ui.screens

/**
 * Where an outgoing transaction's Kaspa went, by owner (iOS `SpendingAddressTransactionHistoryView`
 * `OutgoingBreakdown`, 2026-11-22): to other people, and to this wallet's other addresses (the
 * change), so a spending address's Sent row adds up - an address got 10, sent 1, and the 8.99 of
 * change to another of the wallet's addresses wasn't shown.
 */
data class OutgoingBreakdown(
    /** Everything that went to addresses that aren't this wallet's: the row's "Sent" amount. */
    val toOthers: Long = 0L,
    /** This wallet's addresses other than the one whose history this is, by label, in output order. */
    val change: List<Pair<String, Long>> = emptyList(),
    /** Set when the wallet came out ahead on a transaction this address sent in (a swap that paid
     *  it Kaspa): the row is Received, by this much, with no change lines (iOS 961fc05a). */
    val receivedSompi: Long? = null,
) {
    val changeTotal: Long get() = change.sumOf { it.second }

    /** All of it went to this wallet's own addresses: the row says "Moved", not "Sent". */
    val moved: Boolean get() = toOthers == 0L && changeTotal > 0L

    /** The row's amount: what was moved, or what went to other people. */
    val amountSompi: Long get() = if (moved) changeTotal else toOthers

    companion object {
        /**
         * Splits [outputs] (address, sompi) of a transaction [thisAddress] sent. Outputs back to
         * [thisAddress] and ones with no address are skipped; [ownLabel] names this wallet's own
         * addresses ("Address #15", its name, "Chatting Address") and is null for anyone else's.
         * Outputs to the same own address (by label) add up into one line.
         */
        fun of(
            thisAddress: String,
            outputs: List<Pair<String, Long>>,
            ownLabel: (String) -> String?,
        ): OutgoingBreakdown {
            val self = thisAddress.lowercase()
            var toOthers = 0L
            val change = mutableListOf<Pair<String, Long>>()
            for ((rawAddress, amount) in outputs) {
                val address = rawAddress.lowercase()
                if (address.isEmpty() || address == self) continue
                val label = ownLabel(address)
                if (label != null) {
                    val at = change.indexOfFirst { it.first == label }
                    if (at >= 0) change[at] = label to change[at].second + amount
                    else change += label to amount
                } else {
                    toOthers += amount
                }
            }
            return OutgoingBreakdown(toOthers, change)
        }

        /**
         * An outgoing history row's figures when the transaction also spent coins that aren't
         * this wallet's (iOS 961fc05a `ownHistoryAmount`): a kUSDT swap spends Trestly's pool,
         * which pays its whole balance back to itself, and that output counted as "Sent"
         * (-367,808 KAS for a swap of a few KAS, owner 2026-11-25). Works from what left this
         * wallet instead: its inputs, less what came back to it (this address and the change to
         * the wallet's other addresses) and its part of the network fee, never more than went to
         * others. When the wallet came out ahead the row is Received, by that much.
         *
         * Each of several funding addresses shows the whole send (audit 2026-11-25 L2): coins
         * aren't tied to outputs. The fee: with only this wallet's coins in, it paid all of it;
         * with another party's coins in too, its share by amount; unknown ([feeSompi] null) or an
         * outside input of unknown amount, none taken off.
         */
        fun withOwnHistory(
            base: OutgoingBreakdown,
            thisAddress: String,
            inputs: List<Pair<String, Long?>>,
            outputs: List<Pair<String, Long>>,
            feeSompi: Long?,
            isOwnAddress: (String) -> Boolean,
        ): OutgoingBreakdown {
            val own = ownHistoryAmount(thisAddress, inputs, outputs, feeSompi, base.toOthers, base.changeTotal, isOwnAddress)
                ?: return base
            return if (own.first) base.copy(toOthers = own.second) else OutgoingBreakdown(receivedSompi = own.second)
        }

        /** (isOutgoing, sompi), or null to keep the caller's own figure: no input from
         *  [thisAddress], one of this wallet's inputs with no known amount, or it came out even. */
        internal fun ownHistoryAmount(
            thisAddress: String,
            inputs: List<Pair<String, Long?>>,
            outputs: List<Pair<String, Long>>,
            feeSompi: Long?,
            toOthers: Long,
            ownChange: Long,
            isOwnAddress: (String) -> Boolean,
        ): Pair<Boolean, Long>? {
            val self = thisAddress.lowercase()
            var spentHere = 0L
            var spentByWallet = 0L
            var inputTotal = 0L
            var allInputsKnown = true
            var outsideInputs = false
            for ((rawAddress, amount) in inputs) {
                val address = rawAddress.lowercase()
                val here = address == self
                val ours = here || (address.isNotEmpty() && isOwnAddress(address))
                if (!ours) outsideInputs = true
                if (amount == null) {
                    if (ours) return null
                    allInputsKnown = false
                    continue
                }
                inputTotal += amount
                if (here) spentHere += amount
                if (ours) spentByWallet += amount
            }
            if (spentHere <= 0L) return null
            val returnedHere = outputs.filter { it.first.lowercase() == self }.sumOf { it.second }
            val returnedToWallet = returnedHere + ownChange
            if (returnedToWallet >= spentByWallet) {
                // came out ahead (or even): what it gained
                return if (returnedToWallet > spentByWallet) false to (returnedToWallet - spentByWallet) else null
            }
            var paid = spentByWallet - returnedToWallet
            val fee = if (allInputsKnown) (feeSompi ?: 0L).coerceAtLeast(0L) else 0L
            val walletFee = if (!outsideInputs || inputTotal == 0L) {
                fee
            } else {
                // fee x ours / all, in full width: the pool's balance times a fee can pass a Long
                java.math.BigInteger.valueOf(fee).multiply(java.math.BigInteger.valueOf(spentByWallet))
                    .divide(java.math.BigInteger.valueOf(inputTotal)).toLong()
            }
            paid -= minOf(paid, walletFee)
            return true to minOf(paid, toOthers)
        }
    }
}
