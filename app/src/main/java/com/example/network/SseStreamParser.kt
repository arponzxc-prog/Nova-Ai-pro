package com.example.network

import com.example.model.ProviderType
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.UUID

/**
 * তিন প্রোভাইডারের (OpenAI, Anthropic, Gemini) SSE স্ট্রিম পার্সার ও অ্যাকিউমুলেটর।
 * লাইন-বাই-লাইন SSE ফ্রেমিং পড়ে প্রতিটি টোকেন বা ফাইনাল টুল-কল একত্র করে।
 */
object SseStreamParser {

    /**
     * OkHttp BufferedSource থেকে SSE ইভেন্ট পড়ে প্রতি (eventName, dataPayload) জোড়ায় কলব্যাক দেয়।
     */
    fun readSseEvents(
        source: BufferedSource,
        onEvent: (eventType: String?, data: String) -> Boolean // false ফিরালে লুপ থামবে
    ) {
        var currentEvent: String? = null
        val dataBuilder = StringBuilder()

        fun dispatchBuffered(): Boolean {
            if (dataBuilder.isEmpty()) {
                currentEvent = null
                return true
            }
            val payload = dataBuilder.toString().trimEnd('\n')
            dataBuilder.setLength(0)
            val evt = currentEvent
            currentEvent = null
            if (payload.isBlank()) return true
            return onEvent(evt, payload)
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (!dispatchBuffered()) break
                }
                line.startsWith(":") -> {
                    // SSE কমেন্ট/কিপ-অ্যালাইভ লাইন উপেক্ষা করা
                }
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val chunk = line.removePrefix("data:").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    if (chunk.trim() == "[DONE]") {
                        if (dataBuilder.isNotEmpty()) {
                            if (!dispatchBuffered()) break
                        }
                        onEvent(currentEvent, "[DONE]")
                        break
                    }
                    if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                    dataBuilder.append(chunk)
                }
            }
        }
        // স্ট্রিম EOF-এ কোনো অবশিষ্ট ডাটা থাকলে তা প্রসেস করা
        if (dataBuilder.isNotEmpty()) {
            dispatchBuffered()
        }
    }

    /**
     * কো-রুটিন-বান্ধব SSE রিডার: সরাসরি suspend কলব্যাক ব্যবহার করে Flow-তে টোকেন পাঠায়।
     */
    suspend fun readSseEventsSuspend(
        source: BufferedSource,
        onEvent: suspend (eventType: String?, data: String) -> Boolean
    ) {
        var currentEvent: String? = null
        val dataBuilder = StringBuilder()

        suspend fun dispatchBuffered(): Boolean {
            if (dataBuilder.isEmpty()) {
                currentEvent = null
                return true
            }
            val payload = dataBuilder.toString().trimEnd('\n')
            dataBuilder.setLength(0)
            val evt = currentEvent
            currentEvent = null
            if (payload.isBlank()) return true
            return onEvent(evt, payload)
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (!dispatchBuffered()) break
                }
                line.startsWith(":") -> {
                }
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val chunk = line.removePrefix("data:").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    if (chunk.trim() == "[DONE]") {
                        if (dataBuilder.isNotEmpty()) {
                            if (!dispatchBuffered()) break
                        }
                        onEvent(currentEvent, "[DONE]")
                        break
                    }
                    if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                    dataBuilder.append(chunk)
                }
            }
        }
        if (dataBuilder.isNotEmpty()) {
            dispatchBuffered()
        }
    }
}

/**
 * OpenAI-compatible SSE স্ট্রিম অ্যাকিউমুলেটর (text delta + chunked tool_calls)।
 */
class OpenAiSseAccumulator {
    private data class PartialToolCall(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder()
    )

    private val textBuilder = StringBuilder()
    private val toolCallsMap = TreeMap<Int, PartialToolCall>()

    fun currentText(): String = textBuilder.toString()

    /**
     * @return নতুন টেক্সট ডেল্টা (থাকলে), বা null। `[DONE]` পেলে Pair(null, true) ফেরে।
     */
    fun feed(dataPayload: String): Pair<String?, Boolean> {
        val trimmed = dataPayload.trim()
        if (trimmed == "[DONE]") return null to true
        if (trimmed.isEmpty()) return null to false

        val root = JSONObject(trimmed)
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message").orEmpty().ifBlank { root.optString("error") }
            val codeNum = errObj?.optInt("code", 0)?.takeIf { it in 400..599 }
                ?: mapStreamErrorTypeToHttpCode(errObj?.optString("type").orEmpty(), msg)
            throw ApiHttpException(codeNum, msg, providerType = ProviderType.OPENAI)
        }

