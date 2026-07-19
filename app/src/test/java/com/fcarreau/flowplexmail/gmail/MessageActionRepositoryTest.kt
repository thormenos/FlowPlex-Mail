package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message as GmailApiMessage
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageActionRepositoryTest {

    private val gmail = mockk<Gmail>()
    private val users = mockk<Gmail.Users>()
    private val messages = mockk<Gmail.Users.Messages>()
    private val dao = mockk<MessageDao>(relaxed = true)
    private val httpClient = mockk<UnsubscribeHttpClient>(relaxed = true)

    private val repository = MessageActionRepository(gmail, dao, "moi@exemple.com", httpClient)

    init {
        every { gmail.users() } returns users
        every { users.messages() } returns messages
    }

    private fun message(
        id: String = "msg-1",
        hasUnsub: Boolean = false,
        unsubHeader: String? = null,
        unsubPostHeader: String? = null,
    ) = MessageEntity(
        id = id,
        threadId = "thread-1",
        category = "promotions",
        sender = "Boutique <boutique@exemple.com>",
        subject = "Soldes",
        receivedAtMillis = 0L,
        hasListUnsubscribe = hasUnsub,
        listUnsubscribeHeader = unsubHeader,
        listUnsubscribePostHeader = unsubPostHeader,
    )

    @Test
    fun `trash met le message a la corbeille Gmail et met a jour le statut local`() = runTest {
        val trashRequest = mockk<Gmail.Users.Messages.Trash>()
        every { messages.trash("me", "msg-1") } returns trashRequest
        every { trashRequest.execute() } returns GmailApiMessage()

        repository.trash(message())

        verify { trashRequest.execute() }
        coVerify { dao.updateStatus("msg-1", "trashed") }
    }

    @Test
    fun `ignore ne touche pas a Gmail et marque juste le message ignore`() = runTest {
        repository.ignore(message())

        coVerify { dao.updateStatus("msg-1", "ignored") }
        verify(exactly = 0) { users.messages() }
    }

    @Test
    fun `unsubscribe avec lien http simple fait un GET`() = runTest {
        repository.unsubscribe(
            message(hasUnsub = true, unsubHeader = "<https://exemple.com/unsub>"),
        )

        verify { httpClient.get("https://exemple.com/unsub") }
        verify(exactly = 0) { httpClient.postOneClick(any()) }
        coVerify { dao.updateStatus("msg-1", "unsubscribed") }
    }

    @Test
    fun `unsubscribe avec List-Unsubscribe-Post fait un POST one-click`() = runTest {
        repository.unsubscribe(
            message(
                hasUnsub = true,
                unsubHeader = "<https://exemple.com/unsub>",
                unsubPostHeader = "List-Unsubscribe=One-Click",
            ),
        )

        verify { httpClient.postOneClick("https://exemple.com/unsub") }
        verify(exactly = 0) { httpClient.get(any()) }
    }

    @Test
    fun `unsubscribe avec mailto seul envoie un email via Gmail`() = runTest {
        val sendRequest = mockk<Gmail.Users.Messages.Send>()
        val capturedMessage = slot<GmailApiMessage>()
        every { messages.send(eq("me"), capture(capturedMessage)) } returns sendRequest
        every { sendRequest.execute() } returns GmailApiMessage()

        repository.unsubscribe(
            message(hasUnsub = true, unsubHeader = "<mailto:unsub@exemple.com?subject=stop>"),
        )

        verify { sendRequest.execute() }
        assertFalse(capturedMessage.captured.raw.isNullOrBlank())
        coVerify { dao.updateStatus("msg-1", "unsubscribed") }
    }

    @Test(expected = IllegalStateException::class)
    fun `unsubscribe sans header List-Unsubscribe leve une exception`() = runTest {
        repository.unsubscribe(message(hasUnsub = false, unsubHeader = null))
    }

    @Test
    fun `unsubscribe sans header ne marque pas le message comme traite`() = runTest {
        runCatching { repository.unsubscribe(message(hasUnsub = false, unsubHeader = null)) }

        coVerify(exactly = 0) { dao.updateStatus(any(), "unsubscribed") }
    }
}
