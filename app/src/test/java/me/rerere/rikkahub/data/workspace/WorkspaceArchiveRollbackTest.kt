package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
