package com.example.data

import android.content.ContentResolver
import android.net.Uri
import com.example.model.ModelConfig
import com.example.model.ProviderType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale

/**
 * JSON থেকে বাল্ক মডেল ইমপোর্টের লজিক।
 *
 * - [ModelImportParser]  : JSON ফাইল যাচাই ও পার্স (কোনো ডেটা সেভ করে না)
 * - [ModelImportPlanner] : বিদ্যমান মডেলের সাথে মিলিয়ে ADD / UPDATE / SKIP সিদ্ধান্ত (pure logic)
 * - [ModelImportIo]      : নিরাপদ (সাইজ-সীমিত) ফাইল রিড
 *
 * নিরাপত্তা: এই ফাইলে কোথাও Log ব্যবহার করা হয়নি এবং API Key কোনো toString()/বার্তায় আসে না।
 */

/** ইমপোর্ট ডায়ালগে দেখানো সম্ভাব্য ব্যর্থতার কারণ (ফাইল-লেভেল)। */
enum class ImportFailure {
    UNREADABLE,
    EMPTY_FILE,
    TOO_LARGE,
    NOT_JSON,
    UNSUPPORTED_STRUCTURE,
    NO_MODELS,
    TOO_MANY_MODELS,
    NO_VALID_ENTRIES,
    STORAGE_ERROR
}

/** একটি এন্ট্রি বাতিল হওয়ার কারণ। */
enum class ImportIssue {
    NOT_AN_OBJECT,
    MISSING_MODEL_NAME,
    MISSING_PROVIDER_AND_URL,
    UNKNOWN_PROVIDER,
    INVALID_URL,
    INVALID_TEMPERATURE,
    INVALID_API_KEY,
    FIELD_TOO_LONG
}

enum class DuplicatePolicy { SKIP, UPDATE }

data class RejectedEntry(val index: Int, val label: String, val issue: ImportIssue)

data class ImportedModelEntry(
    val index: Int,
    val name: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val modelName: String,
    val apiKey: String,
    val temperature: Float?,
    val supportsTools: Boolean?,
    val supportsVision: Boolean?,
    val supportsStreaming: Boolean?
) {
    val hasKey: Boolean get() = apiKey.isNotEmpty()

    // API Key যাতে ভুলবশত লগ/ডিবাগ আউটপুটে না যায়
    override fun toString(): String =
        "ImportedModelEntry(#$index, name=$name, model=$modelName, provider=$providerType, key=${if (hasKey) "***" else "none"})"
}

sealed interface ModelImportParseResult {
    data class Success(
        val totalFound: Int,
        val entries: List<ImportedModelEntry>,
        val rejected: List<RejectedEntry>
    ) : ModelImportParseResult

    data class Failure(val reason: ImportFailure, val rejected: List<RejectedEntry> = emptyList()) :
        ModelImportParseResult
}

object ModelImportParser {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 500

    private const val MAX_NAME = 100
    private const val MAX_MODEL = 200
    private const val MAX_URL = 2048
    private const val MAX_KEY = 4096

    private val LIST_KEYS = listOf("models", "configs", "providers", "items")

    private val NAME_KEYS = arrayOf("name", "title", "displayName", "label")
    private val MODEL_KEYS = arrayOf("modelName", "modelId", "model_id", "modelID", "model_name", "model")
    private val PROVIDER_KEYS = arrayOf("providerType", "provider", "provider_type", "type", "providerName")
    private val URL_KEYS = arrayOf("baseUrl", "base_url", "baseURL", "endpoint", "apiUrl", "api_url", "url")
    private val KEY_KEYS = arrayOf("apiKey", "api_key", "apikey", "key", "token", "apiToken")
    private val TEMP_KEYS = arrayOf("temperature", "temp")
    private val TOOLS_KEYS = arrayOf("supportsTools", "supports_tools", "tools")
    private val VISION_KEYS = arrayOf("supportsVision", "supports_vision", "vision")
    private val STREAM_KEYS = arrayOf("supportsStreaming", "supports_streaming", "streaming")

