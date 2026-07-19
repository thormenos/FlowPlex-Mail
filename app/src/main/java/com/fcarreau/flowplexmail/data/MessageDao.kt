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

    @Query("SELECT * FROM messages WHERE status = 'pending' AND category = :category ORDER BY receivedAtMillis DESC")
    fun observePendingByCategory(category: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status = 'pending' AND category = :category AND senderDomain = :domain ORDER BY receivedAtMillis DESC")
    fun observePendingByCategoryAndDomain(category: String, domain: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status = 'pending' AND category = :category AND senderDomain = :domain")
    suspend fun getPendingByCategoryAndDomain(category: String, domain: String): List<MessageEntity>

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

    @Query(
        "SELECT senderDomain, MIN(senderDisplayName) as senderDisplayName, COUNT(*) as count, " +
            "SUM(CASE WHEN hasListUnsubscribe THEN 1 ELSE 0 END) as unsubscribableCount " +
            "FROM messages WHERE status = 'pending' AND category = :category " +
            "GROUP BY senderDomain ORDER BY count DESC",
    )
    fun observeSenderGroups(category: String): Flow<List<SenderGroupCount>>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET status = 'trashed' WHERE category = :category AND senderDomain = :domain AND status = 'pending'")
    suspend fun markDomainTrashed(category: String, domain: String)
}

data class CategoryCount(
    val category: String,
    val count: Int,
    val unsubscribableCount: Int,
)

data class SenderGroupCount(
    val senderDomain: String,
    val senderDisplayName: String,
    val count: Int,
    val unsubscribableCount: Int,
)
