package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `awaitInitialized waits until initialization completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
            val initial = Conversation.ofId(conversationId)
            val session = ConversationSession(
                id = conversationId,
                initial = initial,
                scope = scope,
                onIdle = {},
            )
            val initialized = initial.copy(workspaceCwd = "/workspace/project")

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

    @Test
    fun `old job completion cannot clear a newer job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("22222222-2222-2222-2222-222222222222")
            val session = ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(conversationId),
                scope = scope,
                onIdle = {},
            )
            val first = scope.launch { awaitCancellation() }
            val second = scope.launch { awaitCancellation() }

            assertTrue(session.setJob(first))
            assertTrue(session.setJob(second))
            first.join()

            assertSame(second, session.getJob())
            second.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `paused session rejects a newly installed generation job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("33333333-3333-3333-3333-333333333333")
            val session = ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(conversationId),
                scope = scope,
                onIdle = {},
            )
            session.pauseGeneration()
            val rejected = scope.launch { awaitCancellation() }

            assertFalse(session.setJob(rejected))
            rejected.join()
            assertTrue(rejected.isCancelled)
            assertEquals(null, session.getJob())

            session.resumeGeneration()
            val accepted = scope.launch { awaitCancellation() }
            assertTrue(session.setJob(accepted))
            accepted.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queued messages keep the session alive even when the queue is paused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("44444444-4444-4444-4444-444444444444")
            val session = ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(conversationId),
                scope = scope,
                onIdle = {},
            )

            assertFalse(session.isInUse)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
            session.messageQueue.pause()

            assertTrue(session.isInUse)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `generation finished callback fires once for the current job only`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val conversationId = Uuid.parse("55555555-5555-5555-5555-555555555555")
            val finished = mutableListOf<Uuid>()
            val session = ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(conversationId),
                scope = scope,
                onIdle = {},
                onGenerationFinished = { id, _ -> finished.add(id) },
            )
            val first = scope.launch { awaitCancellation() }
            val second = scope.launch { awaitCancellation() }

            assertTrue(session.setJob(first))
            assertTrue(session.setJob(second))
            first.join()
            // 被替掉的旧任务不算「生成结束」，否则队列会被误推进
            assertEquals(emptyList<Uuid>(), finished)

            second.cancel()
            second.join()
            assertEquals(listOf(conversationId), finished)
        } finally {
            scope.cancel()
        }
    }
}
