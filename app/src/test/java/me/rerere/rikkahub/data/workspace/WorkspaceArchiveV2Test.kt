package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceArchiveV2Test {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    @Test fun `v1 missing manifest and encrypted inputs reject without changing any files`() {
        val live = temporary.newFolder("live")
        File(live, "home").mkdirs()
        File(live, "home/配置").writeText("保留")
        File(live, "settings.json").writeText("settings")
        File(live, "rikka_hub").writeText("database")
        val old = File(temporary.root, "old.zip")
        ZipOutputStream(old.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("""{"format":"heimao.workspace.archive","formatVersion":1,"createdAt":1}""".toByteArray())
            it.closeEntry()
        }
        val missing = File(temporary.root, "missing.zip")
        ZipOutputStream(missing.outputStream()).use {
            it.putNextEntry(ZipEntry("settings.json")); it.write("{}".toByteArray()); it.closeEntry()
        }
        val encrypted = File(temporary.root, "old.zip.enc")
        encrypted.writeBytes(WorkspaceArchiveEncryption.MAGIC)
        val before = tree(temporary.root)
        for (input in listOf(old, missing, encrypted)) {
            assertTrue(runCatching { requireCurrentWorkspaceArchive(input, json, 1024) }.isFailure)
            assertEquals(before, tree(temporary.root))
        }
        assertTrue(runCatching {
            requireWorkspaceManifestVersion("""{"format":"heimao.workspace.archive"}""", json, WorkspaceArchiveManifest.FORMAT)
        }.isFailure)
        assertTrue(runCatching {
            requireWorkspaceManifestVersion("""{"format":"heimao.workspace.snapshot","formatVersion":1}""", json, WorkspaceSnapshotManifest.FORMAT)
        }.isFailure)
    }

    @Test fun `v2 manifest retains both lengths and hashes and requires HOME`() {
        val payload = WorkspaceArchivePayload("workspaces/id/files.tar", 1024, "a".repeat(64))
        val workspace = WorkspaceArchiveWorkspace("id", "中文", "id", files = payload,
            home = payload.copy(path = "workspaces/id/home.tar", length = 2048, sha256 = "b".repeat(64)))
        val manifest = WorkspaceArchiveManifest(createdAt = 1, workspaces = listOf(workspace))
        val text = json.encodeToString(manifest)
        requireWorkspaceManifestVersion(text, json, WorkspaceArchiveManifest.FORMAT)
        val decoded = json.decodeFromString<WorkspaceArchiveManifest>(text)
        decoded.validate()
        assertEquals(1024L, decoded.workspaces.single().files.length)
        assertEquals(2048L, decoded.workspaces.single().home.length)
        assertEquals("b".repeat(64), decoded.workspaces.single().home.sha256)
        assertFalse(text.contains("linux.tar"))
        val snapshot = WorkspaceSnapshotManifest(createdAt = 1, workspace = workspace)
        requireWorkspaceManifestVersion(json.encodeToString(snapshot), json, WorkspaceSnapshotManifest.FORMAT)
        snapshot.validate()
    }

    @Test fun `HOME roundtrip preserves empty directories and Chinese files`() {
        val source = temporary.newFolder("source")
        File(source, "空目录").mkdirs()
        File(source, "配置.txt").writeText("内容")
        val tar = File(temporary.root, "home.tar")
        val summary = WorkspaceArchiveTar.create(source, tar)
        val target = File(temporary.root, "restored")
        WorkspaceArchiveTar.extract(tar, target)
        assertEquals("内容", File(target, "配置.txt").readText())
        assertTrue(File(target, "空目录").isDirectory)
        assertEquals(tar.length(), summary.length)
    }

    @Test fun `HOME roundtrip preserves safe relative symbolic links`() {
        val source = temporary.newFolder("links")
        File(source, "配置.txt").writeText("内容")
        val link = File(source, "link").toPath()
        assumeTrue(runCatching { Files.createSymbolicLink(link, java.nio.file.Path.of("配置.txt")) }.isSuccess)
        val tar = File(temporary.root, "links.tar")
        WorkspaceArchiveTar.create(source, tar)
        val target = File(temporary.root, "restored-links")
        WorkspaceArchiveTar.extract(tar, target)
        assertTrue(Files.isSymbolicLink(File(target, "link").toPath()))
        assertEquals("内容", File(target, "link").readText())
    }

    @Test fun `failure after workspace exchange restores existing HOME and removes incoming data`() {
        val target = temporary.newFolder("workspace")
        File(target, "home").mkdirs()
        File(target, "home/配置").writeText("原数据")
        val staging = temporary.newFolder("incoming")
        File(staging, "home").mkdirs()
        File(staging, "home/new").writeText("incoming")
        val recovery = File(temporary.root, "recovery")
        val before = tree(target)
        val transaction = WorkspaceArchiveDirectoryTransaction(staging, target, recovery)
        transaction.activate()
        transaction.rollback()
        assertEquals(before, tree(target))
        assertFalse(File(target, "home/new").exists())
        assertFalse(recovery.exists())
    }

    private fun tree(root: File): Map<String, String> = root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "D" else it.readBytes().joinToString(",")
    }
}
