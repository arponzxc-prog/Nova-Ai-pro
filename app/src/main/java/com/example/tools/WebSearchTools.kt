package com.example.tools

import com.example.network.NetworkClient
import com.example.security.SecureKeyStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class SearchProviderType(val keyStoreId: String, val displayName: String) {
    TAVILY("search_tavily", "Tavily"),
    BRAVE("search_brave", "Brave Search"),
    SERPAPI("search_serpapi", "SerpAPI")
}

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * লাইভ ওয়েব সার্চ টুল:
 * - Tavily, Brave Search, এবং SerpAPI সাপোর্ট করে
 * - SecureKeyStore-এ আলাদা কী হিসেবে API Key সংরক্ষণ করে
 * - একটি সার্চ প্রোভাইডার ব্যর্থ হলে স্বয়ংক্রিয়ভাবে পরের কনফিগার করা প্রোভাইডারে চেষ্টা করে
 */
object WebSearchTools {

    fun search(
        ctx: ToolExecutionContext,
        args: JSONObject,
        client: OkHttpClient = NetworkClient.baseClient,
        keyOverride: Map<SearchProviderType, String>? = null
    ): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolResult(false, "সার্চ করার জন্য 'query' উল্লেখ করুন।")
        }
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 8)

        val keyStore = if (keyOverride == null) SecureKeyStore(ctx.appContext) else null
        val configuredProviders = SearchProviderType.entries.mapNotNull { provider ->
            val key = (keyOverride?.get(provider) ?: keyStore?.getDecryptedKey(provider.keyStoreId).orEmpty()).trim()
            if (key.isEmpty()) null else provider to key
        }

        if (configuredProviders.isEmpty()) {
            return ToolResult(
                success = false,
                message = "কোনো ওয়েব সার্চ API Key (Tavily, Brave, বা SerpAPI) সেট করা নেই। সেটিংস → ফোন অ্যাকশন ও টুল সেটিংস থেকে অন্তত একটি Search Key যোগ করুন।"
            )
        }

        val errors = mutableListOf<String>()
        for ((provider, apiKey) in configuredProviders) {
            try {
                val items = when (provider) {
                    SearchProviderType.TAVILY -> searchTavily(client, apiKey, query, maxResults)
                    SearchProviderType.BRAVE -> searchBrave(client, apiKey, query, maxResults)
                    SearchProviderType.SERPAPI -> searchSerpApi(client, apiKey, query, maxResults)
                }

                val resultsArray = JSONArray()
                items.forEach { item ->
                    resultsArray.put(JSONObject().apply {
                        put("title", item.title)
                        put("url", item.url)
                        put("snippet", item.snippet)
                    })
                }
                val summaryMsg = if (items.isEmpty()) {
                    "'$query' এর জন্য ${provider.displayName}-এ কোনো ফলাফল পাওয়া যায়নি।"
                } else {
                    buildString {
                        append("${provider.displayName} থেকে '${query}' এর ${items.size}টি ফলাফল:\n")
                        items.forEachIndexed { idx, item ->
                            append("${idx + 1}. ${item.title}\n   URL: ${item.url}\n   ${item.snippet}\n")
                        }
                    }.trimEnd()
                }
                return ToolResult(
                    success = true,
                    message = summaryMsg,
                    data = JSONObject().apply {
                        put("provider", provider.displayName)
                        put("query", query)
                        put("results", resultsArray)
                    }
                )
            } catch (e: Exception) {
                val safeMsg = SecureKeyStore.redactSecrets(e.message ?: "অজানা ত্রুটি")
                errors.add("${provider.displayName}: $safeMsg")
            }
        }

        return ToolResult(
            success = false,
            message = "সব ওয়েব সার্চ প্রোভাইডার ব্যর্থ হয়েছে (${errors.joinToString("; ")})।"
        )
    }

    internal fun searchTavily(
        client: OkHttpClient,
        apiKey: String,
        query: String,
        maxResults: Int,
        endpointUrl: String = "https://api.tavily.com/search"
    ): List<WebSearchResultItem> {
        val payload = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
            put("include_answer", true)
        }
        val req = Request.Builder()
            .url(endpointUrl)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        val bodyStr = client.newCall(req).execute().use { resp ->
            val str = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${str.take(150)}")
            str
        }

        val root = JSONObject(bodyStr)
        val out = mutableListOf<WebSearchResultItem>()
        val answer = root.optString("answer", "").trim()
        if (answer.isNotBlank()) {
            out.add(WebSearchResultItem("Direct Answer", "", answer.take(450)))
        }
        val arr = root.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(
                WebSearchResultItem(
                    title = obj.optString("title", "ফলাফল #${i + 1}"),
                    url = obj.optString("url", ""),
                    snippet = obj.optString("content", "").take(360)
                )
            )
            if (out.size >= maxResults) break
        }
        return out
    }

    internal fun searchBrave(
        client: OkHttpClient,
        apiKey: String,
        query: String,
        maxResults: Int,
        endpointUrl: String = "https://api.search.brave.com/res/v1/web/search"
    ): List<WebSearchResultItem> {
        val url = endpointUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", maxResults.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("X-Subscription-Token", apiKey)
            .build()

        val bodyStr = client.newCall(req).execute().use { resp ->
            val str = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${str.take(150)}")
            str
        }

        val root = JSONObject(bodyStr)
        val arr = root.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<WebSearchResultItem>()
        for (i in 0 until minOf(arr.length(), maxResults)) {
            val obj = arr.getJSONObject(i)
            out.add(
                WebSearchResultItem(
                    title = obj.optString("title", "Result #${i + 1}"),
                    url = obj.optString("url", ""),
                    snippet = obj.optString("description", "").take(360)
                )
            )
        }
        return out
    }

    internal fun searchSerpApi(
        client: OkHttpClient,
        apiKey: String,
        query: String,
        maxResults: Int,
        endpointUrl: String = "https://serpapi.com/search.json"
    ): List<WebSearchResultItem> {
        val url = endpointUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("num", maxResults.toString())
            .addQueryParameter("api_key", apiKey)
            .build()
        val req = Request.Builder().url(url).get().build()

        val bodyStr = client.newCall(req).execute().use { resp ->
            val str = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${str.take(150)}")
            str
        }

        val root = JSONObject(bodyStr)
        val arr = root.optJSONArray("organic_results") ?: JSONArray()
        val out = mutableListOf<WebSearchResultItem>()
        for (i in 0 until minOf(arr.length(), maxResults)) {
            val obj = arr.getJSONObject(i)
            out.add(
                WebSearchResultItem(
                    title = obj.optString("title", "Result #${i + 1}"),
                    url = obj.optString("link", ""),
                    snippet = obj.optString("snippet", "").take(360)
                )
            )
        }
        return out
    }
}
