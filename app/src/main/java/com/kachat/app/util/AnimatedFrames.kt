package com.kachat.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * The animations [GifPrep.prepareForTiers] reads (iOS d7c56280 `GIFPrep`, where ImageIO reads
 * anything with more than one frame): GIF ([GifDecoder]), animated WebP ([WebpAnimation]), APNG
 * ([ApngAnimation]) and HEIF image sequences ([HeifSequenceSource]). Each one is re-encoded as a
 * GIF, so what goes on chain is always a GIF.
 *
 * The containers are parsed here, never trusting a length they declare; each WebP / PNG frame is
 * cut out as a file of its own and decoded by the platform ([FrameImageDecoder]), then drawn onto
 * one canvas with its offset, blending and disposal. Parsing and drawing need no Android, so they
 * are unit tested with a stand-in decoder.
 */
internal object AnimationSources {
    /**
     * The animation in [data], or null when it isn't one of the formats above (or is a still).
     * @throws IllegalArgumentException when its canvas is past [GifDecoder.MAX_CANVAS_PIXELS].
     */
    fun open(data: ByteArray, decoder: FrameImageDecoder = BitmapFrameDecoder): AnimationSource? = when {
        GifScan.hasSignature(data) -> GifDecoder(data)
        WebpContainer.hasSignature(data) -> WebpContainer.parse(data)?.let { WebpAnimation(data, it, decoder) }
        ApngContainer.hasSignature(data) -> ApngContainer.parse(data)?.let { ApngAnimation(data, it, decoder) }
        HeifSequence.hasSignature(data) -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HeifSequenceSource.open(data) else null
        else -> null
    }

    /** More than one frame, read from the container alone (a HEIF sequence also asks the
     *  platform whether it can read its frames). */
    fun isAnimated(data: ByteArray): Boolean = when {
        GifScan.hasSignature(data) -> GifScan.scan(data, stopAfterFrames = 2)?.let { it.frameCount > 1 } == true
        WebpContainer.hasSignature(data) -> (WebpContainer.parse(data, maxFrames = 2)?.frames?.size ?: 0) > 1
        ApngContainer.hasSignature(data) -> (ApngContainer.parse(data, maxFrames = 2)?.frames?.size ?: 0) > 1
        HeifSequence.hasSignature(data) -> (HeifSequence.frameDelays(data, max = 2)?.size ?: 0) > 1 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && HeifSequenceSource.canRead(data)
        else -> false
    }

    /** Whether a file starting with [head] (its first 64 bytes or so) may be an animation this
     *  reads. A WebP says so up front (VP8X's animation flag); a still one is never read whole. */
    fun hasAnimatableSignature(head: ByteArray): Boolean =
        GifScan.hasSignature(head) || WebpContainer.mayBeAnimated(head) || ApngContainer.hasSignature(head) || HeifSequence.hasSignature(head)
}

/** One frame drawn onto its animation's canvas: ARGB, not premultiplied, row by row. The array is
 *  the canvas itself, reused: copy it before asking for the next frame. */
internal class AnimationFrame(val pixels: IntArray, val delayMs: Int)

/** A frame as [GifPrep.prepareForTiers] reads it: a bitmap the caller owns and recycles. */
internal class SourceFrame(val bitmap: Bitmap, val delayMs: Int, val hasAlpha: Boolean)

/** An animation read one frame at a time, so a long one costs one frame of memory. */
internal interface AnimationSource : java.io.Closeable {
    val width: Int
    val height: Int

    /** The next frame, or null at the end (or where the file is cut short or a frame won't
     *  decode). [checkAlpha]: whether to look for see-through pixels. */
    fun nextBitmap(checkAlpha: Boolean): SourceFrame?

    override fun close() {}
}

/** An animation drawn here onto an ARGB canvas ([nextFrame]). */
internal abstract class CanvasAnimation : AnimationSource {
    abstract fun nextFrame(): AnimationFrame?

