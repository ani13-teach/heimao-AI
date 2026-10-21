package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeOperation
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withCodemodeExecution
import me.rerere.ai.ui.withoutCodemodeTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodemodeContextCompressionTest {
    private val localMarker = "LOCAL_TRACE_SENTINEL"
    private val tool = UIMessagePart.Tool(
        toolCallId = "parent", toolName = "codemode", input = "{}",
        output = listOf(UIMessagePart.Text("parent output")),
        metadata = JsonObject(mapOf("signature" to JsonPrimitive("provider signature"))),
    )
    private val trace = CodemodeExecution(startedAt = 1, status = CodemodeStatus.SUCCEEDED,
        operations = listOf(CodemodeOperation("1", "workspace_read_file", JsonNull, 1,
            status = CodemodeStatus.SUCCEEDED, result = JsonPrimitive(localMarker))),
        error = localMarker,
    )

    private fun messages(part: UIMessagePart.Tool) = listOf(
        UIMessage.user("historic question " + "中".repeat(400)),
        UIMessage.assistant("").copy(parts = listOf(part)),
        UIMessage.user("current question"),
    )

    @Test
    fun `trace does not change tokens while provider signature remains counted`() {
        val original = messages(tool)
        val traced = original.map { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.withCodemodeExecution(trace) else it
        }) }
        assertEquals(estimatePromptTokens(LogicalPrompt.fromMessages(original)),
            estimatePromptTokens(LogicalPrompt.fromMessages(traced)))
        val withoutSignature = original.map { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.copy(metadata = null) else it
        }) }
        assertTrue(estimatePromptTokens(LogicalPrompt.fromMessages(withoutSignature)) <
            estimatePromptTokens(LogicalPrompt.fromMessages(original)))
        assertEquals(tool, tool.withCodemodeExecution(trace).withoutCodemodeTrace())
    }

    @Test
    fun `trace changes preserve compression hash and never enter summary source`() = runBlocking {
        val original = messages(tool)
        val traced = original.map { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.withCodemodeExecution(trace) else it
        }) }
        suspend fun compact(messages: List<UIMessage>): ContextCompactionResult {
            val prompt = LogicalPrompt.fromMessages(messages)
            val target = estimatePromptTokens(prompt.copy(history = listOf(contextCompressionMessage("")))) + 40
            return compactContextIfNeeded(prompt, messages, estimatePromptTokens(prompt), target) { source, _ ->
                assertFalse(source.contains(localMarker))
                assertTrue(source.contains("parent output"))
                "preserved facts"
            }
        }
        val plainResult = compact(original)
        val tracedResult = compact(traced)
        assertEquals(plainResult.messages[1].contextCompression!!.sourceHash,
            tracedResult.messages[1].contextCompression!!.sourceHash)
        val changedTrace = tracedResult.messages.map { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.withCodemodeExecution(trace.copy(finishedAt = 999,
                operations = emptyList(), error = "different local data")) else it
        }) }
        assertTrue(changedTrace.withStoredContextCompression().first().isSynthetic)
        val changedSignature = changedTrace.map { message -> message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool) it.copy(metadata = JsonObject(it.metadata.orEmpty() +
                ("signature" to JsonPrimitive("changed provider signature")))) else it
        }) }
        assertFalse(changedSignature.withStoredContextCompression().first().isSynthetic)
    }
}
