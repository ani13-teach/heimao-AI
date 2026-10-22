package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.WorkspaceAuditDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceAptPlanDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.redactWorkspaceUrl
import me.rerere.rikkahub.data.model.toWorkspaceRuntimeMetadata
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingCoordinator
import me.rerere.rikkahub.data.workspace.SharedEnvironmentState
import me.rerere.rikkahub.data.workspace.WorkspaceCatalogMutationLock
import me.rerere.rikkahub.data.workspace.WorkspaceBusyException
import me.rerere.rikkahub.data.workspace.WorkspaceEnvironmentMaintenance
import me.rerere.rikkahub.data.workspace.WorkspaceEnvironmentLayout
import me.rerere.rikkahub.data.workspace.WorkspaceEnvironmentProjection
import me.rerere.rikkahub.data.workspace.WorkspaceEnvironmentRecovery
import me.rerere.rikkahub.data.workspace.WorkspaceEnvironmentSelection
import me.rerere.rikkahub.data.workspace.WorkspaceAptExecutionReport
import me.rerere.rikkahub.data.workspace.WorkspaceAptExecutor
import me.rerere.rikkahub.data.workspace.WorkspaceAptRequest
import me.rerere.rikkahub.data.workspace.WorkspaceAptSimulation
import me.rerere.rikkahub.data.workspace.WorkspaceAptSimulationResult
import me.rerere.rikkahub.data.workspace.APT_GET_GUEST_PATH
import me.rerere.rikkahub.data.workspace.parseSimulation
import me.rerere.rikkahub.data.workspace.requireExecutionArgv
import me.rerere.rikkahub.data.workspace.fingerprint
import me.rerere.rikkahub.data.workspace.sharedAptFingerprint
import me.rerere.rikkahub.data.workspace.terminalPlanState
import me.rerere.rikkahub.data.workspace.RootfsPatchOptionsProvider
import me.rerere.rikkahub.data.workspace.WorkspaceMaintenanceLease
import me.rerere.rikkahub.data.workspace.WorkspaceRuntimeCoordinator
import me.rerere.rikkahub.data.workspace.WorkspaceRuntimeGates
import me.rerere.rikkahub.data.workspace.WorkspaceRuntimeLease
import me.rerere.rikkahub.data.workspace.sanitizeWorkspaceAuditEvent
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsInstallResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessTerminationException
import me.rerere.workspace.WorkspaceShellAccessMode
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.hasUsableFullRootfs
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val assistantWorkspaceBindingCoordinator: AssistantWorkspaceBindingCoordinator,
    private val auditDao: WorkspaceAuditDAO,
    private val workspaceAptPlanDao: WorkspaceAptPlanDAO,
    private val workspaceCatalogMutationLock: WorkspaceCatalogMutationLock,
    private val workspaceRuntimeCoordinator: WorkspaceRuntimeCoordinator = WorkspaceRuntimeCoordinator(),
    private val rootfsPatchOptionsProvider: RootfsPatchOptionsProvider = RootfsPatchOptionsProvider {
        me.rerere.workspace.RootfsPatchOptions()
    },
) : WorkspaceAptExecutor {
    private val rootfsOperationLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * RUN leases that must stay held because their process could not be confirmed dead, keyed by
     * workspace id. Kept until process restart when exit cannot be established.
     */
    private val retainedProcessLeases = ConcurrentHashMap<String, MutableList<AutoCloseable>>()

    /** Owns every mutation of the public Linux environment. Constructing it only resolves paths. */
    private val environmentMaintenance by lazy {
        WorkspaceEnvironmentMaintenance(
            manager,
            workspaceRuntimeCoordinator,
            rootfsPatchOptionsProvider,
        )
    }

    /** Set when startup recovery failed even if the journal itself cannot be read. */
    private val sharedEnvironmentRecoveryFailed = AtomicBoolean(false)

    /**
     * Set while an unfinished public apt plan keeps the shared environment fail-closed. Derived from
     * the plan table at startup and after every execution, never from "the process is probably
     * gone".
     */
    private val sharedAptSessionsBlocked = AtomicBoolean(false)

    /**
     * Set while a public apt process may still be alive (`EXECUTING` / `UNKNOWN`). Even the recovery
     * restore has to wait, because a live process still holds the rootfs files it opened.
     */
    private val sharedAptProcessMayBeAlive = AtomicBoolean(false)

    /** Workspace root hosting the open public maintenance terminal, if any. */
    private val maintenanceAnchorRoot = AtomicReference<String?>(null)

    /** The one open public maintenance session, if any. */
    private val activeMaintenanceLease = AtomicReference<WorkspaceMaintenanceLease?>(null)

    private val environmentRevision = MutableStateFlow(0L)

    fun listFlow(): Flow<List<WorkspaceEntity>> =
        combine(dao.listFlow(), environmentRevision) { entities, _ -> projectEnvironments(entities) }
            .flowOn(Dispatchers.IO)

    private fun environmentChanged() { environmentRevision.update { it + 1 } }

    suspend fun checkIntegrity() {
        if (manager.portableRuntime) {
            migratePortableWorkspaces()
        } else {
            checkLegacyIntegrity()
        }
    }

    suspend fun migratePortableWorkspaces(): Boolean = withEnvironmentArchiveLease {
        check(manager.portableRuntime) { "Portable runtime is required for migration" }
        withWorkspaceCatalogArchiveLock {
            withContext(Dispatchers.IO) {
                var migrated = true
                for (workspace in dao.getAll()) {
                    try {
                        rootfsOperationLock(workspace.id).withLock {
                            check(manager.workspaceDir(workspace.root).isDirectory) {
                                "Workspace directory is missing"
                            }
                            manager.ensureWorkspace(workspace.root)
                            updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        migrated = false
                        updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                        Log.e(TAG, "Portable workspace migration failed: id=${workspace.id}", error)
                    }
                }
                if (migrated) {
                    val shared = manager.environments.sharedLinuxDir().parentFile!!.toPath()
                    if (java.nio.file.Files.exists(shared, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        java.nio.file.Files.walkFileTree(shared, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                            override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                                java.nio.file.Files.delete(path)
                                return java.nio.file.FileVisitResult.CONTINUE
                            }
                            override fun postVisitDirectory(path: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                                if (error != null) throw error
                                java.nio.file.Files.delete(path)
                                return java.nio.file.FileVisitResult.CONTINUE
                            }
                        })
                    }
                }
                environmentChanged()
                migrated
            }
        }
    }

    private suspend fun checkLegacyIntegrity() = withWorkspaceCatalogArchiveLock {
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
                    } else if (isSharedWorkspace(current)) {
                        // A shared workspace owns no private rootfs, so the stored private status
                        // must survive untouched for a later switch back. The shared rootfs itself
                        // is validated through the environment, never through this row.
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

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)?.let(::projectEnvironment)

    suspend fun getAll(): List<WorkspaceEntity> = projectEnvironments(dao.getAll())

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
    ): T = workspaceCatalogMutationLock.withLock { block() }

    /**
     * Shared coordination point for portable backup/restore. Rootfs installation, package
     * shells and archive activation all use this per-workspace mutex, so an archive never
     * races a rootfs exchange.
     */
    suspend fun <T> withWorkspaceArchiveLock(
        id: String,
        block: suspend () -> T,
    ): T {
        // Archiving a workspace that is running would capture a live process's half-written tree.
        // The archive batch holds the environment lease, so this is a check, not a wait.
        if (!workspaceRuntimeCoordinator.isIdle(id)) {
            throw WorkspaceBusyException("Workspace is running; stop its sessions before archiving")
        }
        return rootfsOperationLock(id).withLock { block() }
    }

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
        if (!manager.portableRuntime) manager.environments.initializeShared(workspace.root)
        withContext(Dispatchers.IO) { manager.ensureWorkspace(workspace.root) }
        val ready = if (manager.portableRuntime) workspace.copy(shellStatus = WorkspaceShellStatus.READY.name) else workspace
        dao.upsert(ready)
        ready
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
        check(!manager.portableRuntime) { "Linux installation is unavailable in portable workspaces" }
        val workspace = dao.getById(id) ?: return@withWorkspaceCatalogArchiveLock false
        if (isSharedWorkspace(workspace)) {
            installSharedRootfs(workspace, url, onProgress, expectedSha256)
        } else {
            installPrivateRootfs(workspace, url, onProgress, expectedSha256)
        }
    }

    /**
     * Installs into a workspace's own rootfs. The exclusive lease rejects an active terminal or
     * command: an install exchanges the very tree those sessions are running in.
     */
    private suspend fun installPrivateRootfs(
        workspace: WorkspaceEntity,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit,
        expectedSha256: String?,
    ): Boolean = workspaceRuntimeCoordinator
        .acquireExclusiveLease(workspace.id, environmentKeyForRoot(workspace.root))
        .use {
            rootfsOperationLock(workspace.id).withLock {
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

    /**
     * Installs a new rootfs for the public environment.
     *
     * Nothing here touches the calling workspace: the candidate is staged by an independent
     * installer rooted inside the environment's own scratch directory, and only the finished tree
     * is exchanged. The workspace row is deliberately left alone, so its private status survives
     * for a later switch back; readiness is projected from the environment instead.
     */
    private suspend fun installSharedRootfs(
        workspace: WorkspaceEntity,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit,
        expectedSha256: String?,
    ): Boolean {
        Log.i(TAG, "Installing shared rootfs for workspace=${workspace.id}")
        runInterruptible(Dispatchers.IO) {
            environmentMaintenance.installSharedRootfs(
                url = url,
                onProgress = onProgress,
                expectedSha256 = expectedSha256,
                requireSafeToInstall = ::requireSharedAptSessionsAllowed,
            )
        }
        sharedEnvironmentRecoveryFailed.set(false)
        environmentChanged()
        return true
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

    /** Validates an AI cwd through the workspace file API and returns its canonical UI form. */
    suspend fun requireWorkspaceDirectory(id: String, absolutePath: String): String =
        withStorageAreaLock(id, WorkspaceStorageArea.FILES) {
            withContext(Dispatchers.IO) {
                val relative = normalizeWorkspaceDirectoryPath(absolutePath)
                val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                manager.ensureWorkspaceFiles(workspace.root)
                manager.listFiles(workspace.root, relative, WorkspaceStorageArea.FILES)
                if (relative.isBlank()) "/workspace" else "/workspace/$relative"
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
    ): Long = withWorkspaceRuntimeLease(id, environmentAware = true) {
        rootfsOperationLock(id).withLock {
            withContext(Dispatchers.IO) {
                val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                manager.ensureWorkspaceFiles(workspace.root)
                manager.rootfsFileSize(workspace.root, path)
            }
        }
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withWorkspaceRuntimeLease(id, environmentAware = true) {
        rootfsOperationLock(id).withLock {
            withContext(Dispatchers.IO) {
                val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                manager.ensureWorkspaceFiles(workspace.root)
                manager.exportRootfsFile(workspace.root, path, outputStream)
            }
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
    ): WorkspaceCommandResult = executeWorkspaceCommand(
        id = id,
        command = command,
        cwd = cwd,
        timeoutMillis = timeoutMillis,
        stdin = stdin,
        accessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
        requireReady = true,
        ensureFullWorkspace = true,
    )

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
    ): WorkspaceCommandResult = executeWorkspaceCommand(
        id = id,
        command = command,
        cwd = cwd,
        timeoutMillis = timeoutMillis,
        stdin = stdin,
        accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
        requireReady = false,
        ensureFullWorkspace = false,
    )

    /**
     * AI shell: private workspaces get their development Rootfs; shared workspaces get only
     * the files-area sandbox. Never execute arbitrary AI commands against the shared Rootfs.
     */
    suspend fun executeAiPackageCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = executeWorkspaceCommand(
        id = id,
        command = command,
        cwd = cwd,
        timeoutMillis = timeoutMillis,
        stdin = stdin,
        accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
        requireReady = true,
        ensureFullWorkspace = true,
    )

    suspend fun delete(id: String): Boolean = withWorkspaceCatalogArchiveLock {
        // Deleting a workspace while a terminal runs in it would pull the tree out from under that
        // session. The exclusive lease reports Busy instead of waiting.
        acquireWorkspaceLease(id, exclusive = true, environmentAware = true).use {
            assistantWorkspaceBindingCoordinator.clearDeletedWorkspaceWhileLocked(id) {
                rootfsOperationLock(id).withLock {
                    val current = dao.getById(id) ?: return@withLock null
                    dao.deleteById(id)
                    withContext(Dispatchers.IO) {
                        manager.deleteWorkspace(current.root)
                    }
                    current
                }
            } != null
        }
    }

    /**
     * How [id] is bound to a Linux environment, for UI that must not read the on-disk marker
     * itself. Suspends because it reads the workspace row; the filesystem checks are shallow.
     */
    suspend fun environmentSelection(id: String): WorkspaceEnvironmentSelection? =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext null
            environmentSelectionOf(workspace)
        }

    /** True when [id] runs on the public shared rootfs. */
    suspend fun isShared(id: String): Boolean =
        withContext(Dispatchers.IO) { dao.getById(id)?.let(::isSharedWorkspace) ?: false }

    /**
     * Binds [id] to the public Linux environment. Its private rootfs and HOME are kept, so the
     * switch can be undone, and the stored row is left untouched.
     */
    suspend fun switchToShared(id: String): Boolean = withWorkspaceCatalogArchiveLock {
        check(!manager.portableRuntime) { "Shared Linux is unavailable in portable workspaces" }
        val workspace = dao.getById(id) ?: return@withWorkspaceCatalogArchiveLock false
        if (isSharedWorkspace(workspace)) return@withWorkspaceCatalogArchiveLock true
        workspaceRuntimeCoordinator.acquireExclusiveLease(id, WorkspaceRuntimeGates.SHARED_ENVIRONMENT).use {
            requireSharedAptSessionsAllowed()
            rootfsOperationLock(id).withLock {
                withContext(Dispatchers.IO) { manager.environments.selectShared(workspace.root) }
                environmentChanged()
            }
        }
        true
    }

    /**
     * Binds [id] back to its own rootfs.
     *
     * @throws IllegalStateException when the private rootfs is missing or incomplete, so a switch
     *   can never silently point the workspace at a broken environment.
     */
    suspend fun switchToPrivate(id: String): Boolean = withWorkspaceCatalogArchiveLock {
        val workspace = dao.getById(id) ?: return@withWorkspaceCatalogArchiveLock false
        if (!isSharedWorkspace(workspace)) return@withWorkspaceCatalogArchiveLock true
        acquireWorkspaceLease(id, exclusive = true, environmentAware = true).use {
            rootfsOperationLock(id).withLock {
                withContext(Dispatchers.IO) { manager.environments.selectPrivate(workspace.root) }
                environmentChanged()
            }
        }
        true
    }

    /**
     * Settles an interrupted shared-rootfs exchange. Runs at startup before temp directories are
     * cleaned, so the shared rootfs is coherent before anything can start in it.
     */
    suspend fun recoverSharedEnvironmentTransaction(): WorkspaceEnvironmentRecovery =
        withWorkspaceCatalogArchiveLock {
            workspaceRuntimeCoordinator
                .acquireEnvironmentExclusiveLease(WorkspaceRuntimeGates.SHARED_ENVIRONMENT)
                .use {
                    try {
                        withContext(Dispatchers.IO) { environmentMaintenance.recover() }
                            .also { sharedEnvironmentRecoveryFailed.set(false) }
                    } catch (error: Throwable) {
                        sharedEnvironmentRecoveryFailed.set(true)
                        throw error
                    }
                }
        }

    /** Rebuilds the shared rootfs from the retained recovery snapshot. */
    suspend fun restoreSharedRecovery(): Boolean = withWorkspaceCatalogArchiveLock {
        requireSharedAptRecoveryAllowed()
        workspaceRuntimeCoordinator
            .acquireEnvironmentExclusiveLease(WorkspaceRuntimeGates.SHARED_ENVIRONMENT)
            .use {
                requireSharedAptRecoveryAllowed()
                withContext(Dispatchers.IO) { environmentMaintenance.restoreRecovery() }.also { restored ->
                    if (restored) {
                        sharedEnvironmentRecoveryFailed.set(false)
                        workspaceAptPlanDao.markRecoveryRestored()
                        refreshSharedAptGate()
                    }
                    environmentChanged()
                }
            }
    }

    /**
     * Space and references of the public environment.
     *
     * [WorkspaceSharedEnvironmentInfo.occupiedBytes] walks the whole rootfs, so the report is always
     * built on the IO dispatcher and never on a UI thread.
     */
    suspend fun sharedEnvironmentInfo(): WorkspaceSharedEnvironmentInfo = withContext(Dispatchers.IO) {
        val linuxDir = environmentMaintenance.sharedLinuxDir
        val state = environmentState()
        val ready = state.installed && !state.installPending
        WorkspaceSharedEnvironmentInfo(
            ready = ready,
            occupiedBytes = if (state.installed) environmentMaintenance.rootfsSize() else null,
            referencedWorkspaceIds = dao.getAll().filter(::isSharedWorkspace).map { it.id },
            recoveryAvailable = environmentMaintenance.recoveryAvailable(),
            recoveryAt = recoveryTimestamp(),
            installPending = environmentMaintenance.installPending(),
            freeSpaceBytes = runCatching { linuxDir.usableSpace }.getOrDefault(0L),
            linuxDirectory = linuxDir.path,
        )
    }

    /** Newest entry of the recovery area, so the UI can show when the recovery point was taken. */
    private fun recoveryTimestamp(): Long? = runCatching {
        manager.environments.sharedRecoveryDir()
            .listFiles()
            ?.maxOfOrNull { it.lastModified() }
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * Acquires a RUN lease for an interactive terminal.
     *
     * Multiple terminals coexist. An install, delete, switch or archive on the same workspace (or
     * on the shared environment it uses) reports [WorkspaceBusyException] instead of waiting.
     *
     * This is a suspend function, not a pure synchronous one: it reads the workspace row so the
     * lease can cover the shared environment gate as well. It never waits for a conflicting lease.
     * Close it once the terminal session has exited; closing is idempotent.
     */
    suspend fun acquireTerminalLease(id: String): AutoCloseable =
        acquireWorkspaceLease(
            id = id,
            exclusive = false,
            environmentAware = true,
            requireRunnableSharedEnvironment = true,
        )

    /**
     * The exact shell context the manager would use for an interactive terminal in [id]: same
     * rootfs, HOME, temp areas, bind mounts and patch decision.
     */
    suspend fun prepareTerminalContext(id: String): WorkspaceShellContext =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspace(workspace.root)
            manager.createShellContext(
                root = workspace.root,
                command = "",
                cwd = "",
                accessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
            ).also { shell ->
                if (shell.patchRootfs) me.rerere.workspace.RootfsPatcher().patch(shell.linuxDir, shell.rootfsPatchOptions)
            }
        }

    /**
     * Re-derives the shared-environment apt gate from the persisted plans.
     *
     * Called at startup before any session can start and after every execution attempt. A plan table
     * that cannot be read is not proof the environment is safe, so an unreadable table fails closed
     * as well. The gate is only ever cleared by what is actually persisted, never by assuming a
     * process has already gone away.
     *
     * @return true when the shared environment is fail-closed.
     */
    suspend fun refreshSharedAptGate(): Boolean {
        val blocking = try {
            workspaceAptPlanDao.getBlockingPlans()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to read unfinished public apt plans; failing closed", error)
            sharedAptSessionsBlocked.set(true)
            sharedAptProcessMayBeAlive.set(true)
            return true
        }
        sharedAptSessionsBlocked.set(blocking.isNotEmpty())
        sharedAptProcessMayBeAlive.set(blocking.any { indicatesLiveAptProcess(it.state) })
        return sharedAptSessionsBlocked.get()
    }

    /** Unfinished apt plans are visible even when the environment is blocked. */
    suspend fun blockingSharedAptPlans(): List<WorkspaceAptPlanEntity> = workspaceAptPlanDao.getBlockingPlans()

    /** Only a human operator may attest that an unconfirmed apt process is no longer running. */
    suspend fun acknowledgeAptProcessStopped(planId: String, operationId: String) {
        require(planId.isNotBlank() && operationId.isNotBlank()) { "An apt operation is required" }
        workspaceRuntimeCoordinator.acquireEnvironmentExclusiveLease(
            WorkspaceRuntimeGates.SHARED_ENVIRONMENT,
            maintenance = true,
        ).use {
            check(workspaceAptPlanDao.markProcessStopped(planId, operationId) == 1) {
                "This public apt operation is no longer awaiting process verification"
            }
            refreshSharedAptGate()
        }
    }

    /** True while an unfinished public apt plan keeps the shared environment fail-closed. */
    val isSharedAptBlocked: Boolean get() = sharedAptSessionsBlocked.get()

    private fun requireSharedAptSessionsAllowed() {
        check(!sharedAptSessionsBlocked.get()) {
            "Shared Linux environment is blocked by an unfinished public apt plan; recover it first"
        }
    }

    private fun requireSharedAptRecoveryAllowed() {
        check(!sharedAptProcessMayBeAlive.get()) {
            "A public apt process may still be running; confirm it stopped before restoring the environment"
        }
    }

    private fun indicatesLiveAptProcess(state: String): Boolean =
        state == WorkspaceAptPlanState.EXECUTING || state == WorkspaceAptPlanState.UNKNOWN

    /**
     * Simulate a strictly structured apt request against the shared rootfs. The exclusive lease
     * makes both the input fingerprint and the resolver output a consistent read. No model-supplied
     * shell text reaches the runner, and a process with unconfirmed exit retains its lease.
     */
    override suspend fun simulateSharedApt(
        workspaceId: String,
        request: WorkspaceAptRequest,
    ): Pair<String, WorkspaceAptSimulation> {
        return rootfsOperationLock(workspaceId).withLock {
            val workspace = dao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
            require(isSharedWorkspace(workspace)) { "A public apt plan requires a shared workspace" }
            val lease = workspaceRuntimeCoordinator.acquireEnvironmentExclusiveLease(
                WorkspaceRuntimeGates.SHARED_ENVIRONMENT,
            )
            val ownership = WorkspaceCommandLeaseOwnership(
                release = lease::close,
                retain = { reason -> retainProcessLease(workspaceId, lease, reason) },
            )
            try {
                requireSharedEnvironmentRunnable()
                val rootfs = environmentMaintenance.sharedLinuxDir
                val before = withContext(Dispatchers.IO) { sharedAptFingerprint(rootfs) }
                val argv = listOf(APT_GET_GUEST_PATH) + request.command().drop(1)
                val result = runObservedWorkspaceCommand(Dispatchers.IO, ownership) {
                    manager.executeApt(
                        sharedAptContext(workspace.root, SHARED_APT_SIMULATION_TIMEOUT_MILLIS),
                        argv,
                    )
                }
                check(!result.terminationPending) { "The apt simulation process has not exited" }
                val after = withContext(Dispatchers.IO) { sharedAptFingerprint(rootfs) }
                check(before == after) { "Public environment changed while planning; retry" }
                before to request.parseSimulation(result.toAptSimulationResult())
            } finally {
                ownership.closeIfOwned()
            }
        }
    }

    /**
     * Re-validates a confirmed public apt plan and runs it under one exclusive shared-environment
     * lease (see [WorkspaceAptExecutor.executeSharedAptPlan]).
     *
     * The simulation argv and the execution argv are both built from the validated request, never
     * from model text. A fingerprint that no longer matches the confirmed plan refuses the run
     * before `EXECUTING` is persisted, so the fixed argv only ever runs when it still describes
     * exactly what the user confirmed.
     */
    override suspend fun executeSharedAptPlan(
        workspaceId: String,
        request: WorkspaceAptRequest,
        expectedBaseFingerprint: String,
        expectedPlanFingerprint: String,
        operationId: String,
        persistExecuting: suspend (operationId: String) -> Boolean,
        persistTerminal: suspend (state: String) -> Unit,
    ): WorkspaceAptExecutionReport = rootfsOperationLock(workspaceId).withLock {
        val workspace = dao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        require(isSharedWorkspace(workspace)) { "A public apt plan requires a shared workspace" }
        val executionArgv = request.executionCommand().also(::requireExecutionArgv)
        val lease = workspaceRuntimeCoordinator.acquireEnvironmentExclusiveLease(
            WorkspaceRuntimeGates.SHARED_ENVIRONMENT,
        )
        val ownership = WorkspaceCommandLeaseOwnership(
            release = lease::close,
            retain = { reason -> retainProcessLease(workspaceId, lease, reason) },
        )
        try {
            requireSharedEnvironmentRunnable()
            val rootfs = environmentMaintenance.sharedLinuxDir
            val before = withContext(Dispatchers.IO) { sharedAptFingerprint(rootfs) }
            require(before == expectedBaseFingerprint) {
                "The public environment changed since the plan was made; create a new plan"
            }
            val simulation = runObservedWorkspaceCommand(Dispatchers.IO, ownership) {
                manager.executeApt(
                    sharedAptContext(workspace.root, SHARED_APT_SIMULATION_TIMEOUT_MILLIS),
                    listOf(APT_GET_GUEST_PATH) + request.command().drop(1),
                )
            }
            if (simulation.terminationPending) {
                // The simulation process is not confirmed gone. Never continue into a real change;
                // ownership already retained the lease for this result, so it stays occupied.
                return@withLock WorkspaceAptExecutionReport.Rejected(
                    "The apt simulation process has not exited",
                )
            }
            val after = withContext(Dispatchers.IO) { sharedAptFingerprint(rootfs) }
            require(before == after) { "The public environment changed while revalidating; retry" }
            val remaining = request.parseSimulation(simulation.toAptSimulationResult())
            require(remaining.fingerprint(before) == expectedPlanFingerprint) {
                "The apt plan changed since it was confirmed; create a new plan"
            }
            check(withContext(Dispatchers.IO) { environmentMaintenance.snapshotRecovery() }) {
                "Cannot execute apt without a fresh public environment recovery point"
            }
            if (!persistExecuting(operationId)) {
                return@withLock WorkspaceAptExecutionReport.Rejected(
                    "The apt plan is no longer ready to execute",
                )
            }
            // A terminal-state write failure must never reopen the shared environment.
            sharedAptSessionsBlocked.set(true)
            sharedAptProcessMayBeAlive.set(true)
            val result = try {
                runObservedWorkspaceCommand(Dispatchers.IO, ownership) {
                    manager.executeApt(
                        sharedAptContext(workspace.root, SHARED_APT_EXECUTION_TIMEOUT_MILLIS),
                        executionArgv,
                    )
                }
            } catch (error: Throwable) {
                // A runner failure is only "unknown" when the timed-out process could not be
                // confirmed dead. Otherwise apt definitely ran and its failure needs the retained
                // recovery snapshot. Either way the state is persisted before the error escapes.
                val state = aptFailureState(ownership, error)
                withContext(NonCancellable) {
                    persistTerminal(state)
                    refreshSharedAptGate()
                }
                throw error
            }
            val report = if (result.terminationPending) {
                WorkspaceAptExecutionReport.ExitUnknown
            } else if (result.timedOut) {
                WorkspaceAptExecutionReport.Exited(-1)
            } else {
                WorkspaceAptExecutionReport.Exited(result.exitCode)
            }
            withContext(NonCancellable) {
                persistTerminal(report.terminalPlanState())
                refreshSharedAptGate()
            }
            report
        } finally {
            ownership.closeIfOwned()
        }
    }

    /** The package-mode context apt runs in: the shared rootfs, never patched, workspace-local HOME. */
    private fun sharedAptContext(anchor: String, timeoutMillis: Long): WorkspaceShellContext =
        maintenanceShellContext(anchor).copy(
            accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
            timeoutMillis = timeoutMillis,
        )

    private fun WorkspaceCommandResult.toAptSimulationResult() = WorkspaceAptSimulationResult(
        exitCode = exitCode,
        timedOut = timedOut,
        truncated = truncated,
        stdout = stdout,
        stderr = stderr,
    )

    /**
     * Opens the public-environment maintenance terminal for [id].
     *
     * The order is deliberate: the exclusive environment lease is taken first, then the recovery
     * snapshot is written, and the lease is held until the returned value is closed. No running
     * session is killed: an active terminal or command simply reports [WorkspaceBusyException].
     *
     * Close the returned lease only after the terminal process has really exited, because the
     * kernel keeps the rootfs files it opened live until then. [WorkspaceMaintenanceLease.close]
     * is idempotent, so a composition cleanup may call it safely.
     */
    suspend fun acquireMaintenanceTerminal(id: String): WorkspaceMaintenanceLease {
        requireSharedAptRecoveryAllowed()
        val acquiredLease = AtomicReference<WorkspaceRuntimeLease?>(null)
        try {
            val session = withContext(Dispatchers.IO) {
                rootfsOperationLock(id).withLock {
                    val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                    require(isSharedWorkspace(workspace)) {
                        "The public environment maintenance terminal needs a workspace on the shared environment: $id"
                    }
                    val lease = workspaceRuntimeCoordinator.acquireEnvironmentExclusiveLease(
                        environmentKey = WorkspaceRuntimeGates.SHARED_ENVIRONMENT,
                        maintenance = true,
                    )
                    acquiredLease.set(lease)
                    requireSharedAptSessionsAllowed()
                    environmentMaintenance.snapshotRecovery()
                    manager.ensureWorkspaceFiles(workspace.root)
                    WorkspaceMaintenanceLease(
                        lease = lease,
                        anchor = maintenanceAnchorRoot,
                        activeSession = activeMaintenanceLease,
                        anchorRoot = workspace.root,
                        context = maintenanceShellContext(workspace.root),
                    )
                }
            }
            // Ownership is published only after withContext has handed the value back to the
            // caller dispatcher. Cancellation before this point is caught by the outer holder.
            maintenanceAnchorRoot.set(session.root)
            activeMaintenanceLease.set(session)
            acquiredLease.set(null)
            return session
        } catch (error: Throwable) {
            acquiredLease.getAndSet(null)?.close()
            throw error
        }
    }

    private fun maintenanceShellContext(anchor: String): WorkspaceShellContext {
        val base = java.io.File(environmentMaintenance.sharedLinuxDir.parentFile, "maintenance")
        val files = java.io.File(base, "files").apply { mkdirs() }
        val home = java.io.File(base, "home").apply { mkdirs() }
        val temp = java.io.File(base, "proot").apply { mkdirs() }
        val guestTemp = java.io.File(base, "tmp").apply { mkdirs() }
        val varTemp = java.io.File(base, "var-tmp").apply { mkdirs() }
        return WorkspaceShellContext(
            root = anchor,
            command = "",
            cwd = "",
            filesDir = files,
            linuxDir = environmentMaintenance.sharedLinuxDir,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            accessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
            bindMounts = emptyList(),
            homeDir = home,
            guestTempDir = guestTemp,
            guestVarTempDir = varTemp,
            patchRootfs = false,
        )
    }

    /**
     * Context of the maintenance terminal that is already open.
     *
     * Being the only open session is not enough: the caller must be that session. Pass the lease
     * returned by [acquireMaintenanceTerminal], or call this from inside `withContext(lease) { ... }`;
     * any other caller would be changing system packages outside the terminal the user opened.
     */
    suspend fun prepareMaintenanceContext(
        id: String,
        lease: AutoCloseable? = null,
    ): WorkspaceShellContext {
        // Identity, not "some session is open": the caller must be the session that took the
        // exclusive lease. Declared as AutoCloseable so a caller that already holds the value it
        // got from acquireMaintenanceTerminal can pass it without a cast.
        val session = if (lease != null) {
            activeMaintenanceLease.get()?.takeIf { it === lease }
                ?: throw WorkspaceBusyException(
                    "The public environment maintenance terminal does not own the shared environment"
                )
        } else {
            currentCoroutineContext()[WorkspaceMaintenanceLease]
                ?: throw WorkspaceBusyException(
                    "The public environment maintenance terminal is not open in this coroutine"
                )
        }
        return withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            require(isSharedWorkspace(workspace)) {
                "The public environment maintenance context needs a workspace on the shared environment: $id"
            }
            session.context
        }
    }

    /**
     * Global gate for a portable backup batch. It is the outermost lock: while it is held no
     * terminal, command, install, switch or delete can start, so an archive never races one.
     */
    suspend fun <T> withEnvironmentArchiveLease(
        waitForIdleMillis: Long = 0L,
        block: suspend () -> T,
    ): T = workspaceRuntimeCoordinator.withEnvironmentArchiveLease(waitForIdleMillis) {
        if (!manager.portableRuntime) requireSharedAptSessionsAllowed()
        block()
    }

    private fun environmentSelectionOf(workspace: WorkspaceEntity): WorkspaceEnvironmentSelection {
        val shared = isSharedWorkspace(workspace)
        return WorkspaceEnvironmentSelection(
            workspaceId = workspace.id,
            root = workspace.root,
            shared = shared,
            hasExternalHome = runCatching { manager.environments.hasExternalHome(workspace.root) }
                .getOrDefault(false),
            homeDirectory = runCatching { manager.homeDir(workspace.root) }.getOrNull(),
            sharedRootfsInstalled = environmentMaintenance.sharedInstalled(),
            privateRootfsUsable = runCatching {
                manager.privateLinuxDir(workspace.root).hasUsableFullRootfs()
            }.getOrDefault(false),
        )
    }

    private suspend fun <T> withStorageAreaLock(
        id: String,
        area: WorkspaceStorageArea,
        block: suspend () -> T,
    ): T = acquireWorkspaceLease(
        id = id,
        exclusive = false,
        // A Linux-area operation reads the rootfs an environment switch can replace, so it also
        // takes the shared gate. A files-area operation cannot observe that swap.
        environmentAware = area == WorkspaceStorageArea.LINUX,
    ).use {
        rootfsOperationLock(id).withLock { block() }
    }

    private fun rootfsOperationLock(id: String): Mutex =
        rootfsOperationLocks.computeIfAbsent(id) { Mutex() }

    private suspend fun <T> withWorkspaceRuntimeLease(
        id: String,
        environmentAware: Boolean,
        block: suspend () -> T,
    ): T = acquireWorkspaceLease(id, exclusive = false, environmentAware = environmentAware)
        .use { block() }

    /**
     * Executes one shell command while retaining its RUN lease before leaving the IO dispatcher
     * whenever the process exit is unconfirmed. This ordering closes the cancellation window where
     * a result or exception can be discarded during dispatcher handoff.
     */
    private suspend fun executeWorkspaceCommand(
        id: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        accessMode: WorkspaceShellAccessMode,
        requireReady: Boolean,
        ensureFullWorkspace: Boolean,
    ): WorkspaceCommandResult {
        val lease = acquireWorkspaceLease(
            id = id,
            exclusive = false,
            environmentAware = true,
            requireRunnableSharedEnvironment = requireReady,
        )
        val ownership = WorkspaceCommandLeaseOwnership(
            release = lease::close,
            retain = { reason -> retainProcessLease(id, lease, reason) },
        )
        try {
            return rootfsOperationLock(id).withLock {
                // Re-read under the install/switch mutex before touching either rootfs.
                val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                if (requireReady) {
                    require(workspaceShellReady(workspace)) { "Workspace shell is not ready" }
                }
                runObservedWorkspaceCommand(Dispatchers.IO, ownership) {
                    if (ensureFullWorkspace) {
                        manager.ensureWorkspace(workspace.root)
                    } else {
                        manager.ensureWorkspaceFiles(workspace.root)
                    }
                    manager.executeCommand(
                        root = workspace.root,
                        command = command,
                        cwd = cwd,
                        timeoutMillis = timeoutMillis,
                        stdin = stdin,
                        accessMode = if (accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE) {
                            manager.aiShellAccessMode(workspace.root)
                        } else {
                            accessMode
                        },
                    )
                }
            }
        } finally {
            ownership.closeIfOwned()
        }
    }

    private fun retainProcessLease(id: String, lease: AutoCloseable, reason: String) {
        retainedProcessLeases.compute(id) { _, current ->
            (current ?: mutableListOf()).apply { add(lease) }
        }
        Log.w(TAG, "Keeping the workspace lease; the environment stays occupied: id=$id, reason=$reason")
    }

    private suspend fun acquireWorkspaceLease(
        id: String,
        exclusive: Boolean,
        environmentAware: Boolean,
        requireRunnableSharedEnvironment: Boolean = false,
    ): WorkspaceRuntimeLease {
        val operationLock = rootfsOperationLock(id)
        if (exclusive) {
            if (!operationLock.tryLock()) {
                throw WorkspaceBusyException("Workspace is busy")
            }
            return try {
                acquireWorkspaceLeaseWhileLocked(
                    id,
                    exclusive,
                    environmentAware,
                    requireRunnableSharedEnvironment,
                )
            } finally {
                operationLock.unlock()
            }
        }
        return operationLock.withLock {
            acquireWorkspaceLeaseWhileLocked(
                id,
                exclusive,
                environmentAware,
                requireRunnableSharedEnvironment,
            )
        }
    }

    private suspend fun acquireWorkspaceLeaseWhileLocked(
        id: String,
        exclusive: Boolean,
        environmentAware: Boolean,
        requireRunnableSharedEnvironment: Boolean,
    ): WorkspaceRuntimeLease {
        val workspace = dao.getById(id)
        val environmentKey = if (environmentAware) {
            workspace?.let { environmentKeyForRoot(it.root) }
        } else {
            null
        }
        if (requireRunnableSharedEnvironment && environmentKey != null) {
            requireSharedEnvironmentRunnable()
        } else if (environmentKey != null) {
            requireSharedAptSessionsAllowed()
        }
        return if (exclusive) {
            workspaceRuntimeCoordinator.acquireExclusiveLease(id, environmentKey)
        } else {
            workspaceRuntimeCoordinator.acquireRunLease(id, environmentKey)
        }
    }

    private fun requireSharedEnvironmentRunnable() {
        requireSharedAptSessionsAllowed()
        check(!sharedEnvironmentRecoveryFailed.get()) {
            "Shared Linux environment recovery failed; retry recovery before starting a session"
        }
        check(runCatching { !environmentMaintenance.installPending() }.getOrDefault(false)) {
            "Shared Linux environment recovery is still pending"
        }
        check(environmentMaintenance.sharedInstalled()) {
            "Shared Linux environment rootfs is missing or incomplete"
        }
    }

    private fun environmentKeyForRoot(root: String): String? =
        if (isSharedWorkspace(root)) WorkspaceRuntimeGates.SHARED_ENVIRONMENT else null

    /**
     * True when the workspace is bound to the public rootfs. An unreadable marker is treated as
     * private, which is exactly the layout every workspace had before the store existed.
     */
    private fun isSharedWorkspace(root: String): Boolean = !manager.portableRuntime && manager.environments.isShared(root)

    private fun isSharedWorkspace(entity: WorkspaceEntity): Boolean =
        isSharedWorkspace(entity.root)

    private fun projectEnvironment(entity: WorkspaceEntity): WorkspaceEntity =
        if (manager.portableRuntime) entity
        else WorkspaceEnvironmentProjection.project(entity, isSharedWorkspace(entity), environmentState())

    private fun projectEnvironments(entities: List<WorkspaceEntity>): List<WorkspaceEntity> {
        if (entities.none(::isSharedWorkspace)) return entities
        val state = environmentState()
        return entities.map { entity ->
            WorkspaceEnvironmentProjection.project(entity, isSharedWorkspace(entity), state)
        }
    }

    private fun environmentState(): SharedEnvironmentState {
        if (sharedEnvironmentRecoveryFailed.get()) {
            return SharedEnvironmentState(
                installed = false,
                installPending = true,
                metadataJson = WorkspaceEnvironmentLayout.EMPTY_METADATA,
            )
        }
        return runCatching { environmentMaintenance.environmentState() }.getOrElse {
            SharedEnvironmentState(
                installed = false,
                installPending = true,
                metadataJson = WorkspaceEnvironmentLayout.EMPTY_METADATA,
            )
        }
    }

    /**
     * A shared workspace is ready only after recovery has settled every journaled exchange.
     */
    private fun workspaceShellReady(workspace: WorkspaceEntity): Boolean {
        if (manager.portableRuntime) return workspace.shellStatus == WorkspaceShellStatus.READY.name
        if (!manager.hasRootfs(workspace.root)) return false
        if (!isSharedWorkspace(workspace)) {
            return workspace.shellStatus == WorkspaceShellStatus.READY.name
        }
        val state = environmentState()
        return state.installed && !state.installPending
    }

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

        /** The simulated resolver read is bounded; it never changes the rootfs. */
        private const val SHARED_APT_SIMULATION_TIMEOUT_MILLIS = 120_000L

        /** A real package change can legitimately take much longer than a simulation. */
        private const val SHARED_APT_EXECUTION_TIMEOUT_MILLIS = 15 * 60 * 1_000L
    }
}

internal fun normalizeWorkspaceDirectoryPath(path: String): String {
    val normalized = path.replace('\\', '/').trim().trimEnd('/')
    require(normalized == "/workspace" || normalized.startsWith("/workspace/")) {
        "Workspace directory must be under /workspace"
    }
    val relative = normalized.removePrefix("/workspace").trimStart('/')
    require(relative.split('/').none { it == ".." || it == "." }) {
        "Workspace directory traversal is not allowed"
    }
    require(!relative.contains('\u0000')) { "Workspace directory contains an invalid character" }
    return relative
}

/**
 * Report of the public Linux environment for the workspace settings screen.
 *
 * The first five fields are exactly what the management card binds to; the rest carries context the
 * same screen shows or will need without a second round trip.
 */
data class WorkspaceSharedEnvironmentInfo(
    /** True while the shared rootfs is installed and complete. */
    val ready: Boolean,
    /** Bytes the shared rootfs occupies, or null while there is none. */
    val occupiedBytes: Long?,
    /** Workspace ids bound to the public environment. */
    val referencedWorkspaceIds: List<String>,
    /** True while a recovery point of the shared rootfs is retained. */
    val recoveryAvailable: Boolean,
    /** When the recovery point was taken, or null while there is none. */
    val recoveryAt: Long?,
    /** True while a shared install/upgrade exchange is journaled but not finalized. */
    val installPending: Boolean,
    /** Free space of the volume that holds the shared rootfs. */
    val freeSpaceBytes: Long,
    /** Absolute path of the shared rootfs, for diagnostics. */
    val linuxDirectory: String,
)

internal suspend fun runObservedWorkspaceCommand(
    dispatcher: CoroutineDispatcher,
    ownership: WorkspaceCommandLeaseOwnership,
    execute: () -> WorkspaceCommandResult,
): WorkspaceCommandResult = runInterruptible(dispatcher) {
    try {
        execute().also(ownership::observeResult)
    } catch (error: Throwable) {
        ownership.observeFailure(error)
        throw error
    }
}

internal class WorkspaceCommandLeaseOwnership(
    private val release: () -> Unit,
    private val retain: (String) -> Unit,
) {
    private val retained = AtomicBoolean(false)
    private val unconfirmedExit = AtomicBoolean(false)

    val exitUnconfirmed: Boolean get() = unconfirmedExit.get()

    fun observeResult(result: WorkspaceCommandResult) {
        if (result.terminationPending) {
            unconfirmedExit.set(true)
            retainOnce("the timed-out process could not be confirmed gone")
        }
    }

    fun observeFailure(error: Throwable) {
        if (isUnconfirmedProcessExit(error)) {
            unconfirmedExit.set(true)
            retainOnce(error.message.orEmpty())
        }
    }

    fun closeIfOwned() {
        if (!retained.get()) release()
    }

    private fun retainOnce(reason: String) {
        if (retained.compareAndSet(false, true)) retain(reason)
    }
}

internal fun aptFailureState(ownership: WorkspaceCommandLeaseOwnership, error: Throwable): String =
    if (ownership.exitUnconfirmed || isUnconfirmedProcessExit(error)) {
        WorkspaceAptPlanState.UNKNOWN
    } else {
        WorkspaceAptPlanState.RECOVERY_REQUIRED
    }

/**
 * True when a shell failure means a timed-out process could not be confirmed dead.
 *
 * The workspace must stay occupied in that case: the process can still hold the rootfs files it
 * opened. The check walks the cause chain, because the runner may wrap the failure in order to add
 * the command it was running.
 */
private fun isUnconfirmedProcessExit(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is WorkspaceProcessTerminationException) return true
        val cause = current.cause
        if (cause === current) break
        current = cause
    }
    return false
}
