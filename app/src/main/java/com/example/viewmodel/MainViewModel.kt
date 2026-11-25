package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AiAssistantApplication
import com.example.R
import com.example.camerapill.CameraPillSettings
import com.example.camerapill.PillSourcePhase
import com.example.camerapill.PillVoiceSignal
import com.example.accessibility.AppAccessPolicy
import com.example.assistant.AssistantRuntimeState
import com.example.assistant.AssistantService
import com.example.assistant.RoutineScheduler
import com.example.assistant.WakeWordDetectionEvent
import com.example.assistant.WakeWordEngineState
import com.example.data.ChatBackupManager
import com.example.data.DuplicatePolicy
import com.example.data.ImportFailure
import com.example.data.ModelImportIo
import com.example.data.ModelImportOutcome
import com.example.data.ModelImportParseResult
import com.example.data.ModelImportParser
import com.example.data.ModelImportPreview
import com.example.data.ModelImportReadResult
import com.example.data.ModelImportSummary
import com.example.data.RejectedEntry
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageSearchRow
import com.example.data.local.ModelUsageSummary
import com.example.data.local.RoutineEntity
import com.example.data.local.UsageStatEntity
import com.example.model.ActionCardItem
import com.example.model.ActionLog
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.HistoryLimitPreset
import com.example.model.InputMode
import com.example.model.ModelConfig
import com.example.model.RequestLatencyMetrics
import com.example.model.SpeechLanguageOption
import com.example.model.VoiceCharacter
import com.example.network.AiProviderFactory
import com.example.network.ErrorFormatter
import com.example.network.FailoverCandidate
import com.example.network.FailoverCandidateResolver
import com.example.network.ModelCooldownInfo
import com.example.network.ModelHealthTracker
import com.example.network.PartialStreamException
import com.example.tools.AgentOrchestrator
import com.example.tools.AgentStreamPhase
import com.example.tools.AgentUiBridge
import com.example.tools.ConfirmationOutcome
import com.example.tools.ConfirmationRequestContext
import com.example.tools.ContactMatch
import com.example.tools.IdentityResponder
import com.example.tools.MemoryTools
import com.example.tools.PendingActionConfirmation
import com.example.tools.PendingContactSelection
import com.example.tools.PendingPermissionRequest
import com.example.tools.PromptAndToolOptimizer
import com.example.tools.RiskLevel
import com.example.tools.SearchProviderType
import com.example.tools.ToolDefinition
import com.example.tools.ToolRegistry
import com.example.tools.VoiceConfirmationParser
import com.example.voice.SpeechInputManager
import com.example.voice.SpeechInputState
import com.example.voice.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class StreamUiStatus {
    IDLE,
    THINKING,
    STREAMING,
    TOOL_RUNNING
}

enum class VoiceModePhase {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

data class VoiceModeUiState(
    val isOpen: Boolean = false,
    val phase: VoiceModePhase = VoiceModePhase.IDLE,
    val userTranscript: String = "",
    val aiReplyPreview: String = "",
    @StringRes val errorRes: Int? = null,
    val errorText: String? = null
)

data class PendingAttachment(
    val displayName: String,
    val isImage: Boolean,
    val imageBase64: String? = null,
    val textContent: String? = null,
    val mimeType: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
/** Models & API Keys → JSON ইমপোর্ট ফ্লোর UI স্টেট। */
sealed interface ModelImportUiState {
    object Idle : ModelImportUiState
    /** ফাইল পড়া ও যাচাই চলছে */
    object Reading : ModelImportUiState
    /** যাচাই শেষ; ব্যবহারকারীর নিশ্চিতকরণের অপেক্ষা */
    data class Preview(val preview: ModelImportPreview) : ModelImportUiState
    data class Importing(val done: Int, val total: Int) : ModelImportUiState
    data class Done(val summary: ModelImportSummary) : ModelImportUiState
    data class Failed(val reason: ImportFailure, val rejected: List<RejectedEntry> = emptyList()) : ModelImportUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val app = application as AiAssistantApplication
    private val repository = app.repository
    private val assistantController = app.assistantController
    private val providerFactory = AiProviderFactory()
    private val speechInputManager = SpeechInputManager(application)
    private val ttsManager = TtsManager(application)

    val modelConfigs: StateFlow<List<ModelConfig>> = repository.modelConfigsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val systemPrompt: StateFlow<String> = repository.systemPromptFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            application.getString(R.string.system_prompt_default)
        )

    val themeMode: StateFlow<String> = repository.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val speechLanguage: StateFlow<SpeechLanguageOption> = repository.speechLanguageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeechLanguageOption.AUTO)

