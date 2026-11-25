package com.example.data

import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.InputMode
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * চ্যাট ব্যাকআপ ও এক্সপোর্ট ম্যানেজার:
 * - JSON ও Markdown আকারে সব কথোপকথন এক্সপোর্ট করে
 * - JSON ব্যাকআপ যাচাই করে ইমপোর্ট করে
 * - গোপনীয়তা: API Key বা সিক্রেট কখনোই এক্সপোর্টে যুক্ত হয় না
 */
object ChatBackupManager {

    private const val BACKUP_FORMAT_TAG = "nexa_ai_chat_backup_v1"

    fun exportJson(
        items: List<Pair<Conversation, List<ChatMessage>>>,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val convArray = JSONArray()
        for ((conv, messages) in items) {
            val msgArray = JSONArray()
            for (msg in messages) {
                msgArray.put(JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("inputMode", msg.inputMode.name)
                    put("timestamp", msg.timestamp)
                })
            }
            convArray.put(JSONObject().apply {
                put("id", conv.id)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)
                put("pinned", conv.pinned)
                put("messages", msgArray)
            })
        }

        return JSONObject().apply {
            put("format", BACKUP_FORMAT_TAG)
            put("app", "NOVA AI")
            put("exportedAt", exportedAtMs)
            put("conversations", convArray)
        }.toString(2)
    }

    fun parseJsonBackup(jsonStr: String): List<Pair<Conversation, List<ChatMessage>>> {
        val root = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("সঠিক JSON ফাইল নয়।")
        }

        val convArray = root.optJSONArray("conversations")
            ?: throw IllegalArgumentException("ব্যাকআপ ফাইলে 'conversations' পাওয়া যায়নি।")

        val result = mutableListOf<Pair<Conversation, List<ChatMessage>>>()
        val now = System.currentTimeMillis()

        for (i in 0 until convArray.length()) {
            val cObj = convArray.optJSONObject(i) ?: continue
            val convId = cObj.optString("id", "").trim().ifEmpty { UUID.randomUUID().toString() }
            val conv = Conversation(
                id = convId,
                title = cObj.optString("title", "ইমপোর্ট করা চ্যাট").ifBlank { "ইমপোর্ট করা চ্যাট" },
                createdAt = cObj.optLong("createdAt", now),
                updatedAt = cObj.optLong("updatedAt", now),
                pinned = cObj.optBoolean("pinned", false)
            )
            val msgsArray = cObj.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<ChatMessage>()
            for (j in 0 until msgsArray.length()) {
                val mObj = msgsArray.optJSONObject(j) ?: continue
                val content = mObj.optString("content", "")
                if (content.isBlank()) continue
                val role = mObj.optString("role", "user").let {
                    if (it in setOf("user", "assistant", "system")) it else "user"
                }
                val inputMode = runCatching {
                    InputMode.valueOf(mObj.optString("inputMode", InputMode.TEXT.name))
                }.getOrDefault(InputMode.TEXT)

                msgs.add(
                    ChatMessage(
                        id = mObj.optString("id", "").trim().ifEmpty { UUID.randomUUID().toString() },
                        conversationId = convId,
                        role = role,
                        content = content,
                        inputMode = inputMode,
                        timestamp = mObj.optLong("timestamp", now)
                    )
                )
            }
            result.add(conv to msgs)
        }
        return result
    }

    fun exportMarkdown(
        items: List<Pair<Conversation, List<ChatMessage>>>,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            append("# NOVA AI — Chat Export\n")
            append("_Exported: ${sdf.format(Date(exportedAtMs))}_\n\n")
            if (items.isEmpty()) {
                append("_কোনো কথোপকথন নেই।_\n")
                return@buildString
            }
            for ((conv, messages) in items) {
                append("---\n\n")
                append("## ${conv.title}\n")
                append("_Updated: ${sdf.format(Date(conv.updatedAt))}_\n\n")
                for (msg in messages) {
                    val speaker = when (msg.role) {
                        "assistant" -> "NOVA AI"
                        "system" -> "System"
                        else -> "User"
                    }
                    append("### $speaker (${sdf.format(Date(msg.timestamp))})\n")
                    append("${msg.content}\n\n")
                }
            }
        }
    }
}
