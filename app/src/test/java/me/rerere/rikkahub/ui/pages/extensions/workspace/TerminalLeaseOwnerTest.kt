package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalLeaseOwnerTest {
    @Test
    fun `cleanup during preparation waits until preparation stops using the workspace`() {
        val lease = RecordingLease()
        val owner = TerminalLeaseOwner()

        owner.attachLease(lease)
        owner.cleanup()
        assertEquals(0, lease.closeCount)

        owner.releaseAfterPreparationFailure()
        assertEquals(1, lease.closeCount)
    }

    @Test
    fun `cleanup after successful preparation releases before process attachment`() {
        val lease = RecordingLease()
        val owner = TerminalLeaseOwner()

        owner.attachLease(lease)
        owner.preparationSucceeded()
        owner.cleanup()

        assertEquals(1, lease.closeCount)
    }

    @Test
    fun `unstarted terminal releases immediately without waiting for callback`() {
        val lease = RecordingLease()
        val process = RecordingProcess(pid = 0)
        val owner = TerminalLeaseOwner()
        owner.attachLease(lease)
        owner.preparationSucceeded()
        owner.attachProcess(process)

        owner.cleanup()

        assertEquals(0, process.finishCount)
        assertEquals(1, lease.closeCount)
    }

    @Test
    fun `running terminal keeps lease until finish callback`() {
        val lease = RecordingLease()
        val process = RecordingProcess(pid = 42)
        val owner = TerminalLeaseOwner()
        owner.attachLease(lease)
        owner.preparationSucceeded()
        owner.attachProcess(process)

        owner.cleanup()
        assertEquals(1, process.finishCount)
        assertEquals(0, lease.closeCount)

        owner.onProcessFinished()
        assertEquals(1, lease.closeCount)
    }

    @Test
    fun `old retry callback cannot release new attempt lease`() {
        val oldLease = RecordingLease()
        val oldOwner = TerminalLeaseOwner().apply { attachLease(oldLease) }
        val newLease = RecordingLease()
        val newOwner = TerminalLeaseOwner().apply {
            attachLease(newLease)
            preparationSucceeded()
        }

        oldOwner.onProcessFinished()

        assertEquals(1, oldLease.closeCount)
        assertEquals(0, newLease.closeCount)
        newOwner.cleanup()
        assertEquals(1, newLease.closeCount)
    }

    private class RecordingLease : AutoCloseable {
        var closeCount = 0

        override fun close() {
            closeCount += 1
        }
    }

    private class RecordingProcess(
        override val pid: Int,
    ) : TerminalLeaseProcess {
        var finishCount = 0

        override fun finish() {
            finishCount += 1
        }
    }
}
