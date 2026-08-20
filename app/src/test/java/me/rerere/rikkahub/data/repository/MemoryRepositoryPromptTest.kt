package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryPromptTest {
    @Test
    fun `prompt memory budget keeps newest entries and truncates at boundary`() {
        val memories = listOf(
            AssistantMemory(id = 3, content = "newest"),
            AssistantMemory(id = 2, content = "middle"),
            AssistantMemory(id = 1, content = "oldest"),
        )

        val bounded = memories.boundedForPrompt(maxCharacters = 10)

        assertEquals(listOf(3, 2), bounded.map { it.id })
        assertEquals("newest", bounded[0].content)
        assertEquals("midd", bounded[1].content)
        assertEquals(10, bounded.sumOf { it.content.length })
    }

    @Test
    fun `blank memories do not consume prompt entries`() {
        val bounded = listOf(
            AssistantMemory(id = 2, content = "  "),
            AssistantMemory(id = 1, content = "kept"),
        ).boundedForPrompt(maxCharacters = 10)

        assertEquals(listOf(1), bounded.map { it.id })
        assertTrue(bounded.single().content == "kept")
    }
}
