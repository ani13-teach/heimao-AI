package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState

/**
 * Persistence for public apt plans.
 *
 * Every state transition is a compare-and-set: the update statement repeats the state it expects to
 * replace and returns the number of affected rows. Callers must treat `0` as "the plan was not in
 * the expected state" and must not continue, so two concurrent confirmations or a replayed tool
 * call can never start the same plan twice.
 */
@Dao
interface WorkspaceAptPlanDAO {
    /** Inserts a new plan. A duplicate plan id is rejected instead of overwriting a live plan. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(plan: WorkspaceAptPlanEntity)

    @Query("SELECT * FROM workspace_apt_plans WHERE plan_id = :planId")
    suspend fun getById(planId: String): WorkspaceAptPlanEntity?

    /**
     * Confirms a `PLANNED` plan for the exact workspace/assistant/conversation it was planned in,
     * while the confirmation window is still open, and binds it to [toolCallId].
     *
     * @return number of updated rows; `0` means the plan is missing, belongs to another context,
     * was already confirmed/consumed, or [now] is past `expires_at`.
     */
    @Query(
        """
        UPDATE workspace_apt_plans
        SET state = 'CONFIRMED', confirmed_tool_call_id = :toolCallId
        WHERE plan_id = :planId
          AND workspace_id = :workspaceId
          AND assistant_id = :assistantId
          AND conversation_id = :conversationId
          AND state = 'PLANNED'
          AND expires_at > :now
        """
    )
    suspend fun confirm(
        planId: String,
        workspaceId: String,
        assistantId: String,
        conversationId: String,
        toolCallId: String,
        now: Long,
    ): Int

    /**
     * Moves the confirmed plan into execution and records the operation that will run it. Only the
     * tool call that confirmed the plan may start it.
     *
     * @param operationId identifier persisted together with `EXECUTING`; `null` keeps a stored value.
     * @return number of updated rows; `0` means the plan is not `CONFIRMED` with [toolCallId].
     */
    @Query(
        """
        UPDATE workspace_apt_plans
        SET state = 'EXECUTING', operation_id = COALESCE(:operationId, operation_id)
        WHERE plan_id = :planId
          AND confirmed_tool_call_id = :toolCallId
          AND state = 'CONFIRMED'
          AND expires_at > :now
        """
    )
    suspend fun start(
        planId: String,
        toolCallId: String,
        operationId: String? = null,
        now: Long,
    ): Int

    /**
     * Finalizes a plan that is currently `EXECUTING`.
     *
     * @param state terminal [WorkspaceAptPlanState] to record.
     * @param operationId operation that executed the plan; `null` keeps the stored value.
     * @return number of updated rows; `0` means the plan is not `EXECUTING`.
     */
    @Query(
        """
        UPDATE workspace_apt_plans
        SET state = :state, operation_id = COALESCE(:operationId, operation_id)
        WHERE plan_id = :planId AND state = 'EXECUTING'
        """
    )
    suspend fun finish(
        planId: String,
        state: String,
        operationId: String? = null,
    ): Int

    /**
     * Plans left in `EXECUTING`, i.e. interrupted by process death before [finish] ran, oldest
     * first.
     */
    @Query(
        """
        SELECT * FROM workspace_apt_plans
        WHERE state = 'EXECUTING'
        ORDER BY created_at ASC
        """
    )
    suspend fun getInterrupted(): List<WorkspaceAptPlanEntity>

    /**
     * Plans that keep the public environment fail-closed: an apt process was started and the plan
     * did not reach a clean terminal state. `EXECUTING` and `UNKNOWN` also mean a process may still
     * be alive; `RECOVERY_REQUIRED` means it is gone but the rootfs may need its recovery snapshot.
     *
     * Read at startup and after every execution to re-derive the environment gate.
     */
    @Query(
        """
        SELECT * FROM workspace_apt_plans
        WHERE state IN ('EXECUTING', 'UNKNOWN', 'RECOVERY_REQUIRED')
        ORDER BY created_at ASC
        """
    )
    suspend fun getBlockingPlans(): List<WorkspaceAptPlanEntity>

    /** Only a confirmed, successful operator restore clears failed (not unknown) operations. */
    @Query("UPDATE workspace_apt_plans SET state = 'RESTORED' WHERE state = 'RECOVERY_REQUIRED'")
    suspend fun markRecoveryRestored(): Int

    /** Operator explicitly verified a lost apt process has stopped; still requires restoring rootfs. */
    @Query("UPDATE workspace_apt_plans SET state = 'RECOVERY_REQUIRED' WHERE plan_id = :planId AND operation_id = :operationId AND state IN ('EXECUTING', 'UNKNOWN')")
    suspend fun markProcessStopped(planId: String, operationId: String): Int
}
