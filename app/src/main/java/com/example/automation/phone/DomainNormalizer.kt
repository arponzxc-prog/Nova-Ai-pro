/*
 * Derived from the JARVIS project (com.jarvis.assistant.automation.phone.DomainNormalizer).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: structured result with a confidence level, scheme allow-list (http/https only), guessed
 * ".com" domains are reported as GUESSED so callers can refuse to open them. Modifications © ARPON.
 */
package com.example.automation.phone

import java.util.Locale
import kotlin.math.min

enum class UrlConfidence { EXPLICIT, ALIAS, FUZZY, GUESSED }

data class UrlResolution(val url: String, val confidence: UrlConfidence)

object DomainNormalizer {

    private val KNOWN_DOMAINS = mapOf(
        // Tech & Dev
        "vercel" to "vercel.com",
        "wersel" to "vercel.com",
        "varsel" to "vercel.com",
        "versal" to "vercel.com",
        "vercell" to "vercel.com",
        "21st" to "21st.dev",
        "twenty first" to "21st.dev",
        "twenty-first" to "21st.dev",
        "21st dev" to "21st.dev",
        "twenty first dev" to "21st.dev",
        "github" to "github.com",
        "git hub" to "github.com",
        "git-hub" to "github.com",
        "gethub" to "github.com",
        "geethub" to "github.com",
        "gitlab" to "gitlab.com",
        "git lab" to "gitlab.com",
        "stackoverflow" to "stackoverflow.com",
        "stack overflow" to "stackoverflow.com",
        "chatgpt" to "chatgpt.com",
        "chat gpt" to "chatgpt.com",
        "chad gpt" to "chatgpt.com",
        "openai" to "openai.com",
        "open ai" to "openai.com",
        "anthropic" to "anthropic.com",
        "claude" to "claude.ai",
        "gemini" to "gemini.google.com",
        "google flow" to "labs.google/flow",
        "flow" to "labs.google/flow",
        "flow google" to "labs.google/flow",
        "googleflow" to "labs.google/flow",
        "flow website" to "labs.google/flow",
        "runway" to "runwayml.com",
        "runwayml" to "runwayml.com",
        "pika" to "pika.art",
        "midjourney" to "midjourney.com",
        "canva" to "canva.com",
        "leonardo" to "leonardo.ai",
        "elevenlabs" to "elevenlabs.io",
        "suno" to "suno.com",
        "luma" to "lumalabs.ai",
        "kling" to "klingai.com",
        "huggingface" to "huggingface.co",
        "hugging face" to "huggingface.co",
        "firebase" to "firebase.google.com",
        "aws" to "aws.amazon.com",

        // Search & Google
        "google" to "google.com",
        "gmail" to "mail.google.com",
        "g mail" to "mail.google.com",
        "google mail" to "mail.google.com",
        "google drive" to "drive.google.com",
        "gdrive" to "drive.google.com",
        "drive" to "drive.google.com",
        "google maps" to "maps.google.com",
        "maps" to "maps.google.com",

        // Social Media & Video
        "youtube" to "youtube.com",
        "you tube" to "youtube.com",
        "u tube" to "youtube.com",
        "utube" to "youtube.com",
        "yt" to "youtube.com",
        "twitter" to "x.com",
        "twiter" to "x.com",
        "x" to "x.com",
        "facebook" to "facebook.com",
        "face book" to "facebook.com",
        "fb" to "facebook.com",
        "instagram" to "instagram.com",
        "insta gram" to "instagram.com",
        "insta" to "instagram.com",
        "linkedin" to "linkedin.com",
        "link din" to "linkedin.com",
        "linked in" to "linkedin.com",
        "linkdin" to "linkedin.com",
        "reddit" to "reddit.com",
        "red it" to "reddit.com",
        "pinterest" to "pinterest.com",
        "pin terest" to "pinterest.com",
        "snapchat" to "snapchat.com",
        "snap" to "snapchat.com",
        "telegram" to "web.telegram.org",
        "discord" to "discord.com",
        "whatsapp" to "web.whatsapp.com",

        // Media & Streaming
        "netflix" to "netflix.com",
        "net flix" to "netflix.com",
        "spotify" to "spotify.com",
        "spo ti fy" to "spotify.com",
        "prime video" to "primevideo.com",
        "amazon prime" to "primevideo.com",
        "hotstar" to "hotstar.com",
        "jiocinema" to "jiocinema.com",
        "jio cinema" to "jiocinema.com",

        // Shopping & Food & Finance
        "amazon" to "amazon.in",
        "ama zon" to "amazon.in",
        "flipkart" to "flipkart.com",
        "flip kart" to "flipkart.com",
        "myntra" to "myntra.com",
        "meesho" to "meesho.com",
        "swiggy" to "swiggy.com",
        "zomato" to "zomato.com",
        "blinkit" to "blinkit.com",
        "zepto" to "zepto.com",
        "cricbuzz" to "cricbuzz.com",

        // Reference & Learning
        "wikipedia" to "wikipedia.org",
        "wiki pedia" to "wikipedia.org",
        "coursera" to "coursera.org",
        "udemy" to "udemy.com"
    )

