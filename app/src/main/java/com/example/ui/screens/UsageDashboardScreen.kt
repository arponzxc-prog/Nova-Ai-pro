package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.UsageStatEntity
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DailyBucket(
    val label: String,
    val successCount: Int,
    val failureCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDashboardScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val stats by viewModel.usageStatsLast7Days.collectAsStateWithLifecycle()
    val modelSummaries by viewModel.modelUsageSummary.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    val totalRequests = stats.size
    val successRequests = stats.count { it.success }
    val totalTokens = stats.sumOf { it.inputTokens.toLong() + it.outputTokens.toLong() }
    val failoverCount = stats.count { it.wasFailover }
    val successRate = if (totalRequests > 0) (successRequests * 100) / totalRequests else 0

    val dailyBuckets = remember(stats) { build7DayBuckets(stats) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    if (stats.isNotEmpty() || modelSummaries.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.testTag("clear_usage_btn")
                        ) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.usage_clear_btn),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UsageKpiCard(
                        title = "Requests (7d)",
                        value = totalRequests.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    UsageKpiCard(
                        title = "Tokens (7d)",
                        value = formatCompactNumber(totalTokens),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UsageKpiCard(
                        title = "Success Rate",
                        value = "$successRate%",
                        modifier = Modifier.weight(1f)
                    )
                    UsageKpiCard(
                        title = "Failovers",
                        value = failoverCount.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.BarChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.usage_dashboard_subtitle),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val primaryColor = MaterialTheme.colorScheme.primary
                        val errorColor = MaterialTheme.colorScheme.error
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        ) {
                            val maxTotal = (dailyBuckets.maxOfOrNull { it.successCount + it.failureCount } ?: 0).coerceAtLeast(1)
                            val count = dailyBuckets.size.coerceAtLeast(1)
                            val slotWidth = size.width / count
                            val barWidth = (slotWidth * 0.52f).coerceAtLeast(10f)

                            dailyBuckets.forEachIndexed { idx, bucket ->
                                val x = idx * slotWidth + (slotWidth - barWidth) / 2f
                                drawRoundRect(
                                    color = trackColor,
                                    topLeft = Offset(x, 4f),
                                    size = Size(barWidth, size.height - 8f),
                                    cornerRadius = CornerRadius(10f, 10f)
                                )
                                val total = bucket.successCount + bucket.failureCount
                                if (total > 0) {
                                    val totalHeight = ((size.height - 8f) * total) / maxTotal.toFloat()
                                    val failHeight = (totalHeight * bucket.failureCount) / total.toFloat()
                                    val okHeight = totalHeight - failHeight
                                    val baseBottom = size.height - 4f

                                    if (okHeight > 0f) {
                                        drawRoundRect(
                                            color = primaryColor,
                                            topLeft = Offset(x, baseBottom - okHeight),
                                            size = Size(barWidth, okHeight),
                                            cornerRadius = CornerRadius(10f, 10f)
                                        )
                                    }
                                    if (failHeight > 0f) {
                                        drawRoundRect(
                                            color = errorColor,
                                            topLeft = Offset(x, baseBottom - okHeight - failHeight),
                                            size = Size(barWidth, failHeight),
                                            cornerRadius = CornerRadius(10f, 10f)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            dailyBuckets.forEach { bucket ->
                                Text(
                                    text = bucket.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (modelSummaries.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.usage_empty),
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(modelSummaries, key = { it.modelId }) { summary ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = summary.modelName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = summary.providerType,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Requests: ${summary.requestCount} (${summary.successCount} OK) • In: ${formatCompactNumber(summary.totalInputTokens)} • Out: ${formatCompactNumber(summary.totalOutputTokens)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.usage_clear_btn)) },
            text = { Text(stringResource(R.string.confirm_delete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearUsageStats()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.usage_clear_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UsageKpiCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun build7DayBuckets(stats: List<UsageStatEntity>): List<DailyBucket> {
    val sdf = SimpleDateFormat("EEE", Locale.getDefault())
    val dayKeyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    val grouped = stats.groupBy { dayKeyFmt.format(Date(it.timestamp)) }
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -6)
    return (0 until 7).map {
        val date = cal.time
        val key = dayKeyFmt.format(date)
        val dayStats = grouped[key].orEmpty()
        val label = sdf.format(date)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        DailyBucket(
            label = label,
            successCount = dayStats.count { s -> s.success },
            failureCount = dayStats.count { s -> !s.success }
        )
    }
}

private fun formatCompactNumber(value: Long): String {
    return when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}
