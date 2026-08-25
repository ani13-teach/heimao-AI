package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** The two filesystem operations which use the snapshot journal. */
@Serializable
internal enum class WorkspaceSnapshotRecoveryOperation {
    CREATE,
    RESTORE,
}

/** Durable checkpoints for a local snapshot transaction. */
@Serializable
internal enum class WorkspaceSnapshotRecoveryPhase {
    PREPARED,
    STAGING_READY,
    DIRECTORY_ACTIVATED,
    ENTITY_UPDATED,
    SNAPSHOT_UPDATED,
    COMMIT_READY,
    COMMITTED,
    ROLLED_BACK,
}

/** The old Room row needed to compensate a restore after a process interruption. */
@Serializable
internal data class WorkspaceSnapshotEntityRecoveryState(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
    val toolApprovals: String = "{}",
    val runtimeMetadata: String = "{}",
) {
    fun toEntity(): WorkspaceEntity = WorkspaceEntity(
        id = id,
        name = name,
        root = root,
        shellStatus = shellStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        toolApprovals = toolApprovals,
        runtimeMetadata = runtimeMetadata,
    )
}

internal fun WorkspaceEntity.toSnapshotRecoveryState() = WorkspaceSnapshotEntityRecoveryState(
    id = id,
    name = name,
    root = root,
    shellStatus = shellStatus,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessAt = lastAccessAt,
    toolApprovals = toolApprovals,
    runtimeMetadata = runtimeMetadata,
)

/**
 * A small, app-private journal for snapshot create/restore. The journal is deliberately separate
 * from Room: it must be readable before Koin/Room starts after a process is killed during a swap.
 */
