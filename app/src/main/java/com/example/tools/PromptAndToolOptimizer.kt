package com.example.tools

import com.example.model.ChatMessage
import com.example.model.HistoryLimitPreset
import com.example.network.AgentTurnMessage

/**
 * পাঠানো ডেটার আকার কমানোর ও গতি বাড়ানোর অপ্টিমাইজার:
 * ১) টোকেন হিসাব ও হিস্টোরি সীমাবদ্ধ করা
 * ২) ইউজারের অনুরোধ অনুযায়ী কেবল প্রাসঙ্গিক টুল গ্রুপ পাঠানো (অনিশ্চিত হলে নিরাপদভাবে টুলসহ পাঠানো)
 * ৩) আগের ধাপের পুরোনো স্ক্রিন স্ন্যাপশট ও ছবি হিস্টোরি থেকে ছাঁটা
 */
object PromptAndToolOptimizer {

    private val PHONE_TOOL_NAMES = setOf(
        "make_call",
        "send_sms",
        "search_contacts",
        "set_alarm",
        "set_timer",
        "create_calendar_event",
        "open_app",
        "toggle_flashlight",
        "set_volume",
        "set_brightness",
        "wifi_bluetooth",
        "get_device_status",
        "play_music",
        "open_whatsapp_chat"
    )

    private val UI_TOOL_NAMES = setOf(
        "read_screen",
        "click_element",
        "type_text",
        "scroll",
        "swipe_gesture",
        "global_action",
        "wait_for_screen_change",
        "send_whatsapp_message",
        "open_app",
        "search_contacts"
    )

    /** Step 2 automation tools: only offered to the model when the request mentions them (keeps prompts small). */
    private val AUTOMATION_TOOL_NAMES = setOf(
        "media_control", "open_url", "get_weather_location", "youtube_search", "youtube_play",
        "youtube_subscribe", "youtube_fullscreen", "whatsapp_call", "whatsapp_delete_message",
        "open_whatsapp_chat", "send_whatsapp_message", "search_contacts", "open_app"
    )

    private val AUTOMATION_KEYWORDS = listOf(
        "youtube", "subscribe", "fullscreen", "full screen", "pause", "resume", "next song", "next track", "next video",
        "skip", "previous song", "previous track", "website", ".com", ".org", ".net", "browser", "chrome", "weather",
        "delete message", "delete my message", "delete the message", "video call", "voice call", "whatsapp call",
        "ইউটিউব", "সাবস্ক্রাইব", "ফুলস্ক্রিন", "ফুল স্ক্রিন", "থামাও", "পজ", "ব্রাউজার", "ওয়েবসাইট", "আবহাওয়া",
        "মেসেজ মুছ", "মেসেজ ডিলিট", "ভিডিও কল", "ভয়েস কল"
    )

    private val NOTIFICATION_TOOL_NAMES = setOf(
        "get_notifications",
        "read_screen",
        "open_app"
    )

    private val SEARCH_MEMORY_ROUTINE_TOOLS = setOf(
        "web_search",
        "remember_fact",
        "recall_facts",
        "schedule_routine"
    )

    private val PHONE_KEYWORDS = listOf(
        "call", "phone", "dial", "ring", "sms", "text message", "contact", "number",
        "alarm", "wake me", "timer", "countdown", "calendar", "event", "meeting", "schedule",
        "open ", "launch", "flashlight", "torch", "volume", "sound", "mute", "unmute",
        "brightness", "wifi", "wi-fi", "bluetooth", "battery", "charging", "device status",
        "music", "song", "play ", "whatsapp",
        "কল", "ফোন", "এসএমএস", "মেসেজ", "কন্ট্যাক্ট", "নম্বর",
        "অ্যালার্ম", "এলার্ম", "টাইমার", "ক্যালেন্ডার", "ইভেন্ট", "রিমাইন্ডার",
        "অ্যাপ", "টর্চ", "ফ্ল্যাশ", "ভলিউম", "সাউন্ড", "মিউট", "আওয়াজ",
        "ব্রাইটনেস", "আলো", "ওয়াইফাই", "ওয়াইফাই", "ব্লুটুথ", "ব্যাটারি", "চার্জ",
        "গান", "মিউজিক", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "ওয়াটসঅ্যাপ"
    )

    private val UI_KEYWORDS = listOf(
        "screen", "ui", "click", "tap", "press", "button", "scroll", "swipe",
        "go back", "home screen", "recents", "type ", "write on", "draft", "reply here",
        "help with this", "what's on", "whats on", "on my screen",
        "স্ক্রিন", "পর্দা", "পর্দায়", "ক্লিক", "ট্যাপ", "চাপো", "বাটন", "স্ক্রল", "সোয়াইপ", "সোয়াপ",
        "ব্যাক", "হোম", "টাইপ", "বসাও", "রিপ্লাই", "উত্তর লিখে", "স্ক্রিন দেখে", "সাহায্য", "এখানে"
    )

