package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceArchiveRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `interrupted pending journal restores old database and complete workspace root`() {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val workspaces = File(filesDir, "workspaces")
        val oldRecovery = File(filesDir, ".workspace-recovery/workspaces-root")
        val newStaging = temporaryFolder.newFolder("new-workspaces")
        File(workspaces, "old/files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        File(newStaging, "new/files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        oldRecovery.parentFile!!.mkdirs()
        assertTrue(workspaces.renameTo(oldRecovery))
        assertTrue(newStaging.renameTo(workspaces))

        val oldDatabase = File(databaseDir, "rikka_hub")
        oldDatabase.writeText("old-db")
        val databaseRecovery = File(filesDir, ".database-recovery")
        assertTrue(databaseRecovery.mkdirs())
        assertTrue(oldDatabase.renameTo(File(databaseRecovery, "rikka_hub")))
        File(databaseDir, "rikka_hub").writeText("new-db")

        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        val journal = WorkspaceArchiveRecoveryJournal(
            phase = WorkspaceArchiveRecoveryPhase.DATABASE_EXCHANGED,
            commitState = WorkspaceArchiveCommitState.PENDING,
            workspaces = WorkspaceArchiveDirectoryRecovery(
                newRoot = workspaces.absolutePath,
                oldRootRecovery = oldRecovery.absolutePath,
                oldRootExisted = true,
            ),
            database = WorkspaceArchiveDatabaseRecovery(
                databaseDirectory = databaseDir.absolutePath,
                oldDatabaseRecovery = databaseRecovery.absolutePath,
                targetNames = listOf("rikka_hub"),
                oldExistingNames = listOf("rikka_hub"),
            ),
            cleanupArtifacts = listOf(
                File(filesDir, ".workspace-recovery"),
                databaseRecovery,
            ).map(File::getAbsolutePath),
        )
        WorkspaceArchiveRecovery.writeJournal(journalFile, journal)

        val outcome = WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)

        assertFalse(outcome.settingsNeedsCompensation)
        assertEquals("old", File(workspaces, "old/files/state.txt").readText())
        assertFalse(File(workspaces, "new").exists())
        assertEquals("old-db", File(databaseDir, "rikka_hub").readText())
        assertFalse(journalFile.exists())
    }

    @Test
    fun `pre-Koin recovery restores raw DataStore bytes before clearing journal`() = runBlocking {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val dataStoreFile = File(filesDir, "datastore/settings.preferences_pb").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val recoveryRoot = File(filesDir, ".workspace-recovery").apply { mkdirs() }
        val rawBackup = File(recoveryRoot, "settings.preferences_pb")
        assertTrue(WorkspaceArchiveRecovery.snapshotSettingsDataStore(dataStoreFile, rawBackup))
        dataStoreFile.writeBytes(byteArrayOf(9, 8, 7))
        // An invalid JSON snapshot proves that the exact raw DataStore snapshot wins.
        val jsonBackup = File(recoveryRoot, "settings.json").apply {
            writeText("not-json")
        }
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.ROOM_CLOSED,
                commitState = WorkspaceArchiveCommitState.PENDING,
                database = WorkspaceArchiveDatabaseRecovery(
                    databaseDirectory = databaseDir.absolutePath,
                    oldDatabaseRecovery = File(filesDir, ".database-recovery").absolutePath,
                    targetNames = emptyList(),
                    oldExistingNames = emptyList(),
                ),
                settingsBackup = jsonBackup.absolutePath,
                settingsDataStoreBackup = rawBackup.absolutePath,
                settingsDataStoreExisted = true,
                settingsApplied = true,
                settingsNeedsCompensation = true,
                cleanupArtifacts = listOf(recoveryRoot.absolutePath),
            ),
        )

        assertTrue(
            WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)
                .settingsNeedsCompensation,
        )
        WorkspaceArchiveRecovery.compensatePendingSettingsBeforeKoin(
            journal = journalFile,
            filesDir = filesDir,
            databaseDirectory = databaseDir,
            dataStoreFile = dataStoreFile,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), dataStoreFile.readBytes())
        assertFalse(journalFile.exists())
        assertFalse(recoveryRoot.exists())
    }

    @Test
    fun `failed pre-Koin settings compensation keeps journal and recovery snapshot`() = runBlocking {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val recoveryRoot = File(filesDir, ".workspace-recovery").apply { mkdirs() }
        val missingRawBackup = File(recoveryRoot, "settings.preferences_pb")
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.ROLLED_BACK,
                commitState = WorkspaceArchiveCommitState.ROLLED_BACK,
                database = WorkspaceArchiveDatabaseRecovery(
                    databaseDirectory = databaseDir.absolutePath,
                    oldDatabaseRecovery = File(filesDir, ".database-recovery").absolutePath,
                    targetNames = emptyList(),
                    oldExistingNames = emptyList(),
                ),
                settingsDataStoreBackup = missingRawBackup.absolutePath,
                settingsDataStoreExisted = true,
                settingsNeedsCompensation = true,
                cleanupArtifacts = listOf(recoveryRoot.absolutePath),
            ),
        )

        assertTrue(
            runCatching {
                WorkspaceArchiveRecovery.compensatePendingSettingsBeforeKoin(
                    journal = journalFile,
                    filesDir = filesDir,
                    databaseDirectory = databaseDir,
                    dataStoreFile = File(filesDir, "datastore/settings.preferences_pb"),
                )
            }.isFailure,
        )
        assertTrue(journalFile.exists())
        assertTrue(recoveryRoot.exists())
    }

    @Test
    fun `post-close rollback records that DataStore needs compensation`() {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val settingsBackup = File(filesDir, ".recovery/settings.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.ROOM_CLOSED,
                commitState = WorkspaceArchiveCommitState.PENDING,
                database = WorkspaceArchiveDatabaseRecovery(
                    databaseDirectory = databaseDir.absolutePath,
                    oldDatabaseRecovery = File(filesDir, ".database-recovery").absolutePath,
                    targetNames = emptyList(),
                    oldExistingNames = emptyList(),
                ),
                settingsBackup = settingsBackup.absolutePath,
                settingsApplied = true,
                settingsNeedsCompensation = true,
            ),
        )

        val outcome = WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)

        assertTrue(outcome.settingsNeedsCompensation)
        assertTrue(journalFile.exists())
        assertEquals(
            WorkspaceArchiveCommitState.ROLLED_BACK,
            WorkspaceArchiveRecovery.readJournal(journalFile)?.commitState,
        )
    }

    @Test
    fun `interrupted incremental restore rolls back directory and retains Room cleanup ids`() {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val activated = File(filesDir, "workspaces/imported").apply {
            mkdirs()
            resolve("state.txt").writeText("new")
        }
        val recovery = File(filesDir, ".incremental-recovery/imported")
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.WORKSPACES_ACTIVATED,
                commitState = WorkspaceArchiveCommitState.PENDING,
                legacyDirectories = listOf(
                    WorkspaceArchiveDirectoryRecovery(
                        newRoot = activated.absolutePath,
                        oldRootRecovery = recovery.absolutePath,
                        oldRootExisted = false,
                    )
                ),
                incrementalWorkspaceIds = listOf("imported-id"),
                cleanupArtifacts = listOf(File(filesDir, ".incremental-recovery").absolutePath),
            ),
        )

        val outcome = WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)

        assertFalse(activated.exists())
        assertEquals(listOf("imported-id"), outcome.incrementalWorkspaceIdsToDelete)
        assertTrue(journalFile.exists())
        assertEquals(
            WorkspaceArchiveCommitState.ROLLED_BACK,
            WorkspaceArchiveRecovery.readJournal(journalFile)?.commitState,
        )

        val repeatedOutcome = WorkspaceArchiveRecovery.recoverPendingJournal(
            journalFile,
            filesDir,
            databaseDir,
        )
        assertEquals(listOf("imported-id"), repeatedOutcome.incrementalWorkspaceIdsToDelete)
        assertTrue(journalFile.exists())
    }

    @Test
    fun `committed incremental restore keeps activated directory and cleans recovery journal`() {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val activated = File(filesDir, "workspaces/imported").apply {
            mkdirs()
            resolve("state.txt").writeText("new")
        }
        val recoveryRoot = File(filesDir, ".incremental-recovery").apply { mkdirs() }
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.COMMITTED,
                commitState = WorkspaceArchiveCommitState.COMMITTED,
                legacyDirectories = listOf(
                    WorkspaceArchiveDirectoryRecovery(
                        newRoot = activated.absolutePath,
                        oldRootRecovery = File(recoveryRoot, "imported").absolutePath,
                        oldRootExisted = false,
                    )
                ),
                incrementalWorkspaceIds = listOf("imported-id"),
                cleanupArtifacts = listOf(recoveryRoot.absolutePath),
            ),
        )

        val outcome = WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)

        assertFalse(outcome.settingsNeedsCompensation)
        assertTrue(activated.isDirectory)
        assertFalse(recoveryRoot.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun `failed recovery rename leaves journal and old root untouched for a later process`() {
        val filesDir = temporaryFolder.newFolder("files")
        val databaseDir = temporaryFolder.newFolder("databases")
        val targetParent = File(filesDir, "not-a-directory").apply { writeText("block rename") }
        val newRoot = File(targetParent, "workspaces")
        val oldRecovery = File(filesDir, ".workspace-recovery/workspaces-root")
        File(oldRecovery, "old/files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        val journalFile = File(filesDir, WorkspaceArchiveRecovery.JOURNAL_FILE_NAME)
        WorkspaceArchiveRecovery.writeJournal(
            journalFile,
            WorkspaceArchiveRecoveryJournal(
                phase = WorkspaceArchiveRecoveryPhase.WORKSPACES_ACTIVATED,
                commitState = WorkspaceArchiveCommitState.PENDING,
                workspaces = WorkspaceArchiveDirectoryRecovery(
                    newRoot = newRoot.absolutePath,
                    oldRootRecovery = oldRecovery.absolutePath,
                    oldRootExisted = true,
                ),
                database = WorkspaceArchiveDatabaseRecovery(
                    databaseDirectory = databaseDir.absolutePath,
                    oldDatabaseRecovery = File(filesDir, ".database-recovery").absolutePath,
                    targetNames = emptyList(),
                    oldExistingNames = emptyList(),
                ),
            ),
        )

        assertTrue(runCatching {
            WorkspaceArchiveRecovery.recoverPendingJournal(journalFile, filesDir, databaseDir)
        }.isFailure)
        assertTrue(journalFile.exists())
        assertTrue(oldRecovery.isDirectory)
    }
}
