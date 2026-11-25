package com.kachat.app.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** @mentions by .kachat username (iOS `UsernameMention`, MESSAGING.md "@mentions by username"). */
class UsernameMentionTest {

    private fun names(text: String) = UsernameMention.tokens(text).map { it.name }
    private fun spans(text: String) = UsernameMention.tokens(text).map { text.substring(it.range) }

    // Tokens

    @Test
    fun `a token is @name or @name dot kachat in any case`() {
        assertEquals(listOf("alice", "bob"), names("hi @Alice and @BOB.KaChat"))
        assertEquals(listOf("@Alice", "@BOB.KaChat"), spans("hi @Alice and @BOB.KaChat"))
    }

    @Test
    fun `a token starts the text or follows a space or an opening bracket or quote`() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), names("@a (@b [@c {@d <@e \"@f '@g"))
        assertEquals(listOf("tab", "line"), names("x\t@tab\n@line"))
    }

    @Test
    fun `an at sign inside a word is not a mention`() {
        assertTrue(names("mail me at bob@alice.kachat").isEmpty())
        assertTrue(names("a,@b").isEmpty())
    }

    @Test
    fun `a token ends at the next character outside a-z 0-9 and hyphen`() {
        assertEquals(listOf("@alice"), spans("@alice, hi"))
        assertEquals(listOf("@alice"), spans("@alice: hi"))
        assertEquals(listOf("@alice"), spans("@alice.kas"))
        assertEquals(listOf("@al-ice2"), spans("@al-ice2!"))
    }

    @Test
    fun `names are 1 to 32 characters with no hyphen at either end`() {
        val n32 = "a".repeat(32)
        assertEquals(listOf(n32), names("@$n32"))
        assertTrue(names("@${"a".repeat(33)}").isEmpty())
        assertTrue(names("@-alice").isEmpty())
        assertTrue(names("@alice-").isEmpty())
        assertTrue(names("@ alone").isEmpty())
        assertTrue(names("@").isEmpty())
        assertEquals(listOf("x"), names("@x"))
    }

    @Test
    fun `non ascii letters are not usernames`() {
        assertTrue(names("@éclair").isEmpty())
        assertTrue(names("@аlice").isEmpty()) // a Cyrillic а
    }

    @Test
    fun `mentions checks the bare username`() {
        assertTrue(UsernameMention.mentions("hey @Alice.kachat", "alice"))
        assertTrue(UsernameMention.mentions("@alice", "alice"))
        assertFalse(UsernameMention.mentions("@alice2", "alice"))
        assertFalse(UsernameMention.mentions("bob@alice", "alice"))
        assertFalse(UsernameMention.mentions("@alice", null))
    }

    // Typing

    @Test
    fun `typedQuery is the token at the end of the text`() {
        assertEquals("", UsernameMention.typedQuery("hi @"))
        assertEquals("ali", UsernameMention.typedQuery("hi @Ali"))
        assertEquals("a-b", UsernameMention.typedQuery("(@a-b"))
        assertNull(UsernameMention.typedQuery("hi @ali "))
        assertNull(UsernameMention.typedQuery("bob@ali"))
        assertNull(UsernameMention.typedQuery("no at"))
        assertNull(UsernameMention.typedQuery("@" + "a".repeat(33)))
    }

    @Test
    fun `composerTokenStart finds the at run before the caret`() {
        assertEquals(3, UsernameMention.composerTokenStart("hi @ali", 7))
        assertEquals(3, UsernameMention.composerTokenStart("hi @ali there", 7))
        assertEquals(0, UsernameMention.composerTokenStart("@", 1))
        assertNull(UsernameMention.composerTokenStart("hi @ali there", 13))
        assertNull(UsernameMention.composerTokenStart("hi ali", 6))
        assertNull(UsernameMention.composerTokenStart("", 0))
        assertNull(UsernameMention.composerTokenStart("abc", 9))
    }

    // Group wire format

    private val alice = "kaspatest:qpalicealicealicealicealicealicealicealicealicealice"
    private val alice2 = "kaspatest:qpalice2alice2alice2alice2alice2alice2alice2alice2a"
    private val kaspa = "kaspatest:qpkaspakaspakaspakaspakaspakaspakaspakaspakaspakaspa"

    @Test
    fun `group mentions go out as the member's address with or without the ending`() {
        val named = mapOf("alice" to alice)
        assertEquals("hi @$alice and @$alice", UsernameMention.encodeGroupMentions("hi @alice and @ALICE.kachat", named))
    }

    @Test
    fun `a name that isn't a member's stays text`() {
        val named = mapOf("alice" to alice)
        assertEquals("hi @bob", UsernameMention.encodeGroupMentions("hi @bob", named))
        assertEquals("@alice2 @alice-x", UsernameMention.encodeGroupMentions("@alice2 @alice-x", named))
    }

    @Test
    fun `the longer name wins`() {
        val named = mapOf("alice" to alice, "alice2" to alice2)
        assertEquals("@$alice2 @$alice", UsernameMention.encodeGroupMentions("@alice2 @alice", named))
    }

    @Test
    fun `an address put in is never matched again`() {
        val named = mapOf("alice" to alice, "kaspa" to kaspa)
        assertEquals("@$alice, @$kaspa", UsernameMention.encodeGroupMentions("@alice, @kaspa", named))
    }

    @Test
    fun `group mentions read as names in a banner`() = runBlocking {
        val names = mapOf(alice to "Alice", kaspa to "kaspa.kachat")
        val text = "hi @$alice and @$kaspa, also @someone"
        assertEquals("hi @Alice and @kaspa.kachat, also @someone", UsernameMention.mentionsAsNames(text) { names[it] ?: "?" })
        assertEquals("nothing here", UsernameMention.mentionsAsNames("nothing here") { "?" })
    }

    @Test
    fun `the thread shows a sent mention as the member's name`() {
        val members = listOf(
            com.kachat.app.models.GroupMember(alice, "", isAdmin = false),
            com.kachat.app.models.GroupMember(kaspa, "", isAdmin = true),
        )
        val wire = UsernameMention.encodeGroupMentions("@Alice hi @kaspa.kachat", mapOf("alice" to alice, "kaspa" to kaspa))
        val shown = com.kachat.app.ui.screens.GroupMentionCodec.decodeForDisplay(wire, members) { address ->
            if (address == alice) "alice" else "kaspa"
        }
        assertEquals("@alice hi @kaspa", shown)
    }

    @Test
    fun `group encode and banner decode round trip`() = runBlocking {
        val named = mapOf("alice" to alice)
        val wire = UsernameMention.encodeGroupMentions("@alice.kachat ok", named)
        assertEquals("@alice ok", UsernameMention.mentionsAsNames(wire) { if (it == alice) "alice" else it })
    }
}
