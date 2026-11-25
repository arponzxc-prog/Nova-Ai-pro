/* © ARPON — original NEXA AI code. */
package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.voice.live.LiveConstants
import com.example.voice.live.VoiceMode
import com.example.voice.owner.CaptureMode
import com.example.voice.owner.CapturePhase
import com.example.voice.owner.OwnerVoiceManager
import com.example.voice.owner.OwnerVoiceStrictness
import com.example.voice.owner.VoiceEngineViewModel
import com.example.voice.owner.VoskModelState

/** Settings for the voice engine: Gemini Live key + mode, Hands-free, Owner Voice enrol/test. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceEngineSettingsScreen(
    onNavigateBack: () -> Unit,
    vm: VoiceEngineViewModel = viewModel()
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }

    var pendingMode by remember { mutableStateOf<CaptureMode?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMode?.let { if (granted) vm.startCapture(it) }
        pendingMode = null
    }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun capture(mode: CaptureMode) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startCapture(mode)
        else { pendingMode = mode; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_engine_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("voice_engine_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.voice_engine_intro), style = MaterialTheme.typography.bodyMedium)

            // ---- Voice mode ----
            SectionCard(stringResource(R.string.ve_mode_title)) {
                ModeRow(stringResource(R.string.ve_mode_legacy), settings.mode == VoiceMode.LEGACY) { vm.setMode(VoiceMode.LEGACY) }
                ModeRow(stringResource(R.string.ve_mode_live), settings.mode == VoiceMode.GEMINI_LIVE) { vm.setMode(VoiceMode.GEMINI_LIVE) }
                Text(stringResource(R.string.ve_mode_live_hint), style = MaterialTheme.typography.bodySmall)
            }

            // ---- Gemini key / model / voice ----
            SectionCard(stringResource(R.string.ve_key_title)) {
                Text(
                    if (ui.keyMasked.isEmpty()) stringResource(R.string.ve_key_none) else stringResource(R.string.ve_key_saved, ui.keyMasked),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ve_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("gemini_live_key_field")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text(stringResource(R.string.ve_key_save)) }
                    OutlinedButton(onClick = { vm.clearKey() }, enabled = ui.keyMasked.isNotEmpty()) { Text(stringResource(R.string.ve_key_clear)) }
                }
                OutlinedTextField(
                    value = modelInput, onValueChange = { modelInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ve_model_title)) }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { vm.setModel(modelInput) }, enabled = modelInput.trim() != settings.model) { Text(stringResource(R.string.ve_key_save)) }
                Text(stringResource(R.string.ve_voice_title), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveConstants.SUPPORTED_VOICES.forEach { v ->
                        FilterChip(selected = settings.voiceName == v, onClick = { vm.setVoice(v) }, label = { Text(v) })
                    }
                }
            }

            // ---- Hands-free ----
            SectionCard(stringResource(R.string.ve_handsfree_title)) {
                SwitchRow(stringResource(R.string.ve_handsfree_hint), settings.handsFree) { vm.setHandsFree(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    OutlinedButton(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                        Text(stringResource(R.string.ve_bt_permission))
                    }
                }
            }

            // ---- Owner voice ----
            SectionCard(stringResource(R.string.ve_owner_title)) {
                Text(stringResource(R.string.ve_owner_hint), style = MaterialTheme.typography.bodySmall)

                val stateText = when (val s = modelState) {
                    VoskModelState.Unavailable -> stringResource(R.string.ve_models_unavailable)
                    VoskModelState.NotInstalled -> stringResource(R.string.ve_models_not_installed)
                    is VoskModelState.Installing -> stringResource(R.string.ve_models_installing, (s.progress * 100).toInt())
                    VoskModelState.Installed, VoskModelState.Loaded -> stringResource(R.string.ve_models_installed)
                    is VoskModelState.Failed -> stringResource(R.string.ve_models_failed)
                }
                Text(stateText, fontWeight = FontWeight.Bold)
                (modelState as? VoskModelState.Installing)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (modelState == VoskModelState.NotInstalled || modelState is VoskModelState.Failed) {
                        Button(onClick = { vm.installModels() }) { Text(stringResource(R.string.ve_models_install_btn)) }
                    }
                    if (modelState == VoskModelState.Installed || modelState == VoskModelState.Loaded) {
                        OutlinedButton(onClick = { vm.deleteModels() }) { Text(stringResource(R.string.ve_models_delete_btn)) }
                    }
                }

                val ready = modelState == VoskModelState.Installed || modelState == VoskModelState.Loaded
                SwitchRow(stringResource(R.string.ve_owner_toggle), settings.ownerVoiceEnabled, enabled = ready && ui.hasProfile) { vm.setOwnerEnabled(it) }

                Text(stringResource(R.string.ve_strictness_title), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OwnerVoiceStrictness.entries.forEach { st ->
                        FilterChip(
                            selected = settings.ownerStrictness == st, onClick = { vm.setStrictness(st) },
                            label = { Text(stringResource(when (st) {
                                OwnerVoiceStrictness.STRICT -> R.string.ve_strict
                                OwnerVoiceStrictness.BALANCED -> R.string.ve_balanced
                                OwnerVoiceStrictness.RELAXED -> R.string.ve_relaxed
                            })) }
                        )
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.ve_enroll_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (ui.hasProfile) R.string.ve_profile_present else R.string.ve_profile_absent), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ve_enroll_progress, ui.samplesCaptured, OwnerVoiceManager.ENROLL_SAMPLES_REQUIRED))
                val prompts = listOf(R.string.ve_prompt_1, R.string.ve_prompt_2, R.string.ve_prompt_3)
                Text(stringResource(prompts[ui.samplesCaptured.coerceIn(0, 2)]), fontWeight = FontWeight.Medium)

                if (ui.capture == CapturePhase.RECORDING) {
                    LinearProgressIndicator(progress = { ui.level }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.stopCapture() }) { Text(stringResource(R.string.ve_stop_btn)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { capture(CaptureMode.ENROLL) }, enabled = ready && ui.capture == CapturePhase.IDLE) { Text(stringResource(R.string.ve_record_btn)) }
                        OutlinedButton(onClick = { capture(CaptureMode.TEST) }, enabled = ready && ui.hasProfile && ui.capture == CapturePhase.IDLE) { Text(stringResource(R.string.ve_test_btn)) }
                        TextButton(onClick = { vm.clearProfile() }, enabled = ui.hasProfile) { Text(stringResource(R.string.ve_clear_profile)) }
                    }
                }
                ui.message?.let { m ->
                    Text(stringResource(m.res, *m.args.toTypedArray()), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ve_message"))
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
