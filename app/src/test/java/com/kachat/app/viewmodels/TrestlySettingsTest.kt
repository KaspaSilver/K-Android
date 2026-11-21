package com.kachat.app.viewmodels

import com.kachat.app.viewmodels.TrestlySettingsViewModel.Companion.isCleartextHttp
import com.kachat.app.viewmodels.TrestlySettingsViewModel.Companion.normalizedOrDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connection Settings > Trestly's field (iOS 5b7c772c `isCleartextHTTP`, `normalizedOrDefault`). */
class TrestlySettingsTest {
    private val fallback = "https://api.trestly.io/api/v1"

    @Test
    fun cleartextHttpIsRefused() {
        assertTrue(isCleartextHttp("http://example.com"))
        assertTrue(isCleartextHttp("  HTTP://example.com "))
        assertFalse(isCleartextHttp("https://example.com"))
        assertFalse(isCleartextHttp("example.com"))
        assertFalse(isCleartextHttp(""))
    }

    @Test
    fun blankGoesBackToTheDefault() {
        assertEquals(fallback, normalizedOrDefault("", fallback))
        assertEquals(fallback, normalizedOrDefault("   ", fallback))
    }

    @Test
    fun aBareHostGetsHttps() {
        assertEquals("https://trestly.example/api", normalizedOrDefault(" trestly.example/api ", fallback))
    }

    @Test
    fun httpsIsKeptAsTyped() {
        assertEquals("https://x.io/api/v1", normalizedOrDefault("https://x.io/api/v1", fallback))
        assertEquals("HTTPS://x.io", normalizedOrDefault("HTTPS://x.io", fallback))
    }
}
