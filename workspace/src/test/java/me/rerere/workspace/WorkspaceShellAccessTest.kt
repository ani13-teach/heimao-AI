package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceShellAccessTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `manager strips external mounts from workspace-only commands`() {
        val runner = CapturingShellRunner()
        val external = tempFolder.newFolder("external")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = runner,
            bindMounts = listOf(WorkspaceBindMount(external, "/external")),
        )
        manager.ensureWorkspace(ROOT)

        manager.executeCommand(
            root = ROOT,
            command = "pwd",
            accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
        )

        assertEquals(WorkspaceShellAccessMode.WORKSPACE_ONLY, runner.context.accessMode)
        assertTrue(runner.context.bindMounts.isEmpty())
    }

    @Test
    fun `proot workspace-only command excludes private rootfs and external mounts`() {
        val context = shellContext(WorkspaceShellAccessMode.WORKSPACE_ONLY)
        val runner = ProotShellRunner(tempFolder.newFolder("native"))
        val command = runner.buildCommand(context, File("/native/proot"))
        val binds = command.bindValues()
        val rootIndex = command.indexOf("-r")

        val catDispatcher = File(
            context.tempDir,
            "workspace-only-runtime/cat.sh",
        ).absolutePath
        val envDispatcher = File(
            context.tempDir,
            "workspace-only-runtime/env.sh",
        ).absolutePath

        assertEquals(context.filesDir.absolutePath, command[rootIndex + 1])
        assertTrue(binds.contains("${context.filesDir.absolutePath}:/workspace"))
        assertTrue(binds.contains("${File(context.filesDir, ".rikkahub/tmp").absolutePath}:/tmp!"))
        assertTrue(binds.contains("/system/bin/sh:/bin/sh!"))
        assertTrue(binds.contains("/system/bin/toybox:/bin/.rikkahub-applets/cat!"))
        assertTrue(binds.contains("$catDispatcher:/bin/cat!"))
        assertTrue(binds.contains("$envDispatcher:/usr/bin/env!"))
        assertFalse(binds.contains("/system/bin/toybox:/bin/toybox!"))
        assertFalse(binds.contains("/system/bin/toybox:/bin/cat!"))
        assertFalse(binds.contains("/system:/system!"))
        assertFalse(binds.contains("/system/bin:/bin!"))
        assertFalse(binds.any { it.startsWith("${context.linuxDir.absolutePath}:") })
        assertFalse(binds.any { it.endsWith(":/external") })
        assertFalse(binds.any { it == "/proc" || it == "/sys" || it == "/dev" })
        assertFalse(command.contains("--root-id"))
        assertTrue(command.contains("HOME=/workspace"))
        assertTrue(command.contains("TMPDIR=/workspace/.rikkahub/tmp"))
        assertTrue(command.contains("ENV=/dev/null"))
        assertTrue(command.contains("/bin/sh"))
        assertFalse(command.contains("/bin/bash"))
        assertFalse(command.contains("-l"))
    }

    @Test
    fun `proot full command retains configured external mounts`() {
        val context = shellContext(WorkspaceShellAccessMode.FULL_ROOTFS)
        val runner = ProotShellRunner(tempFolder.newFolder("native-full"))
        val command = runner.buildCommand(context, File("/native/proot"))
        val rootIndex = command.indexOf("-r")

        assertEquals(context.linuxDir.absolutePath, command[rootIndex + 1])
        assertTrue(command.bindValues().contains("${context.bindMounts.single().source.absolutePath}:/external"))
        assertTrue(command.contains("--root-id"))
        assertTrue(command.contains("HOME=/root"))
        assertTrue(command.contains("-l"))
    }

    private fun shellContext(accessMode: WorkspaceShellAccessMode): WorkspaceShellContext {
        val workspace = tempFolder.newFolder("workspace-${accessMode.name.lowercase()}")
        val files = File(workspace, "files").apply { mkdirs() }
        val linux = File(workspace, "linux").apply { mkdirs() }
        val temp = File(workspace, "tmp").apply { mkdirs() }
        val external = tempFolder.newFolder("external-${accessMode.name.lowercase()}")
        return WorkspaceShellContext(
            root = ROOT,
            command = "pwd",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            accessMode = accessMode,
            bindMounts = listOf(WorkspaceBindMount(external, "/external")),
        )
    }

    private fun List<String>.bindValues(): List<String> =
        indices.filter { this[it] == "-b" && it + 1 < size }.map { this[it + 1] }

    private class CapturingShellRunner : WorkspaceShellRunner {
        lateinit var context: WorkspaceShellContext

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            this.context = context
            return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
        }
    }

    private companion object {
        const val ROOT = "test-workspace"
    }
}
