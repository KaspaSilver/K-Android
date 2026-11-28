package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Nextcloud PDF viewer's downloaded file is named by the SHA-256 of its share URL. */
class LinkPreviewCacheFileNameTest {

    @Test
    fun `a share's file is named by the SHA-256 of its url`() {
        // SHA-256("abc"), the FIPS 180-2 test vector
        assertEquals(
            "share-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.bin",
            LinkPreviewService.cacheFileName("abc"),
        )
    }

    @Test
    fun `the same url always maps to the same file and different urls never share one`() {
        val a = "https://cloud.example.com/s/AbCdEf/download"
        assertEquals(LinkPreviewService.cacheFileName(a), LinkPreviewService.cacheFileName(a))
        // "Aa" and "BB" have the same String.hashCode(), which once named the same file
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(LinkPreviewService.cacheFileName("Aa"), LinkPreviewService.cacheFileName("BB"))
    }

    @Test
    fun `the name is safe as a file name`() {
        val name = LinkPreviewService.cacheFileName("https://x.com/../../a?b=c#d")
        assertTrue(name.matches(Regex("share-[0-9a-f]{64}\\.bin")))
    }
}
