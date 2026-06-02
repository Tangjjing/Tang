package com.dschat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    // pending/snoozed first, then done; within that by priority desc, then due/created time
    @Query(
        """SELECT * FROM tasks WHERE status != 'DISMISSED'
           ORDER BY (CASE status WHEN 'DONE' THEN 1 ELSE 0 END) ASC,
                    priority DESC,
                    COALESCE(dueAt, createdAt) ASC"""
    )
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'PENDING' OR status = 'SNOOZED'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE tasks SET dueAt = :dueAt, status = 'SNOOZED' WHERE id = :id")
    suspend fun snooze(id: Long, dueAt: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    /** De-dup helper: how many identical-text tasks were created since :since. */
    @Query("SELECT COUNT(*) FROM tasks WHERE originalText = :text AND createdAt > :since")
    suspend fun countRecentSame(text: String, since: Long): Int

    @Query("UPDATE tasks SET calendarEventId = :eid WHERE id = :id")
    suspend fun setCalendarEvent(id: Long, eid: Long?)

    /** De-dup helper for auto-scheduled calendar events: same title+time created since :since. */
    @Query("SELECT COUNT(*) FROM tasks WHERE title = :title AND dueAt = :due AND calendarEventId IS NOT NULL AND createdAt > :since")
    suspend fun countAutoEvent(title: String, due: Long, since: Long): Int

    /** Pending/snoozed tasks still due in the future — used to re-arm alarms after reboot. */
    @Query("SELECT * FROM tasks WHERE (status = 'PENDING' OR status = 'SNOOZED') AND dueAt IS NOT NULL AND dueAt > :now")
    suspend fun dueAfter(now: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE status = 'DONE' OR status = 'DISMISSED'")
    suspend fun clearFinished()

    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}
