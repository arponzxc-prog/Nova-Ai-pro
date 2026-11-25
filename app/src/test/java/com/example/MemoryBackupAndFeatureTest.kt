package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.RoutineExecutor
import com.example.assistant.RoutineScheduler
import com.example.data.ChatBackupManager
import com.example.data.local.AppDatabase
import com.example.data.local.MemoryEntity
import com.example.model.ActionCardItem
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.InputMode
import com.example.model.ModelConfig
import com.example.model.ProviderType
import com.example.model.RequestLatencyMetrics
import com.example.network.AgentTurnMessage
import com.example.network.AiProvider
import com.example.network.AiStreamEvent
import com.example.network.AiTurnResponse
import com.example.network.ApiHttpException
import com.example.network.ErrorFormatter
import com.example.network.ToolCallRequest
import com.example.tools.AgentOrchestrator
import com.example.tools.AgentUiBridge
import com.example.tools.ConfirmationOutcome
import com.example.tools.ConfirmationRequestContext
import com.example.tools.ContactMatch
import com.example.tools.MemoryTools
import com.example.tools.RiskLevel
import com.example.tools.SearchProviderType
import com.example.tools.ToolDefinition
import com.example.tools.ToolExecutionContext
import com.example.tools.ToolRegistry
import com.example.tools.WebSearchTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryBackupAndFeatureTest {

    @Test
    fun `room v2 to v3 migration creates memories, routines, and usage_stats cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_v2_v3_test.db"
        context.deleteDatabase(dbName)

        // Create a v2 schema database manually
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, pinned INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, inputMode TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS action_logs (id TEXT NOT NULL PRIMARY KEY, toolName TEXT NOT NULL, argsSummary TEXT NOT NULL, status TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val rawDb = helper.writableDatabase
        AppDatabase.MIGRATION_2_3.migrate(rawDb)

        rawDb.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val tables = mutableSetOf<String>()
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
            assertTrue(tables.contains("memories"))
            assertTrue(tables.contains("routines"))
            assertTrue(tables.contains("usage_stats"))
        }
        rawDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `memory tools reject sensitive secrets and rank relevant facts`() {
        assertNotNull(MemoryTools.detectSensitiveReason("My password is secret123"))
        assertNotNull(MemoryTools.detectSensitiveReason("OTP code is 458912"))
        assertNotNull(MemoryTools.detectSensitiveReason("Key sk-abcdefghijklmnopqrstuv"))
        assertNull(MemoryTools.detectSensitiveReason("আমার নাম অর্পণ এবং আমি কফি পছন্দ করি"))

        val all = listOf(
            MemoryEntity("1", "fact", "আমি ঢাকায় থাকি", "ঢাকা, শহর", 100L),
            MemoryEntity("2", "name", "ইউজারের নাম অর্পণ", "নাম", 200L),
            MemoryEntity("3", "preference", "আমি ডার্ক মোড পছন্দ করি", "ডার্ক, থিম", 150L)
        )
        val selected = MemoryTools.selectRelevantMemories("ডার্ক থিম কেমন?", all, limit = 2)
        assertEquals(2, selected.size)
        assertTrue(selected.any { it.id == "3" })
        assertTrue(selected.any { it.id == "2" })
    }

    @Test
    fun `chat backup JSON and Markdown export exclude API keys and roundtrip cleanly`() {
        val conv = Conversation(id = "conv-1", title = "Test Chat", createdAt = 1000L, updatedAt = 2000L, pinned = true)
        val msgs = listOf(
            ChatMessage(id = "m1", conversationId = "conv-1", role = "user", content = "Hello Nexa", inputMode = InputMode.TEXT, timestamp = 1100L),
            ChatMessage(id = "m2", conversationId = "conv-1", role = "assistant", content = "হ্যালো! কীভাবে সাহায্য করব?", inputMode = InputMode.VOICE, timestamp = 1200L)
        )

        val json = ChatBackupManager.exportJson(listOf(conv to msgs), exportedAtMs = 5000L)
        assertFalse(json.contains("apiKey", ignoreCase = true))
        assertFalse(json.contains("sk-"))

        val parsed = ChatBackupManager.parseJsonBackup(json)
        assertEquals(1, parsed.size)
        assertEquals("Test Chat", parsed.first().first.title)
        assertEquals(2, parsed.first().second.size)
        assertEquals(InputMode.VOICE, parsed.first().second[1].inputMode)

        val md = ChatBackupManager.exportMarkdown(listOf(conv to msgs), exportedAtMs = 5000L)
        assertTrue(md.contains("# NOVA AI — Chat Export"))
        assertTrue(md.contains("## Test Chat"))
        assertTrue(md.contains("### NOVA AI"))
    }

    @Test
    fun `error formatter formats HTTP codes and redacts embedded API keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secretErr = ApiHttpException(401, "Unauthorized with sk-liveSecretKey999999999")
        val msg = ErrorFormatter.format(context, secretErr)
        assertFalse(msg.contains("sk-liveSecretKey999999999"))
        assertTrue(msg.contains("401"))
    }

    @Test
    fun `agent orchestrator respects standardMaxSteps and breaks infinite tool loops`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = object : AgentUiBridge {
            override suspend fun requestPermissions(permissions: List<String>, requiresWriteSettings: Boolean, explanationRes: Int) = true
            override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? = null
            override suspend fun requestConfirmation(tool: ToolDefinition, args: JSONObject, isVoiceMode: Boolean, confirmContext: ConfirmationRequestContext) = ConfirmationOutcome.Confirmed()
            override fun onActionCardChanged(card: ActionCardItem) {}
            override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {}
        }

        val loopingProvider = object : AiProvider {
            override suspend fun sendMessage(config: ModelConfig, apiKey: String, systemPrompt: String, history: List<ChatMessage>) = ""
            override suspend fun sendAgentTurn(config: ModelConfig, apiKey: String, systemPrompt: String, messages: List<AgentTurnMessage>, tools: List<ToolDefinition>, maxTokens: Int?) = throw UnsupportedOperationException()
            override fun streamAgentTurn(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<ToolDefinition>,
                maxTokens: Int?
            ): Flow<AiStreamEvent> = flow {
                emit(
                    AiStreamEvent.Completed(
                        AiTurnResponse(
                            text = null,
                            toolCalls = listOf(ToolCallRequest("loop", "get_current_time", "{}")),
                            rawPartsProvider = ProviderType.OPENAI
                        ),
                        RequestLatencyMetrics()
                    )
                )
            }
            override suspend fun testConnection(config: ModelConfig, apiKey: String) = true
        }

        val orchestrator = AgentOrchestrator(context, bridge)
        val result = orchestrator.runAgentLoop(
            provider = loopingProvider,
            config = ModelConfig("m", "M", ProviderType.OPENAI, "https://a.invalid/v1", "gpt-4o"),
            apiKey = "key",
            baseSystemPrompt = "Test",
            history = listOf(ChatMessage(role = "user", content = "time")),
            enabledTools = listOfNotNull(ToolRegistry.getTool("get_current_time")),
            inputMode = InputMode.TEXT,
            logSensitiveData = false,
            preferredSimSlot = -1,
            standardMaxSteps = 8
        )
        assertTrue(result.finalReply.contains("লুপ থামানো হয়েছে"))
    }

    @Test
    fun `routine executor only allows LOW risk tools and scheduler computes next trigger`() {
        val allowed = RoutineExecutor.filterAllowedToolsForRoutine(emptySet())
        assertTrue(allowed.isNotEmpty())
        assertTrue(allowed.all { it.riskLevel == RiskLevel.LOW })
        assertFalse(allowed.any { it.name == "make_call" || it.name == "send_sms" })

        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 10, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sameDayLater = RoutineScheduler.computeNextTriggerMillis(10, 30, "DAILY", false, nowCal.timeInMillis)
        assertTrue(sameDayLater > nowCal.timeInMillis)
        val nextDayEarlier = RoutineScheduler.computeNextTriggerMillis(8, 0, "DAILY", false, nowCal.timeInMillis)
        assertTrue(nextDayEarlier > nowCal.timeInMillis + 12L * 3600_000L)
    }

    @Test
    fun `web search tools fail over from Tavily 429 to Brave Search 200`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("tavily.com")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Rate limited")
                        .body("""{"error":"rate limit"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val json = """{"web":{"results":[{"title":"Nexa News","url":"https://example.org","description":"Fresh update"}]}}"""
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val result = WebSearchTools.search(
            ctx = ToolExecutionContext(context),
            args = JSONObject().put("query", "Bangladesh news"),
            client = client,
            keyOverride = mapOf(
                SearchProviderType.TAVILY to "tvly-test-key",
                SearchProviderType.BRAVE to "brave-test-key"
            )
        )
        assertTrue(result.success)
        assertTrue(result.message.contains("Brave Search"))
        assertTrue(result.message.contains("Nexa News"))
    }
}