@Serializable
internal data class WorkspaceSnapshotRecoveryJournal(
    val version: Int = CURRENT_VERSION,
    val transactionId: String = UUID.randomUUID().toString(),
    val operation: WorkspaceSnapshotRecoveryOperation,
    val phase: WorkspaceSnapshotRecoveryPhase = WorkspaceSnapshotRecoveryPhase.PREPARED,
    val workspaceId: String,
    /** Snapshot archive for CREATE, workspace directory for RESTORE. */
    val target: String,
    /** Old workspace directory for RESTORE; a reserved app-private path for CREATE. */
    val recovery: String,
    /** Snapshot/archive staging directory. */
    val staging: String,
    val snapshotId: String,
    /** True only when the transaction was compensated; CREATE cleanup may remove its target. */
    val rollbackConfirmed: Boolean = false,
    val previousWorkspace: WorkspaceSnapshotEntityRecoveryState? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Filesystem result of pre-Koin recovery; Room state is repaired by the service afterwards. */
internal data class WorkspaceSnapshotRecoveryOutcome(
    val databaseRepairRequired: Boolean,
)

/** Durable filesystem half of the snapshot transaction. */
internal object WorkspaceSnapshotRecovery {
    const val JOURNAL_FILE_NAME = ".workspace-snapshot-recovery.json"
    private const val TEMP_SUFFIX = ".tmp"
    private const val TAG = "WorkspaceSnapshotRecovery"
    private val JSON = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun journalFile(context: Context): File = journalFile(context.filesDir)

    fun journalFile(filesDir: File): File = File(filesDir, JOURNAL_FILE_NAME)

    fun readJournal(file: File): WorkspaceSnapshotRecoveryJournal? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Snapshot recovery journal is a symbolic link" }
        require(file.isFile) { "Snapshot recovery journal is not a regular file" }
        return JSON.decodeFromString(file.readText(Charsets.UTF_8))
    }

    fun writeJournal(file: File, journal: WorkspaceSnapshotRecoveryJournal) {
        require(journal.version == WorkspaceSnapshotRecoveryJournal.CURRENT_VERSION) {
            "Unsupported snapshot recovery journal version: ${journal.version}"
        }
        require(journal.workspaceId.isNotBlank()) { "Snapshot recovery workspace ID is blank" }
        require(journal.snapshotId.isNotBlank()) { "Snapshot recovery snapshot ID is blank" }
        require(!Files.isSymbolicLink(file.toPath())) {
            "Snapshot recovery journal is a symbolic link"
        }
        val parent = file.parentFile ?: error("Snapshot recovery journal has no parent directory")
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create snapshot recovery journal directory"
        }
        val temporary = File(file.path + TEMP_SUFFIX)
        require(!Files.isSymbolicLink(temporary.toPath())) {
            "Snapshot recovery journal temporary file is a symbolic link"
        }
        FileOutputStream(temporary).use { output ->
            output.write(JSON.encodeToString(journal).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Runs before Koin. It never opens Room and never changes a workspace target silently. */
    fun recoverBeforeKoin(context: Context): WorkspaceSnapshotRecoveryOutcome =
        recoverPendingJournal(
            journal = journalFile(context),
            filesDir = context.filesDir,
        )

    /** Pure filesystem recovery entry point used by JVM tests and [recoverBeforeKoin]. */
    fun recoverPendingJournal(
        journal: File,
        filesDir: File,
    ): WorkspaceSnapshotRecoveryOutcome {
        val state = readJournal(journal)
        if (state == null) {
            // A process can die before the first journal write. Only names reserved for snapshot
            // staging are removed here; user-visible archives and workspace directories remain.
            cleanupSnapshotTemporaryDirectories(snapshotDirectory(filesDir))
            return WorkspaceSnapshotRecoveryOutcome(false)
        }
        validateJournalPaths(state, journal, filesDir)

        return when (state.phase) {
            WorkspaceSnapshotRecoveryPhase.COMMITTED -> {
                cleanupCommitted(journal, state, filesDir)
                WorkspaceSnapshotRecoveryOutcome(false)
            }

            WorkspaceSnapshotRecoveryPhase.COMMIT_READY -> {
                // All Room state was durably updated before COMMIT_READY. Never roll the new
                // workspace back from this point; only completion of safe cleanup remains.
                val committed = state.copy(phase = WorkspaceSnapshotRecoveryPhase.COMMITTED)
                writeJournal(journal, committed)
                cleanupCommitted(journal, committed, filesDir)
                WorkspaceSnapshotRecoveryOutcome(false)
            }

            WorkspaceSnapshotRecoveryPhase.ROLLED_BACK -> {
                // Keep this marker until the Room-side repair has restored RESTORING/CREATING
                // rows. Filesystem cleanup is safe and idempotent, but clearing the journal is not.
                cleanupRolledBackArtifacts(state, filesDir)
                WorkspaceSnapshotRecoveryOutcome(true)
            }

            else -> {
                rollbackPendingFilesystem(journal, state, filesDir)
                WorkspaceSnapshotRecoveryOutcome(true)
            }
        }
    }

    /** Completes cleanup after Room has repaired the snapshot row(s). */
    fun finishRolledBackTransaction(
        journal: File,
        state: WorkspaceSnapshotRecoveryJournal,
        filesDir: File,
    ): Boolean {
        require(state.phase == WorkspaceSnapshotRecoveryPhase.ROLLED_BACK) {
            "Snapshot rollback must be confirmed before cleanup commit"
        }
        validateJournalPaths(state, journal, filesDir)
        val committed = state.copy(
            phase = WorkspaceSnapshotRecoveryPhase.COMMITTED,
            rollbackConfirmed = true,
        )
        writeJournal(journal, committed)
        return cleanupCommitted(journal, committed, filesDir)
    }

    /** Best-effort, idempotent cleanup for a committed journal. */
    fun cleanupCommitted(
        journal: File,
        state: WorkspaceSnapshotRecoveryJournal,
        filesDir: File,
    ): Boolean {
        validateJournalPaths(state, journal, filesDir)
        var success = true
        success = deleteSafe(File(state.staging), "staging") && success
        success = deleteSafe(File(state.recovery), "recovery") && success
        if (state.operation == WorkspaceSnapshotRecoveryOperation.CREATE) {
            // A failed CREATE is represented by rollbackConfirmed even after its journal reaches
            // COMMITTED for cleanup purposes. A genuinely committed create retains its archive.
            if (state.rollbackConfirmed) {
                success = deleteSafe(File(state.target), "rolled-back snapshot target") && success
            }
            success = deleteSafe(partialFile(File(state.target)), "partial") && success
        }
        success = cleanupSnapshotTemporaryDirectories(snapshotDirectory(filesDir)) && success
        if (success) {
            runCatching { clearJournal(journal) }
                .onFailure {
                    success = false
                    logCleanupFailure("journal", it)
                }
        }
        return success
    }

    fun clearJournal(file: File) {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file.toPath())) {
                "Snapshot recovery journal is a symbolic link"
            }
            require(file.delete()) { "Unable to remove snapshot recovery journal" }
        }
        val temporary = File(file.path + TEMP_SUFFIX)
        if (Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(temporary.toPath())) {
                "Snapshot recovery journal temporary file is a symbolic link"
            }
            require(temporary.delete()) {
                "Unable to remove snapshot recovery journal temporary file"
            }
        }
    }

    private fun rollbackPendingFilesystem(
        journal: File,
        state: WorkspaceSnapshotRecoveryJournal,
        filesDir: File,
    ) {
        when (state.operation) {
            WorkspaceSnapshotRecoveryOperation.CREATE -> {
                // CREATE has no directory exchange. Its target is an app-private archive and can
                // be discarded while the database row is later marked FAILED.
                deleteSafe(File(state.target), "snapshot target")
                deleteSafe(partialFile(File(state.target)), "snapshot partial")
                deleteSafe(File(state.recovery), "snapshot recovery")
            }

            WorkspaceSnapshotRecoveryOperation.RESTORE -> {
                if (
                    state.phase.ordinal >= WorkspaceSnapshotRecoveryPhase.DIRECTORY_ACTIVATED.ordinal ||
                    Files.exists(File(state.recovery).toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    restoreOldWorkspaceDirectory(state)
                }
                // Never delete target here: after rollback it is the user's old workspace.
                deleteSafe(File(state.staging), "snapshot staging")
                deleteSafe(File(state.recovery), "snapshot recovery")
            }
        }
        val rolledBack = state.copy(
            phase = WorkspaceSnapshotRecoveryPhase.ROLLED_BACK,
            rollbackConfirmed = true,
        )
        // The marker is written only after the old directory has been put back. If this write
        // fails, the old recovery directory is intentionally retained for the next launch.
        writeJournal(journal, rolledBack)
        cleanupRolledBackArtifacts(rolledBack, filesDir)
    }

    private fun cleanupRolledBackArtifacts(
        state: WorkspaceSnapshotRecoveryJournal,
        filesDir: File,
    ) {
        var success = true
        when (state.operation) {
            WorkspaceSnapshotRecoveryOperation.CREATE -> {
                success = deleteSafe(File(state.target), "rolled-back snapshot target") && success
                success = deleteSafe(partialFile(File(state.target)), "rolled-back snapshot partial") && success
            }

            WorkspaceSnapshotRecoveryOperation.RESTORE -> {
                // The target is the restored old workspace after rollback and must never be
                // removed by cleanup.
            }
        }
        success = deleteSafe(File(state.staging), "rolled-back staging") && success
        success = deleteSafe(File(state.recovery), "rolled-back recovery") && success
        success = cleanupSnapshotTemporaryDirectories(snapshotDirectory(filesDir)) && success
        if (!success) {
            Log.w(TAG, "Snapshot recovery cleanup was incomplete; journal is retained")
        }
    }

    private fun restoreOldWorkspaceDirectory(state: WorkspaceSnapshotRecoveryJournal) {
        val target = File(state.target)
        val recovery = File(state.recovery)
        require(!Files.isSymbolicLink(target.toPath())) {
            "Snapshot restore target is a symbolic link"
        }
        require(!Files.isSymbolicLink(recovery.toPath())) {
            "Snapshot restore recovery is a symbolic link"
        }
        require(Files.exists(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Snapshot restore recovery directory is missing"
        }
        require(recovery.isDirectory) { "Snapshot restore recovery is not a directory" }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(target.isDirectory) { "Snapshot restore target is not a directory" }
            deleteRecursively(target)
        }
        require(recovery.renameTo(target)) {
            "Unable to restore old workspace directory: ${target.path}"
        }
    }

    private fun validateJournalPaths(
        state: WorkspaceSnapshotRecoveryJournal,
        journal: File,
        filesDir: File,
    ) {
        require(state.version == WorkspaceSnapshotRecoveryJournal.CURRENT_VERSION) {
            "Unsupported snapshot recovery journal version: ${state.version}"
        }
        require(state.workspaceId.isNotBlank()) { "Snapshot recovery workspace ID is blank" }
        require(state.snapshotId.isNotBlank()) { "Snapshot recovery snapshot ID is blank" }
        require(!Files.isSymbolicLink(filesDir.toPath())) {
            "Application files directory is a symbolic link"
        }
        val filesRoot = filesDir.canonicalFile
        val snapshotsDirectory = snapshotDirectory(filesDir)
        require(!Files.isSymbolicLink(snapshotsDirectory.toPath())) {
            "Workspace snapshots directory is a symbolic link"
        }
        val snapshotsRoot = snapshotsDirectory.canonicalFile
        require(snapshotsRoot.parentFile == filesRoot) {
            "Workspace snapshots directory escapes app-private files"
        }
        fun requirePath(path: String, root: File, label: String) {
            require(path.isNotBlank()) { "$label is blank" }
            val file = File(path)
            require(file.isAbsolute) { "$label must be absolute" }
            val canonical = file.canonicalFile
            require(canonical == root || canonical.path.startsWith(root.path + File.separator)) {
                "$label is outside its allowed root: $path"
            }
            require(!Files.isSymbolicLink(file.toPath())) { "$label is a symbolic link: $path" }
        }
        requirePath(journal.absolutePath, filesRoot, "Snapshot recovery journal")
        requirePath(
            state.target,
            if (state.operation == WorkspaceSnapshotRecoveryOperation.CREATE) snapshotsRoot else filesRoot,
            "Snapshot recovery target",
        )
        requirePath(state.staging, snapshotsRoot, "Snapshot recovery staging")
        requirePath(state.recovery, filesRoot, "Snapshot recovery directory")

        val target = File(state.target).canonicalFile
        when (state.operation) {
            WorkspaceSnapshotRecoveryOperation.CREATE -> {
                // A create journal may only remove the archive reserved by this transaction.
                // Do not let a corrupted journal turn recovery into an arbitrary filesDir delete.
                require(target.parentFile == snapshotsRoot) {
                    "Snapshot recovery create target is not directly below snapshots"
                }
                require(target.name == "${state.snapshotId}.zip") {
                    "Snapshot recovery create target does not match snapshot ID"
                }
            }

            WorkspaceSnapshotRecoveryOperation.RESTORE -> {
                val previous = state.previousWorkspace
                    ?: error("Snapshot restore recovery has no previous workspace row")
                require(previous.id == state.workspaceId) {
                    "Snapshot recovery workspace row does not match journal"
                }
                require(previous.root.isValidArchiveComponent()) {
                    "Snapshot recovery workspace root is invalid"
                }
                val workspacesRoot = File(filesDir, "workspaces")
                require(!Files.isSymbolicLink(workspacesRoot.toPath())) {
                    "Workspace directory catalog is a symbolic link"
                }
                val expectedTarget = File(workspacesRoot, previous.root).canonicalFile
                require(target == expectedTarget) {
                    "Snapshot recovery restore target does not match workspace row"
                }
                require(File(state.recovery).name.startsWith(".workspace-snapshot-recovery-")) {
                    "Snapshot recovery restore directory is not reserved"
                }
            }
        }
    }

    private fun snapshotDirectory(filesDir: File): File = File(filesDir, FileFolders.SNAPSHOTS)

    private fun partialFile(target: File): File = File(target.path.removeSuffix(".zip") + ".zip.partial")

    private fun cleanupSnapshotTemporaryDirectories(root: File): Boolean {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) {
            logCleanupFailure("snapshot temporary root", IllegalStateException("invalid root"))
            return false
        }
        var success = true
        root.listFiles().orEmpty()
            .filter { child ->
                child.name.startsWith(".snapshot-") ||
                    child.name.startsWith(".restore-") ||
                    child.name.startsWith(".restore-payload-") ||
                    child.name.startsWith(".verify-")
            }
            .forEach { child ->
                success = deleteSafe(child, "snapshot temporary item") && success
            }
        return success
    }

    private fun deleteRequired(file: File, label: String) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(file.toPath())) { "$label is a symbolic link" }
        deleteRecursively(file)
    }

    private fun deleteSafe(file: File, label: String): Boolean {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(file.toPath())) {
            logCleanupFailure(label, IllegalStateException("symbolic link"))
            return false
        }
        return runCatching { deleteRecursively(file) }
            .onFailure { logCleanupFailure(label, it) }
            .isSuccess
    }

    private fun deleteRecursively(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to delete snapshot recovery symbolic link: ${file.path}"
        }
        require(file.deleteRecursively()) { "Unable to remove snapshot recovery item: ${file.path}" }
    }

    private fun logCleanupFailure(label: String, error: Throwable) {
        // Do not include paths: a path can contain user-controlled names or credentials.
        Log.w(TAG, "Snapshot recovery cleanup failed: item=$label")
    }
}
