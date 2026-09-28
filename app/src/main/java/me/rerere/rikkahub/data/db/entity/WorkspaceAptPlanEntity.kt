package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted states of a public apt plan.
 *
 * The values are stored verbatim in `workspace_apt_plans.state`, so they must stay stable across
 * releases. The lifecycle is:
 *
 * `PLANNED` -> `CONFIRMED` -> `EXECUTING` -> `SUCCEEDED` / `FAILED` / `RECOVERY_REQUIRED` / `UNKNOWN`
 *
 * with `CANCELLED` and `EXPIRED` as terminal states that are never executed. Only a plan that is
 * still `PLANNED`, not expired and tied to the exact conversation context may be confirmed.
 *
 * `EXECUTING`, `UNKNOWN` and `RECOVERY_REQUIRED` keep the public environment fail-closed after a
 * restart: a row in one of them means an apt process was started and did not finish cleanly, so the
 * shared environment is not handed to a new session until the plan is resolved explicitly.
 */
object WorkspaceAptPlanState {
    /** Planned by the assistant, waiting for an explicit user confirmation. */
    const val PLANNED = "PLANNED"

    /** The user confirmed the plan; the confirmation is bound to [WorkspaceAptPlanEntity.confirmedToolCallId]. */
    const val CONFIRMED = "CONFIRMED"

    /** The confirmed plan is being executed. A leftover `EXECUTING` row means the process died. */
    const val EXECUTING = "EXECUTING"

    /** Terminal state: the plan finished successfully. */
    const val SUCCEEDED = "SUCCEEDED"

    /** Terminal state: the plan finished with a failure that needs no environment recovery. */
    const val FAILED = "FAILED"

    /**
     * Terminal state: apt ran and did not finish cleanly, so the public rootfs may be half-changed.
     * The environment stays fail-closed until the retained recovery snapshot is restored.
     */
    const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"

    /**
     * Terminal state: apt was started but its exit could not be confirmed. The process may still be
     * alive, so the environment stays occupied and must not be restored or reused.
     */
    const val UNKNOWN = "UNKNOWN"

    /** Terminal state: the plan was abandoned before it finished executing. */
    const val CANCELLED = "CANCELLED"

    /** Terminal state: the confirmation window elapsed before the user confirmed. */
    const val EXPIRED = "EXPIRED"

    /** The operator restored the public rootfs from its pre-operation recovery point. */
    const val RESTORED = "RESTORED"
}

/**
 * A public apt plan that must survive process death until the user confirms or declines it.
 *
 * The row stores everything needed to re-validate the plan at confirmation time: the workspace,
 * assistant and conversation it was planned for, the package list, the environment fingerprint the
 * plan was computed against and the confirmation deadline. Executing apt is only allowed through a
 * compare-and-set transition on [WorkspaceAptPlanState], so a stale or forged confirmation cannot
 * start a command.
 */
@Entity(
    tableName = "workspace_apt_plans",
    indices = [
        Index(value = ["workspace_id", "created_at"]),
        Index(value = ["conversation_id"]),
    ],
)
data class WorkspaceAptPlanEntity(
    @PrimaryKey
    @ColumnInfo("plan_id")
    val planId: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** The apt action the plan describes, e.g. `install`. */
    @ColumnInfo("action")
    val action: String,
    /** JSON array of the requested package names, stored as planned. */
    @ColumnInfo("packages_json")
    val packagesJson: String,
    /** Fingerprint of the environment the plan was computed against. */
    @ColumnInfo("base_fingerprint")
    val baseFingerprint: String,
    /** Fingerprint of the plan itself (action + packages + base fingerprint). */
    @ColumnInfo("plan_fingerprint")
    val planFingerprint: String,
    /** JSON summary shown to the user for confirmation. */
    @ColumnInfo("summary_json")
    val summaryJson: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** Wall-clock deadline after which the plan can no longer be confirmed. */
    @ColumnInfo("expires_at")
    val expiresAt: Long,
    /** Tool call id that confirmed the plan; null while the plan is still unconfirmed. */
    @ColumnInfo("confirmed_tool_call_id")
    val confirmedToolCallId: String? = null,
    /** One of [WorkspaceAptPlanState]. */
    @ColumnInfo("state")
    val state: String,
    /** Identifier of the shell operation that executed the plan, when one was started. */
    @ColumnInfo("operation_id")
    val operationId: String? = null,
)
