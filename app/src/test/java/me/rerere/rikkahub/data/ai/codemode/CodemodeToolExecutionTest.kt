package me.rerere.rikkahub.data.ai.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
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
    fun `failed shell retains result and stops all following calls`() = runBlocking {
        var calls = 0
        val t = tool(name = "workspace_shell") {
            calls++
            listOf(UIMessagePart.Text("{\"exitCode\":2,\"stdout\":\"\",\"stderr\":\"bad\",\"timedOut\":false}"))
        }
        val trace = mutableListOf<CodemodeExecution>()
        val execution = CodemodeToolExecution(mapOf(t.name to t), "parent", trace::add)
        assertTrue(runCatching { execution.invoke(t.name, args) }.isFailure)
        assertEquals(CodemodeStatus.FAILED, trace.last().operations.single().status)
        assertEquals(2, trace.last().operations.single().result!!.jsonObject["exitCode"]!!.jsonPrimitive.int)
        assertTrue(runCatching { execution.invoke(t.name, args) }.isFailure)
        assertEquals(1, calls)
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
