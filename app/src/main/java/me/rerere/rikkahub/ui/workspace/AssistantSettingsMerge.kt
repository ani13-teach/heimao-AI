package me.rerere.rikkahub.ui.workspace

import me.rerere.rikkahub.data.model.Assistant

/**
 * Applies a UI snapshot's edits without reviving deleted assistants or dropping concurrent adds.
 * Workspace bindings always come from the latest persisted assistants.
 */
internal fun mergeAssistantSnapshot(
    current: List<Assistant>,
    desired: List<Assistant>,
): List<Assistant> {
    val currentById = current.associateBy { it.id }
    val desiredIds = desired.mapTo(mutableSetOf()) { it.id }
    val reorderedExisting = desired.mapNotNull { edited ->
        currentById[edited.id]?.let { persisted ->
            edited.copy(workspaceId = persisted.workspaceId)
        }
    }
    val concurrentAdds = current.filter { it.id !in desiredIds }
    return reorderedExisting + concurrentAdds
}
