package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationWorkspaceTest {
    private val assistantId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun `conversation keeps an independent workspace cwd`() {
        val conversation = Conversation(
            assistantId = assistantId,
            workspaceCwd = "project/src",
            messageNodes = emptyList(),
        )

        assertEquals("project/src", conversation.workspaceCwd)
    }

    @Test
    fun `new conversation defaults to no workspace cwd`() {
        val conversation = Conversation.ofId(
            id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            assistantId = assistantId,
        )

        assertNull(conversation.workspaceCwd)
    }
}
