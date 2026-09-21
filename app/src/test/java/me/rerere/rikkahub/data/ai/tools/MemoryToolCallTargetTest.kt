package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志分类用的小工具：记忆工具失败时要记下是哪个动作、哪条记录。
 */
class MemoryToolCallTargetTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(input: String) = parseMemoryToolCallTarget(json.parseToJsonElement(input))

    @Test
    fun `local edit reports the action and the record id`() {
        val target = parse("""{"action":"edit","id":12,"edits":[{"old_text":"a","new_text":"b"}]}""")

        assertEquals("edit", target.action)
        assertEquals(12, target.recordId)
    }

    @Test
    fun `create has no record id yet`() {
        val target = parse("""{"action":"create","category":"PARAMS","title":"t","content":"c"}""")

        assertEquals("create", target.action)
        assertNull(target.recordId)
    }

    @Test
    fun `memory_read without arguments yields nulls`() {
        val target = parse("{}")

        assertNull(target.action)
        assertNull(target.recordId)
    }

    @Test
    fun `non integer id is dropped instead of failing the log`() {
        val target = parse("""{"action":"edit","id":"twelve"}""")

        assertEquals("edit", target.action)
        assertNull(target.recordId)
    }

    @Test
    fun `non object input yields nulls`() {
        val target = parseMemoryToolCallTarget(json.parseToJsonElement("[]"))

        assertNull(target.action)
        assertNull(target.recordId)
    }

    @Test
    fun `every built-in memory tool is covered`() {
        assertTrue(
            MEMORY_TOOL_NAMES.containsAll(
                listOf("memory_tool", "memory_read", "memory_write", "memory_consolidate")
            )
        )
    }
}
