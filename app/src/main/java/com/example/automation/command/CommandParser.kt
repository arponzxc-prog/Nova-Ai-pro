/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.command.CommandParser and JarvisCommand).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Rewritten for NEXA AI: the parser outputs a NEXA tool name + JSON arguments (never an action), so every command
 * goes through ToolGateway (confirmation, permissions, kill switch, secure-screen checks). Phrases are Unicode-aware
 * (JARVIS names were [a-zA-Z0-9] only), a WhatsApp message needs an explicit "saying/that/:" separator, a
 * delete needs a named contact and defaults to "for me", and the JARVIS confirm/disambiguation/memory phrases are not
 * ported (NEXA has its own confirmation, contact picker and memory). Modifications © ARPON.
 */
package com.example.automation.command

import com.example.automation.phone.DomainNormalizer
import org.json.JSONObject
import java.util.Locale

data class ParsedCommand(val toolName: String, val args: JSONObject, val confidence: Float, val pattern: String)

object CommandParser {

    const val CONFIDENCE_THRESHOLD = 0.90f

    private val AMBIGUOUS_PLAY_WORDS = setOf(
        "something", "anything", "whatever", "that", "this", "it", "one", "song", "video", "music", "gana", "gane",
        "track", "kuch", "kuch bhi", "pata nahi", "i don't know", "idk", "um", "uh", "hmm", "huh", "কিছু", "গান"
    )

