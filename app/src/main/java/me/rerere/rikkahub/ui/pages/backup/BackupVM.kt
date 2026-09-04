package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.workspace.BackupPasswordSlot
import me.rerere.rikkahub.data.workspace.BackupPasswordStore
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveInspection
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveRestoreResult
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveService
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val workspaceArchiveService: WorkspaceArchiveService,
    private val backupPasswordStore: BackupPasswordStore,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val restoreWarnings = MutableStateFlow<List<String>>(emptyList())

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.transformLatest { current ->
                current.copy(
                    webDavConfig = settings.webDavConfig,
                    s3Config = settings.s3Config,
                    backupReminderConfig = settings.backupReminderConfig,
                    providers = settings.providers,
                )
            }
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup(password: CharArray) {
        webDavSync.backup(settings.value.webDavConfig, password)
        recordBackupTime()
    }

    suspend fun restore(
        item: WebDavBackupItem,
        password: CharArray?,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult {
        return webDavSync.restore(
            config = settings.value.webDavConfig,
            item = item,
            password = password,
            allowLegacy = allowLegacy,
        ).also {
            restoreWarnings.value = it.warnings
        }
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(password: CharArray): File {
        val file = workspaceArchiveService.createBackupFile(
            includeDatabase = true,
            includeFiles = true,
            password = password,
        )
        recordBackupTime()
        return file
    }

    fun inspectLocalBackup(file: File): WorkspaceArchiveInspection =
        workspaceArchiveService.inspectBackupFile(file)

    suspend fun restoreFromLocalFile(
        file: File,
        password: CharArray?,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult {
        return workspaceArchiveService.restoreBackupFile(
            file = file,
            includeDatabase = true,
            includeFiles = true,
            password = password,
            allowLegacy = allowLegacy,
        ).also {
            restoreWarnings.value = it.warnings
        }
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = settings.value.assistantId,
            providers = settings.value.providers,
            onConversation = { conversation ->
                if (conversationRepository.existsConversationById(conversation.id)) {
                    skippedExistingConversations++
                } else {
                    conversationRepository.insertConversation(conversation)
                    importedConversations++
                }
            }
        )

        val targetAssistantId = settings.value.assistantId
        settingsStore.transformLatest { current ->
            current.copy(
                providers = result.providers + current.providers,
                assistants = current.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${result.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3(password: CharArray) {
        s3Sync.backupToS3(settings.value.s3Config, password)
        recordBackupTime()
    }

    suspend fun restoreFromS3(
        item: S3BackupItem,
        password: CharArray?,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult {
        return s3Sync.restoreFromS3(
            config = settings.value.s3Config,
            item = item,
            password = password,
            allowLegacy = allowLegacy,
        ).also {
            restoreWarnings.value = it.warnings
        }
    }

    fun storedBackupPassword(slot: BackupPasswordSlot): CharArray? = backupPasswordStore.read(slot)

    fun rememberBackupPassword(slot: BackupPasswordSlot, password: CharArray) {
        backupPasswordStore.write(slot, password)
    }

    fun forgetBackupPassword(slot: BackupPasswordSlot) {
        backupPasswordStore.clear(slot)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)
