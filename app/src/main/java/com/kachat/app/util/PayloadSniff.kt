package com.kachat.app.util

/**
 * Text searches that rule a payload out before any JSON decode (iOS `PayloadSniff`, smoothness
 * audit 2026-11-27 C1). A whole history's photos and voice parts are JSON too, and each was
 * decoded again by every chess and reply check that went over it.
 */
object PayloadSniff {
    /** `"chess_`: the start of every chess envelope's type value (escaped inside a reply, still
     *  the same characters). */
    const val CHESS_TYPE = "\"chess_"

    /** `"reply"`: a reply envelope's type value. */
    const val REPLY_TYPE = "\"reply\""

    /** Whether [text] can be a chess envelope at all. */
    fun mayBeChess(text: String?): Boolean = text != null && text.contains(CHESS_TYPE)

    /** Whether [text] can be a reply envelope at all. */
    fun mayBeReply(text: String?): Boolean = text != null && text.contains(REPLY_TYPE)
}
