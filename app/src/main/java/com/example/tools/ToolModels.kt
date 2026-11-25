package com.example.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * টুলের ঝুঁকির মাত্রা।
 * LOW: সরাসরি চলবে
 * MEDIUM: ডিফল্টে সরাসরি চলবে (সেটিংস থেকে জিজ্ঞাসা করা অন করা যাবে)
 * HIGH: সবসময় ইউজার কনফার্মেশন বাধ্যতামূলক (কোডে এনফোর্স করা)
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class SchemaType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ARRAY
}

data class ParameterProperty(
    val type: SchemaType,
    val description: String,
    val enumValues: List<String>? = null,
    val itemType: SchemaType? = null
)

data class ToolParametersSchema(
    val properties: Map<String, ParameterProperty>,
    val required: List<String> = emptyList()
) {
    /**
     * OpenAI ও Anthropic-এর জন্য স্ট্যান্ডার্ড JSON Schema (lowercase types) তৈরি করে।
     */
    fun toJsonSchema(uppercaseTypes: Boolean = false): JSONObject {
        val propsObj = JSONObject()
        for ((key, prop) in properties) {
            val typeStr = if (uppercaseTypes) prop.type.name else prop.type.name.lowercase()
            val propJson = JSONObject().apply {
                put("type", typeStr)
                put("description", prop.description)
                if (!prop.enumValues.isNullOrEmpty()) {
                    put("enum", JSONArray(prop.enumValues))
                }
                if (prop.type == SchemaType.ARRAY && prop.itemType != null) {
                    val itemTypeStr = if (uppercaseTypes) prop.itemType.name else prop.itemType.name.lowercase()
                    put("items", JSONObject().put("type", itemTypeStr))
                }
            }
            propsObj.put(key, propJson)
        }
        return JSONObject().apply {
            put("type", if (uppercaseTypes) "OBJECT" else "object")
            put("properties", propsObj)
            if (required.isNotEmpty()) {
                put("required", JSONArray(required))
            }
        }
    }
}

data class ContactMatch(
    val name: String,
    val phoneNumber: String,
    val typeLabel: String = ""
)

data class ToolExecutionContext(
    val appContext: Context,
    val preferredSimSlot: Int = -1,
    val accessPolicy: com.example.accessibility.AppAccessPolicy = com.example.accessibility.AppAccessPolicy(),
    val notifAllowedPackages: Set<String> = emptySet(),
    val screenshotVisionEnabled: Boolean = false,
    val modelSupportsVision: Boolean = false
)

/**
 * প্রতিটি টুল চালানোর পর এই স্ট্যান্ডার্ড ফলাফল ফেরত আসে।
 * transientImageBase64 কখনো ডিস্কে বা লগে সেভ হয় না, কেবল চলমান মেমোরিতে ভিশন মডেলের জন্য ব্যবহৃত হয়।
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null,
    val transientImageBase64: String? = null
) {
    fun toJsonString(): String {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            if (data != null) put("data", data)
        }.toString()
    }
}

/**
 * প্রতিটি ফোনের অ্যাকশন একটি ToolDefinition হিসেবে রেজিস্টার হয়।
 */
data class ToolDefinition(
    val name: String,
    val bengaliTitle: String,
    val description: String,
    val parametersSchema: ToolParametersSchema,
    val riskLevel: RiskLevel,
    val requiredPermissions: List<String> = emptyList(),
    val requiresWriteSettings: Boolean = false,
    val executor: suspend (context: ToolExecutionContext, args: JSONObject) -> ToolResult
)
