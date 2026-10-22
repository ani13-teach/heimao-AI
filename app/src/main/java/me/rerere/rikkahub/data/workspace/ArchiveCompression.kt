package me.rerere.rikkahub.data.workspace

import java.io.File
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ArchiveCompression {
    const val BUFFER_SIZE = 256 * 1024
    private val packedExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif", "jxl",
        "mp4", "mkv", "webm", "mov", "mp3", "aac", "ogg", "opus", "flac",
        "zip", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "apk",
        "pdf", "docx", "xlsx", "pptx", "epub", "woff", "woff2",
    )

    // Level zero avoids a CRC pre-read required by STORED while retaining streaming ZIP output.
    fun level(name: String): Int = if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in packedExtensions) {
        Deflater.NO_COMPRESSION
    } else {
        Deflater.BEST_SPEED
    }

    fun addFile(zip: ZipOutputStream, source: File, name: String, onBytes: (Long) -> Unit = {}) {
        zip.setLevel(level(name))
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                onBytes(read.toLong())
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
    }
}
