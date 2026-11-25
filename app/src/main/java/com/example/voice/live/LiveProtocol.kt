/*
 * Derived from the JARVIS project (com.jarvis.assistant.network.GeminiLiveWebSocket and
 * com.jarvis.assistant.data.model.GeminiLiveModels). Original attribution and license information:
 * see THIRD_PARTY_NOTICES.md. Rewritten for NEXA AI as a pure, testable protocol layer (message builders,
 * server-message parser, failure classifier); camelCase wire format; session resumption and goAway support;
 * no phone-control tool declarations. Modifications © ARPON.
 */
package com.example.voice.live

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Wire constants for the Gemini Live (BidiGenerateContent) WebSocket API. */
object LiveConstants {
    const val WS_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val API_KEY_HEADER = "x-goog-api-key"

    const val INPUT_MIME = "audio/pcm;rate=16000"
    const val OUTPUT_SAMPLE_RATE = 24_000

    /**
     * Default model. Model availability changes (preview models are discontinued), so this is only a default:
     * the user can change it in settings. Verify the current Live model id in Google AI Studio before release.
     */
    const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_VOICE = "Puck"
    val SUPPORTED_VOICES = listOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
}

/** Everything the setup message needs. [tools] is empty in Step 1 (phone-control tools arrive in Step 2). */
data class LiveSetupConfig(
    val model: String = LiveConstants.DEFAULT_MODEL,
    val voiceName: String = LiveConstants.DEFAULT_VOICE,
    val systemPrompt: String = "",
    val temperature: Double = 0.6,
    val tools: List<JSONObject> = emptyList()
)

/** Parsed server → client events. */
sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class Audio(val pcm24k: ByteArray) : LiveServerEvent {
        override fun equals(other: Any?) = other is Audio && pcm24k.contentEquals(other.pcm24k)
        override fun hashCode() = pcm24k.contentHashCode()
    }
    data class UserTranscript(val text: String) : LiveServerEvent
    data class ModelTranscript(val text: String) : LiveServerEvent
    /** Plain model text that is not a transcription of the audio and not a "thought". */
    data class ModelText(val text: String) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object GenerationComplete : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class ToolCall(val id: String, val name: String, val args: JSONObject) : LiveServerEvent
    data class ResumptionHandle(val handle: String, val resumable: Boolean) : LiveServerEvent
    data class GoAway(val timeLeftMs: Long) : LiveServerEvent
    data class ServerError(val code: Int, val status: String, val message: String) : LiveServerEvent
}

/** Why a connection ended, as far as we can tell from the HTTP status / close code / text. */
enum class FailureKind { AUTH_INVALID, QUOTA, MODEL_REJECTED, TRANSIENT }

object LiveProtocol {

    // ---- client → server ------------------------------------------------------------------

