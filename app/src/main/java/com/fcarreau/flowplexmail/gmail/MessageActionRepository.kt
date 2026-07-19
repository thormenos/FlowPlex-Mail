package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.BatchModifyMessagesRequest
import com.google.api.services.gmail.model.Message as GmailApiMessage
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageActionRepository(
    private val gmail: Gmail,
    private val dao: MessageDao,
    private val accountName: String,
    private val httpClient: UnsubscribeHttpClient = NetUnsubscribeHttpClient(),
) {

    suspend fun trash(message: MessageEntity) = trashAll(listOf(message))

    suspend fun trashAll(messages: List<MessageEntity>) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        applyTrashLabels(messages.map { it.id })
        messages.forEach { dao.updateStatus(it.id, "trashed") }
    }

    private fun applyTrashLabels(ids: List<String>) {
        val request = BatchModifyMessagesRequest()
            .setIds(ids)
            .setAddLabelIds(listOf("TRASH"))
            .setRemoveLabelIds(listOf("INBOX"))
        gmail.users().messages().batchModify("me", request).execute()
    }

    suspend fun ignore(message: MessageEntity) = ignoreAll(listOf(message))

    suspend fun ignoreAll(messages: List<MessageEntity>) = withContext(Dispatchers.IO) {
        messages.forEach { dao.updateStatus(it.id, "ignored") }
    }

    suspend fun unsubscribe(message: MessageEntity) = withContext(Dispatchers.IO) {
        unsubscribeInternal(message)
    }

    /** Tente le désabonnement pour chaque message éligible ; les autres sont ignorés silencieusement. */
    suspend fun unsubscribeAll(messages: List<MessageEntity>): Int = withContext(Dispatchers.IO) {
        messages.count { message ->
            message.hasListUnsubscribe && runCatching { unsubscribeInternal(message) }.isSuccess
        }
    }

    /** Se désabonner arrête les emails futurs ; on met aussi celui-ci à la corbeille, sinon la boîte ne se vide jamais vraiment. */
    private suspend fun unsubscribeInternal(message: MessageEntity) {
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

        applyTrashLabels(listOf(message.id))
        dao.updateStatus(message.id, "trashed")
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
