package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProotWorkspaceOnlyInstrumentedTest {
    @Test
    fun commandCanAccessWorkspaceButNotPrivateOrExternalDirectories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(context.cacheDir, "proot-workspace-only-${UUID.randomUUID()}")
        val filesDir = File(testRoot, "files").apply { mkdirs() }
        val linuxDir = File(testRoot, "linux").apply {
            resolve("bin").mkdirs()
            resolve("bin/sh").writeText("rootfs marker")
            resolve("root").mkdirs()
            resolve("root/secret.txt").writeText("private-rootfs")
        }
        val tempDir = File(testRoot, "temp").apply { mkdirs() }
        val externalDir = File(testRoot, "external").apply {
            mkdirs()
            resolve("secret.txt").writeText("external-bind")
        }
        File(filesDir, "input.txt").writeText("workspace-data")

        try {
            val result = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)).execute(
                WorkspaceShellContext(
                    root = "instrumented-test",
                    command = """
                        cat /workspace/input.txt
                        printf '%s' written > /workspace/output.txt
                        [ ! -e /root/secret.txt ] || exit 91
                        [ ! -e /external/secret.txt ] || exit 92
                        [ ! -e /system/bin/content ] || exit 93
                    """.trimIndent(),
                    cwd = "",
                    filesDir = filesDir,
                    linuxDir = linuxDir,
                    tempDir = tempDir,
                    workingDir = filesDir,
                    timeoutMillis = 30_000,
                    accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
                    bindMounts = listOf(WorkspaceBindMount(externalDir, "/external")),
                )
            )

            assertEquals(result.stderr, 0, result.exitCode)
            assertFalse(result.timedOut)
            assertTrue(result.stdout.contains("workspace-data"))
            assertEquals("written", File(filesDir, "output.txt").readText())
            assertEquals("private-rootfs", File(linuxDir, "root/secret.txt").readText())
            assertEquals("external-bind", File(externalDir, "secret.txt").readText())
        } finally {
            testRoot.deleteRecursively()
        }
    }
}