    fun buildSetup(config: LiveSetupConfig, resumeHandle: String?): String {
        val setup = JSONObject().apply {
            put("model", config.model)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("temperature", config.temperature)
                put("speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", config.voiceName)
                    )
                ))
            })
            if (config.systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", config.systemPrompt))
                ))
            }
            if (config.tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(config.tools))))
            }
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("sessionResumption", JSONObject().apply {
                if (!resumeHandle.isNullOrBlank()) put("handle", resumeHandle)
            })
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun buildAudioInput(pcm16k: ByteArray): String {
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        return JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject().put("mimeType", LiveConstants.INPUT_MIME).put("data", b64)
            )
        ).toString()
    }

    fun buildAudioStreamEnd(): String =
        JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    fun buildClientText(text: String): String =
        JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
                ))
                .put("turnComplete", true)
        ).toString()

    fun buildToolResponse(callId: String, name: String, result: JSONObject): String =
        JSONObject().put(
            "toolResponse", JSONObject().put(
                "functionResponses", JSONArray().put(
                    JSONObject().put("id", callId).put("name", name).put("response", result)
                )
            )
        ).toString()

    // ---- server → client ------------------------------------------------------------------

    /** Never throws; malformed input yields an empty list. */
    fun parseServerMessage(raw: String): List<LiveServerEvent> {
        val json = try { JSONObject(raw) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<LiveServerEvent>(4)

        json.optJSONObject("error")?.let { err ->
            out += LiveServerEvent.ServerError(
                code = err.optInt("code", 0),
                status = err.optString("status", ""),
                message = err.optString("message", "")
            )
            return out
        }
        if (json.has("setupComplete")) { out += LiveServerEvent.SetupComplete; return out }

        json.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val handle = u.optString("newHandle", "")
            out += LiveServerEvent.ResumptionHandle(handle, u.optBoolean("resumable", handle.isNotEmpty()))
        }
        json.optJSONObject("goAway")?.let { g ->
            out += LiveServerEvent.GoAway(parseDurationMs(g.optString("timeLeft", "")))
        }
        json.optJSONObject("toolCall")?.let { tc -> out += parseToolCalls(tc) }

        val sc = json.optJSONObject("serverContent") ?: return out
        sc.optJSONObject("toolCall")?.let { tc -> out += parseToolCalls(tc) }

        extractTranscript(sc.optJSONObject("inputTranscription"))?.let { out += LiveServerEvent.UserTranscript(it) }
        val outputText = extractTranscript(sc.optJSONObject("outputTranscription"))
        if (!outputText.isNullOrEmpty()) out += LiveServerEvent.ModelTranscript(outputText)

        if (sc.optBoolean("interrupted", false)) out += LiveServerEvent.Interrupted

        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue // internal reasoning is never surfaced
                val text = part.optString("text", "")
                if (text.isNotEmpty() && outputText.isNullOrEmpty()) out += LiveServerEvent.ModelText(text)
                val data = part.optJSONObject("inlineData")?.optString("data", "").orEmpty()
                if (data.length > 8) {
                    val pcm = try { Base64.decode(data, Base64.DEFAULT) } catch (_: IllegalArgumentException) { null }
                    if (pcm != null && pcm.size >= 16) {
                        out += LiveServerEvent.Audio(com.example.audio.AudioUtils.alignToSampleBoundary(pcm))
                    }
                }
            }
        }
        if (sc.optBoolean("generationComplete", false)) out += LiveServerEvent.GenerationComplete
        if (sc.optBoolean("turnComplete", false)) out += LiveServerEvent.TurnComplete
        return out
    }

    private fun parseToolCalls(obj: JSONObject): List<LiveServerEvent.ToolCall> {
        val calls = obj.optJSONArray("functionCalls") ?: return emptyList()
        val list = ArrayList<LiveServerEvent.ToolCall>(calls.length())
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val name = c.optString("name", "")
            if (name.isEmpty()) continue
            list += LiveServerEvent.ToolCall(c.optString("id", ""), name, c.optJSONObject("args") ?: JSONObject())
        }
        return list
    }

    private fun extractTranscript(obj: JSONObject?): String? {
        obj ?: return null
        val direct = obj.optString("text", "")
        if (direct.isNotEmpty()) return direct
        val parts = obj.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
        return sb.toString().ifEmpty { null }
    }

    /** "30s", "1.5s", "250ms" → milliseconds; unknown formats → 0. */
    fun parseDurationMs(text: String): Long {
        val t = text.trim()
        return try {
            when {
                t.endsWith("ms") -> t.removeSuffix("ms").toDouble().toLong()
                t.endsWith("s") -> (t.removeSuffix("s").toDouble() * 1000).toLong()
                else -> 0L
            }
        } catch (_: NumberFormatException) { 0L }
    }

    // ---- failure classification -----------------------------------------------------------

    /**
     * @param httpCode HTTP status of a failed WebSocket handshake, or 0 if there was none.
     * @param closeCode WebSocket close code, or 0.
     * @param text server-provided reason / response-body snippet (may be empty). Never logged by callers.
     */
    fun classifyFailure(httpCode: Int, closeCode: Int, text: String): FailureKind {
        val t = text.lowercase()
        val keyMarker = "api_key_invalid" in t || "api key not valid" in t || "api key expired" in t ||
            "invalid api key" in t || "permission_denied" in t
        val quotaMarker = "resource_exhausted" in t || "quota" in t
        return when {
            httpCode == 401 || httpCode == 403 -> FailureKind.AUTH_INVALID
            httpCode == 400 && keyMarker -> FailureKind.AUTH_INVALID
            keyMarker -> FailureKind.AUTH_INVALID
            httpCode == 429 || quotaMarker -> FailureKind.QUOTA
            closeCode == 1008 && ("not found" in t || "not supported" in t || "bidigeneratecontent" in t) ->
                FailureKind.MODEL_REJECTED
            httpCode == 404 -> FailureKind.MODEL_REJECTED
            else -> FailureKind.TRANSIENT
        }
    }
}
