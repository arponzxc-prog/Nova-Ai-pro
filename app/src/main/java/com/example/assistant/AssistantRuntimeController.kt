package com.example.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.camerapill.PillVoiceSignal
import com.example.model.ActionCardItem
import com.example.tools.ContactMatch
import com.example.tools.PendingActionConfirmation
import com.example.tools.PendingContactSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PauseReason {
    NONE,
    USER_PAUSED,
    OTHER_MIC_OR_CALL,
    NOT_CHARGING,
    NIGHT_SCHEDULE
}

enum class BackgroundSessionPhase {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    WAITING_CONFIRMATION,
    ERROR
}

data class AssistantRuntimeState(
    val isServiceRunning: Boolean = false,
    val isUserPaused: Boolean = false,
    val effectivePauseReason: PauseReason = PauseReason.NONE,
    val isSessionActive: Boolean = false,
    val sessionPhase: BackgroundSessionPhase = BackgroundSessionPhase.IDLE,
    val userTranscript: String = "",
    val aiReplyPreview: String = "",
    val rmsNormalized: Float = 0f,
    val errorMessage: String? = null,
    val autoCloseSecondsRemaining: Int? = null,
    val actionCards: List<ActionCardItem> = emptyList(),
    val pendingConfirmation: PendingActionConfirmation? = null,
    val pendingContactSelection: PendingContactSelection? = null,
    val activeConversationId: String? = null,
    val isUiAutomationRunning: Boolean = false,
    val uiAutomationLabel: String = "",
    val uiAutomationStoppedByTouch: Boolean = false,
    /** True while the active session is a Gemini Live session (false = SpeechRecognizer + TTS flow). */
    val isLiveVoice: Boolean = false,
    /** Short connection label for Gemini Live ("connecting", "reconnecting", "offline"…); empty when fine. */
    val liveStatus: String = ""
) {
    val isEffectivelyPaused: Boolean
        get() = isUserPaused || effectivePauseReason != PauseReason.NONE
}

/**
 * সার্ভিস, ওভারলে কার্ড, ভয়েস সেশন, Wake Word ইঞ্জিন এবং MainViewModel-এর মধ্যে সমন্বয়কারী সিঙ্গেলটন।
 */
class AssistantRuntimeController(private val appContext: Context) {

    val wakeWordEngine: WakeWordEngine = SherpaOnnxWakeWordEngine(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AssistantRuntimeState())
    val state: StateFlow<AssistantRuntimeState> = _state.asStateFlow()

    /**
     * Camera Pill: voice signal published by the in-app voice mode (MainViewModel).
     * The background session is read directly from [state]; both reuse the existing
     * SpeechRecognizer RMS / TTS flags, so no extra microphone session exists.
     */
    private val _inAppVoiceSignal = MutableStateFlow(PillVoiceSignal())
    val inAppVoiceSignal: StateFlow<PillVoiceSignal> = _inAppVoiceSignal.asStateFlow()

    fun publishInAppVoiceSignal(signal: PillVoiceSignal) {
        _inAppVoiceSignal.value = signal
    }

    var onConfirmAction: ((String?) -> Unit)? = null
    var onCancelAction: (() -> Unit)? = null
    var onPickContact: ((ContactMatch?) -> Unit)? = null
    var onStopSession: (() -> Unit)? = null
    var onRetrySession: (() -> Unit)? = null
    var onCancelUiAutomation: ((byTouch: Boolean) -> Unit)? = null

    fun setServiceRunning(running: Boolean) {
        _state.update {
            if (!running) {
                AssistantRuntimeState(isServiceRunning = false)
            } else {
                it.copy(isServiceRunning = true)
            }
        }
    }

    fun setUiAutomationRunning(running: Boolean, label: String = "") {
        _state.update {
            it.copy(
                isUiAutomationRunning = running,
                uiAutomationLabel = if (running) label else "",
                uiAutomationStoppedByTouch = if (running) false else it.uiAutomationStoppedByTouch
            )
        }
    }

    fun requestStopUiAgent(byTouch: Boolean) {
        _state.update {
            it.copy(
                isUiAutomationRunning = false,
                uiAutomationStoppedByTouch = byTouch
            )
        }
        onCancelUiAutomation?.invoke(byTouch)
    }

    fun updatePauseState(userPaused: Boolean, effectiveReason: PauseReason) {
        _state.update {
            it.copy(
                isUserPaused = userPaused,
                effectivePauseReason = effectiveReason
            )
        }
    }

    fun updateSessionState(transform: (AssistantRuntimeState) -> AssistantRuntimeState) {
        _state.update(transform)
    }

    fun playWakeChimeAndVibrate() {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(65L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(65L)
                    }
                }
            }

            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                delay(240L)
                tg.release()
            }
        }
    }
}
