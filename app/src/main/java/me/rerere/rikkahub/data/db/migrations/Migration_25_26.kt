package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Removes the legacy Lorebook feature: drops the `lorebook_ids` column of
 * `ConversationEntity` by rebuilding the table. All other columns, data,
 * primary key and the unique `conversation_number` index are preserved.
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ConversationEntity_new` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                `title` TEXT NOT NULL,
                `nodes` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `update_at` INTEGER NOT NULL,
                `suggestions` TEXT NOT NULL DEFAULT '[]',
                `is_pinned` INTEGER NOT NULL DEFAULT 0,
                `custom_system_prompt` TEXT NOT NULL DEFAULT '',
                `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
                `workspace_cwd` TEXT NOT NULL DEFAULT '',
                `folder_id` TEXT NOT NULL DEFAULT '',
                `conversation_number` INTEGER NOT NULL DEFAULT 0,
                `summary_source_assistant_id` TEXT NOT NULL DEFAULT '',
                `summary_source_conversation_ids` TEXT NOT NULL DEFAULT '[]',
                `summary_context` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `ConversationEntity_new` (
                id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned,
                custom_system_prompt, mode_injection_ids, workspace_cwd, folder_id,
                conversation_number, summary_source_assistant_id, summary_source_conversation_ids,
                summary_context
            )
            SELECT
                id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned,
                custom_system_prompt, mode_injection_ids, workspace_cwd, folder_id,
                conversation_number, summary_source_assistant_id, summary_source_conversation_ids,
                summary_context
            FROM `ConversationEntity`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `ConversationEntity`")
        db.execSQL("ALTER TABLE `ConversationEntity_new` RENAME TO `ConversationEntity`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ConversationEntity_conversation_number` " +
                "ON `ConversationEntity` (`conversation_number`)"
        )
    }
}
