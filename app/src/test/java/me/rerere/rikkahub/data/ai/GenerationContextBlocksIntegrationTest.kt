package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.transformers.applyTimeReminder
import me.rerere.rikkahub.data.ai.transformers.applyInjections
import me.rerere.rikkahub.data.ai.transformers.timeReminderId
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.ai.ui.UIMessagePart
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

    @Test fun `new and reused summaries bypass input transforms while originals still transform`() = runBlocking {
        val originals = listOf(UIMessage.user("old " + "x".repeat(6000)), UIMessage.assistant("old answer"),
            UIMessage.user("recent"), UIMessage.assistant("recent answer"), UIMessage.user("current"))
        val c = conversation(originals)
        suspend fun transformed(messages: List<UIMessage>, blocks: List<ContextBlock>) =
            transformContextBlockInput(messages, blocks) { raw ->
                raw.map { it.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("transformed:" + it.toText()))) }
            }
        val firstPrompt = LogicalPrompt.fromMessages(transformed(originals, emptyList()))
        val compacted = compactContextBlocks(firstPrompt, c, originals, emptyList(),
            estimatePromptTokens(firstPrompt), 300,
            rebuildPrompt = { LogicalPrompt.fromMessages(transformed(originals, it)) }) { _, _ -> "literal {cur_date} {{model_name}}" }
        val summary = compacted.prompt.history.first()
        assertFalse(summary.toText().contains("transformed:"))
        val next = originals + UIMessage.assistant("answer") + UIMessage.user("next")
        val continued = c.copy(messageNodes = c.messageNodes + next.drop(originals.size).map { MessageNode.of(it) })
        val reused = transformed(next, activeContextBlocks(continued, next, compacted.activeBlocks))
        assertEquals(summary, reused.first())
        assertTrue(reused.drop(1).all { it.toText().startsWith("transformed:") })
        assertEquals(originals, c.currentMessages)
    }

    @Test fun `rebuild moves top injection before summary and never repeats covered image processing`() = runBlocking {
        val oldUser = UIMessage.user("x".repeat(60_000)).copy(parts = listOf(
            UIMessagePart.Text("x".repeat(60_000)), UIMessagePart.Image("file:///old-image.png")))
        val originals = listOf(oldUser, UIMessage.assistant("old answer"), UIMessage.user("recent"),
            UIMessage.assistant("recent answer"), UIMessage.user("now")).mapIndexed { index, message ->
                message.copy(createdAt = kotlinx.datetime.LocalDateTime(2026, 1, 2, 10, index * 5))
            }
        val c = conversation(originals)
        var imagePasses = 0
        var transforms = 0
        suspend fun build(blocks: List<ContextBlock>): LogicalPrompt = LogicalPrompt.fromMessages(
            transformContextBlockInput(originals, blocks) { input ->
                transforms++
                imagePasses += input.count { m -> m.parts.any { it is UIMessagePart.Image } }
                applyInjections(applyTimeReminder(input), mapOf(InjectionPosition.TOP_OF_CHAT to listOf(
                    PromptInjection.ModeInjection(position = InjectionPosition.TOP_OF_CHAT, content = "independent mode"))))
            })
        val initial = build(emptyList())
        assertEquals(listOf(timeReminderId(oldUser.id)), initial.toMessages()
            .filter { it.toText().contains("<time_reminder>") }.map { it.id })
        var rebuilds = 0
        var calls = 0
        var allocatedBudget = 0
        lateinit var budgetPrompt: LogicalPrompt
        val target = 3000
        val compacted = compactContextBlocks(initial, c, originals, emptyList(), estimatePromptTokens(initial), target,
            rebuildPrompt = { blocks ->
                rebuilds++
                assertEquals("", blocks.single().summary)
                build(blocks).also { budgetPrompt = it }
            }) { _, budget ->
                calls++
                allocatedBudget = budget
                ("中" + "f".repeat((budget - 6) * 4) + "文").also {
                    assertEquals(budget, estimateTextTokens(it))
                }
            }
        assertEquals(1, calls)
        assertEquals(1, rebuilds)
        assertEquals(2, transforms)
        assertEquals(target - estimatePromptTokens(budgetPrompt) - 1, allocatedBudget)
        val rebuilt = compacted.prompt
        assertEquals(target, estimatePromptTokens(rebuilt))
        assertFalse(rebuilt.toMessages().any { it.id == timeReminderId(oldUser.id) })
        assertEquals(listOf(timeReminderId(originals[2].id)), rebuilt.toMessages()
            .filter { it.toText().contains("<time_reminder>") }.map { it.id })
        budgetPrompt.history.filter { it.id != compacted.blocks.single().id }.forEach { message ->
            assertSame(message, rebuilt.history.single { it.id == message.id })
        }
        val reused = build(compacted.activeBlocks)
        assertEquals(1, imagePasses)
        assertEquals(rebuilt.toMessages().map { it.role to it.toText() }, reused.toMessages().map { it.role to it.toText() })
        val summaryId = compacted.blocks.single().id
        assertEquals("independent mode", rebuilt.toMessages().first().toText())
        assertEquals(summaryId, rebuilt.toMessages()[1].id)
        assertEquals(rebuilt.toMessages().first { it.id == summaryId }, reused.toMessages().first { it.id == summaryId })
        val recent = originals[2]
        assertEquals(rebuilt.toMessages().first { it.id == timeReminderId(recent.id) },
            reused.toMessages().first { it.id == timeReminderId(recent.id) })
        assertEquals(3, transforms)
        assertEquals(1, rebuilds)
        assertEquals(originals, c.currentMessages)
    }

    @Test fun `projection rejects gaps and transformations cannot drop or duplicate summary`() = runBlocking {
        val originals = listOf(UIMessage.user("x".repeat(6000)), UIMessage.assistant("old answer"),
            UIMessage.user("recent"), UIMessage.assistant("answer"), UIMessage.user("now"))
        val c = conversation(originals)
        val p = LogicalPrompt.fromMessages(originals)
        val result = compactContextBlocks(p, c, originals, emptyList(), estimatePromptTokens(p), 300) { _, _ -> "facts" }
        val blocks = result.activeBlocks
        val withGap = listOf(originals[0], UIMessage.user("injected").copy(isSynthetic = true)) + originals.drop(1)
        assertEquals(withGap, projectContextBlocks(withGap, blocks))
        assertTrue(runCatching { transformContextBlockInput(withGap, blocks) { it } }.isFailure)
        assertTrue(runCatching { transformContextBlockInput(originals, blocks) { it.drop(1) } }.isFailure)
        assertTrue(runCatching { transformContextBlockInput(originals, blocks) { it + it.first() } }.isFailure)
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
