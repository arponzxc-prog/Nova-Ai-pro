package com.example.network

import android.util.Log
import com.example.model.ChatMessage
import com.example.model.ModelConfig
import com.example.model.ProviderType
import com.example.model.RequestLatencyMetrics
import com.example.tools.PromptAndToolOptimizer
import com.example.tools.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * এজেন্ট লুপের ভেতরে প্রতি ধাপের কথোপকথন ও টুল কলের অবস্থা ধরে রাখার মডেল।
 */
data class AgentTurnMessage(
    val role: String, // "user", "assistant", "tool"
    val textContent: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val rawParts: JSONArray? = null,
    val rawPartsProvider: ProviderType? = null,
    val imageBase64: String? = null
)

data class AiTurnResponse(
    val text: String?,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val rawAssistantParts: JSONArray? = null,
    val rawPartsProvider: ProviderType? = null,
    val toolsNotSupported: Boolean = false,
    val metrics: RequestLatencyMetrics = RequestLatencyMetrics()
)

sealed interface AiStreamEvent {
    data class TextDelta(val delta: String) : AiStreamEvent
    data class Completed(val response: AiTurnResponse, val metrics: RequestLatencyMetrics) : AiStreamEvent
}

/**
 * স্ট্রিমিং চলাকালে নেটওয়ার্ক বিচ্ছিন্ন হলে এ পর্যন্ত পাওয়া আংশিক উত্তর সংরক্ষণের জন্য এক্সেপশন।
 */
class PartialStreamException(
    val partialText: String,
    cause: Throwable
) : IOException(cause.message ?: "Stream interrupted", cause)

interface AiProvider {
    suspend fun sendMessage(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>
    ): String

    suspend fun sendAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int? = null
    ): AiTurnResponse

    fun streamAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int? = null
    ): Flow<AiStreamEvent>

    suspend fun testConnection(
        config: ModelConfig,
        apiKey: String
    ): Boolean
}

class AiProviderFactory {
    // পুরো অ্যাপে একই শেয়ার্ড OkHttpClient এবং কানেকশন পুল ব্যবহার করা হচ্ছে
    fun getProvider(type: ProviderType): AiProvider {
        val base = NetworkClient.baseClient
        val stream = NetworkClient.streamingClient
        return when (type) {
            ProviderType.OPENAI -> OpenAiCompatibleProvider(base, stream)
            ProviderType.ANTHROPIC -> AnthropicProvider(base, stream)
            ProviderType.GEMINI -> GeminiProvider(base, stream)
        }
    }
}

private fun monoMs(): Long = System.nanoTime() / 1_000_000L

private fun logLatencyMetrics(provider: ProviderType, metrics: RequestLatencyMetrics) {
    // নিরাপত্তার নিয়ম: লগে কখনোই API Key বা মেসেজের বিষয়বস্তু লেখা হবে না
    runCatching {
        Log.d(
            "AiLatency",
            "provider=${provider.name} stream=${metrics.usedStreaming} " +
                "buildMs=${metrics.requestBuildMs} ttftMs=${metrics.firstTokenMs} totalMs=${metrics.totalMs} " +
                "msgs=${metrics.messageCount} estTok=${metrics.estimatedInputTokens} tools=${metrics.toolCount}"
        )
    }
}

