package me.rerere.rikkahub.data.workspace

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase as RequerySQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Device regression coverage for the schema 31 restore gate.
 *
 * The archive service validates a staged database against the exported schema of the current Room
 * version. When the database moved from 30 to 31 the gate still asked for schema 30, so every 31
 * archive failed. These tests migrate a real database with [Migration_30_31], archive it, and run
 * the production gate: the migrated database must be accepted, and a database carrying an extra
 * table or index must still be rejected.
 */
@RunWith(AndroidJUnit4::class)
class RoomSchema31ArchiveGateInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratedRoom31ArchiveWithAptPlansPassesTheGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "archive-gate31-${System.nanoTime()}"
        val staging = newStaging(context, databaseName)
        val archive = File(staging, "rikka_hub.zip")
        try {
            createDatabaseAt30(databaseName)
            migrateTo31(databaseName)
            tamper(
                context,
                databaseName,
                listOf(
                    "INSERT INTO `workspace_apt_plans` (" +
                        "`plan_id`, `workspace_id`, `assistant_id`, `conversation_id`, `action`, " +
                        "`packages_json`, `base_fingerprint`, `plan_fingerprint`, `summary_json`, " +
                        "`created_at`, `expires_at`, `state`" +
                        ") VALUES ('plan-1', 'workspace-a', 'assistant-a', 'conversation-a', 'install', " +
                        "'[\"htop\"]', 'base-1', 'plan-1', '{}', 1, 1000, 'PLANNED')",
                ),
            )
            assertTrue(
                "The migrated database does not carry the schema 31 table",
                tableExists(context, databaseName, "workspace_apt_plans"),
            )

            writeLegacyArchive(context, databaseName, staging, archive)
            verifyBackup(archive)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun room30ArchiveIsMigratedTo31AndPassesTheGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "archive-gate31-from30-${System.nanoTime()}"
        val staging = newStaging(context, databaseName)
        val archive = File(staging, "rikka_hub.zip")
        try {
            // The legacy restore path migrates the staged copy to the current schema, which is
            // schema 31 now: the migration list has to carry Migration_30_31.
            createDatabaseAt30(databaseName)
            writeLegacyArchive(context, databaseName, staging, archive)
            verifyBackup(archive)
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun migratedRoom31DatabaseWithAnExtraTableIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "archive-gate31-extra-table-${System.nanoTime()}"
        val staging = newStaging(context, databaseName)
        val archive = File(staging, "rikka_hub.zip")
        try {
            createDatabaseAt30(databaseName)
            migrateTo31(databaseName)
            tamper(
                context,
                databaseName,
                listOf("CREATE TABLE restore_attack (payload TEXT NOT NULL)"),
            )
            writeLegacyArchive(context, databaseName, staging, archive)

            val failure = runCatching { verifyBackup(archive) }.exceptionOrNull()
            assertTrue("A database with an extra table was accepted", failure != null)
            assertTrue(
                "The rejection does not name the extra table: ${failure?.message}",
                failure?.message?.contains("restore_attack") == true,
            )
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun migratedRoom31DatabaseWithAnExtraIndexIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "archive-gate31-extra-index-${System.nanoTime()}"
        val staging = newStaging(context, databaseName)
        val archive = File(staging, "rikka_hub.zip")
        try {
            createDatabaseAt30(databaseName)
            migrateTo31(databaseName)
            tamper(
                context,
                databaseName,
                listOf("CREATE INDEX index_ConversationEntity_legacy ON ConversationEntity (title)"),
            )
            writeLegacyArchive(context, databaseName, staging, archive)

            val failure = runCatching { verifyBackup(archive) }.exceptionOrNull()
            assertTrue("A database with an extra index was accepted", failure != null)
            assertTrue(
                "The rejection does not name the extra index: ${failure?.message}",
                failure?.message?.contains("index_ConversationEntity_legacy") == true,
            )
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun migratedRoom31DatabaseWithAnAlteredAptPlanColumnIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "archive-gate31-altered-column-${System.nanoTime()}"
        val staging = newStaging(context, databaseName)
        val archive = File(staging, "rikka_hub.zip")
        try {
            createDatabaseAt30(databaseName)
            migrateTo31(databaseName)
            tamper(
                context,
                databaseName,
                listOf(
                    "ALTER TABLE `workspace_apt_plans` ADD COLUMN legacy_column TEXT",
                ),
            )
            writeLegacyArchive(context, databaseName, staging, archive)

            val failure = runCatching { verifyBackup(archive) }.exceptionOrNull()
            assertTrue("A database with an extra column was accepted", failure != null)
            assertTrue(
                "The rejection does not name the extra column: ${failure?.message}",
                failure?.message?.contains("legacy_column") == true,
            )
        } finally {
            context.deleteDatabase(databaseName)
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun newStaging(context: Context, name: String): File {
        val staging = File(context.cacheDir, "$name-staging")
        assertTrue(staging.mkdirs())
        return staging
    }

    private fun createDatabaseAt30(databaseName: String) {
        helper.createDatabase(databaseName, 30).close()
    }

    private fun migrateTo31(databaseName: String) {
        helper.runMigrationsAndValidate(
            databaseName,
            31,
            true,
            Migration_30_31,
        ).close()
    }

    private fun tamper(context: Context, databaseName: String, statements: List<String>) {
        val file = context.getDatabasePath(databaseName)
        openRequeryDatabase(context, file).use { database ->
            statements.forEach(database::execSQL)
        }
    }

    private fun tableExists(context: Context, databaseName: String, table: String): Boolean =
        openRequeryDatabase(context, context.getDatabasePath(databaseName)).use { database ->
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor -> cursor.moveToFirst() }
        }

    private fun writeLegacyArchive(
        context: Context,
        databaseName: String,
        staging: File,
        archive: File,
    ) {
        val source = context.getDatabasePath(databaseName)
        val main = File(staging, "rikka_hub")
        source.copyTo(main, overwrite = true)
        val wal = File(source.parentFile, "$databaseName-wal").takeIf { it.isFile }
        val shm = File(source.parentFile, "$databaseName-shm").takeIf { it.isFile }
        val stagedWal = wal?.let { File(staging, "rikka_hub-wal").also { target -> it.copyTo(target, overwrite = true) } }
        val stagedShm = shm?.let { File(staging, "rikka_hub-shm").also { target -> it.copyTo(target, overwrite = true) } }

        ZipOutputStream(archive.outputStream()).use { zip ->
            val entries = buildList {
                add("rikka_hub.db" to main)
                stagedWal?.let { add("rikka_hub-wal" to it) }
                stagedShm?.let { add("rikka_hub-shm" to it) }
            }
            entries.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun verifyBackup(archive: File) {
        GlobalContext.get().get<WorkspaceArchiveService>().verifyBackupFile(archive, allowLegacy = true)
    }

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
}
