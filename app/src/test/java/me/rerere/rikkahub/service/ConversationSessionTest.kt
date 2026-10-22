package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun `consecutive tool approvals wait for earlier approval instead of cancelling it`() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val conversation = Conversation.ofId(Uuid.random())
                val session = ConversationSession(conversation.id, conversation, scope, onIdle = {})
                val oldGenerationFinished = CompletableDeferred<Unit>()
                val waiting = CompletableDeferred<Unit>()
                val savedApprovals = mutableListOf<String>()
                val first = scope.launch {
                    session.withToolApproval {
                        waiting.complete(Unit)
                        oldGenerationFinished.await()
                        savedApprovals.add("first")
                    }
                }
                waiting.await()
                val second = scope.launch {
                    session.withToolApproval {
                        savedApprovals.add("second")
                    }
                }
                assertFalse(first.isCancelled)
                assertFalse(second.isCompleted)
                assertTrue(savedApprovals.isEmpty())

                oldGenerationFinished.complete(Unit)
                first.join()
                second.join()
                assertFalse(first.isCancelled)
                assertEquals(listOf("first", "second"), savedApprovals)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `cancelled approval releases the next waiting approval`() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val conversation = Conversation.ofId(Uuid.random())
                val session = ConversationSession(conversation.id, conversation, scope, onIdle = {})
                val entered = CompletableDeferred<Unit>()
                var secondSaved = false
                val first = scope.launch {
                    session.withToolApproval {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                val second = scope.launch {
                    session.withToolApproval { secondSaved = true }
                }
                assertFalse(secondSaved)

                first.cancel()
                first.join()
                second.join()
                assertTrue(first.isCancelled)
                assertTrue(secondSaved)
            } finally {
                scope.cancel()
            }
        }
    }

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
