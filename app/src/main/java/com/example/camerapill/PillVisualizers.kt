package com.example.camerapill

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private const val USER_BAR_COUNT = 9

/**
 * USER voice visualizer: bar heights are driven by the real microphone level that the existing
 * SpeechRecognizer already reports (AssistantRuntimeState.rmsNormalized / SpeechInputState.rmsNormalized).
 * The level sets the overall amplitude; per-bar shaping only adds a natural spread and is not extra audio data.
 */
@Composable
fun UserVoiceBars(
    level: State<Float>,
    timeSeconds: State<Float>,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val smoothed = remember { FloatArray(USER_BAR_COUNT) }
    Canvas(modifier = modifier) {
        val n = USER_BAR_COUNT
        val gap = size.width * 0.045f
        val barW = (size.width - gap * (n - 1)) / n
        val t = timeSeconds.value
        val lv = level.value.coerceIn(0f, 1f)
        val brush = Brush.verticalGradient(listOf(PillCyan, PillBlue))
        for (i in 0 until n) {
            val centerWeight = 1f - kotlin.math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f) * 0.55f
            val wobble = 0.65f + 0.35f * (sin(t * 9f + i * 1.1f) * 0.5f + 0.5f)
            val target = (0.10f + lv * intensity * centerWeight * wobble).coerceIn(0.08f, 1f)
            val cur = smoothed[i]
            smoothed[i] = cur + (target - cur) * (if (target > cur) 0.55f else 0.18f)
            val bh = max(size.height * smoothed[i], barW)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (barW + gap), (size.height - bh) / 2f),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/**
 * AI voice visualizer (distinct look: flowing blue/violet wave). Android TextToSpeech exposes no PCM/amplitude,
 * so this is synchronised to the real TTS speaking state and drives from a procedural envelope.
 */
@Composable
fun AiVoiceWave(
    active: Boolean,
    energy: State<Float>,
    timeSeconds: State<Float>,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val t = timeSeconds.value
        val amp = if (active) (0.35f + 0.65f * energy.value) * intensity else 0.06f
        val midY = size.height / 2f
        val brush = Brush.horizontalGradient(listOf(PillBlue, PillViolet, PillCyan))
        for (layer in 0..1) {
            val path = Path()
            val steps = 28
            for (s in 0..steps) {
                val x = size.width * s / steps
                val nx = s / steps.toFloat()
                val envelope = sin(nx * PI.toFloat())
                val y = midY + sin(nx * 9f - t * (7f + layer * 3f) + layer) * midY * 0.85f * amp * envelope *
                    (if (layer == 0) 1f else 0.55f)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, brush = brush,
                style = Stroke(width = size.height * (if (layer == 0) 0.11f else 0.06f), cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = if (layer == 0) 1f else 0.5f
            )
        }
    }
}
