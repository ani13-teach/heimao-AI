package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class PortableWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager(executor: WorkspacePythonExecutor = WorkspacePythonExecutor { _, _, _, _ -> WorkspaceCommandResult(0, "python\n", "") }): WorkspaceManager {
        try { forceTestDirectory(temporary.root) } catch (failure: Exception) {
            assumeNoException("Real manager preparation requires directory fsync; covered on Android", failure)
        }
        return WorkspaceManager(File(temporary.root, "workspaces"), shellRunner = KotlinWorkspaceShellRunner(executor), portableRuntime = true)
    }

    // Tests ordering and recovery only; real directory fsync is covered on Android.
    private fun prepareCheckpointModel(
        dir: File,
        externalHome: Boolean,
        checkpoint: (PortableWorkspaceRuntime.Stage, File) -> Unit = { _, _ -> },
        directorySync: (File) -> Unit = { assertTrue(it.isDirectory) },
    ) = PortableWorkspaceRuntime.prepare(dir, externalHome, checkpoint, directorySync)
    private fun shell(command: String): WorkspaceCommandResult = manager().let { it.ensureWorkspace("one"); it.executeCommand("one", command) }

    @Test fun pipelinesRedirectsAndQuotedOperators() {
        val m = manager(); m.ensureWorkspace("one")
        val result = m.executeCommand("one", "printf 'b\\na\\na\\n' | sort | uniq > data; cat < data | grep a; echo 'x | y && z' >> data; tail -n 1 data")
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("a\nx | y && z\n", result.stdout)
    }
    @Test fun shortCircuitResumesAtSemicolonAndUnknownIs127() {
        val m = manager(); m.ensureWorkspace("one")
        assertEquals("yes\n", m.executeCommand("one", "false && touch forbidden && echo bad; echo yes").stdout)
        assertFalse(File(m.filesDir("one"), "forbidden").exists())
        assertEquals(127, m.executeCommand("one", "apt-get update").exitCode)
        assertEquals(127, m.executeCommand("one", "unknown").exitCode)
    }
    @Test fun rejectsSubstitutionAndHostPathsBeforeSideEffects() {
        for (command in listOf("touch x; echo \$(pwd)", "touch x; echo `pwd`", "touch x & echo bad")) {
            assertNotEquals(0, shell(command).exitCode)
            assertFalse(File(manager().filesDir("one"), "x").exists())
        }
        for (command in listOf("cat /etc/passwd", "echo bad > /data/leak", "cat ../../outside")) assertNotEquals(0, shell(command).exitCode)
    }
    @Test fun pythonReceivesArgvStdinCwdAndBudget() {
        var called = false
        val m = manager(WorkspacePythonExecutor { context, args, stdin, timeout ->
            called = true
            assertEquals(listOf("-c", "print('hello')"), args)
            assertEquals("value\n", stdin!!.toString(Charsets.UTF_8))
            assertEquals("sub", context.workingDir.name)
            assertEquals("/workspace/sub", context.cwd)
            assertTrue(timeout in 1..30_000)
            assertFalse(context.patchRootfs)
            WorkspaceCommandResult(0, "done\n", "")
        })
        m.ensureWorkspace("one")
        assertEquals("done\n", m.executeCommand("one", "mkdir sub && cd sub && echo value | python3 -c \"print('hello')\"").stdout)
        assertTrue(called)
    }
    @Test fun portablePathsAndContextDoNotNeedRootfs() {
        val m = manager(); val paths = m.runtimePaths("one")
        assertEquals(File(m.workspaceDir("one"), "home"), paths.homeDir)
        assertTrue(m.hasRootfs("one"))
        assertFalse(m.privateLinuxDir("one").exists())
        assertEquals(WorkspaceShellAccessMode.WORKSPACE_ONLY, m.aiShellAccessMode("one"))
        val context = m.createShellContext("one", "pwd")
        assertEquals(paths.homeDir, context.homeDir)
        assertEquals(paths.tempDir, context.guestTempDir)
        assertFalse(context.patchRootfs)
        assertEquals("/root\n/tmp\n", m.executeCommand("one", "echo \$HOME; echo \$TMPDIR").stdout)
        assertEquals(paths.homeDir, m.resolveRootfsPath("one", "/home").rootDir)
    }
    @Test fun migratesTwoHomesAndIsIdempotent() {
        val m = manager()
        for (root in listOf("one", "two")) {
            val legacy = File(m.privateLinuxDir(root), "root/empty")
            legacy.mkdirs(); File(legacy.parentFile, "profile").writeText(root)
            val paths = m.runtimePaths(root)
            assertEquals(root, File(paths.homeDir, "profile").readText())
            assertTrue(File(paths.homeDir, "empty").isDirectory)
            assertFalse(m.privateLinuxDir(root).exists())
            assertEquals("portable-v1", File(m.workspaceDir(root), "runtime").readText())
            assertEquals(paths, m.runtimePaths(root))
        }
    }
    @Test fun keepsExternalSharedAndPrivateHomes() {
        val m = manager()
        for (root in listOf("shared", "private")) {
            if (root == "shared") m.environments.initializeShared(root) else m.environments.initializePrivateWithExternalHome(root)
            File(m.environments.homeDir(root), "profile").writeText(root)
            val legacy = File(m.privateLinuxDir(root), "root"); legacy.mkdirs()
            File(legacy, "obsolete").writeText("old")
            assertEquals(root, File(m.runtimePaths(root).homeDir, "profile").readText())
            assertFalse(File(m.homeDir(root), "obsolete").exists())
        }
    }
    @Test fun conflictRetainsSourceAndNoMarker() {
        val m = manager()
        val source = File(m.privateLinuxDir("one"), "root"); source.mkdirs(); File(source, "file").writeText("source")
        m.homeDir("one").mkdirs(); File(m.homeDir("one"), "file").writeText("different")
        assertTrue(runCatching { m.runtimePaths("one") }.isFailure)
        assertEquals("source", File(source, "file").readText())
        assertEquals("different", File(m.homeDir("one"), "file").readText())
        assertFalse(File(m.workspaceDir("one"), "runtime").exists())
    }
    @Test fun interruptedMigrationRecoversStagingAndPreservesSource() {
        val m = manager(); val source = File(m.privateLinuxDir("one"), "root"); source.mkdirs(); File(source, "file").writeText("source")
        val staging = File(m.workspaceDir("one"), "portable-home-staging"); staging.mkdirs(); File(staging, "partial").writeText("partial")
        Thread.currentThread().interrupt()
        try { m.runtimePaths("one"); fail("Expected interruption") } catch (_: InterruptedException) {} finally { Thread.interrupted() }
        assertTrue(File(source, "file").exists())
        assertEquals("source", File(m.runtimePaths("one").homeDir, "file").readText())
        assertFalse(File(m.homeDir("one"), "partial").exists())
    }
    private fun migrationFixture(name: String, marked: Boolean = false): File {
        val dir = temporary.newFolder(name)
        val source = File(dir, "linux/root")
        File(source, "nested/empty").mkdirs()
        File(source, "profile").writeText("saved profile")
        File(source, "nested/data").writeBytes(ByteArray(20_000) { (it % 251).toByte() })
        if (marked) {
            source.copyRecursively(File(dir, "home"))
            File(dir, "runtime").writeText("portable-v1")
        }
        return dir
    }

    private fun assertCompleteHome(home: File) {
        assertTrue(File(home, "nested/empty").isDirectory)
        assertEquals("saved profile", File(home, "profile").readText())
        assertArrayEquals(ByteArray(20_000) { (it % 251).toByte() }, File(home, "nested/data").readBytes())
    }

    private fun forceTestDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    @Test fun everyMigrationCheckpointRetainsCompleteSourceAndCanResume() {
        for (stage in PortableWorkspaceRuntime.Stage.values()) {
            for (interrupted in listOf(false, true)) {
                val dir = migrationFixture("checkpoint-${stage.name}-$interrupted")
                var reached = false
                try {
                    prepareCheckpointModel(dir, false, checkpoint = { current, _ ->
                        if (current == stage) {
                            reached = true
                            if (interrupted) throw InterruptedException("Injected interruption")
                            throw IOException("Injected failure")
                        }
                    })
                    fail("Expected failure at $stage")
                } catch (failure: Exception) {
                    assertTrue("Did not reach $stage: $failure", reached)
                    assertTrue(if (interrupted) failure is InterruptedException else failure is IOException)
                }
                assertCompleteHome(File(dir, "linux/root"))
                val paths = prepareCheckpointModel(dir, false)
                assertCompleteHome(paths.homeDir)
                assertFalse(File(dir, "linux").exists())
                assertEquals("portable-v1", File(dir, "runtime").readText())
                assertEquals(paths, prepareCheckpointModel(dir, false))
            }
        }
    }

    @Test fun existingMarkerCheckpointsRetainCompleteHomeAndSource() {
        val stages = listOf(
            PortableWorkspaceRuntime.Stage.FILE_SYNCED,
            PortableWorkspaceRuntime.Stage.DIRECTORY_SYNCED,
            PortableWorkspaceRuntime.Stage.HOME_PARENT_SYNCED,
            PortableWorkspaceRuntime.Stage.MARKER_SYNCED,
            PortableWorkspaceRuntime.Stage.MARKER_PARENT_SYNCED,
            PortableWorkspaceRuntime.Stage.BEFORE_SOURCE_REMOVAL
        )
        for (stage in stages) {
            val dir = migrationFixture("marked-${stage.name}", marked = true)
            var reached = false
            try {
                prepareCheckpointModel(dir, false, checkpoint = { current, _ ->
                    if (current == stage) {
                        reached = true
                        throw IOException("Injected failure")
                    }
                })
                fail("Expected failure at $stage")
            } catch (_: IOException) {
                assertTrue("Did not reach $stage", reached)
            }
            assertCompleteHome(File(dir, "home"))
            assertCompleteHome(File(dir, "linux/root"))
            assertCompleteHome(prepareCheckpointModel(dir, false).homeDir)
            assertFalse(File(dir, "linux").exists())
        }
    }

    @Test fun everyDirectorySyncFailureStopsBeforeSourceDeletion() {
        for (marked in listOf(false, true)) {
            val baseline = migrationFixture("sync-baseline-$marked", marked)
            val synced = mutableListOf<String>()
            prepareCheckpointModel(baseline, false, directorySync = {
                assertTrue(it.isDirectory)
                synced.add(baseline.toPath().relativize(it.toPath()).toString())
            })
            assertCompleteHome(File(baseline, "home"))
            assertFalse(File(baseline, "linux").exists())
            for (failedIndex in synced.indices) {
                val dir = migrationFixture("sync-failure-$marked-$failedIndex", marked)
                var calls = 0
                try {
                    prepareCheckpointModel(dir, false, directorySync = {
                        if (calls++ == failedIndex) throw IOException("Injected directory sync failure")
                        assertTrue(it.isDirectory)
                    })
                    fail("Expected directory sync failure $failedIndex")
                } catch (failure: IOException) {
                    assertEquals("Injected directory sync failure", failure.message)
                }
                assertCompleteHome(File(dir, "linux/root"))
                if (marked) assertCompleteHome(File(dir, "home"))
                assertCompleteHome(prepareCheckpointModel(dir, false).homeDir)
                assertFalse(File(dir, "linux").exists())
            }
        }
    }

    @Test fun durabilityOrderPrecedesMarkerAndSourceDeletion() {
        val dir = migrationFixture("durability-order")
        val events = mutableListOf<Pair<PortableWorkspaceRuntime.Stage, File>>()
        prepareCheckpointModel(dir, false, checkpoint = { stage, file ->
            events.add(stage to file)
            if (stage == PortableWorkspaceRuntime.Stage.BEFORE_SOURCE_REMOVAL) {
                assertCompleteHome(File(dir, "home"))
                assertCompleteHome(File(dir, "linux/root"))
            }
        })
        val stages = events.map { it.first }
        fun index(stage: PortableWorkspaceRuntime.Stage) = stages.indexOf(stage).also { assertTrue("$stage missing", it >= 0) }
        val renamed = index(PortableWorkspaceRuntime.Stage.HOME_RENAMED)
        assertTrue(events.take(renamed).any { it.first == PortableWorkspaceRuntime.Stage.FILE_SYNCED })
        assertEquals(PortableWorkspaceRuntime.Stage.DIRECTORY_SYNCED, stages[renamed - 1])
        assertTrue(renamed < index(PortableWorkspaceRuntime.Stage.HOME_PARENT_SYNCED))
        assertTrue(index(PortableWorkspaceRuntime.Stage.HOME_PARENT_SYNCED) < index(PortableWorkspaceRuntime.Stage.MARKER_SYNCED))
        assertTrue(index(PortableWorkspaceRuntime.Stage.MARKER_SYNCED) < index(PortableWorkspaceRuntime.Stage.MARKER_RENAMED))
        assertTrue(index(PortableWorkspaceRuntime.Stage.MARKER_RENAMED) < index(PortableWorkspaceRuntime.Stage.MARKER_PARENT_SYNCED))
        assertTrue(index(PortableWorkspaceRuntime.Stage.MARKER_PARENT_SYNCED) < index(PortableWorkspaceRuntime.Stage.BEFORE_SOURCE_REMOVAL))
        assertCompleteHome(File(dir, "home"))
        assertFalse(File(dir, "linux").exists())
    }

    @Test fun unavailableDirectorySyncRetainsSourceWithOrWithoutMarker() {
        for (marked in listOf(false, true)) {
            val dir = migrationFixture("unsupported-directory-sync-$marked", marked)
            try {
                PortableWorkspaceRuntime.prepare(dir, false, directorySync = {
                    throw IOException("Directory sync unavailable")
                })
                fail("Expected directory sync failure")
            } catch (failure: IOException) {
                assertEquals("Directory sync unavailable", failure.message)
            }
            assertCompleteHome(File(dir, "linux/root"))
            if (marked) assertCompleteHome(File(dir, "home"))
            else assertFalse(File(dir, "runtime").exists())
        }
    }

    @Test fun unsupportedRealDirectorySyncPropagatesWithoutRetiringSource() {
        if (runCatching { forceTestDirectory(temporary.root) }.isSuccess) return
        val dir = migrationFixture("real-sync-failure")
        assertTrue(runCatching { PortableWorkspaceRuntime.prepare(dir, false) }.exceptionOrNull() is IOException)
        assertCompleteHome(File(dir, "linux/root"))
        assertFalse(File(dir, "runtime").exists())
    }

    @Test fun markerWithoutHomeNeverDeletesLegacySource() {
        val dir = migrationFixture("missing-home")
        File(dir, "runtime").writeText("portable-v1")
        assertTrue(runCatching { PortableWorkspaceRuntime.prepare(dir, false) }.isFailure)
        assertCompleteHome(File(dir, "linux/root"))
        assertFalse(File(dir, "home").exists())
    }

    @Test fun commandInterruptionPropagates() {
        val m = manager(); m.ensureWorkspace("one")
        Thread.currentThread().interrupt()
        try { m.executeCommand("one", "echo no"); fail("Expected interruption") } catch (_: InterruptedException) {} finally { Thread.interrupted() }
    }
    @Test fun boundedOutputAndTimeoutContract() {
        val m = manager(WorkspacePythonExecutor { _, _, _, _ -> WorkspaceCommandResult(-1, "x".repeat(MAX_OUTPUT_CHARS + 100), "", timedOut = true, terminationPending = true) })
        m.ensureWorkspace("one")
        val result = m.executeCommand("one", "python -c test; touch shouldNotExist")
        assertTrue(result.timedOut); assertTrue(result.terminationPending); assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
        assertFalse(File(m.filesDir("one"), "shouldNotExist").exists())
        File(m.filesDir("one"), "big").writeBytes(ByteArray(600 * 1024))
        assertTrue(m.executeCommand("one", "cat big | sort").truncated)
    }
    @Test fun symlinkEscapeDeniedAndMigrationPreservesLink() {
        assumeSymbolicLinksSupported(temporary.root)
        val m = manager(); m.ensureWorkspace("one")
        val outside = temporary.newFolder("outside"); File(outside, "secret").writeText("secret")
        Files.createSymbolicLink(File(m.filesDir("one"), "escape").toPath(), outside.toPath())
        assertNotEquals(0, m.executeCommand("one", "cat escape/secret").exitCode)
        assertTrue(runCatching { m.resolveRootfsPath("one", "/workspace/escape/secret") }.isFailure)
        val source = File(m.privateLinuxDir("two"), "root"); source.mkdirs()
        Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        val home = m.runtimePaths("two").homeDir
        assertTrue(Files.isSymbolicLink(File(home, "link").toPath()))
        assertEquals("secret", File(outside, "secret").readText())
    }
    @Test fun resumesPromotedHomeBeforeMarkerAndPreservesSharedLinux() {
        val m = manager()
        val source = File(m.privateLinuxDir("one"), "root"); source.mkdirs()
        File(source, "profile").writeText("saved")
        m.homeDir("one").mkdirs(); File(m.homeDir("one"), "profile").writeText("saved")
        val shared = m.environments.sharedLinuxDir(); shared.mkdirs(); File(shared, "keep").writeText("shared")
        assertEquals("saved", File(m.runtimePaths("one").homeDir, "profile").readText())
        assertEquals("shared", File(shared, "keep").readText())
        assertFalse(m.privateLinuxDir("one").exists())
    }
    @Test fun deadlineAndExecutorInterruptionStopFollowup() {
        val m = manager(WorkspacePythonExecutor { _, _, _, _ -> Thread.sleep(20); WorkspaceCommandResult(0, "late", "") })
        m.ensureWorkspace("one")
        val result = m.executeCommand("one", "python; touch later", timeoutMillis = 5)
        assertTrue(result.timedOut)
        assertFalse(File(m.filesDir("one"), "later").exists())
        val interrupted = manager(WorkspacePythonExecutor { _, _, _, _ -> Thread.currentThread().interrupt(); WorkspaceCommandResult(0, "", "") })
        try { interrupted.executeCommand("one", "python; touch afterInterrupt"); fail("Expected interruption") }
        catch (_: InterruptedException) {} finally { Thread.interrupted() }
        assertFalse(File(m.filesDir("one"), "afterInterrupt").exists())
    }
    @Test fun quotedEscapedOperatorsAndRedirectionFailure() {
        assertEquals("a && b | c\n&\n", shell("echo \"a && b | c\"; echo \\&").stdout)
        assertEquals("recovered\n", shell("echo no > absent/file && echo bad; echo recovered").stdout)
    }
    @Test fun commonFileCommandsCompose() {
        val r = shell("mkdir -p a; touch a/empty; echo value > a/data; cp a/data copy; mv copy moved; cat moved | tee teeFile | wc -l; find a -type f | sort; cut -d : -f 2 < a/data; rm moved")
        assertEquals(r.stderr, 0, r.exitCode)
        assertTrue(r.stdout.startsWith("1\na/data\na/empty\n"))
    }
}
