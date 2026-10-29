package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class GenerationFinalizationTest {
    private fun source() = Conversation(
        assistantId = Uuid.random(),
        messageNodes = listOf(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Reasoning(reasoning = "thinking", finishedAt = null),
            UIMessagePart.Tool(
                toolCallId = "call", toolName = "test", input = "{}",
                approvalState = ToolApprovalState.Pending,
            ),
        )).toMessageNode()),
    )

    private fun finish(source: Conversation, current: Conversation) = finishStoppedGeneration(source, current) { tool ->
        if (tool.isExecuted) tool else tool.copy(
            output = listOf(UIMessagePart.Text("cancelled")),
            approvalState = ToolApprovalState.Denied("stopped"),
        )
    }

    @Test
    fun editCommitSuspensionCannotBeOverwrittenByStopOrReasoningFinalization() = runBlocking {
        withTimeout(5_000) {
            val source = source()
            val state = MutableStateFlow(source)
            var persisted = source
            val mutex = Mutex()
            val editSaving = CompletableDeferred<Unit>()
            val allowEditCommit = CompletableDeferred<Unit>()
            val edited = source.copy(title = "edited title", messageNodes = listOf(
                UIMessage.assistant("edited answer").toMessageNode(),
            ))
            val edit = launch(Dispatchers.Unconfined) {
                mutex.withLock {
                    val before = state.value
                    editSaving.complete(Unit)
                    allowEditCommit.await()
                    persisted = edited
                    state.update { latest ->
                        if (latest == before) persisted else latest.copy(conversationNumber = persisted.conversationNumber)
                    }
                }
            }
            editSaving.await()
            val request = GenerationMessageSnapshot.capture(source)
            val reasoning = launch(Dispatchers.Unconfined) {
                finalizeGenerationState(mutex, state, source.assistantId,
                    transform = { request.finishReasoning(it, source.currentMessages.map { message -> message.id }.toSet()) },
                    persist = { persisted = it },
                )
            }
            val stop = launch(Dispatchers.Unconfined) {
                finalizeGenerationState(mutex, state, source.assistantId,
                    transform = { finish(source, it) }, persist = { persisted = it },
                )
            }
            assertFalse(reasoning.isCompleted)
            assertFalse(stop.isCompleted)
            assertSame(source, state.value)
            allowEditCommit.complete(Unit)
            edit.join()
            reasoning.join()
            stop.join()
            assertEquals(edited, state.value)
            assertEquals(edited, persisted)
        }
    }

    @Test
    fun failedPersistenceStillEndsReasoningAndPendingToolsInMemory() = runBlocking {
        val source = source()
        val state = MutableStateFlow(source)
        val failure = runCatching {
            finalizeGenerationState(Mutex(), state, source.assistantId,
                transform = { finish(source, it) },
                persist = { error("save failed") },
            )
        }.exceptionOrNull()
        assertEquals("save failed", failure?.message)
        val parts = state.value.currentMessages.single().parts
        assertNotNull((parts.first() as UIMessagePart.Reasoning).finishedAt)
        val tool = parts.last() as UIMessagePart.Tool
        assertFalse(tool.isPending)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun cancelledGenerationWaiterDoesNotDeadlockCleanupBehindEditLock() = runBlocking {
        withTimeout(5_000) {
            val source = source()
            val state = MutableStateFlow(source)
            val mutex = Mutex(locked = true)
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val cleanup = appScope.launch {
                    finalizeGenerationState(mutex, state, source.assistantId,
                        transform = { finish(source, it) }, persist = {},
                    )
                }
                val generation = launch(Dispatchers.Unconfined) { cleanup.join() }
                generation.cancelAndJoin()
                assertFalse(cleanup.isCompleted)
                mutex.unlock()
                cleanup.join()
                assertNotNull((state.value.currentMessages.single().parts.first() as UIMessagePart.Reasoning).finishedAt)
            } finally {
                appScope.cancel()
            }
        }
    }
}
