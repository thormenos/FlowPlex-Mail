package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartHeader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailRepositoryTest {

    private val gmail = mockk<Gmail>()
    private val users = mockk<Gmail.Users>()
    private val messages = mockk<Gmail.Users.Messages>()
    private val listRequest = mockk<Gmail.Users.Messages.List>()
    private val dao = mockk<MessageDao>(relaxed = true)
    private val messageFetcher = mockk<GmailMessageFetcher>()
    private val savedBatches = mutableListOf<List<MessageEntity>>()

    private val repository = GmailRepository(gmail, dao, messageFetcher)

    init {
        every { gmail.users() } returns users
        every { users.messages() } returns messages
        every { messages.list("me") } returns listRequest
        every { listRequest.setQ(any()) } returns listRequest
        every { listRequest.setMaxResults(any()) } returns listRequest
        coEvery { dao.upsertAll(capture(savedBatches)) } just Runs
    }

    private fun headerMessage(id: String, headers: Map<String, String>) = Message()
        .setId(id)
        .setThreadId("thread-$id")
        .setInternalDate(1_000L)
        .setPayload(MessagePart().setHeaders(headers.map { (k, v) -> MessagePartHeader().setName(k).setValue(v) }))

    @Test
    fun `sync recupere et classe les messages des 4 categories`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(listOf(Message().setId("m1")))
        every { messageFetcher.fetchMessages(gmail, listOf("m1")) } returns listOf(
            headerMessage(
                "m1",
                mapOf("From" to "Boutique <boutique@exemple.com>", "Subject" to "Soldes", "List-Unsubscribe" to "<https://exemple.com/unsub>"),
            ),
        )

        repository.sync()

        val allEntities = savedBatches.flatten()
        assertEquals(CLEANABLE_CATEGORIES.size, allEntities.size)
        assertEquals(CLEANABLE_CATEGORIES.toSet(), allEntities.map { it.category }.toSet())
        assertTrue(allEntities.all { it.hasListUnsubscribe })
        assertTrue(allEntities.all { it.sender == "Boutique <boutique@exemple.com>" })
        assertTrue(allEntities.all { it.senderDomain == "exemple.com" })
    }

    @Test
    fun `sync sauvegarde chaque categorie des qu elle est prete plutot que d attendre la fin`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(listOf(Message().setId("m1")))
        every { messageFetcher.fetchMessages(gmail, listOf("m1")) } returns listOf(
            headerMessage("m1", mapOf("From" to "a@b.com", "Subject" to "Test")),
        )

        repository.sync()

        assertEquals(CLEANABLE_CATEGORIES.size, savedBatches.size)
        assertTrue(savedBatches.all { it.size == 1 })
    }

    @Test
    fun `sync ignore un message dont le mappage echoue sans interrompre les autres`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(
            listOf(Message().setId("bon"), Message().setId("casse")),
        )
        every { messageFetcher.fetchMessages(gmail, listOf("bon", "casse")) } returns listOf(
            headerMessage("bon", mapOf("From" to "a@b.com", "Subject" to "Ok")),
            Message(), // pas d'id -> le mappage plante, doit etre ignore sans tout casser
        )

        repository.sync()

        val allEntities = savedBatches.flatten()
        assertEquals(CLEANABLE_CATEGORIES.size, allEntities.count { it.id == "bon" })
        assertTrue(allEntities.none { it.threadId == "thread-casse" })
    }

    @Test
    fun `sans List-Unsubscribe le message n est pas marque desabonnable`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(listOf(Message().setId("m2")))
        every { messageFetcher.fetchMessages(gmail, listOf("m2")) } returns listOf(
            headerMessage("m2", mapOf("From" to "a@b.com", "Subject" to "Facture")),
        )

        repository.sync()

        val allEntities = savedBatches.flatten()
        assertTrue(allEntities.all { !it.hasListUnsubscribe })
    }
}
