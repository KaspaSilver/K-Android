package com.kachat.app.util

/**
 * Whether a received `payment_notice` / `token_payment_notice` may show its payment as the
 * contact's (security audit H1, M1, M2). Android only: the notice format is iOS's and unchanged.
 *
 * A notice names a payment by txid, and every txid, amount and payee is public, so a contact
 * could otherwise claim anyone's payment to us - an exchange withdrawal, a customer - as their
 * own. A genuine notice is for a payment made From a spending address, a hardened key nobody
 * else can link to the payer's chatting address, so the chain can't always prove who paid.
 * What it can prove, and what is required here:
 * - the payment is accepted by the chain ([isAccepted]; a double-spent one never is, M1);
 * - it is paid by none of our own addresses and by no other contact;
 * - and EITHER its payer is provably the contact (an input from their chatting address, or a
 *   token coin of theirs spent), OR the notice came right after the payment
 *   ([isFreshClaim]): the payer's app sends it the moment the payment goes out, so a contact
 *   can no longer claim a payment from the past, only race a brand-new one as it lands.
 */
object PaymentNoticeProof {

    /** How long after its payment a notice whose payer can't be proven still counts. A genuine
     *  one goes out right after the payment (ChatPaymentSender.sendSpendingPaymentNotice,
     *  TokenChatPayments.recordTokenPayment); one held in PaymentNoticeOutbox can be later. */
    const val FRESH_NOTICE_WINDOW_MS = 10 * 60 * 1000L

    /** Block times can disagree a little: a notice that seems slightly older than its payment
     *  still counts as right after it. */
    const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    /** An unverified notice bubble whose transaction is still unknown, or never accepted, after
     *  this long is dropped when it is checked again (M2). */
    const val UNVERIFIED_GIVE_UP_MS = 24 * 60 * 60 * 1000L

    /** Rechecks of a notice whose transaction the explorer can't answer for yet (not indexed,
     *  not accepted yet), before it is filed as unverified. */
    val RECHECK_DELAYS_MS = longArrayOf(5_000L, 15_000L, 40_000L)

    sealed class Verdict {
        object Valid : Verdict()
        data class Invalid(val reason: String) : Verdict()
        /** No answer yet. [settled]: the explorer answered, the transaction is just unknown to it
         *  or not accepted - after [UNVERIFIED_GIVE_UP_MS] that won't change. */
        data class Unknown(val settled: Boolean = false) : Verdict()
    }

    /** Whether [noticeBlockTime] came right after [paymentBlockTime] (both chain times, ms). */
    fun isFreshClaim(paymentBlockTime: Long?, noticeBlockTime: Long): Boolean {
        if (paymentBlockTime == null || paymentBlockTime <= 0L || noticeBlockTime <= 0L) return false
        val gap = noticeBlockTime - paymentBlockTime
        return gap in -CLOCK_SKEW_MS..FRESH_NOTICE_WINDOW_MS
    }

    /**
     * A KAS notice (iOS `checkNoticeOnChain` plus the rules above). [inputAddresses] are the
     * payment's inputs' previous-outpoint addresses (null: not resolved), [own] every address
     * this phone knows as ours, [otherContacts] the input addresses that belong to a contact
     * other than [payer] (all lowercased).
     */
    fun checkKas(
        paidSompi: Long,
        claimedSompi: Long,
        payload: String?,
        isAccepted: Boolean?,
        inputAddresses: List<String?>,
        own: Set<String>,
        otherContacts: Set<String>,
        payer: String,
        paymentBlockTime: Long?,
        noticeBlockTime: Long,
    ): Verdict {
        if (paidSompi != claimedSompi) return Verdict.Invalid("it pays $paidSompi sompi, not $claimedSompi")
        if (!payload.isNullOrEmpty()) return Verdict.Invalid("the transaction carries a payload")
        // not (yet) applied by the chain: a payment just sent is accepted within seconds, a
        // double-spent one never (M1)
        if (isAccepted != true) return Verdict.Unknown(settled = true)
        // an input without a resolved address could be one of ours: no chain answer yet
        val inputs = inputAddresses.map { it?.trim()?.lowercase().orEmpty() }
        if (inputs.isEmpty() || inputs.any { it.isEmpty() }) return Verdict.Unknown()
        val payerAddress = payer.trim().lowercase()
        for (input in inputs) {
            if (input in own) return Verdict.Invalid("paid by one of our own addresses")
            if (input != payerAddress && input in otherContacts) return Verdict.Invalid("paid by another contact")
        }
        return payerVerdict(provenPayer = payerAddress in inputs, paymentBlockTime, noticeBlockTime)
    }

    /** The last rule, shared with the token check: a proven payer, or a notice right after. */
    fun payerVerdict(provenPayer: Boolean, paymentBlockTime: Long?, noticeBlockTime: Long): Verdict = when {
        provenPayer -> Verdict.Valid
        isFreshClaim(paymentBlockTime, noticeBlockTime) -> Verdict.Valid
        else -> Verdict.Invalid("its payer isn't the contact, and the notice came long after the payment")
    }

    /** Whether the REST API's covenant id for the paid coin ([restCovenant]) already rules it
     *  out: present and not [expectedCovenant] (audit 2026-11-27 C7). */
    fun restCovenantRefuses(restCovenant: String?, expectedCovenant: String): Boolean {
        val rest = restCovenant?.trim()?.lowercase().orEmpty()
        return rest.isNotEmpty() && rest != expectedCovenant.lowercase()
    }

    /**
     * Whether a token notice's coin is the real token (iOS `checkTokenNoticeOnChain`, audit
     * 2026-11-27 C7). The node decides while the coin is unspent ([coinOnNode]: listed, and
     * [coinMatches]: its script and covenant id are the token's); the REST API's covenant id is
     * only a hint. Spent since (a notice read back from history, or the payee moved it first), the
     * node can't show it any more, so the REST API's matching id is all there is to go on. Called
     * once the node answered; [restCovenantRefuses] has already refused a wrong REST id.
     */
    fun tokenCoinVerdict(
        restCovenant: String?,
        expectedCovenant: String,
        coinOnNode: Boolean,
        coinMatches: Boolean,
        symbol: String,
    ): Verdict {
        if (coinOnNode) return if (coinMatches) Verdict.Valid else Verdict.Invalid("the coin isn't $symbol")
        val rest = restCovenant?.trim()?.lowercase().orEmpty()
        return if (rest.isNotEmpty() && rest == expectedCovenant.lowercase()) Verdict.Valid else Verdict.Unknown()
    }

    /** Whether an unverified notice bubble filed at [filedAt] should go, given [verdict]. */
    fun shouldGiveUp(verdict: Verdict, filedAt: Long, now: Long): Boolean =
        verdict is Verdict.Unknown && verdict.settled && filedAt > 0L && now - filedAt >= UNVERIFIED_GIVE_UP_MS
}
