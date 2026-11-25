package com.example

import android.graphics.Rect
import com.example.accessibility.ScreenSnapshot
import com.example.accessibility.SnapshotNodeRef
import com.example.automation.agent.ActionOutcome
import com.example.automation.agent.ActionVerifier
import com.example.automation.agent.ScreenSignatureCalculator
import com.example.automation.agent.SerializedActionExecutor
import com.example.automation.command.CommandParser
import com.example.automation.contacts.ContactRanking
import com.example.automation.contacts.MatchTier
import com.example.automation.contacts.NamedNumber
import com.example.automation.core.AutomationFeature
import com.example.automation.core.AutomationSelectors
import com.example.automation.core.AutomationSettings
import com.example.automation.core.AutomationSwitches
import com.example.automation.core.UiMatchers
import com.example.automation.phone.DomainNormalizer
import com.example.automation.phone.UrlConfidence
import com.example.automation.youtube.YouTubeApiClient
import com.example.tools.RiskLevel
import com.example.tools.ToolRegistry
import com.example.tools.WeatherParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomationCoreTest {

    // ---------------- CommandParser ----------------
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun parser_flashlight() {
        assertEquals(true, p("turn on the flashlight")!!.args.getBoolean("enabled"))
        assertEquals(false, p("Hey NEXA, turn off torch.")!!.args.getBoolean("enabled"))
        assertEquals("toggle_flashlight", p("flashlight on")!!.toolName)
        assertEquals(true, p("টর্চ চালু করো")!!.args.getBoolean("enabled"))
        assertNull(p("turn the light"))
    }

    @Test fun parser_brightnessAndVolume() {
        assertEquals(40, p("set brightness to 40%")!!.args.getInt("level_percent"))
        assertEquals(100, p("max brightness")!!.args.getInt("level_percent"))
        assertNull(p("brightness up")) // relative brightness is not expressible by NEXA's tool -> model handles it
        val v = p("set volume to 30")!!
        assertEquals("set_volume", v.toolName); assertEquals("set", v.args.getString("action")); assertEquals(30, v.args.getInt("level_percent"))
        assertEquals("up", p("volume up")!!.args.getString("action"))
        assertEquals("down", p("make it quieter")!!.args.getString("action"))
        assertEquals("mute", p("mute")!!.args.getString("action"))
        assertNull(p("set volume to 500"))
    }

    @Test fun parser_mediaKeys() {
        assertEquals("pause", p("pause the music")!!.args.getString("action"))
        assertEquals("play", p("resume playback")!!.args.getString("action"))
        assertEquals("next", p("next song")!!.args.getString("action"))
        assertEquals("previous", p("previous track")!!.args.getString("action"))
        assertNull(p("go back")) // ambiguous with navigation: deliberately not a media command
    }

    @Test fun parser_youtube() {
        assertEquals("youtube_subscribe", p("subscribe")!!.toolName)
        assertEquals("youtube_fullscreen", p("make it fullscreen")!!.toolName)
        val play = p("play Believer by Imagine Dragons on YouTube")!!
        assertEquals("youtube_play", play.toolName); assertEquals("Believer by Imagine Dragons", play.args.getString("query"))
        assertEquals("youtube_search", p("search lofi beats on youtube")!!.toolName)
        assertNull(p("play it on youtube")) // vague title must not be guessed
        assertNull(p("play something on youtube"))
    }

    @Test fun parser_phoneCall_contactVsNumber_andWhatsAppExclusion() {
        assertEquals("rahim", p("call rahim")!!.args.getString("contact_name"))
        assertEquals("+8801712345678", p("dial +880 1712 345678")!!.args.getString("phone_number"))
        assertEquals("আম্মু", p("call আম্মু")!!.args.getString("contact_name"))
        assertNull(p("call him"))
        assertEquals("whatsapp_call", p("call rahim on whatsapp")!!.toolName)
    }

    @Test fun parser_whatsApp() {
        val call = p("whatsapp video call karim")!!
        assertEquals("whatsapp_call", call.toolName); assertTrue(call.args.getBoolean("video")); assertEquals("karim", call.args.getString("contact_name"))
        assertFalse(p("whatsapp call karim")!!.args.getBoolean("video"))
        val msg = p("send whatsapp message to Rahim saying I will be late")!!
        assertEquals("send_whatsapp_message", msg.toolName)
        assertEquals("Rahim", msg.args.getString("contact_name")); assertEquals("I will be late", msg.args.getString("message"))
        assertNull(p("whatsapp rahim")) // no separator/body: never guess where the name ends
        assertNull(p("message rahim saying hi")) // SMS vs WhatsApp is ambiguous without the word whatsapp
    }

    @Test fun parser_whatsAppDelete_needsContact_defaultsToForMe() {
        val forMe = p("delete my last whatsapp message to rahim")!!
        assertEquals("whatsapp_delete_message", forMe.toolName); assertFalse(forMe.args.getBoolean("for_everyone")); assertEquals("rahim", forMe.args.getString("contact_name"))
        val all = p("delete the last message for everyone to rahim")!!
        assertTrue(all.args.getBoolean("for_everyone"))
        assertNull(p("delete message")); assertNull(p("delete the last message for everyone"))
    }

    @Test fun parser_webAndApps() {
        assertEquals("open_url", p("open github.com and vercel.com")!!.toolName)
        assertEquals("open_app", p("open youtube")!!.toolName) // no domain hint -> the app
        assertEquals("youtube", p("open youtube")!!.args.getString("app_name"))
        assertEquals("open_url", p("google best pizza near me")!!.toolName)
        assertEquals("best pizza near me", p("google best pizza near me")!!.args.getString("query"))
        assertNull(p("tell me a joke")); assertNull(p("")); assertNull(p("x".repeat(400)))
    }

    @Test fun parser_neverProducesAnythingBelowThreshold_andOnlyKnownTools() {
        val known = ToolRegistry.allTools.map { it.name }.toSet()
        listOf("turn on flashlight", "set volume to 10", "pause", "play x song name on youtube", "call mom", "open settings", "google cats",
            "whatsapp call ali", "send whatsapp to ali saying hello there").forEach {
            val c = p(it)!!
            assertTrue(c.confidence >= CommandParser.CONFIDENCE_THRESHOLD)
            assertTrue("${c.toolName} not registered", c.toolName in known)
            assertNull("args of ${c.toolName} invalid: $it", ToolRegistry.validateArguments(ToolRegistry.getTool(c.toolName)!!, c.args))
        }
    }

    @Test fun ambiguousPlayQuery() {
        assertTrue(CommandParser.isAmbiguousPlayQuery("it")); assertTrue(CommandParser.isAmbiguousPlayQuery("  "))
        assertTrue(CommandParser.isAmbiguousPlayQuery("song")); assertTrue(CommandParser.isAmbiguousPlayQuery("yo"))
        assertFalse(CommandParser.isAmbiguousPlayQuery("Believer"))
    }

    // ---------------- DomainNormalizer ----------------
    @Test fun domain_aliasExplicitFuzzyGuessed() {
        assertEquals(UrlConfidence.EXPLICIT, DomainNormalizer.resolve("example.org/path")!!.confidence)
        assertEquals("https://example.org/path", DomainNormalizer.resolve("example.org/path")!!.url)
        assertEquals(UrlConfidence.EXPLICIT, DomainNormalizer.resolve("http://example.com")!!.confidence)
        assertEquals("https://example.com", DomainNormalizer.resolve("http://example.com")!!.url) // upgraded to https
        assertEquals(UrlConfidence.GUESSED, DomainNormalizer.resolve("somerandomsite")!!.confidence)
        assertEquals("https://somerandomsite.com", DomainNormalizer.resolve("somerandomsite")!!.url)
        assertNull(DomainNormalizer.resolve("")); assertNull(DomainNormalizer.resolve("two words here"))
    }

    @Test fun domain_rejectsNonWebSchemes() {
        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://contacts", "data:text/html,hi", "tel:123", "http://", "https:///evil").forEach {
            assertNull("must reject $it", DomainNormalizer.resolve(it))
        }
    }

    @Test fun domain_realHostIsNeverRewrittenToALookAlike() {
        assertEquals("https://githup.com", DomainNormalizer.resolve("githup.com")!!.url)
    }

    @Test fun domain_multipleUrls_dedupe() {
        val r = DomainNormalizer.extractMultipleUrls("open vercel.com and github.com and vercel.com")
        assertEquals(listOf("https://vercel.com", "https://github.com"), r.map { it.url })
        assertTrue(DomainNormalizer.extractMultipleUrls("open nexa please").none { it.url.startsWith("javascript") })
    }

    // ---------------- ContactRanking ----------------
    private val book = listOf(NamedNumber("Rahim Uddin", "01711"), NamedNumber("Rahman Khan", "01812"), NamedNumber("Mom", "01913"), NamedNumber("Sumi", "01614"))

    @Test fun contacts_tiers() {
        assertEquals(MatchTier.EXACT, ContactRanking.rank("sumi", book).single().tier)
        assertEquals("Mom", ContactRanking.rank("mother", book).single().contact.name)
        assertEquals(MatchTier.NICKNAME, ContactRanking.rank("mother", book).single().tier)
        assertEquals(2, ContactRanking.rank("rah", book).size) // word-prefix tier: ambiguity is returned, never auto-picked
        assertEquals(MatchTier.WORD, ContactRanking.rank("rah", book).first().tier)
    }

    @Test fun contacts_fuzzyTypoIsCandidateOnly_andUnrelatedYieldsNothing() {
        val r = ContactRanking.rank("sumy", book)
        assertEquals("Sumi", r.single().contact.name); assertEquals(MatchTier.FUZZY, r.single().tier)
        assertTrue(ContactRanking.rank("zzzzzz", book).isEmpty())
        assertTrue(ContactRanking.rank("", book).isEmpty()); assertTrue(ContactRanking.rank("x", emptyList()).isEmpty())
        assertTrue(ContactRanking.rank("abcdefghij", book + book.map { it.copy(name = it.name + " x") }).size <= ContactRanking.FUZZY_MAX_RESULTS)
    }

    @Test fun contacts_levenshtein() {
        assertEquals(0, ContactRanking.levenshtein("a", "a")); assertEquals(3, ContactRanking.levenshtein("kitten", "sitting"))
        assertEquals(1f, ContactRanking.similarity("rahim", "rahim"), 0f)
    }

    // ---------------- Screen signature / verifier ----------------
    private fun el(id: Int, text: String = "", desc: String = "", viewId: String? = null, clickable: Boolean = false, editable: Boolean = false, pw: Boolean = false, r: Rect = Rect(0, id * 100, 100, id * 100 + 90)) =
        SnapshotNodeRef(id, "View", text, desc, viewId, r, clickable, editable, false, null, pw)

    private fun snap(vararg e: SnapshotNodeRef, pkg: String = "p.a", label: String = "Main") =
        ScreenSnapshot(pkg, label, e.toList(), "", false, false)

    @Test fun signature_sameScreenSameHash_changeDetected_passwordTextIgnored() {
        val a = ScreenSignatureCalculator.compute(snap(el(1, "Hello"), el(2, "Go", clickable = true)))
        val b = ScreenSignatureCalculator.compute(snap(el(1, "Hello"), el(2, "Go", clickable = true)))
        assertTrue(a.isEquivalentTo(b))
        assertFalse(a.isEquivalentTo(ScreenSignatureCalculator.compute(snap(el(1, "Hello!"), el(2, "Go", clickable = true)))))
        assertFalse(a.isEquivalentTo(ScreenSignatureCalculator.compute(snap(el(1, "Hello"), el(2, "Go", clickable = true), pkg = "p.b"))))
        val pw1 = ScreenSignatureCalculator.compute(snap(el(1, "secret1", editable = true, pw = true)))
        val pw2 = ScreenSignatureCalculator.compute(snap(el(1, "secret2", editable = true, pw = true)))
        assertTrue(pw1.isEquivalentTo(pw2)); assertTrue(pw1.primaryTexts.isEmpty())
        assertEquals(ScreenSignatureCalculator.EMPTY, ScreenSignatureCalculator.compute(null))
    }

    @Test fun verifier_typing_clickNeverOverclaims_scroll() {
        val ok = snap(el(1, "hello world", editable = true))
        assertTrue(ActionVerifier.verifyTyping(ok, "hello   world").isVerified)
        assertFalse(ActionVerifier.verifyTyping(snap(el(1, "other", editable = true)), "hello").isVerified)
        assertFalse(ActionVerifier.verifyTyping(snap(el(1, "hello", editable = true), el(2, "", editable = true)), "hello", targetElementId = 2).isVerified)
        assertFalse(ActionVerifier.verifyTyping(snap(el(1, "hello", editable = true, pw = true)), "hello").isVerified) // never "verify" via a password field
        assertFalse(ActionVerifier.verifyTyping(null, "x").isVerified)
        val pre = ScreenSignatureCalculator.compute(snap(el(1, "a")))
        val changed = ScreenSignatureCalculator.compute(snap(el(1, "b")))
        assertFalse(ActionVerifier.verifyClick(pre, changed).isVerified); assertTrue(ActionVerifier.verifyClick(pre, changed).stateChanged)
        assertTrue(ActionVerifier.verifyClick(pre, pre).isRetryable)
        assertTrue(ActionVerifier.verifyScroll(pre, changed).isVerified); assertFalse(ActionVerifier.verifyScroll(pre, pre).isVerified)
        assertTrue(ActionVerifier.screenShowsAny(snap(el(1, "Subscribed")), listOf("subscribed")))
        assertFalse(ActionVerifier.screenShowsAny(snap(el(1, "x", pw = true)), listOf("x")))
    }

    // ---------------- SerializedActionExecutor ----------------
    @Test fun executor_serializesAndDistinguishesOutcomes() = runBlocking {
        SerializedActionExecutor.startNewTask()
        val order = ArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val first = async { SerializedActionExecutor.execute { order += 1; gate.await(); order += 2 } }
        delay(50)
        val second = async { SerializedActionExecutor.execute { order += 3 } }
        delay(50); assertEquals(listOf(1), order) // second waits for the first
        gate.complete(Unit)
        assertTrue(first.await() is ActionOutcome.Completed); assertTrue(second.await() is ActionOutcome.Completed)
        assertEquals(listOf(1, 2, 3), order)

        val nullResult = SerializedActionExecutor.execute<String?>(timeoutMs = 500) { null }
        assertEquals(ActionOutcome.Completed<String?>(null), nullResult) // a null result is NOT a timeout
        assertEquals(ActionOutcome.TimedOut, SerializedActionExecutor.execute(timeoutMs = 50) { delay(1000) })
        assertTrue(SerializedActionExecutor.execute<Unit> { error("x") } is ActionOutcome.Failed)
        SerializedActionExecutor.cancelAll()
        assertEquals(ActionOutcome.Rejected, SerializedActionExecutor.execute { 1 })
        SerializedActionExecutor.startNewTask()
        assertTrue(SerializedActionExecutor.execute { 1 } is ActionOutcome.Completed)
    }

    // ---------------- Kill switch / toggles ----------------
    @Test fun killSwitch_blocksEveryAutomationTool_andNothingElse() {
        val killed = AutomationSettings(killSwitch = true)
        AutomationSwitches.TOOL_FEATURE.keys.forEach { assertNotNull("$it must be blocked", AutomationSwitches.blockReason(it, killed)) }
        listOf("web_search", "remember_fact", "set_alarm", "get_current_time").forEach { assertNull(AutomationSwitches.blockReason(it, killed)) }
        assertNull(AutomationSwitches.blockReason("make_call", AutomationSettings()))
    }

    @Test fun featureToggle_blocksOnlyThatFeature() {
        val s = AutomationSettings(enabledFeatures = AutomationFeature.entries.toSet() - AutomationFeature.YOUTUBE)
        assertNotNull(AutomationSwitches.blockReason("youtube_play", s)); assertNull(AutomationSwitches.blockReason("whatsapp_call", s))
    }

    @Test fun everyTableEntryIsARegisteredTool_andNewHighRiskToolsAreHigh() {
        val names = ToolRegistry.allTools.map { it.name }.toSet()
        AutomationSwitches.TOOL_FEATURE.keys.forEach { assertTrue("$it missing from registry", it in names) }
        listOf("whatsapp_call", "whatsapp_delete_message", "youtube_subscribe").forEach { assertEquals(RiskLevel.HIGH, ToolRegistry.getTool(it)!!.riskLevel) }
        assertEquals(names.size, ToolRegistry.allTools.size) // unique names
    }

    @Test fun guardedExecutor_refusesWhenKilled_evenIfCalledDirectly() = runBlocking {
        AutomationSwitches.update(AutomationSettings(killSwitch = true))
        try {
            val tool = ToolRegistry.getTool("media_control")!!
            val r = tool.executor(com.example.tools.ToolExecutionContext(androidx.test.core.app.ApplicationProvider.getApplicationContext()), JSONObject().put("action", "pause"))
            assertFalse(r.success)
        } finally { AutomationSwitches.update(AutomationSettings()) }
    }

    // ---------------- Tool argument validation ----------------
    @Test fun toolValidation_newTools() {
        val wa = ToolRegistry.getTool("whatsapp_call")!!
        assertNotNull(ToolRegistry.validateArguments(wa, JSONObject()))
        assertNull(ToolRegistry.validateArguments(wa, JSONObject().put("contact_name", "Rahim")))
        val del = ToolRegistry.getTool("whatsapp_delete_message")!!
        assertNotNull(ToolRegistry.validateArguments(del, JSONObject().put("for_everyone", true))) // never "delete in whatever chat is open"
        val url = ToolRegistry.getTool("open_url")!!
        assertNotNull(ToolRegistry.validateArguments(url, JSONObject()))
        assertNull(ToolRegistry.validateArguments(url, JSONObject().put("query", "cats")))
        val media = ToolRegistry.getTool("media_control")!!
        assertNotNull(ToolRegistry.validateArguments(media, JSONObject()))
        assertNull(ToolRegistry.validateArguments(media, JSONObject().put("action", "pause")))
    }

    // ---------------- UiMatchers / selectors ----------------
    @Test fun matchers_exactVsContains_exclusions_outgoingBubble() {
        val els = listOf(el(1, "Subscribed", clickable = true), el(2, "Subscribe", clickable = true), el(3, "", desc = "Voice call", clickable = true))
        assertEquals(2, UiMatchers.findByLabel(els, listOf("subscribe"), exact = true, exclude = listOf("subscribed"))!!.id)
        assertEquals(1, UiMatchers.findByLabel(els, listOf("subscribe"), exact = false)!!.id) // contains matches "Subscribed" first: why callers use exact
        assertNull(UiMatchers.findByLabel(els, listOf("call"), exact = true)); assertEquals(3, UiMatchers.findByLabel(els, listOf("voice call"), exact = true)!!.id)
        assertNull(UiMatchers.findByLabel(listOf(el(1, "x", pw = true)), listOf("x")))

        val ids = listOf("com.whatsapp:id/message_text")
        val chat = listOf(
            el(1, "in 1", viewId = ids[0], r = Rect(20, 100, 400, 160)),
            el(2, "out 1", viewId = ids[0], r = Rect(600, 200, 1000, 260)),
            el(3, "in 2", viewId = ids[0], r = Rect(20, 300, 400, 360)),
            el(4, "out 2", viewId = ids[0], r = Rect(600, 400, 1000, 460))
        )
        assertEquals("out 2", UiMatchers.lastOutgoingBubble(chat, ids)!!.text)
        assertNull(UiMatchers.lastOutgoingBubble(chat.filter { it.text.startsWith("in") }, ids)) // returns null rather than guessing
        assertTrue(UiMatchers.titleMatchesContact("Rahim Uddin", "rahim")); assertFalse(UiMatchers.titleMatchesContact("Karim", "rahim"))
        assertFalse(UiMatchers.titleMatchesContact("anything", " "))
    }

    @Test fun selectors_overridesAreValidated() {
        val good = AutomationSelectors.parseOverrides("""{"youtube_subscribe_texts":["abo","  "],"evil_key":["x"],"whatsapp_delete_ids":"notalist"}""")
        assertEquals(listOf("abo"), good["youtube_subscribe_texts"]); assertFalse(good.containsKey("evil_key")); assertFalse(good.containsKey("whatsapp_delete_ids"))
        assertTrue(AutomationSelectors.parseOverrides("not json").isEmpty()); assertTrue(AutomationSelectors.parseOverrides(null).isEmpty())
        assertTrue(AutomationSelectors.get("whatsapp_voice_call_ids").isNotEmpty())
    }

    // ---------------- parsers ----------------
    @Test fun youtubeParser_acceptsOnlyWellFormedIds() {
        val ok = YouTubeApiClient.parseFirstVideo("""{"items":[{"id":{"videoId":"dQw4w9WgXcQ"},"snippet":{"title":"T","channelTitle":"C"}}]}""")!!
        assertEquals("dQw4w9WgXcQ", ok.id); assertEquals("C", ok.channel)
        assertNull(YouTubeApiClient.parseFirstVideo("""{"items":[{"id":{"videoId":"../../x?y"}}]}"""))
        assertNull(YouTubeApiClient.parseFirstVideo("""{"items":[]}""")); assertNull(YouTubeApiClient.parseFirstVideo("garbage"))
    }

    @Test fun weatherParser() {
        val w = WeatherParser.parse("""{"current":{"temperature_2m":31.4,"apparent_temperature":36.0,"relative_humidity_2m":70,"weather_code":61},"daily":{"temperature_2m_max":[33.0],"temperature_2m_min":[26.0]}}""")!!
        assertEquals(31.4, w.tempC, 0.01); assertEquals(70, w.humidity); assertEquals(33.0, w.maxC!!, 0.01)
        assertEquals("বৃষ্টি", WeatherParser.describe(w.code))
        assertNull(WeatherParser.parse("{}")); assertNull(WeatherParser.parse("x"))
    }
}
