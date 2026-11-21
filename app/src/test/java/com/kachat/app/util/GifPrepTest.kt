package com.kachat.app.util

import com.kachat.app.services.DeletedMessageLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** GIF reading and writing for on-chain GIFs (iOS d7c56280 GIFPrep, 5b7c772c play limits). */
class GifPrepTest {

    /** A GIF of [frames] (palette indices, row by row), each with its delay and transparent index. */
    private fun gif(
        width: Int,
        height: Int,
        palette: IntArray,
        frames: List<ByteArray>,
        delayCs: Int = 5,
        transparent: Int? = null,
        frameWidth: Int = width,
        frameHeight: Int = height,
    ): ByteArray {
        val bits = Integer.numberOfTrailingZeros(palette.size).coerceAtLeast(1)
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(width and 0xff); out.write(width shr 8)
        out.write(height and 0xff); out.write(height shr 8)
        out.write(0x80 or (bits - 1))
        out.write(0); out.write(0)
        for (color in palette) {
            out.write((color shr 16) and 0xff); out.write((color shr 8) and 0xff); out.write(color and 0xff)
        }
        for (frame in frames) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(if (transparent != null) 0x01 else 0)
            out.write(delayCs and 0xff); out.write(delayCs shr 8)
            out.write(transparent ?: 0); out.write(0)
            out.write(0x2C)
            out.write(0); out.write(0); out.write(0); out.write(0)
            out.write(frameWidth and 0xff); out.write(frameWidth shr 8)
            out.write(frameHeight and 0xff); out.write(frameHeight shr 8)
            out.write(0)
            out.write(LzwWriter().encode(frame, frame.size, minCodeSize = maxOf(2, bits)))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val white = 0xFFFFFF

    @Test
    fun twoFramesAreAnimatedAndPlayable() {
        val data = gif(2, 2, intArrayOf(red, green, blue, white), listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(3, 2, 1, 0)))
        assertTrue(GifPrep.isGif(data))
        assertTrue(GifPrep.isAnimated(data))
        assertTrue(GifPrep.isSafeToAnimate(data))
    }

    @Test
    fun oneFrameIsNotAnimated() {
        val data = gif(2, 2, intArrayOf(red, green, blue, white), listOf(byteArrayOf(0, 1, 2, 3)))
        assertFalse(GifPrep.isAnimated(data))
        assertFalse(GifPrep.isSafeToAnimate(data))
    }

    @Test
    fun notAGifIsNeither() {
        val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46)
        assertFalse(GifPrep.isGif(jpegHead))
        assertFalse(GifPrep.isAnimated(jpegHead))
        assertFalse(GifPrep.isSafeToAnimate(jpegHead))
        assertFalse(GifPrep.isSafeToAnimate(ByteArray(0)))
    }

    @Test
    fun aHugeCanvasOrFrameDoesNotPlay() {
        val frames = listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(3, 2, 1, 0))
        val bigCanvas = gif(GifPrep.MAX_PLAY_EDGE + 1, 2, intArrayOf(red, green, blue, white), frames, frameWidth = 2, frameHeight = 2)
        assertTrue(GifPrep.isAnimated(bigCanvas))
        assertFalse(GifPrep.isSafeToAnimate(bigCanvas))
        // A frame that claims more than the canvas, past the limit, refuses it too.
        val bigFrame = gif(2, 2, intArrayOf(red, green, blue, white), frames, frameWidth = 16_383, frameHeight = 2)
        assertFalse(GifPrep.isSafeToAnimate(bigFrame))
    }

    @Test
    fun moreThanThreeHundredFramesDoNotPlay() {
        val frame = byteArrayOf(0, 1, 2, 3)
        val atLimit = gif(2, 2, intArrayOf(red, green, blue, white), List(GifPrep.MAX_PLAY_FRAMES) { frame })
        assertTrue(GifPrep.isSafeToAnimate(atLimit))
        val past = gif(2, 2, intArrayOf(red, green, blue, white), List(GifPrep.MAX_PLAY_FRAMES + 1) { frame })
        assertFalse(GifPrep.isSafeToAnimate(past))
    }

    @Test
    fun decoderDrawsFramesAndKeepsTransparentPixels() {
        val data = gif(
            2, 2, intArrayOf(red, green, blue, white),
            listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(3, 3, 3, 0)),
            delayCs = 7,
            transparent = 3,
        )
        val decoder = GifDecoder(data)
        val first = decoder.nextFrame()
        assertNotNull(first)
        val opaque = 0xFF shl 24
        // Index 3 is transparent in every frame: the first frame's last pixel stays clear.
        assertArrayEquals(intArrayOf(opaque or red, opaque or green, opaque or blue, 0), first!!.pixels.copyOf())
        assertEquals(70, first.delayMs)
        val second = decoder.nextFrame()
        assertNotNull(second)
        // Only the last pixel is drawn; the rest show through from the first frame.
        assertArrayEquals(intArrayOf(opaque or red, opaque or green, opaque or blue, opaque or red), second!!.pixels.copyOf())
        assertNull(decoder.nextFrame())
    }

    @Test
    fun lzwRoundTripsAcrossCodeWidthsAndTableResets() {
        val width = 400
        val height = 250
        val random = java.util.Random(7)
        // Noise fills the 4,096-code table several times over; runs exercise long strings.
        val indices = ByteArray(width * height) { i -> if ((i / 900) % 2 == 0) random.nextInt(256).toByte() else (i / 37 % 256).toByte() }
        val palette = IntArray(256) { (it shl 16) or (255 - it) }
        val data = gif(width, height, palette, listOf(indices, indices))
        val frame = GifDecoder(data).nextFrame()!!
        for (i in indices.indices) {
            assertEquals("pixel $i", (0xFF shl 24) or palette[indices[i].toInt() and 0xff], frame.pixels[i])
        }
    }

    @Test
    fun shortDelaysReadAsBrowsersReadThem() {
        assertEquals(100, GifPrep.frameDelayMs(0))
        assertEquals(100, GifPrep.frameDelayMs(1))
        assertEquals(20, GifPrep.frameDelayMs(2))
        assertEquals(1000, GifPrep.frameDelayMs(100))
    }

    @Test
    fun partFileNamesFollowTheType() {
        assertEquals("photo-2of6.jpg", MediaPartName.photo(2, 6, "image/jpeg"))
        assertEquals("animation-2of6.gif", MediaPartName.photo(2, 6, "image/gif"))
        assertEquals("animation-1of3.gif", MediaPartName.photo(1, 3, "IMAGE/GIF"))
    }

    @Test
    fun deletedMessageLogKeepsTheNewestUpToItsLimit() {
        val order = ArrayList<String>()
        val ids = HashSet<String>()
        assertTrue(DeletedMessageLog.append(order, ids, listOf("a", "b", "", "a"), limit = 3))
        assertEquals(listOf("a", "b"), order)
        assertFalse(DeletedMessageLog.append(order, ids, listOf("b", ""), limit = 3))
        assertTrue(DeletedMessageLog.append(order, ids, listOf("c", "d"), limit = 3))
        assertEquals(listOf("b", "c", "d"), order)
        assertEquals(setOf("b", "c", "d"), ids)
    }
}
