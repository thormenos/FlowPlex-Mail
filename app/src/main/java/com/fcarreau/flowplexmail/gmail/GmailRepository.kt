package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RESULTS_PER_CATEGORY = 50L

val CLEANABLE_CATEGORIES = listOf("promotions", "social", "updates", "forums")

class GmailRepository(private val gmail: Gmail, private val dao: MessageDao) {

    suspend fun sync() = withContext(Dispatchers.IO) {
        val entities = CLEANABLE_CATEGORIES.flatMap { category -> fetchCategory(category) }
        dao.upsertAll(entities)
    }

    private fun fetchCategory(category: String): List<MessageEntity> {
        val listResponse = gmail.users().messages().list("me")
            .setQ("category:$category")
            .setMaxResults(MAX_RESULTS_PER_CATEGORY)
            .execute()

        return listResponse.messages.orEmpty().mapNotNull { ref ->
            runCatching { fetchMessage(category, ref.id) }.getOrNull()
        }
    }

    private fun fetchMessage(category: String, messageId: String): MessageEntity {
        val message = gmail.users().messages().get("me", messageId)
            .setFormat("metadata")
            .setMetadataHeaders(listOf("From", "Subject", "Date", "List-Unsubscribe", "List-Unsubscribe-Post"))
            .execute()

        val headers = message.payload?.headers.orEmpty().associate { it.name to it.value }

        return MessageEntity(
            id = message.id,
            threadId = message.threadId ?: "",
            category = category,
            sender = headers["From"] ?: "(expéditeur inconnu)",
            subject = headers["Subject"] ?: "(sans objet)",
            receivedAtMillis = message.internalDate ?: 0L,
            hasListUnsubscribe = headers.containsKey("List-Unsubscribe"),
            listUnsubscribeHeader = headers["List-Unsubscribe"],
            listUnsubscribePostHeader = headers["List-Unsubscribe-Post"],
        )
    }
}
