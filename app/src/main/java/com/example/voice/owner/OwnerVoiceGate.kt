/*
 * © ARPON — original NEXA AI code.
 *
 * Why this exists: in the JARVIS implementation microphone audio was streamed to Gemini first and the
 * owner check only suppressed the *reply*, so a stranger's speech still left the device. This gate holds
 * audio locally, asks for verification, and only forwards it once the speaker has matched.
 */
package com.example.voice.owner

import java.util.ArrayDeque

/**
 * Pure state machine that decides which microphone chunks may be sent to the cloud.
 * Feed every chunk (16 kHz, 16-bit mono) through [onFrame]; execute the returned [Action].
 *
 *  IDLE ──voice──► COLLECTING ──enough speech / end of utterance──► VERIFYING ──match──► PASSED
 *                                                                        └──no match──► REJECTED
 *  PASSED ──model turn complete / long silence──► IDLE;  REJECTED ──silence──► IDLE
 *
 * Nothing leaves IDLE/COLLECTING/VERIFYING/REJECTED. There is deliberately no "trusted lease":
 * every new utterance has to match again.
 */
class OwnerVoiceGate(private val cfg: Config) {

    data class Config(
        /** false → pass-through (owner voice off, or not enrolled). */
        val enforce: Boolean,
        val voiceThreshold: Float = 0.04f,
        val startVoicedMs: Int = 60,
        val verifyAtVoicedMs: Int = 900,
        val shortUtteranceMinMs: Int = 400,
        val utteranceEndSilenceMs: Int = 700,
        val prerollMs: Int = 200,
        val maxBufferMs: Int = 5_000,
        val passedIdleMs: Int = 6_000
    )

    enum class Phase { IDLE, COLLECTING, VERIFYING, PASSED, REJECTED }

    sealed interface Action {
        data object None : Action
        data class Forward(val chunks: List<ByteArray>) : Action
        /** Run speaker verification on [pcm] (concatenated 16 kHz mono PCM) and answer with [onVerified]. */
        class Verify(val pcm: ByteArray) : Action
    }

    var phase: Phase = Phase.IDLE
        private set

    private val preroll = ArrayDeque<ByteArray>()
    private var prerollTotalMs = 0
    private val buffer = ArrayList<ByteArray>()
    private var bufferMs = 0
    private var voicedMs = 0
    private var voicedRunMs = 0
    private var silenceMs = 0

    fun onFrame(chunk: ByteArray, amplitude: Float): Action {
        if (!cfg.enforce) return if (chunk.isEmpty()) Action.None else Action.Forward(listOf(chunk))
        if (chunk.isEmpty()) return Action.None // muted marker from MicCapture
        val ms = chunkMs(chunk)
        val voiced = amplitude > cfg.voiceThreshold

        return when (phase) {
            Phase.IDLE -> {
                addPreroll(chunk, ms)
                voicedRunMs = if (voiced) voicedRunMs + ms else 0
                if (voicedRunMs >= cfg.startVoicedMs) {
                    phase = Phase.COLLECTING
                    buffer.clear(); buffer.addAll(preroll); bufferMs = prerollTotalMs
                    preroll.clear(); prerollTotalMs = 0
                    voicedMs = voicedRunMs; silenceMs = 0
                }
                Action.None
            }
            Phase.COLLECTING -> {
                buffer.add(chunk); bufferMs += ms
                if (voiced) { voicedMs += ms; silenceMs = 0 } else silenceMs += ms
                when {
                    voicedMs >= cfg.verifyAtVoicedMs -> startVerify()
                    silenceMs >= cfg.utteranceEndSilenceMs ->
                        if (voicedMs >= cfg.shortUtteranceMinMs) startVerify() else { toIdle(); Action.None }
                    bufferMs >= cfg.maxBufferMs -> { toIdle(); Action.None } // long low-level noise, not speech
                    else -> Action.None
                }
            }
            Phase.VERIFYING -> {
                buffer.add(chunk); bufferMs += ms
                while (bufferMs > cfg.maxBufferMs && buffer.size > 1) bufferMs -= chunkMs(buffer.removeAt(0))
                Action.None
            }
            Phase.PASSED -> {
                if (voiced) silenceMs = 0 else silenceMs += ms
                if (silenceMs >= cfg.passedIdleMs) { toIdle(); Action.None } else Action.Forward(listOf(chunk))
            }
            Phase.REJECTED -> {
                if (voiced) silenceMs = 0 else silenceMs += ms
                if (silenceMs >= cfg.utteranceEndSilenceMs) toIdle()
                Action.None
            }
        }
    }

    /** Result of the verification requested by [Action.Verify]. Ignored in any other phase. */
    fun onVerified(passed: Boolean): Action {
        if (phase != Phase.VERIFYING) return Action.None
        silenceMs = 0
        return if (passed) {
            phase = Phase.PASSED
            val out = ArrayList(buffer)
            buffer.clear(); bufferMs = 0
            Action.Forward(out)
        } else {
            phase = Phase.REJECTED
            buffer.clear(); bufferMs = 0
            Action.None
        }
    }

    /** The model finished its turn: the next utterance has to be verified again. */
    fun onModelTurnComplete() {
        if (phase == Phase.PASSED) toIdle()
    }

    fun reset() = toIdle()

    private fun startVerify(): Action {
        phase = Phase.VERIFYING
        return Action.Verify(concat(buffer))
    }

    private fun toIdle() {
        phase = Phase.IDLE
        buffer.clear(); bufferMs = 0
        preroll.clear(); prerollTotalMs = 0
        voicedMs = 0; voicedRunMs = 0; silenceMs = 0
    }

    private fun addPreroll(chunk: ByteArray, ms: Int) {
        preroll.addLast(chunk); prerollTotalMs += ms
        while (prerollTotalMs > cfg.prerollMs && preroll.size > 1) prerollTotalMs -= chunkMs(preroll.removeFirst())
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        var total = 0
        for (c in chunks) total += c.size
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) { System.arraycopy(c, 0, out, pos, c.size); pos += c.size }
        return out
    }

    /** 16 kHz × 2 bytes = 32 bytes per millisecond. */
    private fun chunkMs(chunk: ByteArray): Int = (chunk.size / 32).coerceAtLeast(1)
}
