package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security audit 2026-11-27 S4 (the in-app browser's http upgrade) and S3 (the overlay guard's
 *  counting). */
class SecurityFixBrowserTest {

    @Test
    fun `same-site http loads over https with its path, query and fragment`() {
        assertEquals("https://kasshi.io/watch?v=1#t", InAppBrowserRules.httpsUpgrade("http://kasshi.io/watch?v=1#t"))
        assertEquals("https://www.kasmart.org", InAppBrowserRules.httpsUpgrade("http://www.kasmart.org"))
        assertEquals("https://kasshi.io/a%20b", InAppBrowserRules.httpsUpgrade("http://kasshi.io:80/a%20b"))
        // no user:password@ carried over
        assertEquals("https://kasshi.io/", InAppBrowserRules.httpsUpgrade("http://me:pw@kasshi.io/"))
    }

    @Test
    fun `anything but plain http on the default port isn't upgraded`() {
        assertNull(InAppBrowserRules.httpsUpgrade("https://kasshi.io/"))
        assertNull(InAppBrowserRules.httpsUpgrade("http://kasshi.io:8080/"))
        assertNull(InAppBrowserRules.httpsUpgrade("ftp://kasshi.io/"))
        assertNull(InAppBrowserRules.httpsUpgrade("not a url"))
    }

    @Test
    fun `the guard stays until its last holder goes`() {
        val counts = HeldCount<String>()
        assertTrue(counts.acquire("window"))
        assertFalse(counts.acquire("window"))
        assertFalse(counts.release("window"))
        assertEquals(1, counts.holders("window"))
        assertTrue(counts.release("window"))
        assertEquals(0, counts.holders("window"))
        // a release with no holder changes nothing
        assertFalse(counts.release("window"))
        assertTrue(counts.acquire("window"))
    }
}
