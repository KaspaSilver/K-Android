package com.kachat.app.util

import com.kachat.app.models.BroadcastMessageEntity

/**
 * The media-file JSON a room voice note goes on chain as, written by hand so its bytes are iOS's
 * (iOS 1b15521 `MediaFileEnvelope.json`): `type`, `name`, `size`, `mimeType`, then `voicePart` (one
 * part of a long voice note), so head-only sniffs ([VoicePartTag.sniff]) find it, then `content`.
 */
object BroadcastMediaFileEnvelope {
    fun json(name: String, size: Int, mimeType: String, dataUrlContent: String, voicePart: VoicePartTag? = null): String {
        val part = voicePart?.let { ",\"voicePart\":${it.json}" } ?: ""
        return "{\"type\":\"file\",\"name\":\"${escape(name)}\",\"size\":$size,\"mimeType\":\"${escape(mimeType)}\"$part,\"content\":\"${escape(dataUrlContent)}\"}"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Public Chats rooms' voice notes (iOS 148a5331 `VoiceNotePolicy.publicChat`): the same sizes as
 * 1:1 - parts of 14.5 s, at most 31,000 bytes each, the note 170 s (2:50), 12 parts, under 1 KAS.
 */
object PublicChatVoiceNotePolicy {
    val PUBLIC_CHAT = VoiceNotePolicy.Path(partSeconds = 14.5, maxSeconds = 170.0, maxBytes = 31_000)
}

/** Parts of one long voice note in a room: same room, sender, tag id and count (iOS 1b15521
 *  `PublicChatVoiceNote`). */
data class BroadcastVoiceNote(
    val key: String,
    val count: Int,
    /** The parts present, one per index, in index order. */
    val parts: List<BroadcastMessageEntity>,
    /** The lowest-index part present: where the one bubble sits, and whose time it shows. */
    val anchor: BroadcastMessageEntity,
) {
    val isComplete: Boolean get() = parts.size == count

    /** The note's status: any failed part fails it, then any part still sending keeps it
     *  sending, then any part not confirmed shows that. */
    val deliveryStatus: String
        get() {
            val statuses = parts.map { it.deliveryStatus }
            return when {
                "failed" in statuses -> "failed"
                "pending" in statuses -> "pending"
                "unconfirmed" in statuses -> "unconfirmed"
                else -> "sent"
            }
        }

    /** The parts to resend: failed, in index order. */
    val failedParts: List<BroadcastMessageEntity> get() = parts.filter { it.deliveryStatus == "failed" }

    companion object {
        fun key(channel: String, sender: String, tag: VoicePartTag): String =
            "${channel.lowercase()}|${sender.lowercase()}|${tag.id}|${tag.count}"
    }
}

/** iOS 1b15521 `PublicChatVoiceParts`. */
object BroadcastVoiceParts {
    data class Result(
        /** Notes by their anchor's id. */
        val notesByAnchorId: Map<String, BroadcastVoiceNote> = emptyMap(),
        /** Parts that aren't their note's anchor: not drawn. */
        val hiddenIds: Set<String> = emptySet(),
        /** Each hidden part's anchor id, so a reply or reaction aimed at a hidden part (another
         *  client that shows parts one by one) lands on the bubble. */
        val anchorIdByPartId: Map<String, String> = emptyMap(),
    )

    /** Groups the voice-note parts of a room's [messages], which must be in room order. A note
     *  with one part present is still a note (its bubble says it is receiving the rest). */
    fun group(messages: List<BroadcastMessageEntity>): Result {
        val tagged = LinkedHashMap<String, MutableList<Pair<BroadcastMessageEntity, VoicePartTag>>>()
        for (message in messages) {
            if (message.content.firstOrNull() != '{') continue
            val tag = VoicePartTag.sniff(message.content) ?: continue
            val key = BroadcastVoiceNote.key(message.channelName, message.senderAddress, tag)
            tagged.getOrPut(key) { ArrayList() }.add(message to tag)
        }
        if (tagged.isEmpty()) return Result()
        val notes = HashMap<String, BroadcastVoiceNote>()
        val hidden = HashSet<String>()
        val anchorIdByPartId = HashMap<String, String>()
        for ((key, members) in tagged) {
            if (members.isEmpty()) continue
            val byIndex = java.util.TreeMap<Int, BroadcastMessageEntity>()
            for ((message, tag) in members) {
                val existing = byIndex[tag.index]
                // Two messages for one index (a resend that landed twice): the delivered one.
                if (existing == null || priority(message.deliveryStatus) > priority(existing.deliveryStatus)) {
                    byIndex[tag.index] = message
                }
            }
            // The anchor is the lowest index present, not the earliest row: while a note sends,
            // its parts swap places as each one's local time becomes the chain's, and an anchor
            // that followed room order hopped from part to part (and stopped playback).
            val parts = byIndex.values.toList()
            val anchor = parts.firstOrNull() ?: continue
            notes[anchor.id] = BroadcastVoiceNote(
                key = key,
                count = members[0].second.count,
                parts = parts,
                anchor = anchor,
            )
            for ((message, _) in members) {
                if (message.id != anchor.id) {
                    hidden.add(message.id)
                    anchorIdByPartId[message.id] = anchor.id
                }
            }
        }
        return Result(notes, hidden, anchorIdByPartId)
    }

    /** The note key of a message that is a part, else null. */
    fun noteKey(message: BroadcastMessageEntity): String? {
        if (message.content.firstOrNull() != '{') return null
        val tag = VoicePartTag.sniff(message.content) ?: return null
        return BroadcastVoiceNote.key(message.channelName, message.senderAddress, tag)
    }

    private fun priority(status: String): Int = when (status) {
        "sent" -> 3
        "unconfirmed" -> 2
        "pending" -> 1
        else -> 0
    }
}
