package com.kachat.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who KaPost's @ suggests (iOS `KaPostMentionSources`, 157790b0). */
class KaPostMentionSourcesTest {

    @After
    fun tearDown() = KaPostMentionSources.clearRecent()

    private val names = mapOf(
        "kaspa:me" to "me",
        "kaspa:alice" to "alice",
        "kaspa:albert" to "albert",
        "kaspa:bob" to "bob",
        "kaspa:carol" to "carol",
        "kaspa:dave" to "dave",
    )

    private fun handles(
        query: String = "",
        extra: String? = null,
        following: Collection<String> = emptyList(),
        recent: List<String> = emptyList(),
        contacts: List<String> = emptyList(),
        handleOf: (String) -> String? = { names[it.lowercase()] },
    ) = KaPostMentionSources.handles(query, extra, "kaspa:ME", following, recent, contacts, handleOf)

    @Test
    fun `follows sorted, then recent posters, then contacts, never you`() {
        val out = handles(
            following = setOf("kaspa:carol", "kaspa:alice", "kaspa:me"),
            recent = listOf("kaspa:dave", "kaspa:alice"),
            contacts = listOf("kaspa:bob", "kaspa:me"),
        )
        assertEquals(listOf("alice", "carol", "dave", "bob"), out)
    }

    @Test
    fun `only people with a username, each once`() {
        val out = handles(
            following = setOf("kaspa:nobody"),
            recent = listOf("kaspa:BOB", "kaspa:bob"),
            contacts = listOf("kaspa:bob", "kaspa:nobody"),
        )
        assertEquals(listOf("bob"), out)
    }

    @Test
    fun `prefix match on the normalized name`() {
        val out = handles(
            query = "AL",
            recent = listOf("kaspa:alice", "kaspa:bob", "kaspa:albert"),
            handleOf = { names[it]?.let { n -> "$n.kachat" } },
        )
        assertEquals(listOf("alice.kachat", "albert.kachat"), out)
    }

    @Test
    fun `at most 30, the looked-up name first`() {
        val many = (1..40).map { "kaspa:p$it" }
        val out = handles(contacts = many, extra = "zed", handleOf = { it.removePrefix("kaspa:") })
        assertEquals(31, out.size)
        assertEquals("zed", out.first())
        assertEquals("p30", out.last())
    }

    @Test
    fun `the looked-up name only when it matches and isn't listed`() {
        assertEquals(listOf("alice"), handles(query = "al", extra = "bob", recent = listOf("kaspa:alice")))
        assertEquals(listOf("alice"), handles(query = "al", extra = "alice", recent = listOf("kaspa:alice")))
    }

    @Test
    fun `only the newest 200 recent posters are considered`() {
        val recent = (1..250).map { "kaspa:p$it" }
        val out = handles(query = "p2", recent = recent, handleOf = { it.removePrefix("kaspa:") })
        assertTrue("p201" !in out)
        assertTrue("p200" in out)
    }

    @Test
    fun `notePoster keeps the newest 300, newest first, lowercased`() {
        for (i in 1..305) KaPostMentionSources.notePoster("kaspa:P$i")
        KaPostMentionSources.notePoster("kaspa:p10")
        val recent = KaPostMentionSources.recentPosters()
        assertEquals(300, recent.size)
        assertEquals("kaspa:p10", recent.first())
        assertEquals("kaspa:p305", recent[1])
        assertEquals(1, recent.count { it == "kaspa:p10" })
    }
}
