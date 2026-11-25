package com.example

import com.example.audio.AudioUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Pure JVM tests for the PCM helpers (no Android types involved). */
class AudioUtilsTest {

    private fun pcm(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte() }
        return out
    }

    private fun sine(freq: Double, amp: Int, n: Int, rate: Int = 16000) =
        pcm(IntArray(n) { (amp * sin(2 * PI * freq * it / rate)).toInt() })

    @Test fun sampleAt_decodesSignedLittleEndian() {
        val b = pcm(intArrayOf(1, -1, 32767, -32768, 258))
        assertEquals(1, AudioUtils.sampleAt(b, 0))
        assertEquals(-1, AudioUtils.sampleAt(b, 2))
        assertEquals(32767, AudioUtils.sampleAt(b, 4))
        assertEquals(-32768, AudioUtils.sampleAt(b, 6))
        assertEquals(258, AudioUtils.sampleAt(b, 8))
    }

    @Test fun rms_silenceIsZero_emptyIsZero() {
        assertEquals(0f, AudioUtils.calculateRmsNormalized(ByteArray(640)), 0f)
        assertEquals(0f, AudioUtils.calculateRmsNormalized(ByteArray(0)), 0f)
        assertEquals(0f, AudioUtils.calculateRmsNormalized(ByteArray(1)), 0f)
    }

    @Test fun rms_fullScaleClampsToOne_andIsMonotonic() {
        val quiet = AudioUtils.calculateRmsNormalized(sine(440.0, 500, 320))
        val loud = AudioUtils.calculateRmsNormalized(sine(440.0, 8000, 320))
        val huge = AudioUtils.calculateRmsNormalized(sine(440.0, 32000, 320))
        assertTrue(quiet < loud)
        assertEquals(1f, huge, 0f)
    }

    @Test fun rms_ignoresTrailingOddByteAndLengthBeyondBuffer() {
        val b = sine(440.0, 8000, 320)
        val withOdd = b + byteArrayOf(0x7F)
        assertEquals(AudioUtils.calculateRmsNormalized(b), AudioUtils.calculateRmsNormalized(withOdd), 0f)
        assertEquals(AudioUtils.calculateRmsNormalized(b), AudioUtils.calculateRmsNormalized(b, b.size + 1000), 0f)
    }

    @Test fun alignToSampleBoundary_dropsOddByteOnly() {
        val even = ByteArray(8)
        assertSame(even, AudioUtils.alignToSampleBoundary(even))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), AudioUtils.alignToSampleBoundary(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(0, AudioUtils.alignToSampleBoundary(byteArrayOf(9)).size)
    }

    @Test fun pcmDuration_matchesSampleRate() {
        assertEquals(20L, AudioUtils.pcmDurationMs(640, 16000))
        assertEquals(1000L, AudioUtils.pcmDurationMs(48000, 24000))
        assertEquals(0L, AudioUtils.pcmDurationMs(0, 16000))
        assertEquals(0L, AudioUtils.pcmDurationMs(100, 0))
    }

    @Test fun analyze_tooShortOrSilent_hasNoPitch() {
        assertEquals(0f, AudioUtils.analyzeFrequency(ByteArray(4)).amplitude, 0f)
        val silent = AudioUtils.analyzeFrequency(ByteArray(640))
        assertEquals(0f, silent.pitchHz, 0f)
    }

    @Test fun analyze_highToneHasMoreHighEnergyAndHigherPitchThanLowTone() {
        val low = AudioUtils.analyzeFrequency(sine(150.0, 6000, 320))
        val high = AudioUtils.analyzeFrequency(sine(2500.0, 6000, 320))
        assertTrue(high.pitchHz > low.pitchHz)
        assertTrue(high.high > low.high)
        assertTrue(low.bass >= high.bass)
        assertTrue(low.amplitude > 0.1f && high.amplitude > 0.1f)
    }
}
