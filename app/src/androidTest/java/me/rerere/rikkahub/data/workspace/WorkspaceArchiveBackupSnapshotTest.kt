package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.fts.ensureCurrentMessageFtsSchema
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class WorkspaceArchiveBackupSnapshotTest {
    @Test
    fun legacyMessageFtsIsUpgradedBeforeFullBackup() = runBlocking {
        val koin = GlobalContext.get()
        val database = koin.get<AppDatabase>()
        val service = koin.get<WorkspaceArchiveService>()
        val databaseHandle = database.openHelper.writableDatabase
        var generated: File? = null
        try {
            databaseHandle.execSQL("DROP TABLE message_fts")
            databaseHandle.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    role UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent(),
            )

            ensureCurrentMessageFtsSchema(databaseHandle)
            assertEquals(
                listOf("text", "node_id", "message_id", "conversation_id", "title", "update_at"),
                tableColumns(databaseHandle, "message_fts"),
            )

            generated = service.createBackupFile(
                includeDatabase = true,
                includeFiles = false,
            )
            assertTrue(generated.isFile)
            assertTrue(WorkspaceArchiveEncryption.inspect(generated).isLegacy)
        } finally {
            ensureCurrentMessageFtsSchema(databaseHandle)
            generated?.delete()
        }
    }

    @Test
    fun fullBackupKeepsStoredCredentialsAndOnlineSnapshotRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val database = koin.get<AppDatabase>()
        val service = koin.get<WorkspaceArchiveService>()
        val settingsStore = koin.get<SettingsStore>()
        val databaseHandle = database.openHelper.writableDatabase
        val snapshotId = "backup-copy-test-${System.nanoTime()}"
        val backup = File(context.cacheDir, ".backup-copy-test-${System.nanoTime()}.zip")
        val stagedDatabase = File(context.cacheDir, ".backup-copy-test-${System.nanoTime()}.db")
        val sentinelSecret = "sentinel-secret-${System.nanoTime()}"
        val originalSettings = settingsStore.readPersistedSettingsFailFast()
        try {
            databaseHandle.execSQL(
                "INSERT INTO workspace_snapshots " +
                    "(id, workspace_id, path, manifest_hash, size, reason, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    snapshotId,
                    "source-workspace",
                    "/source/device/workspaces/source/snapshots/source.zip",
                    "0".repeat(64),
                    123L,
                    "MANUAL",
                    "READY",
                    System.currentTimeMillis(),
                ),
            )
            assertEquals(1, snapshotCount(databaseHandle, snapshotId))

            // A backup is the only copy of these credentials after a reinstall, so they have to be
            // packaged verbatim. Write a known secret first, then look for it inside the archive.
            settingsStore.transformLatest { settings ->
                settings.copy(
                    webDavConfig = settings.webDavConfig.copy(password = sentinelSecret),
                    s3Config = settings.s3Config.copy(secretAccessKey = sentinelSecret),
                )
            }

            val generated = service.createBackupFile(
                includeDatabase = true,
                includeFiles = false,
            )
            generated.copyTo(backup, overwrite = true)
            generated.delete()

            var manifest: WorkspaceArchiveManifest? = null
            var archivedSettings: String? = null
            ZipInputStream(backup.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "manifest.json" -> {
                            manifest = JsonInstant.decodeFromString<WorkspaceArchiveManifest>(
                                zip.readBytes().toString(Charsets.UTF_8),
                            )
                        }
                        WorkspaceArchiveManifest.SETTINGS_PATH -> {
                            archivedSettings = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        "database/rikka_hub.db" -> {
                            stagedDatabase.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            assertTrue(manifest?.snapshotsExcluded == true)
            val settingsJson = archivedSettings ?: error("Backup has no settings entry")
            assertTrue(
                "Archived settings dropped the stored secret",
                settingsJson.contains(sentinelSecret),
            )
            val settings = JsonInstant.decodeFromString<Settings>(settingsJson)
            assertEquals(sentinelSecret, settings.webDavConfig.password)
            assertEquals(sentinelSecret, settings.s3Config.secretAccessKey)
            assertTrue(stagedDatabase.isFile)
            assertEquals(0, snapshotCountInFile(stagedDatabase, snapshotId))
            assertEquals(1, snapshotCount(databaseHandle, snapshotId))
        } finally {
            databaseHandle.execSQL(
                "DELETE FROM workspace_snapshots WHERE id = ?",
                arrayOf<Any?>(snapshotId),
            )
            settingsStore.replaceAuthoritativelyForRestore(originalSettings)
            backup.delete()
            stagedDatabase.delete()
        }
    }

    private fun tableColumns(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): List<String> = buildList {
        database.query("PRAGMA table_info('$table')").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }

    private fun snapshotCount(database: androidx.sqlite.db.SupportSQLiteDatabase, id: String): Int =
        database.query(
            "SELECT COUNT(*) FROM workspace_snapshots WHERE id = ?",
            arrayOf(id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun snapshotCountInFile(file: File, id: String): Int =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM workspace_snapshots WHERE id = ?",
                arrayOf(id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
}
