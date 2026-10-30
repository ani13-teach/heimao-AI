package me.rerere.ai.provider

import me.rerere.ai.core.Tool
import org.junit.Assert.*
import org.junit.Test

class PromptOrderingTest {
    @Test
    fun `definitions have reproducible case sensitive order without changing objects`() {
        val tools = listOf("z", "a", "A", "工具").map { Tool(it, it, execute = { emptyList() }) }
        val ordered = tools.inPromptOrder()
        assertEquals(listOf("A", "a", "z", "工具"), ordered.map { it.name })
        assertEquals(ordered, tools.reversed().inPromptOrder())
        assertSame(tools[0], ordered[2])
        assertEquals(listOf("z", "a", "A", "工具"), tools.map { it.name })
    }

    @Test
    fun `duplicate names keep original order and built in sets are deterministic`() {
        val first = Tool("a", "first", execute = { emptyList() })
        val second = Tool("a", "second", execute = { emptyList() })
        assertEquals(listOf(first, second), listOf(first, second).inPromptOrder())
        assertEquals(
            linkedSetOf(BuiltInTools.UrlContext, BuiltInTools.Search, BuiltInTools.ImageGeneration).inPromptOrder(),
            linkedSetOf(BuiltInTools.ImageGeneration, BuiltInTools.Search, BuiltInTools.UrlContext).inPromptOrder(),
        )
    }
}
