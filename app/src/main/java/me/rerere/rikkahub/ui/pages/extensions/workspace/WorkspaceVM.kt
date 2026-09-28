package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workspace.WorkspaceBusyException
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress

enum class WorkspaceDeleteFailure { BUSY, FAILED }

internal suspend fun deleteWorkspaceOrFailure(delete: suspend () -> Boolean): WorkspaceDeleteFailure? =
    try {
        if (delete()) null else WorkspaceDeleteFailure.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (_: WorkspaceBusyException) {
        WorkspaceDeleteFailure.BUSY
    } catch (_: Exception) {
        WorkspaceDeleteFailure.FAILED
    }

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _deleteFailure = MutableStateFlow<WorkspaceDeleteFailure?>(null)
    val deleteFailure = _deleteFailure.asStateFlow()

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { repository.create(name) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            _deleteFailure.value = deleteWorkspaceOrFailure { repository.delete(workspace.id) }
        }
    }

    fun dismissDeleteFailure() {
        _deleteFailure.value = null
    }
}
