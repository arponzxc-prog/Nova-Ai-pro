package com.example.camerapill

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip

/** Everything the overlay needs to render one frame of the pill. */
data class CameraPillUiModel(
    val avatarState: PillAvatarState = PillAvatarState.IDLE,
    val happy: Boolean = false,
    /** True when the voice pipeline is actively doing something (pill expands). */
    val expanded: Boolean = false,
    val settings: CameraPillSettings = CameraPillSettings()
)

/** Layout constants shared by the overlay window sizing and the composable. */
object PillDimens {
    val collapsedWidth = 118.dp
    val collapsedHeight = 30.dp
    val expandedWidth = 214.dp
    val expandedHeight = 52.dp
    /** Window must fit the expanded pill + glow padding. */
    val glowPadding = 10.dp
}

@Composable
fun CameraPillContent(
    ui: State<CameraPillUiModel>,
    micLevel: State<Float>,
    contentOffsetXPx: Int,
    modifier: Modifier = Modifier
) {
    val model = ui.value
    val settings = model.settings
    val scale = settings.avatarSize
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val state = model.avatarState
    val isSpeaking = state == PillAvatarState.SPEAKING
    val isListening = state == PillAvatarState.LISTENING || state == PillAvatarState.INTERRUPTED

    val targetW = if (model.expanded) PillDimens.expandedWidth else PillDimens.collapsedWidth
    val targetH = if (model.expanded) PillDimens.expandedHeight else PillDimens.collapsedHeight
    val w by animateDpAsState(targetW * scale, tween(320, easing = FastOutSlowInEasing), label = "pillW")
    val h by animateDpAsState(targetH * scale, tween(320, easing = FastOutSlowInEasing), label = "pillH")

    val animating = settings.avatarAnimationEnabled || (settings.audioVisualizerEnabled && (isListening || isSpeaking))
    val clock = rememberPillClock(running = animating, lowPower = state == PillAvatarState.IDLE || state == PillAvatarState.OFFLINE)

    // Speech energy for the avatar mouth/glow. Real mic level while listening; procedural while AI speaks.
    val energy = remember(clock, isSpeaking, micLevel) {
        derivedStateOf {
            if (isSpeaking) (0.5f + 0.5f * kotlin.math.sin(clock.value * 6.5f) * kotlin.math.sin(clock.value * 2.3f + 0.7f)).coerceIn(0f, 1f)
            else micLevel.value
        }
    }

    val accent = when (state) {
        PillAvatarState.ERROR -> PillErrorRed
        PillAvatarState.OFFLINE -> PillOfflineGray
        PillAvatarState.SPEAKING -> PillBlue
        PillAvatarState.THINKING -> PillViolet
        else -> PillCyan
    }
    val glassTop = if (dark) Color(0xE6101424) else Color(0xE61D2740)
    val glassBottom = if (dark) Color(0xE6070912) else Color(0xE6141B2E)
    val shape = RoundedCornerShape(percent = 50)

    val spec = AvatarRenderSpec(
        state = state,
        happy = model.happy,
        animateIdle = settings.avatarAnimationEnabled,
        animateThinking = settings.avatarAnimationEnabled && settings.showThinkingAnimation,
        animateSpeaking = settings.avatarAnimationEnabled && settings.showSpeakingAnimation,
        intensity = settings.animationIntensity
    )
    val statusText = pillStatusDescription(state)

    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(contentOffsetXPx, 0) }
                .padding(PillDimens.glowPadding)
                .width(w)
                .height(h)
                .shadow(8.dp, shape, ambientColor = accent, spotColor = accent)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
                .border(1.2.dp, Brush.horizontalGradient(listOf(PillCyan, PillBlue, PillCyan)), shape)
                .semantics { contentDescription = statusText },
            contentAlignment = Alignment.CenterStart
        ) {
            val avatarSize: Dp = h - 6.dp * scale
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 4.dp * scale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(avatarSize)) {
                    PillAvatar(spec, clock, energy, Modifier.size(avatarSize))
                }
                if (model.expanded && settings.audioVisualizerEnabled) {
                    Box(
                        Modifier
                            .padding(start = 8.dp * scale, end = 10.dp * scale)
                            .weight(1f)
                            .height(h * 0.5f)
                    ) {
                        if (isSpeaking && settings.showSpeakingAnimation) {
                            AiVoiceWave(true, energy, clock, settings.animationIntensity, Modifier.fillMaxSize())
                        } else if (isListening) {
                            UserVoiceBars(micLevel, clock, settings.animationIntensity * 1.6f, Modifier.fillMaxSize())
                        } else if (state == PillAvatarState.THINKING && settings.showThinkingAnimation) {
                            AiVoiceWave(false, energy, clock, settings.animationIntensity, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** TalkBack description for the overlay. */
fun pillStatusDescription(state: PillAvatarState): String = when (state) {
    PillAvatarState.IDLE -> "NOVA AI idle"
    PillAvatarState.LISTENING -> "NOVA AI listening"
    PillAvatarState.THINKING -> "NOVA AI thinking"
    PillAvatarState.SPEAKING -> "NOVA AI speaking"
    PillAvatarState.INTERRUPTED -> "NOVA AI interrupted, listening again"
    PillAvatarState.ERROR -> "NOVA AI error"
    PillAvatarState.OFFLINE -> "NOVA AI offline"
}
