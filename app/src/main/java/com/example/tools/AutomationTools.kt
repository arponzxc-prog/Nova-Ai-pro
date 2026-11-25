/*
 * Derived in part from the JARVIS project (PhoneControlManager media/web/URL handling, LocationWeatherHelper,
 * WhatsApp/YouTube controllers). Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Exposes the ported automation capabilities as NEXA ToolDefinitions. Modifications © ARPON.
 */
package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.example.accessibility.RecipeExecutionContext
import com.example.automation.phone.DomainNormalizer
import com.example.automation.phone.UrlConfidence
import com.example.automation.whatsapp.WhatsAppCallRecipe
import com.example.automation.whatsapp.WhatsAppDeleteRecipe
import com.example.automation.youtube.YouTubeAutomation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Pure weather-JSON parsing (unit-tested). Open-Meteo "current" block. */
object WeatherParser {
    data class Weather(val tempC: Double, val feelsC: Double, val humidity: Int, val code: Int, val maxC: Double?, val minC: Double?)

    fun parse(body: String): Weather? = try {
        val o = JSONObject(body)
        val cur = o.getJSONObject("current")
        val daily = o.optJSONObject("daily")
        Weather(
            tempC = cur.getDouble("temperature_2m"),
            feelsC = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
            humidity = cur.optInt("relative_humidity_2m", -1),
            code = cur.optInt("weather_code", -1),
            maxC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
            minC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() }
        )
    } catch (_: Exception) { null }

    fun describe(code: Int): String = when (code) {
        0 -> "পরিষ্কার আকাশ"; 1, 2 -> "আংশিক মেঘলা"; 3 -> "মেঘলা"; 45, 48 -> "কুয়াশা"
        51, 53, 55, 56, 57 -> "গুঁড়ি গুঁড়ি বৃষ্টি"; 61, 63, 65, 66, 67, 80, 81, 82 -> "বৃষ্টি"
        71, 73, 75, 77, 85, 86 -> "তুষারপাত"; 95, 96, 99 -> "বজ্রসহ বৃষ্টি"
        else -> "আবহাওয়া অজানা"
    }
}

object AutomationTools {

    private val http by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    // ---- media_control -------------------------------------------------------------------------------

