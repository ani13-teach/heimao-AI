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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.LegacyWorkspaceArchiveRequiresConfirmationException
import me.rerere.rikkahub.ui.pages.backup.components.BackupPasswordDialog
import me.rerere.rikkahub.ui.pages.backup.components.LegacyBackupConfirmationDialog
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
    var passwordAction by remember { mutableStateOf<String?>(null) }
    var passwordActionUri by remember { mutableStateOf<Uri?>(null) }
    var pendingLegacyRestore by remember { mutableStateOf<PendingLocalLegacyRestore?>(null) }
    var showLegacyConfirmation by remember { mutableStateOf(false) }

    fun requestPassword(action: String, uri: Uri) {
        passwordAction = action
        passwordActionUri = uri
    }

    fun startExport(targetUri: Uri, passwordText: String) {
        scope.launch {
            isExporting = true
            val password = passwordText.toCharArray()
            var exportFile: File? = null
            try {
                val generatedFile = vm.exportToFile(password)
                exportFile = generatedFile
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(generatedFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: error("Unable to open selected export destination")
                }
                toaster.show(
                    context.getString(R.string.backup_page_backup_success),
                    type = ToastType.Success,
                )
            } catch (error: Throwable) {
                error.printStackTrace()
                toaster.show(
                    context.getString(R.string.backup_page_backup_failed, error.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    exportFile?.let(::cleanupTemporaryFile)
                }
                password.fill('\u0000')
                isExporting = false
            }
        }
    }

    fun startLocalRestore(sourceUri: Uri, password: CharArray, allowLegacy: Boolean) {
        scope.launch {
            isRestoring = true
            var waitingForLegacyConfirmation = false
            val tempFile = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
            val partialFile = File(tempFile.path + ".partial")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(partialFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: error("Unable to open selected backup")
                    require(partialFile.renameTo(tempFile)) { "Unable to finalize selected backup" }
                }
                vm.restoreFromLocalFile(tempFile, password, allowLegacy)
                vm.restoreWarnings.value.takeIf { it.isNotEmpty() }?.let { warnings ->
                    toaster.show(warnings.joinToString("; "), type = ToastType.Warning)
                }
                toaster.show(
                    context.getString(R.string.backup_page_restore_success),
                    type = ToastType.Success,
                )
            } catch (legacy: LegacyWorkspaceArchiveRequiresConfirmationException) {
                if (allowLegacy) throw legacy
                waitingForLegacyConfirmation = true
                pendingLegacyRestore = PendingLocalLegacyRestore(sourceUri, password)
                showLegacyConfirmation = true
            } catch (error: Throwable) {
                error.printStackTrace()
                toaster.show(
                    context.getString(R.string.backup_page_restore_failed, error.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    cleanupTemporaryFile(partialFile)
                    cleanupTemporaryFile(tempFile)
                }
                if (!waitingForLegacyConfirmation) password.fill('\u0000')
                isRestoring = false
            }
        }
    }

    // The document picker returns before any archive bytes are created/read. A password is
    // collected first, so cancellation never leaves an archive plaintext temp behind.
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { requestPassword("export", it) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            if (importType == "local") {
                requestPassword("restore", sourceUri)
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
                                    inputStream.copyTo(outputStream)
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

    passwordAction?.let { action ->
        BackupPasswordDialog(
            onDismiss = {
                passwordAction = null
                passwordActionUri = null
            },
            onConfirm = { password, _ ->
                val uri = passwordActionUri
                passwordAction = null
                passwordActionUri = null
                if (uri == null) return@BackupPasswordDialog
                if (action == "export") {
                    startExport(uri, password)
                } else {
                    startLocalRestore(uri, password.toCharArray(), allowLegacy = false)
                }
            },
        )
    }

    if (showLegacyConfirmation) {
        LegacyBackupConfirmationDialog(
            onDismiss = {
                pendingLegacyRestore?.password?.fill('\u0000')
                pendingLegacyRestore = null
                showLegacyConfirmation = false
            },
            onConfirm = {
                val pending = pendingLegacyRestore ?: return@LegacyBackupConfirmationDialog
                pendingLegacyRestore = null
                showLegacyConfirmation = false
                startLocalRestore(pending.uri, pending.password, allowLegacy = true)
            },
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
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("黑猫ai_backup_$timestamp.zip.enc")
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
                    onClick = if (!isRestoring) {
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
                    onClick = if (!isRestoring) {
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
                    onClick = if (!isRestoring) {
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

private data class PendingLocalLegacyRestore(
    val uri: Uri,
    val password: CharArray,
)

private fun cleanupTemporaryFile(file: File) {
    if (!file.exists()) return
    require(file.delete()) { "Unable to clean temporary restore file: ${file.path}" }
}
