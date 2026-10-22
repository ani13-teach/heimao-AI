package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceArchiveRollbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `full database restore permits same ids but incremental import rejects them`() {
        assertEquals(
            emptySet<String>(),
            workspaceRestoreConflictIds(
                currentIds = setOf("same"),
                restoreIds = setOf("same"),
                fullDatabaseRestore = true,
            ),
        )
        assertEquals(
            setOf("same"),
            workspaceRestoreConflictIds(
                currentIds = setOf("same"),
                restoreIds = setOf("same", "new"),
                fullDatabaseRestore = false,
            ),
        )
    }

    @Test
    fun `full root replacement removes stale roots and rollback restores the complete old set`() {
        val target = temporaryFolder.newFolder("workspaces")
        File(target, "old-only/files").apply { mkdirs() }
        File(target, "same-id-old-root/files").apply { mkdirs() }
        File(target, "old-only/files/state.txt").writeText("old")
        val staging = temporaryFolder.newFolder("staging")
        File(staging, "archive-only/files").apply { mkdirs() }
        File(staging, "same-id-new-root/files").apply { mkdirs() }
        File(staging, "archive-only/files/state.txt").writeText("new")
        val recovery = File(temporaryFolder.root, "recovery/workspaces")
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)

        transaction.activate()
        assertEquals(
            setOf("archive-only", "same-id-new-root"),
            target.listFiles().orEmpty().map { it.name }.toSet(),
        )
        transaction.rollback()
        assertEquals(
            setOf("old-only", "same-id-old-root"),
            target.listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertEquals("old", File(target, "old-only/files/state.txt").readText())
        assertFalse(staging.exists())
        assertFalse(recovery.exists())
    }

    @Test
    fun `legacy folders share an existing recovery parent and roll back independently`() {
        val recoveryRoot = temporaryFolder.newFolder("legacy-recovery")
        val transactions = listOf("upload", "skills", "fonts").map { folder ->
            val target = temporaryFolder.newFolder(folder)
            File(target, "state.txt").writeText("old-$folder")
            val staging = temporaryFolder.newFolder("staged-$folder")
            File(staging, "state.txt").writeText("new-$folder")
            val recovery = File(recoveryRoot, folder)
            WorkspaceArchiveDirectoryTransaction(staging, target, recovery).also {
                it.activate()
                assertEquals("new-$folder", File(target, "state.txt").readText())
                assertEquals("old-$folder", File(recovery, "state.txt").readText())
            }
        }

        transactions.asReversed().forEach { it.rollback() }

        listOf("upload", "skills", "fonts").forEach { folder ->
            assertEquals("old-$folder", File(temporaryFolder.root, "$folder/state.txt").readText())
            assertFalse(File(recoveryRoot, folder).exists())
        }
    }

    @Test
    fun `legacy folder with no previous target uses an existing recovery parent`() {
        val recoveryRoot = temporaryFolder.newFolder("legacy-recovery")
        val target = File(temporaryFolder.root, "upload")
        val staging = temporaryFolder.newFolder("staged-upload")
        File(staging, "state.txt").writeText("new")
        val recovery = File(recoveryRoot, "upload")
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)

        transaction.activate()

        assertEquals("new", File(target, "state.txt").readText())
        assertFalse(recovery.exists())
        transaction.rollback()
        assertFalse(target.exists())
    }

    @Test
    fun `file blocking the recovery parent fails without moving existing data`() {
        val target = temporaryFolder.newFolder("upload")
        File(target, "state.txt").writeText("old")
        val staging = temporaryFolder.newFolder("staged-upload")
        File(staging, "state.txt").writeText("new")
        val blockedParent = temporaryFolder.newFile("legacy-recovery").apply { writeText("blocker") }
        val recovery = File(blockedParent, "upload")
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)

        assertThrows(IllegalArgumentException::class.java) { transaction.activate() }

        assertEquals("old", File(target, "state.txt").readText())
        assertEquals("new", File(staging, "state.txt").readText())
        assertEquals("blocker", blockedParent.readText())
        assertFalse(recovery.exists())
    }

    @Test
    fun `activation rollback restores the old directory after a failed commit`() {
        val target = temporaryFolder.newFolder("target")
        File(target, "state.txt").writeText("old")
        val staging = temporaryFolder.newFolder("staging")
        File(staging, "state.txt").writeText("new")
        val recovery = File(temporaryFolder.root, "recovery/target")
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)

        transaction.activate()
        try {
            check(File(target, "state.txt").readText() == "new")
            error("injected settings update failure")
        } catch (_: IllegalStateException) {
            transaction.rollback()
        }

        assertEquals("old", File(target, "state.txt").readText())
        assertFalse(staging.exists())
        assertFalse(recovery.exists())
    }
}
