/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.AudioPlayer).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: blocking write() no longer holds the lock that flush() needs (instant barge-in),
 * end-of-playback is computed from written duration instead of poll counters, AudioTrack is created
 * lazily and released exactly once. Modifications © ARPON.
 */
package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams 24 kHz 16-bit mono PCM (Gemini Live output) to an [AudioTrack].
 * Callbacks are invoked on the player thread; collectors must be thread-safe (StateFlow is).
 */
class AudioPlayer(
    private val usage: Int = AudioAttributes.USAGE_ASSISTANT,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_CHUNK_BYTES = 16

        /** After the last chunk is rendered we wait this long before declaring playback finished. */
        private const val TAIL_MS = 120L

        /** If the server never sends turnComplete, declare playback finished after this idle gap. */
        private const val NO_TURN_COMPLETE_IDLE_MS = 800L
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val turnComplete = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val renderEndsAtMs = AtomicLong(0L)
    private val lastEnqueueAtMs = AtomicLong(0L)

    private val trackLock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null

    /** Starts the playback thread. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ playbackLoop() }, "nexa-live-player").apply { isDaemon = true }
        thread = t
        t.start()
    }

    fun enqueueAudio(pcm: ByteArray) {
        if (!running.get() || pcm.size < MIN_CHUNK_BYTES) return
        lastEnqueueAtMs.set(SystemClock.elapsedRealtime())
        turnComplete.set(false)
        queue.offer(AudioUtils.alignToSampleBoundary(pcm))
    }

    /** Server said the model finished this turn; playback ends once buffered audio has been rendered. */
    fun notifyTurnComplete() {
        turnComplete.set(true)
    }

    /** Instantly discards queued and buffered audio (barge-in, interruption, focus loss). */
    fun flush() {
        generation.incrementAndGet()
        queue.clear()
        turnComplete.set(true)
        renderEndsAtMs.set(0L)
        val t = track
        if (t != null) {
            // pause()/flush() unblock a write() that is waiting for buffer space on the player thread.
            runCatching { if (t.state == AudioTrack.STATE_INITIALIZED) { t.pause(); t.flush() } }
        }
        if (playing.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    fun isPlaying(): Boolean = playing.get() || queue.isNotEmpty()

    /** Stops the thread and releases the AudioTrack. Idempotent. */
    fun release() {
        if (!running.getAndSet(false) && thread == null && track == null) return
        generation.incrementAndGet()
        queue.clear()
        val t = track
        runCatching { t?.pause(); t?.flush() } // unblock a pending write()
        thread?.interrupt()
        thread = null
        synchronized(trackLock) {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
        }
        if (playing.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun ensureTrack(): AudioTrack? {
        synchronized(trackLock) {
            track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
            runCatching { track?.release() }
            track = null
            return try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                // ≥ 2 s headroom absorbs network jitter; "standard" performance mode avoids FastMixer underrun loops.
                val size = maxOf(minBuf * 8, 96_000)
                val attrs = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .also { track = it }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack creation failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun playbackLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            while (running.get()) {
                val chunk = queue.poll(20, TimeUnit.MILLISECONDS)
                if (chunk == null || chunk.isEmpty()) {
                    maybeFinish()
                    continue
                }
                val gen = generation.get()
                val t = ensureTrack() ?: continue
                if (!playing.get()) {
                    if (gen != generation.get()) continue
                    playing.set(true)
                    onPlaybackStateChanged(true)
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { t.play() }
                }
                onPlaybackAmplitude(AudioUtils.calculateRmsNormalized(chunk, chunk.size))

                val now = SystemClock.elapsedRealtime()
                val base = maxOf(renderEndsAtMs.get(), now)
                renderEndsAtMs.set(base + AudioUtils.pcmDurationMs(chunk.size, SAMPLE_RATE))

                // write() may block while the buffer is full; flush()/release() unblock it without needing a lock.
                val written = try { t.write(chunk, 0, chunk.size) } catch (e: Exception) { -1 }
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write failed ($written); recreating track")
                    synchronized(trackLock) {
                        runCatching { t.release() }
                        if (track === t) track = null
                    }
                }
            }
        } catch (_: InterruptedException) {
            // release() requested shutdown
        } catch (e: Exception) {
            Log.e(TAG, "Playback loop error: ${e.javaClass.simpleName}")
        }
    }

    private fun maybeFinish() {
        if (!playing.get()) return
        val now = SystemClock.elapsedRealtime()
        val rendered = now >= renderEndsAtMs.get() + TAIL_MS
        val idleTooLong = now - lastEnqueueAtMs.get() >= NO_TURN_COMPLETE_IDLE_MS
        if (queue.isEmpty() && rendered && (turnComplete.get() || idleTooLong)) {
            if (playing.compareAndSet(true, false)) {
                onPlaybackStateChanged(false)
                onPlaybackAmplitude(0f)
            }
        }
    }
}
