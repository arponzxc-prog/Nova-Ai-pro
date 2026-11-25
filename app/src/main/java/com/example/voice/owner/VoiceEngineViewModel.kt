/* © ARPON — original NEXA AI code (Compose + StateFlow replacement for JARVIS VoiceEnrollmentActivity / HandsFreeActivity). */
package com.example.voice.owner

import android.app.Application
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AiAssistantApplication
import com.example.R
import com.example.assistant.AssistantService
import com.example.audio.MicCapture
import com.example.voice.live.LiveConstants
import com.example.voice.live.LiveVoiceSettings
import com.example.voice.live.VoiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class UiMessage(@StringRes val res: Int, val args: List<String> = emptyList())

enum class CapturePhase { IDLE, RECORDING, ANALYZING }
enum class CaptureMode { ENROLL, TEST }

data class VoiceEngineUiState(
    val keyMasked: String = "",
    val hasProfile: Boolean = false,
    val capture: CapturePhase = CapturePhase.IDLE,
    val captureMode: CaptureMode = CaptureMode.ENROLL,
    val samplesCaptured: Int = 0,
    val level: Float = 0f,
    val message: UiMessage? = null,
    val lastMatch: VoiceMatchResult? = null
)

class VoiceEngineViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AiAssistantApplication
    private val repository = app.repository
    private val models = app.voskModelManager
    private val owner = app.ownerVoiceManager
    private val controller = app.assistantController

    val settings: StateFlow<LiveVoiceSettings> =
        repository.liveVoiceSettingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveVoiceSettings())
    val modelState: StateFlow<VoskModelState> = models.state

    private val _ui = MutableStateFlow(VoiceEngineUiState())
    val ui: StateFlow<VoiceEngineUiState> = _ui.asStateFlow()

    private val enrolled = ArrayList<FloatArray>()
    private val pcm = ByteArrayOutputStream()
    private var mic: MicCapture? = null
    private var autoStopJob: Job? = null
    private var micLeased = false

    init {
        models.refreshState()
        viewModelScope.launch {
            val masked = repository.getMaskedGeminiLiveKey()
            val has = withContext(Dispatchers.IO) { owner.hasProfile() }
            _ui.update { it.copy(keyMasked = masked, hasProfile = has) }
        }
    }

    // ---- settings -------------------------------------------------------------------------

    private fun update(transform: (LiveVoiceSettings) -> LiveVoiceSettings) {
        viewModelScope.launch { repository.saveLiveVoiceSettings(transform(settings.value)) }
    }
    fun setMode(m: VoiceMode) = update { it.copy(mode = m) }
    fun setHandsFree(on: Boolean) = update { it.copy(handsFree = on) }
    fun setOwnerEnabled(on: Boolean) = update { it.copy(ownerVoiceEnabled = on) }
    fun setStrictness(s: OwnerVoiceStrictness) = update { it.copy(ownerStrictness = s) }
    fun setModel(m: String) = update { it.copy(model = m) }
    fun setVoice(v: String) = update { it.copy(voiceName = if (v in LiveConstants.SUPPORTED_VOICES) v else LiveConstants.DEFAULT_VOICE) }

    fun saveKey(plain: String) {
        viewModelScope.launch {
            repository.saveGeminiLiveKey(plain)
            _ui.update { it.copy(keyMasked = repository.getMaskedGeminiLiveKey()) }
        }
    }
    fun clearKey() = saveKey("")

    // ---- models ---------------------------------------------------------------------------

    fun installModels() { viewModelScope.launch { models.install() } }
    fun deleteModels() { viewModelScope.launch { models.deleteInstalled() } }

    // ---- enrollment / test ----------------------------------------------------------------

    fun startCapture(mode: CaptureMode) {
        if (_ui.value.capture != CapturePhase.IDLE) return
        if (controller.state.value.isSessionActive) { say(R.string.ve_msg_busy_session); return }
        if (modelState.value !is VoskModelState.Installed && modelState.value != VoskModelState.Loaded) { say(R.string.ve_msg_models_needed); return }
        if (mode == CaptureMode.TEST && !_ui.value.hasProfile) { say(R.string.ve_msg_models_needed); return }

        // Single microphone owner: pause wake-word listening for the duration of the capture.
        controller.wakeWordEngine.stop()
        micLeased = true
        synchronized(pcm) { pcm.reset() }
        val capture = MicCapture(
            context = app,
            onChunk = { chunk, amp, _ ->
                if (chunk.isNotEmpty()) synchronized(pcm) { pcm.write(chunk) }
                _ui.update { it.copy(level = amp) }
            },
            onFatal = { viewModelScope.launch { finishCapture(false); say(R.string.ve_msg_mic_failed) } }
        )
        mic = capture
        if (!capture.start()) { mic = null; releaseMic(); say(R.string.ve_msg_mic_failed); return }
        _ui.update { it.copy(capture = CapturePhase.RECORDING, captureMode = mode, message = UiMessage(R.string.ve_msg_recording), lastMatch = null) }
        autoStopJob = viewModelScope.launch { delay(8_000); stopCapture() }
    }

    fun stopCapture() {
        if (_ui.value.capture != CapturePhase.RECORDING) return
        autoStopJob?.cancel()
        val mode = _ui.value.captureMode
        _ui.update { it.copy(capture = CapturePhase.ANALYZING, level = 0f, message = UiMessage(R.string.ve_msg_analyzing)) }
        viewModelScope.launch {
            mic?.stopAndJoin(); mic = null
            releaseMic()
            val audio = synchronized(pcm) { pcm.toByteArray() }
            val minBytes = if (mode == CaptureMode.ENROLL) OwnerVoiceManager.MIN_ENROLL_BYTES else OwnerVoiceManager.MIN_VERIFY_BYTES
            if (audio.size < minBytes) { finishCapture(false); say(R.string.ve_msg_too_short); return@launch }
            if (!models.load()) { finishCapture(false); say(R.string.ve_msg_models_needed); return@launch }
            try {
                if (mode == CaptureMode.ENROLL) enrollSample(audio) else testSample(audio)
            } finally {
                models.unload()
            }
        }
    }

    private suspend fun enrollSample(audio: ByteArray) {
        val vector = withContext(Dispatchers.Default) { owner.extractVector(audio) }
        if (vector == null) { finishCapture(false); say(R.string.ve_msg_no_vector); return }
        enrolled += vector
        if (enrolled.size < OwnerVoiceManager.ENROLL_SAMPLES_REQUIRED) {
            _ui.update { it.copy(samplesCaptured = enrolled.size) }
            finishCapture(true); say(R.string.ve_msg_sample_ok)
            return
        }
        val ok = withContext(Dispatchers.IO) { owner.saveProfile(enrolled.toList()) }
        enrolled.clear()
        _ui.update { it.copy(samplesCaptured = 0, hasProfile = ok || it.hasProfile) }
        finishCapture(true); say(if (ok) R.string.ve_msg_saved else R.string.ve_msg_save_failed)
    }

    private suspend fun testSample(audio: ByteArray) {
        val res = owner.verify(audio, settings.value.ownerStrictness.threshold)
        _ui.update { it.copy(lastMatch = res) }
        finishCapture(true)
        say(
            when {
                !res.hasVector -> R.string.ve_msg_no_vector
                res.isMatch -> R.string.ve_test_match
                else -> R.string.ve_test_nomatch
            },
            "%.2f".format(res.distance), "%.2f".format(res.threshold)
        )
    }

    fun clearProfile() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { owner.clearProfile() }
            enrolled.clear()
            _ui.update { it.copy(hasProfile = false, samplesCaptured = 0, lastMatch = null) }
            if (settings.value.ownerVoiceEnabled) repository.saveLiveVoiceSettings(settings.value.copy(ownerVoiceEnabled = false))
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun finishCapture(@Suppress("UNUSED_PARAMETER") ok: Boolean) {
        _ui.update { it.copy(capture = CapturePhase.IDLE, level = 0f) }
    }

    private fun say(@StringRes res: Int, vararg args: String) = _ui.update { it.copy(message = UiMessage(res, args.toList())) }

    /** Gives the microphone back: asks the foreground service (if running) to re-arm wake-word listening. */
    private fun releaseMic() {
        if (!micLeased) return
        micLeased = false
        if (controller.state.value.isServiceRunning) {
            runCatching {
                ContextCompat.startForegroundService(
                    app, Intent(app, AssistantService::class.java).setAction(AssistantService.ACTION_REFRESH_CONFIG)
                )
            }
        }
    }

    override fun onCleared() {
        autoStopJob?.cancel()
        mic?.stop(); mic = null
        releaseMic()
        models.unload()
        super.onCleared()
    }
}
