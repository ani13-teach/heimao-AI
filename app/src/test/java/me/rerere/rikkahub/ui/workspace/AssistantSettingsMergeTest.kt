package me.rerere.rikkahub.ui.workspace

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantSettingsMergeTest {
    private val workspaceA = Uuid.parse("20000000-0000-0000-0000-000000000001")
    private val workspaceB = Uuid.parse("20000000-0000-0000-0000-000000000002")

    @Test
    fun `merge preserves concurrent adds and cannot revive deleted assistants`() {
        val keptId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val deletedId = Uuid.parse("10000000-0000-0000-0000-000000000002")
        val addedId = Uuid.parse("10000000-0000-0000-0000-000000000003")
        val current = listOf(
            Assistant(id = keptId, name = "persisted", workspaceId = workspaceA),
            Assistant(id = addedId, name = "concurrent", workspaceId = workspaceB),
        )
        val staleUi = listOf(
            Assistant(id = keptId, name = "edited", workspaceId = workspaceB),
            Assistant(id = deletedId, name = "deleted", workspaceId = workspaceA),
        )

        val merged = mergeAssistantSnapshot(current, staleUi)

        assertEquals(listOf(keptId, addedId), merged.map { it.id })
        assertEquals("edited", merged.first().name)
        assertEquals(workspaceA, merged.first().workspaceId)
        assertEquals("concurrent", merged.last().name)
    }

    @Test
    fun `merge applies requested order to assistants that still exist`() {
        val firstId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val secondId = Uuid.parse("10000000-0000-0000-0000-000000000002")
        val current = listOf(Assistant(id = firstId), Assistant(id = secondId))

        assertEquals(
            listOf(secondId, firstId),
            mergeAssistantSnapshot(current, current.reversed()).map { it.id },
        )
    }
}
