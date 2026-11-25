package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.DuplicatePolicy
import com.example.data.ImportFailure
import com.example.data.ImportIssue
import com.example.data.ModelImportPreview
import com.example.data.ModelImportSummary
import com.example.data.RejectedEntry
import com.example.viewmodel.ModelImportUiState

/** ফাইলে দেখানো নমুনা — `YOUR_API_KEY_HERE` শুধু প্লেসহোল্ডার, কোনো আসল Key নয়। */
private const val SAMPLE_JSON = """{
  "models": [
    {
      "name": "Gemini Flash",
      "provider": "gemini",
      "modelName": "gemini-2.5-flash",
      "apiKey": "YOUR_API_KEY_HERE"
    },
    {
      "name": "OpenRouter Claude",
      "provider": "openai",
      "baseUrl": "https://openrouter.ai/api/v1",
      "modelName": "anthropic/claude-3.5-sonnet",
      "apiKey": "YOUR_API_KEY_HERE",
      "temperature": 0.7,
      "supportsTools": true,
      "supportsVision": false,
      "supportsStreaming": true
    }
  ]
}"""

private const val MAX_REJECTED_SHOWN = 6

/** Models & API Keys পেজের উপরের প্রিমিয়াম IMPORT বাটন। */
@Composable
fun ImportModelsButton(
    onClick: () -> Unit,
    onHelpClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val importCd = stringResource(R.string.import_button_cd)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(scheme.primary, scheme.tertiary)))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = importCd }
                .testTag("import_models_button")
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.FileUpload,
                        contentDescription = null,
                        tint = scheme.onPrimary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.import_button).uppercase(),
                        color = scheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = stringResource(R.string.import_button_subtitle),
                        color = scheme.onPrimary.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        IconButton(onClick = onHelpClick) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.import_help_cd),
                tint = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ImportFormatHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.import_help_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.import_help_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp)
                ) {
                    Text(
                        SAMPLE_JSON,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_help_close)) }
        }
    )
}

/**
 * ইমপোর্ট ফ্লোর সব ডায়ালগ (Reading → Preview → Importing → Done/Failed) এক জায়গায়।
 * [state] Idle হলে কিছুই দেখায় না।
 */
