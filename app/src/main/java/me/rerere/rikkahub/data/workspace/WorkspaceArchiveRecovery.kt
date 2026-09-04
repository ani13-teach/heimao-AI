package me.rerere.rikkahub.data.workspace

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.restoreSettingsDataStore
import me.rerere.rikkahub.data.datastore.settingsDataStoreFile
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Durable state for the full archive restore transaction.
 *
 * The journal is written before either the workspace root, settings, or database is changed. A
 * pending transaction is always rolled back on the next process start. COMMITTED is the only
 * state in which the old root/database may be deleted.
 */
@Serializable
internal enum class WorkspaceArchiveRecoveryPhase {
    PREPARED,
    WORKSPACES_ACTIVATED,
    SETTINGS_APPLYING,
    SETTINGS_APPLIED,
    ROOM_CLOSE_PENDING,
    ROOM_CLOSED,
    DATABASE_EXCHANGED,
    ROLLBACK_REQUIRED,
    COMMITTED,
    ROLLED_BACK,
}

@Serializable
internal enum class WorkspaceArchiveCommitState {
    PENDING,
    COMMITTED,
    ROLLED_BACK,
}

@Serializable
internal data class WorkspaceArchiveDirectoryRecovery(
    /** The new directory currently visible to the application. */
    val newRoot: String,
    /** The old directory moved out of the way before activation. */
    val oldRootRecovery: String,
    val oldRootExisted: Boolean,
)

@Serializable
internal data class WorkspaceArchiveDatabaseRecovery(
    val databaseDirectory: String,
    val oldDatabaseRecovery: String,
    val targetNames: List<String>,
    val oldExistingNames: List<String>,
)

