package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.LayeredMemoryContext
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredMemoryPromptTest {

    private fun prompt(): String = buildLayeredMemoryPrompt(
        LayeredMemoryContext(
            indexText = "1|PARAMS|主轴参数|2026-09-01|✅实测已验证|active",
            activeMemories = listOf(
                AssistantMemory(id = 1, content = "转速: 12000", title = "主轴参数", category = "PARAMS")
            ),
            indexCount = 1,
            activeCount = 1,
        )
    )

    @Test
    fun `prompt tells the model to update memories locally`() {
        val text = prompt()

        assertTrue(text.contains("local by default"))
        assertTrue(text.contains("edits"))
        // 同一记录多点与并行/分轮的边界必须写明
        assertTrue(text.contains("all-or-nothing"))
        assertTrue(text.contains("parallel calls"))
        assertTrue(text.contains("need a later turn"))
        assertTrue(text.contains("局部修改"))
    }

    @Test
    fun `prompt keeps read-first and staleness rules`() {
        val text = prompt()

        assertTrue(text.contains("memory_read"))
        assertTrue(text.contains("older than 7 days"))
        assertTrue(text.contains("never guess parameter details from memory"))
        assertTrue(text.contains("[Index]"))
        assertTrue(text.contains("[ACTIVE]"))
    }
}
