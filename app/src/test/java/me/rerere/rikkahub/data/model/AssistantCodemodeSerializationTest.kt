package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCodemodeSerializationTest {
    @Test
    fun `old assistant without codemode setting defaults to enabled`() {
        val assistant = JsonInstant.decodeFromString<Assistant>("""{"name":"legacy","localTools":[]}""")
        assertTrue(assistant.enableWorkspaceCodemode)
        assertEquals("legacy", assistant.name)
        assertTrue(assistant.localTools.isEmpty())
    }

    @Test
    fun `explicit codemode setting survives serialization independently of local tools`() {
        listOf(true, false).forEach { enabled ->
            val assistant = Assistant(name = "configured", enableWorkspaceCodemode = enabled)
            val decoded = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant))
            assertEquals(assistant, decoded)
            assertEquals(enabled, decoded.enableWorkspaceCodemode)
        }
        assertFalse(JsonInstant.decodeFromString<Assistant>(
            """{"enableWorkspaceCodemode":false}"""
        ).enableWorkspaceCodemode)
    }
}
