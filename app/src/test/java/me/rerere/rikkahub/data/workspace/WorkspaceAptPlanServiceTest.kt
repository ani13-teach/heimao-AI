package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.WorkspaceAptPlanDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WORKSPACE = "ws-1"
private const val ASSISTANT = "as-1"
private const val CONVERSATION = "cv-1"
private const val TOOL_CALL = "call-1"
private const val BASE_FINGERPRINT = "base-fingerprint-1"

/**
 * Covers the plan service state machine: confirm through one compare-and-set, and execute only after
 * re-validating owner, expiry, the confirming tool call and the persisted request.
 *
 * The DAO is a fake that mirrors the compare-and-set semantics of the Room queries, and the executor
 * is a fake: this test pins the service's ordering and the state it records, while the real
 * environment-side ordering (lease, re-simulation, snapshot) lives in `WorkspaceRepository` and is
 * exercised by the repository/lease tests. The real SQL itself is covered by the DAO instrumented
 * test.
 */
class WorkspaceAptPlanServiceTest {

    private val dao = FakePlanDao()
    private val executor = FakeAptExecutor()
    private val service = WorkspaceAptPlanService(executor, dao)

    @Test
    fun `confirm binds a planned plan to the approving tool call`() = runBlocking {
        val plan = planned()

        val confirmed = service.confirm(plan.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, plan.planFingerprint)

        assertEquals(WorkspaceAptPlanState.CONFIRMED, confirmed.state)
        assertEquals(TOOL_CALL, confirmed.confirmedToolCallId)
    }

    @Test
    fun `confirm refuses a foreign, expired or already confirmed plan`() = runBlocking {
        val foreign = planned()
        expectRejection { service.confirm(foreign.planId, "other", ASSISTANT, CONVERSATION, TOOL_CALL, foreign.planFingerprint) }
        expectRejection { service.confirm(foreign.planId, WORKSPACE, "other", CONVERSATION, TOOL_CALL, foreign.planFingerprint) }
        expectRejection { service.confirm(foreign.planId, WORKSPACE, ASSISTANT, "other", TOOL_CALL, foreign.planFingerprint) }
        expectRejection { service.confirm(foreign.planId, WORKSPACE, ASSISTANT, CONVERSATION, "", foreign.planFingerprint) }
        expectRejection { service.confirm(foreign.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, "wrong-hash") }
        assertEquals(WorkspaceAptPlanState.PLANNED, dao.get(foreign.planId)?.state)

        val expired = planned()
        dao.mutate(expired.planId) { it.copy(expiresAt = System.currentTimeMillis() - 1) }
        expectRejection { service.confirm(expired.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, expired.planFingerprint) }
        assertEquals(WorkspaceAptPlanState.PLANNED, dao.get(expired.planId)?.state)

        val live = planned()
        service.confirm(live.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, live.planFingerprint)
        expectRejection { service.confirm(live.planId, WORKSPACE, ASSISTANT, CONVERSATION, "call-2", live.planFingerprint) }
        assertEquals(TOOL_CALL, dao.get(live.planId)?.confirmedToolCallId)
    }

    @Test
    fun `execute rebuilds the request from the row and records success`() = runBlocking {
        val plan = planned()
        service.confirm(plan.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, plan.planFingerprint)
        executor.report = WorkspaceAptExecutionReport.Exited(0)

        val outcome = service.execute(plan.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, plan.planFingerprint)

        assertEquals(WorkspaceAptPlanState.SUCCEEDED, outcome.state)
        assertEquals(0, outcome.exitCode)
        // The request comes from the persisted packages, never from the caller.
        assertEquals(WorkspaceAptRequest.install(listOf("htop")), executor.seenRequest)
        assertEquals(BASE_FINGERPRINT, executor.seenBase)
        assertEquals(plan.planFingerprint, executor.seenPlan)
        assertEquals(1, executor.executions)
        assertEquals(listOf(WorkspaceAptPlanState.SUCCEEDED), executor.terminalStates)

        val stored = dao.get(plan.planId)
        assertEquals(WorkspaceAptPlanState.SUCCEEDED, stored?.state)
        assertEquals(outcome.operationId, stored?.operationId)
    }

    @Test
    fun `execute maps a confirmed failure to RECOVERY_REQUIRED and an unknown exit to UNKNOWN`() = runBlocking {
        val failed = planned()
        service.confirm(failed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, failed.planFingerprint)
        executor.report = WorkspaceAptExecutionReport.Exited(100)

        val failedOutcome = service.execute(failed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, failed.planFingerprint)
        assertEquals(WorkspaceAptPlanState.RECOVERY_REQUIRED, failedOutcome.state)
        assertEquals(100, failedOutcome.exitCode)
        assertEquals(WorkspaceAptPlanState.RECOVERY_REQUIRED, dao.get(failed.planId)?.state)

        val unknown = planned()
        service.confirm(unknown.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, unknown.planFingerprint)
        executor.report = WorkspaceAptExecutionReport.ExitUnknown

        val unknownOutcome = service.execute(unknown.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, unknown.planFingerprint)
        assertEquals(WorkspaceAptPlanState.UNKNOWN, unknownOutcome.state)
        assertNull(unknownOutcome.exitCode)
        assertEquals(WorkspaceAptPlanState.UNKNOWN, dao.get(unknown.planId)?.state)
    }

