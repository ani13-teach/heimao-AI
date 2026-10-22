package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Run prepare, reinstall the same Debug package, run restore, then verify in a fresh process. */
@RunWith(AndroidJUnit4::class)
class PortableWorkspaceUpgradeInstrumentedTest {
    @Test fun crossProcessUpgradeAndRestore() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("portable_stage")
        assumeTrue("Requires an explicit cross-process validation stage", stage != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val stateFile = File(context.cacheDir, "portable-upgrade-validation.properties")
        val koin = GlobalContext.get()
        val manager = koin.get<WorkspaceManager>()
        val repository = koin.get<WorkspaceRepository>()
        val database = koin.get<AppDatabase>()
        val settings = koin.get<SettingsStore>()
        val archive = koin.get<WorkspaceArchiveService>()
        fun save(state: Properties) = stateFile.outputStream().use { state.store(it, null) }
        suspend fun settingsHash() = sha256(JsonInstant.encodeToString(
            settings.readPersistedSettingsFailFast().copy(launchCount = 0)
        ).toByteArray())
        fun conversationsHash(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            database.openHelper.readableDatabase.query("SELECT * FROM conversationentity ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        val value = if (cursor.isNull(column)) byteArrayOf() else cursor.getString(column).toByteArray()
                        digest.update(value.size.toString().toByteArray())
                        digest.update(0.toByte())
                        digest.update(value)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        when (stage) {
            "prepare" -> {
                check(!stateFile.exists()) { "A previous cross-process validation is pending" }
                val workspace = repository.create("portable-upgrade-fixture")
                val dir = manager.workspaceDir(workspace.root)
                check(dir.deleteRecursively())
                File(dir, "files").mkdirs()
                File(dir, "files/document.txt").writeText("preserved-files")
                File(dir, "linux/root").mkdirs()
                File(dir, "linux/root/profile.txt").writeText("preserved-home")
                val row = requireNotNull(database.workspaceDao().getById(workspace.id))
                database.workspaceDao().upsert(row.copy(shellStatus = "DISABLED", runtimeMetadata = "{}"))
                save(Properties().apply {
                    setProperty("id", workspace.id)
                    setProperty("root", workspace.root)
                    setProperty("settings", settingsHash())
                    setProperty("conversations", conversationsHash())
                })
                assertFalse(File(dir, "runtime").exists())
            }
            "restore", "verify" -> {
                check(stateFile.isFile) { "Prepare stage is missing" }
                val state = Properties().apply { stateFile.inputStream().use { load(it) } }
                val id = state.getProperty("id")
                val root = state.getProperty("root")
                val dir = manager.workspaceDir(root)
                assertNotNull(database.workspaceDao().getById(id))
                assertEquals("portable-v1", File(dir, "runtime").readText())
                assertFalse(File(dir, "linux").exists())
                assertEquals("preserved-files", File(dir, "files/document.txt").readText())
                assertEquals("preserved-home", File(dir, "home/profile.txt").readText())
                assertEquals(state.getProperty("settings"), settingsHash())
                assertEquals(state.getProperty("conversations"), conversationsHash())
                if (stage == "restore") {
                    val incremental = archive.createBackupFile(includeDatabase = false, includeFiles = false)
                    try {
                        assertTrue(repository.delete(id))
                        val result = archive.restoreBackupFile(incremental, includeDatabase = false, includeFiles = false)
                        assertTrue(result.restoredWorkspaceIds.contains(id))
                        assertEquals("preserved-files", File(dir, "files/document.txt").readText())
                        assertEquals("preserved-home", File(dir, "home/profile.txt").readText())
                    } finally {
                        incremental.delete()
                    }
                    val full = archive.createBackupFile(includeDatabase = true, includeFiles = false)
                    archive.verifyBackupFile(full)
                    state.setProperty("backup", full.absolutePath)
                    state.setProperty("restoreRequested", "true")
                    save(state)
                    assertTrue(repository.delete(id))
                    assertNull(database.workspaceDao().getById(id))
                    // Success closes Room and exits. Verification must run in the next process.
                    archive.restoreBackupFile(full, includeDatabase = true, includeFiles = false)
                    fail("Full database restore must exit its process")
                } else {
                    assertEquals("true", state.getProperty("restoreRequested"))
                    WorkspaceArchiveCleanup.cleanupPending(context)
                    assertFalse(WorkspaceArchiveRecovery.journalFile(context).exists())
                    assertTrue(repository.delete(id))
                    state.getProperty("backup")?.let { File(it).delete() }
                    assertTrue(stateFile.delete())
                }
            }
            else -> error("Unknown portable validation stage")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
