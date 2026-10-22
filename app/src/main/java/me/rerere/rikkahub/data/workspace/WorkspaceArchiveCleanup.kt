package me.rerere.rikkahub.data.workspace

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Small, durable cleanup records for restore artifacts that must not be removed after Room closes.
 * The next process owns cleanup, after all application singletons have been recreated safely.
 */
internal object WorkspaceArchiveCleanup {
    private const val MARKER_PREFIX = ".workspace-archive-cleanup-"
    private const val MARKER_SUFFIX = ".pending"

    fun createMarker(context: Context, artifacts: List<File>): File {
        val filesDir = context.filesDir
        require(!Files.isSymbolicLink(filesDir.toPath())) { "Application files directory is a symbolic link" }
        val marker = File(filesDir, "$MARKER_PREFIX${UUID.randomUUID()}$MARKER_SUFFIX")
        val names = artifacts.map { artifact ->
            require(artifact.parentFile?.canonicalFile == filesDir.canonicalFile) {
                "Archive cleanup artifact is outside filesDir: ${artifact.path}"
            }
            require(artifact.name.isNotBlank() && artifact.name != "." && artifact.name != "..") {
                "Archive cleanup artifact name is invalid"
            }
            artifact.name
        }.distinct()
        require(names.isNotEmpty()) { "Archive cleanup marker has no artifacts" }
        require(!Files.isSymbolicLink(marker.toPath())) { "Archive cleanup marker is a symbolic link" }
        marker.writeText(names.joinToString("\n"), Charsets.UTF_8)
        return marker
    }

    fun isMarkerFor(context: Context, artifact: File): Boolean {
        if (!artifact.exists()) return false
        val filesDir = context.filesDir
        return pendingMarkers(filesDir).any { marker ->
            readArtifactNames(marker).contains(artifact.name)
        }
    }

    fun removeMarker(marker: File) {
        if (marker.exists()) require(marker.delete()) { "Unable to remove archive cleanup marker" }
    }

    /** Best effort and idempotent; a failed marker remains for the next launch. */
    fun cleanupPending(context: Context) {
        val filesDir = context.filesDir
        pendingMarkers(filesDir).forEach { marker ->
            // A marker is not proof that the transaction committed. Keep every artifact while
            // the durable journal is pending (or while DataStore still needs compensation).
            if (WorkspaceArchiveRecovery.protectsPendingCleanup(context)) return@forEach
            var complete = true
            readArtifactNames(marker).forEach { name ->
                val artifact = File(filesDir, name)
                if (!isSafeArtifactName(name)) {
                    complete = false
                    return@forEach
                }
                if (Files.exists(artifact.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(artifact.toPath())) {
                        complete = false
                    } else {
                        runCatching { require(artifact.deleteRecursively()) }
                            .onFailure { complete = false }
                    }
                }
            }
            if (complete) runCatching { marker.delete() }
        }
        if (WorkspaceArchiveRecovery.protectsPendingCleanup(context)) return
        // A process killed during authenticated restore can occur before a marker is written.
        // Only these private, generated names are eligible for orphan cleanup.
        filesDir.listFiles().orEmpty()
            .filter { file ->
                file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    (file.name.startsWith(".workspace-decrypted-") ||
                        file.name.startsWith(".workspace-verify-decrypted-"))
            }
            .forEach { file -> runCatching { file.delete() } }
        cleanupGeneratedCacheArtifacts(context)
    }

    fun hasPendingCleanup(context: Context): Boolean =
        pendingMarkers(context.filesDir).isNotEmpty() || WorkspaceArchiveRecovery.protectsPendingCleanup(context)

    private fun cleanupGeneratedCacheArtifacts(context: Context) {
        val cacheDir = context.cacheDir
        if (Files.isSymbolicLink(cacheDir.toPath()) || !cacheDir.isDirectory) return
        cacheDir.listFiles().orEmpty()
            .filter { file ->
                !Files.isSymbolicLink(file.toPath()) && (
                    file.name.startsWith(".workspace-archive-") ||
                        file.name.startsWith(".workspace-verify-") ||
                        file.name.startsWith(".legacy-verify-") ||
                        file.name.startsWith(".webdav-restore-") ||
                        file.name.startsWith(".s3-restore-") ||
                        file.name.startsWith("temp_restore_") ||
                        (file.name.startsWith("backup_") && file.name.endsWith(".partial"))
                    )
            }
            .forEach { file -> runCatching { file.deleteRecursively() } }
    }

    private fun pendingMarkers(filesDir: File): List<File> = filesDir.listFiles().orEmpty()
        .filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                it.name.startsWith(MARKER_PREFIX) && it.name.endsWith(MARKER_SUFFIX)
        }

    private fun readArtifactNames(marker: File): Set<String> = runCatching {
        marker.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    private fun isSafeArtifactName(name: String): Boolean =
        name.startsWith(".workspace-restore-") ||
            name.startsWith(".workspace-decrypted-") ||
            name.startsWith(".legacy-restore-") ||
            name.startsWith(".workspace-restore-recovery-") ||
            name.startsWith(".legacy-restore-recovery-") ||
            name.startsWith(".database-recovery-")
}

/** Actions allowed after Room is closed; directory/settings work must precede this sequence. */
enum class WorkspaceArchivePostCloseAction {
    EXCHANGE_DATABASE,
    SCHEDULE_RELAUNCH,
    EXIT_PROCESS,
}

internal fun workspaceArchivePostCloseActionsAreSafe(
    actions: List<WorkspaceArchivePostCloseAction>,
): Boolean = actions == listOf(
    WorkspaceArchivePostCloseAction.EXCHANGE_DATABASE,
    WorkspaceArchivePostCloseAction.SCHEDULE_RELAUNCH,
    WorkspaceArchivePostCloseAction.EXIT_PROCESS,
)
