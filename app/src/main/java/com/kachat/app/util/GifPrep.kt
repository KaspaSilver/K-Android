package com.kachat.app.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Animated GIFs sent on chain (iOS d7c56280 `GIFPrep`, MESSAGING.md "GIFs"): picked with the
 * composer's GIF tile (the picker offers animated images), or an animation picked with Photo.
 * Anything with more than one frame counts, as ImageIO reads it on iOS: a GIF, an animated WebP,
 * an APNG or a HEIF image sequence ([AnimationSources]). Each [PhotoTier] buys the largest GIF
 * that fits its byte budget: re-encoded at a smaller long edge, then with every second or third
 * frame (a kept frame stays up for the dropped ones' time), so it still plays at its own speed.
 * Re-encoding also leaves out every bit of metadata. The result is always `image/gif`, which
 * every platform decodes.
 *
 * Android has no GIF encoder and no frame-by-frame GIF decoder below API 28, so both are here
 * ([GifDecoder], [GifEncoder]). A chat bubble plays only GIFs (iOS 5b7c772c `isSafeToAnimate`);
 * what is sent from here is always one.
 */
object GifPrep {
    class NotAnimatedException : Exception("not an animated GIF")

    /** The GIF doesn't fit the budget it was asked for at any size or frame rate. */
    class TooLongException : Exception("this GIF is too long for that quality")

    /** Longest edges tried, largest first (never larger than the GIF itself). */
    val EDGES = intArrayOf(480, 400, 360, 320, 280, 240, 200, 160, 128, 96)
    private val FRAME_STEPS = intArrayOf(1, 2, 3)

    /** What is read of a long GIF: a few seconds is what fits on chain anyway. */
    const val MAX_FRAMES = 300
    private const val MAX_MILLIS = 30_000L

    /**
     * The largest GIF a chat plays (iOS 5b7c772c): playing decodes every frame at full size, so
     * a file of a few KB declaring huge frames could ask for gigabytes. GIFs sent from here are at
     * most [EDGES]`[0]` (480 px) and [MAX_FRAMES], well inside.
     */
    const val MAX_PLAY_EDGE = 1024
    const val MAX_PLAY_FRAMES = MAX_FRAMES

    /** The MIME type an on-chain GIF goes as. */
    const val MIME_TYPE = "image/gif"

    /** A single on-chain GIF's file name (a multi-part one's parts: [MediaPartName.photo]). */
    const val FILE_NAME = "animation.gif"

    /**
     * More than one frame (iOS `isAnimated`): an animated GIF, WebP, PNG (APNG) or HEIF image
     * sequence. Reads only the container, never a pixel (a HEIF sequence also asks the platform
     * whether it reads its frames). Call it off the main thread.
     */
    fun isAnimated(data: ByteArray): Boolean = AnimationSources.isAnimated(data)

    /** Whether a file starting with [head] (its first [SNIFF_BYTES]) may be one [isAnimated]
     *  reads, before reading it whole. */
    fun mayBeAnimated(head: ByteArray): Boolean = AnimationSources.hasAnimatableSignature(head)

    /** How much of a file [mayBeAnimated] looks at. */
    const val SNIFF_BYTES = 64

    /** The types the GIF tile's picker offers (iOS: "animated images"); [isAnimated] decides. */
    val PICKER_MIME_TYPES = arrayOf(MIME_TYPE, "image/webp", "image/png", "image/heic", "image/heif")

    /** Whether [data] starts like a GIF ("GIF87a" / "GIF89a"), whatever MIME type it was sent as. */
    fun isGif(data: ByteArray): Boolean = GifScan.hasSignature(data)

    /**
     * A real GIF (whatever MIME type the message claims), animated and within [MAX_PLAY_EDGE]
     * and [MAX_PLAY_FRAMES] - the canvas and every frame (iOS 5b7c772c `isSafeToAnimate`).
     * Reads only headers, but call it off the main thread. Anything else shows its still picture.
     */
    fun isSafeToAnimate(data: ByteArray): Boolean {
        val info = GifScan.scan(data, stopAfterFrames = MAX_PLAY_FRAMES + 1) ?: return false
        if (info.frameCount !in 2..MAX_PLAY_FRAMES) return false
        if (maxOf(info.canvasWidth, info.canvasHeight) > MAX_PLAY_EDGE) return false
        return info.framesWithinPlayLimits
    }

    /**
     * The GIF each budget buys, keyed by budget (iOS `prepareForTiers`). A budget nothing fits (a
     * very long GIF at the smallest size and every third frame) has no entry. Heavy: call it off
     * the main thread.
     */
    fun prepareForTiers(data: ByteArray, budgets: Collection<Int>): Map<Int, ByteArray> {
        val source = try {
            AnimationSources.open(data)
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw NotAnimatedException()
        val largest = EDGES[0]
        val canvasLong = maxOf(source.width, source.height)
        val readScale = if (canvasLong > largest) largest.toFloat() / canvasLong else 1f
        val readWidth = (source.width * readScale).toInt().coerceAtLeast(1)
        val readHeight = (source.height * readScale).toInt().coerceAtLeast(1)

        val frames = ArrayList<Bitmap>()
        val delays = ArrayList<Int>()
        var hasAlpha = false
        try {
            var millis = 0L
            while (frames.size < MAX_FRAMES) {
                val frame = source.nextBitmap(checkAlpha = !hasAlpha) ?: break
                if (frame.hasAlpha) hasAlpha = true
                val full = frame.bitmap
                // Every frame at the same size, whatever size the platform decoded it at.
                val read = if (full.width != readWidth || full.height != readHeight) {
                    Bitmap.createScaledBitmap(full, readWidth, readHeight, true).also { if (it !== full) full.recycle() }
                } else {
                    full
                }
                frames.add(read)
                delays.add(frame.delayMs)
                millis += frame.delayMs
                if (millis >= MAX_MILLIS) break
            }
            if (frames.size <= 1) throw NotAnimatedException()

            val ownEdge = maxOf(frames[0].width, frames[0].height)
            val top = minOf(ownEdge, largest)
            val candidateEdges = listOf(top) + EDGES.filter { it < top }

            val open = budgets.toMutableSet()
            val results = HashMap<Int, ByteArray>()
            // (edge, frames kept, bytes) of every encode so far: predicts the next ones
            val measured = ArrayList<Triple<Int, Int, Int>>()
            for (edge in candidateEdges) {
                if (open.isEmpty()) return results
                val scaled = if (edge >= ownEdge) frames else frames.map { scale(it, edge) }
                try {
                    for (step in FRAME_STEPS) {
                        if (open.isEmpty()) return results
                        val kept = ArrayList<Bitmap>()
                        val keptDelays = ArrayList<Int>()
                        var start = 0
                        while (start < frames.size) {
                            kept.add(scaled[start])
                            keptDelays.add(delays.subList(start, minOf(start + step, frames.size)).sum())
                            start += step
                        }
                        if (kept.size <= 1) continue
                        // A size this one can't come near (twice the largest budget still open)
                        // isn't encoded: the bytes scale with the pixels and frames. Predicted
                        // only from as large a GIF with as many frames or more, which a smaller
                        // one undershoots (fewer, further-apart frames differ more), so a size
                        // that could fit is never skipped.
                        val biggest = open.maxOrNull() ?: 0
                        val hopeless = measured.any { (e, n, bytes) ->
                            e >= edge && n >= kept.size &&
                                bytes.toDouble() * edge * edge / (e.toDouble() * e) * kept.size / n > biggest * 2.0
                        }
                        if (hopeless) continue
                        val encoded = GifEncoder.encode(kept, keptDelays, hasAlpha) ?: continue
                        measured.add(Triple(edge, kept.size, encoded.size))
                        for (budget in open.toList()) {
                            if (encoded.size <= budget) {
                                results[budget] = encoded
                                open.remove(budget)
                            }
                        }
                    }
                } finally {
                    if (scaled !== frames) scaled.forEach { it.recycle() }
                }
            }
            return results
        } finally {
            frames.forEach { it.recycle() }
            source.close()
        }
    }

    private fun scale(image: Bitmap, edge: Int): Bitmap {
        val long = maxOf(image.width, image.height)
        val factor = edge.toFloat() / long
        val width = Math.round(image.width * factor).coerceAtLeast(1)
        val height = Math.round(image.height * factor).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(image, width, height, true)
    }

    /** How long a frame shows, as browsers read it (iOS `frameDelay`): under 20 ms means 100 ms. */
    internal fun frameDelayMs(centiseconds: Int): Int = if (centiseconds < 2) 100 else centiseconds * 10

    /** The same for a delay in milliseconds (WebP, APNG, HEIF): under 20 ms means 100 ms. Capped
     *  at the longest a GIF frame can show (655.35 s). */
    internal fun delayMs(millis: Long): Int = if (millis < 20) 100 else millis.coerceAtMost(655_350L).toInt()
}

/** A multi-part photo's part file name (iOS d7c56280 `MediaPartName`): `photo-2of6.jpg`, or
 *  `animation-2of6.gif` for a GIF. */
object MediaPartName {
    fun photo(index: Int, count: Int, mimeType: String): String =
        if (mimeType.lowercase() == GifPrep.MIME_TYPE) "animation-${index}of$count.gif" else "photo-${index}of$count.jpg"
}

/** The block structure of a GIF, read without decoding a pixel. */
internal object GifScan {
    class Info(
        val canvasWidth: Int,
        val canvasHeight: Int,
        /** Frames seen (up to the scan's stop). */
        val frameCount: Int,
        /** Every frame seen has a size, and none is past [GifPrep.MAX_PLAY_EDGE]. */
        val framesWithinPlayLimits: Boolean,
    )

    fun hasSignature(data: ByteArray): Boolean =
        data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
            data[3] == '8'.code.toByte() && (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) && data[5] == 'a'.code.toByte()

    /** Null when [data] isn't a GIF. A file cut short counts the frames it holds whole. */
    fun scan(data: ByteArray, stopAfterFrames: Int): Info? {
        if (!hasSignature(data) || data.size < 13) return null
        val width = u16(data, 6)
        val height = u16(data, 8)
        val packed = data[10].toInt() and 0xff
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 0x07) + 1))
        var frames = 0
        var withinLimits = true
        while (pos < data.size && frames < stopAfterFrames) {
            when (data[pos].toInt() and 0xff) {
                0x21 -> {
                    if (pos + 2 > data.size) break
                    pos = skipSubBlocks(data, pos + 2) ?: break
                }
                0x2C -> {
                    if (pos + 10 > data.size) break
                    val frameWidth = u16(data, pos + 5)
                    val frameHeight = u16(data, pos + 7)
                    val framePacked = data[pos + 9].toInt() and 0xff
                    pos += 10
                    if (framePacked and 0x80 != 0) pos += 3 * (1 shl ((framePacked and 0x07) + 1))
                    if (pos >= data.size) break
                    pos = skipSubBlocks(data, pos + 1) ?: break // the LZW code size, then the data
                    frames++
                    if (frameWidth <= 0 || frameHeight <= 0 || maxOf(frameWidth, frameHeight) > GifPrep.MAX_PLAY_EDGE) withinLimits = false
                }
                else -> break // the trailer (0x3B), or something that isn't GIF
            }
        }
        return Info(width, height, frames, withinLimits)
    }

    /** The position after a run of data sub-blocks starting at [start], or null when cut short. */
    private fun skipSubBlocks(data: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < data.size) {
            val size = data[pos].toInt() and 0xff
            pos += 1
            if (size == 0) return pos
            pos += size
        }
        return null
    }

    fun u16(data: ByteArray, at: Int): Int = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
}

