package com.kachat.app.util

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.min

/**
 * Multi-frame (animated) QR chunking. K writes v1.0.7 frames (below), which every KasSigner reads,
 * and reads both those and v1.1's ([Accumulator], [V11]). The v1.0.7 scheme matches KasSigner's
 * own raw-byte scheme exactly — verified against `kassee/src/qr.rs` and the device firmware's
 * `process_multiframe`/`is_multiframe` (`bootloader/src/handlers/camera_loop.rs`). Fully
 * symmetric: the same chunker/dechunker works whether KaChat is sending an unsigned KSPT to the
 * device or receiving the signed one back. Same wire format as the iPhone's `QrFrameChunker.swift`.
 *
 * ```
 * <=134 bytes total: no wrapper at all — the raw payload is a single QR frame.
 * otherwise: [frameNum(1)] [totalFrames(1)] [fragLen(1)] [fragment(fragLen bytes)], balanced
 *            across frames (not simply 106 bytes each except possibly the last), capped at
 *            64 frames, each frame zero-padded to a minimum of 20 bytes for reliable scanning.
 * ```
 */
object QrFrameChunker {
    const val SINGLE_FRAME_MAX = 134
    const val MAX_FRAME_DATA = 106
    const val MAX_FRAMES = 64
    private const val MIN_FRAME_SIZE = 20
    private const val HEADER_SIZE = 3

    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.size <= SINGLE_FRAME_MAX) return listOf(data)

        val totalFrames = ceil(data.size.toDouble() / MAX_FRAME_DATA).toInt()
        require(totalFrames <= MAX_FRAMES) { "Payload too large to chunk into QR frames (${data.size} bytes)" }
        val perFrame = ceil(data.size.toDouble() / totalFrames).toInt()

        val frames = mutableListOf<ByteArray>()
        var offset = 0
        for (frameNum in 0 until totalFrames) {
            val end = min(offset + perFrame, data.size)
            val fragment = data.copyOfRange(offset, end)
            require(fragment.size <= 255) { "Chunk fragment overflowed a single byte length field" }
            val frame = ByteArray(HEADER_SIZE + fragment.size)
            frame[0] = frameNum.toByte()
            frame[1] = totalFrames.toByte()
            frame[2] = fragment.size.toByte()
            fragment.copyInto(frame, HEADER_SIZE)
            frames.add(if (frame.size < MIN_FRAME_SIZE) frame.copyOf(MIN_FRAME_SIZE) else frame)
            offset = end
        }
        return frames
    }

    /**
     * Reassembles frames scanned in any order (or with duplicates/retries) as the camera happens
     * to catch them mid-animation. [isComplete] identifies a payload that arrived as a single,
     * unwrapped QR frame (no multi-frame header) — pass a magic-byte check like
     * [KsptCodec.looksLikeKspt] for whatever payload type is being scanned.
     *
     * Two frame layouts are read, never mixed in one set:
     * - v1.0.7's `[index, total, fragLen, fragment]` (what K writes, and what a KasSigner shows
     *   for a single-key 0x01 request's reply);
     * - v1.1's (KasSigner `crates/kassigner-protocol/src/frame.rs`), which the unit uses for
     *   every other reply, every 0x06 (quantum) one included, even when it fits one code:
     *   `[0x1B, format, index, total, tag]`, frame 0 three more digest bytes, then the
     *   fragment. The fragments in index order are the payload, and the first four bytes of its
     *   SHA-256 must be the digest frame 0 named, so frames of two transmissions are never
     *   joined into a transaction nobody sent.
     *
     * A v1.0.7 frame can't be taken for a v1.1 one: index 0x1B needs a total of 28 or more,
     * and no v1.1 format byte is in 28...64.
     *
     * [v11Format] is the v1.1 format byte to collect ([V11.FORMAT_KSPT] for a signed KSPT); v1.1
     * frames of any other format are something else in view and are ignored.
     */
    class Accumulator(
        private val isComplete: (ByteArray) -> Boolean,
        private val v11Format: Int = V11.FORMAT_KSPT,
    ) {
        private enum class Layout { V107, V11 }

        private var layout: Layout? = null
        private var totalFrames: Int? = null
        private val received = mutableMapOf<Int, ByteArray>()
        // v1.1 only: the stream's tag (the digest's first byte, in every frame), and the whole
        // digest once frame 0 has been seen.
        private var tag: Int? = null
        private var digest: ByteArray? = null

        /** Feed one scanned frame's raw bytes. Returns the reassembled payload once every frame
         *  has arrived (and, for v1.1, matches its digest), else null. */
        fun addFrame(bytes: ByteArray): ByteArray? {
            if (isComplete(bytes)) return bytes
            if (V11.isFrame(bytes)) return addV11(bytes)
            return addV107(bytes)
        }

        private fun addV107(bytes: ByteArray): ByteArray? {
            if (bytes.size < HEADER_SIZE) return null
            val frameNum = bytes[0].toInt() and 0xFF
            val total = bytes[1].toInt() and 0xFF
            val fragLen = bytes[2].toInt() and 0xFF
            if (total !in 2..MAX_FRAMES || frameNum >= total || fragLen <= 0) return null
            if (bytes.size < HEADER_SIZE + fragLen) return null

            // A v1.1 set in progress names its payload; a bare shape match here is more likely
            // something else in view than the unit switching layouts, so it doesn't wipe it.
            if (layout == Layout.V11) return null
            // A frame from a different scan (mismatched total) — reset and start over rather
            // than silently mixing two different transactions' fragments together.
            if (totalFrames != total) reset()
            layout = Layout.V107
            totalFrames = total
            received[frameNum] = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + fragLen)
            return assembled()
        }

        private fun addV11(bytes: ByteArray): ByteArray? {
            val format = bytes[1].toInt() and 0xFF
            val index = bytes[2].toInt() and 0xFF
            val total = bytes[3].toInt() and 0xFF
            // Another kind of payload (a kpub, a PSKB...) on screen: not ours, and not a reason
            // to drop what we hold.
            if (format != v11Format) return null
            if (total <= 0 || index >= total || total > V11.MAX_FRAMES) return null
            val header = if (index == 0) V11.FIRST_HEADER_LEN else V11.HEADER_LEN
            if (bytes.size < header) return null
            val frameTag = bytes[V11.HEADER_LEN - 1].toInt() and 0xFF
            val frameDigest: ByteArray? = if (index == 0) bytes.copyOfRange(V11.HEADER_LEN - 1, header) else null
            val fragment = bytes.copyOfRange(header, bytes.size)

            // Another transmission: the count or tag changed, or frame 0 names another digest
            // (the unit rebuilt its reply, or a second screen is in view). Start over with it.
            val held = received[index]
            val currentDigest = digest
            if (layout != Layout.V11 || totalFrames != total || tag != frameTag) {
                reset()
            } else if (frameDigest != null && currentDigest != null && !frameDigest.contentEquals(currentDigest)) {
                reset()
            } else if (held != null && !held.contentEquals(fragment)) {
                // A sender never changes a frame: two transmissions are mixed.
                reset()
            }
            layout = Layout.V11
            totalFrames = total
            tag = frameTag
            if (frameDigest != null) digest = frameDigest
            received[index] = fragment

            val payload = assembled() ?: return null
            // Complete: it must be the payload frame 0 named.
            val named = digest
            if (named == null || !V11.digest(payload).contentEquals(named)) {
                reset()
                return null
            }
            return payload
        }

        private fun assembled(): ByteArray? {
            val expected = totalFrames ?: return null
            if (received.size < expected) return null
            val out = ByteArrayOutputStream()
            for (i in 0 until expected) {
                out.write(received[i] ?: return null)
            }
            return out.toByteArray()
        }

        /** (received, total) — null until the first valid frame arrives. */
        val progress: Pair<Int, Int>? get() = totalFrames?.let { received.size to it }

        /** Which frame indices have arrived so far — for a per-slot progress indicator (frames can arrive out of order). */
        val receivedFrameIndices: Set<Int> get() = received.keys

        fun reset() {
            layout = null
            totalFrames = null
            received.clear()
            tag = null
            digest = null
        }
    }

    /** KasSigner v1.1's frame layout (`frame.rs`): the constants and the writer's even split, the
     *  latter only so host tests can build a unit's frames. */
    object V11 {
        const val MAGIC = 0x1B
        const val FORMAT_KSPT = 1
        /** Every format value frame.rs defines (Kspt, Pskb, Kpub, Descriptor, Container, Opaque). */
        val KNOWN_FORMATS: Set<Int> = setOf(1, 2, 3, 4, 5, 0xFF)
        const val HEADER_LEN = 5
        const val DIGEST_LEN = 4
        const val FIRST_HEADER_LEN = HEADER_LEN + DIGEST_LEN - 1
        /** A unit shows a few frames for a reply; this only bounds what a stray code can claim. */
        const val MAX_FRAMES = 64

        fun isFrame(bytes: ByteArray): Boolean =
            bytes.size >= HEADER_LEN && (bytes[0].toInt() and 0xFF) == MAGIC && (bytes[1].toInt() and 0xFF) in KNOWN_FORMATS

        fun digest(payload: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(payload).copyOf(DIGEST_LEN)

        /** frame.rs `write_split`: split evenly with frame 0's three extra header bytes counted
         *  against its fragment. [perFrame] is the unit's bytes per frame past the 5-byte header. */
        fun frames(payload: ByteArray, format: Int = FORMAT_KSPT, perFrame: Int = 225): List<ByteArray> {
            val extra = FIRST_HEADER_LEN - HEADER_LEN
            if (payload.isEmpty() || perFrame <= 0) return emptyList()
            val total = maxOf(1, (payload.size + extra + perFrame - 1) / perFrame)
            val per = (payload.size + extra + total - 1) / total
            val digest = digest(payload)
            val out = mutableListOf<ByteArray>()
            for (i in 0 until total) {
                val start = maxOf(0, i * per - extra)
                val end = minOf(maxOf(0, (i + 1) * per - extra), payload.size)
                if (start >= end) break
                val frame = ByteArrayOutputStream()
                frame.write(MAGIC)
                frame.write(format)
                frame.write(i)
                frame.write(total)
                if (i == 0) frame.write(digest) else frame.write(digest[0].toInt() and 0xFF)
                frame.write(payload, start, end - start)
                out.add(frame.toByteArray())
            }
            return out
        }
    }
}
