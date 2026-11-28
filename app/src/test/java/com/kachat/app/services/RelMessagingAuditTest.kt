package com.kachat.app.services

import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.PaymentTxDirection
import com.kachat.app.repository.pushWatchesAllGroups
import com.kachat.app.services.tokens.TokenChatPayments
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessGameEngine
import com.kachat.app.util.ChessGameStatus
import com.kachat.app.util.ChessGameStatusKind
import com.kachat.app.util.ChessGameSummary
import com.kachat.app.util.ChessInviteColor
import com.kachat.app.util.ChessInviteContent
import com.kachat.app.util.ChessMessage
import com.kachat.app.util.ChessMoveContent
import com.kachat.app.util.MessageReply
import com.kachat.app.util.PaymentNoticeProof
import com.kachat.app.util.PayloadSniff
import com.kachat.app.util.UtxoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iPhone 01f71184's release audit (2026-11-27), messaging and payment core. */
class RelMessagingAuditTest {

    // MARK: R4 - kept payment notices

    private fun notice(txId: String) = PaymentNoticeOutbox.UnsentPaymentNotice(contactAddress = "kaspa:c", payload = "p", txId = txId)

    @Test
    fun `a run removes only the notices it sent, never one kept meanwhile`() {
        // a and b were sent; c was kept while the run was sending; d is still unsent
        val reread = listOf(notice("a"), notice("b"), notice("d"), notice("c"))
        val left = PaymentNoticeOutbox.finishRun(reread, setOf("a", "b"))
        assertEquals(listOf("d", "c"), left.map { it.txId })
    }

    // MARK: R5 - Send Dollars carries its owner

    @Test
    fun `a token payment report is dropped once another account is active`() {
        assertTrue(TokenChatPayments.isSameOwner(null, "kaspa:b"))
        assertTrue(TokenChatPayments.isSameOwner("kaspa:A", "kaspa:a"))
        assertFalse(TokenChatPayments.isSameOwner("kaspa:a", "kaspa:b"))
    }

    // MARK: C5 - owed spending-address notices

    private val owed = ChatPaymentSender.OwedSpendingNotice(
        contactAddress = "kaspa:payee", amountSompi = 20_000_000L, memo = "hi", owner = "kaspa:me", atMs = 1_000L,
    )

    @Test
    fun `an owed notice is due for its payment, its contact and its owner only`() {
        val notices = ChatPaymentSender.OwedSpendingNotices()
        notices.put("ABC", owed)
        assertNull(notices.due("abc", "kaspa:other", "kaspa:me", 2_000L))
        assertNull(notices.due("abc", "kaspa:payee", "kaspa:someone-else", 2_000L))
        assertEquals(owed, notices.due("abc", "KASPA:PAYEE", "kaspa:me", 2_000L))
        notices.remove("abc")
        assertNull(notices.due("abc", "kaspa:payee", "kaspa:me", 2_000L))
    }

    @Test
    fun `an owed notice is forgotten after a week`() {
        val notices = ChatPaymentSender.OwedSpendingNotices()
        notices.put("abc", owed)
        val weekLater = owed.atMs + ChatPaymentSender.OWED_NOTICE_LIFETIME_MS
        assertNull(notices.due("abc", "kaspa:payee", "kaspa:me", weekLater))
        assertNull(notices.due("abc", "kaspa:payee", "kaspa:me", owed.atMs))
    }

    // MARK: C7 - kUSDT notices confirmed on the node

    private val kusdt = "aa11"

    @Test
    fun `a REST covenant id that isn't the token's refuses at once`() {
        assertTrue(PaymentNoticeProof.restCovenantRefuses("bb22", kusdt))
        assertFalse(PaymentNoticeProof.restCovenantRefuses("AA11", kusdt))
        assertFalse(PaymentNoticeProof.restCovenantRefuses(null, kusdt))
        assertFalse(PaymentNoticeProof.restCovenantRefuses(" ", kusdt))
    }

    @Test
    fun `the node decides while the coin is unspent, whatever REST says`() {
        assertEquals(
            PaymentNoticeProof.Verdict.Valid,
            PaymentNoticeProof.tokenCoinVerdict(null, kusdt, coinOnNode = true, coinMatches = true, symbol = "kUSDT"),
        )
        // REST names kUSDT's id, the node's coin is a look-alike
        assertTrue(
            PaymentNoticeProof.tokenCoinVerdict(kusdt, kusdt, coinOnNode = true, coinMatches = false, symbol = "kUSDT")
                is PaymentNoticeProof.Verdict.Invalid
        )
    }