/**
 * OpenAI Compatible Provider (OpenAI, Groq, OpenRouter, DeepSeek, Local LLMs)
 */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    private val streamClient: OkHttpClient
) : AiProvider {

    override suspend fun sendMessage(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>
    ): String {
        val turnMsgs = history.map { AgentTurnMessage(role = it.role, textContent = it.content) }
        val response = sendAgentTurn(config, apiKey, systemPrompt, turnMsgs, emptyList())
        return response.text ?: throw ResponseParseException("কোনো উত্তর পাওয়া যায়নি")
    }

    override suspend fun sendAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse = withContext(Dispatchers.IO) {
        val useTools = config.supportsTools && tools.isNotEmpty()
        try {
            executeOpenAiBlocking(config, apiKey, systemPrompt, messages, if (useTools) tools else emptyList(), maxTokens)
        } catch (_: ToolUnsupportedHttpException) {
            val fallback = executeOpenAiBlocking(config, apiKey, systemPrompt, messages, emptyList(), maxTokens)
            fallback.copy(toolsNotSupported = true)
        }
    }

    override fun streamAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): Flow<AiStreamEvent> = flow {
        val effectiveTools = if (config.supportsTools && tools.isNotEmpty()) tools else emptyList()
        if (!config.supportsStreaming) {
            val res = sendAgentTurn(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens)
            emit(AiStreamEvent.Completed(res, res.metrics))
            return@flow
        }

        try {
            executeOpenAiStream(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens) { emit(it) }
        } catch (_: ToolUnsupportedHttpException) {
            executeOpenAiStream(config, apiKey, systemPrompt, messages, emptyList(), maxTokens, toolsFallback = true) {
                emit(it)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeOpenAiStream(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?,
        toolsFallback: Boolean = false,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val totalStart = monoMs()
        val request = buildOpenAiRequest(config, apiKey, systemPrompt, messages, tools, stream = true, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val acc = OpenAiSseAccumulator()
        val call: Call = streamClient.newCall(request)
        val ctx = currentCoroutineContext()
        val cancelHook = ctx[Job]?.invokeOnCompletion { call.cancel() }
        var ttftMs = 0L
        val netStart = monoMs()

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, errBody)) {
                        throw ToolUnsupportedHttpException(errBody)
                    }
                    throw ApiHttpException(
                        statusCode = resp.code,
                        responseBody = errBody,
                        retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                        providerType = ProviderType.OPENAI
                    )
                }

                val body = resp.body ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
                val source = body.source()

                // কো-রুটিন ক্যান্সেল হলে সাথে সাথে OkHttp কল বাতিল ও লাইন ধরে SSE পড়া
                SseStreamParser.readSseEventsSuspend(source) { _, payload ->
                    ctx.ensureActive()
                    val (delta, done) = acc.feed(payload)
                    if (!delta.isNullOrEmpty()) {
                        if (ttftMs == 0L) ttftMs = monoMs() - netStart
                        emitEvent(AiStreamEvent.TextDelta(delta))
                    } else if (ttftMs == 0L && !done) {
                        ttftMs = monoMs() - netStart
                    }
                    !done
                }
            }
        } catch (e: IOException) {
            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException("Stream cancelled")
            val partial = acc.currentText()
            if (partial.isNotBlank() && !call.isCanceled()) {
                throw PartialStreamException(partial, e)
            }
            throw e
        } finally {
            cancelHook?.dispose()
            call.cancel()
        }

        val totalMs = monoMs() - totalStart
        if (ttftMs == 0L) ttftMs = totalMs
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = true
        )
        logLatencyMetrics(ProviderType.OPENAI, metrics)
        val built = acc.buildResponse().copy(toolsNotSupported = toolsFallback, metrics = metrics)
        emitEvent(AiStreamEvent.Completed(built, metrics))
    }

    private fun executeOpenAiBlocking(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse {
        val totalStart = monoMs()
        val request = buildOpenAiRequest(config, apiKey, systemPrompt, messages, tools, stream = false, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val netStart = monoMs()
        val response = client.newCall(request).execute()
        val ttftMs = monoMs() - netStart
        val responseString = response.use { resp ->
            val str = resp.body?.string() ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
            if (!resp.isSuccessful) {
                if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, str)) {
                    throw ToolUnsupportedHttpException(str)
                }
                throw ApiHttpException(
                    statusCode = resp.code,
                    responseBody = str,
                    retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                    providerType = ProviderType.OPENAI
                )
            }
            str
        }

        val root = JSONObject(responseString)
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) throw ResponseParseException("কোনো উত্তর পাওয়া যায়নি")

        val messageObj = choices.getJSONObject(0).getJSONObject("message")
        val text = if (messageObj.isNull("content")) null else messageObj.optString("content").takeIf { it.isNotBlank() }

        val parsedCalls = mutableListOf<ToolCallRequest>()
        val toolCallsArr = messageObj.optJSONArray("tool_calls")
        if (toolCallsArr != null) {
            for (i in 0 until toolCallsArr.length()) {
                val tcObj = toolCallsArr.getJSONObject(i)
                val fnObj = tcObj.getJSONObject("function")
                parsedCalls.add(
                    ToolCallRequest(
                        id = tcObj.optString("id", UUID.randomUUID().toString()),
                        name = fnObj.getString("name"),
                        argumentsJson = fnObj.optString("arguments", "{}")
                    )
                )
            }
        }
        val totalMs = monoMs() - totalStart
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = false
        )
        logLatencyMetrics(ProviderType.OPENAI, metrics)
        return AiTurnResponse(text = text, toolCalls = parsedCalls, metrics = metrics)
    }

    private fun buildOpenAiRequest(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        maxTokens: Int?
    ): Request {
        val url = if (config.baseUrl.endsWith("/")) {
            "${config.baseUrl}chat/completions"
        } else {
            "${config.baseUrl}/chat/completions"
        }

        val jsonBody = JSONObject().apply {
            put("model", config.modelName)
            put("temperature", config.temperature.toDouble())
            if (stream) put("stream", true)
            val modelLower = config.modelName.lowercase()
            if (maxTokens != null && !modelLower.startsWith("o1") && !modelLower.startsWith("o3") && !modelLower.startsWith("o4")) {
                put("max_tokens", maxTokens)
            }

            val messagesArray = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }

            for (msg in messages) {
                when (msg.role) {
                    "tool" -> {
                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", msg.toolCallId ?: "")
                            put("content", msg.textContent ?: "{}")
                        })
                    }
                    "assistant" if msg.toolCalls.isNotEmpty() -> {
                        messagesArray.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", msg.textContent ?: JSONObject.NULL)
                            val tcArr = JSONArray()
                            msg.toolCalls.forEach { tc ->
                                tcArr.put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    })
                                })
                            }
                            put("tool_calls", tcArr)
                        })
                    }
                    else -> {
                        if (!msg.imageBase64.isNullOrBlank() && msg.role == "user") {
                            val parts = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.textContent ?: "")
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${msg.imageBase64}"))
                                })
                            }
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", parts)
                            })
                        } else {
                            messagesArray.put(JSONObject().apply {
                                put("role", msg.role)
                                put("content", msg.textContent ?: "")
                            })
                        }
                    }
                }
            }
            put("messages", messagesArray)

            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                tools.forEach { tool ->
                    toolsArr.put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parametersSchema.toJsonSchema(uppercaseTypes = false))
                        })
                    })
                }
                put("tools", toolsArr)
            }
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        if (stream) {
            requestBuilder.addHeader("Accept", "text/event-stream")
        }
        if (config.baseUrl.contains("openrouter.ai")) {
            requestBuilder.addHeader("HTTP-Referer", "https://github.com/nexa-ai-android")
            requestBuilder.addHeader("X-Title", "NOVA AI")
        }
        return requestBuilder.build()
    }

    override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean {
        sendMessage(config, apiKey, "Hi", listOf(ChatMessage(role = "user", content = "Test")))
        return true
    }
}

