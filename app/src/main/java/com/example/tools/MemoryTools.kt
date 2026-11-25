package com.example.tools

import com.example.data.local.AppDatabase
import com.example.data.local.MemoryEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * দীর্ঘমেয়াদী মেমোরি (Long-Term Memory) টুল ও ফিল্টার:
 * - Room `memories` টেবিলে নাম, পছন্দ ও তথ্য সংরক্ষণ করে
 * - নিরাপত্তা ফিল্টার: OTP, পাসওয়ার্ড, PIN, কার্ড নম্বর, এবং API Key সংরক্ষণ কঠোরভাবে প্রত্যাখ্যান করে
 * - প্রম্পট ইনজেকশনে কেবল ইউজারের বর্তমান প্রশ্নের সাথে প্রাসঙ্গিক সীমিত মেমোরি নেয়
 */
object MemoryTools {

    private val SENSITIVE_WORD_REGEX = Regex(
        "\\b(password|passwd|passcode|otp|one[-\\s]?time|pin\\s*code|cvv|cvc|secret\\s*key|api\\s*key|private\\s*key|bearer\\s+token|পাসওয়ার্ড|পাসওয়ার্ড|ওটিপি|পিন\\s*নম্বর|গোপন\\s*কোড)\\b",
        RegexOption.IGNORE_CASE
    )
    private val API_KEY_PATTERN = Regex("\\b(sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{10,}|tvly-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{10,})")
    private val CARD_NUMBER_PATTERN = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val PIN_OR_OTP_DIGITS = Regex("\\b(?:otp|pin|code|পিন|ওটিপি)\\s*(?:is|হলো|:|=)?\\s*\\d{4,8}\\b", RegexOption.IGNORE_CASE)

    fun detectSensitiveReason(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "ফাঁকা তথ্য মেমোরিতে সেভ করা যাবে না।"
        if (API_KEY_PATTERN.containsMatchIn(trimmed)) {
            return "নিরাপত্তার কারণে API Key বা টোকেন মেমোরিতে সেভ করা নিষিদ্ধ।"
        }
        if (CARD_NUMBER_PATTERN.containsMatchIn(trimmed)) {
            return "নিরাপত্তার কারণে ব্যাংক কার্ড বা অ্যাকাউন্ট নম্বর মেমোরিতে সেভ করা নিষিদ্ধ।"
        }
        if (PIN_OR_OTP_DIGITS.containsMatchIn(trimmed) || SENSITIVE_WORD_REGEX.containsMatchIn(trimmed)) {
            return "নিরাপত্তার কারণে পাসওয়ার্ড, OTP বা PIN জাতীয় সংবেদনশীল তথ্য মেমোরিতে সেভ করা নিষিদ্ধ।"
        }
        return null
    }

    suspend fun rememberFact(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val content = args.optString("content", "").trim()
        val rawCategory = args.optString("category", "fact").trim().lowercase()
        val category = if (rawCategory in setOf("name", "preference", "fact")) rawCategory else "fact"
        val keywords = args.optString("keywords", "").trim()

        val blockReason = detectSensitiveReason("$content $keywords")
        if (blockReason != null) {
            return ToolResult(false, blockReason)
        }

        val dao = AppDatabase.getInstance(ctx.appContext).chatDao()
        val existing = dao.getAllMemories()
        val duplicate = existing.firstOrNull {
            it.content.equals(content, ignoreCase = true) ||
                (category == "name" && it.category == "name" && it.content.contains(content, ignoreCase = true))
        }

        val entity = MemoryEntity(
            id = duplicate?.id ?: UUID.randomUUID().toString(),
            category = category,
            content = content.take(400),
            keywords = keywords.ifBlank { extractKeywords(content) }.take(200),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertMemory(entity)

        return ToolResult(
            success = true,
            message = "মেমোরিতে সংরক্ষণ করা হয়েছে: ${entity.content}",
            data = JSONObject().apply {
                put("id", entity.id)
                put("category", entity.category)
                put("content", entity.content)
            }
        )
    }

    suspend fun recallFacts(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        val dao = AppDatabase.getInstance(ctx.appContext).chatDao()
        val all = dao.getAllMemories()
        if (all.isEmpty()) {
            return ToolResult(true, "এখনো কোনো মেমোরি সংরক্ষিত নেই।", JSONObject().put("memories", JSONArray()))
        }

        val matched = if (query.isBlank()) {
            all.take(10)
        } else {
            selectRelevantMemories(query, all, limit = 10)
        }

        val arr = JSONArray()
        matched.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("category", m.category)
                put("content", m.content)
                put("keywords", m.keywords)
            })
        }

        val msg = if (matched.isEmpty()) {
            "'$query' সম্পর্কিত কোনো মেমোরি পাওয়া যায়নি।"
        } else {
            matched.joinToString("\n") { "• [${it.category}] ${it.content}" }
        }
        return ToolResult(true, msg, JSONObject().put("memories", arr))
    }

    fun selectRelevantMemories(
        query: String,
        allMemories: List<MemoryEntity>,
        limit: Int = 6
    ): List<MemoryEntity> {
        if (allMemories.isEmpty()) return emptyList()
        val queryTokens = query.lowercase()
            .split(Regex("[\\s,.;:?!।\"'()]+"))
            .filter { it.length >= 2 }
            .toSet()

        return allMemories
            .map { m ->
                var score = 0
                if (m.category == "name") score += 4
                val hay = "${m.content} ${m.keywords}".lowercase()
                queryTokens.forEach { tok ->
                    if (hay.contains(tok)) score += 3
                }
                if (m.category == "preference" && score > 0) score += 1
                m to score
            }
            .filter { (m, score) -> score > 0 || m.category == "name" }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }
    }

    fun extractKeywords(text: String): String {
        return text.lowercase()
            .split(Regex("[\\s,.;:?!।\"'()]+"))
            .filter { it.length >= 3 }
            .distinct()
            .take(8)
            .joinToString(", ")
    }
}
