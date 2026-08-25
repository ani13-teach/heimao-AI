package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `awaitInitialized waits until initialization completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
            val workspaceId = Uuid.parse("22222222-2222-2222-2222-222222222222")
            val initial = Conversation.ofId(conversationId)
            val session = ConversationSession(
                id = conversationId,
                initial = initial,
                scope = scope,
                onIdle = {},
            )
            val initialized = initial.copy(workspaceId = workspaceId)

            val awaitingState = scope.async {
                session.awaitInitialized()
                session.state.value
            }

            assertFalse(awaitingState.isCompleted)
            session.state.value = initialized
            session.completeInitialization()

            assertEquals(initialized, awaitingState.await())
        } finally {
            scope.cancel()
        }
    }
}
