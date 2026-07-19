package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RESULTS_PER_CATEGORY = 200L

val CLEANABLE_CATEGORIES = listOf("promotions", "social", "updates", "forums")

class GmailRepository(
    private val gmail: Gmail,
    private val dao: MessageDao,
    private val messageFetcher: GmailMessageFetcher = BatchGmailMessageFetcher(),
) {

    /** Synchronise catégorie par catégorie et sauvegarde chacune dès qu'elle est prête,
     *  pour que l'interface affiche les résultats progressivement plutôt que d'attendre
     *  que les 4 catégories soient toutes terminées. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        CLEANABLE_CATEGORIES.forEach { category ->
            val entities = fetchCategory(category)
            dao.upsertAll(entities)
        }
    }

    private fun fetchCategory(category: String): List<MessageEntity> {
        val listResponse = gmail.users().messages().list("me")
            .setQ("category:$category")
            .setMaxResults(MAX_RESULTS_PER_CATEGORY)
            .execute()

        val ids = listResponse.messages.orEmpty().map { it.id }
        return messageFetcher.fetchMessages(gmail, ids).mapNotNull { message ->
            runCatching { toEntity(category, message) }.getOrNull()
        }
    }

    private fun toEntity(category: String, message: Message): MessageEntity {
        val headers = message.payload?.headers.orEmpty().associate { it.name to it.value }
        val fromHeader = headers["From"] ?: "(expéditeur inconnu)"
        val sender = parseSender(fromHeader)

        return MessageEntity(
            id = message.id,
            threadId = message.threadId ?: "",
            category = category,
            sender = fromHeader,
            senderDomain = sender.domain,
            senderDisplayName = sender.displayName,
            subject = headers["Subject"] ?: "(sans objet)",
            receivedAtMillis = message.internalDate ?: 0L,
            hasListUnsubscribe = headers.containsKey("List-Unsubscribe"),
            listUnsubscribeHeader = headers["List-Unsubscribe"],
            listUnsubscribePostHeader = headers["List-Unsubscribe-Post"],
        )
    }
}
