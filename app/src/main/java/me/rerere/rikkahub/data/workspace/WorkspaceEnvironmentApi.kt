package me.rerere.rikkahub.data.workspace

import kotlin.coroutines.CoroutineContext
import me.rerere.workspace.WorkspaceShellContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement

/**
 * How one workspace is bound to a Linux environment.
 *
 * UI code must use this instead of reading the `environment` marker file itself: the marker is the
 * store's private format, and the same call also reports whether the shared rootfs is installed and
 * whether switching back to the private rootfs is still possible.
 */
data class WorkspaceEnvironmentSelection(
    val workspaceId: String,
    val root: String,
    /** True when the workspace runs on the public shared rootfs. */
    val shared: Boolean,
    /** True when the guest HOME is workspace-local instead of the rootfs's own `/root`. */
    val hasExternalHome: Boolean,
    /** Guest HOME directory, or null when the workspace row is gone. */
    val homeDirectory: File?,
    /** True while the shared environment has an installed, complete rootfs. */
    val sharedRootfsInstalled: Boolean,
    /** True when this workspace's own rootfs is installed, so switching back cannot break it. */
    val privateRootfsUsable: Boolean,
)

/**
 * Exclusive lease held for the whole lifetime of a public-environment maintenance terminal.
 *
 * The lease is granted before the recovery snapshot is written and is not released until [close]
 * is called, so no workspace can start running in the shared rootfs while the maintenance shell is
 * open.
 *
 * It is also a coroutine context element, which is how the repository recognizes *this* session:
 * run the terminal's work inside `withContext(lease) { ... }` and
 * [WorkspaceRepository.prepareMaintenanceContext] accepts it, while any other caller is refused.
 *
 * Close it only once the terminal process has really exited. [close] is idempotent, so a
 * composition or `DisposableEffect` cleanup may call it safely.
 */
class WorkspaceMaintenanceLease internal constructor(
    private val lease: AutoCloseable,
    private val anchor: AtomicReference<String?>,
    private val activeSession: AtomicReference<WorkspaceMaintenanceLease?>,
    private val anchorRoot: String,
    /** Context a maintenance terminal should run with; never patches the shared rootfs. */
    val context: WorkspaceShellContext,
) : AbstractCoroutineContextElement(Key), AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** Root of the workspace that hosts the maintenance shell. */
    val root: String get() = anchorRoot

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession.compareAndSet(this, null)
        anchor.compareAndSet(anchorRoot, null)
        lease.close()
    }

    internal companion object Key : CoroutineContext.Key<WorkspaceMaintenanceLease>
}
