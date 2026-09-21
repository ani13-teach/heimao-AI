package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.hasUsableFullRootfs
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Fixed names inside the public Linux environment root (`<filesDir>/shared-linux`).
 *
 * The rootfs itself and the recovery area are owned by `WorkspaceEnvironmentStore`; everything
 * here is the app-side transaction state that makes a crash in the middle of an install,
 * upgrade or restore recoverable on the next start.
 */
object WorkspaceEnvironmentLayout {
    /** Journal of the in-flight directory exchange. Absent means "no exchange in progress". */
    const val TRANSACTION_FILE_NAME = "transaction.json"

    /** Public runtime metadata of the installed shared rootfs. Shared by every member workspace. */
    const val METADATA_FILE_NAME = "metadata.json"

    /** The displaced rootfs while an exchange is in flight. */
    const val PREVIOUS_DIR_NAME = "previous"

    /** A rootfs this app could not read while recovering an exchange. */
    const val INCOMPLETE_DIR_NAME = "incomplete"

    /** Scratch area used to stage a candidate rootfs before the exchange. */
    const val STAGING_DIR_NAME = "install"

    /** Full snapshot of the rootfs taken before maintenance, kept inside the recovery area. */
    const val RECOVERY_ARCHIVE_NAME = "active.tar"

    /** Runtime metadata of a workspace whose environment has not installed a rootfs yet. */
    const val EMPTY_METADATA = "{}"
}

/** Journal phases of the atomic active/previous swap. */
enum class WorkspaceEnvironmentPhase {
    /** The old rootfs has been moved away (or is about to be) and the candidate is not active yet. */
    PREPARED,

    /** The candidate is active; only the displaced tree and the journal are stale. */
    COMMITTED,
}

