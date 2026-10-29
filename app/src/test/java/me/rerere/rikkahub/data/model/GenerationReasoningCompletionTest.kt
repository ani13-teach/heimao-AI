package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationReasoningCompletionTest {
    private fun thinking() = UIMessage.assistant("").copy(parts = listOf(
        UIMessagePart.Reasoning(reasoning = "thinking", finishedAt = null),
    ))

    @Test
    fun `completion ends only generated selected reasoning and remains stable on revisit`() {
        val historical = thinking()
        val alternative = thinking()
        val response = thinking()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(historical.toMessageNode(), MessageNode(messages = listOf(alternative, response), selectIndex = 1)),
        )
        val request = GenerationMessageSnapshot.capture(conversation)
        val finished = request.finishReasoning(conversation, setOf(response.id))
        assertEquals(historical, finished.currentMessages.first())
        assertEquals(alternative, finished.messageNodes.last().messages.first())
        assertNotNull((finished.currentMessages.last().parts.single() as UIMessagePart.Reasoning).finishedAt)
        assertSame(finished, request.finishReasoning(finished, setOf(response.id)))
    }

    @Test
    fun `stale generation cannot finish reasoning after branch owner or content changes`() {
        val alternative = thinking()
        val response = thinking()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(alternative, response), selectIndex = 1)),
        )
        val request = GenerationMessageSnapshot.capture(conversation)
        val edited = response.copy(parts = listOf(UIMessagePart.Reasoning(reasoning = "edited", finishedAt = null)))
        val changes = listOf(
            conversation.copy(assistantId = Uuid.random()),
            conversation.copy(messageNodes = listOf(conversation.messageNodes.single().copy(selectIndex = 0))),
            conversation.copy(messageNodes = listOf(conversation.messageNodes.single().copy(messages = listOf(alternative, edited)))),
            conversation.copy(messageNodes = emptyList()),
        )
        changes.forEach { changed ->
            assertSame(changed, request.finishReasoning(changed, setOf(response.id)))
        }
    }
}
