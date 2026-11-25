package com.example

import android.util.Base64
import com.example.voice.live.FailureKind
import com.example.voice.live.LiveConstants
import com.example.voice.live.LiveProtocol
import com.example.voice.live.LiveServerEvent
import com.example.voice.live.LiveSetupConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json and android.util.Base64 need Robolectric on the JVM (same as the other JSON tests).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveProtocolTest {

    private fun parse(json: String) = LiveProtocol.parseServerMessage(json)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // ---- parser ----

    @Test fun malformedOrEmpty_yieldsNoEvents_neverThrows() {
        assertTrue(parse("").isEmpty()); assertTrue(parse("not json").isEmpty()); assertTrue(parse("[]").isEmpty()); assertTrue(parse("{}").isEmpty())
    }

    @Test fun setupComplete() = assertEquals(listOf<LiveServerEvent>(LiveServerEvent.SetupComplete), parse("""{"setupComplete":{}}"""))

    @Test fun errorPayload_isParsed() {
        val e = parse("""{"error":{"code":403,"status":"PERMISSION_DENIED","message":"API key not valid"}}""").single() as LiveServerEvent.ServerError
        assertEquals(403, e.code); assertEquals("PERMISSION_DENIED", e.status)
    }

    @Test fun audioPart_isDecoded_andOddByteDropped() {
        val pcm = ByteArray(33) { (it + 1).toByte() } // odd length
        val ev = parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${b64(pcm)}"}}]}}}""")
        val audio = ev.single() as LiveServerEvent.Audio
        assertEquals(32, audio.pcm24k.size)
        assertEquals(1.toByte(), audio.pcm24k[0])
    }

    @Test fun tinyAudio_isIgnored() {
        assertTrue(parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"${b64(ByteArray(8))}"}}]}}}""").isEmpty())
    }

    @Test fun badBase64_isIgnored_notFatal() {
        assertTrue(parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"@@@@@@@@@@@@@@"}}]}}}""").none { it is LiveServerEvent.Audio })
    }

    @Test fun thoughtParts_areNeverSurfaced() {
        val ev = parse("""{"serverContent":{"modelTurn":{"parts":[{"text":"**Planning**","thought":true},{"text":"Hello"}]}}}""")
        assertEquals(listOf<LiveServerEvent>(LiveServerEvent.ModelText("Hello")), ev)
    }

    @Test fun legitimateBoldTextIsKept_unlikeTheOldKeywordHeuristic() {
        val ev = parse("""{"serverContent":{"modelTurn":{"parts":[{"text":"**Greeting** cards are on sale"}]}}}""")
        assertEquals(listOf<LiveServerEvent>(LiveServerEvent.ModelText("**Greeting** cards are on sale")), ev)
    }

    @Test fun modelText_isSuppressedWhenOutputTranscriptionPresent_toAvoidDuplicates() {
        val ev = parse("""{"serverContent":{"outputTranscription":{"text":"Hi there"},"modelTurn":{"parts":[{"text":"Hi there"}]}}}""")
        assertEquals(listOf<LiveServerEvent>(LiveServerEvent.ModelTranscript("Hi there")), ev)
    }

    @Test fun inputTranscription_textAndPartsForms() {
        assertEquals(LiveServerEvent.UserTranscript("open maps"), parse("""{"serverContent":{"inputTranscription":{"text":"open maps"}}}""").single())
        assertEquals(LiveServerEvent.UserTranscript("ab"), parse("""{"serverContent":{"inputTranscription":{"parts":[{"text":"a"},{"text":"b"}]}}}""").single())
    }

    @Test fun interruptedAndCompletionFlags_orderedAfterContent() {
        val ev = parse("""{"serverContent":{"interrupted":true,"generationComplete":true,"turnComplete":true}}""")
        assertEquals(listOf(LiveServerEvent.Interrupted, LiveServerEvent.GenerationComplete, LiveServerEvent.TurnComplete), ev)
    }

    @Test fun toolCall_topLevel_andNestedInServerContent() {
        val top = parse("""{"toolCall":{"functionCalls":[{"id":"1","name":"f","args":{"x":1}}]}}""").single() as LiveServerEvent.ToolCall
        assertEquals("f", top.name); assertEquals(1, top.args.getInt("x"))
        val nested = parse("""{"serverContent":{"toolCall":{"functionCalls":[{"id":"2","name":"g"}]}}}""").single() as LiveServerEvent.ToolCall
        assertEquals("2", nested.id); assertEquals(0, nested.args.length())
        assertTrue(parse("""{"toolCall":{"functionCalls":[{"id":"3","name":""}]}}""").isEmpty())
    }

    @Test fun resumptionHandle_andGoAway() {
        assertEquals(LiveServerEvent.ResumptionHandle("h1", true), parse("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""").single())
        assertEquals(LiveServerEvent.GoAway(30_000), parse("""{"goAway":{"timeLeft":"30s"}}""").single())
    }

    @Test fun durationParsing() {
        assertEquals(1500L, LiveProtocol.parseDurationMs("1.5s")); assertEquals(250L, LiveProtocol.parseDurationMs("250ms"))
        assertEquals(0L, LiveProtocol.parseDurationMs("")); assertEquals(0L, LiveProtocol.parseDurationMs("abc"))
    }

    // ---- builders ----

    @Test fun setup_hasExpectedShape_andNoToolsByDefault() {
        val o = JSONObject(LiveProtocol.buildSetup(LiveSetupConfig(model = "models/m", voiceName = "Kore", systemPrompt = "be brief"), null)).getJSONObject("setup")
        assertEquals("models/m", o.getString("model"))
        assertEquals("AUDIO", o.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", o.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertEquals("be brief", o.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertFalse(o.has("tools")); assertTrue(o.has("inputAudioTranscription")); assertTrue(o.has("outputAudioTranscription"))
        assertFalse(o.getJSONObject("sessionResumption").has("handle"))
    }

    @Test fun setup_carriesResumeHandle_andToolDeclarations() {
        val tool = JSONObject().put("name", "t")
        val o = JSONObject(LiveProtocol.buildSetup(LiveSetupConfig(tools = listOf(tool)), "H")).getJSONObject("setup")
        assertEquals("H", o.getJSONObject("sessionResumption").getString("handle"))
        assertEquals("t", o.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
    }

    @Test fun setup_blankPromptOmitsSystemInstruction() {
        assertFalse(JSONObject(LiveProtocol.buildSetup(LiveSetupConfig(systemPrompt = "  "), null)).getJSONObject("setup").has("systemInstruction"))
    }

    @Test fun audioInput_roundTrips() {
        val pcm = ByteArray(640) { it.toByte() }
        val a = JSONObject(LiveProtocol.buildAudioInput(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals(LiveConstants.INPUT_MIME, a.getString("mimeType"))
        assertEquals(pcm.toList(), Base64.decode(a.getString("data"), Base64.DEFAULT).toList())
    }

    @Test fun toolResponseAndClientText_shapes() {
        val t = JSONObject(LiveProtocol.buildToolResponse("7", "f", JSONObject().put("ok", true))).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("7", t.getString("id")); assertTrue(t.getJSONObject("response").getBoolean("ok"))
        val c = JSONObject(LiveProtocol.buildClientText("hi")).getJSONObject("clientContent")
        assertTrue(c.getBoolean("turnComplete")); assertEquals("hi", c.getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertTrue(JSONObject(LiveProtocol.buildAudioStreamEnd()).getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }

    // ---- failure classification ----

    @Test fun classify_authQuotaModelTransient() {
        assertEquals(FailureKind.AUTH_INVALID, LiveProtocol.classifyFailure(403, 0, ""))
        assertEquals(FailureKind.AUTH_INVALID, LiveProtocol.classifyFailure(401, 0, ""))
        assertEquals(FailureKind.AUTH_INVALID, LiveProtocol.classifyFailure(400, 0, "API key not valid. Please pass a valid API key."))
        assertEquals(FailureKind.AUTH_INVALID, LiveProtocol.classifyFailure(0, 1007, "API_KEY_INVALID"))
        assertEquals(FailureKind.QUOTA, LiveProtocol.classifyFailure(429, 0, ""))
        assertEquals(FailureKind.QUOTA, LiveProtocol.classifyFailure(0, 1011, "RESOURCE_EXHAUSTED: quota"))
        assertEquals(FailureKind.MODEL_REJECTED, LiveProtocol.classifyFailure(0, 1008, "models/x is not found for API version v1beta, or is not supported for bidiGenerateContent"))
        assertEquals(FailureKind.MODEL_REJECTED, LiveProtocol.classifyFailure(404, 0, ""))
    }

    @Test fun classify_plainBadRequestOrNetworkErrorsAreNotKeyErrors() {
        // The old JARVIS logic treated any HTTP 400 or any text containing "400"/"API key" as an invalid key.
        assertEquals(FailureKind.TRANSIENT, LiveProtocol.classifyFailure(400, 0, "bad request"))
        assertEquals(FailureKind.TRANSIENT, LiveProtocol.classifyFailure(0, 1006, "abnormal closure"))
        assertEquals(FailureKind.TRANSIENT, LiveProtocol.classifyFailure(503, 0, "unavailable"))
        assertEquals(FailureKind.TRANSIENT, LiveProtocol.classifyFailure(0, 1000, "Session renewal"))
    }
}
