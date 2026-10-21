package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.codemodeExecution
import me.rerere.ai.ui.interruptCodemode
import me.rerere.ai.ui.withCodemodeExecution
import me.rerere.rikkahub.data.ai.codemode.CodemodeTraceContext
import me.rerere.rikkahub.data.ai.tools.WorkspaceAptToolCall

internal suspend fun invokeToolRaw(
    tool: Tool,
    arguments: JsonElement,
    callId: String,
): List<UIMessagePart> = withContext(WorkspaceAptToolCall(callId)) {
    tool.execute(arguments)
}

/** A failed durable checkpoint must abort the generation, rather than become tool output. */
internal class CodemodeCheckpointException(cause: Throwable) :
    IllegalStateException("Failed to persist Codemode checkpoint", cause)

private data class TraceCheckpoint(
    val execution: CodemodeExecution,
    val ack: CompletableDeferred<Unit> = CompletableDeferred(),
)

/** Only the calling coroutine consumes progress; the executor cannot advance before its ack. */
internal suspend fun invokeWithCodemodeCheckpoints(
    execute: suspend () -> List<UIMessagePart>,
    checkpoint: suspend (CodemodeExecution) -> Unit,
): List<UIMessagePart> = coroutineScope {
    val progress = Channel<TraceCheckpoint>(capacity = 1)
    val invocation = async {
        withContext(CodemodeTraceContext { execution ->
            val event = TraceCheckpoint(execution)
            progress.send(event)
            event.ack.await()
        }) {
            execute()
        }
    }
    try {
        var result: List<UIMessagePart>? = null
        while (result == null) {
            select<Unit> {
                progress.onReceive { event ->
                    try {
                        checkpoint(event.execution)
                        event.ack.complete(Unit)
                    } catch (error: Throwable) {
                        val failure = if (error is kotlinx.coroutines.CancellationException ||
                            error is CodemodeCheckpointException) error else CodemodeCheckpointException(error)
                        event.ack.completeExceptionally(failure)
                        throw failure
                    }
                }
                invocation.onAwait { result = it }
            }
        }
        checkNotNull(result)
    } finally {
        progress.cancel()
        invocation.cancel()
    }
}

internal fun List<UIMessage>.interruptRunningCodemode(): List<UIMessage> = map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool) part.interruptCodemode() else part
    })
}

internal fun UIMessagePart.Tool.cancelRunningCodemode(): UIMessagePart.Tool {
    val execution = codemodeExecution ?: return this
    if (execution.status != CodemodeStatus.RUNNING) return this
    val now = System.currentTimeMillis()
    return withCodemodeExecution(execution.copy(
        status = CodemodeStatus.CANCELLED,
        finishedAt = now,
        error = "Generation cancelled by user",
        operations = execution.operations.map { operation ->
            if (operation.status == CodemodeStatus.RUNNING) operation.copy(
                status = CodemodeStatus.CANCELLED,
                finishedAt = now,
                error = "Operation cancelled; completed writes are retained",
            ) else operation
        },
    )).copy(output = listOf(UIMessagePart.Text(
        "Codemode cancelled. Completed operations are retained; inspect the workspace before retrying."
    )))
}
