package com.fcarreau.flowplexmail.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TrustRuleEntity)

    @Query("DELETE FROM trust_rules WHERE category = :category AND domain = :domain")
    suspend fun delete(category: String, domain: String)

    @Query("SELECT * FROM trust_rules")
    suspend fun getAll(): List<TrustRuleEntity>

    @Query("SELECT domain FROM trust_rules WHERE category = :category")
    fun observeTrustedDomains(category: String): Flow<List<String>>
}