    @Test
    fun `a coin spent since falls back to the REST id`() {
        assertEquals(
            PaymentNoticeProof.Verdict.Valid,
            PaymentNoticeProof.tokenCoinVerdict(kusdt, kusdt, coinOnNode = false, coinMatches = false, symbol = "kUSDT"),
        )
        assertTrue(
            PaymentNoticeProof.tokenCoinVerdict(null, kusdt, coinOnNode = false, coinMatches = false, symbol = "kUSDT")
                is PaymentNoticeProof.Verdict.Unknown
        )
    }

    // MARK: R6 - overflow-safe REST sums

    @Test
    fun `a REST sum past Long MAX or with a negative amount is no sum`() {
        assertEquals(300L, UtxoMath.restTotalOrNull(listOf(100L, 200L)))
        assertNull(UtxoMath.restTotalOrNull(listOf(Long.MAX_VALUE, 1L)))
        assertNull(UtxoMath.restTotalOrNull(listOf(5L, -1L)))
        assertNull(UtxoMath.restSumOrNull(Long.MAX_VALUE - 1, 2L))
        assertEquals(Long.MAX_VALUE, UtxoMath.restSumOrNull(Long.MAX_VALUE - 1, 1L))
    }

    @Test
    fun `address history keeps a transaction only when its outputs and inputs each sum`() {
        assertEquals(true, UtxoMath.restAmountsFit(listOf(1L, 2L), listOf(Long.MAX_VALUE)))
        assertEquals(false, UtxoMath.restAmountsFit(listOf(Long.MAX_VALUE, 1L), listOf(1L)))
        assertEquals(false, UtxoMath.restAmountsFit(listOf(1L), listOf(Long.MAX_VALUE, Long.MAX_VALUE)))
    }