    override fun nextBitmap(checkAlpha: Boolean): SourceFrame? {
        val frame = nextFrame() ?: return null
        val hasAlpha = checkAlpha && frame.pixels.any { (it ushr 24) < 0x80 }
        return SourceFrame(Bitmap.createBitmap(frame.pixels, width, height, Bitmap.Config.ARGB_8888), frame.delayMs, hasAlpha)
    }
}

/** A decoded still: ARGB, not premultiplied, row by row. */
internal class DecodedImage(val width: Int, val height: Int, val pixels: IntArray)

/** Decodes one frame cut out as a file of its own (a WebP or a PNG). */
internal fun interface FrameImageDecoder {
    /** Null when it won't decode, or is more than [maxPixels]. */
    fun decode(data: ByteArray, maxPixels: Long): DecodedImage?
}

/** [FrameImageDecoder] by [BitmapFactory], in sRGB; the size is read first, so a frame claiming
 *  more than it should is never decoded. */
internal object BitmapFrameDecoder : FrameImageDecoder {
    override fun decode(data: ByteArray, maxPixels: Long): DecodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            DecodedImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}

/** The canvas an animation's frames are drawn onto (ARGB, not premultiplied), starting clear. */
internal class FrameCanvas(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
    private var saved: IntArray? = null

    /** Clears a rectangle to transparent, as browsers dispose "to background". */
    fun clear(x: Int, y: Int, w: Int, h: Int) {
        val x0 = x.coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        val x1 = (x.toLong() + w).coerceIn(0L, width.toLong()).toInt()
        val y1 = (y.toLong() + h).coerceIn(0L, height.toLong()).toInt()
        if (x1 <= x0) return
        for (row in y0 until y1) java.util.Arrays.fill(pixels, row * width + x0, row * width + x1, 0)
    }

    /** Keeps what the canvas shows now, for [restore] (APNG "dispose to previous"). */
    fun save() {
        val copy = saved?.takeIf { it.size == pixels.size } ?: IntArray(pixels.size)
        System.arraycopy(pixels, 0, copy, 0, pixels.size)
        saved = copy
    }

    fun restore() {
        saved?.let { System.arraycopy(it, 0, pixels, 0, pixels.size) }
    }

    /**
     * [image] drawn at ([x], [y]), cut to [w] x [h] and the canvas: over what is there when
     * [blend], else replacing it, see-through pixels and all.
     */
    fun draw(image: DecodedImage, x: Int, y: Int, w: Int, h: Int, blend: Boolean) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val drawW = minOf(image.width, w, width - x)
        val drawH = minOf(image.height, h, height - y)
        if (drawW <= 0 || drawH <= 0 || image.pixels.size < image.width * image.height) return
        for (row in 0 until drawH) {
            val source = row * image.width
            val target = (y + row) * width + x
            if (!blend) {
                System.arraycopy(image.pixels, source, pixels, target, drawW)
                continue
            }
            for (column in 0 until drawW) {
                pixels[target + column] = over(image.pixels[source + column], pixels[target + column])
            }
        }
    }

    companion object {
        /** [src] over [dst], both ARGB not premultiplied ("source over"). */
        fun over(src: Int, dst: Int): Int {
            val sa = src ushr 24
            if (sa == 255) return src
            if (sa == 0) return dst
            val da = dst ushr 24
            if (da == 0) return src
            val sw = sa * 255
            val dw = da * (255 - sa)
            val total = sw + dw
            val alpha = (total + 127) / 255
            val r = ((((src shr 16) and 0xff) * sw + ((dst shr 16) and 0xff) * dw + total / 2) / total)
            val g = ((((src shr 8) and 0xff) * sw + ((dst shr 8) and 0xff) * dw + total / 2) / total)
            val b = (((src and 0xff) * sw + (dst and 0xff) * dw + total / 2) / total)
            return (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** Byte reading for the containers: every read is the caller's to bounds-check first. */
internal object Bytes {
    fun fourcc(data: ByteArray, at: Int): String =
        String(charArrayOf((data[at].toInt() and 0xff).toChar(), (data[at + 1].toInt() and 0xff).toChar(), (data[at + 2].toInt() and 0xff).toChar(), (data[at + 3].toInt() and 0xff).toChar()))

    fun u16be(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)

    fun u24le(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or ((data[at + 2].toInt() and 0xff) shl 16)

    fun u32le(data: ByteArray, at: Int): Long = (u24le(data, at).toLong()) or ((data[at + 3].toLong() and 0xff) shl 24)

    fun u32be(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xff) shl 24) or ((data[at + 1].toLong() and 0xff) shl 16) or
            ((data[at + 2].toLong() and 0xff) shl 8) or (data[at + 3].toLong() and 0xff)

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    fun writeU32le(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xff).toInt())
        out.write(((value shr 8) and 0xff).toInt())
        out.write(((value shr 16) and 0xff).toInt())
        out.write(((value shr 24) and 0xff).toInt())
    }

    fun writeU32be(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xff).toInt())
        out.write(((value shr 16) and 0xff).toInt())
        out.write(((value shr 8) and 0xff).toInt())
        out.write((value and 0xff).toInt())
    }

    fun putU32be(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }
}

