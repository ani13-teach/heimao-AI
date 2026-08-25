package me.rerere.rikkahub.data.workspace

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

/** Streaming TAR codec used by both the files and Linux workspace payloads. */
object WorkspaceArchiveTar {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_LINK_LENGTH = 16 * 1024
    // Keep rwx plus setuid/setgid/sticky bits; masking to 0777 silently loses valid modes.
    private const val PERMISSION_MASK = 0xfff
    private const val OWNER_FILE_MODE = 0x180 // 0600
    private const val DEFAULT_FILE_MODE = 0x1a4 // 0644
    private const val OWNER_DIRECTORY_MODE = 0x1c0 // 0700
    private const val DEFAULT_DIRECTORY_MODE = 0x1ed // 0755

    data class PayloadSummary(
        val length: Long,
        val sha256: String,
        val entries: Int,
    )

    data class Limits(
        val maxEntries: Int = 200_000,
        val maxEntryBytes: Long = 32L * 1024 * 1024 * 1024,
        val maxPayloadBytes: Long = 32L * 1024 * 1024 * 1024,
    )

    fun create(
        sourceRoot: File,
        output: File,
        excludedPaths: Set<String> = emptySet(),
        limits: Limits = Limits(),
        onEntry: () -> Unit = {},
        onFileBytes: (Long) -> Unit = {},
        onOutputBytes: (Long) -> Unit = {},
    ): PayloadSummary {
        output.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        val state = WriteState()
        DigestOutputStream(output.outputStream(), digest, onOutputBytes).use { digestOut ->
            TarArchiveOutputStream(digestOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                if (Files.exists(sourceRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    count = writeTree(
                        root = sourceRoot,
                        current = sourceRoot.toPath(),
                        tarOut = tarOut,
                        hardLinks = HashMap(),
                        excludedPaths = excludedPaths.map { WorkspaceArchivePathSafety.normalize(it) }.toSet(),
                        limits = limits,
                        state = state,
                        onEntry = onEntry,
                        onFileBytes = onFileBytes,
                    )
                }
                tarOut.finish()
            }
        }
        return PayloadSummary(
            length = output.length(),
            sha256 = digest.digest().toHexString(),
            entries = count,
        )
    }

    fun extract(
        input: File,
        targetRoot: File,
        limits: Limits = Limits(),
        onEntry: () -> Unit = {},
        onFileBytes: (Long) -> Unit = {},
    ): Int {
        require(hasTarEndMarkers(input)) { "Truncated TAR archive" }
        WorkspaceArchivePathSafety.ensureDirectory(targetRoot)
        var entries = 0
        var payloadBytes = 0L
        val seen = HashSet<String>()
        val directories = ArrayList<Pair<File, Int>>()
        TarArchiveInputStream(BufferedInputStream(input.inputStream())).use { tarIn ->
            while (true) {
                val entry = tarIn.nextTarEntry ?: break
                entries++
                require(entries <= limits.maxEntries) { "Too many TAR entries" }
                onEntry()
                val rawName = entry.name
                val normalizedName = if (rawName.endsWith("/")) {
                    // TAR writers commonly serialize directory members with one trailing slash;
                    // it is metadata, not an empty path component. Reject malformed repeated
                    // trailing separators before applying the strict lexical path checks.
                    require(rawName.length > 1 && !rawName.endsWith("//")) {
                        "Invalid TAR directory path: $rawName"
                    }
                    rawName.removeSuffix("/")
                } else {
                    rawName
                }
                val name = WorkspaceArchivePathSafety.normalize(normalizedName)
                require(seen.add(name)) { "Duplicate TAR entry: $name" }
                require(entry.size >= 0L && entry.size <= limits.maxEntryBytes) {
                    "TAR entry is too large: $name"
                }
                val target = WorkspaceArchivePathSafety.resolve(targetRoot, name)
                WorkspaceArchivePathSafety.ensureNoSymlinkAncestors(targetRoot, target, includeTarget = true)

                when {
                    entry.isDirectory -> {
                        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                            Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            "TAR directory conflicts with a file: $name"
                        }
                        createDirectory(target)
                        directories += target to entry.mode
                    }

                    entry.isSymbolicLink -> {
                        require(entry.linkName.length <= MAX_LINK_LENGTH) { "TAR link target is too long" }
                        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            "TAR entry already exists: $name"
                        }
                        val resolved = WorkspaceArchivePathSafety.resolveLinkTarget(
                            targetRoot,
                            name,
                            entry.linkName,
                        )
                        val relativeTarget = target.toPath().parent.relativize(resolved)
                        Files.createSymbolicLink(target.toPath(), relativeTarget)
                    }

                    entry.isLink -> {
                        require(entry.linkName.length <= MAX_LINK_LENGTH) { "TAR hardlink target is too long" }
                        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            "TAR entry already exists: $name"
                        }
                        val sourceName = WorkspaceArchivePathSafety.normalize(entry.linkName)
                        require(sourceName in seen) {
                            "TAR hardlink target must precede link: $name -> $sourceName"
                        }
                        val source = WorkspaceArchivePathSafety.resolve(targetRoot, sourceName)
                        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            "TAR hardlink target is not a regular file: $sourceName"
                        }
                        Files.createLink(target.toPath(), source.toPath())
                        applyMode(
                            target,
                            if (entry.mode > 0) entry.mode else DEFAULT_FILE_MODE,
                        )
                    }

                    entry.isFile -> {
                        payloadBytes = Math.addExact(payloadBytes, entry.size)
                        require(payloadBytes <= limits.maxPayloadBytes) { "TAR payload is too large" }
                        // Count before writing so a malicious entry cannot fill staging before the
                        // shared budget rejects it. A truncated TAR still fails in copyExactly.
                        onFileBytes(entry.size)
                        target.parentFile?.let { createDirectory(it) }
                        Files.newOutputStream(
                            target.toPath(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        ).use { output ->
                            copyExactly(tarIn, output, entry.size)
                        }
                        applyMode(
                            target,
                            if (entry.mode > 0) entry.mode else DEFAULT_FILE_MODE,
                        )
                    }

                    else -> throw IllegalArgumentException("Unsupported TAR entry type: $name")
                }
            }
        }
        directories.asReversed().forEach { (directory, mode) ->
            applyMode(directory, if (mode > 0) mode else DEFAULT_DIRECTORY_MODE)
        }
        return entries
    }

    private fun writeTree(
        root: File,
        current: java.nio.file.Path,
        tarOut: TarArchiveOutputStream,
        hardLinks: MutableMap<Any, String>,
        excludedPaths: Set<String>,
        limits: Limits,
        state: WriteState,
        onEntry: () -> Unit,
        onFileBytes: (Long) -> Unit,
    ): Int {
        var count = 0
        val children = ArrayList<java.nio.file.Path>()
        // Do not use Stream.toList(): it is only available on newer Java runtimes than API 26.
        Files.list(current).use { stream ->
            stream.forEach { children.add(it) }
        }
        children.sortBy { it.fileName.toString() }
        children.forEach { path ->
            val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            if (excludedPaths.any { relative == it || relative.startsWith("$it/") }) return@forEach
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            when {
                attributes.isDirectory -> {
                    val entry = TarArchiveEntry(relative, TarConstants.LF_DIR)
                    entry.mode = readMode(path, isDirectory = true)
                    entry.modTime = Date(attributes.lastModifiedTime().toMillis())
                    recordEntry(limits, state, onEntry)
                    tarOut.putArchiveEntry(entry)
                    tarOut.closeArchiveEntry()
                    count++
                    count += writeTree(
                        root,
                        path,
                        tarOut,
                        hardLinks,
                        excludedPaths,
                        limits,
                        state,
                        onEntry,
                        onFileBytes,
                    )
                }

                attributes.isSymbolicLink -> {
                    val entry = TarArchiveEntry(relative, TarConstants.LF_SYMLINK)
                    val link = Files.readSymbolicLink(path).toString()
                    require(link.length <= MAX_LINK_LENGTH) { "Symbolic link target is too long: $relative" }
                    entry.linkName = link
                    entry.mode = readMode(path, isDirectory = false)
                    entry.modTime = Date(attributes.lastModifiedTime().toMillis())
                    recordEntry(limits, state, onEntry)
                    tarOut.putArchiveEntry(entry)
                    tarOut.closeArchiveEntry()
                    count++
                }

                attributes.isRegularFile -> {
                    require(attributes.size() <= limits.maxEntryBytes) {
                        "TAR entry is too large: $relative"
                    }
                    val key = attributes.fileKey()
                    val previous = key?.let { hardLinks[it] }
                    recordEntry(limits, state, onEntry)
                    if (previous != null) {
                        val entry = TarArchiveEntry(relative, TarConstants.LF_LINK)
                        entry.linkName = previous
                        entry.mode = readMode(path, isDirectory = false)
                        entry.modTime = Date(attributes.lastModifiedTime().toMillis())
                        tarOut.putArchiveEntry(entry)
                        tarOut.closeArchiveEntry()
                    } else {
                        val entry = TarArchiveEntry(relative, TarConstants.LF_NORMAL)
                        entry.size = attributes.size()
                        entry.mode = readMode(path, isDirectory = false)
                        entry.modTime = Date(attributes.lastModifiedTime().toMillis())
                        tarOut.putArchiveEntry(entry)
                        Files.newInputStream(path).use { input ->
                            copyFileToTar(input, tarOut, attributes.size(), state, limits, onFileBytes)
                        }
                        tarOut.closeArchiveEntry()
                        if (key != null) hardLinks[key] = relative
                    }
                    count++
                }
            }
        }
        return count
    }

    private fun recordEntry(
        limits: Limits,
        state: WriteState,
        onEntry: () -> Unit,
    ) {
        state.entries++
        require(state.entries <= limits.maxEntries) { "Too many TAR entries" }
        onEntry()
    }

    private fun copyFileToTar(
        input: InputStream,
        output: OutputStream,
        length: Long,
        state: WriteState,
        limits: Limits,
        onFileBytes: (Long) -> Unit,
    ) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "Unexpected EOF while reading TAR source" }
            state.payloadBytes = Math.addExact(state.payloadBytes, read.toLong())
            require(state.payloadBytes <= limits.maxPayloadBytes) { "TAR payload is too large" }
            onFileBytes(read.toLong())
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "Unexpected EOF in TAR entry" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun hasTarEndMarkers(input: File): Boolean {
        if (!input.isFile || input.length() < 1024L) return false
        RandomAccessFile(input, "r").use { randomAccess ->
            randomAccess.seek(input.length() - 1024L)
            val end = ByteArray(1024)
            randomAccess.readFully(end)
            return end.all { it == 0.toByte() }
        }
    }

    private fun createDirectory(directory: File) {
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "TAR path is not a directory: ${directory.path}"
            }
        } else {
            require(directory.mkdirs() || Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Unable to create TAR directory: ${directory.path}"
            }
        }
        require(!Files.isSymbolicLink(directory.toPath())) { "TAR directory is a symbolic link" }
    }

    private fun readMode(path: java.nio.file.Path, isDirectory: Boolean): Int {
        val unixMode = runCatching {
            (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        }.getOrNull()
        if (unixMode != null) return unixMode and PERMISSION_MASK
        val readable = Files.isReadable(path)
        val writable = Files.isWritable(path)
        val executable = Files.isExecutable(path)
        return when {
            isDirectory && executable -> DEFAULT_DIRECTORY_MODE
            isDirectory -> OWNER_DIRECTORY_MODE
            executable -> DEFAULT_DIRECTORY_MODE
            writable && readable -> DEFAULT_FILE_MODE
            readable -> DEFAULT_FILE_MODE
            else -> OWNER_FILE_MODE
        }
    }

    /** Apply exact permission bits; File.set* remains only an API-26/vendor fallback. */
    internal fun applyMode(file: File, mode: Int) {
        val permissions = mode and PERMISSION_MASK
        try {
            Os.chmod(file.path, permissions)
            return
        } catch (error: Throwable) {
            // JVM unit tests and old vendor runtimes may not expose android.system.Os. Keep the
            // common portable modes exact and fail loudly if the compatibility API fails too.
            val ownerOnly = permissions and 0x03f == 0
            val ok = file.setReadable(permissions and 0x100 != 0, ownerOnly) &&
                file.setWritable(permissions and 0x080 != 0, ownerOnly) &&
                file.setExecutable(permissions and 0x040 != 0, ownerOnly)
            require(ok) {
                "Unable to apply mode ${permissions.toString(8)} to ${file.path}: ${error.message}"
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") {
        "%02x".format(Locale.US, it.toInt() and 0xff)
    }

    private data class WriteState(
        var entries: Int = 0,
        var payloadBytes: Long = 0L,
    )

    private class DigestOutputStream(
        output: OutputStream,
        private val digest: MessageDigest,
        private val onBytes: (Long) -> Unit,
    ) : OutputStream() {
        private val delegate = BufferedOutputStream(output)

        override fun write(b: Int) {
            onBytes(1L)
            digest.update(b.toByte())
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            onBytes(len.toLong())
            digest.update(b, off, len)
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }
}