@Serializable
internal data class WorkspaceArchiveRecoveryJournal(
    val version: Int = CURRENT_VERSION,
    val transactionId: String = UUID.randomUUID().toString(),
    val phase: WorkspaceArchiveRecoveryPhase = WorkspaceArchiveRecoveryPhase.PREPARED,
    val commitState: WorkspaceArchiveCommitState = WorkspaceArchiveCommitState.PENDING,
    val workspaces: WorkspaceArchiveDirectoryRecovery? = null,
    val legacyDirectories: List<WorkspaceArchiveDirectoryRecovery> = emptyList(),
    val database: WorkspaceArchiveDatabaseRecovery? = null,
    /** IDs proven absent before an incremental restore and safe to delete during rollback. */
    val incrementalWorkspaceIds: List<String> = emptyList(),
    /** Legacy JSON settings snapshot retained for journals written before raw snapshots. */
    val settingsBackup: String? = null,
    /** Exact Preferences DataStore file snapshot used for pre-Koin compensation. */
    val settingsDataStoreBackup: String? = null,
    /** Null means this journal predates raw DataStore snapshots. */
    val settingsDataStoreExisted: Boolean? = null,
    val settingsApplied: Boolean = false,
    val settingsNeedsCompensation: Boolean = false,
    /** Top-level filesDir artifacts which may be deleted only after recovery is confirmed. */
    val cleanupArtifacts: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal data class WorkspaceArchiveRecoveryOutcome(
    val settingsNeedsCompensation: Boolean,
    val incrementalWorkspaceIdsToDelete: List<String> = emptyList(),
)

/** Journal IO and pre-Koin recovery for full archive restores. */
internal object WorkspaceArchiveRecovery {
    const val JOURNAL_FILE_NAME = ".workspace-archive-recovery.json"
    private const val TEMP_SUFFIX = ".tmp"
    private val JSON = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun journalFile(context: Context): File = journalFile(context.filesDir)

    fun journalFile(filesDir: File): File = File(filesDir, JOURNAL_FILE_NAME)

    fun readJournal(file: File): WorkspaceArchiveRecoveryJournal? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Recovery journal is a symbolic link" }
        require(file.isFile) { "Recovery journal is not a regular file" }
        return JSON.decodeFromString(file.readText(Charsets.UTF_8))
    }

    fun writeJournal(file: File, journal: WorkspaceArchiveRecoveryJournal) {
        require(journal.version == WorkspaceArchiveRecoveryJournal.CURRENT_VERSION) {
            "Unsupported workspace recovery journal version: ${journal.version}"
        }
        require(!Files.isSymbolicLink(file.toPath())) { "Recovery journal is a symbolic link" }
        require(file.parentFile != null) { "Recovery journal has no parent directory" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) {
            "Unable to create recovery journal directory"
        }
        val temporary = File(file.path + TEMP_SUFFIX)
        require(!Files.isSymbolicLink(temporary.toPath())) { "Recovery journal temporary file is a symbolic link" }
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

    fun writeSettingsBackup(file: File, rawSettings: String) {
        require(!Files.isSymbolicLink(file.toPath())) { "Settings recovery file is a symbolic link" }
        require(file.parentFile != null) { "Settings recovery file has no parent directory" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) {
            "Unable to create settings recovery directory"
        }
        FileOutputStream(file).use { output ->
            output.write(rawSettings.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    /**
     * Saves the exact Preferences DataStore bytes before a restore transaction changes settings.
     * The backup remains in place until the recovery journal is finalized.
     */
    fun snapshotSettingsDataStore(source: File, backup: File): Boolean {
        require(!Files.isSymbolicLink(source.toPath())) {
            "Settings DataStore is a symbolic link"
        }
        if (!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        require(source.isFile) { "Settings DataStore is not a regular file" }
        copyFileAtomically(source, backup)
        return true
    }

    /**
     * Restores the exact Preferences DataStore bytes without constructing SettingsStore or Koin.
     * The backup is copied, rather than moved, so a retry after a process interruption is safe.
     */
    internal fun restoreSettingsDataStoreSnapshot(
        target: File,
        backup: File,
        existed: Boolean,
    ) {
        require(!Files.isSymbolicLink(target.toPath())) {
            "Settings DataStore target is a symbolic link"
        }
        require(target.parentFile != null) { "Settings DataStore target has no parent directory" }
        require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) {
            "Unable to create Settings DataStore directory"
        }
        if (existed) {
            require(!Files.isSymbolicLink(backup.toPath())) {
                "Settings DataStore recovery snapshot is a symbolic link"
            }
            require(Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Settings DataStore recovery snapshot is missing"
            }
            copyFileAtomically(backup, target)
        } else if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(target.isFile) { "Settings DataStore target is not a regular file" }
            require(Files.deleteIfExists(target.toPath())) {
                "Unable to remove Settings DataStore file"
            }
        }
    }

    /** Runs before startKoin, so Room cannot open a half-restored database. */
    fun recoverBeforeKoin(context: Context): WorkspaceArchiveRecoveryOutcome =
        recoverPendingJournal(
            journal = journalFile(context),
            filesDir = context.filesDir,
            databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
                ?: error("Database directory is unavailable"),
        )

    /**
     * Pure filesystem recovery entry point used by JVM tests and by [recoverBeforeKoin]. It is
     * intentionally synchronous: no coroutine or Koin object may be needed before the database
     * has been made self-consistent again.
     */
    fun recoverPendingJournal(
        journal: File,
        filesDir: File,
        databaseDirectory: File,
    ): WorkspaceArchiveRecoveryOutcome {
        val state = readJournal(journal) ?: return WorkspaceArchiveRecoveryOutcome(false)
        validateJournalPaths(state, journal, filesDir, databaseDirectory)

        return when (state.commitState) {
            WorkspaceArchiveCommitState.COMMITTED -> {
                finishConfirmedTransaction(journal, state, filesDir, databaseDirectory)
                WorkspaceArchiveRecoveryOutcome(false)
            }

            WorkspaceArchiveCommitState.ROLLED_BACK -> {
                when {
                    state.settingsNeedsCompensation -> WorkspaceArchiveRecoveryOutcome(
                        settingsNeedsCompensation = true,
                        incrementalWorkspaceIdsToDelete = state.incrementalWorkspaceIds,
                    )
                    state.incrementalWorkspaceIds.isNotEmpty() -> WorkspaceArchiveRecoveryOutcome(
                        settingsNeedsCompensation = false,
                        incrementalWorkspaceIdsToDelete = state.incrementalWorkspaceIds,
                    )
                    else -> {
                        finishConfirmedTransaction(journal, state, filesDir, databaseDirectory)
                        WorkspaceArchiveRecoveryOutcome(false)
                    }
                }
            }

            WorkspaceArchiveCommitState.PENDING -> {
                rollbackPendingTransaction(journal, state, filesDir, databaseDirectory)
            }
        }
    }

    /**
     * Completes settings compensation before Koin is started. Raw DataStore snapshots are always
     * preferred; the JSON path is only a compatibility fallback for journals written by an older
     * build. No cleanup is attempted until the settings restore and journal update both succeed.
     */
    suspend fun compensatePendingSettingsBeforeKoin(
        context: Context,
    ): WorkspaceArchiveRecoveryOutcome {
        val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        return compensatePendingSettingsBeforeKoin(
            journal = journalFile(context),
            filesDir = context.filesDir,
            databaseDirectory = databaseDirectory,
            dataStoreFile = settingsDataStoreFile(context),
            restoreJson = { settings -> restoreSettingsDataStore(context, settings) },
        )
    }

    internal suspend fun compensatePendingSettingsBeforeKoin(
        journal: File,
        filesDir: File,
        databaseDirectory: File,
        dataStoreFile: File,
        restoreJson: (suspend (Settings) -> Unit)? = null,
    ): WorkspaceArchiveRecoveryOutcome {
        val state = readJournal(journal) ?: return WorkspaceArchiveRecoveryOutcome(false)
        if (!state.settingsNeedsCompensation) {
            return WorkspaceArchiveRecoveryOutcome(
                settingsNeedsCompensation = false,
                incrementalWorkspaceIdsToDelete = state.incrementalWorkspaceIds,
            )
        }
        validateJournalPaths(state, journal, filesDir, databaseDirectory)
        validateDataStorePath(dataStoreFile, filesDir)

        val rawBackupPath = state.settingsDataStoreBackup
        when {
            rawBackupPath != null && state.settingsDataStoreExisted != null -> {
                restoreSettingsDataStoreSnapshot(
                    target = dataStoreFile,
                    backup = File(rawBackupPath),
                    existed = state.settingsDataStoreExisted,
                )
            }

            rawBackupPath != null && Files.isRegularFile(
                File(rawBackupPath).toPath(),
                LinkOption.NOFOLLOW_LINKS,
            ) -> {
                // Be tolerant of an early journal format which recorded the path but not the
                // existence bit. A present file is unambiguously an old DataStore snapshot.
                restoreSettingsDataStoreSnapshot(
                    target = dataStoreFile,
                    backup = File(rawBackupPath),
                    existed = true,
                )
            }

            else -> {
                val settingsPath = state.settingsBackup
                    ?: error("Recovery journal requires settings compensation but has no backup")
                val settingsFile = File(settingsPath)
                require(Files.isRegularFile(settingsFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "Settings recovery snapshot is missing"
                }
                val oldSettings = JsonInstant.decodeFromString<Settings>(
                    settingsFile.readText(Charsets.UTF_8),
                )
                val restore = requireNotNull(restoreJson) {
                    "Legacy settings recovery requires a DataStore restore callback"
                }
                restore(oldSettings)
            }
        }

        val compensated = state.copy(
            phase = WorkspaceArchiveRecoveryPhase.ROLLED_BACK,
            commitState = WorkspaceArchiveCommitState.ROLLED_BACK,
            settingsApplied = false,
            settingsNeedsCompensation = false,
        )
        // Keep every recovery artifact protected until this durable state transition succeeds.
        writeJournal(journal, compensated)
        if (compensated.incrementalWorkspaceIds.isNotEmpty()) {
            return WorkspaceArchiveRecoveryOutcome(
                settingsNeedsCompensation = false,
                incrementalWorkspaceIdsToDelete = compensated.incrementalWorkspaceIds,
            )
        }
        return finishConfirmedTransaction(journal, compensated, filesDir, databaseDirectory)
    }

    /** Finishes the Room half of an interrupted incremental rollback after Koin has opened Room. */
    fun completeIncrementalRoomRollback(context: Context): WorkspaceArchiveRecoveryOutcome {
        val journal = journalFile(context)
        val state = readJournal(journal) ?: return WorkspaceArchiveRecoveryOutcome(false)
        require(state.commitState == WorkspaceArchiveCommitState.ROLLED_BACK) {
            "Incremental Room rollback requires a rolled-back journal"
        }
        require(!state.settingsNeedsCompensation) {
            "Settings compensation must finish before Room rollback"
        }
        val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        val completed = state.copy(incrementalWorkspaceIds = emptyList())
        writeJournal(journal, completed)
        return finishConfirmedTransaction(journal, completed, context.filesDir, databaseDirectory)
    }

    /** Retries cleanup after application-level compensation durably reached ROLLED_BACK. */
    fun finishRolledBack(context: Context): WorkspaceArchiveRecoveryOutcome {
        val journal = journalFile(context)
        val state = readJournal(journal) ?: return WorkspaceArchiveRecoveryOutcome(false)
        require(state.commitState == WorkspaceArchiveCommitState.ROLLED_BACK) {
            "Workspace archive transaction is not rolled back"
        }
        require(!state.settingsNeedsCompensation && state.incrementalWorkspaceIds.isEmpty()) {
            "Workspace archive rollback still needs compensation"
        }
        val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        return finishConfirmedTransaction(journal, state, context.filesDir, databaseDirectory)
    }

    /** Cleans a transaction only after its durable COMMITTED marker has been written. */
    fun finishCommitted(context: Context): WorkspaceArchiveRecoveryOutcome {
        val journal = journalFile(context)
        val state = readJournal(journal) ?: return WorkspaceArchiveRecoveryOutcome(false)
        require(state.commitState == WorkspaceArchiveCommitState.COMMITTED) {
            "Workspace archive transaction is not committed"
        }
        val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        return finishConfirmedTransaction(journal, state, context.filesDir, databaseDirectory)
    }

    /**
     * Cleanup must refuse every marker while a journal is pending or settings compensation is not
     * complete. This prevents an asynchronous legacy cleanup coroutine from deleting the only
     * copy of the old root after a process interruption.
     */
    fun protectsPendingCleanup(context: Context): Boolean {
        val file = journalFile(context)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val journal = runCatching { readJournal(file) }.getOrElse { return true } ?: return true
        return journal.commitState == WorkspaceArchiveCommitState.PENDING ||
            journal.settingsNeedsCompensation
    }

    fun clearJournal(file: File) {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file.toPath())) { "Recovery journal is a symbolic link" }
            require(file.delete()) { "Unable to remove recovery journal" }
        }
        val temporary = File(file.path + TEMP_SUFFIX)
        if (Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(temporary.toPath())) {
                "Recovery journal temporary file is a symbolic link"
            }
            require(temporary.delete()) { "Unable to remove recovery journal temporary file" }
        }
    }

    private fun rollbackPendingTransaction(
        journal: File,
        state: WorkspaceArchiveRecoveryJournal,
        filesDir: File,
        databaseDirectory: File,
    ): WorkspaceArchiveRecoveryOutcome {
        // Recovery records are processed in the reverse conceptual order: database and settings
        // are restored to the same pre-transaction generation as the old workspace root.
        state.workspaces?.let(::rollbackDirectory)
        state.legacyDirectories.asReversed().forEach(::rollbackDirectory)
        state.database?.let(::rollbackDatabase)

        val rolledBack = state.copy(
            phase = WorkspaceArchiveRecoveryPhase.ROLLED_BACK,
            commitState = WorkspaceArchiveCommitState.ROLLED_BACK,
            settingsNeedsCompensation = state.settingsApplied || state.settingsNeedsCompensation,
        )
        writeJournal(journal, rolledBack)
        if (rolledBack.settingsNeedsCompensation) {
            return WorkspaceArchiveRecoveryOutcome(
                settingsNeedsCompensation = true,
                incrementalWorkspaceIdsToDelete = rolledBack.incrementalWorkspaceIds,
            )
        }
        if (rolledBack.incrementalWorkspaceIds.isNotEmpty()) {
            return WorkspaceArchiveRecoveryOutcome(
                settingsNeedsCompensation = false,
                incrementalWorkspaceIdsToDelete = rolledBack.incrementalWorkspaceIds,
            )
        }
        finishConfirmedTransaction(journal, rolledBack, filesDir, databaseDirectory)
        return WorkspaceArchiveRecoveryOutcome(false)
    }

    private fun rollbackDirectory(record: WorkspaceArchiveDirectoryRecovery) {
        val target = File(record.newRoot)
        val recovery = File(record.oldRootRecovery)
        require(!Files.isSymbolicLink(target.toPath())) { "Recovery target is a symbolic link: ${target.path}" }
        require(!Files.isSymbolicLink(recovery.toPath())) {
            "Recovery source is a symbolic link: ${recovery.path}"
        }
        if (Files.exists(recovery.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(recovery.isDirectory) { "Recovery source is not a directory: ${recovery.path}" }
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(target)
            }
            require(recovery.renameTo(target)) {
                "Unable to restore old directory: ${target.path}"
            }
        } else if (!record.oldRootExisted && Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(target)
        }
    }

    private fun rollbackDatabase(record: WorkspaceArchiveDatabaseRecovery) {
        val databaseDirectory = File(record.databaseDirectory)
        val recoveryDirectory = File(record.oldDatabaseRecovery)
        require(!Files.isSymbolicLink(databaseDirectory.toPath())) {
            "Database directory is a symbolic link"
        }
        require(!Files.isSymbolicLink(recoveryDirectory.toPath())) {
            "Database recovery directory is a symbolic link"
        }
        record.targetNames.forEach { name ->
            require(isSafeDatabaseName(name)) { "Invalid database recovery file name: $name" }
            val target = File(databaseDirectory, name)
            val old = File(recoveryDirectory, name)
            require(!Files.isSymbolicLink(target.toPath())) { "Database target is a symbolic link: $name" }
            require(!Files.isSymbolicLink(old.toPath())) { "Database recovery is a symbolic link: $name" }
            if (Files.exists(old.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteRecursively(target)
                }
                require(old.renameTo(target)) { "Unable to restore old database file: $name" }
            } else if (name !in record.oldExistingNames &&
                Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                // The old generation did not contain this sidecar. It can only be a partially
                // activated file from the interrupted new generation.
                deleteRecursively(target)
            }
        }
    }

    private fun finishConfirmedTransaction(
        journal: File,
        state: WorkspaceArchiveRecoveryJournal,
        filesDir: File,
        databaseDirectory: File,
    ): WorkspaceArchiveRecoveryOutcome {
        validateJournalPaths(state, journal, filesDir, databaseDirectory)
        state.settingsBackup?.let { path ->
            val backup = File(path)
            if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(backup.toPath())) { "Settings recovery is a symbolic link" }
                require(backup.delete()) { "Unable to remove settings recovery snapshot" }
            }
        }
        state.settingsDataStoreBackup?.let { path ->
            val backup = File(path)
            if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(backup.toPath())) {
                    "Settings DataStore recovery snapshot is a symbolic link"
                }
                require(backup.delete()) {
                    "Unable to remove Settings DataStore recovery snapshot"
                }
            }
        }
        state.cleanupArtifacts.forEach { path ->
            val artifact = File(path)
            if (Files.exists(artifact.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(artifact.toPath())) {
                    "Recovery cleanup artifact is a symbolic link: $path"
                }
                deleteRecursively(artifact)
            }
        }
        clearJournal(journal)
        return WorkspaceArchiveRecoveryOutcome(false)
    }

    private fun validateJournalPaths(
        state: WorkspaceArchiveRecoveryJournal,
        journal: File,
        filesDir: File,
        databaseDirectory: File,
    ) {
        require(state.version == WorkspaceArchiveRecoveryJournal.CURRENT_VERSION) {
            "Unsupported workspace recovery journal version: ${state.version}"
        }
        val filesRoot = filesDir.canonicalFile
        val databaseRoot = databaseDirectory.canonicalFile
        fun requirePath(path: String, root: File, label: String) {
            val file = File(path)
            require(file.isAbsolute) { "$label must be absolute" }
            val canonical = file.canonicalFile
            require(canonical == root || canonical.path.startsWith(root.path + File.separator)) {
                "$label is outside its allowed root: $path"
            }
            require(!Files.isSymbolicLink(file.toPath())) { "$label is a symbolic link: $path" }
        }
        fun requireFilesPath(path: String, label: String) = requirePath(path, filesRoot, label)

        state.workspaces?.let { record ->
            requireFilesPath(record.newRoot, "New workspaces root")
            requireFilesPath(record.oldRootRecovery, "Old workspaces root recovery")
        }
        state.legacyDirectories.forEach { record ->
            requireFilesPath(record.newRoot, "New legacy directory")
            requireFilesPath(record.oldRootRecovery, "Old legacy directory recovery")
        }
        state.database?.let { database ->
            requirePath(database.databaseDirectory, databaseRoot, "Database directory")
            requireFilesPath(database.oldDatabaseRecovery, "Old database recovery")
            database.targetNames.forEach { name ->
                require(isSafeDatabaseName(name)) { "Invalid database recovery file name: $name" }
            }
            require(database.oldExistingNames.all { it in database.targetNames }) {
                "Database recovery contains an unknown old file"
            }
        }
        require(state.incrementalWorkspaceIds.distinct().size == state.incrementalWorkspaceIds.size) {
            "Incremental recovery contains duplicate workspace IDs"
        }
        require(state.incrementalWorkspaceIds.all { id ->
            id.isNotBlank() && id.none { it == '/' || it == '\\' }
        }) { "Incremental recovery contains an invalid workspace ID" }
        state.settingsBackup?.let { requireFilesPath(it, "Legacy settings recovery snapshot") }
        state.settingsDataStoreBackup?.let {
            requireFilesPath(it, "Settings DataStore recovery snapshot")
        }
        state.cleanupArtifacts.forEach { requireFilesPath(it, "Recovery cleanup artifact") }
        requireFilesPath(journal.path, "Recovery journal")
    }

    private fun validateDataStorePath(file: File, filesDir: File) {
        require(file.isAbsolute) { "Settings DataStore path must be absolute" }
        val root = filesDir.canonicalFile
        val canonical = file.canonicalFile
        require(canonical == root || canonical.path.startsWith(root.path + File.separator)) {
            "Settings DataStore path is outside filesDir: ${file.path}"
        }
        var current: File? = file
        while (current != null && current != root.parentFile) {
            require(!Files.isSymbolicLink(current.toPath())) {
                "Settings DataStore path contains a symbolic link: ${current.path}"
            }
            current = current.parentFile
        }
    }

    private fun copyFileAtomically(source: File, target: File) {
        require(!Files.isSymbolicLink(source.toPath())) {
            "File copy source is a symbolic link: ${source.path}"
        }
        require(source.isFile) { "File copy source is not a regular file: ${source.path}" }
        val parent = target.parentFile ?: error("File copy target has no parent directory")
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create file copy target directory: ${parent.path}"
        }
        require(!Files.isSymbolicLink(target.toPath())) {
            "File copy target is a symbolic link: ${target.path}"
        }
        val temporary = File(target.path + TEMP_SUFFIX)
        require(!Files.isSymbolicLink(temporary.toPath())) {
            "File copy temporary target is a symbolic link: ${temporary.path}"
        }
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun deleteRecursively(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "Refusing to delete a symbolic link: ${file.path}" }
        require(file.deleteRecursively()) { "Unable to delete recovery path: ${file.path}" }
    }

    private fun isSafeDatabaseName(name: String): Boolean =
        name == "rikka_hub" || name == "rikka_hub-wal" ||
            name == "rikka_hub-shm" || name == "rikka_hub-journal"
}
