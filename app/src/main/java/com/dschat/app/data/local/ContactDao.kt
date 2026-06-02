package com.dschat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ContactDao {

    @Insert
    suspend fun insert(message: ContactMessageEntity): Long

    /** Most recent messages for one contact, returned oldest→newest for LLM context. */
    @Query("SELECT * FROM (SELECT * FROM contact_messages WHERE contactKey = :key ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    suspend fun recentFor(key: String, limit: Int): List<ContactMessageEntity>

    @Query("DELETE FROM contact_messages WHERE contactKey = :key")
    suspend fun clearContact(key: String)

    @Query("SELECT COUNT(*) FROM contact_messages WHERE contactKey = :key")
    suspend fun countFor(key: String): Int
}
