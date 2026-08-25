package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootfsPatcherSecurityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `patch rejects symlinked hosts without writing through it`() {
        assumeSymbolicLinksSupported(tempFolder.root)
        val rootfs = tempFolder.newFolder("rootfs")
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val outside = tempFolder.newFile("outside-hosts").apply { writeText("outside\n") }
        val hosts = File(etc, "hosts")
        Files.createSymbolicLink(hosts.toPath(), outside.toPath())

        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RootfsPatcher().patch(rootfs)
        }

        assertTrue(error.message.orEmpty().contains("symbolic link"))
        assertTrue(Files.isSymbolicLink(hosts.toPath()))
        assertEquals("outside\n", outside.readText())
    }

    @Test
    fun `patch replaces resolv symlink itself without touching its target`() {
        assumeSymbolicLinksSupported(tempFolder.root)
        val rootfs = tempFolder.newFolder("rootfs-resolv")
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val outside = tempFolder.newFile("outside-resolv").apply { writeText("nameserver 127.0.0.53\n") }
        val resolv = File(etc, "resolv.conf")
        Files.createSymbolicLink(resolv.toPath(), outside.toPath())

        RootfsPatcher().patch(
            rootfs,
            RootfsPatchOptions(nameservers = listOf("9.9.9.9"), groupIds = emptyList()),
        )

        assertFalse(Files.isSymbolicLink(resolv.toPath()))
        assertTrue(resolv.readText().contains("nameserver 9.9.9.9"))
        assertEquals("nameserver 127.0.0.53\n", outside.readText())
    }

    @Test
    fun `patch rejects symlinked temporary directory before chmod`() {
        assumeSymbolicLinksSupported(tempFolder.root)
        val rootfs = tempFolder.newFolder("rootfs-tmp")
        val outside = tempFolder.newFolder("outside-tmp")
        File(outside, "marker").writeText("outside\n")
        val tmp = File(rootfs, "tmp")
        Files.createSymbolicLink(tmp.toPath(), outside.toPath())

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RootfsPatcher().patch(rootfs)
        }

        assertTrue(Files.isSymbolicLink(tmp.toPath()))
        assertEquals("outside\n", File(outside, "marker").readText())
    }
}
