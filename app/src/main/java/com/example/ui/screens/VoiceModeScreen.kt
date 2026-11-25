package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.accessibility.UiAgentStatusPill
import com.example.model.SpeechLanguageOption
import com.example.model.VoicePersonas
import com.example.ui.components.ActionCardsBanner
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.StreamUiStatus
import com.example.viewmodel.VoiceModePhase
import kotlin.math.sin

/**
 * ফুল-স্ক্রিন ভয়েস মোড: হ্যান্ডস-ফ্রি কথোপকথনের জন্য বিভ্রান্তিহীন অ্যানিমেটেড অর্ব ও লাইভ ট্রান্সক্রিপ্ট দেখায়।
 */
@Composable
fun VoiceModeScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val voiceState by viewModel.voiceModeState.collectAsState()
    val speechState by viewModel.speechState.collectAsState()
    val speechLang by viewModel.speechLanguage.collectAsState()
    val voicePersonaId by viewModel.voicePersonaId.collectAsStateWithLifecycle()
    val continuousMode by viewModel.continuousVoiceMode.collectAsState()
    val activeActionCards by viewModel.activeActionCards.collectAsStateWithLifecycle()
    val runtimeState by viewModel.assistantRuntimeState.collectAsStateWithLifecycle()
    val streamUiStatus by viewModel.streamUiStatus.collectAsStateWithLifecycle()
    val runningToolLabel by viewModel.runningToolLabel.collectAsStateWithLifecycle()
    val slowResponseWarning by viewModel.slowResponseWarning.collectAsStateWithLifecycle()

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val errorColor = MaterialTheme.colorScheme.error

    val activeAccent by animateColorAsState(
        targetValue = when (voiceState.phase) {
            VoiceModePhase.LISTENING -> primaryColor
            VoiceModePhase.THINKING -> tertiaryColor
            VoiceModePhase.SPEAKING -> secondaryColor
            VoiceModePhase.ERROR -> errorColor
            VoiceModePhase.IDLE -> primaryColor.copy(alpha = 0.7f)
        },
        label = "voiceAccent"
    )

    val statusText = when (voiceState.phase) {
        VoiceModePhase.IDLE -> stringResource(R.string.voice_state_idle)
        VoiceModePhase.LISTENING -> stringResource(R.string.voice_state_listening)
        VoiceModePhase.THINKING -> stringResource(R.string.voice_state_thinking)
        VoiceModePhase.SPEAKING -> stringResource(R.string.voice_state_speaking)
        VoiceModePhase.ERROR -> stringResource(R.string.voice_state_error)
    }

    val langLabel = when (speechLang) {
        SpeechLanguageOption.AUTO -> stringResource(R.string.lang_auto)
        SpeechLanguageOption.BENGALI -> stringResource(R.string.lang_bengali)
        SpeechLanguageOption.ENGLISH -> stringResource(R.string.lang_english)
    }

    val currentPersona = remember(voicePersonaId) {
        VoicePersonas.getById(voicePersonaId) ?: VoicePersonas.ALL.first()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        activeAccent.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // টপ হেডার বার
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = activeAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.voice_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            val all = VoicePersonas.ALL
                            val idx = all.indexOfFirst { it.id == voicePersonaId }
                            val nextPersona = all[(idx + 1) % all.size]
                            viewModel.selectVoicePersona(nextPersona)
                        },
                        label = { Text(currentPersona.nameBengali.substringBefore(" ")) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = activeAccent
                            )
                        }
                    )

                    AssistChip(
                        onClick = {
                            // দ্রুত ভাষা পরিবর্তন: Auto -> bn-BD -> en-US
                            val next = when (speechLang) {
                                SpeechLanguageOption.AUTO -> SpeechLanguageOption.BENGALI
                                SpeechLanguageOption.BENGALI -> SpeechLanguageOption.ENGLISH
                                SpeechLanguageOption.ENGLISH -> SpeechLanguageOption.AUTO
                            }
                            viewModel.updateSpeechLanguage(next)
                        },
                        label = { Text(langLabel) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // স্টেট ব্যাজ
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = activeAccent.copy(alpha = 0.15f)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = activeAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }

            // সেন্টার অ্যানিমেটেড অর্ব / ওয়েভফর্ম
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                VoiceVisualizerOrb(
                    phase = voiceState.phase,
                    rmsLevel = speechState.rmsNormalized,
                    accentColor = activeAccent,
                    secondaryColor = tertiaryColor
                )
            }

            AnimatedVisibility(visible = runtimeState.isUiAutomationRunning) {
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    UiAgentStatusPill(
                        label = runtimeState.uiAutomationLabel,
                        onStopClick = { viewModel.stopUiAutomation() }
                    )
                }
            }

            // চলমান ফোন অ্যাকশন কার্ড (ভয়েস মোডে)
            AnimatedVisibility(visible = activeActionCards.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    ActionCardsBanner(cards = activeActionCards)
                }
            }

            // লাইভ ট্রান্সক্রিপ্ট ও AI উত্তর প্রিভিউ কার্ড
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp, max = 210.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (voiceState.phase == VoiceModePhase.ERROR) {
                        val errMsg = voiceState.errorRes?.let { stringResource(it) }
                            ?: voiceState.errorText
                            ?: stringResource(R.string.speech_error_unknown)
                        Text(
                            text = errMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (voiceState.phase == VoiceModePhase.LISTENING) {
                        val liveText = speechState.partialText.ifBlank {
                            stringResource(R.string.voice_transcript_placeholder)
                        }
                        Text(
                            text = liveText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (speechState.partialText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        if (voiceState.userTranscript.isNotBlank()) {
                            Text(
                                text = "${stringResource(R.string.voice_you_said)} ${voiceState.userTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (voiceState.aiReplyPreview.isNotBlank()) {
                            Text(
                                text = voiceState.aiReplyPreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else if (voiceState.phase == VoiceModePhase.THINKING) {
                            val thinkingText = if (streamUiStatus == StreamUiStatus.TOOL_RUNNING) {
                                val toolName = runningToolLabel
                                if (!toolName.isNullOrBlank()) {
                                    stringResource(R.string.ai_running_tool_named, toolName)
                                } else {
                                    stringResource(R.string.ai_running_tool)
                                }
                            } else {
                                stringResource(R.string.voice_state_thinking)
                            }
                            Text(
                                text = thinkingText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (slowResponseWarning && voiceState.phase == VoiceModePhase.THINKING) {
                            Text(
                                text = stringResource(R.string.slow_response_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // কথোপকথন চালু রাখার সুইচ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.voice_continuous_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = continuousMode,
                    onCheckedChange = { viewModel.updateContinuousVoiceMode(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // নিচের অ্যাকশন বাটন (Stop/Interrupt ও Close)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // বন্ধ করার বাটন
                FilledTonalIconButton(
                    onClick = {
                        viewModel.closeVoiceMode()
                        onClose()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("voice_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.voice_close)
                    )
                }

                // প্রাইমারি কন্ট্রোল বাটন (Stop বা আবার বলুন)
                val isRunning = voiceState.phase == VoiceModePhase.LISTENING ||
                    voiceState.phase == VoiceModePhase.THINKING ||
                    voiceState.phase == VoiceModePhase.SPEAKING

                Button(
                    onClick = {
                        if (isRunning) {
                            viewModel.stopOrInterruptVoiceMode()
                        } else {
                            viewModel.startVoiceListening()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                    modifier = Modifier
                        .height(64.dp)
                        .testTag("voice_primary_action_button")
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) stringResource(R.string.voice_stop_interrupt)
                        else stringResource(R.string.voice_tap_to_speak),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Listening (মাইক ভলিউম RMS রিয়্যাক্টিভ), Thinking (ঘূর্ণায়মান রিং), ও Speaking (হারমোনিক ওয়েভ) অ্যানিমেশন।
 */
@Composable
fun VoiceVisualizerOrb(
    phase: VoiceModePhase,
    rmsLevel: Float,
    accentColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier.size(240.dp)
) {
    val smoothRms by animateFloatAsState(
        targetValue = rmsLevel,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "smoothRms"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "voiceOrbLoop")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.24f

        when (phase) {
            VoiceModePhase.LISTENING -> {
                // বাইরের RMS-রিয়্যাক্টিভ রিংগুলো
                val dynamicExpand = smoothRms * (size.minDimension * 0.16f)
                drawCircle(
                    color = accentColor.copy(alpha = 0.14f),
                    radius = baseRadius + dynamicExpand + 28f * pulse,
                    center = center
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.26f),
                    radius = baseRadius + dynamicExpand * 0.65f + 10f,
                    center = center
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor, secondaryColor),
                        center = center,
                        radius = baseRadius + dynamicExpand * 0.35f
                    ),
                    radius = baseRadius + dynamicExpand * 0.35f,
                    center = center
                )
            }
            VoiceModePhase.THINKING -> {
                // Thinking-এর জন্য ঘূর্ণায়মান গ্রেডিয়েন্ট আর্ক
                drawCircle(
                    color = accentColor.copy(alpha = 0.15f),
                    radius = baseRadius * 1.25f,
                    center = center
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accentColor, secondaryColor, Color.Transparent)),
                    startAngle = rotation,
                    sweepAngle = 250f,
                    useCenter = false,
                    style = Stroke(width = 14f, cap = StrokeCap.Round)
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.85f),
                    radius = baseRadius * (0.72f + 0.12f * sin(wavePhase)),
                    center = center
                )
            }
            VoiceModePhase.SPEAKING -> {
                // Speaking-এর জন্য হারমোনিক সাউন্ড ওয়েভ বার
                drawCircle(
                    color = accentColor.copy(alpha = 0.16f),
                    radius = baseRadius * 1.45f,
                    center = center
                )
                val barCount = 9
                val spacing = 18f
                val totalWidth = (barCount - 1) * spacing
                for (i in 0 until barCount) {
                    val x = center.x - totalWidth / 2f + i * spacing
                    val amp = (0.35f + 0.65f * kotlin.math.abs(sin(wavePhase + i * 0.65f)))
                    val halfH = baseRadius * 0.95f * amp
                    drawLine(
                        color = accentColor,
                        start = Offset(x, center.y - halfH),
                        end = Offset(x, center.y + halfH),
                        strokeWidth = 9f,
                        cap = StrokeCap.Round
                    )
                }
            }
            else -> {
                drawCircle(
                    color = accentColor.copy(alpha = 0.20f),
                    radius = baseRadius * 1.15f,
                    center = center
                )
                drawCircle(
                    color = accentColor,
                    radius = baseRadius * 0.85f,
                    center = center
                )
            }
        }
    }
}
