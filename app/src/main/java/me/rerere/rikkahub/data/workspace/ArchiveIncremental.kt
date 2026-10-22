package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@Serializable
internal data class IncrementalArchiveEntry(
    val path: String, val length: Long, val sha256: String, val fromBase: Boolean = false,
)

@Serializable
internal data class IncrementalArchiveManifest(
    val format: String = "heimao.workspace.incremental",
    val version: Int = 2,
    val runtime: String = "",
    val baseManifestSha256: String,
    val entries: List<IncrementalArchiveEntry>,
) {
    fun validate() {
        require(format == "heimao.workspace.incremental" && version == 2 && runtime == "portable-v1") { "Unsupported incremental backup format" }
        validateEntries()
    }

    fun validateForRestore() {
        require(format == "heimao.workspace.incremental" &&
            ((version == 2 && runtime == "portable-v1") || (version == 1 && runtime.isEmpty()))) {
            "Unsupported incremental backup format"
        }
        validateEntries()
    }

    private fun validateEntries() {
        require(baseManifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid base backup identity" }
        require(entries.size in 1..200_000) { "Invalid incremental entry count" }
        require(entries.first().path == "manifest.json" && !entries.first().fromBase) { "Incremental backup must contain its current manifest" }
        val paths = hashSetOf<String>()
        var totalBytes = 0L
        entries.forEach { entry ->
            require(WorkspaceArchivePathSafety.normalize(entry.path) == entry.path && paths.add(entry.path)) { "Unsafe or duplicate incremental entry" }
            require(entry.path != ArchiveIncremental.INDEX_PATH) { "Reserved incremental entry path" }
            require(entry.length in 0..32L * 1024 * 1024 * 1024) { "Incremental entry is too large" }
            totalBytes = Math.addExact(totalBytes, entry.length)
            require(totalBytes <= 64L * 1024 * 1024 * 1024) { "Incremental restore exceeds archive budget" }
            require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid incremental payload digest" }
        }
    }
}

internal class IncrementalBaseRequiredException : IllegalArgumentException("请选择该增量备份对应的完整基础备份")

/** A differential backup depends on one full archive, never on earlier differential archives. */
internal object ArchiveIncremental {
    const val INDEX_PATH = "incremental.json"
    private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun isIncremental(file: File): Boolean = ZipInputStream(file.inputStream().buffered()).use {
        it.nextEntry?.name == INDEX_PATH
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun describe(path: String, file: File): IncrementalArchiveEntry = file.inputStream().buffered(ArchiveCompression.BUFFER_SIZE).use {
        digestStream(path, it)
    }

    fun describe(path: String, bytes: ByteArray) = IncrementalArchiveEntry(path, bytes.size.toLong(), digest(bytes))

    fun plan(base: File, current: List<IncrementalArchiveEntry>, budget: WorkspaceArchiveBudget = WorkspaceArchiveBudget()): IncrementalArchiveManifest {
        checkedDirectory(base, budget)
        requireCurrentWorkspaceArchive(base, json, 2L * 1024 * 1024, budget)
        budget.checkCompressedFile(base)
        val index = linkedMapOf<String, IncrementalArchiveEntry>()
        ZipInputStream(base.inputStream().buffered(ArchiveCompression.BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                budget.recordZipEntry()
                val path = WorkspaceArchivePathSafety.normalize(entry.name)
                require(!entry.isDirectory && path !in index) { "Invalid base backup entries" }
                if (index.isEmpty()) require(path == "manifest.json") { "基础备份必须是完整备份，不能是另一个增量备份" }
                index[path] = digestStream(path, zip) { budget.recordZipDecompressedBytes(it) }
                zip.closeEntry()
            }
        }
        val baseManifest = index["manifest.json"] ?: error("基础备份缺少 manifest.json")
        return IncrementalArchiveManifest(
            runtime = "portable-v1",
            baseManifestSha256 = baseManifest.sha256,
            entries = current.map { entry ->
                val old = index[entry.path]
                entry.copy(fromBase = entry.path != "manifest.json" && old?.length == entry.length && old.sha256 == entry.sha256)
            },
        ).also { it.validate() }
    }

    fun encode(manifest: IncrementalArchiveManifest): ByteArray {
        manifest.validate()
        return json.encodeToString(manifest).toByteArray().also { require(it.size <= MAX_INDEX_BYTES) { "Incremental index is too large" } }
    }

    /** Streams a virtual full ZIP directly into the existing restore/verification pipeline. */
    fun open(
        file: File, base: File?, budget: WorkspaceArchiveBudget = WorkspaceArchiveBudget(), allowLegacyRestore: Boolean = false,
    ): ZipInputStream {
        if (base == null) throw IncrementalBaseRequiredException()
        if (!allowLegacyRestore) requireCurrentWorkspaceArchive(base, json, 2L * 1024 * 1024, budget)
        val deltaZip = checkedZipFile(file, budget)
        var baseZip: ZipFile? = null
        try {
            val deltaEntries = directory(deltaZip, budget)
            val recipeEntry = deltaEntries[INDEX_PATH] ?: error("Incremental index is missing")
            val recipeBytes = checkedBytes(deltaZip, recipeEntry, MAX_INDEX_BYTES, budget)
            val recipe = json.decodeFromString<IncrementalArchiveManifest>(recipeBytes.toString(Charsets.UTF_8))
            if (allowLegacyRestore) recipe.validateForRestore() else recipe.validate()
            val expectedDelta = recipe.entries.filterNot { it.fromBase }.map { it.path }.toSet() + INDEX_PATH
            require(deltaEntries.keys == expectedDelta) { "Incremental backup has missing or unexpected entries" }
            val openedBase = checkedZipFile(base, budget)
            baseZip = openedBase
            val baseEntries = directory(openedBase, budget)
            require(baseEntries.keys.firstOrNull() == "manifest.json") { "基础备份必须是完整备份" }
            val manifest = baseEntries["manifest.json"] ?: error("基础备份缺少 manifest.json")
            val baseManifestBytes = checkedBytes(openedBase, manifest, 2L * 1024 * 1024, budget)
            require(digest(baseManifestBytes) == recipe.baseManifestSha256) {
                "所选完整备份与增量备份不匹配"
            }
            recipe.entries.forEach { descriptor ->
                val source = if (descriptor.fromBase) baseEntries else deltaEntries
                require(source[descriptor.path]?.size == descriptor.length) { "Incremental payload is missing or has changed size" }
            }
            val currentManifest = deltaEntries.getValue("manifest.json")
            val currentBytes = checkedBytes(deltaZip, currentManifest, 2L * 1024 * 1024, budget)
            if (allowLegacyRestore && recipe.version == 1) {
                val baseManifest = decodeWorkspaceArchiveForRestore(baseManifestBytes.toString(Charsets.UTF_8), json)
                val current = decodeWorkspaceArchiveForRestore(currentBytes.toString(Charsets.UTF_8), json)
                require(baseManifest.isLegacyLinux && current.isLegacyLinux) {
                    "Legacy incremental backups require v2 Linux base and current manifests"
                }
            } else {
                requireWorkspaceManifestVersion(baseManifestBytes.toString(Charsets.UTF_8), json, WorkspaceArchiveManifest.FORMAT)
                json.decodeFromString<WorkspaceArchiveManifest>(baseManifestBytes.toString(Charsets.UTF_8)).validate()
                requireWorkspaceManifestVersion(currentBytes.toString(Charsets.UTF_8), json, WorkspaceArchiveManifest.FORMAT)
                json.decodeFromString<WorkspaceArchiveManifest>(currentBytes.toString(Charsets.UTF_8)).validate()
            }
            return OverlayZipInput(recipe, deltaZip, openedBase, deltaEntries, baseEntries, budget)
        } catch (error: Throwable) {
            runCatching { deltaZip.close() }.onFailure { error.addSuppressed(it) }
            runCatching { baseZip?.close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private fun checkedZipFile(file: File, budget: WorkspaceArchiveBudget): ZipFile {
        budget.checkCompressedFile(file)
        checkedDirectory(file, budget)
        return ZipFile(file)
    }

    internal fun checkedDirectory(file: File, budget: WorkspaceArchiveBudget? = null) {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 22) { "Truncated ZIP directory" }
            val tail = ByteArray(minOf(input.length(), 65_557).toInt())
            val tailStart = input.length() - tail.size
            input.seek(tailStart)
            input.readFully(tail)
            fun u16(bytes: ByteArray, offset: Int) =
                (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            fun u32(bytes: ByteArray, offset: Int) =
                ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            fun u64(bytes: ByteArray, offset: Int) =
                ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
                u32(tail, offset) == 0x06054b50L && offset + 22 + u16(tail, offset + 20) == tail.size
            } ?: error("ZIP directory is missing")
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Multi-disk ZIP is unsupported" }
            var entries = u16(tail, end + 10).toLong()
            var centralBytes = u32(tail, end + 12)
            var centralOffset = u32(tail, end + 16)
            val hasZip64Locator = end >= 20 && u32(tail, end - 20) == 0x07064b50L
            if (hasZip64Locator || entries == 0xffffL || centralBytes == 0xffffffffL || centralOffset == 0xffffffffL) {
                val locatorPosition = tailStart + end - 20
                require(locatorPosition >= 0) { "ZIP64 locator is missing" }
                val locator = ByteArray(20)
                input.seek(locatorPosition); input.readFully(locator)
                require(u32(locator, 0) == 0x07064b50L && u32(locator, 4) == 0L && u32(locator, 16) == 1L) { "Invalid ZIP64 locator" }
                val recordPosition = u64(locator, 8)
                require(recordPosition in 0..locatorPosition - 56) { "Invalid ZIP64 directory offset" }
                val record = ByteArray(56)
                input.seek(recordPosition); input.readFully(record)
                require(u32(record, 0) == 0x06064b50L && u64(record, 4) >= 44 &&
                    u32(record, 16) == 0L && u32(record, 20) == 0L) { "Invalid ZIP64 directory" }
                entries = u64(record, 32)
                centralBytes = u64(record, 40)
                centralOffset = u64(record, 48)
            }
            require(entries in 1..200_000 && centralBytes in 1..64L * 1024 * 1024 &&
                centralOffset >= 0 && centralOffset <= input.length() - centralBytes) { "ZIP directory exceeds archive budget" }
            budget?.recordZipCentralDirectoryBytes(centralBytes)
            val centralEnd = Math.addExact(centralOffset, centralBytes)
            require(centralEnd <= tailStart + end) { "ZIP directory overlaps its trailer" }
            var cursor = centralOffset
            var actualEntries = 0L
            val header = ByteArray(46)
            while (cursor < centralEnd) {
                require(centralEnd - cursor >= header.size) { "Truncated ZIP directory entry" }
                input.seek(cursor); input.readFully(header)
                require(u32(header, 0) == 0x02014b50L && u16(header, 34) == 0) { "Invalid ZIP directory entry" }
                require(u16(header, 8) and 1 == 0) { "Encrypted ZIP entries are unsupported" }
                cursor = Math.addExact(cursor, 46L + u16(header, 28) + u16(header, 30) + u16(header, 32))
                actualEntries++
                require(actualEntries <= 200_000 && cursor <= centralEnd) { "ZIP directory exceeds archive budget" }
            }
            require(actualEntries == entries && cursor == centralEnd) { "ZIP entry count does not match directory" }
        }
    }

    private fun directory(zip: ZipFile, budget: WorkspaceArchiveBudget): Map<String, ZipEntry> {
        val result = linkedMapOf<String, ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            budget.recordZipEntry()
            require(entry.size in 0..32L * 1024 * 1024 * 1024 && entry.compressedSize >= 0) { "ZIP entry exceeds archive budget" }
            require(result.size < 200_000 && !entry.isDirectory) { "Invalid backup ZIP directory" }
            val name = WorkspaceArchivePathSafety.normalize(entry.name)
            require(result.put(name, entry) == null) { "Duplicate ZIP entry" }
        }
        return result
    }

    private fun checkedBytes(zip: ZipFile, entry: ZipEntry, maximum: Long, budget: WorkspaceArchiveBudget): ByteArray {
        require(entry.size in 0..maximum) { "Backup metadata exceeds size limit" }
        val out = ByteArrayOutputStream()
        val crc = CRC32()
        var length = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(ArchiveCompression.BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                length = Math.addExact(length, read.toLong())
                require(length <= maximum) { "Backup metadata exceeds size limit" }
                budget.recordZipDecompressedBytes(read.toLong())
                crc.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        require(length == entry.size && crc.value == entry.crc) { "Backup metadata CRC mismatch" }
        return out.toByteArray()
    }

    private fun digestStream(path: String, input: InputStream, onRead: (Long) -> Unit = {}): IncrementalArchiveEntry {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ArchiveCompression.BUFFER_SIZE)
        var length = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            length = Math.addExact(length, read.toLong())
            require(length <= 32L * 1024 * 1024 * 1024) { "Backup entry is too large" }
            onRead(read.toLong())
            hash.update(buffer, 0, read)
        }
        return IncrementalArchiveEntry(path, length, hash.digest().hex())
    }

    private class OverlayZipInput(
        private val recipe: IncrementalArchiveManifest,
        private val delta: ZipFile,
        private val base: ZipFile,
        private val deltaEntries: Map<String, ZipEntry>,
        private val baseEntries: Map<String, ZipEntry>,
        private val budget: WorkspaceArchiveBudget,
    ) : ZipInputStream(ByteArrayInputStream(byteArrayOf())), BudgetedArchiveInput {
        private var index = 0
        private var current: CheckedInput? = null
        override fun getNextEntry(): ZipEntry? {
            closeEntry()
            val descriptor = recipe.entries.getOrNull(index++) ?: return null
            val zip = if (descriptor.fromBase) base else delta
            val entry = (if (descriptor.fromBase) baseEntries else deltaEntries).getValue(descriptor.path)
            current = CheckedInput(zip.getInputStream(entry), descriptor, entry.crc, budget)
            return ZipEntry(descriptor.path).apply { size = descriptor.length }
        }
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = current?.read(bytes, offset, length) ?: -1
        override fun closeEntry() {
            current?.let { input ->
                val buffer = ByteArray(ArchiveCompression.BUFFER_SIZE)
                while (input.read(buffer) >= 0) { /* Drain to verify the entry digest. */ }
                input.close()
            }
            current = null
        }
        override fun close() {
            try { current?.close() } finally {
                try { delta.close() } finally { try { base.close() } finally { super.close() } }
            }
        }
    }

    private class CheckedInput(
        private val input: InputStream,
        private val descriptor: IncrementalArchiveEntry,
        private val expectedCrc: Long,
        private val budget: WorkspaceArchiveBudget,
    ) : InputStream() {
        private var length = 0L
        private val hash = MessageDigest.getInstance("SHA-256")
        private val crc = CRC32()
        private var verified = false
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            val read = input.read(bytes, offset, count)
            if (read > 0) {
                budget.recordZipDecompressedBytes(read.toLong())
                length = Math.addExact(length, read.toLong())
                require(length <= descriptor.length) { "Incremental payload is larger than declared" }
                hash.update(bytes, offset, read)
                crc.update(bytes, offset, read)
            } else if (read < 0 && !verified) {
                require(length == descriptor.length && hash.digest().hex() == descriptor.sha256 && crc.value == expectedCrc) {
                    "Incremental payload checksum mismatch"
                }
                verified = true
            }
            return read
        }
        override fun close() = input.close()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
