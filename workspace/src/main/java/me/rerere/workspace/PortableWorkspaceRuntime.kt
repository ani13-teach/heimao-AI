package me.rerere.workspace

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class WorkspaceRuntimePaths(val root: String, val filesDir: File, val homeDir: File, val tempDir: File)

/** A recoverable migration: source is retained until HOME and the runtime marker are durable. */
internal object PortableWorkspaceRuntime {
    internal enum class Stage {
        FILE_SYNCED, DIRECTORY_SYNCED, HOME_RENAMED, HOME_PARENT_SYNCED,
        MARKER_SYNCED, MARKER_RENAMED, MARKER_PARENT_SYNCED, BEFORE_SOURCE_REMOVAL
    }

    @Synchronized
    fun prepare(
        dir: File,
        externalHome: Boolean,
        checkpoint: (Stage, File) -> Unit = { _, _ -> },
        directorySync: (File) -> Unit = ::syncDirectory
    ): WorkspaceRuntimePaths {
        fun syncDirectoryTree(home: File) = persistTree(home, checkpoint, directorySync)
        fun syncHomeParent() {
            directorySync(dir)
            dir.parentFile?.let(directorySync)
            checkpoint(Stage.HOME_PARENT_SYNCED, dir)
        }
        fun retireSource(linux: File) {
            checkpoint(Stage.BEFORE_SOURCE_REMOVAL, linux)
            removeTree(linux)
        }
        require(!Files.isSymbolicLink(dir.toPath())) { "Workspace is a symbolic link" }
        Files.createDirectories(dir.toPath())
        val files = directory(dir, "files")
        val temp = directory(dir, "tmp")
        val home = File(dir, "home")
        val marker = File(dir, "runtime")
        require(!Files.isSymbolicLink(marker.toPath())) { "Unsafe runtime marker" }
        if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
            require(marker.isFile && marker.length() < 64 && marker.readText() == "portable-v1") { "Unknown runtime marker" }
            require(!Files.isSymbolicLink(home.toPath()) && home.isDirectory) { "Missing or unsafe portable HOME" }
            val linux = File(dir, "linux")
            if (Files.exists(linux.toPath(), NOFOLLOW_LINKS)) {
                syncDirectoryTree(home)
                syncHomeParent()
                syncFile(marker)
                checkpoint(Stage.MARKER_SYNCED, marker)
                directorySync(dir)
                checkpoint(Stage.MARKER_PARENT_SYNCED, dir)
                retireSource(linux)
            }
            return WorkspaceRuntimePaths(dir.name, files, home, temp)
        }
        val linux = File(dir, "linux")
        require(!Files.isSymbolicLink(linux.toPath())) { "Unsafe legacy Linux directory" }
        val source = File(linux, "root")
        if (!externalHome && Files.exists(source.toPath(), NOFOLLOW_LINKS)) {
            require(Files.isDirectory(source.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(source.toPath())) { "Unsafe legacy HOME" }
            val original = manifest(source)
            if (Files.exists(home.toPath(), NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(home.toPath()) && home.isDirectory && manifest(home) == original) { "HOME migration conflict" }
            } else {
                val staging = File(dir, "portable-home-staging")
                removeTree(staging)
                copy(source, staging, checkpoint, directorySync)
                check(manifest(staging) == original && manifest(source) == original) { "HOME migration verification failed" }
                Files.move(staging.toPath(), home.toPath())
                checkpoint(Stage.HOME_RENAMED, home)
            }
            check(manifest(source) == original && manifest(home) == original) { "HOME changed during migration" }
        } else {
            directory(dir, "home")
        }
        syncDirectoryTree(home)
        syncHomeParent()
        val pending = File(dir, "runtime.tmp")
        require(!Files.isSymbolicLink(pending.toPath())) { "Unsafe runtime staging marker" }
        FileOutputStream(pending).use { it.write("portable-v1".toByteArray()); it.fd.sync() }
        checkpoint(Stage.MARKER_SYNCED, pending)
        Files.move(pending.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        checkpoint(Stage.MARKER_RENAMED, marker)
        directorySync(dir)
        checkpoint(Stage.MARKER_PARENT_SYNCED, dir)
        retireSource(linux)
        return WorkspaceRuntimePaths(dir.name, files, home, temp)
    }

    private fun directory(parent: File, name: String): File = File(parent, name).also {
        require(!Files.isSymbolicLink(it.toPath())) { "Unsafe runtime directory: $name" }
        Files.createDirectories(it.toPath())
        require(it.isDirectory)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Workspace migration interrupted")
    }

    private fun manifest(root: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        fun visit(file: File) {
            checkInterrupted()
            val p = file.toPath()
            val key = root.toPath().relativize(p).toString()
            result[key] = when {
                Files.isSymbolicLink(p) -> "L:" + Files.readSymbolicLink(p)
                Files.isDirectory(p, NOFOLLOW_LINKS) -> "D"
                Files.isRegularFile(p, NOFOLLOW_LINKS) -> {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkInterrupted()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    "F:" + digest.digest().joinToString("") { "%02x".format(it) }
                }
                else -> error("Unsupported HOME entry: $file")
            }
            if (result[key] == "D") Files.newDirectoryStream(p).use { children -> children.forEach { visit(it.toFile()) } }
        }
        visit(root)
        return result
    }

    private fun syncDirectory(dir: File) {
        checkInterrupted()
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun syncFile(file: File) {
        checkInterrupted()
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun persistTree(root: File, checkpoint: (Stage, File) -> Unit, directorySync: (File) -> Unit) {
        checkInterrupted()
        val path = root.toPath()
        when {
            Files.isSymbolicLink(path) -> Unit // The containing directory persists the link itself.
            Files.isDirectory(path, NOFOLLOW_LINKS) -> {
                Files.newDirectoryStream(path).use { children ->
                    children.forEach { persistTree(it.toFile(), checkpoint, directorySync) }
                }
                directorySync(root)
                checkpoint(Stage.DIRECTORY_SYNCED, root)
            }
            Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                syncFile(root)
                checkpoint(Stage.FILE_SYNCED, root)
            }
            else -> error("Unsupported HOME entry: $root")
        }
    }

    private fun copy(
        source: File,
        target: File,
        checkpoint: (Stage, File) -> Unit,
        directorySync: (File) -> Unit
    ) {
        checkInterrupted()
        val p = source.toPath()
        when {
            Files.isSymbolicLink(p) -> Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(p))
            Files.isDirectory(p, NOFOLLOW_LINKS) -> {
                Files.createDirectory(target.toPath())
                Files.newDirectoryStream(p).use { children ->
                    children.forEach { copy(it.toFile(), File(target, it.fileName.toString()), checkpoint, directorySync) }
                }
                directorySync(target)
                checkpoint(Stage.DIRECTORY_SYNCED, target)
            }
            Files.isRegularFile(p, NOFOLLOW_LINKS) -> source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkInterrupted()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                    checkpoint(Stage.FILE_SYNCED, target)
                }
            }
            else -> error("Unsupported HOME entry: $source")
        }
    }

    private fun removeTree(file: File) {
        checkInterrupted()
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
            Files.newDirectoryStream(file.toPath()).use { children -> children.forEach { removeTree(it.toFile()) } }
        }
        Files.delete(file.toPath())
    }
}
