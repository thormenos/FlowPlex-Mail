package com.fcarreau.flowplexmail.gmail

import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message

private const val GMAIL_BATCH_SIZE = 100

private val METADATA_HEADERS = listOf("From", "Subject", "Date", "List-Unsubscribe", "List-Unsubscribe-Post")

interface GmailMessageFetcher {
    fun fetchMessages(gmail: Gmail, ids: List<String>): List<Message>
}

/**
 * Récupère les messages via l'API batch de Gmail (jusqu'à 100 par requête HTTP) au lieu d'un
 * appel réseau par message — c'est ce qui rendait la synchro très lente (des centaines
 * d'aller-retours séquentiels).
 */
class BatchGmailMessageFetcher : GmailMessageFetcher {

    override fun fetchMessages(gmail: Gmail, ids: List<String>): List<Message> {
        if (ids.isEmpty()) return emptyList()
        val results = mutableListOf<Message>()

        ids.chunked(GMAIL_BATCH_SIZE).forEach { chunk ->
            val batch: BatchRequest = gmail.batch()
            chunk.forEach { id ->
                gmail.users().messages().get("me", id)
                    .setFormat("metadata")
                    .setMetadataHeaders(METADATA_HEADERS)
                    .queue(
                        batch,
                        object : JsonBatchCallback<Message>() {
                            override fun onSuccess(message: Message, responseHeaders: HttpHeaders) {
                                results.add(message)
                            }

                            override fun onFailure(e: GoogleJsonError, responseHeaders: HttpHeaders) {
                                // Message ignoré (supprimé entre-temps, accès refusé, etc.)
                            }
                        },
                    )
            }
            batch.execute()
        }

        return results
    }
}
