package com.example.voice

import java.util.Locale

data class SpeechSegment(
    val text: String,
    val locale: Locale
)

/**
 * TTS দিয়ে পড়ার আগে Markdown চিহ্ন, লিংক ও কোড ব্লক পরিষ্কার করার এবং
 * Unicode রেঞ্জ দেখে বাংলা/ইংরেজি ভাষা আলাদা করে বাক্যে ভাগ করার হেল্পার।
 */
object TextSanitizer {
    private val BENGALI_LOCALE = Locale("bn", "BD")
    private val ENGLISH_LOCALE = Locale.US

    private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
    private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
    private val RAW_URL_REGEX = Regex("https?://\\S+")
    private val HEADING_OR_QUOTE_REGEX = Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]\\s+|\\d+\\.\\s+)")
    private val EMPHASIS_REGEX = Regex("(\\*\\*|__|\\*|_|~~)")

    fun containsBengali(text: String): Boolean {
        return text.any { it in '\u0980'..'\u09FF' }
    }

    /**
     * পড়ার জন্য টেক্সট থেকে Markdown, লিংক ও কোড ব্লক পরিষ্কার করে।
     */
    fun cleanForSpeech(rawMarkdown: String, codeReplacementText: String): String {
        var text = rawMarkdown
        // ১. কোড ব্লক সরিয়ে বাংলা বা ইংরেজি নোটিশ বসানো
        text = CODE_BLOCK_REGEX.replace(text, " $codeReplacementText ")
        // ২. Markdown লিংক থেকে শুধু লেবেল রাখা
        text = MARKDOWN_LINK_REGEX.replace(text) { match -> match.groupValues[1] }
        // ৩. সরাসরি URL থাকলে বাদ দেওয়া
        text = RAW_URL_REGEX.replace(text, "")
        // ৪. ইনলাইন কোডের ব্যাকটিক সরানো
        text = INLINE_CODE_REGEX.replace(text) { match -> match.groupValues[1] }
        // ৫. হেডিং, কোট ও লিস্টের চিহ্ন সরানো
        text = HEADING_OR_QUOTE_REGEX.replace(text, "")
        // ৬. Bold/Italic/Strike চিহ্ন সরানো
        text = EMPHASIS_REGEX.replace(text, "")
        // অতিরিক্ত ফাঁকা জায়গা ঠিক করা
        return text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    /**
     * বড় উত্তর দ্রুত শুরু করার জন্য বাক্য ও ভাষা (Unicode রেঞ্জ) অনুযায়ী ভাগ করে।
     */
    fun splitIntoLanguageSegments(cleanText: String): List<SpeechSegment> {
        if (cleanText.isBlank()) return emptyList()

        // বাক্য বিভাজক: বাংলা দাঁড়ি (।), ডট (.), প্রশ্নবোধক (?), বিস্ময়সূচক (!), অথবা নতুন লাইন
        val sentences = cleanText
            .split(Regex("(?<=[।.!?\\n])\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<SpeechSegment>()
        for (sentence in sentences) {
            result.addAll(splitMixedScriptSentence(sentence))
        }
        return result
    }

    /**
     * একটি বাক্যের ভেতর বাংলা (\u0980-\u09FF) ও ইংরেজি (A-Z) মিশ্রিত থাকলে
     * শব্দ ধরে আলাদা অংশে ভাগ করে যেন প্রতিটি অংশ সঠিক অ্যাক্সেন্টে পড়া হয়।
     */
    private fun splitMixedScriptSentence(sentence: String): List<SpeechSegment> {
        val hasBn = sentence.any { it in '\u0980'..'\u09FF' }
        val hasEn = sentence.any { it in 'A'..'Z' || it in 'a'..'z' }

        if (hasBn && !hasEn) return listOf(SpeechSegment(sentence, BENGALI_LOCALE))
        if (!hasBn && hasEn) return listOf(SpeechSegment(sentence, ENGLISH_LOCALE))
        if (!hasBn && !hasEn) return listOf(SpeechSegment(sentence, BENGALI_LOCALE))

        val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        val segments = mutableListOf<SpeechSegment>()
        var currentLocale: Locale? = null
        val buffer = StringBuilder()

        for (word in words) {
            val wordLocale = when {
                word.any { it in '\u0980'..'\u09FF' } -> BENGALI_LOCALE
                word.any { it in 'A'..'Z' || it in 'a'..'z' } -> ENGLISH_LOCALE
                else -> currentLocale ?: BENGALI_LOCALE
            }

            if (currentLocale == null || currentLocale == wordLocale) {
                currentLocale = wordLocale
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(word)
            } else {
                if (buffer.isNotEmpty()) {
                    segments.add(SpeechSegment(buffer.toString(), currentLocale))
                    buffer.clear()
                }
                currentLocale = wordLocale
                buffer.append(word)
            }
        }

        if (buffer.isNotEmpty() && currentLocale != null) {
            segments.add(SpeechSegment(buffer.toString(), currentLocale))
        }
        return segments
    }
}

/**
 * স্ট্রিমিং উত্তরের সময় পুরো লেখা আসার আগেই বাংলা (।) ও ইংরেজি (. ? !) বাক্য শেষ হলে
 * সাথে সাথে TTS কিউতে পাঠানোর জন্য স্ট্রিমিং বাক্য বাফার।
 */
class StreamingSentenceChunker {
    private val buffer = StringBuilder()

    fun reset() {
        buffer.setLength(0)
    }

    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)

        val ready = mutableListOf<String>()
        while (true) {
            val splitAt = findSentenceEndIndex(buffer)
            if (splitAt <= 0) break
            val sentence = buffer.substring(0, splitAt).trim()
            buffer.delete(0, splitAt)
            if (sentence.isNotEmpty()) {
                ready.add(sentence)
            }
        }
        return ready
    }

    fun flush(): String? {
        val remaining = buffer.toString().trim()
        buffer.setLength(0)
        return remaining.takeIf { it.isNotEmpty() }
    }

    private fun findSentenceEndIndex(sb: CharSequence): Int {
        // খোলা কোড ব্লকের (```) ভেতরে থাকলে কোড শেষ না হওয়া পর্যন্ত TTS-এ পাঠানো হবে না
        var fenceCount = 0
        var scanIdx = 0
        while (scanIdx <= sb.length - 3) {
            if (sb[scanIdx] == '`' && sb[scanIdx + 1] == '`' && sb[scanIdx + 2] == '`') {
                fenceCount++
                scanIdx += 3
            } else {
                scanIdx++
            }
        }
        if (fenceCount % 2 != 0) return -1

        var inFence = false
        var i = 0
        while (i < sb.length) {
            if (i <= sb.length - 3 && sb[i] == '`' && sb[i + 1] == '`' && sb[i + 2] == '`') {
                inFence = !inFence
                i += 3
                continue
            }
            if (!inFence) {
                val ch = sb[i]
                when (ch) {
                    '।', '?', '!', '\n' -> {
                        val end = i + 1
                        // খুব ছোট ১-২ অক্ষরের ভগ্নাংশ বাদ দিয়ে অন্তত ২ অক্ষর হলে বাক্য নির্গত করা
                        if (end >= 2) return end
                    }
                    '.' -> {
                        val prev = if (i > 0) sb[i - 1] else ' '
                        val next = if (i + 1 < sb.length) sb[i + 1] else null
                        val isDecimal = prev.isDigit() && (next == null || next.isDigit())
                        if (!isDecimal && next != null && (next.isWhitespace() || next == '\n')) {
                            if (i + 1 >= 3) return i + 1
                        }
                    }
                }
            }
            i++
        }
        return -1
    }
}
