package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ActionCardItem
import com.example.model.ActionCardStatus
import com.example.tools.ContactMatch
import com.example.tools.PendingActionConfirmation
import com.example.tools.PendingContactSelection
import com.example.tools.PendingPermissionRequest
import com.example.tools.RiskLevel

/**
 * চ্যাট ও ভয়েস মোডে চলমান ফোন অ্যাকশন দেখানোর অ্যানিমেটেড কার্ড তালিকা।
 */
@Composable
fun ActionCardsBanner(
    cards: List<ActionCardItem>,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cards.forEach { card ->
            ActionStatusCard(card = card)
        }
    }
}

@Composable
fun ActionStatusCard(card: ActionCardItem) {
    val containerColor by animateColorAsState(
        targetValue = when (card.status) {
            ActionCardStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ActionCardStatus.FAILED, ActionCardStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            ActionCardStatus.WAITING_CONFIRMATION -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "actionCardBg"
    )

    val statusLabel = when (card.status) {
        ActionCardStatus.PENDING -> stringResource(R.string.action_status_pending)
        ActionCardStatus.WAITING_CONFIRMATION -> stringResource(R.string.action_status_waiting_confirm)
        ActionCardStatus.RUNNING -> stringResource(R.string.action_status_running)
        ActionCardStatus.SUCCESS -> stringResource(R.string.action_status_success)
        ActionCardStatus.FAILED -> stringResource(R.string.action_status_failed)
        ActionCardStatus.CANCELLED -> stringResource(R.string.action_status_cancelled)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = toolIconFor(card.toolName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (card.summary.isNotBlank()) {
                    Text(
                        text = card.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (card.detailMessage.isNotBlank() &&
                    (card.status == ActionCardStatus.SUCCESS || card.status == ActionCardStatus.FAILED)
                ) {
                    Text(
                        text = card.detailMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (card.status) {
                ActionCardStatus.RUNNING, ActionCardStatus.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                ActionCardStatus.SUCCESS -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = statusLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ActionCardStatus.FAILED, ActionCardStatus.CANCELLED -> {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = statusLabel,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ActionCardStatus.WAITING_CONFIRMATION -> {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = statusLabel,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * HIGH ও MEDIUM-ঝুঁকির অ্যাকশনের জন্য কনফার্মেশন ডায়ালগ (সম্পাদনা + ৬০ সেকেন্ড টাইমারসহ)।
 */
@Composable
fun ActionConfirmationDialog(
    pending: PendingActionConfirmation,
    onConfirm: (editedSms: String?) -> Unit,
    onCancel: () -> Unit
) {
    var isEditingSms by remember(pending.id) { mutableStateOf(false) }
    var editableSms by remember(pending.id) { mutableStateOf(pending.smsMessage) }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = if (pending.riskLevel == RiskLevel.HIGH) Icons.Outlined.WarningAmber else Icons.Outlined.Shield,
                contentDescription = null,
                tint = if (pending.riskLevel == RiskLevel.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = pending.bengaliTitle,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.confirm_high_risk_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (pending.targetAppName.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.confirm_app_label)} ${pending.targetAppName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (pending.targetButtonLabel.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.confirm_button_label)} ${pending.targetButtonLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (pending.actionContextDetails.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.confirm_context_label)} ${pending.actionContextDetails}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pending.recipientName.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.confirm_recipient_label)} ${pending.recipientName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (pending.phoneNumber.isNotBlank()) {
                    Text(
                        text = "${stringResource(R.string.confirm_number_label)} ${pending.phoneNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (pending.isSms) {
                    Text(
                        text = if (pending.toolName == "send_whatsapp_message") {
                            stringResource(R.string.confirm_message_label)
                        } else {
                            stringResource(R.string.confirm_sms_body_label)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isEditingSms) {
                        OutlinedTextField(
                            value = editableSms,
                            onValueChange = { editableSms = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2,
                            maxLines = 5
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = editableSms,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                } else if (pending.summaryText.isNotBlank() && pending.recipientName.isBlank() && pending.targetButtonLabel.isBlank()) {
                    Text(
                        text = pending.summaryText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (pending.isVoiceListeningForConfirm) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.voice_confirm_listening),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.confirm_timeout_hint, pending.remainingSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pending.isSms && !isEditingSms) {
                    TextButton(
                        onClick = { isEditingSms = true },
                        modifier = Modifier.testTag("confirm_edit_sms_btn")
                    ) {
                        Text(stringResource(R.string.confirm_edit_button))
                    }
                }
                Button(
                    onClick = { onConfirm(if (pending.isSms) editableSms else null) },
                    modifier = Modifier.testTag("confirm_action_btn")
                ) {
                    Text(stringResource(R.string.confirm_action_button))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_action_btn")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * একাধিক কন্ট্যাক্ট মিললে ইউজারকে বেছে নেওয়ার ডায়ালগ।
 */
@Composable
fun ContactPickerDialog(
    pending: PendingContactSelection,
    onSelect: (ContactMatch) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Contacts, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.contact_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.contact_picker_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pending.matches) { contact ->
                        Card(
                            onClick = { onSelect(contact) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(contact.name, fontWeight = FontWeight.Bold)
                                Text(
                                    text = buildString {
                                        append(contact.phoneNumber)
                                        if (contact.typeLabel.isNotBlank()) append(" • ${contact.typeLabel}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * টুল চালানোর জন্য প্রথমবার পারমিশন (Just-in-time) চাওয়ার বাংলা ব্যাখ্যা ডায়ালগ।
 */
@Composable
fun JustInTimePermissionDialog(
    pending: PendingPermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.tool_permission_title)) },
        text = { Text(stringResource(pending.explanationRes)) },
        confirmButton = {
            Button(onClick = onAllow) {
                Text(stringResource(R.string.tool_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

fun toolIconFor(toolName: String): ImageVector {
    return when (toolName) {
        "make_call" -> Icons.Outlined.Call
        "send_sms" -> Icons.Outlined.Sms
        "search_contacts" -> Icons.Outlined.Contacts
        "set_alarm" -> Icons.Outlined.Alarm
        "set_timer" -> Icons.Outlined.Timer
        "create_calendar_event" -> Icons.Outlined.Event
        "open_app" -> Icons.Outlined.Launch
        "toggle_flashlight" -> Icons.Outlined.FlashlightOn
        "set_volume" -> Icons.Outlined.VolumeUp
        "set_brightness" -> Icons.Outlined.BrightnessHigh
        "wifi_bluetooth" -> Icons.Outlined.Wifi
        "get_device_status" -> Icons.Outlined.BatteryChargingFull
        "play_music" -> Icons.Outlined.MusicNote
        "open_whatsapp_chat", "send_whatsapp_message" -> Icons.Outlined.Chat
        "read_screen" -> Icons.Outlined.Visibility
        "click_element" -> Icons.Outlined.TouchApp
        "type_text" -> Icons.Outlined.Keyboard
        "scroll", "swipe_gesture" -> Icons.Outlined.SwipeVertical
        "global_action" -> Icons.Outlined.Navigation
        "wait_for_screen_change" -> Icons.Outlined.HourglassEmpty
        "get_notifications" -> Icons.Outlined.NotificationsActive
        else -> Icons.Outlined.Build
    }
}