    @Test
    fun `a payment whose outputs overflow is skipped, not wrapped into an amount`() {
        val me = "kaspa:qrme"
        val payer = "kaspa:qrpayer"
        val direction = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(payer),
            outputs = listOf(me to Long.MAX_VALUE, me to Long.MAX_VALUE, me to 3L),
        )
        assertEquals(PaymentTxDirection.None, direction)
        val outgoing = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(me),
            outputs = listOf(payer to Long.MAX_VALUE, "kaspa:qrother" to 10L),
        )
        assertEquals(PaymentTxDirection.None, outgoing)
    }

    // MARK: R7 - 1:1 chess status, whole sentences

    private fun summary(status: ChessGameStatus, viewer: ChessColor?) = ChessGameSummary(
        gameId = "g", status = status, board = ChessEngine.initialBoard(),
        whiteAddress = "kaspa:w", blackAddress = "kaspa:b", lastMessageId = "m", viewerColor = viewer,
    )

    @Test
    fun `each chess status is one whole sentence`() {
        assertEquals("Checkmate - Black wins", summary(ChessGameStatus(ChessGameStatusKind.CHECKMATE, ChessColor.BLACK), null).statusKey)
        assertEquals("Checkmate - You win!", summary(ChessGameStatus(ChessGameStatusKind.CHECKMATE, ChessColor.WHITE), ChessColor.WHITE).statusKey)
        assertEquals("White lost on time", summary(ChessGameStatus(ChessGameStatusKind.RESIGNED, ChessColor.WHITE, timedOut = true), null).statusKey)
        assertEquals("They lost on time", summary(ChessGameStatus(ChessGameStatusKind.RESIGNED, ChessColor.BLACK, timedOut = true), ChessColor.WHITE).statusKey)
        assertEquals("Black resigned", summary(ChessGameStatus(ChessGameStatusKind.RESIGNED, ChessColor.BLACK), null).statusKey)
        assertEquals("Your turn", summary(ChessGameStatus(ChessGameStatusKind.IN_PROGRESS), ChessColor.WHITE).statusKey)
        assertEquals("Game declined", summary(ChessGameStatus(ChessGameStatusKind.DECLINED), ChessColor.WHITE).statusKey)
        // no app context in a unit test: the English key comes back
        assertEquals("Your turn", summary(ChessGameStatus(ChessGameStatusKind.IN_PROGRESS), ChessColor.WHITE).statusText)
    }

    // MARK: Smoothness C1 - PayloadSniff

    @Test
    fun `chess and replies are still read, inside a reply too`() {
        val move = ChessMessage.encode(ChessMoveContent(gameId = "g", from = "e2", to = "e4", promotion = null))
        assertNotNull(ChessMessage.parseOrNull(move))
        val reply = MessageReply.encode("id", "kaspa:x", "preview", move)
        assertTrue(PayloadSniff.mayBeChess(reply))
        val unwrapped = MessageReply.parseOrNull(reply)
        assertNotNull(unwrapped)
        assertNotNull(ChessMessage.parseOrNull(unwrapped!!.text))
    }

    @Test
    fun `json without the type value is ruled out with no decode`() {
        assertNull(ChessMessage.parseOrNull("""{"type":"file","name":"photo.jpg"}"""))
        assertNull(MessageReply.parseOrNull("""{"type":"file","name":"photo.jpg"}"""))
        assertFalse(PayloadSniff.mayBeReply("""{"type":"voice"}"""))
    }

    @Test
    fun `a game is summarized over its chess messages alone`() {
        val invite = ChessMessage.encode(ChessInviteContent(gameId = "g", inviterColor = ChessInviteColor.WHITE))
        val messages = listOf(
            ChessGameEngine.SimpleChessSourceMessage("p", """{"type":"file","name":"a.jpg"}""", false, 0),
            ChessGameEngine.SimpleChessSourceMessage("i", invite, true, 1),
            ChessGameEngine.SimpleChessSourceMessage("t", "hello", false, 2),
        )
        assertEquals(listOf("i"), ChessGameEngine.chessMessages(messages).map { it.id })
        val active = ChessGameEngine.activeGame(messages, "kaspa:me", "kaspa:them")
        assertEquals(ChessGameStatusKind.PENDING_RESPONSE, active?.status?.kind)
        assertEquals("i", active?.lastMessageId)
    }

    // MARK: B4 / B5 - group and room polls with push

    @Test
    fun `the group pass runs once per beat, every five minutes while push covers every group`() {
        assertTrue(GroupScanningService.isFullPassDue(beat = 10, lastFullBeat = null, pushCovers = true, sinceLastFullMs = Long.MAX_VALUE))
        assertFalse(GroupScanningService.isFullPassDue(10, 10, pushCovers = false, sinceLastFullMs = 60_000))
        assertTrue(GroupScanningService.isFullPassDue(11, 10, pushCovers = false, sinceLastFullMs = 30_000))
        assertFalse(GroupScanningService.isFullPassDue(11, 10, pushCovers = true, sinceLastFullMs = 30_000))
        assertTrue(GroupScanningService.isFullPassDue(20, 10, pushCovers = true, sinceLastFullMs = GroupScanningService.PUSH_SAFETY_NET_MS - 4_000))
    }

    @Test
    fun `a wake just before the beat counts as it`() {
        val interval = 30_000L
        // beats sit at whole multiples plus the 3 s offset
        val beatAt = 30_000L * 100 + 3_000L
        assertEquals(100L, GroupScanningService.livePollBeat(beatAt, interval))
        assertEquals(100L, GroupScanningService.livePollBeat(beatAt - 400, interval))
        assertEquals(99L, GroupScanningService.livePollBeat(beatAt - 2_000, interval))
    }

    @Test
    fun `push watches every group unless one is silenced or has a quiet member`() {
        assertTrue(pushWatchesAllGroups(listOf("g1", "g2"), emptySet(), setOf("g3|kaspa:x")))
        assertFalse(pushWatchesAllGroups(listOf("g1", "g2"), setOf("g2"), emptySet()))
        assertFalse(pushWatchesAllGroups(listOf("g1", "g2"), emptySet(), setOf("g1|kaspa:x")))
    }

    @Test
    fun `rooms push covers are swept every five minutes while push works`() {
        assertTrue(BroadcastScanningService.isPushCoveredSweepDue(pushActive = false, lastCoveredAt = 1_000, nowMs = 2_000))
        assertTrue(BroadcastScanningService.isPushCoveredSweepDue(pushActive = true, lastCoveredAt = 0, nowMs = 2_000))
        assertFalse(BroadcastScanningService.isPushCoveredSweepDue(pushActive = true, lastCoveredAt = 1_000, nowMs = 61_000))
        assertTrue(
            BroadcastScanningService.isPushCoveredSweepDue(
                pushActive = true, lastCoveredAt = 1_000, nowMs = 1_000 + BroadcastScanningService.PUSH_SAFETY_NET_MS,
            )
        )
    }
}
