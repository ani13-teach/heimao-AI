package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationContextBlocksIntegrationTest {
    private fun conversation(messages: List<UIMessage>) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { MessageNode.of(it) },
        customSystemPrompt = "conversation instruction", workspaceCwd = "project",
    )

    @Test fun `regeneration maps request nodes and response destination without old selected response`() {
        val input = listOf(UIMessage.user("question"))
        val source = conversation(input + UIMessage.assistant("old selected response"))
        val response = UIMessage.assistant("new response")
        val result = generationContextConversation(source, input.size, input + response, listOf(source.messageNodes[1].id))
        assertEquals(source.id, result.id)
        assertEquals(source.assistantId, result.assistantId)
        assertEquals(source.customSystemPrompt, result.customSystemPrompt)
        assertEquals(source.workspaceCwd, result.workspaceCwd)
        assertEquals(source.messageNodes.map { it.id }, result.messageNodes.map { it.id })
        assertEquals(input + response, result.currentMessages)
        assertFalse(result.currentMessages.any { it.toText().contains("old selected") })
    }

    @Test fun `bounded request retains preceding branch hash without sending omitted prefix`() = runBlocking {
        val source = conversation(listOf(UIMessage.user("omitted"), UIMessage.assistant("prefix"),
            UIMessage.user("old " + "x".repeat(6000)), UIMessage.assistant("answer"),
            UIMessage.user("recent"), UIMessage.assistant("recent answer"), UIMessage.user("current")))
        val input = source.currentMessages.drop(2)
        val mapped = generationContextConversation(source, input.size, input, emptyList())
        assertEquals(source.currentMessages, mapped.currentMessages)
        val prompt = LogicalPrompt.fromMessages(input)
        val compacted = compactContextBlocks(prompt, mapped, input, emptyList(), estimatePromptTokens(prompt), 300) { _, _ -> "facts" }
        val block = compacted.blocks.single()
        assertEquals(contextSourceHash(source.currentMessages.take(4)), block.prefixHash)
        assertEquals(listOf(block), activeContextBlocks(source, input, listOf(block)))
        assertFalse(compacted.prompt.toMessages().any { it.toText().contains("omitted") })
    }

    @Test fun `input must remain on selected source branch`() {
        val source = conversation(listOf(UIMessage.user("selected")))
        try {
            generationContextConversation(source, 1, listOf(UIMessage.user("other branch")), emptyList())
            fail("Unselected input must be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun `committed blocks are reusable on tool continuation and originals remain unchanged`() = runBlocking {
        val originals = listOf(
            UIMessage.user("old " + "x".repeat(6000)), UIMessage.assistant("old answer"),
            UIMessage.user("recent"), UIMessage.assistant("recent answer"), UIMessage.user("current"),
        )
        val source = conversation(originals)
        val request = generationContextConversation(source, originals.size, originals, emptyList())
        val prompt = LogicalPrompt.fromMessages(originals)
        val result = compactContextBlocks(
            prompt, request, originals, emptyList(), estimatePromptTokens(prompt), 300,
        ) { _, _ -> "old facts" }
        assertTrue(result.blocks.isNotEmpty())
        var committed = false
        val ready = committedContextBlockPrompt(result.prompt, originals, result.blocks) { savedMessages, savedBlocks ->
            assertEquals(originals, savedMessages)
            assertEquals(result.blocks, savedBlocks)
            assertFalse(savedMessages.any { it.isSynthetic })
            committed = true
        }
        assertTrue(committed)
        assertSame(result.prompt, ready)
        val response = UIMessage.assistant("tool continuation")
        val continued = generationContextConversation(source, originals.size, originals + response, emptyList())
        assertEquals(source.currentMessages, originals)
        assertEquals(result.blocks, activeContextBlocks(continued, originals + response, result.blocks))
        val projected = projectContextBlocks(originals + response, result.blocks)
        assertEquals(1, projected.count { it.id == result.blocks.single().id })
        assertTrue(projected.last().id == response.id)
        assertEquals(contextSourceHash(originals.take(2)), result.blocks.single().sourceHash)
        assertEquals(contextSourceHash(originals.take(2)), result.blocks.single().prefixHash)
    }

    @Test fun `failed block persistence never exposes provider prompt`() = runBlocking {
        val originals = listOf(UIMessage.user("current"))
        var providerCalled = false
        try {
            committedContextBlockPrompt(LogicalPrompt.fromMessages(originals), originals, emptyList()) { _, _ ->
                error("storage unavailable")
            }
            providerCalled = true
            fail("Commit failure must abort")
        } catch (error: IllegalStateException) {
            assertEquals("storage unavailable", error.message)
        }
        assertFalse(providerCalled)
    }

    @Test fun `cancellation after insert prevents provider request`() = runBlocking {
        val originals = listOf(UIMessage.user("current"))
        val requestJob = Job()
        var inserted = false
        var providerCalled = false
        try {
            withContext(requestJob) {
                committedContextBlockPrompt(LogicalPrompt.fromMessages(originals), originals, emptyList<ContextBlock>()) { _, _ ->
                    inserted = true
                    requestJob.cancel()
                }
                providerCalled = true
            }
            fail("Cancellation must abort")
        } catch (_: CancellationException) {
        }
        assertTrue(inserted)
        assertFalse(providerCalled)
    }
}
