package com.dschat.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val model: String,
    val systemPrompt: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

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
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val createdAt: Long
)

/** An auto-detected to-do, distilled by AI from an incoming notification. */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String = "",
    val sourceApp: String = "",
    val sourceLabel: String = "",
    val originalText: String = "",
    val dueAt: Long? = null,
    val priority: Int = 1,            // 0 low · 1 medium · 2 high
    val status: String = TaskStatus.PENDING,
    val suggestedReply: String? = null,
    val notificationKey: String? = null,
    val createdAt: Long = 0,
    /** If this task was auto-written to the system calendar, its event id (for undo). */
    val calendarEventId: Long? = null
)

object TaskStatus {
    const val PENDING = "PENDING"
    const val SNOOZED = "SNOOZED"
    const val DONE = "DONE"
    const val DISMISSED = "DISMISSED"
}

/** One message in a per-contact auto-reply thread (incoming from them, or our reply). Persistent. */
@Entity(tableName = "contact_messages", indices = [Index("contactKey")])
data class ContactMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactKey: String,   // "<package>|<sender>"
    val app: String,
    val contact: String,
    val role: String,         // "them" (incoming) / "me" (our reply)
    val text: String,
    val createdAt: Long
)
