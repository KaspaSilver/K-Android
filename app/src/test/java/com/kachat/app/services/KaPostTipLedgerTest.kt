package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** KaPost tips in chats (iOS 2026-11-22 `KaPostTipLedger`, NotificationService `tipPostId`). */
class KaPostTipLedgerTest {
    private val post = "ab".repeat(32)

    private fun hex(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test
    fun tipPayloadNamesItsPost() {
        val payload = "kchat:1:tip:02${"11".repeat(32)}:${"22".repeat(64)}:${post.uppercase()}:02${"33".repeat(32)}:deadbeef"
        assertEquals(post, KaPostTipLedger.tipPostIdFromPayloadHex(hex(payload)))
        assertEquals(post, KaPostTipLedger.tipPostIdFromPayload(payload.toByteArray()))
    }

    @Test
    fun otherPayloadsAreNotTips() {
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex(""))
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex("zz"))
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex(hex("kchat:1:pay:abcdef")))
        // no sealed record
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex(hex("kchat:1:tip:pk:sig:$post:author:")))
        // a post id that isn't a txid
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex(hex("kchat:1:tip:pk:sig:abc:author:00")))
        // too many fields
        assertNull(KaPostTipLedger.tipPostIdFromPayloadHex(hex("kchat:1:tip:pk:sig:$post:author:00:extra")))
    }

    @Test
    fun pushAmountInKas() {
        assertEquals("0.2", KaPostTipLedger.kasText("20000000"))
        assertEquals("1", KaPostTipLedger.kasText("100000000"))
        assertEquals("1.23456789", KaPostTipLedger.kasText("123456789"))
        assertNull(KaPostTipLedger.kasText("0"))
        assertNull(KaPostTipLedger.kasText(null))
        assertNull(KaPostTipLedger.kasText("abc"))
    }

    @Test
    fun amountFromKaPostTipPush() {
        assertEquals("0.2", KaPostTipLedger.amountFromTipPushBody("alice tipped your post: 0.2 KAS"))
        assertNull(KaPostTipLedger.amountFromTipPushBody("alice tipped your post"))
    }

    @Test
    fun pushKind() {
        assertEquals("tip", KaPostTipLedger.kaPostsPushKind("tip", ""))
        assertEquals("like", KaPostTipLedger.kaPostsPushKind("vote_up", ""))
        assertEquals("tip", KaPostTipLedger.kaPostsPushKind(null, "alice tipped your post: 1 KAS"))
        assertEquals("like", KaPostTipLedger.kaPostsPushKind(null, "alice liked your post"))
        assertEquals("other", KaPostTipLedger.kaPostsPushKind("weird", "hello"))
    }
}