/**
 * An animated WebP's RIFF container: VP8X with the animation flag, then ANMF frames, each with
 * its offset, size, duration, blending and disposal and its bitstream (ALPH + VP8, or VP8L).
 */
internal object WebpContainer {
    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val delayMs: Int,
        /** Alpha-blended onto the canvas; false replaces the rectangle. */
        val blend: Boolean,
        /** Cleared to transparent once shown. */
        val disposeToBackground: Boolean,
        /** "VP8 " or "VP8L". */
        val bitstreamType: String,
        /** The bitstream chunk's payload in the file. */
        val bitstream: IntRange,
        /** A lossy frame's ALPH chunk payload, if it has one. */
        val alpha: IntRange?,
    )

    class Info(val canvasWidth: Int, val canvasHeight: Int, val frames: List<Frame>)

    fun hasSignature(data: ByteArray): Boolean =
        data.size >= 12 && Bytes.fourcc(data, 0) == "RIFF" && Bytes.fourcc(data, 8) == "WEBP"

    /** A WebP whose first chunk is VP8X with the animation flag (needs its first 21 bytes). */
    fun mayBeAnimated(head: ByteArray): Boolean =
        hasSignature(head) && head.size >= 21 && Bytes.fourcc(head, 12) == "VP8X" && (head[20].toInt() and 0x02) != 0

    /**
     * Null when [data] isn't an animated WebP, or is malformed (a frame past the canvas, a frame
     * with no bitstream). A file cut short keeps the frames it holds whole. At most [maxFrames].
     */
    fun parse(data: ByteArray, maxFrames: Int = GifPrep.MAX_FRAMES): Info? {
        if (!hasSignature(data)) return null
        val end = minOf(data.size.toLong(), 8L + Bytes.u32le(data, 4)).toInt()
        var pos = 12
        var canvasWidth = 0
        var canvasHeight = 0
        var first = true
        val frames = ArrayList<Frame>()
        while (pos.toLong() + 8 <= end && frames.size < maxFrames) {
            val type = Bytes.fourcc(data, pos)
            val size = Bytes.u32le(data, pos + 4)
            val start = pos + 8
            val stop = start + size
            if (stop > end) break
            if (first) {
                // Only the extended format animates; a simple "VP8 " / "VP8L" file is a still.
                if (type != "VP8X" || size < 10) return null
                if ((data[start].toInt() and 0x02) == 0) return null
                canvasWidth = Bytes.u24le(data, start + 4) + 1
                canvasHeight = Bytes.u24le(data, start + 7) + 1
                first = false
            } else if (type == "ANMF") {
                frames.add(parseFrame(data, start, stop.toInt(), canvasWidth, canvasHeight) ?: return null)
            }
            val next = stop + (size and 1)
            if (next > end) break
            pos = next.toInt()
        }
        if (first) return null
        return Info(canvasWidth, canvasHeight, frames)
    }

    private fun parseFrame(data: ByteArray, start: Int, stop: Int, canvasWidth: Int, canvasHeight: Int): Frame? {
        if (stop - start < 16) return null
        val x = Bytes.u24le(data, start) * 2
        val y = Bytes.u24le(data, start + 3) * 2
        val width = Bytes.u24le(data, start + 6) + 1
        val height = Bytes.u24le(data, start + 9) + 1
        val duration = Bytes.u24le(data, start + 12)
        val flags = data[start + 15].toInt() and 0xff
        if (x.toLong() + width > canvasWidth || y.toLong() + height > canvasHeight) return null
        var pos = start + 16
        var alpha: IntRange? = null
        while (pos.toLong() + 8 <= stop) {
            val type = Bytes.fourcc(data, pos)
            val size = Bytes.u32le(data, pos + 4)
            val payload = pos + 8
            val payloadEnd = payload + size
            if (payloadEnd > stop) return null
            when (type) {
                "ALPH" -> alpha = payload until payloadEnd.toInt()
                "VP8 ", "VP8L" -> return Frame(
                    x, y, width, height, GifPrep.delayMs(duration.toLong()),
                    blend = flags and 0x02 == 0,
                    disposeToBackground = flags and 0x01 != 0,
                    bitstreamType = type,
                    bitstream = payload until payloadEnd.toInt(),
                    // VP8L carries its own alpha.
                    alpha = if (type == "VP8 ") alpha else null,
                )
            }
            val next = payloadEnd + (size and 1)
            if (next > stop) return null
            pos = next.toInt()
        }
        return null
    }

    /** [frame] as a still WebP of its own: VP8X (with alpha) + ALPH + VP8, or just its bitstream. */
    fun frameFile(data: ByteArray, frame: Frame): ByteArray {
        val chunks = ByteArrayOutputStream()
        val alpha = frame.alpha
        if (alpha != null) {
            val header = ByteArray(10)
            header[0] = 0x10 // alpha
            val w = frame.width - 1
            val h = frame.height - 1
            header[4] = w.toByte(); header[5] = (w shr 8).toByte(); header[6] = (w shr 16).toByte()
            header[7] = h.toByte(); header[8] = (h shr 8).toByte(); header[9] = (h shr 16).toByte()
            writeChunk(chunks, "VP8X", header, 0, header.size)
            writeChunk(chunks, "ALPH", data, alpha.first, alpha.last + 1 - alpha.first)
        }
        writeChunk(chunks, frame.bitstreamType, data, frame.bitstream.first, frame.bitstream.last + 1 - frame.bitstream.first)
        val out = ByteArrayOutputStream(chunks.size() + 12)
        out.write(Bytes.ascii("RIFF"))
        Bytes.writeU32le(out, 4L + chunks.size())
        out.write(Bytes.ascii("WEBP"))
        chunks.writeTo(out)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray, offset: Int, length: Int) {
        out.write(Bytes.ascii(type))
        Bytes.writeU32le(out, length.toLong())
        out.write(data, offset, length)
        if (length and 1 != 0) out.write(0)
    }
}

