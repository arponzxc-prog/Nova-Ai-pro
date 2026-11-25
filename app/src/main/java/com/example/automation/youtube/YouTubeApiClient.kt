/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.youtube.YouTubeApiClient).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Rewritten for NEXA AI: key sent in the x-goog-api-key header (never in the URL), never logged, key comes from
 * SecureKeyStore via AiRepository, explicit result type. Modifications © ARPON.
 */
package com.example.automation.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class YouTubeVideo(val id: String, val title: String, val channel: String)

sealed interface YouTubeLookup {
    data class Found(val video: YouTubeVideo) : YouTubeLookup
    data object NoResult : YouTubeLookup
    data object KeyRejected : YouTubeLookup
    data object QuotaExceeded : YouTubeLookup
    data object NetworkError : YouTubeLookup
}

class YouTubeApiClient(private val http: OkHttpClient = defaultClient) {

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        }
        private const val ENDPOINT = "https://www.googleapis.com/youtube/v3/search"

        /** Pure parser (unit-tested). */
        fun parseFirstVideo(body: String): YouTubeVideo? = try {
            val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0)
            val id = item?.optJSONObject("id")?.optString("videoId", "").orEmpty()
            if (id.isBlank() || !id.matches(Regex("^[A-Za-z0-9_-]{6,20}$"))) null
            else YouTubeVideo(
                id = id,
                title = item?.optJSONObject("snippet")?.optString("title", "").orEmpty(),
                channel = item?.optJSONObject("snippet")?.optString("channelTitle", "").orEmpty()
            )
        } catch (_: Exception) { null }
    }

    suspend fun firstVideo(query: String, apiKey: String): YouTubeLookup = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext YouTubeLookup.NoResult
        try {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet").addQueryParameter("type", "video")
                .addQueryParameter("maxResults", "1").addQueryParameter("q", query.take(200)).build()
            http.newCall(Request.Builder().url(url).header("x-goog-api-key", apiKey).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 400 || resp.code == 401 -> YouTubeLookup.KeyRejected
                    resp.code == 403 -> if (body.contains("quota", ignoreCase = true)) YouTubeLookup.QuotaExceeded else YouTubeLookup.KeyRejected
                    !resp.isSuccessful -> YouTubeLookup.NetworkError
                    else -> parseFirstVideo(body)?.let { YouTubeLookup.Found(it) } ?: YouTubeLookup.NoResult
                }
            }
        } catch (_: Exception) { YouTubeLookup.NetworkError } // exception text can embed the URL: never surfaced
    }
}