        val choices = root.optJSONArray("choices") ?: return null to false
        if (choices.length() == 0) return null to false

        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null to false

        var emittedDelta: String? = null
        if (delta.has("content") && !delta.isNull("content")) {
            val piece = delta.optString("content", "")
            if (piece.isNotEmpty()) {
                textBuilder.append(piece)
                emittedDelta = piece
            }
        }

        val tcArr = delta.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.getJSONObject(i)
                val idx = tc.optInt("index", i)
                val entry = toolCallsMap.getOrPut(idx) { PartialToolCall(idx) }

                if (tc.has("id") && !tc.isNull("id")) {
                    val idPart = tc.optString("id", "")
                    if (idPart.isNotBlank()) entry.id = idPart
                }
                val fn = tc.optJSONObject("function")
                if (fn != null) {
                    if (fn.has("name") && !fn.isNull("name")) {
                        val namePart = fn.optString("name", "")
                        if (namePart.isNotEmpty() && entry.name.isEmpty()) {
                            entry.name = namePart
                        }
                    }
                    if (fn.has("arguments") && !fn.isNull("arguments")) {
                        val argChunk = fn.optString("arguments", "")
                        if (argChunk.isNotEmpty()) {
                            entry.args.append(argChunk)
                        }
                    }
                }
            }
        }
        return emittedDelta to false
    }

    fun buildResponse(): AiTurnResponse {
        val calls = toolCallsMap.values
            .filter { it.name.isNotBlank() }
            .map {
                ToolCallRequest(
                    id = it.id.ifBlank { UUID.randomUUID().toString() },
                    name = it.name,
                    argumentsJson = it.args.toString().ifBlank { "{}" }
                )
            }
        return AiTurnResponse(
            text = textBuilder.toString().takeIf { it.isNotBlank() },
            toolCalls = calls
        )
    }
}

/**
 * Anthropic Messages API SSE অ্যাকিউমুলেটর (text_delta + input_json_delta)।
 */
class AnthropicSseAccumulator {
    private sealed class BlockState(val index: Int) {
        class TextBlock(index: Int, val text: StringBuilder = StringBuilder()) : BlockState(index)
        class ToolUseBlock(
            index: Int,
            val id: String,
            val name: String,
            val partialJson: StringBuilder = StringBuilder()
        ) : BlockState(index)
    }

    private val blocks = TreeMap<Int, BlockState>()

    fun currentText(): String {
        return blocks.values
            .filterIsInstance<BlockState.TextBlock>()
            .joinToString("\n") { it.text.toString() }
    }

