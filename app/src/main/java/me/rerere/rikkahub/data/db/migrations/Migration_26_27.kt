package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds layered-memory metadata columns to `MemoryEntity`.
 *
 * Existing rows keep their content and get the default metadata values
 * (GENERAL category, empty title, unknown update date, no confidence flags,
 * no tags, no product, active status).
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN category TEXT NOT NULL DEFAULT 'GENERAL'")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN updated TEXT NOT NULL DEFAULT '1970-01-01'")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN confidence_flags TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN product TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
    }
}
