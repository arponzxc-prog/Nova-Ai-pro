/* © ARPON — original NEXA AI code. */
package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import com.example.accessibility.AssistantAccessibilityService
import com.example.automation.core.AutomationFeature
import com.example.automation.core.AutomationSettingsViewModel

/** Global automation kill switch, per-feature toggles, fast command routing, YouTube key and permission status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationControlScreen(
    onNavigateBack: () -> Unit,
    onOpenPermissionCenter: () -> Unit,
    vm: AutomationSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val ytMasked by vm.youtubeKeyMasked.collectAsStateWithLifecycle()
    var ytKey by remember { mutableStateOf("") }

    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val permRows = listOf(
        R.string.auto_perm_accessibility to AssistantAccessibilityService.isAccessibilityServiceEnabled(context),
        R.string.auto_perm_contacts to has(Manifest.permission.READ_CONTACTS),
        R.string.auto_perm_call to has(Manifest.permission.CALL_PHONE),
        R.string.auto_perm_location to has(Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val featureLabels = mapOf(
        AutomationFeature.PHONE_CONTROL to R.string.auto_feat_phone,
        AutomationFeature.MEDIA to R.string.auto_feat_media,
        AutomationFeature.WEB to R.string.auto_feat_web,
        AutomationFeature.YOUTUBE to R.string.auto_feat_youtube,
        AutomationFeature.WHATSAPP to R.string.auto_feat_whatsapp,
        AutomationFeature.SCREEN_AGENT to R.string.auto_feat_screen
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.automation_control_title)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("automation_control_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (s.killSwitch) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_kill_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.auto_kill_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = s.killSwitch, onCheckedChange = { vm.setKillSwitch(it) }, modifier = Modifier.testTag("automation_kill_switch"))
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.auto_features_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AutomationFeature.entries.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(featureLabels.getValue(f)), Modifier.weight(1f))
                            Switch(checked = s.isEnabled(f), onCheckedChange = { vm.setFeature(f, it) }, enabled = !s.killSwitch)
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_fast_title))
                            Text(stringResource(R.string.auto_fast_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = s.fastCommandRouting, onCheckedChange = { vm.setFastRouting(it) }, enabled = !s.killSwitch)
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.auto_perm_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    permRows.forEach { (label, ok) ->
                        Text((if (ok) "✓  " else "✗  ") + stringResource(label), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = onOpenPermissionCenter) { Text(stringResource(R.string.auto_perm_open)) }
                }
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.auto_yt_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.auto_yt_hint), style = MaterialTheme.typography.bodySmall)
                    Text(if (ytMasked.isEmpty()) stringResource(R.string.ve_key_none) else stringResource(R.string.ve_key_saved, ytMasked), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = ytKey, onValueChange = { ytKey = it }, singleLine = true,
                        label = { Text(stringResource(R.string.auto_yt_key_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.saveYoutubeKey(ytKey); ytKey = "" }, enabled = ytKey.isNotBlank()) { Text(stringResource(R.string.ve_key_save)) }
                        OutlinedButton(onClick = { vm.saveYoutubeKey("") }, enabled = ytMasked.isNotEmpty()) { Text(stringResource(R.string.ve_key_clear)) }
                    }
                }
            }
        }
    }
}
