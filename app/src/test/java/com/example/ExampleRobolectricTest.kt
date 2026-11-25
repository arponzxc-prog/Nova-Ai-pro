package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ActionLogEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.tools.ContactAndPhoneHelper
import com.example.tools.ToolRegistry
import com.example.tools.VoiceConfirmationParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NOVA AI", appName)
    }

    @Test
    fun `room cascade delete and bengali english search work`() = runBlocking {
        val dao = db.chatDao()
        val now = System.currentTimeMillis()
        dao.insertConversation(
            ConversationEntity(
                id = "c1",
                title = "বাংলা কথোপকথন",
                createdAt = now,
                updatedAt = now,
                pinned = true
            )
        )
        dao.insertMessage(
            MessageEntity(
                id = "m1",
                conversationId = "c1",
                role = "user",
                content = "Kotlin কোড নিয়ে একটা প্রশ্ন আছে",
                inputMode = "VOICE",
                timestamp = now
            )
        )

        val bnResults = dao.searchMessages("প্রশ্ন").first()
        assertEquals(1, bnResults.size)
        assertEquals("c1", bnResults.first().conversationId)

        val enResults = dao.searchMessages("kotlin").first()
        assertEquals(1, enResults.size)

        // Cascade delete যাচাই
        dao.deleteConversationById("c1")
        val remainingMsgs = dao.getMessagesForConversation("c1")
        assertTrue(remainingMsgs.isEmpty())
    }

    @Test
    fun `action log persistence and clear work`() = runBlocking {
        val dao = db.chatDao()
        val now = System.currentTimeMillis()
        dao.insertActionLog(
            ActionLogEntity(
                id = "log1",
                toolName = "set_alarm",
                argsSummary = "hour=7, minute=0",
                status = "SUCCESS",
                timestamp = now
            )
        )
        dao.insertActionLog(
            ActionLogEntity(
                id = "log2",
                toolName = "make_call",
                argsSummary = "contact_name=আম্মু",
                status = "CANCELLED",
                timestamp = now + 100
            )
        )

        val logs = dao.observeActionLogs().first()
        assertEquals(2, logs.size)
        assertEquals("log2", logs.first().id)

        dao.clearActionLogs()
        assertTrue(dao.observeActionLogs().first().isEmpty())
    }

    @Test
    fun `tool registry validates required arguments and targets`() {
        val makeCall = ToolRegistry.getTool("make_call")!!
        assertNotNull(ToolRegistry.validateArguments(makeCall, JSONObject()))
        assertNull(ToolRegistry.validateArguments(makeCall, JSONObject().put("contact_name", "আম্মু")))

        val sendSms = ToolRegistry.getTool("send_sms")!!
        assertNotNull(ToolRegistry.validateArguments(sendSms, JSONObject().put("phone_number", "01711000000")))
        assertNull(
            ToolRegistry.validateArguments(
                sendSms,
                JSONObject().put("phone_number", "01711000000").put("message", "হ্যালো")
            )
        )

        val setAlarm = ToolRegistry.getTool("set_alarm")!!
        assertNotNull(ToolRegistry.validateArguments(setAlarm, JSONObject().put("hour", 7)))
        assertNull(ToolRegistry.validateArguments(setAlarm, JSONObject().put("hour", 7).put("minute", 30)))
    }

    @Test
    fun `voice confirmation parser rejects negative and ambiguous answers`() {
        assertTrue(VoiceConfirmationParser.parseYesNo("হ্যাঁ"))
        assertTrue(VoiceConfirmationParser.parseYesNo("yes confirm"))
        assertTrue(VoiceConfirmationParser.parseYesNo("পাঠাও"))

        assertFalse(VoiceConfirmationParser.parseYesNo("না"))
        assertFalse(VoiceConfirmationParser.parseYesNo("বাতিল"))
        assertFalse(VoiceConfirmationParser.parseYesNo("হ্যাঁ না থাক"))
        assertFalse(VoiceConfirmationParser.parseYesNo("maybe later"))
        assertFalse(VoiceConfirmationParser.parseYesNo(""))
    }

    @Test
    fun `phone number normalization converts bengali digits and formats properly`() {
        assertEquals("01711223344", ContactAndPhoneHelper.normalizePhoneNumber("০১৭১১-২২৩৩৪৪"))
        assertEquals("+8801711223344", ContactAndPhoneHelper.normalizePhoneNumber("+৮৮০১৭১১ ২২৩৩৪৪"))
        assertEquals("8801711223344", ContactAndPhoneHelper.toWhatsAppNumber("01711223344"))
    }

    @Test
    fun `quiet hours schedule handles midnight wrap and daytime ranges`() {
        assertTrue(com.example.assistant.BatteryOemHelper.isWithinQuietHours(23, 23, 7))
        assertTrue(com.example.assistant.BatteryOemHelper.isWithinQuietHours(3, 23, 7))
        assertFalse(com.example.assistant.BatteryOemHelper.isWithinQuietHours(7, 23, 7))
        assertFalse(com.example.assistant.BatteryOemHelper.isWithinQuietHours(14, 23, 7))

        assertTrue(com.example.assistant.BatteryOemHelper.isWithinQuietHours(14, 13, 16))
        assertFalse(com.example.assistant.BatteryOemHelper.isWithinQuietHours(17, 13, 16))
    }

    @Test
    fun `oem brand detector identifies major manufacturers`() {
        assertEquals(com.example.assistant.OemBrand.XIAOMI, com.example.assistant.BatteryOemHelper.detectOemBrand("Xiaomi"))
        assertEquals(com.example.assistant.OemBrand.XIAOMI, com.example.assistant.BatteryOemHelper.detectOemBrand("Redmi"))
        assertEquals(com.example.assistant.OemBrand.SAMSUNG, com.example.assistant.BatteryOemHelper.detectOemBrand("samsung"))
        assertEquals(com.example.assistant.OemBrand.OPPO_REALME_ONEPLUS, com.example.assistant.BatteryOemHelper.detectOemBrand("realme"))
        assertEquals(com.example.assistant.OemBrand.VIVO, com.example.assistant.BatteryOemHelper.detectOemBrand("vivo"))
    }

    @Test
    fun `wake word engine reports missing library safely without crashing and enforces cooldown`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.assistant.SherpaOnnxWakeWordEngine(context)
        engine.updateConfig("Hey Jarvis", 0.6f)
        assertEquals("Hey Jarvis", engine.state.value.activeKeyword)
        assertFalse(engine.state.value.isLibraryPresent)

        // সিমুলেট ট্রিগার পরপর দুবার কল করলে ২ সেকেন্ড cooldown-এ দ্বিতীয়টি আটকে যাবে
        engine.simulateTrigger()
        engine.simulateTrigger()
        assertEquals(1, engine.recentDetections.value.size)
        engine.release()
    }

    @Test
    fun `most recent conversation query ignores older pinned chats for background continuity`() = runBlocking {
        val dao = db.chatDao()
        val now = System.currentTimeMillis()
        // ৩ দিন আগের একটি পিন করা চ্যাট
        dao.insertConversation(
            ConversationEntity(
                id = "old_pinned",
                title = "পুরনো পিন করা",
                createdAt = now - 3 * 24 * 3600_000L,
                updatedAt = now - 3 * 24 * 3600_000L,
                pinned = true
            )
        )
        // ৩ মিনিট আগের একটি সাধারণ চ্যাট
        dao.insertConversation(
            ConversationEntity(
                id = "recent_chat",
                title = "সাম্প্রতিক চ্যাট",
                createdAt = now - 5 * 60_000L,
                updatedAt = now - 3 * 60_000L,
                pinned = false
            )
        )

        val recent = dao.getMostRecentlyUpdatedConversation()
        assertNotNull(recent)
        assertEquals("recent_chat", recent?.id)
        assertTrue(now - recent!!.updatedAt <= 10 * 60_000L)
    }

    @Test
    fun `step 4 service tile receiver and assist activity are declared in manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val serviceInfo = pm.getServiceInfo(
            android.content.ComponentName(context, com.example.assistant.AssistantService::class.java),
            0
        )
        assertNotNull(serviceInfo)

        val tileInfo = pm.getServiceInfo(
            android.content.ComponentName(context, com.example.assistant.AssistantTileService::class.java),
            0
        )
        assertNotNull(tileInfo)

        val receiverInfo = pm.getReceiverInfo(
            android.content.ComponentName(context, com.example.assistant.BootReceiver::class.java),
            0
        )
        assertNotNull(receiverInfo)

        val assistInfo = pm.getActivityInfo(
            android.content.ComponentName(context, com.example.assistant.AssistTriggerActivity::class.java),
            0
        )
        assertNotNull(assistInfo)
    }

    @Test
    fun `screen security guard blocks banking wallet apps and supports allowlist mode`() {
        val defaultPolicy = com.example.accessibility.AppAccessPolicy()
        assertFalse(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.bKash.customerapp", defaultPolicy).allowed)
        assertFalse(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.konasl.nagad", defaultPolicy).allowed)
        assertFalse(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.x8bit.bitwarden", defaultPolicy).allowed)
        assertTrue(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.whatsapp", defaultPolicy).allowed)

        // Allowlist mode চালু হলে কেবল অনুমোদিত প্যাকেজই অনুমোদিত
        val strictPolicy = com.example.accessibility.AppAccessPolicy(
            allowlistMode = true,
            allowedPackages = setOf("com.whatsapp")
        )
        assertTrue(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.whatsapp", strictPolicy).allowed)
        assertFalse(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.google.android.youtube", strictPolicy).allowed)
        // ব্যাংকিং অ্যাপ Allowlist-এ থাকলেও ব্লকড থাকে যদি না ডিফল্ট ব্লকলিস্ট থেকে সরানো হয়
        val strictWithBkash = strictPolicy.copy(allowedPackages = setOf("com.bKash.customerapp"))
        assertFalse(com.example.accessibility.ScreenSecurityGuard.checkAppAccess("com.bKash.customerapp", strictWithBkash).allowed)
    }

    @Test
    fun `screen security guard detects login otp high risk clicks and masks secrets`() {
        assertTrue(com.example.accessibility.ScreenSecurityGuard.isLoginOrOtpScreen(true, emptyList()))
        assertTrue(com.example.accessibility.ScreenSecurityGuard.isLoginOrOtpScreen(false, listOf("Please enter OTP to verify")))
        assertFalse(com.example.accessibility.ScreenSecurityGuard.isLoginOrOtpScreen(false, listOf("Settings", "Wi-Fi")))

        assertTrue(com.example.accessibility.ScreenSecurityGuard.isHighRiskClick("Send", null))
        assertTrue(com.example.accessibility.ScreenSecurityGuard.isHighRiskClick("পাঠান", null))
        assertTrue(com.example.accessibility.ScreenSecurityGuard.isHighRiskClick(null, "Pay Now"))
        assertTrue(com.example.accessibility.ScreenSecurityGuard.isHighRiskClick("ডিলিট করুন", null))
        assertFalse(com.example.accessibility.ScreenSecurityGuard.isHighRiskClick("Open Settings", "Profile"))

        val masked = com.example.accessibility.ScreenSecurityGuard.maskSensitivePatterns(
            "Key sk-proj1234567890abcdef OTP: 482910 Card 4532 1111 2222 3333"
        )
        assertFalse(masked.contains("sk-proj1234567890abcdef"))
        assertFalse(masked.contains("482910"))
        assertFalse(masked.contains("4532 1111 2222 3333"))

        val wrapped = com.example.accessibility.ScreenSecurityGuard.wrapUntrustedScreenContent("Hello Screen")
        assertTrue(wrapped.contains("<screen_content untrusted_data=\"true\">"))
    }

    @Test
    fun `screen snapshot builder handles null root safely and respects 150 element limit`() {
        val nullResult = com.example.accessibility.ScreenSnapshotBuilder.buildFromRoot(
            root = null,
            packageName = "com.whatsapp",
            activityName = "HomeActivity",
            policy = com.example.accessibility.AppAccessPolicy()
        )
        assertTrue(nullResult is com.example.accessibility.SnapshotBuildResult.Unreadable)

        val blockedResult = com.example.accessibility.ScreenSnapshotBuilder.buildFromRoot(
            root = null,
            packageName = "com.bKash.customerapp",
            activityName = "Main",
            policy = com.example.accessibility.AppAccessPolicy()
        )
        assertTrue(blockedResult is com.example.accessibility.SnapshotBuildResult.Blocked)

        val nodes = (1..150).map { i ->
            com.example.accessibility.SnapshotNodeRef(
                id = i,
                type = "button",
                text = "Item $i",
                contentDesc = "",
                viewId = null,
                bounds = android.graphics.Rect(0, i * 10, 100, i * 10 + 10),
                clickable = true,
                editable = false,
                scrollable = false,
                checked = null,
                isPassword = false
            )
        }
        val compact = com.example.accessibility.ScreenSnapshotBuilder.formatCompactText(
            packageName = "com.example.demo",
            screenLabel = "List",
            elements = nodes,
            truncated = true,
            hasPartialUiWarning = true
        )
        assertTrue(compact.contains("[150]"))
        assertTrue(compact.contains("প্রথম 150 টি"))
        assertTrue(compact.contains("WebView"))
    }

    @Test
    fun `notification memory store caps at 50 expires after 2 hours and filters otp banking`() {
        com.example.accessibility.NotificationMemoryStore.clear()
        val now = 1_700_000_000_000L

        // OTP ও ব্যাংকিং নোটিফিকেশন বাদ পড়বে
        assertFalse(
            com.example.accessibility.NotificationMemoryStore.addNotification(
                com.example.accessibility.CapturedNotification(
                    key = "otp1",
                    packageName = "com.android.mms",
                    appLabel = "Messages",
                    title = "Verification",
                    text = "Your OTP is 491822",
                    timestampMs = now
                ),
                nowMs = now
            )
        )
        assertFalse(
            com.example.accessibility.NotificationMemoryStore.addNotification(
                com.example.accessibility.CapturedNotification(
                    key = "bkash1",
                    packageName = "com.bKash.customerapp",
                    appLabel = "bKash",
                    title = "Payment",
                    text = "Received Tk 500",
                    timestampMs = now
                ),
                nowMs = now
            )
        )

        // ৬০টি সাধারণ নোটিফিকেশন যোগ করলে সর্বোচ্চ ৫০টি থাকবে
        for (i in 1..60) {
            com.example.accessibility.NotificationMemoryStore.addNotification(
                com.example.accessibility.CapturedNotification(
                    key = "msg_$i",
                    packageName = if (i % 2 == 0) "com.whatsapp" else "org.telegram.messenger",
                    appLabel = if (i % 2 == 0) "WhatsApp" else "Telegram",
                    title = "Friend $i",
                    text = "Hello message $i",
                    timestampMs = now + i * 1000L
                ),
                nowMs = now + i * 1000L
            )
        }
        val allRecent = com.example.accessibility.NotificationMemoryStore.getRecentNotifications(
            limit = 50,
            nowMs = now + 70_000L
        )
        assertEquals(50, allRecent.size)

        // অ্যাপ ফিল্টার পরীক্ষা
        val onlyWa = com.example.accessibility.NotificationMemoryStore.getRecentNotifications(
            allowedPackages = setOf("com.whatsapp"),
            limit = 50,
            nowMs = now + 70_000L
        )
        assertTrue(onlyWa.all { it.packageName == "com.whatsapp" })

        // ২ ঘণ্টার বেশি পরে মেয়াদোত্তীর্ণ হয়ে খালি হয়ে যাবে
        val expired = com.example.accessibility.NotificationMemoryStore.getRecentNotifications(
            nowMs = now + com.example.accessibility.NotificationMemoryStore.TTL_MS + 100_000L
        )
        assertTrue(expired.isEmpty())
        com.example.accessibility.NotificationMemoryStore.clear()
    }

    @Test
    fun `step 5 tools whatsapp recipe selectors and manifest services are properly configured`() {
        val step5Names = listOf(
            "read_screen", "click_element", "type_text", "scroll",
            "swipe_gesture", "global_action", "wait_for_screen_change",
            "get_notifications", "send_whatsapp_message"
        )
        step5Names.forEach { name ->
            assertNotNull("Tool missing: $name", ToolRegistry.getTool(name))
        }

        val clickTool = ToolRegistry.getTool("click_element")!!
        assertNotNull(ToolRegistry.validateArguments(clickTool, JSONObject()))
        assertNull(ToolRegistry.validateArguments(clickTool, JSONObject().put("element_id", 4)))

        val waTool = ToolRegistry.getTool("send_whatsapp_message")!!
        assertNotNull(ToolRegistry.validateArguments(waTool, JSONObject().put("message", "Hi")))
        assertNull(
            ToolRegistry.validateArguments(
                waTool,
                JSONObject().put("contact_name", "Rahim").put("message", "আসসালামু আলাইকুম")
            )
        )

        val recipe = com.example.accessibility.WhatsAppSendRecipe()
        val snap = com.example.accessibility.ScreenSnapshot(
            packageName = "com.whatsapp",
            screenLabel = "Conversation",
            elements = listOf(
                com.example.accessibility.SnapshotNodeRef(
                    id = 1,
                    type = "input",
                    text = "Hi",
                    contentDesc = "",
                    viewId = "com.whatsapp:id/entry",
                    bounds = android.graphics.Rect(10, 100, 300, 150),
                    clickable = true,
                    editable = true,
                    scrollable = false,
                    checked = null,
                    isPassword = false
                ),
                com.example.accessibility.SnapshotNodeRef(
                    id = 2,
                    type = "button",
                    text = "",
                    contentDesc = "পাঠান",
                    viewId = "com.whatsapp:id/send",
                    bounds = android.graphics.Rect(310, 100, 360, 150),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    checked = null,
                    isPassword = false
                )
            ),
            compactText = "",
            truncated = false,
            hasPartialUiWarning = false
        )
        assertEquals(2, recipe.findSendButton(snap)?.id)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        assertNotNull(
            pm.getServiceInfo(
                android.content.ComponentName(context, com.example.accessibility.AssistantAccessibilityService::class.java),
                0
            )
        )
        assertNotNull(
            pm.getServiceInfo(
                android.content.ComponentName(context, com.example.accessibility.AssistantNotificationListenerService::class.java),
                0
            )
        )
    }

    @Test
    fun `sse stream parser and accumulators handle openai anthropic and gemini deltas and tool calls`() {
        // ১. OpenAI SSE: টেক্সট ডেল্টা + চাঙ্কড tool_calls
        val openAiSse = """
            : keep-alive

            data: {"choices":[{"delta":{"content":"হ্যালো "}}]}

            data: {"choices":[{"delta":{"content":"বিশ্ব!"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"set_alarm","arguments":"{\"hour\":7"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":",\"minute\":30}"}}]}}]}

            data: [DONE]

        """.trimIndent()
        val openAiAcc = com.example.network.OpenAiSseAccumulator()
        val openAiDeltas = mutableListOf<String>()
        com.example.network.SseStreamParser.readSseEvents(okio.Buffer().writeUtf8(openAiSse)) { _, data ->
            val (delta, done) = openAiAcc.feed(data)
            if (delta != null) openAiDeltas.add(delta)
            !done
        }
        assertEquals(listOf("হ্যালো ", "বিশ্ব!"), openAiDeltas)
        val openAiResp = openAiAcc.buildResponse()
        assertEquals("হ্যালো বিশ্ব!", openAiResp.text)
        assertEquals(1, openAiResp.toolCalls.size)
        assertEquals("set_alarm", openAiResp.toolCalls[0].name)
        assertEquals(7, JSONObject(openAiResp.toolCalls[0].argumentsJson).getInt("hour"))
        assertEquals(30, JSONObject(openAiResp.toolCalls[0].argumentsJson).getInt("minute"))

        // ২. Anthropic SSE: text_delta + input_json_delta
        val anthropicSse = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ঠিক আছে"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"toggle_flashlight"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"enable\":true}"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val antAcc = com.example.network.AnthropicSseAccumulator()
        val antDeltas = mutableListOf<String>()
        com.example.network.SseStreamParser.readSseEvents(okio.Buffer().writeUtf8(anthropicSse)) { evt, data ->
            val (delta, done) = antAcc.feed(evt, data)
            if (delta != null) antDeltas.add(delta)
            !done
        }
        assertEquals(listOf("ঠিক আছে"), antDeltas)
        val antResp = antAcc.buildResponse()
        assertEquals("ঠিক আছে", antResp.text)
        assertEquals(1, antResp.toolCalls.size)
        assertEquals("toggle_flashlight", antResp.toolCalls[0].name)
        assertTrue(JSONObject(antResp.toolCalls[0].argumentsJson).getBoolean("enable"))

        // ৩. Gemini SSE: thought টেক্সট বাদ দিয়ে আসল টেক্সট ও functionCall নেওয়া
        val geminiSse = """
            data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking internally..."}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"আমি টর্চ "}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"চালু করছি।"},{"functionCall":{"name":"toggle_flashlight","args":{"enable":true}}}]}}]}

        """.trimIndent()
        val gemAcc = com.example.network.GeminiSseAccumulator()
        val gemDeltas = mutableListOf<String>()
        com.example.network.SseStreamParser.readSseEvents(okio.Buffer().writeUtf8(geminiSse)) { _, data ->
            val delta = gemAcc.feed(data)
            if (delta != null) gemDeltas.add(delta)
            true
        }
        assertEquals(listOf("আমি টর্চ ", "চালু করছি।"), gemDeltas)
        val gemResp = gemAcc.buildResponse()
        assertEquals("আমি টর্চ চালু করছি।", gemResp.text)
        assertEquals(1, gemResp.toolCalls.size)
        assertEquals("toggle_flashlight", gemResp.toolCalls[0].name)
    }

    @Test
    fun `streaming sentence chunker splits bengali english boundaries ignores decimals and defers code blocks`() {
        val chunker = com.example.voice.StreamingSentenceChunker()

        // প্রথম বাক্য এখনো পূর্ণ হয়নি
        assertTrue(chunker.append("আজ আবহাওয়া ").isEmpty())
        // বাংলা দাঁড়ি (।) পেলেই প্রথম বাক্য নির্গত
        assertEquals(listOf("আজ আবহাওয়া ভালো।"), chunker.append("ভালো। দ্বিতীয় "))
        // ৩.৫ দশমিক সংখ্যায় বিভক্ত হবে না
        assertTrue(chunker.append("মান 3.5 শতাংশ").isEmpty())
        assertEquals(
            listOf("দ্বিতীয় মান 3.5 শতাংশ।", "কেমন আছো?"),
            chunker.append("। কেমন আছো? ")
        )
        assertTrue(chunker.append("Next step").isEmpty())

        // খোলা কোড ব্লকের ভেতরে বিভক্ত করবে না যতক্ষণ না ``` বন্ধ হয়
        assertTrue(chunker.append("\n```kotlin\nval x = 1\n").isEmpty())
        val afterClose = chunker.append("```")
        assertEquals(1, afterClose.size)
        assertTrue(afterClose.first().contains("Next step"))
        // কোড ব্লক অংশটুকু বাফারে থেকে flush() এ বের হবে
        val flushedCode = chunker.flush()
        assertNotNull(flushedCode)
        assertTrue(flushedCode!!.contains("val x = 1"))

        // শেষে বাকি থাকা টেক্সট flush() এ পাওয়া যাবে
        assertTrue(chunker.append("শেষ অংশ").isEmpty())
        assertEquals("শেষ অংশ", chunker.flush())
    }

    @Test
    fun `prompt and tool optimizer bounds history selects relevant tools and prunes old snapshots`() {
        val sampleMsgs = (1..20).map { i ->
            com.example.model.ChatMessage(
                id = "m$i",
                conversationId = "c1",
                role = if (i % 2 == 0) "assistant" else "user",
                content = "মেসেজ নম্বর $i",
                timestamp = i * 1000L
            )
        }
        val shortBounded = com.example.tools.PromptAndToolOptimizer.buildBoundedHistory(
            sampleMsgs,
            com.example.model.HistoryLimitPreset.SHORT
        )
        assertEquals(6, shortBounded.size)
        assertEquals("m15", shortBounded.first().id)
        assertEquals("m20", shortBounded.last().id)

        val allTools = ToolRegistry.getEnabledTools(emptySet())
        // সাধারণ Q&A / গল্পে কোনো টুল পাঠানো হবে না
        val chatTools = com.example.tools.PromptAndToolOptimizer.selectRelevantTools("একটা ছোট গল্প বলো", allTools)
        assertTrue(chatTools.isEmpty())

        // অ্যালার্ম চাইলে কেবল ফোন-টুল গ্রুপ যাবে, UI টুল যাবে না
        val alarmTools = com.example.tools.PromptAndToolOptimizer.selectRelevantTools("সকাল ৭টায় অ্যালার্ম দাও", allTools)
        assertTrue(alarmTools.any { it.name == "set_alarm" })
        assertFalse(alarmTools.any { it.name == "swipe_gesture" })

        // অনিশ্চিত অ্যাকশনে নিরাপদভাবে টুলসহ পাঠানো হবে
        val uncertainTools = com.example.tools.PromptAndToolOptimizer.selectRelevantTools("এটা একটু করে দাও তো", allTools)
        assertEquals(allTools.size, uncertainTools.size)

        // পুরনো স্ক্রিন স্ন্যাপশট ও ইমেজ ছাঁটা যাচাই
        val bigText1 = "x".repeat(300)
        val bigText2 = "y".repeat(300)
        val turns = listOf(
            com.example.network.AgentTurnMessage(role = "tool", toolName = "read_screen", textContent = bigText1, imageBase64 = "img1"),
            com.example.network.AgentTurnMessage(role = "tool", toolName = "read_screen", textContent = bigText2, imageBase64 = "img2")
        )
        val pruned = com.example.tools.PromptAndToolOptimizer.pruneOldScreenSnapshots(turns)
        assertTrue(pruned[0].textContent!!.length < 200)
        assertNull(pruned[0].imageBase64)
        assertEquals(bigText2, pruned[1].textContent)
        assertEquals("img2", pruned[1].imageBase64)
    }

    @Test
    fun `streaming markdown parser handles incomplete fenced code block cleanly`() {
        val partial = "এখানে কোড:\n```kotlin\nfun hello() {\n  println(\"Hi\")"
        val blocks = com.example.ui.components.parseMarkdownBlocks(partial, isStreaming = true)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is com.example.ui.components.MarkdownBlock.TextParagraph)
        val codeBlock = blocks[1] as com.example.ui.components.MarkdownBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertTrue(codeBlock.code.contains("fun hello()"))
    }

    @Test
    fun `api key masking uses exact last 4 chars and redactSecrets strips sensitive tokens`() {
        assertEquals("", com.example.security.SecureKeyStore.maskKey(""))
        assertEquals("••••", com.example.security.SecureKeyStore.maskKey("1234"))
        assertEquals("••••ab12", com.example.security.SecureKeyStore.maskKey("sk-live-998877ab12"))
        assertEquals("••••wxyz", com.example.security.SecureKeyStore.maskKey("AIzaSyDummyKey123wxyz"))

        val err = "HTTP 403 at https://example.com/v1?key=AIzaSySecretKey999 Authorization: Bearer sk-secret1234567890"
        val sanitized = com.example.security.SecureKeyStore.redactSecrets(err)
        assertFalse(sanitized.contains("AIzaSySecretKey999"))
        assertFalse(sanitized.contains("sk-secret1234567890"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `repository isolates multiple model configs active selection and deletion`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.AiRepository(context)

        val first = repo.upsertModelConfig(
            config = com.example.model.ModelConfig(
                id = "",
                name = "Config One",
                providerType = com.example.model.ProviderType.OPENAI,
                baseUrl = com.example.model.ProviderType.OPENAI.defaultBaseUrl,
                modelName = "gpt-4o",
                isActive = true
            ),
            apiKey = "sk-first-key-1111",
            keyModified = true
        )
        val second = repo.upsertModelConfig(
            config = com.example.model.ModelConfig(
                id = "",
                name = "Config Two",
                providerType = com.example.model.ProviderType.ANTHROPIC,
                baseUrl = com.example.model.ProviderType.ANTHROPIC.defaultBaseUrl,
                modelName = "claude-3-5-sonnet",
                isActive = false
            ),
            apiKey = "sk-ant-second-2222",
            keyModified = true
        )

        assertFalse(first.id.isBlank())
        assertFalse(second.id.isBlank())
        assertFalse(first.id == second.id)

        val listAfterAdd = repo.modelConfigsFlow.first()
        assertTrue(listAfterAdd.any { it.id == first.id && it.isActive })
        assertTrue(listAfterAdd.any { it.id == second.id && !it.isActive })

        repo.setActiveModel(second.id)
        val listAfterSwitch = repo.modelConfigsFlow.first()
        assertTrue(listAfterSwitch.first { it.id == second.id }.isActive)
        assertFalse(listAfterSwitch.first { it.id == first.id }.isActive)

        repo.deleteModelConfig(second.id)
        val listAfterDelete = repo.modelConfigsFlow.first()
        assertFalse(listAfterDelete.any { it.id == second.id })
        assertTrue(listAfterDelete.any { it.id == first.id })
    }
}
