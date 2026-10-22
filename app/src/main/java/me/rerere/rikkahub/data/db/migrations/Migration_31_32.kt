package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `context_block` (
                `id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL, `sources` TEXT NOT NULL,
                `source_hash` TEXT NOT NULL, `prefix_hash` TEXT NOT NULL,
                `summary` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                `format_version` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_block_conversation_id` ON `context_block` (`conversation_id`)")
    }
}
