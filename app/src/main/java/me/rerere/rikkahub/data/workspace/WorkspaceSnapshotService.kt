package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.WorkspaceSnapshotDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotReason
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotStatus
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.detectRootfsArchitecture
import me.rerere.workspace.hasUsableFullRootfs
import me.rerere.workspace.isRootfsArchitectureCompatible
import me.rerere.workspace.supportedRootfsAbis
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Application-side soft limits. They never delete workspace files or Linux rootfs data. */
data class WorkspaceSnapshotLimits(
    val maxPerWorkspace: Int = MAX_WORKSPACE_SNAPSHOTS_PER_WORKSPACE,
    val maxTotalBytes: Long = MAX_WORKSPACE_SNAPSHOT_TOTAL_BYTES,
)

const val MAX_WORKSPACE_SNAPSHOTS_PER_WORKSPACE = 10
const val MAX_WORKSPACE_SNAPSHOT_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L

enum class WorkspaceSnapshotLimitKind {
    PER_WORKSPACE_COUNT,
    TOTAL_BYTES,
    BOTH,
}

data class WorkspaceSnapshotLimitStatus(
    val currentWorkspaceCount: Int,
    val currentTotalBytes: Long,
    val estimatedNewBytes: Long,
    val maxPerWorkspace: Int,
    val maxTotalBytes: Long,
    val reason: WorkspaceSnapshotReason,
    val kind: WorkspaceSnapshotLimitKind,
) {
    val projectedTotalBytes: Long
        get() = runCatching { Math.addExact(currentTotalBytes, estimatedNewBytes) }
            .getOrDefault(Long.MAX_VALUE)
}

/** Structured soft-limit result. No snapshot or workspace data is deleted automatically. */
class WorkspaceSnapshotLimitExceeded(
    val status: WorkspaceSnapshotLimitStatus,
) : IllegalStateException(
    "${status.reason.name} snapshot limit exceeded: " +
        "${status.currentWorkspaceCount}/${status.maxPerWorkspace} snapshots, " +
        "${status.currentTotalBytes}/${status.maxTotalBytes} bytes",
)

data class WorkspaceSnapshotLimitUiState(
    val currentCount: Int,
    val currentBytes: Long,
    val maxCount: Int,
    val maxBytes: Long,
    val projectedBytes: Long,
    val reason: WorkspaceSnapshotReason,
    val kind: WorkspaceSnapshotLimitKind,
)

fun WorkspaceSnapshotLimitStatus.toUiState() = WorkspaceSnapshotLimitUiState(
    currentCount = currentWorkspaceCount,
    currentBytes = currentTotalBytes,
    maxCount = maxPerWorkspace,
    maxBytes = maxTotalBytes,
    projectedBytes = projectedTotalBytes,
    reason = reason,
    kind = kind,
)

/** Pure limit gate used by the service and JVM tests. */
internal fun checkWorkspaceSnapshotLimits(
    currentWorkspaceCount: Int,
    currentTotalBytes: Long,
    limits: WorkspaceSnapshotLimits,
    estimatedNewBytes: Long,
    reason: WorkspaceSnapshotReason = WorkspaceSnapshotReason.MANUAL,
) {
    require(limits.maxPerWorkspace > 0) { "Snapshot per-workspace limit must be positive" }
    require(limits.maxTotalBytes >= 0L) { "Snapshot total limit must not be negative" }
    require(currentWorkspaceCount >= 0) { "Current snapshot count must not be negative" }
    require(currentTotalBytes >= 0L) { "Current snapshot size must not be negative" }
    require(estimatedNewBytes >= 0L) { "Estimated snapshot size must not be negative" }
    val countExceeded = currentWorkspaceCount >= limits.maxPerWorkspace
    val projected = runCatching { Math.addExact(currentTotalBytes, estimatedNewBytes) }
        .getOrDefault(Long.MAX_VALUE)
    val bytesExceeded = projected > limits.maxTotalBytes
    if (countExceeded || bytesExceeded) {
        throw WorkspaceSnapshotLimitExceeded(
            WorkspaceSnapshotLimitStatus(
                currentWorkspaceCount = currentWorkspaceCount,
                currentTotalBytes = currentTotalBytes,
                estimatedNewBytes = estimatedNewBytes,
                maxPerWorkspace = limits.maxPerWorkspace,
                maxTotalBytes = limits.maxTotalBytes,
                reason = reason,
                kind = when {
                    countExceeded && bytesExceeded -> WorkspaceSnapshotLimitKind.BOTH
                    countExceeded -> WorkspaceSnapshotLimitKind.PER_WORKSPACE_COUNT
                    else -> WorkspaceSnapshotLimitKind.TOTAL_BYTES
                },
            ),
        )
    }
}

/** Disk usage shown in workspace details; all values are best-effort application-side statistics. */
data class WorkspaceStorageStats(
    val filesBytes: Long,
    val linuxBytes: Long,
    val snapshotsBytes: Long,
    val snapshotCount: Int,
    val maxSnapshotsPerWorkspace: Int,
    val maxSnapshotTotalBytes: Long,
    val shellMaxTimeoutMillis: Long = WorkspaceManager.MAX_COMMAND_TIMEOUT_MS,
) {
    val totalWorkspaceBytes: Long
        get() = filesBytes + linuxBytes
}

/** Result of a successful same-ID restore. The pre-restore checkpoint remains in the list. */
data class WorkspaceSnapshotRestoreResult(
    val restoredSnapshot: WorkspaceSnapshotEntity,
    val preRestoreSnapshot: WorkspaceSnapshotEntity,
)

/**
 * Local, single-workspace checkpoint service. Snapshot files live below filesDir/snapshots and
 * that directory is intentionally absent from workspaceBindMounts(), so no shell can mount it.
 */
