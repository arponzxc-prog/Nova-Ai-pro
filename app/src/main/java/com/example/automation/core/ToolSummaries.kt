/* © ARPON — original NEXA AI code. Non-sensitive one-line summaries for action cards/logs of the Step 2 tools. */
package com.example.automation.core

import org.json.JSONObject

object ToolSummaries {
    fun summary(toolName: String, args: JSONObject): String {
        val who = args.optString("contact_name", "").ifBlank { args.optString("phone_number", "") }
        return when (toolName) {
            "whatsapp_call" -> who + if (args.optBoolean("video", false)) " (ভিডিও)" else " (ভয়েস)"
            "whatsapp_delete_message" -> who + if (args.optBoolean("for_everyone", false)) " — সবার জন্য" else " — শুধু আমার জন্য"
            "open_url" -> args.optString("url", args.optString("query", ""))
            "youtube_play", "youtube_search" -> args.optString("query", "")
            "media_control" -> args.optString("action", "")
            else -> ""
        }
    }
}