/** An animated WebP drawn frame by frame (as libwebp's animation decoder draws it). */
internal class WebpAnimation(
    private val data: ByteArray,
    private val info: WebpContainer.Info,
    private val decoder: FrameImageDecoder,
) : CanvasAnimation() {
    override val width: Int = info.canvasWidth
    override val height: Int = info.canvasHeight
    private val canvas: FrameCanvas
    private var index = 0
    private var toClear: WebpContainer.Frame? = null

    init {
        require(width.toLong() * height <= GifDecoder.MAX_CANVAS_PIXELS) { "WebP canvas out of range" }
        canvas = FrameCanvas(width, height)
    }

    override fun nextFrame(): AnimationFrame? {
        if (index >= info.frames.size) return null
        val frame = info.frames[index++]
        toClear?.let { canvas.clear(it.x, it.y, it.width, it.height) }
        toClear = null
        val image = decoder.decode(WebpContainer.frameFile(data, frame), frame.width.toLong() * frame.height) ?: run {
            index = info.frames.size
            return null
        }
        canvas.draw(image, frame.x, frame.y, frame.width, frame.height, frame.blend)
        if (frame.disposeToBackground) toClear = frame
        return AnimationFrame(canvas.pixels, frame.delayMs)
    }
}

/**
 * An APNG's chunks: acTL (before IDAT) makes it animated; each fcTL starts a frame whose image
 * data is the IDAT that follows it (the default image) or its fdAT chunks.
 */
