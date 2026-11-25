package com.example.ui.screens

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
import com.example.assistant.AssistantTileService
import com.example.assistant.WakeWordStatus
import com.example.viewmodel.MainViewModel

/**
 * ধাপ ৪: ব্যাকগ্রাউন্ড লিসেনিং, Wake Word কনফিগ, ফ্লোটিং বাবল, সময়সূচি ও দ্রুত চালুর সেটিংস স্ক্রিন।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToWakeWordTest: () -> Unit,
    onNavigateToHealthCheck: () -> Unit,
    onNavigateToBatteryGuide: () -> Unit
) {
    val context = LocalContext.current

    val bgEnabled by viewModel.backgroundListeningEnabled.collectAsStateWithLifecycle()
    val runtimeState by viewModel.assistantRuntimeState.collectAsStateWithLifecycle()
    val wakeState by viewModel.wakeWordState.collectAsStateWithLifecycle()
    val wakePhrase by viewModel.wakeWordPhrase.collectAsStateWithLifecycle()
    val wakeThreshold by viewModel.wakeWordThreshold.collectAsStateWithLifecycle()
    val chimeEnabled by viewModel.chimeEnabled.collectAsStateWithLifecycle()
    val bubbleEnabled by viewModel.floatingBubbleEnabled.collectAsStateWithLifecycle()
    val allowLowRiskLocked by viewModel.allowLowRiskLocked.collectAsStateWithLifecycle()
    val chargingOnly by viewModel.chargingOnlyWake.collectAsStateWithLifecycle()
    val nightEnabled by viewModel.nightScheduleEnabled.collectAsStateWithLifecycle()
    val nightStart by viewModel.nightStartHour.collectAsStateWithLifecycle()
    val nightEnd by viewModel.nightEndHour.collectAsStateWithLifecycle()
    val bootReminder by viewModel.bootNotificationEnabled.collectAsStateWithLifecycle()

    var phraseDraft by remember(wakePhrase) { mutableStateOf(wakePhrase) }
    var showNotifDialog by remember { mutableStateOf(false) }
    var showMicDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                showNotifDialog = true
            } else {
                viewModel.setBackgroundListeningEnabled(true)
            }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setBackgroundListeningEnabled(true)
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.updateFloatingBubbleEnabled(true)
        }
    }

    fun tryEnableBackgroundListening() {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            showMicDialog = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            showNotifDialog = true
            return
        }
        viewModel.setBackgroundListeningEnabled(true)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bg_settings_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ১. মাস্টার টগল: ব্যাকগ্রাউন্ড লিসেনিং
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.bg_listening_master_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.bg_listening_master_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bgEnabled || runtimeState.isServiceRunning,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable) tryEnableBackgroundListening()
                                else viewModel.setBackgroundListeningEnabled(false)
                            },
                            modifier = Modifier.testTag("bg_listening_switch")
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (runtimeState.isServiceRunning) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (runtimeState.isServiceRunning) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (runtimeState.isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (runtimeState.isServiceRunning) stringResource(R.string.bg_service_running)
                                else stringResource(R.string.bg_service_stopped),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.bg_android12_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ২. Wake Word সেটিংস ও টেস্ট স্ক্রিন
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.wake_word_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedTextField(
                        value = phraseDraft,
                        onValueChange = {
                            phraseDraft = it
                            viewModel.updateWakeWordPhrase(it)
                        },
                        label = { Text(stringResource(R.string.wake_word_phrase_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Hey Nexa", "Hey Jarvis").forEach { preset ->
                            FilterChip(
                                selected = phraseDraft.equals(preset, ignoreCase = true),
                                onClick = {
                                    phraseDraft = preset
                                    viewModel.updateWakeWordPhrase(preset)
                                },
                                label = { Text(preset) },
                                modifier = Modifier.testTag("wake_preset_${preset.lowercase().replace(' ', '_')}")
                            )
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.wake_word_threshold_label), style = MaterialTheme.typography.bodyMedium)
                            Text(String.format("%.2f", wakeThreshold), fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = wakeThreshold,
                            onValueChange = { viewModel.updateWakeWordThreshold(it) },
                            valueRange = 0.1f..0.95f
                        )
                    }

                    // ইঞ্জিন / মডেল না থাকলে সৎভাবে বাংলায় জানানো (ক্র্যাশ নয়)
                    if (!wakeState.isLibraryPresent || !wakeState.isModelPresent) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (!wakeState.isLibraryPresent) stringResource(R.string.wake_word_status_missing_aar)
                                    else stringResource(R.string.wake_word_status_missing_model),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.wake_word_missing_hint),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    } else if (wakeState.status == WakeWordStatus.LISTENING) {
                        Text(
                            text = stringResource(R.string.wake_word_status_listening),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    FilledTonalButton(
                        onClick = onNavigateToWakeWordTest,
                        modifier = Modifier.fillMaxWidth().testTag("open_wake_word_test_btn")
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.wake_word_test_btn))
                    }
                }
            }

            // ৩. দ্রুত চালু করার উপায় (বাবল, চাইম, Quick Settings Tile, Default Assistant)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.triggers_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.bubble_toggle_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.bubble_toggle_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bubbleEnabled,
                            onCheckedChange = { enable ->
                                if (enable && !Settings.canDrawOverlays(context)) {
                                    showOverlayDialog = true
                                } else {
                                    viewModel.updateFloatingBubbleEnabled(enable)
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.chime_toggle_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.chime_toggle_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = chimeEnabled,
                            onCheckedChange = { viewModel.updateChimeEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.add_qs_tile_btn), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(stringResource(R.string.add_qs_tile_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val sbm = context.getSystemService(StatusBarManager::class.java)
                                val addedMsg = context.getString(R.string.qs_tile_added_msg)
                                sbm?.requestAddTileService(
                                    ComponentName(context, AssistantTileService::class.java),
                                    context.getString(R.string.qs_tile_label),
                                    Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                                    context.mainExecutor
                                ) { status ->
                                    if (status == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                        status == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                    ) {
                                        Toast.makeText(context, addedMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, context.getString(R.string.qs_tile_manual_hint), Toast.LENGTH_LONG).show()
                            }
                        }
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.default_assistant_btn), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(stringResource(R.string.default_assistant_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.Assistant, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                        }
                    )
                }
            }

            // ৪. লক স্ক্রিন নিরাপত্তা
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.lockscreen_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.lockscreen_low_risk_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.lockscreen_low_risk_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowLowRiskLocked,
                            onCheckedChange = { viewModel.updateAllowLowRiskLocked(it) }
                        )
                    }
                }
            }

            // ৫. ব্যাটারি, সময়সূচি ও বুট রিমাইন্ডার
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.battery_schedule_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.charging_only_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.charging_only_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = chargingOnly,
                            onCheckedChange = { viewModel.updateChargingOnlyWake(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.night_schedule_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.night_schedule_subtitle, nightStart, nightEnd),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = nightEnabled,
                            onCheckedChange = { viewModel.updateNightSchedule(it, nightStart, nightEnd) }
                        )
                    }

                    if (nightEnabled) {
                        Text(stringResource(R.string.night_start_hour_label, nightStart), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = nightStart.toFloat(),
                            onValueChange = { viewModel.updateNightSchedule(true, it.toInt(), nightEnd) },
                            valueRange = 18f..23f,
                            steps = 4
                        )
                        Text(stringResource(R.string.night_end_hour_label, nightEnd), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = nightEnd.toFloat(),
                            onValueChange = { viewModel.updateNightSchedule(true, nightStart, it.toInt()) },
                            valueRange = 4f..10f,
                            steps = 5
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.boot_reminder_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.boot_reminder_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bootReminder,
                            onCheckedChange = { viewModel.updateBootNotificationEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.health_check_title), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(stringResource(R.string.health_check_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onNavigateToHealthCheck)
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.battery_guide_title), fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(stringResource(R.string.battery_guide_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onNavigateToBatteryGuide)
                    )
                }
            }

            // ৬. সৎ প্রাইভেসি তথ্য কার্ড
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.privacy_notice_title), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = stringResource(R.string.privacy_notice_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showMicDialog) {
        AlertDialog(
            onDismissRequest = { showMicDialog = false },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_rationale)) },
            confirmButton = {
                Button(onClick = {
                    showMicDialog = false
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.mic_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showMicDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showNotifDialog) {
        AlertDialog(
            onDismissRequest = { showNotifDialog = false },
            title = { Text(stringResource(R.string.notif_dialog_title)) },
            text = { Text(stringResource(R.string.notif_dialog_message)) },
            confirmButton = {
                Button(onClick = {
                    showNotifDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text(stringResource(R.string.mic_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotifDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = { Text(stringResource(R.string.overlay_dialog_title)) },
            text = { Text(stringResource(R.string.overlay_dialog_message)) },
            confirmButton = {
                Button(onClick = {
                    showOverlayDialog = false
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    overlayLauncher.launch(intent)
                }) { Text(stringResource(R.string.open_app_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
