package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Composer state the shared ChatViewModel holds shows and sends only in the thread it was set in. */
class ConversationOwnershipTest {

    @Test
    fun contactAndGroupKeysNeverCollide() {
        assertFalse(ConversationOwnership.contact("abc") == ConversationOwnership.group("abc"))
    }

    @Test
    fun stateBelongsOnlyToItsOwnThread() {
        val a = ConversationOwnership.contact("kaspa:a")
        val b = ConversationOwnership.contact("kaspa:b")
        assertTrue(ConversationOwnership.belongsTo(a, a))
        assertFalse(ConversationOwnership.belongsTo(a, b))
        assertFalse(ConversationOwnership.belongsTo(ConversationOwnership.group("g"), ConversationOwnership.contact("g")))
    }

    @Test
    fun unownedStateBelongsToTheThreadShowingIt() {
        // The share sheet's unsent photo is staged before its chat opens.
        assertTrue(ConversationOwnership.belongsTo(null, ConversationOwnership.contact("kaspa:a")))
    }

    @Test
    fun ownedByHidesAnotherThreadsValue() {
        val a = ConversationOwnership.contact("kaspa:a")
        val b = ConversationOwnership.contact("kaspa:b")
        assertEquals(5L, ConversationOwnership.ownedBy(5L, a, a))
        assertNull(ConversationOwnership.ownedBy(5L, a, b))
        assertNull(ConversationOwnership.ownedBy<Long>(null, a, a))
    }

    @Test
    fun chatEntryMatchesOnlyTheSameContact() {
        val route = ConversationOwnership.CHAT_ROUTE
        assertTrue(ConversationOwnership.isChatWith(route, "kaspa:a", "kaspa:a"))
        assertFalse(ConversationOwnership.isChatWith(route, "kaspa:b", "kaspa:a"))
        assertFalse(ConversationOwnership.isChatWith("chat_info/{contactId}", "kaspa:a", "kaspa:a"))
        assertFalse(ConversationOwnership.isChatWith(null, null, "kaspa:a"))
    }
}
