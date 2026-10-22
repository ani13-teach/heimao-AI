package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.log.ChatLogStore
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceBindingCoordinator
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveCleanup
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveRecovery
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveService
import me.rerere.rikkahub.data.workspace.WorkspaceSnapshotRecovery
import me.rerere.rikkahub.data.workspace.WorkspaceSnapshotService
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"

class RikkaHubApp : Application() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        // Compose 1.12 的 Android View 焦点接管路径会在 AndroidView 被移除时同步发起二维焦点搜索,
        // 越界搜索会强制重新测量 LazyColumn, 在 applyChanges 期间重入 compose,
        // 抛出 "pending composition has not been applied" 崩溃 (Google issue 469958424).
        // 关掉这个开关可绕开该路径. 官方仍未修复, 这个标记是 Google 自己给出的临时办法.
        // 必须在 super.onCreate() 之前设置: Compose UI 可能在此之前被初始化.
        ComposeUiFlags.isBypassUnfocusableComposeViewEnabled = false
        super.onCreate()
        // The disposable interpreter must not open Room or run restore/cleanup transactions.
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").inputStream().use { input ->
                input.readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
            }
        }
        if (processName == "$packageName:workspace_python") return

        // Recover the database and workspace root before Koin can open a half-restored Room
        // graph. A failure is intentionally allowed to escape and stop normal startup.
        var recoveryOutcome = WorkspaceArchiveRecovery.recoverBeforeKoin(this)
        // Snapshot recovery is a separate local transaction. Its filesystem half is repaired
        // before Room opens, while the Room status half is repaired immediately after Koin.
        WorkspaceSnapshotRecovery.recoverBeforeKoin(this)
        if (recoveryOutcome.settingsNeedsCompensation) {
            // This is deliberately the only pre-Koin DataStore/journal phase. It restores the
            // exact old settings file before eager SettingsStore/ChatNotificationManager creation.
            runBlocking(Dispatchers.IO) {
                recoveryOutcome = WorkspaceArchiveRecovery.compensatePendingSettingsBeforeKoin(
                    this@RikkaHubApp
                )
            }
        }

        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }

        // Do this synchronously and before checkWorkspaceIntegrity: integrity must observe only
        // READY/FAILED snapshot rows and a directory exchange whose journal is already settled.
        runBlocking(Dispatchers.IO) {
            get<WorkspaceArchiveService>().recoverPendingIncrementalRoomRollback(
                recoveryOutcome.incrementalWorkspaceIdsToDelete
            )
            get<WorkspaceSnapshotService>().recoverPendingTransaction()
            get<AssistantWorkspaceBindingCoordinator>().reconcileLegacyBindings()
        }

        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // Migrate user data before any cleanup can remove legacy runtime directories.
        val workspaceRecoverySucceeded = recoverWorkspaceStateBeforeCleanup()

        // Restore closes Room and exits before it can remove large recovery/staging trees. The
        // next process owns that cleanup after the new Room graph has been created.
        cleanupPendingArchiveArtifacts()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // 清掉过期的运行日志（只留 24 小时）
        pruneExpiredChatLogs()

        // Clean runtime temporary files only after every workspace migrated.
        if (workspaceRecoverySucceeded) cleanupWorkspaceTempDirs()

        // sync upload files to DB
        syncManagedFiles()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val updated = store.transformLatest { settings ->
                    settings.copy(launchCount = settings.launchCount + 1)
                }
                Log.i(TAG, "incrementLaunchCount: ${updated.launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupPendingArchiveArtifacts() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                WorkspaceArchiveCleanup.cleanupPending(this@RikkaHubApp)
            }.onFailure {
                Log.e(TAG, "cleanupPendingArchiveArtifacts failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    /** Migrates workspace data synchronously before runtime cleanup and tool exposure. */
    private fun recoverWorkspaceStateBeforeCleanup(): Boolean =
        runBlocking(Dispatchers.IO) {
            val repository = get<WorkspaceRepository>()
            runCatching {
                repository.migratePortableWorkspaces()
            }.onFailure {
                Log.e(TAG, "migratePortableWorkspaces failed", it)
            }.getOrDefault(false)
        }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun pruneExpiredChatLogs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<ChatLogStore>().pruneExpired()
            }.onFailure {
                Log.e(TAG, "pruneExpiredChatLogs failed", it)
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