internal object ApngContainer {
    const val DISPOSE_NONE = 0
    const val DISPOSE_BACKGROUND = 1
    const val DISPOSE_PREVIOUS = 2
    const val BLEND_SOURCE = 0
    const val BLEND_OVER = 1

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val delayMs: Int,
        val dispose: Int,
        val blend: Int,
        /** The compressed image data: IDAT payloads, or fdAT payloads past their sequence number. */
        val data: List<IntRange>,
    )

    class Info(
        val canvasWidth: Int,
        val canvasHeight: Int,
        /** IHDR's payload. */
        val header: IntRange,
        /** The chunks every frame shares (PLTE, tRNS, gAMA, iCCP...): type and payload. */
        val shared: List<Pair<String, IntRange>>,
        val frames: List<Frame>,
    )

    fun hasSignature(data: ByteArray): Boolean {
        if (data.size < SIGNATURE.size) return false
        for (i in SIGNATURE.indices) if (data[i] != SIGNATURE[i]) return false
        return true
    }

    private class FrameBuilder(
        val x: Int, val y: Int, val width: Int, val height: Int, val delayMs: Int, val dispose: Int, val blend: Int,
        val isDefaultImage: Boolean,
    ) {
        val data = ArrayList<IntRange>()
        fun build() = Frame(x, y, width, height, delayMs, dispose, blend, data)
    }

    /**
     * Null when [data] isn't an APNG, or is malformed (a frame past the canvas, an unknown
     * disposal or blending, a first frame that isn't the whole canvas). A file cut short keeps
     * the frames it holds. At most [maxFrames].
     */
    fun parse(data: ByteArray, maxFrames: Int = GifPrep.MAX_FRAMES): Info? {
        if (!hasSignature(data)) return null
        var pos = SIGNATURE.size
        var header: IntRange? = null
        var canvasWidth = 0
        var canvasHeight = 0
        var animated = false
        var seenImageData = false
        val shared = ArrayList<Pair<String, IntRange>>()
        val frames = ArrayList<Frame>()
        var current: FrameBuilder? = null

        fun finish() {
            current?.let { if (it.data.isNotEmpty() && frames.size < maxFrames) frames.add(it.build()) }
            current = null
        }

        chunks@ while (pos.toLong() + 12 <= data.size) {
            val length = Bytes.u32be(data, pos)
            if (length > Int.MAX_VALUE) return null
            val type = Bytes.fourcc(data, pos + 4)
            val start = pos + 8
            val stop = start + length
            if (stop + 4 > data.size) break // cut short
            val end = stop.toInt()
            if (header == null) {
                if (type != "IHDR" || length != 13L) return null
                val w = Bytes.u32be(data, start)
                val h = Bytes.u32be(data, start + 4)
                if (w <= 0 || h <= 0 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
                canvasWidth = w.toInt()
                canvasHeight = h.toInt()
                header = start until end
            } else {
                when (type) {
                    "acTL" -> if (!seenImageData && length >= 8) animated = true
                    "fcTL" -> {
                        if (length < 26) return null
                        finish()
                        if (frames.size >= maxFrames) break@chunks
                        val w = Bytes.u32be(data, start + 4)
                        val h = Bytes.u32be(data, start + 8)
                        val x = Bytes.u32be(data, start + 12)
                        val y = Bytes.u32be(data, start + 16)
                        val delayNum = Bytes.u16be(data, start + 20)
                        val delayDen = Bytes.u16be(data, start + 22)
                        val dispose = data[start + 24].toInt() and 0xff
                        val blend = data[start + 25].toInt() and 0xff
                        if (w <= 0 || h <= 0 || x + w > canvasWidth || y + h > canvasHeight) return null
                        if (dispose > DISPOSE_PREVIOUS || blend > BLEND_OVER) return null
                        // The default image, when it is a frame, is the whole canvas.
                        if (!seenImageData && (x != 0L || y != 0L || w != canvasWidth.toLong() || h != canvasHeight.toLong())) return null
                        current = FrameBuilder(x.toInt(), y.toInt(), w.toInt(), h.toInt(), delayMs(delayNum, delayDen), dispose, blend, isDefaultImage = !seenImageData)
                    }
                    "IDAT" -> {
                        seenImageData = true
                        current?.let { if (it.isDefaultImage) it.data.add(start until end) }
                    }
                    "fdAT" -> current?.let { if (!it.isDefaultImage && length > 4) it.data.add(start + 4 until end) }
                    "IEND" -> break@chunks
                    else -> if (!seenImageData) shared.add(type to (start until end))
                }
            }
            pos = end + 4 // past the CRC
        }
        finish()
        if (!animated || header == null) return null
        return Info(canvasWidth, canvasHeight, header, shared, frames)
    }

    /** A frame's delay: [num] / [den] seconds, where a [den] of 0 means 100. */
    fun delayMs(num: Int, den: Int): Int {
        val denominator = if (den == 0) 100 else den
        return GifPrep.delayMs((num * 1000L + denominator / 2) / denominator)
    }

    /**
     * [frame] as a still PNG of its own: the signature, IHDR at the frame's size, the shared
     * chunks, its image data as one IDAT, then IEND, every CRC computed afresh.
     */
    fun frameFile(data: ByteArray, info: Info, frame: Frame): ByteArray {
        val out = ByteArrayOutputStream(frame.data.sumOf { it.last + 1 - it.first } + 1024)
        out.write(SIGNATURE)
        val header = data.copyOfRange(info.header.first, info.header.last + 1)
        Bytes.putU32be(header, 0, frame.width)
        Bytes.putU32be(header, 4, frame.height)
        writeChunk(out, "IHDR", header, 0, header.size)
        for ((type, range) in info.shared) writeChunk(out, type, data, range.first, range.last + 1 - range.first)
        val imageData = ByteArrayOutputStream(frame.data.sumOf { it.last + 1 - it.first })
        for (range in frame.data) imageData.write(data, range.first, range.last + 1 - range.first)
        val bytes = imageData.toByteArray()
        writeChunk(out, "IDAT", bytes, 0, bytes.size)
        writeChunk(out, "IEND", ByteArray(0), 0, 0)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray, offset: Int, length: Int) {
        Bytes.writeU32be(out, length.toLong())
        val typeBytes = Bytes.ascii(type)
        out.write(typeBytes)
        out.write(data, offset, length)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, offset, length)
        Bytes.writeU32be(out, crc.value)
    }
}

