package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.withLock

/**
 * Thrown when a runtime lease conflicts with one that is already active.
 *
 * Callers surface this as "busy" instead of queueing: a delete, an install, a switch or an
 * archive must never sit in a queue holding a database transaction while a terminal is open.
 */
class WorkspaceBusyException(message: String) : IllegalStateException(message)

/** Gate keys shared by several workspaces. */
object WorkspaceRuntimeGates {
    /**
     * Every workspace that runs on the public Linux rootfs shares this gate, so one workspace's
     * install/upgrade/restore can exclude commands in all the other workspaces using the same
     * rootfs without touching workspaces that still have a private one.
     */
    const val SHARED_ENVIRONMENT = "shared-environment"
}

/**
 * Marks the coroutine tree that owns an archive batch.
 *
 * Reentrancy is decided by this token, not by the calling thread: nested steps of the same batch
 * inherit it through their coroutine context, while an unrelated coroutine (a second backup, a UI
 * action) carries no token and is refused. Thread identity cannot express that, because a batch may
 * resume on a different thread after any suspension point.
 */
class WorkspaceArchiveBatchToken internal constructor() :
    AbstractCoroutineContextElement(WorkspaceArchiveBatchToken) {
    companion object Key : CoroutineContext.Key<WorkspaceArchiveBatchToken>
}

/**
 * Single answer to "is this workspace running right now?", used by every path that mutates or
 * reads a Linux environment.
 *
 * Two lease kinds, both non-blocking once acquired:
 * - **RUN** leases coexist. Terminals, shell commands and Linux file operations hold one.
 * - **EXCLUSIVE** leases conflict with every other lease of the same gate and report
 *   [WorkspaceBusyException] immediately. Workspace delete, environment switch, rootfs install
 *   and public-environment maintenance hold one.
 *
 * A workspace lease covers two gates at once: the workspace itself, plus the shared environment
 * when the workspace currently uses the public rootfs. An environment-wide exclusive lease
 * therefore sees every run in progress on that environment, while a lease on one workspace only
 * conflicts with other leases on that same workspace.
 *
 * [withEnvironmentArchiveLease] is the outermost third level: while a backup batch holds it, no
 * new lease of any kind can be granted on any gate.
 *
 * Lock order across the app is catalog -> coordinator -> workspace mutex, so an archive batch that
 * already holds the catalog lock can always take this gate next without a cycle. Nothing here ever
 * waits for a lease to drain: a conflicting request reports [WorkspaceBusyException].
 *
 * Lock order: this coordinator's [ReentrantLock] is a leaf lock. It is never held while another
 * lock (catalog, workspace mutex, database) is acquired, so it cannot participate in a cycle.
 */
class WorkspaceRuntimeCoordinator {

    private val gate = ReentrantLock()
    private val workspaceStates = HashMap<String, WorkspaceGateState>()
    private val environmentStates = HashMap<String, WorkspaceGateState>()
    private var activeLeases = 0
    private var archiveBatchOwner: WorkspaceArchiveBatchToken? = null
    private var maintenanceLeases = 0

    /** True while a public-environment maintenance session holds its exclusive lease. */
    val isMaintenanceActive: Boolean
        get() = gate.withLock { maintenanceLeases > 0 }

    /** Number of RUN and EXCLUSIVE leases currently held. */
    val activeLeaseCount: Int
        get() = gate.withLock { activeLeases }

    /** True when nothing runs on [workspaceId]. */
    fun isIdle(workspaceId: String): Boolean =
        gate.withLock { workspaceStates[workspaceId]?.isIdle ?: true }

    /** True when nothing runs against [environmentKey]. */
    fun isEnvironmentIdle(environmentKey: String): Boolean =
        gate.withLock { environmentStates[environmentKey]?.isIdle ?: true }

    /**
     * Acquires a RUN lease. Never suspends and never waits: a conflicting EXCLUSIVE lease or an
     * active archive batch throws [WorkspaceBusyException].
     */
    fun acquireRunLease(
        workspaceId: String,
        environmentKey: String? = null,
    ): WorkspaceRuntimeLease = acquireMemberLease(
        workspaceId = workspaceId,
        environmentKey = environmentKey,
        exclusive = false,
    )

    /**
     * Acquires an EXCLUSIVE lease for one workspace. Fails as soon as anything else runs on that
     * workspace or on its environment: a member mutation is also refused while any other tenant of
     * the same environment runs, which is deliberately conservative.
     */
    fun acquireExclusiveLease(
        workspaceId: String,
        environmentKey: String? = null,
    ): WorkspaceRuntimeLease = acquireMemberLease(
        workspaceId = workspaceId,
        environmentKey = environmentKey,
        exclusive = true,
    )

    /**
     * Acquires an EXCLUSIVE lease over a whole environment, ignoring which workspace asked.
     * Nothing may run on any workspace bound to that environment while it is held.
     */
    fun acquireEnvironmentExclusiveLease(
        environmentKey: String,
        maintenance: Boolean = false,
    ): WorkspaceRuntimeLease = gate.withLock {
        requireNoArchiveBatch()
        val state = environmentStates.getOrPut(environmentKey) { WorkspaceGateState() }
        if (!state.isIdle) {
            throw WorkspaceBusyException("Shared Linux environment is in use")
        }
        state.exclusive = true
        if (maintenance) maintenanceLeases++
        activeLeases++
        WorkspaceRuntimeLease(
            coordinator = this,
            workspaceId = null,
            environmentKey = environmentKey,
            exclusive = true,
            maintenance = maintenance,
        )
    }

