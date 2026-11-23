package com.kachat.app.util

/**
 * A payment's text as people read it: "Sent 1 KAS: Testing", never with the " — " the stored text
 * keeps between amount and memo (iOS `ChatMessage.paymentDisplayText` / `readableContent`, owner
 * 2026-11-22). Only what is displayed changes: the stored body still carries " — "
 * ([PaymentPoolProtocol.receivedNoticeText] and friends write it), because the payment card and
 * older messages split amount from memo on it.
 */
object PaymentDisplayText {

    private const val STORED_SEPARATOR = " — "
    private const val DISPLAY_SEPARATOR = ": "

    /** [content] with its FIRST " — " shown as ": " (a dash inside the memo stays). */
    fun paymentDisplayText(content: String): String {
        val index = content.indexOf(STORED_SEPARATOR)
        if (index < 0) return content
        return content.substring(0, index) + DISPLAY_SEPARATOR + content.substring(index + STORED_SEPARATOR.length)
    }

    /** A message body as shown: [paymentDisplayText] for a payment (`type == "pay"`), any other
     *  message's body unchanged (iOS `readableContent`). */
    fun readableBody(type: String?, body: String): String =
        if (type == MessageProtocol.TYPE_PAY) paymentDisplayText(body) else body

    /** [readableBody] for a nullable body (null stays null). */
    fun readableBodyOrNull(type: String?, body: String?): String? = body?.let { readableBody(type, it) }
}
