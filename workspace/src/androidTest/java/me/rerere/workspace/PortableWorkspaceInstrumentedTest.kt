package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortableWorkspaceInstrumentedTest {
    private lateinit var root: File
    private lateinit var manager: WorkspaceManager

    @Before fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "portable-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        manager = WorkspaceManager(
            File(root, "workspaces"),
            shellRunner = KotlinWorkspaceShellRunner(WorkspacePythonExecutor { _, _, _, _ ->
                WorkspaceCommandResult(0, "stub-python\n", "")
            }),
            portableRuntime = true,
        )
    }

    @After fun tearDown() {
        // Unlink test symlinks first: cleanup must never traverse their targets.
        if (::root.isInitialized && root.exists()) {
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun realDirectorySyncMigratesIndependentHomesAndPreservesFiles() {
        for (name in listOf("one", "two")) {
            val legacy = File(manager.privateLinuxDir(name), "root")
            assertTrue(File(legacy, "nested/empty").mkdirs())
            File(legacy, "profile").writeText(name)
            File(legacy, "nested/data").writeBytes(ByteArray(20000) { (it % 251).toByte() })
            assertTrue(manager.filesDir(name).mkdirs())
            File(manager.filesDir(name), "keep").writeText("files-$name")
            val paths = manager.runtimePaths(name) // No directorySync injection: real Android fsync.
            assertEquals(name, File(paths.homeDir, "profile").readText())
            assertTrue(File(paths.homeDir, "nested/empty").isDirectory)
            assertArrayEquals(ByteArray(20000) { (it % 251).toByte() }, File(paths.homeDir, "nested/data").readBytes())
            assertEquals("files-$name", File(paths.filesDir, "keep").readText())
            assertFalse(manager.privateLinuxDir(name).exists())
            assertEquals("portable-v1", File(manager.workspaceDir(name), "runtime").readText())
            assertEquals(paths, manager.runtimePaths(name))
            File(paths.homeDir, "after").writeText(name)
            assertEquals(name, File(manager.runtimePaths(name).homeDir, "after").readText())
        }
        File(manager.homeDir("one"), "profile").writeText("changed-one")
        File(manager.homeDir("one"), "one-only").writeText("private")
        assertEquals("two", File(manager.homeDir("two"), "profile").readText())
        assertFalse(File(manager.homeDir("two"), "one-only").exists())
        assertEquals("/root\n/tmp\n", manager.executeCommand("one", "echo \$HOME; echo \$TMPDIR").stdout)
        assertFalse(manager.createShellContext("one", "pwd").patchRootfs)
    }

    @Test fun externalHomesAndSharedLinuxAreNotOverwrittenByLegacyMigration() {
        val shared = manager.environments.sharedLinuxDir().apply { mkdirs() }
        File(shared, "keep").writeText("shared-data")
        for (name in listOf("shared", "private")) {
            if (name == "shared") manager.environments.initializeShared(name)
            else manager.environments.initializePrivateWithExternalHome(name)
            File(manager.environments.homeDir(name), "profile").writeText(name)
            val legacy = File(manager.privateLinuxDir(name), "root").apply { mkdirs() }
            File(legacy, "obsolete").writeText("old")
            val paths = manager.runtimePaths(name)
            assertEquals(name, File(paths.homeDir, "profile").readText())
            assertFalse(File(paths.homeDir, "obsolete").exists())
            assertFalse(manager.privateLinuxDir(name).exists())
            assertEquals("shared-data", File(shared, "keep").readText())
        }
    }

    @Test fun realSyncCheckpointFailureRetainsSourceAndResumes() {
        for (stage in PortableWorkspaceRuntime.Stage.values()) {
            val dir = File(root, "failure-${stage.name}")
            val source = File(dir, "linux/root").apply { mkdirs() }
            File(source, "profile").writeText(stage.name)
            var reached = false
            try {
                PortableWorkspaceRuntime.prepare(dir, false, checkpoint = { current, _ ->
                    if (current == stage) {
                        reached = true
                        throw IOException("checkpoint-$stage")
                    }
                })
                fail("Checkpoint was not raised")
            } catch (failure: IOException) {
                assertTrue("Did not reach $stage: $failure", reached)
                assertEquals("checkpoint-$stage", failure.message)
            }
            assertEquals(stage.name, File(source, "profile").readText())
            val paths = PortableWorkspaceRuntime.prepare(dir, false)
            assertEquals(stage.name, File(paths.homeDir, "profile").readText())
            assertFalse(File(dir, "linux").exists())
        }
    }

    @Test fun symlinksArePreservedWithoutFollowingOrEscaping() {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "secret").writeText("untouched")
        val source = File(manager.privateLinuxDir("one"), "root").apply { mkdirs() }
        Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        val home = manager.runtimePaths("one").homeDir
        assertTrue(Files.isSymbolicLink(File(home, "link").toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(home, "link").toPath()))
        Files.createSymbolicLink(File(manager.filesDir("one"), "escape").toPath(), outside.toPath())
        for (command in listOf("cat escape/secret", "echo bad > escape/secret", "cat /root/link/secret")) {
            assertNotEquals(command, 0, manager.executeCommand("one", command).exitCode)
        }
        assertTrue(runCatching { manager.resolveRootfsPath("one", "/workspace/escape/secret") }.isFailure)
        assertEquals("untouched", File(outside, "secret").readText())
        assertFalse(manager.privateLinuxDir("one").exists())
        val unsafe = manager.workspaceDir("unsafe").apply { mkdirs() }
        Files.createSymbolicLink(File(unsafe, "home").toPath(), outside.toPath())
        assertTrue(runCatching { manager.runtimePaths("unsafe") }.isFailure)
        assertFalse(File(unsafe, "runtime").exists())
        assertEquals("untouched", File(outside, "secret").readText())
    }

    @Test fun commandsComposeWithoutRootfsAndPythonUsesStubOnly() {
        manager.ensureWorkspace("one")
        val result = manager.executeCommand("one",
            "printf 'b\\na\\na\\n' | sort | uniq > data; cat < data | grep a; " +
                "echo 'x | y && z' >> data; tail -n 1 data; false && touch forbidden; echo ok")
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("a\nx | y && z\nok\n", result.stdout)
        assertFalse(File(manager.filesDir("one"), "forbidden").exists())
        assertEquals("stub-python\n", manager.executeCommand("one", "python3 -c test").stdout)
        assertEquals(127, manager.executeCommand("one", "apt-get update").exitCode)
        assertFalse(manager.privateLinuxDir("one").exists())
    }
}
