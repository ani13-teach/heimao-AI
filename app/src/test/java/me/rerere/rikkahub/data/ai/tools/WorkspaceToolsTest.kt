package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceToolsTest {
    @Test
    fun `workspace paths are normalized under workspace root`() {
        assertEquals(
            AiWorkspacePath("/workspace/src/main.kt", "src/main.kt"),
            normalizeAiWorkspacePath("  \\workspace\\src\\.\\main.kt  "),
        )
        assertEquals(AiWorkspacePath("/workspace", ""), normalizeAiWorkspacePath("/workspace/"))
    }

    @Test
    fun `workspace paths outside root are rejected`() {
        listOf(
            "/etc/passwd",
            "/skills/example/SKILL.md",
            "/upload/private.txt",
            "/workspace-other/file.txt",
            "/workspace/../upload/private.txt",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeAiWorkspacePath(path)
            }
        }
    }

    @Test
    fun `shell cwd accepts workspace relative and absolute paths`() {
        assertEquals("src/main", normalizeAiWorkspaceCwd("src/main"))
        assertEquals("src/main", normalizeAiWorkspaceCwd("/workspace/src/main"))
        assertEquals("", normalizeAiWorkspaceCwd("/workspace"))
    }

    @Test
    fun `shell cwd traversal and external absolute paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeAiWorkspaceCwd("../other-workspace")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeAiWorkspaceCwd("/root")
        }
    }
}
