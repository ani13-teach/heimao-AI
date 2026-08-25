package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSchema29FtsSqlInstrumentedTest {
    @Test
    fun simpleTokenizerReadFromSqliteMasterIsAccepted() {
        assertMessageFtsSqlAccepted(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                text,
                node_id UNINDEXED,
                message_id UNINDEXED,
                conversation_id UNINDEXED,
                title UNINDEXED,
                update_at UNINDEXED,
                tokenize = 'simple'
            )
            """.trimIndent(),
            expected = true,
        )
    }

    @Test
    fun currentSqliteVersionSupportsFts5RebuildCommand() {
        val database = openDatabase()
        try {
            val version = database.rawQuery("SELECT sqlite_version()", null).use { cursor ->
                check(cursor.moveToFirst()) { "sqlite_version() returned no row" }
                cursor.getString(0)
            }
            assertTrue(version.isNotBlank())
            database.execSQL(messageFtsSql())
            database.execSQL(
                "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                    "VALUES ('rebuild probe', 'node', 'message', 'conversation', 'title', 1)",
            )
            database.execSQL("INSERT INTO message_fts(message_fts) VALUES ('rebuild')")
            database.rawQuery("SELECT COUNT(*) FROM message_fts", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) == 1)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun unicode61TokenizerReadFromSqliteMasterIsRejected() {
        assertMessageFtsSqlAccepted(
            messageFtsSql(tokenizer = "unicode61"),
            expected = false,
        )
    }

    @Test
    fun wrongColumnsReadFromSqliteMasterAreRejected() {
        assertMessageFtsSqlAccepted(
            """
            CREATE VIRTUAL TABLE message_fts USING fts5(
                body,
                node_id UNINDEXED,
                message_id UNINDEXED,
                conversation_id UNINDEXED,
                title UNINDEXED,
                update_at UNINDEXED,
                tokenize = 'simple'
            )
            """.trimIndent(),
            expected = false,
        )
    }

    @Test
    fun extraFtsParameterReadFromSqliteMasterIsRejected() {
        assertMessageFtsSqlAccepted(
            messageFtsSql(extraParameter = ", prefix = '2'"),
            expected = false,
        )
    }

    private fun assertMessageFtsSqlAccepted(createSql: String, expected: Boolean) {
        val database = openDatabase()
        try {
            database.execSQL(createSql)
            val storedSql = database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'message_fts'",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst()) { "message_fts was not created" }
                cursor.getString(0)
            }
            if (expected) {
                assertTrue(roomSchema29MessageFtsSqlIsExact(storedSql))
            } else {
                assertFalse(roomSchema29MessageFtsSqlIsExact(storedSql))
            }
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): SQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SQLiteDatabaseConfiguration(
            SQLiteDatabaseConfiguration.MEMORY_DB_PATH,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.OPEN_CREATE,
            emptyList(),
            emptyList(),
            listOf(
                SQLiteCustomExtension(
                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                    null,
                ),
            ),
        )
        return SQLiteDatabase.openDatabase(configuration, null, null)
    }

    private fun messageFtsSql(
        tokenizer: String = "simple",
        extraParameter: String = "",
    ): String = """
        CREATE VIRTUAL TABLE message_fts USING fts5(
            text,
            node_id UNINDEXED,
            message_id UNINDEXED,
            conversation_id UNINDEXED,
            title UNINDEXED,
            update_at UNINDEXED,
            tokenize = '$tokenizer'$extraParameter
        )
    """.trimIndent()
}
