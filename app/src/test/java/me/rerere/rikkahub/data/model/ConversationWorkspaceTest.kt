package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationWorkspaceTest {
    private val assistantId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val workspaceA = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val workspaceB = Uuid.parse("33333333-3333-3333-3333-333333333333")

    @Test
    fun changingWorkspaceClearsCwd() {
        val conversation = Conversation(
            assistantId = assistantId,
            workspaceId = workspaceA,
            workspaceCwd = "/workspace/project",
            messageNodes = emptyList(),
        )

        val changed = conversation.withWorkspaceId(workspaceB)

        assertEquals(workspaceB, changed.workspaceId)
        assertNull(changed.workspaceCwd)
    }

    @Test
    fun keepingWorkspacePreservesCwd() {
        val conversation = Conversation(
            assistantId = assistantId,
            workspaceId = workspaceA,
            workspaceCwd = "/workspace/project",
            messageNodes = emptyList(),
        )

        val unchanged = conversation.withWorkspaceId(workspaceA)

        assertEquals(conversation, unchanged)
    }

    @Test
    fun unbindingWorkspaceClearsCwd() {
        val conversation = Conversation(
            assistantId = assistantId,
            workspaceId = workspaceA,
            workspaceCwd = "/workspace/project",
            messageNodes = emptyList(),
        )

        val unbound = conversation.withWorkspaceId(null)

        assertNull(unbound.workspaceId)
        assertNull(unbound.workspaceCwd)
    }
}
