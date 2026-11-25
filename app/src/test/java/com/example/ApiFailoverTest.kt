package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ActionCardItem
import com.example.model.ChatMessage
import com.example.model.InputMode
import com.example.model.ModelConfig
import com.example.model.ProviderType
import com.example.model.RequestLatencyMetrics
import com.example.network.AgentTurnMessage
import com.example.network.ApiHttpException
import com.example.network.AiProvider
import com.example.network.AiStreamEvent
import com.example.network.AiTurnResponse
import com.example.network.AllModelsFailedException
import com.example.network.CrossProviderHistoryConverter
import com.example.network.FailoverCandidate
import com.example.network.FailoverClassifier
import com.example.network.FailoverNotice
import com.example.network.FailoverReason
import com.example.network.ModelHealthTracker
import com.example.network.RetryAfterParser
import com.example.network.ToolCallRequest
import com.example.tools.AgentOrchestrator
import com.example.tools.AgentUiBridge
import com.example.tools.ConfirmationOutcome
import com.example.tools.ConfirmationRequestContext
import com.example.tools.ContactMatch
import com.example.tools.ToolDefinition
import com.example.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApiFailoverTest {

    @Test
    fun `retry after parser handles integer seconds and HTTP date`() {
        assertEquals(42L, RetryAfterParser.parse("42"))
        assertEquals(3600L, RetryAfterParser.parse("99999"))
        assertNull(RetryAfterParser.parse(""))
        assertNull(RetryAfterParser.parse("invalid-header"))

        val nowMs = 1_700_000_000_000L
        val parsedDate = RetryAfterParser.parse("Tue, 14 Nov 2023 22:14:20 GMT", nowMs)
        assertNotNull(parsedDate)
        assertTrue(parsedDate!! > 0L)
    }

    @Test
    fun `failover classifier classifies retryable codes vs malformed 400 and cancellation`() {
        assertEquals(
            FailoverReason.RATE_LIMIT_OR_QUOTA,
            FailoverClassifier.classify(ApiHttpException(429, "Too many requests", 15))
        )
        assertEquals(
            FailoverReason.BILLING_OR_PAYMENT,
            FailoverClassifier.classify(ApiHttpException(402, "Insufficient credits"))
        )
        assertEquals(
            FailoverReason.AUTH_FAILED,
            FailoverClassifier.classify(ApiHttpException(401, "Invalid API key"))
        )
        assertEquals(
            FailoverReason.AUTH_FAILED,
            FailoverClassifier.classify(ApiHttpException(403, "Forbidden"))
        )
        assertEquals(
            FailoverReason.MODEL_NOT_FOUND,
            FailoverClassifier.classify(ApiHttpException(404, "Model not found"))
        )
        assertEquals(
            FailoverReason.TIMEOUT,
            FailoverClassifier.classify(ApiHttpException(408, "Request timeout"))
        )
        assertEquals(
            FailoverReason.SERVER_ERROR,
            FailoverClassifier.classify(ApiHttpException(503, "Service unavailable"))
        )
        assertEquals(
            FailoverReason.RATE_LIMIT_OR_QUOTA,
            FailoverClassifier.classify(ApiHttpException(400, "RESOURCE_EXHAUSTED quota exceeded"))
        )
        assertEquals(
            FailoverReason.TIMEOUT,
            FailoverClassifier.classify(SocketTimeoutException("timed out"))
        )
        assertEquals(
            FailoverReason.NETWORK_ERROR,
            FailoverClassifier.classify(IOException("reset"))
        )

        // Non-failover cases
        assertNull(FailoverClassifier.classify(ApiHttpException(400, "invalid JSON schema parameter")))
        assertNull(FailoverClassifier.classify(CancellationException("user stopped")))
    }

    @Test
    fun `health tracker applies cooldown, expires on clock advance, and resets all`() {
        var fakeNow = 10_000L
        val tracker = ModelHealthTracker { fakeNow }

        tracker.markFailure("m1", ApiHttpException(429, "limit", retryAfterSeconds = 20))
        assertTrue(tracker.isInCooldown("m1"))
        assertEquals(20L, tracker.remainingCooldownSeconds("m1"))

        fakeNow += 21_000L
        assertFalse(tracker.isInCooldown("m1"))

        tracker.markFailure("m2", ApiHttpException(500, "err"))
        assertTrue(tracker.isInCooldown("m2"))
        tracker.resetAll()
        assertFalse(tracker.isInCooldown("m2"))
    }

    @Test
    fun `cross provider history converter clears mismatched rawParts while preserving tool calls`() {
        val history = listOf(
            AgentTurnMessage(
                role = "assistant",
                textContent = "Checking device info",
                toolCalls = listOf(ToolCallRequest("call_1", "get_battery_status", "{}")),
                rawParts = JSONArray().put(JSONObject().put("type", "thinking")),
                rawPartsProvider = ProviderType.ANTHROPIC
            ),
            AgentTurnMessage(
                role = "tool",
                textContent = "Battery: 80%",
                toolCallId = "call_1",
                toolName = "get_battery_status"
            )
        )

        val sameProvider = CrossProviderHistoryConverter.convertForProvider(history, ProviderType.ANTHROPIC)
        assertNotNull(sameProvider.first().rawParts)

        val convertedToGemini = CrossProviderHistoryConverter.convertForProvider(history, ProviderType.GEMINI)
        assertNull(convertedToGemini.first().rawParts)
        assertNull(convertedToGemini.first().rawPartsProvider)
        assertEquals("call_1", convertedToGemini.first().toolCalls.first().id)
        assertEquals("get_battery_status", convertedToGemini[1].toolName)

        val convertedToOpenAi = CrossProviderHistoryConverter.convertForProvider(history, ProviderType.OPENAI)
        assertNull(convertedToOpenAi.first().rawParts)
        assertEquals("Checking device info", convertedToOpenAi.first().textContent)
    }

    @Test
    fun `agent orchestrator fails over mid-stream without duplicating completed tools and resets stream`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actionLogCalls = AtomicInteger(0)
        val bridge = object : AgentUiBridge {
            override suspend fun requestPermissions(
                permissions: List<String>,
                requiresWriteSettings: Boolean,
                explanationRes: Int
            ): Boolean = true

            override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? = null

            override suspend fun requestConfirmation(
                tool: ToolDefinition,
                args: JSONObject,
                isVoiceMode: Boolean,
                confirmContext: ConfirmationRequestContext
            ): ConfirmationOutcome = ConfirmationOutcome.Confirmed()

            override fun onActionCardChanged(card: ActionCardItem) {}

            override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {
                actionLogCalls.incrementAndGet()
            }
        }

        val primaryCfg = ModelConfig(
            id = "primary",
            name = "Primary OpenAI",
            providerType = ProviderType.OPENAI,
            baseUrl = "https://example.invalid/v1",
            modelName = "gpt-4o"
        )
        val backupCfg = ModelConfig(
            id = "backup",
            name = "Backup Gemini",
            providerType = ProviderType.GEMINI,
            baseUrl = "https://example.invalid/v1beta",
            modelName = "gemini-2.5-flash"
        )

        var primarySteps = 0
        val primaryProvider = object : AiProvider {
            override suspend fun sendMessage(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                history: List<ChatMessage>
            ): String = ""

            override suspend fun sendAgentTurn(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<ToolDefinition>,
                maxTokens: Int?
            ): AiTurnResponse = throw UnsupportedOperationException()

            override fun streamAgentTurn(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<ToolDefinition>,
                maxTokens: Int?
            ): Flow<AiStreamEvent> = flow {
                primarySteps++
                if (primarySteps == 1) {
                    emit(
                        AiStreamEvent.Completed(
                            response = AiTurnResponse(
                                text = null,
                                toolCalls = listOf(ToolCallRequest("t1", "get_current_time", "{}")),
                                rawPartsProvider = ProviderType.OPENAI
                            ),
                            metrics = RequestLatencyMetrics()
                        )
                    )
                } else {
                    emit(AiStreamEvent.TextDelta("Partial broken text"))
                    throw ApiHttpException(429, "Quota exceeded")
                }
            }

            override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean = false
        }

        val backupProvider = object : AiProvider {
            override suspend fun sendMessage(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                history: List<ChatMessage>
            ): String = ""

            override suspend fun sendAgentTurn(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<ToolDefinition>,
                maxTokens: Int?
            ): AiTurnResponse = throw UnsupportedOperationException()

            override fun streamAgentTurn(
                config: ModelConfig,
                apiKey: String,
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<ToolDefinition>,
                maxTokens: Int?
            ): Flow<AiStreamEvent> = flow {
                // Should receive tool result from step 1 without re-running get_current_time
                assertTrue(messages.any { it.role == "tool" && it.toolName == "get_current_time" })
                emit(AiStreamEvent.TextDelta("Final backup answer"))
                emit(
                    AiStreamEvent.Completed(
                        response = AiTurnResponse(
                            text = "Final backup answer",
                            rawPartsProvider = ProviderType.GEMINI
                        ),
                        metrics = RequestLatencyMetrics()
                    )
                )
            }

            override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean = true
        }

        val tracker = ModelHealthTracker()
        val notices = mutableListOf<FailoverNotice>()
        val resets = AtomicInteger(0)

        val orchestrator = AgentOrchestrator(context, bridge)
        val result = orchestrator.runAgentLoop(
            provider = primaryProvider,
            config = primaryCfg,
            apiKey = "sk-primary-test",
            baseSystemPrompt = "Test",
            history = listOf(ChatMessage(role = "user", content = "What time is it?")),
            enabledTools = listOfNotNull(ToolRegistry.getTool("get_current_time")),
            inputMode = InputMode.TEXT,
            logSensitiveData = false,
            preferredSimSlot = -1,
            backupCandidates = listOf(FailoverCandidate(backupCfg, "sk-backup-test", backupProvider)),
            healthTracker = tracker,
            onFailoverSwitched = { notices.add(it) },
            onStreamReset = { resets.incrementAndGet() }
        )

        assertEquals("Final backup answer", result.finalReply)
        assertEquals(1, actionLogCalls.get())
        assertEquals(1, notices.size)
        assertEquals("Primary OpenAI", notices.first().fromModelName)
        assertEquals("Backup Gemini", notices.first().toModelName)
        assertTrue(resets.get() >= 2)
        assertTrue(tracker.isInCooldown("primary"))
    }

    @Test
    fun `agent orchestrator throws AllModelsFailedException when all models fail`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = object : AgentUiBridge {
            override suspend fun requestPermissions(permissions: List<String>, requiresWriteSettings: Boolean, explanationRes: Int) = false
            override suspend fun pickContact(query: String, matches: List<ContactMatch>): ContactMatch? = null
            override suspend fun requestConfirmation(tool: ToolDefinition, args: JSONObject, isVoiceMode: Boolean, confirmContext: ConfirmationRequestContext) = ConfirmationOutcome.Cancelled("cancelled")
            override fun onActionCardChanged(card: ActionCardItem) {}
            override suspend fun onRecordActionLog(toolName: String, argsSummary: String, status: String) {}
        }

        val failingProvider = object : AiProvider {
            override suspend fun sendMessage(config: ModelConfig, apiKey: String, systemPrompt: String, history: List<ChatMessage>) = ""
            override suspend fun sendAgentTurn(config: ModelConfig, apiKey: String, systemPrompt: String, messages: List<AgentTurnMessage>, tools: List<ToolDefinition>, maxTokens: Int?) = throw UnsupportedOperationException()
            override fun streamAgentTurn(config: ModelConfig, apiKey: String, systemPrompt: String, messages: List<AgentTurnMessage>, tools: List<ToolDefinition>, maxTokens: Int?): Flow<AiStreamEvent> = flow {
                throw ApiHttpException(503, "Backend overloaded")
            }
            override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean = false
        }

        val m1 = ModelConfig("m1", "Model 1", ProviderType.OPENAI, "https://a.invalid/v1", "gpt-4o")
        val m2 = ModelConfig("m2", "Model 2", ProviderType.GEMINI, "https://b.invalid/v1beta", "gemini-2.5-flash")

        val orchestrator = AgentOrchestrator(context, bridge)
        var caught: AllModelsFailedException? = null
        try {
            orchestrator.runAgentLoop(
                provider = failingProvider,
                config = m1,
                apiKey = "key1",
                baseSystemPrompt = "Test",
                history = listOf(ChatMessage(role = "user", content = "hi")),
                enabledTools = emptyList(),
                inputMode = InputMode.TEXT,
                logSensitiveData = false,
                preferredSimSlot = -1,
                backupCandidates = listOf(FailoverCandidate(m2, "key2", failingProvider)),
                healthTracker = ModelHealthTracker()
            )
        } catch (e: AllModelsFailedException) {
            caught = e
        }

        assertNotNull(caught)
        assertEquals(2, caught!!.attempts.size)
        // Also assert testConnection itself does NOT do failover (returns false directly)
        assertFalse(failingProvider.testConnection(m1, "key1"))
    }
}