class WorkspaceSnapshotService(
    private val context: Context,
    private val json: Json,
    private val workspaceRepository: WorkspaceRepository,
    private val snapshotDao: WorkspaceSnapshotDAO,
    private val limits: WorkspaceSnapshotLimits = WorkspaceSnapshotLimits(),
) {
    fun listFlow(workspaceId: String): Flow<List<WorkspaceSnapshotEntity>> =
        snapshotDao.listReadyFlow(workspaceId)

    suspend fun list(workspaceId: String): List<WorkspaceSnapshotEntity> =
        withContext(Dispatchers.IO) { snapshotDao.listReady(workspaceId) }

    /**
     * Repairs the Room half of a snapshot transaction after the pre-Koin filesystem journal has
     * rolled the directory back. This must run before workspace integrity checks; otherwise a
     * transient RESTORING/CREATING row could be mistaken for a user-visible workspace state.
     */
    suspend fun recoverPendingTransaction() = withContext(Dispatchers.IO) {
        val journalFile = WorkspaceSnapshotRecovery.journalFile(context)
        var journal = WorkspaceSnapshotRecovery.readJournal(journalFile)
        if (journal != null && journal.phase != WorkspaceSnapshotRecoveryPhase.COMMITTED) {
            WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, context.filesDir)
            journal = WorkspaceSnapshotRecovery.readJournal(journalFile)
        }
        journal?.let { state ->
            when (state.phase) {
                WorkspaceSnapshotRecoveryPhase.ROLLED_BACK -> {
                    repairRolledBackRoomState(state)
                    WorkspaceSnapshotRecovery.finishRolledBackTransaction(
                        journal = journalFile,
                        state = state,
                        filesDir = context.filesDir,
                    )
                }

                WorkspaceSnapshotRecoveryPhase.COMMITTED -> {
                    // Filesystem cleanup may have been interrupted. It is safe to retry and it
                    // never rolls back the committed workspace.
                    WorkspaceSnapshotRecovery.cleanupCommitted(
                        journal = journalFile,
                        state = state,
                        filesDir = context.filesDir,
                    )
                }

                else -> error("Snapshot recovery journal was not normalized before Room startup")
            }
        }
        recoverOrphanedSnapshotRows()
    }

    private suspend fun repairRolledBackRoomState(
        state: WorkspaceSnapshotRecoveryJournal,
    ) {
        val snapshot = snapshotDao.getById(state.snapshotId)
        when (state.operation) {
            WorkspaceSnapshotRecoveryOperation.CREATE -> {
                if (snapshot != null && snapshot.snapshotStatus() != WorkspaceSnapshotStatus.FAILED) {
                    require(snapshotDao.updateStatus(
                        state.snapshotId,
                        WorkspaceSnapshotStatus.FAILED.name,
                    ) == 1) {
                        "Unable to mark interrupted snapshot creation as failed"
                    }
                }
            }

            WorkspaceSnapshotRecoveryOperation.RESTORE -> {
                val previous = state.previousWorkspace
                    ?: error("Interrupted snapshot restore has no previous workspace row")
                // Upsert, rather than update, deliberately preserves the workspace record even
                // if the process died after the restored row was written or after a bad DAO state.
                workspaceRepository.upsertRestoredWorkspace(previous.toEntity())
                if (snapshot != null && snapshot.snapshotStatus() == WorkspaceSnapshotStatus.RESTORING) {
                    require(snapshotDao.updateStatus(
                        state.snapshotId,
                        WorkspaceSnapshotStatus.READY.name,
                    ) == 1) {
                        "Unable to restore interrupted snapshot status"
                    }
                }
            }
        }
    }

    private suspend fun recoverOrphanedSnapshotRows() {
        snapshotDao.listInProgress().forEach { snapshot ->
            when (snapshot.snapshotStatus()) {
                WorkspaceSnapshotStatus.CREATING -> {
                    require(snapshotDao.updateStatus(snapshot.id, WorkspaceSnapshotStatus.FAILED.name) == 1) {
                        "Unable to mark orphaned snapshot creation as failed"
                    }
                    cleanupOrphanedCreate(snapshot)
                }

                WorkspaceSnapshotStatus.RESTORING -> {
                    require(snapshotDao.updateStatus(snapshot.id, WorkspaceSnapshotStatus.READY.name) == 1) {
                        "Unable to restore orphaned snapshot status"
                    }
                }

                else -> Unit
            }
        }
    }

    private fun cleanupOrphanedCreate(snapshot: WorkspaceSnapshotEntity) {
        runCatching {
            deleteNoFollow(resolveSnapshotPath(snapshot.path, allowPartial = false))
            deleteNoFollow(File(snapshotDirectory(), ".snapshot-${snapshot.id}"))
            deleteNoFollow(File(snapshot.path + ".partial"))
        }.onFailure {
            // The row is already FAILED; a failed cleanup must not remove any other workspace
            // data or turn a recoverable state into a destructive rollback.
            Log.w(TAG, "Failed to clean orphaned snapshot create artifact")
        }
    }

    suspend fun createManualSnapshot(workspaceId: String): WorkspaceSnapshotEntity =
        createSnapshot(workspaceId, WorkspaceSnapshotReason.MANUAL)

    suspend fun createPreRestoreSnapshot(workspaceId: String): WorkspaceSnapshotEntity =
        createSnapshot(workspaceId, WorkspaceSnapshotReason.PRE_RESTORE)

    suspend fun createSnapshot(
        workspaceId: String,
        reason: WorkspaceSnapshotReason,
    ): WorkspaceSnapshotEntity = withContext(Dispatchers.IO) {
        withSnapshotLocks(workspaceId) {
            createSnapshotLocked(workspaceId, reason)
        }
    }

    suspend fun restoreSnapshot(
        workspaceId: String,
        snapshotId: String,
    ): WorkspaceSnapshotRestoreResult = withContext(Dispatchers.IO) {
        withSnapshotLocks(workspaceId) {
            restoreSnapshotLocked(workspaceId, snapshotId)
        }
    }

    suspend fun deleteSnapshot(workspaceId: String, snapshotId: String): Boolean =
        withContext(Dispatchers.IO) {
            withSnapshotLocks(workspaceId) {
                val snapshot = snapshotDao.getById(snapshotId) ?: return@withSnapshotLocks false
                require(snapshot.workspaceId == workspaceId) {
                    "Snapshot does not belong to this workspace"
                }
                require(snapshot.snapshotStatus() == WorkspaceSnapshotStatus.READY) {
                    "Snapshot is not ready for deletion"
                }
                val file = resolveSnapshotPath(snapshot.path, allowPartial = false)
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "Snapshot path is not a regular file"
                    }
                    require(Files.deleteIfExists(file.toPath())) {
                        "Unable to delete workspace snapshot"
                    }
                }
                snapshotDao.deleteById(snapshotId) > 0
            }
        }

    suspend fun storageStats(workspaceId: String): WorkspaceStorageStats =
        withContext(Dispatchers.IO) {
            val workspace = workspaceRepository.getById(workspaceId)
                ?: error("Workspace not found: $workspaceId")
            val snapshotCount = snapshotDao.countReady(workspaceId)
            WorkspaceStorageStats(
                filesBytes = safeDirectorySize(workspaceDirectory(workspace).resolve("files")),
                linuxBytes = safeDirectorySize(workspaceDirectory(workspace).resolve("linux")),
                snapshotsBytes = safeDirectorySize(snapshotDirectory()),
                snapshotCount = snapshotCount,
                maxSnapshotsPerWorkspace = limits.maxPerWorkspace,
                maxSnapshotTotalBytes = limits.maxTotalBytes,
            )
        }

    private suspend fun <T> withSnapshotLocks(
        workspaceId: String,
        block: suspend () -> T,
    ): T = workspaceRepository.withWorkspaceCatalogArchiveLock {
        workspaceRepository.withWorkspaceArchiveLock(workspaceId, block)
    }

    /** Caller holds both the catalog and workspace archive locks. */
    private suspend fun createSnapshotLocked(
        workspaceId: String,
        reason: WorkspaceSnapshotReason,
    ): WorkspaceSnapshotEntity {
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        val sourceDirectory = validateWorkspaceSource(workspace)
        val currentCount = snapshotDao.countReady(workspaceId)
        val currentTotalBytes = snapshotDao.totalReadySize()
        val estimatedBytes = Math.addExact(
            Math.addExact(
                safeDirectorySize(File(sourceDirectory, "files")),
                safeDirectorySize(File(sourceDirectory, "linux")),
            ),
            SNAPSHOT_OVERHEAD_BYTES,
        )
        checkWorkspaceSnapshotLimits(
            currentWorkspaceCount = currentCount,
            currentTotalBytes = currentTotalBytes,
            limits = limits,
            estimatedNewBytes = estimatedBytes,
            reason = reason,
        )

        val snapshotRoot = snapshotDirectory()
        val snapshotId = UUID.randomUUID().toString()
        val finalFile = File(snapshotRoot, "$snapshotId.zip")
        val partialFile = File(snapshotRoot, "$snapshotId.zip.partial")
        val staging = File(snapshotRoot, ".snapshot-$snapshotId")
        val recovery = File(context.filesDir, ".workspace-snapshot-recovery-$snapshotId")
        require(!Files.exists(finalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot target already exists"
        }
        require(!Files.exists(partialFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot partial target already exists"
        }
        require(staging.mkdirs() || staging.isDirectory) {
            "Unable to create snapshot staging directory"
        }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(context)
        val journal = WorkspaceSnapshotRecoveryJournal(
            operation = WorkspaceSnapshotRecoveryOperation.CREATE,
            phase = WorkspaceSnapshotRecoveryPhase.PREPARED,
            workspaceId = workspaceId,
            target = finalFile.absolutePath,
            recovery = recovery.absolutePath,
            staging = staging.absolutePath,
            snapshotId = snapshotId,
        )
        WorkspaceSnapshotRecovery.writeJournal(journalFile, journal)

        val createdAt = System.currentTimeMillis()
        val creating = WorkspaceSnapshotEntity(
            id = snapshotId,
            workspaceId = workspaceId,
            path = finalFile.absolutePath,
            manifestHash = "",
            size = 0L,
            reason = reason.name,
            status = WorkspaceSnapshotStatus.CREATING.name,
            createdAt = createdAt,
        )
        var commitRecorded = false
        var rowRecovered = false
        try {
            snapshotDao.upsert(creating)
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.STAGING_READY),
            )
            val budget = WorkspaceArchiveBudget()
            budget.checkAvailableSpace(snapshotRoot, BUFFER_SIZE.toLong())
            val before = fingerprintWorkspaceSource(sourceDirectory)
            val payloadDirectory = File(staging, "payloads")
            require(payloadDirectory.mkdirs() || payloadDirectory.isDirectory) {
                "Unable to create snapshot payload staging directory"
            }
            val tarLimits = WorkspaceArchiveTar.Limits(
                maxEntries = MAX_TAR_ENTRIES,
                maxEntryBytes = MAX_PAYLOAD_BYTES,
                maxPayloadBytes = MAX_PAYLOAD_BYTES,
            )
            val filesTar = File(payloadDirectory, "files.tar")
            val filesSummary = WorkspaceArchiveTar.create(
                sourceRoot = File(sourceDirectory, "files"),
                output = filesTar,
                limits = tarLimits,
                onEntry = budget::recordTarEntry,
                onFileBytes = budget::recordWorkspaceBytes,
                onOutputBytes = { bytes ->
                    budget.recordTarBytes(bytes)
                    budget.recordStagingBytes(bytes, snapshotRoot)
                },
            )
            val linuxTar = File(payloadDirectory, "linux.tar")
            val linuxSummary = WorkspaceArchiveTar.create(
                sourceRoot = File(sourceDirectory, "linux"),
                output = linuxTar,
                excludedPaths = setOf("tmp", "var/tmp"),
                limits = tarLimits,
                onEntry = budget::recordTarEntry,
                onFileBytes = budget::recordWorkspaceBytes,
                onOutputBytes = { bytes ->
                    budget.recordTarBytes(bytes)
                    budget.recordStagingBytes(bytes, snapshotRoot)
                },
            )
            val after = fingerprintWorkspaceSource(sourceDirectory)
            require(before == after) {
                "Workspace source changed during snapshot; no consistent snapshot was produced"
            }
            val manifest = WorkspaceSnapshotManifest(
                createdAt = createdAt,
                createdAbi = currentAbis(),
                workspace = workspace.toSnapshotWorkspace(
                    files = WorkspaceArchivePayload(
                        path = "workspaces/${workspace.root}/files.tar",
                        length = filesSummary.length,
                        sha256 = filesSummary.sha256,
                    ),
                    linux = WorkspaceArchivePayload(
                        path = "workspaces/${workspace.root}/linux.tar",
                        length = linuxSummary.length,
                        sha256 = linuxSummary.sha256,
                    ),
                ),
            )
            manifest.validate()
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val manifestHash = sha256(manifestBytes)
            writeSnapshotZip(
                partial = partialFile,
                manifestBytes = manifestBytes,
                manifest = manifest,
                filesTar = filesTar,
                linuxTar = linuxTar,
                budget = budget,
                snapshotRoot = snapshotRoot,
            )
            require(before == fingerprintWorkspaceSource(sourceDirectory)) {
                "Workspace source changed while finalizing snapshot"
            }
            budget.checkCompressedFile(partialFile)
            budget.checkAvailableSpace(snapshotRoot, partialFile.length())
            checkWorkspaceSnapshotLimits(
                currentWorkspaceCount = currentCount,
                currentTotalBytes = currentTotalBytes,
                limits = limits,
                estimatedNewBytes = partialFile.length(),
                reason = reason,
            )
            verifySnapshotArchive(
                file = partialFile,
                expectedWorkspaceId = workspace.id,
                expectedWorkspaceRoot = workspace.root,
                expectedManifestHash = manifestHash,
                expectedEntityPath = null,
            )
            atomicMove(partialFile, finalFile)
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.DIRECTORY_ACTIVATED),
            )
            verifySnapshotArchive(
                file = finalFile,
                expectedWorkspaceId = workspace.id,
                expectedWorkspaceRoot = workspace.root,
                expectedManifestHash = manifestHash,
                expectedEntityPath = finalFile.absolutePath,
            )
            require(snapshotDao.finalize(
                id = snapshotId,
                manifestHash = manifestHash,
                size = finalFile.length(),
            ) == 1) {
                "Unable to finalize workspace snapshot record"
            }
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.COMMIT_READY),
            )
            // COMMIT_READY is the durable commit point. From here all Room state and the new
            // archive are authoritative; a failure writing the cosmetic COMMITTED marker is
            // handled as cleanup/retry, never as a destructive rollback.
            commitRecorded = true
            val committedJournal = journal.copy(phase = WorkspaceSnapshotRecoveryPhase.COMMITTED)
            runCatching { WorkspaceSnapshotRecovery.writeJournal(journalFile, committedJournal) }
                .onFailure { Log.w(TAG, "Snapshot commit marker update failed; startup will retry") }
            runCatching {
                WorkspaceSnapshotRecovery.cleanupCommitted(
                    journal = journalFile,
                    state = committedJournal,
                    filesDir = context.filesDir,
                )
            }.onFailure {
                Log.w(TAG, "Committed snapshot cleanup failed")
            }
            return snapshotDao.getById(snapshotId)
                ?: error("Finalized workspace snapshot record is missing")
        } catch (error: Throwable) {
            if (!commitRecorded) {
                runCatching {
                    val updated = snapshotDao.updateStatus(snapshotId, WorkspaceSnapshotStatus.FAILED.name)
                    rowRecovered = updated == 1 || snapshotDao.getById(snapshotId) == null
                }.onFailure(error::addSuppressed)
            }
            if (!commitRecorded) {
                var cleanupSucceeded = true
                runCatching { deleteNoFollow(finalFile) }
                    .onFailure {
                        cleanupSucceeded = false
                        error.addSuppressed(it)
                    }
                runCatching { deleteNoFollow(partialFile) }
                    .onFailure {
                        cleanupSucceeded = false
                        error.addSuppressed(it)
                    }
                runCatching { deleteNoFollow(staging) }
                    .onFailure {
                        cleanupSucceeded = false
                        error.addSuppressed(it)
                    }
                if (rowRecovered && cleanupSucceeded) {
                    runCatching { WorkspaceSnapshotRecovery.clearJournal(journalFile) }
                        .onFailure(error::addSuppressed)
                }
            }
            throw error
        } finally {
            if (!commitRecorded) {
                // Do not let a cleanup failure mask the original failure. If the journal remains,
                // the next process will retry only these app-private artifacts.
                runCatching { deleteNoFollow(staging) }
                    .onFailure { Log.w(TAG, "Snapshot staging cleanup failed") }
            }
        }
    }

    /** Caller holds both locks. The selected snapshot is replaced only after a pre-restore copy. */
    private suspend fun restoreSnapshotLocked(
        workspaceId: String,
        snapshotId: String,
    ): WorkspaceSnapshotRestoreResult {
        val current = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        validateWorkspaceSource(current)
        val selected = snapshotDao.getById(snapshotId)
            ?: error("Workspace snapshot not found: $snapshotId")
        require(selected.workspaceId == workspaceId) {
            "Snapshot does not belong to this workspace"
        }
        require(selected.snapshotStatus() == WorkspaceSnapshotStatus.READY) {
            "Snapshot is not ready for restore"
        }
        val selectedFile = resolveSnapshotPath(selected.path, allowPartial = false)
        val selectedManifest = verifySnapshotArchive(
            file = selectedFile,
            expectedWorkspaceId = current.id,
            expectedWorkspaceRoot = current.root,
            expectedManifestHash = selected.manifestHash,
            expectedEntityPath = selected.path,
        ).manifest

        // This is intentionally before any directory exchange. If soft limits reject it, the
        // restore is refused and the current workspace remains untouched.
        val preRestore = createSnapshotLocked(workspaceId, WorkspaceSnapshotReason.PRE_RESTORE)
        require(snapshotDao.updateStatus(snapshotId, WorkspaceSnapshotStatus.RESTORING.name) == 1) {
            "Unable to mark workspace snapshot as restoring"
        }
        val snapshotRoot = snapshotDirectory()
        val staging = File(snapshotRoot, ".restore-${UUID.randomUUID()}")
        val recovery = File(context.filesDir, ".workspace-snapshot-recovery-${UUID.randomUUID()}")
        require(!Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot restore staging already exists"
        }
        val target = workspaceDirectory(current)
        val journalFile = WorkspaceSnapshotRecovery.journalFile(context)
        val journal = WorkspaceSnapshotRecoveryJournal(
            operation = WorkspaceSnapshotRecoveryOperation.RESTORE,
            phase = WorkspaceSnapshotRecoveryPhase.PREPARED,
            workspaceId = workspaceId,
            target = target.absolutePath,
            recovery = recovery.absolutePath,
            staging = staging.absolutePath,
            snapshotId = snapshotId,
            previousWorkspace = current.toSnapshotRecoveryState(),
        )
        WorkspaceSnapshotRecovery.writeJournal(journalFile, journal)
        var activated = false
        var commitRecorded = false
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)
        try {
            val prepared = extractSnapshotToStaging(
                file = selectedFile,
                expectedWorkspaceId = current.id,
                expectedWorkspaceRoot = current.root,
                expectedManifestHash = selected.manifestHash,
                expectedEntityPath = selected.path,
                targetStaging = staging,
            )
            require(prepared.manifest == selectedManifest) {
                "Snapshot manifest changed while preparing restore"
            }
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.STAGING_READY),
            )
            val restoredWorkspace = prepared.manifest.workspace.toEntity()
            transaction.activate()
            activated = true
            // Keep the old directory in recovery until both the workspace row and snapshot row
            // are durable. This is the restore transaction's commit boundary.
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.DIRECTORY_ACTIVATED),
            )
            workspaceRepository.upsertRestoredWorkspace(restoredWorkspace)
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.ENTITY_UPDATED),
            )
            require(snapshotDao.updateStatus(snapshotId, WorkspaceSnapshotStatus.READY.name) == 1) {
                "Unable to mark restored workspace snapshot as ready"
            }
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.SNAPSHOT_UPDATED),
            )
            WorkspaceSnapshotRecovery.writeJournal(
                journalFile,
                journal.copy(phase = WorkspaceSnapshotRecoveryPhase.COMMIT_READY),
            )
            // The recovery directory is deleted only after this durable commit marker. A cleanup
            // failure is logged and retried later; it is never a reason to destroy the new state.
            commitRecorded = true
            val committedJournal = journal.copy(phase = WorkspaceSnapshotRecoveryPhase.COMMITTED)
            runCatching { WorkspaceSnapshotRecovery.writeJournal(journalFile, committedJournal) }
                .onFailure { Log.w(TAG, "Snapshot commit marker update failed; startup will retry") }
            runCatching { transaction.commit() }
                .onFailure { Log.w(TAG, "Committed workspace snapshot recovery cleanup failed") }
            runCatching {
                WorkspaceSnapshotRecovery.cleanupCommitted(
                    journal = journalFile,
                    state = committedJournal,
                    filesDir = context.filesDir,
                )
            }.onFailure {
                Log.w(TAG, "Workspace snapshot recovery cleanup failed")
            }
            return WorkspaceSnapshotRestoreResult(
                restoredSnapshot = selected.copy(status = WorkspaceSnapshotStatus.READY.name),
                preRestoreSnapshot = preRestore,
            )
        } catch (error: Throwable) {
            if (commitRecorded) {
                // Once COMMITTED is durable, the new directory and Room rows are authoritative.
                // In particular, never turn a cleanup exception into a destructive rollback.
                Log.w(TAG, "Committed workspace snapshot operation reported an error")
                throw error
            }
            var compensationFailed = false
            var directoryRolledBack = !activated &&
                !Files.exists(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (activated || Files.exists(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                runCatching {
                    transaction.rollback()
                    directoryRolledBack = true
                }.onFailure {
                    compensationFailed = true
                    error.addSuppressed(it)
                }
            }
            // Always attempt to restore the old Room row, even if the directory compensation or
            // status update failed. The journal will let the next launch repair either side.
            runCatching { workspaceRepository.upsertRestoredWorkspace(current) }
                .onFailure {
                    compensationFailed = true
                    error.addSuppressed(it)
                }
            runCatching {
                require(snapshotDao.updateStatus(snapshotId, WorkspaceSnapshotStatus.READY.name) == 1) {
                    "Unable to restore snapshot status after failed restore"
                }
            }.onFailure {
                compensationFailed = true
                error.addSuppressed(it)
            }
            if (directoryRolledBack) {
                // The filesystem is known to contain the old workspace. Persist that fact even if
                // Room compensation failed; startup will restore the old entity and READY status
                // from the journal instead of trying to move a non-existent recovery directory.
                val rolledBack = journal.copy(
                    phase = WorkspaceSnapshotRecoveryPhase.ROLLED_BACK,
                    rollbackConfirmed = true,
                )
                runCatching {
                    WorkspaceSnapshotRecovery.writeJournal(journalFile, rolledBack)
                    if (!compensationFailed) {
                        WorkspaceSnapshotRecovery.finishRolledBackTransaction(
                            journal = journalFile,
                            state = rolledBack,
                            filesDir = context.filesDir,
                        )
                    }
                }.onFailure {
                    // Keep the journal at the last durable phase if compensation cleanup itself
                    // failed. The next launch will retry without touching the new state.
                    error.addSuppressed(it)
                }
            } else if (!compensationFailed) {
                // Defensive branch: a journal still at an activation phase is retained while the
                // recovery directory remains available for a later process to restore.
                Log.w(TAG, "Snapshot restore rollback has not completed; journal retained")
            }
            throw error
        } finally {
            if (!commitRecorded) {
                // Never unconditionally delete recovery: it may be the only copy of the old
                // workspace when an update or rollback failed. The journal owns its cleanup.
                runCatching { deleteNoFollow(staging) }
                    .onFailure { Log.w(TAG, "Snapshot restore staging cleanup failed") }
            }
        }
    }

    private fun extractSnapshotToStaging(
        file: File,
        expectedWorkspaceId: String,
        expectedWorkspaceRoot: String,
        expectedManifestHash: String,
        expectedEntityPath: String,
        targetStaging: File,
    ): PreparedSnapshot {
        val selectedPath = resolveSnapshotPath(expectedEntityPath, allowPartial = false)
        require(selectedPath == file) { "Snapshot path changed while preparing restore" }
        require(!Files.exists(targetStaging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot restore staging already exists"
        }
        val restoreRoot = File(snapshotDirectory(), ".restore-payload-${UUID.randomUUID()}")
        require(restoreRoot.mkdirs() || restoreRoot.isDirectory) {
            "Unable to create snapshot payload staging directory"
        }
        try {
            val workspaceRoot = File(restoreRoot, "workspace")
            val payloadDirectory = File(restoreRoot, "payloads")
            require(payloadDirectory.mkdirs() || payloadDirectory.isDirectory) {
                "Unable to create snapshot payload directory"
            }
            var manifest: WorkspaceSnapshotManifest? = null
            val payloadFiles = mutableMapOf<String, File>()
            val seen = HashSet<String>()
            ZipInputStream(FileInputStream(file).buffered()).use { zipIn ->
                val first = zipIn.nextEntry ?: error("Workspace snapshot is empty")
                require(!first.isDirectory) { "Workspace snapshot manifest is a directory" }
                require(WorkspaceArchivePathSafety.normalize(first.name) == WorkspaceSnapshotManifest.MANIFEST_PATH) {
                    "Workspace snapshot manifest must be the first ZIP entry"
                }
                val manifestBytes = readEntryBytes(zipIn, MAX_MANIFEST_BYTES)
                require(sha256(manifestBytes) == expectedManifestHash.lowercase(Locale.US)) {
                    "Workspace snapshot manifest hash mismatch"
                }
                val parsedManifest = json.decodeFromString<WorkspaceSnapshotManifest>(
                    manifestBytes.toString(Charsets.UTF_8),
                ).also { snapshotManifest ->
                    snapshotManifest.validate()
                    require(snapshotManifest.workspace.id == expectedWorkspaceId) {
                        "Workspace snapshot ID does not match restore target"
                    }
                    require(snapshotManifest.workspace.root == expectedWorkspaceRoot) {
                        "Workspace snapshot root does not match restore target"
                    }
                }
                manifest = parsedManifest
                seen += WorkspaceSnapshotManifest.MANIFEST_PATH
                val expected = setOf(
                    parsedManifest.workspace.files.path,
                    requireNotNull(parsedManifest.workspace.linux).path,
                )
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    require(!entry.isDirectory) { "Workspace snapshot contains a directory entry" }
                    val name = WorkspaceArchivePathSafety.normalize(entry.name)
                    require(seen.add(name)) { "Duplicate workspace snapshot entry: $name" }
                    require(name in expected) { "Unexpected workspace snapshot entry: $name" }
                    val target = WorkspaceArchivePathSafety.resolve(
                        payloadDirectory,
                        name.substringAfterLast('/'),
                    )
                    val digest = copyEntryToFile(zipIn, target, MAX_PAYLOAD_BYTES)
                    val descriptor = when {
                        name == parsedManifest.workspace.files.path -> parsedManifest.workspace.files
                        name == requireNotNull(parsedManifest.workspace.linux).path ->
                            requireNotNull(parsedManifest.workspace.linux)
                        else -> error("Unknown workspace snapshot payload: $name")
                    }
                    require(digest.length == descriptor.length) { "Snapshot payload length mismatch: $name" }
                    require(digest.sha256 == descriptor.sha256.lowercase(Locale.US)) {
                        "Snapshot payload hash mismatch: $name"
                    }
                    payloadFiles[name] = target
                    zipIn.closeEntry()
                }
                require(seen == setOf(WorkspaceSnapshotManifest.MANIFEST_PATH) + expected) {
                    "Workspace snapshot is truncated"
                }
            }
            val validManifest = manifest ?: error("Workspace snapshot manifest is missing")
            val filesRoot = File(workspaceRoot, "files")
            val linuxRoot = File(workspaceRoot, "linux")
            val filesPayload = payloadFiles[validManifest.workspace.files.path]
                ?: error("Workspace snapshot files payload is missing")
            val linuxPayload = payloadFiles[requireNotNull(validManifest.workspace.linux).path]
                ?: error("Workspace snapshot Linux payload is missing")
            WorkspaceArchiveTar.extract(
                input = filesPayload,
                targetRoot = filesRoot,
                limits = tarLimits(),
            )
            WorkspaceArchiveTar.extract(
                input = linuxPayload,
                targetRoot = linuxRoot,
                limits = tarLimits(),
            )
            recreateLinuxTempDirectories(linuxRoot)
            validatePreparedWorkspaceRoot(workspaceRoot)
            validateSnapshotAbi(validManifest, linuxRoot)
            // Move only the extracted workspace directory into the transaction. Its parent is the
            // private staging root so the caller can safely remove the payload copies afterwards.
            require(workspaceRoot.renameTo(targetStaging)) {
                "Unable to activate snapshot restore staging directory"
            }
            return PreparedSnapshot(validManifest, targetStaging)
        } finally {
            deleteNoFollow(restoreRoot)
        }
    }

    private fun verifySnapshotArchive(
        file: File,
        expectedWorkspaceId: String,
        expectedWorkspaceRoot: String,
        expectedManifestHash: String,
        expectedEntityPath: String?,
    ): SnapshotVerification {
        require(expectedManifestHash.matches(SHA256_REGEX)) {
            "Workspace snapshot manifest hash is malformed"
        }
        expectedEntityPath?.let {
            require(resolveSnapshotPath(it, allowPartial = false) == file) {
                "Workspace snapshot path is outside the private snapshots directory"
            }
        }
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead()) {
            "Workspace snapshot does not exist or is unreadable"
        }
        val verificationRoot = File(snapshotDirectory(), ".verify-${UUID.randomUUID()}")
        require(verificationRoot.mkdirs() || verificationRoot.isDirectory) {
            "Unable to create snapshot verification staging"
        }
        try {
            var manifest: WorkspaceSnapshotManifest? = null
            val payloadFiles = mutableMapOf<String, File>()
            val seen = HashSet<String>()
            ZipInputStream(FileInputStream(file).buffered()).use { zipIn ->
                val first = zipIn.nextEntry ?: error("Workspace snapshot is empty")
                require(!first.isDirectory) { "Workspace snapshot manifest is a directory" }
                require(WorkspaceArchivePathSafety.normalize(first.name) == WorkspaceSnapshotManifest.MANIFEST_PATH) {
                    "Workspace snapshot manifest must be the first ZIP entry"
                }
                val manifestBytes = readEntryBytes(zipIn, MAX_MANIFEST_BYTES)
                require(sha256(manifestBytes) == expectedManifestHash.lowercase(Locale.US)) {
                    "Workspace snapshot manifest hash mismatch"
                }
                val parsedManifest = json.decodeFromString<WorkspaceSnapshotManifest>(
                    manifestBytes.toString(Charsets.UTF_8),
                ).also {
                    it.validate()
                    require(it.workspace.id == expectedWorkspaceId) {
                        "Workspace snapshot ID does not match target"
                    }
                    require(it.workspace.root == expectedWorkspaceRoot) {
                        "Workspace snapshot root does not match target"
                    }
                }
                manifest = parsedManifest
                seen += WorkspaceSnapshotManifest.MANIFEST_PATH
                val expected = setOf(
                    parsedManifest.workspace.files.path,
                    requireNotNull(parsedManifest.workspace.linux).path,
                )
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    require(!entry.isDirectory) { "Workspace snapshot contains a directory entry" }
                    val name = WorkspaceArchivePathSafety.normalize(entry.name)
                    require(seen.add(name)) { "Duplicate workspace snapshot entry: $name" }
                    require(name in expected) { "Unexpected workspace snapshot entry: $name" }
                    val target = WorkspaceArchivePathSafety.resolve(
                        File(verificationRoot, "payloads"),
                        name.substringAfterLast('/'),
                    )
                    val digest = copyEntryToFile(zipIn, target, MAX_PAYLOAD_BYTES)
                    val descriptor = when {
                        name == parsedManifest.workspace.files.path -> parsedManifest.workspace.files
                        name == requireNotNull(parsedManifest.workspace.linux).path ->
                            requireNotNull(parsedManifest.workspace.linux)
                        else -> error("Unknown workspace snapshot payload: $name")
                    }
                    require(digest.length == descriptor.length) { "Snapshot payload length mismatch: $name" }
                    require(digest.sha256 == descriptor.sha256.lowercase(Locale.US)) {
                        "Snapshot payload hash mismatch: $name"
                    }
                    payloadFiles[name] = target
                    zipIn.closeEntry()
                }
                require(seen == setOf(WorkspaceSnapshotManifest.MANIFEST_PATH) + expected) {
                    "Workspace snapshot is truncated"
                }
            }
            val validManifest = manifest ?: error("Workspace snapshot manifest is missing")
            val extractedRoot = File(verificationRoot, "extracted")
            val filesRoot = File(extractedRoot, "files")
            val linuxRoot = File(extractedRoot, "linux")
            WorkspaceArchiveTar.extract(
                input = payloadFiles[validManifest.workspace.files.path]
                    ?: error("Workspace snapshot files payload is missing"),
                targetRoot = filesRoot,
                limits = tarLimits(),
            )
            WorkspaceArchiveTar.extract(
                input = payloadFiles[requireNotNull(validManifest.workspace.linux).path]
                    ?: error("Workspace snapshot Linux payload is missing"),
                targetRoot = linuxRoot,
                limits = tarLimits(),
            )
            recreateLinuxTempDirectories(linuxRoot)
            validatePreparedWorkspaceRoot(extractedRoot)
            validateSnapshotAbi(validManifest, linuxRoot)
            return SnapshotVerification(validManifest)
        } finally {
            deleteNoFollow(verificationRoot)
        }
    }

    private fun writeSnapshotZip(
        partial: File,
        manifestBytes: ByteArray,
        manifest: WorkspaceSnapshotManifest,
        filesTar: File,
        linuxTar: File,
        budget: WorkspaceArchiveBudget,
        snapshotRoot: File,
    ) {
        require(!Files.exists(partial.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot partial file already exists"
        }
        FileOutputStream(partial).use { output ->
            val zipOut = ZipOutputStream(output.buffered())
            try {
                budget.recordZipEntry()
                zipOut.putNextEntry(ZipEntry(WorkspaceSnapshotManifest.MANIFEST_PATH))
                budget.recordZipDecompressedBytes(manifestBytes.size.toLong())
                zipOut.write(manifestBytes)
                zipOut.closeEntry()
                writeZipPayload(
                    zipOut,
                    manifest.workspace.files.path,
                    filesTar,
                    budget,
                )
                writeZipPayload(
                    zipOut,
                    requireNotNull(manifest.workspace.linux).path,
                    linuxTar,
                    budget,
                )
                zipOut.finish()
                zipOut.flush()
                output.fd.sync()
            } finally {
                zipOut.close()
            }
        }
        budget.recordStagingBytes(partial.length(), snapshotRoot)
    }

    private fun writeZipPayload(
        zipOut: ZipOutputStream,
        name: String,
        source: File,
        budget: WorkspaceArchiveBudget,
    ) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
            "Snapshot payload is not a regular file: $name"
        }
        budget.recordZipEntry()
        zipOut.putNextEntry(ZipEntry(name))
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                budget.recordZipDecompressedBytes(read.toLong())
                zipOut.write(buffer, 0, read)
            }
        }
        zipOut.closeEntry()
    }

    private fun validateWorkspaceSource(workspace: WorkspaceEntity): File {
        require(workspace.root.isValidArchiveComponent()) {
            "Invalid workspace root: ${workspace.root}"
        }
        val filesDirectory = context.filesDir
        val workspacesDirectory = File(filesDirectory, "workspaces")
        require(!Files.isSymbolicLink(filesDirectory.toPath())) {
            "Application files directory is a symbolic link"
        }
        require(!Files.isSymbolicLink(workspacesDirectory.toPath())) {
            "Workspace directory catalog is a symbolic link"
        }
        val filesRoot = filesDirectory.canonicalFile
        val workspacesRoot = workspacesDirectory.canonicalFile
        require(workspacesRoot.parentFile == filesRoot) {
            "Workspace directory catalog escapes the app-private files directory"
        }
        val root = File(workspacesDirectory, workspace.root)
        require(root.canonicalFile.parentFile == workspacesRoot) {
            "Workspace directory escapes the app-private workspace root"
        }
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Workspace directory is missing: ${workspace.id}"
        }
        require(File(root, "files").isDirectory && !Files.isSymbolicLink(File(root, "files").toPath())) {
            "Workspace files directory is missing: ${workspace.id}"
        }
        require(!Files.isSymbolicLink(File(root, "linux").toPath())) {
            "Workspace Linux directory is a symbolic link: ${workspace.id}"
        }
        runCatching { WorkspaceShellStatus.valueOf(workspace.shellStatus) }
            .getOrElse { error("Unknown workspace shell status: ${workspace.shellStatus}") }
        return root
    }

    private fun workspaceDirectory(workspace: WorkspaceEntity): File =
        File(File(context.filesDir, "workspaces"), workspace.root)

    private fun snapshotDirectory(): File {
        require(!Files.isSymbolicLink(context.filesDir.toPath())) {
            "Application files directory is a symbolic link"
        }
        val directory = File(context.filesDir, FileFolders.SNAPSHOTS)
        require(!Files.isSymbolicLink(directory.toPath())) {
            "Workspace snapshots directory is a symbolic link"
        }
        require(directory.mkdirs() || directory.isDirectory) {
            "Unable to create workspace snapshots directory"
        }
        return directory
    }

    private fun resolveSnapshotPath(path: String, allowPartial: Boolean): File {
        val root = snapshotDirectory().canonicalFile
        val candidate = File(path)
        require(candidate.isAbsolute) { "Workspace snapshot path must be absolute" }
        val canonical = candidate.canonicalFile
        require(canonical.parentFile == root) {
            "Workspace snapshot path is outside the app-private snapshots directory"
        }
        require(!Files.isSymbolicLink(candidate.toPath())) {
            "Workspace snapshot path is a symbolic link"
        }
        val suffix = if (allowPartial) ".zip.partial" else ".zip"
        require(candidate.name.matches(Regex("[A-Za-z0-9._-]{1,255}\\Q$suffix\\E"))) {
            "Workspace snapshot file name is invalid"
        }
        return candidate
    }

    private fun validatePreparedWorkspaceRoot(root: File) {
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Prepared workspace root is invalid"
        }
        val children = root.listFiles().orEmpty().map { child ->
            require(!Files.isSymbolicLink(child.toPath())) {
                "Prepared workspace root contains a symbolic link: ${child.name}"
            }
            child.name
        }.toSet()
        require(children == setOf("files", "linux")) {
            "Prepared workspace root contains unexpected entries"
        }
        require(File(root, "files").isDirectory) { "Prepared workspace files directory is missing" }
        require(File(root, "linux").isDirectory) { "Prepared workspace Linux directory is missing" }
    }

    private fun recreateLinuxTempDirectories(linuxRoot: File) {
        val tmp = File(linuxRoot, "tmp")
        val varTmp = File(linuxRoot, "var/tmp")
        require(tmp.mkdirs() || tmp.isDirectory) { "Unable to recreate Linux tmp" }
        require(varTmp.mkdirs() || varTmp.isDirectory) { "Unable to recreate Linux var/tmp" }
        WorkspaceArchiveTar.applyMode(tmp, 0x1c0)
        WorkspaceArchiveTar.applyMode(varTmp, 0x1c0)
    }

    private fun validateSnapshotAbi(
        manifest: WorkspaceSnapshotManifest,
        linuxRoot: File,
    ) {
        val archiveAbis = manifest.createdAbi.mapNotNull(::canonicalAbi).toSet()
        val deviceAbis = supportedRootfsAbis().mapNotNull(::canonicalAbi).toSet()
        require(
            archiveAbis.isEmpty() || deviceAbis.isEmpty() || archiveAbis.intersect(deviceAbis).isNotEmpty()
        ) {
            "Workspace snapshot ABI is incompatible with this device"
        }
        val expected = manifest.workspace.runtimeMetadata.expectedArchitecture
            ?: manifest.workspace.runtimeMetadata.detectedArchitecture
            ?: manifest.createdAbi.firstOrNull()?.let(::canonicalAbi)
        val detected = detectRootfsArchitecture(linuxRoot)
        require(isRootfsArchitectureCompatible(expected, detected, supportedRootfsAbis())) {
            "Workspace snapshot Linux architecture is incompatible"
        }
        if (manifest.workspace.shellStatus == WorkspaceShellStatus.READY.name) {
            require(linuxRoot.hasUsableFullRootfs()) {
                "Workspace snapshot marked READY but Linux rootfs is incomplete"
            }
        }
    }

    private fun currentAbis(): List<String> = runCatching {
        Build.SUPPORTED_ABIS.toList().filter { it.isNotBlank() }
    }.getOrElse { supportedRootfsAbis() }

    private fun canonicalAbi(value: String): String? = when (value.lowercase(Locale.US)) {
        "arm64-v8a", "aarch64", "arm64" -> "arm64"
        "armeabi-v7a", "arm", "armv7", "armv7l", "armhf" -> "arm"
        "x86_64", "amd64", "x86-64" -> "x86_64"
        "x86", "i386", "i686" -> "x86"
        "riscv64" -> "riscv64"
        else -> null
    }

    private fun tarLimits() = WorkspaceArchiveTar.Limits(
        maxEntries = MAX_TAR_ENTRIES,
        maxEntryBytes = MAX_PAYLOAD_BYTES,
        maxPayloadBytes = MAX_PAYLOAD_BYTES,
    )

    private fun fingerprintWorkspaceSource(root: File): WorkspaceSourceFingerprint =
        WorkspaceSourceFingerprint(
            entries = fingerprintTree(File(root, "files"), "files") +
                fingerprintTree(File(root, "linux"), "linux"),
        )

    private fun fingerprintTree(root: File, label: String): List<WorkspaceSourceFingerprintEntry> {
        val result = mutableListOf<WorkspaceSourceFingerprintEntry>()
        fun visit(path: File, relative: String) {
            val nioPath = path.toPath()
            if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) {
                result += WorkspaceSourceFingerprintEntry(relativePath = label + relative, type = "missing")
                return
            }
            val attributes = Files.readAttributes(
                nioPath,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val type = when {
                attributes.isDirectory -> "directory"
                attributes.isRegularFile -> "file"
                attributes.isSymbolicLink -> "symlink"
                else -> "other"
            }
            val linkTarget = if (attributes.isSymbolicLink) {
                Files.readSymbolicLink(nioPath).toString()
            } else null
            val contentHash = if (attributes.isRegularFile) sha256(path) else null
            val mode = runCatching {
                (Files.getAttribute(nioPath, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number)
                    .toInt() and 0x1ff
            }.getOrNull()
            result += WorkspaceSourceFingerprintEntry(
                relativePath = if (relative.isEmpty()) label else "$label/$relative",
                type = type,
                size = attributes.size(),
                modifiedAt = attributes.lastModifiedTime().toMillis(),
                linkTarget = linkTarget,
                fileKey = attributes.fileKey()?.toString(),
                mode = mode,
                contentSha256 = contentHash,
            )
            if (attributes.isDirectory) {
                val children = ArrayList<File>()
                Files.list(nioPath).use { stream -> stream.forEach { children += it.toFile() } }
                children.sortBy { it.name }
                children.forEach { child ->
                    val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                    visit(child, childRelative)
                }
            }
        }
        visit(root, "")
        return result
    }

    private fun safeDirectorySize(root: File): Long {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        require(!Files.isSymbolicLink(path)) { "Workspace storage path is a symbolic link: ${root.path}" }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return root.length()
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace storage path is not a directory: ${root.path}"
        }
        var total = 0L
        root.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) return@forEach
            total = Math.addExact(total, safeDirectorySize(child))
        }
        return total
    }

    private fun readEntryBytes(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            size = Math.addExact(size, read.toLong())
            require(size <= maxBytes) { "Workspace snapshot manifest is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun copyEntryToFile(
        input: InputStream,
        target: File,
        maxBytes: Long,
    ): PayloadDigest {
        target.parentFile?.let { WorkspaceArchivePathSafety.ensureDirectory(it) }
        require(!Files.isSymbolicLink(target.toPath())) { "Workspace snapshot target is a symbolic link" }
        require(!target.exists()) { "Duplicate workspace snapshot payload target" }
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                length = Math.addExact(length, read.toLong())
                require(length <= maxBytes) { "Workspace snapshot payload is too large" }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
            output.fd.sync()
        }
        WorkspaceArchiveTar.applyMode(target, 0x180)
        return PayloadDigest(length, digest.digest().toHexString())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun atomicMove(source: File, target: File) {
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace snapshot target already exists"
        }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun deleteNoFollow(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to remove symbolic-link snapshot artifact: ${file.path}"
        }
        require(file.deleteRecursively()) { "Unable to remove snapshot artifact: ${file.path}" }
    }

    private fun WorkspaceEntity.toSnapshotWorkspace(
        files: WorkspaceArchivePayload,
        linux: WorkspaceArchivePayload,
    ) = WorkspaceArchiveWorkspace(
        id = id,
        name = name,
        root = root,
        shellStatus = shellStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        runtimeMetadata = runtimeMetadataOrDefault(),
        files = files,
        linux = linux,
        toolApprovals = toolApprovals,
    )

    private fun WorkspaceArchiveWorkspace.toEntity(): WorkspaceEntity = WorkspaceEntity(
        id = id,
        name = name,
        root = root,
        shellStatus = shellStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        toolApprovals = toolApprovals,
        runtimeMetadata = JsonInstant.encodeToString(runtimeMetadata),
    )

    private data class SnapshotVerification(val manifest: WorkspaceSnapshotManifest)

    private data class PreparedSnapshot(
        val manifest: WorkspaceSnapshotManifest,
        val stagingRoot: File,
    )

    private data class PayloadDigest(val length: Long, val sha256: String)

    private data class WorkspaceSourceFingerprint(
        val entries: List<WorkspaceSourceFingerprintEntry>,
    )

    private data class WorkspaceSourceFingerprintEntry(
        val relativePath: String,
        val type: String,
        val size: Long = 0L,
        val modifiedAt: Long = 0L,
        val linkTarget: String? = null,
        val fileKey: String? = null,
        val mode: Int? = null,
        val contentSha256: String? = null,
    )

    companion object {
        private const val TAG = "WorkspaceSnapshotService"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val MAX_PAYLOAD_BYTES = 32L * 1024 * 1024 * 1024
        private const val MAX_TAR_ENTRIES = 400_000
        private const val SNAPSHOT_OVERHEAD_BYTES = 64L * 1024L
        private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}

private fun ByteArray.toHexString(): String = joinToString("") {
    "%02x".format(Locale.US, it.toInt() and 0xff)
}
