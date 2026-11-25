package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.accessibility.AccessibilityHelper
import com.example.assistant.BatteryOemHelper
import com.example.viewmodel.MainViewModel

private data class HealthCheckItem(
    val id: String,
    val title: String,
    val detail: String,
    val isOk: Boolean,
    val icon: ImageVector,
    val onFix: (() -> Unit)?
)

/**
 * হেলথ চেক স্ক্রিন: সব দরকারি পারমিশন, ব্যাটারি অপ্টিমাইজেশন, সার্ভিস এবং Wake Word ইঞ্জিনের অবস্থা একসাথে দেখায়।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onOpenWakeWordTest: () -> Unit,
    onOpenAccessibilityGuide: () -> Unit,
    onOpenNotificationGuide: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }

    val runtimeState by viewModel.assistantRuntimeState.collectAsStateWithLifecycle()
    val wakeState by viewModel.wakeWordState.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshWakeWordDiagnostics()
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshTick++
    }

    val micOk = remember(refreshTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val notifOk = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val overlayOk = remember(refreshTick) {
        Settings.canDrawOverlays(context)
    }
    val batteryOk = remember(refreshTick) {
        BatteryOemHelper.isIgnoringBatteryOptimizations(context)
    }
    val wakeOk = wakeState.isLibraryPresent && wakeState.isModelPresent
    val a11yOk = remember(refreshTick) {
        AccessibilityHelper.isAccessibilityServiceEnabled(context)
    }
    val notifAccessOk = remember(refreshTick) {
        AccessibilityHelper.isNotificationAccessEnabled(context)
    }

    val items = listOf(
        HealthCheckItem(
            id = "mic",
            title = stringResource(R.string.health_mic_title),
            detail = if (micOk) stringResource(R.string.permission_granted) else stringResource(R.string.permission_not_granted),
            isOk = micOk,
            icon = Icons.Outlined.Mic,
            onFix = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        ),
        HealthCheckItem(
            id = "notif",
            title = stringResource(R.string.health_notif_title),
            detail = if (notifOk) stringResource(R.string.permission_granted) else stringResource(R.string.permission_not_granted),
            isOk = notifOk,
            icon = Icons.Outlined.Notifications,
            onFix = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        ),
        HealthCheckItem(
            id = "overlay",
            title = stringResource(R.string.health_overlay_title),
            detail = if (overlayOk) stringResource(R.string.permission_granted) else stringResource(R.string.permission_not_granted),
            isOk = overlayOk,
            icon = Icons.Outlined.Layers,
            onFix = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { context.startActivity(intent) }
            }
        ),
        HealthCheckItem(
            id = "battery",
            title = stringResource(R.string.health_battery_title),
            detail = if (batteryOk) stringResource(R.string.health_status_ok) else stringResource(R.string.health_status_issue),
            isOk = batteryOk,
            icon = Icons.Outlined.BatteryChargingFull,
            onFix = {
                runCatching { context.startActivity(BatteryOemHelper.createIgnoreBatteryOptimizationIntent(context)) }
                    .onFailure { BatteryOemHelper.openOemBatteryOrAppSettings(context) }
            }
        ),
        HealthCheckItem(
            id = "service",
            title = stringResource(R.string.health_service_title),
            detail = if (runtimeState.isServiceRunning) stringResource(R.string.bg_service_running)
            else stringResource(R.string.bg_service_stopped),
            isOk = runtimeState.isServiceRunning,
            icon = Icons.Outlined.PlayCircle,
            onFix = {
                if (micOk && notifOk) {
                    viewModel.setBackgroundListeningEnabled(true)
                } else if (!micOk) {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        ),
        HealthCheckItem(
            id = "wakeword",
            title = stringResource(R.string.health_wakeword_title),
            detail = when {
                !wakeState.isLibraryPresent -> stringResource(R.string.wake_word_status_missing_aar)
                !wakeState.isModelPresent -> stringResource(R.string.wake_word_status_missing_model)
                else -> stringResource(R.string.health_status_ok)
            },
            isOk = wakeOk,
            icon = Icons.Outlined.GraphicEq,
            onFix = onOpenWakeWordTest
        ),
        HealthCheckItem(
            id = "accessibility",
            title = stringResource(R.string.health_a11y_title),
            detail = if (a11yOk) stringResource(R.string.a11y_status_enabled) else stringResource(R.string.a11y_status_disabled),
            isOk = a11yOk,
            icon = Icons.Outlined.AccessibilityNew,
            onFix = onOpenAccessibilityGuide
        ),
        HealthCheckItem(
            id = "notification_access",
            title = stringResource(R.string.health_notif_access_title),
            detail = if (notifAccessOk) stringResource(R.string.notif_access_status_enabled) else stringResource(R.string.notif_access_status_disabled),
            isOk = notifAccessOk,
            icon = Icons.Outlined.NotificationsActive,
            onFix = onOpenNotificationGuide
        )
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_check_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = if (item.isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (item.isOk) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.health_status_ok),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else if (item.onFix != null) {
                            FilledTonalButton(onClick = item.onFix) {
                                Text(stringResource(R.string.health_fix_btn))
                            }
                        }
                    }
                }
            }
        }
    }
}
