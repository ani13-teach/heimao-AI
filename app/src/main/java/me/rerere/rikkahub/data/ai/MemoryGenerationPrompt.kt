package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudeMemoryCacheBlock
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.boundedLayeredMemoryError
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryMode

internal fun layeredMemoryFailureOutput(mode: MemoryMode, toolName: String, error: Throwable): String? =
    if (mode == MemoryMode.LAYERED && toolName in mode.toolNames) boundedLayeredMemoryError(error) else null

/** Memory policy affects new request sections only, never historical tool results. */
internal fun buildMemoryGenerationPrompt(
    memoryMode: MemoryMode,
    tools: List<Tool>,
    stableSystems: List<UIMessage>,
    conversationMessages: List<UIMessage>,
    additionalContextMessages: List<UIMessage>,
    memorySnapshot: List<AssistantMemory>,
    claudeMemoryCacheBlocks: List<ClaudeMemoryCacheBlock>?,
    memoryDelta: List<UIMessage>,
    claudeMemoryPatches: List<UIMessage>,
): LogicalPrompt {
    val ordinary = memoryMode == MemoryMode.ORDINARY
    val latestUserIndex = conversationMessages.latestRealUserIndex()
    return LogicalPrompt(
        tools = tools,
        system = stableSystems,
        history = if (latestUserIndex >= 0) {
            conversationMessages.take(latestUserIndex)
        } else {
            conversationMessages
        },
        transientContext = additionalContextMessages,
        memorySnapshot = if (ordinary && memorySnapshot.any { it.content.isNotBlank() }) {
            listOf(UIMessage.system(prompt = buildMemoryPrompt(memorySnapshot)).copy(isSynthetic = true))
        } else {
            emptyList()
        },
        claudeMemoryCacheBlocks = if (ordinary) claudeMemoryCacheBlocks else null,
        claudeMemoryDelta = if (ordinary) {
            // Committed patches already include the pending delta; adding both would duplicate it.
            claudeMemoryPatches.ifEmpty { memoryDelta }
        } else {
            emptyList()
        },
        latestUser = conversationMessages.getOrNull(latestUserIndex),
        currentToolLoop = if (latestUserIndex >= 0) {
            conversationMessages.drop(latestUserIndex + 1)
        } else {
            emptyList()
        },
    )
}