/**
 * Anthropic Claude Provider (Messages API + SSE Streaming)
 */
class AnthropicProvider(
    private val client: OkHttpClient,
    private val streamClient: OkHttpClient
) : AiProvider {

    override suspend fun sendMessage(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>
    ): String {
        val turnMsgs = history.map { AgentTurnMessage(role = it.role, textContent = it.content) }
        val response = sendAgentTurn(config, apiKey, systemPrompt, turnMsgs, emptyList())
        return response.text ?: throw ResponseParseException("রেসপন্স পার্স করা যায়নি")
    }

    override suspend fun sendAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse = withContext(Dispatchers.IO) {
        val useTools = config.supportsTools && tools.isNotEmpty()
        try {
            executeAnthropicBlocking(config, apiKey, systemPrompt, messages, if (useTools) tools else emptyList(), maxTokens)
        } catch (_: ToolUnsupportedHttpException) {
            val fallback = executeAnthropicBlocking(config, apiKey, systemPrompt, messages, emptyList(), maxTokens)
            fallback.copy(toolsNotSupported = true)
        }
    }

    override fun streamAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): Flow<AiStreamEvent> = flow {
        val effectiveTools = if (config.supportsTools && tools.isNotEmpty()) tools else emptyList()
        if (!config.supportsStreaming) {
            val res = sendAgentTurn(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens)
            emit(AiStreamEvent.Completed(res, res.metrics))
            return@flow
        }

        try {
            executeAnthropicStream(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens) { emit(it) }
        } catch (_: ToolUnsupportedHttpException) {
            executeAnthropicStream(config, apiKey, systemPrompt, messages, emptyList(), maxTokens, toolsFallback = true) {
                emit(it)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeAnthropicStream(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?,
        toolsFallback: Boolean = false,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val totalStart = monoMs()
        val request = buildAnthropicRequest(config, apiKey, systemPrompt, messages, tools, stream = true, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val acc = AnthropicSseAccumulator()
        val call = streamClient.newCall(request)
        val ctx = currentCoroutineContext()
        val cancelHook = ctx[Job]?.invokeOnCompletion { call.cancel() }
        var ttftMs = 0L
        val netStart = monoMs()

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, errBody)) {
                        throw ToolUnsupportedHttpException(errBody)
                    }
                    throw ApiHttpException(
                        statusCode = resp.code,
                        responseBody = errBody,
                        retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                        providerType = ProviderType.ANTHROPIC
                    )
                }

                val body = resp.body ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
                SseStreamParser.readSseEventsSuspend(body.source()) { eventType, payload ->
                    ctx.ensureActive()
                    val (delta, done) = acc.feed(eventType, payload)
                    if (!delta.isNullOrEmpty()) {
                        if (ttftMs == 0L) ttftMs = monoMs() - netStart
                        emitEvent(AiStreamEvent.TextDelta(delta))
                    } else if (ttftMs == 0L && !done) {
                        ttftMs = monoMs() - netStart
                    }
                    !done
                }
            }
        } catch (e: IOException) {
            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException("Stream cancelled")
            val partial = acc.currentText()
            if (partial.isNotBlank() && !call.isCanceled()) {
                throw PartialStreamException(partial, e)
            }
            throw e
        } finally {
            cancelHook?.dispose()
            call.cancel()
        }

        val totalMs = monoMs() - totalStart
        if (ttftMs == 0L) ttftMs = totalMs
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = true
        )
        logLatencyMetrics(ProviderType.ANTHROPIC, metrics)
        val built = acc.buildResponse().copy(toolsNotSupported = toolsFallback, metrics = metrics)
        emitEvent(AiStreamEvent.Completed(built, metrics))
    }

    private fun executeAnthropicBlocking(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse {
        val totalStart = monoMs()
        val request = buildAnthropicRequest(config, apiKey, systemPrompt, messages, tools, stream = false, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val netStart = monoMs()
        val response = client.newCall(request).execute()
        val ttftMs = monoMs() - netStart
        val responseString = response.use { resp ->
            val str = resp.body?.string() ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
            if (!resp.isSuccessful) {
                if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, str)) {
                    throw ToolUnsupportedHttpException(str)
                }
                throw ApiHttpException(
                    statusCode = resp.code,
                    responseBody = str,
                    retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                    providerType = ProviderType.ANTHROPIC
                )
            }
            str
        }

        val root = JSONObject(responseString)
        val contentArray = root.getJSONArray("content")
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCallRequest>()

        for (j in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(j)
            when (block.optString("type")) {
                "text" -> {
                    val t = block.optString("text", "")
                    if (t.isNotBlank()) textParts.add(t)
                }
                "tool_use" -> {
                    val inputObj = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.add(
                        ToolCallRequest(
                            id = block.optString("id", UUID.randomUUID().toString()),
                            name = block.getString("name"),
                            argumentsJson = inputObj.toString()
                        )
                    )
                }
            }
        }

        val totalMs = monoMs() - totalStart
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = false
        )
        logLatencyMetrics(ProviderType.ANTHROPIC, metrics)
        return AiTurnResponse(
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            rawAssistantParts = contentArray,
            rawPartsProvider = ProviderType.ANTHROPIC,
            metrics = metrics
        )
    }

    private fun buildAnthropicRequest(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        maxTokens: Int?
    ): Request {
        val url = if (config.baseUrl.endsWith("/")) "${config.baseUrl}messages" else "${config.baseUrl}/messages"
        val jsonBody = JSONObject().apply {
            put("model", config.modelName)
            put("max_tokens", maxTokens ?: 2048)
            put("temperature", config.temperature.toDouble())
            if (stream) put("stream", true)
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }

            val messagesArray = JSONArray()
            var i = 0
            while (i < messages.size) {
                val msg = messages[i]
                when {
                    msg.role == "tool" -> {
                        val toolResultBlocks = JSONArray()
                        while (i < messages.size && messages[i].role == "tool") {
                            val tm = messages[i]
                            toolResultBlocks.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", tm.toolCallId ?: "")
                                put("content", tm.textContent ?: "{}")
                            })
                            i++
                        }
                        messagesArray.put(JSONObject().apply {
                            put("role", "user")
                            put("content", toolResultBlocks)
                        })
                        continue
                    }
                    msg.role == "assistant" && msg.rawParts != null && msg.rawPartsProvider == ProviderType.ANTHROPIC -> {
                        messagesArray.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", msg.rawParts)
                        })
                    }
                    msg.role == "assistant" && msg.toolCalls.isNotEmpty() -> {
                        val blocks = JSONArray()
                        val txt = msg.textContent
                        if (!txt.isNullOrBlank()) {
                            blocks.put(JSONObject().apply {
                                put("type", "text")
                                put("text", txt)
                            })
                        }
                        msg.toolCalls.forEach { tc ->
                            val inputObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
                            blocks.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", inputObj)
                            })
                        }
                        messagesArray.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", blocks)
                        })
                    }
                    else -> {
                        val role = if (msg.role == "system") "user" else msg.role
                        if (!msg.imageBase64.isNullOrBlank() && role == "user") {
                            val blocks = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", msg.imageBase64)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.textContent ?: "")
                                })
                            }
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", blocks)
                            })
                        } else {
                            messagesArray.put(JSONObject().apply {
                                put("role", role)
                                put("content", msg.textContent ?: "")
                            })
                        }
                    }
                }
                i++
            }
            put("messages", messagesArray)

            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                tools.forEach { tool ->
                    toolsArr.put(JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parametersSchema.toJsonSchema(uppercaseTypes = false))
                    })
                }
                put("tools", toolsArr)
            }
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .apply { if (stream) addHeader("accept", "text/event-stream") }
            .build()
    }

    override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean {
        sendMessage(config, apiKey, "Hi", listOf(ChatMessage(role = "user", content = "Test")))
        return true
    }
}

