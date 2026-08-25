package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceAuditDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.redactWorkspaceUrl
import me.rerere.rikkahub.data.model.toWorkspaceRuntimeMetadata
import me.rerere.rikkahub.data.workspace.sanitizeWorkspaceAuditEvent
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsInstallResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellAccessMode
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val auditDao: WorkspaceAuditDAO,
) {
    private val rootfsOperationLocks = ConcurrentHashMap<String, Mutex>()
    private val workspaceCatalogArchiveLock = Mutex()

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withWorkspaceCatalogArchiveLock {
        withContext(Dispatchers.IO) {
            val workspaces = dao.getAll()
            for (workspace in workspaces) {
                rootfsOperationLock(workspace.id).withLock {
                    val current = dao.getById(workspace.id) ?: return@withLock
                    val dir = manager.workspaceDir(current.root)
                    if (!dir.exists()) {
                        // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                        // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                        Log.w(TAG, "Workspace directory missing, marking as broken: id=${current.id}, root=${current.root}")
                        if (current.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                            updateShellState(current.id, WorkspaceShellStatus.BROKEN.name)
                        }
                    } else {
                        rootfsInstaller.recoverPendingSwap(current.root)
                        val refreshed = dao.getById(current.id) ?: return@withLock
                        val statusName = refreshed.shellStatus
                        if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                            && !manager.hasRootfs(refreshed.root)
                        ) {
                            Log.w(TAG, "Rootfs missing, resetting shell status: id=${refreshed.id}")
                            updateShellState(refreshed.id, WorkspaceShellStatus.DISABLED.name)
                        }
                    }
                }
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun getAll(): List<WorkspaceEntity> = dao.getAll()

    fun recentAuditFlow(
        workspaceId: String,
        limit: Int = DEFAULT_AUDIT_LIMIT,
    ): Flow<List<WorkspaceAuditEvent>> = auditDao.getRecentFlow(
        workspaceId = workspaceId,
        limit = limit.coerceIn(1, MAX_AUDIT_LIMIT),
    )

    suspend fun getRecentAudit(
        workspaceId: String,
        limit: Int = DEFAULT_AUDIT_LIMIT,
    ): List<WorkspaceAuditEvent> = auditDao.getRecent(
        workspaceId = workspaceId,
        limit = limit.coerceIn(1, MAX_AUDIT_LIMIT),
    )

    /** Persist an application-side audit event without ever affecting the tool result. */
    suspend fun recordWorkspaceAudit(event: WorkspaceAuditEvent) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                auditDao.insert(sanitizeWorkspaceAuditEvent(event))
            }.onFailure {
                // Do not include command/path/details or exception text: they may contain secrets.
                Log.w(TAG, "Failed to persist workspace audit event")
            }
        }
    }

    /**
     * Global catalog lock shared by all Room/catalog mutations, restore, and archive snapshots.
     * It prevents a backup from observing a catalog list that differs from its payloads or the
     * database snapshot.
     */
    suspend fun <T> withWorkspaceCatalogArchiveLock(
        block: suspend () -> T,
    ): T = workspaceCatalogArchiveLock.withLock { block() }

    /**
     * Shared coordination point for portable backup/restore. Rootfs installation, package
     * shells and archive activation all use this per-workspace mutex, so an archive never
     * races a rootfs exchange.
     */
    suspend fun <T> withWorkspaceArchiveLock(
        id: String,
        block: suspend () -> T,
    ): T = rootfsOperationLock(id).withLock { block() }

    /** Used only after an archive has validated the entity and atomically activated its files. */
    internal suspend fun upsertRestoredWorkspace(workspace: WorkspaceEntity) {
        dao.upsert(workspace)
    }

    /** Compensation hook for a failed application-level restore. */
    internal suspend fun deleteRestoredWorkspace(id: String) {
        dao.deleteById(id)
    }

    suspend fun create(name: String): WorkspaceEntity = withWorkspaceCatalogArchiveLock {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        workspace
    }

    suspend fun rename(id: String, name: String): Boolean = withWorkspaceCatalogArchiveLock {
        val workspace = dao.getById(id) ?: return@withWorkspaceCatalogArchiveLock false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    /** Retained for legacy data compatibility; unified AI workspace tools ignore these values. */
    suspend fun setToolApproval(
        id: String,
        toolName: String,
        needsApproval: Boolean,
    ): Boolean = withWorkspaceCatalogArchiveLock {
        val workspace = dao.getById(id) ?: return@withWorkspaceCatalogArchiveLock false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
        expectedSha256: String? = null,
    ): Boolean = withWorkspaceCatalogArchiveLock {
        rootfsOperationLock(id).withLock {
            val workspace = dao.getById(id) ?: return@withLock false
            val wasReady = workspace.shellStatus == WorkspaceShellStatus.READY.name
            val hadUsableRootfs = manager.hasRootfs(workspace.root)
            try {
            updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            val result: RootfsInstallResult = runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(
                    root = workspace.root,
                    url = url,
                    onProgress = onProgress,
                    expectedSha256 = expectedSha256,
                )
            }
            val metadata = result.toWorkspaceRuntimeMetadata(
                sourceUrl = url,
                installedAt = System.currentTimeMillis(),
            )
            // Metadata is written only after the installer has validated and exchanged the
            // candidate. Any installer failure therefore leaves the previous JSON untouched.
            dao.upsert(
                workspace.copy(
                    shellStatus = WorkspaceShellStatus.READY.name,
                    runtimeMetadata = JsonInstant.encodeToString(metadata),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            // Backup cleanup is not part of the commit decision. If it fails, the new rootfs
            // and metadata are still a consistent pair and startup recovery can retry cleanup.
            withContext(Dispatchers.IO) {
                runCatching { rootfsInstaller.commitPendingInstall(workspace.root) }
                    .onFailure { error ->
                        Log.w(TAG, "Failed to remove committed rootfs backup: root=${workspace.root}", error)
                    }
            }
            true
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    rollbackPendingInstall(workspace.root)
                    restoreShellState(workspace)
                }
                throw e
            } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                rollbackPendingInstall(workspace.root)
                restoreShellState(workspace)
            }
                throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
            } catch (e: Throwable) {
                Log.e(
                    TAG,
                    "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=${redactWorkspaceUrl(url)}, error=${e::class.simpleName}",
                )
                withContext(NonCancellable) {
                    rollbackPendingInstall(workspace.root)
                    val canRestoreReady = wasReady && hadUsableRootfs &&
                        runCatching { manager.hasRootfs(workspace.root) }.getOrDefault(false)
                    updateShellState(
                        workspace.id,
                        if (canRestoreReady) {
                            WorkspaceShellStatus.READY.name
                        } else {
                            WorkspaceShellStatus.BROKEN.name
                        },
                    )
                }
                throw e
            }
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext emptyList()
            manager.ensureWorkspaceFiles(workspace.root)
            manager.listFiles(workspace.root, path, area)
        }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withStorageAreaLock(id, WorkspaceStorageArea.FILES) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.readText(workspace.root, path)
        }
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withStorageAreaLock(id, WorkspaceStorageArea.FILES) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.writeText(workspace.root, path, text, overwrite)
        }
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            when (area) {
                WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
                WorkspaceStorageArea.LINUX -> {
                    val size = manager.fileSize(workspace.root, path, area)
                    require(size <= MAX_PREVIEW_BYTES) {
                        "文件过大, 无法预览 (${size} bytes)"
                    }
                    ByteArrayOutputStream().use { out ->
                        manager.exportFile(workspace.root, path, area, out)
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
        }
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.fileSize(workspace.root, path, area)
        }
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.exportFile(workspace.root, path, area, outputStream)
        }
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = rootfsOperationLock(id).withLock {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.rootfsFileSize(workspace.root, path)
        }
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = rootfsOperationLock(id).withLock {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.exportRootfsFile(workspace.root, path, outputStream)
        }
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withStorageAreaLock(id, area) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withStorageAreaLock(id, WorkspaceStorageArea.FILES) {
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspaceFiles(workspace.root)
            manager.moveFile(workspace.root, source, target, overwrite)
        }
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = rootfsOperationLock(id).withLock {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(
            workspace.shellStatus == WorkspaceShellStatus.READY.name &&
                manager.hasRootfs(workspace.root)
        ) {
            "Workspace shell is not ready"
        }
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    /**
     * Internal files-only channel retained for limited callers and boundary tests. It is not
     * exposed as an AI tool; the model-facing workspace shell uses [executeAiPackageCommand].
     */
    internal suspend fun executeAiCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = rootfsOperationLock(id).withLock {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspaceFiles(workspace.root)
            manager.executeCommand(
                root = workspace.root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
            )
        }
    }

    /**
     * Unified AI workspace shell. PRoot's root is the current workspace's private Linux Rootfs;
     * it is not Android host-root access and has no external app mounts.
     */
    suspend fun executeAiPackageCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = rootfsOperationLock(id).withLock {
        // ChatService may have created the tool from an older workspace snapshot. Re-read the
        // entity while holding the same lock as installation before touching the rootfs.
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(
            workspace.shellStatus == WorkspaceShellStatus.READY.name &&
                manager.hasRootfs(workspace.root)
        ) {
            "Workspace shell is not ready"
        }
        runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                root = workspace.root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
            )
        }
    }

    suspend fun delete(id: String): Boolean = withWorkspaceCatalogArchiveLock {
        val workspace = rootfsOperationLock(id).withLock {
            val current = dao.getById(id) ?: return@withLock null
            dao.deleteById(id)
            withContext(Dispatchers.IO) {
                manager.deleteWorkspace(current.root)
            }
            current
        } ?: return@withWorkspaceCatalogArchiveLock false
        // Keep assistant references inside the same catalog lock. Otherwise a backup could
        // capture settings pointing at a workspace after its row and directory were deleted.
        cleanupAssistantReferences(workspace.id)
        true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun <T> withStorageAreaLock(
        id: String,
        area: WorkspaceStorageArea,
        block: suspend () -> T,
    ): T = rootfsOperationLock(id).withLock { block() }

    private fun rootfsOperationLock(id: String): Mutex =
        rootfsOperationLocks.computeIfAbsent(id) { Mutex() }

    private suspend fun rollbackPendingInstall(root: String) {
        withContext(Dispatchers.IO) {
            runCatching { rootfsInstaller.rollbackPendingInstall(root) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to roll back pending rootfs install: root=$root", error)
                }
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
        private const val DEFAULT_AUDIT_LIMIT = 50
        private const val MAX_AUDIT_LIMIT = 200
    }
}
