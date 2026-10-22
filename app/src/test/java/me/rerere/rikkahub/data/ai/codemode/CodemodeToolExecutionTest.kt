package me.rerere.rikkahub.data.ai.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.CodemodeCheckpointException
import org.junit.Assert.*
import org.junit.Test

class CodemodeToolExecutionTest {
    private val args = buildJsonObject { put("path", "/workspace/a.txt") }
    private fun tool(
        name: String = "workspace_read_file",
        approval: Boolean = false,
        execute: suspend () -> List<UIMessagePart>,
    ) = Tool(name = name, description = "", parameters = {
        InputSchema.Obj(buildJsonObject {
            put("path", buildJsonObject { put("type", "string") })
        }, listOf("path"))
    }, needsApproval = { approval }, execute = { execute() })

    @Test
    fun `schema rejects wrong types missing fields and extra keys before invocation`() = runBlocking {
        var calls = 0
        val t = tool { calls++; listOf(UIMessagePart.Text("{}")) }
        for (arguments in listOf(buildJsonObject {}, buildJsonObject { put("path", 3) },
            buildJsonObject { put("path", "/workspace/a"); put("extra", true) })) {
            val execution = CodemodeToolExecution(mapOf(t.name to t), "parent") {}
            assertTrue(runCatching { execution.invoke(t.name, arguments) }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `approval and nonallowlisted calls cannot execute`() = runBlocking {
        var calls = 0
        for (t in listOf(tool(approval = true) { calls++; emptyList() },
            tool(name = "workspace_apt_execute") { calls++; emptyList() })) {
            val execution = CodemodeToolExecution(mapOf(t.name to t), "parent") {}
            assertTrue(runCatching { execution.invoke(t.name, args) }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `shell exit codes return original results and allow following calls with accurate trace`() = runBlocking {
        for (exitCode in listOf(0, 1, 2, 127, 130)) {
            val result = buildJsonObject {
                put("exitCode", exitCode); put("stdout", "out"); put("stderr", "err"); put("timedOut", false)
                put("extra", "preserved")
            }
            val shell = tool(name = "workspace_shell") { listOf(UIMessagePart.Text(result.toString())) }
            var reads = 0
            val read = tool { reads++; listOf(UIMessagePart.Text("{\"text\":\"after\"}")) }
            val trace = mutableListOf<CodemodeExecution>()
            val execution = CodemodeToolExecution(mapOf(shell.name to shell, read.name to read), "parent", trace::add)
            assertEquals(result, execution.invoke(shell.name, args))
            val operation = trace.last().operations.single()
            assertEquals(if (exitCode == 0) CodemodeStatus.SUCCEEDED else CodemodeStatus.FAILED, operation.status)
            assertEquals(result, operation.result)
            if (exitCode == 0) assertNull(operation.error)
            else assertTrue(operation.error!!.contains("exitCode=$exitCode"))
            assertEquals(buildJsonObject { put("text", "after") }, execution.invoke(read.name, args))
            assertEquals(1, reads)
            assertEquals(CodemodeStatus.SUCCEEDED, trace.last().operations.last().status)
        }
    }

    @Test
    fun `timeouts and invalid shell results stop all following calls`() = runBlocking {
        for (result in listOf(
            "{\"exitCode\":0,\"timedOut\":true}",
            "{\"exitCode\":127,\"timedOut\":true}",
            "{}", "[]", "null",
            "{\"timedOut\":false}", "{\"exitCode\":0}",
            "{\"exitCode\":\"1\",\"timedOut\":false}",
            "{\"exitCode\":1.5,\"timedOut\":false}",
            "{\"exitCode\":true,\"timedOut\":false}",
            "{\"exitCode\":2147483648,\"timedOut\":false}",
            "{\"exitCode\":null,\"timedOut\":false}",
            "{\"exitCode\":-1,\"timedOut\":false}",
            "{\"exitCode\":0,\"timedOut\":\"false\"}",
            "{\"exitCode\":0,\"timedOut\":0}",
            "{\"exitCode\":0,\"timedOut\":null}",
        )) {
            val shell = tool(name = "workspace_shell") { listOf(UIMessagePart.Text(result)) }
            var reads = 0
            val read = tool { reads++; emptyList() }
            val trace = mutableListOf<CodemodeExecution>()
            val execution = CodemodeToolExecution(mapOf(shell.name to shell, read.name to read), "parent", trace::add)
            assertTrue(result, runCatching { execution.invoke(shell.name, args) }.isFailure)
            assertEquals(CodemodeStatus.FAILED, trace.last().operations.single().status)
            assertEquals(Json.parseToJsonElement(result), trace.last().operations.single().result)
            assertTrue(runCatching { execution.invoke(read.name, args) }.isFailure)
            assertEquals(0, reads)
            assertEquals(1, trace.last().operations.size)
        }
    }

    @Test
    fun `tool exceptions remain fatal and block following calls`() = runBlocking {
        val failure = IllegalStateException("tool failed")
        val shell = tool(name = "workspace_shell") { throw failure }
        var reads = 0
        val read = tool { reads++; emptyList() }
        val trace = mutableListOf<CodemodeExecution>()
        val execution = CodemodeToolExecution(mapOf(shell.name to shell, read.name to read), "parent", trace::add)
        val errors = listOf(
            runCatching { execution.invoke(shell.name, args) }.exceptionOrNull(),
            runCatching { execution.invoke(read.name, args) }.exceptionOrNull(),
        )
        errors.forEach { error ->
            assertEquals(failure.javaClass, error?.javaClass)
            assertEquals(failure.message, error?.message)
            assertTrue(generateSequence(error) { it.cause }.any { it === failure })
        }
        assertEquals(0, reads)
        assertEquals(CodemodeStatus.FAILED, trace.last().operations.single().status)
        assertEquals("tool failed", trace.last().operations.single().error)
    }

    @Test
    fun `nonzero completion checkpoint failure propagates without completing twice`() = runBlocking {
        val shell = tool(name = "workspace_shell") {
            listOf(UIMessagePart.Text("{\"exitCode\":1,\"timedOut\":false}"))
        }
        val failure = CodemodeCheckpointException(IllegalStateException("checkpoint failed"))
        var checkpoints = 0
        val execution = CodemodeToolExecution(mapOf(shell.name to shell), "parent") { trace ->
            checkpoints++
            if (trace.operations.last().status == CodemodeStatus.FAILED) throw failure
        }
        assertSame(failure, runCatching { execution.invoke(shell.name, args) }.exceptionOrNull())
        assertEquals(2, checkpoints)
    }

    @Test
    fun `pre and post checkpoints gate writes and preserve successful operations`() = runBlocking {
        var calls = 0
        val trace = mutableListOf<CodemodeExecution>()
        val t = tool(name = "workspace_write_file") {
            assertEquals(CodemodeStatus.RUNNING, trace.last().operations.last().status)
            calls++
            listOf(UIMessagePart.Text("{\"path\":\"/workspace/a.txt\"}"))
        }
        val execution = CodemodeToolExecution(mapOf(t.name to t), "parent", trace::add)
        execution.invoke(t.name, args)
        assertEquals(1, calls)
        assertEquals(CodemodeStatus.SUCCEEDED, trace.last().operations.single().status)
        assertEquals("parent:1", trace.last().operations.single().id)
        assertTrue(runCatching { execution.invoke("missing", args) }.isFailure)
        assertEquals(CodemodeStatus.SUCCEEDED, trace.last().operations.first().status)
    }

    @Test
    fun `cancellation propagates and large trace values are bounded`() = runBlocking {
        val cancellation = CancellationException("stop")
        val cancelled = tool { throw cancellation }
        val execution = CodemodeToolExecution(mapOf(cancelled.name to cancelled), "parent") {}
        val failure = runCatching { execution.invoke(cancelled.name, args) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
        val trace = mutableListOf<CodemodeExecution>()
        val large = tool { listOf(UIMessagePart.Text("x".repeat(60000))) }
        CodemodeToolExecution(mapOf(large.name to large), "parent", trace::add).invoke(large.name, args)
        assertTrue(trace.last().operations.single().truncated)
        assertTrue(trace.last().operations.single().result.toString().toByteArray().size <= 2048)
    }

    @Test
    fun `attachments and oversized results fail and cannot be followed by another write`() = runBlocking {
        for (parts in listOf(listOf(UIMessagePart.Image("content://local/image")),
            listOf(UIMessagePart.Text("x".repeat(65537))))) {
            var calls = 0
            val t = tool { calls++; parts }
            val execution = CodemodeToolExecution(mapOf(t.name to t), "parent") {}
            assertTrue(runCatching { execution.invoke(t.name, args) }.isFailure)
            assertTrue(runCatching { execution.invoke(t.name, args) }.isFailure)
            assertEquals(1, calls)
        }
    }
}
