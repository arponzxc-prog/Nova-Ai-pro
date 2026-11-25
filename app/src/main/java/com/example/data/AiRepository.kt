package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.example.camerapill.CameraPillLogic
import com.example.camerapill.CameraPillMode
import com.example.camerapill.CameraPillSettings
import com.example.data.local.ActionLogEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageSearchRow
import com.example.data.local.ModelUsageSummary
import com.example.data.local.RoutineEntity
import com.example.data.local.UsageStatEntity
import com.example.data.local.toEntity
import com.example.model.ActionLog
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.HistoryLimitPreset
import com.example.model.InputMode
import com.example.model.ModelConfig
import com.example.model.ProviderType
import com.example.model.SpeechLanguageOption
import com.example.security.SecureKeyStore
import com.example.automation.core.AutomationFeature
import com.example.automation.core.AutomationSettings
import com.example.voice.live.LiveVoiceSettings
import com.example.voice.live.VoiceMode
import com.example.voice.owner.OwnerVoiceStrictness
import com.example.voice.owner.SecretStore
import com.example.tools.SearchProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "ai_assistant_settings")

/**
 * সমস্ত লোকাল ডেটা (DataStore সেটিংস, Keystore API Key এবং Room চ্যাট ও অ্যাকশন লগ)
 * পরিচালনার জন্য একক Repository।
 */
class AiRepository(private val context: Context) {
    private val secureKeyStore = SecureKeyStore(context)
    private val chatDao = AppDatabase.getInstance(context).chatDao()

    companion object {
        private val MODELS_KEY = stringPreferencesKey("model_configs_json")
        private val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val NOVA_THEME_KEY = stringPreferencesKey("nova_theme_choice")

        // ধাপ ২: ভয়েস এবং চ্যাট হিস্টোরি সেটিংস
        private val SPEECH_LANGUAGE_KEY = stringPreferencesKey("speech_language")
        private val TTS_SPEECH_RATE_KEY = floatPreferencesKey("tts_speech_rate")
        private val TTS_PITCH_KEY = floatPreferencesKey("tts_pitch")
        private val VOICE_PERSONA_ID_KEY = stringPreferencesKey("voice_persona_id")
        private val AUTO_SPEAK_VOICE_KEY = booleanPreferencesKey("auto_speak_on_voice")
        private val CONTINUOUS_VOICE_KEY = booleanPreferencesKey("continuous_voice_mode")
        private val AUTO_TITLE_AI_KEY = booleanPreferencesKey("auto_title_with_ai")

        // ধাপ ৩: টুল, কনফার্মেশন, লগিং ও ডুয়াল সিম সেটিংস
        private val DISABLED_TOOLS_KEY = stringSetPreferencesKey("disabled_tools")
        private val CONFIRM_MEDIUM_RISK_KEY = booleanPreferencesKey("confirm_medium_risk")
        private val LOG_SENSITIVE_DATA_KEY = booleanPreferencesKey("log_sensitive_data")
        private val PREFERRED_SIM_SLOT_KEY = intPreferencesKey("preferred_sim_slot")

        // ধাপ ৪: ব্যাকগ্রাউন্ড সার্ভিস, Wake Word, ওভারলে, লক স্ক্রিন ও সময়সূচি
        private val BG_LISTENING_ENABLED_KEY = booleanPreferencesKey("bg_listening_enabled")
        private val WAKE_WORD_PHRASE_KEY = stringPreferencesKey("wake_word_phrase")
        private val WAKE_WORD_THRESHOLD_KEY = floatPreferencesKey("wake_word_threshold")
        // Voice engine (Gemini Live / Owner Voice / Hands-free)
        private val LIVE_MODE_KEY = stringPreferencesKey("live_voice_mode")
        private val LIVE_MODEL_KEY = stringPreferencesKey("live_voice_model")
        private val LIVE_VOICE_KEY = stringPreferencesKey("live_voice_name")
        private val LIVE_HANDS_FREE_KEY = booleanPreferencesKey("live_hands_free")
        private val OWNER_VOICE_ENABLED_KEY = booleanPreferencesKey("owner_voice_enabled")
        private val OWNER_VOICE_STRICTNESS_KEY = stringPreferencesKey("owner_voice_strictness")
        const val GEMINI_LIVE_KEY_ID = "gemini_live_api_key"
        const val YOUTUBE_API_KEY_ID = "youtube_data_api_key"
        // Automation (Step 2)
        private val AUTOMATION_KILL_KEY = booleanPreferencesKey("automation_kill_switch")
        private val AUTOMATION_FAST_ROUTING_KEY = booleanPreferencesKey("automation_fast_command_routing")
        private fun automationFeatureKey(f: AutomationFeature) = booleanPreferencesKey("automation_feature_${f.name.lowercase()}")

        private val CHIME_ENABLED_KEY = booleanPreferencesKey("chime_enabled")
        private val FLOATING_BUBBLE_ENABLED_KEY = booleanPreferencesKey("floating_bubble_enabled")

        // Camera Pill (AI Live Avatar) settings
        private val PILL_ENABLED_KEY = booleanPreferencesKey("camera_pill_enabled")
        private val PILL_MODE_KEY = stringPreferencesKey("camera_pill_mode")
        private val PILL_AVATAR_ANIM_KEY = booleanPreferencesKey("camera_pill_avatar_anim")
        private val PILL_VISUALIZER_KEY = booleanPreferencesKey("camera_pill_visualizer")
        private val PILL_THINKING_ANIM_KEY = booleanPreferencesKey("camera_pill_thinking_anim")
        private val PILL_SPEAKING_ANIM_KEY = booleanPreferencesKey("camera_pill_speaking_anim")
        private val PILL_SIZE_KEY = floatPreferencesKey("camera_pill_size")
        private val PILL_INTENSITY_KEY = floatPreferencesKey("camera_pill_intensity")
        private val PILL_IDLE_DELAY_KEY = intPreferencesKey("camera_pill_idle_delay_ms")
        private val ALLOW_LOW_RISK_LOCKED_KEY = booleanPreferencesKey("allow_low_risk_locked")
        private val CHARGING_ONLY_WAKE_KEY = booleanPreferencesKey("charging_only_wake")
        private val NIGHT_SCHEDULE_ENABLED_KEY = booleanPreferencesKey("night_schedule_enabled")
        private val NIGHT_START_HOUR_KEY = intPreferencesKey("night_start_hour")
        private val NIGHT_END_HOUR_KEY = intPreferencesKey("night_end_hour")
        private val BOOT_NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("boot_notification_enabled")

        // ধাপ ৫: Accessibility, স্ক্রিন নিরাপত্তা, ব্লকলিস্ট/অ্যালাউলিস্ট, নোটিফিকেশন ফিল্টার ও ভিশন
        private val ACCESSIBILITY_WAS_ENABLED_KEY = booleanPreferencesKey("a11y_was_enabled")
        private val UI_AGENT_MAX_STEPS_KEY = intPreferencesKey("ui_agent_max_steps")
        private val UI_FLOATING_STOP_KEY = booleanPreferencesKey("ui_floating_stop_enabled")
        private val ALLOWLIST_MODE_KEY = booleanPreferencesKey("screen_allowlist_mode")
        private val CUSTOM_BLOCKED_PKGS_KEY = stringSetPreferencesKey("custom_blocked_packages")
        private val REMOVED_DEFAULT_BLOCK_PKGS_KEY = stringSetPreferencesKey("removed_default_blocked_packages")
        private val ALLOWED_PKGS_KEY = stringSetPreferencesKey("screen_allowed_packages")
        private val NOTIF_ALLOWED_PKGS_KEY = stringSetPreferencesKey("notif_allowed_packages")
        private val AUTO_READ_NOTIF_KEY = booleanPreferencesKey("auto_read_notifications")
        private val SCREENSHOT_VISION_KEY = booleanPreferencesKey("screenshot_vision_enabled")

        // পারফরম্যান্স ফিক্স: হিস্টোরি মেমোরি সীমা ও ডিবাগ ল্যাটেন্সি টগল
        private val HISTORY_LIMIT_KEY = stringPreferencesKey("history_limit_preset")
        private val DEBUG_INFO_ENABLED_KEY = booleanPreferencesKey("debug_info_enabled")

        // Phase 1 & Phase 4: অটো API ফেইলওভার ও ডায়নামিক ধাপ-সীমা
        private val AUTO_FAILOVER_ENABLED_KEY = booleanPreferencesKey("auto_failover_enabled")
        private val FAILOVER_ORDER_IDS_KEY = stringPreferencesKey("failover_order_ids_json")
        private val STANDARD_MAX_STEPS_KEY = intPreferencesKey("standard_max_steps")
    }

