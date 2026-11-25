package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.assistant.AssistantService
import com.example.assistant.BootReceiver
import com.example.ui.components.ActionConfirmationDialog
import com.example.ui.components.ContactPickerDialog
import com.example.ui.components.JustInTimePermissionDialog
import com.example.ui.screens.AccessibilityGuideScreen
import com.example.ui.screens.ActionLogScreen
import com.example.ui.screens.AddEditModelScreen
import com.example.ui.screens.BackgroundSettingsScreen
import com.example.ui.screens.BatteryGuideScreen
import com.example.ui.screens.CameraPillSettingsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HealthCheckScreen
import com.example.ui.screens.ModelListScreen
import com.example.ui.screens.NotificationGuideScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.ScreenSecuritySettingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsSettingsScreen
import com.example.ui.screens.VoiceModeScreen
import com.example.ui.screens.WakeWordTestScreen
import com.example.ui.screens.DeviceControlScreen
import com.example.ui.screens.FailoverSettingsScreen
import com.example.ui.screens.AutomationScreen
import com.example.ui.screens.AppLauncherScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.NotificationCenterScreen
import com.example.ui.screens.PermissionCenterScreen
import com.example.ui.screens.UsageDashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val openVoiceTriggerState = mutableStateOf(false)
    private val pendingStartServiceState = mutableStateOf(false)
    private val pendingConversationIdState = mutableStateOf<String?>(null)
    private val intentDispatchTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntentExtras(intent)
        (application as AiAssistantApplication).cameraPillController.start()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val missingBengaliVoice by viewModel.missingBengaliVoice.collectAsStateWithLifecycle()
            val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
            val pendingContactSelection by viewModel.pendingContactSelection.collectAsStateWithLifecycle()
            val pendingPermissionRequest by viewModel.pendingPermissionRequest.collectAsStateWithLifecycle()
            val context = LocalContext.current

            val writeSettingsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                viewModel.resolvePermissionRequest(Settings.System.canWrite(context))
            }

            val runtimePermissionsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grantMap ->
                val allRuntimeGranted = grantMap.values.all { it }
                val pending = pendingPermissionRequest
                if (allRuntimeGranted && pending?.requiresWriteSettings == true && !Settings.System.canWrite(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    writeSettingsLauncher.launch(intent)
                } else {
                    viewModel.resolvePermissionRequest(allRuntimeGranted)
                }
            }

            val novaTheme by (application as AiAssistantApplication).repository.novaThemeFlow.collectAsStateWithLifecycle(initialValue = "auto")
            MyApplicationTheme(themeMode = themeMode, novaTheme = novaTheme) {
                val navController = rememberNavController()
                val dispatchTick by intentDispatchTick

                LaunchedEffect(dispatchTick) {
                    if (pendingStartServiceState.value) {
                        pendingStartServiceState.value = false
                        viewModel.startAssistantServiceFromForeground()
                    }
                    pendingConversationIdState.value?.let { convId ->
                        pendingConversationIdState.value = null
                        viewModel.selectConversation(convId)
                    }
                    if (openVoiceTriggerState.value) {
                        openVoiceTriggerState.value = false
                        viewModel.openVoiceMode()
                        navController.navigate("voice_mode") {
                            launchSingleTop = true
                        }
                    }
                }

                NavHost(navController = navController, startDestination = "chat") {
                    composable("chat") {
                        ChatScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToModels = { navController.navigate("model_list") },
                            onOpenVoiceMode = { navController.navigate("voice_mode") }
                        )
                    }
                    composable("voice_mode") {
                        BackHandler {
                            viewModel.closeVoiceMode()
                            navController.popBackStack()
                        }
                        VoiceModeScreen(
                            viewModel = viewModel,
                            onClose = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToModels = { navController.navigate("model_list") },
                            onNavigateToTools = { navController.navigate("tools_settings") },
                            onNavigateToPermissions = { navController.navigate("permissions") },
                            onNavigateToActionLogs = { navController.navigate("action_logs") },
                            onNavigateToBackgroundSettings = { navController.navigate("background_settings") },
                            onNavigateToHealthCheck = { navController.navigate("health_check") },
                            onNavigateToBatteryGuide = { navController.navigate("battery_guide") },
                            onNavigateToAccessibilityGuide = { navController.navigate("accessibility_guide") },
                            onNavigateToNotificationGuide = { navController.navigate("notification_guide") },
                            onNavigateToScreenSecurity = { navController.navigate("screen_security") },
                            onNavigateToDeviceControl = { navController.navigate("device_control") },
                            onNavigateToAutomation = { navController.navigate("automation") },
                            onNavigateToAppLauncher = { navController.navigate("app_launcher") },
                            onNavigateToNotificationCenter = { navController.navigate("notification_center") },
                            onNavigateToPermissionCenter = { navController.navigate("permission_center") },
                            onNavigateToFailover = { navController.navigate("failover_settings") },
                            onNavigateToMemory = { navController.navigate("memory_settings") },
                            onNavigateToUsageDashboard = { navController.navigate("usage_dashboard") },
                            onNavigateToCameraPill = { navController.navigate("camera_pill_settings") },
                            onNavigateToVoiceEngine = { navController.navigate("voice_engine_settings") },
                            onNavigateToAutomationControl = { navController.navigate("automation_control") },
                            onNavigateToHud = { navController.navigate("hud_home") },
                            onNavigateToAbout = { navController.navigate("about") }
                        )
                    }
                    composable("about") {
                        com.example.ui.about.AboutScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onOpenNotices = { navController.navigate("legal_notices") }
                        )
                    }
                    composable("legal_notices") {
                        com.example.ui.about.NoticesScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("hud_home") {
                        com.example.ui.hud.HudHomeScreen(
                            onOpenChat = { navController.navigate("chat") { popUpTo("chat") { inclusive = false } } },
                            onOpenSettings = { navController.navigate("settings") },
                            onMic = { navController.navigate("voice_mode") }
                        )
                    }
                    composable("automation_control") {
                        com.example.ui.screens.AutomationControlScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onOpenPermissionCenter = { navController.navigate("permission_center") }
                        )
                    }
                    composable("voice_engine_settings") {
                        com.example.ui.screens.VoiceEngineSettingsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("camera_pill_settings") {
                        CameraPillSettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("failover_settings") {
                        FailoverSettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("memory_settings") {
                        MemoryScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("usage_dashboard") {
                        UsageDashboardScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("device_control") {
                        DeviceControlScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("automation") {
                        AutomationScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("app_launcher") {
                        AppLauncherScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("notification_center") {
                        NotificationCenterScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("permission_center") {
                        PermissionCenterScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("background_settings") {
                        BackgroundSettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToWakeWordTest = { navController.navigate("wake_word_test") },
                            onNavigateToHealthCheck = { navController.navigate("health_check") },
                            onNavigateToBatteryGuide = { navController.navigate("battery_guide") }
                        )
                    }
                    composable("wake_word_test") {
                        WakeWordTestScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("health_check") {
                        HealthCheckScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenWakeWordTest = { navController.navigate("wake_word_test") },
                            onOpenAccessibilityGuide = { navController.navigate("accessibility_guide") },
                            onOpenNotificationGuide = { navController.navigate("notification_guide") }
                        )
                    }
                    composable("battery_guide") {
                        BatteryGuideScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("accessibility_guide") {
                        AccessibilityGuideScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToBatteryGuide = { navController.navigate("battery_guide") }
                        )
                    }
                    composable("notification_guide") {
                        NotificationGuideScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("screen_security") {
                        ScreenSecuritySettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("tools_settings") {
                        ToolsSettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("permissions") {
                        PermissionsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("action_logs") {
                        ActionLogScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("model_list") {
                        ModelListScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToAddEdit = { modelId ->
                                if (modelId != null) {
                                    navController.navigate("model_add_edit/$modelId")
                                } else {
                                    navController.navigate("model_add_edit/none")
                                }
                            },
                            onNavigateToFailover = { navController.navigate("failover_settings") }
                        )
                    }
                    composable(
                        route = "model_add_edit/{modelId}",
                        arguments = listOf(navArgument("modelId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val rawId = backStackEntry.arguments?.getString("modelId")
                        val modelId = if (rawId == "none" || rawId.isNullOrBlank()) null else Uri.decode(rawId)
                        androidx.compose.runtime.key(modelId) {
                            AddEditModelScreen(
                                viewModel = viewModel,
                                modelId = modelId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                // ধাপ ৩: গ্লোবাল কন্ট্যাক্ট পিকার, কনফার্মেশন ও Just-in-time পারমিশন ডায়ালগ (চ্যাট ও ভয়েস উভয় মোডে কাজ করবে)
                pendingContactSelection?.let { pending ->
                    ContactPickerDialog(
                        pending = pending,
                        onSelect = { chosen -> viewModel.resolveContactSelection(chosen) },
                        onCancel = { viewModel.resolveContactSelection(null) }
                    )
                }

                pendingConfirmation?.let { pending ->
                    ActionConfirmationDialog(
                        pending = pending,
                        onConfirm = { editedSms -> viewModel.resolveConfirmationConfirm(editedSms) },
                        onCancel = { viewModel.resolveConfirmationCancel() }
                    )
                }

                pendingPermissionRequest?.let { pending ->
                    JustInTimePermissionDialog(
                        pending = pending,
                        onAllow = {
                            val missingRuntime = pending.permissions.filter {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missingRuntime.isNotEmpty()) {
                                runtimePermissionsLauncher.launch(missingRuntime.toTypedArray())
                            } else if (pending.requiresWriteSettings && !Settings.System.canWrite(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                writeSettingsLauncher.launch(intent)
                            } else {
                                viewModel.resolvePermissionRequest(true)
                            }
                        },
                        onDeny = { viewModel.resolvePermissionRequest(false) }
                    )
                }

                // চ্যাট বা ভয়েস মোড—যেকোনো স্ক্রিনেই বাংলা TTS ভয়েস ডেটা না থাকলে সেটিংসে যাওয়ার ডায়ালগ দেখাবে
                if (missingBengaliVoice) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissMissingBengaliDialog() },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        title = { Text(stringResource(R.string.tts_missing_bengali_title)) },
                        text = { Text(stringResource(R.string.tts_missing_bengali_message)) },
                        confirmButton = {
                            Button(onClick = {
                                viewModel.dismissMissingBengaliDialog()
                                try {
                                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }) {
                                Text(stringResource(R.string.tts_open_settings))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissMissingBengaliDialog() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    private fun consumeIntentExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(BootReceiver.EXTRA_START_BG_SERVICE, false)) {
            pendingStartServiceState.value = true
            intent.removeExtra(BootReceiver.EXTRA_START_BG_SERVICE)
        }
        intent.getStringExtra(AssistantService.EXTRA_CONVERSATION_ID)?.let { convId ->
            pendingConversationIdState.value = convId
            intent.removeExtra(AssistantService.EXTRA_CONVERSATION_ID)
        }
        if (intent.getBooleanExtra(AssistantService.EXTRA_OPEN_VOICE_MODE, false)) {
            openVoiceTriggerState.value = true
            intent.removeExtra(AssistantService.EXTRA_OPEN_VOICE_MODE)
        }
        intentDispatchTick.intValue++
    }
}
