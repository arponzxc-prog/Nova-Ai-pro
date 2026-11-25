package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.MessageSearchRow
import com.example.model.Conversation
import java.util.Calendar

private enum class DateGroup {
    PINNED, TODAY, YESTERDAY, LAST_7_DAYS, OLDER
}

/**
 * চ্যাট হিস্টোরি ও ক্রস-চ্যাট সার্চের জন্য Navigation Drawer কনটেন্ট।
 * তারিখ অনুযায়ী গ্রুপ (আজ, গতকাল, আগের ৭ দিন) এবং পিন করা চ্যাট উপরে দেখায়।
 */
@Composable
fun ChatHistoryDrawerContent(
    conversations: List<Conversation>,
    currentConversationId: String?,
    searchQuery: String,
    searchResults: List<MessageSearchRow>,
    onSearchQueryChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    val groupedConversations = remember(conversations) {
        groupConversationsByDate(conversations)
    }

    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 330.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // শিরোনাম ও New Chat বাটন
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_drawer_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(
                    onClick = onNewChat,
                    modifier = Modifier.testTag("drawer_new_chat_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.new_chat))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ক্রস-চ্যাট সার্চ বক্স
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // সার্চ মোড বনাম সাধারণ হিস্টোরি লিস্ট
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isNotBlank()) {
                    if (searchResults.isEmpty()) {
                        EmptyDrawerMessage(
                            icon = Icons.Outlined.SearchOff,
                            message = stringResource(R.string.no_search_results)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(searchResults, key = { it.messageId }) { row ->
                                SearchResultCard(
                                    row = row,
                                    query = searchQuery,
                                    onClick = {
                                        onSearchQueryChange("")
                                        onSelectConversation(row.conversationId)
                                    }
                                )
                            }
                        }
                    }
                } else if (conversations.isEmpty()) {
                    EmptyDrawerMessage(
                        icon = Icons.Outlined.Forum,
                        message = stringResource(R.string.no_history_yet)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        groupedConversations.forEach { (group, itemsInGroup) ->
                            if (itemsInGroup.isNotEmpty()) {
                                item(key = "header_${group.name}") {
                                    Text(
                                        text = when (group) {
                                            DateGroup.PINNED -> stringResource(R.string.group_pinned)
                                            DateGroup.TODAY -> stringResource(R.string.group_today)
                                            DateGroup.YESTERDAY -> stringResource(R.string.group_yesterday)
                                            DateGroup.LAST_7_DAYS -> stringResource(R.string.group_last_7_days)
                                            DateGroup.OLDER -> stringResource(R.string.group_older)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }

                                items(itemsInGroup, key = { it.id }) { conversation ->
                                    ConversationRowItem(
                                        conversation = conversation,
                                        isSelected = conversation.id == currentConversationId,
                                        onClick = { onSelectConversation(conversation.id) },
                                        onRename = {
                                            renameTarget = conversation
                                            renameInput = conversation.title
                                        },
                                        onTogglePin = { onTogglePin(conversation) },
                                        onDelete = { deleteTarget = conversation }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // সেটিংসে যাওয়ার শর্টকাট
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings_title)) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.testTag("drawer_settings_button")
            )
        }
    }

    // নাম বদলানোর ডায়ালগ
    if (renameTarget != null) {
        val target = renameTarget!!
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    placeholder = { Text(stringResource(R.string.rename_chat_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRenameConversation(target.id, renameInput)
                        }
                        renameTarget = null
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // চ্যাট ডিলিট কনফার্মেশন ডায়ালগ
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_chat)) },
            text = { Text(stringResource(R.string.delete_chat_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(target.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_chat))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ConversationRowItem(
    conversation: Conversation,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (conversation.pinned) Icons.Filled.PushPin else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected || conversation.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_chat)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (conversation.pinned) stringResource(R.string.unpin_chat)
                                else stringResource(R.string.pin_chat)
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_chat), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    row: MessageSearchRow,
    query: String,
    onClick: () -> Unit
) {
    val highlightBg = MaterialTheme.colorScheme.primaryContainer
    val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer

    val highlightedSnippet = remember(row.content, query, highlightBg, highlightFg) {
        buildHighlightedSnippet(row.content, query, highlightBg, highlightFg)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = row.conversationTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = highlightedSnippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDrawerMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * সার্চ করা শব্দটি মেসেজের ভেতর হাইলাইট করে স্নিপেট তৈরি করে (বাংলা ও ইংরেজি উভয়ের জন্য)।
 */
private fun buildHighlightedSnippet(
    content: String,
    query: String,
    highlightBg: Color,
    highlightFg: Color
): AnnotatedString {
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return AnnotatedString(content)

    val matchIndex = content.indexOf(cleanQuery, ignoreCase = true)
    val startWindow = if (matchIndex > 35) matchIndex - 25 else 0
    val snippet = if (startWindow > 0) "…" + content.substring(startWindow) else content

    return buildAnnotatedString {
        var cursor = 0
        while (cursor < snippet.length) {
            val found = snippet.indexOf(cleanQuery, startIndex = cursor, ignoreCase = true)
            if (found < 0) {
                append(snippet.substring(cursor))
                break
            }
            if (found > cursor) {
                append(snippet.substring(cursor, found))
            }
            withStyle(
                SpanStyle(
                    background = highlightBg,
                    color = highlightFg,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(snippet.substring(found, found + cleanQuery.length))
            }
            cursor = found + cleanQuery.length
        }
    }
}

private fun groupConversationsByDate(conversations: List<Conversation>): Map<DateGroup, List<Conversation>> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val startOfYesterday = startOfToday - 24L * 60 * 60 * 1000
    val startOf7DaysAgo = startOfToday - 7L * 24 * 60 * 60 * 1000

    val result = linkedMapOf(
        DateGroup.PINNED to mutableListOf<Conversation>(),
        DateGroup.TODAY to mutableListOf<Conversation>(),
        DateGroup.YESTERDAY to mutableListOf<Conversation>(),
        DateGroup.LAST_7_DAYS to mutableListOf<Conversation>(),
        DateGroup.OLDER to mutableListOf<Conversation>()
    )

    for (conv in conversations) {
        val group = when {
            conv.pinned -> DateGroup.PINNED
            conv.updatedAt >= startOfToday -> DateGroup.TODAY
            conv.updatedAt >= startOfYesterday -> DateGroup.YESTERDAY
            conv.updatedAt >= startOf7DaysAgo -> DateGroup.LAST_7_DAYS
            else -> DateGroup.OLDER
        }
        result[group]?.add(conv)
    }
    return result
}
