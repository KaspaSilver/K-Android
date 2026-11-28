package com.kachat.app.viewmodels

import com.kachat.app.models.KaPostDraft
import org.junit.Assert.assertEquals
import org.junit.Test

/** What an account switch hands back as drafts: every local post not on chain, comments too. */
class KaPostsUnsentIdsTest {

    private fun post(id: String, status: KaPostDraft.Delivery, comments: List<KaPostDraft> = emptyList()) =
        KaPostDraft(id = id, text = id, timestamp = 0L, posterAddress = "kaspa:qa", deliveryStatus = status, comments = comments)

    @Test
    fun pendingFailedAndNotConfirmedAreUnsent() {
        val list = listOf(
            post("sent", KaPostDraft.Delivery.SENT),
            post("pending", KaPostDraft.Delivery.PENDING),
            post("failed", KaPostDraft.Delivery.FAILED),
            post("unconfirmed", KaPostDraft.Delivery.UNCONFIRMED),
        )
        assertEquals(setOf("pending", "failed", "unconfirmed"), unsentPostIds(listOf(list)))
    }

    @Test
    fun nestedCommentsAndEveryListCount() {
        val reply = post("reply", KaPostDraft.Delivery.FAILED)
        val parent = post("parent", KaPostDraft.Delivery.SENT, comments = listOf(post("mid", KaPostDraft.Delivery.SENT, listOf(reply))))
        assertEquals(setOf("reply", "other"), unsentPostIds(listOf(listOf(parent), listOf(post("other", KaPostDraft.Delivery.PENDING)))))
    }
}