@Composable
fun ModelImportDialogHost(
    state: ModelImportUiState,
    onConfirm: (DuplicatePolicy) -> Unit,
    onDismiss: () -> Unit,
    onPickAnother: () -> Unit
) {
    if (state is ModelImportUiState.Idle) return

    AlertDialog(
        onDismissRequest = {
            // ফাইল পড়া/ইমপোর্ট চলাকালে বাইরে ট্যাপ করে বন্ধ করা যাবে না
            if (state !is ModelImportUiState.Importing && state !is ModelImportUiState.Reading) onDismiss()
        },
        shape = RoundedCornerShape(28.dp),
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .verticalScroll(rememberScrollState())
            ) {
                when (state) {
                    is ModelImportUiState.Reading -> ReadingContent()
                    is ModelImportUiState.Preview -> PreviewContent(state.preview)
                    is ModelImportUiState.Importing -> ImportingContent(state.done, state.total)
                    is ModelImportUiState.Done -> SummaryContent(state.summary)
                    is ModelImportUiState.Failed -> FailedContent(state.reason, state.rejected)
                    ModelImportUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is ModelImportUiState.Preview -> {
                    val p = state.preview
                    if (p.duplicateCount > 0) {
                        // ডুপ্লিকেট থাকলে Skip / Update — দুটোই স্পষ্ট বাটন
                        Column(horizontalAlignment = Alignment.End) {
                            Button(onClick = { onConfirm(DuplicatePolicy.UPDATE) }) {
                                Text(stringResource(R.string.import_action_update))
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { onConfirm(DuplicatePolicy.SKIP) }) {
                                Text(stringResource(R.string.import_action_skip))
                            }
                        }
                    } else {
                        Button(onClick = { onConfirm(DuplicatePolicy.SKIP) }) {
                            Text(stringResource(R.string.import_action_import))
                        }
                    }
                }
                is ModelImportUiState.Done ->
                    Button(onClick = onDismiss) { Text(stringResource(R.string.import_help_close)) }
                is ModelImportUiState.Failed ->
                    Button(onClick = onPickAnother) { Text(stringResource(R.string.import_button)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is ModelImportUiState.Preview ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                is ModelImportUiState.Failed ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                else -> Unit
            }
        }
    )
}

// ------------------------------------------------------------------------------------------
// Dialog bodies
// ------------------------------------------------------------------------------------------

@Composable
private fun ReadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.import_reading_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.import_reading_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreviewContent(p: ModelImportPreview) {
    HeaderIcon(Icons.Outlined.ListAlt, MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.import_preview_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.import_found_label, p.totalFound),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.import_preview_new, p.newCount), fontWeight = FontWeight.SemiBold)
            if (p.duplicateCount > 0) Text(stringResource(R.string.import_preview_dups, p.duplicateCount))
            if (p.repeatInFileCount > 0) Text(stringResource(R.string.import_preview_repeat, p.repeatInFileCount))
            if (p.rejected.isNotEmpty()) {
                Text(
                    stringResource(R.string.import_preview_invalid, p.rejected.size),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    if (p.withoutKeyCount > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_preview_nokey, p.withoutKeyCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (p.duplicateCount > 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.import_dup_prompt),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
    RejectedList(p.rejected)
}

@Composable
private fun ImportingContent(done: Int, total: Int) {
    val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.import_importing_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.import_progress, done, total),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_importing_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryContent(s: ModelImportSummary) {
    HeaderIcon(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.import_summary_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatRow(Icons.Outlined.ListAlt, stringResource(R.string.import_stat_total), s.totalFound, MaterialTheme.colorScheme.onSurface)
            StatRow(Icons.Outlined.CheckCircle, stringResource(R.string.import_stat_imported), s.imported, MaterialTheme.colorScheme.primary)
            StatRow(Icons.Outlined.Sync, stringResource(R.string.import_stat_updated), s.updated, MaterialTheme.colorScheme.tertiary)
            StatRow(Icons.Outlined.SkipNext, stringResource(R.string.import_stat_skipped), s.skipped, MaterialTheme.colorScheme.onSurfaceVariant)
            StatRow(
                Icons.Outlined.ErrorOutline,
                stringResource(R.string.import_stat_failed),
                s.failed,
                if (s.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (s.importedWithoutKey > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_done_nokey, s.importedWithoutKey),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    RejectedList(s.rejected)
}

@Composable
private fun FailedContent(reason: ImportFailure, rejected: List<RejectedEntry>) {
    HeaderIcon(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.import_failed_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(failureMessageRes(reason)), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.import_failed_untouched),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    RejectedList(rejected)
}

@Composable
private fun RejectedList(rejected: List<RejectedEntry>) {
    if (rejected.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.import_rejected_header),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
    rejected.take(MAX_REJECTED_SHOWN).forEach { r ->
        Text(
            stringResource(R.string.import_rejected_row, r.index, r.label, stringResource(issueMessageRes(r.issue))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (rejected.size > MAX_REJECTED_SHOWN) {
        Text(
            stringResource(R.string.import_rejected_more, rejected.size - MAX_REJECTED_SHOWN),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: Int, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), fontWeight = FontWeight.Bold, color = tint)
    }
}

private fun failureMessageRes(reason: ImportFailure): Int = when (reason) {
    ImportFailure.UNREADABLE -> R.string.import_err_unreadable
    ImportFailure.EMPTY_FILE -> R.string.import_err_empty
    ImportFailure.TOO_LARGE -> R.string.import_err_too_large
    ImportFailure.NOT_JSON -> R.string.import_err_not_json
    ImportFailure.UNSUPPORTED_STRUCTURE -> R.string.import_err_structure
    ImportFailure.NO_MODELS -> R.string.import_err_no_models
    ImportFailure.TOO_MANY_MODELS -> R.string.import_err_too_many
    ImportFailure.NO_VALID_ENTRIES -> R.string.import_err_no_valid
    ImportFailure.STORAGE_ERROR -> R.string.import_err_storage
}

private fun issueMessageRes(issue: ImportIssue): Int = when (issue) {
    ImportIssue.NOT_AN_OBJECT -> R.string.import_issue_not_object
    ImportIssue.MISSING_MODEL_NAME -> R.string.import_issue_no_model
    ImportIssue.MISSING_PROVIDER_AND_URL -> R.string.import_issue_no_provider
    ImportIssue.UNKNOWN_PROVIDER -> R.string.import_issue_unknown_provider
    ImportIssue.INVALID_URL -> R.string.import_issue_bad_url
    ImportIssue.INVALID_TEMPERATURE -> R.string.import_issue_bad_temp
    ImportIssue.INVALID_API_KEY -> R.string.import_issue_bad_key
    ImportIssue.FIELD_TOO_LONG -> R.string.import_issue_too_long
}
