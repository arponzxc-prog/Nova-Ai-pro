package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.accessibility.AppAccessPolicy
import com.example.accessibility.ScreenSecurityGuard
import com.example.viewmodel.MainViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenSecuritySettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    val allowlistMode by viewModel.allowlistMode.collectAsStateWithLifecycle()
    val customBlockedPackages by viewModel.customBlockedPackages.collectAsStateWithLifecycle()
    val removedDefaultBlockedPackages by viewModel.removedDefaultBlockedPackages.collectAsStateWithLifecycle()
    val allowedPackages by viewModel.allowedPackages.collectAsStateWithLifecycle()
    val uiMaxSteps by viewModel.uiAgentMaxSteps.collectAsStateWithLifecycle()
    val uiFloatingStopEnabled by viewModel.uiFloatingStopEnabled.collectAsStateWithLifecycle()
    val screenshotVisionEnabled by viewModel.screenshotVisionEnabled.collectAsStateWithLifecycle()
    val modelConfigs by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val activeModel = modelConfigs.find { it.isActive } ?: modelConfigs.firstOrNull()

    val policy = remember(allowlistMode, customBlockedPackages, removedDefaultBlockedPackages, allowedPackages) {
        AppAccessPolicy(
            allowlistMode = allowlistMode,
            customBlockedPackages = customBlockedPackages,
            removedDefaultBlockedPackages = removedDefaultBlockedPackages,
            allowedPackages = allowedPackages
        )
    }
    val blockedPackagesList = remember(policy) {
        ScreenSecurityGuard.effectiveBlockedPackages(policy).sorted()
    }
    val installedApps = remember {
        loadInstalledLauncherApps(context.packageManager, context.packageName)
    }
    val appLabelMap = remember(installedApps) {
        installedApps.associate { it.packageName to it.label }
    }

    var manualPkgInput by remember { mutableStateOf("") }
    var showAppPickerForBlocklist by remember { mutableStateOf(false) }
    var showAppPickerForAllowlist by remember { mutableStateOf(false) }
    var pendingDefaultRemovalPkg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_security_card_title)) },
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
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.screen_privacy_disclosure_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = stringResource(R.string.screen_privacy_disclosure_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ui_max_steps_label, uiMaxSteps),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = uiMaxSteps.toFloat(),
                            onValueChange = { viewModel.updateUiAgentMaxSteps(it.roundToInt()) },
                            valueRange = 5f..25f,
                            steps = 19
                        )

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.ui_stop_button_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.ui_stop_button_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiFloatingStopEnabled,
                                onCheckedChange = { viewModel.updateUiFloatingStopEnabled(it) }
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.screenshot_vision_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.screenshot_vision_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (activeModel != null && !activeModel.supportsVision) {
                                    Text(
                                        text = "সক্রিয় মডেল (${activeModel.name})-এ ভিশন অপশন বন্ধ আছে।",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Switch(
                                checked = screenshotVisionEnabled,
                                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                                onCheckedChange = { viewModel.updateScreenshotVisionEnabled(it) }
                            )
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
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.security_mode_allowlist_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.security_mode_allowlist_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = allowlistMode,
                                onCheckedChange = { viewModel.updateAllowlistMode(it) },
                                modifier = Modifier.testTag("allowlist_mode_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${stringResource(R.string.allowlist_section_title)} (${allowedPackages.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            FilledTonalButton(onClick = { showAppPickerForAllowlist = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("অ্যাপ বেছে নিন")
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stringResource(R.string.blocklist_section_title)} (${blockedPackagesList.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showAppPickerForBlocklist = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ইনস্টলড অ্যাপ")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualPkgInput,
                            onValueChange = { manualPkgInput = it },
                            placeholder = { Text(stringResource(R.string.add_app_package_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (manualPkgInput.isNotBlank()) {
                                    viewModel.addCustomBlockedPackage(manualPkgInput)
                                    manualPkgInput = ""
                                }
                            },
                            enabled = manualPkgInput.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }
            }

            items(blockedPackagesList, key = { it }) { pkg ->
                val isDefault = ScreenSecurityGuard.isDefaultProtectedPackage(pkg)
                val appLabel = appLabelMap[pkg]
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDefault) Icons.Outlined.Shield else Icons.Outlined.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = appLabel ?: pkg,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (appLabel != null) {
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (isDefault) {
                                    pendingDefaultRemovalPkg = pkg
                                } else {
                                    viewModel.removeBlockedPackage(pkg, isDefaultProtected = false)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.remove_confirm_btn),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDefaultRemovalPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pendingDefaultRemovalPkg = null },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.remove_default_warning_title)) },
            text = { Text(stringResource(R.string.remove_default_warning_body, appLabelMap[pkg] ?: pkg)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeBlockedPackage(pkg, isDefaultProtected = true)
                        pendingDefaultRemovalPkg = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.remove_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDefaultRemovalPkg = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAppPickerForBlocklist) {
        InstalledAppChecklistDialog(
            title = stringResource(R.string.blocklist_section_title),
            apps = installedApps,
            selectedPackages = blockedPackagesList.toSet(),
            onToggle = { pkg, checked ->
                if (checked) {
                    viewModel.addCustomBlockedPackage(pkg)
                } else {
                    if (ScreenSecurityGuard.isDefaultProtectedPackage(pkg)) {
                        pendingDefaultRemovalPkg = pkg
                    } else {
                        viewModel.removeBlockedPackage(pkg, isDefaultProtected = false)
                    }
                }
            },
            onDismiss = { showAppPickerForBlocklist = false }
        )
    }

    if (showAppPickerForAllowlist) {
        InstalledAppChecklistDialog(
            title = stringResource(R.string.allowlist_section_title),
            apps = installedApps.filterNot { ScreenSecurityGuard.isDefaultProtectedPackage(it.packageName) },
            selectedPackages = allowedPackages,
            onToggle = { pkg, checked -> viewModel.toggleAllowedPackage(pkg, checked) },
            onDismiss = { showAppPickerForAllowlist = false }
        )
    }
}

@Composable
private fun InstalledAppChecklistDialog(
    title: String,
    apps: List<InstalledLauncherApp>,
    selectedPackages: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(apps, search) {
        val q = search.trim().lowercase()
        if (q.isBlank()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.search_apps_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isChecked = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked -> onToggle(app.packageName, checked) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.save))
            }
        }
    )
}
