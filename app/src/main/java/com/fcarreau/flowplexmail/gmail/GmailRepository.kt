package com.fcarreau.flowplexmail.gmail

import android.content.Context
import com.fcarreau.flowplexmail.data.FlowPlexDatabase
import com.fcarreau.flowplexmail.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val METADATA_HEADERS_SIZE = 50L

class GmailRepository(private val context: Context) {

    suspend fun syncPromotions(accountName: String) = withContext(Dispatchers.IO) {
        val gmail = GmailServiceFactory.build(context, accountName)

        val listResponse = gmail.users().messages().list("me")
            .setQ("category:promotions")
            .setMaxResults(METADATA_HEADERS_SIZE)
            .execute()

        val entities = listResponse.messages.orEmpty().mapNotNull { ref ->
            runCatching {
                val message = gmail.users().messages().get("me", ref.id)
                    .setFormat("metadata")
                    .setMetadataHeaders(listOf("From", "Subject", "Date", "List-Unsubscribe"))
                    .execute()

                val headers = message.payload?.headers.orEmpty().associate { it.name to it.value }

                MessageEntity(
                    id = message.id,
                    threadId = message.threadId ?: "",
                    sender = headers["From"] ?: "(expéditeur inconnu)",
                    subject = headers["Subject"] ?: "(sans objet)",
                    receivedAtMillis = message.internalDate ?: 0L,
                    hasListUnsubscribe = headers.containsKey("List-Unsubscribe"),
                    listUnsubscribeHeader = headers["List-Unsubscribe"],
                )
            }.getOrNull()
        }

        FlowPlexDatabase.getInstance(context).messageDao().upsertAll(entities)
    }
}
