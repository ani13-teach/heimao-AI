package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the persisted public apt plans used to gate an explicit user confirmation. */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspace_apt_plans` (
                `plan_id` TEXT NOT NULL,
                `workspace_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `packages_json` TEXT NOT NULL,
                `base_fingerprint` TEXT NOT NULL,
                `plan_fingerprint` TEXT NOT NULL,
                `summary_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `expires_at` INTEGER NOT NULL,
                `confirmed_tool_call_id` TEXT,
                `state` TEXT NOT NULL,
                `operation_id` TEXT,
                PRIMARY KEY(`plan_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_workspace_apt_plans_workspace_id_created_at`
            ON `workspace_apt_plans` (`workspace_id`, `created_at`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_workspace_apt_plans_conversation_id`
            ON `workspace_apt_plans` (`conversation_id`)
            """.trimIndent()
        )
    }
}
