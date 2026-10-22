package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageSearchInstrumentedTest {
    @Test
    fun currentAssistantIncludesTitleAndBodyMatchesWithoutOtherAssistants() = withDatabase { db ->
        val current = search(db, "needle", "assistant-a")
        assertEquals(4, current.size)
        assertEquals(setOf("a-title", "a-body", "a-both"), current.map { it.conversationId }.toSet())
        assertEquals(2, current.count { it.conversationId == "a-body" })

        val all = search(db, "needle", null)
        assertEquals(6, all.size)
        assertEquals(setOf("a-title", "a-body", "a-both", "b-title", "b-body"), all.map { it.conversationId }.toSet())
    }

    @Test
    fun titleOnlyConversationNeedsNoIndexedMessagesAndBothMatchIsNotDuplicated() = withDatabase { db ->
        val results = search(db, "needle", "assistant-a")
        val titleOnly = results.single { it.conversationId == "a-title" }
        assertNull(titleOnly.nodeId)
        assertEquals("needle 标题", titleOnly.snippet)
        assertEquals(1, results.count { it.conversationId == "a-both" })
        assertTrue(results.filter { it.nodeId != null }.all { it.snippet.contains("[needle]") })
    }

    @Test
    fun titleContainsMatchUsesLiveTitleAndSupportsChinese() = withDatabase { db ->
        assertEquals(listOf("a-title"), search(db, "标题", "assistant-a").map { it.conversationId })
        db.execSQL("UPDATE conversationentity SET title = ? WHERE id = ?", arrayOf("updated title", "a-title"))
        assertEquals(listOf("a-title"), search(db, "updated", "assistant-a").map { it.conversationId })
        assertFalse(search(db, "needle", "assistant-a").any { it.conversationId == "a-title" })
    }

    @Test
    fun sortAndLimitApplyToCombinedMatches() = withDatabase { db ->
        val newest = search(db, "needle", null, MessageSearchSort.NEWEST_FIRST, 2)
        assertEquals(listOf("b-body", "b-title"), newest.map { it.conversationId })
        val oldest = search(db, "needle", null, MessageSearchSort.OLDEST_FIRST, 1)
        assertEquals(listOf("a-title"), oldest.map { it.conversationId })
    }

    @Test
    fun aiSearchRemainsBodyOnlyExcludesCurrentAndDeduplicates() = withDatabase { db ->
        val statement = buildMessageSearchStatement(
            keyword = "needle",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = "assistant-a",
            excludeConversationId = "a-both",
        )
        assertEquals(listOf("a-body"), query(db, statement).map { it.conversationId })
    }

    private data class Hit(val nodeId: String?, val conversationId: String, val snippet: String)

    private fun search(
        db: SupportSQLiteDatabase,
        keyword: String,
        assistantId: String?,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
    ): List<Hit> = query(
        db,
        buildMessageSearchStatement(keyword, sort, assistantId, limit = limit, includeTitles = true),
    )

    private fun query(db: SupportSQLiteDatabase, statement: MessageSearchStatement): List<Hit> =
        db.query(statement.sql, statement.args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Hit(
                            nodeId = if (cursor.isNull(0)) null else cursor.getString(0),
                            conversationId = cursor.getString(2),
                            snippet = cursor.getString(6),
                        ),
                    )
                }
            }
        }

    private fun withDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "message-search-${System.nanoTime()}.db")
        val helper = RequerySQLiteOpenHelperFactory(
            listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null),
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
            val db = helper.writableDatabase
            db.execSQL(
                "CREATE TABLE conversationentity(id TEXT PRIMARY KEY, assistant_id TEXT, " +
                    "conversation_number INTEGER, title TEXT, update_at INTEGER)",
            )
            ensureCurrentMessageFtsSchema(db)
            listOf(
                arrayOf<Any>("a-title", "assistant-a", 1, "needle 标题", 10),
                arrayOf<Any>("a-body", "assistant-a", 2, "ordinary", 20),
                arrayOf<Any>("a-both", "assistant-a", 3, "needle both", 30),
                arrayOf<Any>("b-title", "assistant-b", 4, "needle other title", 40),
                arrayOf<Any>("b-body", "assistant-b", 5, "other body", 50),
            ).forEach { values ->
                db.execSQL("INSERT INTO conversationentity VALUES (?, ?, ?, ?, ?)", values)
            }
            listOf("a-body", "a-body", "a-both", "b-body").forEachIndexed { index, conversationId ->
                db.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                        "SELECT ?, ?, ?, id, title, update_at FROM conversationentity WHERE id = ?",
                    arrayOf("needle message $index", "node-$index", "message-$index", conversationId),
                )
            }
            block(db)
        } finally {
            helper.close()
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
