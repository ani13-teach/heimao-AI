package me.rerere.rikkahub.ui.pages.backup.tabs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

internal suspend fun copyBackupStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(256 * 1024)
    var length = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) return
        if (read == 0) continue
        length = Math.addExact(length, read.toLong())
        require(length <= 64L * 1024 * 1024 * 1024) { "Selected backup exceeds archive budget" }
        output.write(buffer, 0, read)
    }
}
