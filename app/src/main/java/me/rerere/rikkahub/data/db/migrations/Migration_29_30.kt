package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds application-side workspace audit records and single-workspace snapshot metadata. */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspace_audit_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `workspace_id` TEXT NOT NULL,
                `assistant_id` TEXT,
                `conversation_id` TEXT,
                `actor` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `target` TEXT NOT NULL,
                `cwd` TEXT NOT NULL,
                `started_at` INTEGER NOT NULL,
                `finished_at` INTEGER NOT NULL,
                `result` TEXT NOT NULL,
                `exit_code` INTEGER,
                `timed_out` INTEGER NOT NULL,
                `bytes` INTEGER,
                `details` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_workspace_audit_events_workspace_id_started_at`
            ON `workspace_audit_events` (`workspace_id`, `started_at`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_workspace_audit_events_conversation_id`
            ON `workspace_audit_events` (`conversation_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspace_snapshots` (
                `id` TEXT NOT NULL,
                `workspace_id` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                `manifest_hash` TEXT NOT NULL,
                `size` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_workspace_snapshots_workspace_id_created_at`
            ON `workspace_snapshots` (`workspace_id`, `created_at`)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_snapshots_path`
            ON `workspace_snapshots` (`path`)
            """.trimIndent()
        )
    }
}