    fun parse(rawInput: String): ModelImportParseResult {
        val text = rawInput.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return ModelImportParseResult.Failure(ImportFailure.EMPTY_FILE)

        val array: JSONArray = try {
            when (text.first()) {
                '[' -> JSONArray(text)
                '{' -> extractArray(JSONObject(text))
                    ?: return ModelImportParseResult.Failure(ImportFailure.UNSUPPORTED_STRUCTURE)
                else -> return ModelImportParseResult.Failure(ImportFailure.NOT_JSON)
            }
        } catch (_: JSONException) {
            return ModelImportParseResult.Failure(ImportFailure.NOT_JSON)
        }

        if (array.length() == 0) return ModelImportParseResult.Failure(ImportFailure.NO_MODELS)
        if (array.length() > MAX_ENTRIES) return ModelImportParseResult.Failure(ImportFailure.TOO_MANY_MODELS)

        val entries = mutableListOf<ImportedModelEntry>()
        val rejected = mutableListOf<RejectedEntry>()

        for (i in 0 until array.length()) {
            val position = i + 1
            val obj = array.optJSONObject(i)
            if (obj == null) {
                rejected.add(RejectedEntry(position, "#$position", ImportIssue.NOT_AN_OBJECT))
                continue
            }
            when (val result = parseEntry(position, obj)) {
                is EntryResult.Ok -> entries.add(result.entry)
                is EntryResult.Bad -> rejected.add(RejectedEntry(position, result.label, result.issue))
            }
        }

        if (entries.isEmpty()) {
            return ModelImportParseResult.Failure(ImportFailure.NO_VALID_ENTRIES, rejected)
        }
        return ModelImportParseResult.Success(array.length(), entries, rejected)
    }

    /** রুট অবজেক্টে "models" ইত্যাদি অ্যারে, অথবা একক মডেল অবজেক্ট হলে সেটিকে এক-এন্ট্রির অ্যারে বানায়। */
    private fun extractArray(root: JSONObject): JSONArray? {
        for (key in LIST_KEYS) {
            val candidate = root.optJSONArray(key)
            if (candidate != null) return candidate
        }
        if (firstString(root, MODEL_KEYS) != null) return JSONArray().put(root)
        return null
    }

    private sealed interface EntryResult {
        data class Ok(val entry: ImportedModelEntry) : EntryResult
        data class Bad(val label: String, val issue: ImportIssue) : EntryResult
    }

    private fun parseEntry(position: Int, obj: JSONObject): EntryResult {
        val modelName = firstString(obj, MODEL_KEYS)
        val nameRaw = firstString(obj, NAME_KEYS)
        val label = nameRaw ?: modelName ?: "#$position"
        val safeLabel = if (label.length > MAX_NAME) label.take(MAX_NAME) else label

        if (modelName == null) return EntryResult.Bad(safeLabel, ImportIssue.MISSING_MODEL_NAME)

        val providerRaw = firstString(obj, PROVIDER_KEYS)
        val urlRaw = firstString(obj, URL_KEYS)?.trimEnd('/')
        val apiKey = firstString(obj, KEY_KEYS) ?: ""

        if (modelName.length > MAX_MODEL || (nameRaw?.length ?: 0) > MAX_NAME ||
            (urlRaw?.length ?: 0) > MAX_URL || apiKey.length > MAX_KEY
        ) {
            return EntryResult.Bad(safeLabel, ImportIssue.FIELD_TOO_LONG)
        }

        if (urlRaw != null && !isValidHttpUrl(urlRaw)) {
            return EntryResult.Bad(safeLabel, ImportIssue.INVALID_URL)
        }

        // API Key-তে স্পেস/নিউলাইন/কন্ট্রোল ক্যারেক্টার থাকলে HTTP হেডার ভেঙে যাবে — তাই বাতিল
        if (apiKey.any { it.isWhitespace() || it.isISOControl() }) {
            return EntryResult.Bad(safeLabel, ImportIssue.INVALID_API_KEY)
        }

        val canonical = providerRaw?.let { canonicalProvider(it) }
        val providerType: ProviderType = when {
            canonical != null -> canonical
            urlRaw != null -> inferProviderFromUrl(urlRaw)
            providerRaw == null -> return EntryResult.Bad(safeLabel, ImportIssue.MISSING_PROVIDER_AND_URL)
            else -> return EntryResult.Bad(safeLabel, ImportIssue.UNKNOWN_PROVIDER)
        }

        // urlRaw না থাকা মানে provider openai/anthropic/gemini (canonical) — তখনই ডিফল্ট URL নিরাপদ।
        // অন্য প্রোভাইডার (groq, custom ইত্যাদি) URL ছাড়া উপরে UNKNOWN_PROVIDER হিসেবে বাতিল হয়েছে,
        // যাতে ভুল এন্ডপয়েন্টে API Key পাঠানো না হয়।
        val baseUrl: String = urlRaw ?: providerType.defaultBaseUrl

        val temperatureField = firstFloat(obj, TEMP_KEYS)
        val temperatureValue: Float? = temperatureField.value
        if (temperatureField.present && (temperatureValue == null || temperatureValue < 0f || temperatureValue > 2f)) {
            return EntryResult.Bad(safeLabel, ImportIssue.INVALID_TEMPERATURE)
        }

        return EntryResult.Ok(
            ImportedModelEntry(
                index = position,
                name = nameRaw ?: modelName,
                providerType = providerType,
                baseUrl = baseUrl,
                modelName = modelName,
                apiKey = apiKey,
                temperature = temperatureValue,
                supportsTools = firstBoolean(obj, TOOLS_KEYS),
                supportsVision = firstBoolean(obj, VISION_KEYS),
                supportsStreaming = firstBoolean(obj, STREAM_KEYS)
            )
        )
    }

