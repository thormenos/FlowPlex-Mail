package com.fcarreau.flowplexmail.data

import androidx.room.Entity

@Entity(tableName = "trust_rules", primaryKeys = ["category", "domain"])
data class TrustRuleEntity(
    val category: String,
    val domain: String,
    val senderDisplayName: String,
    val createdAtMillis: Long,
)
