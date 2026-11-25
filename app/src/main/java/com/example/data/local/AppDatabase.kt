package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room ডাটাবেজ কনফিগারেশন (ভার্সন ২)।
 * ভার্সন ১ থেকে ২-এ ActionLogEntity টেবিল যুক্ত করা হয়েছে এবং নিরাপদ MIGRATION_1_2 দেওয়া হয়েছে।
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ActionLogEntity::class,
        MemoryEntity::class,
        RoutineEntity::class,
        UsageStatEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `action_logs` (
                        `id` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `argsSummary` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_action_logs_timestamp` ON `action_logs` (`timestamp`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `keywords` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_category` ON `memories` (`category`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL,
                        `minute` INTEGER NOT NULL,
                        `daysMask` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `oneShot` INTEGER NOT NULL,
                        `lastRunAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_enabled` ON `routines` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_hour_minute` ON `routines` (`hour`, `minute`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_stats` (
                        `id` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `modelName` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `success` INTEGER NOT NULL,
                        `wasFailover` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_stats_timestamp` ON `usage_stats` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_stats_modelId` ON `usage_stats` (`modelId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_assistant_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
