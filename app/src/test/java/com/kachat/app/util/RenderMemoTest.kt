package com.kachat.app.util

import com.kachat.app.models.GroupMember
import com.kachat.app.ui.screens.GroupMentionCodec
import com.kachat.app.ui.screens.MediaPartsInput
import com.kachat.app.ui.screens.joinedPhotoContent
import com.kachat.app.ui.screens.joinedPhotoMemoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 2026-11-25 smoothness sweep's render caches (iOS d089011f). */
class RenderMemoTest {

    @Test
    fun `keeps at most maxCount, dropping the least recently used`() {
        val memo = RenderMemo<String, Int>(maxCount = 2)
        memo.put("a", 1)
        memo.put("b", 2)
        assertEquals(1, memo.get("a")) // a is now the most recent
        memo.put("c", 3)
        assertNull(memo.get("b"))
        assertEquals(1, memo.get("a"))
        assertEquals(3, memo.get("c"))
        assertEquals(2, memo.size)
    }

    @Test
    fun `keeps within maxCost and never holds an entry above it`() {
        val memo = RenderMemo<String, String>(maxCount = 10, maxCost = 10, costOf = { it.length.toLong() })
        memo.put("a", "12345")
        memo.put("b", "12345")
        memo.put("c", "123")
        assertNull(memo.get("a"))
        assertEquals("12345", memo.get("b"))
        memo.put("huge", "12345678901")
        assertNull(memo.get("huge"))
        assertEquals("123", memo.get("c"))
    }

    @Test
    fun `replacing a key counts its cost once`() {
        val memo = RenderMemo<String, String>(maxCount = 10, maxCost = 10, costOf = { it.length.toLong() })
        memo.put("a", "12345")
        memo.put("a", "123456")
        memo.put("b", "1234")
        assertEquals("123456", memo.get("a"))
        assertEquals("1234", memo.get("b"))
    }

    @Test
    fun `getOrPut computes once per key`() {
        val memo = RenderMemo<String, String>(maxCount = 10)
        var runs = 0
        repeat(3) { memo.getOrPut("k") { runs++; "v" } }
        assertEquals(1, runs)
    }

    @Test
    fun `links are found once per text and answer the same`() {
        val text = "see https://kaspa.org and www.example.com today " + System.nanoTime()
        val first = TextLinkify.findUrls(text)
        assertSame(first, TextLinkify.findUrls(text))
        assertEquals(listOf("https://kaspa.org", "https://www.example.com"), first.map { it.uri })
    }

    @Test
    fun `isEntirelyLink keeps its rules`() {
        assertTrue(TextLinkify.isEntirelyLink("https://kaspa.org"))
        assertTrue(TextLinkify.isEntirelyLink("  https://kaspa.org/path  "))
        assertFalse(TextLinkify.isEntirelyLink("https://kaspa.org."))
        assertTrue(TextLinkify.isEntirelyLink("kaspa.org"))
        assertFalse(TextLinkify.isEntirelyLink("see https://kaspa.org"))
        assertFalse(TextLinkify.isEntirelyLink("https://kaspa.org is great"))
        assertFalse(TextLinkify.isEntirelyLink("   "))
        assertFalse(TextLinkify.isEntirelyLink("hello"))
    }

    @Test
    fun `username tokens are scanned once per text`() {
        val text = "hi @alice and @bob.kachat " + System.nanoTime()
        val first = UsernameMention.tokens(text)
        assertSame(first, UsernameMention.tokens(text))
        assertEquals(listOf("alice", "bob"), first.map { it.name })
        assertTrue(UsernameMention.tokens("no mention here").isEmpty())
    }

    @Test
    fun `a group message mentions a member only by their address`() {
        val alice = GroupMember(address = "kaspa:qalice", xOnlyPubKeyHex = "", isAdmin = false, displayName = "Alice")
        val bob = GroupMember(address = "kaspatest:qbob", xOnlyPubKeyHex = "", isAdmin = false, displayName = "Bob")
        val members = listOf(alice, bob)
        assertTrue(GroupMentionCodec.mentionsAnyMember("hi @kaspa:qalice!", members))
        assertTrue(GroupMentionCodec.mentionsAnyMember("@kaspatest:qbob", members))
        assertFalse(GroupMentionCodec.mentionsAnyMember("hi @Alice", members))
        assertFalse(GroupMentionCodec.mentionsAnyMember("hi @kaspa:qsomeoneelse", members))
        assertFalse(GroupMentionCodec.mentionsAnyMember("plain text", members))
    }

    private fun photoPart(bytes: ByteArray): String =
        ImageMessage.encode("photo.jpg", bytes.size.toLong(), java.util.Base64.getEncoder().encodeToString(bytes))

    @Test
    fun `a multi-part photo is joined once and keyed by its parts`() {
        val input = MediaPartsInput(2, listOf(1 to photoPart(byteArrayOf(1, 2, 3)), 2 to photoPart(byteArrayOf(4, 5))))
        val joined = joinedPhotoContent(input)!!
        assertEquals("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5)), joined.content)
        // the same parts again, as new strings: the same joined photo, not joined again
        val again = MediaPartsInput(2, listOf(1 to photoPart(byteArrayOf(1, 2, 3)), 2 to photoPart(byteArrayOf(4, 5))))
        assertSame(joined, joinedPhotoContent(again))
        // different bytes of the same length are a different photo
        val other = MediaPartsInput(2, listOf(1 to photoPart(byteArrayOf(9, 9, 9)), 2 to photoPart(byteArrayOf(4, 5))))
        assertNotEquals(joinedPhotoMemoKey(input), joinedPhotoMemoKey(other))
        assertNotEquals(joined.content, joinedPhotoContent(other)!!.content)
        // incomplete: nothing
        assertNull(joinedPhotoContent(MediaPartsInput(3, input.parts)))
    }
}
