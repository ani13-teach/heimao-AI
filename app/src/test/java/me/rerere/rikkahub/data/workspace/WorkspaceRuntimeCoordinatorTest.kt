package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimeCoordinatorTest {

    private val coordinator = WorkspaceRuntimeCoordinator()

    @Test
    fun `run leases coexist and release independently`() {
        val first = coordinator.acquireRunLease("ws", SHARED)
        val second = coordinator.acquireRunLease("ws", SHARED)

        assertFalse(coordinator.isIdle("ws"))
        assertEquals(2, coordinator.activeLeaseCount)

        first.close()
        assertFalse(coordinator.isIdle("ws"))

        second.close()
        assertTrue(coordinator.isIdle("ws"))
        assertTrue(coordinator.isEnvironmentIdle(SHARED))
        assertEquals(0, coordinator.activeLeaseCount)
    }

    @Test
    fun `closing a lease twice only releases once`() {
        val lease = coordinator.acquireRunLease("ws", SHARED)
        lease.close()
        lease.close()

        assertTrue(coordinator.isIdle("ws"))
        assertEquals(0, coordinator.activeLeaseCount)
    }

    @Test
    fun `an exclusive lease reports busy instead of waiting for a running session`() {
        coordinator.acquireRunLease("ws", SHARED).use {
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireExclusiveLease("ws", SHARED)
            }
        }
        coordinator.acquireExclusiveLease("ws", SHARED).use {
            assertFalse(coordinator.isIdle("ws"))
        }
        assertTrue(coordinator.isIdle("ws"))
    }

    /**
     * Regression: a member exclusive lease used to increment the environment run count and then
     * decrement the environment *exclusive* flag on release, which left the environment busy
     * forever and let every other tenant keep running while the member was mutated.
     */
    @Test
    fun `a member exclusive lease takes and returns the whole environment gate`() {
        coordinator.acquireExclusiveLease("ws", SHARED).use {
            // Another tenant of the same environment must not run while one member is mutated.
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireRunLease("other", SHARED)
            }
            assertFalse(coordinator.isEnvironmentIdle(SHARED))
        }

        // Released exactly once: nothing stays occupied behind it.
        assertTrue(coordinator.isEnvironmentIdle(SHARED))
        assertTrue(coordinator.isIdle("ws"))
        assertEquals(0, coordinator.activeLeaseCount)
        coordinator.acquireRunLease("other", SHARED).use {
            assertFalse(coordinator.isEnvironmentIdle(SHARED))
        }
        assertTrue(coordinator.isEnvironmentIdle(SHARED))
        assertEquals(0, coordinator.activeLeaseCount)
    }

    @Test
    fun `an environment exclusive lease excludes every tenant but not private workspaces`() {
        coordinator.acquireRunLease("ws", SHARED).use {
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireEnvironmentExclusiveLease(SHARED)
            }
        }

        coordinator.acquireEnvironmentExclusiveLease(SHARED, maintenance = true).use {
            assertTrue(coordinator.isMaintenanceActive)
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireRunLease("ws", SHARED)
            }
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireExclusiveLease("ws", SHARED)
            }
            // A workspace that still owns a private rootfs is unaffected by a shared swap.
            coordinator.acquireRunLease("legacy", null).close()
        }
        assertFalse(coordinator.isMaintenanceActive)
        assertTrue(coordinator.isEnvironmentIdle(SHARED))
        assertEquals(0, coordinator.activeLeaseCount)
    }

    @Test
    fun `the archive batch blocks new leases and lets the same batch nest`() = runBlocking {
        coordinator.withEnvironmentArchiveLease {
            assertThrows(WorkspaceBusyException::class.java) {
                coordinator.acquireRunLease("ws", SHARED)
            }
            // A nested step of the same logical batch inherits the token and must not deadlock.
            assertEquals("nested", coordinator.withEnvironmentArchiveLease { "nested" })
            kotlinx.coroutines.coroutineScope {
                val child = async { coordinator.withEnvironmentArchiveLease { "child" } }
                assertEquals("child", child.await())
            }
        }

        coordinator.acquireRunLease("ws", SHARED).close()
    }

    /** Two independent archives must never exchange directories at the same time. */
    @Test
    fun `an unrelated coroutine cannot join a running archive batch`() = runBlocking {
        val otherScope = CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        coordinator.withEnvironmentArchiveLease {
            val joined = runCatching {
                otherScope.async { coordinator.withEnvironmentArchiveLease { "joined" } }.await()
            }
            assertTrue(joined.exceptionOrNull() is WorkspaceBusyException)
        }

        assertEquals("after", otherScope.async { coordinator.withEnvironmentArchiveLease { "after" } }.await())
        otherScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `the archive batch reports busy while a lease is still held and never waits`() = runBlocking {
        val lease = coordinator.acquireRunLease("ws", SHARED)
        assertThrows(WorkspaceBusyException::class.java) {
            runBlocking { coordinator.withEnvironmentArchiveLease { } }
        }
        lease.close()

        coordinator.withEnvironmentArchiveLease { assertTrue(coordinator.isIdle("ws")) }
    }

    private companion object {
        const val SHARED = WorkspaceRuntimeGates.SHARED_ENVIRONMENT
    }
}
