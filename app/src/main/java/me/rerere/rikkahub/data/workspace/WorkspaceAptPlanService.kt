package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.dao.WorkspaceAptPlanDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Owns the persisted state machine of a public apt plan.
 *
 * The service never runs apt itself and never reads a package list from model text at execution
 * time: a plan is created from an already validated [WorkspaceAptRequest], confirmed through one
 * compare-and-set, and only then executed through [WorkspaceAptExecutor], which re-reads and
 * re-validates the request and holds the one exclusive environment lease.
 */
class WorkspaceAptPlanService(
    private val repository: WorkspaceAptExecutor,
    private val planDao: WorkspaceAptPlanDAO,
) {
    suspend fun createPlan(
        workspaceId: String,
        assistantId: String,
        conversationId: String,
        request: WorkspaceAptRequest,
    ): WorkspaceAptPlanEntity {
        val (base, simulation) = repository.simulateSharedApt(workspaceId, request)
        val now = System.currentTimeMillis()
        val plan = WorkspaceAptPlanEntity(
            planId = Uuid.random().toString(),
            workspaceId = workspaceId,
            assistantId = assistantId,
            conversationId = conversationId,
            action = request.action.wireName,
            packagesJson = buildJsonObject {
                put("packages", buildJsonArray { request.packages.forEach { add(JsonPrimitive(it)) } })
                put("purge", request.purge)
            }.toString(),
            baseFingerprint = base,
            planFingerprint = simulation.fingerprint(base),
            summaryJson = buildJsonArray { simulation.summaryLines().forEach { add(JsonPrimitive(it)) } }.toString(),
            createdAt = now,
            expiresAt = now + PLAN_VALIDITY_MILLIS,
            state = WorkspaceAptPlanState.PLANNED,
        )
        planDao.insert(plan)
        return plan
    }

    /**
     * Confirms a `PLANNED` plan for the exact workspace/assistant/conversation it was planned in,
     * while the confirmation window is still open, and binds it to [toolCallId].
     *
     * The transition is a single compare-and-set in the plan table: a plan that is missing, belongs
     * to another context, was already confirmed/consumed or is past its deadline is refused, so two
     * concurrent confirmations or a replayed tool call can never both win.
     *
     * @throws IllegalStateException when the compare-and-set updated nothing.
     */
    suspend fun confirm(
        planId: String,
        workspaceId: String,
        assistantId: String,
        conversationId: String,
        toolCallId: String,
        planFingerprint: String,
    ): WorkspaceAptPlanEntity {
        require(toolCallId.isNotBlank()) { "The approving tool call is required" }
        check(planDao.getById(planId)?.let {
            it.planFingerprint == planFingerprint &&
                it.state == WorkspaceAptPlanState.PLANNED &&
                it.workspaceId == workspaceId && it.assistantId == assistantId && it.conversationId == conversationId
        } == true) { "The approved apt plan does not match the current conversation" }
        val updated = planDao.confirm(
            planId = planId,
            workspaceId = workspaceId,
            assistantId = assistantId,
            conversationId = conversationId,
            toolCallId = toolCallId,
            now = System.currentTimeMillis(),
        )
        check(updated == 1) {
            "The apt plan is not pending confirmation for this workspace, assistant and conversation"
        }
        return planDao.getById(planId) ?: error("The apt plan disappeared after confirmation")
    }

    /**
     * Executes an already confirmed plan through [toolCallId].
     *
     * Everything that decides whether apt may run is re-checked here before the environment lease is
     * taken: the row must belong to the same workspace, assistant and conversation, it must still be
     * `CONFIRMED` and bound to [toolCallId], it must not have expired, and the request is rebuilt
     * from the row (never from model text). [WorkspaceAptExecutor] then re-validates the simulation,
     * takes the recovery snapshot and persists `EXECUTING` before the fixed argv runs.
     *
     * @throws IllegalStateException when the plan is unknown, owned by another context, not bound to
     *   [toolCallId], expired, or when the executor refuses to start it.
     */
    suspend fun execute(
        planId: String,
        workspaceId: String,
        assistantId: String,
        conversationId: String,
        toolCallId: String,
        planFingerprint: String,
    ): WorkspaceAptExecutionOutcome {
        val plan = requireExecutablePlan(
            planId = planId,
            planFingerprint = planFingerprint,
            workspaceId = workspaceId,
            assistantId = assistantId,
            conversationId = conversationId,
            toolCallId = toolCallId,
        )
        val request = plan.toAptRequest()
        // Validating once more before any state moves means a corrupted row can never leave a plan
        // stuck in EXECUTING behind it.
        requireExecutionArgv(request.executionCommand())
        val operationId = Uuid.random().toString()
        val report = repository.executeSharedAptPlan(
            workspaceId = workspaceId,
            request = request,
            expectedBaseFingerprint = plan.baseFingerprint,
            expectedPlanFingerprint = plan.planFingerprint,
            operationId = operationId,
            persistExecuting = { id ->
                planDao.start(planId, toolCallId, id, System.currentTimeMillis()) == 1
            },
            persistTerminal = { state ->
                check(planDao.finish(planId, state, operationId) == 1) {
                    "The apt operation state could not be saved"
                }
            },
        )
        return when (report) {
            is WorkspaceAptExecutionReport.Rejected ->
                throw IllegalStateException("The public apt plan was not executed: ${report.reason}")

            is WorkspaceAptExecutionReport.Exited -> WorkspaceAptExecutionOutcome(
                planId = planId,
                operationId = operationId,
                state = report.terminalPlanState(),
                exitCode = report.exitCode,
            )

            WorkspaceAptExecutionReport.ExitUnknown -> WorkspaceAptExecutionOutcome(
                planId = planId,
                operationId = operationId,
                state = report.terminalPlanState(),
                exitCode = null,
            )
        }
    }

    /** Only this immutable local row, not model-provided details, is eligible for approval UI. */
    suspend fun pendingPlan(planId: String, planFingerprint: String): WorkspaceAptPlanEntity? =
        planDao.getById(planId)?.takeIf {
            it.state == WorkspaceAptPlanState.PLANNED &&
                it.planFingerprint == planFingerprint &&
                it.expiresAt > System.currentTimeMillis()
        }

    /** Plans that keep the public environment fail-closed, for a read-only operator view. */
    suspend fun blockingPlans(): List<WorkspaceAptPlanEntity> = planDao.getBlockingPlans()

    private suspend fun requireExecutablePlan(
        planId: String,
        workspaceId: String,
        assistantId: String,
        conversationId: String,
        toolCallId: String,
        planFingerprint: String,
    ): WorkspaceAptPlanEntity {
        val plan = planDao.getById(planId) ?: throw IllegalStateException("Unknown apt plan")
        require(toolCallId.isNotBlank()) { "The approving tool call is required" }
        require(
            plan.workspaceId == workspaceId &&
                plan.assistantId == assistantId &&
                plan.conversationId == conversationId,
        ) {
            "The apt plan does not belong to this workspace, assistant and conversation"
        }
        require(plan.planFingerprint == planFingerprint) { "The apt plan fingerprint has changed" }
        require(plan.state == WorkspaceAptPlanState.CONFIRMED) {
            "The apt plan is not waiting to be executed"
        }
        require(plan.confirmedToolCallId == toolCallId) {
            "The apt plan is not confirmed by this tool call"
        }
        require(System.currentTimeMillis() < plan.expiresAt) { "The apt plan has expired" }
        return plan
    }

    private fun WorkspaceAptPlanEntity.toAptRequest(): WorkspaceAptRequest {
        val stored = JsonInstant.parseToJsonElement(packagesJson).jsonObject
        val packages = stored["packages"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val purge = stored["purge"]?.jsonPrimitive?.booleanOrNull ?: false
        return WorkspaceAptRequest.fromWireName(action, packages, purge)
    }

    private companion object {
        const val PLAN_VALIDITY_MILLIS = 10 * 60 * 1_000L
    }
}

/**
 * Final result of [WorkspaceAptPlanService.execute].
 *
 * [state] is one of [WorkspaceAptPlanState.SUCCEEDED], [WorkspaceAptPlanState.RECOVERY_REQUIRED] or
 * [WorkspaceAptPlanState.UNKNOWN]; [exitCode] is apt's exit code when its exit was confirmed and
 * `null` when it was not.
 */
data class WorkspaceAptExecutionOutcome(
    val planId: String,
    val operationId: String,
    val state: String,
    val exitCode: Int?,
)
