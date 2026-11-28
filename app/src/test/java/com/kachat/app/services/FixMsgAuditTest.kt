package com.kachat.app.services

import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.DefiniteSendFailures
import com.kachat.app.viewmodels.ChatPaymentPricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release audit 2026-11-27, "Money and messaging" fixes (C1-C7, A1-A7, B1). */
class FixMsgAuditTest {

    // MARK: C1 - an open chat counts as viewed only in the foreground

    @Test
    fun `an open chat is viewed only while K is in front`() {
        assertTrue(OpenChatHold.isViewing("kaspa:alice", "kaspa:alice", appForeground = true))
        assertFalse(OpenChatHold.isViewing("kaspa:alice", "kaspa:alice", appForeground = false))
        assertFalse(OpenChatHold.isViewing("kaspa:bob", "kaspa:alice", appForeground = true))
        assertFalse(OpenChatHold.isViewing(null, "kaspa:alice", appForeground = true))
    }

    // MARK: C6 - payment and handshake pushes right after an account switch

    @Test
    fun `a push naming its recipient is checked against the active account`() {
        assertFalse(AccountPushGuard.mayBeForPreviousAccount("KASPA:B", "kaspa:b", signedInAtMs = 0L, nowMs = 1_000L))
        assertTrue(AccountPushGuard.mayBeForPreviousAccount("kaspa:a", "kaspa:b", signedInAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `a push naming no recipient is held back just after a switch only`() {
        val switchedAt = 100_000L
        assertTrue(AccountPushGuard.mayBeForPreviousAccount(null, "kaspa:b", switchedAt, switchedAt + 5_000L))
        assertFalse(AccountPushGuard.mayBeForPreviousAccount(null, "kaspa:b", switchedAt, switchedAt + AccountPushGuard.SWITCH_GRACE_MS))
        // no switch in this process
        assertFalse(AccountPushGuard.mayBeForPreviousAccount("", "kaspa:b", 0L, 5_000L))
    }

    // MARK: C3 - a Failed send is never collapsed into an earlier message with the same text

    private fun row(id: String, body: String, status: String, ts: Long, direction: String = "sent") = MessageEntity(
        id = id, contactId = "kaspa:them", walletAddress = "kaspa:me", type = "comm", direction = direction,
        plaintextBody = body, encryptedPayload = "", amountSompi = 0L, blockTimestamp = ts, deliveryStatus = status,
    )

    private val hour = 60 * 60 * 1000L

    @Test
    fun `an own copy is only one sent within minutes of the placeholder`() {
        val t = 10 * hour
        assertTrue(ChatRepository.isOwnCopyTime(t, t + 30_000L))
        assertTrue(ChatRepository.isOwnCopyTime(t, t - 60_000L))
        assertFalse(ChatRepository.isOwnCopyTime(t, t - hour))
        assertFalse(ChatRepository.isOwnCopyTime(t, t + 47 * hour))
    }

    @Test
    fun `a send that failed for sure is never looked for a twin of`() {
        val failed = row("pending_a", "ok", "failed", 10 * hour)
        assertFalse(ChatRepository.mayLookForOwnCopy(failed, listOf(failed), definitelyFailed = setOf("pending_a")))
        assertTrue(ChatRepository.mayLookForOwnCopy(failed, listOf(failed), definitelyFailed = emptySet()))
    }

    @Test
    fun `two placeholders with the same text near each other are left alone`() {
        val a = row("pending_a", "ok", "pending", 10 * hour)
        val b = row("pending_b", " ok ", "pending", 10 * hour + 60_000L)
        assertFalse(ChatRepository.mayLookForOwnCopy(a, listOf(a, b), emptySet()))
        val far = b.copy(blockTimestamp = 12 * hour)
        assertTrue(ChatRepository.mayLookForOwnCopy(a, listOf(a, far), emptySet()))
    }

    @Test
    fun `a failed row is not taken over by another send of the same text hours apart`() {
        val failed = row("pending_a", "ok", "failed", 10 * hour)
        assertEquals(
            ChatRepository.ProvisionalMatch.None,
            ChatRepository.matchProvisionalCandidates(listOf(failed), "ok", 10 * hour + 5 * hour),
        )
        assertEquals(
            ChatRepository.ProvisionalMatch.Exact(failed),
            ChatRepository.matchProvisionalCandidates(listOf(failed), "ok", 10 * hour + 20_000L),
        )
    }

    @Test
    fun `a failed row that failed for sure is never taken over`() {
        val failed = row("pending_a", "ok", "failed", 10 * hour)
        assertEquals(
            ChatRepository.ProvisionalMatch.None,
            ChatRepository.matchProvisionalCandidates(listOf(failed), "ok", 10 * hour + 20_000L, definitelyFailed = setOf("pending_a")),
        )
    }

    @Test
    fun `the definite failure list keeps order, drops duplicates and is capped`() {
        assertEquals(listOf("b", "a"), DefiniteSendFailures.appended(listOf("a", "b"), "a"))
        val full = (1..DefiniteSendFailures.MAX_IDS).map { "p$it" }
        val next = DefiniteSendFailures.appended(full, "new")
        assertEquals(DefiniteSendFailures.MAX_IDS, next.size)
        assertEquals("new", next.last())
        assertFalse("p1" in next)
        assertEquals(listOf("x", "y"), DefiniteSendFailures.decode(DefiniteSendFailures.encode(listOf("x", "y"))))
        assertEquals(emptyList<String>(), DefiniteSendFailures.decode(null))
    }

    // MARK: A1 - owed spending-address notices survive the process

    private class MemoryStorage : ChatPaymentSender.OwedSpendingNotices.Storage {
        var json: String? = null
        override fun load(): String? = json
        override fun save(json: String?) { this.json = json }
    }

    private val owed = ChatPaymentSender.OwedSpendingNotice(
        contactAddress = "kaspa:payee", amountSompi = 20_000_000L, memo = "hi", owner = "kaspa:me", atMs = 1_000L,
    )

    @Test
    fun `an owed notice is read back by a new store`() {
        val storage = MemoryStorage()
        ChatPaymentSender.OwedSpendingNotices(storage).put("ABC", owed)
        val reloaded = ChatPaymentSender.OwedSpendingNotices(storage)
        assertEquals(owed, reloaded.get("abc"))
        assertEquals(listOf("abc"), reloaded.txIds())
        assertEquals(owed, reloaded.due("abc", "kaspa:payee", "kaspa:me", 2_000L))
    }

    @Test
    fun `a landed notice stays landed and goes with its owner`() {
        val storage = MemoryStorage()
        val notices = ChatPaymentSender.OwedSpendingNotices(storage)
        notices.put("abc", owed)
        notices.put("def", owed.copy(owner = "kaspa:other"))
        notices.markLanded("ABC")
        assertTrue(ChatPaymentSender.OwedSpendingNotices(storage).get("abc")!!.landed)
        assertEquals(listOf("abc"), notices.txIdsOwnedBy("KASPA:ME"))
        notices.removeOwner("kaspa:me")
        assertNull(notices.get("abc"))
        assertEquals(listOf("def"), ChatPaymentSender.OwedSpendingNotices(storage).txIds())
        notices.remove("def")
        assertNull(storage.json)
    }

    @Test
    fun `a broken stored entry is dropped, not kept`() {
        val storage = MemoryStorage().apply { json = """{"abc":{"amountSompi":5}}""" }
        assertEquals(emptyList<String>(), ChatPaymentSender.OwedSpendingNotices(storage).txIds())
        storage.json = "not json"
        assertEquals(emptyList<String>(), ChatPaymentSender.OwedSpendingNotices(storage).txIds())
    }

    @Test
    fun `an owed payment is looked up for fifteen minutes, then once per start`() {
        val at = 0L
        val window = ChatPaymentSender.OWED_CHECK_WINDOW_MS
        assertTrue(ChatPaymentSender.OwedSpendingNotices.keepsChecking(at, window - 1, firstCheck = false))
        assertFalse(ChatPaymentSender.OwedSpendingNotices.keepsChecking(at, window, firstCheck = false))
        assertTrue(ChatPaymentSender.OwedSpendingNotices.keepsChecking(at, window * 10, firstCheck = true))
        assertFalse(ChatPaymentSender.OwedSpendingNotices.keepsChecking(at, ChatPaymentSender.OWED_NOTICE_LIFETIME_MS, firstCheck = true))
    }

    // MARK: A5 - a chat=1 request from a spending address is priced without the payload

    @Test
    fun `a chat payment carries its payload from the chatting address only`() {
        assertEquals(140, ChatPaymentPricing.payloadSize(chatPayment = true, sourceIndex = null, payloadSize = 140))
        assertEquals(0, ChatPaymentPricing.payloadSize(chatPayment = true, sourceIndex = 3, payloadSize = 140))
        assertEquals(140, ChatPaymentPricing.payloadSize(chatPayment = false, sourceIndex = 3, payloadSize = 140))
    }

    // MARK: A7 - no confirmed-only rebuild for a payment after "already spent"

    @Test
    fun `a payment is not rebuilt after an already-spent rejection`() {
        assertFalse(SendRetryRule.mayRebuildAfterRejection(amountSompi = 100_000_000L, signatureRejected = false))
        assertTrue(SendRetryRule.mayRebuildAfterRejection(amountSompi = 0L, signatureRejected = false))
        assertTrue(SendRetryRule.mayRebuildAfterRejection(amountSompi = 100_000_000L, signatureRejected = true))
    }
}
