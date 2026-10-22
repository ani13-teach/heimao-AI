package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutCodemodeTrace
import me.rerere.rikkahub.data.datastore.validateContextCompressionSettings
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.ContextBlockSource
import me.rerere.rikkahub.data.model.Conversation
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** A block is tied to the selected node/message path, including the prefix before its sources. */
internal fun activeContextBlocks(
    conversation: Conversation,
    messages: List<UIMessage>,
    blocks: List<ContextBlock>,
): List<ContextBlock> {
    val selected = conversation.messageNodes.map { node ->
        val message = node.messages.getOrNull(node.selectIndex) ?: return emptyList()
        ContextBlockSource(node.id, message.id) to message
    }
    if (selected.map { it.first.messageId }.distinct().size != selected.size ||
        messages.map { it.id }.distinct().size != messages.size) return emptyList()
    val occupied = mutableSetOf<ContextBlockSource>()
    return blocks.sortedWith(compareBy<ContextBlock> { it.createdAt }.thenBy { it.id.toString() })
        .filter { block ->
            if (block.formatVersion != 1 || block.conversationId != conversation.id ||
                block.assistantId != conversation.assistantId || block.summary.isBlank() ||
                block.sources.isEmpty() || block.sources.any { it in occupied }) return@filter false
            val start = selected.indexOfFirst { it.first == block.sources.first() }
            val end = start + block.sources.size
            if (start < 0 || end > selected.size ||
                selected.subList(start, end).map { it.first } != block.sources) return@filter false
            val inputStart = messages.indexOfFirst { it.id == block.sources.first().messageId }
            if (inputStart < 0 || inputStart + block.sources.size > messages.size) return@filter false
            val input = messages.subList(inputStart, inputStart + block.sources.size)
            if (input.map { it.id } != block.sources.map { it.messageId } ||
                contextSourceHash(input) != block.sourceHash ||
                contextSourceHash(selected.subList(start, end).map { it.second }) != block.sourceHash ||
                contextSourceHash(selected.take(end).map { it.second }) != block.prefixHash ||
                input.none { it.role != MessageRole.SYSTEM }) return@filter false
            occupied.addAll(block.sources)
            true
        }.sortedBy { block -> selected.indexOfFirst { it.first == block.sources.first() } }
}

private fun ContextBlock.asMessage(): UIMessage =
    contextCompressionMessage("[context_block id=$id]\n$summary").copy(
        id = id, createdAt = kotlinx.datetime.LocalDateTime(1970, 1, 1, 0, 0),
    )

/** Only fully present ranges may be projected; SYSTEM messages always retain their position. */
internal fun projectContextBlocks(messages: List<UIMessage>, blocks: List<ContextBlock>): List<UIMessage> {
    val replacements = mutableMapOf<Uuid, ContextBlock>()
    val covered = mutableSetOf<Uuid>()
    blocks.sortedWith(compareBy<ContextBlock> { it.createdAt }.thenBy { it.id.toString() }).forEach { block ->
        val ids = block.sources.map { it.messageId }
        val start = messages.indexOfFirst { it.id == ids.firstOrNull() }
        if (ids.isEmpty() || start < 0 || start + ids.size > messages.size ||
            messages.subList(start, start + ids.size).map { it.id } != ids ||
            ids.any { it in covered }) return@forEach
        val first = messages.subList(start, start + ids.size).firstOrNull { it.role != MessageRole.SYSTEM }
            ?: return@forEach
        replacements[first.id] = block
        covered.addAll(ids)
    }
    return buildList {
        messages.forEach { message ->
            if (message.role == MessageRole.SYSTEM) add(message)
            else if (message.id in replacements) add(replacements.getValue(message.id).asMessage())
            else if (message.id !in covered) add(message)
        }
    }
}

private data class ContextRound(val start: Int, val end: Int, val complete: Boolean)

private fun contextRounds(messages: List<UIMessage>): List<ContextRound> {
    val starts = messages.indices.filter { messages[it].role == MessageRole.USER && !messages[it].isSynthetic }
    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1) ?: messages.size
        ContextRound(start, end, index < starts.lastIndex && completeToolRound(messages.subList(start, end)))
    }
}

