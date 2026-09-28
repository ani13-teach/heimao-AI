package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceAccessGate
import me.rerere.rikkahub.data.workspace.WorkspaceAccessRevokedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import me.rerere.rikkahub.data.workspace.MAX_WORKSPACE_SHELL_COMMAND_CHARS
import org.junit.Test

class WorkspaceToolsTest {

    @Test
    fun `workspace gate rejects stale owner and binding`() = runBlocking {
        var owner = "assistant-a"
        var binding: String? = "workspace-a"
        val gate = AssistantWorkspaceAccessGate(
            readConversationOwner = { owner },
            readAssistantBinding = { binding },
        )

        assertEquals(
            "ok",
            gate.withWorkspaceAccess("assistant-a", "conversation-a", "workspace-a") { "ok" },
        )

        binding = "workspace-b"
        assertThrows(WorkspaceAccessRevokedException::class.java) {
            runBlocking {
                gate.withWorkspaceAccess("assistant-a", "conversation-a", "workspace-a") { "unexpected" }
            }
        }

        binding = "workspace-a"
        owner = "assistant-b"
        assertThrows(WorkspaceAccessRevokedException::class.java) {
            runBlocking {
                gate.withWorkspaceAccess("assistant-a", "conversation-a", "workspace-a") { "unexpected" }
            }
        }
        Unit
    }

    @Test
    fun `workspace shell exposes one unified schema without a mode parameter`() {
        val schema = workspaceShellParametersSchema()

        assertEquals(setOf("command", "cwd", "timeout"), schema.keys)
        assertFalse(schema.containsKey("mode"))
        assertTrue(workspaceShellDescription().contains("private Linux environment"))
        assertTrue(workspaceShellDescription().contains("shared Linux environment uses a files-only sandbox"))
        assertTrue(workspaceShellDescription().contains("/workspace/.venv"))
        assertTrue(workspaceShellDescription().contains("public environment maintenance UI"))
        assertFalse(workspaceShellDescription().contains("package mode"))
    }

    @Test
    fun `workspace tools never require per-call approval`() {
        assertFalse(WORKSPACE_TOOL_REQUIRES_APPROVAL)
    }

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
    fun `shell command length is bounded`() {
        assertEquals("echo ok", validateWorkspaceShellCommand("echo ok"))
        assertThrows(IllegalArgumentException::class.java) {
            validateWorkspaceShellCommand("x".repeat(MAX_WORKSPACE_SHELL_COMMAND_CHARS + 1))
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
