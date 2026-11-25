/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.AudioRecorder).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI (single capture thread that owns the AudioRecord, effects re-bound on
 * recovery, bounded recovery attempts, explicit fatal callback). Modifications © ARPON.
 */
package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16 kHz mono PCM microphone capture for Gemini Live.
 *
 * Ownership rules (see PROJECT_HANDOVER "Voice engine"):
 *  - Exactly one instance may run at a time and only while [WakeWordEngine] and
 *    [com.example.voice.SpeechInputManager] are stopped. The coordinator enforces this.
 *  - The capture thread creates, uses and releases the [AudioRecord]; no other thread touches it,
 *    which avoids the release-while-reading race of the original implementation.
 */
class MicCapture(
    private val context: Context,
    private val onChunk: (chunk: ByteArray, amplitude: Float, freq: AudioFrequencyData) -> Unit,
    private val onFatal: (reason: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "MicCapture"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms of 16 kHz 16-bit mono audio. */
        const val CHUNK_BYTES = 640
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        private const val MAX_RECOVERY_FAILURES = 3
    }

    private val running = AtomicBoolean(false)
    @Volatile private var muted = false
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun setMuted(value: Boolean) { muted = value }
    fun isMuted(): Boolean = muted

    /** @return false if the permission is missing or the capture thread could not be started. */
    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        val bufferSize = maxOf(minBuf * 4, 32_000)
        running.set(true)
        val t = Thread({ captureLoop(bufferSize) }, "nexa-live-mic").apply { isDaemon = true }
        thread = t
        t.start()
        return true
    }

    /** Signals the capture thread to stop; it releases the AudioRecord itself. Safe to call repeatedly. */
    fun stop() {
        running.set(false)
    }

    /** Stops and waits (off the main thread) until the AudioRecord has really been released. */
    suspend fun stopAndJoin(timeoutMs: Long = 1_000L) {
        running.set(false)
        val t = thread ?: return
        withContext(Dispatchers.IO) {
            try { t.join(timeoutMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (t.isAlive) Log.w(TAG, "Capture thread did not exit within ${timeoutMs}ms")
    }

    // ---- capture thread -------------------------------------------------------------------

    private class Effects(var aec: AcousticEchoCanceler?, var ns: NoiseSuppressor?, var agc: AutomaticGainControl?) {
        fun release() {
            runCatching { aec?.release() }; runCatching { ns?.release() }; runCatching { agc?.release() }
            aec = null; ns = null; agc = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(bufferSize: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null } else r
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun attachEffects(sessionId: Int): Effects {
        val aec = if (AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.also { it.enabled = true } }.getOrNull() else null
        val agc = if (AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(sessionId)?.also { it.enabled = true } }.getOrNull() else null
        return Effects(aec, ns, agc)
    }

    private fun captureLoop(bufferSize: Int) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var record: AudioRecord? = null
        var effects: Effects? = null
        var recoveryFailures = 0
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            while (running.get()) {
                if (record == null) {
                    record = openRecord(bufferSize)
                    if (record == null) {
                        recoveryFailures++
                        if (recoveryFailures >= MAX_RECOVERY_FAILURES) {
                            running.set(false)
                            onFatal("Microphone could not be opened (busy or permission revoked)")
                            break
                        }
                        sleepQuietly(400L * recoveryFailures)
                        continue
                    }
                    effects = attachEffects(record.audioSessionId)
                    try {
                        record.startRecording()
                    } catch (e: Exception) {
                        Log.e(TAG, "startRecording failed: ${e.javaClass.simpleName}")
                        effects.release(); effects = null
                        record.release(); record = null
                        recoveryFailures++
                        if (recoveryFailures >= MAX_RECOVERY_FAILURES) {
                            running.set(false); onFatal("Microphone start failed"); break
                        }
                        sleepQuietly(400L)
                        continue
                    }
                    recoveryFailures = 0
                }

                var consecutiveErrors = 0
                val active = record
                while (running.get() && active != null) {
                    val n = active.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        consecutiveErrors = 0
                        if (muted) {
                            onChunk(ByteArray(0), 0f, AudioFrequencyData())
                        } else {
                            val freq = AudioUtils.analyzeFrequency(buffer, n, SAMPLE_RATE)
                            onChunk(buffer.copyOf(n), freq.amplitude, freq)
                        }
                    } else if (n < 0) {
                        consecutiveErrors++
                        if (consecutiveErrors > MAX_CONSECUTIVE_READ_ERRORS) {
                            Log.w(TAG, "Too many read errors (last=$n); re-opening AudioRecord")
                            break
                        }
                        sleepQuietly(40L)
                    }
                }

                // Leaving the inner loop: either stop requested or the record is broken → release and (maybe) reopen.
                effects?.release(); effects = null
                active?.let { a ->
                    runCatching { if (a.state == AudioRecord.STATE_INITIALIZED) a.stop() }
                    runCatching { a.release() }
                }
                record = null
                if (running.get()) {
                    // Re-open after read errors: short pause so a dead device cannot cause a hot loop.
                    recoveryFailures++
                    sleepQuietly(300L)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture loop crashed: ${t.javaClass.simpleName}")
            if (running.getAndSet(false)) onFatal("Microphone capture crashed")
        } finally {
            effects?.release()
            runCatching { record?.let { if (it.state == AudioRecord.STATE_INITIALIZED) it.stop(); it.release() } }
            running.set(false)
            thread = null
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
