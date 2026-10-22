package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.GenerationConversationCommitState
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationForkTest {
    @Test
    fun `fork of a saved conversation is eligible for first persistence`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("original").toMessageNode()),
            conversationNumber = 42,
            customSystemPrompt = "custom prompt",
            modeInjectionIds = setOf(Uuid.random()),
            workspaceCwd = "/workspace/project",
        )
        val copiedNodes = source.messageNodes.map { it.copy(id = Uuid.random()) }

        val fork = createForkedConversation(source, copiedNodes)

        assertNotEquals(source.id, fork.id)
        assertTrue(fork.newConversation)
        assertEquals(0L, fork.conversationNumber)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(copiedNodes, fork.messageNodes)
        assertEquals(source.customSystemPrompt, fork.customSystemPrompt)
        assertEquals(source.modeInjectionIds, fork.modeInjectionIds)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        GenerationConversationCommitState(initiallyPersisted = !fork.newConversation)
            .requireValidTarget(fork, null)
    }

    @Test
    fun `deleting a saved fork cannot recreate it from its initial snapshot`() {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val fork = createForkedConversation(source, listOf(UIMessage.user("fork").toMessageNode()))
        val commitState = GenerationConversationCommitState(initiallyPersisted = !fork.newConversation)
        commitState.requireValidTarget(fork, null)
        commitState.recordPersisted()

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            commitState.requireValidTarget(fork, null)
        }
    }
}