    @Test
    fun `execute refuses an unconfirmed, foreign, replayed or expired plan`() = runBlocking {
        val unconfirmed = planned()
        expectRejection { service.execute(unconfirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, unconfirmed.planFingerprint) }

        val confirmed = planned()
        service.confirm(confirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, confirmed.planFingerprint)
        expectRejection { service.execute(confirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, "wrong-hash") }
        expectRejection { service.execute(confirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, "call-2", confirmed.planFingerprint) }
        expectRejection { service.execute(confirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, "", confirmed.planFingerprint) }
        expectRejection { service.execute(confirmed.planId, "other", ASSISTANT, CONVERSATION, TOOL_CALL, confirmed.planFingerprint) }
        expectRejection { service.execute(confirmed.planId, WORKSPACE, "other", CONVERSATION, TOOL_CALL, confirmed.planFingerprint) }
        expectRejection { service.execute(confirmed.planId, WORKSPACE, ASSISTANT, "other", TOOL_CALL, confirmed.planFingerprint) }
        expectRejection { service.execute("does-not-exist", WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, confirmed.planFingerprint) }
        // Nothing reached apt and no plan was consumed.
        assertEquals(0, executor.executions)
        assertEquals(WorkspaceAptPlanState.CONFIRMED, dao.get(confirmed.planId)?.state)

        dao.mutate(confirmed.planId) { it.copy(expiresAt = System.currentTimeMillis() - 1) }
        expectRejection { service.execute(confirmed.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, confirmed.planFingerprint) }
        assertEquals(0, executor.executions)
    }

    @Test
    fun `execute surfaces a rejection from the environment without a terminal state`() = runBlocking {
        val plan = planned()
        service.confirm(plan.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, plan.planFingerprint)
        executor.report = WorkspaceAptExecutionReport.Rejected("fingerprint changed")

        expectRejection { service.execute(plan.planId, WORKSPACE, ASSISTANT, CONVERSATION, TOOL_CALL, plan.planFingerprint) }

        // The plan never reached EXECUTING, so it stays confirmed and no terminal state was written.
        assertEquals(WorkspaceAptPlanState.CONFIRMED, dao.get(plan.planId)?.state)
        assertTrue(executor.terminalStates.isEmpty())
        assertEquals(0, executor.executions)
    }

    @Test
    fun `blocking plans are exactly the unfinished executions`() = runBlocking {
        assertTrue(service.blockingPlans().isEmpty())

        val executing = planned()
        dao.mutate(executing.planId) { it.copy(state = WorkspaceAptPlanState.EXECUTING) }
        val unknown = planned()
        dao.mutate(unknown.planId) { it.copy(state = WorkspaceAptPlanState.UNKNOWN) }
        val recovery = planned()
        dao.mutate(recovery.planId) { it.copy(state = WorkspaceAptPlanState.RECOVERY_REQUIRED) }
        val succeeded = planned()
        dao.mutate(succeeded.planId) { it.copy(state = WorkspaceAptPlanState.SUCCEEDED) }
        val plannedOnly = planned()

        assertEquals(
            listOf(executing.planId, unknown.planId, recovery.planId),
            service.blockingPlans().map { it.planId },
        )
        // A clean plan is not blocking.
        assertEquals(WorkspaceAptPlanState.PLANNED, dao.get(plannedOnly.planId)?.state)
    }

    private suspend fun planned(): WorkspaceAptPlanEntity = service.createPlan(
        workspaceId = WORKSPACE,
        assistantId = ASSISTANT,
        conversationId = CONVERSATION,
        request = WorkspaceAptRequest.install(listOf("htop")),
    )

    private fun expectRejection(block: suspend () -> Unit) {
        val failure = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue(
            "expected a rejection but got $failure",
            failure is IllegalArgumentException || failure is IllegalStateException,
        )
    }

    private class FakePlanDao : WorkspaceAptPlanDAO {
        private val plans = linkedMapOf<String, WorkspaceAptPlanEntity>()

        fun get(planId: String): WorkspaceAptPlanEntity? = plans[planId]

        fun mutate(planId: String, transform: (WorkspaceAptPlanEntity) -> WorkspaceAptPlanEntity) {
            val current = plans[planId] ?: error("unknown plan $planId")
            plans[planId] = transform(current)
        }

