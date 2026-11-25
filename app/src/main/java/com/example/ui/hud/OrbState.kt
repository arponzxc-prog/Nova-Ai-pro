/* © ARPON — original NOVA AI code. Pure mapping from runtime state to the HUD orb (JVM-testable). */
package com.example.ui.hud

import com.example.assistant.AssistantRuntimeState
import com.example.assistant.BackgroundSessionPhase

enum class OrbState(val js: String) { IDLE("idle"), LISTENING("listening"), THINKING("thinking"), SPEAKING("speaking") }

object OrbStateMapper {
    /**
     * IDLE when no session is active. During a session the phase decides; Gemini Live connection states
     * (connecting / reconnecting / waiting for network → [AssistantRuntimeState.liveStatus]) show as THINKING, and a
     * confirmation prompt or an error never looks like "listening".
     */
    fun from(s: AssistantRuntimeState): OrbState {
        if (!s.isSessionActive) return OrbState.IDLE
        if (s.isLiveVoice && s.liveStatus.isNotEmpty()) return OrbState.THINKING
        return when (s.sessionPhase) {
            BackgroundSessionPhase.LISTENING -> OrbState.LISTENING
            BackgroundSessionPhase.THINKING -> OrbState.THINKING
            BackgroundSessionPhase.SPEAKING -> OrbState.SPEAKING
            BackgroundSessionPhase.WAITING_CONFIRMATION -> OrbState.THINKING
            BackgroundSessionPhase.IDLE, BackgroundSessionPhase.ERROR -> OrbState.IDLE
        }
    }
}

enum class OrbRenderer { WEBVIEW_3D, CANVAS }

object OrbRendererPolicy {
    /** Lightweight Canvas orb whenever the 3D one would be wasteful or impossible. */
    fun choose(isLowRamDevice: Boolean, batterySaver: Boolean, animationsDisabled: Boolean, webViewUsable: Boolean): OrbRenderer =
        if (!webViewUsable || isLowRamDevice || batterySaver || animationsDisabled) OrbRenderer.CANVAS else OrbRenderer.WEBVIEW_3D
}
