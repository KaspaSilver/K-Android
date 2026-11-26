package com.kachat.app.services

import com.kachat.app.services.GlobalNotificationCenterStore.Companion.MentionTarget
import com.kachat.app.ui.screens.KaPostsDeepLink
import com.kachat.app.ui.screens.PublicChatJumpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room and group @mentions in Profile's bell, and your own @ opening the Profile tab (iOS d089011f). */
class MentionBellTest {

    private val me = "kaspa:qqmeaddressmeaddressmeaddressmeaddressmeaddressme0000"
    private val them = "kaspa:qqthemaddressthemaddressthemaddressthemaddressthem00"

    // The bell's row

    @Test
    fun `mentions are in the bell, one row per message`() {
        assertTrue(GlobalNotificationCenterStore.SOURCE_MENTION in GlobalNotificationCenterStore.IN_BELL)
        assertEquals("mention-abcdef", GlobalNotificationCenterStore.mentionId("ABCdef"))
        assertEquals("Mention", GlobalNotificationCenterStore.sourceLabel("mention"))
    }

    @Test
    fun `a room row opens the room at the message`() {
        assertEquals(MentionTarget.Room("kaspa", "tx1"), GlobalNotificationCenterStore.parseMentionTarget("room:kaspa|tx1"))
        assertEquals(MentionTarget.Room("kaspa", ""), GlobalNotificationCenterStore.parseMentionTarget("room:kaspa"))
    }

    @Test
    fun `a group row opens the group`() {
        assertEquals(MentionTarget.Group("g1"), GlobalNotificationCenterStore.parseMentionTarget("group:g1|tx9"))
    }

    @Test
    fun `anything else opens nothing`() {
        assertNull(GlobalNotificationCenterStore.parseMentionTarget(null))
        assertNull(GlobalNotificationCenterStore.parseMentionTarget(""))
        assertNull(GlobalNotificationCenterStore.parseMentionTarget("room:|tx"))
        assertNull(GlobalNotificationCenterStore.parseMentionTarget("kaposts:abc|tx"))
    }

    // Which room rows can be a mention

    @Test
    fun `someone else's message in a chat room with an @ is a candidate`() {
        assertTrue(RoomMentionBell.isCandidate("kaspa", them, "hey @alice", me))
    }

    @Test
    fun `your own messages, the machinery rooms and text with no @ are not`() {
        assertFalse(RoomMentionBell.isCandidate("kaspa", me.uppercase(), "hey @alice", me))
        assertFalse(RoomMentionBell.isCandidate(com.kachat.app.models.FeaturedBroadcastChannels.ARENA_CHANNEL, them, "hey @alice", me))
        assertFalse(RoomMentionBell.isCandidate("kaspa", them, "hey alice", me))
    }

    @Test
    fun `reactions, edits and deletes are not`() {
        assertFalse(RoomMentionBell.isCandidate("kaspa", them, """{"type":"reaction","targetTxId":"t","emoji":"@","action":"add"}""", me))
        assertFalse(RoomMentionBell.isCandidate("kaspa", them, """{"type":"edit","targetTxId":"t","text":"hi @alice"}""", me))
        assertFalse(RoomMentionBell.isCandidate("kaspa", them, """{"type":"delete","targetTxId":"t@"}""", me))
    }

    // Your own @

    @Test
    fun `your own address is you in any case, nobody else is`() {
        assertTrue(KaPostsDeepLink.isOwnAddress(me.uppercase(), me))
        assertFalse(KaPostsDeepLink.isOwnAddress(them, me))
        assertFalse(KaPostsDeepLink.isOwnAddress(me, null))
        assertFalse(KaPostsDeepLink.isOwnAddress("", ""))
    }

    @Test
    fun `someone else's profile is handed to KaPost`() {
        KaPostsDeepLink.pendingProfileAddress.value = null
        KaPostsDeepLink.openProfile(them, me)
        assertEquals(them, KaPostsDeepLink.pendingProfileAddress.value)
        KaPostsDeepLink.pendingProfileAddress.value = null
    }

    @Test
    fun `your own profile is the Profile tab, never KaPost's visitor copy`() {
        KaPostsDeepLink.pendingProfileAddress.value = null
        KaPostsDeepLink.openProfile(me, me)
        assertNull(KaPostsDeepLink.pendingProfileAddress.value)
    }

    // The room's jump

    @Test
    fun `a room takes only its own jump`() {
        PublicChatJumpRoute.pending.value = PublicChatJumpRoute.Jump("Kaspa", "tx1")
        assertNull(PublicChatJumpRoute.take("bitcoin"))
        assertEquals("tx1", PublicChatJumpRoute.take(" kaspa ")?.txId)
        assertNull(PublicChatJumpRoute.pending.value)
    }
}
