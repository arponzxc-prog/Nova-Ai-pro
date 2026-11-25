/*
 * © ARPON — original NEXA AI code.
 * Conversation logic adapted in spirit from the JARVIS JarvisConversationService (VAD/echo-guard/barge-in/
 * audio-focus/standby ideas) — see THIRD_PARTY_NOTICES.md. Everything JARVIS-specific (tools, YouTube,
 * WhatsApp, screen agent, UI) is intentionally NOT included.
 */
package com.example.voice.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.example.R
import com.example.assistant.AssistantRuntimeController
import com.example.assistant.BackgroundSessionPhase
import com.example.audio.AudioFrequencyData
import com.example.audio.AudioPlayer
import com.example.audio.AudioRoute
import com.example.audio.AudioRouteManager
import com.example.audio.MicCapture
import com.example.data.AiRepository
import com.example.voice.owner.OwnerVoiceGate
import com.example.voice.owner.OwnerVoiceManager
import com.example.voice.owner.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.ArrayDeque

sealed interface LiveStartResult {
    data object Started : LiveStartResult
    /** Live cannot run now; the caller must use the legacy SpeechRecognizer + TTS flow. */
    data class Fallback(@StringRes val reason: Int) : LiveStartResult
}

/**
 * One Gemini Live voice session inside AssistantService's microphone foreground service.
 * Publishes everything through [AssistantRuntimeController] so overlays and the Camera Pill need no changes.
 * The caller (BackgroundVoiceSessionManager) guarantees WakeWordEngine/SpeechInputManager are stopped first.
 */
