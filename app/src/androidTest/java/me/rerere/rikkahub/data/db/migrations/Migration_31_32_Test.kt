package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_31_32_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun addsIndependentContextBlocksAndPreservesExistingTables() {
        val name = "migration-31-32-test"
        helper.createDatabase(name, 31).close()
        val db = helper.runMigrationsAndValidate(name, 32, true, Migration_31_32)
        db.query("PRAGMA foreign_key_list(context_block)").use {
            assertTrue(it.moveToFirst())
            assertEquals("ConversationEntity", it.getString(it.getColumnIndexOrThrow("table")))
            assertEquals("CASCADE", it.getString(it.getColumnIndexOrThrow("on_delete")))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM context_block").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }
}
