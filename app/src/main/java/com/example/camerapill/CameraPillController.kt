package com.example.camerapill

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import com.example.assistant.AssistantRuntimeController
import com.example.assistant.AssistantRuntimeState
import com.example.assistant.BackgroundSessionPhase
import com.example.data.AiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Process-wide owner of the Camera Pill. It never records audio: it only OBSERVES
 *  - the background voice session (AssistantRuntimeController.state, fed by BackgroundVoiceSessionManager)
 *  - the in-app voice mode (AssistantRuntimeController.inAppVoiceSignal, fed by MainViewModel)
 * both of which already use the app's single SpeechRecognizer / TtsManager.
 *
 * It owns exactly one [CameraPillOverlay], so duplicate overlays cannot occur, and removes it
 * whenever the feature is disabled, permission is lost, or the session/service ends.
 * Idempotent [start]; safe to call from MainActivity and AssistantService.
 */
class CameraPillController(
    context: Context,
    private val repository: AiRepository,
    private val runtime: AssistantRuntimeController
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settingsState: StateFlow<CameraPillSettings> =
        repository.cameraPillSettingsFlow.stateIn(scope, SharingStarted.Eagerly, CameraPillSettings())
    private val themeMode: StateFlow<String> =
        repository.themeModeFlow.stateIn(scope, SharingStarted.Eagerly, "system")

    private val _ui = MutableStateFlow(CameraPillUiModel())
    val ui: StateFlow<CameraPillUiModel> = _ui.asStateFlow()
    private val _mic = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _mic.asStateFlow()

    private val previewState = MutableStateFlow<PillAvatarState?>(null)
    private val previewMic = MutableStateFlow(0f)
    private val online = MutableStateFlow(true)
    private val holdAfterTalking = MutableStateFlow(false)
    private val refreshTick = MutableStateFlow(0)
    private val _overlayVisible = MutableStateFlow(false)
    /** For the settings screen status line. */
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val overlay = CameraPillOverlay(appContext, ui, micLevel) { themeMode.value }

    private var started = false
    private var previewJob: Job? = null
    private var holdJob: Job? = null
    private var permissionPollJob: Job? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    // Transition bookkeeping (touched only from the main-thread collector)
    private var prevPhase = PillSourcePhase.IDLE
    private var wasInteractionActive = false
    private var interruptedUntil = 0L
    private var happyUntil = 0L

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    /** Call after returning from system Settings so a just-granted permission is picked up immediately. */
    fun refresh() { refreshTick.value++ }

    fun start() {
        if (started) return
        started = true
        registerConnectivity()
        registerDisplayListeners()

        scope.launch {
            combine(
                settingsState, effectiveSignalFlow(), previewState, online, holdAfterTalking
            ) { settings, signal, preview, isOnline, hold ->
                Snapshot(settings, signal, preview, isOnline, hold)
            }.combine(refreshTick) { snap, _ -> snap }
                .combine(runtime.state) { snap, rt -> snap.copy(serviceActive = rt.isServiceRunning || rt.isSessionActive) }
                .combine(previewMic) { snap, pm -> snap.copy(previewMic = pm) }
                .collect { render(it) }
        }

        // While the feature is on but permission is missing, notice a grant made in system Settings.
        scope.launch {
            settingsState.collect { s ->
                permissionPollJob?.cancel()
                if (s.enabled) {
                    permissionPollJob = launch {
                        while (true) {
                            delay(3000L)
                            if (!overlay.isShowing) refresh()
                        }
                    }
                }
            }
        }
    }

    private data class Snapshot(
        val settings: CameraPillSettings,
        val signal: PillVoiceSignal,
        val preview: PillAvatarState?,
        val online: Boolean,
        val hold: Boolean,
        val serviceActive: Boolean = false,
        val previewMic: Float = 0f
    )

    private fun effectiveSignalFlow(): StateFlow<PillVoiceSignal> {
        // Background session wins when active; otherwise in-app voice mode.
        val flow = MutableStateFlow(PillVoiceSignal())
        scope.launch {
            combine(runtime.state, runtime.inAppVoiceSignal) { rt, inApp ->
                if (rt.isSessionActive) backgroundSignal(rt) else inApp
            }.collect { flow.value = it }
        }
        return flow
    }

    private fun backgroundSignal(rt: AssistantRuntimeState): PillVoiceSignal {
        val phase = when (rt.sessionPhase) {
            BackgroundSessionPhase.LISTENING -> PillSourcePhase.LISTENING
            BackgroundSessionPhase.THINKING -> PillSourcePhase.THINKING
            BackgroundSessionPhase.SPEAKING -> PillSourcePhase.SPEAKING
            BackgroundSessionPhase.ERROR -> PillSourcePhase.ERROR
            BackgroundSessionPhase.WAITING_CONFIRMATION -> PillSourcePhase.THINKING
            BackgroundSessionPhase.IDLE -> PillSourcePhase.IDLE
        }
        return PillVoiceSignal(
            active = true,
            phase = phase,
            micLevel = if (phase == PillSourcePhase.LISTENING) rt.rmsNormalized else 0f,
            sourceId = "background"
        )
    }

    private fun render(s: Snapshot) {
        val now = SystemClock.elapsedRealtime()
        val settings = s.settings
        val previewing = s.preview != null

        // ---- transition detection (INTERRUPTED / happy / hold) ----
        val phase = s.signal.phase
        if (s.signal.active) {
            if (phase == PillSourcePhase.LISTENING &&
                (prevPhase == PillSourcePhase.SPEAKING || prevPhase == PillSourcePhase.THINKING)
            ) {
                interruptedUntil = now + 700L        // barge-in / stop: smooth return to listening
                scheduleRefresh(700L)
            }
            if (prevPhase == PillSourcePhase.SPEAKING &&
                (phase == PillSourcePhase.IDLE || phase == PillSourcePhase.LISTENING)
            ) {
                happyUntil = now + 1400L             // finished answer: brief happy response
                scheduleRefresh(1400L)
            }
        }
        prevPhase = if (s.signal.active) phase else PillSourcePhase.IDLE

        val interactionActive = CameraPillLogic.isInteractionActive(s.signal)
        if (interactionActive) {
            holdJob?.cancel()
            if (holdAfterTalking.value) holdAfterTalking.value = false
        } else if (wasInteractionActive) {
            holdJob?.cancel()
            holdAfterTalking.value = true
            holdJob = scope.launch {
                delay(settings.idleHideDelayMs)
                holdAfterTalking.value = false
            }
        }
        wasInteractionActive = interactionActive

        // ---- state resolution ----
        val recentlyInterrupted = now < interruptedUntil
        val happy = now < happyUntil && settings.avatarAnimationEnabled
        val avatarState: PillAvatarState
        val expanded: Boolean
        val mic: Float
        if (previewing) {
            avatarState = s.preview!!
            expanded = avatarState != PillAvatarState.IDLE
            mic = s.previewMic
        } else {
            avatarState = CameraPillLogic.deriveAvatarState(s.signal, s.online, recentlyInterrupted)
            expanded = interactionActive
            mic = s.signal.micLevel
        }
        _mic.value = mic

        _ui.value = CameraPillUiModel(
            avatarState = avatarState,
            happy = happy && !previewing && avatarState != PillAvatarState.ERROR,
            expanded = expanded,
            settings = settings
        )

        // ---- overlay lifecycle ----
        val show = CameraPillLogic.shouldShow(
            settings = settings,
            hasOverlayPermission = hasOverlayPermission(),
            signal = s.signal,
            serviceOrSessionActive = s.serviceActive,
            holdAfterTalkingActive = s.hold,
            previewActive = previewing
        )
        if (show) {
            val ok = overlay.show(settings.avatarSize)
            _overlayVisible.value = ok && overlay.isShowing
        } else {
            overlay.hide()
            _overlayVisible.value = false
        }
    }

    private fun scheduleRefresh(afterMs: Long) {
        scope.launch {
            delay(afterMs + 30L)
            refresh()
        }
    }

    /**
     * Short scripted demo of every state. It uses a synthetic level (sine) and NEVER opens the microphone.
     * Requires overlay permission (shouldShow enforces it).
     */
    fun startPreview() {
        previewJob?.cancel()
        previewJob = scope.launch {
            try {
                val script = listOf(
                    PillAvatarState.LISTENING to 2200L,
                    PillAvatarState.THINKING to 1600L,
                    PillAvatarState.SPEAKING to 2600L,
                    PillAvatarState.IDLE to 900L
                )
                for ((st, dur) in script) {
                    previewState.value = st
                    val end = SystemClock.elapsedRealtime() + dur
                    while (SystemClock.elapsedRealtime() < end) {
                        val t = SystemClock.elapsedRealtime() / 1000f
                        previewMic.value = if (st == PillAvatarState.LISTENING)
                            (0.45f + 0.45f * sin(t * 5.1f) * sin(t * 1.7f)).coerceIn(0f, 1f) else 0f
                        delay(60L)
                    }
                }
            } finally {
                previewState.value = null
                previewMic.value = 0f
            }
        }
    }

    // ---------------- environment listeners ----------------

    private fun registerConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) { online.value = false }
        }
        connectivityCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }   // API 24+, matches minSdk
    }

    private fun registerDisplayListeners() {
        // Rotation / density / font changes
        appContext.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { repositionIfShowing() }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })
        // Fold/unfold, display switches, rotation
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        runCatching {
            dm?.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) { repositionIfShowing() }
            }, null)
        }
    }

    private fun repositionIfShowing() {
        if (overlay.isShowing) overlay.reposition(settingsState.value.avatarSize)
    }
}
