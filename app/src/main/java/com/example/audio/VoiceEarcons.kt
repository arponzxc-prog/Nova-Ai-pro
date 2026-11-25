/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.JarvisSoundEffects).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: renamed, single worker thread instead of a thread per sound, always releases
 * the AudioTrack. Modifications © ARPON.
 */
package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/** Procedurally generated wake/standby earcons (no asset files). */
object VoiceEarcons {
    private const val TAG = "VoiceEarcons"
    const val SAMPLE_RATE = 44_100

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nexa-earcon").apply { isDaemon = true } }

    private val wakePcm: ByteArray by lazy {
        generateTwoTone(90, 20, 160, 587.33, doubleArrayOf(1.0, 0.3), 880.0, doubleArrayOf(1.0, 0.35, 0.15), 22000.0, 25000.0)
    }
    private val standbyPcm: ByteArray by lazy {
        generateTwoTone(80, 20, 150, 880.0, doubleArrayOf(1.0, 0.25), 587.33, doubleArrayOf(1.0, 0.2), 20000.0, 21000.0)
    }

    fun playWake() = play(wakePcm)
    fun playStandby() = play(standbyPcm)

    private fun play(pcm: ByteArray) {
        runCatching {
            executor.execute {
                var track: AudioTrack? = null
                try {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(AudioUtils.pcmDurationMs(pcm.size, SAMPLE_RATE) + 60)
                } catch (e: Exception) {
                    Log.w(TAG, "Earcon failed: ${e.javaClass.simpleName}")
                } finally {
                    runCatching { track?.stop() }
                    runCatching { track?.release() }
                }
            }
        }
    }

    /**
     * Two decaying notes separated by [gapMs] of silence. [harm1]/[harm2] are relative amplitudes of the
     * fundamental and successive integer overtones.
     */
    internal fun generateTwoTone(
        note1Ms: Int, gapMs: Int, note2Ms: Int,
        f1: Double, harm1: DoubleArray, f2: Double, harm2: DoubleArray,
        amp1: Double, amp2: Double
    ): ByteArray {
        val n1 = SAMPLE_RATE * note1Ms / 1000
        val gap = SAMPLE_RATE * gapMs / 1000
        val n2 = SAMPLE_RATE * note2Ms / 1000
        val total = n1 + gap + n2
        val out = ByteArray(total * 2)
        var idx = 0
        for (i in 0 until total) {
            val v: Int = when {
                i < n1 -> {
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = (i.toDouble() / (SAMPLE_RATE * 7 / 1000)).coerceAtMost(1.0)
                    val decay = ((n1 - i).toDouble() / (SAMPLE_RATE * 32 / 1000)).coerceIn(0.0, 1.0)
                    (tone(t, f1, harm1) * attack * decay * amp1).toInt()
                }
                i < n1 + gap -> 0
                else -> {
                    val k = i - n1 - gap
                    val t = k.toDouble() / SAMPLE_RATE
                    val attack = (k.toDouble() / (SAMPLE_RATE * 6 / 1000)).coerceAtMost(1.0)
                    val decay = ((n2 - k).toDouble() / (n2 * 0.75)).coerceIn(0.0, 1.0)
                    (tone(t, f2, harm2) * attack * decay * decay * amp2).toInt()
                }
            }.coerceIn(-32767, 32767)
            out[idx++] = (v and 0xFF).toByte()
            out[idx++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun tone(t: Double, f: Double, harmonics: DoubleArray): Double {
        var s = 0.0
        for (h in harmonics.indices) s += harmonics[h] * sin(2 * PI * f * (h + 1) * t)
        return s
    }
}