class LiveVoiceCoordinator(
    context: Context,
    private val repository: AiRepository,
    private val controller: AssistantRuntimeController,
    private val models: VoskModelManager,
    private val owner: OwnerVoiceManager,
    /** Runtime failure after start (invalid key, retries exhausted, mic lost, focus lost). Caller shuts down + falls back. */
    private val onFailed: (@StringRes Int) -> Unit,
    /** Idle timeout reached; caller shuts down normally. */
    private val onIdleTimeout: () -> Unit,
    /** Function declarations offered to the model (Step 2). Empty/null keeps Step 1 behaviour (no tools). */
    private val toolProvider: (suspend () -> List<JSONObject>)? = null,
    /** Runs a model tool call through the app's full safety pipeline (ToolGateway). Null = tools unavailable. */
    private val toolExecutor: (suspend (name: String, args: JSONObject) -> com.example.tools.ToolResult)? = null
) {
    companion object {
        private const val TAG = "LiveVoice"
        private const val ECHO_TAIL_MS = 150L
        private const val BARGE_IN_AMPLITUDE = 0.25f
        private const val BARGE_IN_FRAMES = 4
        private const val PRE_CONNECT_MAX_CHUNKS = 60 // ~1.2 s
        private const val IDLE_TIMEOUT_MS = 45_000L
        private const val IDLE_TIMEOUT_HANDS_FREE_MS = 10 * 60_000L
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var session: LiveSession? = null
    private var mic: MicCapture? = null
    private var player: AudioPlayer? = null
    private var router: AudioRouteManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var gate = OwnerVoiceGate(OwnerVoiceGate.Config(enforce = false))
    private val gateLock = Any()
    private var ownerThreshold = 0.70f

    private val preConnect = ArrayDeque<ByteArray>()
    private val userText = StringBuilder()
    private val modelText = StringBuilder()
    @Volatile private var turnDone = true

    @Volatile private var running = false
    @Volatile private var started = false           // true from successful setup until shutdown()
    @Volatile private var paused = false            // transient audio-focus loss
    @Volatile private var playbackActive = false
    @Volatile private var lastPlaybackEndMs = 0L
    @Volatile private var headset = false
    @Volatile private var lastActivityMs = 0L
    private var bargeFrames = 0
    private var handsFree = false

    // ---- start ----------------------------------------------------------------------------

    suspend fun start(): LiveStartResult {
        val settings = repository.liveVoiceSettingsFlow.first()
        handsFree = settings.handsFree

        val key = withContext(Dispatchers.IO) { repository.getGeminiLiveKey() }
        if (key.isBlank()) return LiveStartResult.Fallback(R.string.live_err_no_key)
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return LiveStartResult.Fallback(R.string.live_err_permission)
        }

        // Owner Voice: fail closed. If the user asked for it, Live does not run without a usable profile + models.
        var enforce = false
        if (settings.ownerVoiceEnabled) {
            val hasProfile = withContext(Dispatchers.IO) { owner.hasProfile() }
            if (!hasProfile) return LiveStartResult.Fallback(R.string.live_err_owner_profile)
            if (!models.load()) return LiveStartResult.Fallback(R.string.live_err_owner_models)
            enforce = true
            ownerThreshold = settings.ownerStrictness.threshold
        }
        synchronized(gateLock) { gate = OwnerVoiceGate(OwnerVoiceGate.Config(enforce = enforce)) }

        if (!requestFocus()) {
            models.unload()
            return LiveStartResult.Fallback(R.string.live_err_focus)
        }

        val cfg = LiveSetupConfig(
            model = settings.model,
            voiceName = settings.voiceName,
            systemPrompt = withContext(Dispatchers.IO) { runCatching { repository.systemPromptFlow.first() }.getOrDefault("") },
            tools = runCatching { toolProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        )

        running = true
        started = true
        lastActivityMs = SystemClock.elapsedRealtime()
        acquireWakeLock()
        withContext(Dispatchers.Main) {
            router = AudioRouteManager(appContext) { r -> headset = r != AudioRoute.DEVICE }.also { it.start() }
            headset = router?.isHeadsetConnected() == true
        }

        player = AudioPlayer(
            onPlaybackStateChanged = { playing -> onPlayback(playing) },
            onPlaybackAmplitude = { a -> if (playbackActive) controller.updateSessionState { it.copy(rmsNormalized = a) } }
        ).also { it.start() }

        val live = LiveSession(
            context = appContext,
            scope = scope,
            apiKeyProvider = { repository.getGeminiLiveKey() },
            configProvider = { cfg },
            listener = object : LiveSession.Listener {
                override fun onStateChanged(state: LiveState) = handleState(state)
                override fun onServerEvent(event: LiveServerEvent) = handleEvent(event)
            }
        )
        session = live

        val capture = MicCapture(
            context = appContext,
            onChunk = { chunk, amp, freq -> onMicChunk(chunk, amp, freq) },
            onFatal = { Log.w(TAG, "Mic fatal"); fail(R.string.live_err_mic) }
        )
        mic = capture
        if (!capture.start()) {
            shutdown()
            return LiveStartResult.Fallback(R.string.live_err_mic)
        }

        controller.updateSessionState {
            it.copy(
                isSessionActive = true, isLiveVoice = true, liveStatus = appContext.getString(R.string.live_status_connecting),
                sessionPhase = BackgroundSessionPhase.THINKING, userTranscript = "", aiReplyPreview = "",
                errorMessage = null, autoCloseSecondsRemaining = null
            )
        }
        live.start()
        scope.launch { idleWatchdog() }
        return LiveStartResult.Started
    }

    // ---- shutdown -------------------------------------------------------------------------

    /** Idempotent. Returns only after the microphone has really been released (safe to restart wake word). */
    suspend fun shutdown() {
        val wasRunning = started
        started = false
        running = false
        session?.stop(); session = null
        player?.release(); player = null
        val m = mic; mic = null
        m?.stopAndJoin()
        abandonFocus()
        withContext(Dispatchers.Main) { router?.stop(); router = null }
        releaseWakeLock()
        models.unload()
        synchronized(preConnect) { preConnect.clear() }
        synchronized(gateLock) { gate.reset() }
        if (wasRunning) {
            controller.updateSessionState {
                it.copy(isSessionActive = false, isLiveVoice = false, liveStatus = "", sessionPhase = BackgroundSessionPhase.IDLE, rmsNormalized = 0f)
            }
        }
        scope.cancel()
    }

    private fun fail(@StringRes reason: Int) {
        if (!running) return
        running = false // prevents repeated callbacks; caller will call shutdown()
        main.post { onFailed(reason) }
    }

    // ---- microphone path (capture thread) ---------------------------------------------------

    private fun onMicChunk(chunk: ByteArray, amp: Float, @Suppress("UNUSED_PARAMETER") freq: AudioFrequencyData) {
        if (!running) return
        if (chunk.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!playbackActive && !paused && controller.state.value.sessionPhase == BackgroundSessionPhase.LISTENING) {
            controller.updateSessionState { it.copy(rmsNormalized = amp) }
        }
        if (paused) return
        if (now - lastPlaybackEndMs < ECHO_TAIL_MS) return // room reverb right after playback

        if (playbackActive) {
            // Over the loudspeaker the mic hears the assistant itself: no barge-in there. With a headset it is safe.
            if (!headset) { bargeFrames = 0; return }
            if (amp > BARGE_IN_AMPLITUDE) bargeFrames++ else bargeFrames = 0
            if (bargeFrames < BARGE_IN_FRAMES) return
            bargeFrames = 0
            player?.flush()
        }

        val action = synchronized(gateLock) { gate.onFrame(chunk, amp) }
        applyGateAction(action)
    }

    private fun applyGateAction(action: OwnerVoiceGate.Action) {
        when (action) {
            OwnerVoiceGate.Action.None -> Unit
            is OwnerVoiceGate.Action.Forward -> action.chunks.forEach { sendUp(it) }
            is OwnerVoiceGate.Action.Verify -> scope.launch {
                val res = owner.verify(action.pcm, ownerThreshold)
                Log.i(TAG, "Owner check: match=${res.isMatch} dist=${"%.2f".format(res.distance)} thr=${res.threshold}")
                val next = synchronized(gateLock) { gate.onVerified(res.isMatch) }
                applyGateAction(next)
            }
        }
    }

    private fun sendUp(chunk: ByteArray) {
        val live = session ?: return
        lastActivityMs = SystemClock.elapsedRealtime()
        if (live.isReady()) {
            synchronized(preConnect) {
                while (preConnect.isNotEmpty()) live.sendAudio(preConnect.poll() ?: break)
            }
            live.sendAudio(chunk)
        } else synchronized(preConnect) {
            if (preConnect.size >= PRE_CONNECT_MAX_CHUNKS) preConnect.pollFirst()
            preConnect.addLast(chunk)
        }
    }

    // ---- server path -----------------------------------------------------------------------

    private fun handleEvent(e: LiveServerEvent) {
        if (!running) return
        when (e) {
            is LiveServerEvent.Audio -> { lastActivityMs = SystemClock.elapsedRealtime(); if (!paused) player?.enqueueAudio(e.pcm24k) }
            is LiveServerEvent.UserTranscript -> {
                if (turnDone) { userText.clear(); modelText.clear(); turnDone = false }
                userText.append(e.text)
                controller.updateSessionState { it.copy(userTranscript = userText.toString().trim(), aiReplyPreview = "") }
            }
            is LiveServerEvent.ModelTranscript -> {
                modelText.append(e.text)
                controller.updateSessionState { it.copy(aiReplyPreview = modelText.toString().trim()) }
            }
            is LiveServerEvent.ModelText -> {
                modelText.append(e.text)
                controller.updateSessionState { it.copy(aiReplyPreview = modelText.toString().trim()) }
            }
            LiveServerEvent.Interrupted -> player?.flush()
            LiveServerEvent.GenerationComplete -> player?.notifyTurnComplete()
            LiveServerEvent.TurnComplete -> {
                player?.notifyTurnComplete()
                turnDone = true
                synchronized(gateLock) { gate.onModelTurnComplete() }
            }
            is LiveServerEvent.ToolCall -> {
                lastActivityMs = SystemClock.elapsedRealtime()
                val exec = toolExecutor
                scope.launch {
                    // Always answer, even on failure, so the model never waits forever on a pending call.
                    val response = try {
                        if (exec == null) JSONObject().put("success", false).put("message", "tool_not_available")
                        else exec(e.name, e.args).let { JSONObject().put("success", it.success).put("message", it.message) }
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        JSONObject().put("success", false).put("message", "tool_error")
                    }
                    lastActivityMs = SystemClock.elapsedRealtime()
                    session?.sendToolResponse(e.id, e.name, response)
                }
            }
            else -> Unit
        }
    }

    private fun onPlayback(playing: Boolean) {
        playbackActive = playing
        if (!playing) lastPlaybackEndMs = SystemClock.elapsedRealtime()
        if (!running) return
        controller.updateSessionState {
            if (playing) it.copy(sessionPhase = BackgroundSessionPhase.SPEAKING)
            else it.copy(sessionPhase = BackgroundSessionPhase.LISTENING, rmsNormalized = 0f)
        }
    }

    private fun handleState(state: LiveState) {
        if (!running) return
        when (state) {
            LiveState.Live -> controller.updateSessionState {
                it.copy(liveStatus = "", errorMessage = null,
                    sessionPhase = if (playbackActive) BackgroundSessionPhase.SPEAKING else BackgroundSessionPhase.LISTENING)
            }
            LiveState.Connecting, LiveState.SettingUp -> controller.updateSessionState {
                it.copy(liveStatus = appContext.getString(R.string.live_status_connecting), sessionPhase = BackgroundSessionPhase.THINKING)
            }
            is LiveState.Reconnecting -> controller.updateSessionState {
                it.copy(liveStatus = appContext.getString(R.string.live_status_reconnecting), sessionPhase = BackgroundSessionPhase.THINKING)
            }
            LiveState.WaitingForNetwork -> controller.updateSessionState {
                it.copy(liveStatus = appContext.getString(R.string.live_status_offline), sessionPhase = BackgroundSessionPhase.THINKING)
            }
            is LiveState.Failed -> fail(
                when (state.reason) {
                    LiveFailure.API_KEY_MISSING -> R.string.live_err_no_key
                    LiveFailure.API_KEY_INVALID -> R.string.live_err_invalid_key
                    LiveFailure.QUOTA -> R.string.live_err_quota
                    LiveFailure.MODEL_REJECTED -> R.string.live_err_model
                    LiveFailure.MAX_RETRIES -> R.string.live_err_retries
                }
            )
            LiveState.Idle, LiveState.Stopped -> Unit
        }
    }

    // ---- idle / audio focus / wake lock ---------------------------------------------------

    private suspend fun idleWatchdog() {
        val limit = if (handsFree) IDLE_TIMEOUT_HANDS_FREE_MS else IDLE_TIMEOUT_MS
        while (scope.isActive && running) {
            delay(5_000)
            if (playbackActive) { lastActivityMs = SystemClock.elapsedRealtime(); continue }
            if (SystemClock.elapsedRealtime() - lastActivityMs > limit) {
                running = false
                main.post { onIdleTimeout() }
                return
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> fail(R.string.live_err_focus)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                paused = true; player?.flush()
            }
            AudioManager.AUDIOFOCUS_GAIN -> paused = false
        }
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener, main).build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        paused = false
    }

    /** Keeps the CPU awake with the screen off. Bounded by a timeout so a bug can never hold it forever. */
    private fun acquireWakeLock() {
        runCatching {
            val limit = (if (handsFree) IDLE_TIMEOUT_HANDS_FREE_MS else IDLE_TIMEOUT_MS) + 60_000L
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${appContext.packageName}:LiveVoice").apply {
                setReferenceCounted(false); acquire(limit)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
