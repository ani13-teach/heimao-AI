package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedWorkspaceArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `local legacy HOME migrates before export and rootfs is not archived`() {
        val base = temporary.newFolder("workspaces")
        org.junit.Assume.assumeTrue("Real directory fsync is covered by Android instrumentation", runCatching {
            java.nio.channels.FileChannel.open(base.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }.isSuccess)
        val legacy = File(base, "project/linux/root").apply { mkdirs() }
        File(legacy, "配置").writeText("保留")
        File(base, "project/linux/usr/bin").mkdirs()
        File(base, "project/linux/usr/bin/old").writeText("runtime")
        val manager = WorkspaceManager(base, portableRuntime = true)
        val source = manager.runtimePaths("project")
        val tar = File(temporary.root, "home.tar")
        WorkspaceArchiveTar.create(source.homeDir, tar)
        val restoredBase = temporary.newFolder("restored")
        WorkspaceArchiveTar.extract(tar, File(restoredBase, "project/home"))
        val restored = WorkspaceManager(restoredBase, portableRuntime = true).runtimePaths("project")
        assertEquals("保留", File(restored.homeDir, "配置").readText())
        assertEquals(source.homeDir, manager.runtimePaths("project").homeDir)
        assertFalse(File(restoredBase, "project/linux").exists())
        assertFalse(File(restored.homeDir, "usr").exists())
        assertEquals("portable-v1", File(restoredBase, "project/runtime").readText())
    }

    @Test fun `independent HOME exports without any runtime installation`() {
        val source = temporary.newFolder("home")
        File(source, "空目录").mkdirs()
        File(source, "中文.txt").writeText("内容")
        val tar = File(temporary.root, "home.tar")
        WorkspaceArchiveTar.create(source, tar)
        val restored = File(temporary.root, "restored")
        WorkspaceArchiveTar.extract(tar, restored)
        assertEquals("内容", File(restored, "中文.txt").readText())
        assertTrue(File(restored, "空目录").isDirectory)
    }
}
