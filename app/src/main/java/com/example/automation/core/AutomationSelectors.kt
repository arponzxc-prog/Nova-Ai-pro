/*
 * © ARPON — original NEXA AI code. Selector lists for WhatsApp call/delete and YouTube UI automation.
 * Defaults are derived from the JARVIS accessibility service (see THIRD_PARTY_NOTICES.md). They can be overridden
 * WITHOUT a code change by placing a JSON file at <app files dir>/automation_selectors.json, e.g.
 *   {"whatsapp_voice_call_ids": ["com.whatsapp:id/voice_call"], "youtube_subscribe_texts": ["subscribe"]}
 * Each key replaces the default list for that key. Unknown keys and malformed files are ignored.
 */
package com.example.automation.core

import android.content.Context
import org.json.JSONObject
import java.io.File

object AutomationSelectors {

    private val defaults: Map<String, List<String>> = mapOf(
        "whatsapp_voice_call_ids" to listOf(
            "com.whatsapp:id/voice_call", "com.whatsapp.w4b:id/voice_call", "com.whatsapp:id/menuitem_conversations_call_voice"
        ),
        "whatsapp_video_call_ids" to listOf(
            "com.whatsapp:id/video_call", "com.whatsapp.w4b:id/video_call", "com.whatsapp:id/menuitem_conversations_call_video"
        ),
        "whatsapp_voice_call_descs" to listOf("voice call", "ভয়েস কল", "audio call"),
        "whatsapp_video_call_descs" to listOf("video call", "ভিডিও কল"),
        "whatsapp_call_confirm_ids" to listOf("com.whatsapp:id/ok", "com.whatsapp:id/call_button", "android:id/button1"),
        "whatsapp_call_confirm_texts" to listOf("call", "কল", "ok", "ঠিক আছে"),
        "whatsapp_delete_ids" to listOf("com.whatsapp:id/menuitem_delete", "com.whatsapp.w4b:id/menuitem_delete"),
        "whatsapp_delete_descs" to listOf("delete", "মুছুন", "মুছে ফেলুন"),
        "whatsapp_delete_for_everyone_texts" to listOf("delete for everyone", "সবার জন্য মুছুন", "সকলের জন্য মুছুন"),
        "whatsapp_delete_for_me_texts" to listOf("delete for me", "আমার জন্য মুছুন", "শুধু আমার জন্য মুছুন"),
        "whatsapp_chat_title_ids" to listOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name"),
        "whatsapp_message_text_ids" to listOf(
            "com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text",
            "com.whatsapp:id/conversation_text_row", "com.whatsapp.w4b:id/conversation_text_row"
        ),
        "youtube_result_descs" to listOf("play video", "ভিডিও চালান"),
        "youtube_packages" to listOf("com.google.android.youtube"),
        "youtube_subscribe_texts" to listOf("subscribe", "সাবস্ক্রাইব"),
        "youtube_subscribed_texts" to listOf("subscribed", "সাবস্ক্রাইবড"),
        "youtube_fullscreen_descs" to listOf("enter fullscreen", "fullscreen", "full screen", "ফুল স্ক্রিন")
    )

    @Volatile private var overrides: Map<String, List<String>> = emptyMap()

    fun get(key: String): List<String> = overrides[key] ?: defaults[key] ?: emptyList()

    /** Safe to call repeatedly; failures leave the built-in defaults in place. */
    fun loadOverrides(context: Context) {
        overrides = parseOverrides(runCatching {
            File(context.filesDir, "automation_selectors.json").takeIf { it.isFile && it.length() < 64 * 1024 }?.readText()
        }.getOrNull())
    }

    internal fun parseOverrides(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, List<String>>()
            for (key in o.keys()) {
                if (key !in defaults) continue
                val arr = o.optJSONArray(key) ?: continue
                val list = (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() && s.length <= 200 } }
                if (list.isNotEmpty()) out[key] = list
            }
            out
        } catch (_: Exception) { emptyMap() }
    }
}
