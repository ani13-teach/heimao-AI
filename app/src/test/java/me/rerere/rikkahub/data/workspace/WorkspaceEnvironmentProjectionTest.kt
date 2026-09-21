package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The stored row keeps the workspace's private status forever, so reads must project the
 * environment it is actually bound to without ever rewriting what is stored.
 */
class WorkspaceEnvironmentProjectionTest {

    @Test
    fun `a private workspace is returned unchanged`() {
        val stored = workspace(shellStatus = WorkspaceShellStatus.READY.name, metadata = PRIVATE)

        val projected = WorkspaceEnvironmentProjection.project(
            entity = stored,
            shared = false,
            state = SharedEnvironmentState.Uninstalled,
        )

        assertSame(stored, projected)
    }

    @Test
    fun `a shared workspace is ready while the shared rootfs is installed`() {
        val stored = workspace(shellStatus = WorkspaceShellStatus.DISABLED.name, metadata = PRIVATE)

        val projected = WorkspaceEnvironmentProjection.project(
            entity = stored,
            shared = true,
            state = SharedEnvironmentState(installed = true, installPending = false, metadataJson = SHARED),
        )

        assertEquals(WorkspaceShellStatus.READY.name, projected.shellStatus)
        assertEquals(SHARED, projected.runtimeMetadata)
        // The row itself must keep the private values for a later switch back.
        assertEquals(WorkspaceShellStatus.DISABLED.name, stored.shellStatus)
        assertEquals(PRIVATE, stored.runtimeMetadata)
    }

    @Test
    fun `a shared workspace is disabled while the environment has no rootfs`() {
        val stored = workspace(shellStatus = WorkspaceShellStatus.READY.name, metadata = PRIVATE)

        val projected = WorkspaceEnvironmentProjection.project(
            entity = stored,
            shared = true,
            state = SharedEnvironmentState.Uninstalled,
        )

        assertEquals(WorkspaceShellStatus.DISABLED.name, projected.shellStatus)
        assertEquals(WorkspaceEnvironmentLayout.EMPTY_METADATA, projected.runtimeMetadata)
    }

    @Test
    fun `a shared workspace reports installing while an exchange is journaled`() {
        val projected = WorkspaceEnvironmentProjection.project(
            entity = workspace(),
            shared = true,
            state = SharedEnvironmentState(installed = true, installPending = true, metadataJson = SHARED),
        )

        assertEquals(WorkspaceShellStatus.INSTALLING.name, projected.shellStatus)
    }

    private fun workspace(
        shellStatus: String = WorkspaceShellStatus.DISABLED.name,
        metadata: String = PRIVATE,
    ): WorkspaceEntity = WorkspaceEntity(
        id = "workspace-id",
        name = "Workspace",
        root = "workspace-id",
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = 2L,
        runtimeMetadata = metadata,
    )

    private companion object {
        const val PRIVATE = """{"metadataVersion":1,"sourceUrl":"private"}"""
        const val SHARED = """{"metadataVersion":1,"sourceUrl":"shared"}"""
    }
}
