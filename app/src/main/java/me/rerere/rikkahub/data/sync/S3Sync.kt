package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveRestoreResult
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveService
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant
import java.util.UUID

private const val TAG = "S3Sync"

/** S3 transport only; all archive semantics are shared with local export and WebDAV. */
class S3Sync(
    private val context: Context,
    private val httpClient: HttpClient,
    private val archiveService: WorkspaceArchiveService,
) {
    private fun getS3Client(config: S3Config): S3Client = S3Client(config, httpClient)

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        getS3Client(config).listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config, password: CharArray) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, password)
        var failure: Throwable? = null
        try {
            getS3Client(config).putObject(
                key = "rikkahub_backups/${file.name}",
                file = file,
                contentType = "application/octet-stream",
            ).getOrThrow()
            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                cleanupTemporaryFile(file, failure)
            }
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        getS3Client(config).listObjects(prefix = "rikkahub_backups/", maxKeys = 1000).getOrThrow()
            .objects
            .filter {
                it.key.startsWith("rikkahub_backups/backup_") &&
                    (it.key.endsWith(".zip.enc") || it.key.endsWith(".zip"))
            }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast('/'),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(
        config: S3Config,
        item: S3BackupItem,
        password: CharArray? = null,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult = withContext(Dispatchers.IO) {
            val backupFile = File(context.cacheDir, ".s3-restore-${UUID.randomUUID()}.partial")
            var failure: Throwable? = null
            try {
                Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
                getS3Client(config).downloadObjectToFile(item.key, backupFile).getOrThrow()
                val result = archiveService.restoreBackupFile(
                    file = backupFile,
                    includeDatabase = S3Config.BackupItem.DATABASE in config.items,
                    includeFiles = S3Config.BackupItem.FILES in config.items,
                    password = password,
                    allowLegacy = allowLegacy,
                )
                result.warnings.forEach { warning -> Log.w(TAG, warning) }
                result
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                withContext(NonCancellable) {
                    cleanupTemporaryFile(backupFile, failure)
                }
            }
        }

    private fun cleanupTemporaryFile(file: File, failure: Throwable?) {
        if (!file.exists()) return
        try {
            require(file.delete()) { "Unable to clean temporary backup file: ${file.path}" }
        } catch (cleanupError: Throwable) {
            if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        getS3Client(config).deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(
        config: S3Config,
        password: CharArray,
    ): File = withContext(Dispatchers.IO) {
        archiveService.createBackupFile(
            includeDatabase = S3Config.BackupItem.DATABASE in config.items,
            includeFiles = S3Config.BackupItem.FILES in config.items,
            password = password,
        )
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
