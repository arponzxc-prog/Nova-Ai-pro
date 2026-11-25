package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
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
import com.example.network.ModelHealthState
import com.example.network.ModelHealthTracker
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailoverSettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val autoFailoverEnabled by viewModel.autoFailoverEnabled.collectAsStateWithLifecycle()
    val models by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val failoverOrderIds by viewModel.failoverOrderIds.collectAsStateWithLifecycle()
    val cooldowns by viewModel.modelCooldowns.collectAsStateWithLifecycle()

    val orderedModels = remember(models, failoverOrderIds) {
        val byId = models.associateBy { it.id }
        val result = mutableListOf<com.example.model.ModelConfig>()
        val seen = HashSet<String>()
        failoverOrderIds.forEach { id ->
            byId[id]?.let {
                if (seen.add(it.id)) result.add(it)
            }
        }
        models.forEach { cfg ->
            if (seen.add(cfg.id)) result.add(cfg)
        }
        result
    }

    val resetDoneMsg = stringResource(R.string.failover_cooldown_reset_done)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.failover_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
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
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.failover_enable_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.failover_enable_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = autoFailoverEnabled,
                                onCheckedChange = { viewModel.updateAutoFailoverEnabled(it) },
                                modifier = Modifier.testTag("failover_toggle_switch")
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.resetAllModelCooldowns()
                                Toast.makeText(context, resetDoneMsg, Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reset_cooldowns_btn")
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.failover_reset_cooldowns))
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.failover_priority_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            if (orderedModels.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.no_models_warning),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(orderedModels, key = { _, item -> item.id }) { index, cfg ->
                    val hasKey by produceState(initialValue = false, key1 = cfg.id, key2 = models) {
                        value = viewModel.getApiKeyAsync(cfg.id).isNotBlank()
                    }
                    val healthPair = remember(cfg.id, hasKey, cooldowns) {
                        ModelHealthTracker.shared.statusFor(cfg.id, hasKey)
                    }
                    val healthState = healthPair.first
                    val remainingSec = healthPair.second

                    val statusText = when (healthState) {
                        ModelHealthState.NO_KEY -> stringResource(R.string.failover_status_no_key)
                        ModelHealthState.COOLDOWN -> stringResource(R.string.failover_status_cooldown, remainingSec.toInt())
                        ModelHealthState.OK -> stringResource(R.string.failover_status_ok)
                    }
                    val badgeContainer = when (healthState) {
                        ModelHealthState.OK -> MaterialTheme.colorScheme.primaryContainer
                        ModelHealthState.COOLDOWN -> MaterialTheme.colorScheme.tertiaryContainer
                        ModelHealthState.NO_KEY -> MaterialTheme.colorScheme.errorContainer
                    }
                    val badgeContent = when (healthState) {
                        ModelHealthState.OK -> MaterialTheme.colorScheme.onPrimaryContainer
                        ModelHealthState.COOLDOWN -> MaterialTheme.colorScheme.onTertiaryContainer
                        ModelHealthState.NO_KEY -> MaterialTheme.colorScheme.onErrorContainer
                    }

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (cfg.isActive) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "#${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = cfg.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (cfg.isActive) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = stringResource(R.string.active_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${cfg.providerType.displayName} • ${cfg.modelName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = badgeContainer
                                ) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeContent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.moveFailoverModel(cfg.id, -1) },
                                enabled = index > 0,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("move_up_${cfg.id}")
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.failover_move_up)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.moveFailoverModel(cfg.id, +1) },
                                enabled = index < orderedModels.lastIndex,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("move_down_${cfg.id}")
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.failover_move_down)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