/**
 * Decodes a GIF one frame at a time onto one canvas (ARGB), applying each frame's disposal, so a
 * long GIF costs one frame of memory, not all of them. [nextFrame] hands back the same canvas each
 * time: copy it before asking for the next one.
 *
 * @throws IllegalArgumentException when [data] isn't a GIF, or its canvas is past [MAX_CANVAS_PIXELS].
 */
internal class GifDecoder(private val data: ByteArray) : CanvasAnimation() {
    override val width: Int
    override val height: Int
    private val canvas: IntArray
    private val globalTable: IntArray?
    private var pos: Int
    private var saved: IntArray? = null

    // The last frame's disposal, done before the next frame draws.
    private var lastDisposal = 0
    private var lastX = 0
    private var lastY = 0
    private var lastW = 0
    private var lastH = 0

    // LZW state, reused frame to frame
    private val prefix = ShortArray(MAX_CODES)
    private val suffix = ByteArray(MAX_CODES)
    private val pixelStack = ByteArray(MAX_CODES + 1)
    private var indices = ByteArray(0)

    init {
        require(GifScan.hasSignature(data) && data.size >= 13) { "not a GIF" }
        width = GifScan.u16(data, 6)
        height = GifScan.u16(data, 8)
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_CANVAS_PIXELS) { "GIF canvas out of range" }
        val packed = data[10].toInt() and 0xff
        pos = 13
        globalTable = if (packed and 0x80 != 0) readTable(1 shl ((packed and 0x07) + 1)) else null
        canvas = IntArray(width * height)
    }

    /** The next frame drawn onto the canvas, or null at the end (or where the file is cut short). */
    override fun nextFrame(): AnimationFrame? {
        var delayCs = 0
        var disposal = 0
        var transparent = -1
        while (pos < data.size) {
            when (data[pos].toInt() and 0xff) {
                0x21 -> {
                    if (pos + 2 > data.size) return null
                    val label = data[pos + 1].toInt() and 0xff
                    pos += 2
                    if (label == 0xF9 && pos + 5 <= data.size && (data[pos].toInt() and 0xff) >= 4) {
                        val gcePacked = data[pos + 1].toInt() and 0xff
                        disposal = (gcePacked shr 2) and 0x07
                        delayCs = GifScan.u16(data, pos + 2)
                        transparent = if (gcePacked and 0x01 != 0) data[pos + 4].toInt() and 0xff else -1
                    }
                    if (!skipSubBlocks()) return null
                }
                0x2C -> {
                    if (pos + 10 > data.size) return null
                    val x = GifScan.u16(data, pos + 1)
                    val y = GifScan.u16(data, pos + 3)
                    val w = GifScan.u16(data, pos + 5)
                    val h = GifScan.u16(data, pos + 7)
                    val framePacked = data[pos + 9].toInt() and 0xff
                    pos += 10
                    val table = if (framePacked and 0x80 != 0) readTable(1 shl ((framePacked and 0x07) + 1)) else globalTable
                    if (pos >= data.size) return null
                    val minCodeSize = data[pos].toInt() and 0xff
                    pos += 1
                    disposePrevious()
                    if (disposal == 3) saved = canvas.copyOf()
                    val pixelCount = w * h
                    if (indices.size < pixelCount) indices = ByteArray(pixelCount)
                    if (minCodeSize in 1..11 && pixelCount > 0) {
                        decodeLzw(minCodeSize, pixelCount)
                        if (table != null) draw(x, y, w, h, framePacked and 0x40 != 0, table, transparent)
                    } else if (!skipSubBlocks()) {
                        return null
                    }
                    lastDisposal = disposal
                    lastX = x
                    lastY = y
                    lastW = w
                    lastH = h
                    return AnimationFrame(canvas, GifPrep.frameDelayMs(delayCs))
                }
                else -> return null
            }
        }
        return null
    }

    private fun readTable(entries: Int): IntArray? {
        if (pos + 3 * entries > data.size) {
            pos = data.size
            return null
        }
        val table = IntArray(entries)
        for (i in 0 until entries) {
            val r = data[pos].toInt() and 0xff
            val g = data[pos + 1].toInt() and 0xff
            val b = data[pos + 2].toInt() and 0xff
            table[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            pos += 3
        }
        return table
    }

    private fun disposePrevious() {
        when (lastDisposal) {
            // Restore to background: transparent, as browsers do.
            2 -> {
                val x0 = lastX.coerceIn(0, width)
                val y0 = lastY.coerceIn(0, height)
                val x1 = (lastX + lastW).coerceIn(0, width)
                val y1 = (lastY + lastH).coerceIn(0, height)
                for (row in y0 until y1) java.util.Arrays.fill(canvas, row * width + x0, row * width + x1, 0)
            }
            3 -> saved?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
        }
        lastDisposal = 0
    }

    private fun draw(x: Int, y: Int, w: Int, h: Int, interlaced: Boolean, table: IntArray, transparent: Int) {
        var pass = 0
        var step = if (interlaced) 8 else 1
        var row = 0
        for (sourceRow in 0 until h) {
            // Interlaced rows come in four passes: every 8th from 0, every 8th from 4, every 4th
            // from 2, every 2nd from 1.
            while (interlaced && row >= h && pass < 3) {
                pass++
                when (pass) {
                    1 -> { row = 4; step = 8 }
                    2 -> { row = 2; step = 4 }
                    else -> { row = 1; step = 2 }
                }
            }
            val canvasRow = y + (if (interlaced) row else sourceRow)
            row += step
            if (canvasRow < 0 || canvasRow >= height) continue
            val base = sourceRow * w
            for (column in 0 until w) {
                val canvasColumn = x + column
                if (canvasColumn >= width) break
                val index = indices[base + column].toInt() and 0xff
                if (index == transparent || index >= table.size) continue
                canvas[canvasRow * width + canvasColumn] = table[index]
            }
        }
    }

    // Data sub-blocks, read a byte at a time by the LZW decoder.
    private var blockRemaining = 0
    private var blocksEnded = false

    private fun nextDataByte(): Int {
        if (blocksEnded) return -1
        if (blockRemaining == 0) {
            if (pos >= data.size) {
                blocksEnded = true
                return -1
            }
            blockRemaining = data[pos].toInt() and 0xff
            pos++
            if (blockRemaining == 0) {
                blocksEnded = true
                return -1
            }
        }
        if (pos >= data.size) {
            blocksEnded = true
            return -1
        }
        blockRemaining--
        return data[pos++].toInt() and 0xff
    }

    private fun skipSubBlocks(): Boolean {
        while (pos < data.size) {
            val size = data[pos].toInt() and 0xff
            pos += 1
            if (size == 0) return true
            pos += size
        }
        return false
    }

    /** The frame's color indices into [indices]; anything missing stays index 0. */
    private fun decodeLzw(minCodeSize: Int, pixelCount: Int) {
        blockRemaining = 0
        blocksEnded = false
        val clear = 1 shl minCodeSize
        val endOfInformation = clear + 1
        var available = clear + 2
        var oldCode = -1
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        for (code in 0 until clear) {
            prefix[code] = 0
            suffix[code] = code.toByte()
        }
        var datum = 0
        var bits = 0
        var first = 0
        var top = 0
        var written = 0
        while (written < pixelCount) {
            if (top == 0) {
                if (bits < codeSize) {
                    val next = nextDataByte()
                    if (next < 0) break
                    datum = datum or (next shl bits)
                    bits += 8
                    continue
                }
                var code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize
                if (code > available || code == endOfInformation) break
                if (code == clear) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = clear + 2
                    oldCode = -1
                    continue
                }
                if (oldCode == -1) {
                    pixelStack[top++] = suffix[code]
                    oldCode = code
                    first = code
                    continue
                }
                val inCode = code
                if (code >= available) {
                    pixelStack[top++] = first.toByte()
                    code = oldCode
                }
                while (code >= clear) {
                    pixelStack[top++] = suffix[code]
                    code = prefix[code].toInt()
                    if (top >= MAX_CODES) break
                }
                first = suffix[code].toInt() and 0xff
                pixelStack[top++] = first.toByte()
                if (available < MAX_CODES) {
                    prefix[available] = oldCode.toShort()
                    suffix[available] = first.toByte()
                    available++
                    if (available and codeMask == 0 && available < MAX_CODES) {
                        codeSize++
                        codeMask += available
                    }
                }
                oldCode = inCode
            }
            top--
            indices[written++] = pixelStack[top]
        }
        java.util.Arrays.fill(indices, written, pixelCount, 0)
        // Whatever is left of the image data, up to its terminator.
        if (!blocksEnded) {
            if (blockRemaining > 0) pos += blockRemaining
            blockRemaining = 0
            skipSubBlocks()
        }
    }

    companion object {
        private const val MAX_CODES = 4096

        /** Past this the canvas isn't decoded at all: one canvas of it is 64 MB. The same limit
         *  holds for every animation [AnimationSources] reads. */
        const val MAX_CANVAS_PIXELS = 16_000_000L
    }
}

