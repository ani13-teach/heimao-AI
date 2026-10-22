package me.rerere.rikkahub.data.ai.codemode

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkspaceCodemodeRuntimeTest {
    @Before fun requireWorkingRuntime() = runBlocking {
        assertTrue("Runtime must be available before behavior assertions", WorkspaceCodemodeRuntime.isAvailable())
    }

    @Test fun availabilityIncludesNativeDeadlineAndAsyncProbe() = runBlocking {
        assertTrue(WorkspaceCodemodeRuntime.isAvailable())
    }

    @Test fun awaitAndExplicitOutputWithoutAutomaticReturn() = runBlocking {
        val output = mutableListOf<JsonElement>()
        WorkspaceCodemodeRuntime.execute(
            "text(await Promise.resolve('hello')); return 42;",
            { _, _ -> JsonNull }, { output += it }
        )
        assertEquals(listOf(JsonPrimitive("hello")), output)
        output.clear()
        WorkspaceCodemodeRuntime.execute("return 42;", { _, _ -> JsonNull }, { output += it })
        assertTrue(output.isEmpty())
    }

    @Test fun promiseAllStillExecutesToolsSerially() = runBlocking {
        val active = AtomicInteger()
        val max = AtomicInteger()
        val calls = mutableListOf<String>()
        val output = mutableListOf<JsonElement>()
        WorkspaceCodemodeRuntime.execute(
            """
                const r = await Promise.all([
                  tools.workspace_read_file({path:'a'}),
                  tools.workspace_write_file({path:'b'}),
                  tools.workspace_edit_file({path:'c'}),
                  tools.workspace_shell({command:'pwd'})
                ]);
                text(r.length);
            """.trimIndent(),
            { name, _ ->
                max.set(maxOf(max.get(), active.incrementAndGet()))
                calls += name
                delay(20)
                active.decrementAndGet()
                JsonPrimitive(name)
            },
            { output += it }
        )
        assertEquals(1, max.get())
        assertEquals(4, calls.size)
        assertEquals(listOf(JsonPrimitive(4)), output)
    }

    @Test fun executionReturnsNonzeroShellResultsThroughPromiseAggregates() = runBlocking {
        for (aggregate in listOf("all", "allSettled")) {
            val exitCodes = listOf(0, 1, 127)
            val results = exitCodes.map { exitCode ->
                buildJsonObject {
                    put("exitCode", exitCode)
                    put("stdout", "out-$exitCode")
                    put("stderr", if (exitCode == 0) "" else "err-$exitCode")
                    put("timedOut", false)
                    put("extra", "preserved")
                }
            }
            val calls = mutableListOf<String>()
            val shell = executionTool("workspace_shell") { arguments ->
                val exitCode = arguments.jsonObject.getValue("command").jsonPrimitive.content.toInt()
                calls += "shell:$exitCode"
                listOf(UIMessagePart.Text(results[exitCodes.indexOf(exitCode)].toString()))
            }
            val after = buildJsonObject { put("text", "after") }
            val read = executionTool("workspace_read_file") {
                calls += "read"
                listOf(UIMessagePart.Text(after.toString()))
            }
            val trace = mutableListOf<CodemodeExecution>()
            val execution = CodemodeToolExecution(mapOf(shell.name to shell, read.name to read), "parent", trace::add)
            val output = execution.run("""
                const results = await Promise.$aggregate([
                  tools.workspace_shell({command:'0'}),
                  tools.workspace_shell({command:'1'}),
                  tools.workspace_shell({command:'127'})
                ]);
                const after = await tools.workspace_read_file({path:'/workspace/after.txt'});
                text({results, after});
            """.trimIndent())
            val expectedResults = if (aggregate == "all") JsonArray(results) else JsonArray(results.map { result ->
                buildJsonObject { put("status", "fulfilled"); put("value", result) }
            })
            assertEquals(buildJsonObject {
                put("results", expectedResults); put("after", after)
            }, Json.parseToJsonElement((output.single() as UIMessagePart.Text).text))
            assertEquals(listOf("shell:0", "shell:1", "shell:127", "read"), calls)
            assertEquals(CodemodeStatus.SUCCEEDED, trace.last().status)
            val operations = trace.last().operations
            assertEquals(listOf(CodemodeStatus.SUCCEEDED, CodemodeStatus.FAILED,
                CodemodeStatus.FAILED, CodemodeStatus.SUCCEEDED), operations.map { it.status })
            assertEquals(results, operations.take(3).map { it.result })
            assertEquals(null, operations[0].error)
            assertTrue(operations[1].error!!.contains("exitCode=1"))
            assertTrue(operations[2].error!!.contains("exitCode=127"))
        }
    }

    @Test fun executionRetainsOutputAndBlocksLaterCallsAfterCaughtToolException() = runBlocking {
        var shellCalls = 0
        var reads = 0
        val shell = executionTool("workspace_shell") { shellCalls++; error("tool failed") }
        val read = executionTool("workspace_read_file") { reads++; emptyList() }
        val trace = mutableListOf<CodemodeExecution>()
        val execution = CodemodeToolExecution(mapOf(shell.name to shell, read.name to read), "parent", trace::add)
        val output = execution.run("""
            text('before');
            try { await tools.workspace_shell({command:'fail'}); } catch (_) {}
            await tools.workspace_read_file({path:'/workspace/after.txt'});
            text('after');
        """.trimIndent())
        assertEquals(1, shellCalls)
        assertEquals(0, reads)
        assertEquals(2, output.size)
        assertEquals("\"before\"", (output.first() as UIMessagePart.Text).text)
        assertTrue((output.last() as UIMessagePart.Text).text.startsWith("Codemode failed:"))
        assertEquals(CodemodeStatus.FAILED, trace.last().status)
        assertEquals(CodemodeStatus.FAILED, trace.last().operations.single().status)
        assertEquals("tool failed", trace.last().operations.single().error)
    }

    private fun executionTool(
        name: String,
        execute: suspend (JsonElement) -> List<UIMessagePart>,
    ): Tool {
        val argument = if (name == "workspace_shell") "command" else "path"
        return Tool(name = name, description = "", parameters = {
            InputSchema.Obj(buildJsonObject {
                put(argument, buildJsonObject { put("type", "string") })
            }, listOf(argument))
        }, execute = execute)
    }

    @Test fun cancellationInterruptsSynchronousLoop() = runBlocking {
        assertTrue(WorkspaceCodemodeRuntime.isAvailable())
        val ready = CompletableDeferred<Unit>()
        val execution = launch {
            WorkspaceCodemodeRuntime.execute(
                "text('ready'); while(true) {}",
                { _, _ -> JsonNull }, { ready.complete(Unit) }
            )
        }
        withTimeout(5_000) { ready.await() }
        withTimeout(5_000) { execution.cancelAndJoin() }
        assertTrue(execution.isCancelled)
        // A subsequent independent context must still work.
        WorkspaceCodemodeRuntime.execute("await Promise.resolve();", { _, _ -> JsonNull }, {})
    }

    @Test fun cancellationCancelsAwaitedToolAndPreventsNextWrite() = runBlocking {
        assertTrue(WorkspaceCodemodeRuntime.isAvailable())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val execution = launch {
            WorkspaceCodemodeRuntime.execute(
                "await tools.workspace_shell({}); await tools.workspace_write_file({});",
                { name, _ ->
                    if (name == "workspace_shell") {
                        entered.complete(Unit)
                        try { delay(60_000) } finally { cancelled.complete(Unit) }
                    } else writes.incrementAndGet()
                    JsonNull
                }, {}
            )
        }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { execution.cancelAndJoin(); cancelled.await() }
        assertEquals(0, writes.get())
    }

    @Test fun memoryLimitAndJavaScriptErrorsReject() = runBlocking {
        expectFailure("const a=[]; while(true) a.push(new Array(10000).fill(1));")
        expectFailure("throw new Error('script failed');")
        expectFailure("function recurse(){ return recurse(); } recurse();")
        expectFailure("await new Promise(() => {});")
        expectFailure("await import('file:///data/local/tmp/code.js');")
    }

    @Test fun bridgesAreHiddenAndOnlyWorkspaceToolsExist() = runBlocking {
        val output = mutableListOf<JsonElement>()
        WorkspaceCodemodeRuntime.execute(
            """
                text(typeof __codemodeHost);
                text(typeof fetch);
                text(typeof console);
                text(typeof JVM);
                text(typeof store);
                text(Object.keys(tools).length);
            """.trimIndent(), { _, _ -> JsonNull }, { output += it }
        )
        assertEquals(List(5) { JsonPrimitive("undefined") } + JsonPrimitive(4), output)
    }

    @Test fun scriptCannotBreakRequiredCleanupGlobals() = runBlocking {
        WorkspaceCodemodeRuntime.execute(
            "try { Object.preventExtensions(globalThis); } catch (_) {}", { _, _ -> JsonNull }, {}
        )
        expectFailure("Object.defineProperty(globalThis, 'format', {value: null});")
        WorkspaceCodemodeRuntime.execute("text(1);", { _, _ -> JsonNull }, {})
    }

    @Test fun byteAndOperationLimitsRejectBeforeHostExecution() = runBlocking {
        expectFailure("text('😀'.repeat(8192));")
        expectFailure("await tools.workspace_read_file({value:'😀'.repeat(16384)});")
        expectFailure("for(let i=0;i<65;i++) await tools.workspace_shell({});")
        expectFailure("text('x'.repeat(20000)); text('y'.repeat(20000));")
        expectFailure(" ".repeat(32769))
    }

    @Test fun hostFailuresCannotBeSwallowedByScript() = runBlocking {
        var failed = false
        try {
            WorkspaceCodemodeRuntime.execute(
                "try { text('x'); } catch (_) {}",
                { _, _ -> JsonNull }, { error("emit failed") }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
        }
        assertTrue(failed)
        failed = false
        try {
            WorkspaceCodemodeRuntime.execute(
                "try { await tools.workspace_shell({}); } catch (_) {}",
                { _, _ -> error("invoke failed") }, {}
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
        }
        assertTrue(failed)
    }

    @Test fun caughtLimitAndSerializationFailuresPreventLaterWrites() = runBlocking {
        for (prefix in listOf(
            "text('x'.repeat(32769));",
            "tools.workspace_read_file({value: 1n});",
            "text(undefined);",
        )) {
            var writes = 0
            val failure = runCatching {
                WorkspaceCodemodeRuntime.execute(
                    "try { $prefix } catch (_) {} await tools.workspace_write_file({});",
                    { _, _ -> writes++; JsonNull }, {},
                )
            }.exceptionOrNull()
            assertTrue("Expected fail-closed execution", failure != null)
            assertEquals(0, writes)
        }
    }

    private suspend fun expectFailure(code: String) {
        var failed = false
        try {
            WorkspaceCodemodeRuntime.execute(code, { _, _ -> JsonNull }, {})
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
        }
        assertTrue("Expected Codemode failure", failed)
    }
}