    // ---------- field helpers ----------

    private fun firstString(obj: JSONObject, keys: Array<String>): String? {
        for (key in keys) {
            val v = obj.opt(key)
            if (v is String) {
                val t = v.trim()
                if (t.isNotEmpty()) return t
            }
        }
        return null
    }

    private fun firstBoolean(obj: JSONObject, keys: Array<String>): Boolean? {
        for (key in keys) {
            when (val v = obj.opt(key)) {
                is Boolean -> return v
                is String -> when (v.trim().lowercase(Locale.ROOT)) {
                    "true" -> return true
                    "false" -> return false
                }
            }
        }
        return null
    }

    private class FloatField(val present: Boolean, val value: Float?)

    private fun firstFloat(obj: JSONObject, keys: Array<String>): FloatField {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val v = obj.opt(key)
            val parsed: Float? = when (v) {
                is Number -> v.toFloat()
                is String -> v.trim().toFloatOrNull()
                else -> null
            }
            val finite = parsed?.takeIf { !it.isNaN() && !it.isInfinite() }
            return FloatField(true, finite)
        }
        return FloatField(false, null)
    }

    private fun isValidHttpUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (url.any { it.isWhitespace() }) return false
        return try {
            !URI(url).host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun canonicalProvider(raw: String): ProviderType? {
        val n = raw.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return when (n) {
            "openai" -> ProviderType.OPENAI
            "anthropic", "claude" -> ProviderType.ANTHROPIC
            "gemini", "google", "googlegemini", "googleai" -> ProviderType.GEMINI
            else -> null
        }
    }

    private fun inferProviderFromUrl(url: String): ProviderType {
        val host = try {
            URI(url).host?.lowercase(Locale.ROOT) ?: ""
        } catch (_: Exception) {
            ""
        }
        return when {
            host.endsWith("anthropic.com") -> ProviderType.ANTHROPIC
            host.endsWith("generativelanguage.googleapis.com") -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Duplicate detection + planning
// ---------------------------------------------------------------------------------------------

enum class PlannedAction { ADD, UPDATE, SKIP_DUPLICATE, SKIP_REPEAT_IN_FILE }

data class PlannedItem(
    val entry: ImportedModelEntry,
    val action: PlannedAction,
    val existingId: String? = null
)

object ModelImportPlanner {

    /** provider + baseUrl + modelId + name — একই নামে একই কনফিগ হলে duplicate। */
    fun identity(type: ProviderType, baseUrl: String, modelName: String, name: String): String =
        listOf(type.name, normalizeUrl(baseUrl), modelName.trim().lowercase(Locale.ROOT), name.trim().lowercase(Locale.ROOT))
            .joinToString("|")

    private fun endpointIdentity(type: ProviderType, baseUrl: String, modelName: String): String =
        listOf(type.name, normalizeUrl(baseUrl), modelName.trim().lowercase(Locale.ROOT)).joinToString("|")

    private fun normalizeUrl(url: String): String = url.trim().trimEnd('/').lowercase(Locale.ROOT)

    /**
     * @param existingKeys configId -> সংরক্ষিত plain API Key (শুধু মেমোরিতে তুলনার জন্য)
     *
     * Duplicate নিয়ম:
     *  1. provider+URL+model+name হুবহু মিললে, অথবা
     *  2. provider+URL+model মিললে এবং API Key-ও একই হলে (একই কনফিগের পুনরায় যোগ)।
     * ভিন্ন নাম ও ভিন্ন Key সহ একই মডেল আলাদা কনফিগ হিসেবে যোগ হয় (একাধিক Key ফেইলওভারের জন্য)।
     */
    fun plan(
        entries: List<ImportedModelEntry>,
        existing: List<ModelConfig>,
        existingKeys: Map<String, String>,
        policy: DuplicatePolicy
    ): List<PlannedItem> {
        val exactIndex = HashMap<String, String>()
        for (m in existing) {
            exactIndex.putIfAbsent(identity(m.providerType, m.baseUrl, m.modelName, m.name), m.id)
        }
        val seenInFile = HashSet<String>()
        val claimedIds = HashSet<String>()
        val result = ArrayList<PlannedItem>(entries.size)

        for (entry in entries) {
            val id = identity(entry.providerType, entry.baseUrl, entry.modelName, entry.name)
            if (!seenInFile.add(id)) {
                result.add(PlannedItem(entry, PlannedAction.SKIP_REPEAT_IN_FILE))
                continue
            }

            var matchId: String? = exactIndex[id]
            if (matchId == null && entry.hasKey) {
                val ep = endpointIdentity(entry.providerType, entry.baseUrl, entry.modelName)
                matchId = existing.firstOrNull { m ->
                    endpointIdentity(m.providerType, m.baseUrl, m.modelName) == ep &&
                        existingKeys[m.id] == entry.apiKey
                }?.id
            }

            when {
                matchId == null -> result.add(PlannedItem(entry, PlannedAction.ADD))
                !claimedIds.add(matchId) -> result.add(PlannedItem(entry, PlannedAction.SKIP_REPEAT_IN_FILE))
                policy == DuplicatePolicy.UPDATE -> result.add(PlannedItem(entry, PlannedAction.UPDATE, matchId))
                else -> result.add(PlannedItem(entry, PlannedAction.SKIP_DUPLICATE, matchId))
            }
        }
        return result
    }
}

// ---------------------------------------------------------------------------------------------
// Summary / preview models
// ---------------------------------------------------------------------------------------------

data class ModelImportPreview(
    val totalFound: Int,
    val entries: List<ImportedModelEntry>,
    val rejected: List<RejectedEntry>,
    val newCount: Int,
    val duplicateCount: Int,
    val repeatInFileCount: Int,
    val withoutKeyCount: Int
)

data class ModelImportSummary(
    val totalFound: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val rejected: List<RejectedEntry>,
    val importedWithoutKey: Int
)

sealed interface ModelImportOutcome {
    data class Success(val summary: ModelImportSummary) : ModelImportOutcome
    /** কিছুই পরিবর্তিত হয়নি (রোলব্যাক সম্পন্ন)। */
    data object StorageFailure : ModelImportOutcome
}

// ---------------------------------------------------------------------------------------------
// File IO (Storage Access Framework Uri)
// ---------------------------------------------------------------------------------------------

sealed interface ModelImportReadResult {
    data class Text(val value: String) : ModelImportReadResult
    data class Error(val reason: ImportFailure) : ModelImportReadResult
}

object ModelImportIo {
    /** সর্বোচ্চ [ModelImportParser.MAX_FILE_BYTES] পর্যন্ত পড়ে; বড় ফাইল হলে TOO_LARGE। ব্লকিং — IO ডিসপ্যাচারে কল করুন। */
    fun readText(resolver: ContentResolver, uri: Uri): ModelImportReadResult {
        return try {
            val input = resolver.openInputStream(uri)
                ?: return ModelImportReadResult.Error(ImportFailure.UNREADABLE)
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > ModelImportParser.MAX_FILE_BYTES) {
                        return ModelImportReadResult.Error(ImportFailure.TOO_LARGE)
                    }
                    out.write(buffer, 0, n)
                }
                ModelImportReadResult.Text(String(out.toByteArray(), Charsets.UTF_8))
            }
        } catch (_: Exception) {
            ModelImportReadResult.Error(ImportFailure.UNREADABLE)
        }
    }
}
