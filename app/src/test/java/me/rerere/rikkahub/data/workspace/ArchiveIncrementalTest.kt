package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveIncrementalTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun manifest(label: String): ByteArray = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
        WorkspaceArchiveManifest(createdAt = 1L, appVersion = label),
    ).toByteArray()

    private fun writeZip(entries: Map<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.setLevel(ArchiveCompression.level(name))
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun delta(base: File, current: Map<String, ByteArray>): File {
        val plan = ArchiveIncremental.plan(base, current.map { ArchiveIncremental.describe(it.key, it.value) })
        return writeZip(linkedMapOf(ArchiveIncremental.INDEX_PATH to ArchiveIncremental.encode(plan)).apply {
            plan.entries.filterNot { it.fromBase }.forEach { put(it.path, current.getValue(it.path)) }
        })
    }
    private fun restore(delta: File, base: File): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ArchiveIncremental.open(delta, base).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    @Test fun `delta only writes changed data and streams full current state including deletions`() {
        val picture = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val original = linkedMapOf(
            "manifest.json" to manifest("base-manifest"), "settings.json" to "settings".toByteArray(),
            "upload/photo.png" to picture, "upload/deleted.txt" to "old".toByteArray(),
        )
        val base = writeZip(original)
        val current = linkedMapOf(
            "manifest.json" to manifest("current-manifest"), "settings.json" to "settings".toByteArray(),
            "upload/photo.png" to picture, "upload/new.txt" to "new".toByteArray(),
        )
        val incremental = delta(base, current)
        assertTrue(ArchiveIncremental.isIncremental(incremental))
        assertFalse(ArchiveIncremental.isIncremental(base))
        assertTrue(incremental.length() < base.length() / 10)
        ZipFile(incremental).use { zip ->
            assertNull(zip.getEntry("upload/photo.png"))
            assertNull(zip.getEntry("settings.json"))
            assertNull(zip.getEntry("upload/deleted.txt"))
        }
        val recovered = restore(incremental, base)
        assertEquals(current.keys, recovered.keys)
        current.forEach { (name, bytes) -> assertArrayEquals(bytes, recovered[name]) }
    }

    @Test fun `missing wrong and incremental base are rejected`() {
        val current = linkedMapOf("manifest.json" to manifest("current"), "upload/a.png" to byteArrayOf(1))
        val base = writeZip(current)
        val incremental = delta(base, current)
        assertTrue(runCatching { ArchiveIncremental.open(incremental, null) }.exceptionOrNull() is IncrementalBaseRequiredException)
        val wrong = writeZip(current + ("manifest.json" to manifest("different")))
        assertTrue(runCatching { restore(incremental, wrong) }.isFailure)
        assertTrue(runCatching {
            ArchiveIncremental.plan(incremental, current.map { ArchiveIncremental.describe(it.key, it.value) })
        }.isFailure)
    }

    @Test fun `base and changed payload tampering are detected even with valid ZIP CRC`() {
        val original = linkedMapOf("manifest.json" to manifest("base"), "upload/a.png" to "same".toByteArray())
        val base = writeZip(original)
        val current = original + ("manifest.json" to manifest("current")) + ("upload/new.txt" to "good".toByteArray())
        val incremental = delta(base, current)
        val tamperedBase = writeZip(original + ("upload/a.png" to "evil".toByteArray()))
        assertTrue(runCatching { restore(incremental, tamperedBase) }.isFailure)
        val deltaEntries = ZipFile(incremental).use { zip ->
            zip.entries().asSequence().associateTo(linkedMapOf()) { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
        }
        val tamperedDelta = writeZip(deltaEntries + ("upload/new.txt" to "evil".toByteArray()))
        assertTrue(runCatching { restore(tamperedDelta, base) }.isFailure)
    }

    @Test fun `ZIP64 full backups remain usable as a base`() {
        val base = temporary.newFile()
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(base).use { zip ->
            zip.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.Always)
            val bytes = manifest("base")
            zip.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("manifest.json").apply { size = bytes.size.toLong() })
            zip.write(bytes); zip.closeArchiveEntry(); zip.finish()
        }
        val current = linkedMapOf("manifest.json" to manifest("current"))
        val incremental = delta(base, current)
        assertArrayEquals(current.getValue("manifest.json"), restore(incremental, base).getValue("manifest.json"))
    }

    @Test fun `unexpected entries unsafe paths and missing data are rejected`() {
        val base = writeZip(linkedMapOf("manifest.json" to manifest("base")))
        val current = linkedMapOf("manifest.json" to manifest("current"))
        val incremental = delta(base, current)
        val entries = ZipFile(incremental).use { zip -> zip.entries().asSequence().associateTo(linkedMapOf()) {
            it.name to zip.getInputStream(it).use { input -> input.readBytes() }
        } }
        assertTrue(runCatching { restore(writeZip(entries + ("extra" to byteArrayOf(1))), base) }.isFailure)
        assertTrue(runCatching { restore(writeZip(entries - "manifest.json"), base) }.isFailure)
        val bad = IncrementalArchiveManifest(runtime = "portable-v1", baseManifestSha256 = "0".repeat(64), entries = listOf(
            ArchiveIncremental.describe("manifest.json", byteArrayOf()), ArchiveIncremental.describe("../outside", byteArrayOf()),
        ))
        assertTrue(runCatching { bad.validate() }.isFailure)
    }

    @Test fun `runtime is required and old incremental versions are rejected`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val valid = ArchiveIncremental.plan(writeZip(linkedMapOf("manifest.json" to manifest("base"))), listOf(ArchiveIncremental.describe("manifest.json", manifest("new"))))
        val encoded = ArchiveIncremental.encode(valid).toString(Charsets.UTF_8)
        assertTrue(runCatching { json.decodeFromString<IncrementalArchiveManifest>(encoded.replace("\"runtime\":\"portable-v1\",", "")).validate() }.isFailure)
        assertTrue(runCatching { json.decodeFromString<IncrementalArchiveManifest>(encoded.replace("\"version\":2", "\"version\":1")).validate() }.isFailure)
    }

    @Test fun `base and delta share compressed logical and entry budgets`() {
        val base = writeZip(linkedMapOf("manifest.json" to manifest("base"), "upload/data" to ByteArray(512)))
        val current = linkedMapOf("manifest.json" to manifest("new"), "upload/data" to ByteArray(512))
        val delta = delta(base, current)
        assertTrue(runCatching { ArchiveIncremental.open(delta, base, WorkspaceArchiveBudget(WorkspaceArchiveBudgetLimits(maxZipCompressedBytes = maxOf(base.length(), delta.length())))) }.isFailure)
        assertTrue(runCatching { ArchiveIncremental.open(delta, base, WorkspaceArchiveBudget(WorkspaceArchiveBudgetLimits(maxZipEntries = 3))) }.isFailure)
        assertTrue(runCatching { ArchiveIncremental.open(delta, base, WorkspaceArchiveBudget(WorkspaceArchiveBudgetLimits(maxZipDecompressedBytes = 1))) }.isFailure)
    }

    @Test fun `ZIP64 oversized directory is rejected before opening entries`() {
        val file = temporary.newFile()
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file).use { zip ->
            zip.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.Always)
            val bytes = manifest("base")
            zip.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("manifest.json").apply { size = bytes.size.toLong() })
            zip.write(bytes); zip.closeArchiveEntry()
        }
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val bytes = ByteArray(raf.length().toInt()); raf.readFully(bytes)
            val offset = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 0x06.toByte() && bytes[it + 3] == 0x06.toByte() }
            raf.seek((offset + 40).toLong())
            raf.write(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(64L * 1024 * 1024 + 1).array())
        }
        assertTrue(runCatching { ArchiveIncremental.checkedDirectory(file) }.isFailure)
    }
}
