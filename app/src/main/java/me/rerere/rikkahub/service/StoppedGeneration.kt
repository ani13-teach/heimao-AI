package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Conversation

internal suspend fun finalizeGenerationState(
    mutex: Mutex,
    state: MutableStateFlow<Conversation>,
    owner: Uuid,
    transform: (Conversation) -> Conversation,
    persist: suspend (Conversation) -> Unit,
) {
    mutex.withLock {
        if (state.value.assistantId != owner) return@withLock
        // Publish under the edit lock before saving, so a failed save cannot leave tools pending.
        state.update(transform)
        persist(state.value)
    }
}

internal fun finishStoppedGeneration(
    source: Conversation,
    current: Conversation,
    cancelTool: (UIMessagePart.Tool) -> UIMessagePart.Tool,
): Conversation {
    // Streaming can append nodes, but an edit, branch switch or move invalidates this stop.
    if (source.id != current.id || source.assistantId != current.assistantId ||
        source.messageNodes.map { it.id to it.currentMessage.id } !=
        current.messageNodes.take(source.messageNodes.size).map { it.id to it.currentMessage.id }
    ) return current

    return current.copy(messageNodes = current.messageNodes.map { node ->
        val selected = node.currentMessage
        node.copy(messages = node.messages.map { message ->
            if (message.id != selected.id) message else {
                val finished = message.finishPendingTools(cancelTool).finishReasoning()
                finished.copy(parts = finished.parts.map { part ->
                    if (part is UIMessagePart.Tool) cancelTool(part) else part
                })
            }
        })
    })
}
