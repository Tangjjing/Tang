package com.dschat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationMeta(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET systemPrompt = :prompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, prompt: String?)

    @Query("UPDATE conversations SET model = :model WHERE id = :id")
    suspend fun updateConversationModel(id: Long, model: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    /** Delete every message AFTER [afterId] in a conversation (keeps [afterId] itself). For 重新生成. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterId")
    suspend fun deleteMessagesAfter(conversationId: Long, afterId: Long)

    /** Delete [fromId] and every message after it in a conversation. For 编辑后重发. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun messageCount(conversationId: Long): Int
}
