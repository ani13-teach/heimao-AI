package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.WorkspaceArchiveEncryption
import androidx.compose.ui.res.stringResource

@Composable
fun BackupPasswordDialog(
    initialPassword: String = "",
    allowRemember: Boolean = false,
    rememberByDefault: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (password: String, remember: Boolean) -> Unit,
) {
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember(rememberByDefault) { mutableStateOf(rememberByDefault) }

    AlertDialog(
        onDismissRequest = {
            password = ""
            onDismiss()
        },
        title = { Text(stringResource(R.string.backup_page_encryption_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.backup_page_encryption_password_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_page_encryption_password)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.backup_page_encryption_password_min_length,
                                WorkspaceArchiveEncryption.MIN_PASSWORD_LENGTH,
                            )
                        )
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                if (passwordVisible) {
                                    stringResource(R.string.backup_page_password_hide)
                                } else {
                                    stringResource(R.string.backup_page_password_show)
                                }
                            )
                        }
                    },
                )
                if (allowRemember) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                        )
                        Text(
                            text = stringResource(R.string.backup_page_remember_encryption_password),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.length >= WorkspaceArchiveEncryption.MIN_PASSWORD_LENGTH,
                onClick = {
                    val submitted = password
                    password = ""
                    onConfirm(submitted, rememberPassword)
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password = ""
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun LegacyBackupConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_legacy_title)) },
        text = { Text(stringResource(R.string.backup_page_legacy_desc)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_page_legacy_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