    /** True when a YouTube play query is too vague/short to trust (a likely mis-heard or dropped title). */
    fun isAmbiguousPlayQuery(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 3 || q in AMBIGUOUS_PLAY_WORDS) return true
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        if (words.size == 1 && words[0].length <= 3) return true
        return words.all { it in AMBIGUOUS_PLAY_WORDS }
    }

    private const val P = "^(?:please\\s+|can you\\s+|could you\\s+|just\\s+)?"
    private const val NAME = "([\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}\\u200c\\u200d .'_-]{0,40}?)"
    private val FLASH = "(?:flash\\s*light|flash|torch(?:\\s*light)?)"
    private const val LEVEL = "(\\d{1,3}|zero|min|minimum|lowest|ten|twenty|twenty five|quarter|thirty|forty|fifty|half|sixty|seventy|seventy five|eighty|ninety|hundred|full|max|maximum|highest)"
    private val NON_PEOPLE = setOf("me", "him", "her", "them", "someone", "anyone", "later", "back", "it", "that", "this")

    private fun cmd(tool: String, conf: Float, pattern: String, build: JSONObject.() -> Unit) =
        ParsedCommand(tool, JSONObject().apply(build), conf, pattern)

    fun parseNumericLevel(token: String): Int? {
        token.toIntOrNull()?.let { return if (it in 0..100) it else null }
        return when (token) {
            "zero", "min", "minimum", "lowest" -> 0
            "ten" -> 10; "twenty" -> 20; "twenty five", "quarter" -> 25; "thirty" -> 30; "forty" -> 40
            "fifty", "half" -> 50; "sixty" -> 60; "seventy" -> 70; "seventy five" -> 75; "eighty" -> 80
            "ninety" -> 90; "hundred", "full", "max", "maximum", "highest" -> 100
            else -> null
        }
    }

    /** Returns a command only when a phrase matches strictly; anything else returns null so the model handles it. */
    fun parse(input: String): ParsedCommand? {
        val orig = input
            .replace(Regex("(?i)^\\s*(hey nexa|hi nexa|hello nexa|ok nexa|nexa)[,\\s]*"), "")
            .replace(Regex("[.,!?:;\"'\\s]+$"), "")
            .trim()
        if (orig.isBlank() || orig.length > 300) return null
        val low = orig.lowercase(Locale.ROOT)
        val body = if (low.length == orig.length) orig else low // original casing for message bodies when safe

        // 1. flashlight
        Regex("$P(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?$FLASH$").find(low)?.let { return flash(it.groupValues[1]) }
        Regex("$P(?:turn|switch)\\s+(?:the\\s+)?$FLASH\\s+(on|off)$").find(low)?.let { return flash(it.groupValues[1]) }
        Regex("^$FLASH\\s+(on|off)$").find(low)?.let { return flash(it.groupValues[1]) }
        if (Regex("^(?:টর্চ|ফ্ল্যাশলাইট)\\s+(?:চালু|অন)(?:\\s+(?:করো|কর|করুন))?$").matches(low)) return flash("on")
        if (Regex("^(?:টর্চ|ফ্ল্যাশলাইট)\\s+(?:বন্ধ|অফ)(?:\\s+(?:করো|কর|করুন))?$").matches(low)) return flash("off")

        // 2. youtube subscribe / fullscreen (before generic phrases)
        if (Regex("$P(?:subscribe|hit\\s+subscribe|click\\s+subscribe|subscribe\\s+to\\s+(?:this|the)\\s+channel)$").matches(low))
            return cmd("youtube_subscribe", 0.97f, "YOUTUBE_SUBSCRIBE") {}
        if (Regex("$P(?:make\\s+it\\s+|go\\s+)?full\\s*screen(?:\\s+mode)?$").matches(low) ||
            Regex("$P(?:expand|maximi[sz]e)\\s+(?:the\\s+)?(?:video|player)$").matches(low))
            return cmd("youtube_fullscreen", 0.97f, "YOUTUBE_FULLSCREEN") {}

        // 3. brightness (absolute only: NEXA's set_brightness has no relative mode)
        Regex("$P(?:set|make|turn|put|bring)?\\s*(?:the\\s+)?(?:screen\\s+)?brightness\\s*(?:to|at)?\\s*$LEVEL\\s*%?$").find(low)?.let { m ->
            parseNumericLevel(m.groupValues[1])?.let { lvl -> return cmd("set_brightness", 0.96f, "BRIGHTNESS_SET") { put("level_percent", lvl) } }
        }
        if (low in setOf("maximum brightness", "full brightness", "max brightness", "brightest")) return cmd("set_brightness", 0.95f, "BRIGHTNESS_MAX") { put("level_percent", 100) }
        if (low in setOf("minimum brightness", "lowest brightness", "min brightness", "dimmest")) return cmd("set_brightness", 0.95f, "BRIGHTNESS_MIN") { put("level_percent", 0) }

        // 4. volume
        Regex("$P(?:set|make|turn|put|bring)?\\s*(?:the\\s+)?(?:media\\s+|sound\\s+)?volume\\s*(?:to|at)?\\s*$LEVEL\\s*%?$").find(low)?.let { m ->
            parseNumericLevel(m.groupValues[1])?.let { lvl -> return volume("set", lvl, "VOLUME_SET") }
        }
        if (low in VOLUME_UP) return volume("up", null, "VOLUME_UP")
        if (low in VOLUME_DOWN) return volume("down", null, "VOLUME_DOWN")
        if (low in setOf("mute", "mute volume", "mute the volume", "mute it", "silence media")) return volume("mute", null, "VOLUME_MUTE")
        if (low in setOf("unmute", "unmute volume", "unmute the volume")) return volume("unmute", null, "VOLUME_UNMUTE")
        if (low in setOf("full volume", "max volume", "maximum volume", "volume max", "loudest")) return volume("set", 100, "VOLUME_MAX")

        // 5. media keys
        MEDIA_PHRASES.entries.firstOrNull { low in it.value }?.let { e ->
            return cmd("media_control", 0.98f, "MEDIA_${e.key.uppercase()}") { put("action", e.key) }
        }

        // 6. youtube play / search (platform must be named: "play X" alone could mean any music app)
        Regex("$P(?:play)\\s+(.+?)\\s+(?:on|in)\\s+youtube$").find(body.lowercase(Locale.ROOT))?.let { m ->
            val q = slice(body, m, 1)
            return if (isAmbiguousPlayQuery(q)) null else cmd("youtube_play", 0.95f, "YOUTUBE_PLAY") { put("query", q) }
        }
        Regex("$P(?:search|find|look\\s+up)\\s+(.+?)\\s+(?:on|in)\\s+youtube$").find(low)?.let { m ->
            return cmd("youtube_search", 0.95f, "YOUTUBE_SEARCH") { put("query", slice(body, m, 1)) }
        }

        // 7. whatsapp delete (needs a named contact; "for me" unless "for everyone" is said explicitly)
        Regex("${P}delete\\s+(?:the\\s+)?(?:(?:last|my|that|latest|recent)\\s+)*(?:whatsapp\\s+)?(?:message|msg)(?:\\s+(?:on|from|in)\\s+whatsapp)?(\\s+(?:for|from)\\s+everyone|\\s+for\\s+me)?(?:\\s+(?:to|for|from|with)\\s+$NAME)?$").find(low)?.let { m ->
            val scope = m.groupValues[1].trim()
            val contact = m.groupValues[2].trim()
            if (contact.isBlank() || contact in NON_PEOPLE) return null
            return cmd("whatsapp_delete_message", 0.94f, "WHATSAPP_DELETE") {
                put("contact_name", slice(body, m, 2)); put("for_everyone", scope.endsWith("everyone"))
            }
        }

        // 8. whatsapp call
        Regex("$P(?:make\\s+(?:a\\s+)?)?whatsapp\\s+(video\\s+)?call\\s+(?:to\\s+)?$NAME$").find(low)?.let { m ->
            val who = m.groupValues[2].trim()
            if (who.isNotBlank() && who !in NON_PEOPLE) return cmd("whatsapp_call", 0.96f, "WHATSAPP_CALL") { put("contact_name", slice(body, m, 2)); put("video", m.groupValues[1].isNotBlank()) }
        }
        Regex("$P(?:make\\s+(?:a\\s+)?)?(video\\s+)?call\\s+(?:to\\s+)?$NAME\\s+on\\s+whatsapp$").find(low)?.let { m ->
            val who = m.groupValues[2].trim()
            if (who.isNotBlank() && who !in NON_PEOPLE) return cmd("whatsapp_call", 0.96f, "WHATSAPP_CALL") { put("contact_name", slice(body, m, 2)); put("video", m.groupValues[1].isNotBlank()) }
        }

        // 9. whatsapp message: explicit separator required so a name is never confused with the text
        val waMsg = listOf(
            Regex("$P(?:send\\s+(?:a\\s+)?)?(?:whatsapp\\s+)?(?:message|msg)\\s+(?:to\\s+)?$NAME\\s+(?:saying|that|:)\\s*(.+)$"),
            Regex("$P(?:send\\s+(?:a\\s+)?)?whatsapp\\s+(?:message\\s+)?(?:to\\s+)?$NAME\\s+(?:saying|that|:)\\s*(.+)$")
        )
        for (re in waMsg) {
            val m = re.find(low) ?: continue
            val who = m.groupValues[1].trim()
            val text = slice(body, m, 2).trim()
            if (who.isNotBlank() && text.isNotBlank() && who !in NON_PEOPLE && low.contains("whatsapp")) {
                return cmd("send_whatsapp_message", 0.95f, "WHATSAPP_MESSAGE") { put("contact_name", slice(body, m, 1).trim()); put("message", text) }
            }
        }

        // 10. cellular call
        Regex("$P(?:make\\s+(?:a\\s+)?(?:phone\\s+)?call\\s+to|phone\\s+call\\s+to|dial|call(?:\\s+to)?)\\s+([\\p{L}\\p{N}+][\\p{L}\\p{M}\\p{N}\\u200c\\u200d+ .'_-]{0,40}?)$").find(low)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotBlank() && target !in NON_PEOPLE && !low.contains("whatsapp") && !low.contains("video")) {
                val digits = target.replace(Regex("[\\s-]"), "")
                return if (digits.matches(Regex("^\\+?\\d{5,15}$"))) cmd("make_call", 0.95f, "CALL_NUMBER") { put("phone_number", digits) }
                else cmd("make_call", 0.95f, "CALL_CONTACT") { put("contact_name", slice(body, m, 1).trim()) }
            }
        }

        // 11. web: only with an explicit domain/website hint (otherwise "open youtube" means the app)
        Regex("$P(?:open|go\\s+to|launch|browse)\\s+(.+)$").find(low)?.let { m ->
            val rest = slice(body, m, 1).trim()
            val hasHint = Regex("\\b[a-z0-9-]+\\.(com|org|net|in|io|co|ai|edu|gov|app|dev|me)\\b").containsMatchIn(rest.lowercase(Locale.ROOT)) ||
                Regex("(?i)\\b(website|site|in browser|in chrome|on chrome)\\b").containsMatchIn(rest)
            if (hasHint && DomainNormalizer.extractMultipleUrls(rest).isNotEmpty()) return cmd("open_url", 0.92f, "OPEN_URL") { put("url", rest) }
        }
        Regex("$P(?:google|search\\s+(?:on\\s+)?google\\s+for)\\s+(.+)$").find(low)?.let { m ->
            return cmd("open_url", 0.92f, "WEB_SEARCH") { put("query", slice(body, m, 1).trim()) }
        }

        // 12. open app
        Regex("$P(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(low)?.let { m ->
            val name = slice(body, m, 1).trim()
            if (name.isNotBlank() && name.length <= 40 && !name.contains(" and ") && name.lowercase() !in NON_PEOPLE)
                return cmd("open_app", 0.90f, "OPEN_APP") { put("app_name", name) }
        }
        return null
    }

    private fun flash(onOff: String) = cmd("toggle_flashlight", 0.98f, if (onOff == "on") "FLASH_ON" else "FLASH_OFF") { put("enabled", onOff == "on") }

    private fun volume(action: String, level: Int?, pattern: String) = cmd("set_volume", 0.95f, pattern) {
        put("stream", "media"); put("action", action); if (level != null) put("level_percent", level)
    }

    /** Group [n] of [m] taken from [body] (original casing) by position. */
    private fun slice(body: String, m: MatchResult, n: Int): String {
        val r = m.groups[n]?.range ?: return ""
        return if (r.last < body.length) body.substring(r.first, r.last + 1) else m.groupValues[n]
    }

    private val VOLUME_UP = setOf(
        "volume up", "increase volume", "louder", "turn up volume", "turn up the volume", "raise volume", "raise the volume",
        "more volume", "make it louder", "up the volume", "sound up", "increase the volume"
    )
    private val VOLUME_DOWN = setOf(
        "volume down", "decrease volume", "lower volume", "softer", "turn down volume", "turn down the volume", "reduce volume",
        "reduce the volume", "less volume", "decrease the volume", "lower the volume", "make it quieter", "quieter", "sound down"
    )
    private val MEDIA_PHRASES: Map<String, Set<String>> = mapOf(
        "pause" to setOf("pause", "pause music", "pause video", "pause playback", "pause the music", "pause the video", "stop playing"),
        "play" to setOf("resume", "resume music", "resume playback", "continue playback", "resume the music", "continue playing", "resume playing"),
        "next" to setOf("next", "next video", "next song", "skip", "next track", "play next"),
        "previous" to setOf("previous", "previous video", "previous song", "previous track", "play previous")
    )
}
