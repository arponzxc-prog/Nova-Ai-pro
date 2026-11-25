package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.tools.RiskLevel
import com.example.tools.SearchProviderType
import com.example.tools.ToolRegistry
import com.example.ui.components.toolIconFor
import com.example.viewmodel.MainViewModel

/**
 * প্রতিটি ফোন টুল আলাদা অন/অফ, মাঝারি ঝুঁকি কনফার্মেশন, সংবেদনশীল লগ এবং ডুয়াল সিম সেটিংস স্ক্রিন।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val disabledTools by viewModel.disabledTools.collectAsStateWithLifecycle()
    val confirmMediumRisk by viewModel.confirmMediumRisk.collectAsStateWithLifecycle()
    val logSensitiveData by viewModel.logSensitiveData.collectAsStateWithLifecycle()
    val preferredSimSlot by viewModel.preferredSimSlot.collectAsStateWithLifecycle()
    val standardMaxSteps by viewModel.standardMaxSteps.collectAsStateWithLifecycle()

    var showSimDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.confirm_medium_risk_title), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.confirm_medium_risk_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = confirmMediumRisk,
                                onCheckedChange = { viewModel.updateConfirmMediumRisk(it) }
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(stringResource(R.string.log_sensitive_title), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.log_sensitive_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = logSensitiveData,
                                onCheckedChange = { viewModel.updateLogSensitiveData(it) }
                            )
                        }

                        HorizontalDivider()

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.standard_max_steps_label, standardMaxSteps),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = standardMaxSteps.toFloat(),
                                onValueChange = { viewModel.updateStandardMaxSteps(it.toInt()) },
                                valueRange = 3f..15f,
                                steps = 11
                            )
                        }

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.dual_sim_title), fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                val simLabel = when (preferredSimSlot) {
                                    0 -> stringResource(R.string.dual_sim_1)
                                    1 -> stringResource(R.string.dual_sim_2)
                                    else -> stringResource(R.string.dual_sim_auto)
                                }
                                Text(simLabel)
                            },
                            leadingContent = { Icon(Icons.Outlined.SimCard, contentDescription = null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                            modifier = Modifier.clickable { showSimDialog = true }
                        )
                    }
                }
            }

            item {
                WebSearchKeysCard(viewModel = viewModel)
            }

            item {
                Text(
                    text = stringResource(R.string.individual_tools_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                )
            }

            items(ToolRegistry.allTools, key = { it.name }) { tool ->
                val enabled = tool.name !in disabledTools
                val riskLabel = when (tool.riskLevel) {
                    RiskLevel.LOW -> stringResource(R.string.risk_low)
                    RiskLevel.MEDIUM -> stringResource(R.string.risk_medium)
                    RiskLevel.HIGH -> stringResource(R.string.risk_high)
                }
                val riskColor = when (tool.riskLevel) {
                    RiskLevel.LOW -> MaterialTheme.colorScheme.primary
                    RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
                    RiskLevel.HIGH -> MaterialTheme.colorScheme.error
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = toolIconFor(tool.name),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tool.bengaliTitle, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${tool.name} • $riskLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = riskColor
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.setToolEnabled(tool.name, it) }
                        )
                    }
                }
            }
        }
    }

    if (showSimDialog) {
        AlertDialog(
            onDismissRequest = { showSimDialog = false },
            title = { Text(stringResource(R.string.dual_sim_title)) },
            text = {
                Column {
                    listOf(
                        -1 to stringResource(R.string.dual_sim_auto),
                        0 to stringResource(R.string.dual_sim_1),
                        1 to stringResource(R.string.dual_sim_2)
                    ).forEach { (slot, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updatePreferredSimSlot(slot)
                                    showSimDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = preferredSimSlot == slot,
                                onClick = {
                                    viewModel.updatePreferredSimSlot(slot)
                                    showSimDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSimDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun WebSearchKeysCard(viewModel: MainViewModel) {
    var editingProvider by remember { mutableStateOf<SearchProviderType?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.web_search_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.web_search_settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SearchProviderType.entries.forEach { provider ->
                val masked by produceState(initialValue = "", key1 = provider, key2 = refreshTick) {
                    value = viewModel.getMaskedSearchApiKeyAsync(provider)
                }
                val labelRes = when (provider) {
                    SearchProviderType.TAVILY -> R.string.tavily_key_label
                    SearchProviderType.BRAVE -> R.string.brave_key_label
                    SearchProviderType.SERPAPI -> R.string.serpapi_key_label
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingProvider = provider
                            keyDraft = ""
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(labelRes), fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (masked.isNotBlank()) masked else stringResource(R.string.key_status_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (masked.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            editingProvider = provider
                            keyDraft = ""
                        },
                        modifier = Modifier.testTag("edit_search_key_${provider.name.lowercase()}")
                    ) {
                        Text(if (masked.isNotBlank()) stringResource(R.string.edit_model) else stringResource(R.string.save))
                    }
                }
            }
        }
    }

    editingProvider?.let { provider ->
        val labelRes = when (provider) {
            SearchProviderType.TAVILY -> R.string.tavily_key_label
            SearchProviderType.BRAVE -> R.string.brave_key_label
            SearchProviderType.SERPAPI -> R.string.serpapi_key_label
        }
        AlertDialog(
            onDismissRequest = { editingProvider = null },
            title = { Text(stringResource(labelRes)) },
            text = {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(stringResource(R.string.api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSearchApiKey(provider, keyDraft.trim())
                        refreshTick++
                        editingProvider = null
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.saveSearchApiKey(provider, "")
                            refreshTick++
                            editingProvider = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.delete_model))
                    }
                    TextButton(onClick = { editingProvider = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}
