package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.Deflater
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveCompressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `pictures and packed files bypass recompression while text stays fast`() {
        listOf("photo.JPG", "a.webp", "a.HEIC", "a.zip", "a.mp4").forEach {
            assertEquals(Deflater.NO_COMPRESSION, ArchiveCompression.level(it))
        }
        listOf("settings.json", "database.db", "linux.tar", "a.txt").forEach {
            assertEquals(Deflater.BEST_SPEED, ArchiveCompression.level(it))
        }
    }

    @Test fun `mixed zip preserves every byte without CRC pre-read`() {
        val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val source = temporary.newFile().apply { writeBytes(data) }
        val zipFile = temporary.newFile()
        var copied = 0L
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            ArchiveCompression.addFile(zip, source, "photo.png") { copied += it }
            ArchiveCompression.addFile(zip, source, "message.txt") { copied += it }
        }
        assertEquals(data.size.toLong() * 2, copied)
        ZipFile(zipFile).use { zip ->
            listOf("photo.png", "message.txt").forEach { name ->
                assertArrayEquals(data, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
            }
            assertTrue(zip.getEntry("photo.png").compressedSize >= data.size)
            assertTrue(zip.getEntry("message.txt").compressedSize < data.size / 4)
        }
    }
}
