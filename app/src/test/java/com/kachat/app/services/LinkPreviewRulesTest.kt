package com.kachat.app.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link preview redirect and Referer rules (iOS d7c56280). */
class LinkPreviewRulesTest {

    @Test
    fun `same host may redirect over http or https`() {
        assertTrue(LinkPreviewService.isAllowedRedirect("http://example.com/a", "http://example.com/b"))
        assertTrue(LinkPreviewService.isAllowedRedirect("http://example.com/a", "https://example.com/b"))
    }

    @Test
    fun `another host only over https on a public name`() {
        assertTrue(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://b.com/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "http://b.com/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://localhost/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://router.local/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://intranet/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://192.168.1.1/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "https://[::1]/y"))
        assertFalse(LinkPreviewService.isAllowedRedirect("https://a.com/x", "ftp://b.com/y"))
    }

    @Test
    fun `same site is the same host or a subdomain either way`() {
        assertTrue(LinkPreviewService.isSameSite("https://x.com/i.jpg", "https://x.com/page"))
        assertTrue(LinkPreviewService.isSameSite("https://cdn.x.com/i.jpg", "https://x.com/page"))
        assertTrue(LinkPreviewService.isSameSite("https://x.com/i.jpg", "https://www.x.com/page"))
        assertFalse(LinkPreviewService.isSameSite("https://cdn.y.com/i.jpg", "https://x.com/page"))
        assertFalse(LinkPreviewService.isSameSite("https://notx.com/i.jpg", "https://x.com/page"))
        assertFalse(LinkPreviewService.isSameSite(null, "https://x.com/page"))
    }
}
