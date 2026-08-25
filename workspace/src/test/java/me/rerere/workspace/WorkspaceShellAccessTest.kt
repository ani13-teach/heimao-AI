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
        var patchCalls = 0
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = runner,
            bindMounts = listOf(WorkspaceBindMount(external, "/external")),
            rootfsPatchOptionsProvider = {
                patchCalls++
                RootfsPatchOptions(nameservers = listOf("9.9.9.9"))
            },
        )
        manager.ensureWorkspace(ROOT)

        manager.executeCommand(
            root = ROOT,
            command = "pwd",
            accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
        )

        assertEquals(WorkspaceShellAccessMode.WORKSPACE_ONLY, runner.context.accessMode)
        assertTrue(runner.context.bindMounts.isEmpty())
        assertEquals(RootfsPatchOptions(), runner.context.rootfsPatchOptions)
        assertEquals(0, patchCalls)
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
        val shellIndex = command.indexOf("/bin/sh")
        assertEquals("-c", command[shellIndex + 1])
        assertEquals("cd -- \"\$1\" && eval \"\$2\"", command[shellIndex + 2])
        assertEquals("rikkahub", command[shellIndex + 3])
        assertEquals("/workspace", command[shellIndex + 4])
        assertEquals(context.command, command[shellIndex + 5])
    }

    @Test
    fun `sandbox reports unavailable Python and shell commands clearly`() {
        val runner = ProotShellRunner(tempFolder.newFolder("native-sandbox-command-check"))

        val result = runner.execute(
            shellContext(WorkspaceShellAccessMode.WORKSPACE_ONLY).copy(
                command = "python3 -c 'print(1)'",
            )
        )

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("Command 'python3' is not available in sandbox mode"))
        assertTrue(result.stderr.contains("Switch to package mode"))
    }

    @Test
    fun `sandbox command check finds commands in a shell chain`() {
        val runner = ProotShellRunner(tempFolder.newFolder("native-sandbox-chain-check"))

        val result = runner.execute(
            shellContext(WorkspaceShellAccessMode.WORKSPACE_ONLY).copy(
                command = "printf ready && pip3 install example",
            )
        )

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("Command 'pip3' is not available in sandbox mode"))
    }

    @Test
    fun `package mode does not apply sandbox command check`() {
        val runner = ProotShellRunner(tempFolder.newFolder("native-package-command-check"))

        val result = runner.execute(
            shellContext(WorkspaceShellAccessMode.WORKSPACE_PACKAGE).copy(
                command = "python3 -c 'print(1)'",
            )
        )

        assertTrue(!result.stderr.contains("not available in sandbox mode"))
    }

    @Test
    fun `manager strips external mounts and passes patch options to package commands`() {
        val runner = CapturingShellRunner()
        val external = tempFolder.newFolder("external-package")
        val expected = RootfsPatchOptions(nameservers = listOf("9.9.9.9"))
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("package-workspaces"),
            shellRunner = runner,
            bindMounts = listOf(WorkspaceBindMount(external, "/external")),
            rootfsPatchOptionsProvider = { expected },
        )
        manager.ensureWorkspace(ROOT)

        manager.executeCommand(
            root = ROOT,
            command = "apt-get update",
            accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
        )

        assertEquals(WorkspaceShellAccessMode.WORKSPACE_PACKAGE, runner.context.accessMode)
        assertTrue(runner.context.bindMounts.isEmpty())
        assertEquals(expected, runner.context.rootfsPatchOptions)
    }

    @Test
    fun `proot package command masks shared directories and only uses safe devices`() {
        val context = shellContext(WorkspaceShellAccessMode.WORKSPACE_PACKAGE).copy(
            command = "python3 -c 'print(1)'",
            cwd = "src/project",
        )
        val userFile = File(context.filesDir, "keep-user-file.txt").apply {
            writeText("keep me")
        }
        val runner = ProotShellRunner(tempFolder.newFolder("native-package"))
        val command = runner.buildCommand(context, File("/native/proot"))
        val binds = command.bindValues()
        val rootIndex = command.indexOf("-r")
        val shellIndex = command.indexOf("/bin/bash")
        val maskTargets = listOf(
            "/skills",
            "/upload",
            "/tool_outputs",
        )

        assertEquals(context.linuxDir.absolutePath, command[rootIndex + 1])
        assertTrue(command.contains("--root-id"))
        assertTrue(binds.contains("${context.filesDir.absolutePath}:/workspace"))
        assertTrue(binds.none { it.endsWith(":/external") })
        assertFalse(binds.any { it.startsWith("${context.bindMounts.single().source.absolutePath}:") })
        assertFalse(binds.any { it.startsWith("${context.linuxDir.absolutePath}:") })
        assertFalse(binds.any { it == "/proc" || it == "/sys" || it == "/dev" })
        val maskBinds = binds.filter { bind -> maskTargets.any { target -> bind == "/dev/null:$target!" } }
        assertEquals(maskTargets.map { target -> "/dev/null:$target!" }, maskBinds)
        assertTrue(userFile.isFile)
        assertEquals("keep me", userFile.readText())
        listOf("/dev/null", "/dev/random", "/dev/urandom", "/dev/zero")
            .filter { File(it).exists() }
            .forEach { path -> assertTrue(binds.contains("$path:$path!")) }
        assertFalse(command.any { it.contains("toybox") })
        assertTrue(command.contains("HOME=/root"))
        assertTrue(command.contains("TMPDIR=/tmp"))
        assertTrue(command.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(command.contains("TERM=xterm-256color"))
        assertTrue(command.contains("LANG=C.UTF-8"))
        assertTrue(command.contains("LC_ALL=C.UTF-8"))
        assertTrue(command.contains("USER=root"))
        assertTrue(command.contains("SHELL=/bin/bash"))
        assertTrue(command.contains("DEBIAN_FRONTEND=noninteractive"))
        assertEquals("-l", command[shellIndex + 1])
        assertEquals("-c", command[shellIndex + 2])
        assertEquals("cd -- \"\$1\" && eval \"\$2\"", command[shellIndex + 3])
        assertEquals("rikkahub", command[shellIndex + 4])
        assertEquals("/workspace/src/project", command[shellIndex + 5])
        assertEquals(context.command, command[shellIndex + 6])
    }

    @Test
    fun `package rootfs reports missing full shell entry points`() {
        val result = ProotShellRunner(tempFolder.newFolder("native-package-incomplete"))
            .execute(shellContext(WorkspaceShellAccessMode.WORKSPACE_PACKAGE))

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("FULL_ROOTFS rootfs is incomplete"))
        assertTrue(result.stderr.contains("bin/bash"))
        assertTrue(result.stderr.contains("usr/bin/env"))
    }

    @Test
    fun `proot full command retains configured external mounts and shell contract`() {
        val context = shellContext(WorkspaceShellAccessMode.FULL_ROOTFS).copy(
            command = "printf '%s\\n' \"quoted value\"\nprintf '%s' \"second line\"",
            cwd = "src/project",
        )
        val runner = ProotShellRunner(tempFolder.newFolder("native-full"))
        val command = runner.buildCommand(context, File("/native/proot"))
        val rootIndex = command.indexOf("-r")
        val shellIndex = command.indexOf("/bin/bash")

        assertEquals(context.linuxDir.absolutePath, command[rootIndex + 1])
        assertTrue(command.bindValues().contains("${context.bindMounts.single().source.absolutePath}:/external"))
        assertTrue(command.contains("--root-id"))
        assertTrue(command.contains("HOME=/root"))
        assertTrue(command.contains("TMPDIR=/tmp"))
        assertTrue(command.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(command.contains("USER=root"))
        assertTrue(command.contains("SHELL=/bin/bash"))
        assertEquals("-l", command[shellIndex + 1])
        assertEquals("-c", command[shellIndex + 2])
        assertEquals("cd -- \"\$1\" && eval \"\$2\"", command[shellIndex + 3])
        assertEquals("rikkahub", command[shellIndex + 4])
        assertEquals("/workspace/src/project", command[shellIndex + 5])
        assertEquals(context.command, command[shellIndex + 6])
    }

    @Test
    fun `interactive full command shares rootfs mounts and environment`() {
        val context = shellContext(WorkspaceShellAccessMode.FULL_ROOTFS)
        val runner = ProotShellRunner(tempFolder.newFolder("native-interactive"))
        val command = runner.buildFullRootfsInteractiveCommand(context, File("/native/proot"))

        assertEquals(context.linuxDir.absolutePath, command[command.indexOf("-r") + 1])
        assertTrue(command.bindValues().contains("${context.filesDir.absolutePath}:/workspace"))
        assertTrue(command.bindValues().contains("${context.bindMounts.single().source.absolutePath}:/external"))
        assertTrue(command.contains("--root-id"))
        assertTrue(command.contains("TMPDIR=/tmp"))
        assertTrue(command.contains("USER=root"))
        WorkspaceManager.KERNEL_FS_MOUNTS
            .filter { File(it).exists() }
            .forEach { path -> assertTrue(command.bindValues().contains(path)) }
        assertFalse(command.contains("-c"))
        assertEquals("/bin/bash", command[command.lastIndex - 1])
        assertEquals("-l", command.last())
    }

    @Test
    fun `manager passes full rootfs patch options to shell runner`() {
        val runner = CapturingShellRunner()
        val expected = RootfsPatchOptions(nameservers = listOf("9.9.9.9"))
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("patch-options-workspaces"),
            shellRunner = runner,
            rootfsPatchOptionsProvider = { expected },
        )
        manager.ensureWorkspace(ROOT)

        manager.executeCommand(ROOT, "true")

        assertEquals(WorkspaceShellAccessMode.FULL_ROOTFS, runner.context.accessMode)
        assertEquals(expected, runner.context.rootfsPatchOptions)
    }

    @Test
    fun `rootfs patch always prepares package manager temporary directories`() {
        val linux = tempFolder.newFolder("rootfs-without-etc")

        RootfsPatcher().patch(linux)

        assertTrue(File(linux, "tmp").isDirectory)
        assertTrue(File(linux, "tmp").canWrite())
        assertTrue(File(linux, "var/tmp").isDirectory)
        assertTrue(File(linux, "root").isDirectory)
    }

    @Test
    fun `full rootfs reports missing bash and env instead of starting a broken shell`() {
        val workspace = tempFolder.newFolder("incomplete-rootfs")
        val files = File(workspace, "files").apply { mkdirs() }
        val linux = File(workspace, "linux").apply {
            resolve("bin").mkdirs()
            resolve("bin/sh").writeText("partial rootfs")
        }
        val temp = File(workspace, "tmp").apply { mkdirs() }
        val context = WorkspaceShellContext(
            root = ROOT,
            command = "true",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            accessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
        )

        val result = ProotShellRunner(tempFolder.newFolder("native-incomplete")).execute(context)

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("bin/bash"))
        assertTrue(result.stderr.contains("usr/bin/env"))
        assertTrue(result.stderr.contains("apt/python3"))
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
