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
    }
}