/** An APNG drawn frame by frame, with its disposal (none / background / previous) and
 *  blending (source / over). */
internal class ApngAnimation(
    private val data: ByteArray,
    private val info: ApngContainer.Info,
    private val decoder: FrameImageDecoder,
) : CanvasAnimation() {
    override val width: Int = info.canvasWidth
    override val height: Int = info.canvasHeight
    private val canvas: FrameCanvas
    private var index = 0
    private var lastDispose = ApngContainer.DISPOSE_NONE
    private var last: ApngContainer.Frame? = null

    init {
        require(width.toLong() * height <= GifDecoder.MAX_CANVAS_PIXELS) { "PNG canvas out of range" }
        canvas = FrameCanvas(width, height)
    }

    override fun nextFrame(): AnimationFrame? {
        if (index >= info.frames.size) return null
        last?.let {
            when (lastDispose) {
                ApngContainer.DISPOSE_BACKGROUND -> canvas.clear(it.x, it.y, it.width, it.height)
                ApngContainer.DISPOSE_PREVIOUS -> canvas.restore()
                else -> Unit
            }
        }
        val frame = info.frames[index]
        // A first frame can't go back to what came before it: it clears instead.
        val dispose = if (index == 0 && frame.dispose == ApngContainer.DISPOSE_PREVIOUS) ApngContainer.DISPOSE_BACKGROUND else frame.dispose
        index++
        if (dispose == ApngContainer.DISPOSE_PREVIOUS) canvas.save()
        val image = decoder.decode(ApngContainer.frameFile(data, info, frame), frame.width.toLong() * frame.height) ?: run {
            index = info.frames.size
            return null
        }
        canvas.draw(image, frame.x, frame.y, frame.width, frame.height, blend = frame.blend == ApngContainer.BLEND_OVER)
        last = frame
        lastDispose = dispose
        return AnimationFrame(canvas.pixels, frame.delayMs)
    }
}

