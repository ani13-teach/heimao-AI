package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.model.WorkspaceRuntimeMetadata
import me.rerere.rikkahub.data.model.toWorkspaceRuntimeMetadata
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallResult
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.hasUsableFullRootfs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.RootfsPatchOptions

/**
 * Owns every mutation of the public Linux environment: install, upgrade, recovery snapshot and
 * restore.
 *
 * It never touches a workspace's private rootfs. A shared install is staged by an independent
 * installer whose manager is rooted inside the environment's own scratch directory, and only the
 * finished tree is moved into place, under the journaled exchange in
 * [WorkspaceEnvironmentTransactionStore].
 *
 * All entry points are blocking; the repository runs them on a background dispatcher.
 */
fun interface RootfsPatchOptionsProvider {
    operator fun invoke(): RootfsPatchOptions
}

internal class WorkspaceEnvironmentMaintenance(
    private val manager: WorkspaceManager,
    private val coordinator: WorkspaceRuntimeCoordinator,
    private val rootfsPatchOptionsProvider: RootfsPatchOptionsProvider,
) {

    private val environments = manager.environments

    private val rootDir: File = environments.sharedLinuxDir().parentFile
        ?: error("Shared Linux environment root is unavailable")

    private val transactions = WorkspaceEnvironmentTransactionStore(
        activeDir = environments.sharedLinuxDir(),
        previousDir = File(rootDir, WorkspaceEnvironmentLayout.PREVIOUS_DIR_NAME),
        stagingDir = File(rootDir, WorkspaceEnvironmentLayout.STAGING_DIR_NAME),
        recoveryArchive = File(
            environments.sharedRecoveryDir(),
            WorkspaceEnvironmentLayout.RECOVERY_ARCHIVE_NAME,
        ),
        transactionFile = File(rootDir, WorkspaceEnvironmentLayout.TRANSACTION_FILE_NAME),
    )

    /** The shared rootfs directory, shared by every workspace bound to the public environment. */
    val sharedLinuxDir: File get() = environments.sharedLinuxDir()

    /** True when the shared rootfs is installed and complete. */
    fun sharedInstalled(): Boolean = transactions.activeUsable()

    /** True while a shared install/upgrade exchange is journaled but not finalized. */
    fun installPending(): Boolean = transactions.hasPendingTransaction()

    /** True when a snapshot of the previously installed shared rootfs is retained. */
    fun recoveryAvailable(): Boolean = transactions.recoveryAvailable()

    fun environmentState(): SharedEnvironmentState = SharedEnvironmentState(
        installed = sharedInstalled(),
        installPending = installPending(),
        metadataJson = metadataJson(),
    )

    /** Runtime metadata of the installed shared rootfs, empty when there is none. */
    fun metadataJson(): String {
        val file = metadataFile(sharedLinuxDir)
        if (!file.isFile) return WorkspaceEnvironmentLayout.EMPTY_METADATA
        return runCatching { file.readText() }
            .getOrDefault(WorkspaceEnvironmentLayout.EMPTY_METADATA)
            .ifBlank { WorkspaceEnvironmentLayout.EMPTY_METADATA }
    }

    fun recoveryArchiveSize(): Long = transactions.recoveryArchive.length()

    fun rootfsSize(): Long = directorySize(sharedLinuxDir)

    /**
     * Settles an interrupted exchange. Runs before temp directories are cleaned so the shared
     * rootfs is coherent before anything can start in it.
     */
    fun recover(): WorkspaceEnvironmentRecovery = transactions.recover()

    /**
     * Takes the recovery snapshot, when there is something to protect. Called before every
     * maintenance action; it is idempotent and never replaces a good snapshot with an empty one.
     */
    fun snapshotRecovery(): Boolean = transactions.snapshotActiveToRecovery()

    /** Rebuilds the shared rootfs from the retained recovery snapshot. */
    fun restoreRecovery(): Boolean = transactions.restoreRecoverySnapshot()

    /**
     * Installs a new shared rootfs.
     *
     * The candidate is downloaded, patched and validated inside an independent manager rooted in
     * the environment's scratch directory, so a failure never disturbs the rootfs in use. The
     * exclusive environment lease is taken only for the snapshot and the swap, which are the only
     * steps that must not race a running workspace.
     */
    fun installSharedRootfs(
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit,
        expectedSha256: String?,
    ): RootfsInstallResult = coordinator.acquireEnvironmentExclusiveLease(
        WorkspaceRuntimeGates.SHARED_ENVIRONMENT,
        maintenance = true,
    ).use {
        transactions.recover()
        transactions.clearStaging()
        require(transactions.stagingDir.mkdirs() || transactions.stagingDir.isDirectory) {
            "Unable to create the shared rootfs staging directory: ${transactions.stagingDir.path}"
        }
        try {
            val stagingManager = WorkspaceManager(
                baseDir = transactions.stagingDir,
                rootfsPatchOptionsProvider = rootfsPatchOptionsProvider::invoke,
            )
            val stagingInstaller = RootfsInstaller(stagingManager)
            val result = stagingInstaller.install(
                root = STAGING_ROOT,
                url = url,
                onProgress = onProgress,
                expectedSha256 = expectedSha256,
            )
            val candidate = stagingManager.privateLinuxDir(STAGING_ROOT)
            require(candidate.hasUsableFullRootfs()) {
                "Staged shared rootfs is incomplete: ${candidate.path}"
            }
            // RootfsInstaller currently patches with its built-in defaults and exposes no patch
            // options hook. Replace only that generated DNS file, then apply the app's actual
            // options. Distribution-provided resolv.conf files remain untouched.
            patchSharedRootfsCandidate(candidate, rootfsPatchOptionsProvider())
            listOf("root", "tmp", "var/tmp", "workspace").forEach { File(candidate, it).mkdirs() }
            writeMetadata(candidate, result, url)
            snapshotRecovery()
            transactions.prepareExchange(candidate)
            transactions.finalizeExchange()
            result
        } finally {
            transactions.clearStaging()
        }
    }

    private fun writeMetadata(candidate: File, result: RootfsInstallResult, sourceUrl: String) {
        val metadata: WorkspaceRuntimeMetadata = result.toWorkspaceRuntimeMetadata(
            sourceUrl = sourceUrl,
            installedAt = System.currentTimeMillis(),
        )
        val file = metadataFile(candidate)
        val parent = file.parentFile
        require(parent != null) { "Shared environment metadata has no parent directory" }
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create the shared environment directory: ${parent.path}"
        }
        val temp = File(parent, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(JsonInstant.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Metadata travels with its rootfs through installation, crash recovery and retained snapshots.
    private fun metadataFile(rootfs: File): File = File(rootfs, ".heimao-runtime-metadata.json")

    private companion object {
        /** Fixed root name used inside the isolated staging manager. */
        const val STAGING_ROOT = "candidate"
    }
}

internal fun patchSharedRootfsCandidate(candidate: File, options: RootfsPatchOptions) {
    val resolvConf = File(candidate, "etc/resolv.conf")
    if (resolvConf.isFile && resolvConf.readLines().firstOrNull() == GENERATED_DNS_HEADER) {
        require(resolvConf.delete()) {
            "Unable to replace installer DNS configuration: ${resolvConf.path}"
        }
    }
    RootfsPatcher().patch(candidate, options)
}

private const val GENERATED_DNS_HEADER = "# Generated by 黑猫ai workspace."
