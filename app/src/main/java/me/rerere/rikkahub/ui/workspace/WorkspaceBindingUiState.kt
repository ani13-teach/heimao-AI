package me.rerere.rikkahub.ui.workspace

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingResult
import kotlin.uuid.Uuid

enum class WorkspaceBindingError {
    CONFLICT,
    ASSISTANT_NOT_FOUND,
    WORKSPACE_NOT_FOUND,
    FAILED,
}

internal fun WorkspaceBindingError.messageResource(): Int = when (this) {
    WorkspaceBindingError.CONFLICT -> R.string.workspace_binding_conflict
    WorkspaceBindingError.ASSISTANT_NOT_FOUND -> R.string.workspace_binding_assistant_not_found
    WorkspaceBindingError.WORKSPACE_NOT_FOUND -> R.string.workspace_binding_workspace_not_found
    WorkspaceBindingError.FAILED -> R.string.workspace_binding_failed
}

data class WorkspaceBindingUiState(
    val committedWorkspaceId: Uuid?,
    val isSubmitting: Boolean = false,
    val error: WorkspaceBindingError? = null,
)

internal fun WorkspaceBindingUiState.startBinding() = copy(
    isSubmitting = true,
    error = null,
)

internal fun WorkspaceBindingUiState.applyBindingResult(
    result: AssistantWorkspaceBindingResult,
): WorkspaceBindingUiState = when (result) {
    is AssistantWorkspaceBindingResult.Success -> copy(
        committedWorkspaceId = result.assistant.workspaceId,
        isSubmitting = false,
        error = null,
    )

    is AssistantWorkspaceBindingResult.Conflict -> copy(
        committedWorkspaceId = result.currentWorkspaceId,
        isSubmitting = false,
        error = WorkspaceBindingError.CONFLICT,
    )

    AssistantWorkspaceBindingResult.AssistantNotFound -> copy(
        isSubmitting = false,
        error = WorkspaceBindingError.ASSISTANT_NOT_FOUND,
    )

    AssistantWorkspaceBindingResult.WorkspaceNotFound -> copy(
        isSubmitting = false,
        error = WorkspaceBindingError.WORKSPACE_NOT_FOUND,
    )

    AssistantWorkspaceBindingResult.Failed -> applyBindingFailure()
}

internal fun WorkspaceBindingUiState.applyBindingFailure() = copy(
    isSubmitting = false,
    error = WorkspaceBindingError.FAILED,
)
