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
class Migration_26_27_Test {
    private val databaseName = "migration-26-27-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesRowsAndAddsLayeredMemoryColumnsWithDefaults() {
        helper.createDatabase(databaseName, 26).apply {
            execSQL(
                """
                INSERT INTO memoryentity (assistant_id, content)
                VALUES ('assistant-a', 'legacy memory content')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO memoryentity (assistant_id, content)
                VALUES ('assistant-b', 'another memory')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            27,
            true,
            Migration_26_27,
        )

        db.query(
            """
            SELECT assistant_id, content, category, title, updated,
                   confidence_flags, tags, product, status
            FROM memoryentity
            ORDER BY id ASC
            """.trimIndent()
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("assistant-a", cursor.getString(0))
            assertEquals("legacy memory content", cursor.getString(1))
            assertEquals("GENERAL", cursor.getString(2))
            assertEquals("", cursor.getString(3))
            assertEquals("1970-01-01", cursor.getString(4))
            assertEquals("[]", cursor.getString(5))
            assertEquals("[]", cursor.getString(6))
            assertEquals("", cursor.getString(7))
            assertEquals("active", cursor.getString(8))

            assertTrue(cursor.moveToNext())
            assertEquals("assistant-b", cursor.getString(0))
            assertEquals("another memory", cursor.getString(1))
            assertEquals("GENERAL", cursor.getString(2))
            assertEquals("", cursor.getString(3))
            assertEquals("1970-01-01", cursor.getString(4))
            assertEquals("[]", cursor.getString(5))
            assertEquals("[]", cursor.getString(6))
            assertEquals("", cursor.getString(7))
            assertEquals("active", cursor.getString(8))
        }
        db.close()
    }

    @Test
    fun migrationAddsAllSevenColumns() {
        helper.createDatabase(databaseName, 26).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            27,
            true,
            Migration_26_27,
        )

        db.query("PRAGMA table_info(`memoryentity`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val dfltIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
                if (cursor.getString(nameIndex) == "category") {
                    assertEquals("'GENERAL'", cursor.getString(dfltIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
                if (cursor.getString(nameIndex) == "updated") {
                    assertEquals("'1970-01-01'", cursor.getString(dfltIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
                if (cursor.getString(nameIndex) == "status") {
                    assertEquals("'active'", cursor.getString(dfltIndex))
                    assertEquals(1, cursor.getInt(notNullIndex))
                }
            }
            listOf("category", "title", "updated", "confidence_flags", "tags", "product", "status")
                .forEach { column ->
                    assertTrue("missing column $column", names.contains(column))
                }
        }
        db.close()
    }
}
