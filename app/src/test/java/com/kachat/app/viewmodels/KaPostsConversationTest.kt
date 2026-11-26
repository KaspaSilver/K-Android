package com.kachat.app.viewmodels

import com.kachat.app.models.KaPostDraft
import com.kachat.app.services.KaPostsNotificationPoller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KaPost conversations like X (iOS 961fc05a): the poster-only thread, reply order (L4) and
 *  "Replying to" (L5). */
class KaPostsConversationTest {
    private val poster = "kaspa:poster"
    private val me = "kaspa:me"
    private val friend = "kaspa:friend"
    private val stranger = "kaspa:stranger"

    private fun reply(id: String, by: String, likes: Int = 0, at: Long = 1_000L, remote: Boolean = true) =
        KaPostDraft(id = id, text = id, timestamp = at, posterAddress = by, remoteId = if (remote) "tx-$id" else null, likes = likes)

    @Test
    fun `someone else's lone reply is never the thread`() {
        assertNull(KaPostsConversation.selfThreadNext(listOf(reply("a", stranger)), poster))
    }

    @Test
    fun `the thread is the poster's earliest own reply`() {
        val next = KaPostsConversation.selfThreadNext(
            listOf(reply("late", poster, at = 3_000), reply("other", stranger, at = 1_000), reply("early", poster, at = 2_000)),
            poster,
        )
        assertEquals("early", next?.id)
    }

    @Test
    fun `replies rank poster, you, followed, rest, then likes, then oldest`() {
        val ranked = KaPostsConversation.rankReplies(
            listOf(
                reply("s-old", stranger, likes = 1, at = 1),
                reply("s-liked", stranger, likes = 9, at = 5),
                reply("s-new", stranger, likes = 1, at = 9),
                reply("f", friend),
                reply("m", me),
                reply("p", poster),
            ),
            poster, me, setOf(friend),
        )
        assertEquals(listOf("p", "m", "f", "s-liked", "s-old", "s-new"), ranked.map { it.id })
    }

    private fun ordered(memo: CommentOrderMemo, replies: List<KaPostDraft>, now: Long = 10_000L) = memo.ordered(
        replies,
        "root",
        rank = { KaPostsConversation.rankReplies(it, poster, me, emptySet()) },
        goesUp = { KaPostsConversation.goesUp(it, me, now) },
        isTopTier = { KaPostsConversation.isTopTier(it, poster, me) },
    ).map { it.id }

    @Test
    fun `rows keep their place when a like lands`() {
        val memo = CommentOrderMemo()
        assertEquals(listOf("a", "b"), ordered(memo, listOf(reply("a", stranger, likes = 5), reply("b", stranger, likes = 1))))
        // b overtakes a on likes: the order shown stays
        assertEquals(listOf("a", "b"), ordered(memo, listOf(reply("a", stranger, likes = 5), reply("b", stranger, likes = 50))))
    }

    @Test
    fun `a later page goes below, your fresh reply goes up after the top tier`() {
        val memo = CommentOrderMemo()
        ordered(memo, listOf(reply("p", poster), reply("a", stranger)))
        val next = ordered(
            memo,
            listOf(reply("p", poster), reply("a", stranger), reply("old-page", stranger, likes = 99), reply("mine", me, remote = false)),
        )
        assertEquals(listOf("p", "mine", "a", "old-page"), next)
    }

    @Test
    fun `forgotten, the next opening ranks afresh`() {
        val memo = CommentOrderMemo()
        ordered(memo, listOf(reply("a", stranger, likes = 5), reply("b", stranger, likes = 1)))
        memo.forget("root")
        assertEquals(listOf("b", "a"), ordered(memo, listOf(reply("a", stranger, likes = 5), reply("b", stranger, likes = 50))))
    }

    @Test
    fun `an old reply of yours from a later page is not fresh`() {
        assertFalse(KaPostsConversation.goesUp(reply("x", me, at = 0), me, KaPostsConversation.FRESH_OWN_REPLY_MS + 1))
        assertTrue(KaPostsConversation.goesUp(reply("x", me, at = 0, remote = false), me, Long.MAX_VALUE))
        assertFalse(KaPostsConversation.goesUp(reply("x", stranger, remote = false), me, 0))
    }

    @Test
    fun `replying to names the parent's author, then the first other mention`() {
        val addressOf = { key: String -> "kaspa:$key" }
        val post = KaPostDraft(
            text = "t", timestamp = 0, posterAddress = "kaspa:self", parentRemoteId = "parent",
            mentionedPubkeys = listOf("self", "bob"),
        )
        // the parent isn't loaded: the first signed mention that isn't the poster
        assertEquals("kaspa:bob", KaPostsConversation.replyingToAddress(post, addressOf) { null })
        // the parent is loaded: its author, even for the poster's own thread reply
        assertEquals("kaspa:self", KaPostsConversation.replyingToAddress(post, addressOf) { "kaspa:self" })
        // known for a reply sent this session
        assertEquals("kaspa:alice", KaPostsConversation.replyingToAddress(post.copy(parentPosterPubkey = "alice"), addressOf) { null })
        // a post answers nobody
        assertNull(KaPostsConversation.replyingToAddress(post.copy(parentRemoteId = null), addressOf) { "x" })
    }

    @Test
    fun `a missed parent is asked again only after 15 seconds`() {
        var now = 0L
        var asked = 0
        var parent: KaPostDraft? = null
        val authors = ReplyParentAuthors { now }
        val find = { _: String -> asked++; parent }
        assertNull(authors.author("p", find))
        now = 5_000
        assertNull(authors.author("p", find))
        assertEquals(1, asked)
        parent = KaPostDraft(text = "", timestamp = 0, posterAddress = "kaspa:alice")
        now = 15_000
        assertEquals("kaspa:alice", authors.author("p", find))
        assertEquals("kaspa:alice", authors.author("p", find))
        assertEquals(2, asked)
    }

    @Test
    fun `the KaPost poll runs every beat without push and every five minutes with it`() {
        assertTrue(KaPostsNotificationPoller.shouldPollOnBeat(pushActive = false, lastPollAtMs = 1_000, nowMs = 2_000))
        assertFalse(KaPostsNotificationPoller.shouldPollOnBeat(pushActive = true, lastPollAtMs = 1_000, nowMs = 61_000))
        assertTrue(KaPostsNotificationPoller.shouldPollOnBeat(pushActive = true, lastPollAtMs = 0, nowMs = 1_000))
        assertTrue(KaPostsNotificationPoller.shouldPollOnBeat(pushActive = true, lastPollAtMs = 1_000, nowMs = 1_000 + 5 * 60_000))
    }
}
