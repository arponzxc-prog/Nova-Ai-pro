package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.ActionLog
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.InputMode

/**
 * প্রতিটি চ্যাট সেশনের তথ্য সংরক্ষণের জন্য Room Entity।
 * updatedAt এবং pinned-এ ইনডেক্স থাকায় লিস্ট দ্রুত সর্ট হয়।
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["pinned"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned
    )
}

/**
 * প্রতিটি মেসেজের Room Entity।
 * Conversation ডিলিট হলে তার সব মেসেজ স্বয়ংক্রিয়ভাবে ডিলিট হবে (CASCADE)।
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val inputMode: String,
    val timestamp: Long
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        inputMode = runCatching { InputMode.valueOf(inputMode) }.getOrDefault(InputMode.TEXT),
        timestamp = timestamp
    )
}

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputMode = inputMode.name,
    timestamp = timestamp
)

/**
 * ধাপ ৩: ফোন অ্যাকশন লগ সংরক্ষণের জন্য Room Entity।
 */
@Entity(
    tableName = "action_logs",
    indices = [Index(value = ["timestamp"])]
)
data class ActionLogEntity(
    @PrimaryKey val id: String,
    val toolName: String,
    val argsSummary: String,
    val status: String,
    val timestamp: Long
) {
    fun toDomain(): ActionLog = ActionLog(
        id = id,
        toolName = toolName,
        argsSummary = argsSummary,
        status = status,
        timestamp = timestamp
    )
}

/**
 * সার্চ রেজাল্টে কনভারসেশন শিরোনাম ও মিলে যাওয়া মেসেজ একসাথে দেখানোর জন্য প্রোজেকশন।
 */
data class MessageSearchRow(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * দীর্ঘমেয়াদী মেমোরি (Long-Term Memory) Room Entity।
 * category: "name", "preference", "fact"
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["category"])
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val content: String,
    val keywords: String,
    val updatedAt: Long
)

/**
 * শিডিউলড রুটিন ও রিমাইন্ডার Room Entity।
 */
@Entity(
    tableName = "routines",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["hour", "minute"])
    ]
)
data class RoutineEntity(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val daysMask: String, // "DAILY" অথবা কমা-সেপারেটেড দিন সংখ্যা যেমন "1,2,3,4,5" (Calendar.SUNDAY=1..SATURDAY=7)
    val enabled: Boolean = true,
    val oneShot: Boolean = false,
    val lastRunAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * ব্যবহার ও টোকেন পরিসংখ্যান (Usage & Cost Dashboard) Room Entity।
 * গোপনীয়তা: এখানে কখনোই মেসেজের লেখা বা API Key সংরক্ষিত হয় না।
 */
@Entity(
    tableName = "usage_stats",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["modelId"])
    ]
)
data class UsageStatEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val modelName: String,
    val providerType: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val success: Boolean,
    val wasFailover: Boolean,
    val timestamp: Long
)

data class ModelUsageSummary(
    val modelId: String,
    val modelName: String,
    val providerType: String,
    val requestCount: Int,
    val successCount: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long
)
