package com.example.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.R
import com.example.accessibility.AppAccessPolicy
import com.example.data.AiRepository
import com.example.model.ActionCardItem
import com.example.model.InputMode
import com.example.model.SpeechLanguageOption
import com.example.network.AiProviderFactory
import com.example.network.ErrorFormatter
import com.example.network.FailoverCandidateResolver
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
import com.example.tools.PromptAndToolOptimizer
import com.example.tools.RiskLevel
import com.example.tools.ToolDefinition
import com.example.tools.ToolRegistry
import com.example.tools.VoiceConfirmationParser
import com.example.AiAssistantApplication
import com.example.voice.SpeechInputManager
import com.example.automation.command.CommandRouter
import com.example.tools.ToolExecutionContext
import com.example.tools.ToolGateway
import com.example.voice.live.LiveStartResult
import com.example.voice.live.LiveToolDeclarations
import com.example.voice.live.LiveVoiceCoordinator
import com.example.voice.live.VoiceMode
import com.example.voice.TtsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * অন্য অ্যাপের ওপর থেকে ব্যাকগ্রাউন্ড ভয়েস সেশন পরিচালনা করে:
 * ১) SpeechRecognizer দিয়ে ভয়েস গ্রহণ
 * ২) কেবল প্রসেসিংয়ের সময় WakeLock ধরে ধাপ ৩ Agent লুপ চালানো
 * ৩) লক স্ক্রিনে HIGH রিস্ক ব্লক ও LOW রিস্ক সেটিং অনুযায়ী যাচাই
 * ৪) Room-এ ১০ মিনিটের নিয়মে সেভ করা
 * ৫) ৮ সেকেন্ড নীরব থাকলে অটো-বন্ধ হয়ে Wake Word পুনরায় চালু করা
 */
