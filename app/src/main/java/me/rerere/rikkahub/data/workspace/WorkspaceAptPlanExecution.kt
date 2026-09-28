package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState

/**
 * Environment-side half of a public apt plan.
 *
 * The plan service owns the persisted state machine; the repository owns the shared environment.
 * This boundary keeps the two apart: the service asks for a simulation or for a lease-guarded
 * execution and never for a raw shell, and it passes no model text across — only the validated
 * [WorkspaceAptRequest] read back from the plan row.
 */
interface WorkspaceAptExecutor {
    /**
     * Simulates a validated request against the shared rootfs under the exclusive environment
     * lease. Returns the environment fingerprint the plan was computed against and the parsed
     * simulation.
     */
    suspend fun simulateSharedApt(
        workspaceId: String,
        request: WorkspaceAptRequest,
    ): Pair<String, WorkspaceAptSimulation>

    /**
     * Re-validates a confirmed plan against the current shared environment and, only when the
     * simulation still matches, runs the fixed execution argv under one exclusive environment
     * lease.
     *
     * The order is deliberate and must not change:
     *
     * 1. take the exclusive lease and refuse while the environment is fail-closed;
     * 2. recompute the environment fingerprint and re-run the simulated argv;
     * 3. reject when the environment or the simulation differs from what was confirmed;
     * 4. take the recovery snapshot;
     * 5. call [persistExecuting] and continue only when it returns `true`;
     * 6. run the fixed argv;
     * 7. call [persistTerminal] with the terminal state before the lease may be released.
     *
     * When the process exit cannot be confirmed the lease is retained (never released) and the
     * terminal state is [WorkspaceAptPlanState.UNKNOWN]. A confirmed non-zero exit is
     * [WorkspaceAptPlanState.RECOVERY_REQUIRED]: nothing is restored automatically, so the operator
     * has to finish the recovery.
     *
     * @param expectedBaseFingerprint base fingerprint persisted with the plan.
     * @param expectedPlanFingerprint plan fingerprint persisted with the plan.
     * @param operationId identifier to persist with `EXECUTING` and the terminal state.
     * @param persistExecuting CASes the plan into `EXECUTING`; `true` only when it affected one row.
     * @param persistTerminal records the terminal state for an already `EXECUTING` plan.
     */
    suspend fun executeSharedAptPlan(
        workspaceId: String,
        request: WorkspaceAptRequest,
        expectedBaseFingerprint: String,
        expectedPlanFingerprint: String,
        operationId: String,
        persistExecuting: suspend (operationId: String) -> Boolean,
        persistTerminal: suspend (state: String) -> Unit,
    ): WorkspaceAptExecutionReport
}

/**
 * Outcome of one environment-side attempt for a confirmed plan.
 */
sealed interface WorkspaceAptExecutionReport {
    /**
     * apt was never started: the re-validation, the fingerprint check or the `EXECUTING`
     * compare-and-set refused it, so the persisted plan row is untouched.
     */
    data class Rejected(val reason: String) : WorkspaceAptExecutionReport

    /** apt was started and its process exit was confirmed. */
    data class Exited(val exitCode: Int) : WorkspaceAptExecutionReport

    /** apt was started but its exit could not be confirmed; the lease stays retained. */
    data object ExitUnknown : WorkspaceAptExecutionReport
}

/**
 * The terminal [WorkspaceAptPlanState] a finished attempt records for its plan.
 *
 * A confirmed exit code of `0` succeeds; any other confirmed exit is [WorkspaceAptPlanState.RECOVERY_REQUIRED]
 * because apt may have applied a partial change that the recovery snapshot has to undo. An
 * unconfirmed exit is [WorkspaceAptPlanState.UNKNOWN] because the process must not be assumed dead.
 * Both failure states keep the shared environment fail-closed.
 */
fun WorkspaceAptExecutionReport.terminalPlanState(): String = when (this) {
    is WorkspaceAptExecutionReport.Rejected ->
        error("A rejected plan has no terminal state")

    is WorkspaceAptExecutionReport.Exited ->
        if (exitCode == 0) WorkspaceAptPlanState.SUCCEEDED else WorkspaceAptPlanState.RECOVERY_REQUIRED

    WorkspaceAptExecutionReport.ExitUnknown -> WorkspaceAptPlanState.UNKNOWN
}
