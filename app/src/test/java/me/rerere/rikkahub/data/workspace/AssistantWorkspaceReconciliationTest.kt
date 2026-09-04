package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.entity.ConversationWorkspaceReference
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantWorkspaceReconciliationTest {
    private val assistantId = Uuid.parse("10000000-0000-0000-0000-000000000001")
    private val workspaceA = Uuid.parse("20000000-0000-0000-0000-000000000001")
    private val workspaceB = Uuid.parse("20000000-0000-0000-0000-000000000002")

    @Test
    fun `settings replacement affects added removed and changed assistants`() {
        val unchanged = Assistant(id = assistantId, workspaceId = workspaceA)
        val changedId = Uuid.parse("10000000-0000-0000-0000-000000000002")
        val removedId = Uuid.parse("10000000-0000-0000-0000-000000000003")
        val addedId = Uuid.parse("10000000-0000-0000-0000-000000000004")

        val affected = affectedAssistantIdsForSettingsReplacement(
            current = me.rerere.rikkahub.data.datastore.Settings(
                assistants = listOf(
                    unchanged,
                    Assistant(id = changedId, workspaceId = workspaceA),
                    Assistant(id = removedId, workspaceId = workspaceB),
                )
            ),
            replacement = me.rerere.rikkahub.data.datastore.Settings(
                assistants = listOf(
                    unchanged,
                    Assistant(id = changedId, workspaceId = workspaceB),
                    Assistant(id = addedId, workspaceId = workspaceA),
                )
            ),
        )

        assertEquals(
            listOf(changedId.toString(), removedId.toString(), addedId.toString()),
            affected,
        )
    }

    @Test
    fun `settings replacement distinguishes missing assistant from null binding`() {
        val removedUnboundId = Uuid.parse("10000000-0000-0000-0000-000000000005")
        val addedUnboundId = Uuid.parse("10000000-0000-0000-0000-000000000006")

        val affected = affectedAssistantIdsForSettingsReplacement(
            current = me.rerere.rikkahub.data.datastore.Settings(
                assistants = listOf(Assistant(id = removedUnboundId, workspaceId = null))
            ),
            replacement = me.rerere.rikkahub.data.datastore.Settings(
                assistants = listOf(Assistant(id = addedUnboundId, workspaceId = null))
            ),
        )

        assertEquals(
            listOf(removedUnboundId.toString(), addedUnboundId.toString()),
            affected,
        )
    }

    @Test
    fun `single distinct valid legacy workspace binds unbound assistant`() {
        val result = decideAssistantWorkspaceReconciliation(
            assistants = listOf(Assistant(id = assistantId)),
            references = listOf(reference("one", workspaceA), reference("two", workspaceA)),
            validWorkspaceIds = setOf(workspaceA.toString()),
        )

        assertEquals(workspaceA, result.assistants.single().workspaceId)
        assertEquals(workspaceA.toString(), result.finalBindings[assistantId.toString()])
    }

    @Test
    fun `multiple valid legacy workspaces remain unbound`() {
        val result = decideAssistantWorkspaceReconciliation(
            assistants = listOf(Assistant(id = assistantId)),
            references = listOf(reference("one", workspaceA), reference("two", workspaceB)),
            validWorkspaceIds = setOf(workspaceA.toString(), workspaceB.toString()),
        )

        assertEquals(null, result.assistants.single().workspaceId)
    }

    @Test
    fun `valid existing binding wins over conflicting legacy rows`() {
        val result = decideAssistantWorkspaceReconciliation(
            assistants = listOf(Assistant(id = assistantId, workspaceId = workspaceB)),
            references = listOf(reference("one", workspaceA)),
            validWorkspaceIds = setOf(workspaceA.toString(), workspaceB.toString()),
        )

        assertEquals(workspaceB, result.assistants.single().workspaceId)
    }

    @Test
    fun `invalid existing binding falls back to one valid legacy workspace`() {
        val result = decideAssistantWorkspaceReconciliation(
            assistants = listOf(Assistant(id = assistantId, workspaceId = workspaceB)),
            references = listOf(reference("one", workspaceA), reference("invalid", workspaceB)),
            validWorkspaceIds = setOf(workspaceA.toString()),
        )

        assertEquals(workspaceA, result.assistants.single().workspaceId)
    }

    @Test
    fun `orphan references never create assistant decisions`() {
        val orphan = Uuid.parse("10000000-0000-0000-0000-000000000099")
        val result = decideAssistantWorkspaceReconciliation(
            assistants = listOf(Assistant(id = assistantId)),
            references = listOf(reference("orphan", workspaceA, orphan)),
            validWorkspaceIds = setOf(workspaceA.toString()),
        )

        assertEquals(null, result.assistants.single().workspaceId)
        assertEquals(setOf(assistantId.toString()), result.finalBindings.keys)
    }

    private fun reference(
        id: String,
        workspaceId: Uuid,
        ownerId: Uuid = assistantId,
    ) = ConversationWorkspaceReference(
        conversationId = id,
        assistantId = ownerId.toString(),
        workspaceId = workspaceId.toString(),
    )
}
