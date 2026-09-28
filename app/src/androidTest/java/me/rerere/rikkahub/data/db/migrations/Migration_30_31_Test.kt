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
class Migration_30_31_Test {
    private val databaseName = "migration-30-31-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsWorkspaceAptPlansTable() {
        helper.createDatabase(databaseName, 30).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            31,
            true,
            Migration_30_31,
        )

        db.query("PRAGMA table_info(`workspace_apt_plans`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                names += name
                when (name) {
                    "plan_id" -> {
                        assertEquals("TEXT", cursor.getString(typeIndex))
                        assertEquals(1, cursor.getInt(notNullIndex))
                        assertEquals(1, cursor.getInt(pkIndex))
                    }
                    "created_at", "expires_at" ->
                        assertEquals("INTEGER", cursor.getString(typeIndex))
                    "confirmed_tool_call_id", "operation_id" ->
                        assertEquals(0, cursor.getInt(notNullIndex))
                    else -> {
                        assertEquals("TEXT", cursor.getString(typeIndex))
                        assertEquals(1, cursor.getInt(notNullIndex))
                    }
                }
            }
            listOf(
                "plan_id", "workspace_id", "assistant_id", "conversation_id", "action",
                "packages_json", "base_fingerprint", "plan_fingerprint", "summary_json",
                "created_at", "expires_at", "confirmed_tool_call_id", "state", "operation_id",
            ).forEach { column ->
                assertTrue("missing column $column", names.contains(column))
            }
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'workspace_apt_plans'"
        ).use { cursor ->
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue(names.contains("index_workspace_apt_plans_workspace_id_created_at"))
            assertTrue(names.contains("index_workspace_apt_plans_conversation_id"))
        }

        // The migrated table must be immediately usable by the compare-and-set transitions.
        db.execSQL(
            """
            INSERT INTO `workspace_apt_plans` (
                `plan_id`, `workspace_id`, `assistant_id`, `conversation_id`, `action`,
                `packages_json`, `base_fingerprint`, `plan_fingerprint`, `summary_json`,
                `created_at`, `expires_at`, `state`
            ) VALUES ('plan-1', 'workspace-a', 'assistant-a', 'conversation-a', 'install',
                      '["htop"]', 'base-1', 'plan-1', '{}', 1, 1000, 'PLANNED')
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE `workspace_apt_plans`
            SET `state` = 'CONFIRMED', `confirmed_tool_call_id` = 'call-1'
            WHERE `plan_id` = 'plan-1' AND `state` = 'PLANNED' AND `expires_at` > 999
            """.trimIndent()
        )
        db.query(
            "SELECT state, confirmed_tool_call_id FROM `workspace_apt_plans` WHERE plan_id = 'plan-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CONFIRMED", cursor.getString(0))
            assertEquals("call-1", cursor.getString(1))
        }
        db.close()
    }
}