class BackgroundVoiceSessionManager(
    private val context: Context,
    private val repository: AiRepository,
    private val controller: AssistantRuntimeController,
    private val onSessionEnded: () -> Unit
) {
    companion object {
        const val AUTO_CLOSE_SECONDS = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val providerFactory = AiProviderFactory()
    private val speechInputManager = SpeechInputManager(context)
    private val ttsManager = TtsManager(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private var activeSessionJob: Job? = null
    private var autoCloseJob: Job? = null
    private var confirmationTimerJob: Job? = null
    private var rmsObserverJob: Job? = null
    /** Non-null only while a Gemini Live session owns the microphone (additional voice mode; legacy is the default). */
    private var live: LiveVoiceCoordinator? = null

    private var activeConfirmationDeferred: CompletableDeferred<ConfirmationOutcome>? = null
    private var activeContactDeferred: CompletableDeferred<ContactMatch?>? = null
    private var sessionConversationId: String? = null
    private var voiceFailoverAnnouncedInSession: Boolean = false

    private val agentBridge = object : AgentUiBridge {
        override suspend fun requestPermissions(
            permissions: List<String>,
            requiresWriteSettings: Boolean,
            explanationRes: Int
        ): Boolean {
            // ব্যাকগ্রাউন্ড সেশন থেকে সিস্টেম পারমিশন ডায়ালগ চাওয়া সম্ভব নয়, তাই বর্তমান স্টেট যাচাই করে জানায়
            val hasRuntime = permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            val hasWrite = !requiresWriteSettings || Settings.System.canWrite(context)
            return hasRuntime && hasWrite
        }

        override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? {
            val deferred = CompletableDeferred<ContactMatch?>()
            activeContactDeferred = deferred
            controller.updateSessionState {
                it.copy(
                    sessionPhase = BackgroundSessionPhase.WAITING_CONFIRMATION,
                    pendingContactSelection = PendingContactSelection(query, matches, deferred)
                )
            }
            return deferred.await()
        }

        override suspend fun requestConfirmation(
            tool: ToolDefinition,
            args: JSONObject,
            isVoiceMode: Boolean,
            confirmContext: ConfirmationRequestContext
        ): ConfirmationOutcome {
            val effectiveRisk = confirmContext.effectiveRiskLevel
            // লক স্ক্রিন নিরাপত্তা যাচাই:
            // লক অবস্থায় HIGH/MEDIUM রিস্ক কাজ কখনোই চলবে না; LOW রিস্ক কেবল ইউজার সেটিংসে অনুমোদিত হলেই চলবে
            val isLocked = keyguardManager?.isKeyguardLocked == true
            if (isLocked) {
                val allowLow = repository.allowLowRiskLockedFlow.first()
                if (effectiveRisk != RiskLevel.LOW || !allowLow) {
                    return ConfirmationOutcome.Cancelled(context.getString(R.string.lockscreen_unlock_required))
                }
            }

            val confirmMedium = repository.confirmMediumRiskFlow.first()
            val needsConfirmation = effectiveRisk == RiskLevel.HIGH ||
                (effectiveRisk == RiskLevel.MEDIUM && confirmMedium)
            if (!needsConfirmation) {
                return ConfirmationOutcome.Confirmed()
            }

            val deferred = CompletableDeferred<ConfirmationOutcome>()
            activeConfirmationDeferred = deferred
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
                summaryText = tool.bengaliTitle,
                targetAppName = confirmContext.targetAppName,
                targetButtonLabel = confirmContext.targetButtonLabel,
                actionContextDetails = confirmContext.actionContextDetails,
                remainingSeconds = 60,
                isVoiceListeningForConfirm = false,
                deferred = deferred
            )

            controller.updateSessionState {
                it.copy(
                    sessionPhase = BackgroundSessionPhase.WAITING_CONFIRMATION,
                    pendingConfirmation = pending
                )
            }

            confirmationTimerJob?.cancel()
            confirmationTimerJob = scope.launch {
                for (sec in 59 downTo 0) {
                    delay(1000L)
                    if (!deferred.isActive) return@launch
                    controller.updateSessionState { st ->
                        st.copy(pendingConfirmation = st.pendingConfirmation?.copy(remainingSeconds = sec))
                    }
                }
                if (deferred.isActive) {
                    speechInputManager.cancel()
                    ttsManager.stop(invokeCallback = false)
                    controller.updateSessionState { it.copy(pendingConfirmation = null) }
                    deferred.complete(ConfirmationOutcome.Cancelled("৬০ সেকেন্ড সময় শেষ"))
                }
            }

            // Gemini Live owns the microphone: no TTS prompt, no second SpeechRecognizer. The user confirms by tapping the
            // on-screen card only (a spoken "yes" is never trusted as consent because the model could mishear or be steered).
            if (live != null) {
                controller.updateSessionState { it.copy(pendingConfirmation = it.pendingConfirmation?.copy(isVoiceListeningForConfirm = false)) }
                return deferred.await()
            }

            val speechLang = repository.speechLanguageFlow.first()
            val targetLabel = if (recipientName.isNotBlank()) recipientName else phoneNumber
            val spokenQuestion = when (tool.name) {
                "make_call" -> context.getString(R.string.voice_confirm_call_prompt, targetLabel)
                "send_sms", "send_whatsapp_message" -> context.getString(R.string.voice_confirm_sms_prompt, targetLabel, smsMessage)
                "click_element" -> "${confirmContext.targetAppName} অ্যাপে '${confirmContext.targetButtonLabel}' বাটনে ক্লিক করব? হ্যাঁ বা না বলুন।"
                else -> context.getString(R.string.voice_confirm_generic_prompt, tool.bengaliTitle)
            }

            ttsManager.speak(null, spokenQuestion) {
                if (deferred.isActive) {
                    controller.updateSessionState { st ->
                        st.copy(
                            pendingConfirmation = st.pendingConfirmation?.copy(isVoiceListeningForConfirm = true)
                        )
                    }
                    speechInputManager.startListening(
                        languageOption = speechLang,
                        onFinalResult = { spokenReply ->
                            if (deferred.isActive) {
                                val yes = VoiceConfirmationParser.parseYesNo(spokenReply)
                                confirmationTimerJob?.cancel()
                                controller.updateSessionState { it.copy(pendingConfirmation = null) }
                                if (yes) {
                                    deferred.complete(ConfirmationOutcome.Confirmed())
                                } else {
                                    deferred.complete(ConfirmationOutcome.Cancelled("ভয়েসে বাতিল বা অস্পষ্ট উত্তর"))
                                }
                            }
                        },
                        onError = {
                            if (deferred.isActive) {
                                confirmationTimerJob?.cancel()
                                controller.updateSessionState { it.copy(pendingConfirmation = null) }
                                deferred.complete(ConfirmationOutcome.Cancelled("ভয়েস নিশ্চিতকরণ পাওয়া যায়নি"))
                            }
                        }
                    )
                }
            }

            return deferred.await()
        }

        override fun onActionCardChanged(card: ActionCardItem) {
            controller.updateSessionState { st ->
                val list = st.actionCards.toMutableList()
                val idx = list.indexOfFirst { it.id == card.id }
                if (idx >= 0) list[idx] = card else list.add(card)
                st.copy(actionCards = list)
            }
        }

        override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {
            repository.recordActionLog(toolName, argsSummary, status)
        }
    }

    private val agentOrchestrator = AgentOrchestrator(context, agentBridge)

    /** Same safety pipeline as the agent loop, for Gemini Live tool calls and the optional rule-based fast path. */
    private val toolGateway by lazy {
        ToolGateway(
            appContext = context,
            bridge = agentBridge,
            confirmationContext = { tool, args -> agentOrchestrator.resolveConfirmationContext(tool, args) },
            executionContext = {
                ToolExecutionContext(
                    appContext = context,
                    preferredSimSlot = repository.preferredSimSlotFlow.first(),
                    accessPolicy = AppAccessPolicy(
                        allowlistMode = repository.allowlistModeFlow.first(),
                        customBlockedPackages = repository.customBlockedPackagesFlow.first(),
                        removedDefaultBlockedPackages = repository.removedDefaultBlockedPackagesFlow.first(),
                        allowedPackages = repository.allowedPackagesFlow.first()
                    ),
                    notifAllowedPackages = repository.notifAllowedPackagesFlow.first(),
                    screenshotVisionEnabled = repository.screenshotVisionEnabledFlow.first()
                )
            },
            disabledTools = { repository.disabledToolsFlow.first() },
            onUiAutomationRunning = { running, label -> controller.setUiAutomationRunning(running, label) }
        )
    }
    private val commandRouter by lazy { CommandRouter(toolGateway) }

    init {
        controller.onConfirmAction = { editedSms ->
            confirmationTimerJob?.cancel()
            speechInputManager.cancel()
            ttsManager.stop(invokeCallback = false)
            controller.updateSessionState { it.copy(pendingConfirmation = null) }
            activeConfirmationDeferred?.takeIf { it.isActive }?.complete(ConfirmationOutcome.Confirmed(editedSms))
        }
        controller.onCancelAction = {
            confirmationTimerJob?.cancel()
            speechInputManager.cancel()
            ttsManager.stop(invokeCallback = false)
            controller.updateSessionState { it.copy(pendingConfirmation = null) }
            activeConfirmationDeferred?.takeIf { it.isActive }?.complete(ConfirmationOutcome.Cancelled("ইউজার বাতিল করেছেন"))
        }
        controller.onPickContact = { chosen ->
            controller.updateSessionState { it.copy(pendingContactSelection = null) }
            activeContactDeferred?.takeIf { it.isActive }?.complete(chosen)
        }
        controller.onStopSession = {
            endSession()
        }
        controller.onRetrySession = {
            startVoiceSession(playChime = false)
        }
    }

    fun startVoiceSession(playChime: Boolean = true) {
        if (live != null) return // a Gemini Live session is already starting/running
        autoCloseJob?.cancel()
        activeSessionJob?.cancel()
        sessionConversationId = null
        voiceFailoverAnnouncedInSession = false

        scope.launch {
            val chimeOn = repository.chimeEnabledFlow.first()
            if (playChime && chimeOn) {
                controller.playWakeChimeAndVibrate()
            }

            // মাইক হ্যান্ডঅফ: WakeWordEngine-এর AudioRecord পুরোপুরি মুক্ত হওয়ার জন্য সংক্ষিপ্ত অপেক্ষা
            delay(140L)

            // Gemini Live is an additional, opt-in voice mode. Anything that stops it from starting
            // (no key, no permission, owner voice not ready, focus denied…) falls back to the legacy flow below.
            var notice: String? = null
            if (repository.liveVoiceSettingsFlow.first().mode == VoiceMode.GEMINI_LIVE) {
                val coordinator = createLiveCoordinator()
                live = coordinator
                when (val r = coordinator.start()) {
                    LiveStartResult.Started -> return@launch
                    is LiveStartResult.Fallback -> {
                        coordinator.shutdown()
                        live = null
                        notice = context.getString(r.reason)
                    }
                }
            }
            startLegacySession(notice)
        }
    }

    private fun createLiveCoordinator(): LiveVoiceCoordinator {
        val app = context.applicationContext as AiAssistantApplication
        return LiveVoiceCoordinator(
            context = context,
            repository = repository,
            controller = controller,
            models = app.voskModelManager,
            owner = app.ownerVoiceManager,
            onFailed = { reasonRes -> onLiveFailed(reasonRes) },
            onIdleTimeout = { endSession() },
            toolProvider = {
                val disabled = repository.disabledToolsFlow.first()
                LiveToolDeclarations.declare(LiveToolDeclarations.select(ToolRegistry.getEnabledTools(disabled)))
            },
            toolExecutor = { name, args -> toolGateway.execute(name, args, isVoice = true) }
        )
    }

    /** Live died while running (bad key, retries exhausted, mic/focus lost): release it, then continue with the legacy flow. */
    private fun onLiveFailed(reasonRes: Int) {
        val failing = live ?: return
        live = null
        scope.launch {
            failing.shutdown()
            startLegacySession(context.getString(reasonRes))
        }
    }

    private suspend fun startLegacySession(notice: String?) {
        run {
            val rate = repository.ttsSpeechRateFlow.first()
            val pitch = repository.ttsPitchFlow.first()
            ttsManager.updateVoiceParams(rate, pitch)

            controller.updateSessionState {
                it.copy(
                    isSessionActive = true,
                    isLiveVoice = false,
                    liveStatus = "",
                    sessionPhase = BackgroundSessionPhase.LISTENING,
                    userTranscript = "",
                    aiReplyPreview = "",
                    errorMessage = notice,
                    autoCloseSecondsRemaining = null,
                    actionCards = emptyList(),
                    pendingConfirmation = null,
                    pendingContactSelection = null
                )
            }

            observeSpeechRms()
            beginListeningTurn()
        }
    }

    private fun observeSpeechRms() {
        rmsObserverJob?.cancel()
        rmsObserverJob = scope.launch {
            speechInputManager.state.collect { st ->
                if (controller.state.value.sessionPhase == BackgroundSessionPhase.LISTENING) {
                    controller.updateSessionState {
                        it.copy(
                            rmsNormalized = st.rmsNormalized,
                            userTranscript = st.partialText
                        )
                    }
                }
            }
        }
    }

    private fun beginListeningTurn() {
        autoCloseJob?.cancel()
        scope.launch {
            val lang = repository.speechLanguageFlow.first()
            controller.updateSessionState {
                it.copy(
                    sessionPhase = BackgroundSessionPhase.LISTENING,
                    errorMessage = null,
                    autoCloseSecondsRemaining = null
                )
            }
            speechInputManager.startListening(
                languageOption = lang,
                onFinalResult = { recognized ->
                    processRecognizedText(recognized)
                },
                onError = { errRes ->
                    val errText = context.getString(errRes)
                    controller.updateSessionState {
                        it.copy(
                            sessionPhase = BackgroundSessionPhase.ERROR,
                            errorMessage = errText,
                            rmsNormalized = 0f
                        )
                    }
                    scheduleAutoCloseCountdown()
                }
            )
        }
    }

    private fun processRecognizedText(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isBlank()) {
            scheduleAutoCloseCountdown()
            return
        }

        val lower = trimmed.lowercase()
        if (controller.state.value.isUiAutomationRunning &&
            (lower.contains("থামো") || lower.contains("বন্ধ করো") || lower == "stop" || lower == "abort")
        ) {
            controller.requestStopUiAgent(byTouch = false)
            scheduleAutoCloseCountdown()
            return
        }

        activeSessionJob?.cancel()
        activeSessionJob = scope.launch {
            // প্রসেসিংয়ের সময়টুকুই কেবল Partial WakeLock ধরা হয় (প্যাসিভ লিসেনিংয়ে নয়)
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AiAssistant::ProcessingWakeLock"
            )?.apply {
                setReferenceCounted(false)
                runCatching { acquire(45_000L) }
            }

            try {
                controller.updateSessionState {
                    it.copy(
                        sessionPhase = BackgroundSessionPhase.THINKING,
                        userTranscript = trimmed,
                        aiReplyPreview = "",
                        errorMessage = null,
                        autoCloseSecondsRemaining = null
                    )
                }

                val autoSpeak = repository.autoSpeakOnVoiceFlow.first()
                val onTtsDone: () -> Unit = {
                    scope.launch {
                        controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.IDLE) }
                        scheduleAutoCloseCountdown()
                    }
                }

                // ধাপ ২ (পরিচয়): অফলাইন-প্রথম IdentityResponder (শূন্য API কল)
                val identityReply = IdentityResponder.match(trimmed, InputMode.VOICE)
                if (identityReply != null) {
                    val targetConvId = sessionConversationId ?: repository.getRecentConversationIdForBackground()
                    val (convId, _) = repository.ensureConversationAndSaveUserMessage(
                        existingConversationId = targetConvId,
                        userText = trimmed,
                        inputMode = InputMode.VOICE
                    )
                    sessionConversationId = convId
                    controller.updateSessionState { it.copy(activeConversationId = convId) }
                    repository.saveAssistantMessage(
                        conversationId = convId,
                        replyText = identityReply.replyText,
                        inputMode = InputMode.VOICE
                    )
                    controller.updateSessionState { it.copy(aiReplyPreview = identityReply.replyText) }
                    if (autoSpeak) {
                        controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.SPEAKING) }
                        ttsManager.speak(null, identityReply.replyText, onTtsDone)
                    } else {
                        controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.IDLE) }
                        scheduleAutoCloseCountdown()
                    }
                    return@launch
                }

                // Optional offline fast path (opt-in, off by default): a clearly phrased command runs through ToolGateway —
                // never directly — so confirmation, permissions, kill switch and secure-screen checks still apply.
                val routed = commandRouter.tryRoute(trimmed, isVoice = true)
                if (routed != null) {
                    val reply = routed.result.message
                    controller.updateSessionState { it.copy(aiReplyPreview = reply) }
                    if (autoSpeak) {
                        controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.SPEAKING) }
                        ttsManager.speak(null, reply, onTtsDone)
                    } else {
                        controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.IDLE) }
                        scheduleAutoCloseCountdown()
                    }
                    return@launch
                }

                val models = repository.modelConfigsFlow.first()
                val activeModel = models.find { it.isActive } ?: models.firstOrNull()
                if (activeModel == null) {
                    showErrorAndAutoClose("আগে অ্যাপ থেকে একটি এআই মডেল ও API Key যোগ করুন।")
                    return@launch
                }

                val autoFailover = repository.autoFailoverEnabledFlow.first()
                val orderIds = repository.failoverOrderIdsFlow.first()
                val candidates = FailoverCandidateResolver.buildCandidates(
                    activeModel = activeModel,
                    allModels = models,
                    failoverOrderIds = orderIds,
                    autoFailoverEnabled = autoFailover,
                    requiresVision = false,
                    keyResolver = { repository.getApiKey(it) },
                    healthTracker = ModelHealthTracker.shared,
                    providerFactory = providerFactory
                )
                if (candidates.isEmpty()) {
                    val allKeyed = FailoverCandidateResolver.buildCandidates(
                        activeModel = activeModel,
                        allModels = models,
                        failoverOrderIds = orderIds,
                        autoFailoverEnabled = autoFailover,
                        requiresVision = false,
                        keyResolver = { repository.getApiKey(it) },
                        healthTracker = ModelHealthTracker.shared,
                        providerFactory = providerFactory,
                        skipCooldownModels = false
                    )
                    if (allKeyed.isEmpty()) {
                        showErrorAndAutoClose("নির্বাচিত মডেলের API Key পাওয়া যায়নি।")
                    } else {
                        showErrorAndAutoClose("সব মডেল সাময়িক কুলডাউনে আছে। কিছুক্ষণ পর আবার চেষ্টা করুন।")
                    }
                    return@launch
                }
                val primaryCand = candidates.first()

                // ১০ মিনিট নিয়ম: শেষ চ্যাট ১০ মিনিটের মধ্যে হলে ওই চ্যাটেই জুড়বে
                val targetConvId = sessionConversationId ?: repository.getRecentConversationIdForBackground()
                val (convId, _) = repository.ensureConversationAndSaveUserMessage(
                    existingConversationId = targetConvId,
                    userText = trimmed,
                    inputMode = InputMode.VOICE
                )
                sessionConversationId = convId
                controller.updateSessionState { it.copy(activeConversationId = convId) }

                val fullHistory = repository.getMessagesSnapshot(convId)
                val historyLimit = repository.historyLimitFlow.first()
                val history = PromptAndToolOptimizer.buildBoundedHistory(fullHistory, historyLimit)
                val disabled = repository.disabledToolsFlow.first()
                val allEnabledTools = if (primaryCand.config.supportsTools || candidates.any { it.config.supportsTools }) {
                    ToolRegistry.getEnabledTools(disabled)
                } else emptyList()
                val enabledTools = PromptAndToolOptimizer.selectRelevantTools(trimmed, allEnabledTools, history)
                val basePrompt = repository.systemPromptFlow.first()
                val logSensitive = repository.logSensitiveDataFlow.first()
                val simSlot = repository.preferredSimSlotFlow.first()
                val standardMaxSteps = repository.standardMaxStepsFlow.first()

                val uiMaxSteps = repository.uiAgentMaxStepsFlow.first()
                val accessPolicy = AppAccessPolicy(
                    allowlistMode = repository.allowlistModeFlow.first(),
                    customBlockedPackages = repository.customBlockedPackagesFlow.first(),
                    removedDefaultBlockedPackages = repository.removedDefaultBlockedPackagesFlow.first(),
                    allowedPackages = repository.allowedPackagesFlow.first()
                )
                val notifAllowed = repository.notifAllowedPackagesFlow.first()
                val screenshotVision = repository.screenshotVisionEnabledFlow.first()

                val allMemories = repository.getAllMemoriesSnapshot()
                val relevantMemories = MemoryTools.selectRelevantMemories(trimmed, allMemories)
                val memoryContext = relevantMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }

                if (autoSpeak) {
                    ttsManager.beginStreamingSession(messageId = null, onCompleted = onTtsDone)
                }

                var lastPreviewUpdateMs = 0L
                val result = agentOrchestrator.runAgentLoop(
                    provider = primaryCand.provider,
                    config = primaryCand.config,
                    apiKey = primaryCand.apiKey,
                    baseSystemPrompt = basePrompt,
                    history = history,
                    enabledTools = enabledTools,
                    inputMode = InputMode.VOICE,
                    logSensitiveData = logSensitive,
                    preferredSimSlot = simSlot,
                    uiMaxSteps = uiMaxSteps,
                    standardMaxSteps = standardMaxSteps,
                    accessPolicy = accessPolicy,
                    notifAllowedPackages = notifAllowed,
                    screenshotVisionEnabled = screenshotVision,
                    runtimeController = controller,
                    backupCandidates = candidates.drop(1),
                    healthTracker = ModelHealthTracker.shared,
                    memoryContext = memoryContext,
                    onFailoverSwitched = { notice ->
                        if (autoSpeak && !voiceFailoverAnnouncedInSession) {
                            voiceFailoverAnnouncedInSession = true
                            ttsManager.enqueueStreamingDelta("${notice.fromModelName} ব্যস্ত, ${notice.toModelName} দিয়ে বলছি। ")
                        }
                    },
                    onUsageRecorded = { modelId, modelName, providerType, inTok, outTok, success, wasFailover ->
                        repository.recordUsageStat(modelId, modelName, providerType, inTok, outTok, success, wasFailover)
                    },
                    onVoiceProgress = { phrase ->
                        if (autoSpeak) ttsManager.speak(null, phrase)
                    },
                    onPhaseChanged = { phase, _ ->
                        controller.updateSessionState {
                            it.copy(
                                sessionPhase = when (phase) {
                                    AgentStreamPhase.STREAMING -> if (autoSpeak) BackgroundSessionPhase.SPEAKING else BackgroundSessionPhase.THINKING
                                    else -> BackgroundSessionPhase.THINKING
                                }
                            )
                        }
                    },
                    onStreamDelta = { delta, accumulated ->
                        if (autoSpeak) {
                            ttsManager.enqueueStreamingDelta(delta)
                        }
                        val now = System.nanoTime() / 1_000_000L
                        if (now - lastPreviewUpdateMs >= 80L) {
                            lastPreviewUpdateMs = now
                            controller.updateSessionState { it.copy(aiReplyPreview = accumulated) }
                        }
                    },
                    onStreamReset = {
                        if (autoSpeak) {
                            ttsManager.beginStreamingSession(messageId = null, onCompleted = onTtsDone)
                        }
                        controller.updateSessionState { it.copy(aiReplyPreview = "") }
                    }
                )

                repository.saveAssistantMessage(
                    conversationId = convId,
                    replyText = result.finalReply,
                    inputMode = InputMode.VOICE
                )

                controller.updateSessionState { it.copy(aiReplyPreview = result.finalReply) }
                if (autoSpeak) {
                    controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.SPEAKING) }
                    ttsManager.finishStreamingSession(fallbackFullText = result.finalReply)
                } else {
                    controller.updateSessionState { it.copy(sessionPhase = BackgroundSessionPhase.IDLE) }
                    scheduleAutoCloseCountdown()
                }
            } catch (e: PartialStreamException) {
                val convId = sessionConversationId
                if (convId != null && e.partialText.isNotBlank()) {
                    repository.saveAssistantMessage(
                        conversationId = convId,
                        replyText = e.partialText,
                        inputMode = InputMode.VOICE
                    )
                    controller.updateSessionState { it.copy(aiReplyPreview = e.partialText) }
                }
                ttsManager.stop(invokeCallback = false)
                showErrorAndAutoClose(context.getString(R.string.stream_interrupted_error))
            } catch (e: Exception) {
                ttsManager.stop(invokeCallback = false)
                showErrorAndAutoClose(ErrorFormatter.format(context, e))
            } finally {
                if (wakeLock?.isHeld == true) {
                    runCatching { wakeLock.release() }
                }
            }
        }
    }

    private fun showErrorAndAutoClose(msg: String) {
        controller.updateSessionState {
            it.copy(
                sessionPhase = BackgroundSessionPhase.ERROR,
                errorMessage = msg
            )
        }
        scheduleAutoCloseCountdown()
    }

    /**
     * সেশন শেষে ৮ সেকেন্ড নীরব থাকলে ওভারলে বন্ধ করে Wake Word ইঞ্জিন আবার চালু করে।
     */
    private fun scheduleAutoCloseCountdown() {
        autoCloseJob?.cancel()
        autoCloseJob = scope.launch {
            for (sec in AUTO_CLOSE_SECONDS downTo 1) {
                controller.updateSessionState { it.copy(autoCloseSecondsRemaining = sec) }
                delay(1000L)
            }
            endSession()
        }
    }

    fun endSession() {
        autoCloseJob?.cancel()
        confirmationTimerJob?.cancel()
        activeSessionJob?.cancel()
        rmsObserverJob?.cancel()

        activeConfirmationDeferred?.takeIf { it.isActive }?.complete(ConfirmationOutcome.Cancelled("সেশন বন্ধ"))
        activeContactDeferred?.takeIf { it.isActive }?.complete(null)

        speechInputManager.cancel()
        ttsManager.stop(invokeCallback = false)

        val liveSession = live
        live = null
        if (liveSession != null) {
            // The microphone must be fully released before the wake-word engine is re-armed (onSessionEnded).
            scope.launch {
                liveSession.shutdown()
                finishEndSession()
            }
        } else {
            finishEndSession()
        }
    }

    private fun finishEndSession() {
        controller.updateSessionState {
            it.copy(
                isSessionActive = false,
                isLiveVoice = false,
                liveStatus = "",
                sessionPhase = BackgroundSessionPhase.IDLE,
                pendingConfirmation = null,
                pendingContactSelection = null,
                autoCloseSecondsRemaining = null,
                rmsNormalized = 0f
            )
        }
        onSessionEnded()
    }

    fun release() {
        val liveSession = live
        live = null
        if (liveSession == null) {
            endSession()
            speechInputManager.release()
            ttsManager.release()
            scope.cancel()
        } else {
            autoCloseJob?.cancel(); confirmationTimerJob?.cancel(); activeSessionJob?.cancel(); rmsObserverJob?.cancel()
            speechInputManager.cancel()
            ttsManager.stop(invokeCallback = false)
            scope.launch {
                liveSession.shutdown()
                controller.updateSessionState { it.copy(isSessionActive = false, isLiveVoice = false, liveStatus = "", sessionPhase = BackgroundSessionPhase.IDLE, rmsNormalized = 0f) }
                speechInputManager.release()
                ttsManager.release()
                scope.cancel()
            }
        }
    }
}
