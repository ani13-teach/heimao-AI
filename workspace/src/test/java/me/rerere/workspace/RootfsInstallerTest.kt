package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `failed reinstall keeps previous rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "old-rootfs")

        // The candidate is deliberately missing bash and env. It is patched and rejected
        // while still in staging, so the existing linux directory must remain untouched.
        val incompleteArchive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray()),
        )
        serve(incompleteArchive) { url ->
            try {
                RootfsInstaller(manager).install(root, url)
                fail("incomplete rootfs should be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("bin/bash"))
            }
        }

        val linuxDir = manager.linuxDir(root)
        assertEquals("old-rootfs", File(linuxDir, "state.txt").readText())
        assertTrue(linuxDir.hasUsableFullRootfs())
    }

    @Test
    fun `archive digest is streamed and mismatch does not replace previous rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("digest-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "old-rootfs")
        val archive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray()),
            TarEntry("bin/bash", '0', "#!/bin/bash\n".toByteArray()),
            TarEntry("usr/", '5'),
            TarEntry("usr/bin/", '5'),
            TarEntry("usr/bin/env", '0', "env\n".toByteArray()),
            TarEntry("state.txt", '0', "new-rootfs".toByteArray()),
        )
        serve(archive) { url ->
            val result = RootfsInstaller(manager).install(
                root = root,
                url = url,
                expectedSha256 = sha256(archive),
            )
            assertEquals(archive.size.toLong(), result.archiveSize)
            assertEquals(sha256(archive), result.archiveSha256)
        }
        assertEquals("new-rootfs", File(manager.linuxDir(root), "state.txt").readText())

        serve(archive) { url ->
            try {
                RootfsInstaller(manager).install(
                    root = root,
                    url = url,
                    expectedSha256 = "0".repeat(64),
                )
                fail("digest mismatch should fail")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("SHA-256"))
            }
        }
        assertEquals("new-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        assertTrue(manager.linuxDir(root).hasUsableFullRootfs())
    }

    @Test
    fun `pending install can roll back after metadata commit failure`() {
        val manager = WorkspaceManager(tmp.newFolder("rollback-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "old-rootfs")
        val archive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray()),
            TarEntry("bin/bash", '0', "#!/bin/bash\n".toByteArray()),
            TarEntry("usr/", '5'),
            TarEntry("usr/bin/", '5'),
            TarEntry("usr/bin/env", '0', "env\n".toByteArray()),
            TarEntry("state.txt", '0', "new-rootfs".toByteArray()),
        )
        val installer = RootfsInstaller(manager)
        serve(archive) { url -> installer.install(root, url) }

        assertEquals("new-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        installer.rollbackPendingInstall(root)
        assertEquals("old-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        assertTrue(manager.linuxDir(root).hasUsableFullRootfs())
    }

    @Test
    fun `incompatible archive architecture is rejected before rootfs exchange`() {
        val manager = WorkspaceManager(tmp.newFolder("architecture-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "old-rootfs")
        val archive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray()),
            TarEntry("bin/bash", '0', "#!/bin/bash\n".toByteArray()),
            TarEntry("usr/", '5'),
            TarEntry("usr/bin/", '5'),
            TarEntry("usr/bin/env", '0', "env\n".toByteArray()),
        )

        serve(archive, "/ubuntu-base-amd64.tar.gz") { url ->
            try {
                RootfsInstaller(manager).install(
                    root = root,
                    url = url,
                    supportedAbis = listOf("arm64-v8a"),
                )
                fail("incompatible architecture should fail")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("incompatible"))
            }
        }
        assertEquals("old-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        assertTrue(manager.linuxDir(root).hasUsableFullRootfs())
    }

    @Test
    fun `inspection records distro capabilities and package versions without sourcing profiles`() {
        val manager = WorkspaceManager(tmp.newFolder("inspection-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val archive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', fakeElf(183)),
            TarEntry("bin/bash", '0', "#!/bin/bash\n".toByteArray()),
            TarEntry("usr/", '5'),
            TarEntry("usr/bin/", '5'),
            TarEntry("usr/bin/env", '0', "env\n".toByteArray()),
            TarEntry("usr/bin/python3", '0', "python\n".toByteArray()),
            TarEntry("etc/", '5'),
            TarEntry("etc/os-release", '0', "ID=ubuntu\nVERSION_ID=24.04\n".toByteArray()),
            TarEntry("var/", '5'),
            TarEntry("var/lib/", '5'),
            TarEntry("var/lib/dpkg/", '5'),
            TarEntry(
                "var/lib/dpkg/status",
                '0',
                "Package: python3\nVersion: 3.12.3\n\n".toByteArray(),
            ),
            TarEntry("root/.bashrc", '0', "this file must never be sourced\n".toByteArray()),
        )

        serve(archive, "/ubuntu-base-arm64.tar.gz") { url ->
            val result = RootfsInstaller(manager).install(
                root = root,
                url = url,
                supportedAbis = listOf("arm64-v8a"),
            )
            assertEquals("arm64", result.expectedArchitecture)
            assertEquals("arm64", result.detectedArchitecture)
            assertEquals("ubuntu", result.distroId)
            assertEquals("24.04", result.distroVersion)
            assertEquals("3.12.3", result.toolVersions["python3"])
            assertTrue(result.capabilityChecks["shell"] == true)
            assertTrue(result.warnings.isNotEmpty())
        }
    }

    @Test
    fun `pending swap restores previous rootfs when linux directory is missing`() {
        val manager = WorkspaceManager(tmp.newFolder("recovery-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val previous = File(manager.tempDir(root), "rootfs-previous")
        writeUsableRootfs(previous, "recovered-rootfs")
        manager.linuxDir(root).deleteRecursively()

        RootfsInstaller(manager).recoverPendingSwap(root)

        assertEquals("recovered-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        assertTrue(manager.linuxDir(root).hasUsableFullRootfs())
        assertFalse(previous.exists())
    }

    @Test
    fun `pending swap backup is removed when new rootfs is already complete`() {
        val manager = WorkspaceManager(tmp.newFolder("completed-recovery-manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "new-rootfs")
        val previous = File(manager.tempDir(root), "rootfs-previous")
        writeUsableRootfs(previous, "old-rootfs")

        RootfsInstaller(manager).recoverPendingSwap(root)

        assertEquals("new-rootfs", File(manager.linuxDir(root), "state.txt").readText())
        assertFalse(previous.exists())
    }

    @Test
    fun `successful reinstall exchanges a usable rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("manager"))
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        writeUsableRootfs(manager.linuxDir(root), "old-rootfs")

        val completeArchive = tarGz(
            TarEntry("bin/", '5'),
            TarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray()),
            TarEntry("bin/bash", '0', "#!/bin/bash\n".toByteArray()),
            TarEntry("usr/", '5'),
            TarEntry("usr/bin/", '5'),
            TarEntry("usr/bin/env", '0', "env\n".toByteArray()),
            TarEntry("state.txt", '0', "new-rootfs".toByteArray()),
        )
        serve(completeArchive) { url ->
            RootfsInstaller(manager).install(root, url)
        }

        val linuxDir = manager.linuxDir(root)
        assertEquals("new-rootfs", File(linuxDir, "state.txt").readText())
        assertTrue(linuxDir.hasUsableFullRootfs())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun writeUsableRootfs(linuxDir: File, state: String) {
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "usr/bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("#!/bin/sh\n")
        File(linuxDir, "bin/bash").writeText("#!/bin/bash\n")
        File(linuxDir, "usr/bin/env").writeText("env\n")
        File(linuxDir, "state.txt").writeText(state)
    }

    private fun serve(
        archive: ByteArray,
        path: String = "/rootfs.tar.gz",
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}$path")
        } finally {
            server.stop(0)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fakeElf(machine: Int): ByteArray = ByteArray(64).apply {
        this[0] = 0x7f.toByte()
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2.toByte()
        this[5] = 1.toByte()
        this[18] = (machine and 0xff).toByte()
        this[19] = ((machine shr 8) and 0xff).toByte()
    }

    private fun tarGz(vararg entries: TarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { out ->
            entries.forEach { entry ->
                out.writeTarEntry(entry.name, entry.type, entry.data)
            }
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        return output.toByteArray()
    }

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    private data class TarEntry(
        val name: String,
        val type: Char,
        val data: ByteArray = ByteArray(0),
    )

    companion object {
        private const val TAR_BLOCK = 512
    }
}
