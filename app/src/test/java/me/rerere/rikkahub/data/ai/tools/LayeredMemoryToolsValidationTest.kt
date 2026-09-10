package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredMemoryToolsValidationTest {

    private fun params(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(build)

    private fun parseError(params: JsonObject): String =
        assertThrows(IllegalArgumentException::class.java) { parseMemoryWriteRequest(params) }
            .message!!

    @Test
    fun `action is required and must be known`() {
        assertTrue(parseError(params { put("content", "c") }).contains("action is required"))
        assertTrue(parseError(params { put("action", "delete") }).contains("unknown action"))
    }

    // ---- create ----

    @Test
    fun `create requires category title content and confidence flags`() {
        assertTrue(
            parseError(params { put("action", "create"); put("title", "t"); put("content", "c") })
                .contains("category is required")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "create"); put("category", "PARAMS"); put("content", "c")
                }
            ).contains("title is required")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "create"); put("category", "PARAMS"); put("title", "t")
                }
            ).contains("content is required")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "create"); put("category", "PARAMS"); put("title", "t"); put("content", "c")
                }
            ).contains("confidence_flags is required")
        )
    }

    @Test
    fun `create rejects unknown category`() {
        assertTrue(
            parseError(
                params {
                    put("action", "create"); put("category", "UNKNOWN"); put("title", "t")
                    put("content", "c"); put("confidence_flags", buildJsonArray { add("✅") })
                }
            ).contains("invalid category")
        )
    }

    @Test
    fun `create parses a valid request`() {
        val command = parseMemoryWriteRequest(
            params {
                put("action", "create")
                put("category", "PARAMS")
                put("title", "转速")
                put("content", "转速: 12000")
                put("confidence_flags", buildJsonArray { add("✅实测已验证") })
                put("tags", buildJsonArray { add("p1") })
                put("product", "P1")
            }
        ) as MemoryWriteCommand.Create

        assertEquals("PARAMS", command.category)
        assertEquals("转速", command.title)
        assertEquals("转速: 12000", command.content)
        assertEquals(listOf("✅实测已验证"), command.confidenceFlags)
        assertEquals(listOf("p1"), command.tags)
        assertEquals("P1", command.product)
    }

    @Test
    fun `create does not accept edits`() {
        assertTrue(
            parseError(
                params {
                    put("action", "create"); put("category", "PARAMS"); put("title", "t")
                    put("content", "c"); put("confidence_flags", buildJsonArray { add("✅") })
                    put("edits", buildJsonArray { add(edit("a", "b")) })
                }
            ).contains("edits are not accepted for action=create")
        )
    }

    // ---- edit（局部修改） ----

    @Test
    fun `edit requires id`() {
        assertTrue(
            parseError(params { put("action", "edit"); put("edits", buildJsonArray { add(edit("a", "b")) }) })
                .contains("id is required for edit")
        )
    }

    @Test
    fun `edit parses a single local change without content`() {
        val command = parseMemoryWriteRequest(
            params {
                put("action", "edit")
                put("id", 7)
                put("edits", buildJsonArray { add(edit("转速: 12000", "转速: 12500")) })
            }
        ) as MemoryWriteCommand.Update

        assertEquals(7, command.id)
        assertEquals(null, command.content)
        assertEquals("edit", command.mode)
        assertEquals(1, command.edits.size)
        assertEquals("转速: 12000", command.edits.first().oldText)
        assertEquals("转速: 12500", command.edits.first().newText)
    }

    @Test
    fun `edit keeps several local changes in order`() {
        val command = parseMemoryWriteRequest(
            params {
                put("action", "edit")
                put("id", 7)
                put(
                    "edits",
                    buildJsonArray {
                        add(edit("a", "1"))
                        add(edit("b", "2"))
                        add(edit("c", "3"))
                    }
                )
            }
        ) as MemoryWriteCommand.Update

        assertEquals(listOf("a", "b", "c"), command.edits.map { it.oldText })
    }

    @Test
    fun `edit rejects content and points to replace`() {
        val error = parseError(
            params {
                put("action", "edit"); put("id", 7); put("content", "整段新内容")
            }
        )
        assertTrue(error.contains("content is not accepted for action=edit"))
        assertTrue(error.contains("replace"))
    }

    @Test
    fun `edit without any change is rejected`() {
        assertTrue(parseError(params { put("action", "edit"); put("id", 7) })
            .contains("at least one change"))
        // 空白 category 不算改动点，否则空写会刷新 updated
        assertTrue(parseError(params { put("action", "edit"); put("id", 7); put("category", "  ") })
            .contains("at least one change"))
        // 空 edits 数组同样不算
        assertTrue(
            parseError(
                params {
                    put("action", "edit"); put("id", 7)
                    put("edits", buildJsonArray { })
                }
            ).contains("at least one change")
        )
    }

    @Test
    fun `edit accepts a field-only change`() {
        val command = parseMemoryWriteRequest(
            params {
                put("action", "edit")
                put("id", 7)
                put("confidence_flags", buildJsonArray { add("✅实测已验证") })
            }
        ) as MemoryWriteCommand.Update

        assertEquals(listOf("✅实测已验证"), command.confidenceFlags)
        assertTrue(command.edits.isEmpty())
        assertEquals(null, command.content)
    }

    @Test
    fun `edit rejects empty confidence flag entries`() {
        assertTrue(
            parseError(
                params {
                    put("action", "edit"); put("id", 7)
                    put("confidence_flags", buildJsonArray { add("  ") })
                }
            ).contains("confidence_flags must not contain empty strings")
        )
    }

    @Test
    fun `edit rejects unknown category`() {
        assertTrue(
            parseError(
                params { put("action", "edit"); put("id", 7); put("category", "NOPE") }
            ).contains("invalid category")
        )
    }

    // ---- replace（整段重写） ----

    @Test
    fun `replace requires id and content and supports field updates`() {
        assertTrue(parseError(params { put("action", "replace"); put("content", "c") })
            .contains("id is required for replace"))
        assertTrue(parseError(params { put("action", "replace"); put("id", 7) })
            .contains("content is required for replace"))

        val command = parseMemoryWriteRequest(
            params {
                put("action", "replace")
                put("id", 7)
                put("content", "整段新内容")
                put("title", "新标题")
            }
        ) as MemoryWriteCommand.Update

        assertEquals("replace", command.mode)
        assertEquals("整段新内容", command.content)
        assertEquals("新标题", command.title)
        assertTrue(command.edits.isEmpty())
    }

    @Test
    fun `replace rejects edits`() {
        assertTrue(
            parseError(
                params {
                    put("action", "replace"); put("id", 7); put("content", "c")
                    put("edits", buildJsonArray { add(edit("a", "b")) })
                }
            ).contains("edits are not accepted for action=replace")
        )
    }

    // ---- edits 结构校验 ----

    @Test
    fun `edits entries require old_text and new_text`() {
        assertTrue(
            parseError(
                params { put("action", "edit"); put("id", 7); put("edits", buildJsonArray { add(edit("", "b")) }) }
            ).contains("old_text must not be empty")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "edit"); put("id", 7)
                    put("edits", buildJsonArray { add(buildJsonObject { put("new_text", "b") }) })
                }
            ).contains("old_text is required")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "edit"); put("id", 7)
                    put("edits", buildJsonArray { add(buildJsonObject { put("old_text", "a") }) })
                }
            ).contains("new_text is required")
        )
    }

    @Test
    fun `edits entry must be an object and edits must be an array`() {
        assertTrue(
            parseError(params { put("action", "edit"); put("id", 7); put("edits", "not-an-array") })
                .contains("edits must be an array")
        )
        assertTrue(
            parseError(
                params {
                    put("action", "edit"); put("id", 7)
                    put("edits", buildJsonArray { add("plain-string") })
                }
            ).contains("edits[0] must be an object")
        )
    }

    @Test
    fun `edits accept an empty new_text to delete text`() {
        val command = parseMemoryWriteRequest(
            params {
                put("action", "edit")
                put("id", 7)
                put("edits", buildJsonArray { add(edit("旧行\n", "")) })
            }
        ) as MemoryWriteCommand.Update

        assertEquals("", command.edits.single().newText)
    }

    @Test
    fun `too many edits are rejected`() {
        val error = parseError(
            params {
                put("action", "edit")
                put("id", 7)
                put(
                    "edits",
                    buildJsonArray { repeat(21) { add(edit("a$it", "b$it")) } }
                )
            }
        )
        assertTrue(error.contains("too many edits"))
    }

    private fun edit(oldText: String, newText: String): JsonObject = buildJsonObject {
        put("old_text", oldText)
        put("new_text", newText)
    }
}
