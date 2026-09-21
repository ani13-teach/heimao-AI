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
import java.nio.file.Files

/**
 * Covers the shared-rootfs environment store, the manager wiring, and the PRoot bind contract.
 * Every test uses a real temporary directory tree; nothing here touches an installed app.
 */
class SharedEnvironmentTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun baseDir(): File = tempFolder.newFolder("workspaces")

    private fun createUsableRootfs(linux: File) {
        File(linux, "bin").mkdirs()
        File(linux, "usr/bin").mkdirs()
        File(linux, "bin/sh").writeText("#!/bin/sh\n")
        File(linux, "bin/bash").writeText("#!/bin/bash\n")
        File(linux, "usr/bin/env").writeText("#!/usr/bin/env\n")
    }

    @Test
    fun `a workspace without a marker stays private`() {
        val store = WorkspaceEnvironmentStore(baseDir())
        val linux = store.privateLinuxDir("legacy").apply { mkdirs() }

        assertFalse(store.isShared("legacy"))
        assertFalse(store.hasExternalHome("legacy"))
        assertEquals(WorkspaceEnvironment.PRIVATE, store.environmentOf("legacy"))
        assertEquals(File(linux, "root"), store.homeDir("legacy"))
    }

    @Test
    fun `twenty shared workspaces use one rootfs with distinct homes`() {
        val baseDir = baseDir()
        val store = WorkspaceEnvironmentStore(baseDir)
        createUsableRootfs(store.sharedLinuxDir())
        val roots = (1..20).map { "ws-%02d".format(it) }

        roots.forEach { store.initializeShared(it) }

        val homes = roots.map { store.homeDir(it).absolutePath }
        assertEquals(roots.size, homes.toSet().size)
        assertEquals(File(baseDir.parentFile, "shared-linux/linux"), store.sharedLinuxDir())
        roots.forEach { root ->
            assertTrue(store.isShared(root))
            assertFalse(store.privateLinuxDir(root).exists())
        }
    }

    @Test
    fun `restored HOME can remain external while private rootfs is unavailable`() {
        val store = WorkspaceEnvironmentStore(baseDir())
        val home = File(store.privateLinuxDir("restored").parentFile, "home").apply { mkdirs() }
        File(home, "profile").writeText("keep")

        store.initializePrivateWithExternalHome("restored")

        assertFalse(store.isShared("restored"))
        assertTrue(store.hasExternalHome("restored"))
        assertEquals(home, store.homeDir("restored"))
        assertEquals("keep", File(store.homeDir("restored"), "profile").readText())
        assertFalse(store.privateLinuxDir("restored").exists())
    }

    @Test
    fun `selecting shared keeps the private rootfs and migrates home`() {
        val store = WorkspaceEnvironmentStore(baseDir())
        val linux = store.privateLinuxDir("project").apply { mkdirs() }
        createUsableRootfs(linux)
        File(linux, "root").mkdirs()
        File(linux, "root/todo.txt").writeText("keep")

        store.selectShared("project")

        assertTrue(store.isShared("project"))
        // The old private rootfs is a fallback, so its bytes must still be there.
        assertEquals("keep", File(linux, "root/todo.txt").readText())
        assertTrue(File(linux, "bin/bash").isFile)
        assertEquals("keep", File(store.homeDir("project"), "todo.txt").readText())
    }

    @Test
    fun `selecting private returns to the kept rootfs and keeps HOME external`() {
        val baseDir = baseDir()
        val store = WorkspaceEnvironmentStore(baseDir)
        val linux = store.privateLinuxDir("project").apply { mkdirs() }
        createUsableRootfs(linux)
        File(linux, "root").mkdirs()
        File(linux, "root/todo.txt").writeText("keep")
        store.selectShared("project")
        val home = store.homeDir("project")

        store.selectPrivate("project")

        assertFalse(store.isShared("project"))
        // An explicit private marker keeps the workspace-local HOME the user configured while
        // shared; only the legacy no-marker layout falls back to rootfs /root.
        assertTrue(store.hasExternalHome("project"))
        assertEquals(home, store.homeDir("project"))
        assertEquals(File(baseDir, "project/home"), home)
        assertEquals(linux, store.privateLinuxDir("project"))
        assertTrue(File(home, "todo.txt").isFile)
    }

    @Test
    fun `after switching back the shell keeps the external HOME but patches the private rootfs`() {
        val baseDir = baseDir()
        val runner = CapturingShellRunner()
        val manager = WorkspaceManager(baseDir = baseDir, shellRunner = runner)
        val linux = manager.privateLinuxDir("pool").apply { mkdirs() }
        createUsableRootfs(linux)
        File(linux, "root").apply {
            mkdirs()
            resolve("keep.txt").writeText("keep")
        }
        manager.ensureWorkspace("pool")
        manager.environments.selectShared("pool")
        manager.environments.selectPrivate("pool")

        manager.executeCommand("pool", "true")

        assertTrue(runner.context.patchRootfs)
        assertEquals(manager.privateLinuxDir("pool"), runner.context.linuxDir)
        assertEquals(manager.environments.homeDir("pool"), runner.context.homeDir)
        assertEquals(File(baseDir, "pool/home"), runner.context.homeDir)
        assertEquals(
            manager.environments.homeDir("pool"),
            manager.resolveRootfsPath("pool", "/root/keep.txt").rootDir,
        )
    }

    @Test
    fun `a failed shared switch keeps the legacy marker and can be retried`() {
        val baseDir = baseDir()
        val store = WorkspaceEnvironmentStore(baseDir)
        val linux = store.privateLinuxDir("project").apply { mkdirs() }
        createUsableRootfs(linux)
        File(linux, "root").apply {
            mkdirs()
            resolve("todo.txt").writeText("keep")
        }
        // An unusable HOME path makes the first switch fail after the marker check.
        File(baseDir, "project/home").writeText("not a directory")

        val error = assertThrows(IllegalStateException::class.java) { store.selectShared("project") }

        assertTrue(error.message!!.contains("home"))
        assertFalse(store.isShared("project"))
        assertFalse(store.hasExternalHome("project"))
        assertFalse(File(baseDir, "project/home-staging").exists())

        File(baseDir, "project/home").delete()
        store.selectShared("project")

        assertTrue(store.isShared("project"))
        assertEquals("keep", File(store.homeDir("project"), "todo.txt").readText())
        assertEquals("keep", File(linux, "root/todo.txt").readText())
    }

    @Test
    fun `a stale staging tree is cleared without following its links`() {
        val baseDir = baseDir()
        assumeSymbolicLinksSupported(baseDir)
        val store = WorkspaceEnvironmentStore(baseDir)
        val linux = store.privateLinuxDir("project").apply { mkdirs() }
        createUsableRootfs(linux)
        File(linux, "root").apply {
            mkdirs()
            resolve("todo.txt").writeText("keep")
        }
        val outside = tempFolder.newFolder("outside").apply { resolve("keep.txt").writeText("safe") }
        val staging = File(baseDir, "project/home-staging").apply { mkdirs() }
        Files.createSymbolicLink(File(staging, "link").toPath(), outside.toPath())

        store.selectShared("project")

        assertTrue(File(outside, "keep.txt").isFile)
        assertFalse(staging.exists())
        assertEquals("keep", File(store.homeDir("project"), "todo.txt").readText())
    }

    @Test
    fun `dot roots are rejected`() {
        val directory = baseDir()
        val store = WorkspaceEnvironmentStore(directory)
        assertThrows(IllegalArgumentException::class.java) { store.privateLinuxDir(".") }
        assertThrows(IllegalArgumentException::class.java) { store.isShared("..") }

        val manager = WorkspaceManager(baseDir = directory)
        assertThrows(IllegalArgumentException::class.java) { manager.workspaceDir(".") }
        assertThrows(IllegalArgumentException::class.java) { manager.linuxDir("..") }
    }

    @Test
    fun `selecting private without a usable private rootfs is rejected`() {
        val store = WorkspaceEnvironmentStore(baseDir())
        store.initializeShared("fresh")

        val error = assertThrows(IllegalStateException::class.java) {
            store.selectPrivate("fresh")
        }

        assertTrue(error.message!!.contains("private rootfs"))
        // The rejected switch must not leave a private marker behind.
        assertTrue(store.isShared("fresh"))
    }

    @Test
    fun `an unrecognized marker is rejected`() {
        val baseDir = baseDir()
        val store = WorkspaceEnvironmentStore(baseDir)
        File(baseDir, "broken").apply {
            mkdirs()
            resolve("environment").writeText("banana")
        }

        val error = assertThrows(IllegalStateException::class.java) { store.isShared("broken") }

        assertTrue(error.message!!.contains("Unrecognized"))
    }

    @Test
    fun `the environment marker is never a symlink`() {
        val baseDir = baseDir()
        assumeSymbolicLinksSupported(baseDir)
        val store = WorkspaceEnvironmentStore(baseDir)
        val dir = File(baseDir, "linked").apply { mkdirs() }
        val target = File(dir, "target").apply { writeText("shared") }
        Files.createSymbolicLink(File(dir, "environment").toPath(), target.toPath())

        val error = assertThrows(IllegalArgumentException::class.java) { store.isShared("linked") }

        assertTrue(error.message!!.contains("symbolic link"))
    }

    @Test
    fun `manager uses the shared rootfs and builds an external-home context`() {
        val runner = CapturingShellRunner()
        val manager = WorkspaceManager(baseDir = baseDir(), shellRunner = runner)
        manager.environments.initializeShared("pool")
        manager.ensureWorkspace("pool")

        assertEquals(manager.environments.sharedLinuxDir(), manager.linuxDir("pool"))
        assertFalse(manager.privateLinuxDir("pool").exists())
        assertTrue(manager.homeDir("pool").isDirectory)

        val expected = manager.createShellContext("pool", "true")
        manager.executeCommand("pool", "true")

        assertEquals(expected, runner.context)
        assertEquals(manager.environments.sharedLinuxDir(), runner.context.linuxDir)
        assertEquals(manager.homeDir("pool"), runner.context.homeDir)
        assertEquals(manager.environments.guestTempDir("pool"), runner.context.guestTempDir)
        assertEquals(manager.environments.guestVarTempDir("pool"), runner.context.guestVarTempDir)
        assertFalse(runner.context.patchRootfs)
    }

    @Test
    fun `a private workspace keeps the legacy context`() {
        val runner = CapturingShellRunner()
        val manager = WorkspaceManager(baseDir = baseDir(), shellRunner = runner)
        manager.ensureWorkspace("solo")

        manager.executeCommand("solo", "true")

        assertEquals(manager.privateLinuxDir("solo"), runner.context.linuxDir)
        assertNull(runner.context.homeDir)
        assertNull(runner.context.guestTempDir)
        assertNull(runner.context.guestVarTempDir)
        assertTrue(runner.context.patchRootfs)
    }

    @Test
    fun `shared rootfs resolves root and temp paths to the workspace-local copies`() {
        val manager = WorkspaceManager(baseDir = baseDir())
        manager.environments.initializeShared("pool")
        manager.ensureWorkspace("pool")

        assertEquals(
            manager.homeDir("pool"),
            manager.resolveRootfsPath("pool", "/root/note.txt").rootDir,
        )
        assertEquals(
            "note.txt",
            manager.resolveRootfsPath("pool", "/root/note.txt").relativePath,
        )
        assertEquals(
            manager.environments.guestTempDir("pool"),
            manager.resolveRootfsPath("pool", "/tmp/scratch").rootDir,
        )
        assertEquals(
            manager.environments.guestVarTempDir("pool"),
            manager.resolveRootfsPath("pool", "/var/tmp/cache").rootDir,
        )
        assertEquals(
            "cache",
            manager.resolveRootfsPath("pool", "/var/tmp/cache").relativePath,
        )
    }

    @Test
    fun `private rootfs keeps root and temp inside the workspace rootfs`() {
        val manager = WorkspaceManager(baseDir = baseDir())
        manager.ensureWorkspace("solo")

        val location = manager.resolveRootfsPath("solo", "/root/note.txt")

        assertEquals(manager.privateLinuxDir("solo"), location.rootDir)
        assertEquals("root/note.txt", location.relativePath)
    }

    @Test
    fun `cleanup never deletes the shared rootfs temp areas`() {
        val manager = WorkspaceManager(baseDir = baseDir())
        manager.environments.initializeShared("pool")
        manager.ensureWorkspace("pool")
        val sharedTmp = File(manager.environments.sharedLinuxDir(), "tmp").apply {
            mkdirs()
            resolve("keep").writeText("x")
        }
        val sharedVarTmp = File(manager.environments.sharedLinuxDir(), "var/tmp").apply {
            mkdirs()
            resolve("keep").writeText("x")
        }
        manager.ensureWorkspace("solo")
        val privateTmp = File(manager.privateLinuxDir("solo"), "tmp").apply {
            mkdirs()
            resolve("drop").writeText("x")
        }

        manager.cleanupAllTempDirs()

        assertTrue(File(sharedTmp, "keep").isFile)
        assertTrue(File(sharedVarTmp, "keep").isFile)
        assertFalse(File(privateTmp, "drop").exists())
    }

    @Test
    fun `proot binds the workspace-local home and temp for a shared full rootfs`() {
        val context = sharedContext(WorkspaceShellAccessMode.FULL_ROOTFS)

        val command = ProotShellRunner(tempFolder.newFolder("native-shared-full"))
            .buildCommand(context, File("/native/proot"))
        val binds = command.bindValues()

        assertTrue(binds.contains("${context.homeDir!!.absolutePath}:/root"))
        assertTrue(binds.contains("${context.guestTempDir!!.absolutePath}:/tmp"))
        assertTrue(binds.contains("${context.guestVarTempDir!!.absolutePath}:/var/tmp"))
        // The guest environment still names the paths the binds replace.
        assertTrue(command.contains("HOME=/root"))
        assertTrue(command.contains("TMPDIR=/tmp"))
    }

    @Test
    fun `proot binds the workspace-local home and temp for a shared package rootfs`() {
        val context = sharedContext(WorkspaceShellAccessMode.WORKSPACE_PACKAGE)

        val command = ProotShellRunner(tempFolder.newFolder("native-shared-package"))
            .buildCommand(context, File("/native/proot"))
        val binds = command.bindValues()

        assertTrue(binds.contains("${context.homeDir!!.absolutePath}:/root"))
        assertTrue(binds.contains("${context.guestTempDir!!.absolutePath}:/tmp"))
        assertTrue(binds.contains("${context.guestVarTempDir!!.absolutePath}:/var/tmp"))
    }

    @Test
    fun `readResult waits for a timed out process to exit`() {
        val process = longRunningProcess()

        val result = process.readResult(200)

        assertTrue(result.timedOut)
        assertFalse(result.terminationPending)
        assertFalse("process must be gone when readResult returns", process.isAlive)
    }

    private fun sharedContext(accessMode: WorkspaceShellAccessMode): WorkspaceShellContext {
        val workspace = tempFolder.newFolder("shared-ws-${accessMode.name.lowercase()}")
        val files = File(workspace, "files").apply { mkdirs() }
        val linux = File(workspace, "linux").apply { mkdirs() }
        val temp = File(workspace, "tmp").apply { mkdirs() }
        return WorkspaceShellContext(
            root = "pool",
            command = "pwd",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            accessMode = accessMode,
            homeDir = File(workspace, "home").apply { mkdirs() },
            guestTempDir = File(workspace, "guest-tmp").apply { mkdirs() },
            guestVarTempDir = File(workspace, "guest-var-tmp").apply { mkdirs() },
            patchRootfs = false,
        )
    }

    private fun longRunningProcess(): Process =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            ProcessBuilder("cmd", "/c", "ping -n 6 127.0.0.1").start()
        } else {
            ProcessBuilder("sleep", "5").start()
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
}
