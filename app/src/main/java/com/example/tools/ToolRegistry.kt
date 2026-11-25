package com.example.tools

import android.Manifest
import org.json.JSONObject

/**
 * সমস্ত ফোনের অ্যাকশন টুলের একক রেজিস্ট্রি।
 * ধাপ ৫-এ নতুন টুল যোগ করা হলে শুধু এই তালিকায় যুক্ত করলেই চলবে।
 */
object ToolRegistry {

    private val baseTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "make_call",
            bengaliTitle = "ফোন কল করা",
            description = "Place a phone call to a contact name or phone number.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "phone_number" to ParameterProperty(
                        SchemaType.STRING,
                        "Exact phone number to call (e.g. 017XXXXXXXX or +88017XXXXXXXX). Optional if contact_name is provided."
                    ),
                    "contact_name" to ParameterProperty(
                        SchemaType.STRING,
                        "Contact name to look up and call if phone_number is unknown."
                    )
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.HIGH,
            requiredPermissions = listOf(Manifest.permission.CALL_PHONE),
            executor = { ctx, args -> CommunicationTools.makeCall(ctx, args) }
        ),
        ToolDefinition(
            name = "send_sms",
            bengaliTitle = "SMS পাঠানো",
            description = "Send an SMS text message to a contact name or phone number.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "phone_number" to ParameterProperty(
                        SchemaType.STRING,
                        "Recipient phone number. Optional if contact_name is provided."
                    ),
                    "contact_name" to ParameterProperty(
                        SchemaType.STRING,
                        "Recipient contact name if phone_number is unknown."
                    ),
                    "message" to ParameterProperty(
                        SchemaType.STRING,
                        "Text message content to send."
                    )
                ),
                required = listOf("message")
            ),
            riskLevel = RiskLevel.HIGH,
            requiredPermissions = listOf(Manifest.permission.SEND_SMS),
            executor = { ctx, args -> CommunicationTools.sendSms(ctx, args) }
        ),
        ToolDefinition(
            name = "search_contacts",
            bengaliTitle = "কন্ট্যাক্ট খোঁজা",
            description = "Search the user's phone contacts by name (supports English and Bengali).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "query" to ParameterProperty(SchemaType.STRING, "Name or partial name to search for.")
                ),
                required = listOf("query")
            ),
            riskLevel = RiskLevel.LOW,
            requiredPermissions = listOf(Manifest.permission.READ_CONTACTS),
            executor = { ctx, args -> CommunicationTools.searchContacts(ctx, args) }
        ),
        ToolDefinition(
            name = "set_alarm",
            bengaliTitle = "অ্যালার্ম সেট করা",
            description = "Set an alarm for a specific hour and minute (24-hour format) with optional label and repeat days.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "hour" to ParameterProperty(SchemaType.INTEGER, "Hour in 24-hour format (0 to 23)."),
                    "minute" to ParameterProperty(SchemaType.INTEGER, "Minute (0 to 59)."),
                    "label" to ParameterProperty(SchemaType.STRING, "Optional alarm label."),
                    "days" to ParameterProperty(
                        SchemaType.ARRAY,
                        "Optional repeat days (e.g. ['mon', 'tue'] or [1..7]).",
                        itemType = SchemaType.STRING
                    )
                ),
                required = listOf("hour", "minute")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> SystemTools.setAlarm(ctx, args) }
        ),
        ToolDefinition(
            name = "set_timer",
            bengaliTitle = "টাইমার সেট করা",
            description = "Start a countdown timer for a specified number of seconds.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "seconds" to ParameterProperty(SchemaType.INTEGER, "Duration in seconds (must be > 0)."),
                    "label" to ParameterProperty(SchemaType.STRING, "Optional timer label.")
                ),
                required = listOf("seconds")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> SystemTools.setTimer(ctx, args) }
        ),
        ToolDefinition(
            name = "create_calendar_event",
            bengaliTitle = "ক্যালেন্ডার ইভেন্ট তৈরি",
            description = "Open calendar creation with event title, optional start time (ISO 8601), duration, location, and description.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "title" to ParameterProperty(SchemaType.STRING, "Title of the calendar event."),
                    "start_time" to ParameterProperty(SchemaType.STRING, "Start time in ISO format like '2026-11-20T15:00'."),
                    "duration_minutes" to ParameterProperty(SchemaType.INTEGER, "Event duration in minutes (default 60)."),
                    "location" to ParameterProperty(SchemaType.STRING, "Optional event location."),
                    "description" to ParameterProperty(SchemaType.STRING, "Optional event notes.")
                ),
                required = listOf("title")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> SystemTools.createCalendarEvent(ctx, args) }
        ),
        ToolDefinition(
            name = "open_app",
            bengaliTitle = "অ্যাপ খোলা",
            description = "Open an installed app by its name or common nickname (English or Bengali).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "app_name" to ParameterProperty(SchemaType.STRING, "App name or nickname such as 'WhatsApp', 'FB', 'ফেসবুক', 'YouTube'.")
                ),
                required = listOf("app_name")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> SystemTools.openApp(ctx, args) }
        ),
        ToolDefinition(
            name = "toggle_flashlight",
            bengaliTitle = "টর্চলাইট নিয়ন্ত্রণ",
            description = "Turn the device camera flashlight (torch) on or off.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "enabled" to ParameterProperty(SchemaType.BOOLEAN, "True to turn on, false to turn off. Omit to toggle.")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> SystemTools.toggleFlashlight(ctx, args) }
        ),
        ToolDefinition(
            name = "set_volume",
            bengaliTitle = "ভলিউম নিয়ন্ত্রণ",
            description = "Adjust or set device audio volume for media, ring, alarm, or notification.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "stream" to ParameterProperty(
                        SchemaType.STRING,
                        "Audio stream type.",
                        enumValues = listOf("media", "ring", "alarm", "notification")
                    ),
                    "action" to ParameterProperty(
                        SchemaType.STRING,
                        "Volume action.",
                        enumValues = listOf("set", "up", "down", "mute", "unmute")
                    ),
                    "level_percent" to ParameterProperty(
                        SchemaType.INTEGER,
                        "Target volume percentage 0 to 100 when action is 'set'."
                    )
                ),
                required = listOf("action")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> SystemTools.setVolume(ctx, args) }
        ),
        ToolDefinition(
            name = "set_brightness",
            bengaliTitle = "ব্রাইটনেস পরিবর্তন",
            description = "Set screen brightness percentage (0 to 100).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "level_percent" to ParameterProperty(SchemaType.INTEGER, "Brightness percentage from 0 to 100.")
                ),
                required = listOf("level_percent")
            ),
            riskLevel = RiskLevel.LOW,
            requiresWriteSettings = true,
            executor = { ctx, args -> SystemTools.setBrightness(ctx, args) }
        ),
        ToolDefinition(
            name = "wifi_bluetooth",
            bengaliTitle = "Wi-Fi / Bluetooth সেটিংস",
            description = "Open the system Wi-Fi, internet, or Bluetooth settings panel so the user can toggle it (Android restricts apps from toggling directly).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "target" to ParameterProperty(
                        SchemaType.STRING,
                        "Which connectivity panel to open.",
                        enumValues = listOf("wifi", "bluetooth", "internet")
                    )
                ),
                required = listOf("target")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> SystemTools.openWifiBluetoothSettings(ctx, args) }
        ),
        ToolDefinition(
            name = "get_device_status",
            bengaliTitle = "ডিভাইস স্ট্যাটাস দেখা",
            description = "Get current battery percentage, charging state, network connectivity type, and current date/time.",
            parametersSchema = ToolParametersSchema(
                properties = emptyMap(),
                required = emptyList()
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, _ -> SystemTools.getDeviceStatus(ctx) }
        ),
        ToolDefinition(
            name = "play_music",
            bengaliTitle = "মিউজিক চালানো",
            description = "Search and play a song or artist in the user's installed music app.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "query" to ParameterProperty(SchemaType.STRING, "Song title, artist, or playlist query.")
                ),
                required = listOf("query")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> SystemTools.playMusic(ctx, args) }
        ),
        ToolDefinition(
            name = "open_whatsapp_chat",
            bengaliTitle = "WhatsApp চ্যাট খোলা",
            description = "Open WhatsApp chat with a specific contact or phone number and pre-fill a message (user presses Send manually).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "phone_number" to ParameterProperty(SchemaType.STRING, "Recipient phone number. Optional if contact_name is provided."),
                    "contact_name" to ParameterProperty(SchemaType.STRING, "Recipient contact name if phone_number is unknown."),
                    "message" to ParameterProperty(SchemaType.STRING, "Optional pre-filled message text.")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> CommunicationTools.openWhatsAppChat(ctx, args) }
        ),
        // ==================== ধাপ ৫: Accessibility, Screen Understanding ও Notification টুল ====================
        ToolDefinition(
            name = "read_screen",
            bengaliTitle = "বর্তমান স্ক্রিন পড়া",
            description = "Read the currently visible app screen elements, text, and IDs. Returns compact snapshot with temporary element IDs.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "include_screenshot" to ParameterProperty(
                        SchemaType.BOOLEAN,
                        "Optional: set true only if text snapshot is insufficient and screenshot vision is needed."
                    )
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.readScreen(ctx, args) }
        ),
        ToolDefinition(
            name = "click_element",
            bengaliTitle = "স্ক্রিনের উপাদানে ক্লিক",
            description = "Click an interactive button or item on the current screen by its temporary element_id from read_screen or by visible text.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "element_id" to ParameterProperty(SchemaType.INTEGER, "Temporary ID from read_screen (e.g. 5)."),
                    "text" to ParameterProperty(SchemaType.STRING, "Visible text or description of the element if ID is not passed.")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.LOW, // Send/Pay/Delete/Confirm ইত্যাদি বাটনে AgentOrchestrator ডায়নামিকভাবে HIGH করে দেবে
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.clickElement(ctx, args) }
        ),
        ToolDefinition(
            name = "type_text",
            bengaliTitle = "স্ক্রিনে লেখা বসানো",
            description = "Type text into an editable input field on screen without automatically pressing Send.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "text" to ParameterProperty(SchemaType.STRING, "Text to insert into the input box."),
                    "element_id" to ParameterProperty(SchemaType.INTEGER, "Optional temporary ID of the input field from read_screen."),
                    "target_hint" to ParameterProperty(SchemaType.STRING, "Optional hint/label of the input field.")
                ),
                required = listOf("text")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.typeText(ctx, args) }
        ),
        ToolDefinition(
            name = "scroll",
            bengaliTitle = "স্ক্রিন স্ক্রল করা",
            description = "Scroll the current screen in a direction ('up', 'down', 'left', 'right').",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "direction" to ParameterProperty(
                        SchemaType.STRING,
                        "Scroll direction.",
                        enumValues = listOf("up", "down", "left", "right")
                    )
                ),
                required = listOf("direction")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.scroll(ctx, args) }
        ),
        ToolDefinition(
            name = "swipe_gesture",
            bengaliTitle = "সোয়াইপ জেসচার",
            description = "Perform a swipe gesture across the screen using screen percentage coordinates (0-100).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "start_x_pct" to ParameterProperty(SchemaType.NUMBER, "Start X percentage (0-100)."),
                    "start_y_pct" to ParameterProperty(SchemaType.NUMBER, "Start Y percentage (0-100)."),
                    "end_x_pct" to ParameterProperty(SchemaType.NUMBER, "End X percentage (0-100)."),
                    "end_y_pct" to ParameterProperty(SchemaType.NUMBER, "End Y percentage (0-100)."),
                    "duration_ms" to ParameterProperty(SchemaType.INTEGER, "Swipe duration in ms (default 320).")
                ),
                required = listOf("start_x_pct", "start_y_pct", "end_x_pct", "end_y_pct")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.swipeGesture(ctx, args) }
        ),
        ToolDefinition(
            name = "global_action",
            bengaliTitle = "সিস্টেম নেভিগেশন অ্যাকশন",
            description = "Trigger system global navigation actions like back, home, recents, notifications, or quick_settings.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "action" to ParameterProperty(
                        SchemaType.STRING,
                        "Global action name.",
                        enumValues = listOf("back", "home", "recents", "notifications", "quick_settings")
                    )
                ),
                required = listOf("action")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.globalAction(ctx, args) }
        ),
        ToolDefinition(
            name = "wait_for_screen_change",
            bengaliTitle = "স্ক্রিন পরিবর্তনের অপেক্ষা",
            description = "Wait event-based for the active window/screen to update after an action, then return the refreshed screen snapshot.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "timeout_ms" to ParameterProperty(SchemaType.INTEGER, "Maximum wait time in milliseconds (400-6000, default 2000).")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.waitForScreenChange(ctx, args) }
        ),
        ToolDefinition(
            name = "get_notifications",
            bengaliTitle = "সাম্প্রতিক নোটিফিকেশন পড়া",
            description = "Read recent non-sensitive notifications from temporary RAM memory (OTP and banking notifications are automatically excluded).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "limit" to ParameterProperty(SchemaType.INTEGER, "Maximum number of recent notifications to return (1-50, default 15).")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.getNotifications(ctx, args) }
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            bengaliTitle = "WhatsApp মেসেজ পাঠানো",
            description = "Send a WhatsApp message to a contact or phone number via Accessibility recipe (opens chat, confirms with user, taps Send).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "phone_number" to ParameterProperty(SchemaType.STRING, "Recipient phone number. Optional if contact_name is provided."),
                    "contact_name" to ParameterProperty(SchemaType.STRING, "Recipient contact name if phone_number is unknown."),
                    "message" to ParameterProperty(SchemaType.STRING, "Message text to send on WhatsApp.")
                ),
                required = listOf("message")
            ),
            riskLevel = RiskLevel.HIGH,
            executor = { ctx, args -> com.example.accessibility.AccessibilityTools.sendWhatsAppMessage(ctx, args) }
        ),
        // ==================== ধাপ ৬: ওয়েব সার্চ, দীর্ঘমেয়াদী মেমোরি ও রুটিন ====================
        ToolDefinition(
            name = "web_search",
            bengaliTitle = "লাইভ ওয়েব সার্চ",
            description = "Search the live web for recent news, weather, prices, facts, or current information using configured search providers (Tavily/Brave/SerpAPI).",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "query" to ParameterProperty(SchemaType.STRING, "Search query in Bengali or English."),
                    "max_results" to ParameterProperty(SchemaType.INTEGER, "Maximum results to fetch (1-8, default 5).")
                ),
                required = listOf("query")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> WebSearchTools.search(ctx, args) }
        ),
        ToolDefinition(
            name = "remember_fact",
            bengaliTitle = "মেমোরিতে সংরক্ষণ",
            description = "Save a durable, non-sensitive personal fact, user name, or preference to long-term memory. Never store passwords, OTPs, PINs, card numbers, or API keys.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "content" to ParameterProperty(SchemaType.STRING, "Clear concise fact to remember (e.g. 'User name is Rahim', 'Prefers dark roast coffee')."),
                    "category" to ParameterProperty(
                        SchemaType.STRING,
                        "Category of memory.",
                        enumValues = listOf("name", "preference", "fact")
                    ),
                    "keywords" to ParameterProperty(SchemaType.STRING, "Optional comma-separated keywords for quick lookup.")
                ),
                required = listOf("content")
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> MemoryTools.rememberFact(ctx, args) }
        ),
        ToolDefinition(
            name = "recall_facts",
            bengaliTitle = "মেমোরি থেকে খোঁজা",
            description = "Recall stored long-term facts, name, or preferences about the user.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "query" to ParameterProperty(SchemaType.STRING, "Optional search terms to filter memories. Leave empty for recent memories.")
                ),
                required = emptyList()
            ),
            riskLevel = RiskLevel.LOW,
            executor = { ctx, args -> MemoryTools.recallFacts(ctx, args) }
        ),
        ToolDefinition(
            name = "schedule_routine",
            bengaliTitle = "রুটিন / রিমাইন্ডার সেট",
            description = "Schedule an automated reminder or daily routine at a specific hour and minute. Only LOW-risk tools run during background routines.",
            parametersSchema = ToolParametersSchema(
                properties = mapOf(
                    "title" to ParameterProperty(SchemaType.STRING, "Short title for the routine or reminder."),
                    "prompt" to ParameterProperty(SchemaType.STRING, "Task or question to run at the scheduled time."),
                    "hour" to ParameterProperty(SchemaType.INTEGER, "Hour in 24-hour format (0-23)."),
                    "minute" to ParameterProperty(SchemaType.INTEGER, "Minute (0-59)."),
                    "one_shot" to ParameterProperty(SchemaType.BOOLEAN, "True to run only once, false to repeat."),
                    "days_mask" to ParameterProperty(SchemaType.STRING, "'DAILY' or comma-separated day numbers (1=Sun..7=Sat).")
                ),
                required = listOf("title", "prompt", "hour", "minute")
            ),
            riskLevel = RiskLevel.MEDIUM,
            executor = { ctx, args -> com.example.assistant.RoutineScheduler.scheduleRoutineFromTool(ctx, args) }
        )
    )

    /**
     * NEXA tools + ported automation tools (Step 2). Every automation tool is wrapped by the kill switch / per-feature
     * toggle guard here, so the check applies on every execution path (chat, voice, Gemini Live, command router).
     */
    val allTools: List<ToolDefinition> =
        (baseTools + AutomationTools.all).map { com.example.automation.core.AutomationSwitches.guard(it) }

    private val byName = allTools.associateBy { it.name }

    fun getTool(name: String): ToolDefinition? = byName[name]

    fun getEnabledTools(disabledToolNames: Set<String>): List<ToolDefinition> {
        return allTools.filter { it.name !in disabledToolNames }
    }

    /**
     * স্কিমা অনুযায়ী আর্গুমেন্ট ভ্যালিডেট করে। অনুপস্থিত বা ভুল প্যারামিটার থাকলে ত্রুটি ফিরিয়ে দেয়।
     */
    fun validateArguments(tool: ToolDefinition, args: JSONObject): String? {
        for (req in tool.parametersSchema.required) {
            if (!args.has(req) || args.isNull(req)) {
                return "অনুপস্থিত বাধ্যতামূলক প্যারামিটার: '$req'"
            }
            val prop = tool.parametersSchema.properties[req]
            if (prop?.type == SchemaType.STRING && args.optString(req, "").isBlank()) {
                return "প্যারামিটার '$req' ফাঁকা রাখা যাবে না"
            }
        }
        // কল, SMS ও WhatsApp-এর ক্ষেত্রে অন্তত phone_number অথবা contact_name থাকা বাধ্যতামূলক
        if (tool.name in CONTACT_RESOLVED_TOOLS) {
            val num = args.optString("phone_number", "").trim()
            val name = args.optString("contact_name", "").trim()
            if (num.isBlank() && name.isBlank()) {
                return "ফোন নম্বর অথবা কন্ট্যাক্টের নাম—যে কোনো একটি উল্লেখ করা আবশ্যক"
            }
        }
        if (tool.name == "open_url" &&
            args.optString("url", "").isBlank() && args.optString("query", "").isBlank()
        ) {
            return "ওয়েবসাইট (url) অথবা সার্চ কোয়েরি (query)—যে কোনো একটি আবশ্যক"
        }
        if (tool.name == "click_element") {
            val id = if (args.has("element_id") && !args.isNull("element_id")) args.optInt("element_id", -1) else -1
            val text = args.optString("text", "").trim()
            if (id <= 0 && text.isBlank()) {
                return "ক্লিক করার জন্য element_id অথবা text উল্লেখ করা আবশ্যক"
            }
        }
        return null
    }
}