        override suspend fun insert(plan: WorkspaceAptPlanEntity) {
            check(plans.putIfAbsent(plan.planId, plan) == null) { "duplicate plan id" }
        }

        override suspend fun getById(planId: String): WorkspaceAptPlanEntity? = plans[planId]

        override suspend fun confirm(
            planId: String,
            workspaceId: String,
            assistantId: String,
            conversationId: String,
            toolCallId: String,
            now: Long,
        ): Int {
            val plan = plans[planId] ?: return 0
            if (plan.workspaceId != workspaceId ||
                plan.assistantId != assistantId ||
                plan.conversationId != conversationId ||
                plan.state != WorkspaceAptPlanState.PLANNED ||
                plan.expiresAt <= now
            ) {
                return 0
            }
            plans[planId] = plan.copy(
                state = WorkspaceAptPlanState.CONFIRMED,
                confirmedToolCallId = toolCallId,
            )
            return 1
        }

        override suspend fun start(planId: String, toolCallId: String, operationId: String?, now: Long): Int {
            val plan = plans[planId] ?: return 0
            if (plan.confirmedToolCallId != toolCallId || plan.state != WorkspaceAptPlanState.CONFIRMED || plan.expiresAt <= now) return 0
            plans[planId] = plan.copy(
                state = WorkspaceAptPlanState.EXECUTING,
                operationId = operationId ?: plan.operationId,
            )
            return 1
        }

        override suspend fun finish(planId: String, state: String, operationId: String?): Int {
            val plan = plans[planId] ?: return 0
            if (plan.state != WorkspaceAptPlanState.EXECUTING) return 0
            plans[planId] = plan.copy(state = state, operationId = operationId ?: plan.operationId)
            return 1
        }

        override suspend fun getInterrupted(): List<WorkspaceAptPlanEntity> =
            plans.values.filter { it.state == WorkspaceAptPlanState.EXECUTING }.sortedBy { it.createdAt }

        override suspend fun markRecoveryRestored(): Int {
            val blocked = plans.values.filter { it.state == WorkspaceAptPlanState.RECOVERY_REQUIRED }
            blocked.forEach { plans[it.planId] = it.copy(state = WorkspaceAptPlanState.RESTORED) }
            return blocked.size
        }

        override suspend fun markProcessStopped(planId: String, operationId: String): Int {
            val plan = plans[planId] ?: return 0
            if (plan.operationId != operationId ||
                plan.state !in setOf(WorkspaceAptPlanState.EXECUTING, WorkspaceAptPlanState.UNKNOWN)
            ) return 0
            plans[planId] = plan.copy(state = WorkspaceAptPlanState.RECOVERY_REQUIRED)
            return 1
        }

        override suspend fun getBlockingPlans(): List<WorkspaceAptPlanEntity> = plans.values
            .filter {
                it.state == WorkspaceAptPlanState.EXECUTING ||
                    it.state == WorkspaceAptPlanState.UNKNOWN ||
                    it.state == WorkspaceAptPlanState.RECOVERY_REQUIRED
            }
            .sortedBy { it.createdAt }
    }

    private class FakeAptExecutor : WorkspaceAptExecutor {
        var report: WorkspaceAptExecutionReport = WorkspaceAptExecutionReport.Exited(0)
        var executions = 0
        val terminalStates = mutableListOf<String>()
        var seenRequest: WorkspaceAptRequest? = null
        var seenBase: String? = null
        var seenPlan: String? = null

        override suspend fun simulateSharedApt(
            workspaceId: String,
            request: WorkspaceAptRequest,
        ): Pair<String, WorkspaceAptSimulation> = BASE_FINGERPRINT to WorkspaceAptSimulation(
            request = request,
            installs = request.packages.map { WorkspaceAptPackageChange(it, toVersion = "1.0") },
        )

        override suspend fun executeSharedAptPlan(
            workspaceId: String,
            request: WorkspaceAptRequest,
            expectedBaseFingerprint: String,
            expectedPlanFingerprint: String,
            operationId: String,
            persistExecuting: suspend (operationId: String) -> Boolean,
            persistTerminal: suspend (state: String) -> Unit,
        ): WorkspaceAptExecutionReport {
            seenRequest = request
            seenBase = expectedBaseFingerprint
            seenPlan = expectedPlanFingerprint
            val rejection = report as? WorkspaceAptExecutionReport.Rejected
            if (rejection != null) return rejection
            if (!persistExecuting(operationId)) {
                return WorkspaceAptExecutionReport.Rejected("the plan is no longer ready to execute")
            }
            executions++
            val state = report.terminalPlanState()
            persistTerminal(state)
            terminalStates += state
            return report
        }
    }
}
