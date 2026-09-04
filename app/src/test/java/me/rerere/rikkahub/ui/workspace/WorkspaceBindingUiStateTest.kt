package me.rerere.rikkahub.ui.workspace

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceBindingUiStateTest {
    private val workspaceA = Uuid.random()
    private val workspaceB = Uuid.random()

    @Test
    fun `create bound success commits selected workspace`() {
        val state = WorkspaceBindingUiState(null)
            .startBinding()
            .applyBindingResult(
                AssistantWorkspaceBindingResult.Success(Assistant(workspaceId = workspaceA))
            )

        assertEquals(workspaceA, state.committedWorkspaceId)
        assertFalse(state.isSubmitting)
        assertNull(state.error)
    }

    @Test
    fun `create unbound success commits null`() {
        val state = WorkspaceBindingUiState(workspaceA)
            .startBinding()
            .applyBindingResult(
                AssistantWorkspaceBindingResult.Success(Assistant(workspaceId = null))
            )

        assertNull(state.committedWorkspaceId)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `cas conflict refreshes committed value and exposes retryable error`() {
        val state = WorkspaceBindingUiState(workspaceA)
            .startBinding()
            .applyBindingResult(AssistantWorkspaceBindingResult.Conflict(null))

        assertNull(state.committedWorkspaceId)
        assertEquals(WorkspaceBindingError.CONFLICT, state.error)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `missing workspace keeps committed value`() {
        val state = WorkspaceBindingUiState(workspaceA)
            .startBinding()
            .applyBindingResult(AssistantWorkspaceBindingResult.WorkspaceNotFound)

        assertEquals(workspaceA, state.committedWorkspaceId)
        assertEquals(WorkspaceBindingError.WORKSPACE_NOT_FOUND, state.error)
    }

    @Test
    fun `missing assistant keeps committed value`() {
        val state = WorkspaceBindingUiState(workspaceA)
            .startBinding()
            .applyBindingResult(AssistantWorkspaceBindingResult.AssistantNotFound)

        assertEquals(workspaceA, state.committedWorkspaceId)
        assertEquals(WorkspaceBindingError.ASSISTANT_NOT_FOUND, state.error)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `success replaces committed value with authoritative assistant value`() {
        val state = WorkspaceBindingUiState(workspaceA)
            .startBinding()
            .applyBindingResult(
                AssistantWorkspaceBindingResult.Success(Assistant(workspaceId = workspaceB))
            )

        assertEquals(workspaceB, state.committedWorkspaceId)
        assertNull(state.error)
    }
}
