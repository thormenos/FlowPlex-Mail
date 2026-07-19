package com.fcarreau.flowplexmail.gmail

import com.fcarreau.flowplexmail.data.MessageDao
import com.fcarreau.flowplexmail.data.MessageEntity
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.BatchModifyMessagesRequest
import com.google.api.services.gmail.model.Message as GmailApiMessage
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        senderDomain = "exemple.com",
        senderDisplayName = "Boutique",
        subject = "Soldes",
        receivedAtMillis = 0L,
        hasListUnsubscribe = hasUnsub,
        listUnsubscribeHeader = unsubHeader,
        listUnsubscribePostHeader = unsubPostHeader,
    )

    private fun stubBatchModify(): io.mockk.CapturingSlot<BatchModifyMessagesRequest> {
        val batchRequest = mockk<Gmail.Users.Messages.BatchModify>()
        val captured = slot<BatchModifyMessagesRequest>()
        every { messages.batchModify("me", capture(captured)) } returns batchRequest
        every { batchRequest.execute() } returns null
        return captured
    }

    @Test
    fun `trash met le message a la corbeille via batchModify et met a jour le statut local`() = runTest {
        val captured = stubBatchModify()

        repository.trash(message())

        assertEquals(listOf("msg-1"), captured.captured.ids)
        assertEquals(listOf("TRASH"), captured.captured.addLabelIds)
        coVerify { dao.updateStatus("msg-1", "trashed") }
    }

    @Test
    fun `trashAll regroupe plusieurs messages dans un seul appel batchModify`() = runTest {
        val captured = stubBatchModify()

        repository.trashAll(listOf(message(id = "a"), message(id = "b"), message(id = "c")))

        assertEquals(listOf("a", "b", "c"), captured.captured.ids)
        verify(exactly = 1) { messages.batchModify(any(), any()) }
        coVerify { dao.updateStatus("a", "trashed") }
        coVerify { dao.updateStatus("b", "trashed") }
        coVerify { dao.updateStatus("c", "trashed") }
    }

    @Test
    fun `trashAll sur une liste vide n appelle pas Gmail`() = runTest {
        repository.trashAll(emptyList())

        verify(exactly = 0) { users.messages() }
    }

    @Test
    fun `ignore ne touche pas a Gmail et marque juste le message ignore`() = runTest {
        repository.ignore(message())

        coVerify { dao.updateStatus("msg-1", "ignored") }
        verify(exactly = 0) { users.messages() }
    }

    @Test
    fun `ignoreAll marque tous les messages comme ignores`() = runTest {
        repository.ignoreAll(listOf(message(id = "a"), message(id = "b")))

        coVerify { dao.updateStatus("a", "ignored") }
        coVerify { dao.updateStatus("b", "ignored") }
    }

    @Test
    fun `unsubscribe avec lien http simple fait un GET et met aussi le message a la corbeille`() = runTest {
        val captured = stubBatchModify()

        repository.unsubscribe(
            message(hasUnsub = true, unsubHeader = "<https://exemple.com/unsub>"),
        )

        verify { httpClient.get("https://exemple.com/unsub") }
        verify(exactly = 0) { httpClient.postOneClick(any()) }
        assertEquals(listOf("msg-1"), captured.captured.ids)
        coVerify { dao.updateStatus("msg-1", "trashed") }
    }

    @Test
    fun `unsubscribe avec List-Unsubscribe-Post fait un POST one-click`() = runTest {
        stubBatchModify()

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
    fun `unsubscribe avec mailto seul envoie un email via Gmail puis met a la corbeille`() = runTest {
        val sendRequest = mockk<Gmail.Users.Messages.Send>()
        val capturedMessage = slot<GmailApiMessage>()
        every { messages.send(eq("me"), capture(capturedMessage)) } returns sendRequest
        every { sendRequest.execute() } returns GmailApiMessage()
        stubBatchModify()

        repository.unsubscribe(
            message(hasUnsub = true, unsubHeader = "<mailto:unsub@exemple.com?subject=stop>"),
        )

        verify { sendRequest.execute() }
        assertFalse(capturedMessage.captured.raw.isNullOrBlank())
        coVerify { dao.updateStatus("msg-1", "trashed") }
    }

    @Test(expected = IllegalStateException::class)
    fun `unsubscribe sans header List-Unsubscribe leve une exception`() = runTest {
        repository.unsubscribe(message(hasUnsub = false, unsubHeader = null))
    }

    @Test
    fun `unsubscribe sans header ne modifie pas le statut du message`() = runTest {
        runCatching { repository.unsubscribe(message(hasUnsub = false, unsubHeader = null)) }

        coVerify(exactly = 0) { dao.updateStatus(any(), any()) }
    }

    @Test
    fun `unsubscribeAll ignore silencieusement les messages sans lien et retourne le nombre reussi`() = runTest {
        val sendRequest = mockk<Gmail.Users.Messages.Send>()
        every { messages.send(eq("me"), any()) } returns sendRequest
        every { sendRequest.execute() } returns GmailApiMessage()
        stubBatchModify()

        val eligible = message(id = "eligible", hasUnsub = true, unsubHeader = "<https://exemple.com/unsub>")
        val notEligible = message(id = "not-eligible", hasUnsub = false)

        val successCount = repository.unsubscribeAll(listOf(eligible, notEligible))

        assertEquals(1, successCount)
        verify { httpClient.get("https://exemple.com/unsub") }
        coVerify { dao.updateStatus("eligible", "trashed") }
        coVerify(exactly = 0) { dao.updateStatus("not-eligible", any()) }
    }

    @Test
    fun `unsubscribeAll continue apres l echec d un message`() = runTest {
        every { httpClient.get("https://exemple.com/casse") } throws RuntimeException("boom")
        stubBatchModify()

        val casse = message(id = "casse", hasUnsub = true, unsubHeader = "<https://exemple.com/casse>")
        val ok = message(id = "ok", hasUnsub = true, unsubHeader = "<https://exemple.com/ok>")

        val successCount = repository.unsubscribeAll(listOf(casse, ok))

        assertEquals(1, successCount)
        coVerify { dao.updateStatus("ok", "trashed") }
        coVerify(exactly = 0) { dao.updateStatus("casse", any()) }
    }
}
