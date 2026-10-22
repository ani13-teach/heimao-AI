package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkspaceArchiveBudgetTest {
    @Test fun `500 MiB stream polls space by byte window not every chunk`() {
        var queries = 0
        val budget = WorkspaceArchiveBudget(usableSpace = { queries++; 8L * 1024 * 1024 * 1024 }, clock = { 0L })
        repeat(8000) { budget.recordStagingBytes(64 * 1024, File("staging")) }
        assertTrue(queries in 62..63)
    }

    @Test fun `time window detects external disk consumption`() {
        var now = 0L
        var space = 100L * 1024 * 1024
        val budget = WorkspaceArchiveBudget(usableSpace = { space }, clock = { now })
        budget.recordStagingBytes(1024, File("staging"))
        space = 1
        now = 250_000_000
        assertTrue(runCatching { budget.recordStagingBytes(1024, File("staging")) }.isFailure)
    }

    @Test fun `large reservation and global byte limits remain authoritative`() {
        val budget = WorkspaceArchiveBudget(
            limits = WorkspaceArchiveBudgetLimits(maxStagingBytes = 20),
            usableSpace = { 1_000_000 }, safetyReserveBytes = 0,
        )
        budget.recordStagingBytes(20, File("staging"))
        assertTrue(runCatching { budget.recordStagingBytes(1, File("staging")) }.isFailure)
        val lowSpace = WorkspaceArchiveBudget(usableSpace = { 10 }, safetyReserveBytes = 0)
        assertTrue(runCatching { lowSpace.recordStagingBytes(11, File("staging")) }.isFailure)
    }

    @Test fun `unknown space still enforces global budget`() {
        val budget = WorkspaceArchiveBudget(
            WorkspaceArchiveBudgetLimits(maxStagingBytes = 5), usableSpace = { 0 },
        )
        budget.recordStagingBytes(5, File("staging"))
        assertTrue(runCatching { budget.recordStagingBytes(1, File("staging")) }.isFailure)
    }
}
