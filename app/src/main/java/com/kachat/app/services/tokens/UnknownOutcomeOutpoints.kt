package com.kachat.app.services.tokens

import com.kachat.app.services.Outpoint
import com.kachat.app.services.OutpointReservations

/**
 * Coins a "Not confirmed" send spent (iOS `ChatService.unknownOutcomeOutpoints`,
 * `markUnknownOutcome`, `unknownOutcomeKeys`; KaPay's audit, 2026-11-20): "txid:index" -> until
 * when. A submit whose answer was lost gives its coins back to every pick (so a resend picks the
 * same ones), and a kUSDT send built after it spends one of these FIRST ([KusdtTransfer.prepare]),
 * so the two conflict and at most one can land - never paid twice. Kept as long as a hold lasts
 * ([OutpointReservations.TTL_MS]). Process-wide, like iOS's `ChatService.shared`.
 */
internal class UnknownOutcomeOutpoints(
    private val ttlMs: Long = OutpointReservations.TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val until = HashMap<String, Long>()

    /** A submit's answer was lost: its coins are remembered. Called by
     *  [com.kachat.app.services.NodePoolManager.submitConfirmingKnown]. */
    @Synchronized
    fun mark(outpoints: List<Outpoint>) {
        if (outpoints.isEmpty()) return
        val current = now()
        until.entries.removeAll { it.value <= current }
        val expiry = current + ttlMs
        for (o in outpoints) until[key(o.transactionId, o.index)] = expiry
    }

    /** Every "txid:index" (lowercase) a "Not confirmed" send spent and that may still land. */
    @Synchronized
    fun keys(): Set<String> {
        val current = now()
        return until.filterValues { it > current }.keys.toSet()
    }

    companion object {
        /** The app's one store. */
        val shared = UnknownOutcomeOutpoints()

        fun key(transactionId: String, index: Int): String = "${transactionId.lowercase()}:$index"

        /** Whether a send from this app holds this coin right now (iOS `heldOutpointKeys`): it's
         *  spent, even while the node and Trestly still list it. */
        fun isHeld(transactionId: String, index: Int): Boolean =
            OutpointReservations.shared.isReserved(Outpoint(transactionId = transactionId, index = index))
    }
}