    private val NOTIFICATION_KEYWORDS = listOf(
        "notification", "notifications", "notif", "unread", "missed", "alert",
        "নোটিফিকেশন", "বিজ্ঞপ্তি", "অ্যালার্ট", "কি মেসেজ এসেছে", "কী মেসেজ এসেছে"
    )

    private val SEARCH_MEMORY_ROUTINE_KEYWORDS = listOf(
        "search", "google", "web", "news", "latest", "today", "weather", "temperature", "price", "score", "match", "live",
        "remember", "memory", "recall", "forget", "my name is", "i like", "i prefer", "routine", "remind me", "every day", "daily",
        "সার্চ", "খুঁজ", "খোঁজ", "খবর", "তাজা", "আজকের", "আবহাওয়া", "আবহাওয়া", "তাপমাত্রা", "দাম", "স্কোর", "ম্যাচ", "লাইভ",
        "মনে রাখ", "মনে রেখ", "মেমোরি", "আমার নাম", "আমার পছন্দ", "রুটিন", "রিমাইন্ড", "মনে করিয়ে", "প্রতিদিন"
    )

    private val GENERIC_ACTION_HINTS = listOf(
        "করো", "করে দাও", "দাও", "খোলো", "চালু", "বন্ধ", "পাঠাও", "সেট", "বদলাও", "বাড়াও", "কমাও",
        "চেক", "দেখো", "পড়ো", "পড়ো", "খোঁজো", "ডিলিট", "মুছে",
        "do ", "open ", "turn ", "enable", "disable", "check ", "read ", "send ", "set ", "find ", "change ", "adjust "
    )

    /**
     * বাংলা ও ইংরেজি মিশ্রিত টেক্সটের আনুমানিক টোকেন গণনা।
     * বাংলা/নন-অ্যাস্কি ইউনিকোড অক্ষরে তুলনামূলক বেশি টোকেন লাগে।
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var asciiChars = 0
        var nonAsciiChars = 0
        for (ch in text) {
            if (ch.code < 128) asciiChars++ else nonAsciiChars++
        }
        val est = (asciiChars / 4.0) + (nonAsciiChars / 1.8)
        return maxOf(1, est.toInt())
    }

    fun estimatePayloadTokens(
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>
    ): Int {
        var tokens = estimateTokens(systemPrompt) + 8
        for (m in messages) {
            tokens += 4 + estimateTokens(m.textContent.orEmpty())
            if (!m.imageBase64.isNullOrBlank()) tokens += 260
            m.toolCalls.forEach { tc ->
                tokens += estimateTokens(tc.name) + estimateTokens(tc.argumentsJson) + 8
            }
        }
        // প্রতিটি টুল স্কিমা আনুমানিক ~৬৫-৯০ টোকেন নেয়
        tokens += tools.size * 75
        return tokens
    }

    /**
     * সেটিংস অনুযায়ী শেষ N মেসেজ এবং আনুমানিক টোকেন সীমার ভেতরে হিস্টোরি সীমিত রাখে।
     */
    fun buildBoundedHistory(
        messages: List<ChatMessage>,
        preset: HistoryLimitPreset
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val selected = mutableListOf<ChatMessage>()
        var totalTokens = 0

        for (msg in messages.asReversed()) {
            if (selected.size >= preset.maxMessages) break
            val msgTokens = estimateTokens(msg.content) + 4
            if (selected.isNotEmpty() && totalTokens + msgTokens > preset.maxTokens) break
            selected.add(msg)
            totalTokens += msgTokens
        }
        return selected.asReversed()
    }