@Serializable
internal data class WorkspaceEnvironmentTransaction(
    val metadataVersion: Int = CURRENT_VERSION,
    val phase: WorkspaceEnvironmentPhase,
    val activeDir: String,
    val previousDir: String,
    val recoveryArchive: String,
    val startedAt: Long,
    val hadActive: Boolean = true,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** What [WorkspaceEnvironmentTransactionStore.recover] did to the shared rootfs. */
enum class WorkspaceEnvironmentRecovery {
    /** Nothing was pending. */
    NONE,

    /** An interrupted exchange was rolled back to the previously installed rootfs. */
    RESTORED_PREVIOUS,

    /** The exchange had completed; the stale backup was removed. */
    DISCARDED_PREVIOUS,

    /** The journal and the displaced tree disagreed; both were kept for a manual reinstall. */
    KEPT_FOR_INSPECTION,
}

/**
 * Owns the crash-safe rootfs exchange of the public Linux environment.
 *
 * The protocol is one journal file plus two renames:
 * 1. write `PREPARED` (active, previous and recovery paths),
 * 2. rename `active -> previous`,
 * 3. rename `candidate -> active`,
 * 4. write `COMMITTED`,
 * 5. delete `previous`, then clear the journal.
 *
 * A crash before step 4 leaves `previous` intact and recovery puts it back. A crash after step 4
 * means the candidate is already live, so recovery only removes the stale tree. Every step is a
 * same-directory rename, so no state in between is observable.
 *
 * All operations are blocking file I/O; callers run them on a background dispatcher.
 */
internal class WorkspaceEnvironmentTransactionStore(
    val activeDir: File,
    val previousDir: File,
    val stagingDir: File,
    val recoveryArchive: File,
    val transactionFile: File,
    private val limits: WorkspaceArchiveTar.Limits = DEFAULT_LIMITS,
) {

    private val incompleteDir: File = File(previousDir.parentFile, WorkspaceEnvironmentLayout.INCOMPLETE_DIR_NAME)

    /** True when the shared rootfs is installed and complete. */
    fun activeUsable(): Boolean = activeDir.hasUsableFullRootfs()

    /** True when a snapshot of the previously installed rootfs is retained. */
    fun recoveryAvailable(): Boolean = recoveryArchive.isFile && recoveryArchive.length() > 0L

    fun readTransaction(): WorkspaceEnvironmentTransaction? {
        if (!Files.exists(transactionFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(transactionFile.isFile && !Files.isSymbolicLink(transactionFile.toPath())) {
            "Shared environment transaction journal is invalid"
        }
        return JsonInstant.decodeFromString<WorkspaceEnvironmentTransaction>(transactionFile.readText()).also {
            require(it.metadataVersion == WorkspaceEnvironmentTransaction.CURRENT_VERSION) {
                "Unsupported shared environment transaction version"
            }
        }
    }

    /** True while an exchange is journaled but not finalized. */
    fun hasPendingTransaction(): Boolean = readTransaction() != null

    /**
     * Copies the current rootfs into the recovery archive using the workspace TAR codec, which
     * keeps permissions, symbolic links and hard links. The archive is written next to its final
     * name, so a crash in the middle leaves the previous snapshot usable.
     *
     * @return true when a snapshot was written. False means there was no usable rootfs to protect.
     */
    fun snapshotActiveToRecovery(): Boolean {
        if (!activeUsable()) {
            // An empty snapshot must never replace a good one: a workspace without a rootfs has
            // nothing to protect, and the stale archive may still be the only copy of one.
            return false
        }
        repairProotRootfsPlaceholders(activeDir)
        val parent = recoveryArchive.parentFile
        require(parent != null) { "Recovery archive has no parent directory" }
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create the shared environment recovery directory: ${parent.path}"
        }
        val partial = File(parent, "${recoveryArchive.name}.partial")
        Files.deleteIfExists(partial.toPath())
        try {
            WorkspaceArchiveTar.create(
                sourceRoot = activeDir,
                output = partial,
                // Guest temp areas are regenerated and are never part of a recovery point.
                excludedPaths = setOf("tmp", "var/tmp"),
                limits = limits,
            )
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(partial.toPath()) }
            throw error
        }
        FileOutputStream(partial, true).use { it.fd.sync() }
        atomicReplace(partial, recoveryArchive)
        return true
    }

    /**
     * Makes [candidateDir] the live rootfs.
     *
     * The caller must already hold the exclusive environment lease and must have taken the
     * recovery snapshot. Only the rename is journaled, so any crash is recoverable.
     */
    fun prepareExchange(candidateDir: File): WorkspaceEnvironmentTransaction {
        require(candidateDir.hasUsableFullRootfs()) { "Candidate rootfs is missing or incomplete: ${candidateDir.path}" }
        check(!hasPendingTransaction()) { "Recover the pending environment transaction first" }
        check(!Files.exists(previousDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "A previous shared environment still needs recovery"
        }
        val transaction = WorkspaceEnvironmentTransaction(
            phase = WorkspaceEnvironmentPhase.PREPARED,
            activeDir = activeDir.absolutePath,
            previousDir = previousDir.absolutePath,
            recoveryArchive = recoveryArchive.absolutePath,
            startedAt = System.currentTimeMillis(),
            hadActive = Files.exists(activeDir.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
        writeTransaction(transaction)
        try {
            if (transaction.hadActive) {
                Files.move(activeDir.toPath(), previousDir.toPath())
            }
            Files.move(candidateDir.toPath(), activeDir.toPath())
            writeTransaction(transaction.copy(phase = WorkspaceEnvironmentPhase.COMMITTED))
        } catch (error: Throwable) {
            // Keep PREPARED until rollback is complete. A failed rollback must remain recoverable.
            runCatching { recover() }.onFailure { error.addSuppressed(it) }
            throw error
        }
        return transaction
    }

    /**
     * Removes the displaced tree and clears the journal. The journal is only cleared once the tree
     * is gone, so a failing delete is retried by the next [recover].
     */
    fun finalizeExchange() {
        deleteTreeNoFollow(previousDir)
        clearTransaction()
    }

    /** Removes the candidate staging area. Safe to call at any time. */
    fun clearStaging() {
        deleteTreeNoFollow(stagingDir)
    }

    /**
     * Rebuilds the rootfs from the retained recovery snapshot. Uses the same journaled exchange, so
     * a crash during the restore is recovered exactly like a crash during an install.
     */
    fun restoreRecoverySnapshot(): Boolean {
        if (!recoveryAvailable()) return false
        clearStaging()
        val extracted = File(stagingDir, RESTORE_DIR_NAME)
        deleteTreeNoFollow(stagingDir)
        require(stagingDir.mkdirs() || stagingDir.isDirectory) {
            "Unable to create the restore staging directory: ${stagingDir.path}"
        }
        try {
            WorkspaceArchiveTar.extract(
                input = recoveryArchive,
                targetRoot = extracted,
                limits = limits,
            )
            require(extracted.hasUsableFullRootfs()) {
                "Recovery snapshot is incomplete: ${recoveryArchive.path}"
            }
            prepareExchange(extracted)
            finalizeExchange()
        } finally {
            clearStaging()
        }
        return true
    }

    /**
     * Settles an interrupted exchange. Called on every start, before temp directories are cleaned,
     * so the shared rootfs is coherent before anything can run in it.
     */
    fun recover(): WorkspaceEnvironmentRecovery {
        clearStaging()
        runCatching { deletePartialRecoveryArchive() }
        val transaction = readTransaction()
        val activeExists = Files.exists(activeDir.toPath(), LinkOption.NOFOLLOW_LINKS)
        val previousExists = Files.exists(previousDir.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (transaction == null && !previousExists) return WorkspaceEnvironmentRecovery.NONE
        if (transaction?.phase == WorkspaceEnvironmentPhase.COMMITTED) {
            // The candidate is already live; only the displaced tree is stale.
            deleteTreeNoFollow(previousDir)
            clearTransaction()
            return WorkspaceEnvironmentRecovery.DISCARDED_PREVIOUS
        }
        if (!previousExists) {
            if (transaction?.phase == WorkspaceEnvironmentPhase.PREPARED && !transaction.hadActive && activeExists) {
                deleteTreeNoFollow(incompleteDir)
                Files.move(activeDir.toPath(), incompleteDir.toPath())
            }
            clearTransaction()
            return WorkspaceEnvironmentRecovery.NONE
        }
        if (!previousDir.hasUsableFullRootfs()) {
            // Preserve broken material under a unique name without occupying the next swap slot.
            val quarantine = File(previousDir.parentFile, "inspection-${java.util.UUID.randomUUID()}")
            Files.move(previousDir.toPath(), quarantine.toPath())
            clearTransaction()
            return WorkspaceEnvironmentRecovery.KEPT_FOR_INSPECTION
        }
        if (!activeExists) {
            Files.move(previousDir.toPath(), activeDir.toPath())
            clearTransaction()
            return WorkspaceEnvironmentRecovery.RESTORED_PREVIOUS
        }
        // A partial tree is in the way. Move it aside first so the restore can never delete the
        // only usable copy, then put it back if the restore fails.
        deleteTreeNoFollow(incompleteDir)
        Files.move(activeDir.toPath(), incompleteDir.toPath())
        try {
            Files.move(previousDir.toPath(), activeDir.toPath())
        } catch (error: Throwable) {
            if (!Files.exists(activeDir.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.exists(incompleteDir.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                runCatching { Files.move(incompleteDir.toPath(), activeDir.toPath()) }
                    .onFailure { error.addSuppressed(it) }
            }
            throw error
        }
        deleteTreeNoFollow(incompleteDir)
        clearTransaction()
        return WorkspaceEnvironmentRecovery.RESTORED_PREVIOUS
    }

    private fun writeTransaction(transaction: WorkspaceEnvironmentTransaction) {
        val parent = transactionFile.parentFile
        require(parent != null) { "Transaction journal has no parent directory" }
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create the shared environment directory: ${parent.path}"
        }
        val temp = File(parent, "${transactionFile.name}.tmp")
        Files.deleteIfExists(temp.toPath())
        FileOutputStream(temp).use { output ->
            output.write(JsonInstant.encodeToString(transaction).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicReplace(temp, transactionFile)
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun clearTransaction() {
        Files.deleteIfExists(transactionFile.toPath())
    }

    private fun deletePartialRecoveryArchive() {
        val parent = recoveryArchive.parentFile ?: return
        Files.deleteIfExists(File(parent, "${recoveryArchive.name}.partial").toPath())
    }

    companion object {
        const val RESTORE_DIR_NAME = "restore"

        /** Matches the limits the portable archive uses for a full rootfs payload. */
        val DEFAULT_LIMITS = WorkspaceArchiveTar.Limits(
            maxEntries = 400_000,
            maxEntryBytes = 32L * 1024 * 1024 * 1024,
            maxPayloadBytes = 32L * 1024 * 1024 * 1024,
        )
    }
}

/**
 * Recursive delete that never follows a symbolic link. `File.deleteRecursively()` descends into a
 * symlinked directory and would delete the link target's contents, which a Linux rootfs is full
 * of (`/lib64 -> /usr/lib64` and friends).
 */
internal fun deleteTreeNoFollow(target: File) {
    val path = target.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(path)
        return
    }
    Files.newDirectoryStream(path).use { children ->
        children.forEach { child -> deleteTreeNoFollow(child.toFile()) }
    }
    Files.deleteIfExists(path)
}

/** Recursive size of [target], used for the shared environment space report. */
internal fun directorySize(target: File): Long {
    val path = target.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        return runCatching { Files.size(path) }.getOrDefault(0L)
    }
    var total = 0L
    Files.newDirectoryStream(path).use { children ->
        children.forEach { child -> total += directorySize(child.toFile()) }
    }
    return total
}
