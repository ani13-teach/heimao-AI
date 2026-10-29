package me.rerere.rikkahub.service

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class StoppedGenerationTest {
    private fun pendingMessage() = UIMessage.assistant("").copy(parts = listOf(
        UIMessagePart.Tool(toolCallId = "call", toolName = "test", input = "{}", approvalState = ToolApprovalState.Pending),
    ))

    private fun cancel(tool: UIMessagePart.Tool) = if (tool.isExecuted) tool else tool.copy(
        output = listOf(UIMessagePart.Text("cancelled")),
        approvalState = ToolApprovalState.Denied("stopped"),
    )

    @Test
    fun stopUsesLatestStreamingContentAndPreservesMetadataAndAlternatives() {
        val old = pendingMessage()
        val response = pendingMessage()
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(old, response), selectIndex = 1),
        ))
        val latestResponse = response.copy(parts = response.parts + UIMessagePart.Text("last streamed text"))
        val latest = source.copy(title = "new title", messageNodes = listOf(
            source.messageNodes.single().copy(messages = listOf(old, latestResponse)),
        ))
        val finished = finishStoppedGeneration(source, latest, ::cancel)
        assertEquals("new title", finished.title)
        assertEquals(old, finished.messageNodes.single().messages.first())
        assertEquals(latestResponse.parts.last(), finished.currentMessages.single().parts.last())
        val tool = finished.currentMessages.single().parts.first() as UIMessagePart.Tool
        assertFalse(tool.isPending)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun stopDoesNotRestoreOrCancelSwitchedOrEditedBranch() {
        val old = pendingMessage()
        val response = pendingMessage()
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode(messages = listOf(old, response), selectIndex = 1),
        ))
        val edited = pendingMessage()
        val changes = listOf(
            source.copy(assistantId = Uuid.random()),
            source.copy(id = Uuid.random()),
            source.copy(messageNodes = listOf(source.messageNodes.single().copy(selectIndex = 0))),
            source.copy(messageNodes = listOf(source.messageNodes.single().copy(messages = listOf(old, response, edited), selectIndex = 2))),
            source.copy(messageNodes = emptyList()),
        )
        changes.forEach { latest ->
            assertSame(latest, finishStoppedGeneration(source, latest, ::cancel))
        }
    }

    @Test
    fun stopIncludesResponseAppendedDuringCancellation() {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("question").toMessageNode()))
        val response = pendingMessage()
        val latest = source.copy(messageNodes = source.messageNodes + response.toMessageNode())
        val finished = finishStoppedGeneration(source, latest, ::cancel)
        assertEquals(source.currentMessages.first(), finished.currentMessages.first())
        assertFalse((finished.currentMessages.last().parts.single() as UIMessagePart.Tool).isPending)
        assertEquals(latest.messageNodes.map { it.id }, finished.messageNodes.map { it.id })
    }
}
