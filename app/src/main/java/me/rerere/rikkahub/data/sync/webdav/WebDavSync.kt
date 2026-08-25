package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveRestoreResult
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveService
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant
import java.util.UUID

private const val TAG = "WebDavSync"

/** WebDAV transport only; archive creation and restore live in [WorkspaceArchiveService]. */
class WebDavSync(
    private val context: Context,
    private val httpClient: HttpClient,
    private val archiveService: WorkspaceArchiveService,
) {
    private fun getClient(config: WebDavConfig): WebDavClient = WebDavClient(config, httpClient)

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        getClient(config).propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig, password: CharArray) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, password)
        var failure: Throwable? = null
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(
                path = file.name,
                file = file,
                contentType = "application/octet-stream",
            ).getOrThrow()
            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                cleanupTemporaryFile(file, failure)
            }
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.ensureCollectionExists().getOrThrow()
        client.list().getOrThrow()
            .filter {
                !it.isCollection && it.displayName.startsWith("backup_") &&
                    (it.displayName.endsWith(".zip.enc") || it.displayName.endsWith(".zip"))
            }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(
        config: WebDavConfig,
        item: WebDavBackupItem,
        password: CharArray? = null,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult = withContext(Dispatchers.IO) {
            val client = getClient(config)
            val backupFile = File(context.cacheDir, ".webdav-restore-${UUID.randomUUID()}.partial")
            var failure: Throwable? = null
            try {
                Log.i(TAG, "restore: Downloading ${item.displayName}")
                client.downloadToFile(item.displayName, backupFile).getOrThrow()
                val result = archiveService.restoreBackupFile(
                    file = backupFile,
                    includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
                    includeFiles = WebDavConfig.BackupItem.FILES in config.items,
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

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        getClient(config).delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        password: CharArray,
    ): File = withContext(Dispatchers.IO) {
        archiveService.createBackupFile(
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
            password = password,
        )
    }

    suspend fun restoreFromLocalFile(
        file: File,
        config: WebDavConfig,
        password: CharArray? = null,
        allowLegacy: Boolean = false,
    ): WorkspaceArchiveRestoreResult = withContext(Dispatchers.IO) {
        require(file.exists() && file.canRead()) { "Backup file does not exist or is unreadable" }
        val result = archiveService.restoreBackupFile(
            file = file,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
            password = password,
            allowLegacy = allowLegacy,
        )
        result.warnings.forEach { warning -> Log.w(TAG, warning) }
        result
    }

    private fun cleanupTemporaryFile(file: File, failure: Throwable?) {
        if (!file.exists()) return
        try {
            require(file.delete()) { "Unable to clean temporary backup file: ${file.path}" }
        } catch (cleanupError: Throwable) {
            if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
        }
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
