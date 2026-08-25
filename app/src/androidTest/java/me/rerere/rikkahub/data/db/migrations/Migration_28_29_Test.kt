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
class Migration_28_29_Test {
    private val databaseName = "migration-28-29-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsRuntimeMetadataWithEmptyDefault() {
        helper.createDatabase(databaseName, 28).apply {
            execSQL(
                """
                INSERT INTO workspaces (
                    id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals
                ) VALUES (
                    'workspace-legacy', 'Legacy', 'workspace-legacy', 'DISABLED', 1000, 1000, NULL, '{}'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            29,
            true,
            Migration_28_29,
        )

        db.query("SELECT runtime_metadata FROM workspaces WHERE id = 'workspace-legacy'").use {
            assertTrue(it.moveToFirst())
            assertEquals("{}", it.getString(0))
        }

        db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "runtime_metadata") {
                    assertEquals(1, cursor.getInt(notNullIndex))
                    assertEquals("'{}'", cursor.getString(defaultIndex))
                    return@use
                }
            }
            throw AssertionError("runtime_metadata column is missing")
        }
        db.close()
    }
}
