package com.kachat.app.util

import com.google.gson.Gson

/**
 * A public chat message's sender deleting it for everyone (iOS `MessageDeleteContent`, owner
 * 2026-11-25): sent in the room as `{"type":"delete","targetTxId":"<txid>"}`, like an edit. Honoured
 * only from the address that sent the message it names. The KaChat indexer removes the message
 * (indexer repo docs/ROOM_MESSAGE_DELETES.md); every K drops it from its cache, and remembers the
 * delete so a page that still carries the message can't bring it back. Wire format in MESSAGING.md
 * ("Public Chat Deletes").
 */
data class MessageDeleteContent(
    val type: String = "delete",
    val targetTxId: String,
)

object MessageDelete {
    private val gson = Gson()

    /** `{"type":"delete","targetTxId":"<txid>"}`, byte for byte as iOS `MessageDeleteCodec.encode`
     *  writes it: written out rather than left to a JSON library's key order and escaping. */
    fun encode(targetTxId: String): String = "{\"type\":\"delete\",\"targetTxId\":${jsonString(targetTxId)}}"

    /**
     * The delete [text] carries, or null (iOS `MessageDeleteCodec.parse`): under 2,000 UTF-8 bytes,
     * starting with "{" once trimmed, naming "delete", with type "delete" and a target.
     */
    fun parseOrNull(text: String?): MessageDeleteContent? {
        if (text == null || text.toByteArray(Charsets.UTF_8).size >= 2_000) return null
        val trimmed = text.trim()
        if (trimmed.firstOrNull() != '{' || "\"delete\"" !in trimmed) return null
        return try {
            val parsed = gson.fromJson(trimmed, MessageDeleteContent::class.java) ?: return null
            @Suppress("SENSELESS_COMPARISON")
            if (parsed.type == "delete" && parsed.targetTxId != null && parsed.targetTxId.isNotEmpty()) parsed else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether [body] is (probably) a delete envelope, including one a push server cut off or
     * re-spaced: never announced (iOS NotificationService `isDeleteEnvelope`).
     */
    fun isDeleteEnvelope(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.firstOrNull() != '{') return false
        return trimmed.replace(" ", "").contains("\"type\":\"delete\"")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

/**
 * A reaction, edit or delete envelope: never a message row of its own (iOS
 * `PublicChatService.isControlEnvelope`). Plain text is most of a room, so only JSON is parsed.
 */
fun isRoomControlEnvelope(content: String): Boolean {
    if (content.firstOrNull { !it.isWhitespace() } != '{' || "\"type\"" !in content) return false
    return MessageReaction.parseOrNull(content) != null || MessageEdit.parseOrNull(content) != null ||
        MessageDelete.parseOrNull(content) != null
}
