package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {
    private val databaseName = "migration-29-30-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsWorkspaceAuditEventsAndSnapshotsTables() {
        helper.createDatabase(databaseName, 29).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            30,
            true,
            Migration_29_30,
        )

        db.query("PRAGMA table_info(`workspace_audit_events`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                names += name
                if (name == "workspace_id") {
                    assertEquals("TEXT", cursor.getString(typeIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
                if (name == "assistant_id" || name == "conversation_id" || name == "exit_code" || name == "bytes") {
                    assertEquals(0, cursor.getInt(notNullIndex))
                }
                if (name == "details") {
                    assertEquals("TEXT", cursor.getString(typeIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
            }
            listOf(
                "workspace_id", "assistant_id", "conversation_id", "actor", "operation", "target",
                "cwd", "started_at", "finished_at", "result", "exit_code", "timed_out", "bytes", "details",
            ).forEach { column ->
                assertTrue("missing column $column", names.contains(column))
            }
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'workspace_audit_events'"
        ).use { cursor ->
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue(names.contains("index_workspace_audit_events_workspace_id_started_at"))
            assertTrue(names.contains("index_workspace_audit_events_conversation_id"))
        }

        db.query("PRAGMA table_info(`workspace_snapshots`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                names += name
                assertEquals(1, cursor.getInt(notNullIndex))
                if (name == "workspace_id" || name == "path" || name == "manifest_hash" ||
                    name == "reason" || name == "status"
                ) {
                    assertEquals("TEXT", cursor.getString(typeIndex))
                }
                if (name == "size" || name == "created_at") {
                    assertEquals("INTEGER", cursor.getString(typeIndex))
                }
            }
            listOf(
                "id", "workspace_id", "path", "manifest_hash", "size", "reason", "status", "created_at",
            ).forEach { column ->
                assertTrue("missing column $column", names.contains(column))
            }
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'workspace_snapshots'"
        ).use { cursor ->
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue(names.contains("index_workspace_snapshots_workspace_id_created_at"))
            assertTrue(names.contains("index_workspace_snapshots_path"))
        }
        db.close()
    }
}
