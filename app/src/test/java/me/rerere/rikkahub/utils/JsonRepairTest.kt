package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型把内容里的英文引号原样写进 JSON 参数（漏了反斜杠）时，补转义这一步要能救回来，
 * 而且不能把本来正常的参数改坏。
 */
class JsonRepairTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun contentOf(raw: String): String? = json.parseToJsonElement(repairUnescapedQuotes(raw))
        .jsonObject["content"]
        ?.jsonPrimitive
        ?.contentOrNull

    @Test
    fun `keeps the quotes the model forgot to escape`() {
        val raw = """{"action":"create","content":"基本盘：挂名"高级测试", 实为工艺大牛"}"""

        assertEquals("基本盘：挂名\"高级测试\", 实为工艺大牛", contentOf(raw))
    }

    @Test
    fun `keeps quotes inside a content that also has json escapes`() {
        val raw =
            """{"action":"create","content":"基本盘：\n * **陈铸国(25k)**：挂名"高级测试", 实为ficonTEC工艺大牛"}"""

        assertEquals(
            "基本盘：\n * **陈铸国(25k)**：挂名\"高级测试\", 实为ficonTEC工艺大牛",
            contentOf(raw)
        )
    }

    @Test
    fun `keeps a quote right before the closing brace`() {
        val raw = """{"action":"create","content":"他自称"老王""}"""

        assertEquals("他自称\"老王\"", contentOf(raw))
    }

    @Test
    fun `keeps quotes in nested values`() {
        val raw = """{"action":"edit","id":3,"content":"叫"小黑""}"""

        assertEquals("叫\"小黑\"", contentOf(raw))
    }

    @Test
    fun `keeps quotes when the text after them looks like a key`() {
        val raw = "{\"action\":\"create\",\"content\":\"他管\"红盘\", \"绿盘\"另有说法\"}"

        assertEquals("他管\"红盘\", \"绿盘\"另有说法", contentOf(raw))
    }

    @Test
    fun `leaves arrays of strings untouched`() {
        val raw = """{"action":"create","tags":["a","b"],"content":"x"}"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `leaves valid arguments untouched`() {
        val raw = """{"action":"edit","id":12,"content":"用户喜欢简短回复"}"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `leaves already escaped quotes untouched`() {
        val raw = """{"action":"create","content":"他说\"你好\"了"}"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `leaves arrays untouched`() {
        val raw = """["a","b"]"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `leaves truncated arguments broken instead of guessing`() {
        val raw = """{"action":"create","content":"没写完"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `leaves arguments with trailing junk broken instead of guessing`() {
        val raw = """{"action":"create","content":"甲"} {"x":1}"""

        assertEquals(raw, repairUnescapedQuotes(raw))
    }

    @Test
    fun `content that really is an empty string still parses`() {
        val raw = """{"action":"delete","id":7,"content":""}"""

        assertEquals(raw, repairUnescapedQuotes(raw))
        assertEquals("", contentOf(raw))
    }
}
