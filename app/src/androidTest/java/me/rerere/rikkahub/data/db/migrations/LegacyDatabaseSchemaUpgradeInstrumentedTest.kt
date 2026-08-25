package me.rerere.rikkahub.data.db.migrations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase as RequerySQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveService
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LegacyDatabaseSchemaUpgradeInstrumentedTest {
    private val databaseName = "legacy-schema27-wal-shm-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun printsRealRoom30SchemaAfterLegacyUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-schema30-dump-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        try {
            helper.createDatabase(databaseName, 27).close()
            val migrated = openRoomDatabase(context, source)
            val database = migrated.openHelper.writableDatabase
            database.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    title UNINDEXED,
                    update_at UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent(),
            )
            logRoom30Schema(database)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upgradedLegacyDatabasePassesTheFinalRoom30ArchiveGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-final-gate-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-final-gate-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val archive = File(staging, "legacy-final-gate.zip")
        try {
            helper.createDatabase(databaseName, 27).close()
            val migrated = openRoomDatabase(context, source)
            val database = migrated.openHelper.writableDatabase
            database.execSQL(
                """
                CREATE VIRTUAL TABLE message_fts USING fts5(
                    text,
                    node_id UNINDEXED,
                    message_id UNINDEXED,
                    conversation_id UNINDEXED,
                    title UNINDEXED,
                    update_at UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent(),
            )
            migrated.close()
            source.copyTo(File(staging, "rikka_hub.db"), overwrite = true)
            writeLegacyDatabaseArchive(
                archive = archive,
                main = File(staging, "rikka_hub.db"),
            )

            GlobalContext.get().get<WorkspaceArchiveService>().verifyBackupFile(archive, allowLegacy = true)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun schema30DatabaseWithUnknownTableIsRejectedByTheFinalArchiveGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-malformed-schema30-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-malformed-schema30-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val archive = File(staging, "legacy-malformed-schema30.zip")
        try {
            helper.createDatabase(databaseName, 27).close()
            openRoomDatabase(context, source).close()
            openRequeryDatabase(context, source).use { database ->
                database.execSQL(
                    """
                    CREATE VIRTUAL TABLE message_fts USING fts5(
                        text,
                        node_id UNINDEXED,
                        message_id UNINDEXED,
                        conversation_id UNINDEXED,
                        title UNINDEXED,
                        update_at UNINDEXED,
                        tokenize = 'simple'
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE TABLE restore_attack (payload TEXT NOT NULL)")
            }
            source.copyTo(File(staging, "rikka_hub.db"), overwrite = true)
            writeLegacyDatabaseArchive(
                archive = archive,
                main = File(staging, "rikka_hub.db"),
            )

            val failure = runCatching {
                GlobalContext.get().get<WorkspaceArchiveService>().verifyBackupFile(
                    archive,
                    allowLegacy = true,
                )
            }.exceptionOrNull()
            assertTrue("Unknown schema object was accepted", failure != null)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun corruptedLegacyDatabaseIsRejectedWithoutChangingTheSourceCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "legacy-corrupt-source-${System.nanoTime()}")
        val workingCopy = File(context.cacheDir, "legacy-corrupt-working-${System.nanoTime()}")
        val original = byteArrayOf(0x52, 0x49, 0x4b, 0x4b, 0x41, 0x00, 0x01)
        source.writeBytes(original)
        source.copyTo(workingCopy)
        try {
            assertTrue(
                runCatching {
                    SQLiteDatabase.openDatabase(
                        workingCopy.path,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    ).close()
                }.isFailure,
            )
            // The rejected working copy may be discarded by SQLite, but the archive/source bytes
            // must remain untouched for a retry or an explicit user decision.
            assertArrayEquals(original, source.readBytes())
        } finally {
            source.delete()
            workingCopy.delete()
        }
    }

    @Test
    fun legacyArchiveWithSchema27WalAndShmPassesTheProductionArchiveGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-archive-schema27-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-archive-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val archive = File(staging, "legacy.zip")
        try {
            val sourceDb = helper.createDatabase(databaseName, 27)
            sourceDb.execSQL(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, workspace_cwd) " +
                    "VALUES ('conversation-legacy', 'assistant', 'Legacy', '[]', 1, 1, '')",
            )
            val messages = JsonInstant.encodeToString(listOf(UIMessage.user("legacy FTS text")))
            sourceDb.execSQL(
                "INSERT INTO message_node " +
                    "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, 0, ?, 0)",
                arrayOf("node-legacy", "conversation-legacy", messages),
            )
            sourceDb.query("PRAGMA journal_mode=WAL").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            sourceDb.beginTransaction()
            sourceDb.execSQL("UPDATE ConversationEntity SET title = 'Legacy WAL' WHERE id = 'conversation-legacy'")
            sourceDb.setTransactionSuccessful()
            sourceDb.endTransaction()

            val sourceWal = File(source.parentFile, "$databaseName-wal")
            val sourceShm = File(source.parentFile, "$databaseName-shm")
            assertTrue(sourceWal.isFile)
            assertTrue(sourceShm.isFile)
            val stagedMain = File(staging, "rikka_hub.db")
            source.copyTo(stagedMain)
            sourceWal.copyTo(File(staging, "rikka_hub-wal"))
            sourceShm.copyTo(File(staging, "rikka_hub-shm"))
            sourceDb.close()

            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf(
                    "rikka_hub.db" to stagedMain,
                    "rikka_hub-wal" to File(staging, "rikka_hub-wal"),
                    "rikka_hub-shm" to File(staging, "rikka_hub-shm"),
                ).forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            val service = GlobalContext.get().get<WorkspaceArchiveService>()
            service.verifyBackupFile(archive, allowLegacy = true)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun legacyFtsRoleSchemaWithMessageDataPassesProductionArchiveGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-fts-role-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-fts-role-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val archive = File(staging, "legacy-role.zip")
        try {
            val sourceDb = helper.createDatabase(databaseName, 27)
            sourceDb.execSQL(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, workspace_cwd) " +
                    "VALUES ('conversation-legacy', 'assistant', 'Legacy', '[]', 1, 1, '')",
            )
            val messages = JsonInstant.encodeToString(listOf(UIMessage.user("legacy FTS message data")))
            sourceDb.execSQL(
                "INSERT INTO message_node " +
                    "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, 0, ?, 0)",
                arrayOf("node-legacy", "conversation-legacy", messages),
            )
            sourceDb.close()

            openRequeryDatabase(context, source).use { sourceRequery ->
                sourceRequery.execSQL(
                    """
                    CREATE VIRTUAL TABLE message_fts USING fts5(
                        text,
                        node_id UNINDEXED,
                        message_id UNINDEXED,
                        conversation_id UNINDEXED,
                        role UNINDEXED,
                        tokenize = 'simple'
                    )
                    """.trimIndent()
                )
                sourceRequery.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, role) " +
                        "VALUES ('stale legacy index', 'node-legacy', 'message-legacy', 'conversation-legacy', 'user')",
                )
                sourceRequery.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                sourceRequery.execSQL(
                    "UPDATE ConversationEntity SET title = 'Legacy FTS WAL' WHERE id = 'conversation-legacy'",
                )
                val sourceWal = File(source.parentFile, "$databaseName-wal")
                val sourceShm = File(source.parentFile, "$databaseName-shm")
                assertTrue(sourceWal.isFile)
                assertTrue(sourceShm.isFile)
                source.copyTo(File(staging, "rikka_hub.db"), overwrite = true)
                sourceWal.copyTo(File(staging, "rikka_hub-wal"), overwrite = true)
                sourceShm.copyTo(File(staging, "rikka_hub-shm"), overwrite = true)
            }

            writeLegacyDatabaseArchive(
                archive = archive,
                main = File(staging, "rikka_hub.db"),
                wal = File(staging, "rikka_hub-wal"),
                shm = File(staging, "rikka_hub-shm"),
            )
            GlobalContext.get().get<WorkspaceArchiveService>().verifyBackupFile(archive, allowLegacy = true)

            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT COUNT(*) FROM message_node", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun emptyCurrentFtsIsRebuiltFromLegacyMessageRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-fts-empty-${System.nanoTime()}"
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-fts-empty-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val archive = File(staging, "legacy-empty.zip")
        try {
            val sourceDb = helper.createDatabase(databaseName, 27)
            sourceDb.execSQL(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, workspace_cwd) " +
                    "VALUES ('conversation-empty', 'assistant', 'Empty FTS', '[]', 1, 1, '')",
            )
            val messages = JsonInstant.encodeToString(listOf(UIMessage.user("message survives empty FTS rebuild")))
            sourceDb.execSQL(
                "INSERT INTO message_node " +
                    "(id, conversation_id, node_index, messages, select_index) VALUES (?, ?, 0, ?, 0)",
                arrayOf("node-empty", "conversation-empty", messages),
            )
            sourceDb.close()

            openRequeryDatabase(context, source).use { sourceRequery ->
                sourceRequery.execSQL(
                    """
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
                )
                sourceRequery.rawQuery("SELECT COUNT(*) FROM message_fts", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                sourceRequery.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                sourceRequery.execSQL(
                    "UPDATE ConversationEntity SET title = 'Empty FTS WAL' WHERE id = 'conversation-empty'",
                )
                val sourceWal = File(source.parentFile, "$databaseName-wal")
                val sourceShm = File(source.parentFile, "$databaseName-shm")
                assertTrue(sourceWal.isFile)
                assertTrue(sourceShm.isFile)
                source.copyTo(File(staging, "rikka_hub.db"), overwrite = true)
                sourceWal.copyTo(File(staging, "rikka_hub-wal"), overwrite = true)
                sourceShm.copyTo(File(staging, "rikka_hub-shm"), overwrite = true)
            }

            writeLegacyDatabaseArchive(
                archive = archive,
                main = File(staging, "rikka_hub.db"),
                wal = File(staging, "rikka_hub-wal"),
                shm = File(staging, "rikka_hub-shm"),
            )
            GlobalContext.get().get<WorkspaceArchiveService>().verifyBackupFile(archive, allowLegacy = true)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun schema27WalAndShmAreCheckpointedThenMigratedToSchema30() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = context.getDatabasePath(databaseName)
        val staging = File(context.cacheDir, "legacy-schema27-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        try {
            val sourceDb = helper.createDatabase(databaseName, 27)
            sourceDb.execSQL(
                "INSERT INTO workspaces " +
                    "(id, name, root, shell_status, created_at, updated_at, last_access_at, tool_approvals) " +
                    "VALUES ('workspace-legacy', 'Legacy', 'legacy', 'DISABLED', 1, 1, NULL, '{}')",
            )
            sourceDb.query("PRAGMA journal_mode=WAL").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            sourceDb.beginTransaction()
            sourceDb.execSQL(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, workspace_cwd) " +
                    "VALUES ('conversation-legacy', 'assistant', 'Legacy', '[]', 1, 1, '')",
            )
            sourceDb.setTransactionSuccessful()
            sourceDb.endTransaction()

            val sourceWal = File(source.parentFile, "$databaseName-wal")
            val sourceShm = File(source.parentFile, "$databaseName-shm")
            assertTrue("schema27 WAL sidecar was not produced", sourceWal.isFile)
            assertTrue("schema27 SHM sidecar was not produced", sourceShm.isFile)
            source.copyTo(File(staging, "rikka_hub"))
            sourceWal.copyTo(File(staging, "rikka_hub-wal"))
            sourceShm.copyTo(File(staging, "rikka_hub-shm"))
            sourceDb.close()

            val combined = File(staging, "rikka_hub")
            SQLiteDatabase.openDatabase(
                combined.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }

            val migrated = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                combined.absolutePath,
            )
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(
                    Migration_6_7,
                    Migration_11_12,
                    Migration_13_14,
                    Migration_14_15,
                    Migration_15_16,
                    Migration_24_25,
                    Migration_25_26,
                    Migration_26_27,
                    Migration_27_28,
                    Migration_28_29,
                    Migration_29_30,
                )
                .build()
            migrated.openHelper.writableDatabase
            migrated.close()

            SQLiteDatabase.openDatabase(combined.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA user_version", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(30, cursor.getInt(0))
                }
                database.rawQuery(
                    "SELECT runtime_metadata FROM workspaces WHERE id = 'workspace-legacy'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("{}", cursor.getString(0))
                }
                database.rawQuery(
                    "SELECT workspace_id, workspace_cwd FROM ConversationEntity WHERE id = 'conversation-legacy'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                    assertEquals("", cursor.getString(1))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
            staging.deleteRecursively()
        }
    }

    private fun openRoomDatabase(context: Context, file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_24_25,
                Migration_25_26,
                Migration_26_27,
                Migration_27_28,
                Migration_28_29,
                Migration_29_30,
            )
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                        io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                            options.customExtensions.add(
                                SQLiteCustomExtension(
                                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                                    null,
                                ),
                            )
                            options
                        },
                    ),
                ),
            )
            .build()

    private fun logRoom30Schema(database: SupportSQLiteDatabase) {
        val tag = "RoomSchema30Dump"
        fun log(message: String) = Log.i(tag, message)
        database.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst())
            log("user_version=${cursor.getInt(0)}")
        }
        database.query("SELECT id, identity_hash FROM room_master_table").use { cursor ->
            while (cursor.moveToNext()) {
                log("room_master_table id=${cursor.getInt(0)} identity_hash=${cursor.getString(1)}")
            }
        }
        database.query(
            "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                log(
                    "sqlite_master type=${cursor.getString(0)} name=${cursor.getString(1)} " +
                        "tbl_name=${cursor.getString(2)} sql=${cursor.getString(3)}",
                )
            }
        }
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0)
                database.query("PRAGMA table_info(${quoteSqlIdentifier(table)})").use { tableCursor ->
                    while (tableCursor.moveToNext()) {
                        log(
                            "table_info table=$table cid=${tableCursor.getInt(0)} " +
                                "name=${tableCursor.getString(1)} type=${tableCursor.getString(2)} " +
                                "notnull=${tableCursor.getInt(3)} dflt=${tableCursor.getString(4)} " +
                                "pk=${tableCursor.getInt(5)}",
                        )
                    }
                }
                database.query("PRAGMA index_list(${quoteSqlIdentifier(table)})").use { indexCursor ->
                    while (indexCursor.moveToNext()) {
                        val indexName = indexCursor.getString(1)
                        log(
                            "index_list table=$table seq=${indexCursor.getInt(0)} name=$indexName " +
                                "unique=${indexCursor.getInt(2)} origin=${indexCursor.getString(3)} " +
                                "partial=${indexCursor.getInt(4)}",
                        )
                        database.query("PRAGMA index_info(${quoteSqlIdentifier(indexName)})").use { infoCursor ->
                            while (infoCursor.moveToNext()) {
                                log(
                                    "index_info index=$indexName seq=${infoCursor.getInt(0)} " +
                                        "cid=${infoCursor.getInt(1)} name=${infoCursor.getString(2)}",
                                )
                            }
                        }
                    }
                }
                database.query("PRAGMA foreign_key_list(${quoteSqlIdentifier(table)})").use { foreignCursor ->
                    while (foreignCursor.moveToNext()) {
                        log(
                            "foreign_key table=$table id=${foreignCursor.getInt(0)} seq=${foreignCursor.getInt(1)} " +
                                "table=${foreignCursor.getString(2)} from=${foreignCursor.getString(3)} " +
                                "to=${foreignCursor.getString(4)} on_update=${foreignCursor.getString(5)} " +
                                "on_delete=${foreignCursor.getString(6)} match=${foreignCursor.getString(7)}",
                        )
                    }
                }
            }
        }
    }

    private fun quoteSqlIdentifier(identifier: String): String =
        "'${identifier.replace("'", "''")}'"

    private fun openRequeryDatabase(context: Context, file: File): RequerySQLiteDatabase {
        val configuration = SQLiteDatabaseConfiguration(
            file.absolutePath,
            RequerySQLiteDatabase.OPEN_READWRITE,
            emptyList(),
            emptyList(),
            listOf(
                SQLiteCustomExtension(
                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                    null,
                ),
            ),
        )
        return RequerySQLiteDatabase.openDatabase(configuration, null, null)
    }

    private fun writeLegacyDatabaseArchive(
        archive: File,
        main: File,
    ) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("rikka_hub.db"))
            main.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun writeLegacyDatabaseArchive(
        archive: File,
        main: File,
        wal: File,
        shm: File,
    ) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf(
                "rikka_hub.db" to main,
                "rikka_hub-wal" to wal,
                "rikka_hub-shm" to shm,
            ).forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
