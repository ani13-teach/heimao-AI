package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotEntity
import me.rerere.rikkahub.data.model.redactWorkspaceText
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.WorkspaceSnapshotLimitExceeded
import me.rerere.rikkahub.data.workspace.WorkspaceSnapshotLimitUiState
import me.rerere.rikkahub.data.workspace.WorkspaceSnapshotService
import me.rerere.rikkahub.data.workspace.WorkspaceStorageStats
import me.rerere.rikkahub.data.workspace.toUiState
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

/** python / git 探测: 名称 -> 命令, 供公共环境管理卡片显示. */
private val SHARED_TOOL_COMMANDS = listOf(
    "python3" to "python3 --version",
    "git" to "git --version",
)

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val snapshotService: WorkspaceSnapshotService,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        loadWorkspace()
        refresh()
        viewModelScope.launch {
            repository.recentAuditFlow(id).collect { events ->
                _state.update { it.copy(auditEvents = events) }
            }
        }
        viewModelScope.launch {
            snapshotService.listFlow(id).collect { snapshots ->
                _state.update { it.copy(snapshots = snapshots, snapshotBusy = false) }
                refreshStorageStats()
            }
        }
        refreshStorageStats()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        refreshStorageStats()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(
                    id = workspace.id,
                    url = url,
                    onProgress = { progress ->
                        _installProgress.value = progress
                    },
                )
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = redactWorkspaceText(error.message ?: "Rootfs 安装失败")
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    /**
     * 公共 (shared) Linux 环境管理.
     *
     * 报告、占用、引用者都来自 repository, UI 不自行读取磁盘标记; 切换/恢复/工具探测同样只经 repository.
     */
    fun refreshSharedEnvironment() {
        viewModelScope.launch {
            _state.update { it.copy(sharedLoading = true) }
            runCatching { repository.sharedEnvironmentInfo() }
                .onSuccess { info ->
                    val byId = runCatching { repository.getAll().associateBy { it.id } }
                        .getOrDefault(emptyMap())
                    val referencing = info.referencedWorkspaceIds.mapNotNull { byId[it] }
                    _state.update {
                        it.copy(
                            sharedEnvironment = info.toUiState(id),
                            referencingWorkspaces = referencing,
                            sharedLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            sharedLoading = false,
                            sharedError = redactWorkspaceText(error.message ?: "加载公共环境信息失败"),
                        )
                    }
                }
        }
    }

    fun switchToSharedEnvironment() =
        runSharedEnvironmentAction("切换到公共环境失败") { repository.switchToShared(id) }

    fun switchToPrivateEnvironment() =
        runSharedEnvironmentAction("切换到私有环境失败") { repository.switchToPrivate(id) }

    fun restoreSharedRecovery() =
        runSharedEnvironmentAction("恢复公共环境失败") { repository.restoreSharedRecovery() }

    /**
     * 公共环境操作是全局的, 同一时刻只允许一个, 失败信息原样展示 (例如切回私有但私有 rootfs 不完整).
     */
    private fun runSharedEnvironmentAction(
        fallbackMessage: String,
        action: suspend () -> Boolean,
    ) {
        if (_state.value.sharedBusy) return
        viewModelScope.launch {
            _state.update { it.copy(sharedBusy = true, sharedError = null) }
            runCatching { action() }
                .onSuccess {
                    _state.update { it.copy(sharedBusy = false) }
                    loadWorkspace()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            sharedBusy = false,
                            sharedError = redactWorkspaceText(error.message ?: fallbackMessage),
                        )
                    }
                }
        }
    }

    /** 通过工作区自身的 shell 探测 python / git, 结果直接展示在管理卡片里. */
    fun checkSharedEnvironmentTools() {
        if (_state.value.toolCheckRunning) return
        viewModelScope.launch {
            _state.update { it.copy(toolCheckRunning = true, toolChecks = emptyList()) }
            val checks = SHARED_TOOL_COMMANDS.map { (name, command) ->
                runCatching { repository.executeCommand(id, command) }
                    .fold(
                        onSuccess = { result ->
                            val output = result.stdout.ifBlank { result.stderr }.trim()
                            WorkspaceToolCheck(
                                name = name,
                                output = output.ifBlank { "exit=${result.exitCode}" },
                                ok = result.exitCode == 0 && !result.timedOut,
                            )
                        },
                        onFailure = { error ->
                            WorkspaceToolCheck(
                                name = name,
                                output = redactWorkspaceText(error.message ?: "执行失败"),
                                ok = false,
                            )
                        },
                    )
            }
            _state.update { it.copy(toolCheckRunning = false, toolChecks = checks) }
        }
    }

    fun dismissSharedError() {
        _state.update { it.copy(sharedError = null) }
    }

    fun createSnapshot() {
        viewModelScope.launch {
            _state.update { it.copy(snapshotBusy = true, snapshotError = null) }
            runCatching { snapshotService.createManualSnapshot(id) }
                .onFailure { error ->
                    val limit = (error as? WorkspaceSnapshotLimitExceeded)?.status?.toUiState()
                    _state.update {
                        it.copy(
                            snapshotBusy = false,
                            snapshotLimitExceeded = limit,
                            snapshotError = if (limit == null) {
                                redactWorkspaceText(error.message ?: "创建工作区快照失败")
                            } else {
                                null
                            },
                        )
                    }
                }
                .onSuccess {
                    _state.update { it.copy(snapshotLimitExceeded = null) }
                    refreshStorageStats()
                }
        }
    }

    fun restoreSnapshot(snapshotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(snapshotBusy = true, snapshotError = null) }
            runCatching { snapshotService.restoreSnapshot(id, snapshotId) }
                .onFailure { error ->
                    val limit = (error as? WorkspaceSnapshotLimitExceeded)?.status?.toUiState()
                    _state.update {
                        it.copy(
                            snapshotBusy = false,
                            snapshotLimitExceeded = limit,
                            snapshotError = if (limit == null) {
                                redactWorkspaceText(error.message ?: "恢复工作区快照失败")
                            } else {
                                null
                            },
                        )
                    }
                }
                .onSuccess {
                    _state.update { it.copy(snapshotLimitExceeded = null) }
                    loadWorkspace()
                    refresh()
                    refreshStorageStats()
                }
        }
    }

    fun deleteSnapshot(snapshotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(snapshotBusy = true, snapshotError = null) }
            runCatching { snapshotService.deleteSnapshot(id, snapshotId) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            snapshotBusy = false,
                            snapshotError = redactWorkspaceText(
                                error.message ?: "删除工作区快照失败",
                            ),
                        )
                    }
                }
                .onSuccess {
                    _state.update { it.copy(snapshotLimitExceeded = null) }
                    refreshStorageStats()
                }
        }
    }

    fun dismissSnapshotError() {
        _state.update { it.copy(snapshotError = null) }
    }

    fun dismissSnapshotLimit() {
        _state.update { it.copy(snapshotLimitExceeded = null) }
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
            refreshStorageStats()
            refreshSharedEnvironment()
        }
    }

    private fun refreshStorageStats() {
        viewModelScope.launch {
            runCatching { snapshotService.storageStats(id) }
                .onSuccess { stats -> _state.update { it.copy(storageStats = stats) } }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val auditEvents: List<WorkspaceAuditEvent> = emptyList(),
    val snapshots: List<WorkspaceSnapshotEntity> = emptyList(),
    val storageStats: WorkspaceStorageStats? = null,
    val snapshotBusy: Boolean = false,
    val snapshotError: String? = null,
    val snapshotLimitExceeded: WorkspaceSnapshotLimitUiState? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    // 公共 (shared) Linux 环境: 本工作区是否使用、占用空间、引用者、恢复点与工具探测结果.
    val sharedEnvironment: SharedEnvironmentUiState = SharedEnvironmentUiState(),
    val sharedLoading: Boolean = true,
    val sharedBusy: Boolean = false,
    val sharedError: String? = null,
    val referencingWorkspaces: List<WorkspaceEntity> = emptyList(),
    val toolChecks: List<WorkspaceToolCheck> = emptyList(),
    val toolCheckRunning: Boolean = false,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
