package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
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
    fun fullBackupUsesSanitizedCopyAndKeepsOnlineSnapshotRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val database = koin.get<AppDatabase>()
        val service = koin.get<WorkspaceArchiveService>()
        val databaseHandle = database.openHelper.writableDatabase
        val snapshotId = "backup-copy-test-${System.nanoTime()}"
        val password = "archive-test-password".toCharArray()
        val backup = File(context.cacheDir, ".backup-copy-test-${System.nanoTime()}.zip.enc")
        val decrypted = File(context.cacheDir, ".backup-copy-test-${System.nanoTime()}.zip")
        val stagedDatabase = File(context.cacheDir, ".backup-copy-test-${System.nanoTime()}.db")
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

            val generated = service.createBackupFile(
                includeDatabase = true,
                includeFiles = false,
                password = password,
            )
            generated.copyTo(backup, overwrite = true)
            generated.delete()
            WorkspaceArchiveEncryption.decrypt(backup, decrypted, password)

            var manifest: WorkspaceArchiveManifest? = null
            ZipInputStream(decrypted.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "manifest.json" -> {
                            manifest = JsonInstant.decodeFromString<WorkspaceArchiveManifest>(
                                zip.readBytes().toString(Charsets.UTF_8),
                            )
                        }
                        "database/rikka_hub.db" -> {
                            stagedDatabase.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            assertTrue(manifest?.snapshotsExcluded == true)
            assertTrue(stagedDatabase.isFile)
            assertEquals(0, snapshotCountInFile(stagedDatabase, snapshotId))
            assertEquals(1, snapshotCount(databaseHandle, snapshotId))
        } finally {
            databaseHandle.execSQL(
                "DELETE FROM workspace_snapshots WHERE id = ?",
                arrayOf<Any?>(snapshotId),
            )
            password.fill('\u0000')
            backup.delete()
            decrypted.delete()
            stagedDatabase.delete()
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
