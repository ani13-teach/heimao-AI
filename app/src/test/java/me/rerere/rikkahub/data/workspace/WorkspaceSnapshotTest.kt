package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class WorkspaceSnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `single workspace snapshot round trips only metadata files and home payloads`() {
        val source = temporaryFolder.newFolder("source")
        val files = File(source, "files").apply { mkdirs() }
        val home = File(source, "home").apply { mkdirs() }
        File(files, "state.txt").writeText("before")
        File(home, "tool").writeText("tool")
        val filesTar = temporaryFolder.newFile("files.tar")
        val homeTar = temporaryFolder.newFile("home.tar")
        val filesSummary = WorkspaceArchiveTar.create(files, filesTar)
        val homeSummary = WorkspaceArchiveTar.create(home, homeTar)
        val manifest = WorkspaceSnapshotManifest(
            createdAt = 1,
            workspace = WorkspaceArchiveWorkspace(
                id = "workspace-id",
                name = "One workspace",
                root = "workspace-root",
                files = filesPayload(filesSummary),
                home = WorkspaceArchivePayload(
                    path = "workspaces/workspace-root/home.tar",
                    length = homeSummary.length,
                    sha256 = homeSummary.sha256,
                ),
                toolApprovals = "{}",
            ),
        )
        manifest.validate()
        val json = Json { encodeDefaults = true }
        val snapshot = temporaryFolder.newFile("snapshot.zip")
        ZipOutputStream(snapshot.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(WorkspaceSnapshotManifest.MANIFEST_PATH))
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.workspace.files.path))
            zip.write(filesTar.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(requireNotNull(manifest.workspace.home).path))
            zip.write(homeTar.readBytes())
            zip.closeEntry()
        }

        val entries = mutableListOf<String>()
        ZipInputStream(snapshot.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += WorkspaceArchivePathSafety.normalize(entry.name)
            }
        }
        assertEquals(
            setOf(
                WorkspaceSnapshotManifest.MANIFEST_PATH,
                "workspaces/workspace-root/files.tar",
                "workspaces/workspace-root/home.tar",
            ),
            entries.toSet(),
        )
        assertEquals(3, entries.size)

        val restored = temporaryFolder.newFolder("restored")
        WorkspaceArchiveTar.extract(filesTar, File(restored, "files"))
        WorkspaceArchiveTar.extract(homeTar, File(restored, "home"))
        assertEquals("before", File(restored, "files/state.txt").readText())
        assertEquals("tool", File(restored, "home/tool").readText())
    }

    @Test
    fun `tampered payload is rejected by manifest digest`() {
        val source = temporaryFolder.newFolder("source")
        File(source, "state.txt").writeText("original")
        val tar = temporaryFolder.newFile("payload.tar")
        val summary = WorkspaceArchiveTar.create(source, tar)
        val bytes = tar.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val tamperedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertFalse(summary.sha256.equals(tamperedDigest, ignoreCase = true))
        assertTrue(
            runCatching {
                require(tamperedDigest == summary.sha256) { "Snapshot payload hash mismatch" }
            }.isFailure,
        )
    }

    @Test
    fun `snapshot count and total capacity reject only the new snapshot`() {
        val limits = WorkspaceSnapshotLimits(maxPerWorkspace = 2, maxTotalBytes = 100)
        checkWorkspaceSnapshotLimits(1, 50, limits, 50)
        assertTrue(runCatching {
            checkWorkspaceSnapshotLimits(2, 0, limits, 1)
        }.isFailure)
        assertTrue(runCatching {
            checkWorkspaceSnapshotLimits(1, 80, limits, 21)
        }.isFailure)
    }

    @Test
    fun `pre-restore limit rejection does not change workspace contents`() {
        val workspace = temporaryFolder.newFolder("workspace")
        val marker = File(workspace, "files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("before-restore")
        }
        val limits = WorkspaceSnapshotLimits(maxPerWorkspace = 1, maxTotalBytes = 1)

        assertTrue(runCatching {
            checkWorkspaceSnapshotLimits(
                currentWorkspaceCount = 1,
                currentTotalBytes = 1,
                limits = limits,
                estimatedNewBytes = 1,
                reason = WorkspaceSnapshotReason.PRE_RESTORE,
            )
        }.exceptionOrNull() is WorkspaceSnapshotLimitExceeded)

        assertTrue(marker.exists())
        assertEquals("before-restore", marker.readText())
    }

    @Test
    fun `limit exceeded exposes structured current usage and UI state`() {
        val limits = WorkspaceSnapshotLimits(maxPerWorkspace = 2, maxTotalBytes = 100)
        val error = runCatching {
            checkWorkspaceSnapshotLimits(
                currentWorkspaceCount = 2,
                currentTotalBytes = 80,
                limits = limits,
                estimatedNewBytes = 30,
                reason = WorkspaceSnapshotReason.PRE_RESTORE,
            )
        }.exceptionOrNull()

        assertTrue(error is WorkspaceSnapshotLimitExceeded)
        val status = (error as WorkspaceSnapshotLimitExceeded).status
        assertEquals(WorkspaceSnapshotLimitKind.BOTH, status.kind)
        assertEquals(WorkspaceSnapshotReason.PRE_RESTORE, status.reason)
        val ui = status.toUiState()
        assertEquals(2, ui.currentCount)
        assertEquals(80L, ui.currentBytes)
        assertEquals(100L, ui.maxBytes)
        assertEquals(110L, ui.projectedBytes)
    }

    @Test
    fun `restore directory transaction rolls back the whole workspace`() {
        val target = temporaryFolder.newFolder("workspace")
        File(target, "files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        File(target, "home/rootfs.txt").apply {
            parentFile!!.mkdirs()
            writeText("old-rootfs")
        }
        val staging = temporaryFolder.newFolder("staging")
        File(staging, "files/state.txt").apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        File(staging, "home/rootfs.txt").apply {
            parentFile!!.mkdirs()
            writeText("new-rootfs")
        }
        val recovery = File(temporaryFolder.root, "recovery/workspace")
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)

        transaction.activate()
        try {
            check(File(target, "files/state.txt").readText() == "new")
            error("injected database update failure")
        } catch (_: IllegalStateException) {
            transaction.rollback()
        }

        assertEquals("old", File(target, "files/state.txt").readText())
        assertEquals("old-rootfs", File(target, "home/rootfs.txt").readText())
        assertFalse(recovery.exists())
    }

    private fun filesPayload(summary: WorkspaceArchiveTar.PayloadSummary) =
        WorkspaceArchivePayload(
            path = "workspaces/workspace-root/files.tar",
            length = summary.length,
            sha256 = summary.sha256,
        )
}
