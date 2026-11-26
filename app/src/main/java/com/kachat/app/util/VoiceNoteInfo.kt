package com.kachat.app.util

import android.util.LruCache

/**
 * A voice note's length and waveform, worked out once per process (iOS d089011f
 * `VoiceNoteInfoCache`): a room's voice-note bubble scrolling back in, or the same note in a new
 * view, reads them from here instead of decoding the note again. Keyed by the audio itself.
 */
object VoiceNoteInfoCache {
    class Info(val durationMs: Long, val waveform: List<Float>)

    private val cache = LruCache<String, Info>(500)

    fun info(key: String): Info? = cache.get(key)

    fun store(key: String, info: Info) {
        cache.put(key, info)
    }
}

/**
 * A voice note's waveform as 1:1 chats draw it (iOS `WebMOpusDecoder.decodePartsToPCMFile`'s
 * waveform): the peak of every 0.1 s across the note, folded into [bars] bars, each the fold's
 * peak doubled, between 0.1 and 1. Fed the decoded samples in order. Pure, for tests.
 */
class VoiceWaveform(sampleRate: Int) {
    private val binFrames = maxOf(1, sampleRate / 10)
    private val bins = ArrayList<Float>()
    private var binPeak = 0f
    private var binFill = 0

    /** One 16-bit sample. */
    fun add(sample: Short) {
        val magnitude = kotlin.math.abs(sample.toInt()) / 32_768f
        if (magnitude > binPeak) binPeak = magnitude
        binFill++
        if (binFill == binFrames) {
            bins.add(binPeak)
            binPeak = 0f
            binFill = 0
        }
    }

    fun bars(bars: Int = DEFAULT_BARS): List<Float> {
        val count = maxOf(1, bars)
        val all = if (binFill > 0) bins + binPeak else bins
        if (all.isEmpty()) return List(count) { PLACEHOLDER }
        return List(count) { bar ->
            val start = minOf(bar * all.size / count, all.size - 1)
            val end = minOf(maxOf(start + 1, (bar + 1) * all.size / count), all.size)
            var peak = 0f
            for (i in start until end) if (all[i] > peak) peak = all[i]
            (peak * 2).coerceIn(0.1f, 1f)
        }
    }

    companion object {
        const val DEFAULT_BARS = 40

        /** What a note whose shape isn't known yet draws (iOS: 40 bars at 0.3). */
        const val PLACEHOLDER = 0.3f

        /** The bars of a 16-bit mono WAV with a 44-byte header, as the voice-note decoders write
         *  it, read in chunks. Null when the file doesn't read. */
        fun ofWav(file: java.io.File, sampleRate: Int, bars: Int = DEFAULT_BARS): List<Float>? = runCatching {
            val waveform = VoiceWaveform(sampleRate)
            java.io.BufferedInputStream(java.io.FileInputStream(file), 64 * 1024).use { input ->
                var skipped = 0L
                while (skipped < 44) {
                    val step = input.skip(44 - skipped)
                    if (step <= 0) return@runCatching null
                    skipped += step
                }
                val buffer = ByteArray(64 * 1024)
                var carry = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    var i = 0
                    if (carry >= 0) {
                        waveform.add(((carry and 0xFF) or (buffer[0].toInt() shl 8)).toShort())
                        carry = -1
                        i = 1
                    }
                    while (i + 1 < read) {
                        waveform.add(((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort())
                        i += 2
                    }
                    if (i < read) carry = buffer[i].toInt()
                }
            }
            waveform.bars(bars)
        }.getOrNull()
    }
}
