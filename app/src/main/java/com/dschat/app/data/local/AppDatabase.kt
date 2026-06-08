package com.dschat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, TaskEntity::class, ContactMessageEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 -> v2: add the tasks table (additive, preserves existing conversations/messages).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `tasks` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `title` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `sourceApp` TEXT NOT NULL,
                        `sourceLabel` TEXT NOT NULL,
                        `originalText` TEXT NOT NULL,
                        `dueAt` INTEGER,
                        `priority` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `suggestedReply` TEXT,
                        `notificationKey` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }

        // v2 -> v3: per-conversation system prompt (additive, nullable).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `systemPrompt` TEXT")
            }
        }

        // v3 -> v4: per-contact auto-reply threads.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `contact_messages` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `contactKey` TEXT NOT NULL,
                        `app` TEXT NOT NULL,
                        `contact` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_messages_contactKey` ON `contact_messages` (`contactKey`)")
            }
        }

        // v4 -> v5: tasks.calendarEventId (for auto-scheduled events + undo). Additive, nullable.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `calendarEventId` INTEGER")
            }
        }

        // v5 -> v6: messages.toolRunsJson — persist agent tool-call groups so they survive reload.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `toolRunsJson` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "deepseek_chat.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