    private val TLDS = "com|org|net|in|io|co|ai|edu|gov|app|dev|me"
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val TLD_HOST = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.($TLDS)(/.*)?$", RegexOption.IGNORE_CASE)

    fun isKnownWebAlias(target: String): Boolean {
        val clean = target.trim().lowercase(Locale.ROOT)
            .replace(Regex("^(https?://|www\\.)"), "")
            .replace(Regex("^(the\\s+)?(website|site|web)\\s+"), "")
            .replace(Regex("\\s+(website|site|web|in browser|in chrome)$"), "")
            .replace(Regex("\\.($TLDS)$"), "")
            .trim()
        return KNOWN_DOMAINS.containsKey(clean) || KNOWN_DOMAINS.containsKey(target.trim().lowercase(Locale.ROOT))
    }

    /**
     * Resolves a spoken/typed website name to an https URL, or null if the input uses a non-web scheme
     * (javascript:, intent:, file:, content:, data: ...) or is blank. Explicit http:// URLs are upgraded to https://.
     */
    fun resolve(raw: String): UrlResolution? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val rest = trimmed.substringAfter("://").trim()
            if (rest.isEmpty() || rest.any { it.isWhitespace() } || rest.startsWith("/")) return null
            return UrlResolution("https://$rest", UrlConfidence.EXPLICIT)
        }
        // "localhost:8080" style has a scheme-like prefix but no "//": treat anything else with a scheme as unsafe.
        if (SCHEME.containsMatchIn(trimmed) && !trimmed.contains(" ") && trimmed.substringBefore(":").none { it == '.' }) return null

        var clean = lower
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\bdot\\s+($TLDS)\\b"), ".$1")
            .replace("twenty first", "21st")
            .replace("twenty-first", "21st")
            .replace(Regex("^(https?://|www\\.)"), "")
            .replace(Regex("^(the\\s+)?(website|site|web)\\s+"), "")
            .replace(Regex("\\s+(website|site|web|in browser|in chrome)$"), "")
            .replace(Regex("[.,/\\s]+$"), "")
        if (clean.isEmpty()) return null

        val withoutExt = clean.replace(Regex("\\.($TLDS)$"), "").trim()

        KNOWN_DOMAINS[clean]?.let { return UrlResolution("https://$it", UrlConfidence.ALIAS) }
        KNOWN_DOMAINS[withoutExt]?.let { return UrlResolution("https://$it", UrlConfidence.ALIAS) }

        // A real-looking host (has a known TLD) is used as typed; it is never "corrected" to a look-alike.
        if (clean.matches(TLD_HOST)) return UrlResolution("https://$clean", UrlConfidence.EXPLICIT)

        var bestDomain: String? = null
        var bestDistance = 2
        for ((key, domain) in KNOWN_DOMAINS) {
            if (key.length >= 5 && withoutExt.length >= 5) {
                val d = levenshtein(withoutExt, key)
                if (d < bestDistance) { bestDistance = d; bestDomain = domain }
            }
        }
        if (bestDomain != null) return UrlResolution("https://$bestDomain", UrlConfidence.FUZZY)

        if (clean.contains(" ") || !clean.matches(Regex("^[a-z0-9-]+$"))) return null
        return UrlResolution("https://$clean.com", UrlConfidence.GUESSED)
    }

    /** Backward-compatible helper: always returns something for non-blank, web-safe input. */
    fun normalizeToUrl(raw: String): String = resolve(raw)?.url ?: ""

    /**
     * Extracts one or more target website URLs from a user speech string.
     * "open vercel.com and github.com" -> [https://vercel.com, https://github.com]
     */
    fun extractMultipleUrls(input: String): List<UrlResolution> {
        val clean = input
            .replace(Regex("(?i)^(hey nexa|hi nexa|hello nexa|ok nexa|nexa|service|please)?\\s*(open|launch|go to|browse|in chrome open|on google chrome open)\\s+"), "")
            .replace(Regex("(?i)\\s+(in chrome|in browser|on chrome|on browser|in multiple tabs|tabs|now|please)$"), "")
            .trim()

        val spaceTokens = clean.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        val domainMatchesCount = spaceTokens.count { TLD_HOST.matches(it) || isKnownWebAlias(it) }

        val rawTokens: List<String> = if (domainMatchesCount > 1) {
            spaceTokens.filter { it.length > 2 && it != "and" && it != "or" && it != "plus" }
        } else {
            clean.split(Regex("(?i)\\s+(?:and|&|or|plus)\\s+|,\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        }

        val out = LinkedHashMap<String, UrlResolution>()
        for (token in rawTokens) resolve(token)?.let { out.putIfAbsent(it.url, it) }
        return out.values.toList()
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
        }
        return dp[s1.length][s2.length]
    }
}
