package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds compact, versioned Rootfs/runtime metadata to workspaces. */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workspaces ADD COLUMN runtime_metadata TEXT NOT NULL DEFAULT '{}'")
    }
}
