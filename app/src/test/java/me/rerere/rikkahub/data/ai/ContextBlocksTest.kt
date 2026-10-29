package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.ContextCompression
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.ContextBlockSource
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextBlocksTest {
    private fun turn(label: String) = listOf(
        UIMessage.user("$label question " + "x".repeat(2400)), UIMessage.assistant("$label answer")
    )
    private fun conversation(messages: List<UIMessage>) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { MessageNode.of(it) }
    )
    private fun block(c: Conversation, start: Int, end: Int, time: Long = 1) = ContextBlock(
        conversationId = c.id, assistantId = c.assistantId,
        sources = c.messageNodes.subList(start, end).map { ContextBlockSource(it.id, it.currentMessage.id) },
        sourceHash = contextSourceHash(c.currentMessages.subList(start, end)),
        prefixHash = contextSourceHash(c.currentMessages.take(end)), summary = "frozen facts", createdAt = time,
    )
    private fun target(p: LogicalPrompt, removable: List<UIMessage>, count: Int = 1): Int {
        val ids = removable.map { it.id }.toSet()
        return estimatePromptTokens(p.copy(history = p.history.filter { it.id !in ids } +
            List(count) { contextCompressionMessage("[context_block id=${Uuid.random()}]\nfacts") })) + 32
    }
    private suspend fun compact(
        c: Conversation, p: LogicalPrompt = LogicalPrompt.fromMessages(c.currentMessages),
        existing: List<ContextBlock> = emptyList(), target: Int,
        maxSourceTokens: Int = 16_000, summarize: suspend (String, Int) -> String = { _, _ -> "facts" },
    ) = compactContextBlocks(p, c, c.currentMessages, existing, estimatePromptTokens(p), target, maxSourceTokens, summarize = summarize)

    @Test fun `blocks validate selected path content prefix owner conversation and format`() {
        val c = conversation(turn("a") + turn("b") + UIMessage.user("now"))
        val b = block(c, 2, 4)
        assertEquals(listOf(b), activeContextBlocks(c, c.currentMessages, listOf(b)))
        val edited = c.updateCurrentMessages(listOf(c.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("edit prefix")))))
        assertTrue(activeContextBlocks(edited, edited.currentMessages, listOf(b)).isEmpty())
        val sourceEdit = c.updateCurrentMessages(listOf(c.currentMessages[2].copy(parts = listOf(UIMessagePart.Text("edit source")))))
        assertTrue(activeContextBlocks(sourceEdit, sourceEdit.currentMessages, listOf(b)).isEmpty())
        assertTrue(activeContextBlocks(c.copy(assistantId = Uuid.random()), c.currentMessages, listOf(b)).isEmpty())
        assertTrue(activeContextBlocks(c.copy(id = Uuid.random()), c.currentMessages, listOf(b)).isEmpty())
        assertTrue(activeContextBlocks(c, c.currentMessages, listOf(b.copy(formatVersion = 3))).isEmpty())
        val fork = c.copy(messageNodes = c.messageNodes.map { it.copy(id = Uuid.random()) })
        assertTrue(activeContextBlocks(fork, fork.currentMessages, listOf(b)).isEmpty())
        val nodes = c.messageNodes.toMutableList()
        nodes[2] = nodes[2].copy(messages = nodes[2].messages + UIMessage.user("other branch"), selectIndex = 1)
        val branch = c.copy(messageNodes = nodes)
        assertTrue(activeContextBlocks(branch, branch.currentMessages, listOf(b)).isEmpty())
        assertTrue(activeContextBlocks(c, c.currentMessages, listOf(b.copy(sources = b.sources.reversed()))).isEmpty())
    }

    @Test fun `overlaps select earliest commit deterministically and subranges cannot hide absent sources`() {
        val c = conversation(turn("a") + turn("b") + UIMessage.user("now"))
        val first = block(c, 0, 2, 1)
        val later = block(c, 1, 4, 2)
        assertEquals(listOf(first), activeContextBlocks(c, c.currentMessages, listOf(later, first)))
        assertEquals(listOf(first), activeContextBlocks(c, c.currentMessages, listOf(first, later)))
        val partial = c.currentMessages.drop(1)
        assertTrue(activeContextBlocks(c, partial, listOf(first)).isEmpty())
        assertEquals(partial, projectContextBlocks(partial, listOf(first)))
        val disjoint = block(c, 2, 4)
        assertEquals(listOf(disjoint), activeContextBlocks(c, c.currentMessages.drop(2), listOf(disjoint)))
    }

    @Test fun `projection preserves system position originals and stable block identity`() {
        val messages = listOf(UIMessage.system("prefix"), UIMessage.user("q"), UIMessage.system("middle"), UIMessage.assistant("a"))
        val c = conversation(messages)
        val b = block(c, 0, 4)
        val projected = projectContextBlocks(messages, listOf(b))
        assertEquals(listOf(messages[0].id, b.id, messages[2].id), projected.map { it.id })
        assertEquals(projected, projectContextBlocks(messages, listOf(b)))
        assertEquals(MessageRole.USER, projected[1].role)
        assertTrue(projected[1].isSynthetic)
        assertTrue(projected[1].toText().contains("[context_block id=${b.id}]"))
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `compaction freezes blocks across generations and protects two real turns`() = runBlocking {
        val messages = turn("old") + turn("protected") + UIMessage.user("current")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val first = compact(c, p, target = target(p, messages.take(2))) { source, _ ->
            assertTrue(source.contains("old question"))
            assertFalse(source.contains("protected question"))
            assertFalse(source.contains("current"))
            "first frozen summary"
        }
        assertEquals(1, first.blocks.size)
        assertEquals(messages.take(2).map { it.id }, first.blocks.single().sources.map { it.messageId })
        assertTrue(estimatePromptTokens(first.prompt) <= target(p, messages.take(2)))
        assertEquals(messages.drop(2).map { it.id }, (first.prompt.history + listOfNotNull(first.prompt.latestUser)).drop(1).map { it.id })
        val nextMessages = messages + UIMessage.assistant("current answer") + UIMessage.user("next")
        val next = c.copy(messageNodes = c.messageNodes + nextMessages.drop(messages.size).map { MessageNode.of(it) })
        val active = activeContextBlocks(next, nextMessages, first.blocks)
        val nextPrompt = LogicalPrompt.fromMessages(projectContextBlocks(nextMessages, active))
        val second = compact(next, nextPrompt, active, target(nextPrompt, messages.subList(2, 4))) { source, _ ->
            assertTrue(source.contains("first frozen summary"))
            assertFalse(source.contains("old question"))
            assertTrue(source.contains("protected question"))
            "second summary"
        }
        assertEquals(1, second.blocks.size)
        assertTrue(second.prompt.history.first().toText().contains("second summary"))
        assertEquals(second.blocks, second.activeBlocks)
        assertEquals(second.blocks, activeContextBlocks(next, nextMessages, active + second.blocks))
        assertEquals(2, validContextBlocks(next, nextMessages, active + second.blocks).size)
        assertTrue(messages.all { it.contextCompression == null })
    }

    @Test fun `synthetic injections and transformed splits survive identity based replacement`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val raw = LogicalPrompt.fromMessages(messages)
        val injected = UIMessage.user("keep injected text").copy(isSynthetic = true)
        val transformed = raw.copy(history = listOf(raw.history[0], injected,
            raw.history[0].copy(parts = listOf(UIMessagePart.Text("split continuation")))) + raw.history.drop(1))
        val result = compact(c, transformed, target = target(transformed, messages.take(2)))
        assertTrue(result.prompt.history.contains(injected))
        assertFalse(result.prompt.history.any { it.id in messages.take(2).map { old -> old.id } })
        assertEquals(1, result.blocks.size)
    }

    @Test fun `missing transformed source prevents partial round deletion`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages).let { it.copy(history = it.history.drop(1)) }
        val error = runCatching { compact(c, p, target = 1) { _, _ -> error("must not call") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("完整历史用户轮"))
    }

    @Test fun `legacy upgrade retains marker and refuses latest marker covering protected turn`() {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val marker = ContextCompression(contextSourceHash(messages.take(2)), "legacy")
        val saved = messages.mapIndexed { i, message -> if (i == 1) message.copy(contextCompression = marker) else message }
        val c = conversation(saved)
        val b = legacyContextBlock(c, saved)!!
        assertEquals(marker.sourceHash, b.sourceHash)
        assertEquals(marker.sourceHash, b.prefixHash)
        assertEquals(marker, saved[1].contextCompression)
        assertEquals(b.id, legacyContextBlock(c, saved)!!.id)
        val newer = saved.mapIndexed { i, message -> if (i == 3) message.copy(contextCompression =
            ContextCompression(contextSourceHash(saved.take(4)), "newer")) else message }
        assertNull(legacyContextBlock(c.updateCurrentMessages(newer), newer))
    }

    @Suppress("DEPRECATION")
    @Test fun `long tool round chunks preserve calls results attachments and exclude reasoning`() = runBlocking {
        val tools = listOf(UIMessage.user("old"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("hidden private reasoning"),
            UIMessagePart.Document("file:///paper.txt", "paper.txt", "text/plain"),
            UIMessagePart.ToolCall("call1", "lookup", "{\"input\":\"" + "x".repeat(9000) + "\"}")
        )), UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult(
            "call1", "lookup", JsonPrimitive("tool output"), JsonPrimitive("tool input")
        ))), UIMessage.assistant("done"))
        val messages = tools + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val sources = mutableListOf<String>()
        val result = compact(c, p, target = target(p, tools), maxSourceTokens = 1000) { source, budget ->
            assertTrue(estimateTextTokens(source) <= 1000)
            assertTrue(budget in 1..1024)
            sources.add(source)
            "f"
        }
        assertTrue(sources.size in 3..16)
        val joined = sources.joinToString("")
        assertTrue(joined.contains("tool output"))
        assertTrue(joined.contains("tool input"))
        assertTrue(joined.contains("paper.txt"))
        assertFalse(joined.contains("hidden private reasoning"))
        assertEquals(tools.map { it.id }, result.blocks.single().sources.map { it.messageId })
    }

    @Suppress("DEPRECATION")
    @Test fun `incomplete legacy pair and pending tool cannot be covered`() = runBlocking {
        for (part in listOf<UIMessagePart>(UIMessagePart.ToolCall("x", "lookup", "{}"),
            UIMessagePart.Tool("x", "lookup", "{}"))) {
            val messages = listOf(UIMessage.user("old"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))) +
                turn("recent") + UIMessage.user("now")
            val c = conversation(messages)
            assertTrue(runCatching { compact(c, target = 1) { _, _ -> error("called") } }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun `too many fragments fail atomically without exceeding call cap`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        var calls = 0
        val error = runCatching { compact(c, p, target = target(p, messages.take(2)), maxSourceTokens = 10) { _, _ -> calls++; "f" } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, calls)
        assertTrue(error!!.message!!.contains("16"))
    }

    @Test fun `many old rounds roll into one bounded snapshot`() = runBlocking {
        val old = (0 until 20).flatMap { turn("old$it") }
        val messages = old + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        var calls = 0
        val result = compact(c, p, target = target(p, old)) { _, _ -> calls++; "f" }
        assertEquals(1, result.blocks.size)
        assertEquals(2, result.blocks.single().formatVersion)
        assertTrue(calls in 1..16)
        assertEquals(old.map { it.id }, result.blocks.single().sources.map { it.messageId })
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `multiple oldest rounds produce one immutable snapshot`() = runBlocking {
        val old = turn("first") + turn("second")
        val messages = old + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val seen = mutableListOf<String>()
        val goal = target(p, old, 2)
        val result = compact(c, p, target = goal) { source, _ -> seen.add(source); "facts" }
        assertEquals(1, result.blocks.size)
        assertEquals(1, seen.size)
        assertTrue(seen.single().contains("first question"))
        assertTrue(seen.single().contains("second question"))
        assertTrue(estimatePromptTokens(result.prompt) <= goal)
        assertEquals(result.blocks, activeContextBlocks(c, messages, result.blocks))
    }

    @Test fun `embedded systems stay at their original timeline position during compaction`() = runBlocking {
        val originalTurn = turn("old")
        val middle = UIMessage.system("mandatory middle")
        val old = listOf(originalTurn[0], middle, originalTurn[1])
        val messages = old + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val goal = target(p, originalTurn)
        val result = compact(c, p, target = goal) { source, _ ->
            assertFalse(source.contains("mandatory middle"))
            "facts"
        }
        assertEquals(middle, result.prompt.history[1])
        assertEquals(result.blocks.single().id, result.prompt.history[0].id)
        assertTrue(result.blocks.single().sources.any { it.messageId == middle.id })
        assertEquals(listOf(middle), result.prompt.history.filter { it.role == MessageRole.SYSTEM })
    }

    @Test fun `empty summary cancellation and oversized summaries return no partial state`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val goal = target(p, messages.take(2))
        assertTrue(runCatching { compact(c, p, target = goal) { _, _ -> " " } }.exceptionOrNull() is IllegalStateException)
        val cancellation = CancellationException("cancel summary")
        assertSame(cancellation, runCatching { compact(c, p, target = goal) { _, _ -> throw cancellation } }.exceptionOrNull())
        var calls = 0
        assertTrue(runCatching { compact(c, p, target = goal) { _, _ -> calls++; "x".repeat(6000) } }.exceptionOrNull() is IllegalStateException)
        assertEquals(2, calls)
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `below threshold appends full history without changing any summary byte or identity`() = runBlocking {
        val original = turn("old") + turn("recent") + UIMessage.user("current")
        val c = conversation(original)
        val p = LogicalPrompt.fromMessages(original)
        val first = compact(c, p, target = target(p, original.take(2)))
        val frozen = first.prompt.history.first()
        val before = kotlinx.serialization.json.Json.encodeToString(UIMessage.serializer(), frozen)
        var next = c
        repeat(3) { index ->
            next = next.copy(messageNodes = next.messageNodes +
                listOf(UIMessage.assistant("answer$index"), UIMessage.user("next$index")).map { MessageNode.of(it) })
            val active = activeContextBlocks(next, next.currentMessages, first.blocks)
            val projected = LogicalPrompt.fromMessages(projectContextBlocks(next.currentMessages, active))
            val reused = compactContextBlocks(projected, next, next.currentMessages, active,
                100_000, 50_000, rebuildPrompt = { error("must not rebuild below threshold") }) { _, _ -> error("must not summarize below threshold") }
            assertTrue(reused.blocks.isEmpty())
            assertEquals(first.blocks, reused.activeBlocks)
            assertEquals(frozen, reused.prompt.history.first())
            assertEquals(before, kotlinx.serialization.json.Json.encodeToString(UIMessage.serializer(), reused.prompt.history.first()))
            assertEquals(next.currentMessages.drop(2).map { it.id },
                reused.prompt.toMessages().drop(1).map { it.id })
        }
    }

    @Test fun `v1 snapshots and uncovered gap merge once without duplicating originals`() = runBlocking {
        val messages = turn("first") + turn("gap") + turn("third") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val old = listOf(block(c, 0, 2).copy(summary = "first saved facts"),
            block(c, 4, 6).copy(summary = "third saved facts"))
        val p = LogicalPrompt.fromMessages(projectContextBlocks(messages, old))
        val result = compact(c, p, old, target(p, p.history.take(4))) { input, _ ->
            assertEquals(1, Regex("first saved facts").findAll(input).count())
            assertEquals(1, Regex("third saved facts").findAll(input).count())
            assertTrue(input.contains("gap question"))
            assertFalse(input.contains("first question"))
            assertFalse(input.contains("third question"))
            "merged facts"
        }
        assertEquals(2, result.blocks.single().formatVersion)
        assertEquals(messages.take(6).map { it.id }, result.blocks.single().sources.map { it.messageId })
        assertEquals(result.blocks, result.activeBlocks)
        assertEquals(result.blocks, activeContextBlocks(c, messages, old + result.blocks))
        assertEquals(3, validContextBlocks(c, messages, old + result.blocks).size)
        val projected = projectContextBlocks(messages, result.activeBlocks)
        assertEquals(listOf(result.blocks.single().id) + messages.drop(6).map { it.id }, projected.map { it.id })
    }

    @Test fun `legacy marker stays stable until actual trigger and then becomes v2`() = runBlocking {
        val original = turn("old") + turn("recent") + UIMessage.user("now")
        val saved = original.mapIndexed { index, message -> if (index == 1) message.copy(contextCompression =
            ContextCompression(contextSourceHash(original.take(2)), "legacy facts")) else message }
        val c = conversation(saved)
        val legacy = legacyContextBlock(c, saved)!!
        val p = LogicalPrompt.fromMessages(projectContextBlocks(saved, listOf(legacy)))
        val idle = compactContextBlocks(p, c, saved, listOf(legacy), 100_000, 50_000) { _, _ -> error("migration request") }
        assertTrue(idle.blocks.isEmpty())
        assertEquals(listOf(legacy), idle.activeBlocks)
        val goal = estimatePromptTokens(p) - 1
        val result = compact(c, p, listOf(legacy), goal) { input, _ ->
            assertTrue(input.contains("legacy facts"))
            assertFalse(input.contains("old question"))
            "f"
        }
        assertEquals(2, result.blocks.single().formatVersion)
        assertEquals(result.blocks, result.activeBlocks)
        assertEquals(saved, c.currentMessages)
    }

    @Suppress("DEPRECATION")
    @Test fun `rolling snapshot cannot span an unclosed tool turn`() = runBlocking {
        val pending = listOf(UIMessage.user("pending"), UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("pending-call", "lookup", "{}"))))
        val old = turn("safe")
        val messages = old + pending + turn("later") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val result = compact(c, p, target = target(p, old)) { input, _ ->
            assertFalse(input.contains("pending-call"))
            assertFalse(input.contains("later question"))
            "facts"
        }
        assertEquals(old.map { it.id }, result.blocks.single().sources.map { it.messageId })
        assertTrue(result.prompt.toMessages().any { it.id == pending.last().id })
    }

    @Test fun `v2 precedence and partial overlap leave uncovered originals visible`() {
        val c = conversation(turn("a") + turn("b") + turn("recent") + UIMessage.user("now"))
        val older = block(c, 0, 4)
        val newer = block(c, 2, 4, 2).copy(formatVersion = 2)
        val active = activeContextBlocks(c, c.currentMessages, listOf(older, newer))
        assertEquals(listOf(newer), active)
        assertEquals(c.currentMessages.take(2).map { it.id } + newer.id + c.currentMessages.drop(4).map { it.id },
            projectContextBlocks(c.currentMessages, active).map { it.id })
        val combined = older.copy(id = Uuid.random(), formatVersion = 2, createdAt = 3)
        assertEquals(listOf(combined), activeContextBlocks(c, c.currentMessages, listOf(newer, older, combined)))
        val edited = c.updateCurrentMessages(listOf(c.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("edit")))))
        assertTrue(validContextBlocks(edited, edited.currentMessages, listOf(newer, combined)).isEmpty())
    }

    @Test fun `rolling summary receives more than 1024 tokens when target permits`() = runBlocking {
        val old = listOf(UIMessage.user("x".repeat(60_000)), UIMessage.assistant("old answer"))
        val messages = old + listOf(UIMessage.user("recent"), UIMessage.assistant("answer"), UIMessage.user("now"))
        val c = conversation(messages)
        var outputBudget = 0
        val result = compact(c, target = 10_000) { _, budget ->
            outputBudget = budget
            "f".repeat(6000)
        }
        assertTrue(outputBudget > 1024)
        assertEquals(1500, estimateTextTokens(result.blocks.single().summary))
    }

    @Test fun `fragment output fits smaller source limit before merge into larger final budget`() = runBlocking {
        val old = listOf(UIMessage.user("x".repeat(60_000)), UIMessage.assistant("old answer"))
        val messages = old + listOf(UIMessage.user("recent"), UIMessage.assistant("answer"), UIMessage.user("now"))
        val c = conversation(messages)
        val count = splitCompressionSource(renderCompressionHistory(old), 2000).size
        assertTrue(count in 2..15)
        var calls = 0
        val result = compact(c, target = 10_000, maxSourceTokens = 2000) { input, budget ->
            calls++
            assertTrue(estimateTextTokens(input) <= 2000)
            if (calls <= count) "f".repeat(budget * 4) else {
                assertTrue(budget > 2000)
                "merged facts"
            }
        }
        assertEquals(count + 1, calls)
        assertEquals("merged facts", result.blocks.single().summary)
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `fragment overshoot and insufficient per fragment budget fail without partial results`() = runBlocking {
        val old = listOf(UIMessage.user("x".repeat(480)), UIMessage.assistant("answer"))
        val messages = old + listOf(UIMessage.user("recent"), UIMessage.assistant("answer"), UIMessage.user("now"))
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val goal = target(p, old) - 32
        val count = splitCompressionSource(renderCompressionHistory(old), 10).size
        assertTrue(count in 6..15)
        var calls = 0
        val insufficient = runCatching {
            compact(c, p, target = goal, maxSourceTokens = 10) { _, _ -> calls++; "f" }
        }.exceptionOrNull()
        assertTrue(insufficient.toString(), insufficient!!.message!!.contains("至少一个"))
        assertEquals(0, calls)
        val over = runCatching {
            compact(c, p, target = target(p, old) - 24, maxSourceTokens = 40) { _, budget ->
                calls++
                "f".repeat(budget * 4 + 4)
            }
        }.exceptionOrNull()
        assertTrue(over.toString(), over!!.message!!.contains("分配预算"))
        assertEquals(1, calls)
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `v2 snapshot serialization retains both immutable versions`() {
        val c = conversation(turn("a") + turn("recent") + UIMessage.user("now"))
        val v1 = block(c, 0, 2)
        val v2 = v1.copy(id = Uuid.random(), formatVersion = 2, summary = "rolling facts")
        val codec = kotlinx.serialization.json.Json
        val serializer = kotlinx.serialization.builtins.ListSerializer(ContextBlock.serializer())
        val saved = listOf(v1, v2)
        assertEquals(saved, codec.decodeFromString(serializer, codec.encodeToString(serializer, saved)))
    }

    @Test fun `rebuilt placeholder must occur exactly once and mandatory content must fit before summary`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val goal = target(p, messages.take(2))
        var calls = 0
        for (mode in 0..2) {
            val failure = runCatching {
                compactContextBlocks(p, c, messages, emptyList(), estimatePromptTokens(p), goal,
                    rebuildPrompt = { blocks ->
                        val rebuilt = LogicalPrompt.fromMessages(projectContextBlocks(messages, blocks))
                        when (mode) {
                            0 -> rebuilt.copy(history = rebuilt.history.filterNot { it.id == blocks.single().id })
                            1 -> rebuilt.copy(history = rebuilt.history + rebuilt.history.first())
                            else -> rebuilt.copy(system = listOf(UIMessage.system("mandatory".repeat(1000))))
                        }
                    }) { _, _ -> calls++; "facts" }
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertTrue(failure!!.message!!, failure.message!!.contains(if (mode < 2) "恰好" else "必留内容"))
        }
        assertEquals(0, calls)
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `cancellation during placeholder rebuild prevents summary`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages)
        val requestJob = Job()
        var rebuilds = 0
        var calls = 0
        val failure = runCatching {
            withContext(requestJob) {
                compactContextBlocks(p, c, messages, emptyList(), estimatePromptTokens(p), target(p, messages.take(2)),
                    rebuildPrompt = { blocks ->
                        rebuilds++
                        requestJob.cancel()
                        LogicalPrompt.fromMessages(projectContextBlocks(messages, blocks))
                    }) { _, _ -> calls++; "facts" }
            }
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is CancellationException)
        assertEquals(1, rebuilds)
        assertEquals(0, calls)
        assertEquals(messages, c.currentMessages)
    }

    @Test fun `mandatory prompt over target fails before calling model and disabled keeps projection`() = runBlocking {
        val messages = turn("old") + turn("recent") + UIMessage.user("now")
        val c = conversation(messages)
        val p = LogicalPrompt.fromMessages(messages).copy(system = listOf(UIMessage.system("mandatory".repeat(1000))))
        var calls = 0
        assertTrue(runCatching { compact(c, p, target = 100) { _, _ -> calls++; "f" } }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
        val b = block(c, 0, 2)
        val projected = LogicalPrompt.fromMessages(projectContextBlocks(messages, listOf(b)))
        val disabled = compactContextBlocks(projected, c, messages, listOf(b), 0, 1,
            rebuildPrompt = { error("must not rebuild when disabled") }) { _, _ -> error("called") }
        assertSame(projected, disabled.prompt)
        assertTrue(disabled.blocks.isEmpty())
    }
}
