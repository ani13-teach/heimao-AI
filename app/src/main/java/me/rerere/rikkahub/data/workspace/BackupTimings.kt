package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class BackupPhase {
    WAIT_LOCK, SOURCE_SCAN, WORKSPACE_FILES, HOME, DATABASE_SNAPSHOT, DATABASE_SNAPSHOT_ATTEMPT, ZIP_WRITE,
    ZIP_IMAGES, ZIP_DATABASE, ZIP_WORKSPACE, ZIP_ATTACHMENTS,
    ARCHIVE_VERIFY, ZIP_EXTRACT, WORKSPACE_EXTRACT, DATABASE_VALIDATE,
    APPLY_RESTORE, SOURCE_COPY, DESTINATION_COPY, CLEANUP, RESTART_RECOVERY,
    INCREMENTAL_COMPARE,
}

@Serializable
internal data class BackupPhaseMeasurement(
    val phase: String,
    val elapsedNanos: Long,
    val calls: Int,
    val bytes: Long,
    val files: Long,
    val status: String,
    val parentPhase: String? = null,
)

@Serializable
internal data class BackupTimingReport(
    val id: String,
    val operation: String,
    val startedAt: Long,
    val startedNanos: Long,
    val elapsedNanos: Long = 0,
    val status: String = "RUNNING",
    val restartNanos: Long? = null,
    val recoveryCommitted: Boolean? = null,
    val phases: List<BackupPhaseMeasurement> = emptyList(),
)

/** Counters only: no source paths, content, exception messages or credentials are persisted. */
internal class BackupTimingSession(
    private val directory: File,
    operation: String,
    private val clock: () -> Long = System::nanoTime,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BackupTimingSession>

    private var report = BackupTimingReport(
        UUID.randomUUID().toString(), operation, System.currentTimeMillis(), clock(),
    )
    private data class Active(val phase: BackupPhase, val start: Long, val bytes: Long, val files: Long, val parent: String?)
    private val active = linkedMapOf<Long, Active>()
    private var sequence = 0L
    private var lastPersistNanos: Long? = null

    init { persist(force = true) }

    @Synchronized fun begin(phase: BackupPhase, bytes: Long = 0, files: Long = 0): Long {
        require(bytes >= 0 && files >= 0)
        val parent = active.values.lastOrNull()?.phase?.name
        return (++sequence).also { active[it] = Active(phase, clock(), bytes, files, parent) }
    }

    @Synchronized fun end(token: Long, status: String = "SUCCESS", bytes: Long? = null, files: Long? = null) {
        val stage = active.remove(token) ?: return
        val measurement = BackupPhaseMeasurement(
            stage.phase.name, (clock() - stage.start).coerceAtLeast(0), 1,
            bytes ?: stage.bytes, files ?: stage.files, status, stage.parent,
        )
        val previous = report.phases.firstOrNull {
            it.phase == measurement.phase && it.status == status && it.parentPhase == measurement.parentPhase
        }
        report = report.copy(phases = report.phases.filterNot { it === previous } + if (previous == null) {
            measurement
        } else {
            measurement.copy(
                elapsedNanos = previous.elapsedNanos + measurement.elapsedNanos,
                calls = previous.calls + 1,
                bytes = previous.bytes + measurement.bytes,
                files = previous.files + measurement.files,
            )
        })
        persist()
    }

    fun <T> measure(phase: BackupPhase, bytes: Long = 0, files: Long = 0, block: () -> T): T {
        val token = begin(phase, bytes, files)
        var status = "SUCCESS"
        try { return block() } catch (error: Throwable) {
            status = if (error is CancellationException) "CANCELLED" else "FAILED"
            throw error
        } finally { end(token, status) }
    }

    @Synchronized fun finish(status: String) {
        if (report.status != "RUNNING") return
        active.keys.toList().forEach { end(it, status) }
        report = report.copy(status = status, elapsedNanos = (clock() - report.startedNanos).coerceAtLeast(0))
        persist(force = true)
    }

    @Synchronized fun restarting() {
        active.keys.toList().forEach { end(it, "RESTARTING") }
        report = report.copy(status = "RESTARTING", restartNanos = clock(),
            elapsedNanos = (clock() - report.startedNanos).coerceAtLeast(0))
        persist(force = true)
    }

    private fun persist(force: Boolean = false) {
        val now = clock()
        val previous = lastPersistNanos
        if (!force && previous != null && now - previous < 250_000_000L) return
        runCatching { BackupTimings.write(directory, report) }.onSuccess { lastPersistNanos = now }
    }
}

