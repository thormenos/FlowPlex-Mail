package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message as GmailMessage
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageActionRepository(
    private val gmail: Gmail,
    private val dao: MessageDao,
    private val accountName: String,
    private val httpClient: UnsubscribeHttpClient = NetUnsubscribeHttpClient(),
) {

    suspend fun trash(message: MessageEntity) = withContext(Dispatchers.IO) {
        gmail.users().messages().trash("me", message.id).execute()
        dao.updateStatus(message.id, "trashed")
    }

    suspend fun ignore(message: MessageEntity) = withContext(Dispatchers.IO) {
        dao.updateStatus(message.id, "ignored")
    }

    suspend fun unsubscribe(message: MessageEntity) = withContext(Dispatchers.IO) {
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

        dao.updateStatus(message.id, "unsubscribed")
    }

    private fun buildUnsubscribeEmail(to: String, subject: String): GmailMessage {
        val raw = buildString {
            append("From: $accountName\r\n")
            append("To: $to\r\n")
            append("Subject: $subject\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return GmailMessage().setRaw(encoded)
    }
}
