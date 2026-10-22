package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test

class ArchiveSnapshotRetryTest {
    private class Busy : RuntimeException()

    @Test fun `successful snapshot executes exactly once`() {
        var copies = 0
        assertEquals(42, retryArchiveSnapshot(3, { it is Busy }) { copies++; 42 })
        assertEquals(1, copies)
    }

    @Test fun `busy snapshot retries and stops at first success`() {
        var copies = 0
        retryArchiveSnapshot(3, { it is Busy }) { if (++copies < 2) throw Busy() }
        assertEquals(2, copies)
    }

    @Test fun `permanent errors are never retried and busy retries are bounded`() {
        var copies = 0
        val failure = runCatching {
            retryArchiveSnapshot(3, { it is Busy }) { copies++; error("corrupt") }
        }.exceptionOrNull()
        assertEquals("corrupt", failure?.message)
        assertEquals(1, copies)
        copies = 0
        assertTrue(runCatching {
            retryArchiveSnapshot(3, { it is Busy }) { copies++; throw Busy() }
        }.exceptionOrNull()?.cause is Busy)
        assertEquals(3, copies)
    }
}
