package me.rerere.rikkahub.ui.pages.backup.tabs

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.BackupPhase
import me.rerere.rikkahub.data.workspace.BackupTimingSession
import me.rerere.rikkahub.data.workspace.timedBackupPhase
import me.rerere.rikkahub.data.workspace.withBackupTiming
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(vm: BackupVM) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf("local") }
    var baseFile by remember { mutableStateOf<File?>(null) }
    var isChoosingBase by remember { mutableStateOf(false) }
    var timingText by remember { mutableStateOf<String?>(null) }
    val busy = isExporting || isRestoring || isChoosingBase
    DisposableEffect(Unit) { onDispose { baseFile?.delete() } }

    fun startExport(targetUri: Uri) {
        if (isExporting || isRestoring || isChoosingBase) return
        isExporting = true
        scope.launch {
            try {
                withBackupTiming(context.filesDir, if (baseFile == null) "EXPORT" else "EXPORT_INCREMENTAL") {
                    var exportFile: File? = null
                    var status = "SUCCESS"
                    try {
                        val generatedFile = vm.exportToFile(baseFile)
                        exportFile = generatedFile
                        timedBackupPhase(BackupPhase.DESTINATION_COPY, bytes = generatedFile.length(), files = 1) {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                                    FileInputStream(generatedFile).use { inputStream ->
                                        copyBackupStream(inputStream, outputStream)
                                    }
                                } ?: error("Unable to open selected export destination")
                            }
                        }
                        toaster.show(context.getString(R.string.backup_page_backup_success), type = ToastType.Success)
                    } catch (error: Throwable) {
                        status = if (error is CancellationException) "CANCELLED" else "FAILED"
                        if (error is CancellationException) throw error
                        error.printStackTrace()
                        toaster.show(
                            context.getString(R.string.backup_page_backup_failed, error.message ?: ""),
                            type = ToastType.Error,
                        )
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            timedBackupPhase(BackupPhase.CLEANUP) { exportFile?.let(::cleanupTemporaryFile) }
                            currentCoroutineContext()[BackupTimingSession]?.finish(status)
                        }
                    }
                }
            } finally { isExporting = false }
        }
    }

    fun startLocalRestore(sourceUri: Uri) {
        if (isExporting || isRestoring || isChoosingBase) return
        isRestoring = true
        scope.launch {
            try {
                withBackupTiming(context.filesDir, if (baseFile == null) "IMPORT" else "IMPORT_INCREMENTAL") {
                    val tempFile = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                    val partialFile = File(tempFile.path + ".partial")
                    var status = "SUCCESS"
                    try {
                        timedBackupPhase(BackupPhase.SOURCE_COPY, files = 1) {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(partialFile).use { outputStream ->
                                        copyBackupStream(inputStream, outputStream)
                                    }
                                } ?: error("Unable to open selected backup")
                                require(partialFile.renameTo(tempFile)) { "Unable to finalize selected backup" }
                            }
                        }
                        vm.restoreFromLocalFile(tempFile, password = null, baseFile = baseFile)
                        vm.restoreWarnings.value.takeIf { it.isNotEmpty() }?.let { warnings ->
                            toaster.show(warnings.joinToString("; "), type = ToastType.Warning)
                        }
                        toaster.show(context.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                    } catch (error: Throwable) {
                        status = if (error is CancellationException) "CANCELLED" else "FAILED"
                        if (error is CancellationException) throw error
                        error.printStackTrace()
                        toaster.show(
                            context.getString(R.string.backup_page_restore_failed, error.message ?: ""),
                            type = ToastType.Error,
                        )
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            timedBackupPhase(BackupPhase.CLEANUP) {
                                cleanupTemporaryFile(partialFile)
                                cleanupTemporaryFile(tempFile)
                            }
                            currentCoroutineContext()[BackupTimingSession]?.finish(status)
                        }
                    }
                }
            } finally { isRestoring = false }
        }
    }

    // The document picker returns before any archive bytes are created/read, so a cancelled pick
    // never starts a backup.
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let(::startExport)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            if (importType == "local") {
                startLocalRestore(sourceUri)
            } else {
                scope.launch {
                    isRestoring = true
                    val tempFile = File(
                        context.cacheDir,
                        "temp_${importType}_${System.currentTimeMillis()}.${if (importType == "chatbox") "json" else "zip"}",
                    )
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    copyBackupStream(inputStream, outputStream)
                                }
                            } ?: error("Unable to open selected file")
                        }
                        if (importType == "chatbox") {
                            vm.restoreFromChatBox(tempFile)
                        } else {
                            vm.restoreFromCherryStudio(tempFile)
                        }
                        toaster.show(
                            context.getString(R.string.backup_page_restore_success),
                            type = ToastType.Success,
                        )
                    } catch (error: Throwable) {
                        error.printStackTrace()
                        toaster.show(
                            context.getString(R.string.backup_page_restore_failed, error.message ?: ""),
                            type = ToastType.Error,
                        )
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            cleanupTemporaryFile(tempFile)
                        }
                        isRestoring = false
                    }
                }
            }
        }
    }

    val exportTimingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(vm.localBackupTimings(context.filesDir))
                    } ?: error("Unable to open selected timing destination")
                }
                toaster.show("计时记录已导出", type = ToastType.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                toaster.show("计时记录导出失败：${error.message.orEmpty()}", type = ToastType.Error)
            }
        }
    }

    val baseDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            isChoosingBase = true
            val selected = File(context.cacheDir, "backup_base_${java.util.UUID.randomUUID()}.zip")
            var accepted = false
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        selected.outputStream().use { output -> copyBackupStream(input, output) }
                    } ?: error("Unable to open selected base backup")
                    vm.verifyFullBase(selected)
                }
                baseFile?.let(::cleanupTemporaryFile)
                baseFile = selected
                accepted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                toaster.show(error.message ?: "无法读取完整基础备份", type = ToastType.Error)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { if (!accepted) cleanupTemporaryFile(selected) }
                isChoosingBase = false
            }
        }
    }
    timingText?.let { text ->
        AlertDialog(
            onDismissRequest = { timingText = null },
            title = { Text("本地备份计时") },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { timingText = null }) { Text("关闭") } },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!busy) ({ baseDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) else null,
                    headlineContent = { Text("选择完整基础备份") },
                    supportingContent = { Text(if (isChoosingBase) "正在检查基础备份" else if (baseFile == null) "未选择：导出完整备份" else "已选择：导出差分备份，并用于差分恢复") },
                )
                if (baseFile != null) item(
                    onClick = if (!busy) ({ baseFile?.let(::cleanupTemporaryFile); baseFile = null }) else null,
                    headlineContent = { Text("取消基础备份") },
                )
                item(
                    onClick = if (!busy) ({ scope.launch { timingText = withContext(Dispatchers.IO) { vm.localBackupTimings(context.filesDir) } } }) else null,
                    headlineContent = { Text("本地备份计时") },
                )
                item(
                    onClick = if (!busy) ({ exportTimingsLauncher.launch("backup-timings.json") }) else null,
                    headlineContent = { Text("导出备份耗时记录") },
                    leadingContent = { Icon(HugeIcons.File01, null) },
                )
                item(
                    onClick = if (!busy) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("黑猫ai_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!busy) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(arrayOf("application/octet-stream", "application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!busy) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!busy) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }
}

private fun cleanupTemporaryFile(file: File) {
    if (!file.exists()) return
    require(file.delete()) { "Unable to clean temporary restore file: ${file.path}" }
}