    val ttsSpeechRate: StateFlow<Float> = repository.ttsSpeechRateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val ttsPitch: StateFlow<Float> = repository.ttsPitchFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val voicePersonaId: StateFlow<String> = repository.voicePersonaIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "nova")

    val autoSpeakOnVoice: StateFlow<Boolean> = repository.autoSpeakOnVoiceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val continuousVoiceMode: StateFlow<Boolean> = repository.continuousVoiceModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoTitleWithAi: StateFlow<Boolean> = repository.autoTitleWithAiFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ধাপ ৩: টুল ও নিরাপত্তা সেটিংস
    val disabledTools: StateFlow<Set<String>> = repository.disabledToolsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val confirmMediumRisk: StateFlow<Boolean> = repository.confirmMediumRiskFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val logSensitiveData: StateFlow<Boolean> = repository.logSensitiveDataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val preferredSimSlot: StateFlow<Int> = repository.preferredSimSlotFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val actionLogs: StateFlow<List<ActionLog>> = repository.actionLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ধাপ ৪: ব্যাকগ্রাউন্ড, Wake Word, ওভারলে ও সময়সূচি স্টেট
    val backgroundListeningEnabled: StateFlow<Boolean> = repository.backgroundListeningEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val wakeWordPhrase: StateFlow<String> = repository.wakeWordPhraseFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hey Jarvis")

    val wakeWordThreshold: StateFlow<Float> = repository.wakeWordThresholdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)

    val chimeEnabled: StateFlow<Boolean> = repository.chimeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val floatingBubbleEnabled: StateFlow<Boolean> = repository.floatingBubbleEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val cameraPillSettings: StateFlow<CameraPillSettings> = repository.cameraPillSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CameraPillSettings())

    fun updateCameraPillSettings(transform: (CameraPillSettings) -> CameraPillSettings) = viewModelScope.launch {
        val current = repository.cameraPillSettingsFlow.first()
        repository.saveCameraPillSettings(transform(current))
    }

    fun previewCameraPill() = app.cameraPillController.startPreview()
    fun refreshCameraPill() = app.cameraPillController.refresh()
    val cameraPillOverlayVisible: StateFlow<Boolean> = app.cameraPillController.overlayVisible

    val allowLowRiskLocked: StateFlow<Boolean> = repository.allowLowRiskLockedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val chargingOnlyWake: StateFlow<Boolean> = repository.chargingOnlyWakeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val nightScheduleEnabled: StateFlow<Boolean> = repository.nightScheduleEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val nightStartHour: StateFlow<Int> = repository.nightStartHourFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 23)

    val nightEndHour: StateFlow<Int> = repository.nightEndHourFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    val bootNotificationEnabled: StateFlow<Boolean> = repository.bootNotificationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ধাপ ৫: Accessibility, নোটিফিকেশন ও স্ক্রিন নিরাপত্তা স্টেট
    val accessibilityWasEnabled: StateFlow<Boolean> = repository.accessibilityWasEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiAgentMaxSteps: StateFlow<Int> = repository.uiAgentMaxStepsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    val uiFloatingStopEnabled: StateFlow<Boolean> = repository.uiFloatingStopEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val allowlistMode: StateFlow<Boolean> = repository.allowlistModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val customBlockedPackages: StateFlow<Set<String>> = repository.customBlockedPackagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val removedDefaultBlockedPackages: StateFlow<Set<String>> = repository.removedDefaultBlockedPackagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val allowedPackages: StateFlow<Set<String>> = repository.allowedPackagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val notifAllowedPackages: StateFlow<Set<String>> = repository.notifAllowedPackagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val autoReadNotifications: StateFlow<Boolean> = repository.autoReadNotificationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val screenshotVisionEnabled: StateFlow<Boolean> = repository.screenshotVisionEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // পারফরম্যান্স ফিক্স: হিস্টোরি সীমা, ডিবাগ ইনফো, স্ট্রিমিং স্টেট ও ল্যাটেন্সি মেট্রিক্স
    val historyLimit: StateFlow<HistoryLimitPreset> = repository.historyLimitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryLimitPreset.MEDIUM)

    val debugInfoEnabled: StateFlow<Boolean> = repository.debugInfoEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Phase 1-4: Failover, Dynamic Max Steps, Memory, Routines & Usage Dashboard
    val autoFailoverEnabled: StateFlow<Boolean> = repository.autoFailoverEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val failoverOrderIds: StateFlow<List<String>> = repository.failoverOrderIdsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val standardMaxSteps: StateFlow<Int> = repository.standardMaxStepsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)

    val modelCooldowns: StateFlow<Map<String, ModelCooldownInfo>> = ModelHealthTracker.shared.cooldownSnapshot

    val memories: StateFlow<List<MemoryEntity>> = repository.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routines: StateFlow<List<RoutineEntity>> = repository.routinesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val usageStatsLast7Days: StateFlow<List<UsageStatEntity>> = repository.allUsageStatsFlow
        .map { list ->
            val cutoff = System.currentTimeMillis() - 7L * 24L * 3600_000L
            list.filter { it.timestamp >= cutoff }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelUsageSummary: StateFlow<List<ModelUsageSummary>> = repository.modelUsageSummaryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val assistantRuntimeState: StateFlow<AssistantRuntimeState> = assistantController.state
    val wakeWordState: StateFlow<WakeWordEngineState> = assistantController.wakeWordEngine.state
    val wakeWordDetections: StateFlow<List<WakeWordDetectionEvent>> = assistantController.wakeWordEngine.recentDetections

    // Room থেকে চ্যাট হিস্টোরি অবজার্ভ করা
    val conversations: StateFlow<List<Conversation>> = repository.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessage>> = _currentConversationId
        .flatMapLatest { convId ->
            if (convId == null) flowOf(emptyList())
            else repository.observeMessages(convId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<MessageSearchRow>> = _searchQuery
        .debounce(250)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else repository.searchMessages(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _streamUiStatus = MutableStateFlow(StreamUiStatus.IDLE)
    val streamUiStatus: StateFlow<StreamUiStatus> = _streamUiStatus.asStateFlow()

    private val _runningToolLabel = MutableStateFlow<String?>(null)
    val runningToolLabel: StateFlow<String?> = _runningToolLabel.asStateFlow()

    private val _streamingReplyText = MutableStateFlow<String?>(null)
    val streamingReplyText: StateFlow<String?> = _streamingReplyText.asStateFlow()

    private val _slowResponseWarning = MutableStateFlow(false)
    val slowResponseWarning: StateFlow<Boolean> = _slowResponseWarning.asStateFlow()

    private val _messageMetrics = MutableStateFlow<Map<String, RequestLatencyMetrics>>(emptyMap())
    val messageMetrics: StateFlow<Map<String, RequestLatencyMetrics>> = _messageMetrics.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ধাপ ৩: অ্যাকশন কার্ড, কনফার্মেশন, কন্ট্যাক্ট পিকার ও পারমিশন প্রম্পট স্টেট
    private val _activeActionCards = MutableStateFlow<List<ActionCardItem>>(emptyList())
    val activeActionCards: StateFlow<List<ActionCardItem>> = _activeActionCards.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingActionConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingActionConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _pendingContactSelection = MutableStateFlow<PendingContactSelection?>(null)
    val pendingContactSelection: StateFlow<PendingContactSelection?> = _pendingContactSelection.asStateFlow()

    private val _pendingPermissionRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingPermissionRequest: StateFlow<PendingPermissionRequest?> = _pendingPermissionRequest.asStateFlow()

    private val _toolsNotSupportedNotice = MutableStateFlow(false)
    val toolsNotSupportedNotice: StateFlow<Boolean> = _toolsNotSupportedNotice.asStateFlow()

    private val _pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    val pendingAttachment: StateFlow<PendingAttachment?> = _pendingAttachment.asStateFlow()

    private val _failoverNotice = MutableStateFlow<String?>(null)
    val failoverNotice: StateFlow<String?> = _failoverNotice.asStateFlow()

    // ভয়েস ইনপুট ও TTS স্টেট
    val speechState: StateFlow<SpeechInputState> = speechInputManager.state
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking
    val speakingMessageId: StateFlow<String?> = ttsManager.speakingMessageId
    val missingBengaliVoice: StateFlow<Boolean> = ttsManager.missingBengaliVoice

    private val _voiceModeState = MutableStateFlow(VoiceModeUiState())
    val voiceModeState: StateFlow<VoiceModeUiState> = _voiceModeState.asStateFlow()

    private var activeRequestJob: Job? = null
    private var confirmationTimeoutJob: Job? = null
    private var slowWarningJob: Job? = null
    private var streamUiFlushJob: Job? = null
    private var voiceFailoverAnnouncedInSession: Boolean = false

    @Volatile private var latestPartialStreamText: String = ""
    @Volatile private var activeConvIdForPartial: String? = null
    @Volatile private var activeInputModeForPartial: InputMode = InputMode.TEXT

    private val agentBridge = object : AgentUiBridge {
        override suspend fun requestPermissions(
            permissions: List<String>,
            requiresWriteSettings: Boolean,
            explanationRes: Int
        ): Boolean {
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
            }
            val needWrite = requiresWriteSettings && !Settings.System.canWrite(appContext)
            if (missing.isEmpty() && !needWrite) return true

            val deferred = CompletableDeferred<Boolean>()
            _pendingPermissionRequest.value = PendingPermissionRequest(
                permissions = missing,
                requiresWriteSettings = needWrite,
                explanationRes = explanationRes,
                deferred = deferred
            )
            return deferred.await()
        }

        override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? {
            val deferred = CompletableDeferred<ContactMatch?>()
            _pendingContactSelection.value = PendingContactSelection(
                query = query,
                matches = matches,
                deferred = deferred
            )
            return deferred.await()
        }

        override suspend fun requestConfirmation(
            tool: ToolDefinition,
            args: JSONObject,
            isVoiceMode: Boolean,
            confirmContext: ConfirmationRequestContext
        ): ConfirmationOutcome {
            val effectiveRisk = confirmContext.effectiveRiskLevel
            // নিরাপত্তার নিয়ম কোডে এনফোর্স করা: HIGH ঝুঁকিতে সবসময় কনফার্মেশন বাধ্যতামূলক!
            val needsConfirmation = effectiveRisk == RiskLevel.HIGH ||
                (effectiveRisk == RiskLevel.MEDIUM && confirmMediumRisk.value)
            if (!needsConfirmation) {
                return ConfirmationOutcome.Confirmed()
            }

            val deferred = CompletableDeferred<ConfirmationOutcome>()
            val recipientName = args.optString("contact_name", "")
            val phoneNumber = args.optString("phone_number", "")
            val smsMessage = args.optString("message", "")
            val isEditableMsg = tool.name == "send_sms" || tool.name == "send_whatsapp_message"

            val pending = PendingActionConfirmation(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                bengaliTitle = tool.bengaliTitle,
                riskLevel = effectiveRisk,
                recipientName = recipientName,
                phoneNumber = phoneNumber,
                smsMessage = smsMessage,
                isSms = isEditableMsg,
                summaryText = buildConfirmSummaryText(tool, args),
                targetAppName = confirmContext.targetAppName,
                targetButtonLabel = confirmContext.targetButtonLabel,
                actionContextDetails = confirmContext.actionContextDetails,
                remainingSeconds = 60,
                isVoiceListeningForConfirm = false,
                deferred = deferred
            )
            _pendingConfirmation.value = pending

            // ১ মিনিট (৬০ সেকেন্ড) সাড়া না পেলে অটো-বাতিল টাইমার
            confirmationTimeoutJob?.cancel()
            confirmationTimeoutJob = viewModelScope.launch {
                for (sec in 59 downTo 0) {
                    delay(1000L)
                    if (!deferred.isActive) return@launch
                    _pendingConfirmation.update { it?.copy(remainingSeconds = sec) }
                }
                if (deferred.isActive) {
                    speechInputManager.cancel()
                    ttsManager.stop(invokeCallback = false)
                    _pendingConfirmation.value = null
                    deferred.complete(ConfirmationOutcome.Cancelled("৬০ সেকেন্ড সময় শেষ হওয়ায় অটো-বাতিল"))
                }
            }

            // ভয়েস মোডে থাকলে ভয়েসে জিজ্ঞেস করে হ্যাঁ/না শোনা (অস্পষ্ট হলে বাতিল ধরবে)
            if (isVoiceMode && _voiceModeState.value.isOpen) {
                val targetLabel = if (recipientName.isNotBlank()) recipientName else phoneNumber
                val spokenQuestion = when (tool.name) {
                    "make_call" -> appContext.getString(R.string.voice_confirm_call_prompt, targetLabel)
                    "send_sms", "send_whatsapp_message" -> appContext.getString(R.string.voice_confirm_sms_prompt, targetLabel, smsMessage)
                    "click_element" -> "${confirmContext.targetAppName} অ্যাপে '${confirmContext.targetButtonLabel}' বাটনে ক্লিক করব? হ্যাঁ বা না বলুন।"
                    else -> appContext.getString(R.string.voice_confirm_generic_prompt, tool.bengaliTitle)
                }
                ttsManager.speak(null, spokenQuestion) {
                    if (deferred.isActive && _voiceModeState.value.isOpen) {
                        _pendingConfirmation.update { it?.copy(isVoiceListeningForConfirm = true) }
                        speechInputManager.startListening(
                            languageOption = speechLanguage.value,
                            onFinalResult = { spokenReply ->
                                if (deferred.isActive) {
                                    val yes = VoiceConfirmationParser.parseYesNo(spokenReply)
                                    confirmationTimeoutJob?.cancel()
                                    _pendingConfirmation.value = null
                                    if (yes) {
                                        deferred.complete(ConfirmationOutcome.Confirmed())
                                    } else {
                                        deferred.complete(ConfirmationOutcome.Cancelled("ভয়েসে বাতিল বা অস্পষ্ট উত্তর"))
                                    }
                                }
                            },
                            onError = {
                                // অস্পষ্ট বা এরর হলে কখনোই হ্যাঁ ধরবে না — বাতিল করবে
                                if (deferred.isActive) {
                                    confirmationTimeoutJob?.cancel()
                                    _pendingConfirmation.value = null
                                    deferred.complete(ConfirmationOutcome.Cancelled("ভয়েস নিশ্চিতকরণ পাওয়া যায়নি"))
                                }
                            }
                        )
                    }
                }
            }

            return deferred.await()
        }

        override fun onActionCardChanged(card: ActionCardItem) {
            _activeActionCards.update { list ->
                val idx = list.indexOfFirst { it.id == card.id }
                if (idx >= 0) list.toMutableList().also { it[idx] = card } else list + card
            }
        }

        override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {
            repository.recordActionLog(toolName, argsSummary, status)
        }
    }

    private val agentOrchestrator = AgentOrchestrator(application, agentBridge)

    init {
        // Camera Pill: publish in-app voice mode state using EXISTING mic RMS + TTS flags (no new audio session)
        viewModelScope.launch {
            combine(_voiceModeState, speechInputManager.state, ttsManager.isSpeaking) { vm, speech, speaking ->
                val phase = when {
                    vm.phase == VoiceModePhase.ERROR -> PillSourcePhase.ERROR
                    vm.phase == VoiceModePhase.THINKING -> PillSourcePhase.THINKING
                    vm.phase == VoiceModePhase.SPEAKING || speaking -> PillSourcePhase.SPEAKING
                    vm.phase == VoiceModePhase.LISTENING -> PillSourcePhase.LISTENING
                    else -> PillSourcePhase.IDLE
                }
                PillVoiceSignal(
                    active = vm.isOpen,
                    phase = phase,
                    micLevel = if (phase == PillSourcePhase.LISTENING) speech.rmsNormalized else 0f,
                    sourceId = "in_app"
                )
            }.distinctUntilChanged().collect { assistantController.publishInAppVoiceSignal(it) }
        }

        viewModelScope.launch {
            val latest = repository.getLatestConversation()
            if (latest != null && _currentConversationId.value == null) {
                _currentConversationId.value = latest.id
            }
        }

        viewModelScope.launch {
            combine(ttsSpeechRate, ttsPitch) { rate, pitch -> rate to pitch }
                .collect { (rate, pitch) ->
                    ttsManager.updateVoiceParams(rate, pitch)
                }
        }
    }

    private fun buildConfirmSummaryText(tool: ToolDefinition, args: JSONObject): String {
        return when (tool.name) {
            "set_alarm" -> "${tool.bengaliTitle}: ${args.optInt("hour")}:${args.optInt("minute")}"
            "set_timer" -> "${tool.bengaliTitle}: ${args.optInt("seconds")} সেকেন্ড"
            "play_music" -> "${tool.bengaliTitle}: ${args.optString("query")}"
            "create_calendar_event" -> "${tool.bengaliTitle}: ${args.optString("title")}"
            else -> tool.bengaliTitle
        }
    }

    // ==================== কনফার্মেশন, কন্ট্যাক্ট ও পারমিশন রেসপন্স হ্যান্ডলার ====================

    fun resolveConfirmationConfirm(editedSms: String?) {
        val pending = _pendingConfirmation.value ?: return
        confirmationTimeoutJob?.cancel()
        speechInputManager.cancel()
        ttsManager.stop(invokeCallback = false)
        _pendingConfirmation.value = null
        if (pending.deferred.isActive) {
            pending.deferred.complete(ConfirmationOutcome.Confirmed(editedSms))
        }
    }

    fun resolveConfirmationCancel() {
        val pending = _pendingConfirmation.value ?: return
        confirmationTimeoutJob?.cancel()
        speechInputManager.cancel()
        ttsManager.stop(invokeCallback = false)
        _pendingConfirmation.value = null
        if (pending.deferred.isActive) {
            pending.deferred.complete(ConfirmationOutcome.Cancelled("ইউজার বাতিল করেছেন"))
        }
    }

    fun resolveContactSelection(match: ContactMatch?) {
        val pending = _pendingContactSelection.value ?: return
        _pendingContactSelection.value = null
        if (pending.deferred.isActive) {
            pending.deferred.complete(match)
        }
    }

    fun resolvePermissionRequest(granted: Boolean) {
        val pending = _pendingPermissionRequest.value ?: return
        _pendingPermissionRequest.value = null
        if (pending.deferred.isActive) {
            pending.deferred.complete(granted)
        }
    }

    // ==================== মডেল ও সেটিংস ম্যানেজমেন্ট ====================

    fun getApiKey(id: String): String = repository.getApiKey(id)

    suspend fun getApiKeyAsync(id: String): String = repository.getApiKeyAsync(id)

    fun getMaskedApiKey(id: String): String = repository.getMaskedApiKey(id)

    fun setActiveModel(id: String) {
        viewModelScope.launch {
            repository.setActiveModel(id)
        }
    }

    fun saveModelConfig(config: ModelConfig, apiKey: String, keyModified: Boolean = true) {
        viewModelScope.launch {
            repository.upsertModelConfig(config, apiKey, keyModified)
        }
    }

    // ---------- Bulk JSON import (Models & API Keys) ----------

    private val _modelImportState = MutableStateFlow<ModelImportUiState>(ModelImportUiState.Idle)
    val modelImportState: StateFlow<ModelImportUiState> = _modelImportState.asStateFlow()

    private var modelImportJob: Job? = null
    private var pendingImportParse: ModelImportParseResult.Success? = null

    /** ফাইল পিকার থেকে পাওয়া Uri পড়ে, যাচাই করে Preview স্টেটে যায়। কিছু সেভ করে না। */
    fun onModelImportFileSelected(uri: Uri) {
        if (_modelImportState.value is ModelImportUiState.Importing) return
        modelImportJob?.cancel()
        pendingImportParse = null
        _modelImportState.value = ModelImportUiState.Reading
        modelImportJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { ModelImportIo.readText(appContext.contentResolver, uri) }
            val text = when (read) {
                is ModelImportReadResult.Error -> {
                    _modelImportState.value = ModelImportUiState.Failed(read.reason)
                    return@launch
                }
                is ModelImportReadResult.Text -> read.value
            }
            val parsed = withContext(Dispatchers.Default) { ModelImportParser.parse(text) }
            when (parsed) {
                is ModelImportParseResult.Failure ->
                    _modelImportState.value = ModelImportUiState.Failed(parsed.reason, parsed.rejected)
                is ModelImportParseResult.Success -> {
                    pendingImportParse = parsed
                    _modelImportState.value = ModelImportUiState.Preview(repository.previewModelImport(parsed))
                }
            }
        }
    }

    /** Preview নিশ্চিত হলে ইমপোর্ট প্রয়োগ। [policy] শুধু ডুপ্লিকেটের ক্ষেত্রে প্রযোজ্য। */
    fun confirmModelImport(policy: DuplicatePolicy) {
        val parsed = pendingImportParse ?: return
        if (_modelImportState.value !is ModelImportUiState.Preview) return
        _modelImportState.value = ModelImportUiState.Importing(0, parsed.entries.size)
        modelImportJob = viewModelScope.launch {
            val outcome = repository.applyModelImport(parsed, policy) { done, total ->
                _modelImportState.value = ModelImportUiState.Importing(done, total)
            }
            pendingImportParse = null
            _modelImportState.value = when (outcome) {
                is ModelImportOutcome.Success -> ModelImportUiState.Done(outcome.summary)
                is ModelImportOutcome.StorageFailure -> ModelImportUiState.Failed(ImportFailure.STORAGE_ERROR)
            }
        }
    }

    /** ডায়ালগ বন্ধ করে ইমপোর্ট স্টেট (ও মেমোরিতে থাকা এন্ট্রি/Key) মুছে ফেলে। ইমপোর্ট চলাকালে উপেক্ষিত। */
    fun dismissModelImport() {
        if (_modelImportState.value is ModelImportUiState.Importing) return
        modelImportJob?.cancel()
        pendingImportParse = null
        _modelImportState.value = ModelImportUiState.Idle
    }

    fun deleteModelConfig(id: String) {
        viewModelScope.launch {
            repository.deleteModelConfig(id)
        }
    }

    fun updateSystemPrompt(prompt: String) = viewModelScope.launch { repository.saveSystemPrompt(prompt) }
    fun updateThemeMode(mode: String) = viewModelScope.launch { repository.saveThemeMode(mode) }
    fun updateSpeechLanguage(option: SpeechLanguageOption) = viewModelScope.launch { repository.saveSpeechLanguage(option) }
    fun updateTtsSpeechRate(rate: Float) = viewModelScope.launch { repository.saveTtsSpeechRate(rate) }
    fun updateTtsPitch(pitch: Float) = viewModelScope.launch { repository.saveTtsPitch(pitch) }
    fun selectVoicePersona(persona: VoiceCharacter) = viewModelScope.launch {
        repository.saveVoicePersona(persona.id, persona.speechRate, persona.speechPitch)
    }
    fun previewVoicePersona(persona: VoiceCharacter) {
        ttsManager.previewVoice(persona.previewText, persona.speechRate, persona.speechPitch)
    }
    fun stopVoicePreview() {
        ttsManager.stop(invokeCallback = false)
    }
    fun updateAutoSpeakOnVoice(enabled: Boolean) = viewModelScope.launch { repository.saveAutoSpeakOnVoice(enabled) }
    fun updateContinuousVoiceMode(enabled: Boolean) = viewModelScope.launch { repository.saveContinuousVoiceMode(enabled) }
    fun updateAutoTitleWithAi(enabled: Boolean) = viewModelScope.launch { repository.saveAutoTitleWithAi(enabled) }

    fun setToolEnabled(toolName: String, enabled: Boolean) = viewModelScope.launch {
        repository.setToolEnabled(toolName, enabled)
    }

    fun updateConfirmMediumRisk(enabled: Boolean) = viewModelScope.launch { repository.saveConfirmMediumRisk(enabled) }
    fun updateLogSensitiveData(enabled: Boolean) = viewModelScope.launch { repository.saveLogSensitiveData(enabled) }
    fun updatePreferredSimSlot(slot: Int) = viewModelScope.launch { repository.savePreferredSimSlot(slot) }
    fun clearActionLogs() = viewModelScope.launch { repository.clearActionLogs() }

    // ==================== ধাপ ৪: ব্যাকগ্রাউন্ড সার্ভিস ও Wake Word কন্ট্রোল ====================

    fun setBackgroundListeningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.saveBackgroundListeningEnabled(enabled)
            if (enabled) {
                startAssistantServiceFromForeground()
            } else {
                stopAssistantService()
            }
        }
    }

    fun startAssistantServiceFromForeground() {
        val intent = Intent(appContext, AssistantService::class.java).setAction(AssistantService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
    }

    fun stopAssistantService() {
        val intent = Intent(appContext, AssistantService::class.java).setAction(AssistantService.ACTION_STOP_SERVICE)
        runCatching { appContext.startService(intent) }
    }

    private fun notifyServiceConfigChanged() {
        if (assistantRuntimeState.value.isServiceRunning) {
            val intent = Intent(appContext, AssistantService::class.java).setAction(AssistantService.ACTION_REFRESH_CONFIG)
            runCatching { appContext.startService(intent) }
        }
    }

    fun updateWakeWordPhrase(phrase: String) = viewModelScope.launch {
        repository.saveWakeWordPhrase(phrase)
        assistantController.wakeWordEngine.updateConfig(phrase, wakeWordThreshold.value)
    }

    fun updateWakeWordThreshold(threshold: Float) = viewModelScope.launch {
        repository.saveWakeWordThreshold(threshold)
        assistantController.wakeWordEngine.updateConfig(wakeWordPhrase.value, threshold)
    }

    fun updateChimeEnabled(enabled: Boolean) = viewModelScope.launch { repository.saveChimeEnabled(enabled) }

    fun updateFloatingBubbleEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.saveFloatingBubbleEnabled(enabled)
        if (enabled && !assistantRuntimeState.value.isServiceRunning) {
            repository.saveBackgroundListeningEnabled(true)
            startAssistantServiceFromForeground()
        } else {
            notifyServiceConfigChanged()
        }
    }

    fun updateAllowLowRiskLocked(enabled: Boolean) = viewModelScope.launch { repository.saveAllowLowRiskLocked(enabled) }

    fun updateChargingOnlyWake(enabled: Boolean) = viewModelScope.launch {
        repository.saveChargingOnlyWake(enabled)
        notifyServiceConfigChanged()
    }

    fun updateNightSchedule(enabled: Boolean, startHour: Int, endHour: Int) = viewModelScope.launch {
        repository.saveNightSchedule(enabled, startHour, endHour)
        notifyServiceConfigChanged()
    }

    fun updateBootNotificationEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.saveBootNotificationEnabled(enabled)
    }

    fun startWakeWordTestMonitor() {
        assistantController.wakeWordEngine.updateConfig(wakeWordPhrase.value, wakeWordThreshold.value)
        assistantController.wakeWordEngine.start(monitorAudioEvenIfModelMissing = true)
    }

    fun stopWakeWordTestMonitor() {
        if (!assistantRuntimeState.value.isServiceRunning) {
            assistantController.wakeWordEngine.stop()
        } else {
            notifyServiceConfigChanged()
        }
    }

    fun simulateWakeWordTrigger() {
        if (chimeEnabled.value) {
            assistantController.playWakeChimeAndVibrate()
        }
        assistantController.wakeWordEngine.simulateTrigger()
    }

    fun refreshWakeWordDiagnostics() {
        assistantController.wakeWordEngine.refreshDiagnostics()
    }

    // ==================== ধাপ ৫: Accessibility, নিরাপত্তা ও নোটিফিকেশন সেটিংস ====================

    fun updateUiAgentMaxSteps(steps: Int) = viewModelScope.launch { repository.saveUiAgentMaxSteps(steps) }
    fun updateUiFloatingStopEnabled(enabled: Boolean) = viewModelScope.launch { repository.saveUiFloatingStopEnabled(enabled) }
    fun updateAllowlistMode(enabled: Boolean) = viewModelScope.launch { repository.saveAllowlistMode(enabled) }
    fun addCustomBlockedPackage(pkg: String) = viewModelScope.launch { repository.addCustomBlockedPackage(pkg) }
    fun removeBlockedPackage(pkg: String, isDefaultProtected: Boolean) = viewModelScope.launch {
        repository.removeBlockedPackage(pkg, isDefaultProtected)
    }
    fun toggleAllowedPackage(pkg: String, allowed: Boolean) = viewModelScope.launch {
        repository.toggleAllowedPackage(pkg, allowed)
    }
    fun toggleNotifAllowedPackage(pkg: String, allowed: Boolean) = viewModelScope.launch {
        repository.toggleNotifAllowedPackage(pkg, allowed)
    }
    fun clearNotifAllowedPackages() = viewModelScope.launch { repository.clearNotifAllowedPackages() }
    fun updateAutoReadNotifications(enabled: Boolean) = viewModelScope.launch { repository.saveAutoReadNotifications(enabled) }
    fun updateScreenshotVisionEnabled(enabled: Boolean) = viewModelScope.launch { repository.saveScreenshotVisionEnabled(enabled) }
    fun updateHistoryLimit(preset: HistoryLimitPreset) = viewModelScope.launch { repository.saveHistoryLimit(preset) }
    fun updateDebugInfoEnabled(enabled: Boolean) = viewModelScope.launch { repository.saveDebugInfoEnabled(enabled) }
    fun stopUiAutomation() {
        assistantController.requestStopUiAgent(byTouch = false)
    }

    // ==================== Phase 1 & Phase 3-4: Failover, Search Keys, Memory, Routines, Usage, Attachments & Backup ====================

    fun updateAutoFailoverEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.saveAutoFailoverEnabled(enabled)
    }

    fun moveFailoverModel(modelId: String, direction: Int) = viewModelScope.launch {
        val currentConfigs = modelConfigs.value
        val storedOrder = failoverOrderIds.value.toMutableList()
        currentConfigs.forEach { cfg ->
            if (cfg.id !in storedOrder) storedOrder.add(cfg.id)
        }
        storedOrder.retainAll(currentConfigs.map { it.id }.toSet())
        val idx = storedOrder.indexOf(modelId)
        val targetIdx = idx + direction
        if (idx >= 0 && targetIdx in storedOrder.indices) {
            val item = storedOrder.removeAt(idx)
            storedOrder.add(targetIdx, item)
            repository.saveFailoverOrderIds(storedOrder)
        }
    }

    fun resetAllModelCooldowns() {
        ModelHealthTracker.shared.resetAll()
    }

    fun dismissFailoverNotice() {
        _failoverNotice.value = null
    }

    fun updateStandardMaxSteps(steps: Int) = viewModelScope.launch {
        repository.saveStandardMaxSteps(steps)
    }

    suspend fun getSearchApiKeyAsync(provider: SearchProviderType): String =
        repository.getSearchApiKeyAsync(provider)

    suspend fun getMaskedSearchApiKeyAsync(provider: SearchProviderType): String =
        repository.getMaskedSearchApiKeyAsync(provider)

    fun saveSearchApiKey(provider: SearchProviderType, key: String) = viewModelScope.launch {
        repository.saveSearchApiKey(provider, key)
    }

    fun saveManualMemory(
        existingId: String?,
        category: String,
        content: String,
        keywords: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val cleanContent = content.trim()
        val sensitiveReason = MemoryTools.detectSensitiveReason("$cleanContent $keywords")
        if (sensitiveReason != null) {
            onResult(false, sensitiveReason)
            return
        }
        viewModelScope.launch {
            val safeCat = if (category in setOf("name", "preference", "fact")) category else "fact"
            val entity = MemoryEntity(
                id = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                category = safeCat,
                content = cleanContent.take(400),
                keywords = keywords.trim().ifBlank { MemoryTools.extractKeywords(cleanContent) }.take(200),
                updatedAt = System.currentTimeMillis()
            )
            repository.saveMemory(entity)
            onResult(true, null)
        }
    }

    fun deleteMemory(id: String) = viewModelScope.launch { repository.deleteMemory(id) }

    fun clearAllMemories() = viewModelScope.launch { repository.clearAllMemories() }

    fun saveRoutine(
        existingId: String?,
        title: String,
        prompt: String,
        hour: Int,
        minute: Int,
        oneShot: Boolean
    ) = viewModelScope.launch {
        val routine = RoutineEntity(
            id = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            title = title.trim().take(80),
            prompt = prompt.trim().take(500),
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            daysMask = "DAILY",
            enabled = true,
            oneShot = oneShot,
            createdAt = System.currentTimeMillis()
        )
        repository.saveRoutine(routine)
        RoutineScheduler.schedule(appContext, routine)
    }

    fun toggleRoutineEnabled(routine: RoutineEntity, enabled: Boolean) = viewModelScope.launch {
        repository.setRoutineEnabled(routine.id, enabled)
        RoutineScheduler.schedule(appContext, routine.copy(enabled = enabled))
    }

    fun deleteRoutine(routineId: String) = viewModelScope.launch {
        RoutineScheduler.cancel(appContext, routineId)
        repository.deleteRoutine(routineId)
    }

    fun clearUsageStats() = viewModelScope.launch { repository.clearUsageStats() }

    suspend fun exportChatsAsJson(): String {
        val items = repository.getAllConversationsWithMessages()
        return ChatBackupManager.exportJson(items)
    }

    suspend fun exportChatsAsMarkdown(): String {
        val items = repository.getAllConversationsWithMessages()
        return ChatBackupManager.exportMarkdown(items)
    }

    suspend fun importChatsFromJson(jsonStr: String): Result<Int> = runCatching {
        val parsed = ChatBackupManager.parseJsonBackup(jsonStr)
        repository.importConversations(parsed)
    }

    fun clearAttachment() {
        _pendingAttachment.value = null
    }

    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = appContext.contentResolver
                val name = queryDisplayName(uri) ?: "image.jpg"
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("ছবি পড়া যায়নি।")
                val encoded = compressBitmapToBase64(bytes)
                    ?: throw IllegalStateException("ছবি ডিকোড করা যায়নি।")
                _pendingAttachment.value = PendingAttachment(
                    displayName = name,
                    isImage = true,
                    imageBase64 = encoded,
                    mimeType = mime
                )
            }.onFailure { err ->
                _errorMessage.value = err.localizedMessage ?: "ছবি সংযুক্ত করা যায়নি।"
            }
        }
    }

    fun attachDocumentFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = appContext.contentResolver
                val name = queryDisplayName(uri) ?: "document.txt"
                val mime = resolver.getType(uri) ?: "text/plain"
                val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buf = CharArray(16 * 1024)
                    val read = reader.read(buf)
                    if (read <= 0) "" else String(buf, 0, read)
                }.orEmpty()
                if (text.isBlank()) {
                    throw IllegalStateException("ফাইলে কোনো পাঠযোগ্য লেখা পাওয়া যায়নি।")
                }
                _pendingAttachment.value = PendingAttachment(
                    displayName = name,
                    isImage = false,
                    textContent = text,
                    mimeType = mime
                )
            }.onFailure { err ->
                _errorMessage.value = err.localizedMessage ?: "ফাইল সংযুক্ত করা যায়নি।"
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun compressBitmapToBase64(rawBytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sampleSize > 1024) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
        val scale = if (maxOf(decoded.width, decoded.height) > 1024) {
            1024f / maxOf(decoded.width, decoded.height).toFloat()
        } else 1f
        val finalBmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                maxOf(1, (decoded.width * scale).toInt()),
                maxOf(1, (decoded.height * scale).toInt()),
                true
            )
        } else decoded
        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (finalBmp !== decoded) finalBmp.recycle()
        decoded.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ==================== চ্যাট হিস্টোরি ====================

    fun startNewChat() {
        ttsManager.stop(invokeCallback = false)
        _currentConversationId.value = null
        _activeActionCards.value = emptyList()
        _errorMessage.value = null
    }

    fun selectConversation(conversationId: String) {
        ttsManager.stop(invokeCallback = false)
        _currentConversationId.value = conversationId
        _activeActionCards.value = emptyList()
        _errorMessage.value = null
    }

    fun renameConversation(id: String, newTitle: String) = viewModelScope.launch { repository.renameConversation(id, newTitle) }

    fun togglePinConversation(conversation: Conversation) = viewModelScope.launch {
        repository.setConversationPinned(conversation.id, !conversation.pinned)
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        repository.deleteConversation(id)
        if (_currentConversationId.value == id) {
            _currentConversationId.value = repository.getLatestConversation()?.id
        }
    }

    fun clearAllHistory() = viewModelScope.launch {
        ttsManager.stop(invokeCallback = false)
        repository.clearAllHistory()
        _currentConversationId.value = null
        _activeActionCards.value = emptyList()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun dismissToolsNotSupportedNotice() {
        _toolsNotSupportedNotice.value = false
    }

    // ==================== মেসেজ পাঠানো ও এজেন্ট লুপ ====================

    fun sendMessage(userText: String, inputMode: InputMode = InputMode.TEXT) {
        val trimmed = userText.trim()
        val currentAttachment = _pendingAttachment.value
        if (trimmed.isBlank() && currentAttachment == null) return

        val effectiveDisplayPrompt = when {
            trimmed.isNotBlank() && currentAttachment != null -> "$trimmed\n📎 ${currentAttachment.displayName}"
            trimmed.isNotBlank() -> trimmed
            else -> "📎 ${currentAttachment?.displayName ?: "সংযুক্তি"}"
        }

        val lower = trimmed.lowercase()
        if (assistantRuntimeState.value.isUiAutomationRunning &&
            (lower.contains("থামো") || lower.contains("বন্ধ করো") || lower == "stop" || lower == "abort")
        ) {
            stopUiAutomation()
            return
        }

        // ধাপ ২: অফলাইন-প্রথম IdentityResponder (সংযুক্তি না থাকলে এবং পরিচয় প্রশ্ন হলে শূন্য API কল)
        if (currentAttachment == null) {
            val identityReply = IdentityResponder.match(trimmed, inputMode)
            if (identityReply != null) {
                cancelActiveStreamJobs()
                activeRequestJob = viewModelScope.launch {
                    val existingConvId = _currentConversationId.value
                    val (convId, _) = repository.ensureConversationAndSaveUserMessage(
                        existingConversationId = existingConvId,
                        userText = trimmed,
                        inputMode = inputMode
                    )
                    _currentConversationId.value = convId
                    val assistantMsg = repository.saveAssistantMessage(
                        conversationId = convId,
                        replyText = identityReply.replyText,
                        inputMode = inputMode
                    )
                    if (inputMode == InputMode.VOICE && autoSpeakOnVoice.value) {
                        if (_voiceModeState.value.isOpen) {
                            _voiceModeState.update {
                                it.copy(
                                    phase = VoiceModePhase.SPEAKING,
                                    userTranscript = trimmed,
                                    aiReplyPreview = identityReply.replyText,
                                    errorRes = null,
                                    errorText = null
                                )
                            }
                        }
                        ttsManager.speak(assistantMsg.id, identityReply.replyText) {
                            if (_voiceModeState.value.isOpen) {
                                if (continuousVoiceMode.value) {
                                    startVoiceListening()
                                } else {
                                    _voiceModeState.update { it.copy(phase = VoiceModePhase.IDLE) }
                                }
                            }
                        }
                    }
                }
                return
            }
        }

        val activeModel = modelConfigs.value.find { it.isActive } ?: modelConfigs.value.firstOrNull()
        if (activeModel == null) {
            val msg = "আগে সেটিংস থেকে একটি এআই মডেল ও এপিআই কি যোগ করুন।"
            _errorMessage.value = msg
            if (_voiceModeState.value.isOpen) {
                _voiceModeState.update { it.copy(phase = VoiceModePhase.ERROR, errorText = msg) }
            }
            return
        }

        cancelActiveStreamJobs()
        activeRequestJob = viewModelScope.launch {
            val requiresVision = currentAttachment?.isImage == true && !currentAttachment.imageBase64.isNullOrBlank()
            val candidates = FailoverCandidateResolver.buildCandidates(
                activeModel = activeModel,
                allModels = modelConfigs.value,
                failoverOrderIds = failoverOrderIds.value,
                autoFailoverEnabled = autoFailoverEnabled.value,
                requiresVision = requiresVision,
                keyResolver = { repository.getApiKey(it) },
                healthTracker = ModelHealthTracker.shared,
                providerFactory = providerFactory
            )

            if (candidates.isEmpty()) {
                val allKeyed = FailoverCandidateResolver.buildCandidates(
                    activeModel = activeModel,
                    allModels = modelConfigs.value,
                    failoverOrderIds = failoverOrderIds.value,
                    autoFailoverEnabled = autoFailoverEnabled.value,
                    requiresVision = requiresVision,
                    keyResolver = { repository.getApiKey(it) },
                    healthTracker = ModelHealthTracker.shared,
                    providerFactory = providerFactory,
                    skipCooldownModels = false
                )
                val msg = when {
                    requiresVision && allKeyed.isEmpty() ->
                        "ছবি বিশ্লেষণের জন্য ভিশন-সাপোর্টেড (supportsVision) একটি মডেল ও API Key সেট করুন।"
                    allKeyed.isEmpty() ->
                        "নির্বাচিত মডেল (${activeModel.name})-এর জন্য কোনো API Key পাওয়া যায়নি। সেটিংস থেকে কি সেট করুন।"
                    else -> {
                        val rem = allKeyed.minOf { ModelHealthTracker.shared.remainingCooldownSeconds(it.config.id) }
                        "সব মডেল সাময়িক কুলডাউনে আছে (~${rem}স)। কিছুক্ষণ পর চেষ্টা করুন বা Failover সেটিংস থেকে কুলডাউন রিসেট করুন।"
                    }
                }
                _errorMessage.value = msg
                if (_voiceModeState.value.isOpen) {
                    _voiceModeState.update { it.copy(phase = VoiceModePhase.ERROR, errorText = msg) }
                }
                return@launch
            }

            _pendingAttachment.value = null
            val primaryCandidate = candidates.first()
            if (requiresVision && !activeModel.supportsVision) {
                _failoverNotice.value = "${activeModel.name} ভিশন সাপোর্ট করে না, ${primaryCandidate.config.name} দিয়ে ছবি পাঠানো হচ্ছে"
            } else {
                _failoverNotice.value = null
            }

            val existingConvId = _currentConversationId.value
            val cachedMsgs = if (existingConvId != null) chatMessages.value else emptyList()
            val wasNewConversation = existingConvId == null
            val (convId, userMsg) = repository.ensureConversationAndSaveUserMessage(
                existingConversationId = existingConvId,
                userText = effectiveDisplayPrompt,
                inputMode = inputMode
            )
            _currentConversationId.value = convId

            val rawHistory = if (existingConvId != null && cachedMsgs.isEmpty()) {
                repository.getMessagesSnapshot(convId)
            } else {
                cachedMsgs + userMsg
            }

            val llmPromptText = when {
                currentAttachment != null && !currentAttachment.isImage && !currentAttachment.textContent.isNullOrBlank() ->
                    "$trimmed\n\n[সংযুক্ত ফাইল: ${currentAttachment.displayName}]\n${currentAttachment.textContent}"
                trimmed.isNotBlank() -> trimmed
                else -> "সংযুক্ত ছবিটি বিশ্লেষণ করে বিস্তারিত বলো।"
            }
            val fullHistory = if (rawHistory.isNotEmpty() && rawHistory.last().id == userMsg.id) {
                rawHistory.dropLast(1) + userMsg.copy(content = llmPromptText)
            } else rawHistory

            executeStreamingTurn(
                convId = convId,
                userText = llmPromptText,
                inputMode = inputMode,
                candidates = candidates,
                fullHistory = fullHistory,
                wasNewConversation = wasNewConversation,
                userImageBase64 = if (requiresVision) currentAttachment?.imageBase64 else null
            )
        }
    }

    /**
     * নেটওয়ার্ক বিচ্ছিন্ন বা ব্যর্থ হওয়ার পর ডুপ্লিকেট ইউজার মেসেজ না বানিয়ে সর্বশেষ মেসেজটি আবার চালায়।
     */
    fun retryLastMessage() {
        val lastUserMsg = chatMessages.value.lastOrNull { it.role == "user" }
        val convId = _currentConversationId.value
        if (lastUserMsg == null || convId == null) {
            dismissError()
            return
        }

        val activeModel = modelConfigs.value.find { it.isActive } ?: modelConfigs.value.firstOrNull()
        if (activeModel == null) {
            _errorMessage.value = "আগে সেটিংস থেকে একটি এআই মডেল ও এপিআই কি যোগ করুন।"
            return
        }

        cancelActiveStreamJobs()
        activeRequestJob = viewModelScope.launch {
            val candidates = FailoverCandidateResolver.buildCandidates(
                activeModel = activeModel,
                allModels = modelConfigs.value,
                failoverOrderIds = failoverOrderIds.value,
                autoFailoverEnabled = autoFailoverEnabled.value,
                requiresVision = false,
                keyResolver = { repository.getApiKey(it) },
                healthTracker = ModelHealthTracker.shared,
                providerFactory = providerFactory
            )
            if (candidates.isEmpty()) {
                _errorMessage.value = "সচল API Key যুক্ত কোনো মডেল পাওয়া যায়নি বা সব মডেল কুলডাউনে আছে।"
                return@launch
            }

            // শেষ ইউজার মেসেজ পর্যন্ত হিস্টোরি নিয়ে আবার চেষ্টা
            val currentList = chatMessages.value
            val lastUserIdx = currentList.indexOfLast { it.id == lastUserMsg.id }
            val historyUpToUser = if (lastUserIdx >= 0) currentList.take(lastUserIdx + 1) else currentList

            executeStreamingTurn(
                convId = convId,
                userText = lastUserMsg.content,
                inputMode = lastUserMsg.inputMode,
                candidates = candidates,
                fullHistory = historyUpToUser,
                wasNewConversation = false,
                userImageBase64 = null
            )
        }
    }

    /**
     * চলমান AI স্ট্রিম থামায় এবং এ পর্যন্ত আসা আংশিক উত্তর চ্যাটে সংরক্ষণ করে।
     */
    fun stopGeneration() {
        val partial = latestPartialStreamText
        val convId = activeConvIdForPartial
        val mode = activeInputModeForPartial
        latestPartialStreamText = ""
        activeConvIdForPartial = null

        cancelActiveStreamJobs()
        ttsManager.stop(invokeCallback = false)
        _isTyping.value = false
        _streamUiStatus.value = StreamUiStatus.IDLE
        _runningToolLabel.value = null
        _streamingReplyText.value = null
        _slowResponseWarning.value = false

        if (_voiceModeState.value.isOpen) {
            _voiceModeState.update { it.copy(phase = VoiceModePhase.IDLE, aiReplyPreview = partial) }
        }

        if (convId != null && partial.isNotBlank()) {
            viewModelScope.launch {
                repository.saveAssistantMessage(
                    conversationId = convId,
                    replyText = partial,
                    inputMode = mode
                )
            }
        }
    }

    private fun cancelActiveStreamJobs() {
        slowWarningJob?.cancel()
        streamUiFlushJob?.cancel()
        activeRequestJob?.cancel()
    }

    private suspend fun executeStreamingTurn(
        convId: String,
        userText: String,
        inputMode: InputMode,
        candidates: List<FailoverCandidate>,
        fullHistory: List<ChatMessage>,
        wasNewConversation: Boolean,
        userImageBase64: String? = null
    ) {
        val primaryCand = candidates.first()
        _isTyping.value = true
        _streamUiStatus.value = StreamUiStatus.THINKING
        _runningToolLabel.value = null
        _streamingReplyText.value = null
        _slowResponseWarning.value = false
        _errorMessage.value = null
        _toolsNotSupportedNotice.value = false
        _activeActionCards.value = emptyList()

        latestPartialStreamText = ""
        activeConvIdForPartial = convId
        activeInputModeForPartial = inputMode

        if (inputMode == InputMode.VOICE && _voiceModeState.value.isOpen) {
            _voiceModeState.update {
                it.copy(
                    phase = VoiceModePhase.THINKING,
                    userTranscript = userText,
                    aiReplyPreview = "",
                    errorRes = null,
                    errorText = null
                )
            }
        }

        // ১৫ সেকেন্ডের বেশি সময় নিলে ধীর সাড়া সতর্কবার্তা চালু করা
        slowWarningJob?.cancel()
        slowWarningJob = viewModelScope.launch {
            delay(15_000L)
            if (_isTyping.value) {
                _slowResponseWarning.value = true
            }
        }

        val voiceAutoSpeak = inputMode == InputMode.VOICE && autoSpeakOnVoice.value
        val onVoiceTtsDone: () -> Unit = {
            if (_voiceModeState.value.isOpen) {
                if (continuousVoiceMode.value) {
                    startVoiceListening()
                } else {
                    _voiceModeState.update { it.copy(phase = VoiceModePhase.IDLE) }
                }
            }
        }

        try {
            val (boundedHistory, relevantTools, accessPolicy) = withContext(Dispatchers.Default) {
                val bounded = PromptAndToolOptimizer.buildBoundedHistory(fullHistory, historyLimit.value)
                val anySupportsTools = candidates.any { it.config.supportsTools }
                val allEnabled = if (anySupportsTools) {
                    ToolRegistry.getEnabledTools(disabledTools.value)
                } else {
                    emptyList()
                }
                val selectedTools = PromptAndToolOptimizer.selectRelevantTools(userText, allEnabled, bounded)
                val policy = AppAccessPolicy(
                    allowlistMode = allowlistMode.value,
                    customBlockedPackages = customBlockedPackages.value,
                    removedDefaultBlockedPackages = removedDefaultBlockedPackages.value,
                    allowedPackages = allowedPackages.value
                )
                Triple(bounded, selectedTools, policy)
            }

            val allMemories = repository.getAllMemoriesSnapshot()
            val relevantMemories = MemoryTools.selectRelevantMemories(userText, allMemories)
            val memoryContext = relevantMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }

            if (voiceAutoSpeak) {
                ttsManager.beginStreamingSession(messageId = null, onCompleted = onVoiceTtsDone)
            }

            // UI আপডেট ~৫০ms ব্যাচে থ্রটল করা যেন প্রতিটি টোকেনে পুরো বাবল রিকম্পোজ না হয়
            var lastUiEmitMs = 0L
            val agentResult = agentOrchestrator.runAgentLoop(
                provider = primaryCand.provider,
                config = primaryCand.config,
                apiKey = primaryCand.apiKey,
                baseSystemPrompt = systemPrompt.value,
                history = boundedHistory,
                enabledTools = relevantTools,
                inputMode = inputMode,
                logSensitiveData = logSensitiveData.value,
                preferredSimSlot = preferredSimSlot.value,
                uiMaxSteps = uiAgentMaxSteps.value,
                standardMaxSteps = standardMaxSteps.value,
                accessPolicy = accessPolicy,
                notifAllowedPackages = notifAllowedPackages.value,
                screenshotVisionEnabled = screenshotVisionEnabled.value,
                runtimeController = assistantController,
                backupCandidates = candidates.drop(1),
                healthTracker = ModelHealthTracker.shared,
                memoryContext = memoryContext,
                userImageBase64 = userImageBase64,
                onFailoverSwitched = { notice ->
                    _failoverNotice.value = appContext.getString(
                        R.string.failover_notice_banner,
                        notice.fromModelName,
                        notice.toModelName
                    )
                    if (voiceAutoSpeak && !voiceFailoverAnnouncedInSession) {
                        voiceFailoverAnnouncedInSession = true
                        ttsManager.enqueueStreamingDelta("${notice.fromModelName} ব্যস্ত, ${notice.toModelName} দিয়ে বলছি। ")
                    }
                },
                onUsageRecorded = { modelId, modelName, providerType, inTok, outTok, success, wasFailover ->
                    repository.recordUsageStat(modelId, modelName, providerType, inTok, outTok, success, wasFailover)
                },
                onVoiceProgress = { phrase ->
                    if (autoSpeakOnVoice.value) ttsManager.speak(null, phrase)
                },
                onPhaseChanged = { phase, toolLabel ->
                    _streamUiStatus.value = when (phase) {
                        AgentStreamPhase.THINKING -> StreamUiStatus.THINKING
                        AgentStreamPhase.STREAMING -> StreamUiStatus.STREAMING
                        AgentStreamPhase.TOOL_RUNNING -> StreamUiStatus.TOOL_RUNNING
                    }
                    _runningToolLabel.value = toolLabel
                },
                onStreamDelta = { delta, accumulated ->
                    latestPartialStreamText = accumulated
                    if (voiceAutoSpeak) {
                        ttsManager.enqueueStreamingDelta(delta)
                    }
                    val now = System.nanoTime() / 1_000_000L
                    if (_streamingReplyText.value == null || now - lastUiEmitMs >= 50L) {
                        lastUiEmitMs = now
                        streamUiFlushJob?.cancel()
                        _streamingReplyText.value = accumulated
                        if (inputMode == InputMode.VOICE && _voiceModeState.value.isOpen) {
                            _voiceModeState.update {
                                it.copy(
                                    phase = if (voiceAutoSpeak) VoiceModePhase.SPEAKING else VoiceModePhase.THINKING,
                                    aiReplyPreview = accumulated
                                )
                            }
                        }
                    } else if (streamUiFlushJob?.isActive != true) {
                        streamUiFlushJob = viewModelScope.launch {
                            delay(50L)
                            val latest = latestPartialStreamText
                            if (_isTyping.value && latest.isNotEmpty()) {
                                lastUiEmitMs = System.nanoTime() / 1_000_000L
                                _streamingReplyText.value = latest
                                if (inputMode == InputMode.VOICE && _voiceModeState.value.isOpen) {
                                    _voiceModeState.update { it.copy(aiReplyPreview = latest) }
                                }
                            }
                        }
                    }
                },
                onStreamReset = {
                    latestPartialStreamText = ""
                    streamUiFlushJob?.cancel()
                    _streamingReplyText.value = null
                    if (voiceAutoSpeak) {
                        ttsManager.beginStreamingSession(messageId = null, onCompleted = onVoiceTtsDone)
                    }
                }
            )

            if (agentResult.toolsNotSupportedNotice) {
                _toolsNotSupportedNotice.value = true
            }

            latestPartialStreamText = ""
            activeConvIdForPartial = null
            streamUiFlushJob?.cancel()
            _streamingReplyText.value = null

            val assistantMsg = repository.saveAssistantMessage(
                conversationId = convId,
                replyText = agentResult.finalReply,
                inputMode = inputMode
            )
            agentResult.metrics?.let { metrics ->
                _messageMetrics.update { it + (assistantMsg.id to metrics) }
            }

            if (wasNewConversation && autoTitleWithAi.value) {
                viewModelScope.launch {
                    generateAiTitleSafely(convId, primaryCand.config, primaryCand.apiKey, userText, agentResult.finalReply)
                }
            }

            if (voiceAutoSpeak) {
                if (_voiceModeState.value.isOpen) {
                    _voiceModeState.update {
                        it.copy(phase = VoiceModePhase.SPEAKING, aiReplyPreview = agentResult.finalReply)
                    }
                }
                ttsManager.finishStreamingSession(fallbackFullText = agentResult.finalReply)
            } else if (_voiceModeState.value.isOpen) {
                _voiceModeState.update {
                    it.copy(phase = VoiceModePhase.IDLE, aiReplyPreview = agentResult.finalReply)
                }
            }
        } catch (e: CancellationException) {
            // stopGeneration() থেকেই ক্যান্সেল করা হলে ইতিমধ্যে সংরক্ষিত হয়ে গেছে
            throw e
        } catch (e: PartialStreamException) {
            streamUiFlushJob?.cancel()
            ttsManager.stop(invokeCallback = false)
            if (e.partialText.isNotBlank()) {
                repository.saveAssistantMessage(
                    conversationId = convId,
                    replyText = e.partialText,
                    inputMode = inputMode
                )
            }
            val partialErr = appContext.getString(R.string.stream_interrupted_error)
            _errorMessage.value = partialErr
            if (_voiceModeState.value.isOpen) {
                _voiceModeState.update {
                    it.copy(phase = VoiceModePhase.ERROR, errorText = partialErr, aiReplyPreview = e.partialText)
                }
            }
        } catch (e: Exception) {
            streamUiFlushJob?.cancel()
            ttsManager.stop(invokeCallback = false)
            val friendlyError = ErrorFormatter.format(appContext, e)
            _errorMessage.value = friendlyError
            if (_voiceModeState.value.isOpen) {
                _voiceModeState.update {
                    it.copy(phase = VoiceModePhase.ERROR, errorText = friendlyError)
                }
            }
        } finally {
            slowWarningJob?.cancel()
            streamUiFlushJob?.cancel()
            latestPartialStreamText = ""
            activeConvIdForPartial = null
            _isTyping.value = false
            _streamUiStatus.value = StreamUiStatus.IDLE
            _runningToolLabel.value = null
            _streamingReplyText.value = null
            _slowResponseWarning.value = false
        }
    }

    private suspend fun generateAiTitleSafely(
        convId: String,
        model: ModelConfig,
        apiKey: String,
        userText: String,
        assistantReply: String
    ) {
        try {
            val provider = providerFactory.getProvider(model.providerType)
            val prompt = "নিচের কথোপকথনের জন্য মাত্র ৩-৫ শব্দের একটি সংক্ষিপ্ত শিরোনাম দাও (কোনো কোটেশন চিহ্ন ছাড়া):\nUser: ${userText.take(150)}\nAI: ${assistantReply.take(150)}"
            val title = provider.sendMessage(
                config = model,
                apiKey = apiKey,
                systemPrompt = "You generate concise 3-5 word chat titles only.",
                history = listOf(ChatMessage(role = "user", content = prompt))
            ).trim().replace("\"", "").take(50)
            if (title.isNotBlank()) {
                repository.renameConversation(convId, title)
            }
        } catch (_: Exception) {
        }
    }

    // ==================== ভয়েস মোড ও TTS কন্ট্রোল ====================

    fun openVoiceMode() {
        ttsManager.stop(invokeCallback = false)
        voiceFailoverAnnouncedInSession = false
        _voiceModeState.value = VoiceModeUiState(
            isOpen = true,
            phase = VoiceModePhase.LISTENING,
            userTranscript = "",
            aiReplyPreview = ""
        )
        startVoiceListening()
    }

    fun closeVoiceMode() {
        stopGeneration()
        confirmationTimeoutJob?.cancel()
        resolveConfirmationCancel()
        speechInputManager.cancel()
        ttsManager.stop(invokeCallback = false)
        _voiceModeState.value = VoiceModeUiState(isOpen = false)
    }

    fun startVoiceListening() {
        ttsManager.stop(invokeCallback = false)
        speechInputManager.clearError()
        _voiceModeState.update {
            it.copy(
                phase = VoiceModePhase.LISTENING,
                errorRes = null,
                errorText = null
            )
        }
        speechInputManager.startListening(
            languageOption = speechLanguage.value,
            onFinalResult = { recognizedText ->
                sendMessage(recognizedText, InputMode.VOICE)
            },
            onError = { errorRes ->
                _voiceModeState.update {
                    it.copy(
                        phase = VoiceModePhase.ERROR,
                        errorRes = errorRes,
                        errorText = null
                    )
                }
                if (continuousVoiceMode.value) {
                    viewModelScope.launch {
                        delay(1200L)
                        if (_voiceModeState.value.isOpen && _voiceModeState.value.phase == VoiceModePhase.ERROR) {
                            startVoiceListening()
                        }
                    }
                }
            }
        )
    }

    fun stopOrInterruptVoiceMode() {
        when (_voiceModeState.value.phase) {
            VoiceModePhase.LISTENING -> {
                speechInputManager.stopListening()
                _voiceModeState.update { it.copy(phase = VoiceModePhase.IDLE) }
            }
            VoiceModePhase.THINKING,
            VoiceModePhase.SPEAKING -> {
                stopGeneration()
                _voiceModeState.update { it.copy(phase = VoiceModePhase.IDLE) }
            }
            else -> startVoiceListening()
        }
    }

    fun toggleSpeakMessage(message: ChatMessage) {
        if (speakingMessageId.value == message.id && isSpeaking.value) {
            ttsManager.stop(invokeCallback = false)
        } else {
            ttsManager.speak(message.id, message.content)
        }
    }

    fun dismissMissingBengaliDialog() {
        ttsManager.dismissMissingBengaliDialog()
    }

    suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean {
        val provider = providerFactory.getProvider(config.providerType)
        return provider.testConnection(config, apiKey)
    }

    override fun onCleared() {
        super.onCleared()
        confirmationTimeoutJob?.cancel()
        speechInputManager.release()
        ttsManager.release()
    }
}
