package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutCodemodeTrace
import me.rerere.rikkahub.data.ai.transformers.timeReminderId
import me.rerere.rikkahub.data.datastore.validateContextCompressionSettings
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.ContextBlockSource
import me.rerere.rikkahub.data.model.Conversation
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** Validate provenance independently of which immutable snapshot is currently sent. */
internal fun validContextBlocks(
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
    return blocks.distinctBy { it.id }.filter { block ->
            if (block.formatVersion !in 1..2 || block.conversationId != conversation.id ||
                block.assistantId != conversation.assistantId || block.summary.isBlank() ||
                block.sources.isEmpty()) return@filter false
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
            true
        }.sortedBy { block -> selected.indexOfFirst { it.first == block.sources.first() } }
}

/** Prefer covering v2 snapshots; an overlapping rejected block leaves its uncovered originals visible. */
internal fun activeContextBlocks(
    conversation: Conversation,
    messages: List<UIMessage>,
    blocks: List<ContextBlock>,
): List<ContextBlock> {
    val valid = validContextBlocks(conversation, messages, blocks)
    val occupied = mutableSetOf<ContextBlockSource>()
    val priority = valid.filter { it.formatVersion == 2 }.sortedWith(
        compareByDescending<ContextBlock> { it.sources.size }.thenByDescending { it.createdAt }.thenBy { it.id.toString() }
    ) + valid.filter { it.formatVersion == 1 }.sortedWith(
        compareBy<ContextBlock> { it.createdAt }.thenBy { it.id.toString() }
    )
    return priority.filter { block ->
        if (block.sources.any { it in occupied }) false else {
            occupied.addAll(block.sources)
            true
        }
    }.sortedBy { block -> messages.indexOfFirst { it.id == block.sources.first().messageId } }
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
        if (ids.isEmpty() || start < 0 || start + ids.size > messages.size) return@forEach
        val present = messages.subList(start, start + ids.size)
        if (present.map { it.id } != ids ||
            ids.any { it in covered }) return@forEach
        val first = present.firstOrNull { it.role != MessageRole.SYSTEM }
            ?: return@forEach
        replacements[first.id] = block
        covered.addAll(ids)
    }
    val inserted = mutableSetOf<Uuid>()
    return buildList {
        messages.forEach { message ->
            if (message.role == MessageRole.SYSTEM) add(message)
            else if (message.id in replacements) {
                val block = replacements.getValue(message.id)
                if (inserted.add(block.id)) add(block.asMessage())
            } else if (message.id !in covered) add(message)
        }
    }
}

