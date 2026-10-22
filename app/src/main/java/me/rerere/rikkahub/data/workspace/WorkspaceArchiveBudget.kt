package me.rerere.rikkahub.data.workspace

import java.io.File

/**
 * One budget is shared by every ZIP/TAR member in one operation. Per-entry limits alone are not
 * sufficient: a valid-looking archive can still exhaust the process by containing many members.
 */
internal data class WorkspaceArchiveBudgetLimits(
    val maxZipEntries: Int = 200_000,
    val maxZipCompressedBytes: Long = 64L * 1024L * 1024L * 1024L,
    val maxZipDecompressedBytes: Long = 64L * 1024L * 1024L * 1024L,
    val maxTarEntries: Int = 400_000,
    val maxTarBytes: Long = 64L * 1024L * 1024L * 1024L,
    val maxWorkspaceBytes: Long = 64L * 1024L * 1024L * 1024L,
    val maxStagingBytes: Long = 128L * 1024L * 1024L * 1024L,
)

internal class WorkspaceArchiveBudget(
    private val limits: WorkspaceArchiveBudgetLimits = WorkspaceArchiveBudgetLimits(),
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val clock: () -> Long = System::nanoTime,
    private val safetyReserveBytes: Long = 16L * 1024 * 1024,
) {
    private data class SpaceReservation(var remaining: Long, var bytes: Long, val checkedAt: Long)
    private val reservations = mutableMapOf<String, SpaceReservation>()
    private var zipCentralDirectoryBytes = 0L
    private var zipEntries = 0
    private var zipCompressedBytes = 0L
    private var zipDecompressedBytes = 0L
    private var tarEntries = 0
    private var tarBytes = 0L
    private var workspaceBytes = 0L
    private var stagingBytes = 0L

    fun recordZipCentralDirectoryBytes(bytes: Long) {
        zipCentralDirectoryBytes = addWithin(zipCentralDirectoryBytes, bytes, 64L * 1024 * 1024, "ZIP central directory exceeds archive budget")
    }

    fun recordZipEntry() {
        zipEntries++
        require(zipEntries <= limits.maxZipEntries) { "Too many ZIP entries in archive" }
    }

    fun recordZipCompressedBytes(bytes: Long) {
        zipCompressedBytes = addWithin(
            current = zipCompressedBytes,
            increment = bytes,
            maximum = limits.maxZipCompressedBytes,
            message = "Compressed ZIP bytes exceed archive budget",
        )
    }

    fun recordZipDecompressedBytes(bytes: Long) {
        zipDecompressedBytes = addWithin(
            current = zipDecompressedBytes,
            increment = bytes,
            maximum = limits.maxZipDecompressedBytes,
            message = "Decompressed ZIP bytes exceed archive budget",
        )
    }

    fun recordTarEntry() {
        tarEntries++
        require(tarEntries <= limits.maxTarEntries) { "Too many TAR entries in archive" }
    }

    fun recordTarBytes(bytes: Long) {
        tarBytes = addWithin(
            current = tarBytes,
            increment = bytes,
            maximum = limits.maxTarBytes,
            message = "TAR bytes exceed archive budget",
        )
    }

    fun recordWorkspaceBytes(bytes: Long) {
        workspaceBytes = addWithin(
            current = workspaceBytes,
            increment = bytes,
            maximum = limits.maxWorkspaceBytes,
            message = "Workspace data exceeds archive budget",
        )
    }

    fun recordStagingBytes(bytes: Long) {
        stagingBytes = addWithin(
            current = stagingBytes,
            increment = bytes,
            maximum = limits.maxStagingBytes,
            message = "Restore/archive staging exceeds space budget",
        )
    }

    fun checkCompressedFile(file: File, countBytes: Boolean = true) {
        require(java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Archive is not a regular file"
        }
        val length = file.length()
        require(length <= limits.maxZipCompressedBytes) {
            "Compressed ZIP bytes exceed archive budget"
        }
        if (countBytes) recordZipCompressedBytes(length)
    }

    /**
     * Check the next write against the filesystem's currently usable space. A provider may report
     * zero when the value is unavailable; that is not treated as infinite space, but the global
     * staging budget still remains authoritative. Callers invoke this before every streamed
     * chunk, so a large archive cannot pass only because one entry is small.
     */
    fun checkAvailableSpace(root: File, requiredBytes: Long = 1L) {
        require(requiredBytes >= 0L) { "Required available space must not be negative" }
        val usable = usableSpace(root)
        if (usable > 0L) {
            require(usable >= requiredBytes) {
                "Insufficient available space for archive staging: need=$requiredBytes, usable=$usable"
            }
        }
    }

    fun recordStagingBytes(bytes: Long, spaceRoot: File) {
        require(bytes >= 0L) { "Staging byte count must not be negative" }
        recordStagingBytes(bytes)
        val key = spaceRoot.absolutePath
        val now = clock()
        var reservation = reservations[key]
        val required = Math.addExact(bytes, safetyReserveBytes)
        if (reservation == null || now - reservation.checkedAt >= 250_000_000L ||
            reservation.bytes >= 8L * 1024 * 1024 || reservation.remaining < required) {
            val available = usableSpace(spaceRoot)
            if (available > 0L) require(available >= required) {
                "Insufficient available space for archive staging: need=$required, usable=$available"
            }
            reservation = SpaceReservation(if (available > 0L) available else Long.MAX_VALUE, 0, now)
            reservations[key] = reservation
        }
        reservation.remaining -= bytes
        reservation.bytes = Math.addExact(reservation.bytes, bytes)
    }

    fun checkStagingDirectory(root: File) {
        val size = directorySize(root)
        require(size <= limits.maxStagingBytes) {
            "Restore/archive staging exceeds space budget"
        }
        checkAvailableSpace(root)
    }

    private fun addWithin(
        current: Long,
        increment: Long,
        maximum: Long,
        message: String,
    ): Long {
        require(increment >= 0L) { "Archive budget increment must not be negative" }
        val next = Math.addExact(current, increment)
        require(next <= maximum) { message }
        return next
    }

    private fun directorySize(root: File): Long {
        val path = root.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) return 0L
        if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return root.length()
        }
        if (!java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        val children = root.listFiles()
            ?: error("Unable to inspect archive staging directory: ${root.path}")
        children.forEach { child ->
            total = Math.addExact(total, directorySize(child))
        }
        return total
    }
}
