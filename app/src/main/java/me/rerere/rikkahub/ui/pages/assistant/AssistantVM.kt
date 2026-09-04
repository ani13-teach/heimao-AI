package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingCoordinator
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingResult
import me.rerere.rikkahub.ui.workspace.mergeAssistantSnapshot

sealed interface WorkspaceCatalogUiState {
    data object Loading : WorkspaceCatalogUiState
    data object Error : WorkspaceCatalogUiState
    data class Loaded(val workspaces: List<WorkspaceEntity>) : WorkspaceCatalogUiState
}

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val bindingCoordinator: AssistantWorkspaceBindingCoordinator,
) : ViewModel() {
    private val _creatingAssistant = MutableStateFlow(false)
    val creatingAssistant = _creatingAssistant.asStateFlow()

    val workspaceCatalog: StateFlow<WorkspaceCatalogUiState> = workspaceRepository.listFlow()
        .map<List<WorkspaceEntity>, WorkspaceCatalogUiState>(WorkspaceCatalogUiState::Loaded)
        .onStart { emit(WorkspaceCatalogUiState.Loading) }
        .catch { emit(WorkspaceCatalogUiState.Error) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceCatalogUiState.Loading)
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.transformLatest { current ->
                current.copy(
                    assistants = mergeAssistantSnapshot(current.assistants, settings.assistants),
                    assistantTags = settings.assistantTags,
                )
            }
        }
    }

    fun createAssistant(
        assistant: Assistant,
        onResult: (AssistantWorkspaceBindingResult) -> Unit,
    ) {
        if (_creatingAssistant.value) return
        _creatingAssistant.value = true
        viewModelScope.launch {
            try {
                val result = bindingCoordinator.createAssistant(assistant)
                onResult(result)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                onResult(AssistantWorkspaceBindingResult.Failed)
            } finally {
                _creatingAssistant.value = false
            }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            bindingCoordinator.removeAssistantAndQuiesce(assistant.id) { removed ->
                cleanupAssistantFiles(removed)
                memoryRepository.deleteMemoriesOfAssistant(removed.id.toString())
                conversationRepo.deleteConversationOfAssistant(removed.id)
            }
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            bindingCoordinator.createAssistant(copiedAssistant)
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