/** Legacy pairs must close within one real turn; unresolved tools may never be hidden. */
@Suppress("DEPRECATION")
private fun completeToolRound(messages: List<UIMessage>): Boolean {
    if (messages.none { it.role == MessageRole.ASSISTANT && !it.isSynthetic }) return false
    val calls = mutableMapOf<String, String>()
    fun complete(parts: List<UIMessagePart>): Boolean {
        for (part in parts) {
            when (part) {
                is UIMessagePart.ToolCall -> {
                    if (part.approvalState is ToolApprovalState.Pending || calls.put(part.toolCallId, part.toolName) != null) return false
                }
                is UIMessagePart.ToolResult -> if (calls.remove(part.toolCallId) != part.toolName) return false
                is UIMessagePart.Tool -> if (!part.isExecuted || part.isPending || !complete(part.output)) return false
                is UIMessagePart.ServerTool -> if (!part.isFinished) return false
                else -> Unit
            }
        }
        return true
    }
    return messages.all { complete(it.parts) } && calls.isEmpty()
}

/** Keep both the current real turn and the most recent complete real turn. */
private fun protectionStart(messages: List<UIMessage>): Int {
    val rounds = contextRounds(messages)
    return rounds.dropLast(1).lastOrNull { it.complete }?.start ?: rounds.lastOrNull()?.start ?: 0
}

internal fun legacyContextBlock(conversation: Conversation, messages: List<UIMessage>): ContextBlock? {
    val protected = protectionStart(messages)
    for (index in messages.lastIndex downTo 0) {
        val marker = messages[index].contextCompression ?: continue
        val prefix = messages.take(index + 1)
        if (marker.summary.isBlank() || marker.sourceHash != contextSourceHash(prefix)) continue
        // Do not fall back to an older marker after finding a valid but unsafe latest marker.
        if (index >= protected) return null
        val sources = prefix.map { message ->
            val node = conversation.messageNodes.firstOrNull { it.messages.getOrNull(it.selectIndex)?.id == message.id }
                ?: return null
            ContextBlockSource(node.id, message.id)
        }
        val stableId = Uuid.parse(UUID.nameUUIDFromBytes(
            "${conversation.id}:${conversation.assistantId}:${marker.sourceHash}".toByteArray(StandardCharsets.UTF_8)
        ).toString())
        val block = ContextBlock(
            id = stableId, conversationId = conversation.id, assistantId = conversation.assistantId,
            sources = sources, sourceHash = marker.sourceHash, prefixHash = marker.sourceHash,
            summary = marker.summary,
        )
        return activeContextBlocks(conversation, messages, listOf(block)).singleOrNull()
    }
    return null
}

internal data class BlockCompactionResult(val prompt: LogicalPrompt, val blocks: List<ContextBlock>)

