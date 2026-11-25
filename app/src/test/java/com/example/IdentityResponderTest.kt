package com.example

import com.example.model.InputMode
import com.example.tools.IdentityResponder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityResponderTest {

    @Test
    fun `positive identity queries in Bengali, Banglish, and English return NOVA AI and ARPON`() {
        val nameQueries = listOf(
            "তোমার নাম কী?",
            "তোমার নাম কি",
            "আপনার শুভ নাম কী?",
            "তুমি কে?",
            "আপনি কে",
            "তোমার পরিচয় দাও",
            "তোমাকে কী নামে ডাকব?",
            "What is your name?",
            "what's your name?",
            "Who are you?",
            "Tell me your name",
            "Introduce yourself",
            "tomar nam ki",
            "apnar nam ki",
            "tumi ke?"
        )
        nameQueries.forEach { q ->
            val reply = IdentityResponder.match(q, InputMode.TEXT)
            assertNotNull("Expected identity match for: $q", reply)
            assertTrue("Expected NOVA AI in reply for: $q", reply!!.replyText.contains("NOVA AI"))
        }

        val creatorQueries = listOf(
            "তোমাকে কে বানিয়েছে?",
            "তোমাকে কে তৈরি করেছে?",
            "তোমার ডেভেলপার কে?",
            "আপনার নির্মাতা কে?",
            "নেক্সাকে কে ডেভেলপ করেছে?",
            "Who created you?",
            "Who made you?",
            "Who is your developer?",
            "Who built Nexa?",
            "Who built Nova?",
            "tomake ke baniyeche",
            "tomar developer ke"
        )
        creatorQueries.forEach { q ->
            val reply = IdentityResponder.match(q, InputMode.TEXT)
            assertNotNull("Expected creator match for: $q", reply)
            assertTrue("Expected ARPON in reply for: $q", reply!!.replyText.contains("ARPON"))
        }

        // Voice mode produces short natural spoken greeting
        val voiceBn = IdentityResponder.match("তোমার নাম কী?", InputMode.VOICE)
        assertEquals(IdentityResponder.IdentityKind.NAME, voiceBn?.kind)
        assertTrue(voiceBn?.replyText?.contains("NOVA AI") == true)

        val voiceEn = IdentityResponder.match("What is your name?", InputMode.VOICE)
        assertEquals(true, voiceEn?.isEnglish)
        assertTrue(voiceEn?.replyText?.contains("NOVA AI") == true)
    }

    @Test
    fun `false positive queries about user name, files, or coding tasks return null`() {
        val nonIdentityQueries = listOf(
            "আমার নাম কি?",
            "amar nam ki",
            "What is my name?",
            "রহিমের নাম কী ছিল?",
            "এই ফাইলের নাম কী?",
            "What is the file name?",
            "package name বলো",
            "আমার জন্য একটা অ্যাপ তৈরি করে দাও",
            "একটা ওয়েবসাইট বানিয়ে দাও",
            "Write a Kotlin function to sort a list",
            "How to create an Android app?",
            "Who created Python?",
            "Who created Java?",
            "তোমার নাম পরিবর্তন করে রাখো Jarvis",
            "Change your name to Max",
            ""
        )
        nonIdentityQueries.forEach { q ->
            assertNull("Expected NO identity match for false-positive query: $q", IdentityResponder.match(q, InputMode.TEXT))
        }
    }
}
