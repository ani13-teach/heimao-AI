package me.rerere.rikkahub.data.workspace

/** Stable identity used when comparing the Room workspace catalog with archive metadata. */
internal data class WorkspaceArchiveWorkspaceKey(
    val id: String,
    val root: String,
)

/**
 * The cross-source state that must agree before a portable backup is accepted.
 *
 * Lists are intentionally retained instead of converted to sets so duplicate rows/references
 * cannot be hidden by the consistency check.
 */
internal data class WorkspaceArchiveCatalogConsistency(
    val stagedDatabaseWorkspaces: List<WorkspaceArchiveWorkspaceKey>,
    val manifestWorkspaces: List<WorkspaceArchiveWorkspaceKey>,
    val settingsAssistantReferences: List<WorkspaceAssistantReference>,
    val manifestAssistantReferences: List<WorkspaceAssistantReference>,
    val stagedDatabaseConversationReferences: List<WorkspaceConversationReference>,
    val manifestConversationReferences: List<WorkspaceConversationReference>,
    val actualDirectoryRoots: Set<String>,
)

/**
 * Returns true only when every workspace identity and every workspace-bearing reference agrees
 * across the stable database snapshot, manifest, settings, conversation rows, and directories.
 */
internal fun workspaceArchiveCatalogIsConsistent(
    state: WorkspaceArchiveCatalogConsistency,
): Boolean {
    val databaseWorkspaces = state.stagedDatabaseWorkspaces.sortedWith(WORKSPACE_KEY_ORDER)
    val manifestWorkspaces = state.manifestWorkspaces.sortedWith(WORKSPACE_KEY_ORDER)
    if (databaseWorkspaces != manifestWorkspaces) return false

    val databaseIds = databaseWorkspaces.map { it.id }
    val databaseRoots = databaseWorkspaces.map { it.root }
    if (
        databaseIds.size != databaseIds.toSet().size ||
        databaseRoots.size != databaseRoots.toSet().size
    ) {
        return false
    }

    val settingsReferences = state.settingsAssistantReferences.sortedWith(ASSISTANT_REFERENCE_ORDER)
    val manifestAssistantReferences =
        state.manifestAssistantReferences.sortedWith(ASSISTANT_REFERENCE_ORDER)
    if (settingsReferences != manifestAssistantReferences) return false
    if (settingsReferences.any {
            it.workspaceId != null && it.workspaceId !in databaseIds
        }
    ) {
        return false
    }

    val databaseConversationReferences =
        state.stagedDatabaseConversationReferences.sortedWith(CONVERSATION_REFERENCE_ORDER)
    val manifestConversationReferences =
        state.manifestConversationReferences.sortedWith(CONVERSATION_REFERENCE_ORDER)
    if (databaseConversationReferences != manifestConversationReferences) return false
    if (
        databaseConversationReferences.any { it.workspaceId != null } ||
        manifestConversationReferences.any { it.workspaceId != null }
    ) {
        return false
    }

    return state.actualDirectoryRoots == databaseRoots.toSet()
}

/** New archives retain conversation IDs only; workspace ownership lives in assistant settings. */
internal fun authoritativeArchiveConversationReferences(
    references: List<WorkspaceConversationReference>,
): List<WorkspaceConversationReference> = references.map { it.copy(workspaceId = null) }

/**
 * A restore that touched the Room database must never return control to the current process.
 * The status is included as a second guard for failure paths where Room was closed before a
 * database swap or rollback completed.
 */
internal fun shouldRestartWorkspaceArchive(
    databaseRestoreAttempted: Boolean,
    databaseStatus: WorkspaceArchiveDatabaseRestoreStatus,
): Boolean = databaseRestoreAttempted ||
    databaseStatus != WorkspaceArchiveDatabaseRestoreStatus.NOT_REQUESTED

private val WORKSPACE_KEY_ORDER = compareBy<WorkspaceArchiveWorkspaceKey>({ it.id }, { it.root })
private val ASSISTANT_REFERENCE_ORDER = compareBy<WorkspaceAssistantReference>(
    { it.assistantId },
    { it.workspaceId ?: "" },
)
private val CONVERSATION_REFERENCE_ORDER = compareBy<WorkspaceConversationReference>(
    { it.conversationId },
    { it.workspaceId ?: "" },
)
