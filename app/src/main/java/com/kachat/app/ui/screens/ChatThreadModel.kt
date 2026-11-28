package com.kachat.app.ui.screens

import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatMessagePartAccessors
import com.kachat.app.util.ChessGameEngine
import com.kachat.app.util.VoicePartGrouping

/**
 * What the open 1:1 thread draws, worked out once per change OFF the main thread (audit
 * 2026-11-27 Smoothness S1): the hidden-row filter, the long voice note / multi-part photo
 * grouping and the chess source list used to run in composition over the whole loaded history on
 * every emission - a read flag or another chat's sweep included.
 *
 * Deliberately not a data class: Compose compares a new state value with the old one on the main
 * thread, and a structural compare of thousands of rows is the very cost this moves away. The
 * flow that builds it drops unchanged lists before building ([ChatViewModel.threadModel]).
 */
class ChatThreadModel(
    /** The window plus the older pages, in chain order (what [ChatViewModel.threadMessages] gives). */
    val all: List<MessageEntity>,
    /** What the list shows: no hidden placeholders, and one row per voice note or photo. */
    val messages: List<MessageEntity>,
    val mediaPartGroups: VoicePartGrouping.Result<MessageEntity>,
    /** Only the rows that can hold a chess message, for the top bar's game and the chess rows. */
    val chessSources: List<ChessGameEngine.SimpleChessSourceMessage>,
    /** Each shown row's [ThreadRowKind], for the list's contentType. */
    private val rowKinds: Map<String, Int> = emptyMap(),
) {
    fun rowKind(message: MessageEntity): Int = rowKinds[message.id] ?: ThreadRowKind.TEXT

    companion object {
        val EMPTY = ChatThreadModel(emptyList(), emptyList(), VoicePartGrouping.Result(), emptyList())

        /** [previous]: the model this one replaces. Its chess list is kept when unchanged, so the
         *  chess rows' per-game summaries (remembered on that list) survive an unrelated change. */
        fun build(all: List<MessageEntity>, previous: ChatThreadModel? = null): ChatThreadModel {
            if (all.isEmpty()) return EMPTY
            // "Sent via another device" rows are backup fill-in slots, never UI (matches iOS and the
            // chess mini chat); messages this account can't decrypt are hidden the same way.
            val visible = all.filterNot { it.isHiddenPlaceholder }
            // Long voice notes' and multi-part photos' parts (iOS 7c5c22e3): each shows as one
            // row, its earliest part; the other parts are left out.
            val groups = VoicePartGrouping.group(visible, ChatMessagePartAccessors)
            val messages = if (groups.hiddenTxIds.isEmpty()) visible else visible.filterNot { it.id in groups.hiddenTxIds }
            val chess = messages.filter { mayHoldChess(it.plaintextBody) }.map {
                ChessGameEngine.SimpleChessSourceMessage(
                    id = it.id,
                    plaintextBody = it.plaintextBody,
                    isOutgoing = it.direction == "sent",
                    blockTimestamp = it.blockTimestamp,
                    sendFailed = it.direction == "sent" && it.deliveryStatus == "failed",
                )
            }
            val chessSources = if (previous != null && previous.chessSources == chess) previous.chessSources else chess
            val kinds = HashMap<String, Int>(messages.size * 2)
            for (m in messages) kinds[m.id] = ThreadRowKind.of(m)
            return ChatThreadModel(all, messages, groups, chessSources, kinds)
        }
    }
}

/**
 * How much older history entering a 1:1 chat pulls into memory on its own (audit 2026-11-27
 * Battery R3). iOS d30cc0a loads the whole stored history; a chat with hundreds of photos or long
 * voice notes held tens of MB of base64 that way, re-merged on every page. The preload now stops
 * at the newest [MAX_ROWS] older rows or about [MAX_CHARS] characters of bodies, whichever comes
 * first; anything older still pages in as the reader scrolls up, and "jump to the first message"
 * still loads it all.
 */
object ThreadHistoryPreload {
    const val MAX_ROWS = 2_000
    /** About 16 MB as Java strings (two bytes a character). */
    const val MAX_CHARS = 8_000_000L

    /** The characters [rows] hold in their two text columns. */
    fun charsHeld(rows: List<MessageEntity>): Long =
        rows.sumOf { (it.plaintextBody?.length ?: 0).toLong() + it.encryptedPayload.length }

    /** Whether the preload may fetch another page after holding [rows]. */
    fun mayContinue(rows: List<MessageEntity>): Boolean =
        rows.size < MAX_ROWS && charsHeld(rows) < MAX_CHARS
}

/**
 * A timeline row's shape, for a lazy list's `contentType` (audit 2026-11-27 Smoothness S4): with
 * one reuse pool for every row, a slot that held a photo, voice note, chess or payment bubble was
 * handed to a text bubble (or the reverse) and most of its subtree rebuilt. Read from the head of
 * the body only - a photo's or voice note's base64 is never scanned.
 */
object ThreadRowKind {
    const val TEXT = 0
    const val IMAGE = 1
    const val VOICE = 2
    const val CHESS = 3
    const val PAYMENT = 4
    const val SYSTEM = 5
    const val FILE = 6

    fun of(body: String?, isPayment: Boolean = false, isSystem: Boolean = false): Int {
        if (isSystem) return SYSTEM
        if (isPayment) return PAYMENT
        val mime = com.kachat.app.util.InlineMediaSniff.mimeType(body)?.lowercase()
        return when {
            mime == null -> if (body != null && body.contains("\"chess_")) CHESS else TEXT
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("audio/") -> VOICE
            else -> FILE
        }
    }

    /** A 1:1 row: a payment and a handshake each have their own bubble. */
    fun of(message: MessageEntity): Int = of(
        message.plaintextBody,
        isPayment = message.type == "pay",
        isSystem = message.type == com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE,
    )
}
