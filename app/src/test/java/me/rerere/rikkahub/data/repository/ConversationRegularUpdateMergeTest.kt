package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRegularUpdateMergeTest {
    private val persistedAssistantId = Uuid.parse("10000000-0000-0000-0000-000000000001")
    private val staleAssistantId = Uuid.parse("10000000-0000-0000-0000-000000000002")
    private val persistedFolderId = Uuid.parse("20000000-0000-0000-0000-000000000001")
    private val staleFolderId = Uuid.parse("20000000-0000-0000-0000-000000000002")

    @Test
    fun `regular save preserves dedicated ownership fields from database`() {
        val incoming = Conversation(
            id = Uuid.parse("30000000-0000-0000-0000-000000000001"),
            assistantId = staleAssistantId,
            title = "updated title",
            messageNodes = emptyList(),
            workspaceCwd = "stale/path",
            folderId = staleFolderId,
            conversationNumber = 7,
        )
        val existing = entity(
            id = incoming.id.toString(),
            assistantId = persistedAssistantId.toString(),
            folderId = persistedFolderId.toString(),
            workspaceCwd = "current/path",
            conversationNumber = 42,
        )

        val merged = mergeConversationForRegularUpdate(incoming, existing)

        assertEquals("updated title", merged.title)
        assertEquals(persistedAssistantId, merged.assistantId)
        assertEquals(persistedFolderId, merged.folderId)
        assertEquals("current/path", merged.workspaceCwd)
        assertEquals(42, merged.conversationNumber)
    }

    @Test
    fun `regular save preserves empty dedicated fields as unassigned`() {
        val incoming = Conversation(
            assistantId = staleAssistantId,
            messageNodes = emptyList(),
            workspaceCwd = "stale/path",
            folderId = staleFolderId,
        )

        val merged = mergeConversationForRegularUpdate(
            incoming,
            entity(
                id = incoming.id.toString(),
                assistantId = persistedAssistantId.toString(),
            ),
        )

        assertEquals(persistedAssistantId, merged.assistantId)
        assertNull(merged.folderId)
        assertNull(merged.workspaceCwd)
    }

    private fun entity(
        id: String,
        assistantId: String,
        folderId: String = "",
        workspaceCwd: String = "",
        conversationNumber: Long = 1,
    ) = ConversationEntity(
        id = id,
        assistantId = assistantId,
        title = "persisted title",
        nodes = "[]",
        createAt = 1,
        updateAt = 2,
        chatSuggestions = "[]",
        isPinned = false,
        folderId = folderId,
        workspaceCwd = workspaceCwd,
        conversationNumber = conversationNumber,
    )
}
