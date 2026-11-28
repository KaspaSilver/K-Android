package com.kachat.app.util

/**
 * One activity-scoped ChatViewModel serves every 1:1 and group thread, so composer state it holds
 * (a reply, an edit, a staged photo, the fee pill's custom fee) has to remember which conversation
 * it was set in: it shows and sends only there, and it doesn't leak into another chat or account.
 * iOS keeps this state per view, so it never had the problem.
 */
object ConversationOwnership {
    /** The owner key for a 1:1 thread. */
    fun contact(contactId: String): String = "contact:$contactId"

    /** The owner key for a group thread. */
    fun group(groupId: String): String = "group:$groupId"

    /** Whether state staged with [owner] belongs to [conversation]. A null owner (staged outside any
     *  thread, like the share sheet's unsent photo, which then opens its chat) belongs to whichever
     *  thread shows it. */
    fun belongsTo(owner: String?, conversation: String): Boolean = owner == null || owner == conversation

    /** [value] when it belongs to [conversation], else null. */
    fun <T : Any> ownedBy(value: T?, owner: String?, conversation: String): T? =
        if (value != null && belongsTo(owner, conversation)) value else null

    /** The 1:1 thread route NavHost registers, `chat/{contactId}?paymentMode={paymentMode}`. */
    const val CHAT_ROUTE = "chat/{contactId}?paymentMode={paymentMode}"

    /** Whether a back stack entry ([route] its destination's route, [contactIdArg] its contactId
     *  argument) is the 1:1 thread with [contactId] - pushing that chat again would stack it twice. */
    fun isChatWith(route: String?, contactIdArg: String?, contactId: String): Boolean =
        route == CHAT_ROUTE && contactIdArg == contactId
}
