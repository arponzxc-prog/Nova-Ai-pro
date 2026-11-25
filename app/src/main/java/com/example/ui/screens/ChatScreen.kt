package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.accessibility.UiAgentStatusPill
import com.example.model.ChatMessage
import com.example.model.InputMode
import com.example.model.RequestLatencyMetrics
import com.example.ui.components.ActionCardsBanner
import com.example.ui.components.ChatHistoryDrawerContent
import com.example.ui.components.MarkdownText
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.StreamUiStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToModels: () -> Unit,
    onOpenVoiceMode: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val models by viewModel.modelConfigs.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConversationId by viewModel.currentConversationId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val missingBengaliVoice by viewModel.missingBengaliVoice.collectAsStateWithLifecycle()
    val activeActionCards by viewModel.activeActionCards.collectAsStateWithLifecycle()
    val toolsNotSupportedNotice by viewModel.toolsNotSupportedNotice.collectAsStateWithLifecycle()
    val runtimeState by viewModel.assistantRuntimeState.collectAsStateWithLifecycle()
    val streamUiStatus by viewModel.streamUiStatus.collectAsStateWithLifecycle()
    val runningToolLabel by viewModel.runningToolLabel.collectAsStateWithLifecycle()
    val streamingReplyText by viewModel.streamingReplyText.collectAsStateWithLifecycle()
    val slowResponseWarning by viewModel.slowResponseWarning.collectAsStateWithLifecycle()
    val debugInfoEnabled by viewModel.debugInfoEnabled.collectAsStateWithLifecycle()
    val messageMetrics by viewModel.messageMetrics.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val failoverNotice by viewModel.failoverNotice.collectAsStateWithLifecycle()

    val activeModel = models.find { it.isActive } ?: models.firstOrNull()

    var textInput by remember { mutableStateOf("") }
    var showModelBottomSheet by remember { mutableStateOf(false) }
    var showAttachBottomSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.attachImageFromUri(uri)
    }

    val pickDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.attachDocumentFromUri(uri)
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            pendingCameraUri?.let { viewModel.attachImageFromUri(it) }
        }
        pendingCameraUri = null
    }

    // RECORD_AUDIO পারমিশন ডায়ালগ স্টেট
    var showMicRationaleDialog by remember { mutableStateOf(false) }
    var showMicDeniedPoliteDialog by remember { mutableStateOf(false) }
    var showMicPermanentlyDeniedDialog by remember { mutableStateOf(false) }

    val uiPrefs = remember(context) {
        context.getSharedPreferences("ui_permission_prefs", Context.MODE_PRIVATE)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        uiPrefs.edit().putBoolean("mic_asked_before", true).apply()
        if (isGranted) {
            viewModel.openVoiceMode()
            onOpenVoiceMode()
        } else {
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            if (canAskAgain) {
                showMicDeniedPoliteDialog = true
            } else {
                showMicPermanentlyDeniedDialog = true
            }
        }
    }

    fun handleMicButtonClick() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.openVoiceMode()
            onOpenVoiceMode()
            return
        }

        val askedBefore = uiPrefs.getBoolean("mic_asked_before", false)
        val shouldShowRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.RECORD_AUDIO
        )

        if (askedBefore && !shouldShowRationale) {
            showMicPermanentlyDeniedDialog = true
        } else {
            showMicRationaleDialog = true
        }
    }

    // ড্রয়ার খোলা থাকলে ব্যাক প্রেস করলে ড্রয়ার বন্ধ হবে
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    // নতুন মেসেজ বা স্ট্রিমিং শুরু হলে নিচে স্ক্রল; স্ট্রিমিংয়ে ইউজার উপরে স্ক্রল করে পড়তে চাইলে জোর করে টেনে নামাবে না
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty() || isTyping) {
            val target = maxOf(0, messages.size + if (isTyping) 0 else -1)
            listState.animateScrollToItem(target)
        }
    }
    LaunchedEffect(streamingReplyText?.length) {
        if (!streamingReplyText.isNullOrBlank()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0 && lastVisible >= totalItems - 2) {
                listState.scrollToItem(totalItems - 1)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawerContent(
                conversations = conversations,
                currentConversationId = currentConversationId,
                searchQuery = searchQuery,
                searchResults = searchResults,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onNewChat = {
                    viewModel.startNewChat()
                    coroutineScope.launch { drawerState.close() }
                },
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    coroutineScope.launch { drawerState.close() }
                },
                onRenameConversation = { id, newTitle ->
                    viewModel.renameConversation(id, newTitle)
                },
                onTogglePin = { conv ->
                    viewModel.togglePinConversation(conv)
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                },
                onOpenSettings = {
                    coroutineScope.launch { drawerState.close() }
                    onNavigateToSettings()
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("history_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_history)
                            )
                        }
                    },
                    title = {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showModelBottomSheet = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = activeModel?.name ?: stringResource(R.string.no_model_label),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activeModel?.providerType?.displayName
                                        ?: stringResource(R.string.add_model_short),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.select_model))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.startNewChat() },
                            modifier = Modifier.testTag("new_chat_button")
                        ) {
                            Icon(Icons.Outlined.AddComment, contentDescription = stringResource(R.string.new_chat))
                        }
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            bottomBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    AnimatedVisibility(visible = toolsNotSupportedNotice) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tools_not_supported_notice),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.dismissToolsNotSupportedNotice() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = runtimeState.isUiAutomationRunning) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            UiAgentStatusPill(
                                label = runtimeState.uiAutomationLabel,
                                onStopClick = { viewModel.stopUiAutomation() }
                            )
                        }
                    }

                    AnimatedVisibility(visible = activeActionCards.isNotEmpty()) {
                        ActionCardsBanner(
                            cards = activeActionCards,
                            modifier = Modifier.heightIn(max = 220.dp)
                        )
                    }

                    AnimatedVisibility(visible = slowResponseWarning) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.HourglassEmpty,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.slow_response_warning),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { showModelBottomSheet = true },
                                    modifier = Modifier.testTag("switch_model_shortcut_button")
                                ) {
                                    Text(stringResource(R.string.switch_model_shortcut))
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = failoverNotice != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = failoverNotice ?: "",
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.dismissFailoverNotice() },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(
                                    onClick = { viewModel.retryLastMessage() },
                                    modifier = Modifier.testTag("retry_button")
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = pendingAttachment != null) {
                        val att = pendingAttachment
                        if (att != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (att.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = att.displayName,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { viewModel.clearAttachment() },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.cancel)
                                            )
                                        }
                                    }
                                    if (att.isImage && activeModel?.supportsVision != true) {
                                        Text(
                                            text = stringResource(R.string.attach_vision_notice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ইনপুট বার (কীবোর্ড খুললে ঠিকমতো উপরে উঠবে)
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showAttachBottomSheet = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("attach_button")
                            ) {
                                Icon(
                                    Icons.Outlined.AttachFile,
                                    contentDescription = stringResource(R.string.attach_file_desc),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            FilledTonalIconButton(
                                onClick = { handleMicButtonClick() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("mic_button")
                            ) {
                                Icon(
                                    Icons.Outlined.Mic,
                                    contentDescription = stringResource(R.string.voice_input_desc),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                placeholder = { Text(stringResource(R.string.type_message)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp, max = 128.dp)
                                    .testTag("message_input"),
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                maxLines = 4
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            if (isTyping) {
                                FilledIconButton(
                                    onClick = { viewModel.stopGeneration() },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("stop_generation_button")
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.stop_generation)
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        if (textInput.isNotBlank() || pendingAttachment != null) {
                                            viewModel.sendMessage(textInput.trim(), InputMode.TEXT)
                                            textInput = ""
                                        }
                                    },
                                    enabled = textInput.isNotBlank() || pendingAttachment != null,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("send_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.nav_chat)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (models.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_models_warning),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_models_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onNavigateToModels) {
                            Text(stringResource(R.string.configure_model))
                        }
                    }
                } else if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.welcome_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.welcome_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))

                        val suggestions = listOf(
                            stringResource(R.string.suggestion_1),
                            stringResource(R.string.suggestion_2),
                            stringResource(R.string.suggestion_3),
                            stringResource(R.string.suggestion_4)
                        )

                        suggestions.forEach { suggestion ->
                            OutlinedCard(
                                onClick = { viewModel.sendMessage(suggestion, InputMode.TEXT) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 520.dp)
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = suggestion,
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                msg = msg,
                                activeModelName = activeModel?.name,
                                isCurrentlySpeaking = isSpeaking && speakingMessageId == msg.id,
                                onToggleSpeak = { viewModel.toggleSpeakMessage(msg) },
                                metrics = messageMetrics[msg.id],
                                showDebugMetrics = BuildConfig.DEBUG && debugInfoEnabled
                            )
                        }

                        if (!streamingReplyText.isNullOrBlank()) {
                            item(key = "streaming_reply_bubble") {
                                StreamingMessageBubble(
                                    text = streamingReplyText!!,
                                    activeModelName = activeModel?.name
                                )
                            }
                        }

                        if (isTyping && (streamingReplyText.isNullOrBlank() || streamUiStatus == StreamUiStatus.TOOL_RUNNING)) {
                            item(key = "typing_indicator") {
                                val statusLabel = when (streamUiStatus) {
                                    StreamUiStatus.TOOL_RUNNING -> {
                                        val toolName = runningToolLabel
                                        if (!toolName.isNullOrBlank()) {
                                            stringResource(R.string.ai_running_tool_named, toolName)
                                        } else {
                                            stringResource(R.string.ai_running_tool)
                                        }
                                    }
                                    else -> stringResource(R.string.ai_thinking)
                                }
                                TypingIndicatorBubble(label = statusLabel)
                            }
                        }
                    }
                }
            }
        }
    }

    // মডেল পরিবর্তন বটমশিট
    if (showModelBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showModelBottomSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_model),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(14.dp))

                models.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.name) },
                        supportingContent = { Text("${model.providerType.displayName} • ${model.modelName}") },
                        trailingContent = {
                            if (model.isActive) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.active_badge),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.setActiveModel(model.id)
                                showModelBottomSheet = false
                            }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        showModelBottomSheet = false
                        onNavigateToModels()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.manage_models))
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // ১. প্রথমবার মাইক্রোফোন অনুমতি ব্যাখ্যা ডায়ালগ
    if (showMicRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showMicRationaleDialog = false },
            icon = { Icon(Icons.Outlined.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_rationale)) },
            confirmButton = {
                Button(onClick = {
                    showMicRationaleDialog = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(stringResource(R.string.mic_permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationaleDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAttachBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachBottomSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.attach_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.attach_gallery)) },
                    leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachBottomSheet = false
                            pickImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.attach_camera)) },
                    leadingContent = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachBottomSheet = false
                            runCatching {
                                val dir = File(context.cacheDir, "camera_images").apply { mkdirs() }
                                val file = File.createTempFile("cam_", ".jpg", dir)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                pendingCameraUri = uri
                                takePictureLauncher.launch(uri)
                            }
                        }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.attach_document)) },
                    leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachBottomSheet = false
                            pickDocLauncher.launch(
                                arrayOf("text/*", "application/json", "*/*")
                            )
                        }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ২. অনুমতি অস্বীকার করলে ভদ্র মেসেজ
    if (showMicDeniedPoliteDialog) {
        AlertDialog(
            onDismissRequest = { showMicDeniedPoliteDialog = false },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_denied_polite)) },
            confirmButton = {
                TextButton(onClick = { showMicDeniedPoliteDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // ৩. চিরতরে অস্বীকার করলে সেটিংসে যাওয়ার বাটনসহ ডায়ালগ
    if (showMicPermanentlyDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showMicPermanentlyDeniedDialog = false },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_permanently_denied)) },
            confirmButton = {
                Button(onClick = {
                    showMicPermanentlyDeniedDialog = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.open_app_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermanentlyDeniedDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: ChatMessage,
    activeModelName: String? = null,
    isCurrentlySpeaking: Boolean,
    onToggleSpeak: () -> Unit,
    metrics: RequestLatencyMetrics? = null,
    showDebugMetrics: Boolean = false
) {
    val context = LocalContext.current
    val isUser = msg.role == "user"
    val copiedToast = stringResource(R.string.copied_to_clipboard)
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000L)
            isCopied = false
        }
    }

    val copyMessage: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Message", msg.content))
        isCopied = true
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }

    val shareMessage: () -> Unit = {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, msg.content)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, context.getString(R.string.share_message))
        context.startActivity(shareIntent)
    }

    val timeFormatted = remember(msg.timestamp) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        sdf.format(Date(msg.timestamp))
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    } else {
        RoundedCornerShape(18.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = copyMessage
                    ),
                color = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            ),
                            shape = bubbleShape
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column {
                        if (msg.inputMode == InputMode.VOICE) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Voice",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                )
                            }
                        }
                        Text(
                            text = msg.content,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        } else {
            // প্রফেশনাল, স্লিক এআই রেসপন্স কার্ড
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .clip(bubbleShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = bubbleShape
                    ),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Assistant Header (Avatar, Title, Model Badge, Timestamp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.ai_identity_name),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!activeModelName.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
                                ) {
                                    Text(
                                        text = activeModelName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    MarkdownText(
                        markdown = msg.content,
                        textColor = MaterialTheme.colorScheme.onSurface
                    )

                    if (showDebugMetrics && metrics != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val totalSec = String.format(Locale.getDefault(), "%.2fs", metrics.totalMs / 1000.0)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.debug_latency_summary,
                                    metrics.requestBuildMs,
                                    metrics.firstTokenMs,
                                    totalSec,
                                    metrics.messageCount,
                                    metrics.estimatedInputTokens,
                                    metrics.toolCount
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.7.dp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // বটম অ্যাকশন বার (কথা বলা, কপি করা, শেয়ার করা)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // স্পিকার / পড়া
                        IconButton(
                            onClick = onToggleSpeak,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isCurrentlySpeaking) Icons.Default.StopCircle
                                else Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = if (isCurrentlySpeaking)
                                    stringResource(R.string.stop_speaking)
                                else
                                    stringResource(R.string.speak_message),
                                tint = if (isCurrentlySpeaking) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // কপি বাটন (with animated checkmark feedback)
                        IconButton(
                            onClick = copyMessage,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Crossfade(targetState = isCopied, label = "copyTransition") { copied ->
                                if (copied) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.copied_feedback),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = stringResource(R.string.copied_to_clipboard),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        // শেয়ার বাটন
                        IconButton(
                            onClick = shareMessage,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.share_message),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamingMessageBubble(
    text: String,
    activeModelName: String? = null
) {
    val bubbleShape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .clip(bubbleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = bubbleShape
                ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.ai_identity_name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_streaming_typing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                MarkdownText(
                    markdown = text,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isStreaming = true
                )
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble(label: String = stringResource(R.string.ai_thinking_label)) {
    val bubbleShape = RoundedCornerShape(18.dp)
    val transition = rememberInfiniteTransition(label = "typingDots")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .clip(bubbleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = bubbleShape
                ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { index ->
                        val alpha by transition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, delayMillis = index * 160),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot_$index"
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                        )
                    }
                }
            }
        }
    }
}
