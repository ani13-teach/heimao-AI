package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关丢掉 content_block_start 时（例如它把两轮响应拼成一条流），流里会冒出从没开过头的索引，
 * 只剩 delta 和 stop。
 *
 * 以前这里直接抛 `Unknown content block index: N`，整轮回复连文字带工具一起废掉，用户只看到
 * 一条报错。现在这种内容一律丢掉并记一条日志：不能报错，也不能当成正文渲染出来 —— 它的位置
 * 不可信，渲染出来会出现「搜索还没结束就先写了下文」。
 */
class ClaudeMissingBlockStartTest {
    private val decoder = ClaudeStreamDecoder()

    @Test
    fun `text delta without a start is dropped instead of failing the stream`() {
        val chunks = decode(
            messageStart(),
            sse("content_block_start", blockStart(0, "text")),
            sse("content_block_delta", textDelta(0, "前半段")),
            sse("content_block_stop", indexOnly(0)),
            // 6 号块没有 start，直接就是 delta
            sse("content_block_delta", textDelta(6, "下半段")),
            sse("content_block_stop", indexOnly(6)),
            messageDelta("end_turn"),
            messageStop(),
        )

        assertEquals(
            listOf("前半段"),
            chunks.filterIsInstance<StreamChunk.TextDelta>().map { it.text },
        )
        // 没开过头的块不能凭空多出一个文字块，否则界面会先显示上下文还没到的正文
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextStart>().size)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextEnd>().size)
        assertEquals("end_turn", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `thinking delta without a start is dropped`() {
        val chunks = decode(
            messageStart(),
            sse("content_block_delta", reasoningDelta(6, "想了想")),
            sse("content_block_stop", indexOnly(6)),
            messageDelta("end_turn"),
            messageStop(),
        )

        assertTrue(chunks.filterIsInstance<StreamChunk.ReasoningDelta>().isEmpty())
        assertTrue(chunks.filterIsInstance<StreamChunk.ReasoningStart>().isEmpty())
        assertEquals("end_turn", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `stop without a start is ignored instead of failing the stream`() {
        val chunks = decode(
            messageStart(),
            sse("content_block_start", blockStart(0, "text")),
            sse("content_block_delta", textDelta(0, "只有一段")),
            sse("content_block_stop", indexOnly(0)),
            sse("content_block_stop", indexOnly(6)),
            messageDelta("end_turn"),
            messageStop(),
        )

        assertEquals(
            listOf("只有一段"),
            chunks.filterIsInstance<StreamChunk.TextDelta>().map { it.text },
        )
        assertEquals("end_turn", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    /**
     * 工具参数补不出来：identity 和 name 都只在 content_block_start 里。宁可丢掉这个调用，
     * 也不要造一个没有名字的工具调用出来让下层去猜。
     */
    @Test
    fun `tool input for an unknown block is dropped instead of failing the stream`() {
        val chunks = decode(
            messageStart(),
            sse("content_block_delta", buildJsonObject {
                put("index", 6)
                put("delta", buildJsonObject {
                    put("type", "input_json_delta")
                    put("partial_json", """{"query":"tavily"}""")
                })
            }),
            sse("content_block_stop", indexOnly(6)),
            messageDelta("tool_use"),
            messageStop(),
        )

        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallStart>().isEmpty())
        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallDelta>().isEmpty())
        assertEquals("tool_use", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    private fun decode(vararg events: SseEvent): List<StreamChunk> =
        events.flatMap { decoder.accept(it).chunks }

    private fun messageStart(): SseEvent = sse("message_start", buildJsonObject {
        put("message", buildJsonObject {
            put("id", "msg_1")
            put("model", "claude-test")
        })
    })

    private fun messageDelta(reason: String): SseEvent = sse("message_delta", buildJsonObject {
        put("delta", buildJsonObject { put("stop_reason", reason) })
    })

    private fun messageStop(): SseEvent = sse("message_stop", buildJsonObject {})

    private fun blockStart(index: Int, type: String): JsonObject = buildJsonObject {
        put("index", index)
        put("content_block", buildJsonObject { put("type", type) })
    }

    private fun textDelta(index: Int, text: String): JsonObject = buildJsonObject {
        put("index", index)
        put("delta", buildJsonObject {
            put("type", "text_delta")
            put("text", text)
        })
    }

    private fun reasoningDelta(index: Int, thinking: String): JsonObject = buildJsonObject {
        put("index", index)
        put("delta", buildJsonObject {
            put("type", "thinking_delta")
            put("thinking", thinking)
        })
    }

    private fun indexOnly(index: Int): JsonObject = buildJsonObject { put("index", index) }

    private fun sse(event: String, payload: JsonObject): SseEvent =
        SseEvent(event = event, data = json.encodeToString(payload))
}
