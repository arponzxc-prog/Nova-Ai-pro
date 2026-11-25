/* © ARPON — original NEXA AI code (Step 2: automation kill switch and per-feature toggles). */
package com.example.automation.core

import com.example.tools.ToolDefinition
import com.example.tools.ToolResult

enum class AutomationFeature { PHONE_CONTROL, MEDIA, WEB, YOUTUBE, WHATSAPP, SCREEN_AGENT }

/** Persisted by AiRepository (DataStore); mirrored into [AutomationSwitches] for the synchronous guard. */
data class AutomationSettings(
    /** Global kill switch: while true NO automation tool executes. */
    val killSwitch: Boolean = false,
    val enabledFeatures: Set<AutomationFeature> = AutomationFeature.entries.toSet(),
    /** Opt-in: let the offline CommandParser pre-empt the model for clear voice commands. */
    val fastCommandRouting: Boolean = false
) {
    fun isEnabled(f: AutomationFeature) = f in enabledFeatures
}

/**
 * Single choke point for "is this automation tool allowed to run right now?".
 * Every automation [ToolDefinition] is wrapped by [guard] when the registry is built, so the check runs for
 * model tool calls (chat, background voice, Gemini Live), rule-based command routing and routines alike.
 */
object AutomationSwitches {

    /** Tool -> feature. Tools not listed here (memory, alarms, calendar, web_search...) are not "automation". */
    val TOOL_FEATURE: Map<String, AutomationFeature> = mapOf(
        "make_call" to AutomationFeature.PHONE_CONTROL,
        "send_sms" to AutomationFeature.PHONE_CONTROL,
        "open_app" to AutomationFeature.PHONE_CONTROL,
        "toggle_flashlight" to AutomationFeature.PHONE_CONTROL,
        "set_volume" to AutomationFeature.PHONE_CONTROL,
        "set_brightness" to AutomationFeature.PHONE_CONTROL,
        "wifi_bluetooth" to AutomationFeature.PHONE_CONTROL,
        "play_music" to AutomationFeature.PHONE_CONTROL,
        "get_weather_location" to AutomationFeature.PHONE_CONTROL,
        "media_control" to AutomationFeature.MEDIA,
        "open_url" to AutomationFeature.WEB,
        "youtube_play" to AutomationFeature.YOUTUBE,
        "youtube_search" to AutomationFeature.YOUTUBE,
        "youtube_subscribe" to AutomationFeature.YOUTUBE,
        "youtube_fullscreen" to AutomationFeature.YOUTUBE,
        "open_whatsapp_chat" to AutomationFeature.WHATSAPP,
        "send_whatsapp_message" to AutomationFeature.WHATSAPP,
        "whatsapp_call" to AutomationFeature.WHATSAPP,
        "whatsapp_delete_message" to AutomationFeature.WHATSAPP,
        "read_screen" to AutomationFeature.SCREEN_AGENT,
        "click_element" to AutomationFeature.SCREEN_AGENT,
        "type_text" to AutomationFeature.SCREEN_AGENT,
        "scroll" to AutomationFeature.SCREEN_AGENT,
        "swipe_gesture" to AutomationFeature.SCREEN_AGENT,
        "global_action" to AutomationFeature.SCREEN_AGENT,
        "wait_for_screen_change" to AutomationFeature.SCREEN_AGENT
    )

    @Volatile var settings: AutomationSettings = AutomationSettings()
        private set

    /** False until the persisted settings were read once; automation fails CLOSED until then (never runs on defaults). */
    @Volatile var loaded: Boolean = false
        private set

    private val killListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Called once on the off->on transition so running loops/sessions can stop immediately. */
    fun addKillListener(l: () -> Unit) { killListeners.addIfAbsent(l) }

    fun update(new: AutomationSettings) {
        val wasKilled = settings.killSwitch
        settings = new
        loaded = true
        if (new.killSwitch && !wasKilled) killListeners.forEach { runCatching(it) }
    }

    fun isAutomationTool(name: String) = name in TOOL_FEATURE

    /** Pure decision (testable): null = allowed, otherwise the refusal message (Bengali, like other tool messages). */
    fun blockReason(toolName: String, s: AutomationSettings): String? {
        val feature = TOOL_FEATURE[toolName] ?: return null
        if (s.killSwitch) return "অটোমেশন কিল সুইচ চালু আছে, তাই কোনো ফোন অটোমেশন চালানো হবে না। সেটিংস → অটোমেশন থেকে বন্ধ করুন।"
        if (!s.isEnabled(feature)) return "এই অটোমেশন ফিচারটি সেটিংসে বন্ধ করা আছে ($toolName)।"
        return null
    }

    fun guard(tool: ToolDefinition): ToolDefinition {
        if (!isAutomationTool(tool.name)) return tool
        val inner = tool.executor
        return tool.copy(executor = { ctx, args ->
            val ready = loaded || kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                while (!loaded) kotlinx.coroutines.delay(20L)
                true
            } == true
            if (!ready) {
                ToolResult(false, "অটোমেশন সেটিংস লোড হয়নি; নিরাপত্তার জন্য কাজটি চালানো হয়নি। আবার চেষ্টা করুন।")
            } else {
                val reason = blockReason(tool.name, settings)
                if (reason != null) ToolResult(false, reason) else inner(ctx, args)
            }
        })
    }
}
