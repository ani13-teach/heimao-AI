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
class Migration_27_28_Test {
    private val databaseName = "migration-27-28-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsNullableWorkspaceIdentityWithoutGuessingLegacyWorkspace() {
        helper.createDatabase(databaseName, 27).apply {
            execSQL(
                """
                INSERT INTO ConversationEntity (
                    id, assistant_id, title, nodes, create_at, update_at, workspace_cwd
                ) VALUES (
                    'conversation-legacy', 'assistant-a', 'legacy', '[]', 1000, 1000, '/old-cwd'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            28,
            true,
            Migration_27_28,
        )

        db.query("SELECT workspace_id, workspace_cwd FROM ConversationEntity WHERE id = 'conversation-legacy'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals("", it.getString(1))
        }

        db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "workspace_id") {
                    assertEquals(0, cursor.getInt(notNullIndex))
                    return@use
                }
            }
            throw AssertionError("workspace_id column is missing")
        }
        db.close()
    }
}
