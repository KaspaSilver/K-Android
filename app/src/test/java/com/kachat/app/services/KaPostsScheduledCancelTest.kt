package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Audit 2026-11-27 D4: a cancel acts on the list of the account the post belongs to. */
class KaPostsScheduledCancelTest {

    private fun entry(txId: String, at: Long) = KaPostScheduledEntry(
        txId = txId,
        text = "t",
        notBeforeMs = at,
        createdAtMs = 0L,
        spentOutpoints = emptyList(),
        transaction = RawTransaction(inputs = emptyList(), outputs = emptyList()),
    )

    @Test
    fun `cancelling drops only that post and keeps the rest in time order`() {
        val stored = listOf(entry("c", 30), entry("a", 10), entry("b", 20))
        assertEquals(listOf("a", "c"), KaPostsScheduledStore.entriesWithout(stored, "b").map { it.txId })
        assertEquals(listOf("a", "b", "c"), KaPostsScheduledStore.entriesWithout(stored, "zz").map { it.txId })
    }

    @Test
    fun `each account files its owed cancels under its own key`() {
        val a = KaPostsScheduledStore.pendingCancelsKeyFor("kaspa:qa")
        val b = KaPostsScheduledStore.pendingCancelsKeyFor("kaspa:qb")
        assertNotEquals(a, b)
        assertEquals("scheduled_kaspa_qa_pending_cancels", a)
    }
}
