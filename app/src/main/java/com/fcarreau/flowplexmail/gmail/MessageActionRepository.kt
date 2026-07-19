package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.BatchModifyMessagesRequest
import com.google.api.services.gmail.model.Message as GmailApiMessage
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GMAIL_SEARCH_PAGE_SIZE = 500L
private const val GMAIL_BATCH_MODIFY_LIMIT = 1000

class MessageActionRepository(
    private val gmail: Gmail,
    private val dao: MessageDao,
    private val accountName: String,
    private val httpClient: UnsubscribeHttpClient = NetUnsubscribeHttpClient(),
) {

    suspend fun trash(message: MessageEntity) = trashAll(listOf(message))

    suspend fun trashAll(messages: List<MessageEntity>) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        trashIds(messages.map { it.id })
        messages.forEach { dao.updateStatus(it.id, "trashed") }
    }

    suspend fun ignore(message: MessageEntity) = ignoreAll(listOf(message))

    suspend fun ignoreAll(messages: List<MessageEntity>) = withContext(Dispatchers.IO) {
        messages.forEach { dao.updateStatus(it.id, "ignored") }
    }

    suspend fun unsubscribe(message: MessageEntity) = withContext(Dispatchers.IO) {
        performUnsubscribeAction(message)
        trashIds(listOf(message.id))
        dao.updateStatus(message.id, "trashed")
    }

    /** Tente le désabonnement pour chaque message éligible ; les autres sont ignorés silencieusement. */
    suspend fun unsubscribeAll(messages: List<MessageEntity>): Int = withContext(Dispatchers.IO) {
        messages.count { message ->
            message.hasListUnsubscribe && runCatching {
                performUnsubscribeAction(message)
                trashIds(listOf(message.id))
                dao.updateStatus(message.id, "trashed")
            }.isSuccess
        }
    }

    /**
     * Utilisée pour l'action groupée par expéditeur/domaine : le cache local n'a qu'un
     * échantillon des emails (limité à chaque synchro), donc au moment d'agir on recherche
     * TOUS les emails de ce domaine sur Gmail (pagination complète) plutôt que de se limiter
     * à ce qui a été synchronisé, puis on les met tous à la corbeille en un minimum d'appels.
     */
    suspend fun trashAllForDomain(category: String, domain: String): Int = withContext(Dispatchers.IO) {
        val ids = findAllMessageIds(category, domain)
        trashIds(ids)
        dao.markDomainTrashed(category, domain)
        ids.size
    }

    /** Se désabonne une seule fois (via un message échantillon) puis jette tous les emails du domaine trouvés sur Gmail. */
    suspend fun unsubscribeAndTrashDomain(category: String, domain: String, sample: MessageEntity): Int = withContext(Dispatchers.IO) {
        performUnsubscribeAction(sample)
        val ids = findAllMessageIds(category, domain)
        trashIds(ids)
        dao.markDomainTrashed(category, domain)
        ids.size
    }

    private fun findAllMessageIds(category: String, domain: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val response = gmail.users().messages().list("me")
                .setQ("category:$category from:$domain")
                .setMaxResults(GMAIL_SEARCH_PAGE_SIZE)
                .setPageToken(pageToken)
                .execute()
            response.messages.orEmpty().forEach { ids.add(it.id) }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return ids
    }

    private fun trashIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(GMAIL_BATCH_MODIFY_LIMIT).forEach { chunk ->
            val request = BatchModifyMessagesRequest()
                .setIds(chunk)
                .setAddLabelIds(listOf("TRASH"))
                .setRemoveLabelIds(listOf("INBOX"))
            gmail.users().messages().batchModify("me", request).execute()
        }
    }

    private fun performUnsubscribeAction(message: MessageEntity) {
        val target = parseListUnsubscribe(message.listUnsubscribeHeader, message.listUnsubscribePostHeader)
            ?: error("Aucun lien de désabonnement trouvé pour ce message")

        when (target) {
            is UnsubscribeTarget.Http -> {
                if (target.oneClickPost) httpClient.postOneClick(target.url) else httpClient.get(target.url)
            }
            is UnsubscribeTarget.Mailto -> {
                val email = buildUnsubscribeEmail(target.address, target.subject ?: "unsubscribe")
                gmail.users().messages().send("me", email).execute()
            }
        }
    }

    private fun buildUnsubscribeEmail(to: String, subject: String): GmailApiMessage {
        val raw = buildString {
            append("From: $accountName\r\n")
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return GmailApiMessage().setRaw(encoded)
    }
}
