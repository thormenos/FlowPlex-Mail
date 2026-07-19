package com.fcarreau.flowplexmail.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE status = 'pending' ORDER BY receivedAtMillis DESC")
    fun observePending(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE status = 'pending' AND hasListUnsubscribe = 1")
    fun observeUnsubscribableCount(): Flow<Int>

    @Query(
        "SELECT category, COUNT(*) as count, " +
            "SUM(CASE WHEN hasListUnsubscribe THEN 1 ELSE 0 END) as unsubscribableCount " +
            "FROM messages WHERE status = 'pending' GROUP BY category",
    )
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}

data class CategoryCount(
    val category: String,
    val count: Int,
    val unsubscribableCount: Int,
)