/**
 * Writes a looping GIF of the given frames and nothing else: no metadata. One palette for the
 * whole GIF (median cut over every frame, 255 colors plus transparency), and after the first frame
 * only the rectangle that changed, its unchanged pixels transparent; a frame identical to the last
 * adds its time to it. A GIF with transparency is written whole, frame by frame, each cleared
 * after it shows.
 */
internal object GifEncoder {
    private const val TRANSPARENT = 255

    fun encode(frames: List<Bitmap>, delaysMs: List<Int>, hasAlpha: Boolean): ByteArray? {
        if (frames.isEmpty()) return null
        val width = frames[0].width
        val height = frames[0].height
        if (frames.any { it.width != width || it.height != height }) return null
        val count = width * height
        val pixels = IntArray(count)

        // One palette from every frame's colors.
        val histogram = IntArray(BINS)
        val sumR = LongArray(BINS)
        val sumG = LongArray(BINS)
        val sumB = LongArray(BINS)
        for (frame in frames) {
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            for (pixel in pixels) {
                if (hasAlpha && (pixel ushr 24) < 0x80) continue
                val bin = bin(pixel)
                histogram[bin]++
                sumR[bin] += ((pixel shr 16) and 0xff).toLong()
                sumG[bin] += ((pixel shr 8) and 0xff).toLong()
                sumB[bin] += (pixel and 0xff).toLong()
            }
        }
        val lookup = IntArray(BINS)
        val palette = medianCut(histogram, sumR, sumG, sumB, maxColors = 255, lookup = lookup)

        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeU16(out, width)
        writeU16(out, height)
        out.write(0x80 or 0x70 or 0x07) // a 256-entry global table, 8-bit color
        out.write(0)
        out.write(0)
        for (i in 0 until 256) {
            val color = if (i < palette.size) palette[i] else 0
            out.write((color shr 16) and 0xff)
            out.write((color shr 8) and 0xff)
            out.write(color and 0xff)
        }
        // Loop for ever (NETSCAPE2.0, loop count 0).
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))

        val displayed = ByteArray(count) // what the canvas shows now, as palette indices
        val current = ByteArray(count)
        val frameIndices = ByteArray(count)
        val lzw = LzwWriter()
        // One frame held back: an identical next frame adds its time to it.
        var pendingDelay = 0
        var pendingRect: IntArray? = null
        var pendingData: ByteArray? = null

        fun flush() {
            val rect = pendingRect ?: return
            val data = pendingData ?: return
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
            val disposal = if (hasAlpha) 2 else 1
            out.write((disposal shl 2) or 0x01)
            writeU16(out, ((pendingDelay + 5) / 10).coerceIn(2, 65_535))
            out.write(TRANSPARENT)
            out.write(0)
            out.write(0x2C)
            writeU16(out, rect[0])
            writeU16(out, rect[1])
            writeU16(out, rect[2])
            writeU16(out, rect[3])
            out.write(0) // no local table, not interlaced
            out.write(data)
            pendingRect = null
            pendingData = null
        }

        for ((index, frame) in frames.withIndex()) {
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in 0 until count) {
                val pixel = pixels[i]
                current[i] = if (hasAlpha && (pixel ushr 24) < 0x80) TRANSPARENT.toByte() else lookup[bin(pixel)].toByte()
            }
            var x0 = 0
            var y0 = 0
            var x1 = width - 1
            var y1 = height - 1
            if (index > 0 && !hasAlpha) {
                // Only the rectangle that changed.
                x0 = width; y0 = height; x1 = -1; y1 = -1
                for (y in 0 until height) {
                    val rowStart = y * width
                    for (x in 0 until width) {
                        if (current[rowStart + x] != displayed[rowStart + x]) {
                            if (x < x0) x0 = x
                            if (x > x1) x1 = x
                            if (y < y0) y0 = y
                            if (y > y1) y1 = y
                        }
                    }
                }
                if (x1 < 0) {
                    pendingDelay += delaysMs[index]
                    continue
                }
            }
            flush()
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            var n = 0
            for (y in y0..y1) {
                val rowStart = y * width
                for (x in x0..x1) {
                    val value = current[rowStart + x]
                    frameIndices[n++] = if (index > 0 && !hasAlpha && value == displayed[rowStart + x]) TRANSPARENT.toByte() else value
                }
            }
            System.arraycopy(current, 0, displayed, 0, count)
            pendingRect = intArrayOf(x0, y0, w, h)
            pendingData = lzw.encode(frameIndices, n, minCodeSize = 8)
            pendingDelay = delaysMs[index]
        }
        flush()
        out.write(0x3B)
        return out.toByteArray()
    }

    private const val BINS = 1 shl 15

    private fun bin(pixel: Int): Int =
        (((pixel shr 19) and 0x1f) shl 10) or (((pixel shr 11) and 0x1f) shl 5) or ((pixel shr 3) and 0x1f)

    private fun component(bin: Int, axis: Int): Int = when (axis) {
        0 -> (bin shr 10) and 0x1f
        1 -> (bin shr 5) and 0x1f
        else -> bin and 0x1f
    }

    /**
     * Up to [maxColors] colors (RGB) for the colors in [histogram] (5 bits a channel): median
     * cut, each color its box's average. [lookup] gets each bin's palette index.
     */
    internal fun medianCut(histogram: IntArray, sumR: LongArray, sumG: LongArray, sumB: LongArray, maxColors: Int, lookup: IntArray): IntArray {
        val bins = (0 until BINS).filter { histogram[it] > 0 }.toIntArray()
        if (bins.isEmpty()) return intArrayOf(0)
        // Boxes as [start, end) ranges of bins.
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        starts.add(0)
        ends.add(bins.size)
        val sorted = IntArray(bins.size)
        while (starts.size < maxColors) {
            var best = -1
            var bestScore = 0L
            var bestAxis = 0
            for (box in starts.indices) {
                val start = starts[box]
                val end = ends[box]
                if (end - start < 2) continue
                var population = 0L
                val low = intArrayOf(31, 31, 31)
                val high = intArrayOf(0, 0, 0)
                for (i in start until end) {
                    val bin = bins[i]
                    population += histogram[bin]
                    for (axis in 0..2) {
                        val value = component(bin, axis)
                        if (value < low[axis]) low[axis] = value
                        if (value > high[axis]) high[axis] = value
                    }
                }
                var axis = 0
                for (a in 1..2) if (high[a] - low[a] > high[axis] - low[axis]) axis = a
                val score = population * (high[axis] - low[axis] + 1)
                if (score > bestScore) {
                    bestScore = score
                    best = box
                    bestAxis = axis
                }
            }
            if (best < 0) break
            val start = starts[best]
            val end = ends[best]
            // Counting sort of the box's bins along the axis.
            val buckets = IntArray(33)
            for (i in start until end) buckets[component(bins[i], bestAxis) + 1]++
            for (v in 1..32) buckets[v] += buckets[v - 1]
            for (i in start until end) {
                val bin = bins[i]
                sorted[start + buckets[component(bin, bestAxis)]++] = bin
            }
            System.arraycopy(sorted, start, bins, start, end - start)
            var total = 0L
            for (i in start until end) total += histogram[bins[i]]
            var running = 0L
            var split = start + 1
            for (i in start until end - 1) {
                running += histogram[bins[i]]
                split = i + 1
                if (running * 2 >= total) break
            }
            starts[best] = start
            ends[best] = split
            starts.add(split)
            ends.add(end)
        }
        val palette = IntArray(starts.size)
        for (box in starts.indices) {
            var population = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in starts[box] until ends[box]) {
                val bin = bins[i]
                population += histogram[bin]
                r += sumR[bin]
                g += sumG[bin]
                b += sumB[bin]
                lookup[bin] = box
            }
            palette[box] = if (population == 0L) 0 else
                (((r / population).toInt() and 0xff) shl 16) or (((g / population).toInt() and 0xff) shl 8) or ((b / population).toInt() and 0xff)
        }
        return palette
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }
}

