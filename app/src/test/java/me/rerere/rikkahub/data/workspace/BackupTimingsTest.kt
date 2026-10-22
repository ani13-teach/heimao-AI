package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupTimingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `stage calls bytes files and monotonic elapsed are aggregated`() {
        var now = 10L
        val root = temporary.newFolder()
        val session = BackupTimingSession(BackupTimings.directory(root), "EXPORT") { now }
        repeat(2) {
            val token = session.begin(BackupPhase.ZIP_WRITE, 100, 3)
            now += 20
            session.end(token)
        }
        session.finish("SUCCESS")
        val report = BackupTimings.reports(root).single()
        assertEquals(40L, report.elapsedNanos)
        assertEquals(2, report.phases.single().calls)
        assertEquals(200L, report.phases.single().bytes)
        assertEquals(6L, report.phases.single().files)
        assertEquals(40L, report.phases.single().elapsedNanos)
    }

    @Test fun `failure does not store exception message or paths`() = runBlocking {
        val root = temporary.newFolder()
        runCatching {
            withBackupTiming(root, "EXPORT") {
                timedBackupPhase(BackupPhase.SOURCE_COPY) { error("secret-key /private/user-file") }
            }
        }
        val text = BackupTimings.export(root)
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("user-file"))
        assertEquals("FAILED", BackupTimings.reports(root).single().status)
        assertEquals("FAILED", BackupTimings.reports(root).single().phases.single().status)
    }

    @Test fun `nested operations use one session and cancellation is distinct`() = runBlocking {
        val root = temporary.newFolder()
        runCatching {
            withBackupTiming(root, "IMPORT") {
                withBackupTiming(root, "IMPORT") {
                    timedBackupPhase(BackupPhase.ZIP_EXTRACT) { throw CancellationException() }
                }
            }
        }
        assertEquals(1, BackupTimings.reports(root).size)
        assertEquals("CANCELLED", BackupTimings.reports(root).single().status)
    }

    @Test fun `nested phases retain their parent instead of being added twice`() {
        val root = temporary.newFolder()
        var now = 0L
        val session = BackupTimingSession(BackupTimings.directory(root), "IMPORT") { now }
        val parent = session.begin(BackupPhase.APPLY_RESTORE)
        val child = session.begin(BackupPhase.DATABASE_VALIDATE)
        now = 20
        session.end(child)
        now = 30
        session.end(parent)
        session.finish("SUCCESS")
        val phases = BackupTimings.reports(root).single().phases
        assertEquals("APPLY_RESTORE", phases.first { it.phase == "DATABASE_VALIDATE" }.parentPhase)
        assertEquals(30L, phases.first { it.phase == "APPLY_RESTORE" }.elapsedNanos)
    }

    @Test fun `cleanup retry keeps committed recovery outcome across launches`() {
        val root = temporary.newFolder()
        val session = BackupTimingSession(BackupTimings.directory(root), "IMPORT") { 100L }
        session.restarting()
        BackupTimings.noteRecoveryDecision(root, committed = true)
        BackupTimings.noteRecoveryDecision(root, committed = false)
        BackupTimings.completeRestart(root, committed = false) { 200L }
        assertEquals("SUCCESS", BackupTimings.reports(root).single().status)
    }

    @Test fun `restart restores timing only after recovery and records rollback`() {
        val root = temporary.newFolder()
        var now = 100L
        val session = BackupTimingSession(BackupTimings.directory(root), "IMPORT") { now }
        session.begin(BackupPhase.APPLY_RESTORE)
        now = 200
        session.restarting()
        session.finish("SUCCESS")
        assertEquals("RESTARTING", BackupTimings.reports(root).single().status)
        now = 500
        BackupTimings.completeRestart(root, committed = false) { now }
        val report = BackupTimings.reports(root).single()
        assertEquals("ROLLED_BACK", report.status)
        assertEquals(400L, report.elapsedNanos)
        assertEquals(300L, report.phases.last().elapsedNanos)
    }
}
