package me.rerere.rikkahub.data.summary

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSummaryTest {
    @Test
    fun `number parser supports lists ranges and duplicates`() {
        val parsed = parseConversationNumberSelection("1, 3，5-7, 3").getOrThrow()

        assertEquals(setOf(1L, 3L, 5L, 6L, 7L), parsed)
    }

    @Test
    fun `selection parser accepts numbers ranges and UUIDs`() {
        val id = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val parsed = parseConversationSelection("1,5-6,$id").getOrThrow()

        assertEquals(setOf(1L, 5L, 6L), parsed.numbers)
        assertEquals(setOf(id), parsed.ids)
    }

    @Test
    fun `number parser rejects invalid and excessive ranges`() {
        assertTrue(parseConversationNumberSelection("0").isFailure)
        assertTrue(parseConversationNumberSelection("8-2").isFailure)
        assertTrue(parseConversationNumberSelection("1-1000", maxRangeSize = 10).isFailure)
    }

    @Test
    fun `summary source includes visible content but excludes reasoning and tools`() {
        val conversation = Conversation(
            id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            assistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            conversationNumber = 42,
            title = "Project review",
            messageNodes = listOf(
                me.rerere.rikkahub.data.model.MessageNode.of(
                    UIMessage.system("Hidden system prompt")
                ),
                me.rerere.rikkahub.data.model.MessageNode.of(
                    UIMessage.user("Visible question")
                ),
                me.rerere.rikkahub.data.model.MessageNode.of(
                    UIMessage.assistant("Visible answer").copy(
                        parts = listOf(
                            UIMessagePart.Text("Visible answer"),
                            UIMessagePart.Reasoning(reasoning = "Hidden reasoning"),
                            UIMessagePart.Tool(
                                toolCallId = "tool-1",
                                toolName = "secret_tool",
                                input = "{}",
                                output = listOf(UIMessagePart.Text("Raw tool output")),
                            )
                        )
                    )
                )
            )
        )

        val source = buildConversationSummarySource(listOf(conversation))

        assertTrue(source.contains("conversation #42"))
        assertTrue(source.contains("Visible question"))
        assertTrue(source.contains("Visible answer"))
        assertFalse(source.contains("Hidden system prompt"))
        assertFalse(source.contains("Hidden reasoning"))
        assertFalse(source.contains("Raw tool output"))
    }

    @Test
    fun `empty conversation has no visible summary messages`() {
        val conversation = Conversation(
            assistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            messageNodes = emptyList(),
            conversationNumber = 1,
        )

        assertTrue(conversation.toSummarySource().messages.isEmpty())
    }

    @Test
    fun `summary source enforces configured size limit`() {
        val conversation = Conversation(
            assistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            conversationNumber = 1,
            messageNodes = listOf(
                me.rerere.rikkahub.data.model.MessageNode.of(
                    UIMessage.user("x".repeat(100))
                )
            )
        )

        val result = runCatching {
            buildConversationSummarySource(listOf(conversation), maxCharacters = 50)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `source splitter respects maximum size and preserves content`() {
        val source = "alpha\n" + "b".repeat(25) + "\nomega"
        val chunks = splitConversationSummarySource(source, maxCharacters = 10)

        assertTrue(chunks.all { it.length <= 10 })
        assertEquals(source.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
    }
}
