package com.example

import com.example.data.DuplicatePolicy
import com.example.data.ImportFailure
import com.example.data.ImportIssue
import com.example.data.ImportedModelEntry
import com.example.data.ModelImportParseResult
import com.example.data.ModelImportParser
import com.example.data.ModelImportPlanner
import com.example.data.PlannedAction
import com.example.model.ModelConfig
import com.example.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json Android-এ stub, তাই Robolectric রানার ব্যবহার করা হয়েছে (বাকি টেস্টের মতো)।
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelImportTest {

    private fun success(json: String): ModelImportParseResult.Success {
        val r = ModelImportParser.parse(json)
        assertTrue("expected success but was $r", r is ModelImportParseResult.Success)
        return r as ModelImportParseResult.Success
    }

    private fun failure(json: String): ModelImportParseResult.Failure {
        val r = ModelImportParser.parse(json)
        assertTrue("expected failure but was $r", r is ModelImportParseResult.Failure)
        return r as ModelImportParseResult.Failure
    }

    // ---------------- parser ----------------

    @Test
    fun parsesRootArray() {
        val r = success(
            """[
              {"name":"A","provider":"gemini","modelName":"gemini-2.5-flash","apiKey":"k1"},
              {"name":"B","provider":"openai","baseUrl":"https://openrouter.ai/api/v1/","modelName":"x/y","apiKey":"k2",
               "temperature":1.2,"supportsTools":false,"supportsVision":true,"supportsStreaming":false}
            ]"""
        )
        assertEquals(2, r.totalFound)
        assertEquals(2, r.entries.size)
        assertTrue(r.rejected.isEmpty())
        assertEquals(ProviderType.GEMINI.defaultBaseUrl, r.entries[0].baseUrl)
        val b = r.entries[1]
        assertEquals("https://openrouter.ai/api/v1", b.baseUrl) // trailing slash trimmed
        assertEquals(1.2f, b.temperature!!, 0.0001f)
        assertEquals(false, b.supportsTools)
        assertEquals(true, b.supportsVision)
        assertEquals(false, b.supportsStreaming)
    }

    @Test
    fun parsesModelsObjectAndSingleObjectAndBom() {
        assertEquals(1, success("""{"models":[{"provider":"claude","model":"claude-x","api_key":"k"}]}""").entries.size)
        val single = success("\uFEFF" + """{"provider":"anthropic","modelId":"m1"}""")
        assertEquals(ProviderType.ANTHROPIC, single.entries[0].providerType)
        assertEquals("m1", single.entries[0].name) // name defaults to modelName
        assertEquals("", single.entries[0].apiKey)
    }

    @Test
    fun fileLevelFailures() {
        assertEquals(ImportFailure.EMPTY_FILE, failure("   ").reason)
        assertEquals(ImportFailure.NOT_JSON, failure("hello").reason)
        assertEquals(ImportFailure.NOT_JSON, failure("{ broken").reason)
        assertEquals(ImportFailure.UNSUPPORTED_STRUCTURE, failure("""{"foo":1}""").reason)
        assertEquals(ImportFailure.NO_MODELS, failure("[]").reason)
    }

    @Test
    fun allEntriesInvalidFailsWithReasons() {
        val f = failure("""[1, {"provider":"openai"}]""")
        assertEquals(ImportFailure.NO_VALID_ENTRIES, f.reason)
        assertEquals(ImportIssue.NOT_AN_OBJECT, f.rejected[0].issue)
        assertEquals(ImportIssue.MISSING_MODEL_NAME, f.rejected[1].issue)
    }

    @Test
    fun entryValidationRules() {
        val r = success(
            """[
              {"modelName":"ok","provider":"openai"},
              {"modelName":"no-provider-no-url"},
              {"modelName":"unknown","provider":"mystery"},
              {"modelName":"groq-no-url","provider":"groq"},
              {"modelName":"bad-url","provider":"openai","baseUrl":"ftp://x"},
              {"modelName":"bad-temp","provider":"openai","temperature":5},
              {"modelName":"bad-key","provider":"openai","apiKey":"has space"},
              {"modelName":"infer","provider":"groq","baseUrl":"https://api.groq.com/openai/v1"}
            ]"""
        )
        assertEquals(8, r.totalFound)
        assertEquals(listOf("ok", "infer"), r.entries.map { it.modelName })
        assertEquals(ProviderType.OPENAI, r.entries[1].providerType)
        assertEquals(
            listOf(
                ImportIssue.MISSING_PROVIDER_AND_URL,
                ImportIssue.UNKNOWN_PROVIDER,
                ImportIssue.UNKNOWN_PROVIDER, // groq: URL ছাড়া ভুল এন্ডপয়েন্টে Key যাওয়া এড়াতে বাতিল
                ImportIssue.INVALID_URL,
                ImportIssue.INVALID_TEMPERATURE,
                ImportIssue.INVALID_API_KEY
            ),
            r.rejected.map { it.issue }
        )
        // মোট = সফল + ব্যর্থ
        assertEquals(r.totalFound, r.entries.size + r.rejected.size)
    }

    @Test
    fun infersProviderFromUrlHost() {
        val r = success(
            """[
              {"modelName":"c","provider":"custom","baseUrl":"https://api.anthropic.com/v1"},
              {"modelName":"g","provider":"custom","baseUrl":"https://generativelanguage.googleapis.com/v1beta"}
            ]"""
        )
        assertEquals(ProviderType.ANTHROPIC, r.entries[0].providerType)
        assertEquals(ProviderType.GEMINI, r.entries[1].providerType)
    }

    @Test
    fun apiKeyNeverAppearsInToStringOrRejectedLabels() {
        val secret = "sk-SUPERSECRET123456"
        val r = success("""[{"name":"N","provider":"openai","modelName":"m","apiKey":"$secret"}]""")
        assertFalse(r.entries[0].toString().contains(secret))
        assertFalse(r.toString().contains(secret))
    }

    // ---------------- planner ----------------

    private fun entry(
        name: String = "N",
        model: String = "m",
        url: String = "https://api.openai.com/v1",
        key: String = "",
        type: ProviderType = ProviderType.OPENAI,
        index: Int = 1
    ) = ImportedModelEntry(index, name, type, url, model, key, null, null, null, null)

    private fun existing(id: String, name: String = "N", model: String = "m", url: String = "https://api.openai.com/v1") =
        ModelConfig(id = id, name = name, providerType = ProviderType.OPENAI, baseUrl = url, modelName = model)

    @Test
    fun plannerAddsNewAndDetectsExactDuplicate() {
        val plan = ModelImportPlanner.plan(
            listOf(entry(name = "n"), entry(name = "Other", index = 2)),
            listOf(existing("e1", url = "https://api.openai.com/v1/")), // নাম/URL-এর কেস ও স্ল্যাশ উপেক্ষিত
            emptyMap(),
            DuplicatePolicy.SKIP
        )
        assertEquals(PlannedAction.SKIP_DUPLICATE, plan[0].action)
        assertEquals("e1", plan[0].existingId)
        assertEquals(PlannedAction.ADD, plan[1].action) // ভিন্ন নাম = আলাদা কনফিগ (একাধিক Key)
    }

    @Test
    fun plannerUpdatePolicyAndKeyBasedMatch() {
        val keyed = ModelImportPlanner.plan(
            listOf(entry(name = "Renamed", key = "same-key")),
            listOf(existing("e1")),
            mapOf("e1" to "same-key"),
            DuplicatePolicy.UPDATE
        )
        assertEquals(PlannedAction.UPDATE, keyed[0].action)
        assertEquals("e1", keyed[0].existingId)

        val differentKey = ModelImportPlanner.plan(
            listOf(entry(name = "Renamed", key = "other-key")),
            listOf(existing("e1")),
            mapOf("e1" to "same-key"),
            DuplicatePolicy.UPDATE
        )
        assertEquals(PlannedAction.ADD, differentKey[0].action)
    }

    @Test
    fun plannerSkipsRepeatsInsideFileAndNeverDoubleUpdatesOneExisting() {
        val repeat = ModelImportPlanner.plan(
            listOf(entry(), entry(index = 2)),
            emptyList(),
            emptyMap(),
            DuplicatePolicy.SKIP
        )
        assertEquals(PlannedAction.ADD, repeat[0].action)
        assertEquals(PlannedAction.SKIP_REPEAT_IN_FILE, repeat[1].action)
        assertNull(repeat[1].existingId)

        val claimed = ModelImportPlanner.plan(
            listOf(entry(name = "N"), entry(name = "Other", key = "k", index = 2)),
            listOf(existing("e1")),
            mapOf("e1" to "k"),
            DuplicatePolicy.UPDATE
        )
        assertEquals(PlannedAction.UPDATE, claimed[0].action)
        assertEquals(PlannedAction.SKIP_REPEAT_IN_FILE, claimed[1].action)
    }
}