    /**
     * হালকা কীওয়ার্ড ও ক্যাটাগরি ম্যাচিং দিয়ে নির্ধারণ করে এই রিকোয়েস্টে কোন টুলগুলো পাঠানো উচিত।
     * - সাধারণ কথোপকথনে টুল তালিকা খালি রাখে (হাজার হাজার ইনপুট টোকেন বাঁচে)
     * - অনিশ্চিত হলে নিরাপদভাবে টুলসহ পাঠায়
     */
    fun selectRelevantTools(
        userText: String,
        enabledTools: List<ToolDefinition>,
        recentMessages: List<ChatMessage> = emptyList()
    ): List<ToolDefinition> {
        if (enabledTools.isEmpty()) return emptyList()

        val lower = userText.trim().lowercase()
        if (lower.isBlank()) return enabledTools

        val wantsPhone = PHONE_KEYWORDS.any { lower.contains(it) }
        val wantsUi = UI_KEYWORDS.any { lower.contains(it) }
        val wantsNotif = NOTIFICATION_KEYWORDS.any { lower.contains(it) }
        val wantsSearchMemoryRoutine = SEARCH_MEMORY_ROUTINE_KEYWORDS.any { lower.contains(it) }
        val wantsAutomation = AUTOMATION_KEYWORDS.any { lower.contains(it) }

        if (wantsPhone || wantsUi || wantsNotif || wantsSearchMemoryRoutine || wantsAutomation) {
            val allowedNames = mutableSetOf<String>()
            if (wantsPhone) {
                allowedNames.addAll(PHONE_TOOL_NAMES)
                allowedNames.add("schedule_routine")
            }
            if (wantsUi) allowedNames.addAll(UI_TOOL_NAMES)
            if (wantsNotif) allowedNames.addAll(NOTIFICATION_TOOL_NAMES)
            if (wantsAutomation) allowedNames.addAll(AUTOMATION_TOOL_NAMES)
            if (wantsSearchMemoryRoutine) allowedNames.addAll(SEARCH_MEMORY_ROUTINE_TOOLS)
            // WhatsApp উল্লেখ করলে চ্যাট ও UI উভয় পথ খোলা রাখা
            if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("ওয়াটসঅ্যাপ")) {
                allowedNames.addAll(UI_TOOL_NAMES)
                allowedNames.add("open_whatsapp_chat")
                allowedNames.add("whatsapp_call")
                allowedNames.add("whatsapp_delete_message")
            }
            return enabledTools.filter { it.name in allowedNames }
        }

        // ফলো-আপ ছোট উত্তর (যেমন: "হ্যাঁ ওটা করো", "ওটাই", "Send to Rahim") পূর্বের টার্নে অ্যাকশন থাকলে টুল রাখবে
        val recentHasAction = recentMessages.takeLast(3).any { msg ->
            val m = msg.content.lowercase()
            PHONE_KEYWORDS.any { m.contains(it) } ||
                UI_KEYWORDS.any { m.contains(it) } ||
                NOTIFICATION_KEYWORDS.any { m.contains(it) } ||
                SEARCH_MEMORY_ROUTINE_KEYWORDS.any { m.contains(it) }
        }
        val hasGenericActionVerb = GENERIC_ACTION_HINTS.any { lower.contains(it) }

        // "কেমন আছো", "গল্প বলো", "Kotlin কী", "ব্যাখ্যা করো" — স্পষ্ট প্রশ্ন/ব্যাখ্যায় অ্যাকশন না থাকলে টুল বাদ
        val isPureExplainOrChat = (
            lower.startsWith("what is ") || lower.startsWith("who is ") ||
                lower.startsWith("why ") || lower.startsWith("how to ") ||
                lower.startsWith("explain ") || lower.startsWith("write a ") ||
                lower.startsWith("translate ") || lower.contains("কেমন আছ") ||
                lower.contains("গল্প") || lower.contains("কবিতা") ||
                lower.contains("ব্যাখ্যা") || lower.contains("অনুবাদ") ||
                lower.contains("মানে কী") || lower.contains("কোড লিখে")
            ) && !hasGenericActionVerb

        return when {
            isPureExplainOrChat -> emptyList()
            hasGenericActionVerb || recentHasAction -> enabledTools // অনিশ্চিত হলে নিরাপদভাবে টুলসহ পাঠানো
            else -> emptyList()
        }
    }

    /**
     * ধাপ ৫-এর একাধিক টুল স্টেপে আগের ধাপের পুরোনো স্ক্রিন স্ন্যাপশট ও ছবি হিস্টোরি থেকে ছাঁটে,
     * কেবল সর্বশেষ স্ন্যাপশট ও সর্বশেষ ছবি রাখে।
     */
    fun pruneOldScreenSnapshots(messages: List<AgentTurnMessage>): List<AgentTurnMessage> {
        val snapshotIndices = mutableListOf<Int>()
        val imageIndices = mutableListOf<Int>()

        messages.forEachIndexed { idx, msg ->
            if (msg.role == "tool" && (msg.toolName == "read_screen" || msg.toolName == "wait_for_screen_change")) {
                if ((msg.textContent?.length ?: 0) > 220) {
                    snapshotIndices.add(idx)
                }
            }
            if (!msg.imageBase64.isNullOrBlank()) {
                imageIndices.add(idx)
            }
        }

        if (snapshotIndices.size <= 1 && imageIndices.size <= 1) {
            return messages
        }

        val lastSnapshotIdx = snapshotIndices.lastOrNull() ?: -1
        val lastImageIdx = imageIndices.lastOrNull() ?: -1

        return messages.mapIndexed { idx, msg ->
            var updated = msg
            if (idx in snapshotIndices && idx != lastSnapshotIdx) {
                updated = updated.copy(
                    textContent = """{"success":true,"note":"আগের স্ক্রিন স্ন্যাপশট টোকেন বাঁচাতে সংক্ষিপ্ত করা হয়েছে।"}"""
                )
            }
            if (idx in imageIndices && idx != lastImageIdx) {
                updated = updated.copy(imageBase64 = null)
            }
            updated
        }
    }
}
