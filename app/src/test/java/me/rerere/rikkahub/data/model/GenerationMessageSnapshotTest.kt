package me.rerere.rikkahub.data.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ContextCompression
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationMessageSnapshotTest {
    private fun conversation() = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(
            UIMessage.user("first").toMessageNode(),
            MessageNode(messages = listOf(UIMessage.assistant("old"), UIMessage.assistant("selected")), selectIndex = 1),
            UIMessage.user("last").toMessageNode(),
        ),
    )

    private fun compressed(messages: List<UIMessage>) = messages.mapIndexed { index, message ->
        if (index == 0) message.copy(contextCompression = ContextCompression("source", "summary")) else message
    }

    @Test
    fun `nonzero range updates identity and preserves alternatives`() {
        val base = conversation()
        val request = GenerationMessageSnapshot.capture(base, 1..2)
        val updated = request.applyTo(base, compressed(request.input))
        assertEquals(base.messageNodes[0], updated.messageNodes[0])
        assertEquals(1, updated.messageNodes[1].selectIndex)
        assertEquals(base.messageNodes[1].messages[0], updated.messageNodes[1].messages[0])
        assertEquals("summary", updated.messageNodes[1].currentMessage.contextCompression?.summary)
        assertEquals(updated, base.updateCurrentMessages(updated.currentMessages.drop(1)))
    }

    @Test
    fun `regeneration appends alternative to explicit response node`() {
        val base = conversation()
        var request = GenerationMessageSnapshot.capture(base, 0..0)
        val response = UIMessage.assistant("new")
        val updated = request.applyTo(base, request.input + response)
        assertEquals(3, updated.messageNodes.size)
        assertEquals(base.messageNodes[1].messages + response, updated.messageNodes[1].messages)
        assertEquals(2, updated.messageNodes[1].selectIndex)
        assertEquals(base.messageNodes[2], updated.messageNodes[2])
        request = request.copy(expected = updated)
        val next = request.applyTo(updated, request.input + response.copy(parts = listOf(UIMessagePart.Text("stream"))))
        assertEquals(3, next.messageNodes[1].messages.size)
        assertEquals("stream", next.messageNodes[1].currentMessage.toText())
    }

    @Test
    fun `edit deletion branch and owner changes reject checkpoint before persistence`() = runBlocking {
        val base = conversation()
        val request = GenerationMessageSnapshot.capture(base)
        val changes = listOf(
            base.copy(assistantId = Uuid.random()),
            base.copy(messageNodes = base.messageNodes.dropLast(1)),
            base.copy(messageNodes = base.messageNodes.mapIndexed { i, n -> if (i == 1) n.copy(selectIndex = 0) else n }),
            base.copy(messageNodes = base.messageNodes.mapIndexed { i, n ->
                if (i == 2) n.copy(messages = listOf(n.currentMessage.copy(parts = listOf(UIMessagePart.Text("edited"))))) else n
            }),
        )
        changes.forEach { changed ->
            var saves = 0
            val state = MutableStateFlow(changed)
            val result = runCatching { commitGenerationMessages(state, request, compressed(request.input)) { saves++; it } }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(0, saves)
            assertEquals(changed, state.value)
        }
    }

    @Test
    fun `edit during persistence restores latest state and rejects caller continuation`() = runBlocking {
        val base = conversation()
        val request = GenerationMessageSnapshot.capture(base)
        val state = MutableStateFlow(base)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored = base
        var mainRequestSent = false
        val result = async {
            runCatching {
                commitGenerationMessages(state, request, compressed(request.input)) {
                    stored = it
                    if (!entered.isCompleted) { entered.complete(Unit); release.await() }
                    it
                }
                mainRequestSent = true
            }
        }
        entered.await()
        val edited = base.copy(messageNodes = base.messageNodes.dropLast(1))
        state.value = edited
        release.complete(Unit)
        assertTrue(result.await().isFailure)
        assertFalse(mainRequestSent)
        assertEquals(edited, stored)
        assertEquals(edited, state.value)
    }

    @Test
    fun `cancel during persistence does not publish half checkpoint`() = runBlocking {
        val base = conversation()
        val state = MutableStateFlow(base)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored = base
        val job = launch {
            val request = GenerationMessageSnapshot.capture(base)
            commitGenerationMessages(state, request, compressed(request.input)) {
                stored = it
                if (!entered.isCompleted) { entered.complete(Unit); release.await() }
                it
            }
            fail("Cancelled checkpoint continued")
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(base, state.value)
        assertEquals(base, stored)
    }

    @Test
    fun `normal checkpoint and append preserve unrelated accounting`() = runBlocking {
        val base = conversation()
        val request = GenerationMessageSnapshot.capture(base)
        val latest = base.copy(title = "new title", messageNodes = base.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.copy(translation = "translation") })
        })
        val state = MutableStateFlow(latest)
        var stored = latest
        val accepted = commitGenerationMessages(state, request, compressed(request.input)) { stored = it; it }
        assertEquals("new title", stored.title)
        assertEquals("translation", stored.currentMessages.first().translation)
        val response = UIMessage.assistant("answer")
        commitGenerationMessages(state, accepted, compressed(request.input) + response)
        assertEquals(4, state.value.messageNodes.size)
        assertEquals(response, state.value.currentMessages.last())
    }
}
