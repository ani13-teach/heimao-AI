package me.rerere.rikkahub.data.workspace

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceArchiveDatabaseCopyTest {
    @Test
    fun sanitizingBackupCopyLeavesOnlineSourceAndRemovesSnapshotRowsAndSidecars() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "archive-copy-test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        val source = File(root, "source.db")
        val copy = File(root, "copy.db")
        try {
            createDatabaseWithSnapshot(source, "/source/device/workspaces/root/snapshots/a.zip")
            source.copyTo(copy)
            File(copy.path + "-journal").writeText("stale sidecar")

            sanitizeWorkspaceDatabaseCopy(copy)

            assertEquals(1, snapshotCount(source))
            assertEquals(0, snapshotCount(copy))
            assertFalse(File(copy.path + "-journal").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createDatabaseWithSnapshot(file: File, path: String) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE workspace_snapshots " +
                    "(id TEXT PRIMARY KEY NOT NULL, path TEXT NOT NULL)",
            )
            database.execSQL(
                "INSERT INTO workspace_snapshots(id, path) VALUES (?, ?)",
                arrayOf("snapshot", path),
            )
        }
    }

    private fun snapshotCount(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM workspace_snapshots", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
}
