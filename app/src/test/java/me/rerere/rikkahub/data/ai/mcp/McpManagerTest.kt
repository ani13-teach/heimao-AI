package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class McpManagerTest {
    @Test
    fun `tavily search topic is normalized to general`() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("latest news"))
            put("topic", JsonPrimitive("news"))
        }

        val normalized = normalizeMcpToolArgs("tavily_search", args)

        assertEquals(JsonPrimitive("general"), normalized["topic"])
        assertEquals(JsonPrimitive("news"), args["topic"])
        assertEquals(args["query"], normalized["query"])
    }

    @Test
    fun `tavily search keeps general topic`() {
        val args = buildJsonObject {
            put("topic", JsonPrimitive("general"))
        }

        assertEquals(args, normalizeMcpToolArgs("tavily_search", args))
    }

    @Test
    fun `tavily search without topic keeps arguments unchanged`() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("latest news"))
        }

        assertEquals(args, normalizeMcpToolArgs("tavily_search", args))
    }

    @Test
    fun `other tools keep their topic unchanged`() {
        val args = buildJsonObject {
            put("topic", JsonPrimitive("news"))
        }

        assertEquals(args, normalizeMcpToolArgs("another_tool", args))
    }
}
