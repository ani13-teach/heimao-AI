package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LayeredMemoryToolsValidationTest {
    @Test
    fun `action is required and must be create or edit`() {
        assertNotNull(validateWriteRequest(null, null, "PARAMS", "t", "c", listOf("✅实测已验证")))
        assertNotNull(validateWriteRequest("delete", null, "PARAMS", "t", "c", listOf("✅实测已验证")))
        assertNull(validateWriteRequest("create", null, "PARAMS", "t", "c", listOf("✅实测已验证")))
        assertNull(validateWriteRequest("edit", 1, null, null, "c", null))
    }

    @Test
    fun `content is required for both actions`() {
        assertNotNull(validateWriteRequest("create", null, "PARAMS", "t", "  ", listOf("✅实测已验证")))
        assertNotNull(validateWriteRequest("edit", 1, null, null, null, null))
    }

    @Test
    fun `create requires category from the allowed enum`() {
        assertNotNull(validateWriteRequest("create", null, null, "t", "c", listOf("✅实测已验证")))
        assertNotNull(validateWriteRequest("create", null, "UNKNOWN", "t", "c", listOf("✅实测已验证")))
        assertNull(validateWriteRequest("create", null, "PARAMS", "t", "c", listOf("✅实测已验证")))
        assertNull(validateWriteRequest("create", null, "ACTIVE", "t", "c", listOf("✅实测已验证")))
    }

    @Test
    fun `create requires title`() {
        assertNotNull(validateWriteRequest("create", null, "PARAMS", "", "c", listOf("✅实测已验证")))
    }

    @Test
    fun `create requires non-empty confidence flags`() {
        assertNotNull(validateWriteRequest("create", null, "PARAMS", "t", "c", null))
        assertNotNull(validateWriteRequest("create", null, "PARAMS", "t", "c", emptyList()))
        assertNotNull(validateWriteRequest("create", null, "PARAMS", "t", "c", listOf("   ")))
        assertNull(validateWriteRequest("create", null, "PARAMS", "t", "c", listOf("✅实测已验证", "⚠️单板待确认")))
    }

    @Test
    fun `edit requires id`() {
        assertNotNull(validateWriteRequest("edit", null, null, null, "c", null))
        assertNull(validateWriteRequest("edit", 42, null, null, "c", null))
    }

    @Test
    fun `edit does not require category title or flags`() {
        assertNull(validateWriteRequest("edit", 7, null, null, "c", null))
    }
}
