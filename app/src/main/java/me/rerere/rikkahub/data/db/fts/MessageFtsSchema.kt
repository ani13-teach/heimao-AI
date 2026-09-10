package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Locale

private const val TAG = "MessageFtsSchema"
private const val MESSAGE_FTS_TABLE = "message_fts"

private val CURRENT_COLUMNS = listOf(
    "text",
    "node_id",
    "message_id",
    "conversation_id",
    "title",
    "update_at",
)
private val LEGACY_COLUMNS = listOf(
    "text",
    "node_id",
    "message_id",
    "conversation_id",
    "role",
)

private val CREATE_MESSAGE_FTS_SQL = """
    CREATE VIRTUAL TABLE message_fts USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
""".trimIndent()

/** Upgrades the pre-title/update_at FTS table, which Room does not manage or migrate. */
internal fun ensureCurrentMessageFtsSchema(database: SupportSQLiteDatabase) {
    val definition = database.query(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(MESSAGE_FTS_TABLE),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
    if (definition == null) {
        database.execSQL(CREATE_MESSAGE_FTS_SQL)
        return
    }

    val columns = database.query("PRAGMA table_info('$MESSAGE_FTS_TABLE')").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }
    require(isSupportedSimpleFts5(definition)) {
        "Unsupported message_fts definition"
    }
    when (columns) {
        CURRENT_COLUMNS -> return
        LEGACY_COLUMNS -> rebuildLegacyMessageFts(database)
        else -> error("Unsupported message_fts columns: ${columns.joinToString()}")
    }
}

private fun rebuildLegacyMessageFts(database: SupportSQLiteDatabase) {
    database.beginTransaction()
    try {
        database.execSQL("DROP TABLE `$MESSAGE_FTS_TABLE`")
        database.execSQL(CREATE_MESSAGE_FTS_SQL)
        val insert = database.compileStatement(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        )
        try {
            database.query(
                "SELECT message_node.id, message_node.conversation_id, message_node.messages, " +
                    "ConversationEntity.title, ConversationEntity.update_at " +
                    "FROM message_node " +
                    "INNER JOIN ConversationEntity ON ConversationEntity.id = message_node.conversation_id " +
                    "ORDER BY message_node.conversation_id, message_node.node_index",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val nodeId = cursor.getString(0)
                    val messages = runCatching {
                        JsonInstant.decodeFromString<List<UIMessage>>(cursor.getString(2))
                    }.getOrElse { error ->
                        Log.w(TAG, "Skipping unreadable message node while rebuilding search index: $nodeId", error)
                        emptyList()
                    }
                    messages.forEach { message ->
                        val text = message.parts
                            .filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                            .take(10_000)
                        if (text.isBlank()) return@forEach
                        insert.clearBindings()
                        insert.bindString(1, text)
                        insert.bindString(2, nodeId)
                        insert.bindString(3, message.id.toString())
                        insert.bindString(4, cursor.getString(1))
                        insert.bindString(5, cursor.getString(3))
                        insert.bindLong(6, cursor.getLong(4))
                        insert.executeInsert()
                    }
                }
            }
        } finally {
            insert.close()
        }
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
}

private fun isSupportedSimpleFts5(sql: String): Boolean {
    val normalized = sql
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s`\"]"), "")
        .replace("ifnotexists", "")
        .removeSuffix(";")
    return normalized.startsWith("createvirtualtablemessage_ftsusingfts5(") &&
        normalized.endsWith("tokenize='simple')")
}
