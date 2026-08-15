package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds stable conversation numbers and persisted summary-source metadata. */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ConversationEntity ADD COLUMN conversation_number INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE ConversationEntity ADD COLUMN summary_source_assistant_id TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE ConversationEntity ADD COLUMN summary_source_conversation_ids TEXT NOT NULL DEFAULT '[]'"
        )
        db.execSQL(
            "ALTER TABLE ConversationEntity ADD COLUMN summary_context TEXT NOT NULL DEFAULT ''"
        )

        // Assign deterministic numbers to old rows before creating the unique index.
        val ids = buildList {
            db.query("SELECT id FROM ConversationEntity ORDER BY create_at ASC, id ASC").use {
                while (it.moveToNext()) add(it.getString(0))
            }
        }
        ids.forEachIndexed { index, id ->
            db.execSQL(
                "UPDATE ConversationEntity SET conversation_number = ? WHERE id = ?",
                arrayOf(index + 1L, id)
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_number_sequence (
                id INTEGER NOT NULL PRIMARY KEY,
                next_number INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO conversation_number_sequence (id, next_number)
            SELECT 0, COALESCE(MAX(conversation_number), 0) + 1
            FROM ConversationEntity
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_ConversationEntity_conversation_number " +
                "ON ConversationEntity (conversation_number)"
        )
    }
}
