package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationMessageSnapshot
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionStopTest {
    @Test
    fun `stopping thinking finalizes reasoning and allows the next send after persistence`() = runBlocking {
        withSession { session, scope ->
            val response = UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking", finishedAt = null),
            ))
            session.state.value = session.state.value.copy(messageNodes = listOf(response.toMessageNode()))
            val request = GenerationMessageSnapshot.capture(session.state.value)
            val generation = scope.launch {
                flow<Unit> { awaitCancellation() }
                    .onCompletion {
                        session.state.update { request.finishReasoning(it, setOf(response.id)) }
                    }.collect {}
            }
            session.setJob(generation)
            val saving = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            var persisted: Conversation? = null
            var nextStarted = false
            fun dispatchNext() {
                if (session.getJob() != null || session.isGenerationPaused) return
                if (session.messageQueue.takeNext() == null) return
                nextStarted = true
                session.setJob(scope.launch { awaitCancellation() })
            }
            session.messageQueue.pause()
            val stop = scope.async {
                session.stopGeneration(
                    onStopped = {
                        saving.complete(Unit)
                        saved.await()
                        persisted = session.state.value
                    },
                    onReady = ::dispatchNext,
                )
            }
            saving.await()
            assertTrue(generation.isCompleted)
            assertNull(session.getJob())
            assertTrue(session.isGenerationPaused)
            assertTrue(session.isInUse)
            val reasoning = session.state.value.currentMessages.single().parts.single() as UIMessagePart.Reasoning
            assertNotNull(reasoning.finishedAt)
            val finishedAt = reasoning.finishedAt

            // A manual send from an empty, paused queue must wait for stop persistence.
            session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next question")))
            dispatchNext()
            assertFalse(nextStarted)
            saved.complete(Unit)
            stop.await()

            assertEquals(session.state.value, persisted)
            assertEquals(finishedAt, (persisted!!.currentMessages.single().parts.single() as UIMessagePart.Reasoning).finishedAt)
            assertFalse(session.isGenerationPaused)
            assertTrue(nextStarted)
            assertNotNull(session.getJob())
            assertTrue(session.getJob()!!.isActive)
        }
    }

    @Test
    fun `leaving the page does not cancel shared stop cleanup and concurrent stops coalesce`() = runBlocking {
        withSession { session, scope ->
            val generation = scope.launch { awaitCancellation() }
            session.setJob(generation)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cleanups = 0
            var ready = 0
            var persisted = false
            val page = scope.async {
                session.stopGeneration(onStopped = {
                    cleanups++
                    entered.complete(Unit)
                    release.await()
                    persisted = true
                }, onReady = { ready++ })
            }
            entered.await()
            val otherPage = scope.async {
                session.stopGeneration(onStopped = { cleanups++ }, onReady = { ready++ })
            }
            page.cancelAndJoin()
            assertFalse(otherPage.isCompleted)
            assertTrue(session.isGenerationPaused)
            val rejected = scope.launch { awaitCancellation() }
            assertFalse(session.setJob(rejected))
            assertTrue(rejected.isCancelled)

            release.complete(Unit)
            otherPage.await()
            assertTrue(persisted)
            assertEquals(1, cleanups)
            assertEquals(1, ready)
            assertNull(session.getJob())
            assertFalse(session.isGenerationPaused)
        }
    }

    @Test
    fun `failed cleanup releases stop barrier and keeps queue paused`() = runBlocking {
        withSession { session, scope ->
            val pending = UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Tool(toolCallId = "call", toolName = "test", input = "{}", approvalState = ToolApprovalState.Pending),
            ))
            session.state.value = session.state.value.copy(messageNodes = listOf(pending.toMessageNode()))
            val source = session.state.value
            session.messageQueue.resume()
            val failure = IllegalStateException("save failed")
            val result = runCatching {
                session.stopGeneration(onStopped = {
                    session.state.update { current ->
                        finishStoppedGeneration(source, current) { tool ->
                            tool.copy(output = listOf(UIMessagePart.Text("cancelled")), approvalState = ToolApprovalState.Denied("stopped"))
                        }
                    }
                    throw failure
                })
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertFalse(session.isGenerationPaused)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next question")))
            assertNull(session.messageQueue.takeNext())
            assertFalse(session.state.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            })
            session.messageQueue.resume()
            assertNotNull(session.messageQueue.takeNext())
            assertTrue(session.setJob(scope.launch { awaitCancellation() }))
            assertTrue(session.isGenerating)
            var recovered = false
            session.stopGeneration(onStopped = { recovered = true })
            assertTrue(recovered)
        }
    }

    private suspend fun withSession(block: suspend (ConversationSession, CoroutineScope) -> Unit) {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val initial = Conversation.ofId(Uuid.random())
                block(ConversationSession(initial.id, initial, scope, onIdle = {}), scope)
            } finally {
                scope.cancel()
            }
        }
    }
}
