package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceEnvironmentStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedWorkspaceArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `shared Linux exports each HOME and restores as private without changing public files`() {
        val base = temporary.newFolder("workspaces")
        val store = WorkspaceEnvironmentStore(base)
        val linux = store.sharedLinuxDir()
        File(linux, "usr/lib").mkdirs()
        File(linux, "usr/lib/public-library").writeText("common")
        File(linux, "root").mkdirs()
        File(linux, "root/not-project-data").writeText("public-home")
        for (id in listOf("one", "two")) {
            store.initializeShared(id)
            File(store.homeDir(id), "config").writeText(id)
            val tar = File(temporary.root, "$id.tar")
            WorkspaceArchiveTar.create(linux, tar, directoryOverrides = mapOf("root" to store.homeDir(id)))
            val restoredBase = File(temporary.root, "restored-$id")
            val restoredLinux = File(restoredBase, "$id/linux")
            WorkspaceArchiveTar.extract(tar, restoredLinux)
            assertEquals("common", File(restoredLinux, "usr/lib/public-library").readText())
            assertEquals(id, File(restoredLinux, "root/config").readText())
            assertFalse(File(restoredLinux, "root/not-project-data").exists())
            assertFalse(WorkspaceEnvironmentStore(restoredBase).isShared(id))
        }
        assertEquals("public-home", File(linux, "root/not-project-data").readText())
        assertEquals("common", File(linux, "usr/lib/public-library").readText())
    }

    @Test fun `skipped Linux keeps archived HOME in a private workspace`() {
        val workspaces = temporary.newFolder("restore-workspaces")
        val workspace = File(workspaces, "project").apply { mkdirs() }
        val linux = File(workspace, "linux")
        File(linux, "root").mkdirs()
        File(linux, "root/profile").writeText("keep")
        File(linux, "usr/bin").mkdirs()
        File(linux, "usr/bin/tool").writeText("incompatible")

        val home = preserveWorkspaceHomeFromSkippedRootfs(
            workspaceStagingRoot = workspaces,
            workspaceRoot = workspace,
            root = "project",
            extractedLinux = linux,
        )
        assertTrue(linux.deleteRecursively())

        val store = WorkspaceEnvironmentStore(workspaces)
        assertEquals("keep", File(home, "profile").readText())
        assertFalse(linux.exists())
        assertFalse(store.isShared("project"))
        assertTrue(store.hasExternalHome("project"))
        assertEquals(home, store.homeDir("project"))
    }

    @Test fun `HOME survives export before Linux has been installed`() {
        val home = temporary.newFolder("home")
        File(home, "keep").writeText("configuration")
        val tar = File(temporary.root, "empty-linux.tar")
        WorkspaceArchiveTar.create(File(temporary.root, "missing"), tar, directoryOverrides = mapOf("root" to home))
        val restored = File(temporary.root, "restored")
        WorkspaceArchiveTar.extract(tar, restored)
        assertEquals("configuration", File(restored, "root/keep").readText())
    }
}
