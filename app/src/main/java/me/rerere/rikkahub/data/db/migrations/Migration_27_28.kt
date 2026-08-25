package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the persisted workspace identity to conversations.
 *
 * Existing conversations intentionally remain NULL: the assistant's current workspace
 * cannot reliably reconstruct the workspace used by an old conversation.
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN workspace_id TEXT")
        // A legacy CWD has no safe workspace owner once the identity is unknown.
        db.execSQL("UPDATE ConversationEntity SET workspace_cwd = '' WHERE workspace_id IS NULL")
    }
}
