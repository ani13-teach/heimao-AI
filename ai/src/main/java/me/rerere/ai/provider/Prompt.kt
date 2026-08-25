package me.rerere.ai.provider

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage

/**
 * Provider-independent cache boundaries. Adapters may map these boundaries to their
 * protocol-specific request fields, but they must not invent additional boundaries.
 */
enum class CacheBoundary {
    HISTORY_END,
    MEMORY_END,
}

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
    val memory: List<UIMessage> = emptyList(),
    val latestUser: UIMessage? = null,
    val currentToolLoop: List<UIMessage> = emptyList(),
) {
    /** Whether the memory section contains content that can be sent to a provider. */
    val hasMemory: Boolean
        get() = memory.any { it.isValidToUpload() }

    /**
     * Flatten the logical prompt without changing the section order. Memory and transient
     * system messages are kept as system messages here; adapters decide how their wire format
     * represents sections that cannot be interleaved with a provider system field.
     */
    fun toMessages(): List<UIMessage> = buildList {
        addAll(system)
        addAll(history)
        addAll(transientContext)
        addAll(memory)
        latestUser?.let(::add)
        addAll(currentToolLoop)
    }

    /**
     * Flatten the prompt for a provider whose system field is separate from its message list.
     * Memory/transient system messages are represented as user context so their position after
     * history remains observable on the wire. The stable [system] section is optionally kept.
     */
    fun toProviderMessages(includeSystem: Boolean): List<UIMessage> = buildList {
        if (includeSystem) addAll(system)
        addAll(history)
        transientContext.forEach { add(it.asContextMessage()) }
        memory.forEach { add(it.asContextMessage()) }
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

/** A stable, non-sensitive key for one conversation's implicit provider cache. */
object PromptCacheKey {
    private const val PREFIX = "chat_"
    private const val APP_ID = "rikkahub"

    fun forSession(sessionId: String, appId: String = APP_ID): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((appId + sessionId).toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
