package me.rerere.rikkahub.data.workspace

import java.io.File
import java.nio.file.Files

/** Atomic directory activation primitive shared by workspace and legacy-folder restore. */
internal class WorkspaceArchiveDirectoryTransaction(
    private val staging: File,
    private val target: File,
    private val recovery: File,
) {
    private var oldDirectoryMoved = false

    fun activate() {
        require(staging.isDirectory && !Files.isSymbolicLink(staging.toPath())) {
            "Restore staging directory is invalid: ${staging.path}"
        }
        require(!Files.isSymbolicLink(target.toPath())) {
            "Restore target is a symbolic link: ${target.path}"
        }
        recovery.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) { "Unable to create restore recovery directory: ${parent.path}" }
        }
        if (target.exists()) {
            require(target.isDirectory) { "Restore target is not a directory: ${target.path}" }
            require(!recovery.exists()) { "Restore recovery path already exists: ${recovery.path}" }
            require(target.renameTo(recovery)) { "Failed to move old directory to recovery" }
            oldDirectoryMoved = true
        }
        try {
            require(staging.renameTo(target)) { "Failed to atomically activate restore staging" }
        } catch (error: Throwable) {
            if (oldDirectoryMoved && recovery.exists() && !target.exists() && !recovery.renameTo(target)) {
                error.addSuppressed(IllegalStateException("Failed to restore old directory"))
            }
            throw error
        }
    }

    fun commit() {
        if (recovery.exists()) {
            require(recovery.deleteRecursively()) { "Failed to remove restore recovery" }
        }
    }

    fun rollback() {
        if (target.exists()) require(target.deleteRecursively()) {
            "Failed to remove activated restore directory"
        }
        if (oldDirectoryMoved && recovery.exists()) {
            require(recovery.renameTo(target)) { "Failed to restore old directory" }
        }
    }
}
