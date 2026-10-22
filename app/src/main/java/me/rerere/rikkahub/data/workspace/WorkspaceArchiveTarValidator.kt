package me.rerere.rikkahub.data.workspace

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Validates the same entry/link rules as fresh-directory extraction without materializing files. */
internal object WorkspaceArchiveTarValidator {
    private enum class Kind { DIRECTORY, FILE, LINK }

    fun validate(
        input: InputStream,
        limits: WorkspaceArchiveTar.Limits = WorkspaceArchiveTar.Limits(),
        maxArchiveBytes: Long = 32L * 1024 * 1024 * 1024,
        onEntry: () -> Unit = {},
        onFileBytes: (Long) -> Unit = {},
        onReadBytes: (Long) -> Unit = {},
        forbiddenPaths: Set<String> = emptySet(),
    ): WorkspaceArchiveTar.PayloadSummary {
        val tracked = TrackingInput(input, maxArchiveBytes, onReadBytes)
        val tar = TarArchiveInputStream(tracked, 512)
        val nodes = hashMapOf<String, Kind>()
        val explicit = hashSetOf<String>()
        var count = 0
        var payload = 0L
        val buffer = ByteArray(ArchiveCompression.BUFFER_SIZE)
        while (true) {
            val entry = tar.nextTarEntry ?: break
            require(entry.isCheckSumOK) { "Invalid TAR header checksum" }
            count++
            require(count <= limits.maxEntries) { "Too many TAR entries" }
            onEntry()
            val raw = entry.name
            val normalized = if (raw.endsWith('/')) {
                require(raw.length > 1 && !raw.endsWith("//")) { "Invalid TAR directory path" }
                raw.dropLast(1)
            } else raw
            val name = WorkspaceArchivePathSafety.normalize(normalized)
            require(forbiddenPaths.none { name == it || name.startsWith("$it/") }) { "Forbidden TAR path" }
            require(explicit.add(name)) { "Duplicate TAR entry: $name" }
            require(entry.size in 0..limits.maxEntryBytes) { "TAR entry is too large" }
            val components = name.split('/')
            for (index in 1 until components.size) {
                val parent = components.take(index).joinToString("/")
                require(nodes[parent] == null || nodes[parent] == Kind.DIRECTORY) {
                    "TAR entry has a non-directory ancestor"
                }
                nodes[parent] = Kind.DIRECTORY
            }
            when {
                entry.isDirectory -> {
                    require(nodes[name] == null || nodes[name] == Kind.DIRECTORY) { "TAR directory conflicts with a file" }
                    nodes[name] = Kind.DIRECTORY
                }
                entry.isSymbolicLink -> {
                    require(nodes[name] == null) { "TAR link target already exists" }
                    validateLink(name, entry.linkName)
                    nodes[name] = Kind.LINK
                }
                entry.isLink -> {
                    require(nodes[name] == null) { "TAR hardlink target already exists" }
                    require(entry.linkName.length <= 16 * 1024) { "TAR hardlink target is too long" }
                    val source = WorkspaceArchivePathSafety.normalize(entry.linkName)
                    require(source in explicit && nodes[source] == Kind.FILE) {
                        "TAR hardlink target must precede link and be a regular file"
                    }
                    nodes[name] = Kind.FILE
                }
                entry.isFile -> {
                    require(nodes[name] == null) { "TAR file target already exists" }
                    payload = Math.addExact(payload, entry.size)
                    require(payload <= limits.maxPayloadBytes) { "TAR payload is too large" }
                    onFileBytes(entry.size)
                    nodes[name] = Kind.FILE
                }
                else -> error("Unsupported TAR entry type")
            }
            var remaining = entry.size
            while (remaining > 0) {
                val read = tar.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                require(read > 0) { "Unexpected EOF in TAR entry" }
                remaining -= read
            }
        }
        require(tracked.length >= 1024 && tracked.tail.all { it == 0.toByte() }) { "Truncated TAR archive" }
        // Read the complete outer entry so its CRC, length and digest include all TAR padding.
        while (true) {
            val read = tracked.read(buffer)
            if (read < 0) break
            require((0 until read).all { buffer[it] == 0.toByte() }) { "Non-zero data after TAR end marker" }
        }
        require(tracked.length % 512L == 0L) { "Truncated TAR padding" }
        return WorkspaceArchiveTar.PayloadSummary(tracked.length, tracked.digest(), count)
    }

    private fun validateLink(name: String, link: String) {
        require(link.isNotBlank() && link.length <= 16 * 1024 && '\u0000' !in link) { "Invalid TAR link target" }
        val value = link.replace('\\', '/')
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) { "Drive-qualified TAR link target" }
        val parts = if (value.startsWith('/')) mutableListOf() else name.split('/').dropLast(1).toMutableList()
        value.split('/').forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "TAR link escapes staging root" }; parts.removeAt(parts.lastIndex) }
                else -> parts += component
            }
        }
    }

    private class TrackingInput(
        private val input: InputStream,
        private val maximum: Long,
        private val onRead: (Long) -> Unit,
    ) : InputStream() {
        var length = 0L
            private set
        val tail = ByteArray(1024)
        private val hash = MessageDigest.getInstance("SHA-256")
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte) < 0) -1 else byte[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            val read = input.read(bytes, offset, count)
            if (read > 0) {
                length = Math.addExact(length, read.toLong())
                require(length <= maximum) { "TAR archive exceeds size limit" }
                onRead(read.toLong())
                hash.update(bytes, offset, read)
                val kept = minOf(read, tail.size)
                if (kept < tail.size) System.arraycopy(tail, kept, tail, 0, tail.size - kept)
                System.arraycopy(bytes, offset + read - kept, tail, tail.size - kept, kept)
            }
            return read
        }
        override fun skip(count: Long): Long {
            val bytes = ByteArray(minOf(count.coerceAtLeast(0), ArchiveCompression.BUFFER_SIZE.toLong()).toInt())
            var remaining = count
            while (remaining > 0) {
                val read = read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                if (read <= 0) break
                remaining -= read
            }
            return count - remaining
        }
        override fun close() = Unit
        fun digest(): String = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