/**
 * GIF's variable-width LZW (12 bits at most, a clear code when the table fills), into data
 * sub-blocks: the minimum code size byte, the blocks, and the zero-length terminator.
 */
internal class LzwWriter {
    private val hashKeys = IntArray(HASH_SIZE)
    private val hashCodes = IntArray(HASH_SIZE)

    private lateinit var out: ByteArrayOutputStream
    private val block = ByteArray(255)
    private var blockLength = 0
    private var accumulator = 0
    private var accumulatedBits = 0
    private var bitCount = 0
    private var maxCode = 0
    private var initialBits = 0
    private var clearCode = 0
    private var nextCode = 0
    private var clearPending = false

    fun encode(indices: ByteArray, count: Int, minCodeSize: Int): ByteArray {
        out = ByteArrayOutputStream(count / 2 + 16)
        out.write(minCodeSize)
        blockLength = 0
        accumulator = 0
        accumulatedBits = 0
        initialBits = minCodeSize + 1
        bitCount = initialBits
        maxCode = (1 shl bitCount) - 1
        clearCode = 1 shl minCodeSize
        val endOfInformation = clearCode + 1
        nextCode = clearCode + 2
        clearPending = false
        java.util.Arrays.fill(hashKeys, 0)
        writeCode(clearCode)
        if (count > 0) {
            var prefix = indices[0].toInt() and 0xff
            for (i in 1 until count) {
                val symbol = indices[i].toInt() and 0xff
                val key = ((prefix shl 8) or symbol) + 1
                var slot = (key * HASH_MULTIPLIER) ushr (32 - HASH_BITS)
                var found = -1
                while (hashKeys[slot] != 0) {
                    if (hashKeys[slot] == key) {
                        found = hashCodes[slot]
                        break
                    }
                    slot = (slot + 1) and (HASH_SIZE - 1)
                }
                if (found >= 0) {
                    prefix = found
                    continue
                }
                writeCode(prefix)
                if (nextCode < MAX_CODES) {
                    hashKeys[slot] = key
                    hashCodes[slot] = nextCode++
                } else {
                    // The table is full: start over.
                    java.util.Arrays.fill(hashKeys, 0)
                    nextCode = clearCode + 2
                    clearPending = true
                    writeCode(clearCode)
                }
                prefix = symbol
            }
            writeCode(prefix)
        }
        writeCode(endOfInformation)
        if (accumulatedBits > 0) {
            pushByte(accumulator and 0xff)
            accumulator = 0
            accumulatedBits = 0
        }
        flushBlock()
        out.write(0)
        return out.toByteArray()
    }

    private fun writeCode(code: Int) {
        accumulator = accumulator or (code shl accumulatedBits)
        accumulatedBits += bitCount
        while (accumulatedBits >= 8) {
            pushByte(accumulator and 0xff)
            accumulator = accumulator ushr 8
            accumulatedBits -= 8
        }
        // The code width grows when the next code to assign no longer fits, as the decoder reads it.
        if (clearPending) {
            bitCount = initialBits
            maxCode = (1 shl bitCount) - 1
            clearPending = false
        } else if (nextCode > maxCode && bitCount < 12) {
            bitCount++
            maxCode = if (bitCount == 12) MAX_CODES else (1 shl bitCount) - 1
        }
    }

    private fun pushByte(value: Int) {
        block[blockLength++] = value.toByte()
        if (blockLength == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength == 0) return
        out.write(blockLength)
        out.write(block, 0, blockLength)
        blockLength = 0
    }

    companion object {
        private const val MAX_CODES = 4096
        private const val HASH_BITS = 13
        private const val HASH_SIZE = 1 shl HASH_BITS
        private const val HASH_MULTIPLIER = -0x61c88647 // 0x9E3779B9
    }
}
