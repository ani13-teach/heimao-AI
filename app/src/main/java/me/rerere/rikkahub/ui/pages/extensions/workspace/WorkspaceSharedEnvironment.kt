package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceSharedEnvironmentInfo
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import java.time.Instant
import me.rerere.rikkahub.utils.toLocalDateTime

/**
 * UI model for the public ("shared") Linux environment of the workspace catalog.
 *
 * [isShared] describes *this* workspace: it is public exactly when the shared environment lists it
 * as a referencing workspace.
 */
data class SharedEnvironmentUiState(
    val isShared: Boolean = false,
    val ready: Boolean = false,
    val occupiedBytes: Long? = null,
    val referencedWorkspaceIds: List<String> = emptyList(),
    val recoveryAvailable: Boolean = false,
    val recoveryAt: Long? = null,
)

/** One Python/Git probe result shown next to the management buttons. */
data class WorkspaceToolCheck(
    val name: String,
    val output: String,
    val ok: Boolean,
)

/**
 * The single adaptation point between the repository's environment report and this screen.
 *
 * Everything else in the UI reads [SharedEnvironmentUiState], so a repository-side rename is a
 * change to this one function.
 */
internal fun WorkspaceSharedEnvironmentInfo.toUiState(workspaceId: String): SharedEnvironmentUiState =
    SharedEnvironmentUiState(
        isShared = workspaceId in referencedWorkspaceIds,
        ready = ready,
        occupiedBytes = occupiedBytes,
        referencedWorkspaceIds = referencedWorkspaceIds,
        recoveryAvailable = recoveryAvailable,
        recoveryAt = recoveryAt,
    )

/**
 * Management entry for the public Linux environment: which environment this workspace uses, what
 * the shared rootfs occupies, who else uses it, whether a recovery point exists, and the actions
 * that change any of it.
 *
 * Every button calls the repository. The destructive ones (switch, recovery restore, maintenance
 * terminal) ask for confirmation first, because they are global to every referencing workspace.
 */
@Composable
internal fun WorkspaceSharedEnvironmentCard(
    state: SharedEnvironmentUiState,
    loading: Boolean,
    busy: Boolean,
    referencingWorkspaces: List<WorkspaceEntity>,
    toolChecks: List<WorkspaceToolCheck>,
    toolCheckRunning: Boolean,
    rootfsReady: Boolean,
    onSwitchToShared: () -> Unit,
    onSwitchToPrivate: () -> Unit,
    onRestoreRecovery: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onCheckTools: () -> Unit,
) {
    var confirmSwitchShared by remember { mutableStateOf(false) }
    var confirmSwitchPrivate by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmMaintenance by remember { mutableStateOf(false) }

    val referencingLabel = if (referencingWorkspaces.isEmpty()) {
        stringResource(R.string.workspace_shared_references_none)
    } else {
        referencingWorkspaces.joinToString(" · ", transform = { it.name })
    }
    val recoveryLabel = when {
        state.recoveryAt != null ->
            stringResource(R.string.workspace_shared_recovery_at, Instant.ofEpochMilli(state.recoveryAt).toLocalDateTime())

        state.recoveryAvailable -> stringResource(R.string.workspace_shared_recovery_available)
        else -> stringResource(R.string.workspace_shared_recovery_missing)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.workspace_shared_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.isShared) {
                        stringResource(R.string.workspace_shared_badge_shared)
                    } else {
                        stringResource(R.string.workspace_shared_badge_private)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.isShared) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(R.string.workspace_shared_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WorkspaceInfoRow(
                label = stringResource(R.string.workspace_shared_status),
                value = when {
                    loading -> stringResource(R.string.workspace_detail_loading)
                    state.ready -> stringResource(R.string.workspace_shared_status_installed)
                    else -> stringResource(R.string.workspace_shared_status_missing)
                },
            )
            WorkspaceInfoRow(
                label = stringResource(R.string.workspace_shared_occupied),
                value = state.occupiedBytes?.fileSizeToString()
                    ?: stringResource(R.string.workspace_shared_occupied_unknown),
            )
            WorkspaceInfoRow(
                label = stringResource(R.string.workspace_shared_references),
                value = referencingLabel,
                maxLines = 3,
            )
            WorkspaceInfoRow(
                label = stringResource(R.string.workspace_shared_recovery),
                value = recoveryLabel,
                maxLines = 2,
            )

            if (!state.isShared) {
                Button(
                    onClick = { confirmSwitchShared = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_shared_switch_to_shared))
                }
            } else {
                Button(
                    onClick = { confirmSwitchPrivate = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_shared_switch_to_private))
                }
            }

            OutlinedButton(
                onClick = { confirmRestore = true },
                enabled = !busy && state.recoveryAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workspace_shared_restore_recovery))
            }

            OutlinedButton(
                onClick = { confirmMaintenance = true },
                // Maintenance is exclusive to the public environment: it needs a public
                // environment that is installed and no other session running on it.
                enabled = !busy && state.isShared && rootfsReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workspace_shared_maintenance))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.workspace_shared_tools),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onCheckTools,
                    enabled = !toolCheckRunning && rootfsReady,
                ) {
                    Text(stringResource(R.string.workspace_shared_check_tools))
                }
            }
            toolChecks.forEach { check ->
                Text(
                    text = "${check.name}: ${check.output}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = confirmSwitchShared,
        title = stringResource(R.string.workspace_shared_switch_to_shared),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmSwitchShared = false
            onSwitchToShared()
        },
        onDismiss = { confirmSwitchShared = false },
    ) {
        Text(stringResource(R.string.workspace_shared_switch_to_shared_confirm))
    }

    RikkaConfirmDialog(
        show = confirmSwitchPrivate,
        title = stringResource(R.string.workspace_shared_switch_to_private),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmSwitchPrivate = false
            onSwitchToPrivate()
        },
        onDismiss = { confirmSwitchPrivate = false },
    ) {
        Text(stringResource(R.string.workspace_shared_switch_to_private_confirm))
    }

    RikkaConfirmDialog(
        show = confirmRestore,
        title = stringResource(R.string.workspace_shared_restore_recovery),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmRestore = false
            onRestoreRecovery()
        },
        onDismiss = { confirmRestore = false },
    ) {
        Text(stringResource(R.string.workspace_shared_restore_recovery_confirm))
    }

    RikkaConfirmDialog(
        show = confirmMaintenance,
        title = stringResource(R.string.workspace_shared_maintenance),
        confirmText = stringResource(R.string.common_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            confirmMaintenance = false
            onOpenMaintenance()
        },
        onDismiss = { confirmMaintenance = false },
    ) {
        Text(stringResource(R.string.workspace_shared_maintenance_confirm))
    }
}