/** Covered originals never reach input transforms; immutable summaries are restored afterward. */
internal suspend fun transformContextBlockInput(
    messages: List<UIMessage>,
    blocks: List<ContextBlock>,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): List<UIMessage> {
    val projected = projectContextBlocks(messages, blocks)
    val summaries = projected.filter { message -> blocks.any { it.id == message.id } }.associateBy { it.id }
    check(summaries.size == blocks.size) { "摘要的完整连续来源不可用，无法安全投影上下文" }
    val transformed = transform(projected)
    check(blocks.all { block -> transformed.count { it.id == block.id } == 1 }) {
        "输入转换丢失或复制了摘要，无法安全复用上下文"
    }
    return transformed.map { summaries[it.id] ?: it }
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

internal data class BlockCompactionResult(
    val prompt: LogicalPrompt,
    /** Newly created immutable snapshots, not the final provider selection. */
    val blocks: List<ContextBlock>,
    val activeBlocks: List<ContextBlock>,
)

/** Roll the prior summary and newly eligible originals into one immutable snapshot. */
internal suspend fun compactContextBlocks(
    prompt: LogicalPrompt,
    conversation: Conversation,
    messages: List<UIMessage>,
    existingBlocks: List<ContextBlock>,
    triggerTokens: Int,
    targetTokens: Int,
    maxSourceTokens: Int = 16_000,
    rebuildPrompt: (suspend (List<ContextBlock>) -> LogicalPrompt)? = null,
    summarize: suspend (String, Int) -> String,
): BlockCompactionResult {
    validateContextCompressionSettings(triggerTokens, targetTokens)?.let { error(it) }
    coroutineContext.ensureActive()
    val active = activeContextBlocks(conversation, messages, existingBlocks)
    if (triggerTokens == 0 || estimatePromptTokens(prompt) < triggerTokens) {
        return BlockCompactionResult(prompt, emptyList(), active)
    }
    require(maxSourceTokens > 0) { "压缩来源分块上限必须大于 0" }
    val sourceLimit = minOf(maxSourceTokens, 16_000)
    val protected = protectionStart(messages)
    val historyIds = prompt.history.filter { it.role != MessageRole.SYSTEM }.map { it.id }.toSet()
    val outsideIds = (prompt.system + prompt.transientContext + prompt.memorySnapshot + prompt.memoryDelta + prompt.memory +
        prompt.claudeMemoryDelta + prompt.currentToolLoop + listOfNotNull(prompt.latestUser))
        .map { it.id }.toSet()
    val bySource = active.flatMap { block -> block.sources.map { it.messageId to block } }.toMap()
    val rounds = contextRounds(messages).filter { it.start < protected }
    // A pending tool turn is a hard boundary, not a hole that a rolling snapshot may span.
    val eligible = rounds.dropWhile { !it.complete }.takeWhile { round ->
        round.complete && round.end <= protected && messages.subList(round.start, round.end).all { message ->
            if (message.role == MessageRole.SYSTEM) true else {
                val sentId = bySource[message.id]?.id ?: message.id
                sentId in historyIds && sentId !in outsideIds
            }
        }
    }
    check(eligible.isNotEmpty()) { "没有可压缩的完整历史用户轮，无法满足上下文预算" }
    var start = eligible.first().start
    val end = eligible.last().end
    // Legacy prefix snapshots may include leading SYSTEM messages before the first user turn.
    active.filter { block ->
        val first = messages.indexOfFirst { it.id == block.sources.first().messageId }
        val last = first + block.sources.size
        first < start && last > start && last <= end
    }.forEach { block -> start = messages.indexOfFirst { it.id == block.sources.first().messageId } }
    val source = messages.subList(start, end)
    val ids = source.map { it.id }.toSet()
    val merged = active.filter { block -> block.sources.any { it.messageId in ids } }
    check(merged.all { block -> block.sources.all { it.messageId in ids } }) {
        "摘要来源跨越受保护用户轮，无法安全归并"
    }
    val sources = source.map { message ->
        val node = conversation.messageNodes.firstOrNull { it.messages.getOrNull(it.selectIndex)?.id == message.id }
            ?: error("压缩来源不在当前选中分支")
        ContextBlockSource(node.id, message.id)
    }
    val prefixEnd = conversation.messageNodes.indexOfFirst { it.id == sources.last().nodeId } + 1
    val emptyBlock = ContextBlock(
        conversationId = conversation.id, assistantId = conversation.assistantId,
        sources = sources, sourceHash = contextSourceHash(source),
        prefixHash = contextSourceHash(conversation.currentMessages.take(prefixEnd)),
        summary = "", formatVersion = 2,
    )
    check(validContextBlocks(conversation, messages, listOf(emptyBlock.copy(summary = "validation"))).size == 1) {
        "压缩来源不是当前分支的连续原文"
    }
    val replaceIds = source.filter { it.role != MessageRole.SYSTEM }.map { it.id }.toSet() +
        source.filter { it.role == MessageRole.USER && !it.isSynthetic }.map { timeReminderId(it.id) } + merged.map { it.id }
    fun replace(block: ContextBlock): LogicalPrompt {
        var inserted = false
        val history = buildList {
            prompt.history.forEach { message ->
                if (message.role == MessageRole.SYSTEM || message.id !in replaceIds) add(message)
                else if (!inserted) {
                    add(block.asMessage())
                    inserted = true
                }
            }
        }
        check(inserted) { "没有可替换的完整历史来源" }
        return prompt.copy(history = history)
    }
    val mergedIds = merged.map { it.id }.toSet()
    val placeholderBlocks = active.filterNot { it.id in mergedIds } + emptyBlock
    val budgetPrompt = rebuildPrompt?.invoke(placeholderBlocks) ?: replace(emptyBlock)
    coroutineContext.ensureActive()
    check(budgetPrompt.history.count { it.id == emptyBlock.id } == 1) {
        "重建请求必须恰好保留一条摘要占位消息"
    }
    // Joining summary text to the wrapper can split an ASCII run at either boundary.
    val remainingBudget = targetTokens.toLong() - estimatePromptTokens(budgetPrompt) - 1
    check(remainingBudget > 0) { "系统、工具、记忆、近期两轮及已有摘要等必留内容已超出压缩目标" }
    val budget = remainingBudget.coerceAtMost(16_000L).toInt()
    val rendered = renderCompressionHistory(projectContextBlocks(source, merged)
        .filter { it.role != MessageRole.SYSTEM }.map { it.withoutCodemodeTrace() })
    check(rendered.isNotBlank()) { "历史消息没有可总结的内容，无法压缩上下文" }
    var calls = 0
    suspend fun summarizeChecked(input: String, outputBudget: Int): String {
        coroutineContext.ensureActive()
        check(calls < 16) { "本次上下文压缩已达到 16 次摘要调用上限" }
        check(estimateTextTokens(input) <= sourceLimit) { "摘要输入超过来源分块上限" }
        calls++
        val result = summarize(input, outputBudget)
        coroutineContext.ensureActive()
        check(result.isNotBlank()) { "压缩模型返回了空摘要，请重试或更换压缩模型" }
        return result
    }
    val chunks = splitCompressionSource(rendered, sourceLimit)
    check(chunks.size + (if (chunks.size > 1) 1 else 0) <= 16) {
        "完整历史所需摘要调用超过本次 16 次上限"
    }
    val intermediateLimit = minOf(budget, sourceLimit)
    var fragmentBudget = intermediateLimit - (chunks.size - 1)
    if (chunks.size > 1) {
        check(fragmentBudget >= chunks.size) { "中间摘要预算无法为每片预留至少一个 token" }
    }
    var summary = if (chunks.size == 1) {
        summarizeChecked(chunks.single(), budget)
    } else {
        chunks.mapIndexed { index, chunk ->
            val share = fragmentBudget / (chunks.size - index)
            summarizeChecked(chunk, share).also {
                val used = estimateTextTokens(it)
                check(used <= share) { "中间摘要超过分配预算，无法安全归并" }
                fragmentBudget -= used
            }
        }.joinToString("\n").also {
            check(estimateTextTokens(it) <= intermediateLimit) { "中间摘要超过最终摘要或来源分块预算" }
        }
    }
    // Partial summaries are local only. No fragment is persisted if merge/shrink fails.
    if (chunks.size > 1 || estimateTextTokens(summary) > budget) {
        summary = summarizeChecked(summary, budget)
    }
    check(estimateTextTokens(summary) <= budget) { "摘要收缩后仍超过分块预算，请提高压缩目标或更换压缩模型" }
    val block = emptyBlock.copy(summary = summary)
    val working = if (rebuildPrompt == null) replace(block) else {
        budgetPrompt.copy(history = budgetPrompt.history.map { message ->
            if (message.id == block.id) block.asMessage() else message
        })
    }
    check(estimatePromptTokens(working) <= targetTokens) { "新增摘要累积后仍超出完整请求预算，请提高压缩目标" }
    val finalActive = activeContextBlocks(conversation, messages, active + block)
    check(finalActive.any { it.id == block.id } && merged.none { old -> finalActive.any { it.id == old.id } }) {
        "滚动摘要未完整替换已覆盖快照"
    }
    coroutineContext.ensureActive()
    return BlockCompactionResult(working, listOf(block), finalActive)
}
