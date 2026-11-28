package com.kachat.app.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Audit 2026-11-27 Smoothness S3: the image parse answers non-photos from the head and caches photos. */
class ImageMessageSniffTest {
    private fun envelope(mime: String) =
        """{"type":"file","name":"f","size":3,"mimeType":"$mime","content":"data:$mime;base64,${"Q".repeat(5_000)}"}"""

    @Test
    fun voiceNoteIsNotAPhoto() {
        assertNull(ImageMessage.parseOrNull(envelope("audio/webm")))
    }

    @Test
    fun chessAndOtherJsonAreNotPhotos() {
        assertNull(ImageMessage.parseOrNull("""{"type":"chess_move","gameId":"g","move":"e2e4"}"""))
        assertNull(ImageMessage.parseOrNull("""{"a":"b"}"""))
        assertNull(ImageMessage.parseOrNull("plain text"))
    }

    @Test
    fun photoParsesOnceThenComesFromTheCache() {
        val text = envelope("image/jpeg")
        val first = ImageMessage.parseOrNull(text)
        assertNotNull(first)
        // A different String instance with the same text: the cached parse.
        assertSame(first, ImageMessage.parseOrNull(String(text.toCharArray())))
    }

    @Test
    fun escapedSlashMimeStillParses() {
        val text = envelope("image/png").replaceFirst("\"mimeType\":\"image/png\"", "\"mimeType\":\"image\\/png\"")
        assertNotNull(ImageMessage.parseOrNull(text))
    }
}