/**
 * A HEIF image sequence (HEICS): an ISO base media file whose 'ftyp' names a HEIF brand, with a
 * 'pict' (or 'vide') track in 'moov'. The frames' delays come from the track's 'stts'.
 */
internal object HeifSequence {
    private val BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")

    fun hasSignature(data: ByteArray): Boolean {
        if (data.size < 16 || Bytes.fourcc(data, 4) != "ftyp") return false
        val size = Bytes.u32be(data, 0)
        if (size < 16) return false
        if (Bytes.fourcc(data, 8) in BRANDS) return true
        val end = minOf(size, data.size.toLong()).toInt()
        var pos = 16
        while (pos + 4 <= end) {
            if (Bytes.fourcc(data, pos) in BRANDS) return true
            pos += 4
        }
        return false
    }

    /**
     * Each frame's delay, at most [max] of them, from the first image sequence track ('pict',
     * else 'vide'); null when [data] has none, or it can't be read.
     */
    fun frameDelays(data: ByteArray, max: Int = GifPrep.MAX_FRAMES): IntArray? {
        if (!hasSignature(data)) return null
        val moov = children(data, 0, data.size).firstOrNull { it.first == "moov" }?.second ?: return null
        var fallback: IntArray? = null
        for ((type, trak) in children(data, moov.first, moov.last + 1)) {
            if (type != "trak") continue
            val mdia = child(data, trak, "mdia") ?: continue
            val hdlr = child(data, mdia, "hdlr") ?: continue
            if (hdlr.last + 1 - hdlr.first < 12) continue
            val handler = Bytes.fourcc(data, hdlr.first + 8)
            if (handler != "pict" && handler != "vide") continue
            val delays = trackDelays(data, mdia, max) ?: continue
            if (handler == "pict") return delays
            if (fallback == null) fallback = delays
        }
        return fallback
    }

    private fun trackDelays(data: ByteArray, mdia: IntRange, max: Int): IntArray? {
        val mdhd = child(data, mdia, "mdhd") ?: return null
        if (mdhd.isEmpty()) return null
        val version = data[mdhd.first].toInt() and 0xff
        val timescaleAt = mdhd.first + if (version == 1) 20 else 12
        if (timescaleAt + 4 > mdhd.last + 1) return null
        val timescale = Bytes.u32be(data, timescaleAt)
        if (timescale <= 0) return null
        val minf = child(data, mdia, "minf") ?: return null
        val stbl = child(data, minf, "stbl") ?: return null
        val stts = child(data, stbl, "stts") ?: return null
        val end = stts.last + 1
        if (stts.first + 8 > end) return null
        val entries = Bytes.u32be(data, stts.first + 4)
        val delays = ArrayList<Int>()
        var pos = stts.first + 8
        var entry = 0L
        while (entry < entries && pos + 8 <= end && delays.size < max) {
            val count = Bytes.u32be(data, pos)
            val delta = Bytes.u32be(data, pos + 4)
            val ms = GifPrep.delayMs(delta * 1000 / timescale)
            var n = 0L
            while (n < count && delays.size < max) {
                delays.add(ms)
                n++
            }
            pos += 8
            entry++
        }
        return delays.toIntArray()
    }

