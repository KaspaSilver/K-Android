package com.kachat.app.services

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS 8cb4c88e, the notification switches audit (2026-11-24). */
class NotificationSwitchesAuditTest {

    @Test
    fun `a room push's mention flag reads as the server may send it`() {
        assertTrue(KaChatFirebaseMessagingService.isTruePushFlag("true"))
        assertTrue(KaChatFirebaseMessagingService.isTruePushFlag("TRUE"))
        assertTrue(KaChatFirebaseMessagingService.isTruePushFlag("1"))
        assertTrue(KaChatFirebaseMessagingService.isTruePushFlag(" true "))
        assertFalse(KaChatFirebaseMessagingService.isTruePushFlag(null))
        assertFalse(KaChatFirebaseMessagingService.isTruePushFlag(""))
        assertFalse(KaChatFirebaseMessagingService.isTruePushFlag("false"))
        assertFalse(KaChatFirebaseMessagingService.isTruePushFlag("0"))
        assertFalse(KaChatFirebaseMessagingService.isTruePushFlag("yes"))
    }

    @Test
    fun `the registration carries Mentions and Play sound under the server's names`() {
        val json = Gson().toJson(
            PushRegistrationRequest(
                deviceToken = "t",
                platform = "android",
                watchedAddresses = emptyList(),
                kaPostsNotifyMentions = false,
                notifySound = false,
            )
        )
        val fields = Gson().fromJson(json, Map::class.java)
        assertEquals(false, fields["kaposts_notify_mentions"])
        assertEquals(false, fields["notify_sound"])
    }

    @Test
    fun `Mentions and Play sound default to on`() {
        val fields = Gson().fromJson(
            Gson().toJson(PushRegistrationRequest(deviceToken = "t", platform = "android", watchedAddresses = emptyList())),
            Map::class.java,
        )
        assertEquals(true, fields["kaposts_notify_mentions"])
        assertEquals(true, fields["notify_sound"])
    }
}
