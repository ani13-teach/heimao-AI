package me.rerere.rikkahub.data.workspace

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceArchiveTarValidatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Triple<String, Byte, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        TarArchiveOutputStream(bytes).use { tar ->
            entries.forEach { (name, type, value) ->
                val entry = TarArchiveEntry(name, type)
                val body = if (type == TarConstants.LF_NORMAL) value.toByteArray() else byteArrayOf()
                entry.size = body.size.toLong()
                if (type == TarConstants.LF_SYMLINK || type == TarConstants.LF_LINK) entry.linkName = value
                tar.putArchiveEntry(entry)
                tar.write(body)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }.toByteArray()

    @Test fun `stream validator covers all bytes and leaves next zip entry readable`() {
        val payload = archive(Triple("nested/file", TarConstants.LF_NORMAL, "body"))
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("files.tar")); zip.write(payload); zip.closeEntry()
            zip.putNextEntry(ZipEntry("next")); zip.write("ok".toByteArray()); zip.closeEntry()
        }
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            zip.nextEntry
            val summary = WorkspaceArchiveTarValidator.validate(zip)
            assertEquals(payload.size.toLong(), summary.length)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(hash, summary.sha256)
            assertEquals("next", zip.nextEntry.name)
            assertEquals("ok", zip.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test fun `valid HOME symlinks and preceding hardlinks need no physical extraction`() {
        val payload = archive(
            Triple("usr/bin/tool", TarConstants.LF_NORMAL, "tool"),
            Triple("bin", TarConstants.LF_SYMLINK, "usr/bin"),
            Triple("etc/alias", TarConstants.LF_SYMLINK, "/bin/tool"),
            Triple("copy", TarConstants.LF_LINK, "usr/bin/tool"),
        )
        assertEquals(4, WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(payload)).entries)
    }

    @Test fun `unsafe paths duplicate writes symlink ancestors and forward hardlinks fail`() {
        val samples = listOf(
            archive(Triple("../escape", TarConstants.LF_NORMAL, "x")),
            archive(Triple("a", TarConstants.LF_NORMAL, "x"), Triple("a", TarConstants.LF_NORMAL, "y")),
            archive(Triple("link", TarConstants.LF_SYMLINK, "../../outside")),
            archive(Triple("link", TarConstants.LF_SYMLINK, "folder"), Triple("link/file", TarConstants.LF_NORMAL, "x")),
            archive(Triple("link", TarConstants.LF_LINK, "later"), Triple("later", TarConstants.LF_NORMAL, "x")),
            archive(Triple("a/file", TarConstants.LF_NORMAL, "x"), Triple("a", TarConstants.LF_NORMAL, "y")),
        )
        samples.forEach { assertTrue(runCatching { WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(it)) }.isFailure) }
    }

    @Test fun `trailing garbage and incomplete padding are rejected by validator and extraction`() {
        val payload = archive(Triple("a", TarConstants.LF_NORMAL, "data"))
        val samples = listOf(
            payload + byteArrayOf(1) + ByteArray(1023),
            payload + ByteArray(1),
        )
        samples.forEachIndexed { index, bytes ->
            assertTrue(runCatching { WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(bytes)) }.isFailure)
            val input = temporary.newFile("trailing-$index.tar").apply { writeBytes(bytes) }
            assertTrue(runCatching { WorkspaceArchiveTar.extract(input, java.io.File(temporary.root, "target-$index")) }.isFailure)
        }
    }

    @Test fun `truncation header tampering and budgets are rejected`() {
        val payload = archive(Triple("a", TarConstants.LF_NORMAL, "data".repeat(1000)))
        assertTrue(runCatching {
            WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(payload.copyOf(payload.size - 1300)))
        }.isFailure)
        val tampered = payload.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(tampered)) }.isFailure)
        assertTrue(runCatching {
            WorkspaceArchiveTarValidator.validate(ByteArrayInputStream(payload), WorkspaceArchiveTar.Limits(maxPayloadBytes = 1))
        }.isFailure)
    }
}
