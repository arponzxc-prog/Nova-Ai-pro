package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ModelConfig
import com.example.security.SecureKeyStore
import com.example.ui.components.ImportFormatHelpDialog
import com.example.ui.components.ImportModelsButton
import com.example.ui.components.ModelImportDialogHost
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAddEdit: (String?) -> Unit,
    onNavigateToFailover: () -> Unit = {}
) {
    val models by viewModel.modelConfigs.collectAsStateWithLifecycle()
    var deleteConfirmModel by remember { mutableStateOf<ModelConfig?>(null) }
    val importState by viewModel.modelImportState.collectAsStateWithLifecycle()
    var showImportHelp by remember { mutableStateOf(false) }

    // Native Android Storage Access Framework ফাইল পিকার (কোনো স্টোরেজ পারমিশন লাগে না)
    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onModelImportFileSelected(uri)
    }
    val launchImportPicker = {
        importFileLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToFailover) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = stringResource(R.string.failover_settings_title)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToAddEdit(null) },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_model))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ImportModelsButton(
                onClick = { launchImportPicker() },
                onHelpClick = { showImportHelp = true },
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp)
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (models.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_models_warning),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_models_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(models, key = { it.id }) { config ->
                        val maskedKey by produceState(initialValue = "", key1 = config.id, key2 = models) {
                            value = SecureKeyStore.maskKey(viewModel.getApiKeyAsync(config.id))
                        }
                        ModelConfigCard(
                            config = config,
                            maskedApiKey = maskedKey,
                            onSetActive = { viewModel.setActiveModel(config.id) },
                            onEdit = { onNavigateToAddEdit(config.id) },
                            onDelete = { deleteConfirmModel = config }
                        )
                    }
                }
            }
            }
        }
    }

    ModelImportDialogHost(
        state = importState,
        onConfirm = { policy -> viewModel.confirmModelImport(policy) },
        onDismiss = { viewModel.dismissModelImport() },
        onPickAnother = {
            viewModel.dismissModelImport()
            launchImportPicker()
        }
    )

    if (showImportHelp) {
        ImportFormatHelpDialog(onDismiss = { showImportHelp = false })
    }

    if (deleteConfirmModel != null) {
        val model = deleteConfirmModel!!
        AlertDialog(
            onDismissRequest = { deleteConfirmModel = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text("${stringResource(R.string.confirm_delete)} (${model.name})") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModelConfig(model.id)
                        deleteConfirmModel = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_model))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmModel = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ModelConfigCard(
    config: ModelConfig,
    maskedApiKey: String,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (config.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = config.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (config.isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(
                                    stringResource(R.string.active_badge),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${config.providerType.displayName} • ${config.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (config.supportsTools) "Tools: ON" else "Tools: OFF"} • ${if (config.supportsStreaming) "Streaming: ON" else "Streaming: OFF"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (config.supportsTools || config.supportsStreaming) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (maskedApiKey.isNotBlank()) "Key: $maskedApiKey" else stringResource(R.string.key_status_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (maskedApiKey.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_model))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_model), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (!config.isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onSetActive,
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.set_active))
                }
            }
        }
    }
}
