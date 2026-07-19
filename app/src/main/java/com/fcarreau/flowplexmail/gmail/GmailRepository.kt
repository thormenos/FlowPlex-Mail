package com.fcarreau.flowplexmail.gmail

import android.content.Context
import com.fcarreau.flowplexmail.data.FlowPlexDatabase
import com.fcarreau.flowplexmail.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RESULTS_PER_CATEGORY = 50L

val CLEANABLE_CATEGORIES = listOf("promotions", "social", "updates", "forums")

class GmailRepository(private val context: Context) {

    suspend fun sync(accountName: String) = withContext(Dispatchers.IO) {
        val gmail = GmailServiceFactory.build(context, accountName)

        val entities = CLEANABLE_CATEGORIES.flatMap { category ->
            val listResponse = gmail.users().messages().list("me")
                .setQ("category:$category")
                .setMaxResults(MAX_RESULTS_PER_CATEGORY)
                .execute()

            listResponse.messages.orEmpty().mapNotNull { ref ->
                runCatching {
                    val message = gmail.users().messages().get("me", ref.id)
                        .setFormat("metadata")
                        .setMetadataHeaders(listOf("From", "Subject", "Date", "List-Unsubscribe"))
                        .execute()

                    val headers = message.payload?.headers.orEmpty().associate { it.name to it.value }

                    MessageEntity(
                        id = message.id,
                        threadId = message.threadId ?: "",
                        category = category,
                        sender = headers["From"] ?: "(expéditeur inconnu)",
                        subject = headers["Subject"] ?: "(sans objet)",
                        receivedAtMillis = message.internalDate ?: 0L,
                        hasListUnsubscribe = headers.containsKey("List-Unsubscribe"),
                        listUnsubscribeHeader = headers["List-Unsubscribe"],
                    )
                }.getOrNull()
            }
        }

        FlowPlexDatabase.getInstance(context).messageDao().upsertAll(entities)
    }
}
