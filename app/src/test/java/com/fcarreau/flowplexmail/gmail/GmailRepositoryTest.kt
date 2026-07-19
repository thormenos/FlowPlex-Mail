package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartHeader
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

    private val repository = GmailRepository(gmail, dao)

    init {
        every { gmail.users() } returns users
        every { users.messages() } returns messages
        every { messages.list("me") } returns listRequest
        every { listRequest.setQ(any()) } returns listRequest
        every { listRequest.setMaxResults(any()) } returns listRequest
    }

    private fun headerMessage(id: String, headers: Map<String, String>) = Message()
        .setId(id)
        .setThreadId("thread-$id")
        .setInternalDate(1_000L)
        .setPayload(MessagePart().setHeaders(headers.map { (k, v) -> MessagePartHeader().setName(k).setValue(v) }))

    private fun stubGet(id: String, result: Message) {
        val getRequest = mockk<Gmail.Users.Messages.Get>()
        every { messages.get("me", id) } returns getRequest
        every { getRequest.setFormat(any()) } returns getRequest
        every { getRequest.setMetadataHeaders(any()) } returns getRequest
        every { getRequest.execute() } returns result
    }

    @Test
    fun `sync recupere et classe les messages des 4 categories`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(
            listOf(Message().setId("m1")),
        )
        stubGet(
            "m1",
            headerMessage(
                "m1",
                mapOf("From" to "Boutique <boutique@exemple.com>", "Subject" to "Soldes", "List-Unsubscribe" to "<https://exemple.com/unsub>"),
            ),
        )

        repository.sync()

        val captured = slot<List<MessageEntity>>()
        coVerify { dao.upsertAll(capture(captured)) }

        assertEquals(CLEANABLE_CATEGORIES.size, captured.captured.size)
        assertEquals(CLEANABLE_CATEGORIES.toSet(), captured.captured.map { it.category }.toSet())
        assertTrue(captured.captured.all { it.hasListUnsubscribe })
        assertTrue(captured.captured.all { it.sender == "Boutique <boutique@exemple.com>" })
    }

    @Test
    fun `sync ignore un message dont la recuperation echoue sans interrompre les autres`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(
            listOf(Message().setId("bon"), Message().setId("casse")),
        )
        stubGet("bon", headerMessage("bon", mapOf("From" to "a@b.com", "Subject" to "Ok")))
        val brokenGetRequest = mockk<Gmail.Users.Messages.Get>()
        every { messages.get("me", "casse") } returns brokenGetRequest
        every { brokenGetRequest.setFormat(any()) } returns brokenGetRequest
        every { brokenGetRequest.setMetadataHeaders(any()) } returns brokenGetRequest
        every { brokenGetRequest.execute() } throws RuntimeException("boom")

        repository.sync()

        val captured = slot<List<MessageEntity>>()
        coVerify { dao.upsertAll(capture(captured)) }

        assertEquals(CLEANABLE_CATEGORIES.size, captured.captured.count { it.id == "bon" })
        assertTrue(captured.captured.none { it.id == "casse" })
    }

    @Test
    fun `sans List-Unsubscribe le message n est pas marque desabonnable`() = runTest {
        every { listRequest.execute() } returns ListMessagesResponse().setMessages(listOf(Message().setId("m2")))
        stubGet("m2", headerMessage("m2", mapOf("From" to "a@b.com", "Subject" to "Facture")))

        repository.sync()

        val captured = slot<List<MessageEntity>>()
        coVerify { dao.upsertAll(capture(captured)) }

        assertTrue(captured.captured.all { !it.hasListUnsubscribe })
    }
}
