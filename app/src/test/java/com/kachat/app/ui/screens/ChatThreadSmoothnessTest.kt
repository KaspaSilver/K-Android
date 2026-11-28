package com.kachat.app.ui.screens

import com.kachat.app.models.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-27 Smoothness S1, S4, S10 and Battery R3: the 1:1 thread model, row kinds,
 *  the history preload's budget and the chess sniff. */
class ChatThreadSmoothnessTest {
    private fun msg(
        id: String,
        body: String?,
        t: Long = id.hashCode().toLong(),
        type: String = "comm",
        direction: String = "received",
        payload: String = "",
    ) = MessageEntity(
        id = id, contactId = "kaspa:qcontact", walletAddress = "kaspa:qme", type = type, direction = direction,
        plaintextBody = body, encryptedPayload = payload, amountSompi = null, blockTimestamp = t,
    )

    private val photo = """{"type":"file","name":"p.jpg","size":3,"mimeType":"image/jpeg","content":"data:image/jpeg;base64,""" +
        "A".repeat(40_000) + "\"}"
    private val voice = """{"type":"file","name":"v.webm","size":3,"mimeType":"audio/webm","content":"data:audio/webm;base64,""" +
        "B".repeat(40_000) + "\"}"
    private val chess = """{"type":"chess_move","gameId":"g1","move":"e2e4"}"""

    @Test
    fun hiddenPlaceholdersAreLeftOut() {
        val rows = listOf(
            msg("a", "hello", 1),
            msg("b", MessageEntity.UNREADABLE_PLACEHOLDER, 2),
            msg("c", "world", 3),
        )
        val model = ChatThreadModel.build(rows)
        assertEquals(listOf("a", "c"), model.messages.map { it.id })
        assertEquals(3, model.all.size)
    }

    @Test
    fun chessSourcesSkipMediaAndKeepTheirListWhenUnchanged() {
        val rows = listOf(msg("a", photo, 1), msg("b", chess, 2, direction = "sent"), msg("c", voice, 3))
        val first = ChatThreadModel.build(rows)
        assertEquals(listOf("b"), first.chessSources.map { it.id })
        assertTrue(first.chessSources.single().isOutgoing)
        // A read flag elsewhere: a new model, the same chess list instance (its summaries stay).
        val second = ChatThreadModel.build(rows + msg("d", "hi", 4), first)
        assertSame(first.chessSources, second.chessSources)
        // A new move: a new list.
        val third = ChatThreadModel.build(rows + msg("e", chess.replace("e2e4", "d2d4"), 5), second)
        assertEquals(listOf("b", "e"), third.chessSources.map { it.id })
    }

    @Test
    fun emptyHistoryIsTheSharedEmptyModel() {
        assertSame(ChatThreadModel.EMPTY, ChatThreadModel.build(emptyList()))
    }

    @Test
    fun rowKindsReadOnlyTheHead() {
        assertEquals(ThreadRowKind.IMAGE, ThreadRowKind.of(photo))
        assertEquals(ThreadRowKind.VOICE, ThreadRowKind.of(voice))
        assertEquals(ThreadRowKind.CHESS, ThreadRowKind.of(chess))
        assertEquals(ThreadRowKind.TEXT, ThreadRowKind.of("just text"))
        assertEquals(ThreadRowKind.TEXT, ThreadRowKind.of(null))
        assertEquals(ThreadRowKind.FILE, ThreadRowKind.of("""{"type":"file","mimeType":"application/pdf","content":"data:application/pdf;base64,AA"}"""))
        assertEquals(ThreadRowKind.PAYMENT, ThreadRowKind.of(msg("p", "1 KAS", type = "pay")))
        assertEquals(ThreadRowKind.SYSTEM, ThreadRowKind.of(msg("h", "{}", type = "handshake")))
        val model = ChatThreadModel.build(listOf(msg("a", photo, 1), msg("b", "x", 2)))
        assertEquals(ThreadRowKind.IMAGE, model.rowKind(model.messages[0]))
        assertEquals(ThreadRowKind.TEXT, model.rowKind(model.messages[1]))
    }

    @Test
    fun mayHoldChessAnswersMediaFromItsHead() {
        assertTrue(mayHoldChess(chess))
        assertFalse(mayHoldChess(photo))
        // A photo whose base64 happens to hold the marker is still a photo.
        assertFalse(mayHoldChess(photo.replace("AAAA", "\"chess_")))
        assertFalse(mayHoldChess(null))
        assertFalse(mayHoldChess("hello"))
    }

    @Test
    fun preloadStopsAtTheRowCap() {
        val rows = (1..ThreadHistoryPreload.MAX_ROWS).map { msg("m$it", "hi", it.toLong()) }
        assertTrue(ThreadHistoryPreload.mayContinue(rows.dropLast(1)))
        assertFalse(ThreadHistoryPreload.mayContinue(rows))
    }

    @Test
    fun preloadStopsAtTheCharacterCap() {
        // 200 photo parts of ~40 KB: under the row cap, over the size cap.
        val rows = (1..200).map { msg("p$it", photo, it.toLong()) }
        assertTrue(ThreadHistoryPreload.charsHeld(rows) >= ThreadHistoryPreload.MAX_CHARS)
        assertFalse(ThreadHistoryPreload.mayContinue(rows))
        assertTrue(ThreadHistoryPreload.mayContinue(rows.take(10)))
        // The payload counts too.
        assertEquals(7L, ThreadHistoryPreload.charsHeld(listOf(msg("x", "abc", payload = "dead"))))
    }
}
