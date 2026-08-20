package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredMemoryIndexTest {
    private fun memory(
        id: Int,
        category: String = "GENERAL",
        title: String = "",
        updated: String = "2025-01-01",
        confidenceFlags: String = "[]",
        status: String = "active",
    ) = MemoryEntity(
        id = id,
        assistantId = "assistant-a",
        content = "content of #$id",
        category = category,
        title = title,
        updated = updated,
        confidenceFlags = confidenceFlags,
        status = status,
    )

    @Test
    fun `index contains header line plus one line per entry`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(1), memory(2)),
            maxEntries = 100,
            maxChars = 10_000,
        )
        val lines = index.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("# Memory index"))
    }

    @Test
    fun `index respects maxEntries`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(1), memory(2), memory(3), memory(4), memory(5)),
            maxEntries = 2,
            maxChars = 10_000,
        )
        val lines = index.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("1|"))
        assertTrue(lines[2].startsWith("2|"))
    }

    @Test
    fun `index truncates when maxChars is small`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(1), memory(2), memory(3)),
            maxEntries = 100,
            maxChars = 60,
        )
        val lines = index.split('\n')
        assertTrue("expected truncation, got ${lines.size} lines", lines.size < 4)
        assertTrue(index.length <= 60)
    }

    @Test
    fun `legacy entries without title are rendered as Legacy hash id`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(id = 42, title = "")),
            maxEntries = 100,
            maxChars = 10_000,
        )
        assertTrue(index.contains("|Legacy #42|"))
    }

    @Test
    fun `unknown update date renders as unknown`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(id = 7, updated = "1970-01-01")),
            maxEntries = 100,
            maxChars = 10_000,
        )
        assertTrue(index.contains("|unknown|"))
    }

    @Test
    fun `known update date is preserved`() {
        val index = buildLayeredIndex(
            entries = listOf(memory(id = 7, updated = "2024-06-15")),
            maxEntries = 100,
            maxChars = 10_000,
        )
        assertTrue(index.contains("|2024-06-15|"))
    }

    @Test
    fun `confidence summary joins flags with slash and uses dash when empty`() {
        val withFlags = buildLayeredIndex(
            entries = listOf(
                memory(1, confidenceFlags = """["✅实测已验证","⚠️单板待确认"]""")
            ),
            maxEntries = 100,
            maxChars = 10_000,
        )
        assertTrue(withFlags.contains("|✅实测已验证/⚠️单板待确认|"))

        val withoutFlags = buildLayeredIndex(
            entries = listOf(memory(2, confidenceFlags = "[]")),
            maxEntries = 100,
            maxChars = 10_000,
        )
        assertTrue(withoutFlags.contains("|—|"))
    }

    @Test
    fun `line format is id category title updated confidence status`() {
        val index = buildLayeredIndex(
            entries = listOf(
                memory(
                    id = 9,
                    category = "PARAMS",
                    title = "baud rate",
                    updated = "2025-03-01",
                    confidenceFlags = """["✅实测已验证"]""",
                    status = "active",
                )
            ),
            maxEntries = 100,
            maxChars = 10_000,
        )
        val line = index.split('\n')[1]
        assertEquals("9|PARAMS|baud rate|2025-03-01|✅实测已验证|active", line)
    }

    @Test
    fun `empty entries produce header only`() {
        val index = buildLayeredIndex(emptyList(), maxEntries = 100, maxChars = 10_000)
        assertEquals(1, index.split('\n').size)
        assertFalse(index.contains("Legacy"))
    }

    @Test
    fun `newlines and pipes in fields are sanitized`() {
        val index = buildLayeredIndex(
            entries = listOf(
                memory(id = 1, title = "line1\nline2|with pipe"),
                memory(id = 2, category = "GENERAL", title = "ok"),
            ),
            maxEntries = 100,
            maxChars = 10_000,
        )
        val lines = index.split('\n')
        // 每条记录仍然是一行，未被换行/竖线破坏
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("1|"))
        assertTrue(lines[1].contains("line1 line2/with pipe"))
        assertTrue(lines[2].startsWith("2|"))
    }

    @Test
    fun `over-budget entry is skipped instead of terminating the index`() {
        // #1 即使字段截断（title 限 60 字符）后行仍超过剩余预算 → 跳过；
        // 更短的 #2 仍能进入索引，而不是整个索引终止。
        val headerLen = "# Memory index (id|category|title|updated|confidence|status); use memory_read to load full records".length
        val longTitle = "t".repeat(500)
        val index = buildLayeredIndex(
            entries = listOf(
                memory(id = 1, title = longTitle),
                memory(id = 2, title = "short"),
            ),
            maxEntries = 100,
            // 剩余预算 = maxChars - headerLen - 1 = 85：截断后的 #1 行（90 字符+换行）放不下，#2 可以
            maxChars = headerLen + 86,
        )
        // 按行判断（避免日期 "2025-01-01|" 中的 "1|" 子串误匹配）
        assertFalse(index.lines().any { it.startsWith("1|") })
        assertTrue(index.lines().any { it.startsWith("2|") && it.contains("|short|") })
        assertTrue(index.length <= headerLen + 86)
    }
}
