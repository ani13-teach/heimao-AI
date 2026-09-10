package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageFtsSchemaMigrationTest {
    @Test
    fun legacyRoleSchemaIsRebuiltWithCurrentColumnsAndRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "legacy-message-fts-${System.nanoTime()}.db")
        val helper = RequerySQLiteOpenHelperFactory(
            listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null,
                        ),
                    )
                    options
                },
            ),
        ).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        try {
            val database = helper.writableDatabase
            createSourceTables(database)
            database.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    role UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO ConversationEntity(id, title, update_at) VALUES (?, ?, ?)",
                arrayOf<Any?>("conversation-1", "旧会话", 1234L),
            )
            val messages = JsonInstant.encodeToString(
                listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("旧索引升级测试")),
                    ),
                ),
            )
            database.execSQL(
                "INSERT INTO message_node(id, conversation_id, messages, node_index) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("node-1", "conversation-1", messages, 0),
            )

            ensureCurrentMessageFtsSchema(database)

            assertEquals(
                listOf("text", "node_id", "message_id", "conversation_id", "title", "update_at"),
                tableColumns(database, "message_fts"),
            )
            database.query(
                "SELECT title, update_at FROM message_fts WHERE message_fts MATCH jieba_query(?)",
                arrayOf("升级测试"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧会话", cursor.getString(0))
                assertEquals(1234L, cursor.getLong(1))
            }
        } finally {
            helper.close()
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    private fun createSourceTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE ConversationEntity(id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, update_at INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE message_node(id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, messages TEXT NOT NULL, node_index INTEGER NOT NULL)",
        )
    }

    private fun tableColumns(database: SupportSQLiteDatabase, table: String): List<String> = buildList {
        database.query("PRAGMA table_info('$table')").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }
}
