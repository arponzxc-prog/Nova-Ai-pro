package com.example.camerapill

/** Camera Pill display modes (user setting). */
enum class CameraPillMode(val key: String) {
    /** Visible only while the user/AI is speaking or a voice interaction is being processed. */
    ONLY_WHILE_TALKING("talking"),

    /** Visible whenever the assistant service/session is active. */
    ALWAYS_ON("always");

    companion object {
        fun fromKey(key: String?): CameraPillMode = entries.firstOrNull { it.key == key } ?: ONLY_WHILE_TALKING
    }
}

/** Persisted Camera Pill preferences (DataStore, see AiRepository). */
data class CameraPillSettings(
    val enabled: Boolean = false,
    val mode: CameraPillMode = CameraPillMode.ONLY_WHILE_TALKING,
    val avatarAnimationEnabled: Boolean = true,
    val audioVisualizerEnabled: Boolean = true,
    val showThinkingAnimation: Boolean = true,
    val showSpeakingAnimation: Boolean = true,
    /** 0.7f..1.4f multiplier for pill/avatar size. */
    val avatarSize: Float = 1.0f,
    /** 0.5f..1.5f multiplier for animation amplitude. */
    val animationIntensity: Float = 1.0f,
    /** Delay before hiding in ONLY_WHILE_TALKING mode, milliseconds. */
    val idleHideDelayMs: Long = 2500L
) {
    companion object {
        const val SIZE_MIN = 0.7f
        const val SIZE_MAX = 1.4f
        const val INTENSITY_MIN = 0.5f
        const val INTENSITY_MAX = 1.5f
        const val IDLE_DELAY_MIN_MS = 1000L
        const val IDLE_DELAY_MAX_MS = 8000L
    }
}

/** Visual/avatar states required by the feature spec. */
enum class PillAvatarState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED, ERROR, OFFLINE }

/** Raw phase reported by whichever voice pipeline is active (in-app voice mode or background session). */
enum class PillSourcePhase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Single published snapshot of the voice pipeline for the Camera Pill.
 * [micLevel] comes from the EXISTING SpeechRecognizer RMS (no extra mic session).
 * Android TextToSpeech exposes no amplitude, so AI speech is visualised from [phase]==SPEAKING only.
 */
data class PillVoiceSignal(
    val active: Boolean = false,
    val phase: PillSourcePhase = PillSourcePhase.IDLE,
    val micLevel: Float = 0f,
    val sourceId: String = ""
)

/** Pure, unit-testable mapping helpers. */
object CameraPillLogic {

    fun deriveAvatarState(signal: PillVoiceSignal, isOnline: Boolean, recentlyInterrupted: Boolean): PillAvatarState {
        if (!signal.active) return if (!isOnline) PillAvatarState.OFFLINE else PillAvatarState.IDLE
        return when (signal.phase) {
            PillSourcePhase.ERROR -> PillAvatarState.ERROR
            PillSourcePhase.LISTENING -> if (recentlyInterrupted) PillAvatarState.INTERRUPTED else PillAvatarState.LISTENING
            PillSourcePhase.THINKING -> if (!isOnline) PillAvatarState.OFFLINE else PillAvatarState.THINKING
            PillSourcePhase.SPEAKING -> PillAvatarState.SPEAKING
            PillSourcePhase.IDLE -> if (!isOnline) PillAvatarState.OFFLINE else PillAvatarState.IDLE
        }
    }

    /** True while a voice interaction is in progress (used by ONLY_WHILE_TALKING). */
    fun isInteractionActive(signal: PillVoiceSignal): Boolean =
        signal.active && signal.phase != PillSourcePhase.IDLE

    fun shouldShow(
        settings: CameraPillSettings,
        hasOverlayPermission: Boolean,
        signal: PillVoiceSignal,
        serviceOrSessionActive: Boolean,
        holdAfterTalkingActive: Boolean,
        previewActive: Boolean
    ): Boolean {
        if (previewActive && hasOverlayPermission) return true
        if (!settings.enabled || !hasOverlayPermission) return false
        return when (settings.mode) {
            CameraPillMode.ALWAYS_ON -> serviceOrSessionActive || signal.active
            CameraPillMode.ONLY_WHILE_TALKING -> isInteractionActive(signal) || holdAfterTalkingActive
        }
    }

    fun clampSettings(s: CameraPillSettings): CameraPillSettings = s.copy(
        avatarSize = s.avatarSize.coerceIn(CameraPillSettings.SIZE_MIN, CameraPillSettings.SIZE_MAX),
        animationIntensity = s.animationIntensity.coerceIn(
            CameraPillSettings.INTENSITY_MIN, CameraPillSettings.INTENSITY_MAX
        ),
        idleHideDelayMs = s.idleHideDelayMs.coerceIn(
            CameraPillSettings.IDLE_DELAY_MIN_MS, CameraPillSettings.IDLE_DELAY_MAX_MS
        )
    )
}
