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
import com.example.R

private data class PermissionEntry(
    val key: String,
    val title: String,
    val descriptionRes: Int,
    val icon: ImageVector,
    val isWriteSettings: Boolean = false,
    val isOverlay: Boolean = false
)

/**
 * প্রতিটি প্রয়োজনীয় Android পারমিশনের বর্তমান অবস্থা (দেওয়া/না) ও অনুমতি দেওয়ার স্ক্রিন।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // সিস্টেম সেটিংস থেকে ফিরে এলে পারমিশনের অবস্থা স্বয়ংক্রিয়ভাবে রিফ্রেশ হয়
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshTrigger++
    }

    val entries = remember {
        buildList {
            add(
                PermissionEntry(
                    key = Manifest.permission.RECORD_AUDIO,
                    title = "মাইক্রোফোন (RECORD_AUDIO)",
                    descriptionRes = R.string.mic_permission_rationale,
                    icon = Icons.Outlined.Mic
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PermissionEntry(
                        key = Manifest.permission.POST_NOTIFICATIONS,
                        title = "নোটিফিকেশন (POST_NOTIFICATIONS)",
                        descriptionRes = R.string.notif_dialog_message,
                        icon = Icons.Outlined.Notifications
                    )
                )
            }
            add(
                PermissionEntry(
                    key = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    title = "অন্য অ্যাপের ওপর প্রদর্শন (Overlay)",
                    descriptionRes = R.string.overlay_dialog_message,
                    icon = Icons.Outlined.Layers,
                    isOverlay = true
                )
            )
            add(
                PermissionEntry(
                    key = Manifest.permission.CALL_PHONE,
                    title = "ফোন কল (CALL_PHONE)",
                    descriptionRes = R.string.perm_desc_call_phone,
                    icon = Icons.Outlined.Call
                )
            )
            add(
                PermissionEntry(
                    key = Manifest.permission.SEND_SMS,
                    title = "SMS পাঠানো (SEND_SMS)",
                    descriptionRes = R.string.perm_desc_send_sms,
                    icon = Icons.Outlined.Sms
                )
            )
            add(
                PermissionEntry(
                    key = Manifest.permission.READ_CONTACTS,
                    title = "কন্ট্যাক্টস পড়া (READ_CONTACTS)",
                    descriptionRes = R.string.perm_desc_read_contacts,
                    icon = Icons.Outlined.Contacts
                )
            )
            add(
                PermissionEntry(
                    key = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    title = "সিস্টেম সেটিংস (WRITE_SETTINGS)",
                    descriptionRes = R.string.perm_desc_write_settings,
                    icon = Icons.Outlined.BrightnessHigh,
                    isWriteSettings = true
                )
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_screen_title)) },
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
            items(entries, key = { it.key }) { item ->
                val isGranted = remember(refreshTrigger, item.key) {
                    when {
                        item.isWriteSettings -> Settings.System.canWrite(context)
                        item.isOverlay -> Settings.canDrawOverlays(context)
                        else -> ContextCompat.checkSelfPermission(context, item.key) == PackageManager.PERMISSION_GRANTED
                    }
                }

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
                            tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            Text(
                                text = stringResource(item.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isGranted) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = if (isGranted) stringResource(R.string.permission_granted)
                                    else stringResource(R.string.permission_not_granted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isGranted) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (!isGranted) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    when {
                                        item.isWriteSettings -> {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                        item.isOverlay -> {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                        else -> permLauncher.launch(item.key)
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.permission_grant_btn))
                            }
                        }
                    }
                }
            }
        }
    }
}
