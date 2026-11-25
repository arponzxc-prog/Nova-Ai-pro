package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // পিন করা চ্যাট সবসময় উপরে, এরপর সর্বশেষ আপডেট অনুযায়ী সাজানো
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC LIMIT 1")
    suspend fun getLatestConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecentlyUpdatedConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun updateConversationPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTimestamp(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    // বাংলা ও ইংরেজি উভয় ভাষায় সব চ্যাটের মেসেজ এবং শিরোনাম থেকে সার্চ
    @Query(
        """
        SELECT m.id AS messageId,
               m.conversationId AS conversationId,
               c.title AS conversationTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE m.content LIKE '%' || :query || '%' OR c.title LIKE '%' || :query || '%'
        ORDER BY m.timestamp DESC
        LIMIT 50
        """
    )
    fun searchMessages(query: String): Flow<List<MessageSearchRow>>

    // ==================== অ্যাকশন লগ কুয়েরি (ধাপ ৩) ====================

    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeActionLogs(): Flow<List<ActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionLog(log: ActionLogEntity)

    @Query("DELETE FROM action_logs")
    suspend fun clearActionLogs()

    // ==================== দীর্ঘমেয়াদী মেমোরি (Memories) ====================

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // ==================== শিডিউলড রুটিন (Routines) ====================

    @Query("SELECT * FROM routines ORDER BY hour ASC, minute ASC")
    fun observeAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE enabled = 1")
    suspend fun getEnabledRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun getRoutineById(id: String): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity)

    @Query("UPDATE routines SET enabled = :enabled WHERE id = :id")
    suspend fun setRoutineEnabled(id: String, enabled: Boolean)

    @Query("UPDATE routines SET lastRunAt = :lastRunAt, enabled = :enabled WHERE id = :id")
    suspend fun updateRoutineAfterRun(id: String, lastRunAt: Long, enabled: Boolean)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutineById(id: String)

    // ==================== ব্যবহার ও টোকেন পরিসংখ্যান (Usage Stats) ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageStat(stat: UsageStatEntity)

    @Query("SELECT * FROM usage_stats WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    fun observeUsageStatsSince(sinceMs: Long): Flow<List<UsageStatEntity>>

    @Query("SELECT * FROM usage_stats ORDER BY timestamp DESC")
    fun observeAllUsageStats(): Flow<List<UsageStatEntity>>

    @Query(
        """
        SELECT modelId,
               modelName,
               providerType,
               COUNT(*) AS requestCount,
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCount,
               SUM(inputTokens) AS totalInputTokens,
               SUM(outputTokens) AS totalOutputTokens
        FROM usage_stats
        GROUP BY modelId
        ORDER BY requestCount DESC
        """
    )
    fun observeModelUsageSummary(): Flow<List<ModelUsageSummary>>

    @Query("DELETE FROM usage_stats")
    suspend fun clearUsageStats()
}
