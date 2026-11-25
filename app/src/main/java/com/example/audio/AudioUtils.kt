/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.AudioUtils).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI. Modifications © ARPON.
 */
package com.example.audio

import kotlin.math.sqrt

/** Lightweight per-chunk spectral summary used only for UI/VAD hints (not speech recognition). */
data class AudioFrequencyData(
    val amplitude: Float = 0f,
    val pitchHz: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f
)

/** Pure, Android-free PCM helpers (unit-testable on the JVM). All PCM is 16-bit little-endian mono. */
object AudioUtils {

    /** RMS value that maps to 1.0 in [calculateRmsNormalized]; tuned for phone-mic speech. */
    const val RMS_FULL_SCALE = 3500.0

    /** Reads the signed 16-bit little-endian sample that starts at [index]. */
    fun sampleAt(bytes: ByteArray, index: Int): Int {
        val low = bytes[index].toInt() and 0xFF
        val high = bytes[index + 1].toInt() // sign-extended on purpose
        return (high shl 8) or low
    }

    /** Normalised RMS in 0..1 for the first [length] bytes of 16-bit mono PCM. */
    fun calculateRmsNormalized(bytes: ByteArray, length: Int = bytes.size): Float {
        val usable = minOf(length, bytes.size)
        val sampleCount = usable / 2
        if (sampleCount == 0) return 0f
        var sumSquares = 0.0
        var i = 0
        while (i < sampleCount * 2) {
            val s = sampleAt(bytes, i)
            sumSquares += (s * s).toDouble()
            i += 2
        }
        val rms = sqrt(sumSquares / sampleCount)
        return (rms / RMS_FULL_SCALE).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Drops a trailing odd byte so the buffer always holds whole samples.
     * Returns the same instance when no change is needed.
     */
    fun alignToSampleBoundary(pcm: ByteArray): ByteArray {
        val even = pcm.size and 1.inv()
        return if (even == pcm.size) pcm else pcm.copyOf(even)
    }

    /** Duration in milliseconds of [byteCount] bytes of 16-bit mono PCM at [sampleRate]. */
    fun pcmDurationMs(byteCount: Int, sampleRate: Int): Long {
        if (byteCount <= 0 || sampleRate <= 0) return 0L
        return (byteCount / 2) * 1000L / sampleRate
    }

    /**
     * Cheap real-time analysis: amplitude, zero-crossing pitch estimate and bass/mid/high energies.
     * The pitch value is a rough estimate (zero-crossing rate), suitable for visualisation only.
     */
    fun analyzeFrequency(bytes: ByteArray, length: Int = bytes.size, sampleRate: Int = 16000): AudioFrequencyData {
        val usable = minOf(length, bytes.size)
        val sampleCount = usable / 2
        if (sampleCount < 4) return AudioFrequencyData()

        var sumSquares = 0.0
        var zeroCrossings = 0
        var lastSample = 0
        var diffSumSquares = 0.0
        var lowPassSumSquares = 0.0
        var lowPassState = 0.0
        val alpha = 0.17 // single-pole low-pass, ~500 Hz cutoff at 16 kHz

        var i = 0
        var idx = 0
        while (i < sampleCount * 2) {
            val sample = sampleAt(bytes, i)
            sumSquares += (sample * sample).toDouble()
            if (idx > 0) {
                if ((sample >= 0 && lastSample < 0) || (sample < 0 && lastSample >= 0)) zeroCrossings++
                val diff = sample - lastSample
                diffSumSquares += (diff * diff).toDouble()
            }
            lowPassState += alpha * (sample - lowPassState)
            lowPassSumSquares += lowPassState * lowPassState
            lastSample = sample
            idx++
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val amp = (rms / RMS_FULL_SCALE).toFloat().coerceIn(0f, 1f)
        if (amp < 0.015f) return AudioFrequencyData(amplitude = amp)

        val denom = (sampleCount - 1).coerceAtLeast(1)
        val zcr = zeroCrossings.toFloat() / denom
        val pitchHz = (zcr * (sampleRate / 2.0f)).coerceIn(80f, 3500f)
        val bass = ((sqrt(lowPassSumSquares / sampleCount) / 2500.0).toFloat() * 1.2f).coerceIn(0f, 1f)
        val high = ((sqrt(diffSumSquares / denom) / 3000.0).toFloat() * 1.1f).coerceIn(0f, 1f)
        val mid = ((amp * 1.3f) - (bass * 0.4f) - (high * 0.3f)).coerceIn(0f, 1f)
        return AudioFrequencyData(amp, pitchHz, bass, mid, high)
    }
}
