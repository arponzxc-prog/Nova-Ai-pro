package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.camerapill.CameraPillMode
import com.example.camerapill.CameraPillSettings
import com.example.viewmodel.MainViewModel
import kotlin.math.roundToInt

/**
 * Camera Pill (AI Live Avatar) settings. Every control writes to DataStore through
 * MainViewModel.updateCameraPillSettings and is consumed live by CameraPillController / the overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPillSettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.cameraPillSettings.collectAsStateWithLifecycle()
    val runtime by viewModel.assistantRuntimeState.collectAsStateWithLifecycle()
    val overlayVisible by viewModel.cameraPillOverlayVisible.collectAsStateWithLifecycle()

    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf(false) }
    var pendingPreview by remember { mutableStateOf(false) }

    fun onPermissionResult() {
        hasOverlay = Settings.canDrawOverlays(context)
        viewModel.refreshCameraPill()
        if (hasOverlay) {
            if (pendingEnable) viewModel.updateCameraPillSettings { it.copy(enabled = true) }
            if (pendingPreview) viewModel.previewCameraPill()
        }
        pendingEnable = false
        pendingPreview = false
    }

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionResult()
    }

    // Re-check on resume (user may have toggled the permission in system Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                viewModel.refreshCameraPill()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pill_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.pill_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1 + 10 + 11: master toggle, permission status, preview
            SettingsCard {
                ToggleRow(
                    title = stringResource(R.string.pill_enable_title),
                    subtitle = stringResource(R.string.pill_enable_subtitle),
                    checked = settings.enabled,
                    tag = "pill_enable_switch"
                ) { enable ->
                    if (enable && !hasOverlay) {
                        pendingEnable = true
                        showPermissionDialog = true
                    } else {
                        viewModel.updateCameraPillSettings { it.copy(enabled = enable) }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.pill_overlay_permission_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(if (hasOverlay) R.string.pill_permission_granted else R.string.pill_permission_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!hasOverlay) {
                        Button(onClick = { showPermissionDialog = true }, modifier = Modifier.testTag("pill_grant_permission")) {
                            Text(stringResource(R.string.pill_grant_permission))
                        }
                    }
                }
                Text(
                    stringResource(if (overlayVisible) R.string.pill_status_visible else R.string.pill_status_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        if (hasOverlay) viewModel.previewCameraPill() else {
                            pendingPreview = true
                            showPermissionDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("pill_preview_button")
                ) { Text(stringResource(R.string.pill_preview_button)) }
                Text(
                    stringResource(R.string.pill_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2 + 3: display mode
            SettingsCard {
                Text(stringResource(R.string.pill_mode_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ModeRow(
                    selected = settings.mode == CameraPillMode.ONLY_WHILE_TALKING,
                    title = stringResource(R.string.pill_mode_talking_title),
                    subtitle = stringResource(R.string.pill_mode_talking_subtitle),
                    tag = "pill_mode_talking"
                ) { viewModel.updateCameraPillSettings { it.copy(mode = CameraPillMode.ONLY_WHILE_TALKING) } }
                ModeRow(
                    selected = settings.mode == CameraPillMode.ALWAYS_ON,
                    title = stringResource(R.string.pill_mode_always_title),
                    subtitle = stringResource(R.string.pill_mode_always_subtitle),
                    tag = "pill_mode_always"
                ) { viewModel.updateCameraPillSettings { it.copy(mode = CameraPillMode.ALWAYS_ON) } }

                if (settings.mode == CameraPillMode.ONLY_WHILE_TALKING) {
                    var delaySec by remember(settings.idleHideDelayMs) { mutableFloatStateOf(settings.idleHideDelayMs / 1000f) }
                    LabeledSlider(
                        label = stringResource(R.string.pill_idle_delay_label, delaySec),
                        value = delaySec,
                        range = 1f..8f,
                        steps = 13,
                        tag = "pill_idle_delay_slider",
                        onValueChange = { delaySec = it },
                        onFinished = {
                            viewModel.updateCameraPillSettings { s -> s.copy(idleHideDelayMs = (delaySec * 1000f).roundToInt().toLong()) }
                        }
                    )
                } else if (!runtime.isServiceRunning) {
                    Text(
                        stringResource(R.string.pill_always_needs_service),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 4 - 7: feature toggles
            SettingsCard {
                ToggleRow(
                    stringResource(R.string.pill_avatar_anim_title), stringResource(R.string.pill_avatar_anim_subtitle),
                    settings.avatarAnimationEnabled, "pill_avatar_anim_switch"
                ) { v -> viewModel.updateCameraPillSettings { it.copy(avatarAnimationEnabled = v) } }
                HorizontalDivider()
                ToggleRow(
                    stringResource(R.string.pill_visualizer_title), stringResource(R.string.pill_visualizer_subtitle),
                    settings.audioVisualizerEnabled, "pill_visualizer_switch"
                ) { v -> viewModel.updateCameraPillSettings { it.copy(audioVisualizerEnabled = v) } }
                HorizontalDivider()
                ToggleRow(
                    stringResource(R.string.pill_thinking_title), stringResource(R.string.pill_thinking_subtitle),
                    settings.showThinkingAnimation, "pill_thinking_switch"
                ) { v -> viewModel.updateCameraPillSettings { it.copy(showThinkingAnimation = v) } }
                HorizontalDivider()
                ToggleRow(
                    stringResource(R.string.pill_speaking_title), stringResource(R.string.pill_speaking_subtitle),
                    settings.showSpeakingAnimation, "pill_speaking_switch"
                ) { v -> viewModel.updateCameraPillSettings { it.copy(showSpeakingAnimation = v) } }
            }

            // 8 + 9: sliders
            SettingsCard {
                var size by remember(settings.avatarSize) { mutableFloatStateOf(settings.avatarSize) }
                LabeledSlider(
                    label = stringResource(R.string.pill_size_label, (size * 100).roundToInt()),
                    value = size,
                    range = CameraPillSettings.SIZE_MIN..CameraPillSettings.SIZE_MAX,
                    steps = 13,
                    tag = "pill_size_slider",
                    onValueChange = { size = it },
                    onFinished = { viewModel.updateCameraPillSettings { it.copy(avatarSize = size) } }
                )
                var intensity by remember(settings.animationIntensity) { mutableFloatStateOf(settings.animationIntensity) }
                LabeledSlider(
                    label = stringResource(R.string.pill_intensity_label, (intensity * 100).roundToInt()),
                    value = intensity,
                    range = CameraPillSettings.INTENSITY_MIN..CameraPillSettings.INTENSITY_MAX,
                    steps = 9,
                    tag = "pill_intensity_slider",
                    onValueChange = { intensity = it },
                    onFinished = { viewModel.updateCameraPillSettings { it.copy(animationIntensity = intensity) } }
                )
            }

            Text(
                stringResource(R.string.pill_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showPermissionDialog = false
                pendingEnable = false
                pendingPreview = false
            },
            title = { Text(stringResource(R.string.overlay_dialog_title)) },
            text = { Text(stringResource(R.string.overlay_dialog_message)) },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text(stringResource(R.string.open_app_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    pendingEnable = false
                    pendingPreview = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier.testTag(tag)
        )
    }
}
