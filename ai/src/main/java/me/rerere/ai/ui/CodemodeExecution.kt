package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.util.json

const val CODEMODE_METADATA_KEY = "codemodeExecution"

@Serializable
enum class CodemodeStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

@Serializable
data class CodemodeOperation(
    val id: String,
    val toolName: String,
    val arguments: JsonElement,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: CodemodeStatus = CodemodeStatus.RUNNING,
    val result: JsonElement? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)

@Serializable
data class CodemodeExecution(
    val version: Int = 1,
    val status: CodemodeStatus = CodemodeStatus.RUNNING,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val operations: List<CodemodeOperation> = emptyList(),
    val error: String? = null,
)

val UIMessagePart.Tool.codemodeExecution: CodemodeExecution?
    get() = metadata?.get(CODEMODE_METADATA_KEY)?.let { value ->
        runCatching { json.decodeFromJsonElement<CodemodeExecution>(value) }
            .getOrNull()?.takeIf { it.version == 1 }
    }

fun UIMessagePart.Tool.withCodemodeExecution(execution: CodemodeExecution): UIMessagePart.Tool =
    copy(metadata = JsonObject(metadata.orEmpty() +
        (CODEMODE_METADATA_KEY to json.encodeToJsonElement(execution))))

/** Local execution details never become model context or summary source material. */
fun UIMessagePart.withoutCodemodeTrace(): UIMessagePart = when (this) {
    is UIMessagePart.Tool -> copy(
        metadata = metadata?.let { JsonObject(it - CODEMODE_METADATA_KEY).takeIf { it.isNotEmpty() } },
        output = output.map { it.withoutCodemodeTrace() },
    )
    else -> this
}

fun UIMessage.withoutCodemodeTrace(): UIMessage = copy(parts = parts.map { it.withoutCodemodeTrace() })

/** A persisted running operation has an unknown outcome after a process interruption. */
fun UIMessagePart.Tool.interruptCodemode(): UIMessagePart.Tool {
    val trace = codemodeExecution ?: return this
    if (trace.status != CodemodeStatus.RUNNING) return this
    val now = System.currentTimeMillis()
    return withCodemodeExecution(trace.copy(
        status = CodemodeStatus.INTERRUPTED,
        finishedAt = now,
        error = "Execution interrupted; completed writes are retained. Do not automatically retry.",
        operations = trace.operations.map {
            if (it.status == CodemodeStatus.RUNNING) it.copy(
                status = CodemodeStatus.INTERRUPTED, finishedAt = now,
                error = "Operation outcome unknown after interruption",
            ) else it
        },
    )).copy(output = listOf(UIMessagePart.Text(
        "Codemode interrupted. Operation outcome may be unknown; inspect the workspace before retrying."
    )))
}
