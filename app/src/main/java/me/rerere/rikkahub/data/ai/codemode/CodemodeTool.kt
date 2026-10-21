package me.rerere.rikkahub.data.ai.codemode

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.CodemodeCheckpointException
import me.rerere.rikkahub.data.ai.invokeToolRaw
import me.rerere.rikkahub.data.ai.tools.WorkspaceAptToolCall

class CodemodeTraceContext(val checkpoint: suspend (CodemodeExecution) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CodemodeTraceContext>
}

private val allowedCodemodeTools = setOf(
    "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_shell",
)

fun createWorkspaceCodemodeTool(workspaceTools: List<Tool>): Tool {
    val allowed = workspaceTools.filter { it.name in allowedCodemodeTools }.associateBy { it.name }
    return Tool(
        name = "codemode",
        description = "Run JavaScript to batch workspace operations and filter results. " +
            "Prefer Codemode when a task needs multiple workspace operations, or when results must be filtered or " +
            "processed into statistics before returning them. For a single simple workspace operation, call the " +
            "corresponding workspace tool directly. Use await tools.workspace_read_file, " +
            "workspace_write_file, workspace_edit_file or workspace_shell with their normal arguments. " +
            "Calls execute serially, including Promise.all. Only text(value) output is returned to the model. " +
            "No network, filesystem, timers, imports or host objects are available to JavaScript. " +
            "Text files only; image attachments are unsupported. Stop on failed operations; completed writes are not rolled back. " +
            "Limits: 32 KiB code, 64 calls, 64 KiB per argument/result, 32 KiB total output, 120 seconds. " +
            "Example: const r = await tools.workspace_read_file({path:'/workspace/a.txt'}); text(r.text); " +
            "Available tools: " + allowed.keys.joinToString(", "),
        parameters = {
            InputSchema.Obj(buildJsonObject {
                put("code", buildJsonObject { put("type", "string"); put("description", "Async JavaScript body; call text(value) explicitly") })
            }, required = listOf("code"))
        },
        execute = { input ->
            val reporter = checkNotNull(currentCoroutineContext()[CodemodeTraceContext]) {
                "Codemode requires a durable execution checkpoint"
            }
            val parentId = checkNotNull(currentCoroutineContext()[WorkspaceAptToolCall]).id
            val args = input as? JsonObject ?: error("Codemode input must be an object")
            require(args.keys == setOf("code")) { "Codemode accepts only code" }
            val code = (args["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Codemode code must be a string")
            val execution = CodemodeToolExecution(allowed, parentId, reporter.checkpoint)
            execution.run(code)
        },
    )
}

/** Per-invocation state: only bounded previews enter local history, never provider messages. */
internal class CodemodeToolExecution(
    private val tools: Map<String, Tool>,
    private val parentId: String,
    private val checkpoint: suspend (CodemodeExecution) -> Unit,
) {
    private var trace = CodemodeExecution(startedAt = System.currentTimeMillis())
    private val output = mutableListOf<UIMessagePart>()
    private var fatal: Throwable? = null

    suspend fun run(code: String): List<UIMessagePart> {
        checkpoint(trace)
        try {
            WorkspaceCodemodeRuntime.execute(code, ::invoke) { value ->
                currentCoroutineContext().ensureActive()
                output += UIMessagePart.Text(value.toString())
            }
            fatal?.let { throw it }
            checkpoint(trace.copy(status = CodemodeStatus.SUCCEEDED, finishedAt = System.currentTimeMillis()).also { trace = it })
            return output.ifEmpty { listOf(UIMessagePart.Text("Codemode completed without text() output.")) }
        } catch (error: Throwable) {
            if (error is CancellationException || error is CodemodeCheckpointException) throw error
            val message = (error.message ?: error.javaClass.simpleName).take(1024)
            trace = trace.copy(status = CodemodeStatus.FAILED, finishedAt = System.currentTimeMillis(), error = message)
            checkpoint(trace)
            return output + UIMessagePart.Text("Codemode failed: $message. Completed operations are retained.")
        }
    }

    internal suspend fun invoke(name: String, arguments: JsonObject): JsonElement {
        currentCoroutineContext().ensureActive()
        fatal?.let { throw it }
        require(trace.operations.size < 64) { "Codemode exceeds 64 operations" }
        val (argumentPreview, argumentsTruncated) = preview(arguments, 1536)
        val operation = CodemodeOperation(
            id = "$parentId:${trace.operations.size + 1}", toolName = name,
            arguments = argumentPreview, startedAt = System.currentTimeMillis(), truncated = argumentsTruncated,
        )
        trace = trace.copy(operations = trace.operations + operation)
        checkpoint(trace)
        var result: JsonElement? = null
        try {
            require(name in allowedCodemodeTools) { "Tool is not available in Codemode" }
            val tool = tools[name] ?: error("Tool is not available in this workspace")
            validateArguments(tool, arguments)
            check(!tool.needsApproval(arguments)) { "Approval-requiring tools must be called directly" }
            val parts = invokeToolRaw(tool, arguments, operation.id)
            require(parts.all { it is UIMessagePart.Text }) { "Codemode supports text results only; use the direct tool for attachments" }
            val values = parts.filterIsInstance<UIMessagePart.Text>().map { part ->
                runCatching { Json.parseToJsonElement(part.text) }.getOrElse { JsonPrimitive(part.text) }
            }
            result = when (values.size) { 0 -> JsonNull; 1 -> values.single(); else -> JsonArray(values) }
            require(result.toString().toByteArray(Charsets.UTF_8).size <= 65536) { "Codemode result exceeds 64 KiB; use a narrower shell query" }
            if (name == "workspace_shell") {
                val shell = result as? JsonObject ?: error("Invalid workspace shell result")
                val exitCode = (shell["exitCode"] as? JsonPrimitive)?.intOrNull
                val timedOut = (shell["timedOut"] as? JsonPrimitive)?.booleanOrNull
                check(exitCode == 0 && timedOut == false) { "Workspace shell failed (exitCode=$exitCode, timedOut=$timedOut)" }
            }
        } catch (error: Throwable) {
            fatal = error
            if (error is CancellationException || error is CodemodeCheckpointException) throw error
            complete(operation, CodemodeStatus.FAILED, result, (error.message ?: "Tool failed").take(1024))
            throw error
        }
        complete(operation, CodemodeStatus.SUCCEEDED, result, null)
        return checkNotNull(result)
    }

    private suspend fun complete(operation: CodemodeOperation, status: CodemodeStatus, result: JsonElement?, error: String?) {
        val (value, truncated) = result?.let { preview(it, 2048) } ?: (null to false)
        val upstreamTruncated = ((result as? JsonObject)?.get("truncated") as? JsonPrimitive)?.booleanOrNull == true
        trace = trace.copy(operations = trace.operations.dropLast(1) + operation.copy(
            status = status, finishedAt = System.currentTimeMillis(), result = value, error = error,
            truncated = operation.truncated || truncated || upstreamTruncated,
        ))
        checkpoint(trace)
    }
}

internal fun validateArguments(tool: Tool, arguments: JsonObject) {
    require(arguments.toString().toByteArray(Charsets.UTF_8).size <= 65536) { "Codemode arguments exceed 64 KiB" }
    val schema = tool.parameters() as? InputSchema.Obj ?: error("Codemode tool requires an object schema")
    require(arguments.keys.all { it in schema.properties }) { "Unknown tool argument" }
    require(schema.required.orEmpty().all { it in arguments }) { "Missing required tool argument" }
    arguments.forEach { (key, value) ->
        val type = (schema.properties[key] as? JsonObject)?.get("type")?.jsonPrimitive?.content
        val primitive = value as? JsonPrimitive
        val valid = when (type) {
            "string" -> primitive?.isString == true
            "boolean" -> primitive?.isString == false && primitive.booleanOrNull != null
            "integer" -> primitive?.isString == false && primitive.longOrNull != null
            "number" -> primitive?.isString == false && primitive.doubleOrNull?.isFinite() == true
            else -> false
        }
        require(valid) { "Invalid type for argument $key" }
    }
}

private fun preview(value: JsonElement, maxBytes: Int): Pair<JsonElement, Boolean> {
    val encoded = value.toString()
    if (encoded.toByteArray(Charsets.UTF_8).size <= maxBytes) return value to false
    // A code point occupies at most four UTF-8 bytes; this also bounds JSON escaping.
    return JsonPrimitive(encoded.take(maxBytes / 6)) to true
}