    suspend fun mediaControl(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val action = args.optString("action", "").lowercase()
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolResult(false, "অজানা মিডিয়া অ্যাকশন '$action' (play, pause, toggle, next, previous)।")
        }
        val am = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasActive = am.isMusicActive
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        delay(400)
        val isActive = am.isMusicActive
        val evidence = when (action) {
            "pause" -> if (wasActive && !isActive) "মিডিয়া থেমেছে।" else if (!wasActive) "কোনো মিডিয়া চলছিল না।" else "কমান্ড পাঠানো হয়েছে, তবে থেমেছে কিনা নিশ্চিত নয়।"
            "play" -> if (!wasActive && isActive) "মিডিয়া চালু হয়েছে।" else "কমান্ড পাঠানো হয়েছে, তবে চালু হয়েছে কিনা নিশ্চিত নয়।"
            else -> "কমান্ড পাঠানো হয়েছে (ফলাফল যাচাই করা যায় না)।"
        }
        return ToolResult(true, "মিডিয়া '$action': $evidence", JSONObject().put("was_playing", wasActive).put("is_playing", isActive))
    }

    // ---- open_url ------------------------------------------------------------------------------------

    suspend fun openUrl(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        val rawUrl = args.optString("url", "").trim()
        val targets = ArrayList<String>()
        if (rawUrl.isNotBlank()) {
            val resolved = DomainNormalizer.extractMultipleUrls(rawUrl).take(5)
            if (resolved.isEmpty()) return ToolResult(false, "'$rawUrl' একটি বৈধ ওয়েবসাইট ঠিকানা নয় (শুধু http/https খোলা যায়)।")
            val guessed = resolved.filter { it.confidence == UrlConfidence.GUESSED }
            if (guessed.isNotEmpty()) {
                return ToolResult(false, "'${guessed.first().url}' শুধু অনুমান করা ঠিকানা; ভুল সাইটে না গিয়ে ইউজারের কাছ থেকে পূর্ণ ঠিকানা (যেমন example.com) জিজ্ঞেস করুন।")
            }
            resolved.forEach { targets += it.url }
        } else if (query.isNotBlank()) {
            targets += "https://www.google.com/search?q=" + Uri.encode(query.take(300))
        } else return ToolResult(false, "খোলার জন্য ওয়েবসাইট বা সার্চ কোয়েরি দিন।")

        var opened = 0
        for ((i, url) in targets.withIndex()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.appContext.startActivity(intent) }.isSuccess) opened++
            if (i < targets.lastIndex) delay(450)
        }
        return if (opened == targets.size) ToolResult(true, if (targets.size == 1) "ব্রাউজারে খোলা হয়েছে: ${targets[0]}" else "${targets.size}টি সাইট খোলা হয়েছে।", JSONObject().put("opened", opened))
        else ToolResult(opened > 0, "$opened/${targets.size}টি লিংক খোলা গেছে।")
    }

    // ---- get_weather_location ------------------------------------------------------------------------

    suspend fun weather(ctx: ToolExecutionContext, @Suppress("UNUSED_PARAMETER") args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val lm = ctx.appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull() ?: return@withContext ToolResult(false, "ডিভাইসের লোকেশন পাওয়া যায়নি। লোকেশন চালু করে একবার ম্যাপ খুলে আবার চেষ্টা করুন।")
        // Coordinates are rounded to 2 decimals (~1 km) before leaving the device.
        val lat = Math.round(loc.latitude * 100) / 100.0
        val lon = Math.round(loc.longitude * 100) / 100.0
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
        val body = try {
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) { null } ?: return@withContext ToolResult(false, "আবহাওয়ার তথ্য আনা যায়নি (ইন্টারনেট বা সার্ভিস সমস্যা)।")
        val w = WeatherParser.parse(body) ?: return@withContext ToolResult(false, "আবহাওয়ার উত্তর পড়া যায়নি।")
        val ageMin = (System.currentTimeMillis() - loc.time) / 60_000
        val range = if (w.maxC != null && w.minC != null) " আজ সর্বোচ্চ ${w.maxC.toInt()}°, সর্বনিম্ন ${w.minC.toInt()}°।" else ""
        ToolResult(
            true,
            "${WeatherParser.describe(w.code)}, তাপমাত্রা ${w.tempC.toInt()}° (অনুভূত ${w.feelsC.toInt()}°), আর্দ্রতা ${w.humidity}%।$range" +
                if (ageMin > 360) " (সতর্কতা: লোকেশন ${ageMin / 60} ঘণ্টা পুরোনো)" else "",
            JSONObject().put("lat", lat).put("lon", lon).put("source", "open-meteo.com")
        )
    }

    // ---- definitions ---------------------------------------------------------------------------------

    private fun str(d: String) = ParameterProperty(SchemaType.STRING, d)

    val all: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "media_control", bengaliTitle = "মিডিয়া নিয়ন্ত্রণ",
            description = "Control the currently playing media (any app): play, pause, toggle, next track, previous track.",
            parametersSchema = ToolParametersSchema(
                mapOf("action" to ParameterProperty(SchemaType.STRING, "Media action.", enumValues = listOf("play", "pause", "toggle", "next", "previous"))),
                required = listOf("action")
            ),
            riskLevel = RiskLevel.LOW, executor = { c, a -> mediaControl(c, a) }
        ),
        ToolDefinition(
            name = "open_url", bengaliTitle = "ওয়েবসাইট খোলা",
            description = "Open one or more websites in the browser (url: 'github.com', 'open vercel and github') or run a Google search in the browser (query). Only http/https.",
            parametersSchema = ToolParametersSchema(
                mapOf("url" to str("Website address or spoken name(s), e.g. 'github.com and vercel'. Max 5."), "query" to str("Search text to open on Google in the browser."))
            ),
            riskLevel = RiskLevel.MEDIUM, executor = { c, a -> openUrl(c, a) }
        ),
        ToolDefinition(
            name = "get_weather_location", bengaliTitle = "আবহাওয়া",
            description = "Current weather at the device's last known (approximate) location.",
            parametersSchema = ToolParametersSchema(emptyMap()),
            riskLevel = RiskLevel.MEDIUM, requiredPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            executor = { c, a -> weather(c, a) }
        ),
        ToolDefinition(
            name = "youtube_search", bengaliTitle = "YouTube সার্চ",
            description = "Open YouTube search results for a query.",
            parametersSchema = ToolParametersSchema(mapOf("query" to str("Search text.")), required = listOf("query")),
            riskLevel = RiskLevel.LOW, executor = { c, a -> YouTubeAutomation.search(c, a) }
        ),
        ToolDefinition(
            name = "youtube_play", bengaliTitle = "YouTube ভিডিও চালানো",
            description = "Find and play a specific YouTube video. The query must name the video; do not guess if the user was vague.",
            parametersSchema = ToolParametersSchema(mapOf("query" to str("Video title / artist / description.")), required = listOf("query")),
            riskLevel = RiskLevel.MEDIUM, executor = { c, a -> YouTubeAutomation.play(c, a) }
        ),
        ToolDefinition(
            name = "youtube_subscribe", bengaliTitle = "চ্যানেল সাবস্ক্রাইব",
            description = "Subscribe to the channel shown in the YouTube app (it must already be open on a channel or video).",
            parametersSchema = ToolParametersSchema(emptyMap()),
            riskLevel = RiskLevel.HIGH, executor = { c, a -> YouTubeAutomation.subscribe(c, a) }
        ),
        ToolDefinition(
            name = "youtube_fullscreen", bengaliTitle = "YouTube ফুলস্ক্রিন",
            description = "Enter fullscreen for the video playing in the YouTube app.",
            parametersSchema = ToolParametersSchema(emptyMap()),
            riskLevel = RiskLevel.LOW, executor = { c, a -> YouTubeAutomation.fullscreen(c, a) }
        ),
        ToolDefinition(
            name = "whatsapp_call", bengaliTitle = "WhatsApp কল",
            description = "Start a WhatsApp voice or video call to a contact name or phone number.",
            parametersSchema = ToolParametersSchema(
                mapOf(
                    "phone_number" to str("Exact phone number. Optional if contact_name is given."),
                    "contact_name" to str("Contact name to look up."),
                    "video" to ParameterProperty(SchemaType.BOOLEAN, "True for a video call, false for voice.")
                )
            ),
            riskLevel = RiskLevel.HIGH,
            executor = { c, a -> WhatsAppCallRecipe().execute(RecipeExecutionContext(c.appContext, c.accessPolicy), a).toolResult }
        ),
        ToolDefinition(
            name = "whatsapp_delete_message", bengaliTitle = "WhatsApp মেসেজ মোছা",
            description = "Delete the user's most recent SENT message in the chat with a contact (for me only, or for everyone). Irreversible.",
            parametersSchema = ToolParametersSchema(
                mapOf(
                    "phone_number" to str("Exact phone number. Optional if contact_name is given."),
                    "contact_name" to str("Contact whose chat contains the message."),
                    "for_everyone" to ParameterProperty(SchemaType.BOOLEAN, "True = delete for everyone; false (default) = delete only for the user.")
                )
            ),
            riskLevel = RiskLevel.HIGH,
            executor = { c, a -> WhatsAppDeleteRecipe().execute(RecipeExecutionContext(c.appContext, c.accessPolicy), a).toolResult }
        )
    )
}