    fun feed(eventType: String?, dataPayload: String): Pair<String?, Boolean> {
        val trimmed = dataPayload.trim()
        if (trimmed.isEmpty()) return null to false
        val root = JSONObject(trimmed)
        val type = eventType?.takeIf { it.isNotBlank() } ?: root.optString("type", "")

        when (type) {
            "error" -> {
                val errObj = root.optJSONObject("error")
                val errType = errObj?.optString("type").orEmpty()
                val msg = errObj?.optString("message").orEmpty().ifBlank { trimmed }
                val codeNum = mapStreamErrorTypeToHttpCode(errType, msg)
                throw ApiHttpException(codeNum, msg, providerType = ProviderType.ANTHROPIC)
            }
            "message_stop" -> return null to true
            "content_block_start" -> {
                val idx = root.optInt("index", blocks.size)
                val cb = root.optJSONObject("content_block") ?: return null to false
                when (cb.optString("type")) {
                    "text" -> {
                        val initial = cb.optString("text", "")
                        val tb = BlockState.TextBlock(idx)
                        if (initial.isNotEmpty()) tb.text.append(initial)
                        blocks[idx] = tb
                        return initial.takeIf { it.isNotEmpty() } to false
                    }
                    "tool_use" -> {
                        val id = cb.optString("id", UUID.randomUUID().toString())
                        val name = cb.optString("name", "")
                        val tu = BlockState.ToolUseBlock(idx, id, name)
                        val initialInput = cb.optJSONObject("input")
                        if (initialInput != null && initialInput.length() > 0) {
                            tu.partialJson.append(initialInput.toString())
                        }
                        blocks[idx] = tu
                    }
                }
            }
            "content_block_delta" -> {
                val idx = root.optInt("index", 0)
                val delta = root.optJSONObject("delta") ?: return null to false
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val txt = delta.optString("text", "")
                        if (txt.isNotEmpty()) {
                            val tb = blocks.getOrPut(idx) { BlockState.TextBlock(idx) } as? BlockState.TextBlock
                            tb?.text?.append(txt)
                            return txt to false
                        }
                    }
                    "input_json_delta" -> {
                        val pj = delta.optString("partial_json", "")
                        if (pj.isNotEmpty()) {
                            val tu = blocks[idx] as? BlockState.ToolUseBlock
                            tu?.partialJson?.append(pj)
                        }
                    }
                }
            }
        }
        return null to false
    }

    fun buildResponse(): AiTurnResponse {
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCallRequest>()
        val rawParts = JSONArray()

        for (block in blocks.values) {
            when (block) {
                is BlockState.TextBlock -> {
                    val str = block.text.toString()
                    if (str.isNotBlank()) {
                        textParts.add(str)
                        rawParts.put(JSONObject().apply {
                            put("type", "text")
                            put("text", str)
                        })
                    }
                }
                is BlockState.ToolUseBlock -> {
                    if (block.name.isNotBlank()) {
                        val rawJson = block.partialJson.toString().ifBlank { "{}" }
                        val parsedInput = runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }
                        toolCalls.add(
                            ToolCallRequest(
                                id = block.id,
                                name = block.name,
                                argumentsJson = parsedInput.toString()
                            )
                        )
                        rawParts.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", block.id)
                            put("name", block.name)
                            put("input", parsedInput)
                        })
                    }
                }
            }
        }

        return AiTurnResponse(
            text = textParts.joinToString("\n").takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            rawAssistantParts = rawParts,
            rawPartsProvider = ProviderType.ANTHROPIC
        )
    }
}

/**
 * Gemini `streamGenerateContent?alt=sse` অ্যাকিউমুলেটর।
 */
class GeminiSseAccumulator {
    private val textBuilder = StringBuilder()
    private val toolCalls = mutableListOf<ToolCallRequest>()
    private val nonTextParts = mutableListOf<JSONObject>()

    fun currentText(): String = textBuilder.toString()

    fun feed(dataPayload: String): String? {
        val trimmed = dataPayload.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return null

        val root = JSONObject(trimmed)
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message").orEmpty().ifBlank { trimmed }
            val codeNum = errObj?.optInt("code", 0)?.takeIf { it in 400..599 }
                ?: mapStreamErrorTypeToHttpCode(errObj?.optString("status").orEmpty(), msg)
            throw ApiHttpException(codeNum, msg, providerType = ProviderType.GEMINI)
        }

        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null

        val contentObj = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = contentObj.optJSONArray("parts") ?: return null

        val chunkDelta = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val isThought = part.optBoolean("thought", false)
            if (part.has("text") && !isThought) {
                val t = part.optString("text", "")
                if (t.isNotEmpty()) {
                    textBuilder.append(t)
                    chunkDelta.append(t)
                }
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
                nonTextParts.add(part)
            } else if (isThought) {
                nonTextParts.add(part)
            }
        }
        return chunkDelta.toString().takeIf { it.isNotEmpty() }
    }

    fun buildResponse(): AiTurnResponse {
        val combinedText = textBuilder.toString()
        val rawParts = JSONArray().apply {
            if (combinedText.isNotEmpty()) {
                put(JSONObject().put("text", combinedText))
            }
            nonTextParts.forEach { put(it) }
        }
        return AiTurnResponse(
            text = combinedText.takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            rawAssistantParts = rawParts,
            rawPartsProvider = ProviderType.GEMINI
        )
    }
}

private fun mapStreamErrorTypeToHttpCode(typeOrStatus: String, message: String): Int {
    val combined = "${typeOrStatus.lowercase()} ${message.lowercase()}"
    return when {
        combined.contains("rate_limit") || combined.contains("resource_exhausted") || combined.contains("quota") -> 429
        combined.contains("overloaded") || combined.contains("unavailable") -> 503
        combined.contains("authentication") || combined.contains("invalid_api_key") || combined.contains("unauthorized") -> 401
        combined.contains("permission") -> 403
        combined.contains("not_found") -> 404
        combined.contains("billing") || combined.contains("credit") || combined.contains("payment") -> 402
        else -> 500
    }
}