    private fun child(data: ByteArray, parent: IntRange, type: String): IntRange? =
        children(data, parent.first, parent.last + 1).firstOrNull { it.first == type }?.second

    /** The boxes from [start] to [end]: type and payload. Stops at a box that runs past [end]. */
    internal fun children(data: ByteArray, start: Int, end: Int): List<Pair<String, IntRange>> {
        val boxes = ArrayList<Pair<String, IntRange>>()
        var pos = start
        while (pos.toLong() + 8 <= end) {
            var size = Bytes.u32be(data, pos)
            val type = Bytes.fourcc(data, pos + 4)
            var headerSize = 8
            if (size == 1L) {
                if (pos.toLong() + 16 > end) break
                if (Bytes.u32be(data, pos + 8) != 0L) break // past 4 GB
                size = Bytes.u32be(data, pos + 12)
                headerSize = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < headerSize || pos + size > end) break
            boxes.add(type to (pos + headerSize until (pos + size).toInt()))
            pos = (pos + size).toInt()
        }
        return boxes
    }
}

/**
 * A HEIF image sequence's frames, as the platform decodes them ([MediaMetadataRetriever] reads
 * the sequence as a video track, `getFrameAtIndex`), with the delays [HeifSequence] read.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal class HeifSequenceSource private constructor(
    private val retriever: MediaMetadataRetriever,
    override val width: Int,
    override val height: Int,
    private val delays: IntArray,
) : AnimationSource {
    private var index = 0

    override fun nextBitmap(checkAlpha: Boolean): SourceFrame? {
        if (index >= delays.size) return null
        val bitmap = try {
            retriever.getFrameAtIndex(index)
        } catch (e: Exception) {
            null
        } ?: run {
            index = delays.size
            return null
        }
        val delay = delays[index++]
        return SourceFrame(bitmap, delay, checkAlpha && bitmap.hasAlpha() && hasSeeThroughPixel(bitmap))
    }

    override fun close() {
        try {
            retriever.release()
        } catch (e: Exception) {
            // already released
        }
    }

    private fun hasSeeThroughPixel(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) < 0x80 }) return true
        }
        return false
    }

    private class ByteArraySource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= data.size) return -1
            val count = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }

    companion object {
        /** Whether the platform reads more than one frame of the sequence in [data]. */
        fun canRead(data: ByteArray): Boolean = try {
            open(data)?.close() != null
        } catch (e: IllegalArgumentException) {
            true // a sequence, only too big to read
        }

        /**
         * The sequence in [data], or null when it has none or the platform can't read its frames.
         * @throws IllegalArgumentException when its frames are past [GifDecoder.MAX_CANVAS_PIXELS].
         */
        fun open(data: ByteArray): HeifSequenceSource? {
            val delays = HeifSequence.frameDelays(data) ?: return null
            if (delays.size < 2) return null
            val retriever = MediaMetadataRetriever()
            var width = 0
            var height = 0
            var count = 0
            try {
                retriever.setDataSource(ByteArraySource(data))
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                    count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                }
            } catch (e: Exception) {
                count = 0
            }
            if (count < 2 || width <= 0 || height <= 0) {
                runCatching { retriever.release() }
                return null
            }
            if (width.toLong() * height > GifDecoder.MAX_CANVAS_PIXELS) {
                runCatching { retriever.release() }
                throw IllegalArgumentException("HEIF frames out of range")
            }
            return HeifSequenceSource(retriever, width, height, delays.copyOf(minOf(count, delays.size)))
        }
    }
}