/** Adds disjoint blocks atomically. Existing summaries are never used as summarizer input. */
@Suppress("DEPRECATION")
internal suspend fun compactContextBlocks(
    prompt: LogicalPrompt,
    conversation: Conversation,
    messages: List<UIMessage>,
    existingBlocks: List<ContextBlock>,
    triggerTokens: Int,
    targetTokens: Int,
    maxSourceTokens: Int = 16_000,
    summarize: suspend (String, Int) -> String,
): BlockCompactionResult {
    validateContextCompressionSettings(triggerTokens, targetTokens)?.let { error(it) }
    coroutineContext.ensureActive()
    // The caller has already projected active existing blocks before applying transformers.
    if (triggerTokens == 0 || estimatePromptTokens(prompt) < triggerTokens) {
        return BlockCompactionResult(prompt, emptyList())
    }
    require(maxSourceTokens > 0) { "压缩来源分块上限必须大于 0" }
    val sourceLimit = minOf(maxSourceTokens, 16_000)
    val active = activeContextBlocks(conversation, messages, existingBlocks)
    val occupied = active.flatMap { it.sources }.map { it.messageId }.toSet()
    val protected = protectionStart(messages)
    val historyIds = prompt.history.filter { it.role != MessageRole.SYSTEM }.map { it.id }.toSet()
    val outsideIds = (prompt.system + prompt.transientContext + prompt.memorySnapshot + prompt.memoryDelta + prompt.memory +
        prompt.claudeMemoryDelta + prompt.currentToolLoop + listOfNotNull(prompt.latestUser))
        .map { it.id }.toSet()
    val candidates = contextRounds(messages).filter { round ->
        val source = messages.subList(round.start, round.end)
        val ids = source.filter { it.role != MessageRole.SYSTEM }.map { it.id }
        round.complete && round.end <= protected && source.none { it.id in occupied } &&
            ids.isNotEmpty() && ids.all { it in historyIds && it !in outsideIds }
    }.mapNotNull { round ->
        val source = messages.subList(round.start, round.end)
        val sources = source.map { message ->
            val node = conversation.messageNodes.firstOrNull { it.messages.getOrNull(it.selectIndex)?.id == message.id }
                ?: return@mapNotNull null
            ContextBlockSource(node.id, message.id)
        }
        val end = conversation.messageNodes.indexOfFirst { it.id == sources.last().nodeId } + 1
        val block = ContextBlock(
            conversationId = conversation.id, assistantId = conversation.assistantId,
            sources = sources, sourceHash = contextSourceHash(source),
            prefixHash = contextSourceHash(conversation.currentMessages.take(end)), summary = "",
        )
        // Check path contiguity and selected content before any model call.
        if (activeContextBlocks(conversation, messages, listOf(block.copy(summary = "validation"))).isEmpty()) null
        else block to source
    }
    check(candidates.isNotEmpty()) { "没有可压缩的完整历史用户轮，无法满足上下文预算" }

    val systemIds = messages.filter { it.role == MessageRole.SYSTEM }.map { it.id }.toSet()
    fun replace(history: List<UIMessage>, block: ContextBlock): List<UIMessage> {
        val ids = block.sources.map { it.messageId }.filter { it !in systemIds }.toSet()
        var inserted = false
        return buildList {
            history.forEach { message ->
                if (message.role == MessageRole.SYSTEM || message.id !in ids) add(message)
                else if (!inserted) {
                    add(block.asMessage())
                    inserted = true
                }
            }
        }
    }

    // Reserve only the oldest necessary rounds, not an empty block for every old turn.
    val planned = mutableListOf<Pair<ContextBlock, List<UIMessage>>>()
    var minimumHistory = prompt.history
    var remainingBudget = 0L
    for (candidate in candidates) {
        planned.add(candidate)
        minimumHistory = replace(minimumHistory, candidate.first)
        remainingBudget = targetTokens.toLong() - estimatePromptTokens(prompt.copy(history = minimumHistory))
        if (remainingBudget >= planned.size) break
    }
    check(remainingBudget >= planned.size) { "系统、工具、记忆、近期两轮及已有摘要等必留内容已超出压缩目标" }
    var calls = 0
    suspend fun summarizeChecked(source: String, budget: Int): String {
        coroutineContext.ensureActive()
        check(calls < 16) { "本次上下文压缩已达到 16 次摘要调用上限" }
        check(estimateTextTokens(source) <= sourceLimit) { "摘要输入超过来源分块上限" }
        calls++
        val result = summarize(source, budget)
        coroutineContext.ensureActive()
        check(result.isNotBlank()) { "压缩模型返回了空摘要，请重试或更换压缩模型" }
        return result
    }

    var working = prompt
    val added = mutableListOf<ContextBlock>()
    planned.forEachIndexed { index, (emptyBlock, source) ->
        val budget = (remainingBudget / (planned.size - index)).coerceIn(1L, 1024L).toInt()
        val rendered = renderCompressionHistory(source.filter { it.role != MessageRole.SYSTEM }.map { it.withoutCodemodeTrace() })
        check(rendered.isNotBlank()) { "历史消息没有可总结的内容，无法压缩上下文" }
        val chunks = splitCompressionSource(rendered, sourceLimit)
        check(calls + chunks.size <= 16) { "完整用户轮所需摘要调用超过本次 16 次上限" }
        var fragmentBudget = budget.toLong()
        var summary = chunks.mapIndexed { chunkIndex, chunk ->
            val share = (fragmentBudget / (chunks.size - chunkIndex)).coerceIn(1L, 1024L).toInt()
            summarizeChecked(chunk, share).also {
                fragmentBudget = (fragmentBudget - estimateTextTokens(it) - 1).coerceAtLeast(0)
            }
        }.joinToString("\n")
        // A newly created block gets at most one shrink; no committed block is rewritten.
        if (estimateTextTokens(summary) > budget) summary = summarizeChecked(summary, budget)
        check(estimateTextTokens(summary) <= budget) { "摘要收缩后仍超过分块预算，请提高压缩目标或更换压缩模型" }
        val block = emptyBlock.copy(summary = summary)
        remainingBudget -= estimateTextTokens(summary)
        working = working.copy(history = replace(working.history, block))
        added.add(block)
        if (estimatePromptTokens(working) <= targetTokens) {
            coroutineContext.ensureActive()
            return BlockCompactionResult(working, added.toList())
        }
    }
    error("新增摘要累积后仍超出完整请求预算，请提高压缩目标")
}