    /**
     * Global gate for a portable-backup batch. It is the outermost lock: while it is held no
     * workspace lease can be granted, so the archive never races a terminal, an install or an
     * environment switch.
     *
     * Reentrant only for the coroutine tree that entered it first: nested steps inherit the batch
     * token through their context and pass straight through. A different coroutine, including a
     * second backup triggered by the user, is refused with [WorkspaceBusyException] instead of
     * joining the running batch, because two batch owners could otherwise exchange directories at
     * the same time.
     *
     * @param waitForIdleMillis kept for source compatibility. The batch never waits: a running lease
     *   is reported as busy so a backup can never stall behind a terminal that may never end.
     */
    suspend fun <T> withEnvironmentArchiveLease(
        waitForIdleMillis: Long = 0L,
        block: suspend () -> T,
    ): T {
        if (currentCoroutineContext()[WorkspaceArchiveBatchToken] != null) {
            return block()
        }
        val token = WorkspaceArchiveBatchToken()
        enterArchiveBatch(token)
        return try {
            withContext(token) { block() }
        } finally {
            exitArchiveBatch(token)
        }
    }

    private fun acquireMemberLease(
        workspaceId: String,
        environmentKey: String?,
        exclusive: Boolean,
    ): WorkspaceRuntimeLease = gate.withLock {
        requireNoArchiveBatch()
        val workspaceState = workspaceStates.getOrPut(workspaceId) { WorkspaceGateState() }
        val environmentState = environmentKey?.let { key ->
            environmentStates.getOrPut(key) { WorkspaceGateState() }
        }
        if (exclusive) {
            if (!workspaceState.isIdle) throw WorkspaceBusyException("Workspace is running")
            if (environmentState != null && !environmentState.isIdle) {
                throw WorkspaceBusyException("Shared Linux environment is in use")
            }
        } else {
            if (workspaceState.exclusive) throw WorkspaceBusyException("Workspace is busy")
            if (environmentState != null && environmentState.exclusive) {
                throw WorkspaceBusyException("Shared Linux environment is in use")
            }
        }
        // Both gates are acquired in the same mode and released from the same flag, so a member
        // exclusive lease can never leave the environment permanently busy.
        if (exclusive) {
            workspaceState.exclusive = true
            environmentState?.let { it.exclusive = true }
        } else {
            workspaceState.runLeases++
            environmentState?.let { it.runLeases++ }
        }
        activeLeases++
        WorkspaceRuntimeLease(
            coordinator = this,
            workspaceId = workspaceId,
            environmentKey = environmentKey,
            exclusive = exclusive,
            maintenance = false,
        )
    }

    internal fun release(
        workspaceId: String?,
        environmentKey: String?,
        exclusive: Boolean,
        maintenance: Boolean,
    ) = gate.withLock {
        workspaceId?.let { id ->
            workspaceStates[id]?.let { state ->
                if (exclusive) {
                    state.exclusive = false
                } else {
                    state.runLeases = (state.runLeases - 1).coerceAtLeast(0)
                }
                if (state.isIdle) workspaceStates.remove(id)
            }
        }
        environmentKey?.let { key ->
            environmentStates[key]?.let { state ->
                if (exclusive) {
                    state.exclusive = false
                } else {
                    state.runLeases = (state.runLeases - 1).coerceAtLeast(0)
                }
                if (state.isIdle) environmentStates.remove(key)
            }
        }
        if (maintenance) maintenanceLeases = (maintenanceLeases - 1).coerceAtLeast(0)
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
    }

    private fun requireNoArchiveBatch() {
        if (archiveBatchOwner != null) {
            throw WorkspaceBusyException("Workspace archive is running")
        }
    }

    private fun enterArchiveBatch(token: WorkspaceArchiveBatchToken) = gate.withLock {
        if (archiveBatchOwner != null) {
            throw WorkspaceBusyException("Another workspace archive is running")
        }
        if (activeLeases > 0) {
            throw WorkspaceBusyException("Workspace runtime is busy")
        }
        archiveBatchOwner = token
    }

    private fun exitArchiveBatch(token: WorkspaceArchiveBatchToken) = gate.withLock {
        if (archiveBatchOwner === token) {
            archiveBatchOwner = null
        }
    }

    private class WorkspaceGateState {
        var runLeases = 0
        var exclusive = false

        val isIdle: Boolean
            get() = runLeases == 0 && !exclusive
    }
}

/**
 * A granted RUN or EXCLUSIVE lease. Closing is idempotent and may happen on any thread, so a
 * lease can be handed to a terminal session and released when that session ends.
 */
class WorkspaceRuntimeLease internal constructor(
    private val coordinator: WorkspaceRuntimeCoordinator,
    private val workspaceId: String?,
    private val environmentKey: String?,
    private val exclusive: Boolean,
    private val maintenance: Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            coordinator.release(workspaceId, environmentKey, exclusive, maintenance)
        }
    }
}
