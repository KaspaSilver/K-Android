package com.kachat.app.services

import com.kachat.app.util.ColdQuantumNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold Storage's quantum (KCC-3) pieces that need no device: the unit's address number as typed,
 *  which change addresses the unit marks as change, and a history row's figures (iOS 961fc05a,
 *  audit 2026-11-25 Q2, Q4, L3). */
class ColdQuantumTest {

    @Test
    fun `an address number is empty, a whole number below 2^31, or refused`() {
        assertEquals(ColdQuantumNumber.Parsed.Value(null), ColdQuantumNumber.parse("  "))
        assertEquals(ColdQuantumNumber.Parsed.Value(0L), ColdQuantumNumber.parse("0"))
        assertEquals(ColdQuantumNumber.Parsed.Value(42L), ColdQuantumNumber.parse(" 42 "))
        assertEquals(ColdQuantumNumber.Parsed.Value(2_147_483_647L), ColdQuantumNumber.parse("2147483647"))
        assertEquals(ColdQuantumNumber.Parsed.Invalid(tooLarge = true), ColdQuantumNumber.parse("2147483648"))
        assertEquals(ColdQuantumNumber.Parsed.Invalid(tooLarge = true), ColdQuantumNumber.parse("99999999999999999999"))
        assertEquals(ColdQuantumNumber.Parsed.Invalid(tooLarge = false), ColdQuantumNumber.parse("-1"))
        assertEquals(ColdQuantumNumber.Parsed.Invalid(tooLarge = false), ColdQuantumNumber.parse("1.5"))
        assertEquals(ColdQuantumNumber.Parsed.Invalid(tooLarge = false), ColdQuantumNumber.parse("٣"))
    }

    @Test
    fun `the unit marks only its change chain 0 to 4 as change`() {
        val change = ColdStorageManager.ColdQuantumAddress.CHAIN_CHANGE
        val receive = ColdStorageManager.ColdQuantumAddress.CHAIN_RECEIVE
        assertTrue(ColdStorageManager.ColdQuantumAddress("a", 0, change).unitMarksAsChange)
        assertTrue(ColdStorageManager.ColdQuantumAddress("a", 4, change).unitMarksAsChange)
        assertFalse(ColdStorageManager.ColdQuantumAddress("a", 5, change).unitMarksAsChange)
        assertFalse(ColdStorageManager.ColdQuantumAddress("a", null, change).unitMarksAsChange)
        assertFalse(ColdStorageManager.ColdQuantumAddress("a", 0, receive).unitMarksAsChange)
    }

    @Test
    fun `a record saved before the chain existed decodes as receive`() {
        val decoded = com.google.gson.Gson().fromJson(
            """{"id":"x","name":"n","kpub":"k","quantumAddresses":[{"address":"kaspa:pq","unitIndex":3}]}""",
            ColdStorageManager.ColdAccount::class.java
        )
        assertEquals(1, decoded.quantumList.size)
        assertEquals(ColdStorageManager.ColdQuantumAddress.CHAIN_RECEIVE, decoded.quantumList[0].chain)
        assertEquals(3L, decoded.quantumList[0].unitIndex)
        val old = com.google.gson.Gson().fromJson("""{"id":"x","name":"n","kpub":"k"}""", ColdStorageManager.ColdAccount::class.java)
        assertTrue(old.quantumList.isEmpty())
    }

    private fun input(address: String, amount: Long?) = TransactionInput(
        previousOutpointHash = "00".repeat(32), previousOutpointIndex = 0,
        previousOutpointAddress = address, previousOutpointAmount = amount, signatureScript = "",
    )

    private fun output(address: String, amount: Long) = TransactionOutput(amount, "", address)

    private fun tx(inputs: List<TransactionInput>, outputs: List<TransactionOutput>) = TransactionResponse(
        transactionId = "t", inputsOrNull = inputs, outputs = outputs, blockTime = 1L, payload = null,
    )

    @Test
    fun `a quantum send's change to the account's fresh address isn't Sent`() {
        val source = "kaspa:psource"
        val change = "kaspa:pchange"
        val row = ColdStorageAddressDiscovery.HistoryRows.historyRow(
            source,
            tx(listOf(input(source, 10_000L)), listOf(output("kaspa:qfriend", 3_000L), output(change, 6_990L))),
            setOf(source, change),
        )
        assertTrue(row.sent)
        assertFalse(row.moved)
        assertEquals(3_000L, row.amountSompi)
    }

    @Test
    fun `everything to the account's own addresses is Moved`() {
        val source = "kaspa:psource"
        val fresh = "kaspa:pfresh"
        val row = ColdStorageAddressDiscovery.HistoryRows.historyRow(
            source,
            tx(listOf(input(source, 5_000L), input(source, 5_000L)), listOf(output(fresh, 9_990L))),
            setOf(source, fresh),
        )
        assertTrue(row.moved)
        assertEquals(9_990L, row.amountSompi)
    }

    @Test
    fun `a receive counts what came to this address`() {
        val here = "kaspa:qhere"
        val row = ColdStorageAddressDiscovery.HistoryRows.historyRow(
            here,
            tx(listOf(input("kaspa:qsomeone", 10_000L)), listOf(output(here, 4_000L), output("kaspa:qsomeone", 5_990L))),
            setOf(here),
        )
        assertFalse(row.sent)
        assertEquals(4_000L, row.amountSompi)
    }
}
