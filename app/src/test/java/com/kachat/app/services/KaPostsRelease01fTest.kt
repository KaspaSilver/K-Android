package com.kachat.app.services

import com.kachat.app.models.KaPostDraft
import com.kachat.app.util.KaPostsMentionToken
import com.kachat.app.viewmodels.KaPostsConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 01f71184's Posts changes: repost-of-repost cards, the mention token, T4 and B1. */
class KaPostsRelease01fTest {

    // MARK: - Repost of a repost (KaPostRepostOrigins)

    private fun ref(id: String?, text: String, author: String = "kaspa:$id") =
        KaPostDraft.QuotedRef(remoteId = id, text = text, posterAddress = author, timestamp = null)

    private fun resolver(
        quotes: Map<String, KaPostDraft.QuotedRef?>,
        clock: LongArray = longArrayOf(0L),
        asked: MutableList<String> = mutableListOf(),
    ) = KaPostRepostOrigins.Resolver(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        now = { clock[0] },
        quoteOf = { id -> asked.add(id); quotes[id] },
    )

    @Test
    fun `a plain repost is a quote with no words and an id`() {
        assertTrue(KaPostRepostOrigins.isPlainRepost(ref("a", "")))
        assertTrue(KaPostRepostOrigins.isPlainRepost(ref("a", "  \n")))
        assertFalse(KaPostRepostOrigins.isPlainRepost(ref("a", "words")))
        assertFalse(KaPostRepostOrigins.isPlainRepost(ref(null, "")))
        assertFalse(KaPostRepostOrigins.isPlainRepost(ref("", "")))
    }

    @Test
    fun `a repost of a repost shows the original post`() = runBlocking {
        val original = ref("o", "the words", author = "kaspa:alice")
        val r = resolver(mapOf("a" to original))
        assertEquals(original, r.original(ref("a", "", author = "kaspa:bob")))
        // kept for the run, read while drawing
        assertEquals(original, r.cached(ref("a", "")))
    }

    @Test
    fun `reposts are followed up to five deep`() = runBlocking {
        val quotes = mapOf(
            "r1" to ref("r2", ""), "r2" to ref("r3", ""), "r3" to ref("r4", ""),
            "r4" to ref("o", "found"),
        )
        assertEquals("found", resolver(quotes).original(ref("r1", ""))?.text)
        val deep = (1..5).associate { "d$it" to ref("d${it + 1}", "") } + ("d6" to ref("o", "too deep"))
        assertNull(resolver(deep).original(ref("d1", "")))
    }

    @Test
    fun `a loop of reposts ends the walk`() = runBlocking {
        val quotes = mapOf("a" to ref("b", ""), "b" to ref("a", ""))
        assertNull(resolver(quotes).original(ref("a", "")))
    }

    @Test
    fun `a post with words is never looked up`() = runBlocking {
        val asked = mutableListOf<String>()
        assertNull(resolver(emptyMap(), asked = asked).original(ref("a", "words")))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a failed lookup is asked again only after two minutes`() = runBlocking {
        val clock = longArrayOf(1_000_000L)
        val asked = mutableListOf<String>()
        val r = resolver(emptyMap(), clock, asked)
        assertNull(r.original(ref("a", "")))
        clock[0] += 119_000L
        assertNull(r.original(ref("a", "")))
        assertEquals(1, asked.size)
        clock[0] += 2_000L
        r.original(ref("a", ""))
        assertEquals(2, asked.size)
    }

    // MARK: - The @token typed at the end (P6)

    /** The regex it replaced, as the reference. */
    private val oldRegex = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]*)$", RegexOption.IGNORE_CASE)

    @Test
    fun `the mention token matches the old regex`() {
        val samples = listOf(
            "", "@", "hi @", "hi @Ali", "hi @ali-b", "(@bob", "[@x", "{@x", "<@x", "\"@x", "'@x",
            "mail me@host", "@@x", "x@", "hello\n@kas", "@kas ", "a @b c", "@KaSpa9", "@ab_c", "@é",
            "tab\t@y", "end @-",
        )
        for (text in samples) {
            val expected = oldRegex.find(text)?.groupValues?.get(2)?.lowercase()
            assertEquals("for \"$text\"", expected, KaPostsMentionToken.query(text))
        }
    }

    // MARK: - T4: your reply to your own post joins its thread

    @Test
    fun `a self reply continues the chain that ends at its parent`() {
        val chains = mapOf("root" to listOf("p1", "p2"))
        assertEquals("root", KaPostsConversation.selfThreadChainRoot(chains, "p2"))
        // a reply to the root with no chain yet starts one
        assertEquals("solo", KaPostsConversation.selfThreadChainRoot(chains, "solo"))
        // a reply to the middle of a chain doesn't continue it: that post (no chain of its own)
        // is the root, as on iOS
        assertEquals("p1", KaPostsConversation.selfThreadChainRoot(chains, "p1"))
        // nor a reply to a root that already has a chain
        assertNull(KaPostsConversation.selfThreadChainRoot(chains, "root"))
    }

    // MARK: - B1: the block stream and network changes

    @Test
    fun `the block stream runs only for a Posts feed on screen off a metered network`() {
        assertTrue(KaPostsLiveChainFeed.shouldStream(active = true, foreground = true, feedOnScreen = true, metered = false))
        assertFalse(KaPostsLiveChainFeed.shouldStream(active = true, foreground = true, feedOnScreen = false, metered = false))
        assertFalse(KaPostsLiveChainFeed.shouldStream(active = true, foreground = true, feedOnScreen = true, metered = true))
        assertFalse(KaPostsLiveChainFeed.shouldStream(active = false, foreground = true, feedOnScreen = true, metered = false))
        assertFalse(KaPostsLiveChainFeed.shouldStream(active = true, foreground = false, feedOnScreen = true, metered = false))
    }

    @Test
    fun `timeouts just after a network change don't count against the indexer`() {
        assertTrue(KaPostsIndexerHealth.isHandoffError(java.net.SocketTimeoutException("read timed out")))
        assertTrue(KaPostsIndexerHealth.isHandoffError(java.net.SocketException("Connection reset")))
        assertTrue(KaPostsIndexerHealth.isHandoffError(java.io.InterruptedIOException("timeout")))
        assertFalse(KaPostsIndexerHealth.isHandoffError(java.net.ConnectException("refused")))
        assertFalse(KaPostsIndexerHealth.isHandoffError(java.io.IOException("HTTP 503")))

        val change = 5_000_000L
        assertTrue(KaPostsIndexerHealth.inNetworkChangeGrace(change + 29_000L, change))
        assertFalse(KaPostsIndexerHealth.inNetworkChangeGrace(change + 30_000L, change))
        // no change seen yet
        assertFalse(KaPostsIndexerHealth.inNetworkChangeGrace(10_000L, 0L))
    }
}
