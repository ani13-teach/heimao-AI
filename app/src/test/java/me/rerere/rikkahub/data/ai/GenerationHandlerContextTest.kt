package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GenerationHandlerContextTest {
    @Test
    fun `additional context is inserted immediately before latest user request`() {
        val firstUser = UIMessage.user("earlier")
        val assistant = UIMessage.assistant("answer")
        val request = UIMessage.user("summarize by project")
        val source = UIMessage.user("historical source")

        val result = listOf(firstUser, assistant, request)
            .withAdditionalContextMessages(listOf(source))

        assertEquals(listOf(firstUser, assistant, source, request), result)
    }

    @Test
    fun `empty additional context preserves the original list`() {
        val messages = listOf(UIMessage.user("request"))

        val result = messages.withAdditionalContextMessages(emptyList())

        assertSame(messages, result)
    }
}
