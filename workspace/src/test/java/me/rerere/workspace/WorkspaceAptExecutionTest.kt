package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the dedicated apt channel: the structured argv that reaches PRoot, the environment the
 * guest runs apt in, and the contract the package context has to keep.
 *
 * Nothing here starts a real process; the argv is inspected as data, which is exactly what makes
 * the channel narrow (no shell string can be hidden in it).
 */
class WorkspaceAptExecutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `apt command is a structured argv with the package mask and a fixed guest environment`() {
        val context = aptContext()
        val runner = ProotShellRunner(tempFolder.newFolder("native-apt"))
        val proot = File("/native/proot")
        val args = listOf("/usr/bin/apt-get", "-s", "install", "curl", "htop")

        val command = runner.buildAptCommand(context, proot, args)
        val binds = command.bindValues()
        val envIndex = command.indexOf("/usr/bin/env")

        // Rootfs, workspace and package prefix.
        assertEquals(proot.absolutePath, command.first())
        assertEquals(context.linuxDir.absolutePath, command[command.indexOf("-r") + 1])
        assertTrue(command.contains("--root-id"))
        assertTrue(command.contains("--link2symlink"))
        assertTrue(command.contains("--kill-on-exit"))
        assertEquals("/workspace", command[command.indexOf("-w") + 1])
        assertTrue(binds.contains("${context.filesDir.absolutePath}:/workspace"))