/**
 * Google Gemini Provider (generateContent + streamGenerateContent?alt=sse)
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val streamClient: OkHttpClient
) : AiProvider {

    override suspend fun sendMessage(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>
    ): String {
        val turnMsgs = history.map { AgentTurnMessage(role = it.role, textContent = it.content) }
        val response = sendAgentTurn(config, apiKey, systemPrompt, turnMsgs, emptyList())
        return response.text ?: throw ResponseParseException("Gemini রেসপন্স থেকে টেক্সট পাওয়া যায়নি")
    }

    override suspend fun sendAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse = withContext(Dispatchers.IO) {
        val useTools = config.supportsTools && tools.isNotEmpty()
        try {
            executeGeminiBlocking(config, apiKey, systemPrompt, messages, if (useTools) tools else emptyList(), maxTokens)
        } catch (_: ToolUnsupportedHttpException) {
            val fallback = executeGeminiBlocking(config, apiKey, systemPrompt, messages, emptyList(), maxTokens)
            fallback.copy(toolsNotSupported = true)
        }
    }

    override fun streamAgentTurn(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): Flow<AiStreamEvent> = flow {
        val effectiveTools = if (config.supportsTools && tools.isNotEmpty()) tools else emptyList()
        if (!config.supportsStreaming) {
            val res = sendAgentTurn(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens)
            emit(AiStreamEvent.Completed(res, res.metrics))
            return@flow
        }

        try {
            executeGeminiStream(config, apiKey, systemPrompt, messages, effectiveTools, maxTokens) { emit(it) }
        } catch (_: ToolUnsupportedHttpException) {
            executeGeminiStream(config, apiKey, systemPrompt, messages, emptyList(), maxTokens, toolsFallback = true) {
                emit(it)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeGeminiStream(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?,
        toolsFallback: Boolean = false,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val totalStart = monoMs()
        val request = buildGeminiRequest(config, apiKey, systemPrompt, messages, tools, stream = true, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val acc = GeminiSseAccumulator()
        val call = streamClient.newCall(request)
        val ctx = currentCoroutineContext()
        val cancelHook = ctx[Job]?.invokeOnCompletion { call.cancel() }
        var ttftMs = 0L
        val netStart = monoMs()

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, errBody)) {
                        throw ToolUnsupportedHttpException(errBody)
                    }
                    throw ApiHttpException(
                        statusCode = resp.code,
                        responseBody = errBody,
                        retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                        providerType = ProviderType.GEMINI
                    )
                }

                val body = resp.body ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
                SseStreamParser.readSseEventsSuspend(body.source()) { _, payload ->
                    ctx.ensureActive()
                    val delta = acc.feed(payload)
                    if (!delta.isNullOrEmpty()) {
                        if (ttftMs == 0L) ttftMs = monoMs() - netStart
                        emitEvent(AiStreamEvent.TextDelta(delta))
                    } else if (ttftMs == 0L) {
                        ttftMs = monoMs() - netStart
                    }
                    true
                }
            }
        } catch (e: IOException) {
            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException("Stream cancelled")
            val partial = acc.currentText()
            if (partial.isNotBlank() && !call.isCanceled()) {
                throw PartialStreamException(partial, e)
            }
            throw e
        } finally {
            cancelHook?.dispose()
            call.cancel()
        }

        val totalMs = monoMs() - totalStart
        if (ttftMs == 0L) ttftMs = totalMs
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = true
        )
        logLatencyMetrics(ProviderType.GEMINI, metrics)
        val built = acc.buildResponse().copy(toolsNotSupported = toolsFallback, metrics = metrics)
        emitEvent(AiStreamEvent.Completed(built, metrics))
    }

    private fun executeGeminiBlocking(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int?
    ): AiTurnResponse {
        val totalStart = monoMs()
        val request = buildGeminiRequest(config, apiKey, systemPrompt, messages, tools, stream = false, maxTokens = maxTokens)
        val buildMs = monoMs() - totalStart
        val estTokens = PromptAndToolOptimizer.estimatePayloadTokens(systemPrompt, messages, tools)

        val netStart = monoMs()
        val response = client.newCall(request).execute()
        val ttftMs = monoMs() - netStart
        val responseString = response.use { resp ->
            val str = resp.body?.string() ?: throw Exception("ফাঁকা রেসপন্স এসেছে")
            if (!resp.isSuccessful) {
                if (tools.isNotEmpty() && isToolsUnsupportedError(resp.code, str)) {
                    throw ToolUnsupportedHttpException(str)
                }
                throw ApiHttpException(
                    statusCode = resp.code,
                    responseBody = str,
                    retryAfterSeconds = RetryAfterParser.parse(resp.header("Retry-After")),
                    providerType = ProviderType.GEMINI
                )
            }
            str
        }

        val root = JSONObject(responseString)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw Exception("Gemini রেসপন্স থেকে কোনো উত্তর পাওয়া যায়নি")
        }

        val contentObj = candidates.getJSONObject(0).optJSONObject("content")
        val parts = contentObj?.optJSONArray("parts") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCallRequest>()

        for (j in 0 until parts.length()) {
            val part = parts.getJSONObject(j)
            if (part.has("text") && !part.optBoolean("thought", false)) {
                val t = part.optString("text", "")
                if (t.isNotBlank()) textParts.add(t)
            }
            if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                val argsObj = fc.optJSONObject("args") ?: JSONObject()
                toolCalls.add(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = fc.getString("name"),
                        argumentsJson = argsObj.toString()
                    )
                )
            }
        }

        val totalMs = monoMs() - totalStart
        val metrics = RequestLatencyMetrics(
            requestBuildMs = buildMs,
            firstTokenMs = ttftMs,
            totalMs = totalMs,
            messageCount = messages.size,
            estimatedInputTokens = estTokens,
            toolCount = tools.size,
            usedStreaming = false
        )
        logLatencyMetrics(ProviderType.GEMINI, metrics)
        return AiTurnResponse(
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            rawAssistantParts = parts,
            rawPartsProvider = ProviderType.GEMINI,
            metrics = metrics
        )
    }

    private fun buildGeminiRequest(
        config: ModelConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<ToolDefinition>,
        stream: Boolean,
        maxTokens: Int?
    ): Request {
        val modelName = config.modelName.ifBlank { "gemini-2.5-flash" }
        val cleanBase = config.baseUrl.trimEnd('/')
        val url = if (stream) {
            "$cleanBase/models/$modelName:streamGenerateContent?alt=sse&key=$apiKey"
        } else {
            "$cleanBase/models/$modelName:generateContent?key=$apiKey"
        }

        val jsonBody = JSONObject().apply {
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            }

            val contentsArray = JSONArray()
            var i = 0
            while (i < messages.size) {
                val msg = messages[i]
                when {
                    msg.role == "tool" -> {
                        val partsArr = JSONArray()
                        while (i < messages.size && messages[i].role == "tool") {
                            val tm = messages[i]
                            val respObj = runCatching { JSONObject(tm.textContent ?: "{}") }
                                .getOrElse { JSONObject().put("result", tm.textContent ?: "") }
                            partsArr.put(JSONObject().apply {
                                put("functionResponse", JSONObject().apply {
                                    put("name", tm.toolName ?: "unknown_tool")
                                    put("response", respObj)
                                })
                            })
                            i++
                        }
                        contentsArray.put(JSONObject().apply {
                            put("role", "user")
                            put("parts", partsArr)
                        })
                        continue
                    }
                    msg.role == "assistant" && msg.rawParts != null && msg.rawPartsProvider == ProviderType.GEMINI -> {
                        contentsArray.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", msg.rawParts)
                        })
                    }
                    msg.role == "assistant" && msg.toolCalls.isNotEmpty() -> {
                        val partsArr = JSONArray()
                        val txt = msg.textContent
                        if (!txt.isNullOrBlank()) {
                            partsArr.put(JSONObject().put("text", txt))
                        }
                        msg.toolCalls.forEach { tc ->
                            val argsObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
                            partsArr.put(JSONObject().apply {
                                put("functionCall", JSONObject().apply {
                                    put("name", tc.name)
                                    put("args", argsObj)
                                })
                            })
                        }
                        contentsArray.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", partsArr)
                        })
                    }
                    else -> {
                        val geminiRole = if (msg.role == "assistant") "model" else "user"
                        val parts = JSONArray().apply {
                            put(JSONObject().put("text", msg.textContent ?: ""))
                            if (!msg.imageBase64.isNullOrBlank() && geminiRole == "user") {
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", "image/jpeg")
                                        put("data", msg.imageBase64)
                                    })
                                })
                            }
                        }
                        contentsArray.put(JSONObject().apply {
                            put("role", geminiRole)
                            put("parts", parts)
                        })
                    }
                }
                i++
            }
            put("contents", contentsArray)

            if (tools.isNotEmpty()) {
                val funcDecls = JSONArray()
                tools.forEach { tool ->
                    funcDecls.put(JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parametersSchema.toJsonSchema(uppercaseTypes = true))
                    })
                }
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", funcDecls)))
            }

            put("generationConfig", JSONObject().apply {
                put("temperature", config.temperature.toDouble())
                put("maxOutputTokens", maxTokens ?: 2048)
            })
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .apply { if (stream) addHeader("Accept", "text/event-stream") }
            .build()
    }

    override suspend fun testConnection(config: ModelConfig, apiKey: String): Boolean {
        sendMessage(config, apiKey, "Hi", listOf(ChatMessage(role = "user", content = "Test")))
        return true
    }
}

private fun isToolsUnsupportedError(code: Int, body: String): Boolean {
    if (code != 400 && code != 422 && code != 501) return false
    val lower = body.lowercase()
    return lower.contains("tool") ||
        lower.contains("function") ||
        lower.contains("unsupported") ||
        lower.contains("not support")
}

private class ToolUnsupportedHttpException(message: String) : Exception(message)

class ResponseParseException(message: String) : Exception(message)
