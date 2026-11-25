package com.example.camerapill

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal val PillCyan = Color(0xFF22D3EE)
internal val PillBlue = Color(0xFF3B82F6)
internal val PillViolet = Color(0xFF8B5CF6)
internal val PillErrorRed = Color(0xFFF87171)
internal val PillOfflineGray = Color(0xFF94A3B8)

/** Per-frame visual parameters resolved from avatar state + user settings. */
data class AvatarRenderSpec(
    val state: PillAvatarState,
    val happy: Boolean,
    val animateIdle: Boolean,
    val animateThinking: Boolean,
    val animateSpeaking: Boolean,
    val intensity: Float
)

/**
 * Lightweight vector "2.5D" female assistant avatar drawn on a Canvas.
 * Every animated value is read inside the draw lambda only, so per-frame updates
 * invalidate drawing but never trigger recomposition.
 * This is stylised vector art (gradients + highlights), NOT a rigged 3D model.
 */
@Composable
fun PillAvatar(
    spec: AvatarRenderSpec,
    timeSeconds: State<Float>,
    speechEnergy: State<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawAvatar(spec, timeSeconds.value, speechEnergy.value)
    }
}

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** Eye openness 0..1 with a natural blink (~every 3.6s, 0.16s long) plus a deterministic double blink. */
internal fun blinkOpenness(t: Float, enabled: Boolean): Float {
    if (!enabled) return 1f
    val period = 3.6f
    val local = t % period
    val blinkLen = 0.16f
    val first = if (local < blinkLen) abs(local / blinkLen * 2f - 1f) else 1f
    val dbl = if (((t / period).toInt() % 3 == 2) && local in 0.3f..(0.3f + blinkLen)) {
        abs((local - 0.3f) / blinkLen * 2f - 1f)
    } else 1f
    return min(first, dbl).coerceIn(0.05f, 1f)
}

