package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.ConversationWorkspaceReference
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

sealed interface AssistantWorkspaceBindingResult {
    data class Success(val assistant: Assistant) : AssistantWorkspaceBindingResult
    data class Conflict(val currentWorkspaceId: Uuid?) : AssistantWorkspaceBindingResult
    data object AssistantNotFound : AssistantWorkspaceBindingResult
    data object WorkspaceNotFound : AssistantWorkspaceBindingResult
    data object Failed : AssistantWorkspaceBindingResult
}

internal data class AssistantWorkspaceReconciliation(
    val assistants: List<Assistant>,
    val finalBindings: Map<String, String?>,
)

internal fun affectedAssistantIdsForSettingsReplacement(
    current: Settings,
    replacement: Settings,
): List<String> {
    val currentBindings = current.assistants.associate { it.id to it.workspaceId }
    val replacementBindings = replacement.assistants.associate { it.id to it.workspaceId }
    return (currentBindings.keys + replacementBindings.keys)
        .filter { assistantId ->
            assistantId !in currentBindings ||
                assistantId !in replacementBindings ||
                currentBindings[assistantId] != replacementBindings[assistantId]
        }
        .map { it.toString() }
        .sorted()
}

internal fun decideAssistantWorkspaceReconciliation(
    assistants: List<Assistant>,
    references: List<ConversationWorkspaceReference>,
    validWorkspaceIds: Set<String>,
): AssistantWorkspaceReconciliation {
    val referencesByAssistant = references.groupBy { it.assistantId }
    val reconciled = assistants.map { assistant ->
        val existing = assistant.workspaceId?.toString()?.takeIf(validWorkspaceIds::contains)
        val finalId = existing ?: referencesByAssistant[assistant.id.toString()]
            .orEmpty()
            .mapNotNull { it.workspaceId?.takeIf(validWorkspaceIds::contains) }
            .distinct()
            .singleOrNull()
        assistant.copy(workspaceId = finalId?.let(Uuid::parse))
    }
    return AssistantWorkspaceReconciliation(
        assistants = reconciled,
        finalBindings = reconciled.associate { it.id.toString() to it.workspaceId?.toString() },
    )
}

