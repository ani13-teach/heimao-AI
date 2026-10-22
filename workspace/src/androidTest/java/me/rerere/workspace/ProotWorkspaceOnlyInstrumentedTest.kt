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
        val sharedMarker = "shared-${UUID.randomUUID()}"
        val sharedDirs = listOf(
            "skills" to File(testRoot, "shared-skills"),
            "upload" to File(testRoot, "shared-upload"),
            "tool_outputs" to File(testRoot, "shared-tool-outputs"),
        ).onEach { (target, source) ->
            source.mkdirs()
            File(source, sharedMarker).writeText("shared-marker")
            File(linuxDir, target).apply {
                mkdirs()
                resolve(sharedMarker).writeText("private-rootfs-marker")
            }
        }
        File(filesDir, "input.txt").writeText("workspace-data")
        val linkerConfigReadable = File("/linkerconfig/ld.config.txt").canRead()

        try {
            val result = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)).execute(
                WorkspaceShellContext(
                    root = "instrumented-test",
                    command = """
                        set -e
                        cat /workspace/input.txt
                        printf '\n--xargs--\n'
                        printf '%s\n' /workspace/input.txt | xargs cat
                        printf '%s' written > /workspace/output.txt
                        [ ! -e /root/secret.txt ] || exit 91
                        [ ! -e /external/secret.txt ] || exit 92
                        [ ! -e /system/bin/content ] || exit 93
                        [ ! -e /proc/self/exe ] || exit 94
                        [ ! -e /bin/toybox ] || exit 95
                        [ ! -e /bin/.rikkahub-applets/ps ] || exit 96
                        [ ! -e /system/bin/toybox ] || exit 97
                        [ ! -e /usr/bin/apt ] || exit 98
                        [ ! -e /usr/bin/apt-get ] || exit 99
                        [ ! -e /usr/bin/python3 ] || exit 100
                        [ ! -e /usr/bin/pip3 ] || exit 101
                        [ ! -e /skills/$sharedMarker ] || exit 102
                        [ ! -e /upload/$sharedMarker ] || exit 103
                        [ ! -e /tool_outputs/$sharedMarker ] || exit 104
                        ${if (linkerConfigReadable) "cat /linkerconfig/ld.config.txt > /dev/null || exit 105" else ":"}
                    """.trimIndent(),
                    cwd = "",
                    filesDir = filesDir,
                    linuxDir = linuxDir,
                    tempDir = tempDir,
                    workingDir = filesDir,
                    timeoutMillis = 30_000,
                    accessMode = WorkspaceShellAccessMode.WORKSPACE_ONLY,
                    bindMounts = sharedDirs.map { (target, source) ->
                        WorkspaceBindMount(source, "/$target")
                    } + WorkspaceBindMount(externalDir, "/external"),
                )
            )

            assertEquals(result.stderr, 0, result.exitCode)
            assertFalse(result.timedOut)
            if (linkerConfigReadable) {
                assertFalse(result.stderr, result.stderr.contains("failed to find generated linker configuration"))
            }
            assertFalse(result.stderr, result.stderr.contains("can\'t sanitize binding \"/linkerconfig\""))
            assertTrue(
                "Expected direct and xargs workspace output; stdout=${result.stdout}; stderr=${result.stderr}",
                result.stdout.contains("workspace-data\n--xargs--\nworkspace-data"),
            )
            assertEquals("written", File(filesDir, "output.txt").readText())
            assertEquals("private-rootfs", File(linuxDir, "root/secret.txt").readText())
            assertEquals("external-bind", File(externalDir, "secret.txt").readText())
        } finally {
            testRoot.deleteRecursively()
        }
    }
}
