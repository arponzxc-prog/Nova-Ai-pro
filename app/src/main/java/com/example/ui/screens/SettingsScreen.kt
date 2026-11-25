package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.compose.foundation.shape.CircleShape
import com.example.BuildConfig
import com.example.R
import com.example.model.HistoryLimitPreset
import com.example.model.SpeechLanguageOption
import com.example.model.VoiceCharacter
import com.example.model.VoicePersonas
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToActionLogs: () -> Unit,
    onNavigateToBackgroundSettings: () -> Unit,
    onNavigateToHealthCheck: () -> Unit,
    onNavigateToBatteryGuide: () -> Unit,
    onNavigateToAccessibilityGuide: () -> Unit,
    onNavigateToNotificationGuide: () -> Unit,
    onNavigateToScreenSecurity: () -> Unit,
    onNavigateToDeviceControl: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToAppLauncher: () -> Unit,
    onNavigateToNotificationCenter: () -> Unit,
    onNavigateToPermissionCenter: () -> Unit,
    onNavigateToFailover: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToUsageDashboard: () -> Unit = {},
    onNavigateToCameraPill: () -> Unit = {},
    onNavigateToVoiceEngine: () -> Unit = {},
    onNavigateToAutomationControl: () -> Unit = {},
    onNavigateToHud: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val json = viewModel.exportChatsAsJson()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("Cannot open output stream")
                    }
                }.onSuccess {
                    Toast.makeText(context, "JSON Backup exported", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val md = viewModel.exportChatsAsMarkdown()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(md.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("Cannot open output stream")
                    }
                }.onSuccess {
                    Toast.makeText(context, "Markdown exported", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                }
                val result = viewModel.importChatsFromJson(raw)
                result.onSuccess { count ->
                    Toast.makeText(context, "Imported $count conversation(s)", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, err.localizedMessage ?: "Import failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val speechLanguage by viewModel.speechLanguage.collectAsStateWithLifecycle()
    val ttsSpeechRate by viewModel.ttsSpeechRate.collectAsStateWithLifecycle()
    val ttsPitch by viewModel.ttsPitch.collectAsStateWithLifecycle()
    val voicePersonaId by viewModel.voicePersonaId.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val autoSpeakOnVoice by viewModel.autoSpeakOnVoice.collectAsStateWithLifecycle()
    val continuousVoiceMode by viewModel.continuousVoiceMode.collectAsStateWithLifecycle()
    val autoTitleWithAi by viewModel.autoTitleWithAi.collectAsStateWithLifecycle()
    val historyLimit by viewModel.historyLimit.collectAsStateWithLifecycle()
    val debugInfoEnabled by viewModel.debugInfoEnabled.collectAsStateWithLifecycle()

    var promptText by remember(systemPrompt) { mutableStateOf(systemPrompt) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showVoicePersonaDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val defaultPrompt = stringResource(R.string.system_prompt_default)
    val clearedMsg = stringResource(R.string.clear_all_done)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // মডেল ও API Key ম্যানেজমেন্ট
            Card(
                onClick = onNavigateToModels,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.models_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_models_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // অটো API ফেইলওভার
            Card(
                onClick = onNavigateToFailover,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("failover_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.failover_settings_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.failover_settings_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // দীর্ঘমেয়াদী মেমোরি
            Card(
                onClick = onNavigateToMemory,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("memory_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.memory_screen_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.memory_screen_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ব্যবহার ও টোকেন ড্যাশবোর্ড
            Card(
                onClick = onNavigateToUsageDashboard,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("usage_dashboard_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.usage_dashboard_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.usage_dashboard_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // About NOVA AI (credits and licences)
            Card(
                onClick = onNavigateToAbout,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("about_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_card_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.about_card_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // HUD home (orb)
            Card(
                onClick = onNavigateToHud,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("hud_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.hud_card_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.hud_card_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // Appearance (NOVA themes)
            run {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val repo = (ctx.applicationContext as com.example.AiAssistantApplication).repository
                val themeScope = androidx.compose.runtime.rememberCoroutineScope()
                val novaChoice by repo.novaThemeFlow.collectAsState(initial = "auto")
                var showThemeSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                Card(
                    onClick = { showThemeSheet = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.testTag("appearance_settings_card")
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.appearance_title), fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(stringResource(R.string.appearance_subtitle)) },
                        leadingContent = { Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                    )
                }
                if (showThemeSheet) {
                    com.example.ui.theme.ThemeSelectionSheet(
                        current = novaChoice,
                        onSelect = { key -> themeScope.launch { repo.saveNovaTheme(key) }; showThemeSheet = false },
                        onDismiss = { showThemeSheet = false }
                    )
                }
            }

            // Automation control (kill switch, per-feature toggles)
            Card(
                onClick = onNavigateToAutomationControl,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("automation_control_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.automation_control_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.automation_control_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // Voice engine (Gemini Live / Owner Voice / Hands-free)
            Card(
                onClick = onNavigateToVoiceEngine,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("voice_engine_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.voice_engine_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.voice_engine_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // Camera Pill (AI Live Avatar)
            Card(
                onClick = onNavigateToCameraPill,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("camera_pill_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pill_settings_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.pill_settings_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ডিভাইস কন্ট্রোল
            Card(
                onClick = onNavigateToDeviceControl,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.device_control_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_device_control_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // স্মার্ট অটোমেশন ও রুটিন
            Card(
                onClick = onNavigateToAutomation,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.automation_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_automation_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.AutoMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // অ্যাপ লাঞ্চার ও ম্যানেজার
            Card(
                onClick = onNavigateToAppLauncher,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_launcher_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_app_launcher_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // নোটিফিকেশন সেন্টার
            Card(
                onClick = onNavigateToNotificationCenter,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notification_center_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_notif_center_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // পারমিশন সেন্টার
            Card(
                onClick = onNavigateToPermissionCenter,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.permission_center_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.settings_perm_center_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৩: ফোন অ্যাকশন ও টুল সেটিংস
            Card(
                onClick = onNavigateToTools,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("tools_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tools_settings_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.tools_settings_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৩: পারমিশন স্ট্যাটাস ও অনুমোদন
            Card(
                onClick = onNavigateToPermissions,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("permissions_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.permissions_screen_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.permissions_screen_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৩: অ্যাকশন লগ
            Card(
                onClick = onNavigateToActionLogs,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("action_logs_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_log_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.action_log_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৪: ব্যাকগ্রাউন্ড ও Wake Word
            Card(
                onClick = onNavigateToBackgroundSettings,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("background_settings_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bg_settings_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.bg_settings_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৪: হেলথ চেক
            Card(
                onClick = onNavigateToHealthCheck,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("health_check_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.health_check_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.health_check_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৪: ব্যাটারি অপ্টিমাইজেশন গাইড
            Card(
                onClick = onNavigateToBatteryGuide,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("battery_guide_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.battery_guide_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.battery_guide_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৫: Accessibility ও স্ক্রিন অ্যাসিস্ট্যান্ট
            Card(
                onClick = onNavigateToAccessibilityGuide,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("a11y_guide_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.a11y_settings_card_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.a11y_settings_card_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.AccessibilityNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৫: নোটিফিকেশন পড়া (Notification Access)
            Card(
                onClick = onNavigateToNotificationGuide,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("notif_reader_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_reader_card_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.notif_reader_card_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ধাপ ৫: স্ক্রিন নিরাপত্তা, ব্লকলিস্ট ও UI সীমা
            Card(
                onClick = onNavigateToScreenSecurity,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.testTag("screen_security_card")
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.screen_security_card_title), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.screen_security_card_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // থিম নির্বাচন
            Card(
                onClick = { showThemeDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.theme_setting), fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        val text = when (themeMode) {
                            "dark" -> stringResource(R.string.theme_dark)
                            "light" -> stringResource(R.string.theme_light)
                            else -> stringResource(R.string.theme_system)
                        }
                        Text(text)
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            // ভয়েস ও অডিও সেটিংস কার্ড (ধাপ ২)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.voice_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // AI কণ্ঠ / ভয়েস ক্যারেক্টার নির্বাচন
                    val currentPersona = remember(voicePersonaId) {
                        VoicePersonas.getById(voicePersonaId) ?: VoicePersonas.ALL.first()
                    }
                    Card(
                        onClick = { showVoicePersonaDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().testTag("voice_persona_card")
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.voice_persona_title),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            supportingContent = {
                                Text("${currentPersona.nameBengali} • ${currentPersona.descriptionBengali}")
                            },
                            leadingContent = {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = when (currentPersona.gender) {
                                                "Female" -> Icons.Outlined.Person
                                                "Male" -> Icons.Outlined.Face
                                                "Robot" -> Icons.Outlined.SmartToy
                                                else -> Icons.Outlined.Tune
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (isSpeaking) {
                                                viewModel.stopVoicePreview()
                                            } else {
                                                viewModel.previewVoicePersona(currentPersona)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Outlined.VolumeUp,
                                            contentDescription = stringResource(if (isSpeaking) R.string.voice_persona_stop_btn else R.string.voice_persona_preview_btn),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ভাষা নির্বাচন
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.speech_language_title)) },
                        supportingContent = {
                            val label = when (speechLanguage) {
                                SpeechLanguageOption.BENGALI -> stringResource(R.string.lang_bengali)
                                SpeechLanguageOption.ENGLISH -> stringResource(R.string.lang_english)
                                SpeechLanguageOption.AUTO -> stringResource(R.string.lang_auto)
                            }
                            Text(label)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable { showLanguageDialog = true }
                    )

                    // কথার গতি (Speech Rate)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.tts_rate_title), style = MaterialTheme.typography.bodyMedium)
                            Text("${String.format(Locale.getDefault(), "%.2f", ttsSpeechRate)}x", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = ttsSpeechRate,
                            onValueChange = { viewModel.updateTtsSpeechRate(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 14
                        )
                    }

                    // কণ্ঠের সুর (Pitch)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.tts_pitch_title), style = MaterialTheme.typography.bodyMedium)
                            Text("${String.format(Locale.getDefault(), "%.2f", ttsPitch)}x", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = ttsPitch,
                            onValueChange = { viewModel.updateTtsPitch(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 14
                        )
                    }

                    // ভয়েস ইনপুটে অটো-স্পিক (ডিফল্ট অন)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.auto_speak_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.auto_speak_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSpeakOnVoice,
                            onCheckedChange = { viewModel.updateAutoSpeakOnVoice(it) }
                        )
                    }

                    // কথোপকথন চালু রাখুন
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.voice_continuous_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = continuousVoiceMode,
                            onCheckedChange = { viewModel.updateContinuousVoiceMode(it) }
                        )
                    }
                }
            }

            // চ্যাট হিস্টোরি সেটিংস কার্ড (ধাপ ২)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.history_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.auto_title_ai_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.auto_title_ai_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoTitleWithAi,
                            onCheckedChange = { viewModel.updateAutoTitleWithAi(it) }
                        )
                    }

                    // চ্যাট মেমোরি সীমা (কম / মাঝারি / বেশি)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.history_limit_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.history_limit_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                HistoryLimitPreset.SHORT to R.string.history_limit_short,
                                HistoryLimitPreset.MEDIUM to R.string.history_limit_medium,
                                HistoryLimitPreset.LONG to R.string.history_limit_long
                            ).forEach { (preset, labelRes) ->
                                FilterChip(
                                    selected = historyLimit == preset,
                                    onClick = { viewModel.updateHistoryLimit(preset) },
                                    label = { Text(stringResource(labelRes)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showClearHistoryDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.clear_all_history))
                    }
                }
            }

            // চ্যাট ব্যাকআপ ও এক্সপোর্ট/ইমপোর্ট (API Key বাদে)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.backup_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.backup_card_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportJsonLauncher.launch("nova_backup_$stamp.json")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.backup_export_json))
                        }
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportMdLauncher.launch("nova_chats_$stamp.md")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.backup_export_md))
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            importJsonLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.backup_import_json))
                    }
                }
            }

            // সিস্টেম প্রম্পট
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.system_prompt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                promptText = defaultPrompt
                                viewModel.updateSystemPrompt(defaultPrompt)
                            }
                        ) {
                            Text(stringResource(R.string.reset))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = {
                            promptText = it
                            viewModel.updateSystemPrompt(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        maxLines = 6
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.system_prompt_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.testTag("debug_info_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.debug_info_toggle_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.debug_info_toggle_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugInfoEnabled,
                            onCheckedChange = { viewModel.updateDebugInfoEnabled(it) },
                            modifier = Modifier.testTag("debug_info_switch")
                        )
                    }
                }
            }

            // অ্যাপ সম্পর্কে
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_app), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(R.string.app_version_5)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.theme_setting)) },
            text = {
                Column {
                    listOf(
                        "system" to stringResource(R.string.theme_system),
                        "light" to stringResource(R.string.theme_light),
                        "dark" to stringResource(R.string.theme_dark)
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    viewModel.updateThemeMode(mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.speech_language_title)) },
            text = {
                Column {
                    listOf(
                        SpeechLanguageOption.AUTO to stringResource(R.string.lang_auto),
                        SpeechLanguageOption.BENGALI to stringResource(R.string.lang_bengali),
                        SpeechLanguageOption.ENGLISH to stringResource(R.string.lang_english)
                    ).forEach { (opt, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSpeechLanguage(opt)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = speechLanguage == opt,
                                onClick = {
                                    viewModel.updateSpeechLanguage(opt)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showVoicePersonaDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.stopVoicePreview()
                showVoicePersonaDialog = false
            },
            icon = {
                Icon(
                    Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.voice_persona_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoicePersonas.ALL.forEach { persona ->
                        val isSelected = persona.id == voicePersonaId
                        val genderLabel = when (persona.gender) {
                            "Female" -> stringResource(R.string.voice_gender_female)
                            "Male" -> stringResource(R.string.voice_gender_male)
                            "Robot" -> stringResource(R.string.voice_gender_robot)
                            else -> stringResource(R.string.voice_gender_custom)
                        }

                        Card(
                            onClick = {
                                viewModel.selectVoicePersona(persona)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainer
                            ),
                            border = if (isSelected)
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.selectVoicePersona(persona)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = persona.nameBengali,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                        ) {
                                            Text(
                                                text = genderLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = persona.descriptionBengali,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.voice_persona_rate_pitch,
                                            persona.speechRate.toString(),
                                            persona.speechPitch.toString()
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isSpeaking) {
                                            viewModel.stopVoicePreview()
                                        } else {
                                            viewModel.previewVoicePersona(persona)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSpeaking && isSelected) Icons.Default.Stop else Icons.Outlined.VolumeUp,
                                        contentDescription = stringResource(R.string.voice_persona_preview_btn),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopVoicePreview()
                        showVoicePersonaDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm_action_button))
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.clear_all_history)) },
            text = { Text(stringResource(R.string.clear_all_history_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryDialog = false
                        Toast.makeText(context, clearedMsg, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_chat))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