    val systemPromptFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SYSTEM_PROMPT_KEY] ?: context.getString(R.string.system_prompt_default)
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY] ?: "system"
    }

    val speechLanguageFlow: Flow<SpeechLanguageOption> = context.dataStore.data.map { prefs ->
        val raw = prefs[SPEECH_LANGUAGE_KEY] ?: SpeechLanguageOption.AUTO.tag
        SpeechLanguageOption.entries.find { it.tag == raw } ?: SpeechLanguageOption.AUTO
    }

    val ttsSpeechRateFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[TTS_SPEECH_RATE_KEY] ?: 1.0f
    }

    val ttsPitchFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[TTS_PITCH_KEY] ?: 1.0f
    }

    val voicePersonaIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[VOICE_PERSONA_ID_KEY] ?: "nova"
    }

    val autoSpeakOnVoiceFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_SPEAK_VOICE_KEY] ?: true
    }

    val continuousVoiceModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CONTINUOUS_VOICE_KEY] ?: true
    }

    val autoTitleWithAiFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_TITLE_AI_KEY] ?: false
    }

    val disabledToolsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[DISABLED_TOOLS_KEY] ?: emptySet()
    }

    val confirmMediumRiskFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CONFIRM_MEDIUM_RISK_KEY] ?: true
    }

    val logSensitiveDataFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LOG_SENSITIVE_DATA_KEY] ?: false
    }

    val preferredSimSlotFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PREFERRED_SIM_SLOT_KEY] ?: -1
    }

    val backgroundListeningEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BG_LISTENING_ENABLED_KEY] ?: false
    }

    val wakeWordPhraseFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[WAKE_WORD_PHRASE_KEY] ?: "Hey Jarvis"
    }

    val wakeWordThresholdFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[WAKE_WORD_THRESHOLD_KEY] ?: 0.5f
    }

    val liveVoiceSettingsFlow: Flow<LiveVoiceSettings> = context.dataStore.data.map { prefs ->
        val d = LiveVoiceSettings()
        LiveVoiceSettings(
            mode = VoiceMode.fromName(prefs[LIVE_MODE_KEY]),
            model = prefs[LIVE_MODEL_KEY]?.takeIf { it.isNotBlank() } ?: d.model,
            voiceName = prefs[LIVE_VOICE_KEY]?.takeIf { it.isNotBlank() } ?: d.voiceName,
            handsFree = prefs[LIVE_HANDS_FREE_KEY] ?: false,
            ownerVoiceEnabled = prefs[OWNER_VOICE_ENABLED_KEY] ?: false,
            ownerStrictness = OwnerVoiceStrictness.fromName(prefs[OWNER_VOICE_STRICTNESS_KEY])
        )
    }

    suspend fun saveLiveVoiceSettings(s: LiveVoiceSettings) {
        context.dataStore.edit { prefs ->
            prefs[LIVE_MODE_KEY] = s.mode.name
            prefs[LIVE_MODEL_KEY] = s.model.trim().ifBlank { LiveVoiceSettings().model }
            prefs[LIVE_VOICE_KEY] = s.voiceName
            prefs[LIVE_HANDS_FREE_KEY] = s.handsFree
            prefs[OWNER_VOICE_ENABLED_KEY] = s.ownerVoiceEnabled
            prefs[OWNER_VOICE_STRICTNESS_KEY] = s.ownerStrictness.name
        }
    }

    // Gemini Live key: only ever stored through SecureKeyStore (never DataStore, never logged).
    suspend fun saveGeminiLiveKey(key: String): Boolean = withContext(Dispatchers.IO) {
        secureKeyStore.trySaveEncryptedKey(GEMINI_LIVE_KEY_ID, key.trim())
    }
    fun getGeminiLiveKey(): String = secureKeyStore.getDecryptedKey(GEMINI_LIVE_KEY_ID)
    suspend fun getMaskedGeminiLiveKey(): String = withContext(Dispatchers.IO) { SecureKeyStore.maskKey(getGeminiLiveKey()) }

    /** Adapter so the owner-voice profile is encrypted by the same SecureKeyStore instance. */
    val secretStore: SecretStore = object : SecretStore {
        override fun get(id: String) = secureKeyStore.getDecryptedKey(id)
        override fun save(id: String, value: String) = secureKeyStore.trySaveEncryptedKey(id, value)
        override fun delete(id: String) = secureKeyStore.deleteKey(id)
    }

    val automationSettingsFlow: Flow<AutomationSettings> = context.dataStore.data.map { prefs ->
        AutomationSettings(
            killSwitch = prefs[AUTOMATION_KILL_KEY] ?: false,
            enabledFeatures = AutomationFeature.entries.filter { prefs[automationFeatureKey(it)] ?: true }.toSet(),
            fastCommandRouting = prefs[AUTOMATION_FAST_ROUTING_KEY] ?: false
        )
    }

    suspend fun saveAutomationSettings(s: AutomationSettings) {
        context.dataStore.edit { prefs ->
            prefs[AUTOMATION_KILL_KEY] = s.killSwitch
            prefs[AUTOMATION_FAST_ROUTING_KEY] = s.fastCommandRouting
            AutomationFeature.entries.forEach { prefs[automationFeatureKey(it)] = it in s.enabledFeatures }
        }
    }

    // YouTube Data API key (optional): only ever stored through SecureKeyStore, never logged.
    suspend fun saveYoutubeApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        secureKeyStore.trySaveEncryptedKey(YOUTUBE_API_KEY_ID, key.trim())
    }
    fun getYoutubeApiKey(): String = secureKeyStore.getDecryptedKey(YOUTUBE_API_KEY_ID)
    suspend fun getMaskedYoutubeApiKey(): String = withContext(Dispatchers.IO) { SecureKeyStore.maskKey(getYoutubeApiKey()) }

    val chimeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CHIME_ENABLED_KEY] ?: true
    }

    val floatingBubbleEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[FLOATING_BUBBLE_ENABLED_KEY] ?: false
    }

    val cameraPillSettingsFlow: Flow<CameraPillSettings> = context.dataStore.data.map { prefs ->
        val d = CameraPillSettings()
        CameraPillLogic.clampSettings(
            CameraPillSettings(
                enabled = prefs[PILL_ENABLED_KEY] ?: d.enabled,
                mode = CameraPillMode.fromKey(prefs[PILL_MODE_KEY]),
                avatarAnimationEnabled = prefs[PILL_AVATAR_ANIM_KEY] ?: d.avatarAnimationEnabled,
                audioVisualizerEnabled = prefs[PILL_VISUALIZER_KEY] ?: d.audioVisualizerEnabled,
                showThinkingAnimation = prefs[PILL_THINKING_ANIM_KEY] ?: d.showThinkingAnimation,
                showSpeakingAnimation = prefs[PILL_SPEAKING_ANIM_KEY] ?: d.showSpeakingAnimation,
                avatarSize = prefs[PILL_SIZE_KEY] ?: d.avatarSize,
                animationIntensity = prefs[PILL_INTENSITY_KEY] ?: d.animationIntensity,
                idleHideDelayMs = (prefs[PILL_IDLE_DELAY_KEY] ?: d.idleHideDelayMs.toInt()).toLong()
            )
        )
    }

    val allowLowRiskLockedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ALLOW_LOW_RISK_LOCKED_KEY] ?: false
    }

    val chargingOnlyWakeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CHARGING_ONLY_WAKE_KEY] ?: false
    }

    val nightScheduleEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[NIGHT_SCHEDULE_ENABLED_KEY] ?: false
    }

    val nightStartHourFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[NIGHT_START_HOUR_KEY] ?: 23
    }

    val nightEndHourFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[NIGHT_END_HOUR_KEY] ?: 7
    }

    val bootNotificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BOOT_NOTIFICATION_ENABLED_KEY] ?: true
    }

    val accessibilityWasEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ACCESSIBILITY_WAS_ENABLED_KEY] ?: false
    }

    val uiAgentMaxStepsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[UI_AGENT_MAX_STEPS_KEY] ?: 15
    }

    val uiFloatingStopEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[UI_FLOATING_STOP_KEY] ?: true
    }

    val allowlistModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ALLOWLIST_MODE_KEY] ?: false
    }

    val customBlockedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_BLOCKED_PKGS_KEY] ?: emptySet()
    }

    val removedDefaultBlockedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[REMOVED_DEFAULT_BLOCK_PKGS_KEY] ?: emptySet()
    }

    val allowedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[ALLOWED_PKGS_KEY] ?: emptySet()
    }

    val notifAllowedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[NOTIF_ALLOWED_PKGS_KEY] ?: emptySet()
    }

    val autoReadNotificationsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_READ_NOTIF_KEY] ?: false
    }

    val screenshotVisionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SCREENSHOT_VISION_KEY] ?: false
    }

    val historyLimitFlow: Flow<HistoryLimitPreset> = context.dataStore.data.map { prefs ->
        val raw = prefs[HISTORY_LIMIT_KEY] ?: HistoryLimitPreset.MEDIUM.name
        runCatching { HistoryLimitPreset.valueOf(raw) }.getOrDefault(HistoryLimitPreset.MEDIUM)
    }

    val debugInfoEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DEBUG_INFO_ENABLED_KEY] ?: false
    }

    val autoFailoverEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_FAILOVER_ENABLED_KEY] ?: true
    }

    val failoverOrderIdsFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[FAILOVER_ORDER_IDS_KEY] ?: return@map emptyList()
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    val standardMaxStepsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[STANDARD_MAX_STEPS_KEY] ?: 8).coerceIn(3, 15)
    }

    val modelConfigsFlow: Flow<List<ModelConfig>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[MODELS_KEY] ?: return@map defaultConfigs()
        parseModelsJson(jsonStr, fallbackToDefault = false)
    }

    private fun defaultConfigs(): List<ModelConfig> {
        val defaultId = "default_gemini"
        val config = ModelConfig(
            id = defaultId,
            name = "Gemini Flash (Default)",
            providerType = ProviderType.GEMINI,
            baseUrl = ProviderType.GEMINI.defaultBaseUrl,
            modelName = "gemini-2.5-flash",
            temperature = 0.7f,
            isActive = true,
            supportsTools = true,
            supportsStreaming = true
        )
        return listOf(config)
    }

    private fun parseModelsJson(jsonStr: String, fallbackToDefault: Boolean = true): List<ModelConfig> {
        val list = mutableListOf<ModelConfig>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    ModelConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        providerType = ProviderType.valueOf(obj.getString("providerType")),
                        baseUrl = obj.getString("baseUrl"),
                        modelName = obj.getString("modelName"),
                        temperature = obj.optDouble("temperature", 0.7).toFloat(),
                        isActive = obj.optBoolean("isActive", false),
                        supportsTools = obj.optBoolean("supportsTools", true),
                        supportsVision = obj.optBoolean("supportsVision", false),
                        supportsStreaming = obj.optBoolean("supportsStreaming", true),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
            return if (fallbackToDefault) defaultConfigs() else emptyList()
        }
        return if (list.isEmpty() && fallbackToDefault) defaultConfigs() else list
    }

    private fun serializeModels(configs: List<ModelConfig>): String {
        val jsonArray = JSONArray()
        for (config in configs) {
            val obj = JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("providerType", config.providerType.name)
                put("baseUrl", config.baseUrl)
                put("modelName", config.modelName)
                put("temperature", config.temperature.toDouble())
                put("isActive", config.isActive)
                put("supportsTools", config.supportsTools)
                put("supportsVision", config.supportsVision)
                put("supportsStreaming", config.supportsStreaming)
                put("createdAt", config.createdAt)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    suspend fun saveModels(configs: List<ModelConfig>) {
        val json = serializeModels(configs)
        context.dataStore.edit { prefs ->
            prefs[MODELS_KEY] = json
        }
    }

    /**
     * অ্যাটমিকভাবে মডেল কনফিগ সেভ/আপডেট করে ও তার নিজস্ব config.id-তে API Key সংরক্ষণ করে।
     * - নতুন কনফিগে সবসময় ইউনিক UUID নিশ্চিত করে
     * - keyModified = false হলে আগের সংরক্ষিত Key অক্ষত রাখে
     */
    suspend fun upsertModelConfig(
        config: ModelConfig,
        apiKey: String,
        keyModified: Boolean = true
    ): ModelConfig = withContext(Dispatchers.IO) {
        val safeId = config.id.trim().ifEmpty { UUID.randomUUID().toString() }

        // আগে KeyStore-এ ওই নির্দিষ্ট safeId-র Key সেভ/মুছে নেওয়া, যাতে DataStore ইভেন্ট আসার সাথেই UI সঠিক Key পায়
        if (keyModified) {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isNotEmpty()) {
                secureKeyStore.saveEncryptedKey(safeId, trimmedKey)
            } else {
                secureKeyStore.deleteKey(safeId)
            }
        }

        var resolvedConfig = config.copy(id = safeId)
        context.dataStore.edit { prefs ->
            val rawJson = prefs[MODELS_KEY]
            val currentList = if (rawJson == null) {
                defaultConfigs().toMutableList()
            } else {
                parseModelsJson(rawJson, fallbackToDefault = false).toMutableList()
            }

            val existingIndex = currentList.indexOfFirst { it.id == safeId }
            val shouldBeActive = config.isActive || currentList.isEmpty()

            if (shouldBeActive) {
                for (i in currentList.indices) {
                    currentList[i] = currentList[i].copy(isActive = false)
                }
            }

            resolvedConfig = config.copy(id = safeId, isActive = shouldBeActive)
            if (existingIndex >= 0) {
                currentList[existingIndex] = resolvedConfig
            } else {
                currentList.add(resolvedConfig)
            }

            // কমপক্ষে একটি Active মডেল থাকলে তা নিশ্চিত করা
            if (currentList.isNotEmpty() && currentList.none { it.isActive }) {
                currentList[0] = currentList[0].copy(isActive = true)
            }

            prefs[MODELS_KEY] = serializeModels(currentList)
        }

        resolvedConfig
    }

    suspend fun setActiveModel(id: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            val rawJson = prefs[MODELS_KEY]
            val currentList = if (rawJson == null) {
                defaultConfigs()
            } else {
                parseModelsJson(rawJson, fallbackToDefault = false)
            }
            if (currentList.isEmpty()) return@edit
            val updated = currentList.map { it.copy(isActive = it.id == id) }.toMutableList()
            if (updated.none { it.isActive }) {
                updated[0] = updated[0].copy(isActive = true)
            }
            prefs[MODELS_KEY] = serializeModels(updated)
        }
    }

    suspend fun deleteModelConfig(id: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            val rawJson = prefs[MODELS_KEY]
            val currentList = if (rawJson == null) {
                defaultConfigs()
            } else {
                parseModelsJson(rawJson, fallbackToDefault = false)
            }
            val updated = currentList.filter { it.id != id }.toMutableList()
            if (updated.isNotEmpty() && updated.none { it.isActive }) {
                updated[0] = updated[0].copy(isActive = true)
            }
            prefs[MODELS_KEY] = serializeModels(updated)
        }
        // কেবল মুছে ফেলা configId-র নিজস্ব Key মুছে ফেলা হচ্ছে
        secureKeyStore.deleteKey(id)
    }

    // ---------------------------------------------------------------------------------------
    // Bulk JSON import (Models & API Keys)
    // ---------------------------------------------------------------------------------------

    private val modelImportMutex = Mutex()

    private class ModelImportAbort : Exception()

    /** ব্যর্থ ইমপোর্টে লেখা Key-গুলো আগের অবস্থায় ফেরানো (নতুনগুলো মুছে, বদলানোগুলো পুনরুদ্ধার)। */
    private fun rollbackImportedKeys(backups: Map<String, String>) {
        for ((id, previous) in backups) {
            if (previous.isEmpty()) secureKeyStore.deleteKey(id)
            else secureKeyStore.saveEncryptedKey(id, previous)
        }
    }

    private suspend fun currentModelsAndKeys(): Pair<List<ModelConfig>, Map<String, String>> {
        val raw = context.dataStore.data.first()[MODELS_KEY]
        val list = if (raw == null) defaultConfigs() else parseModelsJson(raw, fallbackToDefault = false)
        val keys = HashMap<String, String>()
        for (m in list) keys[m.id] = secureKeyStore.getDecryptedKey(m.id)
        return list to keys
    }

    /** ইমপোর্টের আগে কেবল গণনা (কিছু সেভ করে না) — নতুন/ডুপ্লিকেট কতগুলো তা দেখানোর জন্য। */
    suspend fun previewModelImport(parsed: ModelImportParseResult.Success): ModelImportPreview =
        withContext(Dispatchers.IO) {
            val (list, keys) = currentModelsAndKeys()
            val plan = ModelImportPlanner.plan(parsed.entries, list, keys, DuplicatePolicy.SKIP)
            ModelImportPreview(
                totalFound = parsed.totalFound,
                entries = parsed.entries,
                rejected = parsed.rejected,
                newCount = plan.count { it.action == PlannedAction.ADD },
                duplicateCount = plan.count { it.action == PlannedAction.SKIP_DUPLICATE },
                repeatInFileCount = plan.count { it.action == PlannedAction.SKIP_REPEAT_IN_FILE },
                withoutKeyCount = parsed.entries.count { !it.hasKey }
            )
        }

    /**
     * যাচাইকৃত এন্ট্রিগুলো অ্যাটমিকভাবে প্রয়োগ করে।
     * - মডেল তালিকা একটিমাত্র DataStore edit-এ লেখা হয়; মাঝপথে ব্যর্থ হলে কিছুই লেখা হয় না।
     * - ব্যর্থতায় এই ইমপোর্টে লেখা Key-গুলো আগের অবস্থায় ফিরিয়ে দেওয়া হয় (নতুন Key মুছে, পুরোনো Key পুনরুদ্ধার)।
     * - Active মডেল বদলায় না (শুধু কোনো Active না থাকলে প্রথমটি Active হয়)।
     * - API Key কোথাও লগ করা হয় না।
     */
    suspend fun applyModelImport(
        parsed: ModelImportParseResult.Success,
        policy: DuplicatePolicy,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ModelImportOutcome = withContext(Dispatchers.IO) {
        modelImportMutex.withLock {
            var added = 0
            var updated = 0
            var skipped = 0
            var addedWithoutKey = 0
            val keyBackups = LinkedHashMap<String, String>() // configId -> আগের Key ("" = আগে ছিল না)

            try {
                context.dataStore.edit { prefs ->
                    val raw = prefs[MODELS_KEY]
                    val list = (if (raw == null) defaultConfigs() else parseModelsJson(raw, fallbackToDefault = false))
                        .toMutableList()
                    val existingKeys = HashMap<String, String>()
                    for (m in list) existingKeys[m.id] = secureKeyStore.getDecryptedKey(m.id)

                    val plan = ModelImportPlanner.plan(parsed.entries, list, existingKeys, policy)
                    var done = 0
                    run {
                        for (item in plan) {
                            val entry = item.entry
                            when (item.action) {
                                PlannedAction.ADD -> {
                                    val newId = UUID.randomUUID().toString()
                                    if (entry.hasKey) {
                                        keyBackups[newId] = ""
                                        if (!secureKeyStore.trySaveEncryptedKey(newId, entry.apiKey)) throw ModelImportAbort()
                                    } else {
                                        addedWithoutKey++
                                    }
                                    list.add(
                                        ModelConfig(
                                            id = newId,
                                            name = entry.name,
                                            providerType = entry.providerType,
                                            baseUrl = entry.baseUrl,
                                            modelName = entry.modelName,
                                            temperature = entry.temperature ?: 0.7f,
                                            isActive = false,
                                            supportsTools = entry.supportsTools ?: true,
                                            supportsVision = entry.supportsVision ?: false,
                                            supportsStreaming = entry.supportsStreaming ?: true
                                        )
                                    )
                                    added++
                                }
                                PlannedAction.UPDATE -> {
                                    val id = item.existingId ?: throw ModelImportAbort()
                                    val idx = list.indexOfFirst { it.id == id }
                                    if (idx < 0) throw ModelImportAbort()
                                    val old = list[idx]
                                    // JSON-এ Key থাকলে ও আলাদা হলেই বদলাবে; না থাকলে বিদ্যমান Key অক্ষত
                                    if (entry.hasKey && existingKeys[id] != entry.apiKey) {
                                        keyBackups[id] = existingKeys[id] ?: ""
                                        if (!secureKeyStore.trySaveEncryptedKey(id, entry.apiKey)) throw ModelImportAbort()
                                    }
                                    list[idx] = old.copy(
                                        name = entry.name,
                                        providerType = entry.providerType,
                                        baseUrl = entry.baseUrl,
                                        modelName = entry.modelName,
                                        temperature = entry.temperature ?: old.temperature,
                                        supportsTools = entry.supportsTools ?: old.supportsTools,
                                        supportsVision = entry.supportsVision ?: old.supportsVision,
                                        supportsStreaming = entry.supportsStreaming ?: old.supportsStreaming
                                    )
                                    updated++
                                }
                                PlannedAction.SKIP_DUPLICATE,
                                PlannedAction.SKIP_REPEAT_IN_FILE -> skipped++
                            }
                            done++
                            onProgress(done, plan.size)
                        }

                        if (list.isNotEmpty() && list.none { it.isActive }) {
                            list[0] = list[0].copy(isActive = true)
                        }
                        prefs[MODELS_KEY] = serializeModels(list)
                    }
                }
            } catch (e: CancellationException) {
                rollbackImportedKeys(keyBackups)
                throw e
            } catch (_: Exception) {
                // transform বা ডিস্ক-রাইট যেখানেই ব্যর্থ হোক: লেখা Key ফিরিয়ে দিই; মডেল তালিকা DataStore-এ কমিটই হয়নি
                rollbackImportedKeys(keyBackups)
                return@withLock ModelImportOutcome.StorageFailure
            }

            ModelImportOutcome.Success(
                ModelImportSummary(
                    totalFound = parsed.totalFound,
                    imported = added,
                    updated = updated,
                    skipped = skipped,
                    failed = parsed.rejected.size,
                    rejected = parsed.rejected,
                    importedWithoutKey = addedWithoutKey
                )
            )
        }
    }

    suspend fun saveSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs -> prefs[SYSTEM_PROMPT_KEY] = prompt }
    }

    /** NovaThemeChoice.key: auto | dark | light | red | sunset. Unknown values read as auto. */
    val novaThemeFlow: Flow<String> = context.dataStore.data.map { prefs -> prefs[NOVA_THEME_KEY] ?: "auto" }

    suspend fun saveNovaTheme(key: String) {
        context.dataStore.edit { prefs -> prefs[NOVA_THEME_KEY] = key }
    }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode }
    }

    suspend fun saveSpeechLanguage(option: SpeechLanguageOption) {
        context.dataStore.edit { prefs -> prefs[SPEECH_LANGUAGE_KEY] = option.tag }
    }

    suspend fun saveTtsSpeechRate(rate: Float) {
        context.dataStore.edit { prefs -> prefs[TTS_SPEECH_RATE_KEY] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun saveTtsPitch(pitch: Float) {
        context.dataStore.edit { prefs -> prefs[TTS_PITCH_KEY] = pitch.coerceIn(0.5f, 2.0f) }
    }

    suspend fun saveVoicePersona(personaId: String, speechRate: Float, speechPitch: Float) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_PERSONA_ID_KEY] = personaId
            if (personaId != "custom") {
                prefs[TTS_SPEECH_RATE_KEY] = speechRate.coerceIn(0.5f, 2.0f)
                prefs[TTS_PITCH_KEY] = speechPitch.coerceIn(0.5f, 2.0f)
            }
        }
    }

    suspend fun saveAutoSpeakOnVoice(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_SPEAK_VOICE_KEY] = enabled }
    }

    suspend fun saveContinuousVoiceMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CONTINUOUS_VOICE_KEY] = enabled }
    }

    suspend fun saveAutoTitleWithAi(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_TITLE_AI_KEY] = enabled }
    }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[DISABLED_TOOLS_KEY]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.remove(toolName) else current.add(toolName)
            prefs[DISABLED_TOOLS_KEY] = current
        }
    }

    suspend fun saveConfirmMediumRisk(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CONFIRM_MEDIUM_RISK_KEY] = enabled }
    }

    suspend fun saveLogSensitiveData(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[LOG_SENSITIVE_DATA_KEY] = enabled }
    }

    suspend fun savePreferredSimSlot(slot: Int) {
        context.dataStore.edit { prefs -> prefs[PREFERRED_SIM_SLOT_KEY] = slot }
    }

    suspend fun saveBackgroundListeningEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[BG_LISTENING_ENABLED_KEY] = enabled }
    }

    suspend fun saveWakeWordPhrase(phrase: String) {
        context.dataStore.edit { prefs -> prefs[WAKE_WORD_PHRASE_KEY] = phrase.ifBlank { "Hey Jarvis" } }
    }

    suspend fun saveWakeWordThreshold(threshold: Float) {
        context.dataStore.edit { prefs -> prefs[WAKE_WORD_THRESHOLD_KEY] = threshold.coerceIn(0.1f, 0.95f) }
    }

    suspend fun saveChimeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CHIME_ENABLED_KEY] = enabled }
    }

    suspend fun saveCameraPillSettings(settings: CameraPillSettings) {
        val s = CameraPillLogic.clampSettings(settings)
        context.dataStore.edit { prefs ->
            prefs[PILL_ENABLED_KEY] = s.enabled
            prefs[PILL_MODE_KEY] = s.mode.key
            prefs[PILL_AVATAR_ANIM_KEY] = s.avatarAnimationEnabled
            prefs[PILL_VISUALIZER_KEY] = s.audioVisualizerEnabled
            prefs[PILL_THINKING_ANIM_KEY] = s.showThinkingAnimation
            prefs[PILL_SPEAKING_ANIM_KEY] = s.showSpeakingAnimation
            prefs[PILL_SIZE_KEY] = s.avatarSize
            prefs[PILL_INTENSITY_KEY] = s.animationIntensity
            prefs[PILL_IDLE_DELAY_KEY] = s.idleHideDelayMs.toInt()
        }
    }

    suspend fun saveFloatingBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[FLOATING_BUBBLE_ENABLED_KEY] = enabled }
    }

    suspend fun saveAllowLowRiskLocked(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[ALLOW_LOW_RISK_LOCKED_KEY] = enabled }
    }

    suspend fun saveChargingOnlyWake(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CHARGING_ONLY_WAKE_KEY] = enabled }
    }

    suspend fun saveNightSchedule(enabled: Boolean, startHour: Int, endHour: Int) {
        context.dataStore.edit { prefs ->
            prefs[NIGHT_SCHEDULE_ENABLED_KEY] = enabled
            prefs[NIGHT_START_HOUR_KEY] = startHour.coerceIn(0, 23)
            prefs[NIGHT_END_HOUR_KEY] = endHour.coerceIn(0, 23)
        }
    }

    suspend fun saveBootNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[BOOT_NOTIFICATION_ENABLED_KEY] = enabled }
    }

    suspend fun saveAccessibilityWasEnabled(wasEnabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[ACCESSIBILITY_WAS_ENABLED_KEY] = wasEnabled }
    }

    suspend fun saveUiAgentMaxSteps(steps: Int) {
        context.dataStore.edit { prefs -> prefs[UI_AGENT_MAX_STEPS_KEY] = steps.coerceIn(5, 25) }
    }

    suspend fun saveUiFloatingStopEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[UI_FLOATING_STOP_KEY] = enabled }
    }

    suspend fun saveAllowlistMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[ALLOWLIST_MODE_KEY] = enabled }
    }

    suspend fun addCustomBlockedPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val removedDefault = prefs[REMOVED_DEFAULT_BLOCK_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
            if (removedDefault.remove(clean)) {
                prefs[REMOVED_DEFAULT_BLOCK_PKGS_KEY] = removedDefault
            } else {
                val custom = prefs[CUSTOM_BLOCKED_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
                custom.add(clean)
                prefs[CUSTOM_BLOCKED_PKGS_KEY] = custom
            }
        }
    }

    suspend fun removeBlockedPackage(pkg: String, isDefaultProtected: Boolean) {
        context.dataStore.edit { prefs ->
            if (isDefaultProtected) {
                val removed = prefs[REMOVED_DEFAULT_BLOCK_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
                removed.add(pkg)
                prefs[REMOVED_DEFAULT_BLOCK_PKGS_KEY] = removed
            } else {
                val custom = prefs[CUSTOM_BLOCKED_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
                custom.remove(pkg)
                prefs[CUSTOM_BLOCKED_PKGS_KEY] = custom
            }
        }
    }

    suspend fun toggleAllowedPackage(pkg: String, allowed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[ALLOWED_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
            if (allowed) current.add(pkg) else current.remove(pkg)
            prefs[ALLOWED_PKGS_KEY] = current
        }
    }

    suspend fun toggleNotifAllowedPackage(pkg: String, allowed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[NOTIF_ALLOWED_PKGS_KEY]?.toMutableSet() ?: mutableSetOf()
            if (allowed) current.add(pkg) else current.remove(pkg)
            prefs[NOTIF_ALLOWED_PKGS_KEY] = current
        }
    }

    suspend fun clearNotifAllowedPackages() {
        context.dataStore.edit { prefs -> prefs[NOTIF_ALLOWED_PKGS_KEY] = emptySet() }
    }

    suspend fun saveAutoReadNotifications(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_READ_NOTIF_KEY] = enabled }
    }

    suspend fun saveScreenshotVisionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SCREENSHOT_VISION_KEY] = enabled }
    }

    suspend fun saveHistoryLimit(preset: HistoryLimitPreset) {
        context.dataStore.edit { prefs -> prefs[HISTORY_LIMIT_KEY] = preset.name }
    }

    suspend fun saveDebugInfoEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DEBUG_INFO_ENABLED_KEY] = enabled }
    }

    suspend fun saveAutoFailoverEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_FAILOVER_ENABLED_KEY] = enabled }
    }

    suspend fun saveFailoverOrderIds(orderIds: List<String>) {
        val arr = JSONArray()
        orderIds.distinct().forEach { arr.put(it) }
        context.dataStore.edit { prefs -> prefs[FAILOVER_ORDER_IDS_KEY] = arr.toString() }
    }

    suspend fun saveStandardMaxSteps(steps: Int) {
        context.dataStore.edit { prefs -> prefs[STANDARD_MAX_STEPS_KEY] = steps.coerceIn(3, 15) }
    }

    fun getApiKey(id: String): String = secureKeyStore.getDecryptedKey(id)

    suspend fun getApiKeyAsync(id: String): String = withContext(Dispatchers.IO) {
        secureKeyStore.getDecryptedKey(id)
    }

    fun getMaskedApiKey(id: String): String = SecureKeyStore.maskKey(getApiKey(id))

    suspend fun getMaskedApiKeyAsync(id: String): String = SecureKeyStore.maskKey(getApiKeyAsync(id))

    fun saveApiKey(id: String, key: String) = secureKeyStore.saveEncryptedKey(id, key)

    fun deleteApiKey(id: String) = secureKeyStore.deleteKey(id)

    fun getSearchApiKey(provider: SearchProviderType): String =
        secureKeyStore.getDecryptedKey(provider.keyStoreId)

    suspend fun getSearchApiKeyAsync(provider: SearchProviderType): String = withContext(Dispatchers.IO) {
        secureKeyStore.getDecryptedKey(provider.keyStoreId)
    }

    suspend fun getMaskedSearchApiKeyAsync(provider: SearchProviderType): String =
        SecureKeyStore.maskKey(getSearchApiKeyAsync(provider))

    suspend fun saveSearchApiKey(provider: SearchProviderType, key: String) = withContext(Dispatchers.IO) {
        val clean = key.trim()
        if (clean.isEmpty()) {
            secureKeyStore.deleteKey(provider.keyStoreId)
        } else {
            secureKeyStore.saveEncryptedKey(provider.keyStoreId, clean)
        }
    }

    // ==================== Room চ্যাট হিস্টোরি ও অ্যাকশন লগ ====================

    val conversationsFlow: Flow<List<Conversation>> = chatDao.observeAllConversations()
        .map { entities -> entities.map { it.toDomain() } }

    val actionLogsFlow: Flow<List<ActionLog>> = chatDao.observeActionLogs()
        .map { entities -> entities.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessagesForConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }

    suspend fun getLatestConversation(): Conversation? = withContext(Dispatchers.IO) {
        chatDao.getLatestConversation()?.toDomain()
    }

    /**
     * ধাপ ৪: ব্যাকগ্রাউন্ড ভয়েস সেশনের কথোপকথন Room-এ সেভ করার নিয়ম:
     * শেষ চ্যাট ১০ মিনিটের মধ্যে হলে ওই চ্যাটেই জুড়বে, নইলে নতুন চ্যাট শুরু হবে।
     */
    suspend fun getRecentConversationIdForBackground(
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = 10 * 60 * 1000L
    ): String? = withContext(Dispatchers.IO) {
        val latest = chatDao.getMostRecentlyUpdatedConversation() ?: return@withContext null
        if (nowMs - latest.updatedAt <= windowMs) latest.id else null
    }

    suspend fun getMessagesSnapshot(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        chatDao.getMessagesForConversation(conversationId).map { it.toDomain() }
    }

    suspend fun ensureConversationAndSaveUserMessage(
        existingConversationId: String?,
        userText: String,
        inputMode: InputMode
    ): Pair<String, ChatMessage> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val convId = existingConversationId ?: UUID.randomUUID().toString()

        if (existingConversationId == null) {
            val autoTitle = generateLocalTitle(userText)
            chatDao.insertConversation(
                ConversationEntity(
                    id = convId,
                    title = autoTitle,
                    createdAt = now,
                    updatedAt = now,
                    pinned = false
                )
            )
        } else {
            chatDao.updateConversationTimestamp(convId, now)
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = "user",
            content = userText,
            inputMode = inputMode,
            timestamp = now
        )
        chatDao.insertMessage(userMsg.toEntity())
        convId to userMsg
    }

    suspend fun saveAssistantMessage(
        conversationId: String,
        replyText: String,
        inputMode: InputMode
    ): ChatMessage = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "assistant",
            content = replyText,
            inputMode = inputMode,
            timestamp = now
        )
        chatDao.insertMessage(assistantMsg.toEntity())
        chatDao.updateConversationTimestamp(conversationId, now)
        assistantMsg
    }

    suspend fun renameConversation(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            chatDao.updateConversationTitle(id, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun setConversationPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        chatDao.updateConversationPinned(id, pinned)
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        chatDao.deleteConversationById(id)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        chatDao.deleteAllConversations()
    }

    fun searchMessages(query: String): Flow<List<MessageSearchRow>> =
        chatDao.searchMessages(query.trim())

    suspend fun recordActionLog(toolName: String, argsSummary: String, status: String) = withContext(Dispatchers.IO) {
        chatDao.insertActionLog(
            ActionLogEntity(
                id = UUID.randomUUID().toString(),
                toolName = toolName,
                argsSummary = argsSummary,
                status = status,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearActionLogs() = withContext(Dispatchers.IO) {
        chatDao.clearActionLogs()
    }

    fun generateLocalTitle(firstMessage: String): String {
        val cleaned = firstMessage.trim().replace(Regex("\\s+"), " ")
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        val snippet = words.take(6).joinToString(" ")
        return if (snippet.length > 42) snippet.take(40).trimEnd() + "…" else snippet.ifBlank { "নতুন চ্যাট" }
    }

    // ==================== দীর্ঘমেয়াদী মেমোরি (Memories) ====================

    val memoriesFlow: Flow<List<MemoryEntity>> = chatDao.observeAllMemories()

    suspend fun getAllMemoriesSnapshot(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        chatDao.getAllMemories()
    }

    suspend fun saveMemory(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        chatDao.insertMemory(memory)
    }

    suspend fun deleteMemory(id: String) = withContext(Dispatchers.IO) {
        chatDao.deleteMemoryById(id)
    }

    suspend fun clearAllMemories() = withContext(Dispatchers.IO) {
        chatDao.clearAllMemories()
    }

    // ==================== শিডিউলড রুটিন (Routines) ====================

    val routinesFlow: Flow<List<RoutineEntity>> = chatDao.observeAllRoutines()

    suspend fun saveRoutine(routine: RoutineEntity) = withContext(Dispatchers.IO) {
        chatDao.insertRoutine(routine)
    }

    suspend fun setRoutineEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        chatDao.setRoutineEnabled(id, enabled)
    }

    suspend fun deleteRoutine(id: String) = withContext(Dispatchers.IO) {
        chatDao.deleteRoutineById(id)
    }

    // ==================== ব্যবহার ও টোকেন পরিসংখ্যান (Usage Stats) ====================

    val allUsageStatsFlow: Flow<List<UsageStatEntity>> = chatDao.observeAllUsageStats()

    val modelUsageSummaryFlow: Flow<List<ModelUsageSummary>> = chatDao.observeModelUsageSummary()

    suspend fun recordUsageStat(
        modelId: String,
        modelName: String,
        providerType: ProviderType,
        inputTokens: Int,
        outputTokens: Int,
        success: Boolean,
        wasFailover: Boolean
    ) = withContext(Dispatchers.IO) {
        chatDao.insertUsageStat(
            UsageStatEntity(
                id = UUID.randomUUID().toString(),
                modelId = modelId,
                modelName = modelName,
                providerType = providerType.name,
                inputTokens = inputTokens.coerceAtLeast(0),
                outputTokens = outputTokens.coerceAtLeast(0),
                success = success,
                wasFailover = wasFailover,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearUsageStats() = withContext(Dispatchers.IO) {
        chatDao.clearUsageStats()
    }

    // ==================== চ্যাট ব্যাকআপ ও রিস্টোর (API Key ছাড়া) ====================

    suspend fun getAllConversationsWithMessages(): List<Pair<Conversation, List<ChatMessage>>> =
        withContext(Dispatchers.IO) {
            val convList = mutableListOf<Pair<Conversation, List<ChatMessage>>>()
            val allConvs = chatDao.observeAllConversations().first()
            for (entity in allConvs) {
                val msgs = chatDao.getMessagesForConversation(entity.id).map { it.toDomain() }
                convList.add(entity.toDomain() to msgs)
            }
            convList
        }

    suspend fun importConversations(items: List<Pair<Conversation, List<ChatMessage>>>): Int =
        withContext(Dispatchers.IO) {
            var importedCount = 0
            for ((conv, msgs) in items) {
                val existing = chatDao.getConversationById(conv.id)
                val targetConvId = if (existing == null) conv.id else UUID.randomUUID().toString()
                chatDao.insertConversation(
                    ConversationEntity(
                        id = targetConvId,
                        title = conv.title.ifBlank { "Imported Chat" },
                        createdAt = conv.createdAt,
                        updatedAt = conv.updatedAt,
                        pinned = conv.pinned
                    )
                )
                for (msg in msgs) {
                    chatDao.insertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = targetConvId,
                            role = msg.role,
                            content = msg.content,
                            inputMode = msg.inputMode.name,
                            timestamp = msg.timestamp
                        )
                    )
                }
                importedCount++
            }
            importedCount
        }
}
