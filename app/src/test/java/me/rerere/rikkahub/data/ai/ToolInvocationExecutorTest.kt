package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeOperation
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.codemodeExecution
import me.rerere.ai.ui.withCodemodeExecution
import me.rerere.rikkahub.data.ai.codemode.CodemodeTraceContext
import me.rerere.rikkahub.data.ai.tools.WorkspaceAptToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ToolInvocationExecutorTest {
    private val running = CodemodeExecution(startedAt = 1)
    private val output = listOf(UIMessagePart.Text("parent output"))

    @Test
    fun `raw invocation preserves call context and cancellation`() = runBlocking {
        val cancellation = CancellationException("stop")
        val tool = Tool(name = "workspace_write_file", description = "", execute = {
            assertEquals("parent-1", currentCoroutineContext()[WorkspaceAptToolCall]?.id)
            throw cancellation
        })
        val failure = runCatching { invokeToolRaw(tool, JsonNull, "parent-1") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
    }

    @Test
    fun `executor waits for durable ack and checkpoints emit in collecting flow context`() = runBlocking {
        withTimeout(5_000) {
            val events = mutableListOf<String>()
            val values = flow {
                val result = invokeWithCodemodeCheckpoints(
                    execute = {
                        withContext(Dispatchers.Default) {
                            currentCoroutineContext()[CodemodeTraceContext]!!.checkpoint(running)
                            events += "operation"
                            currentCoroutineContext()[CodemodeTraceContext]!!.checkpoint(
                                running.copy(status = CodemodeStatus.SUCCEEDED)
                            )
                            output
                        }
                    },
                    checkpoint = {
                        events += "saved ${it.status}"
                        emit(it.status)
                    },
                )
                assertEquals(output, result)
            }.toList()
            assertEquals(listOf(CodemodeStatus.RUNNING, CodemodeStatus.SUCCEEDED), values)
            assertEquals(listOf("saved RUNNING", "operation", "saved SUCCEEDED"), events)
        }
    }

    @Test
    fun `failed checkpoint prevents writes and following operations`() = runBlocking {
        for (failAt in listOf(1, 2)) {
            var writes = 0
            var checkpoints = 0
            val diskFailure = IOException("disk unavailable")
            val failure = runCatching {
                invokeWithCodemodeCheckpoints(
                    execute = {
                        repeat(2) {
                            currentCoroutineContext()[CodemodeTraceContext]!!.checkpoint(running)
                            writes++
                        }
                        output
                    },
                    checkpoint = {
                        if (++checkpoints == failAt) throw diskFailure
                    },
                )
            }.exceptionOrNull()
            assertTrue(failure is CodemodeCheckpointException)
            assertTrue(generateSequence(failure) { it.cause }.any { it === diskFailure })
            assertEquals(failAt - 1, writes)
            assertEquals(failAt, checkpoints)
        }
    }

    @Test
    fun `cancellation while checkpoint waits does not start the operation`() = runBlocking {
        withTimeout(5_000) {
            var writes = 0
            val saving = CompletableDeferred<Unit>()
            coroutineScope {
                val job = launch {
                    invokeWithCodemodeCheckpoints(
                        execute = {
                            currentCoroutineContext()[CodemodeTraceContext]!!.checkpoint(running)
                            writes++
                            output
                        },
                        checkpoint = {
                            saving.complete(Unit)
                            awaitCancellation()
                        },
                    )
                }
                saving.await()
                job.cancel()
                job.join()
            }
            assertEquals(0, writes)
        }
    }

    @Test
    fun `recovered execution is completed for scheduling without replay and preserves completed steps`() {
        val succeeded = CodemodeOperation("1", "workspace_write_file", JsonNull, 1,
            status = CodemodeStatus.SUCCEEDED, finishedAt = 2)
        val active = CodemodeOperation("2", "workspace_edit_file", JsonNull, 3)
        val tool = UIMessagePart.Tool(toolCallId = "parent", toolName = "codemode", input = "{}",
            approvalState = ToolApprovalState.Approved).withCodemodeExecution(
            running.copy(operations = listOf(succeeded, active))
        )
        val recovered = listOf(UIMessage.assistant("").copy(parts = listOf(tool)))
            .interruptRunningCodemode().single().getTools().single()
        assertTrue(recovered.isExecuted)
        assertFalse(recovered.canResumeExecution)
        assertEquals(CodemodeStatus.INTERRUPTED, recovered.codemodeExecution!!.status)
        assertEquals(succeeded, recovered.codemodeExecution!!.operations.first())
        assertEquals(CodemodeStatus.INTERRUPTED, recovered.codemodeExecution!!.operations.last().status)

        val cancelled = tool.cancelRunningCodemode()
        assertTrue(cancelled.isExecuted)
        assertEquals(CodemodeStatus.CANCELLED, cancelled.codemodeExecution!!.status)
        assertEquals(succeeded, cancelled.codemodeExecution!!.operations.first())
        assertEquals(CodemodeStatus.CANCELLED, cancelled.codemodeExecution!!.operations.last().status)
        assertSame(recovered, recovered.cancelRunningCodemode())
        val successful = tool.withCodemodeExecution(running.copy(status = CodemodeStatus.SUCCEEDED))
            .copy(output = output)
        assertSame(successful, successful.cancelRunningCodemode())
    }
}
