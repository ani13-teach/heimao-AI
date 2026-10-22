package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.files.FileFolders
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class WorkspaceArchiveIncrementalInstrumentedTest {
    @Test fun fullAndDeltaRestoreFilesHomeAndAttachmentDeletions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val koin = GlobalContext.get()
        val repository = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
        org.junit.Assume.assumeTrue("Requires an empty workspace catalog", repository.getAll().isEmpty())
        val service = koin.get<WorkspaceArchiveService>()
        val workspace = repository.create("portable-delta-${UUID.randomUUID()}")
        val root = File(context.filesDir, "workspaces/${workspace.root}")
        val files = File(root, "files")
        val home = me.rerere.workspace.WorkspaceManager(File(context.filesDir, "workspaces"), portableRuntime = true).runtimePaths(workspace.root).homeDir
        val prefix = "portable-delta-${UUID.randomUUID()}"
        val upload = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val deletedAttachment = File(upload, "$prefix-old.txt")
        val newAttachment = File(upload, "$prefix-new.png")
        val picture = ByteArray(2048) { (it % 251).toByte() }
        var base: File? = null
        var delta: File? = null
        val invalidFixtures = mutableListOf<File>()
        try {
            File(files, "updated.txt").writeText("old files")
            File(files, "deleted.txt").writeText("delete")
            File(home, "updated.txt").writeText("old home")
            File(home, "deleted.txt").writeText("delete")
            deletedAttachment.writeText("delete")
            val full = service.createBackupFile(includeDatabase = false, includeFiles = true)
            base = full
            File(files, "updated.txt").writeText("new files")
            File(files, "deleted.txt").delete()
            File(home, "updated.txt").writeText("new home")
            File(home, "deleted.txt").delete()
            deletedAttachment.delete()
            newAttachment.writeBytes(picture)
            val difference = service.createBackupFile(includeDatabase = false, includeFiles = true, baseFile = full)
            delta = difference
            val wrongBase = service.createBackupFile(includeDatabase = false, includeFiles = true).also(invalidFixtures::add)
            fun invalidArchive(manifest: String, index: String? = null): File =
                File.createTempFile("portable-invalid-", ".zip", context.cacheDir).also { file ->
                    invalidFixtures.add(file)
                    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                        if (index != null) {
                            zip.putNextEntry(java.util.zip.ZipEntry(ArchiveIncremental.INDEX_PATH))
                            zip.write(index.toByteArray()); zip.closeEntry()
                        }
                        zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                        zip.write(manifest.toByteArray()); zip.closeEntry()
                    }
                }
            val originalManifest = ZipFile(full).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes().toString(Charsets.UTF_8) } }
            val linuxArchive = invalidArchive(originalManifest.replaceFirst("{", "{\"sharedLinuxPayloads\":null,"))
            val oldIncremental = invalidArchive(originalManifest,
                """{"format":"heimao.workspace.incremental","version":1,"runtime":"portable-v1","baseManifestSha256":"unused","entries":[]}""")
            repository.delete(workspace.id)
            service.restoreBackupFile(full, includeDatabase = false, includeFiles = true)
            assertEquals("old files", File(files, "updated.txt").readText())
            assertEquals("old home", File(home, "updated.txt").readText())
            assertTrue(File(home, "deleted.txt").isFile)
            assertTrue(deletedAttachment.isFile)
            assertFalse(newAttachment.exists())
            repository.delete(workspace.id)
            assertTrue(runCatching { service.restoreBackupFile(difference, false, true) }.isFailure)
            assertTrue(repository.getAll().isEmpty())
            listOf(wrongBase, difference).forEach { rejectedBase ->
                assertTrue(runCatching { service.restoreBackupFile(difference, false, true, baseFile = rejectedBase) }.isFailure)
                assertTrue(repository.getAll().isEmpty())
                assertFalse(files.exists())
            }
            listOf(linuxArchive, oldIncremental).forEach { rejectedArchive ->
                assertTrue(runCatching { service.restoreBackupFile(rejectedArchive, false, true, baseFile = full) }.isFailure)
                assertTrue(repository.getAll().isEmpty())
                assertFalse(files.exists())
            }
            service.restoreBackupFile(difference, includeDatabase = false, includeFiles = true, baseFile = full)
            assertEquals("new files", File(files, "updated.txt").readText())
            assertEquals("new home", File(home, "updated.txt").readText())
            assertFalse(File(files, "deleted.txt").exists())
            assertFalse(File(home, "deleted.txt").exists())
            assertFalse(deletedAttachment.exists())
            assertArrayEquals(picture, newAttachment.readBytes())
        } finally {
            repository.delete(workspace.id)
            base?.delete(); delta?.delete()
            invalidFixtures.forEach { it.delete() }
            deletedAttachment.delete(); newAttachment.delete()
        }
    }

    @Test fun fullAndIncrementalKeepOriginalPictureBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val service = GlobalContext.get().get<WorkspaceArchiveService>()
        val prefix = "archive-incremental-${UUID.randomUUID()}"
        val upload = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val picture = File(upload, "$prefix.png")
        val addition = File(upload, "$prefix.txt")
        val original = ByteArray(1024 * 1024) { (it % 251).toByte() }
        var full: File? = null
        var delta: File? = null
        try {
            picture.writeBytes(original)
            val baseline = service.createBackupFile(includeDatabase = true, includeFiles = true)
            full = baseline
            addition.writeText("new attachment")
            val incremental = service.createBackupFile(includeDatabase = true, includeFiles = true, baseFile = baseline)
            delta = incremental
            service.verifyBackupFile(incremental, baseFile = baseline)
            ZipFile(incremental).use { zip ->
                assertNull(zip.getEntry("${FileFolders.UPLOAD}/${picture.name}"))
                assertNotNull(zip.getEntry("${FileFolders.UPLOAD}/${addition.name}"))
            }
            var recovered = false
            ArchiveIncremental.open(incremental, baseline).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "${FileFolders.UPLOAD}/${picture.name}") {
                        assertArrayEquals(original, zip.readBytes())
                        recovered = true
                    }
                    zip.closeEntry()
                }
            }
            assertTrue(recovered)
        } finally {
            full?.delete()
            delta?.delete()
            picture.delete()
            addition.delete()
        }
    }
}
