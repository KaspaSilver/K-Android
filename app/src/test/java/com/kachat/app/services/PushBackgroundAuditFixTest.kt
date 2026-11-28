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
    fun `push counts as unavailable with no service or repeated failed registrations, not while unknown`() {
        val hour = PushState.UNAVAILABLE_AFTER_MS
        val now = 10 * hour
        assertTrue(PushState.pushUnavailable(pushActive = false, noPushService = true, failedRegistrations = 0, failingSinceMs = 0L, nowMs = now))
        assertTrue(PushState.pushUnavailable(pushActive = false, noPushService = false, failedRegistrations = 3, failingSinceMs = now - hour, nowMs = now))
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, failedRegistrations = 0, failingSinceMs = 0L, nowMs = now))
        // Notifications switched off (an unregister) or a success: no streak.
        assertFalse(PushState.pushUnavailable(pushActive = true, noPushService = false, failedRegistrations = 0, failingSinceMs = 0L, nowMs = now))
        // A push working before, failing now.
        assertFalse(PushState.pushUnavailable(pushActive = true, noPushService = false, failedRegistrations = 5, failingSinceMs = now - 2 * hour, nowMs = now))
    }

    // Audit 2026-11-27 R7: one failure is not enough

    @Test
    fun `a single failed registration does not count as push unavailable`() {
        val now = 10 * PushState.UNAVAILABLE_AFTER_MS
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, failedRegistrations = 1, failingSinceMs = now - 2 * PushState.UNAVAILABLE_AFTER_MS, nowMs = now))
        // Three quick failures within the hour: still not.
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, failedRegistrations = 3, failingSinceMs = now - 60_000L, nowMs = now))
        // Failing since never recorded: not.
        assertFalse(PushState.pushUnavailable(pushActive = false, noPushService = false, failedRegistrations = 3, failingSinceMs = 0L, nowMs = now))
    }

    @Test
    fun `the failure streak counts in a row from its first failure and a success clears it`() {
        var streak = PushState.RegisterFailures()
        streak = PushState.nextRegisterFailures(streak, succeeded = false, nowMs = 1_000L)
        assertEquals(PushState.RegisterFailures(1, 1_000L), streak)
        streak = PushState.nextRegisterFailures(streak, succeeded = false, nowMs = 5_000L)
        streak = PushState.nextRegisterFailures(streak, succeeded = false, nowMs = 9_000L)
        assertEquals(PushState.RegisterFailures(3, 1_000L), streak)
        assertEquals(PushState.RegisterFailures(), PushState.nextRegisterFailures(streak, succeeded = true, nowMs = 10_000L))
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
