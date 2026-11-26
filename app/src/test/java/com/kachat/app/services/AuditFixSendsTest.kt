package com.kachat.app.services

import com.kachat.app.services.kachatnames.KachatNamesActions
import com.kachat.app.util.KsptCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-25 fixes: the quantum Cold Storage coin set (T1), the Address Activity poll gate
 *  (B7) and the .kachat registration driver's back-off (B9). */
class AuditFixSendsTest {

    private fun coin(tx: Int, amount: Long) = UtxoEntry(
        address = "kaspa:p",
        outpoint = Outpoint(transactionId = "%064x".format(tx), index = 0),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 1, isCoinbase = false),
    )

    @Test
    fun `a quantum source spends the largest coins up to the cap`() {
        val coins = (1..40).map { coin(it, it * 1_000L) }
        val picked = ColdStorageSendEngine.quantumSourceCoins(coins.shuffled(), leadingOutpoint = null)
        assertEquals(KsptCodec.MAX_INPUTS, picked.size)
        assertEquals(40_000L, picked.first().utxoEntry.amount)
        assertEquals(coins.sortedByDescending { it.utxoEntry.amount }.take(KsptCodec.MAX_INPUTS), picked)
    }

    @Test
    fun `a Not confirmed send's coin leads, even from outside the largest 32 (T1)`() {
        val coins = (1..40).map { coin(it, it * 1_000L) }
        val lead = coins.first() // the smallest: not among the top 32
        val key = "${lead.outpoint.transactionId}:${lead.outpoint.index}".uppercase()
        val picked = ColdStorageSendEngine.quantumSourceCoins(coins, key)
        assertEquals(KsptCodec.MAX_INPUTS, picked.size)
        assertEquals(lead, picked.first())
        // Max, Compound and the build all call this one function, so they price the same set
        assertEquals(picked, ColdStorageSendEngine.quantumSourceCoins(coins.reversed(), key))
    }

    @Test
    fun `Address Activity polls with its notifications on, or while a balance screen listens (B7)`() {
        assertTrue(AddressActivityNotifier.shouldPoll(featureOn = true, balanceScreenListening = false))
        assertTrue(AddressActivityNotifier.shouldPoll(featureOn = false, balanceScreenListening = true))
        assertFalse(AddressActivityNotifier.shouldPoll(featureOn = false, balanceScreenListening = false))
    }

    @Test
    fun `the registration driver backs off when the node keeps not answering (B9)`() {
        assertEquals(2_000L, KachatNamesActions.driverDelayMs(registering = true, unansweredPasses = 0))
        assertEquals(5_000L, KachatNamesActions.driverDelayMs(registering = false, unansweredPasses = 2))
        assertEquals(30_000L, KachatNamesActions.driverDelayMs(registering = true, unansweredPasses = 3))
        assertEquals(60_000L, KachatNamesActions.driverDelayMs(registering = false, unansweredPasses = 10))
    }
}
