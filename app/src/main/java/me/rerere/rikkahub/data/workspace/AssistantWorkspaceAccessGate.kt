package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import java.util.concurrent.ConcurrentHashMap

class WorkspaceAccessRevokedException(message: String) : IllegalStateException(message)

/** Serializes capability use and revocation for each assistant. */
class AssistantWorkspaceAccessGate internal constructor(
    private val readConversationOwner: suspend (String) -> String?,
    private val readAssistantBinding: suspend (String) -> String?,
) {
    constructor(settingsStore: SettingsStore, conversationDao: ConversationDAO) : this(
        readConversationOwner = conversationDao::getAssistantId,
        readAssistantBinding = { assistantId ->
            settingsStore.readPersistedSettingsFailFast().assistants
                .find { it.id.toString() == assistantId }
                ?.workspaceId
                ?.toString()
        },
    )

    private val gates = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withWorkspaceAccess(
        assistantId: String,
        conversationId: String,
        workspaceId: String,
        block: suspend () -> T,
    ): T = gate(assistantId).withLock {
        val owner = readConversationOwner(conversationId)
        if (owner != assistantId) {
            throw WorkspaceAccessRevokedException("Conversation workspace owner changed")
        }
        val binding = readAssistantBinding(assistantId)
        if (binding != workspaceId) {
            throw WorkspaceAccessRevokedException("Assistant workspace binding changed")
        }
        block()
    }

    suspend fun <T> withAssistantMutation(assistantId: String, block: suspend () -> T): T =
        gate(assistantId).withLock { block() }

    suspend fun <T> withAssistantMutations(assistantIds: Collection<String>, block: suspend () -> T): T {
        val ordered = assistantIds.distinct().sorted()
        suspend fun acquire(index: Int): T = if (index == ordered.size) {
            block()
        } else {
            gate(ordered[index]).withLock { acquire(index + 1) }
        }
        return acquire(0)
    }

    private fun gate(assistantId: String): Mutex = gates.computeIfAbsent(assistantId) { Mutex() }
}

/** Keeps the data layer independent from ChatService while allowing capability revocation. */
class AssistantWorkspaceSessionInvalidator {
    @Volatile
    private var handler: (suspend (String) -> Unit)? = null

    fun register(handler: suspend (String) -> Unit) {
        this.handler = handler
    }

    suspend fun invalidate(assistantId: String) {
        handler?.invoke(assistantId)
    }
}
