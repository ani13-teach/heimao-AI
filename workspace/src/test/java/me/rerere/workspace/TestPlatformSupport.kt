package me.rerere.workspace

import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Probes capabilities needed by filesystem tests without hiding failures in production code.
 * Non-Windows failures are rethrown so Linux/Android test environments still exercise links.
 */
internal fun assumeSymbolicLinksSupported(parent: File) {
    val probeDir = Files.createTempDirectory(parent.toPath(), "symlink-probe-")
    try {
        val target = probeDir.resolve("target")
        Files.createFile(target)
        val link = probeDir.resolve("link")
        try {
            Files.createSymbolicLink(link, target.fileName)
        } catch (error: IOException) {
            skipUnsupportedWindowsSymlink(error)
        } catch (error: UnsupportedOperationException) {
            skipUnsupportedWindowsSymlink(error)
        } catch (error: SecurityException) {
            skipUnsupportedWindowsSymlink(error)
        }
        check(Files.isSymbolicLink(link)) { "JVM did not create a symbolic link" }
    } finally {
        probeDir.toFile().deleteRecursively()
    }
}

private fun skipUnsupportedWindowsSymlink(error: Exception): Nothing {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        assumeTrue("Symbolic links are unavailable in this Windows JVM", false)
    }
    throw error
}
