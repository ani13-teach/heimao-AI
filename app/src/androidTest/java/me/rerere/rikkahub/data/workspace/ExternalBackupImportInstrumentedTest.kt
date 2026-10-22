package me.rerere.rikkahub.data.workspace

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in tests for private archives staged in the isolated debug application's cache. */
@RunWith(AndroidJUnit4::class)
class ExternalBackupImportInstrumentedTest {
    private fun inputDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalBackupImport") == "true")
        return File(context.cacheDir, "external-backup-import").apply { mkdirs() }
    }

    @Test fun importRequestedArchive() = runBlocking {
        val directory = inputDirectory()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("archive") ?: error("archive argument is required")
        require(name == "full.zip" || name == "delta.zip")
        val archive = File(directory, name)
        val base = if (name == "delta.zip") File(directory, "full.zip") else null
        check(archive.isFile && (base == null || base.isFile))
        File(directory, "import-started").writeText(name)
        File(directory, "import-failed").delete()
        try {
            GlobalContext.get().get<WorkspaceArchiveService>().restoreBackupFile(
                archive, includeDatabase = true, includeFiles = true, baseFile = base,
            )
            fail("Database restore must terminate the old process")
        } catch (error: Throwable) {
            File(directory, "import-failed").writeText(error.stackTraceToString())
            throw error
        }
    }

    @Test fun verifyRestartedDatabase() {
        val directory = inputDirectory()
        assertTrue(File(directory, "import-started").isFile)
        assertFalse(File(directory, "import-failed").exists())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = GlobalContext.get().get<AppDatabase>().openHelper.writableDatabase
        val counts = Bundle()
        for (table in listOf("conversationentity", "message_node", "workspaces", "message_fts")) {
            database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val count = cursor.getLong(0)
                assertTrue("Restored $table must not be empty", count > 0)
                counts.putLong(table, count)
            }
        }
        database.query("PRAGMA quick_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        database.query("SELECT root FROM workspaces").use { cursor ->
            while (cursor.moveToNext()) {
                val root = File(context.filesDir, "workspaces/${cursor.getString(0)}")
                assertTrue(File(root, "files").isDirectory)
                assertTrue(File(root, "home").isDirectory)
                assertFalse(File(root, "linux").exists())
            }
        }
        assertFalse(File(context.filesDir, "shared-linux").exists())
        counts.putLong("databaseVersion", database.version.toLong())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, counts)
        File(directory, "restart-verified").writeText(File(directory, "import-started").readText())
    }
}
