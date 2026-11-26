package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS d089011f: room voice notes draw 1:1's waveform (peaks per 0.1 s, folded into 40 bars). */
class VoiceWaveformTest {
    @Test
    fun `nothing decoded draws the placeholder`() {
        val bars = VoiceWaveform(24_000).bars()
        assertEquals(40, bars.size)
        assertEquals(List(40) { 0.3f }, bars)
    }

    @Test
    fun `each bar is its fold's peak doubled, between 0_1 and 1`() {
        val waveform = VoiceWaveform(sampleRate = 10) // one sample per 0.1 s bin
        // four bins: silence, a quarter, an eighth, full scale
        listOf<Short>(0, 8_192, 4_096, Short.MIN_VALUE).forEach(waveform::add)
        val bars = waveform.bars(4)
        assertEquals(0.1f, bars[0], 1e-6f)
        assertEquals(0.5f, bars[1], 1e-6f)
        assertEquals(0.25f, bars[2], 1e-6f)
        assertEquals(1f, bars[3], 1e-6f)
        // folded into two bars: each half's peak
        assertEquals(listOf(0.5f, 1f), waveform.bars(2))
    }

    @Test
    fun `a short note still fills every bar`() {
        val waveform = VoiceWaveform(sampleRate = 10)
        waveform.add(8_192)
        assertEquals(List(40) { 0.5f }, waveform.bars())
    }

    @Test
    fun `a WAV is read past its header, in chunks`() {
        val file = java.io.File.createTempFile("waveform", ".wav")
        try {
            val samples = ShortArray(70_001) { i -> (if (i < 35_000) 4_000 else 16_384).toShort() }
            val bytes = java.nio.ByteBuffer.allocate(44 + samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put(ByteArray(44))
            samples.forEach { bytes.putShort(it) }
            file.writeBytes(bytes.array())
            val bars = VoiceWaveform.ofWav(file, sampleRate = 10, bars = 2)!!
            assertEquals(4_000 / 32_768f * 2, bars[0], 1e-4f)
            assertEquals(1f, bars[1], 1e-6f)
        } finally {
            file.delete()
        }
    }
}
