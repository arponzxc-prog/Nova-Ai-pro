package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.assistant.WakeWordStatus
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wake Word টেস্ট স্ক্রিন: লাইভ মাইক্রোফোন অডিও লেভেল, ইঞ্জিন/মডেল অবস্থা,
 * সংবেদনশীলতা স্লাইডার ও ডিটেকশন টাইমলাইন সরাসরি দেখায়।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordTestScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val wakeState by viewModel.wakeWordState.collectAsStateWithLifecycle()
    val detections by viewModel.wakeWordDetections.collectAsStateWithLifecycle()
    val wakeThreshold by viewModel.wakeWordThreshold.collectAsStateWithLifecycle()

    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
        if (granted) {
            viewModel.startWakeWordTestMonitor()
        }
    }

    DisposableEffect(hasMicPerm) {
        viewModel.refreshWakeWordDiagnostics()
        if (hasMicPerm) {
            viewModel.startWakeWordTestMonitor()
        }
        onDispose {
            viewModel.stopWakeWordTestMonitor()
        }
    }

    val statusText = when (wakeState.status) {
        WakeWordStatus.LISTENING -> stringResource(R.string.wake_word_status_listening)
        WakeWordStatus.COOLDOWN -> stringResource(R.string.wake_word_status_cooldown)
        WakeWordStatus.MISSING_AAR -> stringResource(R.string.wake_word_status_missing_aar)
        WakeWordStatus.MISSING_MODEL -> stringResource(R.string.wake_word_status_missing_model)
        WakeWordStatus.NO_PERMISSION -> stringResource(R.string.permission_not_granted)
        WakeWordStatus.ERROR -> wakeState.errorDetail ?: stringResource(R.string.voice_state_error)
        WakeWordStatus.STOPPED -> stringResource(R.string.wake_word_status_stopped)
    }

    val badgeColor by animateColorAsState(
        targetValue = when (wakeState.status) {
            WakeWordStatus.COOLDOWN -> MaterialTheme.colorScheme.tertiaryContainer
            WakeWordStatus.LISTENING -> MaterialTheme.colorScheme.primaryContainer
            WakeWordStatus.MISSING_AAR, WakeWordStatus.MISSING_MODEL -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.errorContainer
        },
        label = "wakeStatusBadge"
    )

    val timeFmt = remember { SimpleDateFormat("hh:mm:ss a", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wake_word_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = badgeColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Wake Word: \"${wakeState.activeKeyword}\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                        if (!wakeState.isLibraryPresent || !wakeState.isModelPresent) {
                            Text(
                                text = stringResource(R.string.wake_word_missing_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!hasMicPerm) {
                            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                                Text(stringResource(R.string.mic_permission_allow))
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wake_word_live_audio_level),
                            fontWeight = FontWeight.Bold
                        )
                        LinearProgressIndicator(
                            progress = { wakeState.rmsLevel.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(12.dp)
                        )
                        Text(
                            text = "${stringResource(R.string.wake_word_threshold_label)}: ${String.format("%.2f", wakeThreshold)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = wakeThreshold,
                            onValueChange = { viewModel.updateWakeWordThreshold(it) },
                            valueRange = 0.1f..0.95f
                        )

                        Button(
                            onClick = { viewModel.simulateWakeWordTrigger() },
                            modifier = Modifier.fillMaxWidth().testTag("simulate_wake_trigger_btn")
                        ) {
                            Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.wake_word_simulate_trigger))
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.wake_word_detections_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (detections.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.wake_word_no_detections),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(detections) { ev ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (ev.isSimulated) "${ev.keyword} (টেস্ট)" else ev.keyword,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = timeFmt.format(Date(ev.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
