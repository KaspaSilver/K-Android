package com.kachat.app.services

import com.kachat.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-25, background work, push and startup (A4, N3, N7, N11, P4, P5, battery exemption). */
class PushBackgroundAuditFixTest {

    // N7 / B4: the background gate

    @Test
    fun `the background is quiet with push, signed out or notifications off, and only then`() {
        assertTrue(PushState.backgroundQuiet(pushActive = true, localBannersWanted = true, systemNotificationsOn = true))
        assertTrue(PushState.backgroundQuiet(pushActive = false, localBannersWanted = false, systemNotificationsOn = true))
        assertTrue(PushState.backgroundQuiet(pushActive = false, localBannersWanted = true, systemNotificationsOn = false))
        assertFalse(PushState.backgroundQuiet(pushActive = false, localBannersWanted = true, systemNotificationsOn = true))
    }

    // A4: SyncWorker only without push and with groups

    @Test
    fun `the group worker is scheduled only when nothing else delivers and there is a group`() {
        assertTrue(SyncWorker.wanted(backgroundQuiet = false, hasGroups = true))
        assertFalse(SyncWorker.wanted(backgroundQuiet = true, hasGroups = true))
        assertFalse(SyncWorker.wanted(backgroundQuiet = false, hasGroups = false))
        assertFalse(SyncWorker.wanted(backgroundQuiet = true, hasGroups = false))
    }

    // N3: re-register only when something changed

    private val day = PushRegistrationManager.REGISTRATION_RENEW_MS

    private fun fresh(
        stored: String? = "fp",
        at: Long = 1_000_000L,
        storedBuild: Long = 7,
        fingerprint: String = "fp",
        now: Long = 1_000_000L + 60_000L,
        build: Long = 7,
    ) = PushRegistrationManager.registrationStillFresh(stored, at, storedBuild, fingerprint, now, build)

    @Test
    fun `the same registration from an earlier launch today is not sent again`() {
        assertTrue(fresh())
    }

    @Test
    fun `a change, a new build, a day's age or nothing stored sends it`() {
        assertFalse(fresh(fingerprint = "other"))
        assertFalse(fresh(build = 8))
        assertFalse(fresh(now = 1_000_000L + day))
        assertFalse(fresh(stored = null))
        assertFalse(fresh(at = 0L))
    }

    @Test
    fun `a clock set back counts as stale`() {
        assertFalse(fresh(now = 999_000L))
    }

    // Battery exemption: only where push can't deliver

    @Test
    fun `push counts as unavailable with no service or a failed registration, not while unknown`() {
        assertTrue(PushState.pushUnavailable(pushActive = false, noPushService = true, lastAction = null, lastAttemptSucceeded = null))
        assertTrue(PushState.pushUnavailable(pushActive = false, noPushService = false, lastAction = "register", lastAttemptSucceeded = false))
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, lastAction = null, lastAttemptSucceeded = null))
        assertFalse(PushState.pushUnavailable(pushActive = true, noPushService = false, lastAction = "register", lastAttemptSucceeded = true))
        // Notifications switched off: an unregister, nothing for the exemption to deliver.
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, lastAction = "unregister", lastAttemptSucceeded = true))
        // A push working before, failing now.
        assertFalse(PushState.pushUnavailable(pushActive = true, noPushService = false, lastAction = "register", lastAttemptSucceeded = false))
    }

    // P4: a queued push's clock starts at its arrival

    @Test
    fun `a push handled on its own arrived now`() {
        assertEquals(50_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 50_000L, queueStartMs = 50_000L, sentTimeMs = 0L))
        assertEquals(50_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 50_000L, queueStartMs = 50_000L, sentTimeMs = 49_000L))
        // A server clock ahead of the phone's never puts the arrival in the future.
        assertEquals(50_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 50_000L, queueStartMs = 50_000L, sentTimeMs = 52_000L))
    }

    @Test
    fun `a queued push arrived no earlier than its run began or it was sent`() {
        assertEquals(10_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 40_000L, queueStartMs = 10_000L, sentTimeMs = 0L))
        assertEquals(25_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 40_000L, queueStartMs = 10_000L, sentTimeMs = 25_000L))
        assertEquals(10_000L, KaChatFirebaseMessagingService.arrivalEstimate(nowMs = 40_000L, queueStartMs = 10_000L, sentTimeMs = 5_000L))
    }

    // N11: later parts of a burst read once

    @Test
    fun `a read for the same sender within a minute makes one attempt`() {
        val window = KaChatFirebaseMessagingService.BURST_READ_WINDOW_MS
        assertFalse(KaChatFirebaseMessagingService.singleBurstRead(lastDoneAtMs = null, nowMs = 100_000L))
        assertTrue(KaChatFirebaseMessagingService.singleBurstRead(lastDoneAtMs = 100_000L, nowMs = 100_000L + 5_000L))
        assertFalse(KaChatFirebaseMessagingService.singleBurstRead(lastDoneAtMs = 100_000L, nowMs = 100_000L + window))
        assertFalse(KaChatFirebaseMessagingService.singleBurstRead(lastDoneAtMs = 100_000L, nowMs = 90_000L))
    }

    // P5: four channels, four names

    @Test
    fun `each notification channel has its own name and the usual one stays Messages`() {
        val names = listOf(true to true, true to false, false to true, false to false)
            .map { (sound, vibration) -> NotificationHelper.channelNameRes(sound, vibration) }
        assertEquals(4, names.toSet().size)
        assertEquals(R.string.messages, NotificationHelper.channelNameRes(soundEnabled = true, vibrationEnabled = true))
    }
}
