package me.rerere.rikkahub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import me.rerere.rikkahub.data.workspace.sanitizeWorkspaceDatabaseCopy
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceAptPlanDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: WorkspaceAptPlanDAO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workspaceAptPlanDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun plan(
        planId: String = "plan-1",
        workspaceId: String = "workspace-a",
        assistantId: String = "assistant-a",
        conversationId: String = "conversation-a",
        createdAt: Long = 100L,
        expiresAt: Long = 1_000L,
        state: String = WorkspaceAptPlanState.PLANNED,
    ) = WorkspaceAptPlanEntity(
        planId = planId,
        workspaceId = workspaceId,
        assistantId = assistantId,
        conversationId = conversationId,
        action = "install",
        packagesJson = """["htop","curl"]""",
        baseFingerprint = "base-1",
        planFingerprint = "plan-1",
        summaryJson = """{"title":"install htop curl"}""",
        createdAt = createdAt,
        expiresAt = expiresAt,
        state = state,
    )

    @Test
    fun insertAndGetByIdRoundTripsEveryField() = runBlocking {
        dao.insert(plan())

        val stored = dao.getById("plan-1")
        assertNotNull(stored)
        requireNotNull(stored)
        assertEquals("plan-1", stored.planId)
        assertEquals("workspace-a", stored.workspaceId)
        assertEquals("assistant-a", stored.assistantId)
        assertEquals("conversation-a", stored.conversationId)
        assertEquals("install", stored.action)
        assertEquals("""["htop","curl"]""", stored.packagesJson)
        assertEquals("base-1", stored.baseFingerprint)
        assertEquals("plan-1", stored.planFingerprint)
        assertEquals("""{"title":"install htop curl"}""", stored.summaryJson)
        assertEquals(100L, stored.createdAt)
        assertEquals(1_000L, stored.expiresAt)
        assertEquals(WorkspaceAptPlanState.PLANNED, stored.state)
        assertNull(stored.confirmedToolCallId)
        assertNull(stored.operationId)
    }

    @Test
    fun duplicatePlanIdIsRejectedInsteadOfOverwriting() = runBlocking {
        dao.insert(plan())

        val failure = runCatching { dao.insert(plan().copy(summaryJson = "other")) }

        assertTrue("duplicate plan id was accepted", failure.isFailure)
        assertEquals(
            """{"title":"install htop curl"}""",
            dao.getById("plan-1")?.summaryJson,
        )
    }

    @Test
    fun confirmBindsPlannedPlanToToolCallWithinWindow() = runBlocking {
        dao.insert(plan())

        val updated = dao.confirm(
            planId = "plan-1",
            workspaceId = "workspace-a",
            assistantId = "assistant-a",
            conversationId = "conversation-a",
            toolCallId = "call-1",
            now = 999L,
        )

        assertEquals(1, updated)
        val stored = dao.getById("plan-1")
        assertEquals(WorkspaceAptPlanState.CONFIRMED, stored?.state)
        assertEquals("call-1", stored?.confirmedToolCallId)
    }

    @Test
    fun confirmRejectsMismatchedContext() = runBlocking {
        dao.insert(plan())

        assertEquals(
            0,
            dao.confirm("plan-1", "workspace-b", "assistant-a", "conversation-a", "call-1", 999L),
        )
        assertEquals(
            0,
            dao.confirm("plan-1", "workspace-a", "assistant-b", "conversation-a", "call-1", 999L),
        )
        assertEquals(
            0,
            dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-b", "call-1", 999L),
        )
        assertEquals(WorkspaceAptPlanState.PLANNED, dao.getById("plan-1")?.state)
        assertNull(dao.getById("plan-1")?.confirmedToolCallId)
    }

    @Test
    fun confirmRejectsExpiredAndAlreadyConfirmedPlans() = runBlocking {
        dao.insert(plan(expiresAt = 1_000L))
        assertEquals(
            0,
            dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 1_000L),
        )

        dao.insert(plan(planId = "plan-2"))
        assertEquals(
            1,
            dao.confirm("plan-2", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L),
        )
        // A second confirmation with another tool call must not steal the plan.
        assertEquals(
            0,
            dao.confirm("plan-2", "workspace-a", "assistant-a", "conversation-a", "call-2", 900L),
        )
        assertEquals("call-1", dao.getById("plan-2")?.confirmedToolCallId)
    }

    @Test
    fun startOnlyAcceptsTheConfirmingToolCall() = runBlocking {
        dao.insert(plan())
        dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L)

        assertEquals(0, dao.start("plan-1", "call-other", now = 900L))
        assertEquals(WorkspaceAptPlanState.CONFIRMED, dao.getById("plan-1")?.state)

        assertEquals(1, dao.start("plan-1", "call-1", now = 900L))
        assertEquals(WorkspaceAptPlanState.EXECUTING, dao.getById("plan-1")?.state)

        // A replayed start must not run the plan twice.
        assertEquals(0, dao.start("plan-1", "call-1", now = 900L))
    }

    @Test
    fun startRejectsUnconfirmedPlan() = runBlocking {
        dao.insert(plan())

        assertEquals(0, dao.start("plan-1", "call-1", now = 900L))
        assertEquals(WorkspaceAptPlanState.PLANNED, dao.getById("plan-1")?.state)
    }

    @Test
    fun startRejectsPlanThatExpiredAfterConfirmation() = runBlocking {
        dao.insert(plan())
        assertEquals(1, dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L))
        assertEquals(0, dao.start("plan-1", "call-1", operationId = "op-1", now = 1_000L))
        assertEquals(WorkspaceAptPlanState.CONFIRMED, dao.getById("plan-1")?.state)
    }

    @Test
    fun onlyTheMatchingUnknownOperationCanBeAcknowledged() = runBlocking {
        dao.insert(plan(planId = "unknown", state = WorkspaceAptPlanState.UNKNOWN).copy(operationId = "op-1"))
        dao.insert(plan(planId = "live", state = WorkspaceAptPlanState.EXECUTING).copy(operationId = "op-2"))
        assertEquals(0, dao.markProcessStopped("unknown", "op-other"))
        assertEquals(1, dao.markProcessStopped("unknown", "op-1"))
        assertEquals(WorkspaceAptPlanState.RECOVERY_REQUIRED, dao.getById("unknown")?.state)
        assertEquals(0, dao.markProcessStopped("unknown", "op-1"))
        assertEquals(WorkspaceAptPlanState.EXECUTING, dao.getById("live")?.state)
        assertEquals(1, dao.markProcessStopped("live", "op-2"))
    }

    @Test
    fun restoredRecoveryClearsOnlyConfirmedExitedFailures() = runBlocking {
        dao.insert(plan(planId = "failed", state = WorkspaceAptPlanState.RECOVERY_REQUIRED))
        dao.insert(plan(planId = "unknown", state = WorkspaceAptPlanState.UNKNOWN))
        assertEquals(1, dao.markRecoveryRestored())
        assertEquals(WorkspaceAptPlanState.RESTORED, dao.getById("failed")?.state)
        assertEquals(WorkspaceAptPlanState.UNKNOWN, dao.getById("unknown")?.state)
        assertEquals(listOf("unknown"), dao.getBlockingPlans().map { it.planId })
    }

    @Test
    fun startRecordsTheOperationUsedToExecuteThePlan() = runBlocking {
        dao.insert(plan())
        dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L)

        assertEquals(1, dao.start("plan-1", "call-1", operationId = "op-1", now = 900L))

        val stored = dao.getById("plan-1")
        assertEquals(WorkspaceAptPlanState.EXECUTING, stored?.state)
        assertEquals("op-1", stored?.operationId)
    }

    @Test
    fun getBlockingPlansReturnsOnlyUnfinishedExecutionsOldestFirst() = runBlocking {
        // Confirmed but never started, and finished cleanly: neither blocks the environment.
        dao.insert(plan(planId = "plan-planned", createdAt = 5L))
        dao.insert(plan(planId = "plan-done", createdAt = 10L))
        dao.confirm("plan-done", "workspace-a", "assistant-a", "conversation-a", "call-done", 900L)
        dao.start("plan-done", "call-done", now = 900L)
        dao.finish("plan-done", WorkspaceAptPlanState.SUCCEEDED)

        // Interrupted while executing: blocks.
        dao.insert(plan(planId = "plan-executing", createdAt = 20L))
        dao.confirm("plan-executing", "workspace-a", "assistant-a", "conversation-a", "call-exec", 900L)
        dao.start("plan-executing", "call-exec", now = 900L)

        // Exit could not be confirmed, and apt failed: both keep the environment fail-closed.
        dao.insert(plan(planId = "plan-unknown", createdAt = 15L, state = WorkspaceAptPlanState.UNKNOWN))
        dao.insert(
            plan(
                planId = "plan-recovery",
                createdAt = 30L,
                state = WorkspaceAptPlanState.RECOVERY_REQUIRED,
            ),
        )

        assertEquals(
            listOf("plan-unknown", "plan-executing", "plan-recovery"),
            dao.getBlockingPlans().map { it.planId },
        )
    }

    @Test
    fun finishOnlyAppliesToExecutingPlan() = runBlocking {
        dao.insert(plan())
        dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L)

        assertEquals(0, dao.finish("plan-1", WorkspaceAptPlanState.SUCCEEDED))
        assertEquals(WorkspaceAptPlanState.CONFIRMED, dao.getById("plan-1")?.state)

        dao.start("plan-1", "call-1", now = 900L)
        assertEquals(
            1,
            dao.finish("plan-1", WorkspaceAptPlanState.SUCCEEDED, operationId = "op-1"),
        )

        val stored = dao.getById("plan-1")
        assertEquals(WorkspaceAptPlanState.SUCCEEDED, stored?.state)
        assertEquals("op-1", stored?.operationId)

        // Terminal rows are immutable through finish.
        assertEquals(0, dao.finish("plan-1", WorkspaceAptPlanState.FAILED))
        assertEquals(WorkspaceAptPlanState.SUCCEEDED, dao.getById("plan-1")?.state)
    }

    @Test
    fun finishWithoutOperationIdLeavesTheOperationUnset() = runBlocking {
        dao.insert(plan())
        dao.confirm("plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L)
        dao.start("plan-1", "call-1", now = 900L)

        assertEquals(1, dao.finish("plan-1", WorkspaceAptPlanState.FAILED))

        val stored = dao.getById("plan-1")
        assertEquals(WorkspaceAptPlanState.FAILED, stored?.state)
        assertNull(stored?.operationId)
    }

    @Test
    fun getInterruptedReturnsOnlyExecutingPlansOldestFirst() = runBlocking {
        // Finished normally: must not be reported.
        dao.insert(plan(planId = "plan-old", createdAt = 10L))
        dao.confirm("plan-old", "workspace-a", "assistant-a", "conversation-a", "call-a", 900L)
        dao.start("plan-old", "call-a", now = 900L)
        dao.finish("plan-old", WorkspaceAptPlanState.SUCCEEDED)

        // Confirmed but never started: must not be reported.
        dao.insert(plan(planId = "plan-new", createdAt = 30L))
        dao.confirm("plan-new", "workspace-a", "assistant-a", "conversation-a", "call-b", 900L)

        // Died while executing: reported.
        dao.insert(plan(planId = "plan-mid", createdAt = 20L))
        dao.confirm("plan-mid", "workspace-a", "assistant-a", "conversation-a", "call-c", 900L)
        dao.start("plan-mid", "call-c", now = 900L)

        assertEquals(listOf("plan-mid"), dao.getInterrupted().map { it.planId })
    }

    @Test
    fun archiveCopyExpiresConfirmedPlansWithoutChangingLiveDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "apt-archive-${System.nanoTime()}"
        val source = context.getDatabasePath(name)
        val copy = File(context.cacheDir, "$name-copy.db")
        try {
            val live = Room.databaseBuilder(context, AppDatabase::class.java, source.absolutePath)
                .allowMainThreadQueries().build()
            live.workspaceAptPlanDao().insert(plan())
            live.workspaceAptPlanDao().confirm(
                "plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L,
            )
            live.close()
            source.copyTo(copy)
            sanitizeWorkspaceDatabaseCopy(copy, removeSnapshots = false)

            val archived = Room.databaseBuilder(context, AppDatabase::class.java, copy.absolutePath)
                .allowMainThreadQueries().build()
            val archivedPlan = archived.workspaceAptPlanDao().getById("plan-1")
            archived.close()
            assertEquals(WorkspaceAptPlanState.EXPIRED, archivedPlan?.state)
            assertNull(archivedPlan?.confirmedToolCallId)

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, source.absolutePath)
                .allowMainThreadQueries().build()
            assertEquals(WorkspaceAptPlanState.CONFIRMED, reopened.workspaceAptPlanDao().getById("plan-1")?.state)
            reopened.close()
        } finally {
            context.deleteDatabase(name)
            copy.delete()
        }
    }

    @Test
    fun planSurvivesReopenOfAPersistentDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "apt-plan-persistence-${System.nanoTime()}"
        val file = context.getDatabasePath(name)
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
            first.workspaceAptPlanDao().insert(plan())
            first.workspaceAptPlanDao().confirm(
                "plan-1", "workspace-a", "assistant-a", "conversation-a", "call-1", 900L,
            )
            first.close()

            val second = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()
            val stored = second.workspaceAptPlanDao().getById("plan-1")
            second.close()

            assertEquals(WorkspaceAptPlanState.CONFIRMED, stored?.state)
            assertEquals("call-1", stored?.confirmedToolCallId)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
