package com.example

import com.example.voice.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSanitizerTest {

    @Test
    fun `cleanForSpeech removes markdown links and replaces code blocks`() {
        val raw = """
            # শিরোনাম
            এখানে **গুরুত্বপূর্ণ** তথ্য ও [ডকুমেন্টেশন](https://example.com) আছে।
            ```kotlin
            fun hello() = println("Hi")
            ```
            আরও `inlineCode` দেখুন।
        """.trimIndent()

        val cleaned = TextSanitizer.cleanForSpeech(raw, "কোড দেখানো হয়েছে।")
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://example.com"))
        assertFalse(cleaned.contains("println"))
        assertTrue(cleaned.contains("গুরুত্বপূর্ণ"))
        assertTrue(cleaned.contains("ডকুমেন্টেশন"))
        assertTrue(cleaned.contains("কোড দেখানো হয়েছে।"))
        assertTrue(cleaned.contains("inlineCode"))
    }

    @Test
    fun `splitIntoLanguageSegments separates mixed bengali and english cleanly`() {
        val segments = TextSanitizer.splitIntoLanguageSegments("আমি Android অ্যাপ তৈরি করছি।")
        assertEquals(3, segments.size)
        assertEquals("bn", segments[0].locale.language)
        assertEquals("en", segments[1].locale.language)
        assertEquals("bn", segments[2].locale.language)
    }
}
