package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.withoutCodemodeTrace
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    // Conversation-specific path inside the assistant's current workspace.
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 永久会话编号，用于历史选择；0 仅表示尚未持久化的新会话
    val conversationNumber: Long = 0,
    @Transient
    val summarySourceAssistantId: Uuid? = null,
    @Transient
    val summarySourceConversationIds: List<Uuid> = emptyList(),
    @Transient
    val summaryContext: String = "",
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = messageNodes.toMutableList()
        messages.forEach { message ->
            val index = newNodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
            if (index < 0) {
                newNodes.add(message.toMessageNode())
            } else {
                val node = newNodes[index]
                newNodes[index] = node.copy(messages = node.messages.map {
                    if (it.id == message.id) message else it
                })
            }
        }
        return copy(messageNodes = newNodes)
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false,
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

/** Caller serializes persistence with other conversation saves, never with provider calls. */
internal suspend fun commitGenerationMessages(
    state: MutableStateFlow<Conversation>,
    request: GenerationMessageSnapshot,
    messages: List<UIMessage>,
    persist: (suspend (Conversation) -> Conversation)? = null,
): GenerationMessageSnapshot {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (persist == null) {
        while (true) {
            caller.ensureActive()
            val before = state.value
            val updated = request.applyTo(before, messages)
            if (state.compareAndSet(before, updated)) return request.copy(expected = updated)
        }
    }
    return withContext(NonCancellable) {
        while (true) {
            caller.ensureActive()
            val before = state.value
            val updated = request.applyTo(before, messages)
            val saved = persist(updated)
            if (!caller.isActive || !request.matches(state.value)) {
                persist(state.value)
                caller.ensureActive()
                error("Generation source changed during checkpoint persistence")
            }
            if (state.compareAndSet(before, saved)) return@withContext request.copy(expected = saved)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable generation commit")
    }
}

/** Request-local identity map; never infer a node from a callback's list offset. */
internal data class GenerationMessageSnapshot(
    val expected: Conversation,
    val input: List<UIMessage>,
    val responseNodeIds: List<Uuid>,
) {
    fun matches(current: Conversation): Boolean =
        current.id == expected.id && current.assistantId == expected.assistantId &&
            current.messageNodes.map { it.id to it.currentMessage.sourceIdentity() } ==
            expected.messageNodes.map { it.id to it.currentMessage.sourceIdentity() }

    fun finishReasoning(current: Conversation, generatedMessageIds: Set<Uuid>): Conversation {
        if (!matches(current)) return current
        val nodes = current.messageNodes.map { node ->
            val selected = node.currentMessage
            node.copy(messages = node.messages.map { message ->
                if (message.id == selected.id && message.id in generatedMessageIds) {
                    message.finishReasoning()
                } else message
            })
        }
        return if (nodes == current.messageNodes) current else current.copy(
            messageNodes = nodes,
            updateAt = Instant.now(),
        )
    }

    fun applyTo(current: Conversation, messages: List<UIMessage>): Conversation {
        check(matches(current)) { "Generation source changed" }
        check(messages.take(input.size).map { it.id } == input.map { it.id }) {
            "Generation input mapping changed"
        }
        val nodes = current.messageNodes.toMutableList()
        messages.forEachIndexed { index, message ->
            val existing = nodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
            if (existing >= 0) {
                val node = nodes[existing]
                nodes[existing] = node.copy(messages = node.messages.map { old ->
                    if (old.id != message.id) old else {
                        val previous = expected.getMessageNodeByMessageId(old.id)?.currentMessage
                        message.copy(
                            usage = if (message.usage == previous?.usage) old.usage else message.usage,
                            translation = if (message.translation == previous?.translation) old.translation else message.translation,
                            finishedAt = if (message.finishedAt == previous?.finishedAt) old.finishedAt else message.finishedAt,
                        )
                    }
                })
            } else {
                check(index >= input.size) { "Generation input message disappeared" }
                val destination = responseNodeIds.getOrNull(index - input.size)
                val target = nodes.indexOfFirst { it.id == destination }
                if (destination != null) {
                    check(target >= 0) { "Generation response node disappeared" }
                    val node = nodes[target]
                    nodes[target] = node.copy(messages = node.messages + message, selectIndex = node.messages.size)
                } else {
                    nodes.add(message.toMessageNode())
                }
            }
        }
        return current.copy(messageNodes = nodes)
    }

    companion object {
        fun capture(conversation: Conversation, range: ClosedRange<Int>? = null): GenerationMessageSnapshot {
            val start = range?.start ?: 0
            val end = range?.endInclusive?.plus(1) ?: conversation.messageNodes.size
            return GenerationMessageSnapshot(
                expected = conversation,
                input = conversation.currentMessages.subList(start, end).toList(),
                responseNodeIds = conversation.messageNodes.drop(end).map { it.id },
            )
        }
    }
}

private fun UIMessage.sourceIdentity(): Triple<Uuid, MessageRole, List<UIMessagePart>> =
    Triple(id, role, parts.map { it.sourceIdentity() })

private fun UIMessagePart.sourceIdentity(): UIMessagePart = when (val part = withoutCodemodeTrace()) {
    is UIMessagePart.Reasoning -> part.copy(finishedAt = null)
    is UIMessagePart.Tool -> part.copy(output = part.output.map { it.sourceIdentity() })
    else -> part
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
