package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceAccessGate
import org.junit.Assert.*
import org.junit.Test

class ConversationDeletionSafetyTest {
    @Test
    fun `deletion during summary cancels and joins before deleting without sending main request`() = runBlocking {
        withTimeout(5_000) {
            val summarizing = CompletableDeferred<Unit>()
            val summaryResponse = CompletableDeferred<Unit>()
            var mainRequestSent = false
            var finished = false
            val generation = launch {
                try {
                    summarizing.complete(Unit)
                    summaryResponse.await()
                    mainRequestSent = true
                } finally {
                    finished = true
                }
            }
            summarizing.await()
            var deleted = false
            cancelGenerationBeforeDelete(
                pauseGeneration = { generation.also { it.cancel() } },
                delete = {
                    assertTrue(finished)
                    assertTrue(generation.isCompleted)
                    deleted = true
                },
            )
            assertTrue(deleted)
            assertFalse(mainRequestSent)
        }
    }

    @Test
    fun `checkpoint compensation can reacquire gate and conversation lock before delete`() = runBlocking {
        withTimeout(5_000) {
            val gate = AssistantWorkspaceAccessGate({ "assistant" }, { null })
            val conversationLock = Mutex()
            val entered = CompletableDeferred<Unit>()
            var compensated = false
            val generation = launch {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        gate.withAssistantMutation("assistant") {
                            conversationLock.withLock { compensated = true }
                        }
                    }
                }
            }
            entered.await()
            cancelGenerationBeforeDelete(
                pauseGeneration = { generation.also { it.cancel() } },
                delete = {
                    gate.withAssistantMutation("assistant") {
                        conversationLock.withLock { assertTrue(compensated) }
                    }
                },
            )
            assertTrue(generation.isCompleted)
        }
    }
}
