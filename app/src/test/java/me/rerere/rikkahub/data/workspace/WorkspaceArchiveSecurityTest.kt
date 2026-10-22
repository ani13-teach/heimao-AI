package me.rerere.rikkahub.data.workspace

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class WorkspaceArchiveSecurityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `path safety rejects absolute traversal and duplicate segments`() {
        listOf("/etc/passwd", "C:/windows/system32", "../outside", "a/../../outside", "a//b", "./a").forEach {
            runCatching { WorkspaceArchivePathSafety.normalize(it) }
                .onSuccess { error("unsafe path accepted: $it") }
        }
        assertEquals("workspaces/id/files.tar", WorkspaceArchivePathSafety.normalize("workspaces\\id\\files.tar"))
    }

    @Test
    fun `tar round trip streams executable symlink and hardlink`() {
        val source = temporaryFolder.newFolder("source")
        val payload = temporaryFolder.newFile("files.tar")
        File(source, "nested").mkdirs()
        val executable = File(source, "nested/run.sh")
        executable.writeText("#!/bin/sh\necho ok\n")
        executable.setExecutable(true, false)
        val hardlink = File(source, "nested/run-copy.sh")
        val symlink = File(source, "run-link")
        val linksAvailable = runCatching {
            Files.createLink(hardlink.toPath(), executable.toPath())
            Files.createSymbolicLink(symlink.toPath(), java.nio.file.Paths.get("nested/run.sh"))
        }.isSuccess
        assumeTrue("filesystem does not allow links", linksAvailable)

        WorkspaceArchiveTar.create(source, payload)
        val restored = temporaryFolder.newFolder("restored")
        WorkspaceArchiveTar.extract(payload, restored)

        assertEquals("#!/bin/sh\necho ok\n", File(restored, "nested/run.sh").readText())
        assertTrue(File(restored, "nested/run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(restored, "run-link").toPath()))
        assumeTrue(
            "filesystem does not expose stable hardlink identities",
            Files.readAttributes(executable.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                .fileKey() != null,
        )
        assertTrue(Files.isSameFile(
            File(restored, "nested/run.sh").toPath(),
            File(restored, "nested/run-copy.sh").toPath(),
        ))
    }

    @Test
    fun `tar extraction rejects escaping symbolic links`() {
        val payload = temporaryFolder.newFile("unsafe.tar")
        TarArchiveOutputStream(payload.outputStream()).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("escape", TarConstants.LF_SYMLINK).apply {
                linkName = "../../outside"
            })
            tar.closeArchiveEntry()
            tar.finish()
        }
        val restored = temporaryFolder.newFolder("restored")
        val error = runCatching { WorkspaceArchiveTar.extract(payload, restored) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(File(temporaryFolder.root, "outside").exists())
    }

    @Test
    fun `tar round trip keeps a usrmerge rootfs layout`() {
        // The Ubuntu base that installs a portable workspace links through its own directories:
        // bin -> usr/bin, lib -> usr/lib, and entries that point back through them. Re-creating
        // those links relative to a fresh staging root is what the export path has to accept.
        val source = temporaryFolder.newFolder("rootfs")
        File(source, "usr/bin").mkdirs()
        File(source, "usr/lib/systemd/system").mkdirs()
        File(source, "etc/alternatives").mkdirs()
        File(source, "etc/systemd/system/timers.target.wants").mkdirs()
        File(source, "usr/bin/more").writeText("more\n")
        File(source, "usr/lib/systemd/system/apt-daily.timer").writeText("[Timer]\n")
        val linksAvailable = runCatching {
            Files.createSymbolicLink(File(source, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
            Files.createSymbolicLink(File(source, "lib").toPath(), java.nio.file.Paths.get("usr/lib"))
            Files.createSymbolicLink(
                File(source, "etc/alternatives/pager").toPath(),
                java.nio.file.Paths.get("../../bin/more"),
            )
            Files.createSymbolicLink(
                File(source, "etc/systemd/system/timers.target.wants/apt-daily.timer").toPath(),
                java.nio.file.Paths.get("../../../../lib/systemd/system/apt-daily.timer"),
            )
        }.isSuccess
        assumeTrue("filesystem does not allow links", linksAvailable)

        val payload = temporaryFolder.newFile("rootfs.tar")
        WorkspaceArchiveTar.create(source, payload)
        val restored = temporaryFolder.newFolder("restored")
        WorkspaceArchiveTar.extract(payload, restored)

        assertTrue(Files.isSymbolicLink(File(restored, "bin").toPath()))
        assertTrue(
            File(restored, "etc/alternatives/pager").canonicalFile.endsWith(File("usr/bin/more")),
        )
        assertTrue(
            File(restored, "etc/systemd/system/timers.target.wants/apt-daily.timer").canonicalFile
                .endsWith(File("usr/lib/systemd/system/apt-daily.timer")),
        )
    }

    @Test
    fun `tampering changes the streaming payload digest`() {
        val source = temporaryFolder.newFolder("source")
        File(source, "data.txt").writeText("payload")
        val payload = temporaryFolder.newFile("payload.tar")
        val summary = WorkspaceArchiveTar.create(source, payload)
        val bytes = payload.readBytes()
        bytes[bytes.lastIndex - 1024] = (bytes[bytes.lastIndex - 1024].toInt() xor 1).toByte()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertFalse(summary.sha256 == digest)
    }

    @Test
    fun `truncated tar is rejected`() {
        val source = temporaryFolder.newFolder("source")
        File(source, "data.txt").writeText("payload".repeat(1024))
        val payload = temporaryFolder.newFile("full.tar")
        WorkspaceArchiveTar.create(source, payload)
        val truncated = temporaryFolder.newFile("truncated.tar")
        val bytes = payload.readBytes()
        truncated.outputStream().use { it.write(bytes, 0, (bytes.size - 1200).coerceAtLeast(1)) }
        val restored = temporaryFolder.newFolder("restored")
        assertTrue(runCatching { WorkspaceArchiveTar.extract(truncated, restored) }.isFailure)
    }

    @Test
    fun `tar restore enforces cumulative payload bytes across entries`() {
        val payload = temporaryFolder.newFile("budget.tar")
        TarArchiveOutputStream(payload.outputStream()).use { tar ->
            listOf("one" to "1234", "two" to "5678").forEach { (name, content) ->
                tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_NORMAL).apply {
                    size = content.length.toLong()
                })
                tar.write(content.toByteArray())
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        val restored = temporaryFolder.newFolder("budget-restored")
        assertTrue(
            runCatching {
                WorkspaceArchiveTar.extract(
                    payload,
                    restored,
                    limits = WorkspaceArchiveTar.Limits(maxPayloadBytes = 5),
                )
            }.isFailure
        )
    }

    @Test
    fun `tar restore rejects duplicate entry tampering`() {
        val payload = temporaryFolder.newFile("duplicate.tar")
        TarArchiveOutputStream(payload.outputStream()).use { tar ->
            repeat(2) {
                tar.putArchiveEntry(TarArchiveEntry("same", TarConstants.LF_NORMAL).apply { size = 1 })
                tar.write(byteArrayOf(1))
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        val restored = temporaryFolder.newFolder("duplicate-restored")
        assertTrue(runCatching { WorkspaceArchiveTar.extract(payload, restored) }.isFailure)
    }

    @Test
    fun `abi mismatch is reported without accepting the rootfs`() {
        assertTrue(me.rerere.workspace.isRootfsArchitectureCompatible(
            expectedArchitecture = "arm64",
            detectedArchitecture = "arm64",
            supportedAbis = listOf("arm64-v8a"),
        ))
        assertFalse(me.rerere.workspace.isRootfsArchitectureCompatible(
            expectedArchitecture = "x86_64",
            detectedArchitecture = "x86_64",
            supportedAbis = listOf("arm64-v8a"),
        ))
    }

    @Test
    fun `global archive budgets accumulate across entries and bytes`() {
        val budget = WorkspaceArchiveBudget(
            WorkspaceArchiveBudgetLimits(
                maxZipEntries = 2,
                maxZipDecompressedBytes = 5,
                maxStagingBytes = 5,
            )
        )
        budget.recordZipEntry()
        budget.recordZipEntry()
        assertTrue(runCatching { budget.recordZipEntry() }.isFailure)
        budget.recordZipDecompressedBytes(3)
        assertTrue(runCatching { budget.recordZipDecompressedBytes(3) }.isFailure)
        budget.recordStagingBytes(5)
        assertTrue(runCatching { budget.recordStagingBytes(1) }.isFailure)
    }

    @Test
    fun `manifest strictly validates database version and workspace references`() {
        val payload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val oldVersion = WorkspaceArchiveManifest(
            createdAt = 1,
            databaseVersion = 30,
            workspaces = listOf(WorkspaceArchiveWorkspace("id", "one", "id", files = payload, home = payload.copy(path = "workspaces/id/home.tar"))),
        )
        assertTrue(runCatching { oldVersion.validate() }.isFailure)

        val outsideReference = WorkspaceArchiveManifest(
            createdAt = 1,
            workspaces = listOf(WorkspaceArchiveWorkspace("id", "one", "id", files = payload, home = payload.copy(path = "workspaces/id/home.tar"))),
            references = WorkspaceArchiveReferences(
                assistants = listOf(WorkspaceAssistantReference("assistant", "outside")),
            ),
        )
        assertTrue(runCatching { outsideReference.validate() }.isFailure)
    }

    @Test
    fun `manifest accepts legacy conversation workspace reference for restore`() {
        val payload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val manifest = WorkspaceArchiveManifest(
            createdAt = 1,
            workspaces = listOf(WorkspaceArchiveWorkspace("id", "one", "id", files = payload, home = payload.copy(path = "workspaces/id/home.tar"))),
            references = WorkspaceArchiveReferences(
                assistants = listOf(WorkspaceAssistantReference("assistant", null)),
                conversations = listOf(WorkspaceConversationReference("conversation", "id")),
            ),
        )

        assertTrue(runCatching { manifest.validate() }.isSuccess)
    }

    @Test
    fun `manifest rejects duplicate conversation references`() {
        val payload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val manifest = WorkspaceArchiveManifest(
            createdAt = 1,
            workspaces = listOf(WorkspaceArchiveWorkspace("id", "one", "id", files = payload, home = payload.copy(path = "workspaces/id/home.tar"))),
            references = WorkspaceArchiveReferences(
                conversations = listOf(
                    WorkspaceConversationReference("conversation", "id"),
                    WorkspaceConversationReference("conversation", null),
                ),
            ),
        )
        assertTrue(runCatching { manifest.validate() }.isFailure)
    }

    @Test
    fun `manifest validates payload hashes and rejects duplicate workspace ids`() {
        val payload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val manifest = WorkspaceArchiveManifest(
            createdAt = 1,
            workspaces = listOf(
                WorkspaceArchiveWorkspace("id", "one", "id", files = payload, home = payload.copy(path = "workspaces/id/home.tar")),
                WorkspaceArchiveWorkspace("id", "two", "other", files = payload.copy(path = "workspaces/other/files.tar"), home = payload.copy(path = "workspaces/other/home.tar")),
            ),
        )
        assertTrue(runCatching { manifest.validate() }.isFailure)
    }

    @Test
    fun `database manifest must explicitly exclude non portable snapshots`() {
        val workspacePayload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val base = WorkspaceArchiveManifest(
            createdAt = 1,
            databaseEntries = listOf("database/rikka_hub.db"),
            databasePayloads = listOf(
                WorkspaceArchivePayload("database/rikka_hub.db", 1, "1".repeat(64)),
            ),
            workspaces = listOf(
                WorkspaceArchiveWorkspace("id", "one", "id", files = workspacePayload, home = workspacePayload.copy(path = "workspaces/id/home.tar")),
            ),
        )
        assertTrue(runCatching { base.validate() }.isFailure)
        assertTrue(runCatching { base.copy(snapshotsExcluded = true).validate() }.isSuccess)
    }

    @Test
    fun `manifest requires a length and sha descriptor for the database entry`() {
        val workspacePayload = WorkspaceArchivePayload(
            path = "workspaces/id/files.tar",
            length = 1,
            sha256 = "0".repeat(64),
        )
        val databasePath = "database/rikka_hub.db"
        val withoutDescriptor = WorkspaceArchiveManifest(
            createdAt = 1,
            databaseEntries = listOf(databasePath),
            workspaces = listOf(
                WorkspaceArchiveWorkspace("id", "one", "id", files = workspacePayload, home = workspacePayload.copy(path = "workspaces/id/home.tar")),
            ),
        )
        assertTrue(runCatching { withoutDescriptor.validate() }.isFailure)

        val withDescriptor = withoutDescriptor.copy(
            snapshotsExcluded = true,
            databasePayloads = listOf(
                WorkspaceArchivePayload(databasePath, 1, "1".repeat(64)),
            ),
        )
        assertTrue(runCatching { withDescriptor.validate() }.isSuccess)
    }
}
