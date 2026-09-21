package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Effective state of the public Linux environment, as far as a workspace row is concerned.
 *
 * The `workspaces` table keeps whatever the workspace had while it owned a private rootfs. A
 * workspace bound to the shared environment therefore never has its stored status rewritten;
 * reads are projected through this state instead, so switching back to the private rootfs
 * restores the original value exactly.
 */
internal data class SharedEnvironmentState(
    val installed: Boolean,
    val installPending: Boolean,
    val metadataJson: String,
) {
    companion object {
        val Uninstalled = SharedEnvironmentState(
            installed = false,
            installPending = false,
            metadataJson = WorkspaceEnvironmentLayout.EMPTY_METADATA,
        )
    }
}

/**
 * Projects a stored workspace row onto the environment it is actually bound to.
 *
 * A shared workspace is READY only while the shared rootfs is installed, INSTALLING while an
 * exchange is journaled, and DISABLED while the shared environment has no rootfs yet.
 */
internal object WorkspaceEnvironmentProjection {

    fun project(
        entity: WorkspaceEntity,
        shared: Boolean,
        state: SharedEnvironmentState,
    ): WorkspaceEntity {
        if (!shared) return entity
        val status = when {
            state.installPending -> WorkspaceShellStatus.INSTALLING
            state.installed -> WorkspaceShellStatus.READY
            else -> WorkspaceShellStatus.DISABLED
        }
        return entity.copy(
            shellStatus = status.name,
            runtimeMetadata = if (state.installed) {
                state.metadataJson
            } else {
                WorkspaceEnvironmentLayout.EMPTY_METADATA
            },
        )
    }
}