        // Fixed guest environment, then the caller argv verbatim.
        assertTrue(envIndex > 0)
        assertEquals(
            listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "TMPDIR=/tmp",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "DEBIAN_FRONTEND=noninteractive",
            ),
            command.subList(envIndex, envIndex + 6),
        )
        assertEquals(args, command.takeLast(args.size))

        // No shell, no shell script evaluation.
        assertFalse(command.any { it == "/bin/bash" || it == "/bin/sh" })
        assertFalse(command.any { it.contains("bash") })
        assertFalse(command.any { it.contains("eval") })
        assertFalse(command.any { it == "-c" })
        // The context command is a shell-injection-looking string and must be ignored entirely.
        assertFalse(command.any { it.contains("rm -rf") })

        // Package mask replaces external mounts and kernel filesystems.
        assertFalse(binds.any { it.endsWith(":/external") })
        assertFalse(binds.any { it.startsWith("${context.bindMounts.single().source.absolutePath}:") })
        assertFalse(binds.any { it == "/proc" || it == "/sys" || it == "/dev" })
        listOf("/skills", "/upload", "/tool_outputs").forEach { target ->
            assertTrue(binds.contains("/dev/null:$target!"))
        }
        assertFalse(command.any { it.contains("toybox") })

        // Workspace-local HOME and temp areas carry the guest HOME/TMPDIR.
        assertTrue(binds.contains("${context.homeDir!!.absolutePath}:/root"))
        assertTrue(binds.contains("${context.guestTempDir!!.absolutePath}:/tmp"))
        assertTrue(binds.contains("${context.guestVarTempDir!!.absolutePath}:/var/tmp"))
    }

    @Test
    fun `apt command rejects anything but the package environment`() {
        val runner = ProotShellRunner(tempFolder.newFolder("native-apt-mode"))
        val proot = File("/native/proot")
        val args = listOf("/usr/bin/apt-get", "-s", "install", "curl")

        // A full-rootfs terminal context would carry external mounts and a patchable rootfs.
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(fullRootfsContext(), proot, args)
        }
        // Package access mode is required, not just "not full rootfs".
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(
                aptContext().copy(accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY),
                proot,
                args,
            )
        }
        // A rootfs that would be patched is never apt's target.
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(aptContext().copy(patchRootfs = true), proot, args)
        }
        // The same contract is enforced before PRoot is ever started.
        assertThrows(IllegalArgumentException::class.java) {
            runner.executeApt(fullRootfsContext(), args)
        }
    }

    @Test
    fun `apt command rejects an argv that is not exactly apt-get`() {
        val runner = ProotShellRunner(tempFolder.newFolder("native-apt-argv"))
        val proot = File("/native/proot")

        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(aptContext(), proot, emptyList())
        }
        // A bare name or an interpreter must never be accepted as the first element.
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(aptContext(), proot, listOf("apt-get", "-s", "install", "curl"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(aptContext(), proot, listOf("/bin/sh", "-c", "apt-get install curl"))
        }
        // Control characters could split a single argument into a command line.
        assertThrows(IllegalArgumentException::class.java) {
            runner.buildAptCommand(
                aptContext(),
                proot,
                listOf("/usr/bin/apt-get", "-s", "install", "curl\nrm -rf /"),
            )
        }
    }

    @Test
    fun `apt execution reports an incomplete rootfs instead of starting proot`() {
        val context = aptContext()

        val result = ProotShellRunner(tempFolder.newFolder("native-apt-incomplete"))
            .executeApt(context, listOf("/usr/bin/apt-get", "-s", "install", "curl"))

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("FULL_ROOTFS rootfs is incomplete"))
        assertTrue(result.stderr.contains("bin/bash"))
        assertTrue(result.stderr.contains("usr/bin/env"))
    }

    @Test
    fun `a shell runner without apt support rejects apt execution`() {
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        val result = runner.executeApt(
            aptContext(),
            listOf("/usr/bin/apt-get", "-s", "install", "curl"),
        )

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("cannot execute apt commands"))
    }

    @Test
    fun `manager forwards apt execution to the shell runner`() {
        val runner = CapturingAptRunner()
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("apt-workspaces"),
            shellRunner = runner,
        )
        manager.ensureWorkspace(ROOT)
        val context = manager.createShellContext(
            root = ROOT,
            command = "ignored",
            accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
        )
        val args = listOf("/usr/bin/apt-get", "-s", "install", "curl")

        val result = manager.executeApt(context, args)

        assertEquals(0, result.exitCode)
        assertEquals(context, runner.context)
        assertEquals(args, runner.args)
    }

    @Test
    fun `proot environment keeps rootfs pollution disabled for package and shared executions`() {
        val loader = File("/native/libproot_loader.so")
        val packageContext = aptContext()

        val packageEnv = prootEnvironmentOverrides(packageContext, loader)
        assertEquals(loader.absolutePath, packageEnv["PROOT_LOADER"])
        assertEquals("1", packageEnv["PROOT_DONT_POLLUTE_ROOTFS"])
        assertEquals(packageContext.tempDir.absolutePath, packageEnv["PROOT_TMP_DIR"])
        assertEquals(packageContext.tempDir.absolutePath, packageEnv["TMPDIR"])

        val sharedFullRootfs = prootEnvironmentOverrides(
            fullRootfsContext().copy(patchRootfs = false),
            loader,
        )
        assertEquals("1", sharedFullRootfs["PROOT_DONT_POLLUTE_ROOTFS"])

        val sandbox = prootEnvironmentOverrides(
            aptContext().copy(accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY),
            loader,
        )
        assertEquals("1", sandbox["PROOT_DONT_POLLUTE_ROOTFS"])

        // The manual FULL_ROOTFS terminal keeps its legacy, unpolluted-flag behavior.
        val manualTerminal = prootEnvironmentOverrides(fullRootfsContext(), loader)
        assertNull(manualTerminal["PROOT_DONT_POLLUTE_ROOTFS"])
    }

    /** A package context with no external mounts, a shared (never patched) rootfs and temp binds. */
    private fun aptContext(): WorkspaceShellContext {
        val workspace = tempFolder.newFolder()
        val files = File(workspace, "files").apply { mkdirs() }
        val linux = File(workspace, "linux").apply { mkdirs() }
        val temp = File(workspace, "tmp").apply { mkdirs() }
        val external = tempFolder.newFolder()
        return WorkspaceShellContext(
            root = ROOT,
            // The apt channel must ignore this completely, so it is deliberately hostile.
            command = "eval \"rm -rf /workspace\"",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            accessMode = WorkspaceShellAccessMode.WORKSPACE_PACKAGE,
            // Kept to prove the package mask drops it instead of forwarding it to PRoot.
            bindMounts = listOf(WorkspaceBindMount(external, "/external")),
            homeDir = File(workspace, "home").apply { mkdirs() },
            guestTempDir = File(workspace, "guest-tmp").apply { mkdirs() },
            guestVarTempDir = File(workspace, "guest-var-tmp").apply { mkdirs() },
            patchRootfs = false,
        )
    }

    private fun fullRootfsContext(): WorkspaceShellContext {
        val workspace = tempFolder.newFolder()
        val files = File(workspace, "files").apply { mkdirs() }
        val linux = File(workspace, "linux").apply { mkdirs() }
        val temp = File(workspace, "tmp").apply { mkdirs() }
        return WorkspaceShellContext(
            root = ROOT,
            command = "pwd",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            accessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
        )
    }

    private fun List<String>.bindValues(): List<String> =
        indices.filter { this[it] == "-b" && it + 1 < size }.map { this[it + 1] }

    private class CapturingAptRunner : WorkspaceShellRunner {
        lateinit var context: WorkspaceShellContext
        lateinit var args: List<String>

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
            WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")

        override fun executeApt(
            context: WorkspaceShellContext,
            args: List<String>,
        ): WorkspaceCommandResult {
            this.context = context
            this.args = args
            return WorkspaceCommandResult(exitCode = 0, stdout = "ok", stderr = "")
        }
    }

    private companion object {
        const val ROOT = "test-workspace"
    }
}
