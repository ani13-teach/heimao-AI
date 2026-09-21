package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceProcessTerminationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceRepositoryLeaseOwnershipTest {

    @Test
    fun `pending termination retains lease before cancelled dispatcher handoff`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val released = AtomicBoolean(false)
            val retained = AtomicBoolean(false)
            val observationStarted = CountDownLatch(1)
            val allowObservationToFinish = CountDownLatch(1)
            val ownership = WorkspaceCommandLeaseOwnership(
                release = { released.set(true) },
                retain = {
                    retained.set(true)
                    observationStarted.countDown()
                    try {
                        allowObservationToFinish.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
            )

            val command = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    runObservedWorkspaceCommand(dispatcher, ownership) {
                        WorkspaceCommandResult(
                            exitCode = -1,
                            stdout = "",
                            stderr = "",
                            timedOut = true,
                            terminationPending = true,
                        )
                    }
                } finally {
                    ownership.closeIfOwned()
                }
            }

            assertTrue(observationStarted.await(5, TimeUnit.SECONDS))
            command.cancel()
            allowObservationToFinish.countDown()
            runCatching { command.await() }

            assertTrue(retained.get())
            assertFalse(released.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `termination exception hidden under cancellation cause retains lease`() {
        val released = AtomicBoolean(false)
        val retained = AtomicBoolean(false)
        val ownership = WorkspaceCommandLeaseOwnership(
            release = { released.set(true) },
            retain = { retained.set(true) },
        )
        val cancellation = CancellationException("cancelled").also {
            it.initCause(WorkspaceProcessTerminationException())
        }

        ownership.observeFailure(cancellation)
        ownership.closeIfOwned()

        assertTrue(retained.get())
        assertFalse(released.get())
    }

    @Test
    fun `confirmed command result releases lease`() {
        val released = AtomicBoolean(false)
        val ownership = WorkspaceCommandLeaseOwnership(
            release = { released.set(true) },
            retain = { error("lease must not be retained") },
        )

        ownership.observeResult(WorkspaceCommandResult(0, "ok", ""))
        ownership.closeIfOwned()

        assertTrue(released.get())
    }
}
