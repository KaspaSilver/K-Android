package com.kachat.app.util

/**
 * The @token being typed at the end of a post's text (iOS 01f71184 `KaPostsMentionToken`).
 *
 * Walks back over the token only: the regex it replaced (`(^|[\s(\[{<"'])@([a-z0-9-]*)$`)
 * scanned the whole post, several times per keystroke (iOS audit 2026-11-27 P6).
 */
object KaPostsMentionToken {
    /** The token ("" right after "@"), lowercased, or null. It must start the text or follow
     *  whitespace or opening punctuation, so emails don't trigger it. */
    fun query(text: String): String? {
        var start = text.length
        while (start > 0) {
            val ch = text[start - 1]
            if (ch.code >= 128 || !(ch.isLetterOrDigit() || ch == '-')) break
            start -= 1
        }
        if (start == 0) return null
        val at = start - 1
        if (text[at] != '@') return null
        if (at > 0) {
            val lead = text[at - 1]
            if (!(lead.isWhitespace() || lead in "([{<\"'")) return null
        }
        return text.substring(start).lowercase()
    }
}