internal object BackupTimings {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private const val DIRECTORY = "backup-diagnostics"
    private const val KEEP_REPORTS = 20

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY)

    @Synchronized fun write(directory: File, report: BackupTimingReport) {
        require(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "${report.id}.json")
        val partial = File(file.path + ".partial")
        partial.outputStream().use { output ->
            output.write(json.encodeToString(report).toByteArray(Charsets.UTF_8))
            if (report.status != "RUNNING") output.fd.sync()
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        directory.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.drop(KEEP_REPORTS).forEach { it.delete() }
    }

    fun reports(filesDir: File): List<BackupTimingReport> = directory(filesDir).listFiles().orEmpty()
        .filter { it.extension == "json" && it.length() <= 256 * 1024 }
        .mapNotNull { runCatching { json.decodeFromString<BackupTimingReport>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.startedAt }

    fun export(filesDir: File): String = json.encodeToString(reports(filesDir))

    fun noteRecoveryDecision(filesDir: File, committed: Boolean) {
        reports(filesDir).filter { it.status == "RESTARTING" && it.recoveryCommitted == null }.forEach {
            write(directory(filesDir), it.copy(recoveryCommitted = committed))
        }
    }

    fun completeRestart(filesDir: File, committed: Boolean, clock: () -> Long = System::nanoTime) {
        reports(filesDir).filter { it.status == "RESTARTING" }.forEach { report ->
            val now = clock()
            val start = report.restartNanos ?: now
            val sameBoot = now >= start && now >= report.startedNanos
            val restored = report.recoveryCommitted ?: committed
            write(directory(filesDir), report.copy(
                status = if (!sameBoot) "INTERRUPTED" else if (restored) "SUCCESS" else "ROLLED_BACK",
                elapsedNanos = if (sameBoot) now - report.startedNanos else report.elapsedNanos,
                phases = report.phases + BackupPhaseMeasurement(
                    BackupPhase.RESTART_RECOVERY.name, if (sameBoot) now - start else 0, 1, 0, 0,
                    if (restored && sameBoot) "SUCCESS" else "FAILED",
                ),
            ))
        }
    }
}

internal suspend fun <T> withBackupTiming(filesDir: File, operation: String, block: suspend () -> T): T {
    if (currentCoroutineContext()[BackupTimingSession] != null) return block()
    val session = withContext(kotlinx.coroutines.Dispatchers.IO) {
        BackupTimingSession(BackupTimings.directory(filesDir), operation)
    }
    return withContext(session) {
        var status = "SUCCESS"
        try { block() } catch (error: Throwable) {
            status = if (error is CancellationException) "CANCELLED" else "FAILED"
            throw error
        } finally { session.finish(status) }
    }
}

internal suspend fun <T> timedBackupPhase(
    phase: BackupPhase, bytes: Long = 0, files: Long = 0, block: suspend () -> T,
): T {
    val session = currentCoroutineContext()[BackupTimingSession] ?: return block()
    val token = session.begin(phase, bytes, files)
    var status = "SUCCESS"
    try { return block() } catch (error: Throwable) {
        status = if (error is CancellationException) "CANCELLED" else "FAILED"
        throw error
    } finally { session.end(token, status) }
}
