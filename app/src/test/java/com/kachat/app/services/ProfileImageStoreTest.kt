package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile pictures' own store (iOS `KNSProfileImageCache`): when a copy is checked for a newer
 * picture, what a HEAD answer means, and the failed-download cooldown.
 */
class ProfileImageStoreTest {
    private val store = ProfileImageStore
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aCopyIsDueAfterFiveDaysOrWhenNeverChecked() {
        val now = 100 * day
        assertTrue(store.isDue(null, now))
        assertFalse(store.isDue(ProfileImageStore.Check(validatedAt = now - 4 * day), now))
        assertTrue(store.isDue(ProfileImageStore.Check(validatedAt = now - 5 * day), now))
        // the hourly rescan's own age
        assertFalse(store.isDue(ProfileImageStore.Check(validatedAt = now - 10 * 60_000), now, 50 * 60_000L))
        assertTrue(store.isDue(ProfileImageStore.Check(validatedAt = now - 51 * 60_000), now, 50 * 60_000L))
    }

    @Test
    fun aHeaderTheHostDidNotSendSaysNothing() {
        assertTrue(store.validatorMatches<String>("a", null))
        assertTrue(store.validatorMatches<String>(null, null))
        assertTrue(store.validatorMatches("a", "a"))
        assertFalse(store.validatorMatches("a", "b"))
        // sent now but not stored: can't tell, so it's a different picture (iOS)
        assertFalse(store.validatorMatches(null, "b"))
    }

    @Test
    fun theHeadAnswerComparesEveryValidator() {
        val stored = ProfileImageStore.Check(1L, eTag = "\"x\"", lastModified = "Mon", contentLength = 100)
        assertTrue(store.sameAsStored(stored, "\"x\"", "Mon", 100))
        assertTrue(store.sameAsStored(stored, "\"x\"", null, null))
        assertFalse(store.sameAsStored(stored, "\"y\"", "Mon", 100))
        assertFalse(store.sameAsStored(stored, "\"x\"", "Mon", 101))
        assertTrue(stored.hasValidators)
        assertFalse(ProfileImageStore.Check(1L, contentLength = 5).hasValidators)
    }

    @Test
    fun aFailedPictureWaitsAMinute() {
        val url = "https://example.invalid/avatar-${System.nanoTime()}.jpg"
        val t = 1_000_000L
        assertFalse(store.failedRecently(url, t))
        store.noteFailure(url, t)
        assertTrue(store.failedRecently(url, t + 30_000))
        assertEquals(30_000L, store.cooldownLeftMs(url, t + 30_000))
        assertFalse(store.failedRecently(url, t + ProfileImageStore.FAILURE_COOLDOWN_MS))
        store.noteFailure(url, t)
        store.noteLoaded(url)
        assertFalse(store.failedRecently(url, t + 1))
    }
}
