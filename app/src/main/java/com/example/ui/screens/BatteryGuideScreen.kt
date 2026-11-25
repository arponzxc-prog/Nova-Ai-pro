package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.assistant.BatteryOemHelper

/**
 * ডিভাইস নির্মাতা (Xiaomi, Oppo, Vivo, Samsung ইত্যাদি) শনাক্ত করে ব্যাকগ্রাউন্ডে
 * অ্যাসিস্ট্যান্ট সচল রাখার জন্য ব্যাটারি অপ্টিমাইজেশন ও অটো-স্টার্ট গাইড স্ক্রিন।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryGuideScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var showBatteryRationale by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val oemBrand = remember { BatteryOemHelper.detectOemBrand() }
    val steps = remember(oemBrand) { BatteryOemHelper.getOemBatterySteps(oemBrand) }
    val isIgnoring = remember(refreshTick) { BatteryOemHelper.isIgnoringBatteryOptimizations(context) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.battery_guide_title)) },
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
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isIgnoring) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isIgnoring) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.health_battery_title),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isIgnoring) stringResource(R.string.health_status_ok)
                            else stringResource(R.string.health_status_issue),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (!isIgnoring) {
                Button(
                    onClick = { showBatteryRationale = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.battery_ignore_opt_btn))
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.battery_detected_oem, oemBrand.displayName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    steps.forEach { step ->
                        Text(text = step, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { BatteryOemHelper.openOemBatteryOrAppSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.battery_open_oem_settings_btn))
                    }
                }
            }
        }
    }

    if (showBatteryRationale) {
        AlertDialog(
            onDismissRequest = { showBatteryRationale = false },
            title = { Text(stringResource(R.string.battery_dialog_title)) },
            text = { Text(stringResource(R.string.battery_dialog_message)) },
            confirmButton = {
                Button(onClick = {
                    showBatteryRationale = false
                    runCatching {
                        context.startActivity(BatteryOemHelper.createIgnoreBatteryOptimizationIntent(context))
                    }.onFailure {
                        BatteryOemHelper.openOemBatteryOrAppSettings(context)
                    }
                }) {
                    Text(stringResource(R.string.mic_permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