private fun DrawScope.drawAvatar(spec: AvatarRenderSpec, t: Float, energy: Float) {
    val w = size.width
    val h = size.height
    val s = min(w, h)
    val c = Offset(w / 2f, h / 2f)
    val k = spec.intensity

    val state = spec.state
    val accent = when (state) {
        PillAvatarState.ERROR -> PillErrorRed
        PillAvatarState.OFFLINE -> PillOfflineGray
        PillAvatarState.SPEAKING -> PillBlue
        PillAvatarState.THINKING -> PillViolet
        else -> PillCyan
    }
    val dim = state == PillAvatarState.OFFLINE

    // Subtle float / breathing
    val bob = if (spec.animateIdle) sin(t * 1.6f) * s * 0.012f * k else 0f
    val breathe = if (spec.animateIdle) 1f + sin(t * 1.6f) * 0.012f * k else 1f
    val head = Offset(c.x, c.y + bob)
    val r = s * 0.40f * breathe

    // Glow aura (pulses with speech energy when speaking)
    val pulse = when {
        state == PillAvatarState.SPEAKING && spec.animateSpeaking -> 0.25f + energy * 0.35f * k
        state == PillAvatarState.LISTENING -> 0.28f + (if (spec.animateIdle) (sin(t * 3f) * 0.5f + 0.5f) * 0.14f * k else 0f)
        else -> 0.18f
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = if (dim) 0.08f else pulse), Color.Transparent),
            center = head, radius = r * 1.55f
        ),
        radius = r * 1.55f, center = head
    )

    // Hair (back layer) – flowing violet/blue gradient
    val hairBrush = Brush.linearGradient(
        listOf(if (dim) Color(0xFF475569) else PillViolet, if (dim) Color(0xFF334155) else PillBlue),
        start = Offset(head.x - r, head.y - r), end = Offset(head.x + r, head.y + r)
    )
    val sway = if (spec.animateIdle) sin(t * 1.1f) * r * 0.05f * k else 0f
    val back = Path().apply {
        moveTo(head.x - r * 1.02f, head.y)
        cubicTo(head.x - r * 1.25f, head.y - r * 1.2f, head.x + r * 1.25f, head.y - r * 1.2f, head.x + r * 1.02f, head.y)
        cubicTo(head.x + r * 1.15f + sway, head.y + r * 0.7f, head.x + r * 0.85f + sway, head.y + r * 1.15f, head.x + r * 0.6f + sway, head.y + r * 1.1f)
        lineTo(head.x - r * 0.6f + sway, head.y + r * 1.1f)
        cubicTo(head.x - r * 0.85f + sway, head.y + r * 1.15f, head.x - r * 1.15f + sway, head.y + r * 0.7f, head.x - r * 1.02f, head.y)
        close()
    }
    drawPath(back, hairBrush)

    // Face with soft 3D shading
    val skin = if (dim) Color(0xFFCBD5E1) else Color(0xFFFFE2D1)
    val skinShade = if (dim) Color(0xFF94A3B8) else Color(0xFFF4B9A0)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(skin, skinShade),
            center = Offset(head.x - r * 0.3f, head.y - r * 0.35f), radius = r * 1.5f
        ),
        radius = r * 0.86f, center = Offset(head.x, head.y + r * 0.08f)
    )

    // Fringe (front hair)
    val fringe = Path().apply {
        moveTo(head.x - r * 0.88f, head.y - r * 0.05f)
        cubicTo(head.x - r * 0.9f, head.y - r * 0.95f, head.x + r * 0.9f, head.y - r * 0.95f, head.x + r * 0.88f, head.y - r * 0.05f)
        cubicTo(head.x + r * 0.45f, head.y - r * 0.38f, head.x + r * 0.05f, head.y - r * 0.55f, head.x - r * 0.25f, head.y - r * 0.28f)
        cubicTo(head.x - r * 0.5f, head.y - r * 0.12f, head.x - r * 0.75f, head.y - r * 0.1f, head.x - r * 0.88f, head.y - r * 0.05f)
        close()
    }
    drawPath(fringe, hairBrush)
    // Hair highlight
    drawArc(
        color = Color.White.copy(alpha = 0.35f), startAngle = 200f, sweepAngle = 70f, useCenter = false,
        topLeft = Offset(head.x - r * 0.8f, head.y - r * 0.85f), size = Size(r * 1.6f, r * 1.4f),
        style = Stroke(width = r * 0.06f, cap = StrokeCap.Round)
    )

    // Gaze
    val gazeX = when (state) {
        PillAvatarState.THINKING -> if (spec.animateThinking) sin(t * 1.4f) * r * 0.10f * k + r * 0.06f else 0f
        PillAvatarState.LISTENING -> 0f
        else -> if (spec.animateIdle) sin(t * 0.7f) * r * 0.03f * k else 0f
    }
    val gazeY = if (state == PillAvatarState.THINKING && spec.animateThinking) -r * 0.07f else 0f

    // Eyes
    val eyeY = head.y + r * 0.16f
    val eyeDx = r * 0.36f
    val open = when {
        state == PillAvatarState.OFFLINE -> 0.45f
        else -> blinkOpenness(t, spec.animateIdle)
    }
    val widen = if (state == PillAvatarState.LISTENING || state == PillAvatarState.INTERRUPTED) 1.15f else 1f
    val eyeColor = if (dim) Color(0xFF64748B) else Color(0xFF0E7490)
    for (sign in listOf(-1f, 1f)) {
        val ex = head.x + sign * eyeDx
        if (spec.happy) {
            // ^ ^ happy eyes
            drawArc(
                color = Color(0xFF1E293B), startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(ex - r * 0.13f, eyeY - r * 0.10f), size = Size(r * 0.26f, r * 0.24f),
                style = Stroke(width = r * 0.06f, cap = StrokeCap.Round)
            )
        } else {
            val ew = r * 0.14f * widen
            val eh = r * 0.19f * widen * open
            drawOval(Color.White, Offset(ex - ew, eyeY - eh), Size(ew * 2, max(eh * 2, 1f)))
            val iris = ew * 0.78f
            val ih = max(eh * 0.92f, 0.5f)
            drawOval(
                brush = Brush.radialGradient(listOf(PillCyan, eyeColor), center = Offset(ex + gazeX, eyeY + gazeY), radius = iris),
                topLeft = Offset(ex + gazeX - iris, eyeY + gazeY - ih), size = Size(iris * 2, ih * 2)
            )
            if (open > 0.5f) {
                drawCircle(Color.White, radius = ew * 0.22f, center = Offset(ex + gazeX - iris * 0.3f, eyeY + gazeY - ih * 0.4f))
            }
        }
        // Brows
        val browLift = when (state) {
            PillAvatarState.LISTENING, PillAvatarState.INTERRUPTED -> -r * 0.05f
            PillAvatarState.ERROR -> r * 0.04f
            else -> 0f
        }
        val tilt = if (state == PillAvatarState.ERROR) -sign * r * 0.05f else 0f
        drawLine(
            Color(0xFF312E81).copy(alpha = 0.7f),
            Offset(ex - r * 0.13f, eyeY - r * 0.27f + browLift - tilt),
            Offset(ex + r * 0.13f, eyeY - r * 0.27f + browLift + tilt),
            strokeWidth = r * 0.045f, cap = StrokeCap.Round
        )
    }

    // Cheeks
    val blush = if (spec.happy) 0.45f else if (state == PillAvatarState.SPEAKING) 0.22f else 0.14f
    if (!dim) for (sign in listOf(-1f, 1f)) {
        drawCircle(Color(0xFFFB7185).copy(alpha = blush), r * 0.12f, Offset(head.x + sign * r * 0.55f, head.y + r * 0.42f))
    }

    // Mouth
    val mouthY = head.y + r * 0.56f
    val mouthW = r * 0.22f
    val mouthColor = Color(0xFFBE185D)
    when {
        state == PillAvatarState.SPEAKING && spec.animateSpeaking -> {
            // Layered sines + real-time speech energy envelope => viseme-like open/close
            val wob = (sin(t * 14f) * 0.5f + 0.5f) * 0.6f + (sin(t * 23f + 1.3f) * 0.5f + 0.5f) * 0.4f
            val openAmt = (0.18f + wob * (0.35f + 0.5f * energy)) * k.coerceIn(0.6f, 1.4f)
            val mh = r * 0.22f * openAmt.coerceIn(0.08f, 1f)
            drawOval(mouthColor, Offset(head.x - mouthW * (0.8f + 0.2f * openAmt), mouthY - mh / 2), Size(mouthW * 2 * (0.8f + 0.2f * openAmt), mh * 2))
            drawOval(Color(0xFFFB7185), Offset(head.x - mouthW * 0.5f, mouthY + mh * 0.5f), Size(mouthW, mh * 0.8f))
        }
        spec.happy -> drawArc(
            color = mouthColor, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(head.x - mouthW * 1.2f, mouthY - r * 0.16f), size = Size(mouthW * 2.4f, r * 0.34f),
            style = Stroke(width = r * 0.065f, cap = StrokeCap.Round)
        )
        state == PillAvatarState.THINKING -> drawCircle(mouthColor.copy(alpha = 0.85f), r * 0.045f, Offset(head.x + r * 0.05f, mouthY))
        state == PillAvatarState.ERROR || state == PillAvatarState.OFFLINE -> drawArc(
            color = mouthColor.copy(alpha = 0.8f), startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(head.x - mouthW * 0.9f, mouthY - r * 0.02f), size = Size(mouthW * 1.8f, r * 0.2f),
            style = Stroke(width = r * 0.05f, cap = StrokeCap.Round)
        )
        state == PillAvatarState.INTERRUPTED -> drawOval(mouthColor, Offset(head.x - r * 0.05f, mouthY - r * 0.04f), Size(r * 0.1f, r * 0.12f))
        else -> drawArc(
            color = mouthColor, startAngle = 30f, sweepAngle = 120f, useCenter = false,
            topLeft = Offset(head.x - mouthW, mouthY - r * 0.10f), size = Size(mouthW * 2f, r * 0.2f),
            style = Stroke(width = r * 0.05f, cap = StrokeCap.Round)
        )
    }

    // Thinking dots
    if (state == PillAvatarState.THINKING && spec.animateThinking) {
        for (i in 0..2) {
            val ph = ((t * 1.8f - i * 0.25f) % 1f + 1f) % 1f
            val a = (sin(ph * PI.toFloat()).coerceAtLeast(0f)) * 0.9f + 0.1f
            drawCircle(PillViolet.copy(alpha = a), r * 0.05f, Offset(head.x + r * 0.78f + i * r * 0.14f, head.y - r * 0.72f - i * r * 0.05f))
        }
    }

    // Error / offline badge
    if (state == PillAvatarState.ERROR) {
        drawCircle(PillErrorRed, r * 0.16f, Offset(head.x + r * 0.78f, head.y - r * 0.72f))
        drawLine(Color.White, Offset(head.x + r * 0.78f, head.y - r * 0.80f), Offset(head.x + r * 0.78f, head.y - r * 0.70f), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
        drawCircle(Color.White, r * 0.025f, Offset(head.x + r * 0.78f, head.y - r * 0.63f))
    }
    if (state == PillAvatarState.OFFLINE) {
        drawLine(PillOfflineGray, Offset(head.x - r * 0.9f, head.y + r * 0.95f), Offset(head.x + r * 0.9f, head.y - r * 0.9f), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
    }
}

/** Frame clock that only invalidates draw scopes. [lowPower] throttles to ~25 fps. */
@Composable
fun rememberPillClock(running: Boolean, lowPower: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(running, lowPower) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) time.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
            if (lowPower) kotlinx.coroutines.delay(40L)
        }
    }
    return time
}
