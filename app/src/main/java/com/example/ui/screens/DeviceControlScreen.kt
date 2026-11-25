package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.tools.SystemTools
import com.example.tools.ToolExecutionContext
import com.example.viewmodel.MainViewModel
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    val batteryPct = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            -1
        }
    }

    val storageInfo = remember {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        String.format(java.util.Locale.getDefault(), "মোট: %.1f GB • খালি: %.1f GB", totalGb, freeGb)
    }

    var flashlightOn by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_control_title)) },
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
            // ফ্ল্যাশলাইট কার্ড
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FlashlightOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.flashlight_toggle), fontWeight = FontWeight.Bold)
                            Text("ক্যামেরা ফ্ল্যাশ বা টর্চলাইট অন/অফ করুন", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(
                        checked = flashlightOn,
                        onCheckedChange = { checked ->
                            flashlightOn = checked
                            val res = SystemTools.toggleFlashlight(
                                ToolExecutionContext(context),
                                JSONObject().put("enabled", checked)
                            )
                            Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // ভলিউম কন্ট্রোল কার্ড
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.volume_control_title), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = currentVolume,
                        onValueChange = { newVal ->
                            currentVolume = newVal
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVal.toInt(), 0)
                        },
                        valueRange = 0f..maxVolume.toFloat(),
                        steps = maxOf(0, maxVolume - 1)
                    )
                }
            }

            // ব্যাটারি ও স্টোরেজ তথ্য কার্ড
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.device_info_title), fontWeight = FontWeight.Bold)
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text("• ব্যাটারি চার্জ: ${if (batteryPct >= 0) "$batteryPct%" else "অজানা"}")
                    Text("• স্টোরেজ: $storageInfo")
                    Text("• অ্যান্ড্রয়েড ভার্সন: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    Text("• ডিভাইস মডেল: ${Build.MANUFACTURER} ${Build.MODEL}")
                }
            }

            // সিস্টেম সেটিংস প্যানেল শর্টকাট
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.open_settings_panel), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Wi-Fi")
                        }
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bluetooth")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sound")
                        }
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Display")
                        }
                    }
                }
            }
        }
    }
}
