package com.kachat.app.util

import com.google.gson.Gson

/**
 * A reaction (tapback) to an earlier message — embedded as JSON directly in the same plaintext
 * content used for plain text (no separate wire type), matching how [MessageReply] already
 * carries its target reference. [targetTxId] is the reacted-to message's Kaspa transaction id
 * (the only identifier both parties/platforms agree on - a local row id isn't shared). [action]
 * is "add" or "remove": picking a new emoji on a message you've already reacted to replaces your
 * previous one, and tapping your currently-active reaction again removes it.
 */
data class MessageReactionContent(
    val type: String = "reaction",
    val targetTxId: String,
    val emoji: String,
    val action: String
)

object MessageReaction {
    private val gson = Gson()

    fun encode(targetTxId: String, emoji: String, action: String): String {
        return gson.toJson(MessageReactionContent(targetTxId = targetTxId, emoji = emoji, action = action))
    }

    /**
     * Parses [text] as a reaction if it looks like one, else returns null - same {-prefix +
     * size-guard + explicit type check as [MessageReply.parseOrNull], for the same reason (avoid
     * a wasted Gson parse attempt on every other JSON-shaped message envelope during scrolling).
     */
    fun parseOrNull(text: String?): MessageReactionContent? {
        val parsed = parseRaw(text) ?: return null
        // Anything but one emoji is never shown (pill or banner). It still parses, so it never
        // becomes a JSON bubble: it reads as a removal (empty emoji), since an add replaces the
        // reactor's earlier reaction anyway (iOS 5b7c772c MessageReactionCodec.parse, audit #12).
        if (!isReactionEmoji(parsed.emoji)) return parsed.copy(emoji = "", action = "remove")
        return parsed
    }

    /** A reaction whose emoji isn't one emoji: the app reads it as a removal and shows nothing,
     *  so a banner says nothing either (iOS 5b7c772c NotificationService.isInvalidReaction). */
    fun isInvalidReaction(text: String?): Boolean {
        val parsed = parseRaw(text) ?: return false
        return !isReactionEmoji(parsed.emoji)
    }

    private fun parseRaw(text: String?): MessageReactionContent? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, MessageReactionContent::class.java) ?: return null
            if (parsed.type == "reaction") parsed else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One grapheme cluster that is an emoji: an emoji-presentation scalar, or one made an emoji by
     * more scalars (U+FE0F, a skin tone, a ZWJ sequence, a keycap, a flag's pair or tags), at most
     * 48 UTF-8 bytes (iOS 5b7c772c `MessageReactionCodec.isReactionEmoji`). Gson can leave
     * [text] null for a missing field.
     */
    fun isReactionEmoji(text: String?): Boolean {
        if (text.isNullOrEmpty() || text.toByteArray(Charsets.UTF_8).size > 48) return false
        val scalars = text.codePoints().toArray()
        val first = scalars.first()
        if (!EmojiScalars.isEmoji(first)) return false
        val allEmojiParts = scalars.all { scalar ->
            EmojiScalars.isEmoji(scalar) || EmojiScalars.isModifier(scalar) ||
                scalar == VARIATION_SELECTOR_16 || scalar == ZWJ || scalar == COMBINING_KEYCAP || scalar in TAG_RANGE
        }
        if (!allEmojiParts || !isOneCluster(scalars)) return false
        return EmojiScalars.isEmojiPresentation(first) || scalars.size > 1
    }

    /**
     * Whether [scalars] - every one an emoji part - make one user-perceived character (Swift's
     * `count == 1`): a base, its extenders (U+FE0F, the keycap mark, a skin tone, tags), and
     * ZWJ-joined pictographs after it; or a pair of regional indicators (a flag).
     */
    private fun isOneCluster(scalars: IntArray): Boolean {
        var i = 1
        // a flag: two regional indicators
        if (EmojiScalars.isRegionalIndicator(scalars[0]) && i < scalars.size && EmojiScalars.isRegionalIndicator(scalars[i])) i++
        var lastBaseIsPictograph = EmojiScalars.isPictograph(scalars[0])
        while (i < scalars.size) {
            val scalar = scalars[i]
            when {
                isExtender(scalar) -> i++
                scalar == ZWJ -> {
                    i++
                    if (i < scalars.size && !isExtender(scalars[i]) && scalars[i] != ZWJ) {
                        // GB11: only a pictograph joins a pictograph
                        if (!lastBaseIsPictograph || !EmojiScalars.isPictograph(scalars[i])) return false
                        lastBaseIsPictograph = true
                        i++
                    }
                }
                else -> return false
            }
        }
        return true
    }

    private fun isExtender(scalar: Int): Boolean =
        scalar == VARIATION_SELECTOR_16 || scalar == COMBINING_KEYCAP || EmojiScalars.isModifier(scalar) || scalar in TAG_RANGE

    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val ZWJ = 0x200D
    private const val COMBINING_KEYCAP = 0x20E3
    private val TAG_RANGE = 0xE0020..0xE007F
}

/**
 * Unicode's Emoji, Emoji_Presentation and Emoji_Modifier properties, as iOS reads them from
 * `Unicode.Scalar.Properties`. Android has no equivalent on every supported API level (and none in
 * unit tests), so they come from the emoji table the reaction picker already carries (iOS's own
 * values, [com.kachat.app.ui.screens.EMOJI_SCALAR_TABLES]) plus what that table leaves out: the
 * keycap bases, the regional indicators and the skin tones.
 */
internal object EmojiScalars {
    /** Scalar -> whether it has Emoji_Presentation. */
    private val table: Map<Int, Boolean> by lazy {
        val map = HashMap<Int, Boolean>(1_500)
        com.kachat.app.ui.screens.EMOJI_SCALAR_TABLES.forEach { chunk ->
            chunk.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split(';', limit = 3)
                val value = parts.getOrNull(0)?.toIntOrNull(16) ?: return@forEach
                map[value] = parts.getOrNull(1)?.contains('P') == true
            }
        }
        map
    }

    fun isRegionalIndicator(scalar: Int): Boolean = scalar in 0x1F1E6..0x1F1FF
    fun isModifier(scalar: Int): Boolean = scalar in 0x1F3FB..0x1F3FF
    private fun isKeycapBase(scalar: Int): Boolean = scalar == 0x23 || scalar == 0x2A || scalar in 0x30..0x39

    fun isEmoji(scalar: Int): Boolean =
        table.containsKey(scalar) || isKeycapBase(scalar) || isRegionalIndicator(scalar) || isModifier(scalar)

    fun isEmojiPresentation(scalar: Int): Boolean =
        table[scalar] == true || isRegionalIndicator(scalar) || isModifier(scalar)

    /** Extended_Pictographic, near enough: an emoji that isn't a keycap base, a regional
     *  indicator or a skin tone - what a ZWJ sequence joins. */
    fun isPictograph(scalar: Int): Boolean = table.containsKey(scalar)
}
