package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSnapshotRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `interrupted restore rolls back directory and retains journal for Room repair`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val target = File(filesDir, "workspaces/ws").apply { mkdirs() }
        File(target, "files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        val recovery = File(filesDir, ".workspace-snapshot-recovery-r")
        val staging = File(snapshots, ".restore-r")
        File(staging, "files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        assertTrue(target.renameTo(recovery))
        assertTrue(staging.renameTo(target))

        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        WorkspaceSnapshotRecovery.writeJournal(
            journalFile,
            WorkspaceSnapshotRecoveryJournal(
                operation = WorkspaceSnapshotRecoveryOperation.RESTORE,
                phase = WorkspaceSnapshotRecoveryPhase.DIRECTORY_ACTIVATED,
                workspaceId = "ws",
                target = target.absolutePath,
                recovery = recovery.absolutePath,
                staging = staging.absolutePath,
                snapshotId = "snapshot-r",
                previousWorkspace = WorkspaceSnapshotEntityRecoveryState(
                    id = "ws",
                    name = "Workspace",
                    root = "ws",
                    shellStatus = "DISABLED",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )

        val outcome = WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, filesDir)

        assertTrue(outcome.databaseRepairRequired)
        assertEquals("old", File(target, "files/state.txt").readText())
        assertFalse(File(target, "files/state.txt").readText() == "new")
        assertEquals(
            WorkspaceSnapshotRecoveryPhase.ROLLED_BACK,
            WorkspaceSnapshotRecovery.readJournal(journalFile)?.phase,
        )
        val rolledBack = WorkspaceSnapshotRecovery.readJournal(journalFile)!!
        WorkspaceSnapshotRecovery.finishRolledBackTransaction(journalFile, rolledBack, filesDir)
        assertFalse(journalFile.exists())
    }

    @Test
    fun `interrupted create marks filesystem transaction rolled back and removes archive`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val target = File(snapshots, "snapshot-c.zip").apply { writeText("partial archive") }
        val staging = File(snapshots, ".snapshot-c").apply { mkdirs() }
        File(staging, "payloads/files.tar").apply {
            parentFile!!.mkdirs()
            writeText("payload")
        }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        WorkspaceSnapshotRecovery.writeJournal(
            journalFile,
            WorkspaceSnapshotRecoveryJournal(
                operation = WorkspaceSnapshotRecoveryOperation.CREATE,
                phase = WorkspaceSnapshotRecoveryPhase.STAGING_READY,
                workspaceId = "ws",
                target = target.absolutePath,
                recovery = File(filesDir, ".snapshot-recovery-c").absolutePath,
                staging = staging.absolutePath,
                snapshotId = "snapshot-c",
            ),
        )

        WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, filesDir)

        assertFalse(target.exists())
        assertFalse(staging.exists())
        assertEquals(
            WorkspaceSnapshotRecoveryPhase.ROLLED_BACK,
            WorkspaceSnapshotRecovery.readJournal(journalFile)?.phase,
        )
        WorkspaceSnapshotRecovery.finishRolledBackTransaction(
            journalFile,
            WorkspaceSnapshotRecovery.readJournal(journalFile)!!,
            filesDir,
        )
        assertFalse(journalFile.exists())
    }

    @Test
    fun `commit-ready journal is finalized without rolling back the committed target`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val target = File(snapshots, "snapshot-commit-ready.zip").apply { writeText("ready") }
        val staging = File(snapshots, ".snapshot-commit-ready").apply { mkdirs() }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        WorkspaceSnapshotRecovery.writeJournal(
            journalFile,
            WorkspaceSnapshotRecoveryJournal(
                operation = WorkspaceSnapshotRecoveryOperation.CREATE,
                phase = WorkspaceSnapshotRecoveryPhase.COMMIT_READY,
                workspaceId = "ws",
                target = target.absolutePath,
                recovery = File(filesDir, ".snapshot-recovery-commit-ready").absolutePath,
                staging = staging.absolutePath,
                snapshotId = "snapshot-commit-ready",
            ),
        )

        WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, filesDir)

        assertTrue(target.exists())
        assertFalse(staging.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun `rollback cleanup cannot commit before filesystem rollback is confirmed`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        val journal = WorkspaceSnapshotRecoveryJournal(
            operation = WorkspaceSnapshotRecoveryOperation.CREATE,
            phase = WorkspaceSnapshotRecoveryPhase.DIRECTORY_ACTIVATED,
            workspaceId = "ws",
            target = File(snapshots, "snapshot-guard.zip").absolutePath,
            recovery = File(filesDir, ".snapshot-recovery-guard").absolutePath,
            staging = File(snapshots, ".snapshot-guard").absolutePath,
            snapshotId = "snapshot-guard",
        )
        WorkspaceSnapshotRecovery.writeJournal(journalFile, journal)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSnapshotRecovery.finishRolledBackTransaction(
                journalFile,
                journal,
                filesDir,
            )
        }
        assertTrue(journalFile.exists())
    }

    @Test
    fun `journal cannot authorize cleanup outside snapshot target`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val protected = File(filesDir, "protected.txt").apply { writeText("keep") }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        WorkspaceSnapshotRecovery.writeJournal(
            journalFile,
            WorkspaceSnapshotRecoveryJournal(
                operation = WorkspaceSnapshotRecoveryOperation.CREATE,
                phase = WorkspaceSnapshotRecoveryPhase.STAGING_READY,
                workspaceId = "ws",
                target = protected.absolutePath,
                recovery = File(filesDir, ".snapshot-recovery-guard").absolutePath,
                staging = File(snapshots, ".snapshot-guard").absolutePath,
                snapshotId = "snapshot-guard",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, filesDir)
        }
        assertTrue(protected.exists())
        assertEquals("keep", protected.readText())
    }

    @Test
    fun `committed cleanup never removes committed create target`() {
        val filesDir = temporaryFolder.newFolder("files")
        val snapshots = File(filesDir, "snapshots").apply { mkdirs() }
        val target = File(snapshots, "snapshot-ready.zip").apply { writeText("ready") }
        val staging = File(snapshots, ".snapshot-ready").apply { mkdirs() }
        val journalFile = WorkspaceSnapshotRecovery.journalFile(filesDir)
        val journal = WorkspaceSnapshotRecoveryJournal(
            operation = WorkspaceSnapshotRecoveryOperation.CREATE,
            phase = WorkspaceSnapshotRecoveryPhase.COMMITTED,
            workspaceId = "ws",
            target = target.absolutePath,
            recovery = File(filesDir, ".snapshot-recovery-ready").absolutePath,
            staging = staging.absolutePath,
            snapshotId = "snapshot-ready",
        )
        WorkspaceSnapshotRecovery.writeJournal(journalFile, journal)

        WorkspaceSnapshotRecovery.recoverPendingJournal(journalFile, filesDir)

        assertTrue(target.exists())
        assertFalse(staging.exists())
        assertFalse(journalFile.exists())
    }
}
