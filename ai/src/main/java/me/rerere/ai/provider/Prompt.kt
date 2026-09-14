package me.rerere.ai.provider

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage

/**
 * Provider-independent cache boundaries. Adapters may map these boundaries to their
 * protocol-specific request fields, but they must not invent additional boundaries.
 */
enum class CacheBoundary {
    SYSTEM_END,
    MEMORY_END,
    HISTORY_END,
}

/** A Claude-only top-level system text block used for append-only memory caching. */
data class ClaudeMemoryCacheBlock(
    val text: String,
)

/**
 * The stable logical shape of a chat prompt.
 *
 * [system] is the stable instruction prefix. [history] contains every message before
 * the current user turn, including historical tool calls and results. [transientContext]
 * is request-local material, [memory] is the optional memory section, and [currentToolLoop]
 * contains only messages appended after [latestUser] during the current turn.
 */
data class LogicalPrompt(
    val tools: List<Tool> = emptyList(),
    val system: List<UIMessage> = emptyList(),
    val history: List<UIMessage> = emptyList(),
    val transientContext: List<UIMessage> = emptyList(),
    val memorySnapshot: List<UIMessage> = emptyList(),
    val memoryDelta: List<UIMessage> = emptyList(),
    /**
     * Claude-only append-only Base/Patch blocks. `null` keeps the legacy memorySnapshot fallback;
     * an empty list explicitly means managed memory with no stable blocks yet.
     */
    val claudeMemoryCacheBlocks: List<ClaudeMemoryCacheBlock>? = null,
    val claudeMemoryDelta: List<UIMessage> = emptyList(),
    val latestUser: UIMessage? = null,
    val currentToolLoop: List<UIMessage> = emptyList(),
    /** Legacy alias retained for provider tests and callers that do not use snapshot state. */
    @Deprecated("Use memorySnapshot or memoryDelta")
    val memory: List<UIMessage> = emptyList(),
) {
    private val effectiveMemorySnapshot: List<UIMessage>
        get() = if (memorySnapshot.isNotEmpty()) memorySnapshot else memory

    /** Whether the stable memory section contains content that can be sent to a provider. */
    val hasMemory: Boolean
        get() = effectiveMemorySnapshot.any { it.isValidToUpload() }

    /** Flatten in cache-aware order: stable snapshot, growing history, then dynamic suffix. */
    fun toMessages(): List<UIMessage> = buildList {
        addAll(system)
        addAll(effectiveMemorySnapshot)
        addAll(history)
        addAll(transientContext)
        addAll(memoryDelta)
        latestUser?.let(::add)
        addAll(currentToolLoop)
    }

    /**
     * Flatten for providers with a separate system field. Snapshot stays directly after the
     * stable system prefix; dynamic memory is deliberately kept after history.
     */
    fun toProviderMessages(includeSystem: Boolean): List<UIMessage> = buildList {
        if (includeSystem) addAll(system)
        effectiveMemorySnapshot.forEach { add(it.asContextMessage()) }
        addAll(history)
        transientContext.forEach { add(it.asContextMessage()) }
        memoryDelta.forEach { add(it.asContextMessage()) }
        latestUser?.let(::add)
        addAll(currentToolLoop)
    }

    private fun UIMessage.asContextMessage(): UIMessage =
        if (role == MessageRole.SYSTEM) copy(role = MessageRole.USER) else this

    companion object {
        /**
         * Reconstruct the safest possible logical shape from the legacy flat API.
         * Only a leading contiguous system prefix has an unambiguous meaning as the stable
         * system section. System messages appearing later stay in history so the fallback
         * never reorders a historical timeline or guesses that a message is memory.
         */
        fun fromMessages(
            messages: List<UIMessage>,
            tools: List<Tool> = emptyList(),
        ): LogicalPrompt {
            val stableSystemCount = messages.takeWhile { it.role == MessageRole.SYSTEM }.size
            val stableSystem = messages.take(stableSystemCount)
            val timeline = messages.drop(stableSystemCount)
            val latestUserIndex = timeline.indexOfLast { it.role == MessageRole.USER }
            val latestUser = timeline.getOrNull(latestUserIndex)
            val historyEnd = if (latestUserIndex >= 0) latestUserIndex else timeline.size

            return LogicalPrompt(
                tools = tools,
                system = stableSystem,
                history = timeline.take(historyEnd),
                memory = emptyList(),
                latestUser = latestUser,
                currentToolLoop = if (latestUserIndex >= 0) {
                    timeline.drop(latestUserIndex + 1)
                } else {
                    emptyList()
                },
            )
        }
    }
}
