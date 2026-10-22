package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.settingsDataStoreFile
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.WorkspaceRuntimeMetadata
import me.rerere.rikkahub.data.model.redactWorkspaceUrl
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceEnvironmentStore
import me.rerere.workspace.WorkspaceShellStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable plaintext ZIP containing user files and HOME TAR payloads. */
class WorkspaceArchiveService(
    private val context: Context,
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val conversationDAO: ConversationDAO,
    private val database: AppDatabase,
    private val bindingCoordinator: AssistantWorkspaceBindingCoordinator,
) {
    private val runtimeManager by lazy {
        me.rerere.workspace.WorkspaceManager(
            File(context.filesDir, "workspaces"), portableRuntime = true,
        )
    }

    private fun runtimePaths(root: String) = runtimeManager.runtimePaths(root)

    suspend fun createBackupFile(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        baseFile: File? = null,
    ): File = withBackupTiming(context.filesDir, if (baseFile == null) "BACKUP_FULL" else "BACKUP_INCREMENTAL") {
        workspaceRepository.withEnvironmentArchiveLease {
        withContext(Dispatchers.IO) {
            workspaceRepository.withWorkspaceCatalogArchiveLock {
                try {
                    timedBackupPhase(BackupPhase.SOURCE_SCAN) { createBackupFileLocked(includeDatabase, includeFiles, baseFile) }
                } catch (error: FileSystemException) {
                    // A java.nio file error carries nothing but a path as its message, so a failed
                    // backup would otherwise be undiagnosable from the transport UI. Name the file
                    // and the reason ("Permission denied", "No such file or directory", ...).
                    throw IllegalStateException(
                        "Cannot read workspace archive source: ${error.file} " +
                            "(${error.reason ?: error.javaClass.simpleName})",
                        error,
                    )
                }
            }
        }
    }

    }

    private suspend fun createBackupFileLocked(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        baseFile: File?,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val target = File(context.cacheDir, "backup_${timestamp}_${UUID.randomUUID()}.zip")
        // The archive is written next to its final name and only renamed once every check below
        // passed, so no transport can ever observe a half-written backup.
        val partialZip = File(target.path + ".partial")
        val payloadDirectory = File(context.cacheDir, ".workspace-archive-${UUID.randomUUID()}")
        val budget = WorkspaceArchiveBudget()
        var finalized = false
        var failure: Throwable? = null
        try {
            budget.checkAvailableSpace(context.cacheDir, BUFFER_SIZE.toLong())
            deleteIfPresent(partialZip)
            deleteIfPresent(target)
            require(payloadDirectory.mkdirs() || payloadDirectory.isDirectory) {
                "Unable to create archive staging directory"
            }

            // Settings are archived exactly as the device holds them, credentials included: the
            // backup is the only copy of them after a reinstall, so it must not strip keys, URLs,
            // model lists, MCP tokens or transport credentials.
            val settings = settingsStore.readPersistedSettingsFailFast()
            // Workspace payloads are the portable unit and are always included. The legacy
            // FILES switch only controls upload/skills/fonts compatibility entries.
            val workspaces = workspaceRepository.getAll()
            val rawWorkspaceCatalog = database.workspaceDao().getAll()
                .map(::workspaceCatalogRecord)
                .sorted()
            // The portable manifest carries the environment-aware projection of every workspace
            // (a shared-rootfs workspace reports its shared status, not the device-local column),
            // and the database copy is written with exactly the same values.
            val exportedWorkspaceCatalog = workspaces.map(::workspaceCatalogRecord).sorted()
            val sourceFingerprints = mutableMapOf<String, WorkspaceSourceFingerprint>()
            val payloads = workspaces.map { workspace ->
                workspaceRepository.withWorkspaceArchiveLock(workspace.id) {
                    runtimePaths(workspace.root)
                    // DocumentsProvider/open-FD users cannot be strongly frozen by this mutex.
                    // The before/after fingerprint is a change detector, not a filesystem freeze.
                    val before = fingerprintWorkspaceSource(workspace)
                    sourceFingerprints[workspace.id] = before
                    val payload = createWorkspacePayloads(workspace, payloadDirectory, budget)
                    val after = fingerprintWorkspaceSource(workspace)
                    require(before == after) {
                        "Workspace source changed during backup (detection only; no consistent archive produced): ${workspace.id}"
                    }
                    payload
                }
            }

            val databaseSnapshot = if (includeDatabase) {
                createDatabaseSnapshot(payloadDirectory, budget, exportedWorkspaceCatalog)
            } else {
                null
            }
            val databaseFiles = listOfNotNull(databaseSnapshot?.source)
            val databaseWorkspaceCatalog = databaseSnapshot?.liveWorkspaceCatalog
                ?: rawWorkspaceCatalog
            databaseSnapshot?.let { snapshot ->
                require(snapshot.exportedWorkspaceCatalog.sorted() == exportedWorkspaceCatalog) {
                    "Workspace catalog changed while creating database snapshot"
                }
            }
            val expectedDirectoryRoots = verifyWorkspaceDirectoryCatalog(workspaces)
            val conversationReferences = authoritativeArchiveConversationReferences(
                databaseSnapshot?.conversationReferences
                    ?: conversationDAO.getWorkspaceReferences().map { reference ->
                        WorkspaceConversationReference(reference.conversationId, reference.workspaceId)
                    }
            )
            val assistantReferences = settings.assistants.map { assistant ->
                WorkspaceAssistantReference(
                    assistantId = assistant.id.toString(),
                    workspaceId = assistant.workspaceId?.toString(),
                )
            }
            val references = WorkspaceArchiveReferences(
                assistants = assistantReferences,
                conversations = conversationReferences,
            )
            val legacyFiles = if (includeFiles) collectLegacyFiles() else emptyList()
            verifyWorkspaceSourcesUnchanged(workspaces, sourceFingerprints)
            val manifest = WorkspaceArchiveManifest(
                appVersion = BuildConfig.VERSION_NAME,
                databaseVersion = AppDatabase.CURRENT_VERSION,
                createdAt = System.currentTimeMillis(),
                databaseEntries = databaseFiles.map { it.archiveName },
                databasePayloads = databaseFiles.map { source ->
                    WorkspaceArchivePayload(
                        path = source.archiveName,
                        length = source.file.length(),
                        sha256 = sha256(source.file),
                    )
                },
                legacyEntries = legacyFiles.map { it.archiveName },
                legacyFilesIncluded = includeFiles,
                snapshotsExcluded = databaseSnapshot?.snapshotsExcluded == true,
                workspaces = payloads,
                references = references,
            )
            manifest.validate()
            verifyWorkspaceArchiveCatalogConsistency(
                databaseWorkspaceCatalog = databaseWorkspaceCatalog,
                manifest = manifest,
                databaseConversationReferences = conversationReferences,
                assistantReferences = assistantReferences,
                actualDirectoryRoots = expectedDirectoryRoots,
            )

            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val settingsBytes = json.encodeToString(settings).toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Workspace manifest is too large" }
            val sources = databaseFiles + payloads.flatMap { workspace ->
                listOf(workspace.files, workspace.home).map { payload ->
                    ArchiveSource(File(payloadDirectory, payloadFileName(payload)), payload.path)
                }
            } + legacyFiles
            val incremental = baseFile?.let { base ->
                timedBackupPhase(BackupPhase.INCREMENTAL_COMPARE) {
                    ArchiveIncremental.plan(base, listOf(
                        ArchiveIncremental.describe("manifest.json", manifestBytes),
                        ArchiveIncremental.describe(WorkspaceArchiveManifest.SETTINGS_PATH, settingsBytes),
                    ) + sources.map { ArchiveIncremental.describe(it.archiveName, it.file) }, budget)
                }
            }
            val reused = incremental?.entries?.filter { it.fromBase }?.map { it.path }?.toSet().orEmpty()
            timedBackupPhase(BackupPhase.ZIP_WRITE) {
            FileOutputStream(partialZip).use { rawOutput ->
                BudgetOutputStream(rawOutput) { bytes ->
                    budget.recordZipCompressedBytes(bytes)
                    budget.recordStagingBytes(bytes, context.cacheDir)
                }.use { trackedOutput ->
                    ZipOutputStream(BufferedOutputStream(trackedOutput)).use { zipOut ->
                        incremental?.let { addBytes(zipOut, ArchiveIncremental.INDEX_PATH, ArchiveIncremental.encode(it), budget) }
                        addBytes(zipOut, "manifest.json", manifestBytes, budget)
                        if (WorkspaceArchiveManifest.SETTINGS_PATH !in reused) {
                            addBytes(zipOut, WorkspaceArchiveManifest.SETTINGS_PATH, settingsBytes, budget)
                        }
                        sources.filterNot { it.archiveName in reused }.forEach { source ->
                            currentCoroutineContext().ensureActive()
                            addFile(zipOut, source.file, source.archiveName, budget)
                        }
                    }
                }
            }
            }
            verifyWorkspaceSourcesUnchanged(workspaces, sourceFingerprints)
            budget.checkCompressedFile(partialZip, countBytes = false)
            budget.checkStagingDirectory(payloadDirectory)
            timedBackupPhase(BackupPhase.ARCHIVE_VERIFY) { verifyPortableArchive(partialZip, baseFile) }
            verifyWorkspaceSourcesUnchanged(workspaces, sourceFingerprints)
            verifyArchiveStateUnchanged(
                expectedWorkspaces = workspaces,
                expectedDatabaseCatalog = databaseWorkspaceCatalog,
                manifest = manifest,
                expectedDirectoryRoots = expectedDirectoryRoots,
            )

            require(partialZip.renameTo(target)) { "Unable to finalize workspace backup" }
            deleteDirectoryIfPresent(payloadDirectory)
            verifyWorkspaceSourcesUnchanged(workspaces, sourceFingerprints)
            verifyArchiveStateUnchanged(
                expectedWorkspaces = workspaces,
                expectedDatabaseCatalog = databaseWorkspaceCatalog,
                manifest = manifest,
                expectedDirectoryRoots = expectedDirectoryRoots,
            )
            finalized = true
            target
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val preserveFinalizedOutput = finalized && currentCoroutineContext().isActive
            withContext(NonCancellable) {
                cleanupArchiveArtifacts(
                    partialZip = partialZip,
                    payloadDirectory = payloadDirectory,
                    target = target,
                    finalized = preserveFinalizedOutput,
                    failure = failure,
                )
            }
        }
    }

    suspend fun recoverPendingIncrementalRoomRollback(workspaceIds: List<String>) {
        if (workspaceIds.isEmpty()) return
        workspaceRepository.withWorkspaceCatalogArchiveLock {
            workspaceIds.distinct().forEach { workspaceId ->
                workspaceRepository.deleteRestoredWorkspace(workspaceId)
            }
            WorkspaceArchiveRecovery.completeIncrementalRoomRollback(context)
        }
    }

    suspend fun restoreBackupFile(
        file: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        password: CharArray? = null,
        allowLegacy: Boolean = false,
        baseFile: File? = null,
    ): WorkspaceArchiveRestoreResult = withBackupTiming(context.filesDir, "RESTORE") {
        workspaceRepository.withEnvironmentArchiveLease {
        withContext(Dispatchers.IO) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead()) {
            "Backup file does not exist or is unreadable"
        }
        if (!ArchiveIncremental.isIncremental(file)) requireRestorableWorkspaceArchive(file, json, MAX_MANIFEST_BYTES)
        val result = workspaceRepository.withWorkspaceCatalogArchiveLock {
            timedBackupPhase(BackupPhase.APPLY_RESTORE) {
                restorePortableArchive(file, includeDatabase, includeFiles, archiveCleanupFile = null, baseFile = baseFile)
            }
        }
        require(!result.restartRequired && !result.databaseRestoreAttempted) {
            "A database restore must not return before relaunch"
        }
        result
        }
    }

    }

    private fun requireWorkspaceCatalogProjectionCompatible(
        liveCatalog: List<WorkspaceCatalogRecord>,
        exportedCatalog: List<WorkspaceCatalogRecord>,
    ) {
        val liveById = liveCatalog.associateBy { it.id }
        val exportedById = exportedCatalog.associateBy { it.id }
        require(liveById.keys == exportedById.keys) {
            "Workspace catalog changed while projecting environment"
        }
        liveById.forEach { (id, live) ->
            val exported = exportedById.getValue(id)
            require(live.copy(shellStatus = exported.shellStatus, runtimeMetadata = exported.runtimeMetadata) == exported) {
                "Workspace catalog projection changed non-environment fields: $id"
            }
        }
    }

    private fun createWorkspacePayloads(
        workspace: WorkspaceEntity,
        payloadDirectory: File,
        budget: WorkspaceArchiveBudget,
    ): WorkspaceArchiveWorkspace {
        require(workspace.root.isValidArchiveComponent()) { "Invalid workspace root: ${workspace.root}" }
        val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
        require(workspaceDir.isDirectory && !Files.isSymbolicLink(workspaceDir.toPath())) {
            "Workspace directory is missing: ${workspace.id}"
        }
        val filesDir = runtimePaths(workspace.root).filesDir
        require(filesDir.isDirectory && !Files.isSymbolicLink(filesDir.toPath())) {
            "Workspace files directory is missing: ${workspace.id}"
        }
        val tarLimits = WorkspaceArchiveTar.Limits(
            maxEntries = MAX_TAR_ENTRIES,
            maxEntryBytes = MAX_PAYLOAD_BYTES,
            maxPayloadBytes = MAX_PAYLOAD_BYTES,
        )
        val filesPayloadFile = File(payloadDirectory, "${workspace.root}-files.tar")
        WorkspaceArchiveTar.create(
            sourceRoot = filesDir,
            output = filesPayloadFile,
            limits = tarLimits,
            onEntry = budget::recordTarEntry,
            onFileBytes = budget::recordWorkspaceBytes,
            onOutputBytes = { bytes ->
                budget.recordTarBytes(bytes)
                budget.recordStagingBytes(bytes, context.cacheDir)
            },
        )
        val homeDir = runtimePaths(workspace.root).homeDir
        val homePayloadFile = File(payloadDirectory, "${workspace.root}-home.tar")
        require(!Files.isSymbolicLink(homeDir.toPath())) {
            "Workspace HOME directory is a symbolic link: ${workspace.id}"
        }
        WorkspaceArchiveTar.create(
            sourceRoot = homeDir,
            output = homePayloadFile,
            limits = tarLimits,
            onEntry = budget::recordTarEntry,
            onFileBytes = budget::recordWorkspaceBytes,
            onOutputBytes = { bytes ->
                budget.recordTarBytes(bytes)
                budget.recordStagingBytes(bytes, context.cacheDir)
            },
        )
        val homePayload = WorkspaceArchivePayload(
            path = "workspaces/${workspace.root}/home.tar",
            length = homePayloadFile.length(),
            sha256 = sha256(homePayloadFile),
        )
        return WorkspaceArchiveWorkspace(
            id = workspace.id,
            name = workspace.name,
            root = workspace.root,
            shellStatus = workspace.shellStatus,
            createdAt = workspace.createdAt,
            updatedAt = workspace.updatedAt,
            lastAccessAt = workspace.lastAccessAt,
            runtimeMetadata = sanitizeRuntimeMetadata(workspace.runtimeMetadataOrDefault()),
            files = WorkspaceArchivePayload(
                path = "workspaces/${workspace.root}/files.tar",
                length = filesPayloadFile.length(),
                sha256 = sha256(filesPayloadFile),
            ),
            home = homePayload,
            toolApprovals = workspace.toolApprovals,
        )
    }

    /**
     * Create a stable main-file snapshot. The checkpoint is deliberately followed by a Room
     * transaction using Room's exclusive transaction path. If a writer got between the two
     * operations, the WAL check aborts and retries instead of archiving a mixed main/WAL pair.
     * WAL/SHM are never copied into a portable archive.
     */
    private fun createDatabaseSnapshot(
        payloadDirectory: File,
        budget: WorkspaceArchiveBudget,
        exportedWorkspaceCatalog: List<WorkspaceCatalogRecord>,
    ): DatabaseSnapshot? {
        val source = context.getDatabasePath("rikka_hub")
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val destination = File(payloadDirectory, "rikka_hub.db")
        val writableDatabase = database.openHelper.writableDatabase
        var conversationReferences = emptyList<WorkspaceConversationReference>()
        var liveWorkspaceCatalog = emptyList<WorkspaceCatalogRecord>()
        retryArchiveSnapshot(MAX_DATABASE_SNAPSHOT_ATTEMPTS, { it is SnapshotRetry }) {
            checkpointWal(writableDatabase)
                database.runInTransaction {
                    val wal = File(source.parentFile, "${source.name}-wal")
                    require(!Files.isSymbolicLink(wal.toPath())) {
                        "Room database WAL is a symbolic link"
                    }
                    if (!walIsEmpty(wal)) throw SnapshotRetry()
                    require(readUserVersion(writableDatabase) == AppDatabase.CURRENT_VERSION) {
                        "Active Room database user_version does not match current schema"
                    }
                    conversationReferences = readWorkspaceReferences(writableDatabase)
                    // The raw catalog is read once, inside the exclusive transaction, so the
                    // copy can be checked against the live rows even though the exported
                    // projection rewrites status and metadata below.
                    liveWorkspaceCatalog = readWorkspaceCatalog(writableDatabase)
                    requireWorkspaceCatalogProjectionCompatible(
                        liveCatalog = liveWorkspaceCatalog,
                        exportedCatalog = exportedWorkspaceCatalog,
                    )
                    copyStableDatabaseFile(source, destination, budget)
                    // Snapshot files live only on this device. Sanitize the isolated copy, never
                    // the Room-owned source database, before it becomes a backup payload. The
                    // copy is also rewritten to the exported environment projection so a restored
                    // database describes the environment it was exported from.
                    removeWorkspaceSnapshotsFromDatabaseCopy(destination, exportedWorkspaceCatalog)
                    require(walIsEmpty(wal)) {
                        "Database WAL changed while creating the exclusive snapshot"
                    }
                }
                // Validate the copied main file, not the live Room connection. The archive never
                // includes the live -wal/-shm sidecars, so the copied file must stand alone.
                validateStagedDatabase(
                    file = destination,
                    expectedVersion = AppDatabase.CURRENT_VERSION,
                    expectedReferences = WorkspaceArchiveReferences(conversations = conversationReferences),
                    expectedWorkspaceCatalog = exportedWorkspaceCatalog,
                    expectedSnapshotsEmpty = true,
                )
        }
        return DatabaseSnapshot(
            source = ArchiveSource(destination, "database/rikka_hub.db"),
            conversationReferences = authoritativeArchiveConversationReferences(conversationReferences),
            liveWorkspaceCatalog = liveWorkspaceCatalog,
            exportedWorkspaceCatalog = exportedWorkspaceCatalog,
            snapshotsExcluded = true,
        )
    }

    private fun checkpointWal(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            require(cursor.moveToFirst()) { "Room WAL checkpoint returned no status" }
            require(cursor.getInt(0) == 0) { "Room WAL checkpoint is busy" }
        }
    }

    private fun walIsEmpty(wal: File): Boolean = !wal.exists() || wal.length() == 0L

    private fun readUserVersion(database: SupportSQLiteDatabase): Int {
        database.query("PRAGMA user_version").use { cursor ->
            require(cursor.moveToFirst()) { "Room user_version is unavailable" }
            return cursor.getInt(0)
        }
    }

    private fun readWorkspaceReferences(database: SupportSQLiteDatabase): List<WorkspaceConversationReference> {
        val references = mutableListOf<WorkspaceConversationReference>()
        database.query("SELECT id, workspace_id FROM conversationentity").use { cursor ->
            while (cursor.moveToNext()) {
                references += WorkspaceConversationReference(
                    conversationId = cursor.getString(0),
                    workspaceId = if (cursor.isNull(1)) null else cursor.getString(1),
                )
            }
        }
        return references
    }

    private fun readWorkspaceCatalog(database: SupportSQLiteDatabase): List<WorkspaceCatalogRecord> {
        val catalog = mutableListOf<WorkspaceCatalogRecord>()
        database.query(
            "SELECT id, name, root, shell_status, created_at, updated_at, " +
                "last_access_at, tool_approvals, runtime_metadata FROM workspaces",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                catalog += WorkspaceCatalogRecord(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    root = cursor.getString(2),
                    shellStatus = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                    updatedAt = cursor.getLong(5),
                    lastAccessAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    toolApprovals = cursor.getString(7),
                    runtimeMetadata = cursor.getString(8),
                )
            }
        }
        return catalog
    }

    private fun copyStableDatabaseFile(
        source: File,
        destination: File,
        budget: WorkspaceArchiveBudget,
    ) {
        deleteIfPresent(destination)
        val expectedLength = source.length()
        require(expectedLength > 0L) { "Room database main file is empty" }
        FileInputStream(source).use { input ->
            BudgetOutputStream(FileOutputStream(destination)) { bytes ->
                budget.recordStagingBytes(bytes, context.cacheDir)
            }.use { output ->
                copyExactly(input, output, expectedLength)
                output.flush()
                output.sync()
            }
        }
        require(destination.length() == expectedLength) {
            "Database snapshot length changed while copying"
        }
        WorkspaceArchiveTar.applyMode(destination, 0x180) // 0600
    }

    /**
     * Remove device-local snapshot metadata from an already stable database copy. The caller must
     * never pass the live Room database path here: this function intentionally opens and mutates
     * only the isolated backup file.
     */
    private fun removeWorkspaceSnapshotsFromDatabaseCopy(
        file: File,
        exportedWorkspaceCatalog: List<WorkspaceCatalogRecord>,
    ) {
        sanitizeWorkspaceDatabaseCopy(
            file = file,
            workspaceProjection = exportedWorkspaceCatalog.map { record ->
                WorkspaceRowProjection(
                    id = record.id,
                    shellStatus = record.shellStatus,
                    runtimeMetadata = record.runtimeMetadata,
                )
            },
        )
    }

    private fun checkpointStandaloneDatabase(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            require(cursor.moveToFirst()) { "SQLite WAL checkpoint returned no status" }
            require(cursor.getInt(0) == 0) { "SQLite WAL checkpoint is busy" }
        }
    }

    private fun cleanupDatabaseSidecars(main: File) {
        listOf(
            File(main.parentFile, "${main.name}-wal"),
            File(main.parentFile, "${main.name}-shm"),
            File(main.parentFile, "${main.name}-journal"),
        ).forEach { sidecar ->
            if (Files.exists(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(sidecar.toPath())) {
                    "Database staging sidecar is a symbolic link: ${sidecar.name}"
                }
                require(sidecar.delete()) { "Unable to clean database staging sidecar: ${sidecar.name}" }
            }
        }
    }

    private suspend fun verifyWorkspaceSourcesUnchanged(
        workspaces: List<WorkspaceEntity>,
        before: Map<String, WorkspaceSourceFingerprint>,
    ) {
        workspaces.forEach { workspace ->
            val expected = before[workspace.id] ?: error("Missing workspace source fingerprint")
            workspaceRepository.withWorkspaceArchiveLock(workspace.id) {
                val after = fingerprintWorkspaceSource(workspace)
                require(expected == after) {
                    "Workspace source changed during backup (detection only; no consistent archive produced): ${workspace.id}"
                }
            }
        }
    }

    /**
     * Best-effort double observation for sources that cannot be frozen, such as a
     * DocumentsProvider-backed tree or a caller holding an open file descriptor. The fingerprint
     * includes deterministic structure, metadata, link targets and regular-file content hashes,
     * so same-size/same-mtime edits are normally detected. It is not a filesystem transaction:
     * a change that is fully reverted before the second observation, provider-side races outside
     * this tree, and writes after the final observation remain outside this bounded guarantee.
     */
    private fun fingerprintWorkspaceSource(workspace: WorkspaceEntity): WorkspaceSourceFingerprint {
        require(workspace.root.isValidArchiveComponent()) { "Invalid workspace root: ${workspace.root}" }
        val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
        return WorkspaceSourceFingerprint(
            entries = fingerprintTree(runtimePaths(workspace.root).filesDir, "files") +
                fingerprintTree(runtimePaths(workspace.root).homeDir, "home"),
        )
    }

    private suspend fun verifyWorkspaceDirectoryCatalog(expected: List<WorkspaceEntity>): Set<String> {
        val actual = workspaceRepository.getAll()
        val expectedCatalog = expected.map(::workspaceCatalogRecord).sorted()
        val actualCatalog = actual.map(::workspaceCatalogRecord).sorted()
        require(actualCatalog == expectedCatalog) {
            "Workspace catalog changed while creating archive"
        }
        val expectedRoots = expected.map { workspace ->
            require(workspace.root.isValidArchiveComponent()) {
                "Invalid workspace root: ${workspace.root}"
            }
            workspace.root
        }
        require(expectedRoots.size == expectedRoots.toSet().size) {
            "Workspace catalog contains duplicate roots"
        }
        val actualRoots = workspaceDirectoryRoots()
        require(actualRoots == expectedRoots.toSet()) {
            "Workspace directory catalog changed while creating archive"
        }
        expected.forEach { workspace ->
            val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
            require(workspaceDir.isDirectory && !Files.isSymbolicLink(workspaceDir.toPath())) {
                "Workspace directory changed while creating archive: ${workspace.id}"
            }
            val filesDir = File(workspaceDir, "files")
            require(filesDir.isDirectory && !Files.isSymbolicLink(filesDir.toPath())) {
                "Workspace files directory changed while creating archive: ${workspace.id}"
            }
            require(!Files.isSymbolicLink(runtimePaths(workspace.root).homeDir.toPath())) {
                "Workspace HOME directory changed while creating archive: ${workspace.id}"
            }
        }
        return actualRoots
    }

    private fun workspaceDirectoryRoots(root: File = File(context.filesDir, "workspaces")): Set<String> {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptySet()
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Workspace root is not a real directory"
        }
        val entries = root.listFiles() ?: error("Unable to list workspace directory catalog")
        return entries.map { entry ->
            require(entry.name.isValidArchiveComponent()) {
                "Invalid workspace directory name: ${entry.name}"
            }
            require(entry.isDirectory && !Files.isSymbolicLink(entry.toPath())) {
                "Unexpected workspace directory catalog entry: ${entry.name}"
            }
            entry.name
        }.toSet()
    }

    private fun verifyWorkspaceArchiveCatalogConsistency(
        databaseWorkspaceCatalog: List<WorkspaceCatalogRecord>,
        manifest: WorkspaceArchiveManifest,
        databaseConversationReferences: List<WorkspaceConversationReference>,
        assistantReferences: List<WorkspaceAssistantReference>,
        actualDirectoryRoots: Set<String>,
    ) {
        require(
            workspaceArchiveCatalogIsConsistent(
                WorkspaceArchiveCatalogConsistency(
                    stagedDatabaseWorkspaces = databaseWorkspaceCatalog.map {
                        WorkspaceArchiveWorkspaceKey(it.id, it.root)
                    },
                    manifestWorkspaces = manifest.workspaces.map {
                        WorkspaceArchiveWorkspaceKey(it.id, it.root)
                    },
                    settingsAssistantReferences = assistantReferences,
                    manifestAssistantReferences = manifest.references.assistants,
                    stagedDatabaseConversationReferences = databaseConversationReferences,
                    manifestConversationReferences = manifest.references.conversations,
                    actualDirectoryRoots = actualDirectoryRoots,
                )
            )
        ) {
            "Workspace catalog, archive references, or directory catalog is inconsistent; refusing backup"
        }
    }

    private suspend fun verifyArchiveStateUnchanged(
        expectedWorkspaces: List<WorkspaceEntity>,
        expectedDatabaseCatalog: List<WorkspaceCatalogRecord>,
        manifest: WorkspaceArchiveManifest,
        expectedDirectoryRoots: Set<String>,
    ) {
        val actualRoots = verifyWorkspaceDirectoryCatalog(expectedWorkspaces)
        require(actualRoots == expectedDirectoryRoots) {
            "Workspace directory catalog changed while creating archive"
        }
        val actualCatalog = database.workspaceDao().getAll().map(::workspaceCatalogRecord).sorted()
        require(actualCatalog == expectedDatabaseCatalog.sorted()) {
            "Workspace catalog changed while creating archive"
        }
        val actualAssistantReferences = workspaceAssistantReferences(settingsStore.readPersistedSettingsFailFast())
        verifySettingsWorkspaceReferences(manifest.references.assistants)
        val actualConversationReferences = authoritativeArchiveConversationReferences(
            conversationDAO.getWorkspaceReferences().map { reference ->
                WorkspaceConversationReference(reference.conversationId, reference.workspaceId)
            }
        )
        require(
            workspaceArchiveCatalogIsConsistent(
                WorkspaceArchiveCatalogConsistency(
                    stagedDatabaseWorkspaces = expectedDatabaseCatalog.map {
                        WorkspaceArchiveWorkspaceKey(it.id, it.root)
                    },
                    manifestWorkspaces = manifest.workspaces.map {
                        WorkspaceArchiveWorkspaceKey(it.id, it.root)
                    },
                    settingsAssistantReferences = actualAssistantReferences,
                    manifestAssistantReferences = manifest.references.assistants,
                    stagedDatabaseConversationReferences = actualConversationReferences,
                    manifestConversationReferences = manifest.references.conversations,
                    actualDirectoryRoots = actualRoots,
                )
            )
        ) {
            "Workspace catalog or references changed while creating archive"
        }
    }

    private fun workspaceAssistantReferences(settings: Settings): List<WorkspaceAssistantReference> =
        settings.assistants.map { assistant ->
            WorkspaceAssistantReference(
                assistantId = assistant.id.toString(),
                workspaceId = assistant.workspaceId?.toString(),
            )
        }

    private suspend fun verifySettingsWorkspaceReferences(
        expected: List<WorkspaceAssistantReference>,
    ) {
        val actualSettingsReferences = workspaceAssistantReferences(settingsStore.readPersistedSettingsFailFast())
        val sort: (WorkspaceAssistantReference) -> String = {
            "${it.assistantId}\u0000${it.workspaceId ?: ""}"
        }
        require(actualSettingsReferences.sortedBy(sort) == expected.sortedBy(sort)) {
            "Assistant workspace references changed while creating archive"
        }
        val workspaceIds = workspaceRepository.getAll().map { it.id }.toSet()
        actualSettingsReferences.forEach { reference ->
            reference.workspaceId?.let { workspaceId ->
                require(workspaceId in workspaceIds) {
                    "Assistant ${reference.assistantId} references a missing workspace: $workspaceId"
                }
            }
        }
    }

    private fun fingerprintTree(root: File, label: String, overrides: Map<String, File> = emptyMap()): List<WorkspaceSourceFingerprintEntry> {
        val result = mutableListOf<WorkspaceSourceFingerprintEntry>()
        fun visit(original: File, relative: String) {
            val path = overrides[relative] ?: original
            val nioPath = path.toPath()
            if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) {
                result += WorkspaceSourceFingerprintEntry(relativePath = relative, type = "missing")
                if (relative.isEmpty()) {
                    overrides.forEach { (name, replacement) -> visit(replacement, name) }
                }
                return
            }
            val attributes = Files.readAttributes(
                nioPath,
                BasicFileAttributes::class.java,
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
            } else {
                null
            }
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
                Files.list(nioPath).use { stream -> stream.forEach { children.add(it.toFile()) } }
                if (relative.isEmpty()) {
                    overrides.forEach { (name, replacement) ->
                        if (children.none { it.name == name }) children.add(File(path, name))
                    }
                }
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

    private fun collectLegacyFiles(): List<ArchiveSource> {
        val result = mutableListOf<ArchiveSource>()
        val upload = File(context.filesDir, FileFolders.UPLOAD)
        upload.listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            result += ArchiveSource(file, "${FileFolders.UPLOAD}/${file.name}")
        }
        val skills = File(context.filesDir, FileFolders.SKILLS)
        collectDirectoryFiles(skills, skills, FileFolders.SKILLS, result)
        val fonts = File(context.filesDir, FileFolders.FONTS)
        fonts.listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
            result += ArchiveSource(file, "${FileFolders.FONTS}/${file.name}")
        }
        return result
    }

    private fun collectDirectoryFiles(
        root: File,
        current: File,
        prefix: String,
        result: MutableList<ArchiveSource>,
    ) {
        if (!current.isDirectory || Files.isSymbolicLink(current.toPath())) return
        current.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                collectDirectoryFiles(root, file, prefix, result)
            } else if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                result += ArchiveSource(file, "$prefix/${file.relativeTo(root).invariantSeparatorsPath}")
            }
        }
    }

    /** Inspect the outer envelope without opening a legacy ZIP or decrypting plaintext. */
    fun inspectBackupFile(file: File): WorkspaceArchiveInspection {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead()) {
            "Backup file does not exist or is unreadable"
        }
        return WorkspaceArchiveEncryption.inspect(file)
    }

    /** Public verification entry used by transports and tamper tests before any restore. */
    fun verifyBackupFile(
        file: File,
        password: CharArray? = null,
        allowLegacy: Boolean = false,
        baseFile: File? = null,
    ) {
        if (!ArchiveIncremental.isIncremental(file)) requireCurrentArchive(file)
        verifyPortableArchive(file, baseFile)
    }

    private fun requireCurrentArchive(file: File) =
        requireCurrentWorkspaceArchive(file, json, MAX_MANIFEST_BYTES)

    private fun verifyPortableArchive(file: File, baseFile: File? = null) {
        val budget = WorkspaceArchiveBudget()
        val verificationRoot = File(context.cacheDir, ".workspace-verify-${UUID.randomUUID()}")
        var failure: Throwable? = null
        try {
            budget.checkAvailableSpace(context.cacheDir, BUFFER_SIZE.toLong())
            require(verificationRoot.mkdirs() || verificationRoot.isDirectory) {
                "Unable to create archive verification staging"
            }
            var manifest: WorkspaceArchiveManifest? = null
            var settings: Settings? = null
            var stagedDatabase: File? = null
            openPortableInput(file, baseFile, budget).use { zipIn ->
                val first = zipIn.nextEntry ?: error("Workspace archive is empty")
                if (zipIn !is BudgetedArchiveInput) budget.recordZipEntry()
                val firstName = WorkspaceArchivePathSafety.normalize(first.name)
                require(firstName == "manifest.json" && !first.isDirectory) {
                    "Portable workspace manifest must be the first ZIP entry"
                }
                manifest = decodePortableManifest(readCurrentEntry(first, zipIn, MAX_MANIFEST_BYTES, budget))
                val validatedManifest = manifest!!
                val expected = expectedPortableEntries(validatedManifest)
                val seen = HashSet<String>()
                seen += "manifest.json"
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    if (zipIn !is BudgetedArchiveInput) budget.recordZipEntry()
                    val name = WorkspaceArchivePathSafety.normalize(entry.name)
                    require(seen.add(name)) { "Duplicate ZIP entry: $name" }
                    require(name in expected && !entry.isDirectory) { "Unexpected ZIP entry: $name" }
                    when {
                        name in validatedManifest.workspacePayloadPaths() -> {
                            val descriptor = validatedManifest.payloadByPath(name)
                            val digest = WorkspaceArchiveTarValidator.validate(
                                zipIn,
                                limits = WorkspaceArchiveTar.Limits(MAX_TAR_ENTRIES, MAX_PAYLOAD_BYTES, MAX_PAYLOAD_BYTES),
                                maxArchiveBytes = MAX_PAYLOAD_BYTES,
                                onEntry = budget::recordTarEntry,
                                onFileBytes = budget::recordWorkspaceBytes,
                                onReadBytes = { bytes ->
                                    budget.recordTarBytes(bytes)
                                    if (zipIn !is BudgetedArchiveInput) budget.recordZipDecompressedBytes(bytes)
                                },
                            )
                            require(digest.length == descriptor.length) { "Payload length mismatch: $name" }
                            require(digest.sha256 == descriptor.sha256.lowercase()) { "Payload hash mismatch: $name" }
                        }

                        name in validatedManifest.databaseEntries -> {
                            val target = WorkspaceArchivePathSafety.resolve(verificationRoot, name)
                            val digest = copyCurrentEntryToFile(
                                zipIn,
                                target,
                                MAX_DATABASE_ENTRY_BYTES,
                                budget,
                                stagingRoot = verificationRoot,
                            )
                            requirePayloadDigest(
                                name = name,
                                digest = digest,
                                descriptor = validatedManifest.databasePayloadFor(name),
                            )
                            stagedDatabase = target
                        }

                        name == WorkspaceArchiveManifest.SETTINGS_PATH -> {
                            settings = decodeArchiveSettings(
                                readCurrentEntry(entry, zipIn, MAX_SETTINGS_BYTES, budget)
                                    .toString(Charsets.UTF_8)
                            )
                        }

                        else -> copyCurrentEntry(zipIn, NullOutputStream, MAX_NON_PAYLOAD_BYTES, budget)
                    }
                    zipIn.closeEntry()
                }
                require(seen.containsAll(expected)) { "Portable workspace archive is truncated" }
            }
            val validatedManifest = manifest ?: error("Portable workspace manifest is missing")
            val validatedSettings = settings ?: error("Portable workspace settings are missing")
            validateManifestSettingsReferences(validatedManifest, validatedSettings)
            stagedDatabase?.let {
                validateStagedDatabase(
                    file = it,
                    expectedVersion = validatedManifest.databaseVersion,
                    expectedReferences = validatedManifest.references,
                    expectedWorkspaceKeys = validatedManifest.workspaces.map { workspace ->
                        WorkspaceArchiveWorkspaceKey(workspace.id, workspace.root)
                    },
                    expectedSnapshotsEmpty = validatedManifest.snapshotsExcluded,
                )
                upgradePortableDatabaseIfNeeded(it, validatedManifest.databaseVersion, verificationRoot, budget)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            cleanupDirectory(verificationRoot, failure)
        }
    }

    private fun upgradePortableDatabaseIfNeeded(
        source: File,
        sourceVersion: Int,
        stagingRoot: File,
        budget: WorkspaceArchiveBudget,
    ): File {
        if (sourceVersion == AppDatabase.CURRENT_VERSION) return source
        require(sourceVersion == 31) { "Unsupported portable database schema $sourceVersion" }
        val directory = File(stagingRoot, "portable-upgraded")
        require(directory.mkdirs() || directory.isDirectory) { "Unable to create portable database staging" }
        val target = File(directory, "rikka_hub")
        copyStagedDatabaseFile(source, target, budget, stagingRoot)
        val stagedRoom = openStagedRoomDatabase(target)
        try {
            stagedRoom.openHelper.writableDatabase
        } finally {
            stagedRoom.close()
        }
        checkpointLegacyDatabase(target)
        cleanupDatabaseSidecars(target)
        validateStagedDatabase(target, expectedVersion = AppDatabase.CURRENT_VERSION)
        return target
    }

    private fun copyStagedDatabaseFile(
        source: File,
        target: File,
        budget: WorkspaceArchiveBudget,
        stagingRoot: File,
    ) {
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Legacy database staging source is not a regular file"
        }
        require(!Files.isSymbolicLink(target.toPath())) { "Legacy database staging target is a symbolic link" }
        deleteIfPresent(target)
        FileInputStream(source).use { input ->
            BudgetOutputStream(FileOutputStream(target)) { bytes ->
                budget.recordStagingBytes(bytes, stagingRoot)
            }.use { output ->
                copyExactly(input, output, source.length())
                output.flush()
                output.sync()
            }
        }
        WorkspaceArchiveTar.applyMode(target, 0x180)
        require(target.length() == source.length()) { "Legacy database staging copy was truncated" }
    }

    private fun checkpointLegacyDatabase(main: File) {
        // SQLite itself decides whether the sidecars belong to this main file. Do not gate on the
        // persisted journal_mode pragma: legacy Android backups can carry a copied WAL/SHM pair
        // whose pragma is still reported as DELETE until SQLite has attached the sidecars.
        val database = SQLiteDatabase.openDatabase(
            main.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
        try {
            checkpointStandaloneDatabase(database)
        } finally {
            database.close()
        }
        cleanupDatabaseSidecars(main)
    }

    private fun readStandaloneUserVersion(main: File): Int {
        val database = SQLiteDatabase.openDatabase(
            main.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                require(cursor.moveToFirst()) { "Legacy database user_version is unavailable" }
                cursor.getInt(0)
            }
        } finally {
            database.close()
        }
    }

    private fun checkpointSupportDatabase(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            require(cursor.moveToFirst()) { "Staged Room WAL checkpoint returned no status" }
            require(cursor.getInt(0) == 0) { "Staged Room WAL checkpoint is busy" }
        }
    }

    private fun openStagedRoomDatabase(file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*LEGACY_DATABASE_MIGRATIONS)
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                        RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                            options.customExtensions.add(
                                SQLiteCustomExtension(
                                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                                    null,
                                )
                            )
                            options
                        }
                    )
                )
            )
            .build()

    /**
     * Recreate the content-bearing FTS5 table from migrated message_node rows.
     *
     * A legacy database can contain the pre-title/update_at FTS5 definition. Do not blindly drop
     * every sqlite_master row in reverse order: FTS5 must destroy the virtual table before its
     * shadow tables. Dropping the shadows first makes xDestroy fail with "vtable constructor
     * failed" on the Android SQLite build used by the app.
     */
    private fun rebuildMessageFts(database: SupportSQLiteDatabase) {
        val objects = readMessageFtsObjects(database)
        val main = objects.singleOrNull { it.name == MESSAGE_FTS_TABLE }
        val shadowObjects = objects.filter { it.name != MESSAGE_FTS_TABLE }
        val unexpectedObjects = shadowObjects.filter { it.name !in FTS_SHADOW_NAMES }
        require(unexpectedObjects.isEmpty()) {
            "Unsupported message FTS objects: ${unexpectedObjects.joinToString { it.name }}"
        }
        require(shadowObjects.all { it.type.equals("table", ignoreCase = true) }) {
            "Unsupported message FTS shadow object type"
        }
        val completeShadowLayout = shadowObjects.map { it.name }.toSet() == FTS_SHADOW_NAMES

        val rebuildInPlace = when {
            main == null -> {
                require(shadowObjects.isEmpty()) {
                    "message_fts shadow tables exist without the virtual table"
                }
                false
            }

            !main.type.equals("table", ignoreCase = true) -> {
                error("message_fts is not an FTS5 virtual table")
            }

            roomSchema31MessageFtsSqlIsExact(main.sql) -> {
                require(completeShadowLayout) {
                    "message_fts has an incomplete FTS5 shadow layout"
                }
                true
            }

            isSupportedLegacyMessageFts5(database, main.sql) -> {
                require(completeShadowLayout) {
                    "Legacy message_fts has an incomplete FTS5 shadow layout"
                }
                false
            }

            else -> error("Unsupported or corrupt message_fts FTS5 definition")
        }

        database.beginTransaction()
        try {
            if (rebuildInPlace) {
                // This only touches FTS5's derived content/index. It never deletes message_node
                // or ConversationEntity rows. The rebuild command also validates that this
                // SQLite build can attach the current virtual table before its content is cleared.
                database.execSQL("INSERT INTO message_fts(message_fts) VALUES ('rebuild')")
                database.execSQL("DELETE FROM message_fts")
            } else {
                if (main != null) dropMessageFtsObjects(database, objects)
                database.execSQL(CREATE_MESSAGE_FTS_SQL)
            }
            insertMessageFtsRows(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readMessageFtsObjects(database: SupportSQLiteDatabase): List<MessageFtsSchemaObject> {
        val objects = mutableListOf<MessageFtsSchemaObject>()
        database.query(
            "SELECT DISTINCT type, name, sql FROM sqlite_master " +
                "WHERE name = '$MESSAGE_FTS_TABLE' OR name LIKE '${MESSAGE_FTS_TABLE}_%' " +
                "OR tbl_name = '$MESSAGE_FTS_TABLE' " +
                "OR (type = 'trigger' AND lower(COALESCE(sql, '')) LIKE '%message_fts%')",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                objects += MessageFtsSchemaObject(
                    type = cursor.getString(0),
                    name = cursor.getString(1),
                    sql = if (cursor.isNull(2)) null else cursor.getString(2),
                )
            }
        }
        return objects
    }

    private fun isSupportedLegacyMessageFts5(
        database: SupportSQLiteDatabase,
        sql: String?,
    ): Boolean {
        val normalizedSql = sql
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[\\s`\"]"), "")
            ?.replace("ifnotexists", "")
            ?.removeSuffix(";")
        if (normalizedSql != LEGACY_MESSAGE_FTS_SQL) return false

        val columns = mutableListOf<String>()
        database.query("PRAGMA table_info('$MESSAGE_FTS_TABLE')").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        return columns == LEGACY_MESSAGE_FTS_COLUMNS
    }

    private fun dropMessageFtsObjects(
        database: SupportSQLiteDatabase,
        objects: List<MessageFtsSchemaObject>,
    ) {
        val main = objects.singleOrNull { it.name == MESSAGE_FTS_TABLE }
        require(main?.type.equals("table", ignoreCase = true)) {
            "Refusing to drop an unverified message_fts object"
        }
        // Drop the virtual table first. FTS5 owns its shadow tables and xDestroy expects them to
        // still exist; the old reverse-order loop caused the reported vtable constructor failure.
        database.execSQL("DROP TABLE IF EXISTS `$MESSAGE_FTS_TABLE`")
        objects.asSequence()
            .filter { it.name != MESSAGE_FTS_TABLE }
            .forEach { objectInfo ->
                require(objectInfo.name in FTS_SHADOW_NAMES)
                require(objectInfo.type.equals("table", ignoreCase = true))
                database.execSQL("DROP TABLE IF EXISTS `${objectInfo.name}`")
            }
    }

    private fun insertMessageFtsRows(database: SupportSQLiteDatabase) {
        val insert = database.compileStatement(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        )
        try {
            database.query(
                "SELECT message_node.id, message_node.conversation_id, message_node.messages, " +
                    "conversationentity.title, conversationentity.update_at " +
                    "FROM message_node " +
                    "INNER JOIN conversationentity ON conversationentity.id = message_node.conversation_id " +
                    "ORDER BY message_node.conversation_id, message_node.node_index",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val nodeId = cursor.getString(0)
                    val conversationId = cursor.getString(1)
                    val messagesJson = cursor.getString(2)
                    val title = cursor.getString(3)
                    val updateAt = cursor.getLong(4)
                    val messages = runCatching {
                        JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                    }.getOrElse { error ->
                        throw IllegalArgumentException(
                            "Unable to rebuild message_fts for node $nodeId",
                            error,
                        )
                    }
                    messages.forEach { message ->
                        val text = message.parts
                            .filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\\n") { it.text }
                            .take(10_000)
                        if (text.isBlank()) return@forEach
                        insert.clearBindings()
                        insert.bindString(1, text)
                        insert.bindString(2, nodeId)
                        insert.bindString(3, message.id.toString())
                        insert.bindString(4, conversationId)
                        insert.bindString(5, title)
                        insert.bindLong(6, updateAt)
                        insert.executeInsert()
                    }
                }
            }
        } finally {
            insert.close()
        }
    }

    private suspend fun restorePortableArchive(
        file: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        archiveCleanupFile: File? = null,
        baseFile: File? = null,
    ): WorkspaceArchiveRestoreResult {
        val staging = File(context.filesDir, ".workspace-restore-${UUID.randomUUID()}.partial")
        val budget = WorkspaceArchiveBudget()
        budget.checkAvailableSpace(context.filesDir, BUFFER_SIZE.toLong())
        require(!staging.exists()) { "Restore staging already exists" }
        require(staging.mkdirs() || staging.isDirectory) { "Unable to create restore staging" }
        var failure: Throwable? = null
        var restoreResult: WorkspaceArchiveRestoreResult? = null
        try {
            val prepared = timedBackupPhase(BackupPhase.ZIP_EXTRACT) {
                extractPortableArchive(file, staging, includeDatabase, budget, baseFile)
            }
            val warnings = mutableListOf<String>()
            val preparedWorkspaces = timedBackupPhase(BackupPhase.WORKSPACE_EXTRACT) {
                prepareWorkspaceStaging(prepared, staging, warnings, budget)
            }
            if (prepared.manifest.legacyFilesIncluded == true) {
                listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS).forEach { folder ->
                    WorkspaceArchivePathSafety.ensureDirectory(File(prepared.legacyDirectory, folder))
                }
            }
            val restoredSettings = prepared.settingsFile.let { settingsFile ->
                decodeArchiveSettings(settingsFile.readText(Charsets.UTF_8))
            }
            validateManifestSettingsReferences(prepared.manifest, restoredSettings)
            val currentWorkspaces = workspaceRepository.getAll()
            val currentIds = currentWorkspaces.map { it.id }.toSet()
            val restoreIds = preparedWorkspaces.map { it.manifest.id }.toSet()
            val databaseRestoreRequested = includeDatabase &&
                prepared.databaseFiles.containsKey("database/rikka_hub.db")
            val conflictingIds = workspaceRestoreConflictIds(
                currentIds = currentIds,
                restoreIds = restoreIds,
                fullDatabaseRestore = databaseRestoreRequested,
            )
            require(conflictingIds.isEmpty()) {
                "Workspace ID already exists; delete the existing workspace before restoring: " +
                    conflictingIds.sorted().joinToString(", ")
            }
            val workspacesRoot = File(context.filesDir, "workspaces")
            require(!Files.isSymbolicLink(workspacesRoot.toPath())) { "Workspace root is a symbolic link" }
            if (!databaseRestoreRequested) {
                require(workspacesRoot.mkdirs() || workspacesRoot.isDirectory) {
                    "Unable to create workspace root"
                }
            }
            preparedWorkspaces.forEach { candidate ->
                require(candidate.manifest.root.isValidArchiveComponent()) { "Invalid workspace root" }
                if (!databaseRestoreRequested) {
                    val target = File(workspacesRoot, candidate.manifest.root)
                    require(!Files.isSymbolicLink(target.toPath())) {
                        "Workspace directory is a symbolic link: ${candidate.manifest.id}"
                    }
                    require(!target.exists()) {
                        "Workspace directory already exists; refusing to merge: ${candidate.manifest.id}"
                    }
                }
            }

            // A full database restore replaces the physical root, so lock current-only IDs too;
            // otherwise an old workspace could still be using its root while the whole tree moves.
            val workspaceIds = (currentWorkspaces.map { it.id } + preparedWorkspaces.map { it.manifest.id })
                .distinct()
                .sorted()
            val replacementSettings = restoredSettings
            val fullRestoreAssistantIds = if (databaseRestoreRequested) {
                replacementSettings.assistants.map { it.id.toString() } +
                    settingsStore.readPersistedSettingsFailFast().assistants.map { it.id.toString() }
            } else {
                emptyList()
            }
            val result = bindingCoordinator.quiesceForSettingsReplacementWhileLocked(
                replacement = replacementSettings,
                additionalAssistantIds = fullRestoreAssistantIds,
            ) { _ ->
                settingsStore.withAuthoritativeRestoreTransaction { currentSettings ->
                    withWorkspaceLocks(workspaceIds) {
                        applyPortableRestore(
                            prepared = prepared,
                            preparedWorkspaces = preparedWorkspaces,
                            restoredSettings = restoredSettings,
                            currentSettings = currentSettings,
                            settingsTransaction = this@withAuthoritativeRestoreTransaction,
                        warnings = warnings,
                        includeDatabase = includeDatabase,
                        includeFiles = includeFiles,
                        workspacesRoot = workspacesRoot,
                        archiveCleanupFile = archiveCleanupFile,
                    )
                }
            }
            }
            restoreResult = result
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                cleanupRestoreStaging(staging, failure, restoreResult)
            }
        }
    }

    private fun extractPortableArchive(
        file: File,
        staging: File,
        includeDatabase: Boolean,
        budget: WorkspaceArchiveBudget,
        baseFile: File?,
    ): PreparedPortableArchive {
        openPortableInput(file, baseFile, budget, allowLegacyRestore = true).use { zipIn ->
            val first = zipIn.nextEntry ?: error("Workspace archive is empty")
            if (zipIn !is BudgetedArchiveInput) budget.recordZipEntry()
            require(WorkspaceArchivePathSafety.normalize(first.name) == "manifest.json" && !first.isDirectory) {
                "Portable workspace manifest must be the first ZIP entry"
            }
            val restored = decodeWorkspaceArchiveForRestore(
                readCurrentEntry(first, zipIn, MAX_MANIFEST_BYTES, budget).toString(Charsets.UTF_8), json,
            )
            val manifest = restored.portable
            val expected = expectedPortableEntries(manifest) + restored.ignoredLinuxPayloads.keys
            val seen = HashSet<String>()
            seen += "manifest.json"
            val settingsFile = File(staging, WorkspaceArchiveManifest.SETTINGS_PATH)
            val databaseDirectory = File(staging, "database")
            val payloadDirectory = File(staging, "payloads")
            val legacyDirectory = File(staging, "legacy")
            val databaseFiles = mutableMapOf<String, File>()
            val payloadFiles = mutableMapOf<String, File>()
            var entryCount = 1
            while (true) {
                val entry = zipIn.nextEntry ?: break
                entryCount++
                if (zipIn !is BudgetedArchiveInput) budget.recordZipEntry()
                require(entryCount <= MAX_ZIP_ENTRIES) { "Too many ZIP entries" }
                val name = WorkspaceArchivePathSafety.normalize(entry.name)
                require(seen.add(name)) { "Duplicate ZIP entry: $name" }
                require(name in expected && !entry.isDirectory) { "Unexpected ZIP entry: $name" }
                when {
                    name == WorkspaceArchiveManifest.SETTINGS_PATH -> {
                        copyCurrentEntryToFile(
                            zipIn,
                            settingsFile,
                            MAX_SETTINGS_BYTES,
                            budget,
                            stagingRoot = staging,
                        )
                    }

                    name in manifest.databaseEntries -> {
                        if (includeDatabase) {
                            val target = WorkspaceArchivePathSafety.resolve(databaseDirectory, name.substringAfter('/'))
                            val digest = copyCurrentEntryToFile(
                                zipIn,
                                target,
                                MAX_DATABASE_ENTRY_BYTES,
                                budget,
                                stagingRoot = staging,
                            )
                            requirePayloadDigest(
                                name = name,
                                digest = digest,
                                descriptor = manifest.databasePayloadFor(name),
                            )
                            databaseFiles[name] = target
                        } else {
                            val digest = copyCurrentEntry(
                                zipIn,
                                NullOutputStream,
                                MAX_DATABASE_ENTRY_BYTES,
                                budget,
                            )
                            requirePayloadDigest(
                                name = name,
                                digest = digest,
                                descriptor = manifest.databasePayloadFor(name),
                            )
                        }
                    }

                    name in restored.ignoredLinuxPayloads -> {
                        val payload = restored.ignoredLinuxPayloads.getValue(name)
                        val digest = copyCurrentEntry(zipIn, NullOutputStream, MAX_PAYLOAD_BYTES, budget)
                        require(digest.length == payload.length) { "Payload length mismatch: $name" }
                        require(digest.sha256 == payload.sha256) { "Payload hash mismatch: $name" }
                    }

                    name in manifest.workspacePayloadPaths() -> {
                        val payload = manifest.payloadByPath(name)
                        val target = File(payloadDirectory, payloadFileName(payload))
                        val digest = copyCurrentEntryToFile(
                            zipIn,
                            target,
                            MAX_PAYLOAD_BYTES,
                            budget,
                            stagingRoot = staging,
                        )
                        require(digest.length == payload.length) { "Payload length mismatch: $name" }
                        require(digest.sha256 == payload.sha256.lowercase()) { "Payload hash mismatch: $name" }
                        payloadFiles[name] = target
                    }

                    else -> {
                        val target = WorkspaceArchivePathSafety.resolve(legacyDirectory, name)
                        copyCurrentEntryToFile(
                            zipIn,
                            target,
                            MAX_NON_PAYLOAD_BYTES,
                            budget,
                            stagingRoot = staging,
                        )
                    }
                }
                zipIn.closeEntry()
            }
            require(seen.containsAll(expected)) { "Portable workspace archive is truncated" }
            require(settingsFile.isFile) { "Portable workspace settings are missing" }
            budget.checkStagingDirectory(staging)
            databaseFiles["database/rikka_hub.db"]?.let { stagedDatabase ->
                validateStagedDatabase(
                    file = stagedDatabase,
                    expectedVersion = manifest.databaseVersion,
                    expectedReferences = manifest.references,
                    expectedWorkspaceKeys = manifest.workspaces.map { workspace ->
                        WorkspaceArchiveWorkspaceKey(workspace.id, workspace.root)
                    },
                    expectedSnapshotsEmpty = manifest.snapshotsExcluded,
                )
                val upgradedDatabase = upgradePortableDatabaseIfNeeded(stagedDatabase, manifest.databaseVersion, staging, budget)
                databaseFiles["database/rikka_hub.db"] = upgradedDatabase
            }
            val preparedManifest = if (databaseFiles.isNotEmpty()) manifest.copy(
                databaseVersion = AppDatabase.CURRENT_VERSION,
                databasePayloads = databaseFiles.map { (path, file) ->
                    WorkspaceArchivePayload(path, file.length(), sha256(file))
                },
            ) else manifest
            return PreparedPortableArchive(
                manifest = preparedManifest,
                settingsFile = settingsFile,
                databaseFiles = databaseFiles,
                payloadFiles = payloadFiles,
                legacyDirectory = legacyDirectory,
                staging = staging,
            )
        }
    }

    private fun prepareWorkspaceStaging(
        prepared: PreparedPortableArchive,
        staging: File,
        warnings: MutableList<String>,
        budget: WorkspaceArchiveBudget,
    ): List<PreparedWorkspace> {
        val result = mutableListOf<PreparedWorkspace>()
        val workspaceStagingRoot = File(staging, "workspaces")
        require(workspaceStagingRoot.mkdirs() || workspaceStagingRoot.isDirectory) {
            "Unable to create workspace restore staging root"
        }
        prepared.manifest.workspaces.forEach { manifestWorkspace ->
            val workspaceRoot = File(workspaceStagingRoot, manifestWorkspace.root)
            val filesRoot = File(workspaceRoot, "files")
            val filesPayload = prepared.payloadFiles[manifestWorkspace.files.path]
                ?: error("Missing files payload: ${manifestWorkspace.id}")
            budget.recordTarBytes(filesPayload.length())
            WorkspaceArchiveTar.extract(
                input = filesPayload,
                targetRoot = filesRoot,
                limits = WorkspaceArchiveTar.Limits(
                    maxEntries = MAX_TAR_ENTRIES,
                    maxEntryBytes = MAX_PAYLOAD_BYTES,
                    maxPayloadBytes = MAX_PAYLOAD_BYTES,
                ),
                onEntry = budget::recordTarEntry,
                onFileBytes = { bytes ->
                    budget.recordWorkspaceBytes(bytes)
                    budget.recordStagingBytes(bytes, staging)
                },
            )
            budget.checkStagingDirectory(staging)

            val homePayload = manifestWorkspace.home
            val homePayloadFile = prepared.payloadFiles[homePayload.path]
                ?: error("Missing HOME payload: ${manifestWorkspace.id}")
            budget.recordTarBytes(homePayloadFile.length())
            WorkspaceArchiveTar.extract(
                input = homePayloadFile,
                targetRoot = File(workspaceRoot, "home"),
                limits = WorkspaceArchiveTar.Limits(
                    maxEntries = MAX_TAR_ENTRIES,
                    maxEntryBytes = MAX_PAYLOAD_BYTES,
                    maxPayloadBytes = MAX_PAYLOAD_BYTES,
                ),
                onEntry = budget::recordTarEntry,
                onFileBytes = { bytes ->
                    budget.recordWorkspaceBytes(bytes)
                    budget.recordStagingBytes(bytes, staging)
                },
            )
            val paths = me.rerere.workspace.WorkspaceManager(
                workspaceStagingRoot, portableRuntime = true,
            ).runtimePaths(manifestWorkspace.root)
            budget.checkStagingDirectory(staging)
            result += PreparedWorkspace(manifestWorkspace, workspaceRoot, paths.homeDir)
        }
        return result
    }

    private fun verifyPreparedWorkspaceStaging(
        preparedWorkspaces: List<PreparedWorkspace>,
        stagingRoot: File,
    ) {
        val expectedRoots = preparedWorkspaces.map { candidate ->
            require(candidate.manifest.root.isValidArchiveComponent()) {
                "Invalid prepared workspace root: ${candidate.manifest.root}"
            }
            candidate.manifest.root
        }
        require(expectedRoots.size == expectedRoots.toSet().size) {
            "Prepared workspace staging contains duplicate roots"
        }
        require(workspaceDirectoryRoots(stagingRoot) == expectedRoots.toSet()) {
            "Prepared workspace staging catalog does not match the archive"
        }
        preparedWorkspaces.forEach { candidate ->
            val workspaceRoot = File(stagingRoot, candidate.manifest.root)
            val expectedChildren = setOf("files", "home", "tmp", "runtime")
            val actualChildren = workspaceRoot.listFiles().orEmpty().map { child ->
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Prepared workspace staging contains a symbolic link: ${child.path}"
                }
                child.name
            }.toSet()
            require(actualChildren == expectedChildren) {
                "Prepared workspace staging contains unexpected files: ${workspaceRoot.path}"
            }
            require(File(workspaceRoot, "files").isDirectory) {
                "Prepared workspace files directory is missing: ${candidate.manifest.id}"
            }
            require(candidate.homeRoot.isDirectory) { "Prepared workspace HOME directory is missing" }
            require(File(workspaceRoot, "runtime").readText() == "portable-v1") {
                "Prepared workspace runtime marker is invalid"
            }
        }
    }

    private suspend fun applyPortableRestore(
        prepared: PreparedPortableArchive,
        preparedWorkspaces: List<PreparedWorkspace>,
        restoredSettings: Settings?,
        currentSettings: Settings,
        settingsTransaction: SettingsStore.AuthoritativeRestoreTransaction,
        warnings: MutableList<String>,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        workspacesRoot: File,
        archiveCleanupFile: File?,
    ): WorkspaceArchiveRestoreResult {
        val movedWorkspaces = mutableListOf<MovedPath>()
        val insertedIds = mutableListOf<String>()
        val legacyMoves = mutableListOf<MovedPath>()
        val recoveryRoot = File(context.filesDir, ".workspace-restore-recovery-${UUID.randomUUID()}")
        val databaseRestoreRequested = includeDatabase &&
            prepared.databaseFiles.containsKey("database/rikka_hub.db")
        val databaseRecoveryDirectory = File(context.filesDir, ".database-recovery-${UUID.randomUUID()}")
        var databaseCloseAttempted = false
        var settingsApplied = false
        var cleanupMarker: File? = null
        var recoveryJournal: WorkspaceArchiveRecoveryJournal? = null
        try {
            // Every expensive and suspendable operation happens while Room is still usable. This
            // includes the complete physical-root exchange, legacy folders and DataStore update.
            require(recoveryRoot.mkdirs() || recoveryRoot.isDirectory) {
                "Unable to create workspace restore recovery directory"
            }
            if (databaseRestoreRequested) {
                require(databaseRecoveryDirectory.mkdirs() || databaseRecoveryDirectory.isDirectory) {
                    "Unable to create database recovery directory"
                }
            }
            val incrementalDirectories = if (databaseRestoreRequested) {
                emptyList()
            } else {
                preparedWorkspaces.map { candidate ->
                    WorkspaceArchiveDirectoryRecovery(
                        newRoot = File(workspacesRoot, candidate.manifest.root).absolutePath,
                        oldRootRecovery = File(recoveryRoot, candidate.manifest.root).absolutePath,
                        oldRootExisted = false,
                    )
                }
            }
            recoveryJournal = beginRecoveryJournal(
                currentSettings = currentSettings,
                recoveryRoot = recoveryRoot,
                databaseRecoveryDirectory = databaseRecoveryDirectory.takeIf { databaseRestoreRequested },
                workspacesRoot = workspacesRoot.takeIf { databaseRestoreRequested },
                incrementalDirectories = incrementalDirectories,
                incrementalWorkspaceIds = if (databaseRestoreRequested) {
                    emptyList()
                } else {
                    preparedWorkspaces.map { it.manifest.id }
                },
                legacyStaging = prepared.legacyDirectory,
                includeFiles = includeFiles,
                cleanupArtifacts = listOf(
                    prepared.staging,
                    recoveryRoot,
                ) + listOfNotNull(
                    databaseRecoveryDirectory.takeIf { databaseRestoreRequested },
                    archiveCleanupFile,
                ),
            )
            if (databaseRestoreRequested) {
                val stagedDatabase = prepared.databaseFiles["database/rikka_hub.db"]
                    ?: error("Requested database restore has no staged main database")
                validateDatabasePayload(
                    file = stagedDatabase,
                    descriptor = prepared.manifest.databasePayloadFor("database/rikka_hub.db"),
                )
                sanitizeWorkspaceDatabaseCopy(
                    file = stagedDatabase,
                    workspaceProjection = preparedWorkspaces.map { candidate ->
                        candidate.toEntity(json).let { entity ->
                            WorkspaceRowProjection(
                                id = entity.id,
                                shellStatus = entity.shellStatus,
                                runtimeMetadata = entity.runtimeMetadata,
                            )
                        }
                    },
                    removeSnapshots = false,
                )
                validateStagedDatabase(
                    file = stagedDatabase,
                    expectedVersion = AppDatabase.CURRENT_VERSION,
                    expectedReferences = prepared.manifest.references,
                    expectedWorkspaceKeys = prepared.manifest.workspaces.map {
                        WorkspaceArchiveWorkspaceKey(it.id, it.root)
                    },
                    expectedSnapshotsEmpty = prepared.manifest.snapshotsExcluded,
                )
                verifyPreparedWorkspaceStaging(preparedWorkspaces, File(prepared.staging, "workspaces"))

                // A database payload is a catalog replacement, not a merge. Move the old root as
                // one unit so archive-outside workspaces and same-ID old roots disappear together;
                // the same unit restores the complete old tree if any pre-close step fails.
                val stagedRoot = File(prepared.staging, "workspaces")
                val recovery = File(recoveryRoot, "workspaces-root")
                WorkspaceArchiveDirectoryTransaction(
                    staging = stagedRoot,
                    target = workspacesRoot,
                    recovery = recovery,
                ).activate()
                movedWorkspaces += MovedPath(
                    stagedRoot,
                    workspacesRoot,
                    recovery.takeIf { it.exists() },
                )
                require(workspaceDirectoryRoots(workspacesRoot) == preparedWorkspaces.map { it.manifest.root }.toSet()) {
                    "Activated workspace directory catalog does not match the restored database"
                }
                recoveryJournal?.let { journal ->
                    val updated = journal.copy(
                        phase = WorkspaceArchiveRecoveryPhase.WORKSPACES_ACTIVATED,
                    )
                    WorkspaceArchiveRecovery.writeJournal(
                        WorkspaceArchiveRecovery.journalFile(context),
                        updated,
                    )
                    recoveryJournal = updated
                }
            } else {
                // Incremental files-only restore deliberately retains the historical per-root
                // merge behavior and its ID-conflict guard.
                preparedWorkspaces.forEach { candidate ->
                    val target = File(workspacesRoot, candidate.manifest.root)
                    require(!Files.isSymbolicLink(target.toPath())) {
                        "Workspace directory is a symbolic link: ${candidate.manifest.id}"
                    }
                    require(!target.exists()) {
                        "Workspace directory appeared during incremental restore"
                    }
                    val recovery = File(recoveryRoot, "workspaces/${candidate.manifest.root}")
                    WorkspaceArchiveDirectoryTransaction(
                        staging = candidate.stagingRoot,
                        target = target,
                        recovery = recovery,
                    ).activate()
                    movedWorkspaces += MovedPath(candidate.stagingRoot, target, null)
                }
            }

            if (!databaseRestoreRequested) {
                preparedWorkspaces.forEach { candidate ->
                    val entity = candidate.toEntity(json)
                    workspaceRepository.upsertRestoredWorkspace(entity)
                    insertedIds += entity.id
                }
            }
            if (includeFiles) {
                legacyMoves += replaceLegacyDirectories(prepared.legacyDirectory, recoveryRoot)
            }
            restoredSettings?.let {
                recoveryJournal?.let { journal ->
                    val applying = journal.copy(
                        phase = WorkspaceArchiveRecoveryPhase.SETTINGS_APPLYING,
                        settingsApplied = true,
                        settingsNeedsCompensation = true,
                    )
                    WorkspaceArchiveRecovery.writeJournal(
                        WorkspaceArchiveRecovery.journalFile(context),
                        applying,
                    )
                    recoveryJournal = applying
                }
                settingsApplied = true
                settingsTransaction.replace(
                    mergeLocalCredentials(currentSettings, it)
                )
                recoveryJournal?.let { journal ->
                    val applied = journal.copy(phase = WorkspaceArchiveRecoveryPhase.SETTINGS_APPLIED)
                    WorkspaceArchiveRecovery.writeJournal(
                        WorkspaceArchiveRecovery.journalFile(context),
                        applied,
                    )
                    recoveryJournal = applied
                }
            }

            if (databaseRestoreRequested) {
                // The marker is only an ownership hint. The journal remains authoritative and is
                // written before this point, so cleanup cannot delete an unconfirmed recovery.
                cleanupMarker = WorkspaceArchiveCleanup.createMarker(
                    context,
                    listOf(prepared.staging, recoveryRoot, databaseRecoveryDirectory) +
                        listOfNotNull(archiveCleanupFile),
                )
                val journal = recoveryJournal ?: error("Full restore recovery journal is missing")
                val closing = journal.copy(phase = WorkspaceArchiveRecoveryPhase.ROOM_CLOSE_PENDING)
                WorkspaceArchiveRecovery.writeJournal(
                    WorkspaceArchiveRecovery.journalFile(context),
                    closing,
                )
                recoveryJournal = closing
                databaseCloseAttempted = true
                currentCoroutineContext()[BackupTimingSession]?.restarting()
                closeDatabaseAndExitAfterSwap(
                    staged = prepared.databaseFiles,
                    recovery = databaseRecoveryDirectory,
                    journal = closing,
                )
            }

            // No database was closed in the incremental path. Persist the commit decision before
            // deleting rollback material; a process death after this point completes cleanup on
            // the next launch instead of rolling back a successful import.
            val committedJournal = requireNotNull(recoveryJournal).copy(
                phase = WorkspaceArchiveRecoveryPhase.COMMITTED,
                commitState = WorkspaceArchiveCommitState.COMMITTED,
                settingsNeedsCompensation = false,
            )
            WorkspaceArchiveRecovery.writeJournal(
                WorkspaceArchiveRecovery.journalFile(context),
                committedJournal,
            )
            recoveryJournal = committedJournal
            val committedWarnings = mutableListOf<String>()
            movedWorkspaces.flatMapTo(committedWarnings) { it.commitBestEffort("workspace") }
            legacyMoves.flatMapTo(committedWarnings) { it.commitBestEffort("legacy") }
            cleanupRecoveryRoot(recoveryRoot, committedWarnings)
            WorkspaceArchiveRecovery.finishCommitted(context)
            return WorkspaceArchiveRestoreResult(
                portable = true,
                restoredWorkspaceIds = preparedWorkspaces.map { it.manifest.id },
                warnings = (warnings + committedWarnings).distinct(),
                restartRequired = false,
                databaseRestoreAttempted = false,
                databaseRestoreStatus = WorkspaceArchiveDatabaseRestoreStatus.NOT_REQUESTED,
            )
        } catch (error: Throwable) {
            if (recoveryJournal?.commitState == WorkspaceArchiveCommitState.COMMITTED) {
                // The durable decision is authoritative. Startup will retry cleanup.
                throw error
            }
            // Once close starts there is intentionally no application-level compensation path:
            // scheduling relaunch is the only safe continuation. All restore artifacts remain
            // behind the marker for the next process.
            if (databaseCloseAttempted) {
                WorkspaceArchiveRestart.scheduleAndExit(context)
            }

            val compensationFailures = mutableListOf<Throwable>()
            withContext(NonCancellable) {
                suspend fun compensate(action: suspend () -> Unit) {
                    try {
                        action()
                    } catch (rollbackError: Throwable) {
                        compensationFailures += rollbackError
                    }
                }
                if (settingsApplied) compensate {
                    settingsTransaction.replace(currentSettings)
                }
                legacyMoves.asReversed().forEach { moved -> compensate { moved.rollback() } }
                movedWorkspaces.asReversed().forEach { moved -> compensate { moved.rollback() } }
                insertedIds.asReversed().forEach { id ->
                    compensate { workspaceRepository.deleteRestoredWorkspace(id) }
                }
                cleanupMarker?.let { marker ->
                    if (marker.exists()) compensate { WorkspaceArchiveCleanup.removeMarker(marker) }
                }
                if (cleanupMarker == null) {
                    compensate { deleteDirectoryIfPresent(databaseRecoveryDirectory) }
                }
                if (
                    recoveryJournal != null &&
                    !databaseCloseAttempted &&
                    compensationFailures.isEmpty() &&
                    error.suppressed.isEmpty()
                ) {
                    val rolledBack = recoveryJournal!!.copy(
                        phase = WorkspaceArchiveRecoveryPhase.ROLLED_BACK,
                        commitState = WorkspaceArchiveCommitState.ROLLED_BACK,
                        settingsApplied = false,
                        settingsNeedsCompensation = false,
                        incrementalWorkspaceIds = emptyList(),
                    )
                    compensate {
                        WorkspaceArchiveRecovery.writeJournal(
                            WorkspaceArchiveRecovery.journalFile(context),
                            rolledBack,
                        )
                        recoveryJournal = rolledBack
                        WorkspaceArchiveRecovery.finishRolledBack(context)
                    }
                }
            }
            compensationFailures.forEach(error::addSuppressed)
            throw error
        }
    }

    private fun beginRecoveryJournal(
        currentSettings: Settings,
        recoveryRoot: File,
        databaseRecoveryDirectory: File?,
        workspacesRoot: File?,
        incrementalDirectories: List<WorkspaceArchiveDirectoryRecovery> = emptyList(),
        incrementalWorkspaceIds: List<String> = emptyList(),
        legacyStaging: File?,
        includeFiles: Boolean,
        cleanupArtifacts: List<File>,
    ): WorkspaceArchiveRecoveryJournal {
        val databaseDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        val targetNames = listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm", "rikka_hub-journal")
        val oldExistingNames = if (databaseRecoveryDirectory != null) {
            targetNames.filter { name ->
                val target = File(databaseDirectory, name)
                require(!Files.isSymbolicLink(target.toPath())) {
                    "Database file is a symbolic link: $name"
                }
                target.exists()
            }
        } else {
            emptyList()
        }
        val workspaces = workspacesRoot?.let { root ->
            WorkspaceArchiveDirectoryRecovery(
                newRoot = root.absolutePath,
                oldRootRecovery = File(recoveryRoot, "workspaces-root").absolutePath,
                oldRootExisted = root.exists(),
            )
        }
        val legacyDirectories = if (includeFiles && legacyStaging != null) {
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS).mapNotNull { folder ->
                val staged = File(legacyStaging, folder)
                if (!staged.exists()) return@mapNotNull null
                val target = File(context.filesDir, folder)
                require(!Files.isSymbolicLink(target.toPath())) {
                    "Refusing to replace symbolic-link folder: $folder"
                }
                WorkspaceArchiveDirectoryRecovery(
                    newRoot = target.absolutePath,
                    oldRootRecovery = File(recoveryRoot, folder).absolutePath,
                    oldRootExisted = target.exists(),
                )
            }
        } else {
            emptyList()
        }
        val settingsBackup = File(recoveryRoot, "settings.json")
        WorkspaceArchiveRecovery.writeSettingsBackup(
            settingsBackup,
            json.encodeToString(currentSettings),
        )
        val settingsDataStoreBackup = File(recoveryRoot, "settings.preferences_pb")
        val settingsDataStoreExisted = WorkspaceArchiveRecovery.snapshotSettingsDataStore(
            source = settingsDataStoreFile(context),
            backup = settingsDataStoreBackup,
        )
        val journal = WorkspaceArchiveRecoveryJournal(
            transactionId = UUID.randomUUID().toString(),
            phase = WorkspaceArchiveRecoveryPhase.PREPARED,
            commitState = WorkspaceArchiveCommitState.PENDING,
            workspaces = workspaces,
            legacyDirectories = incrementalDirectories + legacyDirectories,
            database = databaseRecoveryDirectory?.let { recoveryDirectory ->
                WorkspaceArchiveDatabaseRecovery(
                    databaseDirectory = databaseDirectory.absolutePath,
                    oldDatabaseRecovery = recoveryDirectory.absolutePath,
                    targetNames = targetNames,
                    oldExistingNames = oldExistingNames,
                )
            },
            incrementalWorkspaceIds = incrementalWorkspaceIds.distinct(),
            settingsBackup = settingsBackup.absolutePath,
            settingsDataStoreBackup = settingsDataStoreBackup.absolutePath,
            settingsDataStoreExisted = settingsDataStoreExisted,
            cleanupArtifacts = cleanupArtifacts.map(File::getAbsolutePath),
        )
        WorkspaceArchiveRecovery.writeJournal(WorkspaceArchiveRecovery.journalFile(context), journal)
        return journal
    }

    /**
     * The only code allowed to run after Room closes. The journal is advanced before and after
     * every irreversible boundary. If close/swap or the process fails, the next process rolls
     * back instead of deleting the recovery tree.
     */
    private suspend fun closeDatabaseAndExitAfterSwap(
        staged: Map<String, File>,
        recovery: File,
        journal: WorkspaceArchiveRecoveryJournal,
    ): Nothing = withContext(NonCancellable) {
        try {
            val journalFile = WorkspaceArchiveRecovery.journalFile(context)
            val closing = journal.copy(phase = WorkspaceArchiveRecoveryPhase.ROOM_CLOSE_PENDING)
            WorkspaceArchiveRecovery.writeJournal(journalFile, closing)
            database.close()
            val closed = closing.copy(phase = WorkspaceArchiveRecoveryPhase.ROOM_CLOSED)
            WorkspaceArchiveRecovery.writeJournal(journalFile, closed)
            replaceDatabaseFiles(
                staged = staged,
                databaseClosed = true,
                recovery = recovery,
            )
            val exchanged = closed.copy(phase = WorkspaceArchiveRecoveryPhase.DATABASE_EXCHANGED)
            WorkspaceArchiveRecovery.writeJournal(journalFile, exchanged)
            // This is the two-phase commit point. Old DB/root recovery is never deleted before
            // this durable state is on disk.
            WorkspaceArchiveRecovery.writeJournal(
                journalFile,
                exchanged.copy(
                    phase = WorkspaceArchiveRecoveryPhase.COMMITTED,
                    commitState = WorkspaceArchiveCommitState.COMMITTED,
                    settingsNeedsCompensation = false,
                ),
            )
        } catch (error: Throwable) {
            // Room is already closed (or close was attempted). Keep the journal and let the next
            // process restore old DB/root before Koin. Do not run cleanup in this process.
            runCatching {
                WorkspaceArchiveRecovery.writeJournal(
                    WorkspaceArchiveRecovery.journalFile(context),
                    journal.copy(phase = WorkspaceArchiveRecoveryPhase.ROLLBACK_REQUIRED),
                )
            }.onFailure { error.addSuppressed(it) }
        }
        WorkspaceArchiveRestart.scheduleAndExit(context)
    }

    private suspend fun replaceDatabaseFiles(
        staged: Map<String, File>,
        databaseClosed: Boolean,
        recovery: File,
    ): Unit = withContext(NonCancellable + Dispatchers.IO) {
        require(databaseClosed) {
            "Database replacement is forbidden while Room is open"
        }
        require(staged.keys.all { it == "database/rikka_hub.db" }) {
            "Only the staged main database may be activated"
        }
        val targetDirectory = context.getDatabasePath("rikka_hub").parentFile
            ?: error("Database directory is unavailable")
        require(!Files.isSymbolicLink(targetDirectory.toPath())) {
            "Database directory is a symbolic link"
        }
        require(recovery.isDirectory && !Files.isSymbolicLink(recovery.toPath())) {
            "Database recovery directory is not prepared"
        }
        val targets = listOf("rikka_hub", "rikka_hub-wal", "rikka_hub-shm", "rikka_hub-journal")
        val moved = mutableListOf<MovedPath>()
        try {
            targets.forEach { targetName ->
                val target = File(targetDirectory, targetName)
                require(!Files.isSymbolicLink(target.toPath())) {
                    "Database file is a symbolic link: $targetName"
                }
                if (target.exists()) {
                    val backup = File(recovery, target.name)
                    require(target.renameTo(backup)) { "Failed to save old database file: ${target.name}" }
                    moved += MovedPath(backup, target, backup)
                }
            }
            staged.forEach { (archiveName, source) ->
                require(!Files.isSymbolicLink(source.toPath()) && source.isFile) {
                    "Staged database is not a regular file: $archiveName"
                }
                val target = File(targetDirectory, "rikka_hub")
                require(source.renameTo(target)) { "Failed to activate database file: $archiveName" }
                moved += MovedPath(source, target, null)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                moved.asReversed().forEach { movedPath ->
                    try {
                        movedPath.rollback()
                    } catch (rollbackError: Throwable) {
                        error.addSuppressed(rollbackError)
                    }
                }
                // Never clean the recovery directory in the closed-DB critical section. The
                // durable cleanup marker lets the next process remove it safely.
            }
            throw error
        }
    }

    private suspend fun replaceLegacyDirectories(
        staging: File,
        recoveryRoot: File,
    ): List<MovedPath> {
        if (!staging.isDirectory) return emptyList()
        val result = mutableListOf<MovedPath>()
        try {
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS).forEach { folder ->
                val source = File(staging, folder)
                if (!source.exists()) return@forEach
                val target = File(context.filesDir, folder)
                require(!Files.isSymbolicLink(target.toPath())) {
                    "Refusing to replace symbolic-link folder: $folder"
                }
                val recovery = File(recoveryRoot, folder)
                WorkspaceArchiveDirectoryTransaction(
                    staging = source,
                    target = target,
                    recovery = recovery,
                ).activate()
                result += MovedPath(source, target, recovery.takeIf { it.exists() })
            }
            return result
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                result.asReversed().forEach { moved ->
                    try {
                        moved.rollback()
                    } catch (rollbackError: Throwable) {
                        error.addSuppressed(rollbackError)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun <T> withWorkspaceLocks(
        ids: List<String>,
        index: Int = 0,
        block: suspend () -> T,
    ): T {
        if (index >= ids.size) return block()
        return workspaceRepository.withWorkspaceArchiveLock(ids[index]) {
            withWorkspaceLocks(ids, index + 1, block)
        }
    }

    private fun sanitizeRuntimeMetadata(metadata: WorkspaceRuntimeMetadata): WorkspaceRuntimeMetadata =
        WorkspaceRuntimeMetadata()

    private fun decodeArchiveSettings(raw: String): Settings {
        val migrated = SettingsJsonMigrator.migrate(raw)
        return json.decodeFromString<Settings>(migrated)
    }

    private fun validateManifestSettingsReferences(
        manifest: WorkspaceArchiveManifest,
        settings: Settings,
    ) {
        val actual = settings.assistants.map { assistant ->
            WorkspaceAssistantReference(
                assistantId = assistant.id.toString(),
                workspaceId = assistant.workspaceId?.toString(),
            )
        }.sortedBy { "${it.assistantId}\u0000${it.workspaceId ?: ""}" }
        val expected = manifest.references.assistants
            .sortedBy { "${it.assistantId}\u0000${it.workspaceId ?: ""}" }
        require(actual == expected) {
            "Portable manifest assistant workspace references do not match settings"
        }
    }

    private fun expectedPortableEntries(manifest: WorkspaceArchiveManifest): Set<String> = buildSet {
        add("manifest.json")
        add(WorkspaceArchiveManifest.SETTINGS_PATH)
        addAll(manifest.databaseEntries)
        addAll(manifest.legacyEntries)
        manifest.workspacePayloadPaths().forEach(::add)
    }

    private fun WorkspaceArchiveManifest.workspacePayloadPaths(): Set<String> = buildSet {
        workspaces.forEach { workspace ->
            add(workspace.files.path)
            workspace.home?.let { add(it.path) }
        }
    }

    private fun WorkspaceArchiveManifest.payloadByPath(path: String): WorkspaceArchivePayload =
        workspaces.firstNotNullOfOrNull { workspace ->
            when {
                workspace.files.path == path -> workspace.files
                workspace.home?.path == path -> workspace.home
                else -> null
            }
        } ?: error("Payload not declared in manifest: $path")

    private fun WorkspaceArchiveManifest.databasePayloadFor(path: String): WorkspaceArchivePayload =
        databasePayloads.firstOrNull { it.path == path }
            ?: error("Database payload is not declared in manifest: $path")

    private fun payloadFileName(payload: WorkspaceArchivePayload): String {
        val root = payload.path.substringAfter("workspaces/").substringBefore('/')
        return "${root}-${payload.path.substringAfterLast('/')}"
    }

    private fun addFile(
        zipOut: ZipOutputStream,
        file: File,
        name: String,
        budget: WorkspaceArchiveBudget,
    ) {
        require(file.isFile) { "Backup source is not a file: ${file.path}" }
        ZipEntry(name).let { entry ->
            budget.recordZipEntry()
            zipOut.setLevel(ArchiveCompression.level(name))
            zipOut.putNextEntry(entry)
            file.inputStream().buffered(BUFFER_SIZE).use { input ->
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
    }

    private fun addBytes(
        zipOut: ZipOutputStream,
        name: String,
        bytes: ByteArray,
        budget: WorkspaceArchiveBudget,
    ) {
        budget.recordZipEntry()
        budget.recordZipDecompressedBytes(bytes.size.toLong())
        zipOut.setLevel(ArchiveCompression.level(name))
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun decodePortableManifest(bytes: ByteArray): WorkspaceArchiveManifest {
        val text = bytes.toString(Charsets.UTF_8)
        requireWorkspaceManifestVersion(text, json, WorkspaceArchiveManifest.FORMAT)
        return json.decodeFromString<WorkspaceArchiveManifest>(text).also { it.validate() }
    }

    private fun openPortableInput(
        file: File, baseFile: File?, budget: WorkspaceArchiveBudget, allowLegacyRestore: Boolean = false,
    ): ZipInputStream {
        if (ArchiveIncremental.isIncremental(file)) return ArchiveIncremental.open(file, baseFile, budget, allowLegacyRestore)
        ArchiveIncremental.checkedDirectory(file, budget)
        budget.checkCompressedFile(file)
        return ZipInputStream(BufferedInputStream(FileInputStream(file), BUFFER_SIZE))
    }

    private fun hasPortableManifest(file: File): Boolean =
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zipIn ->
            val entry = zipIn.nextEntry
            entry != null && WorkspaceArchivePathSafety.normalize(entry.name) == "manifest.json"
        }

    private fun readCurrentEntry(
        entry: ZipEntry,
        input: InputStream,
        maxBytes: Long,
        budget: WorkspaceArchiveBudget? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        copyCurrentEntry(input, output, maxBytes, budget)
        return output.toByteArray()
    }

    private fun copyCurrentEntryToFile(
        input: InputStream,
        target: File,
        maxBytes: Long,
        budget: WorkspaceArchiveBudget? = null,
        stagingRoot: File? = null,
    ): PayloadDigest {
        target.parentFile?.let { WorkspaceArchivePathSafety.ensureDirectory(it) }
        require(!Files.isSymbolicLink(target.toPath())) { "Archive target is a symbolic link" }
        require(!target.exists()) { "Duplicate archive target: ${target.path}" }
        return FileOutputStream(target).use { output ->
            copyCurrentEntry(
                input = input,
                output = output,
                maxBytes = maxBytes,
                budget = budget,
                stagingBudget = budget,
                stagingRoot = stagingRoot ?: target.parentFile,
            )
        }.also {
            WorkspaceArchiveTar.applyMode(target, 0x180) // 0600
        }
    }

    private fun copyCurrentEntry(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        budget: WorkspaceArchiveBudget? = null,
        stagingBudget: WorkspaceArchiveBudget? = null,
        stagingRoot: File? = null,
    ): PayloadDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var length = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            length = Math.addExact(length, read.toLong())
            require(length <= maxBytes) { "Archive entry exceeds size limit" }
            if (input !is BudgetedArchiveInput) budget?.recordZipDecompressedBytes(read.toLong())
            if (stagingBudget != null) {
                if (stagingRoot != null) {
                    stagingBudget.recordStagingBytes(read.toLong(), stagingRoot)
                } else {
                    stagingBudget.recordStagingBytes(read.toLong())
                }
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return PayloadDigest(length, digest.digest().toHexString())
    }

    private fun validateTarPayload(
        payload: File,
        verificationRoot: File,
        budget: WorkspaceArchiveBudget,
    ) {
        budget.recordTarBytes(payload.length())
        val targetRoot = File(verificationRoot, "validated-tar/${payload.nameWithoutExtension}")
        WorkspaceArchiveTar.extract(
            input = payload,
            targetRoot = targetRoot,
            limits = WorkspaceArchiveTar.Limits(
                maxEntries = MAX_TAR_ENTRIES,
                maxEntryBytes = MAX_PAYLOAD_BYTES,
                maxPayloadBytes = MAX_PAYLOAD_BYTES,
            ),
            onEntry = budget::recordTarEntry,
            onFileBytes = { bytes ->
                budget.recordWorkspaceBytes(bytes)
                budget.recordStagingBytes(bytes, verificationRoot)
            },
        )
    }

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

    private fun ByteArray.toHexString(): String = joinToString("") {
        "%02x".format(java.util.Locale.US, it.toInt() and 0xff)
    }

    private fun requirePayloadDigest(
        name: String,
        digest: PayloadDigest,
        descriptor: WorkspaceArchivePayload,
    ) {
        require(digest.length == descriptor.length) {
            "Database payload length mismatch: $name"
        }
        require(digest.sha256 == descriptor.sha256.lowercase()) {
            "Database payload hash mismatch: $name"
        }
    }

    private fun validateDatabasePayload(
        file: File,
        descriptor: WorkspaceArchivePayload,
    ) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Staged database is not a regular file"
        }
        requirePayloadDigest(
            name = descriptor.path,
            digest = PayloadDigest(file.length(), sha256(file)),
            descriptor = descriptor,
        )
    }

    private fun validateStagedDatabase(
        file: File,
        expectedVersion: Int = AppDatabase.CURRENT_VERSION,
        expectedReferences: WorkspaceArchiveReferences? = null,
        expectedWorkspaceKeys: List<WorkspaceArchiveWorkspaceKey>? = null,
        expectedWorkspaceCatalog: List<WorkspaceCatalogRecord>? = null,
        expectedSnapshotsEmpty: Boolean = false,
    ) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Staged database is not a regular file"
        }
        require(file.length() > 0L) { "Staged database is empty" }
        require(expectedVersion in setOf(31, 32)) {
            "Unsupported staged database schema $expectedVersion"
        }
        val validationHelper = openStagedDatabaseForValidation(file, expectedVersion)
        try {
            val staged = validationHelper.readableDatabase
            requirePragmaHealthy(staged, "quick_check")
            requirePragmaHealthy(staged, "integrity_check")
            requireNoForeignKeyViolations(staged)

            val userVersion = staged.query("PRAGMA user_version").use { cursor ->
                require(cursor.moveToFirst()) { "Staged database user_version is unavailable" }
                cursor.getInt(0)
            }
            require(userVersion == expectedVersion) {
                "Staged database user_version $userVersion does not match expected $expectedVersion"
            }
            val identity = staged.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Staged database Room identity is missing" }
                cursor.getString(0)
            }
            require(identity.matches(ROOM_IDENTITY_HASH_REGEX)) {
                "Staged database Room identity is malformed"
            }
            val schema = readRoomSchemaSnapshot(staged, userVersion, identity)
            val expectation = if (expectedVersion == 31) ROOM_SCHEMA_31_EXPECTATION else ROOM_SCHEMA_32_EXPECTATION
            if (!roomSchemaIsExact(schema, expectation)) {
                // The message reaches the backup UI, so name the drifting objects there instead of
                // only stating that the schema is not the current Room schema. A release build
                // refuses run-as, so the same structure also goes to a file this device can hand
                // over.
                val difference = describeRoomSchemaDifference(schema, expectation)
                val report = writeSchemaDriftReport(schema, difference)
                Log.e(
                    TAG,
                    "Staged database is not Room schema $expectedVersion: " +
                        "$difference (report=${report?.absolutePath ?: "unavailable"})",
                )
                error(
                    "Staged database schema is not exactly Room schema $expectedVersion: " +
                        "$difference" +
                        (report?.let { " (report: ${it.name})" } ?: ""),
                )
            }

            expectedReferences?.let { expected ->
                val actual = mutableListOf<WorkspaceConversationReference>()
                staged.query("SELECT id, workspace_id FROM conversationentity").use { cursor ->
                    while (cursor.moveToNext()) {
                        actual += WorkspaceConversationReference(
                            conversationId = cursor.getString(0),
                            workspaceId = if (cursor.isNull(1)) null else cursor.getString(1),
                        )
                    }
                }
                val sortReferences: (WorkspaceConversationReference) -> String = {
                    "${it.conversationId}\u0000${it.workspaceId ?: ""}"
                }
                require(actual.sortedBy(sortReferences) == expected.conversations.sortedBy(sortReferences)) {
                    "Staged database workspace references do not match manifest"
                }
            }
            expectedWorkspaceKeys?.let { expected ->
                val actual = readValidatedWorkspaceCatalog(staged).map { workspace ->
                    WorkspaceArchiveWorkspaceKey(workspace.id, workspace.root)
                }
                val sortKeys: (WorkspaceArchiveWorkspaceKey) -> String = {
                    "${it.id}\u0000${it.root}"
                }
                require(actual.sortedBy(sortKeys) == expected.sortedBy(sortKeys)) {
                    "Staged database workspace catalog does not match manifest"
                }
            }
            expectedWorkspaceCatalog?.let { expected ->
                val actual = readValidatedWorkspaceCatalog(staged).sorted()
                require(actual == expected.sorted()) {
                    "Staged database workspace catalog changed while snapshot was copied"
                }
            }
            if (expectedSnapshotsEmpty) {
                val snapshotCount = staged.query(
                    "SELECT COUNT(*) FROM workspace_snapshots",
                ).use { cursor ->
                    require(cursor.moveToFirst()) { "Workspace snapshot table is unreadable" }
                    cursor.getLong(0)
                }
                require(snapshotCount == 0L) {
                    "Staged database contains non-portable workspace snapshot rows"
                }
            }
        } finally {
            validationHelper.close()
        }
    }

    /**
     * Drop the rejected database's structure next to the app's external files.
     *
     * The device that hits a schema rejection is usually the only one holding the offending
     * database, and a release build cannot be read over adb. The report is DDL only, so it carries
     * no conversation data, and it is bounded so a hostile archive cannot fill the shared storage.
     */
    private fun writeSchemaDriftReport(snapshot: RoomSchemaSnapshot, difference: String): File? {
        val directory = context.getExternalFilesDir(null) ?: return null
        val report = File(directory, "schema-drift-${System.currentTimeMillis()}.txt")
        val text = roomSchemaDriftReport(
            snapshot = snapshot,
            difference = difference,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            expectation = ROOM_SCHEMA_31_EXPECTATION,
        )
        return runCatching {
            report.writeText(text)
            report
        }.onFailure { error -> Log.w(TAG, "Unable to write the schema drift report", error) }
            .getOrNull()
    }

    private fun openStagedDatabaseForValidation(file: File, expectedVersion: Int): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(expectedVersion) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                error("Staged database unexpectedly requested creation")
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                error("Staged database unexpectedly requested migration: $oldVersion->$newVersion")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(callback)
            .build()
        return RequerySQLiteOpenHelperFactory(
            listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null,
                        )
                    )
                    options
                }
            )
        ).create(configuration)
    }

    private fun requirePragmaHealthy(database: SupportSQLiteDatabase, pragma: String) {
        database.query("PRAGMA $pragma").use { cursor ->
            require(cursor.moveToFirst()) { "Staged database $pragma returned no result" }
            do {
                val result = cursor.getString(0)?.trim()
                require(result.equals("ok", ignoreCase = true)) {
                    "Staged database $pragma failed: ${result ?: "null"}"
                }
            } while (cursor.moveToNext())
        }
    }

    private fun requireNoForeignKeyViolations(database: SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { cursor ->
            require(!cursor.moveToFirst()) {
                "Staged database has foreign-key violations"
            }
        }
    }

    private fun readRoomSchemaSnapshot(
        database: SupportSQLiteDatabase,
        userVersion: Int,
        identityHash: String,
    ): RoomSchemaSnapshot {
        val objects = mutableListOf<RoomSchemaObjectSnapshot>()
        val sqliteMasterSql = mutableMapOf<String, String?>()
        database.query(
            "SELECT type, name, sql FROM sqlite_master " +
                "WHERE type IN ('table', 'index', 'trigger', 'view') " +
                "AND name != 'android_metadata'",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                objects += RoomSchemaObjectSnapshot(
                    type = cursor.getString(0),
                    name = name,
                )
                sqliteMasterSql[name] = if (cursor.isNull(2)) null else cursor.getString(2)
            }
        }
        val tableNames = objects.filter { it.type == "table" }.map { it.name }
        val tables = tableNames.map { tableName ->
            RoomSchemaTableSnapshot(
                name = tableName,
                columns = readRoomSchemaColumns(database, tableName),
                foreignKeys = readRoomSchemaForeignKeys(database, tableName),
            )
        }
        val indices = tableNames.flatMap { tableName ->
            readRoomSchemaIndices(database, tableName)
        }
        return RoomSchemaSnapshot(
            userVersion = userVersion,
            identityHash = identityHash,
            tables = tables,
            indices = indices,
            objects = objects,
            sqliteMasterSql = sqliteMasterSql,
        )
    }

    private fun readRoomSchemaColumns(
        database: SupportSQLiteDatabase,
        table: String,
    ): List<RoomSchemaColumnSnapshot> {
        val columns = mutableListOf<RoomSchemaColumnSnapshot>()
        database.query("PRAGMA table_info('${table.replace("'", "''")}')").use { cursor ->
            while (cursor.moveToNext()) {
                columns += RoomSchemaColumnSnapshot(
                    name = cursor.getString(1),
                    type = cursor.getString(2) ?: "",
                    notNull = cursor.getInt(3) != 0,
                    primaryKeyPosition = cursor.getInt(5),
                    defaultValue = if (cursor.isNull(4)) null else cursor.getString(4),
                )
            }
        }
        return columns
    }

    private fun readRoomSchemaForeignKeys(
        database: SupportSQLiteDatabase,
        table: String,
    ): List<RoomSchemaForeignKeySnapshot> {
        data class ForeignKeyKey(
            val id: Int,
            val table: String,
            val onDelete: String,
            val onUpdate: String,
            val match: String,
        )
        val grouped = linkedMapOf<ForeignKeyKey, MutableList<Pair<Int, Pair<String, String>>>>()
        database.query("PRAGMA foreign_key_list('${table.replace("'", "''")}')").use { cursor ->
            while (cursor.moveToNext()) {
                val key = ForeignKeyKey(
                    id = cursor.getInt(0),
                    table = cursor.getString(2),
                    onDelete = cursor.getString(6),
                    onUpdate = cursor.getString(5),
                    match = cursor.getString(7),
                )
                grouped.getOrPut(key) { mutableListOf() } +=
                    cursor.getInt(1) to (cursor.getString(3) to cursor.getString(4))
            }
        }
        return grouped.entries.sortedBy { it.key.id }.map { (key, pairs) ->
            val ordered = pairs.sortedBy { it.first }
            RoomSchemaForeignKeySnapshot(
                table = key.table,
                onDelete = key.onDelete,
                onUpdate = key.onUpdate,
                columns = ordered.map { it.second.first },
                referencedColumns = ordered.map { it.second.second },
                match = key.match,
            )
        }
    }

    private fun readRoomSchemaIndices(
        database: SupportSQLiteDatabase,
        table: String,
    ): List<RoomSchemaIndexSnapshot> {
        val indices = mutableListOf<RoomSchemaIndexSnapshot>()
        database.query("PRAGMA index_list('${table.replace("'", "''")}')").use { cursor ->
            val originColumn = cursor.getColumnIndex("origin")
            val partialColumn = cursor.getColumnIndex("partial")
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val columns = mutableListOf<String>()
                database.query("PRAGMA index_info('${name.replace("'", "''")}')").use { indexCursor ->
                    while (indexCursor.moveToNext()) {
                        columns += indexCursor.getString(2)
                    }
                }
                indices += RoomSchemaIndexSnapshot(
                    table = table,
                    name = name,
                    unique = cursor.getInt(2) != 0,
                    columns = columns,
                    origin = if (originColumn >= 0) cursor.getString(originColumn) else "",
                    partial = partialColumn >= 0 && cursor.getInt(partialColumn) != 0,
                )
            }
        }
        return indices
    }

    private fun readValidatedWorkspaceCatalog(database: SupportSQLiteDatabase): List<WorkspaceCatalogRecord> {
        val catalog = mutableListOf<WorkspaceCatalogRecord>()
        database.query(
            "SELECT id, name, root, shell_status, created_at, updated_at, " +
                "last_access_at, tool_approvals, runtime_metadata FROM workspaces",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                catalog += WorkspaceCatalogRecord(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    root = cursor.getString(2),
                    shellStatus = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                    updatedAt = cursor.getLong(5),
                    lastAccessAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    toolApprovals = cursor.getString(7),
                    runtimeMetadata = cursor.getString(8),
                )
            }
        }
        return catalog
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "Unexpected EOF while copying database snapshot" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun deleteIfPresent(file: File) {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(file.delete()) { "Unable to delete ${file.path}" }
        }
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(directory.deleteRecursively()) { "Unable to clean archive staging: ${directory.path}" }
        }
    }

    private fun cleanupDirectory(directory: File, failure: Throwable?) {
        try {
            deleteDirectoryIfPresent(directory)
        } catch (cleanupError: Throwable) {
            if (failure != null) {
                failure.addSuppressed(cleanupError)
            } else {
                throw cleanupError
            }
        }
    }

    private fun cleanupRestoreStaging(
        staging: File,
        failure: Throwable?,
        result: WorkspaceArchiveRestoreResult?,
    ) {
        // A database restore has already transferred cleanup ownership to the next process. Do
        // not recursively delete staging after Room closes, even on a cancellation/failure path.
        if (WorkspaceArchiveCleanup.isMarkerFor(context, staging)) return
        try {
            deleteDirectoryIfPresent(staging)
        } catch (cleanupError: Throwable) {
            when {
                failure != null -> failure.addSuppressed(cleanupError)
                result?.restartRequired == true -> {
                    throw WorkspaceArchiveRestartRequiredException(
                        message = result.databaseRestoreStatus.message,
                        cause = cleanupError,
                        databaseStatus = result.databaseRestoreStatus,
                    )
                }
                else -> throw cleanupError
            }
        }
    }

    private fun cleanupArchiveArtifacts(
        partialZip: File,
        payloadDirectory: File,
        target: File,
        finalized: Boolean,
        failure: Throwable?,
    ) {
        val cleanupErrors = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (cleanupError: Throwable) {
                cleanupErrors += cleanupError
            }
        }
        cleanup { deleteIfPresent(partialZip) }
        cleanup { deleteDirectoryIfPresent(payloadDirectory) }
        if (!finalized) cleanup { deleteIfPresent(target) }
        if (failure != null) {
            cleanupErrors.forEach(failure::addSuppressed)
        } else if (cleanupErrors.isNotEmpty()) {
            val first = cleanupErrors.first()
            cleanupErrors.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun cleanupRecoveryRoot(root: File, warnings: MutableList<String>) {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        try {
            deleteDirectoryIfPresent(root)
        } catch (cleanupError: Throwable) {
            warnings += "Restore recovery cleanup is pending: ${root.path} (${cleanupError.message ?: cleanupError::class.simpleName})"
        }
    }

    private class BudgetOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Long) -> Unit,
    ) : OutputStream() {
        override fun write(b: Int) {
            onBytes(1L)
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            onBytes(len.toLong())
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        fun sync() {
            (delegate as? FileOutputStream)?.fd?.sync()
        }
    }

    private fun PreparedWorkspace.toEntity(json: Json): WorkspaceEntity {
        val metadata = manifest.runtimeMetadata
        return WorkspaceEntity(
            id = manifest.id,
            name = manifest.name,
            root = manifest.root,
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = manifest.createdAt,
            updatedAt = manifest.updatedAt,
            lastAccessAt = manifest.lastAccessAt,
            toolApprovals = manifest.toolApprovals,
            runtimeMetadata = JsonInstant.encodeToString(metadata),
        )
    }

    private data class ArchiveSource(val file: File, val archiveName: String)

    private data class DatabaseSnapshot(
        val source: ArchiveSource,
        val conversationReferences: List<WorkspaceConversationReference>,
        val liveWorkspaceCatalog: List<WorkspaceCatalogRecord>,
        val exportedWorkspaceCatalog: List<WorkspaceCatalogRecord>,
        val snapshotsExcluded: Boolean,
    )

    private data class WorkspaceCatalogRecord(
        val id: String,
        val name: String,
        val root: String,
        val shellStatus: String,
        val createdAt: Long,
        val updatedAt: Long,
        val lastAccessAt: Long?,
        val toolApprovals: String,
        val runtimeMetadata: String,
    ) : Comparable<WorkspaceCatalogRecord> {
        override fun compareTo(other: WorkspaceCatalogRecord): Int = id.compareTo(other.id)
    }

    private fun workspaceCatalogRecord(workspace: WorkspaceEntity): WorkspaceCatalogRecord =
        WorkspaceCatalogRecord(
            id = workspace.id,
            name = workspace.name,
            root = workspace.root,
            shellStatus = workspace.shellStatus,
            createdAt = workspace.createdAt,
            updatedAt = workspace.updatedAt,
            lastAccessAt = workspace.lastAccessAt,
            toolApprovals = workspace.toolApprovals,
            runtimeMetadata = workspace.runtimeMetadata,
        )

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

    private class SnapshotRetry : RuntimeException()

    private data class PayloadDigest(val length: Long, val sha256: String)

    private data class PreparedPortableArchive(
        val manifest: WorkspaceArchiveManifest,
        val settingsFile: File,
        val databaseFiles: Map<String, File>,
        val payloadFiles: Map<String, File>,
        val legacyDirectory: File,
        val staging: File,
    )

    private data class PreparedWorkspace(
        val manifest: WorkspaceArchiveWorkspace,
        val stagingRoot: File,
        val homeRoot: File,
    )

    private data class MessageFtsSchemaObject(
        val type: String,
        val name: String,
        val sql: String?,
    )

    private data class MovedPath(
        val source: File,
        val target: File,
        val recovery: File?,
    ) {
        fun commitBestEffort(kind: String): List<String> {
            if (recovery == null || !recovery.exists()) return emptyList()
            return try {
                require(recovery.deleteRecursively()) {
                    "Failed to remove restore recovery: ${recovery.path}"
                }
                emptyList()
            } catch (cleanupError: Throwable) {
                listOf(
                    "${kind} recovery cleanup is pending: ${recovery.path} " +
                        "(${cleanupError.message ?: cleanupError::class.simpleName})"
                )
            }
        }

        fun rollback() {
            if (target.exists() && !target.deleteRecursively()) {
                error("Failed to remove activated restore path: ${target.path}")
            }
            if (recovery != null && recovery.exists()) {
                require(recovery.renameTo(target)) { "Failed to roll back ${target.path}" }
            }
        }
    }

    companion object {
        private const val TAG = "WorkspaceArchiveService"
        private const val BUFFER_SIZE = ArchiveCompression.BUFFER_SIZE
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val MAX_SETTINGS_BYTES = 16L * 1024 * 1024
        private const val MAX_DATABASE_ENTRY_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_PAYLOAD_BYTES = 32L * 1024 * 1024 * 1024
        private const val MAX_NON_PAYLOAD_BYTES = 256L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 200_000
        private const val MAX_TAR_ENTRIES = 400_000
        private const val MAX_DATABASE_SNAPSHOT_ATTEMPTS = 3
        private val LEGACY_DATABASE_MIGRATIONS = arrayOf(
            Migration_6_7,
            Migration_11_12,
            Migration_13_14,
            Migration_14_15,
            Migration_15_16,
            Migration_24_25,
            Migration_25_26,
            Migration_26_27,
            Migration_27_28,
            Migration_28_29,
            Migration_29_30,
            Migration_30_31,
            Migration_31_32,
        )
        private const val MESSAGE_FTS_TABLE = "message_fts"
        private val FTS_SHADOW_NAMES = setOf(
            "message_fts_config",
            "message_fts_content",
            "message_fts_data",
            "message_fts_docsize",
            "message_fts_idx",
        )
        private val LEGACY_MESSAGE_FTS_COLUMNS = listOf(
            "text",
            "node_id",
            "message_id",
            "conversation_id",
            "role",
        )
        private const val LEGACY_MESSAGE_FTS_SQL =
            "createvirtualtablemessage_ftsusingfts5(text,node_idunindexed,message_idunindexed," +
                "conversation_idunindexed,roleunindexed,tokenize='simple')"
        private val CREATE_MESSAGE_FTS_SQL = """
            CREATE VIRTUAL TABLE message_fts USING fts5(
                text,
                node_id UNINDEXED,
                message_id UNINDEXED,
                conversation_id UNINDEXED,
                title UNINDEXED,
                update_at UNINDEXED,
                tokenize = 'simple'
            )
        """.trimIndent()
        private const val LEGACY_RESTORE_WARNING =
            "Legacy unencrypted archive restored after explicit confirmation"
        private val ROOM_IDENTITY_HASH_REGEX = Regex("[0-9a-fA-F]{32}")
        private val NullOutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
    }
}

/**
 * Effective, environment-aware workspace values written into an isolated backup database copy.
 * Only the columns that describe the runtime environment are projected; identity, timestamps and
 * tool approvals always stay exactly as the device holds them.
 */
internal data class WorkspaceRowProjection(
    val id: String,
    val shellStatus: String,
    val runtimeMetadata: String,
)

/**
 * Sanitize only an isolated backup database copy. This is intentionally package-visible so the
 * device regression test can prove that source rows remain intact while the copy is cleaned.
 *
 * [workspaceProjection] rewrites the copy's workspace rows to the exported projection. An empty
 * projection (the plain sanitize-only call) leaves the workspace rows untouched. [removeSnapshots]
 * is disabled while preparing a restore: that path only adjusts workspace availability after an
 * incompatible Linux payload was skipped and must not remove other archived data.
 */
internal fun sanitizeWorkspaceDatabaseCopy(
    file: File,
    workspaceProjection: List<WorkspaceRowProjection> = emptyList(),
    removeSnapshots: Boolean = true,
) {
    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Database backup copy is not a regular file"
    }
    val database = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )
    try {
        database.beginTransaction()
        try {
            if (removeSnapshots) database.execSQL("DELETE FROM workspace_snapshots")
            // An archive is not an executable approval. Invalidate confirmations in the isolated
            // copy (both export and restore), leaving the source database untouched.
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'workspace_apt_plans'",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    database.execSQL(
                        "UPDATE workspace_apt_plans SET state = 'EXPIRED', confirmed_tool_call_id = NULL " +
                            "WHERE state IN ('PLANNED', 'CONFIRMED')",
                    )
                }
            }
            workspaceProjection.forEach { row ->
                database.execSQL(
                    "UPDATE workspaces SET shell_status = ?, runtime_metadata = ? WHERE id = ?",
                    arrayOf(row.shellStatus, row.runtimeMetadata, row.id),
                )
                database.rawQuery(
                    "SELECT COUNT(*) FROM workspaces WHERE id = ?",
                    arrayOf(row.id),
                ).use { cursor ->
                    require(cursor.moveToFirst()) { "Database backup copy workspace count is unavailable" }
                    require(cursor.getLong(0) == 1L) {
                        "Database backup copy is missing workspace row: ${row.id}"
                    }
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            require(cursor.moveToFirst()) { "SQLite WAL checkpoint returned no status" }
            require(cursor.getInt(0) == 0) { "SQLite WAL checkpoint is busy" }
        }
    } finally {
        database.close()
    }
    listOf(
        File(file.parentFile, "${file.name}-wal"),
        File(file.parentFile, "${file.name}-shm"),
        File(file.parentFile, "${file.name}-journal"),
    ).forEach { sidecar ->
        if (Files.exists(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(sidecar.toPath())) {
                "Database backup sidecar is a symbolic link: ${sidecar.name}"
            }
            require(sidecar.delete()) { "Unable to clean database backup sidecar: ${sidecar.name}" }
        }
    }
}

/** Full database restore replaces the catalog; a files-only import must never merge an ID. */
internal fun workspaceRestoreConflictIds(
    currentIds: Set<String>,
    restoreIds: Set<String>,
    fullDatabaseRestore: Boolean,
): Set<String> = if (fullDatabaseRestore) emptySet() else currentIds.intersect(restoreIds)

enum class WorkspaceArchiveDatabaseRestoreStatus(val message: String) {
    NOT_REQUESTED("数据库未恢复，不需要重启"),
    ACTIVATED_RESTART_REQUIRED("数据库已替换并完成恢复；Room 已关闭，必须立即重启应用"),
    ROLLED_BACK_RESTART_REQUIRED("数据库恢复失败，数据库文件已回滚；Room 已关闭，必须立即重启应用"),
    ROLLBACK_FAILED_RESTART_REQUIRED("数据库恢复失败且数据库回滚失败；状态不安全，必须立即重启应用"),
    ROOM_CLOSE_FAILED_RESTART_REQUIRED("数据库恢复已尝试但 Room 关闭失败；状态需要重启确认，必须立即重启应用"),
    ROOM_CLOSED_NO_SWAP_RESTART_REQUIRED("Room 已关闭但数据库尚未交换；必须立即重启应用确认状态"),
}

class WorkspaceArchiveRestartRequiredException(
    message: String,
    cause: Throwable? = null,
    val databaseStatus: WorkspaceArchiveDatabaseRestoreStatus =
        WorkspaceArchiveDatabaseRestoreStatus.ROOM_CLOSED_NO_SWAP_RESTART_REQUIRED,
) : IllegalStateException(message, cause)

data class WorkspaceArchiveRestoreResult(
    val portable: Boolean,
    val restoredWorkspaceIds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val restartRequired: Boolean = false,
    val databaseRestoreAttempted: Boolean = false,
    val databaseRestoreStatus: WorkspaceArchiveDatabaseRestoreStatus =
        WorkspaceArchiveDatabaseRestoreStatus.NOT_REQUESTED,
    val legacy: Boolean = false,
)