/** Owns assistant-level workspace creation, mutation, deletion cleanup, and legacy reconciliation. */
class AssistantWorkspaceBindingCoordinator(
    private val settingsStore: SettingsStore,
    private val workspaceDao: WorkspaceDAO,
    private val conversationDao: ConversationDAO,
    private val catalogMutationLock: WorkspaceCatalogMutationLock,
    private val accessGate: AssistantWorkspaceAccessGate,
    private val sessionInvalidator: AssistantWorkspaceSessionInvalidator,
) {
    suspend fun createAssistant(assistant: Assistant): AssistantWorkspaceBindingResult =
        catalogMutationLock.withLock {
            val workspaceId = assistant.workspaceId?.toString()
            if (workspaceId != null && workspaceDao.getById(workspaceId) == null) {
                return@withLock AssistantWorkspaceBindingResult.WorkspaceNotFound
            }
            var duplicate = false
            settingsStore.transformLatest { settings ->
                duplicate = settings.assistants.any { it.id == assistant.id }
                if (duplicate) settings else settings.copy(assistants = settings.assistants + assistant)
            }
            if (duplicate) {
                AssistantWorkspaceBindingResult.Conflict(
                    settingsStore.readPersistedSettingsFailFast()
                        .getAssistantById(assistant.id)?.workspaceId,
                )
            } else {
                AssistantWorkspaceBindingResult.Success(assistant)
            }
        }

    suspend fun removeAssistantAndQuiesce(
        assistantId: Uuid,
        cleanup: suspend (Assistant) -> Unit,
    ): Assistant? = catalogMutationLock.withLock {
        accessGate.withAssistantMutation(assistantId.toString()) {
            val removed = settingsStore.readPersistedSettingsFailFast()
                .getAssistantById(assistantId)
                ?: return@withAssistantMutation null

            sessionInvalidator.invalidate(assistantId.toString())
            conversationDao.clearWorkspaceCwdForAssistant(assistantId.toString())
            var removedFromLatest = false
            settingsStore.transformLatest { latest ->
                if (latest.assistants.none { it.id == assistantId }) {
                    latest
                } else {
                    removedFromLatest = true
                    latest.copy(assistants = latest.assistants.filter { it.id != assistantId })
                }
            }
            if (!removedFromLatest) return@withAssistantMutation null

            cleanup(removed)
            removed
        }
    }

    suspend fun setBinding(
        assistantId: Uuid,
        expectedCurrent: Uuid?,
        workspaceId: Uuid?,
    ): AssistantWorkspaceBindingResult = catalogMutationLock.withLock {
        accessGate.withAssistantMutation(assistantId.toString()) mutation@{
            if (workspaceId != null && workspaceDao.getById(workspaceId.toString()) == null) {
                return@mutation AssistantWorkspaceBindingResult.WorkspaceNotFound
            }

            val current = settingsStore.readPersistedSettingsFailFast()
                .getAssistantById(assistantId)
                ?: return@mutation AssistantWorkspaceBindingResult.AssistantNotFound
            if (current.workspaceId != expectedCurrent) {
                return@mutation AssistantWorkspaceBindingResult.Conflict(current.workspaceId)
            }
            if (current.workspaceId == workspaceId) {
                return@mutation AssistantWorkspaceBindingResult.Success(current)
            }

            sessionInvalidator.invalidate(assistantId.toString())
            conversationDao.clearWorkspaceCwdForAssistant(assistantId.toString())
            var updatedAssistant: Assistant? = null
            settingsStore.transformLatest { latest ->
                val latestAssistant = latest.getAssistantById(assistantId)
                    ?: return@transformLatest latest
                if (latestAssistant.workspaceId != expectedCurrent) {
                    return@transformLatest latest
                }
                latest.copy(
                    assistants = latest.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(workspaceId = workspaceId).also { updatedAssistant = it }
                        } else {
                            assistant
                        }
                    }
                )
            }
            updatedAssistant?.let(AssistantWorkspaceBindingResult::Success)
                ?: AssistantWorkspaceBindingResult.Conflict(
                    settingsStore.readPersistedSettingsFailFast()
                        .getAssistantById(assistantId)?.workspaceId,
                )
        }
    }

    /**
     * The caller already owns the catalog lock. Assistant gates are acquired before the caller
     * takes any workspace-root lock, and cleanup happens only after deletion succeeds.
     */
    internal suspend fun <T> clearDeletedWorkspaceWhileLocked(
        workspaceId: String,
        deleteWorkspace: suspend () -> T?,
    ): T? {
        val before = settingsStore.readPersistedSettingsFailFast()
        val affected = before.assistants
            .filter { it.workspaceId?.toString() == workspaceId }
        return accessGate.withAssistantMutations(affected.map { it.id.toString() }) {
            affected.forEach {
                sessionInvalidator.invalidate(it.id.toString())
                conversationDao.clearWorkspaceCwdForAssistant(it.id.toString())
            }
            settingsStore.transformLatest { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.workspaceId?.toString() == workspaceId) {
                            assistant.copy(workspaceId = null)
                        } else {
                            assistant
                        }
                    }
                )
            }
            try {
                deleteWorkspace()
            } catch (error: Throwable) {
                // Restore bindings only while the catalog row still exists. If the row was
                // deleted and filesystem cleanup failed, restoring would create dangling refs.
                if (workspaceDao.getById(workspaceId) != null) {
                    val oldBindings = affected.associate { it.id to it.workspaceId }
                    settingsStore.transformLatest { settings ->
                        settings.copy(
                            assistants = settings.assistants.map { assistant ->
                                oldBindings[assistant.id]?.let { assistant.copy(workspaceId = it) }
                                    ?: assistant
                            }
                        )
                    }
                }
                throw error
            }
        }
    }

    /**
     * Called only while the catalog lock is already held. It acquires assistant gates before
     * workspace-root locks, revokes stale tools, and supplies the latest persisted rollback base.
     */
    internal suspend fun <T> quiesceForSettingsReplacementWhileLocked(
        replacement: Settings,
        additionalAssistantIds: Collection<String> = emptyList(),
        block: suspend (current: Settings) -> T,
    ): T {
        val initial = settingsStore.readPersistedSettingsFailFast()
        val initialAffected = (
            affectedAssistantIdsForSettingsReplacement(initial, replacement) + additionalAssistantIds
        ).distinct().sorted()
        return accessGate.withAssistantMutations(initialAffected) {
            val current = settingsStore.readPersistedSettingsFailFast()
            val affected = (
                affectedAssistantIdsForSettingsReplacement(current, replacement) + additionalAssistantIds
            ).distinct().sorted()
            check(affected.all(initialAffected::contains)) {
                "Assistant bindings changed without the workspace catalog lock"
            }
            affected.forEach { assistantId ->
                sessionInvalidator.invalidate(assistantId)
                conversationDao.clearWorkspaceCwdForAssistant(assistantId)
            }
            block(current)
        }
    }

    suspend fun reconcileLegacyBindings() = catalogMutationLock.withLock {
        val settings = settingsStore.readPersistedSettingsFailFast()
        val references = conversationDao.getLegacyWorkspaceReferences()
        val decision = decideAssistantWorkspaceReconciliation(
            assistants = settings.assistants,
            references = references,
            validWorkspaceIds = workspaceDao.getAll().mapTo(mutableSetOf()) { it.id },
        )

        // Persist authoritative assistant bindings before consuming their only legacy input.
        if (decision.assistants != settings.assistants) {
            settingsStore.transformLatest { latest ->
                val decidedById = decision.assistants.associateBy { it.id }
                latest.copy(
                    assistants = latest.assistants.map { assistant ->
                        val decided = decidedById[assistant.id]
                        if (decided == null) assistant else assistant.copy(workspaceId = decided.workspaceId)
                    }
                )
            }
        }

        // Once every legacy ID has been consumed, Room reconciliation is a true no-op. This
        // keeps a CWD proven safe on the first pass while still clearing invalid assistant IDs.
        if (references.isEmpty()) return@withLock

        conversationDao.consumeAllLegacyWorkspaceReferences(decision.finalBindings)
    }
}
