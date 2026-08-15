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
class Migration_24_25_Test {
    private val databaseName = "migration-24-25-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAssignsStableNumbersAndInitializesSummaryColumns() {
        helper.createDatabase(databaseName, 24).apply {
            execSQL(
                """
                INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at)
                VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'newer', '[]', 2000, 2000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'older', '[]', 1000, 1000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            25,
            true,
            Migration_24_25,
        )

        db.query(
            "SELECT title, conversation_number, summary_source_assistant_id, " +
                "summary_source_conversation_ids, summary_context " +
                "FROM ConversationEntity ORDER BY conversation_number"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("older", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals("", cursor.getString(2))
            assertEquals("[]", cursor.getString(3))
            assertEquals("", cursor.getString(4))

            assertTrue(cursor.moveToNext())
            assertEquals("newer", cursor.getString(0))
            assertEquals(2L, cursor.getLong(1))
        }

        db.query("SELECT next_number FROM conversation_number_sequence WHERE id = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
        }
        db.close()
    }
}
