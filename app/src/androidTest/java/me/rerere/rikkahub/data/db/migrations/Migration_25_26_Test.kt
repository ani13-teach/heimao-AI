package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    private val databaseName = "migration-25-26-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationRebuildsConversationTableWithoutLegacyColumn() {
        helper.createDatabase(databaseName, 25).apply {
            execSQL(
                """
                INSERT INTO ConversationEntity (
                    id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned,
                    custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id,
                    conversation_number, summary_source_assistant_id, summary_source_conversation_ids,
                    summary_context
                ) VALUES (
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                    'preserved conversation',
                    '[{"selectedIndex":0}]',
                    1000,
                    2000,
                    '["suggestion"]',
                    1,
                    'custom system prompt',
                    '["mode-1"]',
                    '["legacy-lorebook"]',
                    '/workspace/project',
                    'cccccccc-cccc-cccc-cccc-cccccccccccc',
                    42,
                    'dddddddd-dddd-dddd-dddd-dddddddddddd',
                    '["source-id"]',
                    'summary context'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            26,
            true,
            Migration_25_26,
        )

        db.query(
            """
            SELECT assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned,
                custom_system_prompt, mode_injection_ids, workspace_cwd, folder_id,
                conversation_number, summary_source_assistant_id, summary_source_conversation_ids,
                summary_context
            FROM ConversationEntity
            WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", cursor.getString(0))
            assertEquals("preserved conversation", cursor.getString(1))
            assertEquals("[{\"selectedIndex\":0}]", cursor.getString(2))
            assertEquals(1000L, cursor.getLong(3))
            assertEquals(2000L, cursor.getLong(4))
            assertEquals("[\"suggestion\"]", cursor.getString(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals("custom system prompt", cursor.getString(7))
            assertEquals("[\"mode-1\"]", cursor.getString(8))
            assertEquals("/workspace/project", cursor.getString(9))
            assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", cursor.getString(10))
            assertEquals(42L, cursor.getLong(11))
            assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", cursor.getString(12))
            assertEquals("[\"source-id\"]", cursor.getString(13))
            assertEquals("summary context", cursor.getString(14))
        }

        db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                assertFalse(cursor.getString(columnNameIndex) == "lorebook_ids")
            }
        }
        db.close()
    }
}
