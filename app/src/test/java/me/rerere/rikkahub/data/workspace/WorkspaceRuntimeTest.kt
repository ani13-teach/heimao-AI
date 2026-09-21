package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `dns selection is empty without an active network`() {
        assertEquals(
            emptyList<String>(),
            selectWorkspaceDnsServers(
                activeNetworkPresent = false,
                dnsServerAddresses = listOf("1.1.1.1"),
            ),
        )
    }

    @Test
    fun `dns selection falls back when the platform query is forbidden`() {
        assertEquals(
            emptyList<String>(),
            selectWorkspaceDnsServers(true) {
                throw SecurityException("permission denied")
            },
        )
    }

    @Test
    fun `dns selection removes blanks and duplicate addresses`() {
        assertEquals(
            listOf("1.1.1.1", "::1"),
            selectWorkspaceDnsServers(
                activeNetworkPresent = true,
                dnsServerAddresses = listOf(" 1.1.1.1 ", "", "1.1.1.1", "::1"),
            ),
        )
    }

    @Test
    fun `only permissionless rootfs directories are repaired`() {
        val rootfs = rootfs(
            directories = listOf("skills", "upload", "etc"),
            files = listOf("tool.sh"),
        )
        val applied = mutableListOf<String>()

        val repaired = repairProotRootfsPlaceholders(
            linuxDir = rootfs,
            permissionBits = { path ->
                when (path.fileName.toString()) {
                    "skills", "upload", "tool.sh" -> 0
                    else -> 0b111_101_101 // 0755
                }
            },
            applyMode = { file, mode -> applied += "${file.name}:${Integer.toOctalString(mode)}" },
        )

        assertEquals(listOf("skills", "upload"), repaired)
        assertEquals(listOf("skills:700", "upload:700"), applied)
    }

    @Test
    fun `nested placeholder modes are outside the repair scope`() {
        val rootfs = rootfs(directories = listOf("var", "var/tmp"))
        val applied = mutableListOf<String>()

        val repaired = repairProotRootfsPlaceholders(
            linuxDir = rootfs,
            permissionBits = { path ->
                if (path.fileName.toString() == "tmp") 0 else 0b111_101_101 // 0755
            },
            applyMode = { file, _ -> applied += file.name },
        )

        assertEquals(emptyList<String>(), repaired)
        assertEquals(emptyList<String>(), applied)
    }

    @Test
    fun `an unreported permission mode is left alone`() {
        val rootfs = rootfs(directories = listOf("skills"))
        val applied = mutableListOf<String>()

        // Platforms that cannot report Unix permissions must not guess.
        val repaired = repairProotRootfsPlaceholders(
            linuxDir = rootfs,
            permissionBits = { null },
            applyMode = { file, _ -> applied += file.name },
        )

        assertEquals(emptyList<String>(), repaired)
        assertEquals(emptyList<String>(), applied)
    }

    private fun rootfs(directories: List<String>, files: List<String> = emptyList()): File {
        val root = temporaryFolder.newFolder("linux")
        directories.forEach { File(root, it).mkdirs() }
        files.forEach { File(root, it).writeText("placeholder") }
        return root
    }
}
