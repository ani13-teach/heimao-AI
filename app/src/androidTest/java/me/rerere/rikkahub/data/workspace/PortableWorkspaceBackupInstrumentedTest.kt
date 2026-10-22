package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.KotlinWorkspaceShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspacePythonExecutor
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class PortableWorkspaceBackupInstrumentedTest {
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var manager: WorkspaceManager
    private lateinit var repository: WorkspaceRepository
    private lateinit var archive: WorkspaceArchiveService
    private lateinit var snapshots: WorkspaceSnapshotService

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "portable-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        manager = WorkspaceManager(File(context.filesDir, "workspaces"),
            shellRunner = KotlinWorkspaceShellRunner(WorkspacePythonExecutor { _, _, _, _ ->
                WorkspaceCommandResult(0, "", "")
            }), portableRuntime = true)
        // Settings are read only. A second SettingsStore would share the process-wide DataStore.
        val settings = GlobalContext.get().get<SettingsStore>()
        val lock = WorkspaceCatalogMutationLock()
        val binding = AssistantWorkspaceBindingCoordinator(settings, database.workspaceDao(),
            database.conversationDao(), lock, AssistantWorkspaceAccessGate(settings, database.conversationDao()),
            AssistantWorkspaceSessionInvalidator())
        repository = WorkspaceRepository(database.workspaceDao(), manager, RootfsInstaller(manager), binding,
            database.workspaceAuditDao(), database.workspaceAptPlanDao(), lock)
        archive = WorkspaceArchiveService(context, JsonInstant, settings, repository,
            database.conversationDao(), database, binding)
        snapshots = WorkspaceSnapshotService(context, JsonInstant, repository, database.workspaceSnapshotDao())
        runBlocking {
            // Represent existing bindings only in the isolated catalog, never in the live database.
            settings.readPersistedSettingsFailFast().assistants.mapNotNull { it.workspaceId?.toString() }
                .distinct().forEach { id ->
                    manager.ensureWorkspace(id)
                    database.workspaceDao().upsert(WorkspaceEntity(id, "fixture-$id", id,
                        createdAt = 1L, updatedAt = 1L))
                }
        }
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun v2ExportContainsFilesAndHomeAndRejectsCorruptionWithoutTouchingLiveFixture() = runBlocking {
        val workspace = repository.create("export-fixture")
        val paths = manager.runtimePaths(workspace.root)
        File(paths.filesDir, "document").writeText("saved-files")
        File(paths.homeDir, "profile").writeText("saved-home")
        val backup = archive.createBackupFile(includeDatabase = false, includeFiles = false)
        archive.verifyBackupFile(backup)
        val entries = readZip(backup)
        val manifest = JsonInstant.decodeFromString<WorkspaceArchiveManifest>(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertEquals(2, manifest.formatVersion)
        val exported = manifest.workspaces.single { it.id == workspace.id }
        val restoredFiles = File(root, "extracted-files")
        val restoredHome = File(root, "extracted-home")
        val filesTar = File(root, "files.tar").apply { writeBytes(entries.getValue(exported.files.path)) }
        val homeTar = File(root, "home.tar").apply { writeBytes(entries.getValue(exported.home.path)) }
        WorkspaceArchiveTar.extract(filesTar, restoredFiles)
        WorkspaceArchiveTar.extract(homeTar, restoredHome)
        assertEquals("saved-files", File(restoredFiles, "document").readText())
        assertEquals("saved-home", File(restoredHome, "profile").readText())
        assertFalse(entries.keys.any { it.contains("/linux") })
        for (kind in listOf("hash", "v1", "conflict")) {
            val changed = entries.toMutableMap()
            when (kind) {
                "hash" -> changed[exported.home.path] = changed.getValue(exported.home.path).copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
                "v1" -> {
                    val header = JsonInstant.parseToJsonElement(changed.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
                    changed["manifest.json"] = JsonObject(header + ("formatVersion" to JsonPrimitive(1))).toString().toByteArray()
                }
            }
            val rejected = File(root, "$kind.zip")
            writeZip(rejected, changed)
            if (kind != "conflict") {
                val invalid = runCatching { archive.verifyBackupFile(rejected) }.exceptionOrNull()
                assertTrue("Expected validation failure for $kind: $invalid", invalid is IllegalArgumentException)
            }
            val failure = runCatching {
                archive.restoreBackupFile(rejected, includeDatabase = false, includeFiles = false)
            }.exceptionOrNull()
            assertTrue("Expected restore refusal for $kind: $failure", failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty(), when (kind) {
                "hash" -> failure.message.orEmpty().contains("hash", ignoreCase = true)
                "v1" -> failure.message.orEmpty().contains("v2")
                else -> failure.message.orEmpty().contains("already exists")
            })
            assertEquals("saved-files", File(paths.filesDir, "document").readText())
            assertEquals("saved-home", File(paths.homeDir, "profile").readText())
            assertNotNull(database.workspaceDao().getById(workspace.id))
        }
    }

    @Test fun snapshotServiceRoundTripsFilesHomeAndRetainsCurrentDataOnBadHash() = runBlocking {
        val workspace = repository.create("snapshot-fixture")
        val paths = manager.runtimePaths(workspace.root)
        File(paths.filesDir, "document").writeText("saved-files")
        File(paths.homeDir, "profile").writeText("saved-home")
        val snapshot = snapshots.createManualSnapshot(workspace.id)
        val entries = readZip(File(snapshot.path))
        val manifest = JsonInstant.decodeFromString<WorkspaceSnapshotManifest>(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertEquals(2, manifest.formatVersion)
        assertTrue(entries.containsKey(manifest.workspace.files.path))
        assertTrue(entries.containsKey(manifest.workspace.home.path))
        File(paths.filesDir, "document").writeText("current-files")
        File(paths.homeDir, "profile").writeText("current-home")
        val result = snapshots.restoreSnapshot(workspace.id, snapshot.id)
        assertEquals(snapshot.id, result.restoredSnapshot.id)
        assertEquals("saved-files", File(paths.filesDir, "document").readText())
        assertEquals("saved-home", File(paths.homeDir, "profile").readText())
        snapshots.restoreSnapshot(workspace.id, result.preRestoreSnapshot.id)
        assertEquals("current-files", File(paths.filesDir, "document").readText())
        assertEquals("current-home", File(paths.homeDir, "profile").readText())
        val original = File(snapshot.path).readBytes()
        try {
            val damaged = entries.toMutableMap()
            damaged[manifest.workspace.home.path] = damaged.getValue(manifest.workspace.home.path).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            writeZip(File(snapshot.path), damaged)
            val failure = runCatching { snapshots.restoreSnapshot(workspace.id, snapshot.id) }.exceptionOrNull()
            assertTrue("Expected payload hash refusal: $failure", failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("hash", ignoreCase = true))
            assertEquals("current-files", File(paths.filesDir, "document").readText())
            assertEquals("current-home", File(paths.homeDir, "profile").readText())
            assertNotNull(database.workspaceDao().getById(workspace.id))
        } finally {
            File(snapshot.path).writeBytes(original)
        }
    }

    private fun readZip(file: File): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(file.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                result[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
