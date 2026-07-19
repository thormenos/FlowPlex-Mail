package com.fcarreau.flowplexmail.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val sender: String,
    val subject: String,
    val receivedAtMillis: Long,
    val hasListUnsubscribe: Boolean,
    val listUnsubscribeHeader: String?,
    val status: String = "pending",
)
