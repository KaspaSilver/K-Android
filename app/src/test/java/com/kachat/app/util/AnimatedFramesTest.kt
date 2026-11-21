package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Animated WebP, APNG and HEIF sequence reading (iOS d7c56280 GIFPrep reads anything with more
 * than one frame). The frames' bitstreams here are stand-ins (width, height, then ARGB words),
 * decoded by stand-in decoders that also check the per-frame files the containers are cut into.
 */
class AnimatedFramesTest {

    private val opaque = 0xFF shl 24
    private val red = opaque or 0xFF0000
    private val green = opaque or 0x00FF00
    private val halfBlue = (0x80 shl 24) or 0x0000FF

    // ---- byte helpers ----

    private fun ByteArrayOutputStream.le24(v: Int) { write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff) }
    private fun ByteArrayOutputStream.le32(v: Long) { le24((v and 0xffffff).toInt()); write(((v shr 24) and 0xff).toInt()) }
    private fun ByteArrayOutputStream.be16(v: Int) { write((v shr 8) and 0xff); write(v and 0xff) }
    private fun ByteArrayOutputStream.be32(v: Long) { write(((v shr 24) and 0xff).toInt()); write(((v shr 16) and 0xff).toInt()); write(((v shr 8) and 0xff).toInt()); write((v and 0xff).toInt()) }
    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun bytes(build: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(build).toByteArray()

    /** The stand-in bitstream: width, height (a byte each), then ARGB words. */
    private fun image(w: Int, h: Int, pixels: IntArray): ByteArray = bytes {
        write(w); write(h)
        for (p in pixels) be32(p.toLong() and 0xffffffffL)
    }

    private fun readImage(payload: ByteArray): DecodedImage {
        val w = payload[0].toInt() and 0xff
        val h = payload[1].toInt() and 0xff
        require(payload.size == 2 + 4 * w * h)
        val pixels = IntArray(w * h) { i -> Bytes.u32be(payload, 2 + 4 * i).toInt() }
        return DecodedImage(w, h, pixels)
    }

    // ---- WebP ----

    private fun webpChunk(type: String, payload: ByteArray): ByteArray = bytes {
        ascii(type); le32(payload.size.toLong()); write(payload)
        if (payload.size % 2 == 1) write(0)
    }

    private fun webp(vararg chunks: ByteArray): ByteArray = bytes {
        ascii("RIFF"); le32(4L + chunks.sumOf { it.size }); ascii("WEBP")
        chunks.forEach { write(it) }
    }

    private fun vp8x(w: Int, h: Int, animated: Boolean = true): ByteArray =
        webpChunk("VP8X", bytes { write(if (animated) 0x12 else 0x10); write(0); write(0); write(0); le24(w - 1); le24(h - 1) })

    private fun anim(): ByteArray = webpChunk("ANIM", ByteArray(6))

    private fun anmf(x: Int, y: Int, w: Int, h: Int, durationMs: Int, blend: Boolean, disposeToBackground: Boolean, vararg sub: ByteArray): ByteArray =
        webpChunk("ANMF", bytes {
            le24(x / 2); le24(y / 2); le24(w - 1); le24(h - 1); le24(durationMs)
            write((if (blend) 0 else 0x02) or (if (disposeToBackground) 0x01 else 0))
            sub.forEach { write(it) }
        })

    private fun vp8l(w: Int, h: Int, pixels: IntArray) = webpChunk("VP8L", image(w, h, pixels))

    /** A lossy frame: opaque colors in "VP8 ", their alpha in ALPH. */
    private fun vp8WithAlpha(w: Int, h: Int, pixels: IntArray): Array<ByteArray> = arrayOf(
        webpChunk("ALPH", ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }),
        webpChunk("VP8 ", image(w, h, IntArray(pixels.size) { opaque or (pixels[it] and 0xffffff) })),
    )

    private val webpFiles = ArrayList<ByteArray>()

    /** Decodes the per-frame WebP the container cut out, checking it is a well-formed still. */
    private val webpDecoder = FrameImageDecoder { file, maxPixels ->
        runCatching {
            webpFiles.add(file)
            require(Bytes.fourcc(file, 0) == "RIFF" && Bytes.fourcc(file, 8) == "WEBP")
            require(Bytes.u32le(file, 4) == (file.size - 8).toLong())
            var pos = 12
            var canvas: Pair<Int, Int>? = null
            var alpha: ByteArray? = null
            var picture: DecodedImage? = null
            while (pos < file.size) {
                val type = Bytes.fourcc(file, pos)
                val size = Bytes.u32le(file, pos + 4).toInt()
                val payload = file.copyOfRange(pos + 8, pos + 8 + size)
                when (type) {
                    "VP8X" -> {
                        require(payload[0].toInt() == 0x10) // alpha, not animated
                        canvas = (Bytes.u24le(payload, 4) + 1) to (Bytes.u24le(payload, 7) + 1)
                    }
                    "ALPH" -> alpha = payload
                    "VP8 ", "VP8L" -> picture = readImage(payload)
                    else -> error("unexpected chunk $type")
                }
                pos += 8 + size + (size and 1)
            }
            var result = picture!!
            canvas?.let { require(it == result.width to result.height) }
            alpha?.let { a ->
                require(canvas != null)
                result = DecodedImage(result.width, result.height, IntArray(result.pixels.size) { ((a[it].toInt() and 0xff) shl 24) or (result.pixels[it] and 0xffffff) })
            }
            require(result.width.toLong() * result.height <= maxPixels)
            result
        }.getOrNull()
    }

    /** 4 x 2: red, then half-blue blended at (2, 0) and cleared, then a lossy 2 x 1 replacing (0, 0). */
    private fun threeFrameWebp(): ByteArray = webp(
        vp8x(4, 2),
        anim(),
        anmf(0, 0, 4, 2, 50, blend = false, disposeToBackground = false, vp8l(4, 2, IntArray(8) { red })),
        anmf(2, 0, 2, 2, 10, blend = true, disposeToBackground = true, vp8l(2, 2, IntArray(4) { halfBlue })),
        anmf(0, 0, 2, 1, 70, blend = false, disposeToBackground = false, *vp8WithAlpha(2, 1, intArrayOf(0x0000FF00, green))),
    )

    @Test
    fun overBlendsUnpremultipliedColors() {
        assertEquals(0xFF7F0080.toInt(), FrameCanvas.over(halfBlue, red))
        assertEquals(halfBlue, FrameCanvas.over(halfBlue, 0))
        assertEquals(red, FrameCanvas.over(0x00123456, red))
        assertEquals(green, FrameCanvas.over(green, red))
    }

    @Test
    fun webpFramesDrawWithOffsetsBlendingAndDisposal() {
        val data = threeFrameWebp()
        assertTrue(GifPrep.isAnimated(data))
        assertFalse(GifPrep.isGif(data))
        assertFalse(GifPrep.isSafeToAnimate(data)) // a bubble plays GIFs only
        assertTrue(GifPrep.mayBeAnimated(data.copyOf(GifPrep.SNIFF_BYTES)))

        val info = WebpContainer.parse(data)!!
        assertEquals(4, info.canvasWidth)
        assertEquals(2, info.canvasHeight)
        assertEquals(3, info.frames.size)
        val animation = WebpAnimation(data, info, webpDecoder)

        val first = animation.nextFrame()!!
        assertArrayEquals(IntArray(8) { red }, first.pixels.copyOf())
        assertEquals(50, first.delayMs)

        val second = animation.nextFrame()!!
        val blended = FrameCanvas.over(halfBlue, red)
        assertArrayEquals(intArrayOf(red, red, blended, blended, red, red, blended, blended), second.pixels.copyOf())
        assertEquals(100, second.delayMs) // under 20 ms reads as 100

        val third = animation.nextFrame()!!
        // The second frame's rectangle cleared; the third replaces (0, 0) with a clear pixel.
        assertArrayEquals(intArrayOf(0x0000FF00, green, 0, 0, red, red, 0, 0), third.pixels.copyOf())
        assertEquals(70, third.delayMs)
        assertNull(animation.nextFrame())

        // The lossy frame went out as VP8X (alpha) + ALPH + VP8; the lossless ones as bare VP8L.
        assertEquals("VP8L", Bytes.fourcc(webpFiles[0], 12))
        assertEquals("VP8X", Bytes.fourcc(webpFiles[2], 12))
    }

    @Test
    fun stillOrOneFrameWebpIsNotAnimated() {
        val simple = webp(vp8l(2, 2, IntArray(4) { red }))
        assertFalse(GifPrep.isAnimated(simple))
        assertFalse(GifPrep.mayBeAnimated(simple))
        val extendedStill = webp(vp8x(2, 2, animated = false), vp8l(2, 2, IntArray(4) { red }))
        assertFalse(GifPrep.isAnimated(extendedStill))
        assertFalse(GifPrep.mayBeAnimated(extendedStill))
        val oneFrame = webp(vp8x(2, 2), anim(), anmf(0, 0, 2, 2, 50, false, false, vp8l(2, 2, IntArray(4) { red })))
        assertFalse(GifPrep.isAnimated(oneFrame))
        assertTrue(GifPrep.mayBeAnimated(oneFrame))
    }

    @Test
    fun malformedWebpIsRefusedWithoutCrashing() {
        // A frame past the canvas.
        val pastCanvas = webp(
            vp8x(4, 2), anim(),
            anmf(0, 0, 4, 2, 50, false, false, vp8l(4, 2, IntArray(8) { red })),
            anmf(2, 0, 4, 2, 50, false, false, vp8l(4, 2, IntArray(8) { red })),
        )
        assertNull(WebpContainer.parse(pastCanvas))
        assertFalse(GifPrep.isAnimated(pastCanvas))
        // A frame with no bitstream.
        val empty = webp(vp8x(2, 2), anmf(0, 0, 2, 2, 50, false, false), anmf(0, 0, 2, 2, 50, false, false))
        assertNull(WebpContainer.parse(empty))
        // Cut short in the third frame: the two whole ones are kept.
        val full = threeFrameWebp()
        assertEquals(2, WebpContainer.parse(full.copyOf(full.size - 10))!!.frames.size)
        // A chunk (ANIM, after RIFF and VP8X) claiming 4 GB: nothing past it is read.
        val huge = full.copyOf().also { for (i in 34..37) it[i] = 0xFF.toByte() }
        assertEquals(0, WebpContainer.parse(huge)!!.frames.size)
        // A RIFF size past the file.
        val riffLie = full.copyOf().also { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte(); it[6] = 0xFF.toByte(); it[7] = 0x7F }
        assertEquals(3, WebpContainer.parse(riffLie)!!.frames.size)
        fuzz(full) { data ->
            GifPrep.isAnimated(data)
            WebpContainer.parse(data)?.takeIf { it.canvasWidth.toLong() * it.canvasHeight <= 4096 }?.let { info ->
                runCatching { WebpAnimation(data, info, webpDecoder) }.getOrNull()?.let { a -> while (a.nextFrame() != null) Unit }
            }
        }
    }

    @Test
    fun hugeWebpCanvasIsNotDecoded() {
        val data = webp(
            vp8x(5000, 5000), anim(),
            anmf(0, 0, 2, 2, 50, false, false, vp8l(2, 2, IntArray(4) { red })),
            anmf(0, 0, 2, 2, 50, false, false, vp8l(2, 2, IntArray(4) { green })),
        )
        assertTrue(GifPrep.isAnimated(data))
        try {
            AnimationSources.open(data, webpDecoder)
            fail("a 25 MP canvas must be refused")
        } catch (e: IllegalArgumentException) {
            // refused
        }
        try {
            GifPrep.prepareForTiers(data, listOf(10_000))
            fail("a 25 MP canvas must be refused")
        } catch (e: GifPrep.NotAnimatedException) {
            // refused
        }
    }

    // ---- APNG ----

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngChunk(type: String, data: ByteArray): ByteArray = bytes {
        be32(data.size.toLong()); ascii(type); write(data)
        val crc = CRC32()
        crc.update(type.toByteArray(Charsets.US_ASCII))
        crc.update(data)
        be32(crc.value)
    }

    private fun png(vararg chunks: ByteArray): ByteArray = bytes { write(pngSignature); chunks.forEach { write(it) } }
    private fun ihdr(w: Int, h: Int) = pngChunk("IHDR", bytes { be32(w.toLong()); be32(h.toLong()); write(8); write(6); write(0); write(0); write(0) })
    private fun actl(frames: Int) = pngChunk("acTL", bytes { be32(frames.toLong()); be32(0) })
    private fun gama() = pngChunk("gAMA", bytes { be32(45455) })

    private fun fctl(sequence: Int, w: Int, h: Int, x: Int, y: Int, num: Int, den: Int, dispose: Int, blend: Int) =
        pngChunk("fcTL", bytes {
            be32(sequence.toLong()); be32(w.toLong()); be32(h.toLong()); be32(x.toLong()); be32(y.toLong())
            be16(num); be16(den); write(dispose); write(blend)
        })

    private fun fdat(sequence: Int, data: ByteArray) = pngChunk("fdAT", bytes { be32(sequence.toLong()); write(data) })
    private fun idat(data: ByteArray) = pngChunk("IDAT", data)
    private fun iend() = pngChunk("IEND", ByteArray(0))

    private val pngFiles = ArrayList<ByteArray>()

    /** Decodes the per-frame PNG the container cut out, checking its CRCs and chunks. */
    private val pngDecoder = FrameImageDecoder { file, maxPixels ->
        runCatching {
            pngFiles.add(file)
            require(file.copyOf(8).contentEquals(pngSignature))
            var pos = 8
            val types = ArrayList<String>()
            var size: Pair<Int, Int>? = null
            var imageData: ByteArray? = null
            while (pos < file.size) {
                val length = Bytes.u32be(file, pos).toInt()
                val type = Bytes.fourcc(file, pos + 4)
                val crc = CRC32()
                crc.update(file, pos + 4, 4 + length)
                require(crc.value == Bytes.u32be(file, pos + 8 + length)) { "bad CRC in $type" }
                val payload = file.copyOfRange(pos + 8, pos + 8 + length)
                types.add(type)
                if (type == "IHDR") {
                    size = Bytes.u32be(payload, 0).toInt() to Bytes.u32be(payload, 4).toInt()
                    require(payload[8].toInt() == 8 && payload[9].toInt() == 6) // depth and color type kept
                }
                if (type == "IDAT") imageData = payload
                pos += 12 + length
            }
            require(types == listOf("IHDR", "gAMA", "IDAT", "IEND")) { "chunks $types" }
            val picture = readImage(imageData!!)
            require(size == picture.width to picture.height)
            require(picture.width.toLong() * picture.height <= maxPixels)
            picture
        }.getOrNull()
    }

    private fun split(data: ByteArray): Pair<ByteArray, ByteArray> = data.copyOf(data.size / 2) to data.copyOfRange(data.size / 2, data.size)

    /**
     * 4 x 2: red as the default image (in two IDATs), half-blue blended over (1, 0) and put back
     * after ("previous"), then green replacing (0, 1) and cleared after ("background").
     */
    private fun threeFrameApng(): ByteArray {
        val (redA, redB) = split(image(4, 2, IntArray(8) { red }))
        val (blueA, blueB) = split(image(2, 2, IntArray(4) { halfBlue }))
        return png(
            ihdr(4, 2), actl(3), gama(),
            fctl(0, 4, 2, 0, 0, 1, 10, ApngContainer.DISPOSE_NONE, ApngContainer.BLEND_SOURCE),
            idat(redA), idat(redB),
            fctl(1, 2, 2, 1, 0, 3, 0, ApngContainer.DISPOSE_PREVIOUS, ApngContainer.BLEND_OVER),
            fdat(2, blueA), fdat(3, blueB),
            fctl(4, 1, 1, 0, 1, 1, 100, ApngContainer.DISPOSE_BACKGROUND, ApngContainer.BLEND_SOURCE),
            fdat(5, image(1, 1, intArrayOf(green))),
            pngChunk("tEXt", "Comment\u0000hi".toByteArray(Charsets.ISO_8859_1)), // after the image data: not shared
            iend(),
        )
    }

    @Test
    fun apngFramesDrawWithOffsetsBlendingAndDisposal() {
        val data = threeFrameApng()
        assertTrue(GifPrep.isAnimated(data))
        assertTrue(GifPrep.mayBeAnimated(data.copyOf(GifPrep.SNIFF_BYTES)))
        assertFalse(GifPrep.isSafeToAnimate(data))

        val info = ApngContainer.parse(data)!!
        assertEquals(3, info.frames.size)
        assertEquals(listOf("gAMA"), info.shared.map { it.first })
        val animation = ApngAnimation(data, info, pngDecoder)

        val first = animation.nextFrame()!!
        assertArrayEquals(IntArray(8) { red }, first.pixels.copyOf())
        assertEquals(100, first.delayMs)

        val second = animation.nextFrame()!!
        val blended = FrameCanvas.over(halfBlue, red)
        assertArrayEquals(intArrayOf(red, blended, blended, red, red, blended, blended, red), second.pixels.copyOf())
        assertEquals(30, second.delayMs) // 3 / 0 is 3 / 100 s

        val third = animation.nextFrame()!!
        // Back to before the second frame, then green at (0, 1).
        assertArrayEquals(intArrayOf(red, red, red, red, green, red, red, red), third.pixels.copyOf())
        assertEquals(100, third.delayMs) // 10 ms reads as 100
        assertNull(animation.nextFrame())
        assertEquals(3, pngFiles.size)
    }

    @Test
    fun apngHiddenDefaultImageAndFirstFramePrevious() {
        val data = png(
            ihdr(2, 1), actl(2), gama(),
            idat(image(2, 1, intArrayOf(green, green))), // not part of the animation
            fctl(0, 2, 1, 0, 0, 1, 10, ApngContainer.DISPOSE_PREVIOUS, ApngContainer.BLEND_SOURCE),
            fdat(1, image(2, 1, intArrayOf(red, red))),
            fctl(2, 1, 1, 1, 0, 1, 10, ApngContainer.DISPOSE_NONE, ApngContainer.BLEND_OVER),
            fdat(3, image(1, 1, intArrayOf(halfBlue))),
            iend(),
        )
        val info = ApngContainer.parse(data)!!
        assertEquals(2, info.frames.size)
        val animation = ApngAnimation(data, info, pngDecoder)
        assertArrayEquals(intArrayOf(red, red), animation.nextFrame()!!.pixels.copyOf())
        // A first frame's "previous" clears it, so the half-blue lands on nothing.
        assertArrayEquals(intArrayOf(0, halfBlue), animation.nextFrame()!!.pixels.copyOf())
    }

    @Test
    fun stillOrOneFramePngIsNotAnimated() {
        val still = png(ihdr(2, 1), gama(), idat(image(2, 1, intArrayOf(red, red))), iend())
        assertFalse(GifPrep.isAnimated(still))
        assertTrue(GifPrep.mayBeAnimated(still)) // a PNG says only once read
        val oneFrame = png(
            ihdr(2, 1), actl(1),
            fctl(0, 2, 1, 0, 0, 1, 10, 0, 0), idat(image(2, 1, intArrayOf(red, red))), iend(),
        )
        assertFalse(GifPrep.isAnimated(oneFrame))
        // acTL after the image data doesn't count.
        val lateActl = png(
            ihdr(2, 1), idat(image(2, 1, intArrayOf(red, red))), actl(2),
            fctl(0, 2, 1, 0, 0, 1, 10, 0, 0), fdat(1, image(2, 1, intArrayOf(red, red))),
            fctl(2, 2, 1, 0, 0, 1, 10, 0, 0), fdat(3, image(2, 1, intArrayOf(red, red))), iend(),
        )
        assertFalse(GifPrep.isAnimated(lateActl))
    }

    @Test
    fun malformedApngIsRefusedWithoutCrashing() {
        fun twoFrames(second: ByteArray) = png(
            ihdr(4, 2), actl(2),
            fctl(0, 4, 2, 0, 0, 1, 10, 0, 0), idat(image(4, 2, IntArray(8) { red })),
            second, fdat(2, image(2, 2, IntArray(4) { red })), iend(),
        )
        assertNotNull(ApngContainer.parse(twoFrames(fctl(1, 2, 2, 2, 0, 1, 10, 0, 0))))
        assertNull(ApngContainer.parse(twoFrames(fctl(1, 2, 2, 3, 0, 1, 10, 0, 0)))) // past the canvas
        assertNull(ApngContainer.parse(twoFrames(fctl(1, 0, 2, 0, 0, 1, 10, 0, 0)))) // no width
        assertNull(ApngContainer.parse(twoFrames(fctl(1, 2, 2, 0, 0, 1, 10, 3, 0)))) // unknown disposal
        assertNull(ApngContainer.parse(twoFrames(fctl(1, 2, 2, 0, 0, 1, 10, 0, 2)))) // unknown blending
        // A default-image frame smaller than the canvas.
        assertNull(ApngContainer.parse(png(ihdr(4, 2), actl(2), fctl(0, 2, 2, 0, 0, 1, 10, 0, 0), idat(ByteArray(4)), iend())))
        // No IHDR first.
        assertNull(ApngContainer.parse(png(actl(2), ihdr(4, 2), iend())))
        // A chunk claiming 4 GB.
        val full = threeFrameApng()
        val huge = full.copyOf().also { for (i in 33..36) it[i] = 0xFF.toByte() }
        assertNull(ApngContainer.parse(huge))
        // Cut short in the last frame: the two whole ones are kept.
        val cut = full.copyOf(full.lastIndexOfChunk("fdAT") + 10)
        assertEquals(2, ApngContainer.parse(cut)!!.frames.size)
        fuzz(full) { data ->
            GifPrep.isAnimated(data)
            ApngContainer.parse(data)?.takeIf { it.canvasWidth.toLong() * it.canvasHeight <= 4096 }?.let { info ->
                runCatching { ApngAnimation(data, info, pngDecoder) }.getOrNull()?.let { a -> while (a.nextFrame() != null) Unit }
            }
        }
    }

    @Test
    fun apngDelaysAreNumOverDen() {
        assertEquals(100, ApngContainer.delayMs(1, 10))
        assertEquals(30, ApngContainer.delayMs(3, 0))
        assertEquals(100, ApngContainer.delayMs(1, 100))
        assertEquals(100, ApngContainer.delayMs(0, 0))
        assertEquals(42, ApngContainer.delayMs(42, 1000))
        assertEquals(655_350, ApngContainer.delayMs(65_535, 1))
    }

    private fun ByteArray.lastIndexOfChunk(type: String): Int {
        val target = type.toByteArray(Charsets.US_ASCII)
        for (i in size - target.size downTo 0) if ((target.indices).all { this[i + it] == target[it] }) return i
        return -1
    }

    // ---- HEIF sequences ----

    private fun box(type: String, payload: ByteArray): ByteArray = bytes { be32(8L + payload.size); ascii(type); write(payload) }
    private fun container(type: String, vararg children: ByteArray): ByteArray = box(type, bytes { children.forEach { write(it) } })

    private fun ftyp(major: String, vararg compatible: String) = box("ftyp", bytes { ascii(major); be32(0); compatible.forEach { ascii(it) } })

    private fun track(handler: String, timescale: Long, vararg stts: Pair<Long, Long>) = box(
        "trak",
        container(
            "mdia",
            box("mdhd", bytes { be32(0); be32(0); be32(0); be32(timescale); be32(0); be32(0) }),
            box("hdlr", bytes { be32(0); be32(0); ascii(handler); be32(0); be32(0); be32(0); write(0) }),
            box("minf", box("stbl", box("stts", bytes {
                be32(0); be32(stts.size.toLong())
                stts.forEach { (count, delta) -> be32(count); be32(delta) }
            }))),
        ),
    )

    @Test
    fun heifSequenceDelaysComeFromThePictTrack() {
        val data = bytes {
            write(ftyp("msf1", "msf1", "heic", "hevc"))
            write(box("meta", ByteArray(12)))
            write(container("moov", box("mvhd", ByteArray(100)), track("soun", 1000, 5L to 7L), track("pict", 600, 2L to 30L, 1L to 6L)))
            write(box("mdat", ByteArray(32)))
        }
        assertTrue(HeifSequence.hasSignature(data))
        assertTrue(GifPrep.mayBeAnimated(data.copyOf(GifPrep.SNIFF_BYTES)))
        // 30 / 600 s is 50 ms; 6 / 600 s is 10 ms, which reads as 100.
        assertArrayEquals(intArrayOf(50, 50, 100), HeifSequence.frameDelays(data))
        assertArrayEquals(intArrayOf(50, 50), HeifSequence.frameDelays(data, max = 2))
    }

    @Test
    fun heifStillOrOtherFilesAreNotSequences() {
        val still = bytes { write(ftyp("heic", "mif1", "heic")); write(box("meta", ByteArray(40))); write(box("mdat", ByteArray(32))) }
        assertTrue(HeifSequence.hasSignature(still))
        assertNull(HeifSequence.frameDelays(still))
        assertFalse(GifPrep.isAnimated(still))
        val mp4 = bytes { write(ftyp("isom", "isom", "mp41")); write(box("moov", track("vide", 600, 10L to 20L))) }
        assertFalse(HeifSequence.hasSignature(mp4))
        assertFalse(GifPrep.mayBeAnimated(mp4))
        assertFalse(GifPrep.isAnimated(mp4))
        // Boxes that run past their parent, or claim 4 GB, are not followed.
        val broken = bytes { write(ftyp("msf1", "msf1")); be32(0xFFFFFFF0L); ascii("moov"); write(ByteArray(16)) }
        assertNull(HeifSequence.frameDelays(broken))
        val sequence = bytes { write(ftyp("msf1", "msf1")); write(box("moov", track("pict", 600, 3L to 30L))) }
        fuzz(sequence) { HeifSequence.frameDelays(it) }
    }

    @Test
    fun picksOfOtherFilesAreNotSniffedAsAnimations() {
        val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46)
        assertFalse(GifPrep.mayBeAnimated(jpegHead))
        assertFalse(GifPrep.mayBeAnimated(ByteArray(0)))
        assertFalse(GifPrep.isAnimated(ByteArray(0)))
        assertNull(AnimationSources.open(jpegHead, webpDecoder))
    }

    /** Cuts and flipped bytes of [valid]: [read] must never throw. */
    private fun fuzz(valid: ByteArray, read: (ByteArray) -> Unit) {
        val random = java.util.Random(11)
        for (length in valid.indices) read(valid.copyOf(length))
        repeat(2_000) {
            val data = valid.copyOf()
            repeat(1 + random.nextInt(4)) { data[random.nextInt(data.size)] = random.nextInt(256).toByte() }
            read(data)
        }
    }
}
