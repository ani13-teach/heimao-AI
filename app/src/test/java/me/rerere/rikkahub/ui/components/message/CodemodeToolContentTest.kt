package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeOperation
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withCodemodeExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodemodeToolContentTest {
    private val tool = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "codemode",
        input = """{"code":"text(42)"}""",
        output = listOf(UIMessagePart.Text("42")),
    )

    @Test
    fun `export uses every actual parent status even with nonempty output`() {
        val labels = mapOf(
            CodemodeStatus.RUNNING to "运行中",
            CodemodeStatus.SUCCEEDED to "成功",
            CodemodeStatus.FAILED to "失败",
            CodemodeStatus.CANCELLED to "已取消",
            CodemodeStatus.INTERRUPTED to "已中断",
        )
        labels.forEach { (status, label) ->
            val traced = tool.withCodemodeExecution(CodemodeExecution(startedAt = 1, status = status))
            val markdown = requireNotNull(formatCodemodeToolMarkdown(traced))
            assertTrue(markdown.startsWith("**Codemode** · $label · 0 项操作"))
            assertTrue(markdown.contains("text(42)"))
            assertTrue(markdown.contains("原始输出:\n```text\n42"))
            assertEquals(listOf(UIMessagePart.Text("42")), traced.output)
        }
    }

    @Test
    fun `export preserves operation order errors results and truncation`() {
        val operations = listOf(
            CodemodeOperation(
                id = "write-1", toolName = "write", startedAt = 2,
                arguments = buildJsonObject { put("path", "/workspace/output.txt") },
                status = CodemodeStatus.SUCCEEDED, result = JsonPrimitive("written"),
            ),
            CodemodeOperation(
                id = "shell-1", toolName = "shell", startedAt = 3,
                arguments = buildJsonObject { put("command", "false") },
                status = CodemodeStatus.FAILED, error = "exit 1", truncated = true,
            ),
        )
        val markdown = requireNotNull(formatCodemodeToolMarkdown(tool.withCodemodeExecution(
            CodemodeExecution(startedAt = 1, status = CodemodeStatus.FAILED,
                operations = operations, error = "script stopped")
        )))
        assertTrue(markdown.contains("失败 · 2 项操作"))
        assertTrue(markdown.indexOf("1. write · 成功") < markdown.indexOf("2. shell · 失败"))
        listOf("/workspace/output.txt", "written", "false", "exit 1", "script stopped", "结果已截断")
            .forEach { assertTrue(markdown.contains(it)) }
    }

    @Test
    fun `all operation statuses are exported in order including interruption and cancellation`() {
        val operations = CodemodeStatus.entries.mapIndexed { index, status ->
            CodemodeOperation(id = "$index", toolName = "read", startedAt = 2,
                arguments = JsonPrimitive("/workspace/long/path.txt"), status = status)
        }
        val markdown = requireNotNull(formatCodemodeToolMarkdown(tool.withCodemodeExecution(
            CodemodeExecution(startedAt = 1, status = CodemodeStatus.INTERRUPTED, operations = operations)
        )))
        operations.forEachIndexed { index, operation ->
            assertTrue(markdown.contains("${index + 1}. read · ${operation.status.displayName()}"))
        }
    }

    @Test
    fun `missing malformed and unsupported traces fall back to original renderer`() {
        assertNull(formatCodemodeToolMarkdown(tool))
        assertNull(formatCodemodeToolMarkdown(tool.copy(metadata = buildJsonObject {
            put("codemodeExecution", "broken")
        })))
        assertNull(formatCodemodeToolMarkdown(tool.withCodemodeExecution(
            CodemodeExecution(version = 2, startedAt = 1)
        )))
        assertNull(formatCodemodeToolMarkdown(tool.copy(toolName = "read").withCodemodeExecution(
            CodemodeExecution(startedAt = 1)
        )))
    }

    @Test
    fun `export fences contain literal code fences without changing original output`() {
        val content = "before\n```\n# literal\nafter"
        val traced = tool.copy(output = listOf(UIMessagePart.Text(content))).withCodemodeExecution(
            CodemodeExecution(startedAt = 1, status = CodemodeStatus.SUCCEEDED)
        )
        val markdown = requireNotNull(formatCodemodeToolMarkdown(traced))
        assertTrue(markdown.contains("````text\n$content\n````"))
        assertEquals(content, (traced.output.single() as UIMessagePart.Text).text)
        assertFalse(markdown.contains("codemodeExecution"))
    }
}
